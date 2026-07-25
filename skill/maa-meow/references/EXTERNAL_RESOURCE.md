# 外部 Resource（请求参数门控）

## 规则

| 场景 | 行为 |
|---|---|
| 请求 **未**带 `resource_path` | Meow **内置**资源 |
| **带** `resource_path` | 本次 `AsstLoadResource(parent)` |
| Meow UI / 内部 reload | 始终内置，不粘滞 |

HTTP：`POST /v1/resource` 或 `POST /v1/tasks` body 内字段 `resource_path` / `resource_overrides`。

`parent` 下必须有 `resource/` 子目录。

## 目录布局

```
/storage/emulated/0/maa/
  MaaResource/                 # RESOURCE_PATH
    resource/
      …（MaaResource：stages/template/公招等）
      tasks/                   # 从 MAA 主仓稀疏同步（官方 tasks）
      stages.json
  overrides/                   # RESOURCE_OVERRIDES（最后加载，优先级最高）
    resource/tasks/Stages/AD.json   # 本地 EX 导航等补丁
  .meta/maa_resource.rev / maa_tasks.rev
  last_intent_resource.log     # 每次注入结果
```

说明：官方 [MaaResource](https://github.com/MaaAssistantArknights/MaaResource) **不含** `tasks/`；导航任务在 [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) 的 `resource/tasks/`。`sync_external_resource.sh` 会拼好两者，并把本地 `AD.json` **烘焙**进主树（避免大包 `LoadResource` 后 RemoteService `DeadObject` 导致 overrides 二次加载失败）。

## 同步

```bash
bash ~/.cursor/skills/maa-meow/scripts/sync_external_resource.sh          # 全量
bash ~/.cursor/skills/maa-meow/scripts/sync_external_resource.sh resource # 仅动态包
bash ~/.cursor/skills/maa-meow/scripts/sync_external_resource.sh tasks    # 仅官方 tasks
bash ~/.cursor/skills/maa-meow/scripts/sync_external_resource.sh overrides
bash ~/.cursor/skills/maa-meow/scripts/sync_external_resource.sh status
bash ~/.cursor/skills/maa-meow/scripts/sync_external_resource.sh verify
```

## 启用（必须带参）

```bash
curl -sS -H 'Content-Type: application/json' \
  -d '{"resource_path":"/storage/emulated/0/maa/MaaResource","resource_overrides":"/storage/emulated/0/maa/overrides"}' \
  http://127.0.0.1:17878/v1/resource

# 或与任务同发（body 内带 resource_path）
curl -N -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"resource_path":"/storage/emulated/0/maa/MaaResource","resource_overrides":"/storage/emulated/0/maa/overrides","tasks":[{"type":"Fight","params":{"stage":"AD-EX-2","times":1}}]}' \
  'http://127.0.0.1:17878/v1/tasks?stream=1'
```


| 字段 | 含义 |
|---|---|
| `resource_path` | **门控**；无此参 = 内置 |
| `resource_overrides` | 本地补丁 parent |
| `resource_mode` | `append`（默认）/ `replace` |
| `reload` | 注入前 `reset+ensureLoaded` |

## 如何确认「真的跑了外置」

1. **硬证据**：`/storage/emulated/0/maa/last_intent_resource.log` 出现  
   `LoadResource /storage/emulated/0/maa/MaaResource = true`
2. **logcat**：`adb logcat -s MaaMeowPatch:V` → `LoadResource(...)=true`
3. **行为**：asst 出现 `AD-EX-2@AD-EXOpenOpt` 等（须本次带了 `RESOURCE_OVERRIDES`）

```bash
cat /storage/emulated/0/maa/last_intent_resource.log
bash ~/.cursor/skills/maa-meow/scripts/sync_external_resource.sh verify
```
