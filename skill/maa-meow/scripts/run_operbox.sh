#!/bin/bash
# ## Agent 提示: 见 SKILL.md；主任务入口是 meow_sse.sh
#
# 运行 MaaCore OperBox 干员识别，解析 asst.log 并保存 operbox.json
#
#   bash run_operbox.sh              # 识别 + 保存
#   bash run_operbox.sh --parse-only # 仅解析现有 asst.log
#   bash run_operbox.sh --timeout 900
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb -s emulator-5554}"
PKG=com.aliothmoon.maameow
ASST="/storage/emulated/0/Android/data/${PKG}/files/Maa/debug/asst.log"
REMOTE_OPERBOX="/storage/emulated/0/Android/data/${PKG}/files/Maa/user/operbox.json"
LOCAL_OPERBOX="${OPERBOX_OUT:-$SCRIPT_DIR/../data/operbox.json}"
PARSE_ONLY=false
TIMEOUT="${OPERBOX_TIMEOUT:-900}"

usage() {
  sed -n '2,10p' "$0" | sed 's/^# //'
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --parse-only) PARSE_ONLY=true ;;
    --timeout) TIMEOUT="$2"; shift ;;
    --out) LOCAL_OPERBOX="$2"; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown: $1"; usage ;;
  esac
  shift
done

mkdir -p "$(dirname "$LOCAL_OPERBOX")"

parse_and_save() {
  local tmp=/tmp/maa_asst_operbox.log
  $ADB shell su -c "grep OperBoxInfo '$ASST'" 2>/dev/null > "$tmp" || true
  if [ ! -s "$tmp" ]; then
    $ADB shell su -c "tail -c 12000000 '$ASST'" 2>/dev/null > "$tmp" || true
  fi
  if [ ! -s "$tmp" ]; then
    echo "ERROR: cannot read asst.log"
    return 1
  fi
  python3 "$SCRIPT_DIR/operbox_lib.py" parse "$tmp" --out "$LOCAL_OPERBOX"
  $ADB shell "mkdir -p $(dirname "$REMOTE_OPERBOX")" 2>/dev/null || true
  $ADB push "$LOCAL_OPERBOX" "$REMOTE_OPERBOX" >/dev/null 2>&1 || true
}

if [ "$PARSE_ONLY" = true ]; then
  parse_and_save
  exit $?
fi

echo "=== [1/3] stop + OperBox SSE（最多 ${TIMEOUT}s 流超时）==="
curl -sS -X POST --max-time 30 http://127.0.0.1:17878/v1/stop >/dev/null 2>&1 || true
sleep 1
TIMEOUT_MS=$((TIMEOUT * 1000)) \
  bash "$SCRIPT_DIR/meow_sse.sh" '{
    "force_stop_game": false,
    "closedown_after": false,
    "tasks": [{"type":"OperBox","params":{"enable":true}}]
  }' || true

echo "=== [2/3] 解析 OperBoxInfo ==="
parse_and_save

echo "=== [3/3] done → $LOCAL_OPERBOX"
