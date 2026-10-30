"""终端实时进度视图（多文件并发进度 + 速度 + 百分比）。

非 TTY（如重定向到文件/日志）时自动降级为周期性单行摘要，避免乱码。
该视图与传输核心通过 on_progress/on_file_done 回调对接，Android 端可用
等价的 UI 组件替换。
"""

from __future__ import annotations

import sys
import time
from typing import Dict

from .protocol import get_local_ip


def human_size(n: float) -> str:
    units = ["B", "KB", "MB", "GB", "TB"]
    f = float(n)
    for u in units:
        if f < 1024 or u == "TB":
            return f"{f:.1f} {u}" if u != "B" else f"{int(f)} {u}"
        f /= 1024
    return f"{n} B"


class ProgressView:
    def __init__(self, title: str = "传输", use_tui: bool | None = None):
        self.title = title
        self.use_tui = use_tui if use_tui is not None else sys.stdout.isatty()
        self.files: Dict[str, dict] = {}
        self.order: list[str] = []
        # 必须用 RLock（可重入）：on_progress / on_file_done 持锁后会调用 _draw()，
        # 而 _draw() 内部还要再次加锁，普通 Lock 会导致同线程自死锁。
        self.lock = __import__("threading").RLock()
        self.last_draw = 0.0
        self.last_summary = 0.0
        self.start_time = time.time()

    # -- 回调入口 ----------------------------------------------------------
    def on_connect(self, session_id: str) -> None:
        if not self.use_tui:
            print(f"[连接] 会话 {session_id} 已建立")

    def on_progress(self, session_id: str, states: Dict[str, dict]) -> None:
        with self.lock:
            now = time.time()
            for fid, st in states.items():
                prev = self.files.get(fid)
                if prev is None:
                    self.files[fid] = {
                        "name": st["name"],
                        "received": st["received"],
                        "total": st["total"],
                        "speed": 0.0,
                        "last": st["received"],
                        "t": now,
                        "done": False,
                    }
                    if fid not in self.order:
                        self.order.append(fid)
                else:
                    dt = now - prev["t"]
                    if dt > 0:
                        inst = (st["received"] - prev["last"]) / dt
                        prev["speed"] = prev["speed"] * 0.6 + inst * 0.4
                    prev["received"] = st["received"]
                    prev["total"] = st["total"]
                    prev["last"] = st["received"]
                    prev["t"] = now
        self._maybe_draw()

    def on_file_done(self, session_id: str, file_id: str, name: str,
                     ok: bool, error: str) -> None:
        with self.lock:
            rec = self.files.get(file_id)
            if rec is None:
                self.files[file_id] = {"name": name, "received": 0, "total": 0,
                                       "speed": 0.0, "last": 0, "t": time.time(),
                                       "done": True, "ok": ok, "error": error}
                if file_id not in self.order:
                    self.order.append(file_id)
            else:
                rec["done"] = True
                rec["ok"] = ok
                rec["error"] = error
                rec["received"] = rec["total"]
        if self.use_tui:
            self._draw(force=True)
        else:
            mark = "OK " if ok else "FAIL"
            msg = f"[{mark}] {name}" + (f" ({error})" if not ok else "")
            print(msg)

    def on_session_done(self, session_id: str, results: Dict[str, dict]) -> None:
        ok_all = all(r.get("ok") for r in results.values())
        total = len(results)
        ok_n = sum(1 for r in results.values() if r.get("ok"))
        if not self.use_tui:
            print(f"[完成] 会话 {session_id}：{ok_n}/{total} 个文件成功")
        else:
            self._draw(force=True)
            print()
            print(f">>> 会话结束：{ok_n}/{total} 个文件传输成功"
                  + ("" if ok_all else "（存在失败项，详见上方）"))

    # -- 渲染 --------------------------------------------------------------
    def _maybe_draw(self) -> None:
        if not self.use_tui:
            return
        now = time.time()
        if now - self.last_draw < 0.15:
            return
        self.last_draw = now
        self._draw(force=False)

    def _draw(self, force: bool) -> None:
        # 注意：此处必须使用可重入锁（RLock）。
        # on_progress / on_file_done 会先持锁再调用 _draw()，若用普通 Lock 会在
        # 同一线程二次加锁而自死锁 —— 该死锁发生在控制连接读取线程上，
        # 会导致后续 FILE_DONE 消息永远得不到处理（表现为「未收到完成确认」）。
        with self.lock:
            lines = []
            total_recv = 0
            total_all = 0
            for fid in self.order:
                rec = self.files[fid]
                name = rec["name"]
                recd = rec["received"]
                tot = rec["total"]
                total_recv += recd
                total_all += tot
                if rec.get("done"):
                    status = "完成" if rec.get("ok") else "失败"
                    line = f"  [{'x' if not rec.get('ok') else 'v'}] {name[:28]:<28} {status}"
                    if not rec.get("ok"):
                        line += f"  {rec.get('error','')}"
                    lines.append(line)
                else:
                    pct = (recd / tot * 100) if tot > 0 else 0.0
                    bar_len = 18
                    filled = int(bar_len * pct / 100)
                    bar = "#" * filled + "-" * (bar_len - filled)
                    spd = human_size(rec.get("speed", 0.0)) + "/s"
                    lines.append(
                        f"  [ ] {name[:22]:<22} |{bar}| {pct:5.1f}% "
                        f"{human_size(recd)}/{human_size(tot)} {spd}"
                    )
            overall = (total_recv / total_all * 100) if total_all > 0 else 0.0
            header = f"== {self.title} | 总进度 {overall:5.1f}% {human_size(total_recv)}/{human_size(total_all)} =="
        # 实际输出（锁外，避免重入）
        if getattr(self, "_rendered", 0) and not force:
            # 上移已绘行后重绘
            sys.stdout.write(f"\033[{self._rendered}A")
        sys.stdout.write("\033[2K" + header + "\n")
        for ln in lines:
            sys.stdout.write("\033[2K" + ln + "\n")
        sys.stdout.flush()
        self._rendered = len(lines) + 1

    def reset_cursor(self) -> None:
        if self.use_tui and getattr(self, "_rendered", 0):
            sys.stdout.write(f"\033[{self._rendered}B")
            sys.stdout.flush()
            self._rendered = 0
