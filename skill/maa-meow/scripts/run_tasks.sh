#!/system/bin/sh
# HTTP + SSE 任务入口（补丁 ≥1.2.10）。am start 仅用于拉起 Meow / HTTP。
#
# WAIT=1（默认）→ POST /v1/tasks?stream=1（SSE 关键日志到 done）
# WAIT=0        → POST /v1/tasks 立即返回
# --status / --stop / --reload-resource / --game start|close|kill
#
# 启游戏进 VD：任务链 StartUp（AUTO_STARTUP=auto）或 --game start（同 TaskExecutor）

PKG=com.aliothmoon.maameow
GAME_PKG=com.hypergryph.arknights
ACT=com.aliothmoon.maameow/.MainActivity
FLAGS=0x28800000
HTTP_BASE="${HTTP_BASE:-http://127.0.0.1:17878}"
COPILOT_DIR="/storage/emulated/0/Android/data/${PKG}/files/Maa/copilot"
DEFAULT_JOB="${COPILOT_DIR}/97725_AD-1.json"

am unfreeze "$PKG" 2>/dev/null
dumpsys tv_input unfreeze "$PKG" 0 2>/dev/null

have_curl() { command -v curl >/dev/null 2>&1; }

http_get() {
  local path="$1"
  if have_curl; then
    curl -sS --connect-timeout 3 --max-time 30 "${HTTP_BASE}${path}"
  else
    wget -qO- "${HTTP_BASE}${path}" 2>/dev/null
  fi
}

http_post() {
  local path="$1"
  local body="$2"
  if have_curl; then
    curl -sS --connect-timeout 3 --max-time 180 \
      -H 'Content-Type: application/json' -d "$body" "${HTTP_BASE}${path}"
  else
    echo "ERROR: 需要 curl" >&2
    return 1
  fi
}

json_field() {
  local json="$1" key="$2"
  printf '%s' "$json" | tr -d '\n' | sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" | head -1
}

ensure_http() {
  local i body
  am start -W -n "$ACT" -f "$FLAGS" >/dev/null 2>&1 || \
    am start -n "$ACT" -f "$FLAGS" >/dev/null 2>&1 || true
  for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    body=$(http_get /v1/health 2>/dev/null) || body=""
    if printf '%s' "$body" | grep -q '"ok"[[:space:]]*:[[:space:]]*true'; then
      echo "[http] ready ${HTTP_BASE}"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: HTTP 未就绪 ${HTTP_BASE}/v1/health（补丁≥1.2.9 / LSPosed？）"
  return 1
}

# 优先读 HTTP status.game.running（与 Meow 同步）；否则 pidof
game_running() {
  local body g
  if have_curl; then
    body=$(curl -sS --connect-timeout 1 --max-time 2 "${HTTP_BASE}/v1/status" 2>/dev/null) || body=""
    if [ -n "$body" ]; then
      g=$(printf '%s' "$body" | tr -d '\n' | sed -n 's/.*"game"[[:space:]]*:[[:space:]]*{\([^}]*\)}.*/\1/p' | head -1)
      if [ -n "$g" ]; then
        printf '%s' "$g" | grep -q '"running"[[:space:]]*:[[:space:]]*true' && return 0
        return 1
      fi
    fi
  fi
  pidof "$GAME_PKG" >/dev/null 2>&1 && return 0
  ps -A 2>/dev/null | grep -F "$GAME_PKG" | grep -v grep | grep -v ':pushcore' | grep -q .
}

infer_display_stage() {
  local base
  base=$(basename "$1" .json)
  echo "$base" | grep -oE '([A-Za-z]{1,6}-EX-[0-9]+|[A-Za-z]{1,6}-[0-9]+)' | tail -1
}

print_status() {
  ensure_http || return 1
  echo "=== GET /v1/status ==="
  http_get /v1/status
  echo
}

# 格式化 SSE 行：YYYY-MM-dd HH:mm:ss.SSS  EVENT  message
sse_format_print() {
  local ev="$1" data="$2"
  local time msg state active result tag
  time=$(printf '%s' "$data" | sed -n 's/.*"time"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
  [ -z "$time" ] && time=$(date '+%Y-%m-%d %H:%M:%S')
  tag=$(printf '%-10s' "$(printf '%s' "$ev" | tr 'a-z' 'A-Z')")
  case "$ev" in
    log|run_accepted|start_ok|session_end|terminal|stop_requested)
      msg=$(printf '%s' "$data" | sed -n 's/.*"msg"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
      printf '%s  %s  %s\n' "$time" "$tag" "$msg"
      ;;
    status|accepted|done)
      state=$(json_field "$data" state)
      active="?"
      case "$data" in
        *'"active":true'*|*'\"active\":true'*|*'"active": true'*) active=true ;;
        *'"active":false'*|*'\"active\":false'*|*'"active": false'*) active=false ;;
      esac
      result=$(json_field "$data" result)
      [ -z "$result" ] && result="-"
      task=$(json_field "$data" task_running)
      [ -z "$task" ] && task="-"
      game=$(printf '%s' "$data" | sed -n 's/.*"game"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
      [ -z "$game" ] && game=$(printf '%s' "$data" | sed -n 's/.*"game"[[:space:]]*:[[:space:]]*{\([^}]*\)}.*/\1/p' | head -1)
      [ -z "$game" ] && game="-"
      comp=$(json_field "$data" composition_state)
      [ -z "$comp" ] && comp="-"
      printf '%s  %s  state=%s active=%s result=%s task=%s game=%s comp=%s\n' \
        "$time" "$tag" "${state:-?}" "$active" "$result" "$task" "$game" "$comp"
      ;;
    error)
      msg=$(printf '%s' "$data" | sed -n 's/.*"error"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
      printf '%s  %s  %s\n' "$time" "$tag" "$msg"
      ;;
    hello)
      printf '%s  %s  sse ready\n' "$time" "$tag"
      ;;
    *)
      printf '%s  %s  %s\n' "$time" "$tag" "$data"
      ;;
  esac
}

run_tasks_sse() {
  local body="$1"
  local timeout_s="${TIMEOUT:-3600}"
  local timeout_ms=$((timeout_s * 1000))
  local path="/v1/tasks?stream=1&timeout_ms=${timeout_ms}"
  local tmp result r cur_ev=""
  have_curl || { echo "ERROR: SSE 需要 curl" >&2; return 1; }
  echo "$(date '+%Y-%m-%d %H:%M:%S')  SSE       POST ${HTTP_BASE}${path}"
  tmp=/data/local/tmp/maa_sse_$$.txt
  rm -f "$tmp"
  curl -N -sS --connect-timeout 5 --max-time "$((timeout_s + 30))" \
    -H 'Content-Type: application/json' \
    -H 'Accept: text/event-stream' \
    -d "$body" \
    "${HTTP_BASE}${path}" | tee "$tmp" | while IFS= read -r line; do
      case "$line" in
        "event: "*)
          cur_ev="${line#event: }"
          ;;
        "data: "*)
          sse_format_print "$cur_ev" "${line#data: }"
          ;;
        ": ping"*|": "*)
          ;;
        "")
          cur_ev=""
          ;;
      esac
    done
  result=$(grep -A1 '^event: done$' "$tmp" 2>/dev/null | sed -n 's/^data: //p' | tail -1)
  [ -z "$result" ] && result=$(grep '^data: ' "$tmp" 2>/dev/null | tail -1 | sed 's/^data: //')
  rm -f "$tmp" 2>/dev/null
  if printf '%s' "$result" | grep -qE '"error"[[:space:]]*:[[:space:]]*"(timeout|stream_timeout)"'; then
    # 流超时：任务未 stop，仅 SSE 结束
    echo "$(date '+%Y-%m-%d %H:%M:%S')  SSE       STREAM_TIMEOUT (task still running)"; return 124
  fi
  r=$(json_field "$result" result)
  echo "$(date '+%Y-%m-%d %H:%M:%S')  SSE       finished result=${r:-(none)}"
  case "$r" in SUCCESS) return 0 ;; STOPPED) return 2 ;; *) return 1 ;; esac
}

if [ "${1:-}" = "--stop" ]; then
  ensure_http || exit 1
  http_post /v1/stop '{}'; echo; exit $?
fi

if [ "${1:-}" = "--status" ] || [ "${1:-}" = "--poll-status" ]; then
  print_status; exit $?
fi

if [ "${1:-}" = "--game" ]; then
  ACTION="${2:-}"
  [ -n "$ACTION" ] || { echo "ERROR: --game start|close|kill"; exit 1; }
  ensure_http || exit 1
  BODY=$(printf '{"action":"%s","force_stop_game":%s,"client_type":"%s"}' \
    "$ACTION" "${FORCE_STOP_GAME:-false}" "${CLIENT_TYPE:-Official}")
  if [ "$ACTION" = "kill" ] || [ "${WAIT:-1}" = "0" ]; then
    http_post /v1/game "$BODY"; echo; exit $?
  fi
  # start/close 与 tasks 同路径 SSE（带时间格式化）
  run_tasks_sse_path="/v1/game?stream=1&timeout_ms=$(( ${TIMEOUT:-600} * 1000 ))"
  echo "$(date '+%Y-%m-%d %H:%M:%S')  SSE       POST ${HTTP_BASE}${run_tasks_sse_path} action=$ACTION"
  tmp=/data/local/tmp/maa_sse_game_$$.txt
  rm -f "$tmp"
  cur_ev=""
  curl -N -sS --connect-timeout 5 --max-time "$(( ${TIMEOUT:-600} + 30 ))" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d "$BODY" "${HTTP_BASE}${run_tasks_sse_path}" | tee "$tmp" | while IFS= read -r line; do
      case "$line" in
        "event: "*) cur_ev="${line#event: }" ;;
        "data: "*) sse_format_print "$cur_ev" "${line#data: }" ;;
        ": "*) ;;
        "") cur_ev="" ;;
      esac
    done
  rm -f "$tmp" 2>/dev/null
  exit $?
fi

if [ "${1:-}" = "--async" ]; then WAIT=0; shift; fi

if [ "${1:-}" = "--reload-resource" ]; then
  [ -n "${RESOURCE_PATH:-}" ] || { echo "ERROR: RESOURCE_PATH=..."; exit 1; }
  ensure_http || exit 1
  BODY=$(printf '{"resource_path":"%s","resource_mode":"%s","resource_overrides":"%s","reload":%s}' \
    "$RESOURCE_PATH" "${RESOURCE_MODE:-append}" "${RESOURCE_OVERRIDES:-}" "${RELOAD_RESOURCE:-false}")
  http_post /v1/resource "$BODY"; echo; exit $?
fi

TASKS_PATH="${TASKS_PATH:-}"
TASKS_JSON="${TASKS_JSON:-}"
FORCE_STOP_GAME="${FORCE_STOP_GAME:-false}"
FORCE_START="${FORCE_START:-true}"
CLOSEDOWN_AFTER="${CLOSEDOWN_AFTER:-false}"
WAIT_READY_MS="${WAIT_READY_MS:-15000}"
STAGE_NAME="${STAGE_NAME:-1-7}"
MODE="${MODE:-}"
JOB="${JOB:-$DEFAULT_JOB}"
AUTO_STARTUP="${AUTO_STARTUP:-auto}"
CLIENT_TYPE="${CLIENT_TYPE:-Official}"
COPILOT_NAV="${COPILOT_NAV:-1}"
FIGHT_MEDICINE="${FIGHT_MEDICINE:-0}"
USE_SANITY_POTION="${USE_SANITY_POTION:-false}"
RESOURCE_PATH="${RESOURCE_PATH:-}"
RESOURCE_MODE="${RESOURCE_MODE:-}"
RESOURCE_OVERRIDES="${RESOURCE_OVERRIDES:-}"
RELOAD_RESOURCE="${RELOAD_RESOURCE:-false}"
WAIT="${WAIT:-1}"
TIMEOUT="${TIMEOUT:-3600}"

if [ -z "$TASKS_JSON" ] && [ -z "$TASKS_PATH" ] && [ -z "$MODE" ]; then
  echo "ERROR: 需要 TASKS_JSON / TASKS_PATH，或 MODE=fight|copilot|operbox"
  exit 1
fi

# 先拉起 HTTP，再用 status.game 决定是否前置 StartUp（与 Intent 热启动语义一致）
ensure_http || exit 1

if [ -n "${WITH_STARTUP:-}" ]; then
  :
elif [ "$AUTO_STARTUP" = "true" ]; then
  WITH_STARTUP=true
elif [ "$AUTO_STARTUP" = "false" ]; then
  WITH_STARTUP=false
else
  if game_running; then
    WITH_STARTUP=false
    echo "AUTO_STARTUP=auto → game.running, skip StartUp"
  else
    WITH_STARTUP=true
    echo "AUTO_STARTUP=auto → game not running, prepend StartUp (进 VD)"
  fi
fi

[ "$FORCE_STOP_GAME" = "true" ] || FORCE_STOP_GAME=false

if [ -z "$TASKS_JSON" ] && [ -z "$TASKS_PATH" ]; then
  STARTUP_JSON="{\"type\":\"StartUp\",\"params\":{\"client_type\":\"${CLIENT_TYPE}\",\"start_game_enabled\":true,\"account_name\":\"\"}}"
  if [ "$MODE" = "operbox" ]; then
    TASK_ITEM='{"type":"OperBox","params":{"enable":true}}'
  elif [ "$MODE" = "copilot" ]; then
    if [ "$COPILOT_NAV" = "1" ] || [ "$COPILOT_NAV" = "true" ]; then
      INFER=$(infer_display_stage "$JOB" 2>/dev/null || true)
      NAV_STAGE="${STAGE_NAME}"
      [ -n "$INFER" ] && NAV_STAGE="$INFER"
      COPILOT_PARAMS="{\"copilot_list\":[{\"id\":0,\"filename\":\"${JOB}\",\"stage_name\":\"${NAV_STAGE}\",\"is_raid\":false}],\"formation\":true,\"ignore_requirements\":true,\"loop_times\":1,\"use_sanity_potion\":${USE_SANITY_POTION},\"support_unit_usage\":0,\"add_trust\":false,\"user_additional\":[]}"
    else
      COPILOT_PARAMS="{\"filename\":\"${JOB}\",\"skip_navigation\":true,\"formation\":true,\"ignore_requirements\":true,\"loop_times\":1,\"use_sanity_potion\":${USE_SANITY_POTION},\"support_unit_usage\":0,\"add_trust\":false,\"user_additional\":[]}"
    fi
    TASK_ITEM="{\"type\":\"Copilot\",\"params\":${COPILOT_PARAMS}}"
  else
    FIGHT_PARAMS="{\"stage\":\"${STAGE_NAME}\",\"medicine\":${FIGHT_MEDICINE},\"expiring_medicine\":0,\"stone\":0,\"times\":1,\"series\":0,\"report_to_penguin\":false,\"drone_usage\":\"NotUse\",\"penguin_id\":\"\",\"server\":\"CN\",\"client_type\":\"${CLIENT_TYPE}\",\"DrGrandet\":false}"
    TASK_ITEM="{\"type\":\"Fight\",\"params\":${FIGHT_PARAMS}}"
  fi
  if [ "$WITH_STARTUP" = "true" ]; then
    TASKS_JSON="[${STARTUP_JSON},${TASK_ITEM}]"
  else
    TASKS_JSON="[${TASK_ITEM}]"
  fi
fi

if [ -n "$TASKS_PATH" ] && [ -z "$TASKS_JSON" ]; then
  TASKS_JSON=$(cat "$TASKS_PATH")
fi

# 手写 TASKS_JSON 时也尊重 AUTO_STARTUP（与 Intent 热启动一致）
if [ "$WITH_STARTUP" = "true" ]; then
  if ! printf '%s' "$TASKS_JSON" | grep -qE '"type"[[:space:]]*:[[:space:]]*"StartUp"'; then
    STARTUP_JSON="{\"type\":\"StartUp\",\"params\":{\"client_type\":\"${CLIENT_TYPE}\",\"start_game_enabled\":true,\"account_name\":\"\"}}"
    case "$TASKS_JSON" in
      \[*) TASKS_JSON="[${STARTUP_JSON},${TASKS_JSON#\[}" ;;
      *) TASKS_JSON="[${STARTUP_JSON},${TASKS_JSON}]" ;;
    esac
    echo "AUTO_STARTUP → prepend StartUp"
  fi
fi

BODY=$(printf '{"tasks":%s,"force_start":%s,"force_stop_game":%s,"closedown_after":%s,"wait_ready_ms":%s,"client_type":"%s"' \
  "$TASKS_JSON" "$FORCE_START" "$FORCE_STOP_GAME" "$CLOSEDOWN_AFTER" "$WAIT_READY_MS" "$CLIENT_TYPE")
if [ -n "$RESOURCE_PATH" ]; then
  BODY="${BODY},\"resource_path\":\"${RESOURCE_PATH}\""
  [ -n "$RESOURCE_MODE" ] && BODY="${BODY},\"resource_mode\":\"${RESOURCE_MODE}\""
  [ -n "$RESOURCE_OVERRIDES" ] && BODY="${BODY},\"resource_overrides\":\"${RESOURCE_OVERRIDES}\""
  if [ "$RELOAD_RESOURCE" = "1" ] || [ "$RELOAD_RESOURCE" = "true" ]; then
    BODY="${BODY},\"reload_resource\":true"
  fi
fi
BODY="${BODY}}"

echo "params: MODE=${MODE:-(json)} WAIT=$WAIT TIMEOUT=$TIMEOUT STARTUP=${WITH_STARTUP:-n/a} RESOURCE=${RESOURCE_PATH:-(builtin)}"

if [ "$WAIT" = "0" ] || [ "$WAIT" = "false" ]; then
  echo "POST /v1/tasks (async)"
  http_post /v1/tasks "$BODY" || exit 1
  echo
  exit 0
fi

run_tasks_sse "$BODY"
exit $?
