#!/bin/bash
# 关闭常见遮挡弹窗（签到等）。默认只点按。
# RUN_AWARD=1 时额外跑 Award（会离开活动地图）。
set -euo pipefail
ADB="${ADB:-adb -s emulator-5554}"
if [ -z "${VD:-}" ]; then
  VD=$($ADB shell su -c 'dumpsys SurfaceFlinger --display-id' 2>/dev/null \
    | grep 'Virtual display' | tail -1 | awk '{print $2}' | tr -d '\r')
fi
VD="${VD:-7}"

tap() {
  $ADB shell "su -c 'input -d $VD tap $1 $2'" >/dev/null 2>&1 || true
}

tap 1217 69
sleep 0.6
tap 1217 69
sleep 0.4
tap 1180 620
sleep 0.3
tap 640 360
sleep 0.3

if [ "${RUN_AWARD:-0}" = "1" ]; then
  ROOT="$(cd "$(dirname "$0")/.." && pwd)"
  bash "$ROOT/meow_sse.sh" '{
    "force_stop_game": false,
    "closedown_after": false,
    "tasks": [{"type":"Award","params":{"enable":true}}]
  }' >/dev/null 2>&1 || true
fi

echo "popups dismissed (tap-only)"
