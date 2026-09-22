#!/usr/bin/env bash
# Bfilter 构建包装脚本 —— 固化沙箱（workspace-write / HOME 只读）下必须的环境。
#
# 为什么要这个脚本：
#   1. GRADLE_USER_HOME 必须可写 → 指向工程内 .gradle-home（已从 counter 复制，含依赖缓存）
#   2. ANDROID_USER_HOME 必须可写 → 否则 AGP 无法创建 debug keystore，
#      报 "Unable to create debug keystore in /home/mozinodey/.android because it is not writable"
#   3. SDK 与 Gradle 只读复用 counter 工程内的那份，不重复下载
#
# 用法：
#   tools/build.sh :app:assembleDebug
#   tools/build.sh :app:testDebugUnitTest :app:assembleRelease
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$REPO/.gradle-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$REPO/.android-home}"
export ANDROID_HOME="${ANDROID_HOME:-/home/mozinodey/PycharmProjects/counter/.android-sdk}"

GRADLE="${GRADLE:-/home/mozinodey/PycharmProjects/counter/.tooling/gradle/bin/gradle}"
if [ ! -x "$GRADLE" ]; then
  echo "找不到 Gradle：$GRADLE（可用 GRADLE=... 覆盖）" >&2
  exit 1
fi

mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"

# debug keystore：AGP 会在 $ANDROID_USER_HOME 下找/建它。预先生成一份，
# 避免首次构建时因目录竞争或权限问题失败。
DEBUG_KS="$ANDROID_USER_HOME/debug.keystore"
if [ ! -f "$DEBUG_KS" ]; then
  keytool -genkeypair -v -keystore "$DEBUG_KS" \
    -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 || true
fi

exec "$GRADLE" --no-daemon --console=plain "$@"
