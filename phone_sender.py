#!/usr/bin/env python3
"""手机端发送程序（局域网文件传输工具）。

功能：
  * 自动发现：UDP 扫描同网内电视设备，无需手动填写连接地址；
    也可通过 --tv-host 直接指定（联调/脚本化场景）。
  * 多线程并发：大文件切片后多连接并行发送，小文件多文件亦可并行。
  * 完整性：发送端预计算整文件 SHA256，电视端落盘后回校，不符即报错。
  * 进度反馈：实时显示每个文件与总体进度、速度、完成状态。

用法示例：
  python phone_sender.py file1.mp4 file2.zip          # 自动发现并选择电视
  python phone_sender.py --tv-host 192.168.1.50 a.bin # 直连指定电视
  python phone_sender.py --threads 16 *.iso            # 指定并发数
"""

from __future__ import annotations

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lantransfer import discovery, transfer, progress
from lantransfer.protocol import CONTROL_PORT, DATA_PORT, DISCOVERY_PORT


def build_items(paths: list[str]) -> list[transfer.FileItem]:
    items: list[transfer.FileItem] = []
    for p in paths:
        if not os.path.isfile(p):
            print(f"[跳过] 不是普通文件：{p}")
            continue
        items.append(transfer.FileItem.from_path(os.path.abspath(p), file_id=os.path.basename(p)))
    return items


def pick_device(scanner: discovery.PhoneScanner, timeout: float) -> discovery.TvDevice | None:
    deadline = time.time() + timeout
    print(f"正在扫描同网内的电视设备（{timeout:.0f}s）……")
    while time.time() < deadline:
        devs = scanner.list_devices()
        if devs:
            break
        time.sleep(0.3)
    devs = scanner.list_devices()
    if not devs:
        return None
    devs.sort(key=lambda d: d.last_seen, reverse=True)
    print("发现以下电视设备：")
    for i, d in enumerate(devs, 1):
        print(f"  [{i}] {d.name}  ({d.ip}:{d.tcp_port})")
    if sys.stdin.isatty():
        while True:
            try:
                choice = input(f"请选择目标设备 [1-{len(devs)}，回车默认 1]：").strip()
            except (EOFError, KeyboardInterrupt):
                return None
            idx = int(choice) - 1 if choice else 0
            if 0 <= idx < len(devs):
                return devs[idx]
            print("输入无效，请重试。")
    return devs[0]


def main() -> int:
    ap = argparse.ArgumentParser(description="手机端发送程序（局域网文件传输）")
    ap.add_argument("files", nargs="+", help="要发送的任意格式文件（可多个）")
    ap.add_argument("--tv-host", default=None, help="直接指定电视端 IP，跳过自动发现")
    ap.add_argument("--tv-port", type=int, default=CONTROL_PORT, help="电视端控制端口")
    ap.add_argument("--threads", type=int, default=8, help="并发发送线程数，默认 8")
    ap.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT)
    ap.add_argument("--control-port", type=int, default=CONTROL_PORT)
    ap.add_argument("--data-port", type=int, default=DATA_PORT)
    ap.add_argument("--scan-timeout", type=float, default=6.0, help="自动发现超时（秒）")
    args = ap.parse_args()

    items = build_items(args.files)
    if not items:
        print("没有可发送的文件，退出。")
        return 1

    total_bytes = sum(it.size for it in items)
    print(f"待发送：{len(items)} 个文件，共 {progress.human_size(total_bytes)}")

    target_host = args.tv_host
    target_port = args.tv_port
    scanner = None
    if not target_host:
        scanner = discovery.PhoneScanner(
            listen_udp_port=args.discovery_port,
            control_port=args.control_port,
            include_loopback=True,
        ).start()
        try:
            dev = pick_device(scanner, args.scan_timeout)
        finally:
            scanner.stop()
        if not dev:
            print("未发现任何电视设备。请确认电视端已启动且处于同一局域网。")
            return 1
        target_host, target_port = dev.ip, dev.tcp_port
        print(f"已选择：{dev.name} ({target_host}:{target_port})")

    view = progress.ProgressView(title=f"发送 -> {target_host}")
    client = transfer.PhoneTransferClient(
        host=target_host,
        control_port=target_port,
        data_port=args.data_port,
        threads=args.threads,
        on_connect=view.on_connect,
        on_progress=view.on_progress,
        on_file_done=view.on_file_done,
        on_session_done=view.on_session_done,
    )

    print(f"开始传输（并发 {args.threads} 线程）……")
    t0 = time.time()
    results = client.transfer(items)
    dt = max(time.time() - t0, 0.001)

    ok_n = sum(1 for r in results.values() if r.get("ok"))
    print("-" * 60)
    for fid, r in results.items():
        mark = "成功" if r.get("ok") else "失败"
        print(f"  [{mark}] {r.get('name')}" + ("" if r.get("ok") else f"  -> {r.get('error')}"))
    print(f"传输完成：{ok_n}/{len(results)} 成功，耗时 {dt:.1f}s，"
          f"平均 {progress.human_size(total_bytes / dt)}/s")
    view.reset_cursor()
    return 0 if ok_n == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
