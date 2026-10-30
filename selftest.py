#!/usr/bin/env python3
"""端到端自检：进程内验证 自动发现 + 分块并发传输 + SHA256 完整性校验。

不依赖外部网络，全部在 127.0.0.1 完成，便于 CI 验证。
运行：python selftest.py
"""

from __future__ import annotations

import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lantransfer import discovery, transfer, progress
from lantransfer.protocol import (
    CHUNK_SIZE,
    sha256_of_file,
    FileItem,
)

DPORT = 49134
CPORT = 49135
DATAP = 49136


def make_random_file(path: str, size: int) -> None:
    with open(path, "wb") as f:
        remaining = size
        while remaining > 0:
            blk = min(1024 * 1024, remaining)
            f.write(os.urandom(blk))
            remaining -= blk


def test_transfer() -> bool:
    tmp = tempfile.mkdtemp(prefix="lft_tx_")
    src_dir = os.path.join(tmp, "src")
    recv_dir = os.path.join(tmp, "recv")
    os.makedirs(src_dir)
    sizes = [0, 100, 10 * 1024 * 1024, 33 * 1024 * 1024]  # 空 / 小 / 中 / 大
    paths = []
    for i, sz in enumerate(sizes):
        p = os.path.join(src_dir, f"f{i}.bin")
        make_random_file(p, sz)
        paths.append(p)

    server = transfer.TvTransferServer(
        out_dir=recv_dir,
        control_port=CPORT,
        data_port=DATAP,
        host="127.0.0.1",
    ).start()
    time.sleep(0.3)

    items = [FileItem.from_path(p, file_id=os.path.basename(p)) for p in paths]
    client = transfer.PhoneTransferClient(
        host="127.0.0.1",
        control_port=CPORT,
        data_port=DATAP,
        threads=8,
    )
    results = client.transfer(items)
    server.stop()

    ok = True
    for it in items:
        r = results.get(it.file_id, {})
        recv_path = os.path.join(recv_dir, it.name)
        if not r.get("ok"):
            print(f"  [FAIL] {it.name}: 传输状态失败 {r.get('error')}")
            ok = False
            continue
        if not os.path.exists(recv_path):
            print(f"  [FAIL] {it.name}: 接收端文件缺失")
            ok = False
            continue
        if sha256_of_file(recv_path) != it.sha256:
            print(f"  [FAIL] {it.name}: SHA256 不一致")
            ok = False
            continue
        print(f"  [ OK ] {it.name} ({progress.human_size(it.size)}) 校验通过")
    return ok


def test_discovery() -> bool:
    name = "SelftestTV"
    ann = discovery.TvAnnouncer(
        name=name, device_id="selftest-dev", control_port=CPORT,
        discover_port=DPORT, beacon_interval=0.5,
    ).start()
    scan = discovery.PhoneScanner(
        listen_udp_port=DPORT, control_port=CPORT, include_loopback=True,
    ).start()
    try:
        found = None
        for _ in range(30):  # 最多等待 ~3s
            devs = scan.list_devices()
            for d in devs:
                if d.name == name:
                    found = d
                    break
            if found:
                break
            time.sleep(0.1)
    finally:
        scan.stop()
        ann.stop()
    if found:
        print(f"  [ OK ] 自动发现成功：{found.name} @ {found.ip}:{found.tcp_port}")
        return True
    print("  [FAIL] 自动发现失败：未收到电视端宣告")
    return False


def main() -> int:
    print("=" * 50)
    print("局域网文件传输工具 — 端到端自检")
    print("=" * 50)
    print("[1/2] 自动发现（UDP 扫描/信标）")
    d_ok = test_discovery()
    print("[2/2] 分块并发传输 + SHA256 校验（含空/小/中/大文件）")
    t_ok = test_transfer()
    print("=" * 50)
    if d_ok and t_ok:
        print("全部通过 ✅")
        return 0
    print("存在失败项 ❌")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
