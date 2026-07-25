#!/bin/bash
# ## Agent 提示: 见 SKILL.md；主任务入口是 meow_sse.sh
#
# 查看 MaaCore / Meow 运行日志（在手机 Termux/Ubuntu 或 adb shell 外层执行）
# 用法:
#   bash ~/.cursor/skills/maa-meow/scripts/watch_maa_logs.sh
#   bash ~/.cursor/skills/maa-meow/scripts/watch_maa_logs.sh --tail 80
#   bash ~/.cursor/skills/maa-meow/scripts/watch_maa_logs.sh --follow
set -euo pipefail

ADB="${ADB:-adb -s emulator-5554}"
PKG=com.aliothmoon.maameow
BASE="/storage/emulated/0/Android/data/${PKG}/files/Maa"
TAIL_N=60
FOLLOW=false

while [ $# -gt 0 ]; do
  case "$1" in
    --tail) TAIL_N="$2"; shift ;;
    --follow|-f) FOLLOW=true ;;
    -h|--help)
      echo "Usage: $0 [--tail N] [--follow]"
      exit 0
      ;;
  esac
  shift
done

echo "=== MaaMeowPatch (logcat) ==="
$ADB logcat -d -s MaaMeowPatch:V 2>/dev/null | tail -n "$TAIL_N" || true

echo
echo "=== meow_log (最新) ==="
LATEST=$($ADB shell "ls -t ${BASE}/debug/gui/meow_log_*.log 2>/dev/null | head -1" | tr -d '\r')
echo "${LATEST:-"(none)"}"
if [ -n "${LATEST:-}" ]; then
  $ADB shell "cat '$LATEST'" | tail -n "$TAIL_N"
fi

echo
echo "=== asst.log 关键行 ==="
$ADB shell "su -c \"tail -c 200000 ${BASE}/debug/asst.log\"" 2>/dev/null \
  | strings \
  | grep -E "TaskChain|Parse |AD-|StageNavigation|No stage|Connected|force_stop|ExceededLimit|BattleStart|SideStory|红丝绒|演出|SubTaskError|ScreencapFailed" \
  | tail -n "$TAIL_N" || true

if [ "$FOLLOW" = true ]; then
  echo
  echo "=== follow logcat MaaMeowPatch ==="
  $ADB logcat -s MaaMeowPatch:V
fi
