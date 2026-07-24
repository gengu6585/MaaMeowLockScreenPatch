#!/bin/bash
# MAA 运行环境诊断：进程 / VD / AD.json / 作业 / 最近错误
set -euo pipefail
ADB="${ADB:-adb -s emulator-5554}"
PKG=com.aliothmoon.maameow
GAME=com.hypergryph.arknights
BASE="/storage/emulated/0/Android/data/${PKG}/files/Maa"
FAIL=0

ok() { echo "  OK  $*"; }
bad() { echo "  BAD $*"; FAIL=1; }
warn() { echo " WARN $*"; }

echo "=== 1. 进程 ==="
$ADB shell "ps -A | grep -E 'maameow|arknights' | grep -v grep" || warn "无相关进程"
if $ADB shell "pidof $GAME" 2>/dev/null | grep -q '[0-9]'; then
  ok "游戏主进程 $GAME"
else
  bad "游戏主进程不在（:pushcore 不算）"
fi
if $ADB shell "pidof $PKG" 2>/dev/null | grep -q '[0-9]'; then
  ok "Meow 进程"
else
  warn "Meow 未运行（发 Intent 会拉起）"
fi

echo
echo "=== 2. 虚拟屏 ==="
VD=$($ADB shell su -c 'dumpsys SurfaceFlinger --display-id' 2>/dev/null | grep -i 'Virtual display' | head -1 || true)
if echo "$VD" | grep -qi virtual; then
  ok "$VD"
else
  bad "无 Virtual display — 需 StartUp / 跑过任务才会建 VD"
fi

echo
echo "=== 3. 补丁模块 ==="
VER=$($ADB shell dumpsys package com.tinkerlab.maameowpatch 2>/dev/null | grep versionName | head -1 || true)
echo "  $VER"
echo "$VER" | grep -q '1\.2' && ok "patch ≥1.2" || warn "建议升级到 1.2.x（RUN_TASKS）"

echo
echo "=== 4. AD.json（红丝绒导航）==="
AD="$BASE/resource/tasks/Stages/AD.json"
CNT=$($ADB shell "su -c \"grep -c AD-1 $AD 2>/dev/null || echo 0\"" | tr -d '\r')
if [ "${CNT:-0}" -ge 1 ] 2>/dev/null; then
  ok "resource AD.json 含 AD-1 (matches=$CNT)"
else
  bad "resource AD.json 缺 AD-1 → Fight 无法走 AD-Open；运行 ensure_ad_resources.sh"
fi
OV="$BASE/overrides/resource/tasks/Stages/AD.json"
if $ADB shell "su -c \"test -f $OV && echo yes\"" | grep -q yes; then
  ok "overrides AD.json 存在"
else
  warn "overrides 无 AD.json（可选，资源热更后可被覆盖）"
fi

echo
echo "=== 5. Copilot 作业 ==="
N=$($ADB shell "ls $BASE/copilot/*_AD-*.json 2>/dev/null | wc -l" | tr -d '\r ')
echo "  AD 作业文件数: $N"
$ADB shell "test -f $BASE/copilot/97725_AD-1.json && echo yes" | grep -q yes \
  && ok "97725_AD-1.json" || warn "缺默认 AD-1 作业"
$ADB shell "test -f $BASE/copilot/task_list.json && echo yes" | grep -q yes \
  && ok "task_list.json" || warn "缺 task_list.json"

echo
echo "=== 6. 设备脚本 ==="
for s in launch_cli_tasks.sh launch_copilot.sh; do
  if $ADB shell "test -x /data/local/tmp/$s && echo yes" | grep -q yes; then
    ok "/data/local/tmp/$s"
  else
    bad "缺 /data/local/tmp/$s — adb push skill/scripts/$s"
  fi
done

echo
echo "=== 7. 最近 meow / asst 异常 ==="
LATEST=$($ADB shell "ls -t $BASE/debug/gui/meow_log_*.log 2>/dev/null | head -1" | tr -d '\r')
if [ -n "$LATEST" ]; then
  echo "  latest: $LATEST"
  $ADB shell "grep -E 'ERROR|截图失败|游戏进程未启动' '$LATEST' 2>/dev/null | tail -5" || true
fi
$ADB shell "su -c \"tail -c 80000 $BASE/debug/asst.log\"" 2>/dev/null \
  | strings 2>/dev/null \
  | grep -E "No stage template|ScreencapFailed|screencap failed|SubTaskError" \
  | tail -8 || true

echo
echo "=== 8. Meow「结束后关游戏」提示 ==="
echo "  若任务结束游戏总消失：在 Meow 设置里关闭「任务结束后关闭游戏」"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "RESULT: PASS (无致命项)"
else
  echo "RESULT: FAIL (见 BAD)"
fi
exit "$FAIL"
