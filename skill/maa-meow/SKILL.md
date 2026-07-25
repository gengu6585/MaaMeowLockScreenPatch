---
name: maa-meow
description: >-
  明日方舟（Arknights）游戏自动化：刷关、剿灭、活动/EX 首通与抄作业、公招、
  基建、领奖、肉鸽等。经手机容器 tutu 上的 Maa-Meow HTTP/SSE 下发任务。
  用户提到明日方舟、MAA、Meow、刷图、剿灭、红丝绒、Copilot/作业时使用。
---

# Maa-Meow

## 环境

| | |
|---|---|
| Agent | `root@tutu.gugenzzz.top` → `/root/.cursor/skills/maa-meow/` |
| ADB | 仅拉起 Meow |
| API | `http://127.0.0.1:17878`（容器直连，不经 adb） |
| 补丁 | ≥ **1.2.15** |

## 硬约束

1. `force_stop_game=false`、`closedown_after=false`
2. 禁止随意 `force-stop` Meow
3. 任务用 **JSON 字符串** 直传（不要为每次请求写临时文件）
4. `resource_path` 仅在 body 里带时才注入外置资源
5. **禁止向本 skill 目录写入临时脚本/日志/JSON**（含 `scripts/`、`scripts/examples/`）。编排调试用的一次性脚本只放在**当前工作区**或 `/tmp`；skill 目录仅保留仓库维护的必要工具
6. **多关卡/多任务必须一次编排进同一个 `tasks` 数组**，用一次 `meow_sse.sh` 下发并跟着 SSE 看进度。禁止为串关自写 `for`/`while` shell（会丢掉实时 SSE，也无法在出错时及时停）。
7. **长任务观察**：优先前台跑 `meow_sse.sh`（实时行输出）。若必须后台：每隔 **20–60s** 查一次 `/v1/status` 或 `/v1/events`（或读 SSE 增量），**禁止**一次 `wait`/`sleep` 数十分钟；出现 `任务出错` / `result=FAILED` / 长时间无新日志要立刻读日志并决定 `stop` 或改 params 重试。

## 怎么编排任务

用户要做某事时：**先查官方协议拿 `type`/`params`，再拼进同一个 `tasks` 一次下发**。不要臆造字段，不要拆成多个临时脚本。

### 1. 查官方文档

| 文档 | URL | 何时看 |
|---|---|---|
| **集成协议（权威）** | https://docs.maa.plus/zh-cn/protocol/integration.html | 所有任务的 `type` 与 `params` |
| 作业协议 | https://docs.maa.plus/zh-cn/protocol/copilot-schema.html | Copilot 作业文件结构 |
| 基建排班 | https://docs.maa.plus/zh-cn/protocol/base-scheduling-schema.html | 自定义基建 JSON |
| 回调 | https://docs.maa.plus/zh-cn/protocol/callback-schema.html | 日志/进度含义 |

本仓摘要（能不能练级/专精等）：[references/MAA_TASKS.md](./references/MAA_TASKS.md)。  
读文档流程示例：[references/OFFICIAL_DOCS.md](./references/OFFICIAL_DOCS.md)。

### 2. 拼 body

```json
{
  "force_stop_game": false,
  "closedown_after": false,
  "tasks": [
    {"type": "StartUp", "params": {"client_type": "Official", "start_game_enabled": true}},
    {"type": "Award", "params": {"enable": true}},
    {"type": "Fight", "params": {"stage": "CE-6", "medicine": 1, "times": 3}}
  ]
}
```

- `tasks`：**数组，按顺序执行**；多关卡就写多个元素（或一个 `Copilot` + `copilot_list`），一次链跑完。
- 每个元素：`type`（与集成文档一致）+ `params`（抄官方示例再改关卡/次数等）。
- 游戏已在前台且有 VD 时，可省略 `StartUp`。
- 活动关缺资源时再带 `resource_path` / `resource_overrides`（见 [EXTERNAL_RESOURCE.md](./references/EXTERNAL_RESOURCE.md)）。
- Meow 常会在单个 `Fight` 失败后**继续**跑 `tasks` 里后续项（已实测：EX-4 出错仍会开 EX-5）。但无代理时每关都会在 UsePrts 失败——首通请改用下面的 `copilot_list`，或单关 `Fight` 导航 + `skip_navigation` Copilot。

### 多关卡首通示例（红丝绒 EX-4～EX-8）

未首通 / 无代理：用**一个** `Copilot` + `copilot_list`（一次 JSON，不要 shell 循环）：

```bash
COPILOT=/storage/emulated/0/Android/data/com.aliothmoon.maameow/files/Maa/copilot
bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh "{
  \"force_stop_game\": false,
  \"closedown_after\": false,
  \"resource_path\": \"/storage/emulated/0/maa/MaaResource\",
  \"resource_overrides\": \"/storage/emulated/0/maa/overrides\",
  \"tasks\": [
    {\"type\": \"StartUp\", \"params\": {\"client_type\": \"Official\", \"start_game_enabled\": true}},
    {
      \"type\": \"Copilot\",
      \"params\": {
        \"formation\": true,
        \"ignore_requirements\": true,
        \"loop_times\": 1,
        \"use_sanity_potion\": true,
        \"copilot_list\": [
          {\"filename\": \"${COPILOT}/96315_AD-EX-4.json\", \"stage_name\": \"AD-EX-4\"},
          {\"filename\": \"${COPILOT}/96316_AD-EX-5.json\", \"stage_name\": \"AD-EX-5\"},
          {\"filename\": \"${COPILOT}/96317_AD-EX-6.json\", \"stage_name\": \"AD-EX-6\"},
          {\"filename\": \"${COPILOT}/96318_AD-EX-7.json\", \"stage_name\": \"AD-EX-7\"},
          {\"filename\": \"${COPILOT}/97884_AD-EX-8.json\", \"stage_name\": \"AD-EX-8\"}
        ]
      }
    }
  ]
}"
```

已首通有代理后的日常刷图再用多个 `Fight`。`copilot_list` 导航不稳时：单关 `Fight` 导航 → `Copilot` + `skip_navigation`。

### 3. 下发

```bash
# health 不通时才拉起
adb -s emulator-5554 shell su -c \
  'am start -W -n com.aliothmoon.maameow/.MainActivity -f 0x28800000'

bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh '{
  "force_stop_game": false,
  "closedown_after": false,
  "tasks": [
    {"type": "Award", "params": {"enable": true}},
    {"type": "Recruit", "params": {"enable": true, "refresh": true, "times": 4}},
    {"type": "Infrast", "params": {
      "enable": true, "mode": 0,
      "facility": ["Control","Mfg","Trade","Power","Reception","Office","Dorm"],
      "threshold": 0.3
    }}
  ]
}'

# 等价：stdin
bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh <<'EOF'
{"force_stop_game":false,"closedown_after":false,"tasks":[{"type":"Fight","params":{"stage":"CE-6","medicine":1,"times":3}}]}
EOF
```

停 / 状态：

```bash
curl -sS -X POST http://127.0.0.1:17878/v1/stop
curl -sS http://127.0.0.1:17878/v1/status
```

### SSE 超时

`timeout_ms`（默认 900000）只结束 **SSE 流**，`done.error=stream_timeout` 且 `task_stopped=false`。  
**不会** stop 已在跑的任务；客户端断开同理。要停任务必须显式 `POST /v1/stop`。

## EX 首通（特例）

普通刷图按上一节 `Fight` 即可。EX **首通**（无代理）用两段：

1. `Fight` + 已解锁关名（如 `AD-EX-2`）导航到备战（常在 UsePrts 失败，属预期）
2. 可选 `POST /v1/stop`
3. `Copilot` + `skip_navigation: true` + 作业 `filename`（先用 `search_prts_jobs.py` 下到 copilot 目录）

```bash
bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh '{
  "force_stop_game": false,
  "closedown_after": false,
  "resource_path": "/storage/emulated/0/maa/MaaResource",
  "resource_overrides": "/storage/emulated/0/maa/overrides",
  "tasks": [{
    "type": "Copilot",
    "params": {
      "filename": "/storage/emulated/0/Android/data/com.aliothmoon.maameow/files/Maa/copilot/96311_AD-EX-2.json",
      "skip_navigation": true,
      "formation": true,
      "ignore_requirements": true,
      "loop_times": 1,
      "use_sanity_potion": true
    }
  }]
}'
```

| 勿用 | 用 |
|---|---|
| 未解锁的 `AD-EX-N`（空滑） | 已解锁关名 |
| Copilot 关卡导航 | `skip_navigation: true` |

## HTTP

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/health` `/v1/status` `/v1/events` | 探活 / 状态 / 事件 |
| POST | `/v1/tasks?stream=1` | SSE（流超时不 stop 任务） |
| POST | `/v1/stop` `/v1/resource` `/v1/game` | 停 / 资源 / 游戏 |

## 工具

| | |
|---|---|
| `meow_sse.sh` | JSON 字符串 → 实时 SSE |
| `search_prts_jobs.py` | 搜/下作业 |
| `sync_external_resource.sh` | 外置 resource |
| `maa-screenshot.sh` | 截图 |

排错 → [references/TROUBLESHOOTING.md](./references/TROUBLESHOOTING.md)。  
编排补充 → [references/TASK_ORCHESTRATION.md](./references/TASK_ORCHESTRATION.md)。
