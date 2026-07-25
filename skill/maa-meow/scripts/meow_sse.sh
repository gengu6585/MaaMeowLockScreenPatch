#!/bin/bash
# tutu：直连 Meow SSE + 行缓冲格式化。仅 health 不通时 adb am start。
#
#   bash meow_sse.sh '{"tasks":[...]}'
#   bash meow_sse.sh -d '{"tasks":[...]}'          # 同上
#   bash meow_sse.sh <<'EOF'                       # stdin JSON
#   {"tasks":[...]}
#   EOF
#   TIMEOUT_MS=3600000 bash meow_sse.sh '...'      # 仅 SSE 流超时，不 stop 任务
set -euo pipefail

if [ -z "${MEOW_SSE_LINEBUF:-}" ] && [ ! -t 1 ] && command -v stdbuf >/dev/null 2>&1; then
  export MEOW_SSE_LINEBUF=1
  exec stdbuf -oL -eL bash "$0" "$@"
fi

ADB="${ADB:-adb -s emulator-5554}"
HTTP_BASE="${HTTP_BASE:-http://127.0.0.1:17878}"
TIMEOUT_MS="${TIMEOUT_MS:-900000}"

json_field() {
  printf '%s' "$1" | tr -d '\n' | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" | head -1
}

sse_print() {
  local ev="$1" data="$2" time msg state active result tasks tag
  time=$(printf '%s' "$data" | sed -n 's/.*"time"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
  [ -z "$time" ] && time=$(date '+%Y-%m-%d %H:%M:%S')
  tag=$(printf '%-10s' "$(printf '%s' "$ev" | tr 'a-z' 'A-Z')")
  case "$ev" in
    log|run_accepted|start_ok|session_end|terminal|stop_requested)
      msg=$(printf '%s' "$data" | sed -n 's/.*"msg"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
      printf '%s  %s  %s\n' "$time" "$tag" "$msg"
      ;;
    status|accepted|done)
      state=$(json_field "$data" state); [ -z "$state" ] && state="?"
      active="?"
      case "$data" in
        *'"active":true'*) active=true ;;
        *'"active":false'*) active=false ;;
      esac
      result=$(json_field "$data" result); [ -z "$result" ] && result="-"
      tasks=$(json_field "$data" tasks); [ -z "$tasks" ] && tasks="-"
      printf '%s  %s  state=%s active=%s result=%s tasks=%s\n' \
        "$time" "$tag" "$state" "$active" "$result" "$tasks"
      ;;
    error)
      msg=$(printf '%s' "$data" | sed -n 's/.*"error"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
      printf '%s  %s  %s\n' "$time" "$tag" "$msg"
      ;;
    hello) printf '%s  %s  sse ready\n' "$time" "$tag" ;;
    *)
      msg=$(printf '%s' "$data" | sed -n 's/.*"msg"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)
      printf '%s  %s  %s\n' "$time" "$tag" "${msg:-$data}"
      ;;
  esac
}

BODY=""
if [ "${1:-}" = "-d" ] || [ "${1:-}" = "--data" ]; then
  BODY="${2:-}"
elif [ $# -ge 1 ]; then
  BODY="$1"
elif [ ! -t 0 ]; then
  BODY=$(cat)
fi

# 去掉首尾空白
BODY=$(printf '%s' "$BODY" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
case "$BODY" in
  \{*|\[*) ;;
  *)
    echo "usage: $0 '{\"tasks\":[...]}'" >&2
    echo "       $0 -d '{\"tasks\":[...]}'" >&2
    echo "       $0 <<'EOF'  ...json...  EOF" >&2
    exit 1
    ;;
esac

if [ "${SKIP_START:-0}" != "1" ]; then
  if ! curl -sS --connect-timeout 1 --max-time 2 "$HTTP_BASE/v1/health" >/dev/null 2>&1; then
    $ADB shell su -c \
      'am start -W -n com.aliothmoon.maameow/.MainActivity -f 0x28800000 >/dev/null' || true
    sleep 1
  fi
fi

URL="${HTTP_BASE}/v1/tasks?stream=1&timeout_ms=${TIMEOUT_MS}"
echo "$(date '+%Y-%m-%d %H:%M:%S')  SSE  POST $URL" >&2

cur_ev=""
set +e
# timeout_ms 只限制 SSE 流；服务端不会因此 stop 任务
curl -N --no-buffer -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d "$BODY" \
  "$URL" \
| while IFS= read -r line || [ -n "$line" ]; do
  line=$(printf '%s' "$line" | tr -d '\r')
  case "$line" in
    "event: "*) cur_ev="${line#event: }" ;;
    "data: "*) sse_print "$cur_ev" "${line#data: }" ;;
    ": ping"*|": "*) ;;
    "") cur_ev="" ;;
  esac
done
exit "${PIPESTATUS[0]}"
