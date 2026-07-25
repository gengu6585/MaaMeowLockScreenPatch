#!/bin/bash
# ## Agent 提示: 见 SKILL.md；主任务入口是 meow_sse.sh
#
# maa-screenshot.sh — 截 Maa-Meow 主屏 + 虚拟屏
# Usage:
#   ./maa-screenshot.sh                  # 截两张 + 拉回本地
#   ./maa-screenshot.sh --pull-only      # 只拉已有截图（不重新截）
#   ./maa-screenshot.sh --help           # 帮助

# NOTE: set -euo pipefail intentionally NOT used here.
# grep for "Virtual display" returns non-zero exit when no virtual display exists,
# which would crash the script under pipefail.

ADB="adb shell su -c"
PULL_DIR="${MAA_SCREENSHOT_DIR:-/root/maa-screenshots}"
mkdir -p "$PULL_DIR"

TIMESTAMP=$(date '+%Y%m%d_%H%M%S')

show_help() {
  sed -n '2,10p' "$0" | sed 's/^# //'
  exit 0
}

# ── 检测 Display ID ──────────────────────────────────
detect_displays() {
  local sf_out
  sf_out=$(adb shell 'su -c "dumpsys SurfaceFlinger --display-id"')

  # 物理屏: HWC display
  PHYSICAL_ID=$(echo "$sf_out" | grep "HWC display" | head -1 | awk '{print $2}')
  # 虚拟屏: Virtual display（取最后一个，通常就是 MAA 的）
  VIRTUAL_ID=$(echo "$sf_out" | grep "Virtual display" | tail -1 | awk '{print $2}')

  if [[ -z "$PHYSICAL_ID" ]]; then
    echo "❌ 未检测到物理屏"
    exit 1
  fi

  echo "📺 物理屏 ID: $PHYSICAL_ID"
  if [[ -n "$VIRTUAL_ID" ]]; then
    echo "🖥️  虚拟屏 ID: $VIRTUAL_ID"
  else
    echo "ℹ️  无虚拟屏（Maa-Meow 可能未运行）"
  fi
}

# ── 截指定屏 ──────────────────────────────────────────
screenshot_display() {
  local display_id="$1"
  local label="$2"
  local outfile="$3"

  echo "  → 截图 $label ..."
  adb shell "su -c \"screencap -d $display_id /sdcard/maa_${label}.png\"" 2>/dev/null
  adb pull "/sdcard/maa_${label}.png" "$outfile" 2>/dev/null
  local size
  size=$(stat -c%s "$outfile" 2>/dev/null || echo 0)
  if [[ "$size" -gt 1000 ]]; then
    echo "    ✓ ${label}: ${outfile} ($((size/1024)) KB)"
  else
    echo "    ⚠ ${label}: 文件过小 (${size}B)，可能截图失败"
  fi
}

# ── 主逻辑 ────────────────────────────────────────────
case "${1:-}" in
  --help|-h)
    show_help
    ;;
  --pull-only)
    echo "📂 当前截图目录: $PULL_DIR"
    ls -lh "$PULL_DIR"/*.png 2>/dev/null || echo "(空)"
    exit 0
    ;;
esac

echo "🔍 检测显示器..."
detect_displays

PHYSICAL_OUT="${PULL_DIR}/physical_${TIMESTAMP}.png"
VIRTUAL_OUT="${PULL_DIR}/virtual_${TIMESTAMP}.png"

echo ""
echo "📸 开始截图..."
screenshot_display "$PHYSICAL_ID" "physical" "$PHYSICAL_OUT"

if [[ -n "$VIRTUAL_ID" ]]; then
  screenshot_display "$VIRTUAL_ID" "virtual" "$VIRTUAL_OUT"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ 完成！"
echo "  物理屏: $PHYSICAL_OUT"
[[ -n "$VIRTUAL_ID" ]] && echo "  虚拟屏: $VIRTUAL_OUT"
echo "━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "当前 Display ID 信息（可用于更新 skill）："
echo "  PHYSICAL_ID=$PHYSICAL_ID"
echo "  VIRTUAL_ID=${VIRTUAL_ID:-N/A}"
