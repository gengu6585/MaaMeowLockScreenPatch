# 如何读官方文档并编排任务

## 文档入口

| 文档 | URL | 用途 |
|---|---|---|
| **集成协议** | https://docs.maa.plus/zh-cn/protocol/integration.html | 任务 `type` / `params` |
| 回调消息 | https://docs.maa.plus/zh-cn/protocol/callback-schema.html | 日志/进度语义 |
| 基建排班 | https://docs.maa.plus/zh-cn/protocol/base-scheduling-schema.html | 自定义基建 JSON |
| 作业协议 | https://docs.maa.plus/zh-cn/protocol/copilot-schema.html | Copilot 作业文件 |
| MaaResource | https://github.com/MaaAssistantArknights/MaaResource | 最新 tasks/stages |

任务语义摘要：[MAA_TASKS.md](./MAA_TASKS.md)。

## 编排

1. 在集成文档找任务类型与 `params`。
2. 组成 JSON body（可多任务顺序执行）。
3. 一次 `meow_sse.sh` 下发整条链（tutu 直连 HTTP/SSE）。
4. 失败再查 `asst.log` / [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)。

## Demo

```bash
bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh '{
  "force_stop_game": false,
  "closedown_after": false,
  "tasks": [
    {"type":"Award","params":{"enable":true}},
    {"type":"Recruit","params":{"enable":true,"refresh":true,"times":4,"expedite":false}},
    {"type":"Infrast","params":{"enable":true,"mode":0,"facility":["Control","Mfg","Trade","Power","Reception","Office","Dorm"],"threshold":0.3}}
  ]
}'

bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh '{
  "force_stop_game": false,
  "closedown_after": false,
  "tasks": [{"type":"Fight","params":{"stage":"CE-6","medicine":1,"times":3}}]
}'
```

活动关需资源里有对应 `tasks/Stages/*.json` 与 `stages.json`；缺了再看 [EXTERNAL_RESOURCE.md](./EXTERNAL_RESOURCE.md)。
