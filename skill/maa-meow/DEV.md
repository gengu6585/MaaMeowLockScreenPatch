# 开发者指南（追加功能用）

面向后续给 **MaaMeowLockScreenPatch** / **maa-meow skill** 加能力的人。运营向说明见同目录 [SKILL.md](./SKILL.md)。

## 仓库与职责边界

| 仓库 | 路径（本机） | 改什么 |
|---|---|---|
| **MaaMeowLockScreenPatch** | `~/Code/tinkerlab/MaaMeowLockScreenPatch` | LSPosed 模块、Intent API、设备脚本、本 skill（`skill/maa-meow/`） |
| **MAA-Meow** | `~/Code/tinkerlab/MAA-Meow-src` | 上游壳（一般只读对照；勿把补丁逻辑塞进上游除非明确 fork） |
| **MaaCore** | 上游 GitHub | 任务语义 / `AD.json` / Copilot 协议；本仓库只消费 |
| **cursor-byok** | 无关 | **不要改** |

远程：`https://github.com/gengu6585/MaaMeowLockScreenPatch.git`（`main`）。

手机侧 skill 部署副本：`root@tutu.gugenzzz.top:/root/.cursor/skills/maa-meow/`（改完本仓库后 `rsync` 过去）。

## 模块架构（加功能前先读）

```
外部 am start Intent
        │
        ▼
MainActivity.onCreate / onNewIntent
        │
        ▼
MainHook.maybeHandleExternalActions
        ├─ LAUNCH_COPILOT → CopilotLaunchHelper
        ├─ RUN_TASKS / STOP_TASKS → CliTaskLaunchHelper
        └─ 官方 LAUNCH_PROFILE 等 → Meow 原逻辑
                │
                ▼
        MeowBridge（Koin 解析 + suspend 反射）
                │
                ▼
        MaaCompositionService.start(tasks, clientType, …)
                │
                ▼
        MaaCore AsstAppendTask（StartUp/Fight/Copilot/…）
                │
        buildConnectConfig ← hookConnectForceStopFlag
                （extra_force_stop_game=false 时改写 force_stop）
```

| 类 | 职责 | 加功能时 |
|---|---|---|
| `MainHook` | 锁屏/RunMode/定时 hooks；`force_stop` hook；Intent 分发 | 新 action 在此注册并分发 |
| `CliTaskLaunchHelper` | 通用任务链 JSON → `MaaTaskParams` | 新任务类型 normalization / 校验放这里 |
| `CopilotLaunchHelper` | `task_list.json` → `buildListTask` | 列表/配置字段兼容 |
| `MeowBridge` | 唯一反射桥 | **禁止**再复制一份 Koin/suspend 代码 |

### Intent 约定（保持兼容）

- Action 前缀：`com.tinkerlab.maameowpatch.action.*`
- 热调试默认：**不杀游戏**
  - `extra_force_stop_game` 默认 `false`
  - `extra_closedown_after` 默认 `false`
  - `extra_with_startup`（LAUNCH_COPILOT）默认 `false`
- `CliTaskLaunchHelper` 的 `FORCE_STOP_GAME` **粘滞到下一次 Intent**（勿在 `start` 返回后立刻还原）
- 长 JSON 用 `extra_tasks_path` 文件，勿塞超长 `extra_tasks_json`

### 脚本分层

| 层 | 位置 | 说明 |
|---|---|---|
| 仓库 `scripts/` | 编译部署、设备 launch | `install_local_to_phone.sh` 推 APK + `/data/local/tmp/launch_*.sh` |
| `skill/maa-meow/scripts/` | Agent 运维 | 诊断、搜作业、截图、自测；**与 `scripts/` 同名文件改完要两边同步或只维护 skill 再 cp** |

当前建议：**以 `skill/maa-meow/scripts/` 为运维脚本源**，改完后：

```bash
cp skill/maa-meow/scripts/launch_cli_tasks.sh skill/maa-meow/scripts/launch_copilot.sh scripts/
rsync -az skill/maa-meow/ root@tutu.gugenzzz.top:/root/.cursor/skills/maa-meow/
adb push skill/maa-meow/scripts/launch_cli_tasks.sh /data/local/tmp/
```

## 本地开发循环

```bash
cd ~/Code/tinkerlab/MaaMeowLockScreenPatch
export JAVA_HOME=.../corretto-17.../Contents/Home
export ANDROID_HOME=~/Library/Android/sdk

# 编签 + scp + pm install + 推 launch 脚本 + force-stop 仅 Meow
bash scripts/install_local_to_phone.sh

# 注意：install 会 force-stop Meow → VD 可能丢；下一轮用 AUTO_STARTUP=auto/true
ssh root@tutu.gugenzzz.top "adb -s emulator-5554 shell su -c \
  'MODE=fight STAGE_NAME=AD-1 AUTO_STARTUP=auto FORCE_STOP_GAME=false sh /data/local/tmp/launch_cli_tasks.sh'"
```

- JDK：**17**（Corretto）
- `compileSdk=36` / `versionName` 在 `app/build.gradle.kts` 递增
- 签名：`keystore/`（gitignore，勿提交）

## 如何追加一类新能力

### A. 新 Intent / 新任务类型

1. 确认 Meow `MaaTaskType` 已有对应 `value`（或只能走 `Custom`）。
2. 若需新 action：在 `*LaunchHelper` 增加常量 + extras 文档；`MainHook.maybeHandleExternalActions` 分发。
3. 参数校验/归一化放 `CliTaskLaunchHelper`（参考 `normalizeCopilotParams`）。
4. 更新 `skill/maa-meow/scripts/launch_cli_tasks.sh` 的 `MODE` 或示例 `TASKS_JSON`。
5. 更新 [SKILL.md](./SKILL.md) 任务表 + 本文件「已知坑」。
6. `selftest_scripts.sh` 能测的加上（至少 dry-run / STOP）。
7. bump `versionCode`/`versionName` → `install_local_to_phone.sh` → 真机看 `MaaMeowPatch` logcat。

### B. 新活动关卡（类似红丝绒 AD）

1. 确认 MaaCore `resource/tasks/Stages/XX.json` 是否含目标关；缺则做 overrides（参考 `resources/AD.json` + `ensure_ad_resources.sh`）。
2. `download_prts_copilot.py` / `search_prts_jobs.py` 增加 stage 映射表。
3. `deploy_copilot_jobs.sh` 的 `order` 列表追加关卡名。
4. 进关验证：**优先 Fight `stage=显示名`**；不要假设 Copilot OCR 能从主界面进活动。
5. 记一笔到 SKILL「已验证路径」。

### C. 只加 Agent 脚本（不改 APK）

1. 写在 `skill/maa-meow/scripts/`，`chmod +x`。
2. SKILL 脚本表加一行；`selftest_scripts.sh` 引用。
3. `rsync` 到手机 skill 目录；需要设备侧的再 `adb push` 到 `/data/local/tmp/`。

## 对照 Meow 源码的入口

| 需求 | 去哪看（MAA-Meow-src） |
|---|---|
| 任务枚举 | `maa/task/MaaTaskType.kt` |
| 组合启动 | `domain/service/MaaCompositionService` |
| Copilot 列表 → Core params | `domain/service/CopilotManager.buildListTask` |
| 连接 JSON / force_stop | `buildConnectConfig` |
| 资源路径 / overrides | `MaaResourceLoader` / `PathConfig` |

DeepWiki / 上游文档可查 `"No stage template available"`、`StageNavigationTask`、`MultiCopilotTaskPlugin` 差异。

## 测试清单（合并前）

```bash
# 手机 Ubuntu
export ADB='adb -s emulator-5554'
bash ~/.cursor/skills/maa-meow/scripts/selftest_scripts.sh
bash ~/.cursor/skills/maa-meow/scripts/diagnose_maa.sh

# 热路径：游戏已在 → 不应带 StartUp、不应 force_stop
$ADB shell su -c 'MODE=fight STAGE_NAME=AD-1 AUTO_STARTUP=auto FORCE_STOP_GAME=false \
  CLOSEDOWN_AFTER=false sh /data/local/tmp/launch_cli_tasks.sh'
# logcat 应有: WITH_STARTUP 跳过（脚本打印）、buildConnectConfig force_stop=false、start Success
$ADB shell su -c 'sh /data/local/tmp/launch_cli_tasks.sh --stop'
```

人工再确认：`watch_maa_logs.sh` + `maa-screenshot.sh` 的 `virtual_*.png`。

## 已知坑（写功能时默认规避）

1. **`am force-stop com.aliothmoon.maameow`** 会拆 VirtualDisplay；install 脚本已这样做——之后必须 `StartUp` 或用户已重建 VD。
2. Meow 设置「任务结束后关闭游戏」与 Intent 热调试冲突；文档要求用户关掉。
3. **Copilot `copilot_list`** 走 MultiCopilot（模板/OCR），**不是** Fight 的 `AD.json` `AD-OpenOpt` 路径。
4. 作业必须有 **`actions`**；`stage_name` 是地图 ID（`act43side_01`），导航显示名是 `AD-1`。
5. 本机访问 `prts.maa.plus` 可能超时——搜/下作业优先在 **手机侧**跑 Python。
6. 不要在 Helper 里再抄一份 `invokeSuspend`；改 `MeowBridge`。
7. `keystore/`、`local.properties` 永不提交。

## 版本与发布

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`。
2. 本地 `install_local_to_phone.sh` 验证。
3. `git commit` + `git push origin main`（或 PR）。
4. 若有 GitHub Release workflow，tag 触发；否则至少 push 源码 + 更新 SKILL/DEV。
5. `rsync skill/maa-meow/` 到手机 `~/.cursor/skills/maa-meow/`。

## Commit 信息风格（本仓）

简短说明 **为什么**，例如：

- `Add RUN_TASKS Intent for maa-cli style task chains without killing the game`
- `Document developer workflow for extending patch and skill`

## 目录速查

```
MaaMeowLockScreenPatch/
├── app/src/main/java/.../maameowpatch/   # Java 补丁
├── scripts/                              # 本机部署 + 设备 launch 副本
├── skill/maa-meow/
│   ├── SKILL.md                          # Agent 运维
│   ├── DEV.md                            # 本文件
│   ├── resources/AD.json                 # 红丝绒导航补丁
│   └── scripts/                          # 诊断 / 作业 / 自测
└── README.md
```
