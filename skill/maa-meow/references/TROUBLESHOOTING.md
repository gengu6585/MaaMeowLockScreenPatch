# 排错速查

主流程失败时再看；不要任务前全量 diagnose。

| 现象 | 查什么 | 处理 |
|---|---|---|
| HTTP 未就绪 | `curl 127.0.0.1:17878/v1/health` / logcat `MeowHttpServer` | 先 `am start` Meow；补丁 ≥1.2.9、LSPosed 注入 |
| accepted 但无任务 | `GET /v1/status` · `composition_state` | `REMOTE_ACCESS`：合并任务链，勿连 STOP |
| 进错关 / 导航失败 | `asst.log` Stage* | 资源缺关；可选外置 resource |
| Copilot 空转滑屏 | `FullStageNavigation` | `skip_navigation: true`（须已在备战页） |
| Fight 空滑很久 | `SwipeToStage` + 未解锁关 | 用已解锁的 `AD-EX-N` |
| SSE 像没日志 / 攒到结束才出 | 非 TTY 块缓冲 | `meow_sse.sh`（内置 stdbuf -oL） |
| SSE `stream_timeout` | 仅流结束 | **任务未停**；`POST /v1/stop` 才停 |
| 截图不对 | 物理屏 | `maa-screenshot.sh` → `virtual_*.png` |
| 游戏未进 VD | `status.game.running` + StartUp | `--game start` 或任务链加 StartUp |
| 外部资源未生效 | `last_intent_resource.log` | 请求必须带 `resource_path` |

```bash
# 设备内
curl -s http://127.0.0.1:17878/v1/status
adb -s emulator-5554 logcat -s MaaMeowPatch:V | tail -40
grep -E 'TaskChainError|StageDrops|FullStageNavigation' \
  /storage/emulated/0/Android/data/com.aliothmoon.maameow/files/Maa/debug/asst.log | tail -40
```

完整诊断（慢）：`scripts/diagnose_maa.sh`（仅失败后）。
