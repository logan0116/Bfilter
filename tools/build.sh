#!/usr/bin/env bash
# 构建包装脚本。
#
# 普通用户其实**不需要**它 —— 直接用 ./gradlew 就行。它存在的意义是在受限环境
# （$HOME 只读、不能下载 Gradle 发行版）里固化几个必需的环境变量：
#
#   GRADLE_USER_HOME   必须可写，否则依赖缓存没处放
#   ANDROID_USER_HOME  必须可写，否则 AGP 建不了 debug keystore，
#                      会报 "Unable to create debug keystore in ~/.android because it is not writable"
#
# 用法：
#   tools/build.sh :app:assembleDebug                  # 用工程内的 gradlew（标准方式）
#   GRADLE=/opt/gradle/bin/gradle tools/build.sh ...   # 用本机已装好的 Gradle
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$REPO/.gradle-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$REPO/.android-home}"

GRADLE="${GRADLE:-$REPO/gradlew}"
if [ ! -x "$GRADLE" ]; then
  echo "找不到可执行的 Gradle：$GRADLE" >&2
  echo "（可用 GRADLE=/path/to/gradle 覆盖）" >&2
  exit 1
fi

mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"

# 预生成 debug keystore：AGP 会在 $ANDROID_USER_HOME 下找它，
# 提前建好可以避开首次构建时的目录竞争与权限问题。
DEBUG_KS="$ANDROID_USER_HOME/debug.keystore"
if [ ! -f "$DEBUG_KS" ]; then
  keytool -genkeypair -v -keystore "$DEBUG_KS" \
    -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 || true
fi

exec "$GRADLE" --no-daemon --console=plain "$@"
