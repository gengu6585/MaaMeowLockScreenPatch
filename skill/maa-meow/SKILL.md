---
name: maa-meow
description: >-
  Maa-Meow：从官方集成协议查 type/params，拼 JSON 任务链，经 tutu 直连
  Meow HTTP/SSE（meow_sse.sh）下发。仅拉起用 adb。Agent：root@tutu.gugenzzz.top。
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

## 怎么编排任务

用户要做某事时：**先查官方协议拿 `type`/`params`，再拼进 `tasks` 一次下发**。不要臆造字段。

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

- `tasks`：**数组，按顺序执行**；尽量一次链跑完，勿中途 stop 再启。
- 每个元素：`type`（与集成文档一致）+ `params`（抄官方示例再改关卡/次数等）。
- 游戏已在前台且有 VD 时，可省略 `StartUp`。
- 活动关缺资源时再带 `resource_path` / `resource_overrides`（见 [EXTERNAL_RESOURCE.md](./references/EXTERNAL_RESOURCE.md)）。

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
