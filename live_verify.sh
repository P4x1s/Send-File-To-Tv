#!/usr/bin/env bash
# =============================================================================
# 局域网文件传输工具 —— 真机联调一键脚本
#
# 用法：
#   bash live_verify.sh                      # 自动探测（电视 192.168.1.100）
#   bash live_verify.sh <电视IP> [手机IP]     # 指定设备
#
# 流程：
#   1. adb 连接电视（over TCP 5555）
#   2. 安装并启动电视端 App
#   3. 探测服务端口（HTTP 9527 / TCP 41235 / 41236）
#   4. HTTP 通道端到端验证（分块并发上传 + SHA256 校验）
#   5. TCP 通道端到端验证（分块并发 + SHA256 校验）
#   6. 校验电视端落盘文件哈希是否与源文件一致
#   7. 清理测试文件
# =============================================================================
set -u

TV_IP="${1:-192.168.1.100}"
PHONE_IP="${2:-192.168.1.2}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 工具链定位：优先 PATH，其次 $TOOLS_HOME，最后 Android SDK 常见位置
find_adb() {
  if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
  for c in "$TOOLS_HOME/android-sdk/platform-tools/adb.exe" \
           "$ANDROID_HOME/platform-tools/adb.exe" \
           "$HOME/Android/Sdk/platform-tools/adb"; do
    [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return; }
  done
}
find_python() {
  if command -v python >/dev/null 2>&1; then command -v python; return; fi
  if command -v python3 >/dev/null 2>&1; then command -v python3; return; fi
  for c in "$TOOLS_HOME"/python/versions/*/python.exe; do
    [ -x "$c" ] && { echo "$c"; return; }
  done
}
ADB="$(find_adb)"
PY="$(find_python)"
[ -n "$ADB" ] || { echo "[FAIL] 找不到 adb（请安装 Android SDK Platform Tools 或设置 TOOLS_HOME）"; exit 1; }
[ -n "$PY" ]  || { echo "[FAIL] 找不到 python（请安装 Python 3.8+ 或设置 TOOLS_HOME）"; exit 1; }
TV_APK="$ROOT/android/tv/build/outputs/apk/debug/tv-debug.apk"
PHONE_APK="$ROOT/android/phone/build/outputs/apk/debug/phone-debug.apk"

HTTP_PORT=9527
CTRL_PORT=41235
DATA_PORT=41236

WORK="$ROOT/_live"
SRC="$WORK/src"
RC=0

say()  { printf "\n\033[1;36m==> %s\033[0m\n" "$*"; }
ok()   { printf "  \033[1;32m[OK]\033[0m %s\n" "$*"; }
bad()  { printf "  \033[1;31m[FAIL]\033[0m %s\n" "$*"; RC=1; }
info() { printf "  %s\n" "$*"; }

port_open() { (echo > /dev/tcp/"$1"/"$2") >/dev/null 2>&1; }

# --------------------------------------------------------------------------
say "0/7 环境准备"
rm -rf "$WORK"; mkdir -p "$SRC"
[ -x "$ADB" ]  || { bad "缺少 adb：$ADB"; exit 1; }
[ -f "$TV_APK" ] || { bad "缺少电视端 APK：$TV_APK"; exit 1; }
ok "电视端 APK 就绪（$(du -m "$TV_APK" | cut -f1) MB）"

# --------------------------------------------------------------------------
say "1/7 连接电视设备（adb over TCP）"
"$ADB" connect "$TV_IP:5555" >/dev/null 2>&1
sleep 2
STATE=$("$ADB" -s "$TV_IP:5555" get-state 2>/dev/null)
if [ "$STATE" != "device" ]; then
  bad "电视未授权 adb 调试（state=$STATE）"
  info "请在电视屏幕上点击「允许 USB 调试」勾选「一律允许」后重试"
  exit 2
fi
ok "电视已连接并授权"

# --------------------------------------------------------------------------
say "2/7 安装并启动电视端 App"
"$ADB" -s "$TV_IP:5555" install -r -g "$TV_APK" 2>&1 | tail -2
PKG=$("$ADB" -s "$TV_IP:5555" shell pm list packages 2>/dev/null | grep -o 'com\.lanfile\.tv' | head -1)
[ -n "$PKG" ] || { bad "电视端包未安装成功"; exit 3; }
ok "电视端包已安装：$PKG"

"$ADB" -s "$TV_IP:5555" shell am force-stop "$PKG" >/dev/null 2>&1
"$ADB" -s "$TV_IP:5555" shell am start -n "$PKG/.MainActivity" 2>&1 | tail -2
sleep 4
ok "电视端 App 已启动"

# --------------------------------------------------------------------------
say "3/7 探测服务端口"
for i in 1 2 3 4 5 6 7 8 9 10; do
  if port_open "$TV_IP" "$HTTP_PORT"; then break; fi
  sleep 2
done
if port_open "$TV_IP" "$HTTP_PORT"; then ok "HTTP 服务端口 $HTTP_PORT 已就绪"; else bad "HTTP 端口 $HTTP_PORT 未开放"; fi
if port_open "$TV_IP" "$CTRL_PORT"; then ok "TCP 控制端口 $CTRL_PORT 已就绪"; else bad "TCP 控制端口 $CTRL_PORT 未开放"; fi
if port_open "$TV_IP" "$DATA_PORT"; then ok "TCP 数据端口 $DATA_PORT 已就绪"; else bad "TCP 数据端口 $DATA_PORT 未开放"; fi

if ! port_open "$TV_IP" "$HTTP_PORT"; then
  info "电视端可能停留在前台界面或未启动服务，请检查电视屏幕上的 App 状态"
  exit 4
fi

# --------------------------------------------------------------------------
say "4/7 HTTP 通道端到端验证"
"$PY" -u "$ROOT/http_selftest.py" --host "$TV_IP" --port "$HTTP_PORT" --src-dir "$SRC" 2>&1 | tail -25
HTTP_RC=${PIPESTATUS[0]}
[ "$HTTP_RC" = "0" ] && ok "HTTP 通道验证通过" || bad "HTTP 通道验证失败（rc=$HTTP_RC）"

# --------------------------------------------------------------------------
say "5/7 TCP 通道端到端验证"
"$PY" -u "$ROOT/phone_sender.py" --tv-host "$TV_IP" --tv-port "$CTRL_PORT" --data-port "$DATA_PORT" \
       "$SRC/lanhttp_test.bin" "$SRC/lanhttp_test.txt" 2>&1 | tail -15
TCP_RC=${PIPESTATUS[0]}
[ "$TCP_RC" = "0" ] && ok "TCP 通道验证通过" || bad "TCP 通道验证失败（rc=$TCP_RC）"

# --------------------------------------------------------------------------
say "6/7 校验电视端落盘文件哈希"
# 电视端接收目录（Android app 外部私有目录）
REMOTE_DIR=$("$ADB" -s "$TV_IP:5555" shell "run-as $PKG ls -1 /storage/emulated/0/Android/data/$PKG/files/received 2>/dev/null || ls -1 /sdcard/Android/data/$PKG/files/received 2>/dev/null" 2>/dev/null | tr -d '\r')
if [ -z "$REMOTE_DIR" ]; then
  REMOTE_DIR=$("$ADB" -s "$TV_IP:5555" shell "ls -1 /sdcard/Android/data/$PKG/files/received 2>/dev/null" 2>/dev/null | tr -d '\r')
fi
if [ -z "$REMOTE_DIR" ]; then
  bad "无法列出电视端接收目录"
else
  info "电视端接收目录内容："
  echo "$REMOTE_DIR" | sed 's/^/      /'
  for base in $(echo "$SRC" | xargs -I{} find {} -type f -printf "%f\n" 2>/dev/null); do
    SRC_SHA=$(sha256sum "$SRC/$base" 2>/dev/null | cut -d' ' -f1)
    DST_SHA=$("$ADB" -s "$TV_IP:5555" shell "sha256sum /sdcard/Android/data/$PKG/files/received/$base 2>/dev/null || echo NOFILE" 2>/dev/null | tr -d '\r' | awk '{print $1}')
    case "$DST_SHA" in
      NOFILE|"") bad "$base 电视端未找到（源 ${SRC_SHA:0:16}）" ;;
      *) if [ "$SRC_SHA" = "$DST_SHA" ]; then ok "$base 哈希一致 ${SRC_SHA:0:16}"; else bad "$base 哈希不一致 src=${SRC_SHA:0:16} dst=${DST_SHA:0:16}"; fi ;;
    esac
  done
fi

# --------------------------------------------------------------------------
say "7/7 清理测试文件"
for base in $(echo "$SRC" | xargs -I{} find {} -type f -printf "%f\n" 2>/dev/null); do
  "$ADB" -s "$TV_IP:5555" shell "rm -f /sdcard/Android/data/$PKG/files/received/$base" >/dev/null 2>&1
done
ok "已清理电视端测试文件"

# --------------------------------------------------------------------------
printf "\n==============================================\n"
if [ "$RC" = "0" ]; then
  printf "\033[1;32m  真机联调全部通过 ✓\033[0m\n"
else
  printf "\033[1;31m  存在失败项 ✗\033[0m\n"
fi
printf "==============================================\n"
exit $RC
