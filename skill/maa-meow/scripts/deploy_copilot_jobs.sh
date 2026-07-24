#!/bin/bash
# 推送 Copilot 作业到手机 MAA copilot 目录，并生成 task_list.json / config.json
set -euo pipefail

ADB="${ADB:-adb -s emulator-5554}"
PKG=com.aliothmoon.maameow
REMOTE_DIR="/storage/emulated/0/Android/data/${PKG}/files/Maa/copilot"
SRC_DIR="${1:-/tmp/arknights-ad-jobs}"
MANIFEST="${2:-$SRC_DIR/manifest.json}"

if [ ! -d "$SRC_DIR" ]; then
  echo "ERROR: copilot source dir not found: $SRC_DIR"
  exit 1
fi

echo "[1/4] 创建手机 copilot 目录 ..."
$ADB shell "mkdir -p '$REMOTE_DIR'"

echo "[2/4] 推送作业 JSON ..."
$ADB push "$SRC_DIR/." "$REMOTE_DIR/"

echo "[3/4] 生成 task_list.json ..."
python3 << PYEOF
import json, os, glob

manifest_path = "$MANIFEST"
src_dir = "$SRC_DIR"
remote_dir = "$REMOTE_DIR"

order = [
    "AD-1","AD-2","AD-3","AD-4","AD-5","AD-6","AD-7","AD-8",
    "AD-EX-1","AD-EX-2","AD-EX-3","AD-EX-4","AD-EX-5","AD-EX-6","AD-EX-7","AD-EX-8",
]
manifest = json.load(open(manifest_path)) if os.path.exists(manifest_path) else {}
name_to_file = {}
for k, v in manifest.items():
    base = os.path.basename(v.get("file", ""))
    if base:
        name_to_file[k] = base

items = []
for stage in order:
    base = name_to_file.get(stage)
    if not base:
        cand = glob.glob(os.path.join(src_dir, f"*_{stage}.json"))
        if not cand:
            print(f"WARN: missing job for {stage}")
            continue
        base = os.path.basename(cand[0])
    prts_id = manifest.get(stage, {}).get("id", 0)
    items.append({
        "name": stage,
        "filePath": f"{remote_dir}/{base}",
        "isRaid": False,
        "copilotId": prts_id,
        "isChecked": True,
        "source": "web",
    })

config = {
    "formation": True,
    "addTrust": False,
    "ignoreRequirements": True,
    "useSanityPotion": False,
    "supportUnitUsage": 0,
    "useSupportUnit": False,
    "loopTimes": 1,
    "loop": False,
    "useFormation": False,
    "formationIndex": 1,
    "addUserAdditional": False,
    "userAdditional": "",
}

out_dir = "/tmp/maameow-copilot-deploy"
os.makedirs(out_dir, exist_ok=True)
json.dump(items, open(f"{out_dir}/task_list.json", "w"), ensure_ascii=False, indent=2)
json.dump(config, open(f"{out_dir}/config.json", "w"), ensure_ascii=False, indent=2)
print(f"task_list items: {len(items)}")
PYEOF

echo "[4/4] 推送清单与配置 ..."
$ADB push /tmp/maameow-copilot-deploy/task_list.json "$REMOTE_DIR/"
$ADB push /tmp/maameow-copilot-deploy/config.json "$REMOTE_DIR/"
echo "Done. Remote dir: $REMOTE_DIR"
