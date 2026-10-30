"""TCP 分块并发传输核心（与 UI 无关，通过回调上报进度与状态）。

手机端 (PhoneTransferClient)
  * 建立一条控制连接，发送 HELLO -> MANIFEST。
  * 等待 MANIFEST_ACK 后，把所有分块任务投入线程池并发执行。
  * 每个分块独立建一条数据连接：首行 CHUNK 头 + 原始字节，读回 CHUNK_OK/FAIL。
  * 控制连接由独立读线程接收电视端推送的 PROGRESS / FILE_DONE / SESSION_DONE。

电视端 (TvTransferServer)
  * 控制端口：应答 HELLO、校验 MANIFEST、回 ACK，并启动该会话的通知线程。
  * 数据端口：并发接收分块，逐块校验 SHA256 并落盘到正确偏移。
  * 文件分块到齐后计算整文件 SHA256 与清单比对，回 FILE_DONE。
  * 周期推送 PROGRESS 供对端渲染进度。

关键实现：使用带缓冲的 FramedConn 读取，绝不丢弃换行符之后的字节，
否则 TCP 合并发送会导致后续二进制/消息错位（分块损坏、会话卡死）。

回调契约（两端共用同一套，便于复用同一个进度视图）：
  on_connect(session_id)
  on_progress(session_id, states)        states: {file_id: {name, received, total}}
  on_file_done(session_id, file_id, name, ok, error)
  on_session_done(session_id, results)   results: {file_id: {name, ok, error, sha256}}
"""

from __future__ import annotations

import hashlib
import json
import os
import socket
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Callable, Dict, List, Optional

from .protocol import (
    CHUNK_SIZE,
    DATA_PORT,
    CONTROL_PORT,
    FileItem,
    send_line,
    sha256_of_bytes,
)


# ---------------------------------------------------------------------------
# 带缓冲的连接读取器：行读取后保留剩余字节，供后续二进制/消息继续使用
# ---------------------------------------------------------------------------
class FramedConn:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self._buf = b""

    def recv_line(self, max_bytes: int = 1 << 20) -> dict:
        while b"\n" not in self._buf:
            want = min(65536, max_bytes - len(self._buf))
            chunk = self.sock.recv(want)
            if not chunk:
                if not self._buf:
                    raise ConnectionError("对端连接已关闭")
                break
            self._buf += chunk
            if len(self._buf) > max_bytes:
                raise ValueError("控制消息超长")
        line, _, rest = self._buf.partition(b"\n")
        self._buf = rest
        return json.loads(line.decode("utf-8"))  # 所有控制/分块头均为 JSON 行

    def recv_exact(self, n: int) -> bytes:
        while len(self._buf) < n:
            chunk = self.sock.recv(n - len(self._buf)) if n > len(self._buf) else b""
            if not chunk:  # 防止 n<=len(buf) 时 recv(0) 误判断连
                if len(self._buf) >= n:
                    break
                raise ConnectionError("对端在传输分块中途断开")
            self._buf += chunk
        out = self._buf[:n]
        self._buf = self._buf[n:]
        return bytes(out)

    def send_line(self, obj) -> None:
        send_line(self.sock, obj)

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


# ---------------------------------------------------------------------------
# 哈希辅助：单次读取同时得到整文件与每个分块的 SHA256
# ---------------------------------------------------------------------------
def hash_file_with_chunks(path: str, chunks) -> tuple[str, List[str]]:
    whole = hashlib.sha256()
    chunk_hashes: List[str] = []
    with open(path, "rb") as f:
        for ch in chunks:
            f.seek(ch.offset)
            data = f.read(ch.size) if ch.size > 0 else b""
            whole.update(data)
            chunk_hashes.append(sha256_of_bytes(data))
    return whole.hexdigest(), chunk_hashes


def _safe_name(name: str) -> str:
    base = os.path.basename(name.replace("\\", "/"))
    base = base.strip().replace("/", "").replace("\\", "")
    return base or "unnamed"


def _normalize_states(files) -> Dict[str, dict]:
    """把 PROGRESS 消息里的 "files" 载荷统一成 {file_id: {name, received, total}}。

    兼容两种线上格式：
      * dict —— Python 参考实现：{"f1": {"name": ..., "received": ..., "total": ...}}
      * list —— Android 实现：[{"file_id": ..., "name": ..., "received": ..., "total": ...}]
    """
    if isinstance(files, dict):
        return files
    out: Dict[str, dict] = {}
    if isinstance(files, list):
        for item in files:
            if not isinstance(item, dict):
                continue
            fid = item.get("file_id")
            if fid is None:
                continue
            out[fid] = {
                "name": item.get("name", ""),
                "received": item.get("received", 0),
                "total": item.get("total", 0),
            }
    return out


class _TcpAcceptor:
    def __init__(self, port: int, handler: Callable[[socket.socket, tuple], None],
                 host: str = "0.0.0.0"):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind((host, port))
        self._sock.listen(128)
        self._handler = handler
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> "_TcpAcceptor":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._stop.set()
        try:
            self._sock.close()
        except OSError:
            pass

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                conn, _addr = self._sock.accept()
            except OSError:
                break
            t = threading.Thread(target=self._dispatch, args=(conn,), daemon=True)
            t.start()

    def _dispatch(self, conn) -> None:
        try:
            self._handler(conn)
        except Exception:
            try:
                conn.close()
            except OSError:
                pass


# ---------------------------------------------------------------------------
# 电视端接收服务
# ---------------------------------------------------------------------------
class _FileState:
    __slots__ = ("name", "size", "sha256", "path", "total_chunks",
                 "done_chunks", "received", "done", "ok", "error", "lock")

    def __init__(self, name, size, sha256, path, total_chunks):
        self.name = name
        self.size = size
        self.sha256 = sha256
        self.path = path
        self.total_chunks = total_chunks
        self.done_chunks = 0
        self.received = 0
        self.done = False
        self.ok = False
        self.error = ""
        self.lock = threading.Lock()


class _Session:
    def __init__(self, session_id, ctrl: FramedConn, send_lock):
        self.session_id = session_id
        self.ctrl = ctrl
        self.send_lock = send_lock
        self.files: Dict[str, _FileState] = {}
        self.total_files = 0
        self.done_files = 0
        self.lock = threading.Lock()
        self.finished = threading.Event()


class TvTransferServer:
    def __init__(self, out_dir: str,
                 control_port: int = CONTROL_PORT,
                 data_port: int = DATA_PORT,
                 host: str = "0.0.0.0",
                 on_connect: Optional[Callable[[str], None]] = None,
                 on_progress: Optional[Callable[[str, Dict], None]] = None,
                 on_file_done: Optional[Callable[[str, str, str, bool, str], None]] = None,
                 on_session_done: Optional[Callable[[str, Dict], None]] = None):
        self._out_dir = out_dir
        os.makedirs(out_dir, exist_ok=True)
        self._control_port = control_port
        self._data_port = data_port
        self._host = host
        self._on_connect = on_connect
        self._on_progress = on_progress
        self._on_file_done = on_file_done
        self._on_session_done = on_session_done
        self._sessions: Dict[str, _Session] = {}
        self._slock = threading.Lock()
        self._seq = 0
        self._control = _TcpAcceptor(control_port, self._handle_control, host)
        self._data = _TcpAcceptor(data_port, self._handle_data, host)

    def start(self) -> "TvTransferServer":
        self._control.start()
        self._data.start()
        return self

    def stop(self) -> None:
        self._control.stop()
        self._data.stop()

    def _new_session_id(self) -> str:
        with self._slock:
            self._seq += 1
            return f"s{self._seq:08d}"

    # -- 控制连接 ----------------------------------------------------------
    def _handle_control(self, conn) -> None:
        fc = FramedConn(conn)
        sess: Optional[_Session] = None
        try:
            hello = fc.recv_line()
            if hello.get("t") != "HELLO":
                return
            session_id = self._new_session_id()
            send_lock = threading.Lock()
            with self._slock:
                sess = _Session(session_id, fc, send_lock)
                self._sessions[session_id] = sess
            fc.send_line({"t": "READY", "session_id": session_id,
                          "accept": True, "chunk_size": CHUNK_SIZE})
            if self._on_connect:
                try:
                    self._on_connect(session_id)
                except Exception:
                    pass
            manifest = fc.recv_line()
            if manifest.get("t") != "MANIFEST":
                return
            self._apply_manifest(sess, manifest)
            with sess.send_lock:
                fc.send_line({"t": "MANIFEST_ACK", "session_id": session_id,
                              "accepted": True, "files": len(sess.files)})
            threading.Thread(target=self._notifier, args=(sess,), daemon=True).start()
        except (OSError, ValueError, ConnectionError, AttributeError):
            if sess is not None:
                try:
                    fc.close()
                except OSError:
                    pass

    def _apply_manifest(self, sess: _Session, manifest: dict) -> None:
        files = manifest.get("files", [])
        sess.total_files = len(files)
        for f in files:
            fid = f["file_id"]
            name = _safe_name(f["name"])
            path = self._unique_path(name)
            with open(path, "wb") as fh:
                if f["size"] > 0:
                    fh.truncate(f["size"])
            fs = _FileState(name, f["size"], f["sha256"], path,
                            int(f.get("chunk_count", 1)))
            sess.files[fid] = fs

    def _unique_path(self, name: str) -> str:
        base, ext = os.path.splitext(name)
        candidate = os.path.join(self._out_dir, name)
        i = 1
        while os.path.exists(candidate):
            candidate = os.path.join(self._out_dir, f"{base}({i}){ext}")
            i += 1
        return candidate

    # -- 数据连接 ----------------------------------------------------------
    def _handle_data(self, conn) -> None:
        fc = FramedConn(conn)
        try:
            header = fc.recv_line()
            if header.get("t") != "CHUNK":
                return
            session_id = header.get("session_id")
            fid = header.get("file_id")
            index = int(header.get("index", -1))
            offset = int(header.get("offset", 0))
            size = int(header.get("size", 0))
            expect = header.get("sha256", "")

            sess = self._wait_session(session_id)
            if sess is None:
                fc.send_line({"t": "CHUNK_FAIL", "file_id": fid,
                              "index": index, "error": "session not found"})
                return
            fs = sess.files.get(fid)
            if fs is None:
                fc.send_line({"t": "CHUNK_FAIL", "file_id": fid,
                              "index": index, "error": "unknown file"})
                return

            data = fc.recv_exact(size)
            if sha256_of_bytes(data) != expect:
                fc.send_line({"t": "CHUNK_FAIL", "file_id": fid,
                              "index": index, "error": "chunk checksum mismatch"})
                return

            with open(fs.path, "r+b") as fh:
                fh.seek(offset)
                fh.write(data)

            with fs.lock:
                fs.received += size
                fs.done_chunks += 1
                completed = (fs.done_chunks >= fs.total_chunks)
            fc.send_line({"t": "CHUNK_OK", "file_id": fid, "index": index})

            if completed:
                self._finalize_file(sess, fid)
        except (OSError, ValueError, ConnectionError) as e:
            try:
                fc.send_line({"t": "CHUNK_FAIL", "file_id": header.get("file_id"),
                             "index": int(header.get("index", -1)),
                             "error": str(e)})
            except Exception:
                pass
        finally:
            fc.close()

    def _wait_session(self, session_id, timeout: float = 3.0) -> Optional[_Session]:
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self._slock:
                sess = self._sessions.get(session_id)
            if sess is not None:
                return sess
            time.sleep(0.02)
        return None

    def _finalize_file(self, sess: _Session, fid: str) -> None:
        fs = sess.files[fid]
        ok = False
        error = ""
        whole = ""
        try:
            h = hashlib.sha256()
            with open(fs.path, "rb") as fh:
                for block in iter(lambda: fh.read(1024 * 1024), b""):
                    h.update(block)
            whole = h.hexdigest()
            ok = (whole == fs.sha256)
            if not ok:
                error = "整文件校验失败"
        except OSError as e:
            error = f"读回校验失败: {e}"
        fs.done = True
        fs.ok = ok
        fs.error = error

        # 关键顺序：先把 FILE_DONE 真正写入控制连接，再递增 done_files 计数。
        # 若先计数，notifier 线程可能据此判定「全部完成」而调用 _finish_session
        # 关闭控制连接，导致在途的 FILE_DONE 丢失（客户端表现为「未收到完成确认」）。
        with sess.send_lock:
            try:
                sess.ctrl.send_line({"t": "FILE_DONE", "session_id": sess.session_id,
                                     "file_id": fid, "ok": ok, "sha256": whole,
                                     "error": error})
            except OSError:
                pass
            with sess.lock:
                sess.done_files += 1
                all_done = (sess.done_files >= sess.total_files)
        if self._on_file_done:
            try:
                self._on_file_done(sess.session_id, fid, fs.name, ok, error)
            except Exception:
                pass
        if all_done:
            self._finish_session(sess)

    def _finish_session(self, sess: _Session) -> None:
        if sess.finished.is_set():
            return
        sess.finished.set()
        results = {fid: {"name": fs.name, "ok": fs.ok, "error": fs.error,
                         "sha256": fs.sha256}
                   for fid, fs in sess.files.items()}
        with sess.send_lock:
            try:
                sess.ctrl.send_line({"t": "SESSION_DONE", "session_id": sess.session_id,
                                     "ok": all(v["ok"] for v in results.values()),
                                     "files": len(results)})
            except OSError:
                pass
        if self._on_session_done:
            try:
                self._on_session_done(sess.session_id, results)
            except Exception:
                pass
        # 优雅关闭：先 shutdown(SHUT_WR) 让已写入的数据全部送达，
        # 再延迟 close()。直接 close() 会触发 RST，对端可能读不到 FILE_DONE。
        try:
            sess.ctrl.sock.shutdown(socket.SHUT_WR)
            time.sleep(0.3)
            sess.ctrl.close()
        except OSError:
            pass
        with self._slock:
            self._sessions.pop(sess.session_id, None)

    def _notifier(self, sess: _Session) -> None:
        while not sess.finished.is_set():
            time.sleep(0.2)
            with sess.lock:
                states = {fid: {"name": fs.name, "received": fs.received,
                               "total": fs.size}
                          for fid, fs in sess.files.items()}
            if self._on_progress:
                try:
                    self._on_progress(sess.session_id, states)
                except Exception:
                    pass
            # 注意：此处不主动结束会话。
            # 会话结束统一由 _finalize_file 在「最后一个文件的 FILE_DONE 写出后」触发，
            # 否则 notifier 与 _finalize_file 之间存在竞态，可能在 FILE_DONE 尚未
            # 送达对端时就关闭控制连接，对端收到 RST 而读不到 FILE_DONE。


# ---------------------------------------------------------------------------
# 手机端发送客户端
# ---------------------------------------------------------------------------
class PhoneTransferClient:
    def __init__(self, host: str,
                 control_port: int = CONTROL_PORT,
                 data_port: int = DATA_PORT,
                 threads: int = 8,
                 on_connect: Optional[Callable[[str], None]] = None,
                 on_progress: Optional[Callable[[str, Dict], None]] = None,
                 on_file_done: Optional[Callable[[str, str, str, bool, str], None]] = None,
                 on_session_done: Optional[Callable[[str, Dict], None]] = None):
        self._host = host
        self._control_port = control_port
        self._data_port = data_port
        self._threads = threads
        self._on_connect = on_connect
        self._on_progress = on_progress
        self._on_file_done = on_file_done
        self._on_session_done = on_session_done

    def transfer(self, items: List[FileItem]) -> Dict[str, dict]:
        ctrl_sock = socket.create_connection((self._host, self._control_port), timeout=10)
        ctrl = FramedConn(ctrl_sock)
        results: Dict[str, dict] = {}
        manifest_ack = threading.Event()
        session_done = threading.Event()
        local_results: Dict[str, dict] = {}
        local_lock = threading.Lock()
        session_id_holder = {"id": None}

        # HELLO
        ctrl.send_line({"t": "HELLO", "proto": 1, "device": "phone"})

        # 关键：控制连接上的「读取」必须全部由 reader 线程完成。
        # 若主线程与 reader 线程并发调用 recv_line，FramedConn._buf 的读写不是线程安全的，
        # 会出现消息被错位消费（FILE_DONE 被当成 PROGRESS 丢弃）的现象，
        # 表现为大文件「未收到完成确认」而小文件正常。
        ready_holder: Dict[str, dict] = {}

        def reader():
            try:
                while True:
                    try:
                        msg = ctrl.recv_line()
                    except (OSError, ValueError, ConnectionError):
                        break
                    t = msg.get("t")
                    if t == "READY":
                        ready_holder["ready"] = msg
                        continue
                    if t == "MANIFEST_ACK":
                        session_id_holder["id"] = msg.get("session_id")
                        manifest_ack.set()
                    elif t == "PROGRESS":
                        if self._on_progress:
                            # 兼容两种 "files" 载荷格式：
                            #   1) dict: {file_id: {name, received, total}}（Python 参考实现）
                            #   2) list: [{file_id, name, received, total}, ...]（Android 实现）
                            # 早期 Android 端发的是 list，直接当 dict 处理会抛 AttributeError
                            # 并使控制连接读取线程崩溃，导致后续 FILE_DONE 全部丢失。
                            self._on_progress(msg.get("session_id"),
                                              _normalize_states(msg.get("files")))
                    elif t == "FILE_DONE":
                        fid = msg.get("file_id")
                        with local_lock:
                            local_results[fid] = {"ok": msg.get("ok"),
                                                 "error": msg.get("error", ""),
                                                 "sha256": msg.get("sha256", "")}
                        if self._on_file_done:
                            self._on_file_done(msg.get("session_id"), fid, fid,
                                              bool(msg.get("ok")), msg.get("error", ""))
                    elif t == "SESSION_DONE":
                        session_done.set()
                        # 关键：不能就此 break。
                        # 电视端并发发送各文件的 FILE_DONE，SESSION_DONE 可能在部分
                        # FILE_DONE 之前抵达控制连接；若此处直接退出循环，
                        # 剩余的 FILE_DONE 将永远不会被读取（表现为「未收到完成确认」）。
                        # 改为继续读到连接关闭，由对端关闭来终止循环。
                        continue
                    else:
                        # 忽略未知消息（如 PROGRESS 之外的新类型），继续等待终止消息
                        continue
            except Exception:
                session_done.set()

        rthread = threading.Thread(target=reader, daemon=True)
        rthread.start()

        # 等待 READY（由 reader 线程接收）
        deadline = time.time() + 10
        while time.time() < deadline:
            if "ready" in ready_holder:
                break
            time.sleep(0.01)
        ready = ready_holder.get("ready")
        if not ready or not ready.get("accept", False):
            ctrl.close()
            raise ConnectionError("电视端拒绝连接")
        session_id = ready.get("session_id")
        session_id_holder["id"] = session_id
        if self._on_connect:
            try:
                self._on_connect(session_id)
            except Exception:
                pass

        # 预计算整文件与各分块哈希（每个文件仅完整读一遍）
        prepared = []
        for item in items:
            whole, chunk_hashes = hash_file_with_chunks(item.path, item.chunks)
            if whole != item.sha256:
                item = FileItem(item.file_id, item.name, item.size, whole, item.path, item.chunks)
            prepared.append((item, chunk_hashes))

        manifest = {
            "t": "MANIFEST",
            "session_id": session_id,
            "files": [it.to_dict() for it, _ in prepared],
        }
        ctrl.send_line(manifest)
        if not manifest_ack.wait(timeout=10):
            ctrl.close()
            raise ConnectionError("未收到 MANIFEST_ACK")

        # 构建所有分块任务并并发执行
        tasks = []
        for (item, chunk_hashes) in prepared:
            for ch in item.chunks:
                tasks.append((item, ch, chunk_hashes[ch.index]))

        failed_chunks: Dict[str, int] = {}
        with ThreadPoolExecutor(max_workers=self._threads) as ex:
            futures = {
                ex.submit(self._send_chunk, session_id, item, ch, chash): (item.file_id, ch.index)
                for (item, ch, chash) in tasks
            }
            for fut in as_completed(futures):
                fid, _idx = futures[fut]
                try:
                    ok = fut.result()
                except Exception:
                    ok = False
                if not ok:
                    failed_chunks[fid] = failed_chunks.get(fid, 0) + 1

        # 等待会话结束。
        # 关键：控制连接的所有读取都由 reader 线程完成（主线程不得并发 recv_line，
        # 否则 FramedConn._buf 非线程安全会导致消息错位）。
        # 也不能因为收到 SESSION_DONE 就立即结束等待——电视端并发发送各文件的
        # FILE_DONE，SESSION_DONE 可能在部分 FILE_DONE 之前抵达；
        # reader 线程会一直读到对端关闭连接为止，把在途的 FILE_DONE 全部读完。
        deadline = time.time() + 30
        while time.time() < deadline:
            with local_lock:
                if len(local_results) >= len(prepared):
                    break
            if session_done.is_set():
                # SESSION_DONE 已到，但可能仍有 FILE_DONE 在途：
                # 继续等 reader 线程把剩余 FILE_DONE 读完（reader 读到连接关闭才退出）。
                if not rthread.is_alive():
                    break
            time.sleep(0.05)

        # 等待 reader 线程自然结束（对端关闭控制连接后即退出）
        rthread.join(timeout=10)
        # 给予在途数据一点时间
        time.sleep(0.2)

        # 汇总结果
        for item, _chashes in prepared:
            fid = item.file_id
            if fid in local_results:
                r = dict(local_results[fid])
                r["name"] = item.name
                r["sha256"] = item.sha256
            else:
                r = {"name": item.name, "ok": False,
                     "error": "未收到完成确认", "sha256": item.sha256}
            if fid in failed_chunks and not r.get("ok"):
                r["error"] = (r.get("error") or "") + f" | {failed_chunks[fid]} 个分块发送失败"
            results[fid] = r

        try:
            ctrl.send_line({"t": "BYE", "session_id": session_id})
        except OSError:
            pass
        ctrl.close()

        if self._on_session_done:
            try:
                self._on_session_done(session_id, results)
            except Exception:
                pass
        return results

    def _send_chunk(self, session_id, item: FileItem, ch, chunk_hash) -> bool:
        last_err = ""
        for attempt in range(5):
            try:
                s = socket.create_connection((self._host, self._data_port), timeout=60)
            except OSError as e:
                last_err = str(e)
                time.sleep(0.2 * (attempt + 1))
                continue
            fc = FramedConn(s)
            try:
                fc.send_line({
                    "t": "CHUNK",
                    "session_id": session_id,
                    "file_id": item.file_id,
                    "index": ch.index,
                    "offset": ch.offset,
                    "size": ch.size,
                    "sha256": chunk_hash,
                })
                if ch.size > 0 and item.path:
                    with open(item.path, "rb") as f:
                        f.seek(ch.offset)
                        remaining = ch.size
                        while remaining > 0:
                            buf = f.read(min(1024 * 1024, remaining))
                            if not buf:
                                break
                            fc.sock.sendall(buf)
                            remaining -= len(buf)
                result = fc.recv_line()
                if result.get("t") == "CHUNK_OK":
                    return True
                last_err = result.get("error", "CHUNK_FAIL")
            except (OSError, ValueError, ConnectionError) as e:
                last_err = str(e)
            finally:
                fc.close()
            time.sleep(0.2 * (attempt + 1))
        return False
