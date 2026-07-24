#!/system/bin/sh
# 通用 RUN_TASKS：把 Maa-Meow 当 maa-cli（默认不杀游戏、支持连续任务）
#
# 用法:
#   sh /data/local/tmp/launch_cli_tasks.sh
#   MODE=fight STAGE_NAME=AD-1 sh /data/local/tmp/launch_cli_tasks.sh
#   MODE=copilot JOB=.../97725_AD-1.json sh /data/local/tmp/launch_cli_tasks.sh
#   TASKS_JSON='[{"type":"Fight","params":{...}}]' sh /data/local/tmp/launch_cli_tasks.sh
#   sh /data/local/tmp/launch_cli_tasks.sh --stop
#   sh /data/local/tmp/launch_cli_tasks.sh --status
#
# 关键环境变量:
#   AUTO_STARTUP=auto|true|false
#     auto(默认): 检测明日方舟主进程，已在则不加 StartUp；不在则前置 StartUp
#   FORCE_STOP_GAME=false|true   连接时是否杀游戏重拉（默认 false）
#   CLOSEDOWN_AFTER=false|true   任务链末尾 CloseDown 关游戏（默认 false）
#   MODE=fight|copilot           默认任务模板（未设 TASKS_* 时）
#   STAGE_NAME=AD-1
#   WITH_STARTUP=...             显式覆盖 AUTO_STARTUP 决策（一般别设）

PKG=com.aliothmoon.maameow
GAME_PKG=com.hypergryph.arknights
ACT=com.aliothmoon.maameow/.MainActivity
ACTION_RUN=com.tinkerlab.maameowpatch.action.RUN_TASKS
ACTION_STOP=com.tinkerlab.maameowpatch.action.STOP_TASKS
FLAGS=0x28800000
COPILOT_DIR="/storage/emulated/0/Android/data/${PKG}/files/Maa/copilot"
DEFAULT_JOB="${COPILOT_DIR}/97725_AD-1.json"

am unfreeze "$PKG" 2>/dev/null
dumpsys tv_input unfreeze "$PKG" 0 2>/dev/null

game_running() {
  # 主进程（排除 :pushcore）
  pidof "$GAME_PKG" >/dev/null 2>&1 && return 0
  # 部分 ROM pidof 不含子进程名；再扫一遍
  ps -A 2>/dev/null | grep -F "$GAME_PKG" | grep -v grep | grep -v ':pushcore' | grep -q .
}

print_status() {
  echo "=== meow / game ==="
  ps -A 2>/dev/null | grep -E "maameow|arknights" | grep -v grep || true
  echo "game_main_running=$(game_running && echo yes || echo no)"
  echo "=== latest meow_log ==="
  LATEST=$(ls -t /storage/emulated/0/Android/data/${PKG}/files/Maa/debug/gui/meow_log_*.log 2>/dev/null | head -1)
  echo "${LATEST:-"(none)"}"
  [ -n "$LATEST" ] && tail -8 "$LATEST" 2>/dev/null
}

if [ "${1:-}" = "--stop" ]; then
  am start -W -n "$ACT" -a "$ACTION_STOP" -f "$FLAGS"
  exit $?
fi

if [ "${1:-}" = "--status" ]; then
  print_status
  exit 0
fi

TASKS_PATH="${TASKS_PATH:-}"
TASKS_JSON="${TASKS_JSON:-}"
FORCE_STOP_GAME="${FORCE_STOP_GAME:-false}"
FORCE_START="${FORCE_START:-true}"
CLOSEDOWN_AFTER="${CLOSEDOWN_AFTER:-false}"
WAIT_READY_MS="${WAIT_READY_MS:-20000}"
STAGE_NAME="${STAGE_NAME:-AD-1}"
MODE="${MODE:-fight}"
JOB="${JOB:-$DEFAULT_JOB}"
AUTO_STARTUP="${AUTO_STARTUP:-auto}"
CLIENT_TYPE="${CLIENT_TYPE:-Official}"

# 解析是否前置 StartUp
if [ -n "${WITH_STARTUP:-}" ]; then
  :
elif [ "$AUTO_STARTUP" = "true" ]; then
  WITH_STARTUP=true
elif [ "$AUTO_STARTUP" = "false" ]; then
  WITH_STARTUP=false
else
  # auto
  if game_running; then
    WITH_STARTUP=false
    echo "AUTO_STARTUP=auto → game alive, skip StartUp"
  else
    WITH_STARTUP=true
    echo "AUTO_STARTUP=auto → game not running, prepend StartUp"
  fi
fi

# 有游戏时绝不默认 force_stop；显式 FORCE_STOP_GAME=true 才杀
if [ "$FORCE_STOP_GAME" != "true" ]; then
  FORCE_STOP_GAME=false
fi

if [ -z "$TASKS_JSON" ] && [ -z "$TASKS_PATH" ]; then
  STARTUP_JSON="{\"type\":\"StartUp\",\"params\":{\"client_type\":\"${CLIENT_TYPE}\",\"start_game_enabled\":true,\"account_name\":\"\"}}"
  if [ "$MODE" = "copilot" ]; then
    COPILOT_PARAMS="{\"copilot_list\":[{\"id\":0,\"filename\":\"${JOB}\",\"stage_name\":\"${STAGE_NAME}\",\"is_raid\":false}],\"formation\":true,\"ignore_requirements\":true,\"loop_times\":1,\"use_sanity_potion\":false,\"support_unit_usage\":0,\"add_trust\":false,\"user_additional\":[]}"
    TASK_ITEM="{\"type\":\"Copilot\",\"params\":${COPILOT_PARAMS}}"
  else
    # fight：走 StageNavigationTask + AD.json（红丝绒 AD-1 已验证）
    FIGHT_PARAMS="{\"stage\":\"${STAGE_NAME}\",\"medicine\":0,\"expiring_medicine\":0,\"stone\":0,\"times\":1,\"series\":0,\"report_to_penguin\":false,\"drone_usage\":\"NotUse\",\"penguin_id\":\"\",\"server\":\"CN\",\"client_type\":\"${CLIENT_TYPE}\",\"DrGrandet\":false}"
    TASK_ITEM="{\"type\":\"Fight\",\"params\":${FIGHT_PARAMS}}"
  fi
  if [ "$WITH_STARTUP" = "true" ]; then
    TASKS_JSON="[${STARTUP_JSON},${TASK_ITEM}]"
  else
    TASKS_JSON="[${TASK_ITEM}]"
  fi
fi

echo "params: MODE=$MODE STAGE=$STAGE_NAME WITH_STARTUP=$WITH_STARTUP FORCE_STOP_GAME=$FORCE_STOP_GAME CLOSEDOWN_AFTER=$CLOSEDOWN_AFTER"

CMD="am start -W -n $ACT -a $ACTION_RUN -f $FLAGS"
CMD="$CMD --ez extra_force_start $FORCE_START"
CMD="$CMD --ez extra_force_stop_game $FORCE_STOP_GAME"
CMD="$CMD --ez extra_closedown_after $CLOSEDOWN_AFTER"
CMD="$CMD --el extra_wait_ready_ms $WAIT_READY_MS"
CMD="$CMD --es extra_client_type $CLIENT_TYPE"

if [ -n "$TASKS_PATH" ]; then
  CMD="$CMD --es extra_tasks_path $TASKS_PATH"
else
  TMP=/data/local/tmp/maa_run_tasks.json
  printf '%s' "$TASKS_JSON" > "$TMP"
  CMD="$CMD --es extra_tasks_path $TMP"
fi

echo "exec: $CMD"
eval "$CMD"
