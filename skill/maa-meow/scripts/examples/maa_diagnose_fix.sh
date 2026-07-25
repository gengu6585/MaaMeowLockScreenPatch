#!/bin/bash
# 诊断 + 可选拉起 HTTP / StartUp（通用）
#   bash maa_diagnose_fix.sh
#   bash maa_diagnose_fix.sh --fix
#   bash maa_diagnose_fix.sh --ensure-ready
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=../maa_lib.sh
source "$SCRIPT_DIR/../maa_lib.sh"

MODE="${1:---diagnose}"
FAIL=0
note_ok() { echo "  OK  $*"; }
note_warn() { echo " WARN $*"; }
note_bad() { echo "  BAD $*"; FAIL=1; }
note_fix() { echo "  FIX $*"; }

diagnose() {
  echo "=== 进程 / VD ==="
  if maa_game_running; then note_ok "游戏 $GAME"; else note_bad "游戏未运行"; fi
  if maa_meow_running; then note_ok "Meow $PKG"; else note_warn "Meow 未运行（meow_sse 会 am start）"; fi
  local vd
  vd=$(maa_virtual_display_id || true)
  if [ -n "$vd" ]; then note_ok "虚拟屏 display=$vd"; else note_bad "无 VirtualDisplay"; fi

  echo "=== HTTP ==="
  if maa_ensure_http; then
    note_ok "health $HTTP_BASE"
    maa_http_status | head -c 400
    echo
  else
    note_bad "HTTP 未就绪（补丁≥1.2.15？）"
  fi

  echo "=== AD.json ==="
  local cnt
  cnt=$($ADB shell "su -c \"grep -c AD-1 ${MAA_BASE}/resource/tasks/Stages/AD.json 2>/dev/null || echo 0\"" | tr -d '\r')
  if [ "${cnt:-0}" -ge 1 ] 2>/dev/null; then
    note_ok "AD.json 含 AD-1"
  else
    note_bad "AD.json 缺 AD-1"
  fi
}

fix_http_and_startup() {
  maa_ensure_http || true
  if ! maa_game_running || [ -z "$(maa_virtual_display_id || true)" ]; then
    note_fix "StartUp"
    maa_stop_if_running 3
    maa_run_tasks_json '{
      "force_stop_game": false,
      "closedown_after": false,
      "tasks": [{"type":"StartUp","params":{"client_type":"Official","start_game_enabled":true}}]
    }' || note_bad "StartUp 失败"
  fi
  if [ -f "$SCRIPT_DIR/ensure_ad_resources.sh" ]; then
    local cnt
    cnt=$($ADB shell "su -c \"grep -c AD-1 ${MAA_BASE}/resource/tasks/Stages/AD.json 2>/dev/null || echo 0\"" | tr -d '\r')
    if [ "${cnt:-0}" -lt 1 ] 2>/dev/null; then
      bash "$SCRIPT_DIR/ensure_ad_resources.sh" || true
      note_fix "ensure_ad_resources"
    fi
  fi
}

diagnose
case "$MODE" in
  --fix|--ensure-ready) fix_http_and_startup; diagnose ;;
esac
exit "$FAIL"
