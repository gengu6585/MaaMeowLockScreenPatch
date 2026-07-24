#!/system/bin/sh
# 触发 LAUNCH_COPILOT（作业列表）；默认不杀游戏、不强制 StartUp
# 用法:
#   sh /data/local/tmp/launch_copilot.sh
#   WITH_STARTUP=auto FORCE_STOP_GAME=false CLOSEDOWN_AFTER=false sh ...

PKG=com.aliothmoon.maameow
GAME_PKG=com.hypergryph.arknights
ACT=com.aliothmoon.maameow/.MainActivity
ACTION=com.tinkerlab.maameowpatch.action.LAUNCH_COPILOT
COPILOT_DIR="/storage/emulated/0/Android/data/${PKG}/files/Maa/copilot"
TASK_LIST="${TASK_LIST:-$COPILOT_DIR/task_list.json}"
CONFIG="${CONFIG:-$COPILOT_DIR/config.json}"
TAB_INDEX="${TAB_INDEX:-0}"
FORCE_START="${FORCE_START:-true}"
FORCE_STOP_GAME="${FORCE_STOP_GAME:-false}"
CLOSEDOWN_AFTER="${CLOSEDOWN_AFTER:-false}"
AUTO_STARTUP="${AUTO_STARTUP:-auto}"
REQ_ID="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s)"

game_running() {
  pidof "$GAME_PKG" >/dev/null 2>&1 && return 0
  ps -A 2>/dev/null | grep -F "$GAME_PKG" | grep -v grep | grep -v ':pushcore' | grep -q .
}

if [ -n "${WITH_STARTUP:-}" ]; then
  :
elif [ "$AUTO_STARTUP" = "true" ]; then
  WITH_STARTUP=true
elif [ "$AUTO_STARTUP" = "false" ]; then
  WITH_STARTUP=false
else
  if game_running; then
    WITH_STARTUP=false
    echo "AUTO_STARTUP=auto → game alive, skip StartUp"
  else
    WITH_STARTUP=true
    echo "AUTO_STARTUP=auto → game not running, with StartUp"
  fi
fi

am unfreeze "$PKG" 2>/dev/null
dumpsys tv_input unfreeze "$PKG" 0 2>/dev/null

FLAGS=0x28800000
echo "LAUNCH_COPILOT withStartup=$WITH_STARTUP forceStopGame=$FORCE_STOP_GAME closedownAfter=$CLOSEDOWN_AFTER"
am start -W -n "$ACT" -a "$ACTION" -f "$FLAGS" \
  --es extra_task_list_path "$TASK_LIST" \
  --es extra_config_path "$CONFIG" \
  --ei extra_tab_index "$TAB_INDEX" \
  --ez extra_force_start "$FORCE_START" \
  --ez extra_with_startup "$WITH_STARTUP" \
  --ez extra_force_stop_game "$FORCE_STOP_GAME" \
  --ez extra_closedown_after "$CLOSEDOWN_AFTER" \
  --es extra_request_id "$REQ_ID"
