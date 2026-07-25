# 任务编排补充

主流程见 [SKILL.md](../SKILL.md)「怎么编排任务」。本文只补细节。

## 权威来源

[集成协议](https://docs.maa.plus/zh-cn/protocol/integration.html) 的任务表 = `type` / `params` 唯一权威。  
本仓 [MAA_TASKS.md](./MAA_TASKS.md) 是能力摘要；字段以官方为准。

## Body 形状

| 字段 | 说明 |
|---|---|
| `tasks` | 必填数组，顺序执行 |
| `force_stop_game` | 固定 `false` |
| `closedown_after` | 固定 `false` |
| `resource_path` / `resource_overrides` | 可选；见 [EXTERNAL_RESOURCE.md](./EXTERNAL_RESOURCE.md) |

下发：`meow_sse.sh '<json>'` 或 stdin。API：`POST /v1/tasks?stream=1`。

## 常见链

| 需求 | 任务链思路 |
|---|---|
| 日常 | `Award` → `Recruit` → `Infrast`（params 抄集成文档） |
| 刷图 | `Fight`：`stage` / `medicine` / `times` |
| 抄作业 | `Copilot`：`filename`；已在备战页则 `skip_navigation: true` |
| EX 首通 | 已解锁关 `Fight` 导航 → stop → `Copilot` + `skip_navigation` |
| 干员识别 | `OperBox`（见 `run_operbox.sh`） |

## SSE

`timeout_ms` 只关流，不 stop 任务（`task_stopped=false`）。要停：`POST /v1/stop`。
