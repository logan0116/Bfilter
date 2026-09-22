#!/usr/bin/env bash
# 在容器里跑 adb —— 本机沙箱的 /dev 被裁剪掉了，看不到 USB，必须借道 privileged 容器。
#
# 用法：
#   tools/adb-wrap.sh devices
#   tools/adb-wrap.sh install -r app/build/outputs/apk/debug/app-debug.apk
#   tools/adb-wrap.sh exec-out screencap -p > /tmp/shot.png
#   tools/adb-wrap.sh logcat -d -s AndroidRuntime:E
#
# 说明：容器里 adb server 随容器生命周期结束，每次调用都会重启一个（约 2 秒）。
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM_TOOLS="${PLATFORM_TOOLS:-$HOME/fun/platform-tools}"
ADB_HOME="${ADB_HOME:-$HOME/.android}"

if [ ! -x "$PLATFORM_TOOLS/adb" ]; then
  echo "找不到 adb：$PLATFORM_TOOLS/adb（可用 PLATFORM_TOOLS=... 覆盖）" >&2
  exit 1
fi

# 关键：把宿主 ~/.android 挂进容器，复用同一对 adb 密钥。
# 否则容器每次生成新密钥，手机端会判为 unauthorized，每次都要重新授权。
mkdir -p "$ADB_HOME"

exec docker run --rm -i --privileged \
  -v "$PLATFORM_TOOLS":/adb:ro \
  -v "$ADB_HOME":/root/.android \
  -v "$REPO":/work \
  -w /work \
  ubuntu:24.04 \
  bash -lc '/adb/adb start-server >/dev/null 2>&1; sleep 1; exec /adb/adb "$@"' _ "$@"
