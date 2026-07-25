# 任务编排补充

主流程见 [SKILL.md](../SKILL.md)「怎么编排任务」。

## 原则

1. **一次 JSON**：多关卡 / 多任务全部放进同一个 `tasks`（或一个 `Copilot.copilot_list`），一次 `meow_sse.sh`。
2. **跟着 SSE**：不要自写 shell `for` 串关；会丢掉实时日志，出错也无法及时 `stop`。
3. **长任务分节看**：前台 SSE 最佳；后台则 **20–60s** 轮询 `/v1/status` 或 `/v1/events`，禁止一次傻等过久。

## 权威来源

[集成协议](https://docs.maa.plus/zh-cn/protocol/integration.html) = `type` / `params` 权威。  
能力摘要：[MAA_TASKS.md](./MAA_TASKS.md)。

## 多关卡怎么写

| 场景 | 写法 |
|---|---|
| 有代理连刷 EX-4～8 | 多个 `Fight`，`stage` 各写一关，同一 `tasks` |
| 无代理首通多关 | 一个 `Copilot` + `copilot_list`（每项 `filename` + `stage_name`） |
| 单关首通最稳 | `Fight` 已解锁关导航 → `Copilot` `skip_navigation: true` |

单个 `Fight` 失败后 Meow 仍可能继续后续 `tasks`（已实测）；无代理时连刷会关关 UsePrts 失败——首通用 `copilot_list` 或单关导航+Copilot。

## 长任务观察（Agent）

```bash
# 推荐：前台，SSE 实时
TIMEOUT_MS=3600000 bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh '...'

# 若后台：短间隔轮询（示例 30s）
while curl -sS http://127.0.0.1:17878/v1/status | grep -q '"active":true'; do
  curl -sS 'http://127.0.0.1:17878/v1/events?limit=5'
  sleep 30
done
```

见 `任务出错` / `FAILED` → 立刻查 `last_error` / meow_log，决定停或改 params。
