#!/usr/bin/env bash
# 息屏前后各跑一次，对比输出就能定位"到底断在哪一层"。
#
#   tools/playback_probe.sh          # 抓一次快照
#
# 关心的四件事：
#   1. 前台服务还在不在  —— 不在，就是前台身份没保住
#   2. 音频播放器还在不在启动态 —— 决定"是不是彻底不出声了"
#   3. 进程 importance  —— cached 就意味着随时会被省电策略收掉
#   4. WifiLock —— media3 的 setWakeMode 有没有真的把 Wi-Fi 钉住
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="${BFILTER_PACKAGE:-io.github.logan0116.bfilter}"
ADB="$REPO/tools/adb-wrap.sh"

echo "########## $(date '+%H:%M:%S') ##########"

echo "--- 1. 前台 Activity ---"
timeout 90 "$ADB" shell "dumpsys activity activities | grep ResumedActivity" 2>/dev/null | tail -2

echo "--- 2. Bfilter 前台服务 ---"
timeout 90 "$ADB" shell "dumpsys activity services $PKG" 2>/dev/null \
  | grep -E "ServiceRecord|isForeground|foregroundServiceType|startRequested|createdFromFg" | head -8

echo "--- 3. 音频播放状态 ---"
timeout 90 "$ADB" shell "dumpsys audio" 2>/dev/null \
  | grep -iE "piid:|state:(started|paused|idle)" | head -14

echo "--- 4. 进程 importance / oom adj ---"
timeout 90 "$ADB" shell "dumpsys activity processes" 2>/dev/null \
  | grep -E "ProcessRecord.*$PKG" | head -3

echo "--- 5. WakeLock / WifiLock ---"
timeout 90 "$ADB" shell "dumpsys power" 2>/dev/null \
  | grep -iE "mWakeLockSummary|Wake Locks: size" | head -4
timeout 90 "$ADB" shell "dumpsys wifi" 2>/dev/null \
  | grep -iE "wifilock" | head -6
