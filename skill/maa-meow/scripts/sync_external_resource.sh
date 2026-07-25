#!/bin/bash
# 同步可 git 管理的外置 MaaCore 资源（MaaResource 动态包 + 官方 tasks + 本地 overrides）。
#
# ## Agent 提示
# - 只维护磁盘文件；启用须 run_tasks / HTTP 带 RESOURCE_PATH。
# - MaaResource 仓库本身不含 tasks/；tasks 从 MaaAssistantArknights 稀疏拉取。
# - 验证：sync 后看 --status；跑任务后看 /sdcard/maa/last_intent_resource.log
# - 详见 references/EXTERNAL_RESOURCE.md
#
# ## 用法
#   bash sync_external_resource.sh              # 全量：resource + tasks + overrides
#   bash sync_external_resource.sh resource     # 仅 pull MaaResource
#   bash sync_external_resource.sh tasks        # 仅同步官方 tasks/stages
#   bash sync_external_resource.sh overrides    # 仅应用 skill/resources → overrides
#   bash sync_external_resource.sh stages-ex    # 仅补 AD-EX-1..8 到 stages.json
#   bash sync_external_resource.sh status
#   bash sync_external_resource.sh verify       # 结构检查 + 最近一次 Intent 注入记录
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SKILL_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MAA_ROOT="${MAA_ROOT:-/storage/emulated/0/maa}"
REPO_DIR="${REPO_DIR:-$MAA_ROOT/MaaResource}"
OVERRIDES_DIR="${OVERRIDES_DIR:-$MAA_ROOT/overrides}"
META_DIR="${META_DIR:-$MAA_ROOT/.meta}"
TASKS_SPARSE="${TASKS_SPARSE:-/tmp/maa-tasks-sparse}"
MAA_RESOURCE_URL="${MAA_RESOURCE_URL:-https://github.com/MaaAssistantArknights/MaaResource.git}"
MAA_CORE_URL="${MAA_CORE_URL:-https://github.com/MaaAssistantArknights/MaaAssistantArknights.git}"
LAST_LOAD_LOG="$MAA_ROOT/last_intent_resource.log"

cmd="${1:-all}"

mkdir -p "$MAA_ROOT" "$OVERRIDES_DIR/resource/tasks/Stages" "$META_DIR"
rm -f "$MAA_ROOT/resource.conf"  # 禁止旧粘滞 conf

sync_resource() {
  echo "[resource] MaaResource → $REPO_DIR"
  if [ -d "$REPO_DIR/.git" ]; then
    git -C "$REPO_DIR" pull --ff-only || git -C "$REPO_DIR" pull --rebase || true
  else
    [ -d "$REPO_DIR" ] && [ ! -d "$REPO_DIR/.git" ] && rm -rf "$REPO_DIR"
    git clone --depth 1 "$MAA_RESOURCE_URL" "$REPO_DIR"
  fi
  test -d "$REPO_DIR/resource" || { echo "ERROR: missing $REPO_DIR/resource"; exit 1; }
  git -C "$REPO_DIR" rev-parse --short HEAD | tee "$META_DIR/maa_resource.rev"
  chmod -R a+rX "$REPO_DIR/resource" 2>/dev/null || true
}

sync_tasks() {
  echo "[tasks] sparse MAA resource/tasks + stages.json"
  rm -rf "$TASKS_SPARSE"
  mkdir -p "$TASKS_SPARSE"
  (
    cd "$TASKS_SPARSE"
    git init -q
    git remote add origin "$MAA_CORE_URL"
    BR=$(git ls-remote --symref origin HEAD | awk '/^ref:/ {sub("refs/heads/","",$2); print $2; exit}')
    echo "  branch=$BR"
    git config core.sparseCheckout true
    printf '%s\n' 'resource/tasks/' 'resource/stages.json' > .git/info/sparse-checkout
    git pull --depth 1 origin "$BR"
  )
  test -d "$TASKS_SPARSE/resource/tasks" || { echo "ERROR: sparse tasks missing"; exit 1; }
  mkdir -p "$REPO_DIR/resource"
  rsync -a --delete "$TASKS_SPARSE/resource/tasks/" "$REPO_DIR/resource/tasks/"
  # stages：保留已有 EX 补丁条目
  if [ -f "$REPO_DIR/resource/stages.json" ]; then
    python3 - "$TASKS_SPARSE/resource/stages.json" "$REPO_DIR/resource/stages.json" <<'PY'
import json, sys
new_p, old_p = sys.argv[1], sys.argv[2]
new = json.load(open(new_p))
old = json.load(open(old_p)) if __import__("os").path.exists(old_p) else []
codes = {i.get("code") for i in new if isinstance(i, dict)}
extra = [i for i in old if isinstance(i, dict) and str(i.get("code","")).startswith("AD-EX-") and i.get("code") not in codes]
if extra:
    new.extend(extra)
    print("  kept local EX stages:", [x["code"] for x in extra])
json.dump(new, open(old_p, "w"), ensure_ascii=False, separators=(",", ":"))
print("  stages.json entries:", len(new))
PY
  else
    cp -f "$TASKS_SPARSE/resource/stages.json" "$REPO_DIR/resource/stages.json"
  fi
  git -C "$TASKS_SPARSE" rev-parse --short HEAD | tee "$META_DIR/maa_tasks.rev"
  chmod -R a+rX "$REPO_DIR/resource/tasks" "$REPO_DIR/resource/stages.json" 2>/dev/null || true
  echo "  AD.json in tasks: $(test -f "$REPO_DIR/resource/tasks/Stages/AD.json" && echo yes || echo NO)"
}

sync_overrides() {
  echo "[overrides] skill/resources → $OVERRIDES_DIR (+ bake into RESOURCE_PATH)"
  local src_dir="$SKILL_ROOT/resources"
  if [ -f "$src_dir/AD.json" ]; then
    mkdir -p "$OVERRIDES_DIR/resource/tasks/Stages"
    cp "$src_dir/AD.json" "$OVERRIDES_DIR/resource/tasks/Stages/AD.json"
    # 烘焙进主树：大包 LoadResource 后 RemoteService 偶发 DeadObject，双路径不可靠
    if [ -d "$REPO_DIR/resource/tasks/Stages" ]; then
      cp "$src_dir/AD.json" "$REPO_DIR/resource/tasks/Stages/AD.json"
      echo "  AD.json → overrides + baked into MaaResource/tasks"
    else
      echo "  AD.json → overrides only (tasks/ 尚未同步)"
    fi
  fi
  if [ -d "$src_dir/overrides" ]; then
    rsync -a "$src_dir/overrides/" "$OVERRIDES_DIR/"
    echo "  merged resources/overrides/"
  fi
  chmod -R a+rX "$OVERRIDES_DIR" "$REPO_DIR/resource/tasks" 2>/dev/null || true
}

ensure_stages_ex() {
  local p="$REPO_DIR/resource/stages.json"
  test -f "$p" || { echo "ERROR: no stages.json, run tasks first"; exit 1; }
  python3 - "$p" <<'PY'
import json, sys
p = sys.argv[1]
data = json.load(open(p))
codes = {item.get("code") for item in data if isinstance(item, dict)}
need = []
for i in range(1, 9):
    code = f"AD-EX-{i}"
    if code not in codes:
        need.append({
            "apCost": 36,
            "code": code,
            "dropInfos": [
                {"dropType": "NORMAL_DROP", "itemId": "30073"},
                {"dropType": "FURNITURE", "itemId": "furni"},
            ],
            "stageId": f"act43side_ex0{i}_rep",
        })
if not need:
    print("[stages-ex] already ok")
else:
    data.extend(need)
    json.dump(data, open(p, "w"), ensure_ascii=False, separators=(",", ":"))
    print("[stages-ex] appended", [x["code"] for x in need])
PY
}

print_status() {
  echo "=== external resource status ==="
  echo "MAA_ROOT=$MAA_ROOT"
  echo "RESOURCE_PATH=$REPO_DIR"
  echo "RESOURCE_OVERRIDES=$OVERRIDES_DIR"
  echo "maa_resource.rev=$(cat "$META_DIR/maa_resource.rev" 2>/dev/null || echo '?')"
  echo "maa_tasks.rev=$(cat "$META_DIR/maa_tasks.rev" 2>/dev/null || echo '?')"
  echo "has resource/=$(test -d "$REPO_DIR/resource" && echo yes || echo no)"
  echo "has tasks/=$(test -d "$REPO_DIR/resource/tasks" && echo yes || echo no)"
  echo "upstream AD-EX-2=$(python3 -c "import json;d=json.load(open('$REPO_DIR/resource/tasks/Stages/AD.json'));print('AD-EX-2' in d)" 2>/dev/null || echo missing)"
  echo "override AD-EX-2=$(python3 -c "import json;d=json.load(open('$OVERRIDES_DIR/resource/tasks/Stages/AD.json'));print('AD-EX-2' in d)" 2>/dev/null || echo missing)"
  if [ -f "$LAST_LOAD_LOG" ]; then
    echo "--- last_intent_resource.log ---"
    cat "$LAST_LOAD_LOG"
  else
    echo "last_intent_resource.log: (none — 尚未用 RESOURCE_PATH 请求 注入，或补丁<1.2.5)"
  fi
  echo "启用:"
  echo "  curl -sS -X POST http://127.0.0.1:17878/v1/resource -H 'Content-Type: application/json' \\"
  echo "    -d '{\"resource_path\":\"$REPO_DIR\",\"resource_overrides\":\"$OVERRIDES_DIR\"}'"
}

verify() {
  local err=0
  test -d "$REPO_DIR/resource" || { echo "FAIL: no resource/"; err=1; }
  test -d "$REPO_DIR/resource/tasks" || { echo "FAIL: no tasks/ (run: $0 tasks)"; err=1; }
  test -f "$REPO_DIR/resource/stages.json" || { echo "FAIL: no stages.json"; err=1; }
  test -f "$OVERRIDES_DIR/resource/tasks/Stages/AD.json" || { echo "WARN: no override AD.json"; }
  if [ -f "$LAST_LOAD_LOG" ] && grep -q "LoadResource $REPO_DIR = true" "$LAST_LOAD_LOG" 2>/dev/null; then
    echo "OK: last LoadResource loaded RESOURCE_PATH"
  elif [ -f "$LAST_LOAD_LOG" ] && grep -q "LoadResource .*= true" "$LAST_LOAD_LOG"; then
    echo "OK: last LoadResource LoadResource succeeded (see log)"
    cat "$LAST_LOAD_LOG"
  else
    echo "INFO: 无成功注入记录；POST /v1/resource 或 meow_sse body 带 resource_path 后再 verify"
  fi
  # 行为证明提示：官方 tasks AD 通常无 EX，导航 EX 依赖 overrides
  python3 - <<PY || true
import json, os
up="$REPO_DIR/resource/tasks/Stages/AD.json"
ov="$OVERRIDES_DIR/resource/tasks/Stages/AD.json"
if os.path.isfile(up) and os.path.isfile(ov):
  u,o=json.load(open(up)),json.load(open(ov))
  print("upstream AD-EX keys:", sorted(k for k in u if "EX" in k)[:10] or "(none)")
  print("override  AD-EX keys:", sorted(k for k in o if "EX" in k)[:10] or "(none)")
  if "AD-EX-2" not in u and "AD-EX-2" in o:
    print("NOTE: EX 导航任务仅在 overrides；请求必须带 RESOURCE_OVERRIDES 才会盖过内置")
PY
  return "$err"
}

case "$cmd" in
  all)
    sync_resource
    sync_tasks
    ensure_stages_ex
    sync_overrides
    print_status
    ;;
  resource) sync_resource ;;
  tasks) sync_tasks; ensure_stages_ex ;;
  overrides) sync_overrides ;;
  stages-ex) ensure_stages_ex ;;
  status) print_status ;;
  verify) verify ;;
  -h|--help) sed -n '2,20p' "$0" ;;
  *) echo "unknown: $cmd (try all|resource|tasks|overrides|status|verify)"; exit 1 ;;
esac
