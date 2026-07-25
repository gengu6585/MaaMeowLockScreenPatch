#!/bin/bash
# Maa-Meow 共享库：ADB 探针 + tutu 直连 HTTP
# shellcheck shell=bash
set -euo pipefail

export ADB="${ADB:-adb -s emulator-5554}"
export PKG="${PKG:-com.aliothmoon.maameow}"
export GAME="${GAME:-com.hypergryph.arknights}"
export HTTP_BASE="${HTTP_BASE:-http://127.0.0.1:17878}"
export MAA_BASE="/storage/emulated/0/Android/data/${PKG}/files/Maa"
export ASST="${MAA_BASE}/debug/asst.log"
export MEOW_DIR="${MAA_BASE}/debug/gui"
export COPILOT_DIR="${MAA_BASE}/copilot"
export SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export WAIT_READY_MS="${WAIT_READY_MS:-15000}"

maa_asst_tail() {
  $ADB shell su -c "tail -c ${1:-25000} $ASST" 2>/dev/null | strings
}

maa_latest_meow_log() {
  $ADB shell "ls -t ${MEOW_DIR}/meow_log_*.log 2>/dev/null | head -1" | tr -d '\r'
}

maa_game_running() {
  $ADB shell "pidof $GAME" 2>/dev/null | grep -q '[0-9]'
}

maa_meow_running() {
  $ADB shell "pidof $PKG" 2>/dev/null | grep -q '[0-9]'
}

maa_virtual_display_id() {
  $ADB shell su -c 'dumpsys SurfaceFlinger --display-id' 2>/dev/null \
    | grep 'Virtual display' | tail -1 | awk '{print $2}' | tr -d '\r'
}

maa_ensure_http() {
  if curl -sS --connect-timeout 1 --max-time 2 "$HTTP_BASE/v1/health" >/dev/null 2>&1; then
    return 0
  fi
  $ADB shell su -c \
    'am start -W -n com.aliothmoon.maameow/.MainActivity -f 0x28800000 >/dev/null' || true
  sleep 1
  curl -sS --connect-timeout 2 --max-time 3 "$HTTP_BASE/v1/health" >/dev/null 2>&1
}

maa_http_status() {
  maa_ensure_http || true
  curl -sS --connect-timeout 2 --max-time 5 "$HTTP_BASE/v1/status"
}

maa_stop_tasks() {
  maa_ensure_http || true
  curl -sS -X POST --connect-timeout 2 --max-time 30 "$HTTP_BASE/v1/stop" >/dev/null 2>&1 || true
  sleep "${1:-3}"
}

# 推荐入口：实时 SSE（见 meow_sse.sh）
maa_run_tasks_json() {
  local json="$1"
  bash "$SCRIPT_DIR/meow_sse.sh" "$json"
}

maa_tap_vd() {
  local x="$1" y="$2"
  local vd="${VD:-$(maa_virtual_display_id)}"
  [ -n "$vd" ] || return 1
  $ADB shell "su -c 'input -d $vd tap $x $y'" >/dev/null 2>&1 || true
}

maa_tap_start() {
  maa_tap_vd 640 680
  sleep 1.2
  maa_tap_vd 640 680
}

maa_post_task_cooldown() {
  sleep "${1:-3}"
}

maa_wait_remote_ready() {
  local timeout="${1:-20}"
  local mark now vd
  mark=$(date +%s)
  while true; do
    now=$(date +%s)
    if [ $((now - mark)) -ge "$timeout" ]; then
      echo "  remote/VD not ready after ${timeout}s" >&2
      return 1
    fi
    vd=$(maa_virtual_display_id || true)
    if [ -n "$vd" ] && maa_game_running; then
      sleep 1
      return 0
    fi
    sleep 1
  done
}

maa_dismiss_popups() {
  local dismiss="$SCRIPT_DIR/examples/dismiss_game_popups.sh"
  [ -f "$dismiss" ] || return 0
  VD="$(maa_virtual_display_id)" bash "$dismiss" >/dev/null 2>&1 || true
}

maa_task_running() {
  local st
  st=$(maa_http_status 2>/dev/null || true)
  case "$st" in
    *'"active":true'*|*'\"active\": true'*) return 0 ;;
    *'"task_running":true'*) return 0 ;;
  esac
  return 1
}

maa_stop_if_running() {
  if maa_task_running; then
    maa_stop_tasks "${1:-3}"
  else
    echo "  skip stop (no active task)"
    sleep 1
  fi
}

# 确保 stages.json 含 AD-EX-1..8（复刻 stageId）；在容器主机跑 Python
maa_ensure_ex_stages() {
  local remote="${MAA_BASE}/resource/stages.json"
  local local_json=/tmp/maa_stages.json
  $ADB pull "$remote" "$local_json" >/dev/null
  python3 - "$local_json" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f:
    data = json.load(f)
codes = {item.get("code") for item in data if isinstance(item, dict)}
need = []
for i in range(1, 9):
    code = f"AD-EX-{i}"
    if code not in codes:
        need.append({
            "apCost": 36,
            "code": code,
            "dropInfos": [
                {"dropType": "NORMAL_DROP", "itemId": "30073"},
                {"dropType": "FURNITURE", "itemId": "furni"},
            ],
            "stageId": f"act43side_ex0{i}_rep",
        })
if not need:
    print("stages.json EX ok")
else:
    data.extend(need)
    with open(p, "w") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print("stages.json appended", [x["code"] for x in need])
PY
  $ADB push "$local_json" /data/local/tmp/maa_stages.json >/dev/null
  $ADB shell su -c "cp /data/local/tmp/maa_stages.json $remote"
}
