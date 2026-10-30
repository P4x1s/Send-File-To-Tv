"""协议常量、网络辅助函数、哈希与分块计算、行帧编解码。

设计要点
--------
* 发现层（UDP）：手机端广播 ``DISCOVER``，电视端单播回 ``ANNOUNCE``；
  电视端还会周期性 ``ANNOUNCE`` 信标，使新加入的手机无需主动扫描即可发现。
* 传输层（TCP）：控制端口 + 数据端口分离。
  - 控制连接：HELLO -> READY -> MANIFEST -> MANIFEST_ACK，之后电视端
    通过同一连接推送 PROGRESS / FILE_DONE / SESSION_DONE。
  - 数据连接：每个分块独立建连，首行 ``CHUNK <json>`` 后紧跟原始字节，
    服务端校验分块 SHA256 并回 ``CHUNK_OK/FILE`` 后关闭。
* 完整性：整文件 SHA256 在发送端预计算随清单下发；每个分块另带独立 SHA256
  用于传输期早期检错与重传。
"""

from __future__ import annotations

import hashlib
import json
import os
import socket
import struct
from dataclasses import dataclass, field
from typing import Dict, List, Tuple

# ---------------------------------------------------------------------------
# 端口与协议常量
# ---------------------------------------------------------------------------
PROTO_VERSION = 1
DISCOVERY_PORT = 41234          # UDP 自动发现
CONTROL_PORT = 41235           # TCP 控制连接
DATA_PORT = 41236              # TCP 数据分块连接

MAGIC = b"LFT1"                 # UDP 报文前缀，用于过滤杂包

CHUNK_SIZE = 4 * 1024 * 1024    # 单分块默认 4 MiB
DEFAULT_THREADS = 8             # 默认并发线程数

# 心跳/信标间隔（秒）
BEACON_INTERVAL = 3.0
DISCOVER_INTERVAL = 2.0
DISCOVER_TIMEOUT = 6.0          # 设备无信标超过该时长则视为离线


# ---------------------------------------------------------------------------
# 网络辅助
# ---------------------------------------------------------------------------
def get_local_ip() -> str:
    """返回本机在局域网中的首选 IPv4 地址（非 127.0.0.1）。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # 不需要真的发包，仅借由路由表解析出出接口地址
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def list_broadcast_targets(include_loopback: bool = True) -> List[str]:
    """返回发现广播目标地址列表。

    始终包含受限广播 255.255.255.255；如需本地端到端测试，再追加 127.0.0.1。
    """
    targets = ["255.255.255.255"]
    if include_loopback:
        targets.append("127.0.0.1")
    return targets


# ---------------------------------------------------------------------------
# 哈希与分块
# ---------------------------------------------------------------------------
def sha256_of_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_of_file(path: str, chunk_size: int = 1024 * 1024) -> str:
    """以流式方式计算文件 SHA256，避免把大文件整体载入内存。"""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(chunk_size), b""):
            h.update(block)
    return h.hexdigest()


@dataclass
class ChunkSpec:
    index: int
    offset: int
    size: int


def plan_chunks(file_size: int, chunk_size: int = CHUNK_SIZE) -> List[ChunkSpec]:
    """将文件切分为 (index, offset, size) 分块列表。

    空文件返回单个长度为 0 的分块，便于统一处理。
    """
    if file_size <= 0:
        return [ChunkSpec(0, 0, 0)]
    specs: List[ChunkSpec] = []
    off = 0
    idx = 0
    while off < file_size:
        size = min(chunk_size, file_size - off)
        specs.append(ChunkSpec(idx, off, size))
        off += size
        idx += 1
    return specs


def effective_threads(chunk_count: int, requested: int) -> int:
    """实际并发数 = min(分块数, 请求线程数)，避免小文件空转线程。"""
    return max(1, min(chunk_count, max(1, requested)))


# ---------------------------------------------------------------------------
# 行帧（newline-delimited JSON）编解码，用于 TCP 控制消息
# ---------------------------------------------------------------------------
def send_line(sock: socket.socket, obj) -> None:
    """发送一行 JSON 文本，自动追加换行符。"""
    payload = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    sock.sendall(payload + b"\n")


def recv_line(sock: socket.socket, max_bytes: int = 1 << 20) -> dict:
    """从 socket 读取一行（遇 \\n 结束）并解析为 dict。"""
    buf = b""
    while b"\n" not in buf:
        chunk = sock.recv(min(4096, max_bytes - len(buf)))
        if not chunk:
            if not buf:
                raise ConnectionError("对端连接已关闭")
            break
        buf += chunk
        if len(buf) > max_bytes:
            raise ValueError("控制消息超长")
    line, _, _ = buf.partition(b"\n")
    return json.loads(line.decode("utf-8"))


def recv_exact(sock: socket.socket, n: int) -> bytes:
    """从 socket 精确读取 n 字节，不足则持续读取。"""
    if n == 0:
        return b""
    buf = bytearray(n)
    view = memoryview(buf)
    got = 0
    while got < n:
        got += sock.recv_into(view[got:])
        if got == 0:
            raise ConnectionError("对端在传输分块中途断开")
    return bytes(buf)


# ---------------------------------------------------------------------------
# 文件清单描述
# ---------------------------------------------------------------------------
@dataclass
class FileItem:
    file_id: str
    name: str          # 仅文件名（不含路径）
    size: int
    sha256: str
    path: str = ""     # 发送端本地路径；不进入清单，不会传给对端
    chunks: List[ChunkSpec] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "file_id": self.file_id,
            "name": self.name,
            "size": self.size,
            "sha256": self.sha256,
            "chunk_count": len(self.chunks),
        }

    @staticmethod
    def from_path(path: str, file_id: str, chunk_size: int = CHUNK_SIZE) -> "FileItem":
        name = os.path.basename(path)
        size = os.path.getsize(path)
        digest = sha256_of_file(path)
        return FileItem(
            file_id=file_id,
            name=name,
            size=size,
            sha256=digest,
            path=path,
            chunks=plan_chunks(size, chunk_size),
        )
