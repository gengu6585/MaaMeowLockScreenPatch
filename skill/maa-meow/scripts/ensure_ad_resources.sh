#!/bin/bash
# 确保红丝绒 AD.json（含 AD-1）在 resource + overrides
set -euo pipefail
ADB="${ADB:-adb -s emulator-5554}"
PKG=com.aliothmoon.maameow
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-$SCRIPT_DIR/../resources/AD.json}"
BASE="/storage/emulated/0/Android/data/${PKG}/files/Maa"

if [ ! -f "$SRC" ]; then
  echo "ERROR: AD.json not found: $SRC"
  exit 1
fi

python3 - <<PY
import json,sys
d=json.load(open("$SRC"))
assert "AD-1" in d and "AD-OpenOpt" in d, "AD.json missing AD-1/AD-OpenOpt"
print("local AD.json keys:", len(d), "AD-1 OK")
PY

echo "[1] push resource ..."
$ADB push "$SRC" /data/local/tmp/AD.json
$ADB shell su -c "cp /data/local/tmp/AD.json $BASE/resource/tasks/Stages/AD.json"
$ADB shell su -c "mkdir -p $BASE/overrides/resource/tasks/Stages"
$ADB shell su -c "cp /data/local/tmp/AD.json $BASE/overrides/resource/tasks/Stages/AD.json"
$ADB shell su -c "grep -c AD-1 $BASE/resource/tasks/Stages/AD.json"
echo "Done. 若 Meow 已加载旧资源，下一轮 RUN_TASKS 会经 overrides 合并；仍 No stage 时再重启 Meow（会丢 VD）。"
