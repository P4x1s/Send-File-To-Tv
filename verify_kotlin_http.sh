#!/usr/bin/env bash
# ============================================================
# 用**真实的 Kotlin HttpUploadServer**（编译自 tv 模块源码）跑一次端到端验证：
#   1. 启动 JVM harness（真实 Kotlin 实现）
#   2. 等待 READY 行拿到端口
#   3. 用 Python 客户端（http_selftest.py）对该端口发真实 HTTP 请求
#   4. 比对接收目录中的文件与源文件哈希
#   5. 关闭服务
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
B="${WORKBUDDY_BIN:-/c/Users/Administrator/.workbuddy/binaries}"
JAVA="$B/jdk17/jdk-17.0.21.0.2+1/bin/java.exe"
PY="C:/Users/Administrator/.workbuddy/binaries/python/versions/3.13.12/python.exe"

OUT_DIR="${1:-/tmp/lan_kotlin_verify}"
PORT="${2:-19527}"
rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR"

HARNESS_LIB="$ROOT/android/harness/build/install/harness/lib"
if [ ! -d "$HARNESS_LIB" ]; then
  echo "[FAIL] harness 未构建，请先执行 gradle :harness:installDist"
  exit 1
fi

echo "[1/4] 启动真实 Kotlin HttpUploadServer (JVM harness)..."
CP="$HARNESS_LIB/harness.jar:$HARNESS_LIB/json-20240303.jar:$HARNESS_LIB/kotlin-stdlib-1.9.24.jar"
"$JAVA" -cp "$CP" com.lanfile.tv.http.JvmHarnessKt "$OUT_DIR" "$PORT" > /tmp/harness_run.log 2>&1 &
JAVA_PID=$!
cleanup() { kill $JAVA_PID 2>/dev/null; }
trap cleanup EXIT

# 等待 READY
for i in $(seq 1 40); do
  if grep -q "READY port=" /tmp/harness_run.log 2>/dev/null; then break; fi
  sleep 0.5
done
if ! grep -q "READY port=" /tmp/harness_run.log 2>/dev/null; then
  echo "[FAIL] harness 未就绪，日志："; cat /tmp/harness_run.log; exit 1
fi
ACTUAL_PORT=$(grep -o "READY port=[0-9]*" /tmp/harness_run.log | head -1 | cut -d= -f2)
echo "      harness 就绪，端口=$ACTUAL_PORT"

echo "[2/4] 运行 Python 客户端端到端测试..."
cd "$ROOT"
SRC_DIR="$ROOT/_verify_src"
rm -rf "$SRC_DIR"; mkdir -p "$SRC_DIR"
"$PY" http_selftest.py --host 127.0.0.1 --port "$ACTUAL_PORT" --src-dir "$SRC_DIR" > /tmp/lan_http_live.log 2>&1
PY_RC=$?

echo "[3/4] 校验接收目录中的文件哈希..."
SHA_RC=0
for f in "$OUT_DIR"/*; do
  [ -f "$f" ] || continue
  base=$(basename "$f")
  src="$SRC_DIR/$base"
  if [ -f "$src" ]; then
    h1=$(sha256sum "$src" | cut -d' ' -f1)
    h2=$(sha256sum "$f" | cut -d' ' -f1)
    if [ "$h1" = "$h2" ]; then
      echo "      [MATCH] $base  ${h1:0:16}"
    else
      echo "      [MISMATCH] $base src=${h1:0:16} recv=${h2:0:16}"
      SHA_RC=1
    fi
  fi
done

echo "[4/4] 结果"
echo "--- Python 客户端测试 ---"
tail -30 /tmp/lan_http_live.log
echo "--- Kotlin 服务端事件日志（部分）---"
grep "^EVENT" /tmp/harness_run.log | head -15
echo "--- 接收目录 ---"
ls -la "$OUT_DIR"

if [ $PY_RC -eq 0 ] && [ $SHA_RC -eq 0 ]; then
  echo ""
  echo "=============================================================="
  echo "验证通过：真实 Kotlin HTTP 服务器 + Python 客户端，"
  echo "          并发分块上传 + 双重 SHA-256 校验全部符合预期。"
  echo "=============================================================="
  exit 0
else
  echo ""
  echo "验证失败：PY_RC=$PY_RC SHA_RC=$SHA_RC"
  exit 1
fi
