#!/bin/bash
# 根据手机已有作业重建 task_list：同关多文件时优先较大 PRTS id
set -euo pipefail
ADB="${ADB:-adb -s emulator-5554}"
PKG=com.aliothmoon.maameow
REMOTE="/storage/emulated/0/Android/data/${PKG}/files/Maa/copilot"

$ADB shell "ls '$REMOTE'/*_AD-*.json 2>/dev/null" | tr -d '\r' > /tmp/maa_copilot_files.txt || true
python3 <<'PY'
import json, os, re
from pathlib import Path
order = [
    "AD-1","AD-2","AD-3","AD-4","AD-5","AD-6","AD-7","AD-8",
    "AD-EX-1","AD-EX-2","AD-EX-3","AD-EX-4","AD-EX-5","AD-EX-6","AD-EX-7","AD-EX-8",
]
remote = "/storage/emulated/0/Android/data/com.aliothmoon.maameow/files/Maa/copilot"
files = Path("/tmp/maa_copilot_files.txt").read_text().strip().splitlines()
by_stage = {}
for f in files:
    f = f.strip()
    if not f:
        continue
    base = os.path.basename(f)
    m = re.search(r"(AD-(?:EX-)?\d+)", base)
    if not m:
        continue
    stage = m.group(1)
    m2 = re.match(r"(\d+)_", base)
    jid = int(m2.group(1)) if m2 else 0
    prev = by_stage.get(stage)
    if prev is None or jid >= prev[1]:
        by_stage[stage] = (base, jid)

items = []
for stage in order:
    if stage not in by_stage:
        print(f"WARN missing {stage}")
        continue
    base, jid = by_stage[stage]
    items.append({
        "name": stage,
        "filePath": f"{remote}/{base}",
        "isRaid": False,
        "copilotId": jid,
        "isChecked": True,
        "source": "web",
    })
config = {
    "formation": True, "addTrust": False, "ignoreRequirements": True,
    "useSanityPotion": False, "supportUnitUsage": 0, "useSupportUnit": False,
    "loopTimes": 1, "loop": False, "useFormation": False, "formationIndex": 1,
    "addUserAdditional": False, "userAdditional": "",
}
out = Path("/tmp/maameow-copilot-rebuild")
out.mkdir(exist_ok=True)
(out / "task_list.json").write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
(out / "config.json").write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"task_list items: {len(items)}")
for it in items:
    print(f"  {it['name']} -> {os.path.basename(it['filePath'])}")
PY

$ADB push /tmp/maameow-copilot-rebuild/task_list.json "$REMOTE/"
$ADB push /tmp/maameow-copilot-rebuild/config.json "$REMOTE/"
echo "Done."
