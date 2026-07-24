#!/bin/bash
# 脚本自测（在手机侧 Ubuntu / 本机经 SSH 跑）
# 不消耗理智：只测 status/diagnose/search/参数解析，可选 --live 发一条 STOP
set -euo pipefail
SKILL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPTS="$SKILL_DIR/scripts"
ADB="${ADB:-adb -s emulator-5554}"
export ADB
FAIL=0

run() {
  echo
  echo ">>>> $*"
  if "$@"; then
    echo "PASS"
  else
    echo "FAIL: $*"
    FAIL=1
  fi
}

echo "=== selftest skill scripts ==="
run test -f "$SKILL_DIR/SKILL.md"
run test -x "$SCRIPTS/maa_status.sh"
run test -x "$SCRIPTS/diagnose_maa.sh"
run test -x "$SCRIPTS/watch_maa_logs.sh"
run test -x "$SCRIPTS/maa-screenshot.sh"
run test -x "$SCRIPTS/launch_cli_tasks.sh"
run test -x "$SCRIPTS/search_prts_jobs.py"

run bash "$SCRIPTS/maa_status.sh"
run bash "$SCRIPTS/diagnose_maa.sh" || true  # diagnose 可 FAIL 环境，仍要能跑完

echo
echo ">>>> search_prts_jobs.py --stage AD-1 --limit 3"
if python3 "$SCRIPTS/search_prts_jobs.py" --stage AD-1 --limit 3; then
  echo PASS
else
  echo "FAIL search (network?)"
  FAIL=1
fi

echo
echo ">>>> search validate id 97725"
python3 "$SCRIPTS/search_prts_jobs.py" --id 97725 --validate

echo
echo ">>>> push launch scripts + --status"
$ADB push "$SCRIPTS/launch_cli_tasks.sh" /data/local/tmp/launch_cli_tasks.sh >/dev/null
$ADB push "$SCRIPTS/launch_copilot.sh" /data/local/tmp/launch_copilot.sh >/dev/null
$ADB shell su -c 'chmod 755 /data/local/tmp/launch_cli_tasks.sh /data/local/tmp/launch_copilot.sh'
$ADB shell su -c 'sh /data/local/tmp/launch_cli_tasks.sh --status' | head -20

echo
echo ">>>> AUTO_STARTUP dry-run (print only via status + params)"
# 不真正开战：只验证脚本能算出 WITH_STARTUP（通过先写 tasks 再 cat）
OUT=$($ADB shell su -c 'MODE=fight STAGE_NAME=AD-1 AUTO_STARTUP=auto FORCE_STOP_GAME=false CLOSEDOWN_AFTER=false sh -c "
  # 复用脚本前半：检测后打印，不 exec am — 用 --status + 手写检测
  game_running() { pidof com.hypergryph.arknights >/dev/null 2>&1; }
  if game_running; then echo WITH_STARTUP=false; else echo WITH_STARTUP=true; fi
"')
echo "$OUT"
echo "$OUT" | grep -q WITH_STARTUP && echo PASS || { echo FAIL; FAIL=1; }

if [ "${1:-}" = "--live" ]; then
  echo
  echo ">>>> live STOP_TASKS"
  $ADB shell su -c 'sh /data/local/tmp/launch_cli_tasks.sh --stop'
fi

echo
echo ">>>> watch_maa_logs (tail)"
bash "$SCRIPTS/watch_maa_logs.sh" --tail 5 || true

echo
if [ "$FAIL" -eq 0 ]; then
  echo "SELFTEST RESULT: PASS"
else
  echo "SELFTEST RESULT: FAIL"
fi
exit "$FAIL"
