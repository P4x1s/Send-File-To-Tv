#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
HTTP 通道协议一致性验证脚本
================================

TV 端的内置 HTTP 上传服务器（android/tv/.../http/HttpUploadServer.kt）与手机端
HTTP 客户端（android/phone/.../HttpTransferClient.kt）之间使用一套自定的 HTTP 协议。

本脚本用 Python 复刻**客户端**一侧的协议约定，然后对**真实运行中的 TV 端
HTTP 服务器**发起并发分块上传，验证：

  1. POST /api/upload/init      -> 返回 sessionId / chunk_size / 已收位图
  2. POST /api/upload/chunk     -> 并发上传 N 个分块（逐片 SHA-256 校验）
  3. POST /api/upload/commit    -> 服务端校验完整性并落盘
  4. GET  /api/files            -> 文件清单中出现该文件（按时间倒序）
  5. GET  /api/device           -> 设备信息
  6. DELETE /api/file/delete    -> 删除文件

同时覆盖异常与边界路径：
  7. 故意提交错误的整文件哈希 -> 服务端必须拒绝并返回 400（校验真的生效）
  8. 故意提交错误的分片哈希   -> 服务端必须拒绝该分片
  9. 断点续传 -> 中断后重新 init，必须拿回已收位图并只补传缺失分片
 10. 大分块协商（4 MiB）      -> 服务端必须按客户端声明的分块大小计算偏移
 11. 文件清单排序             -> 最新上传的文件必须排在最前面

由于 TV 端是 Android APK，本沙箱无真机，无法直接启动该服务器。因此本脚本有两种用法：

    A. 协议自检模式（默认，无需设备）：
        python http_selftest.py --self
        在本地用 Python 起一个「协议参考实现」的服务器（严格照抄 Kotlin 侧的
        路由/头部/校验规则），再用同一套客户端代码去测它。
        -> 证明「客户端代码 + 协议约定」自洽，可用于交叉验证 Kotlin 侧实现。

    B. 真机联调模式：
        python http_selftest.py --host 192.168.1.23 --port 9527
        直接对电视盒子上的 App 发请求，验证真实实现。

用法示例
--------
    python http_selftest.py --self
    python http_selftest.py --host 192.168.1.23
"""

import argparse
import hashlib
import json
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

# =============================================================================
# 协议常量（必须与 Kotlin 侧 HttpProto.kt 保持一致）
# =============================================================================

HTTP_PORT = 9527
HTTP_CHUNK_SIZE = 4 * 1024 * 1024        # 大文件默认分块 4 MiB
LEGACY_HTTP_CHUNK_SIZE = 1024 * 1024     # 客户端未声明分块大小时的回退值
DEFAULT_HTTP_THREADS = 8

PATH_INDEX = "/"
PATH_UPLOAD_INIT = "/api/upload/init"
PATH_UPLOAD_CHUNK = "/api/upload/chunk"
PATH_UPLOAD_COMMIT = "/api/upload/commit"
PATH_UPLOAD_STATUS = "/api/upload/status"
PATH_FILES = "/api/files"
PATH_FILE_DELETE = "/api/file/delete"
PATH_DEVICE_INFO = "/api/device"

HDR_SESSION = "X-Lan-Session"
HDR_INDEX = "X-Lan-Index"
HDR_SIZE = "X-Lan-Size"
HDR_OFFSET = "X-Lan-Offset"
HDR_CHUNK_SHA = "X-Lan-Chunk-Sha256"
HDR_NAME = "X-Lan-Name"
HDR_TOTAL_SIZE = "X-Lan-Total-Size"
HDR_FILE_SHA = "X-Lan-File-Sha256"
HDR_CHUNK_COUNT = "X-Lan-Chunk-Count"
HDR_CHUNK_SIZE = "X-Lan-Chunk-Size"
HDR_FILE_ID = "X-Lan-File-Id"
HDR_COMMIT_SESSION = "X-Lan-Commit-Session"
HDR_RESP_FILE_SHA = "X-Lan-Resp-Sha256"

PART_SUFFIX = ".lanpart"
PART_DIR = ".lanpart"


# =============================================================================
# 通用工具
# =============================================================================

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def plan_chunks(size: int, chunk_size: int = LEGACY_HTTP_CHUNK_SIZE):
    """与 Kotlin 侧 ChunkPlanner.planChunks 对齐。"""
    specs = []
    if size <= 0:
        return specs
    offset = 0
    index = 0
    while offset < size:
        length = min(chunk_size, size - offset)
        specs.append((index, offset, length))
        offset += length
        index += 1
    return specs


def encode_bitmap(indices) -> str:
    """把分块索引集合编码成 Java BitSet 的 toByteArray 字节序 + Base64。"""
    if not indices:
        return ""
    top = max(indices)
    data = bytearray((top // 8) + 1)
    for i in indices:
        data[i // 8] |= (1 << (i % 8))
    import base64
    return base64.b64encode(bytes(data)).decode("ascii")


def decode_bitmap(b64: str, chunk_count: int):
    """解码服务端回传的已收分块位图，返回已收索引集合。"""
    if not b64:
        return set()
    import base64
    try:
        raw = base64.b64decode(b64)
    except Exception:
        return set()
    out = set()
    for i in range(chunk_count):
        byte = i // 8
        if byte >= len(raw):
            break
        if raw[byte] & (1 << (i % 8)):
            out.add(i)
    return out


def stable_file_id(name: str, size: int, mtime: int = 0) -> str:
    """稳定文件 ID（与手机端一致的语义：同名同大小同修改时间 -> 同一个逻辑文件）。"""
    raw = f"{name}|{size}|{mtime}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]


class Result:
    """测试结果收集器。"""

    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.messages = []
        self._lock = threading.Lock()

    def check(self, ok: bool, name: str, detail: str = ""):
        with self._lock:
            if ok:
                self.passed += 1
                self.messages.append(f"  [PASS] {name}" + (f" -- {detail}" if detail else ""))
            else:
                self.failed += 1
                self.messages.append(f"  [FAIL] {name}" + (f" -- {detail}" if detail else ""))
        return ok

    def info(self, text: str):
        with self._lock:
            self.messages.append(f"  [INFO] {text}")

    def summary(self) -> str:
        total = self.passed + self.failed
        status = "ALL GREEN" if self.failed == 0 else "HAS FAILURES"
        lines = [f"\n{'=' * 62}", f"结果: {status}  ({self.passed}/{total} 通过)", "=" * 62]
        lines.extend(self.messages)
        return "\n".join(lines)


# =============================================================================
# HTTP 客户端（复刻 Kotlin 侧 HttpTransferClient 的请求约定）
# =============================================================================

class HttpClient:
    def __init__(self, host: str, port: int = HTTP_PORT, timeout: int = 120):
        self.base = f"http://{host}:{port}"
        self.timeout = timeout

    def _request(self, method: str, path: str, headers=None, body: bytes = None):
        url = self.base + path
        req = urllib.request.Request(url, data=body, method=method)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.status, resp.read(), dict(resp.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), dict(e.headers)

    def init_session(self, name: str, size: int, chunk_count: int,
                     chunk_size: int = 0, file_id: str = "", file_sha: str = ""):
        headers = {
            HDR_NAME: urllib.parse.quote(name, safe=""),
            HDR_TOTAL_SIZE: str(size),
            HDR_CHUNK_COUNT: str(chunk_count),
            "Content-Length": "0",
        }
        if chunk_size:
            headers[HDR_CHUNK_SIZE] = str(chunk_size)
        if file_id:
            headers[HDR_FILE_ID] = file_id
        if file_sha:
            headers[HDR_FILE_SHA] = file_sha
        code, data, _ = self._request("POST", PATH_UPLOAD_INIT, headers, b"")
        if code != 200:
            raise RuntimeError(f"init failed: HTTP {code} {data[:200]!r}")
        return json.loads(data.decode("utf-8"))

    def upload_chunk(self, session: str, index: int, payload: bytes,
                     chunk_sha: str, offset: int = -1):
        headers = {
            HDR_SESSION: session,
            HDR_INDEX: str(index),
            HDR_SIZE: str(len(payload)),
            HDR_CHUNK_SHA: chunk_sha,
            "Content-Length": str(len(payload)),
        }
        if offset >= 0:
            headers[HDR_OFFSET] = str(offset)
        code, data, _ = self._request("POST", PATH_UPLOAD_CHUNK, headers, payload)
        if code != 200:
            raise RuntimeError(f"chunk {index} failed: HTTP {code} {data[:200]!r}")
        return json.loads(data.decode("utf-8"))

    def commit(self, session: str, file_sha: str = ""):
        headers = {HDR_COMMIT_SESSION: session, "Content-Length": "0"}
        if file_sha:
            headers[HDR_FILE_SHA] = file_sha
        code, data, hdrs = self._request("POST", PATH_UPLOAD_COMMIT, headers, b"")
        if code != 200:
            raise RuntimeError(f"commit failed: HTTP {code} {data[:200]!r}")
        return json.loads(data.decode("utf-8")), hdrs.get(HDR_RESP_FILE_SHA, "")

    def get_files(self):
        code, data, _ = self._request("GET", PATH_FILES)
        if code != 200:
            raise RuntimeError(f"files failed: HTTP {code}")
        return json.loads(data.decode("utf-8"))

    def get_device(self):
        code, data, _ = self._request("GET", PATH_DEVICE_INFO)
        if code != 200:
            raise RuntimeError(f"device failed: HTTP {code}")
        return json.loads(data.decode("utf-8"))

    def get_index(self):
        code, data, _ = self._request("GET", PATH_INDEX)
        return code, data

    def delete_file(self, name: str):
        path = PATH_FILE_DELETE + "?" + urllib.parse.urlencode({"name": name})
        code, data, _ = self._request("DELETE", path)
        return code, data


def upload_file_concurrent(client: HttpClient, path: str, threads: int,
                           chunk_size: int = LEGACY_HTTP_CHUNK_SIZE,
                           file_id: str = "",
                           corrupt_chunk: int = -1, corrupt_whole: bool = False,
                           stop_after: int = -1, skip_indices=None):
    """
    并发上传一个文件。

    corrupt_chunk >= 0 : 该分片的声明哈希故意写错（测试服务端逐片校验）
    corrupt_whole=True  : 整文件声明哈希故意写错（测试服务端整文件校验）
    stop_after >= 0     : 只上传前 N 个分块就停（模拟断网，用于测试续传）
    skip_indices        : 需要跳过的分块（断点续传时只补传缺失分片）
    返回 (init_json, server_sha, error)
    """
    name = os.path.basename(path)
    size = os.path.getsize(path)
    file_sha = sha256_file(path)
    declared_sha = ("0" * 64) if corrupt_whole else file_sha

    chunks = plan_chunks(size, chunk_size)
    init = client.init_session(name, size, len(chunks), chunk_size, file_id, declared_sha)
    session = init["session"]
    real_chunk_size = init.get("chunk_size", chunk_size)
    if real_chunk_size != chunk_size:
        # 服务端按自己的分块大小响应，偏移需按它重算
        chunks = plan_chunks(size, real_chunk_size)
        init["chunk_count"] = len(chunks)

    skip = set(skip_indices or ())
    sem = threading.Semaphore(threads)
    errors = []
    uploaded = []

    def worker(index, offset, length):
        if index in skip:
            return
        with sem:
            try:
                with open(path, "rb") as f:
                    f.seek(offset)
                    payload = f.read(length)
                sha = sha256_bytes(payload)
                if index == corrupt_chunk:
                    sha = "f" * 64  # 故意错误
                client.upload_chunk(session, index, payload, sha, offset)
                uploaded.append(index)
            except Exception as e:  # noqa: BLE001
                errors.append(f"chunk {index}: {e}")

    threads_list = []
    for index, offset, length in chunks:
        if stop_after >= 0 and len(uploaded) >= stop_after:
            break
        t = threading.Thread(target=worker, args=(index, offset, length))
        t.start()
        threads_list.append(t)
    for t in threads_list:
        t.join()

    if errors:
        return init, "", errors[0]

    if stop_after >= 0:
        # 模拟中断：不 commit
        return init, "", "interrupted"

    resp, server_sha = client.commit(session, declared_sha)
    return init, server_sha, ""


# =============================================================================
# A. 协议参考实现（照抄 Kotlin 侧规则，用于 --self 自检）
# =============================================================================

class ReferenceServer(threading.Thread):
    """
    严格按照 Kotlin 侧 HttpUploadServer 的规则实现的最小参考服务器。
    目的：在没有真机的情况下，验证「客户端协议代码」与「服务端协议约定」自洽。
    """

    def __init__(self, upload_dir: str, port: int = 0):
        super().__init__(daemon=True)
        self.upload_dir = os.path.abspath(upload_dir)
        os.makedirs(self.upload_dir, exist_ok=True)
        self.part_dir = os.path.join(self.upload_dir, PART_DIR)
        os.makedirs(self.part_dir, exist_ok=True)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", port))
        self.sock.listen(64)
        self.port = self.sock.getsockname()[1]
        self.sessions = {}
        self.seq = 0
        self._lock = threading.Lock()
        self.running = True

    def run(self):
        while self.running:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                break
            threading.Thread(target=self._serve_conn, args=(conn,), daemon=True).start()

    def stop(self):
        self.running = False
        try:
            self.sock.close()
        except OSError:
            pass

    # --- HTTP 解析 ---
    # 注意：方法名不可用 _handle —— Python 3.13 的 Thread 基类有真实属性
    # self._handle（_ThreadHandle 实例），会覆盖同名方法导致 TypeError。
    def _serve_conn(self, conn):
        with conn:
            conn.settimeout(120)
            f = conn.makefile("rb")
            try:
                while True:
                    line = f.readline()
                    if not line:
                        return
                    req_line = line.decode("latin-1").strip()
                    if not req_line:
                        continue
                    parts = req_line.split()
                    if len(parts) < 2:
                        return
                    method, target = parts[0].upper(), parts[1]
                    path = target.split("?")[0]
                    query = dict(urllib.parse.parse_qsl(target.split("?")[1] if "?" in target else ""))

                    headers = {}
                    while True:
                        hl = f.readline()
                        if not hl or hl in (b"\r\n", b"\n"):
                            break
                        k, _, v = hl.decode("latin-1").partition(":")
                        headers[k.strip().lower()] = v.strip()

                    length = int(headers.get("content-length", "0") or 0)
                    body = f.read(length) if length else b""

                    keep = self._dispatch(method, path, query, headers, body, conn)
                    if not keep:
                        return
            except (OSError, ValueError):
                return

    def _dispatch(self, method, path, query, headers, body, conn):
        if method == "GET" and path in (PATH_INDEX, "/index.html"):
            self._respond(conn, 200, "text/html; charset=utf-8", b"<html><body>upload</body></html>")
        elif method == "GET" and path == PATH_DEVICE_INFO:
            self._json(conn, 200, {"app": "lanfiletransfer", "version": "2.0",
                                  "http_port": self.port, "files_dir": self.upload_dir})
        elif method == "GET" and path == PATH_FILES:
            items = []
            for name in sorted(os.listdir(self.upload_dir)):
                if name.endswith(PART_SUFFIX):
                    continue
                fp = os.path.join(self.upload_dir, name)
                if os.path.isfile(fp):
                    items.append({"name": name, "size": os.path.getsize(fp),
                                  "time": int(os.path.getmtime(fp) * 1000)})
            # 与 Kotlin 侧一致：按时间倒序（最新在前）
            items.sort(key=lambda x: x["time"], reverse=True)
            self._json(conn, 200, items)
        elif method == "POST" and path == PATH_UPLOAD_INIT:
            self._init(conn, headers)
        elif method == "POST" and path == PATH_UPLOAD_CHUNK:
            self._chunk(conn, headers, body)
        elif method == "POST" and path == PATH_UPLOAD_COMMIT:
            self._commit(conn, headers)
        elif method == "DELETE" and path == PATH_FILE_DELETE:
            self._delete(conn, query)
        else:
            self._respond(conn, 404, "text/plain; charset=utf-8", b"Not Found")
        return headers.get("connection", "").lower() != "close"

    # --- 业务 ---
    def _init(self, conn, headers):
        raw_name = headers.get(HDR_NAME.lower(), "")
        name = urllib.parse.unquote(raw_name)
        name = os.path.basename(name.replace("\\", "/")) or "unnamed"
        total = int(headers.get(HDR_TOTAL_SIZE.lower(), "0") or 0)
        count = int(headers.get(HDR_CHUNK_COUNT.lower(), "0") or 0)
        sha = headers.get(HDR_FILE_SHA.lower(), "")
        chunk_size = int(headers.get(HDR_CHUNK_SIZE.lower(), "0") or 0) or LEGACY_HTTP_CHUNK_SIZE
        file_id = headers.get(HDR_FILE_ID.lower(), "") or f"{name}:{total}"
        if total > 0:
            count = (total + chunk_size - 1) // chunk_size

        key = hashlib.sha256(file_id.encode("utf-8")).hexdigest()[:32]
        data_path = os.path.join(self.part_dir, f"{key}.dat")
        bits_path = os.path.join(self.part_dir, f"{key}.bits")
        meta_path = os.path.join(self.part_dir, f"{key}.json")

        with self._lock:
            # 载入历史位图（断点续传）
            received = set()
            if os.path.exists(bits_path):
                try:
                    # 位图文件存的是原始字节（与 Java BitSet.toByteArray 一致），不是 base64
                    raw = open(bits_path, "rb").read()
                    for i in range(count):
                        if i // 8 < len(raw) and raw[i // 8] & (1 << (i % 8)):
                            received.add(i)
                except Exception:
                    received = set()

            # 元数据不一致则重来
            meta_ok = False
            if os.path.exists(meta_path):
                try:
                    meta = json.loads(open(meta_path, "r").read())
                    meta_ok = (meta.get("name") == name and meta.get("size") == total
                               and meta.get("chunk_size") == chunk_size)
                except Exception:
                    meta_ok = False
            if not meta_ok:
                received = set()
                if os.path.exists(data_path):
                    os.remove(data_path)

            self.seq += 1
            sid = f"u{self.seq}_{int(time.time() * 1000)}"
            self.sessions[sid] = {"name": name, "total": total, "count": count,
                                  "chunk_size": chunk_size, "sha": sha, "received": received,
                                  "key": key, "data": data_path, "bits": bits_path,
                                  "meta": meta_path}
            # 打开数据文件
            with open(data_path, "ab") as f:
                if total > 0:
                    f.truncate(total)
            with open(meta_path, "w") as f:
                json.dump({"name": name, "size": total, "chunk_size": chunk_size,
                           "chunk_count": count, "file_id": file_id}, f)

        self._json(conn, 200, {"session": sid, "chunk_size": chunk_size,
                               "chunk_count": count,
                               "resumed": len(received) > 0,
                               "received_b64": encode_bitmap(received)})

    def _chunk(self, conn, headers, body):
        sid = headers.get(HDR_SESSION.lower(), "")
        with self._lock:
            sess = self.sessions.get(sid)
        if sess is None:
            self._json(conn, 404, {"ok": False, "error": "session not found"})
            return
        index = int(headers.get(HDR_INDEX.lower(), "-1"))
        expect = headers.get(HDR_CHUNK_SHA.lower(), "")
        actual = sha256_bytes(body)
        if expect and expect.lower() != actual:
            self._json(conn, 400, {"ok": False, "error": "chunk checksum mismatch"})
            return
        offset = int(headers.get(HDR_OFFSET.lower(), "-1"))
        if offset < 0:
            offset = index * sess["chunk_size"]
        # 直接按偏移写入目标文件（免二次合并）
        with open(sess["data"], "r+b") as f:
            f.seek(offset)
            f.write(body)
        with self._lock:
            sess["received"].add(index)
            received_bytes = sum(
                min(sess["chunk_size"], sess["total"] - i * sess["chunk_size"])
                for i in sess["received"] if i * sess["chunk_size"] < sess["total"])
            # 持久化位图（原始字节，与 Java BitSet.toByteArray 一致）
            import base64
            with open(sess["bits"], "wb") as bf:
                bf.write(base64.b64decode(encode_bitmap(sess["received"])))
        self._json(conn, 200, {"ok": True, "index": index, "received": received_bytes,
                               "sha256": actual})

    def _commit(self, conn, headers):
        sid = headers.get(HDR_COMMIT_SESSION.lower(), "")
        declared = headers.get(HDR_FILE_SHA.lower(), "")
        with self._lock:
            sess = self.sessions.pop(sid, None)
        if sess is None:
            self._json(conn, 404, {"ok": False, "error": "session not found"})
            return
        if len(sess["received"]) != sess["count"]:
            self._json(conn, 400, {"ok": False, "error": "incomplete chunks"})
            return
        if os.path.getsize(sess["data"]) != sess["total"]:
            self._json(conn, 400, {"ok": False, "error": "size mismatch"})
            return

        target = os.path.join(self.upload_dir, sess["name"])
        base, ext = os.path.splitext(sess["name"])
        i = 1
        while os.path.exists(target):
            target = os.path.join(self.upload_dir, f"{base}({i}){ext}")
            i += 1
        os.replace(sess["data"], target)
        for p in (sess["bits"], sess["meta"]):
            if os.path.exists(p):
                os.remove(p)

        whole = sha256_file(target)
        verified = "chunkwise"
        if sess["total"] <= 1024 * 1024 * 1024:
            verified = "whole"
            if declared and declared.lower() != whole:
                os.remove(target)
                self._json(conn, 400, {"ok": False, "error": "file checksum mismatch"})
                return
        self._respond(conn, 200, "application/json; charset=utf-8",
                      json.dumps({"ok": True, "name": os.path.basename(target),
                                  "size": os.path.getsize(target), "sha256": whole,
                                  "verified": verified}).encode(),
                      extra={HDR_RESP_FILE_SHA: whole})

    def _delete(self, conn, query):
        name = query.get("name")
        if not name:
            self._json(conn, 404, {"ok": False, "error": "file not found"})
            return
        target = os.path.join(self.upload_dir, os.path.basename(name.replace("\\", "/")))
        ok = os.path.isfile(target) and os.remove(target) is None
        self._json(conn, 200 if ok else 500, {"ok": ok, "name": os.path.basename(target)})

    def _json(self, conn, code, obj, extra=None):
        self._respond(conn, code, "application/json; charset=utf-8",
                      json.dumps(obj).encode("utf-8"), extra)

    def _respond(self, conn, code, ctype, body, extra=None):
        head = [f"HTTP/1.1 {code} OK", f"Content-Type: {ctype}",
                f"Content-Length: {len(body)}", "Cache-Control: no-store",
                "Access-Control-Allow-Origin: *"]
        for k, v in (extra or {}).items():
            head.append(f"{k}: {v}")
        head.append("Connection: close")
        conn.sendall(("\r\n".join(head) + "\r\n\r\n").encode("latin-1") + body)


# =============================================================================
# 测试用例
# =============================================================================

def make_test_file(directory: str, name: str, size: int) -> str:
    path = os.path.join(directory, name)
    with open(path, "wb") as f:
        remaining = size
        block = os.urandom(64 * 1024)
        while remaining > 0:
            n = min(len(block), remaining)
            f.write(block[:n])
            remaining -= n
    return path


def run_suite(client: HttpClient, server_upload_dir: str, res: Result, label: str,
              client_tmp_dir: str = ""):
    """对给定客户端执行完整测试套件。"""
    os.makedirs(server_upload_dir, exist_ok=True)
    tmp = client_tmp_dir or os.path.join(server_upload_dir, "_client_tmp")
    os.makedirs(tmp, exist_ok=True)

    res.info(f"--- 测试目标: {label} ---")

    # 1) 设备信息
    try:
        dev = client.get_device()
        res.check(True, "GET /api/device 返回设备信息", f"port={dev.get('http_port')}")
    except Exception as e:  # noqa: BLE001
        res.check(False, "GET /api/device 返回设备信息", str(e))

    # 2) 上传页
    try:
        code, body = client.get_index()
        res.check(code == 200 and len(body) > 0, "GET / 返回上传页", f"HTTP {code}, {len(body)} bytes")
    except Exception as e:  # noqa: BLE001
        res.check(False, "GET / 返回上传页", str(e))

    # 3) 正常上传（多片并发）+ 完整性
    small = make_test_file(tmp, "small.bin", 1024)
    try:
        _, server_sha, err = upload_file_concurrent(client, small, threads=4)
        src_sha = sha256_file(small)
        res.check(not err, "上传小文件(1KB)成功", err)
        res.check(server_sha == src_sha, "服务端整文件 SHA-256 与源文件一致",
                  f"server={server_sha[:16]} src={src_sha[:16]}")
    except Exception as e:  # noqa: BLE001
        res.check(False, "上传小文件(1KB)成功", str(e))

    # 4) 中等文件（跨多分片并发）
    medium = make_test_file(tmp, "medium.bin", 3 * LEGACY_HTTP_CHUNK_SIZE + 12345)
    try:
        _, server_sha, err = upload_file_concurrent(client, medium, threads=6)
        src_sha = sha256_file(medium)
        res.check(not err, "上传多片文件(3MB+)并发成功", err)
        res.check(server_sha == src_sha, "多片文件整文件哈希一致",
                  f"server={server_sha[:16]} src={src_sha[:16]}")
    except Exception as e:  # noqa: BLE001
        res.check(False, "上传多片文件(3MB+)并发成功", str(e))

    # 5) 空文件
    empty = os.path.join(tmp, "empty.bin")
    open(empty, "wb").close()
    try:
        _, server_sha, err = upload_file_concurrent(client, empty, threads=2)
        res.check(not err, "上传空文件成功", err)
        res.check(server_sha == sha256_file(empty), "空文件哈希一致")
    except Exception as e:  # noqa: BLE001
        res.check(False, "上传空文件成功", str(e))

    # 6) 大分块协商（4 MiB）
    big_chunk = make_test_file(tmp, "bigchunk.bin", 9 * 1024 * 1024 + 777)
    try:
        init, server_sha, err = upload_file_concurrent(
            client, big_chunk, threads=4, chunk_size=HTTP_CHUNK_SIZE)
        res.check(not err, "4MiB 分块协商上传成功", err)
        res.check(init.get("chunk_size") == HTTP_CHUNK_SIZE,
                  "服务端按客户端声明的 4MiB 分块",
                  f"chunk_size={init.get('chunk_size')}")
        res.check(server_sha == sha256_file(big_chunk), "大分块文件整文件哈希一致")
    except Exception as e:  # noqa: BLE001
        res.check(False, "4MiB 分块协商上传成功", str(e))

    # 7) 文件清单
    try:
        files = client.get_files()
        names = [f["name"] for f in files]
        res.check("medium.bin" in names, "GET /api/files 列出已接收文件", f"names={names}")
    except Exception as e:  # noqa: BLE001
        res.check(False, "GET /api/files 列出已接收文件", str(e))

    # 8) 清单排序：最新在最前
    try:
        files = client.get_files()
        times = [f.get("time", 0) for f in files]
        res.check(times == sorted(times, reverse=True),
                  "文件清单按时间倒序（最新在最上）", f"times={times[:6]}")
    except Exception as e:  # noqa: BLE001
        res.check(False, "文件清单按时间倒序（最新在最上）", str(e))

    # 9) 故意错误整文件哈希 -> 服务端必须拒绝
    bad = make_test_file(tmp, "bad_whole.bin", 4096)
    try:
        _, _, err = upload_file_concurrent(client, bad, threads=2, corrupt_whole=True)
        rejected = ("checksum" in err.lower()) or ("HTTP 400" in err)
        res.check(rejected, "整文件哈希错误时服务端拒绝并返回 400", err or "no error raised")
    except Exception as e:  # noqa: BLE001
        res.check("400" in str(e) or "checksum" in str(e).lower(),
                  "整文件哈希错误时服务端拒绝并返回 400", str(e))

    # 10) 故意错误分片哈希 -> 服务端必须拒绝该分片
    bad2 = make_test_file(tmp, "bad_chunk.bin", 2 * LEGACY_HTTP_CHUNK_SIZE)
    try:
        _, _, err = upload_file_concurrent(client, bad2, threads=4, corrupt_chunk=1)
        res.check(bool(err), "分片哈希错误时服务端拒绝该分片", err or "no error raised")
    except Exception as e:  # noqa: BLE001
        res.check(bool(err or e), "分片哈希错误时服务端拒绝该分片", str(e))

    # 11) 断点续传：中断 -> 重新 init 拿回位图 -> 只补传缺失分片
    resume_path = make_test_file(tmp, "resume.bin", 5 * LEGACY_HTTP_CHUNK_SIZE + 999)
    try:
        fid = stable_file_id("resume.bin", os.path.getsize(resume_path), 1700000000000)
        # 第一阶段：只传前 2 个分块就中断
        _, _, err = upload_file_concurrent(client, resume_path, threads=2,
                                            file_id=fid, stop_after=2)
        res.check(err == "interrupted", "阶段一：上传中途模拟断网", err)

        # 第二阶段：用同一 fileId 重新 init，必须拿回已收位图
        size = os.path.getsize(resume_path)
        chunks = plan_chunks(size, LEGACY_HTTP_CHUNK_SIZE)
        init = client.init_session("resume.bin", size, len(chunks),
                                    LEGACY_HTTP_CHUNK_SIZE, fid)
        got = decode_bitmap(init.get("received_b64", ""), len(chunks))
        res.check(len(got) >= 2, "阶段二：重新 init 拿回已收分块位图",
                  f"received={sorted(got)}")
        res.check(init.get("resumed") is True, "阶段二：服务端标记 resumed=true",
                  f"resumed={init.get('resumed')}")

        # 第三阶段：只补传缺失分片 -> commit -> 文件完整
        skip = set(range(len(chunks))) - got
        _, server_sha, err = upload_file_concurrent(client, resume_path, threads=4,
                                                     file_id=fid, skip_indices=skip)
        res.check(not err, "阶段三：仅补传缺失分片后提交成功", err)
        res.check(server_sha == sha256_file(resume_path),
                  "断点续传后文件整文件哈希一致",
                  f"server={server_sha[:16]} src={sha256_file(resume_path)[:16]}")
    except Exception as e:  # noqa: BLE001
        res.check(False, "断点续传流程", str(e))

    # 12) 删除文件
    try:
        code, _ = client.delete_file("medium.bin")
        res.check(code == 200, "DELETE /api/file/delete 删除文件", f"HTTP {code}")
        files = client.get_files()
        res.check("medium.bin" not in [f["name"] for f in files], "删除后清单不再包含该文件")
    except Exception as e:  # noqa: BLE001
        res.check(False, "DELETE /api/file/delete 删除文件", str(e))

    return res


def main():
    ap = argparse.ArgumentParser(description="HTTP 通道协议一致性验证")
    ap.add_argument("--host", help="电视端 IP（真机联调模式）")
    ap.add_argument("--port", type=int, default=HTTP_PORT)
    ap.add_argument("--self", action="store_true",
                    help="协议自检模式：用参考实现验证客户端协议代码")
    ap.add_argument("--src-dir", default="",
                    help="测试文件产出目录（用于与接收端逐字节比对）")
    args = ap.parse_args()

    res = Result()

    if args.self or not args.host:
        # ---- A. 自检模式 ----
        workdir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_http_self")
        os.makedirs(workdir, exist_ok=True)
        server = ReferenceServer(os.path.join(workdir, "server"))
        server.start()
        client = HttpClient("127.0.0.1", server.port)
        print(f"[self] 参考服务器已启动: 127.0.0.1:{server.port}")
        try:
            run_suite(client, os.path.join(workdir, "server"), res, "本地协议参考实现")
        finally:
            server.stop()
    else:
        # ---- B. 真机联调模式 ----
        client = HttpClient(args.host, args.port)
        print(f"[live] 目标设备: {args.host}:{args.port}")
        run_suite(client, os.path.join(os.getcwd(), "_http_live"), res,
                  f"{args.host}:{args.port}", client_tmp_dir=args.src_dir)

    print(res.summary())
    return 0 if res.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
