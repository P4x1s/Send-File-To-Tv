#!/usr/bin/env python3
"""电视端接收程序（局域网文件传输工具）。

功能：
  * UDP 自动宣告：周期性广播 ANNOUNCE 信标，并应答手机端的 DISCOVER，
    使手机端无需手动配置即可发现本设备。
  * TCP 并发接收：控制端口 + 数据端口，多线程接收分块并落盘。
  * 完整性校验：整文件 SHA256 校验，失败会明确报错。
  * 实时进度：终端多文件进度条（非 TTY 时降级为摘要日志）。

用法示例：
  python tv_receiver.py                       # 默认 0.0.0.0，接收目录 ./received
  python tv_receiver.py --out-dir D:/recv --name "客厅电视"
  python tv_receiver.py --host 127.0.0.1      # 仅本机环回，便于本地联调
"""

from __future__ import annotations

import argparse
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lantransfer import discovery, transfer, progress
from lantransfer.protocol import CONTROL_PORT, DATA_PORT, DISCOVERY_PORT, get_local_ip


def main() -> int:
    ap = argparse.ArgumentParser(description="电视端接收程序（局域网文件传输）")
    ap.add_argument("--host", default="0.0.0.0", help="TCP 监听地址，默认 0.0.0.0（所有网卡）")
    ap.add_argument("--out-dir", default="received", help="文件接收目录，默认 ./received")
    ap.add_argument("--name", default=None, help="设备显示名，默认 'TV-<随机>'")
    ap.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT)
    ap.add_argument("--control-port", type=int, default=CONTROL_PORT)
    ap.add_argument("--data-port", type=int, default=DATA_PORT)
    ap.add_argument("--no-beacon", action="store_true", help="关闭周期信标（仅应答 DISCOVER）")
    args = ap.parse_args()

    name = args.name or f"TV-{uuid.uuid4().hex[:4].upper()}"
    device_id = uuid.uuid4().hex
    view = progress.ProgressView(title=f"接收 @ {name}")

    server = transfer.TvTransferServer(
        out_dir=args.out_dir,
        control_port=args.control_port,
        data_port=args.data_port,
        host=args.host,
        on_connect=view.on_connect,
        on_progress=view.on_progress,
        on_file_done=view.on_file_done,
        on_session_done=view.on_session_done,
    )
    server.start()

    announcer = None
    if not args.no_beacon:
        announcer = discovery.TvAnnouncer(
            name=name,
            device_id=device_id,
            control_port=args.control_port,
            discover_port=args.discovery_port,
        )
        announcer.start()

    print("=" * 60)
    print(f"电视端接收程序已启动")
    print(f"  设备名 : {name}")
    print(f"  局域网IP : {get_local_ip()}")
    print(f"  接收目录 : {os.path.abspath(args.out_dir)}")
    print(f"  控制端口 : {args.control_port}    数据端口 : {args.data_port}")
    print(f"  发现端口 : {args.discovery_port}  信标 : {'关' if args.no_beacon else '开'}")
    print("  等待手机端连接……（Ctrl+C 退出）")
    print("=" * 60)

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n正在关闭……")
    finally:
        if announcer:
            announcer.stop()
        server.stop()
        view.reset_cursor()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
