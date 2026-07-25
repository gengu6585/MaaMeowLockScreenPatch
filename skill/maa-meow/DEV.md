# 开发者指南

面向在 **开发者 Mac** 上改 **MaaMeowLockScreenPatch** / 维护 skill 源码的人。  
手机容器运维手册见 [SKILL.md](./SKILL.md)。

## 两个执行方

| 谁 | 在哪 | 做什么 |
|---|---|---|
| **运维 Agent** | `root@tutu.gugenzzz.top` | 读 `/root/.cursor/skills/maa-meow/`，直连 `http://127.0.0.1:17878` |
| **开发者** | Mac 本仓库 | 改 Java / skill 源、`install_local_to_phone.sh`、`git push`、`rsync` |

```bash
rsync -az skill/maa-meow/ root@tutu.gugenzzz.top:/root/.cursor/skills/maa-meow/
```

远程：`https://github.com/gengu6585/MaaMeowLockScreenPatch.git`（`main`）。

## 架构

```
am start（仅拉起）→ MeowHttpServer :17878
  → TaskExecutor（唯一启停）
  → MeowBridge → MaaCompositionService
```

| 类 | 职责 |
|---|---|
| `TaskExecutor` | 启停 / `controlGame` / `refreshStatus` + `refreshStatusLight` |
| `TaskSseStreamer` | SSE；流超时**不** stop 任务 |
| `TaskRunTracker` | 状态 + events 落盘；`exec_state` 不落盘 |
| `ResourceOverrideHelper` | 仅 body 带 `resource_path` 时注入 |

单测：`gradle :app:testDebugUnitTest`（JDK 17）。

## 约定

- 运维入口：**`meow_sse.sh` + JSON 字符串**（不要为每次请求写临时文件）
- `force_stop_game=false`、`closedown_after=false`
- SSE `timeout_ms` 只关流，不 stop 任务

## Mac 开发循环

```bash
cd ~/Code/tinkerlab/MaaMeowLockScreenPatch
gradle :app:testDebugUnitTest
bash scripts/install_local_to_phone.sh
rsync -az skill/maa-meow/ root@tutu.gugenzzz.top:/root/.cursor/skills/maa-meow/

# tutu 上冒烟
ssh root@tutu.gugenzzz.top \
  "bash ~/.cursor/skills/maa-meow/scripts/meow_sse.sh '{\"force_stop_game\":false,\"closedown_after\":false,\"tasks\":[{\"type\":\"Award\",\"params\":{\"enable\":true}}]}'"
```

## 追加能力

1. 确认 Meow `MaaTaskType` / 参数；改 `TaskExecutor` 或 Router；补单测。
2. 更新 [SKILL.md](./SKILL.md) / [TASK_ORCHESTRATION.md](./references/TASK_ORCHESTRATION.md)。
3. bump `versionCode`/`versionName` → 装机验证 → commit/push → rsync skill。

活动关：优先 overrides（参考 `resources/AD.json`）；EX 用已解锁关名导航 + Copilot `skip_navigation`。

## 已知坑

1. `am force-stop` Meow 会拆 VD；装机后需 StartUp 重建。
2. 未解锁勿 `Fight stage=AD-EX-N`（空滑）。
3. 无必要勿改 `AD.json`。
4. 搜/下作业在 **手机容器**跑（Mac 访问 prts 可能超时）。
5. `keystore/`、`local.properties` 勿提交。

## 目录

```
app/src/main/java/.../maameowpatch/   # 补丁
scripts/                              # Mac 部署
skill/maa-meow/                       # rsync → 手机 skill
  SKILL.md / DEV.md / scripts/meow_sse.sh
```
