---
name: maa-meow
description: >-
  Maa-Meow：tutu 直连 Meow HTTP/SSE，JSON 字符串发起任务。仅拉起用 adb。
  Agent：root@tutu.gugenzzz.top。
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

## 发起任务

```bash
# health 不通时才拉起
adb -s emulator-5554 shell su -c \
  'am start -W -n com.aliothmoon.maameow/.MainActivity -f 0x28800000'

# 推荐：JSON 字符串（实时 SSE）
bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh '{
  "force_stop_game": false,
  "closedown_after": false,
  "resource_path": "/storage/emulated/0/maa/MaaResource",
  "resource_overrides": "/storage/emulated/0/maa/overrides",
  "tasks": [
    {"type": "StartUp", "params": {"client_type": "Official", "start_game_enabled": true}},
    {"type": "Fight", "params": {"stage": "AD-EX-2", "medicine": 0, "times": 1}}
  ]
}'

# 等价：stdin
bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh <<'EOF'
{"force_stop_game":false,"closedown_after":false,"tasks":[{"type":"Award","params":{"enable":true}}]}
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

## EX 首通

1. `Fight` + 已解锁关名（如 `AD-EX-2`）导航到备战（无代理时常在 UsePrts 失败，属预期）
2. 可选 `POST /v1/stop`
3. `Copilot` + `skip_navigation: true` + 作业 `filename`

```bash
# 作业先下到设备 copilot 目录后：
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
