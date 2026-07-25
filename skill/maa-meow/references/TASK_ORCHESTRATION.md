# 任务编排（tutu 直连）

`http://127.0.0.1:17878`。仅 `am start` 用 adb。

## SSE

```bash
bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh '{"tasks":[...]}'
```

`timeout_ms` 只关 SSE 流，**不 stop 任务**（`done` 含 `error=stream_timeout`、`task_stopped=false`）。

## EX

导航用已解锁 `Fight stage`（如 `AD-EX-2`）→ 备战页 → `Copilot` + `skip_navigation`。
