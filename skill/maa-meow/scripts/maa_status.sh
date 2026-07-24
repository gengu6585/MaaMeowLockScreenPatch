#!/bin/bash
# 进程/虚拟屏/最新任务状态快照
set -euo pipefail
ADB="${ADB:-adb -s emulator-5554}"
PKG=com.aliothmoon.maameow
GAME=com.hypergryph.arknights

echo "=== processes ==="
$ADB shell "ps -A | grep -E 'maameow|arknights' | grep -v grep" || true

echo
echo "=== game main ==="
if $ADB shell "pidof $GAME" 2>/dev/null | grep -q '[0-9]'; then
  echo "RUNNING ($GAME)"
else
  echo "NOT RUNNING (only :pushcore does not count)"
fi

echo
echo "=== displays ==="
$ADB shell su -c 'dumpsys SurfaceFlinger --display-id' 2>/dev/null | head -20 || true

echo
echo "=== patch module ==="
$ADB shell "dumpsys package com.tinkerlab.maameowpatch 2>/dev/null | grep -E 'versionName|versionCode' | head -5" || true

echo
$ADB shell su -c 'sh /data/local/tmp/launch_cli_tasks.sh --status' 2>/dev/null || true
