#!/bin/bash
# 部署红丝绒 AD.json 补丁到外部 overrides（优先）+ 兼容旧 Meow 内置路径
set -euo pipefail
ADB="${ADB:-adb -s emulator-5554}"
PKG=com.aliothmoon.maameow
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-$SCRIPT_DIR/../../resources/AD.json}"
MAA_ROOT="${MAA_ROOT:-/storage/emulated/0/maa}"
EXT_OVERRIDES="${EXT_OVERRIDES:-$MAA_ROOT/overrides}"
BASE="/storage/emulated/0/Android/data/${PKG}/files/Maa"

if [ ! -f "$SRC" ]; then
  echo "ERROR: AD.json not found: $SRC"
  exit 1
fi

python3 - <<PY
import json
d=json.load(open("$SRC"))
assert "AD-1" in d and "AD-OpenOpt" in d, "AD.json missing AD-1/AD-OpenOpt"
assert "AD-EX-1" in d and "AD-EXTab" in d, "AD.json missing AD-EX navigation"
assert "roi" in d["AD-EXTab"], "AD-EXTab missing roi"
print("local AD.json keys:", len(d), "AD-1/AD-EX OK, EXTab.roi=", d["AD-EXTab"]["roi"])
PY

echo "[1] external overrides (git-friendly): $EXT_OVERRIDES"
mkdir -p "$EXT_OVERRIDES/resource/tasks/Stages" 2>/dev/null || true
if [ -d "$EXT_OVERRIDES/resource/tasks/Stages" ] || mkdir -p "$EXT_OVERRIDES/resource/tasks/Stages"; then
  cp "$SRC" "$EXT_OVERRIDES/resource/tasks/Stages/AD.json"
  echo "  wrote $EXT_OVERRIDES/resource/tasks/Stages/AD.json"
else
  $ADB push "$SRC" /data/local/tmp/AD.json
  $ADB shell su -c "mkdir -p $EXT_OVERRIDES/resource/tasks/Stages && cp /data/local/tmp/AD.json $EXT_OVERRIDES/resource/tasks/Stages/AD.json"
fi

# 若已启用外部资源，写/保持 conf
if [ -d "$MAA_ROOT/MaaResource/resource" ]; then
  cat > "$MAA_ROOT/resource.conf" <<EOF
# MaaMeowPatch external resource (AsstLoadResource parent)
enabled=true
mode=${RESOURCE_MODE:-append}
path=$MAA_ROOT/MaaResource
overrides=$EXT_OVERRIDES
EOF
  echo "[2] resource.conf enabled → $MAA_ROOT/MaaResource"
fi

echo "[3] legacy Meow paths (compat) ..."
$ADB push "$SRC" /data/local/tmp/AD.json
$ADB shell su -c "cp /data/local/tmp/AD.json $BASE/resource/tasks/Stages/AD.json" || true
$ADB shell su -c "mkdir -p $BASE/overrides/resource/tasks/Stages && cp /data/local/tmp/AD.json $BASE/overrides/resource/tasks/Stages/AD.json" || true

# 同步补 EX stages（Fight analyze_stage_code 依赖）
# shellcheck source=../maa_lib.sh
source "$SCRIPT_DIR/../maa_lib.sh"
maa_ensure_ex_stages

echo "Done. 外部 overrides 已写；启用后用:"
echo "  bash $SCRIPT_DIR/../sync_external_resource.sh --reload"
echo "  或 meow_sse.sh body 带 resource_path / resource_overrides"
