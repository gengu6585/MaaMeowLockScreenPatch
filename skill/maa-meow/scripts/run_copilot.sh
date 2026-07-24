#!/bin/bash
# 一键：prts 下载 → 推送手机 → 触发 Copilot 任务链
# 依赖: MaaMeowLockScreenPatch v1.1+ 已安装且 Vector scope 已配置
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb -s emulator-5554}"
OUT_DIR="${OUT_DIR:-/tmp/arknights-ad-jobs}"
ACTIVITY="${ACTIVITY:-act43side}"
PRESET="${PRESET:-hot}"
DOWNLOAD_ONLY=false
DEPLOY_ONLY=false
LAUNCH_ONLY=false

usage() {
  sed -n '2,8p' "$0" | sed 's/^# //'
  echo ""
  echo "Options:"
  echo "  --download-only   只从 prts 下载"
  echo "  --deploy-only     只推送已有 JSON（跳过下载）"
  echo "  --launch-only     只触发 LAUNCH_COPILOT"
  echo "  --preset hot|tryuhark"
  echo "  --out-dir PATH"
  echo "  --activity PREFIX   默认 act43side（红丝绒）"
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --download-only) DOWNLOAD_ONLY=true ;;
    --deploy-only) DEPLOY_ONLY=true ;;
    --launch-only) LAUNCH_ONLY=true ;;
    --preset) PRESET="$2"; shift ;;
    --out-dir) OUT_DIR="$2"; shift ;;
    --activity) ACTIVITY="$2"; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown: $1"; usage ;;
  esac
  shift
done

if [ "$LAUNCH_ONLY" = false ] && [ "$DEPLOY_ONLY" = false ]; then
  echo "=== [1/3] 从 prts.maa.plus 下载作业 ==="
  python3 "$SCRIPT_DIR/download_prts_copilot.py" \
    --activity "$ACTIVITY" --preset "$PRESET" --out-dir "$OUT_DIR"
fi

if [ "$DOWNLOAD_ONLY" = true ]; then
  echo "Download only — done."
  exit 0
fi

if [ "$LAUNCH_ONLY" = false ]; then
  echo "=== [2/3] 推送到手机 copilot 目录 ==="
  bash "$SCRIPT_DIR/deploy_copilot_jobs.sh" "$OUT_DIR"
fi

if [ "$DEPLOY_ONLY" = true ]; then
  echo "Deploy only — done."
  exit 0
fi

echo "=== [3/3] 触发 Copilot（虚拟屏，AUTO_STARTUP，不杀游戏）==="
$ADB shell su -c 'AUTO_STARTUP=auto FORCE_STOP_GAME=false CLOSEDOWN_AFTER=false sh /data/local/tmp/launch_copilot.sh'

echo ""
echo "验证 logcat（最多等 10s）："
timeout 10 $ADB logcat -d -s MaaMeowPatch:* 2>/dev/null | tail -15 || true
echo ""
echo "截图: bash $SCRIPT_DIR/maa-screenshot.sh"
