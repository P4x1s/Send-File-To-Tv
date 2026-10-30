"""局域网自动发现（UDP 广播/信标）。

手机端（扫描方）：
  * 周期性向广播地址发送 ``DISCOVER``。
  * 同时监听 ``ANNOUNCE``（既是对 DISCOVER 的应答，也是电视端周期信标）。
  * 维护一个带 TTL 的设备表，超时的设备自动移除。

电视端（被发现的设备）：
  * 监听 UDP 发现端口，收到 ``DISCOVER`` 后单播回 ``ANNOUNCE``。
  * 周期性广播 ``ANNOUNCE`` 信标，使新加入的手机无需主动扫描也能发现。
"""

from __future__ import annotations

import json
import socket
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Callable, Dict, Optional

from .protocol import (
    BEACON_INTERVAL,
    DISCOVER_INTERVAL,
    DISCOVER_TIMEOUT,
    MAGIC,
    get_local_ip,
    list_broadcast_targets,
)

DISCOVER = "discover"
ANNOUNCE = "announce"


@dataclass
class TvDevice:
    ip: str
    name: str
    device_id: str
    tcp_port: int
    last_seen: float = 0.0

    @property
    def age(self) -> float:
        return time.time() - self.last_seen

    def is_alive(self, now: Optional[float] = None) -> bool:
        now = now if now is not None else time.time()
        return (now - self.last_seen) <= DISCOVER_TIMEOUT


# ---------------------------------------------------------------------------
# 手机端扫描器
# ---------------------------------------------------------------------------
class PhoneScanner:
    """后台线程：发送 DISCOVER 并收集 ANNOUNCE，维护在线电视设备表。"""

    def __init__(self, listen_udp_port: int, control_port: int,
                 include_loopback: bool = True,
                 on_discover: Optional[Callable[[TvDevice], None]] = None):
        self._port = listen_udp_port
        self._control_port = control_port
        self._targets = list_broadcast_targets(include_loopback)
        self._on_discover = on_discover
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("", listen_udp_port))
        self._sock.settimeout(0.5)
        self._devices: Dict[str, TvDevice] = {}
        self._lock = threading.Lock()
        self._token = uuid.uuid4().hex[:16]
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> "PhoneScanner":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        try:
            self._sock.close()
        except OSError:
            pass

    def list_devices(self) -> list[TvDevice]:
        with self._lock:
            return [d for d in self._devices.values() if d.is_alive()]

    def device_by_id(self, device_id: str) -> Optional[TvDevice]:
        with self._lock:
            return self._devices.get(device_id)

    def _run(self) -> None:
        last_discover = 0.0
        while not self._stop.is_set():
            now = time.time()
            if now - last_discover >= DISCOVER_INTERVAL:
                self._send_discover()
                last_discover = now
            # 清理过期设备
            with self._lock:
                expired = [k for k, d in self._devices.items() if not d.is_alive(now)]
                for k in expired:
                    del self._devices[k]
            self._recv_loop()

    def _send_discover(self) -> None:
        msg = json.dumps({
            "magic": MAGIC.decode(),
            "t": DISCOVER,
            "v": 1,
            "token": self._token,
            "port": self._control_port,
        }).encode("utf-8")
        for target in self._targets:
            try:
                self._sock.sendto(msg, (target, self._port))
            except OSError:
                pass

    def _recv_loop(self) -> None:
        try:
            data, addr = self._sock.recvfrom(65535)
        except socket.timeout:
            return
        except OSError:
            return
        self._handle(data, addr[0])

    def _handle(self, data: bytes, src_ip: str) -> None:
        # 兼容两种载荷格式：
        #   1) b"LFT1" + JSON          —— Python 电视端与 Android 电视端使用的前缀形式
        #   2) 纯 JSON（含 "magic" 字段）—— 其他实现的简化形式
        if data.startswith(MAGIC):
            payload = data[len(MAGIC):]
        else:
            payload = data
        try:
            obj = json.loads(payload.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return
        if not isinstance(obj, dict):
            return
        if obj.get("t") != ANNOUNCE:
            return
        device_id = obj.get("device_id")
        if not device_id:
            return
        dev = TvDevice(
            ip=obj.get("ip", src_ip),
            name=obj.get("name", "未知电视"),
            device_id=device_id,
            tcp_port=int(obj.get("tcp_port", self._control_port)),
            last_seen=time.time(),
        )
        is_new = False
        with self._lock:
            if dev.device_id not in self._devices:
                is_new = True
            self._devices[dev.device_id] = dev
        if is_new and self._on_discover:
            try:
                self._on_discover(dev)
            except Exception:
                pass


# ---------------------------------------------------------------------------
# 电视端宣告器
# ---------------------------------------------------------------------------
class TvAnnouncer:
    """后台线程：应答 DISCOVER 并周期广播 ANNOUNCE 信标。"""

    def __init__(self, name: str, device_id: str, control_port: int,
                 discover_port: int = 41234, beacon_interval: float = BEACON_INTERVAL):
        self._name = name
        self._device_id = device_id
        self._control_port = control_port
        self._discover_port = discover_port
        self._beacon_interval = beacon_interval
        self._targets = list_broadcast_targets(True)
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("", discover_port))
        self._sock.settimeout(0.5)
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> "TvAnnouncer":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        try:
            self._sock.close()
        except OSError:
            pass

    def _make_announce(self, dst_ip: str) -> bytes:
        payload = json.dumps({
            "magic": MAGIC.decode(),
            "t": ANNOUNCE,
            "v": 1,
            "token": "",
            "name": self._name,
            "device_id": self._device_id,
            "ip": get_local_ip(),
            "tcp_port": self._control_port,
        }).encode("utf-8")
        return MAGIC + payload

    def _run(self) -> None:
        last_beacon = 0.0
        while not self._stop.is_set():
            now = time.time()
            if now - last_beacon >= self._beacon_interval:
                self._beacon()
                last_beacon = now
            self._recv_loop()

    def _beacon(self) -> None:
        msg = self._make_announce("255.255.255.255")
        for target in self._targets:
            try:
                self._sock.sendto(msg, (target, self._discover_port))
            except OSError:
                pass

    def _recv_loop(self) -> None:
        try:
            data, addr = self._sock.recvfrom(65535)
        except socket.timeout:
            return
        except OSError:
            return
        # 兼容 "LFT1"+JSON 与 纯 JSON 两种载荷格式
        if data.startswith(MAGIC):
            payload = data[len(MAGIC):]
        else:
            payload = data
        try:
            obj = json.loads(payload.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return
        if not isinstance(obj, dict):
            return
        if obj.get("t") != DISCOVER:
            return
        # 单播应答
        try:
            self._sock.sendto(self._make_announce(addr[0]), (addr[0], self._discover_port))
        except OSError:
            pass
