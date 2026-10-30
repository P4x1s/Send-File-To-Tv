#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
大文件 + 大量文件压测（针对真实电视端 HTTP 服务器）

验证点：
  1. 大文件（默认 300MB）单次上传：**4 MiB 分块协商**、整文件 SHA-256 一致、
     传输速度与耗时；
  2. 传输过程中服务端的**内存占用**（通过 /proc/<pid>/status 读取 VmRSS），
     验证「分块流式落盘 + 直写目标文件」确实不会把整个文件读进内存；
  3. 大量文件（默认 60 个）顺序上传，验证服务端**文件清单按时间倒序**
     （最新上传的排在最上面）；
  4. 断点续传在**真实设备**上的表现：中断 -> 重连 -> 只补传缺失分片。

用法：
    python stress_test.py --host 192.168.50.189 --port 9527 [--big-mb 300] [--many 60]
"""

import argparse
import hashlib
import json
import os

import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

HTTP_CHUNK_SIZE = 4 * 1024 * 1024
LEGACY_HTTP_CHUNK_SIZE = 1024 * 1024

PATH_INIT = "/api/upload/init"
PATH_CHUNK = "/api/upload/chunk"
PATH_COMMIT = "/api/upload/commit"
PATH_FILES = "/api/files"

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


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for blk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(blk)
    return h.hexdigest()


def request(method, url, headers=None, body=None, timeout=300):
    req = urllib.request.Request(url, data=body, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def init_session(base, name, size, chunk_count, chunk_size, file_id, sha=""):
    headers = {
        HDR_NAME: urllib.parse.quote(name),
        HDR_TOTAL_SIZE: str(size),
        HDR_CHUNK_COUNT: str(chunk_count),
        HDR_CHUNK_SIZE: str(chunk_size),
        HDR_FILE_ID: file_id,
    }
    if sha:
        headers[HDR_FILE_SHA] = sha
    code, body, _ = request("POST", base + PATH_INIT, headers, b"")
    if code != 200:
        raise RuntimeError(f"init failed HTTP {code}: {body[:200]}")
    return json.loads(body.decode())


def upload_chunk(base, session, index, offset, payload, sha):
    headers = {
        HDR_SESSION: session,
        HDR_INDEX: str(index),
        HDR_SIZE: str(len(payload)),
        HDR_OFFSET: str(offset),
        HDR_CHUNK_SHA: sha,
    }
    code, body, _ = request("POST", base + PATH_CHUNK, headers, payload)
    if code != 200:
        raise RuntimeError(f"chunk {index} failed HTTP {code}: {body[:200]}")


def commit(base, session, sha=""):
    headers = {HDR_COMMIT_SESSION: session}
    if sha:
        headers[HDR_FILE_SHA] = sha
    code, body, hdrs = request("POST", base + PATH_COMMIT, headers, b"")
    if code != 200:
        raise RuntimeError(f"commit failed HTTP {code}: {body[:200]}")
    return hdrs.get(HDR_RESP_FILE_SHA, "")


def upload_concurrent(base, path, threads=8, chunk_size=HTTP_CHUNK_SIZE, file_id="",
                      stop_after=None):
    """并发上传一个文件。stop_after=N 时只传 N 个分块就中断（模拟断网）。"""
    name = os.path.basename(path)
    size = os.path.getsize(path)
    src_sha = sha256_file(path)
    if not file_id:
        file_id = hashlib.sha256(f"{name}|{size}|0".encode()).hexdigest()[:32]

    count = (size + chunk_size - 1) // chunk_size
    init = init_session(base, name, size, count, chunk_size, file_id, src_sha)
    session = init["session"]
    real_cs = init.get("chunk_size", chunk_size)
    if real_cs != chunk_size:
        count = (size + real_cs - 1) // real_cs
        chunk_size = real_cs

    limit = count if stop_after is None else min(stop_after, count)
    err = []
    lock = threading.Lock()
    idx = [0]

    def worker():
        with open(path, "rb") as f:
            while True:
                with lock:
                    i = idx[0]
                    idx[0] += 1
                if i >= limit:
                    return
                off = i * chunk_size
                f.seek(off)
                data = f.read(chunk_size)
                if not data:
                    return
                try:
                    upload_chunk(base, session, i, off, data,
                                 hashlib.sha256(data).hexdigest())
                except Exception as e:  # noqa: BLE001
                    with lock:
                        err.append(str(e))
                    return

    ts = [threading.Thread(target=worker) for _ in range(threads)]
    t0 = time.time()
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    elapsed = time.time() - t0

    if stop_after is not None:
        return session, src_sha, elapsed, count, err
    server_sha = commit(base, session, src_sha)
    return session, server_sha, elapsed, count, err


def read_server_rss(adb_serial):
    """读取电视端 App 进程的常驻内存（VmRSS）。"""
    try:
        out = subprocess.run(
            ["adb", "-s", adb_serial, "shell",
             "cat /proc/$(pidof com.lanfile.tv)/status 2>/dev/null | grep VmRSS"],
            capture_output=True, text=True, timeout=20)
        for line in out.stdout.splitlines():
            if "VmRSS" in line:
                kb = int(line.split()[1])
                return kb / 1024.0
    except Exception:
        pass
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, default=9527)
    ap.add_argument("--big-mb", type=int, default=300)
    ap.add_argument("--many", type=int, default=60)
    ap.add_argument("--adb", default="", help="adb 设备序列号（用于读服务端内存）")
    ap.add_argument("--keep", action="store_true", help="保留测试文件")
    args = ap.parse_args()

    base = f"http://{args.host}:{args.port}"
    work = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_stress_src")
    os.makedirs(work, exist_ok=True)

    print("=" * 62)
    print("大文件 + 大量文件压测")
    print("=" * 62)

    rss_before = read_server_rss(args.adb) if args.adb else None

    # ---------------- 1) 大文件 ----------------
    big = os.path.join(work, f"large{args.big_mb}.bin")
    if not os.path.exists(big) or os.path.getsize(big) != args.big_mb * 1024 * 1024:
        with open(big, "wb") as f:
            blk = os.urandom(1024 * 1024)
            for _ in range(args.big_mb):
                f.write(blk)
    size = os.path.getsize(big)
    print(f"\n[1] 大文件压测: {size / 1024 / 1024:.0f} MB -> {os.path.basename(big)}")

    peak = [rss_before or 0.0]
    stop_flag = threading.Event()

    def sampler():
        while not stop_flag.is_set():
            v = read_server_rss(args.adb) if args.adb else None
            if v:
                peak[0] = max(peak[0], v)
            time.sleep(0.5)

    smp = threading.Thread(target=sampler, daemon=True)
    if args.adb:
        smp.start()

    _, server_sha, elapsed, count, err = upload_concurrent(base, big, threads=8)
    stop_flag.set()
    src_sha = sha256_file(big)
    mbps = size / 1024 / 1024 / elapsed if elapsed > 0 else 0
    print(f"    分块数       : {count} (4 MiB/片)")
    print(f"    耗时         : {elapsed:.1f} s")
    print(f"    平均速度     : {mbps:.1f} MB/s")
    print(f"    整文件哈希   : {'MATCH' if server_sha == src_sha else 'MISMATCH'}")
    print(f"    传输错误     : {len(err)}")
    if args.adb:
        print(f"    服务端内存   : 传输前 {rss_before:.1f} MB / 峰值 {peak[0]:.1f} MB "
              f"(文件 {size / 1024 / 1024:.0f} MB)")
    ok_big = (server_sha == src_sha) and not err
    print(f"    => {'PASS' if ok_big else 'FAIL'}")

    # ---------------- 2) 断点续传（真机） ----------------
    print(f"\n[2] 断点续传（真实设备）")
    # 用 32MB 文件 => 8 个 4MiB 分片，中断在第 3 片，确保确有「缺失分片」可补传
    resume = os.path.join(work, "resume_live.bin")
    RESUME_MB = 32
    if not os.path.exists(resume) or os.path.getsize(resume) != RESUME_MB * 1024 * 1024:
        with open(resume, "wb") as f:
            blk = os.urandom(1024 * 1024)
            for _ in range(RESUME_MB):
                f.write(blk)
    rsize = os.path.getsize(resume)
    rcount_all = (rsize + HTTP_CHUNK_SIZE - 1) // HTTP_CHUNK_SIZE
    rfid = hashlib.sha256(f"{os.path.basename(resume)}|{rsize}|0".encode()).hexdigest()[:32]
    # 阶段一：只传 3 个分片就中断
    sess, _, _, rcount, _ = upload_concurrent(base, resume, threads=4,
                                               file_id=rfid, stop_after=3)
    # 阶段二：重新 init，验证拿到位图
    init2 = init_session(base, os.path.basename(resume), rsize, rcount_all,
                          HTTP_CHUNK_SIZE, rfid)
    import base64
    raw = base64.b64decode(init2.get("received_b64", ""))
    got = [i for i in range(rcount_all) if i // 8 < len(raw) and raw[i // 8] & (1 << (i % 8))]
    # 阶段三：完整补传
    _, rsha, _, _, rerr = upload_concurrent(base, resume, threads=4, file_id=rfid)
    rsrc = sha256_file(resume)
    print(f"    总分片数     : {rcount_all}（中断在第 3 片）")
    print(f"    中断后已收分片: {got}")
    print(f"    服务端 resumed : {init2.get('resumed')}")
    print(f"    补传后哈希     : {'MATCH' if rsha == rsrc else 'MISMATCH'}")
    # 通过标准：确有部分分片已收、标记 resumed、且未传满（否则不算真续传）
    ok_resume = (len(got) >= 3 and len(got) < rcount_all
                 and init2.get("resumed") is True and rsha == rsrc and not rerr)
    print(f"    => {'PASS' if ok_resume else 'FAIL'}")

    # ---------------- 3) 大量文件 + 排序 ----------------
    print(f"\n[3] 大量文件上传 ({args.many} 个) 与清单排序")
    t0 = time.time()
    many_ok = 0
    for i in range(args.many):
        p = os.path.join(work, f"m{i:03d}.bin")
        if not os.path.exists(p):
            with open(p, "wb") as f:
                f.write(os.urandom(64 * 1024))
        try:
            _, s, _, _, e = upload_concurrent(base, p, threads=4)
            if s == sha256_file(p) and not e:
                many_ok += 1
        except Exception as ex:  # noqa: BLE001
            print(f"    文件 {i} 失败: {ex}")
    print(f"    成功上传       : {many_ok}/{args.many}，耗时 {time.time() - t0:.1f}s")

    code, body, _ = request("GET", base + PATH_FILES)
    files = json.loads(body.decode())
    times = [f["time"] for f in files]
    names = [f["name"] for f in files]
    ordered = times == sorted(times, reverse=True)
    # 最新上传的 mNNN 应该在清单靠前位置
    print(f"    清单总数       : {len(files)}")
    print(f"    时间倒序       : {'PASS' if ordered else 'FAIL'}")
    print(f"    清单前 5 项    : {names[:5]}")
    ok_many = many_ok == args.many and ordered
    print(f"    => {'PASS' if ok_many else 'FAIL'}")

    if not args.keep:
        # 只清理本脚本自己生成的文件（逐个删除，不做目录级批量删除）
        for fn in os.listdir(work):
            if fn.startswith(("large", "resume_live", "m0")) and fn.endswith(".bin"):
                try:
                    os.remove(os.path.join(work, fn))
                except OSError:
                    pass

    print("\n" + "=" * 62)
    all_ok = ok_big and ok_resume and ok_many
    print(f"压测结果: {'ALL PASS' if all_ok else 'HAS FAILURE'}"
          f"  (大文件={'PASS' if ok_big else 'FAIL'},"
          f" 续传={'PASS' if ok_resume else 'FAIL'},"
          f" 大量文件={'PASS' if ok_many else 'FAIL'})")
    print("=" * 62)
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
