---
name: maa-meow
description: >-
  Maa-Meow（猫猫 MAA）明日方舟 Android 自动化：PRTS 搜/下作业、RUN_TASKS Intent、
  Fight/Copilot、虚拟屏截图、连续任务（默认不杀游戏）。
  默认直接 launch，不要先跑 diagnose/selftest。
  仅供手机容器内 Agent（root@tutu.gugenzzz.top，~/.cursor/skills/maa-meow/）。
---

# Maa-Meow 工具链

## 执行环境（必读）

| 角色 | 在哪 | 做什么 |
|---|---|---|
| **本 skill 的 Agent** | 手机 Ubuntu 容器 `root@tutu.gugenzzz.top` | 读本目录、跑 `scripts/`、`adb` 控 HyperOS、搜/下作业、发 Intent |
| **Skill 安装路径** | `/root/.cursor/skills/maa-meow/` | Agent 只认这里，不要找 Mac 路径 |
| **补丁编译 / 改 Java** | 开发者 Mac 仓库 | **不是**本 skill 的运行方；见 [DEV.md](./DEV.md) |

你（手机容器里的 Agent）应：

1. 直接用 `~/.cursor/skills/maa-meow/scripts/...`（或本 skill 相对路径）
2. 用 `adb -s emulator-5554`（容器内已连模拟器/HyperOS）
3. **不要** `ssh` 回 Mac、**不要**假设存在 `~/Code/tinkerlab/...`
4. 需要新版本 skill 时，由人在 Mac `rsync` 过来；你只消费当前目录

## 项目背景

自动化栈三层（对照用，运行时你只碰 adb / Meow / 本 skill）：

| 层 | 是什么 | 仓库 |
|---|---|---|
| **MaaCore** | C++ 识别与任务引擎（Fight/Copilot/…） | [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) |
| **MAA-Meow** | Android 壳：虚拟屏、Koin、Profile/定时、资源包 | [Aliothmoon/MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) |
| **LockScreenPatch** | LSPosed：锁屏拉起 + **RUN_TASKS/LAUNCH_COPILOT**（当 maa-cli） | [gengu6585/MaaMeowLockScreenPatch](https://github.com/gengu6585/MaaMeowLockScreenPatch) |

作业市场：[prts.plus](https://prts.plus) / API `https://prts.maa.plus`。

游戏在 Meow 的 **VirtualDisplay** 上，物理屏截图看不到战斗；截图只用 `maa-screenshot.sh` → `virtual_*.png`。

**追加功能 / 改补丁**：见 [DEV.md](./DEV.md)（给改仓库的人；手机 Agent 运维仍以本文为准）。

## 何时用

- 搜/下/部署 PRTS 作业，刷活动关（红丝绒 AD-*）
- Intent 下发 Fight / Copilot / 自定义任务链
- 排错：进不了关、截图失败、任务结束游戏没了

## 硬约束

1. **默认不杀游戏**：`FORCE_STOP_GAME=false`、`CLOSEDOWN_AFTER=false`、`AUTO_STARTUP=auto`
2. **禁止**随意 `am force-stop com.aliothmoon.maameow`（拆 VD，游戏常一起挂）
3. Meow 设置 **关闭**「任务结束后关闭游戏」
4. 截图只看 `maa-screenshot.sh` → `virtual_*.png`
5. 进红丝绒关卡优先 **`MODE=fight`**（走 `AD.json`）；纯 Copilot 从主界面不可靠
6. **不要每次任务前跑诊断**：`diagnose_maa.sh` / `selftest_scripts.sh` / 全量 `ensure_ad_resources.sh` 都偏慢；默认直接开刷，**只有失败或用户要求排错时**再跑

---

## 默认：直接开刷（推荐）

用户要刷关 / 跑任务时，**第一步就是发 Intent**，不要先 diagnose：

```bash
export ADB='adb -s emulator-5554'

# 连续刷 AD-1（有游戏则不 StartUp）
$ADB shell su -c 'MODE=fight STAGE_NAME=AD-1 AUTO_STARTUP=auto FORCE_STOP_GAME=false CLOSEDOWN_AFTER=false sh /data/local/tmp/launch_cli_tasks.sh'
```

停：`$ADB shell su -c 'sh /data/local/tmp/launch_cli_tasks.sh --stop'`

可选轻量确认（仍比 diagnose 快）：看脚本打印的 `WITH_STARTUP=... FORCE_STOP_GAME=false`，或 `watch_maa_logs.sh --tail 20`。

### 何时才诊断 / 补资源

| 动作 | 时机 |
|---|---|
| `diagnose_maa.sh` | 进关失败、Intent 无反应、游戏没了、用户明确要排查 |
| `ensure_ad_resources.sh` | Fight AD 报缺关 / 无 `AD-OpenOpt`；**不是**每次刷之前 |
| `selftest_scripts.sh` | 改脚本后自测；日常任务 **不要跑** |
| `maa-screenshot.sh` | 怀疑卡界面、要看虚拟屏；成功刷关不必每轮截 |

---

## 环境速查

| 项 | 值 |
|---|---|
| Agent / skill 主机 | 手机容器 `root@tutu.gugenzzz.top`（**不是**开发者 Mac） |
| Skill 路径 | `/root/.cursor/skills/maa-meow/` |
| ADB | `adb -s emulator-5554`（在容器内直接跑） |
| Meow | `com.aliothmoon.maameow` |
| 游戏主进程 | `com.hypergryph.arknights`（`:pushcore` 不算） |
| 补丁 | `com.tinkerlab.maameowpatch` ≥ **1.2.1** |
| 作业目录 | `.../files/Maa/copilot/` |
| 设备脚本 | `/data/local/tmp/launch_cli_tasks.sh` |

---

## Intent

### `RUN_TASKS`（推荐）

`com.tinkerlab.maameowpatch.action.RUN_TASKS`

| Extra | 默认 | 含义 |
|---|---|---|
| `extra_tasks_path` / `extra_tasks_json` | 必填 | 任务数组 |
| `extra_force_start` | true | stop **当前 MAA 任务**（不杀 APK） |
| `extra_force_stop_game` | **false** | 连接时是否杀游戏 |
| `extra_closedown_after` | **false** | 链尾 `CloseDown` |
| `extra_wait_ready_ms` | 15000 | 等 RemoteService |
| `extra_client_type` | Official | 客户端 |

脚本变量：`AUTO_STARTUP=auto|true|false`、`MODE=fight|copilot`、`STAGE_NAME`、`JOB`、`TASKS_JSON`。

### `STOP_TASKS` / `LAUNCH_COPILOT` / 官方 `LAUNCH_PROFILE`

- `LAUNCH_COPILOT`：`extra_with_startup`（默认 false）、`extra_force_stop_game`、`extra_closedown_after`
- Profile：启动前用 DataStore 校验 profileId

---

## MaaCore 任务类型

`StartUp` `CloseDown` `Fight` `Copilot` `SSSCopilot` `ParadoxCopilot`  
`Recruit` `Infrast` `Mall` `Award` `Roguelike` `Reclamation` `Depot` `OperBox` `Custom`

### Fight 进 AD-1（已验证）

`stage=AD-1` → `StageNavigationTask` → `AD.json` 的 `AD-OpenOpt`（红丝绒 / 演出开始）→ 点关卡 → 代理指挥。

依赖：`resource/tasks/Stages/AD.json` **必须含 AD-1**（上游默认可能只有 AD-3/6/7/8）。已部署过则跳过；仅报缺关 / 无 `AD-OpenOpt` 时再跑 `ensure_ad_resources.sh`。

### Copilot

- 必须有 `stage_name`（或 `copilot_list[].stage_name`）；仅 `filename` 会跳过导航
- `copilot_list` 走 MultiCopilot：靠 StageNavigation **PNG 模板**或 OCR；主界面无「AD-1」字样会空转
- **可靠做法**：Fight 进关刷；或已在活动地图后再 Copilot 抄作业编队

---

## 找作业 / 构造作业

### 搜索（脚本）

```bash
python3 $SKILL/search_prts_jobs.py --stage AD-1 --limit 10
python3 $SKILL/search_prts_jobs.py --keyword 丰川祥子 --activity act43side
python3 $SKILL/search_prts_jobs.py --id 97725 --validate
python3 $SKILL/search_prts_jobs.py --stage AD-1 --download --out-dir /tmp/jobs
```

### 批量下载红丝绒

```bash
python3 $SKILL/download_prts_copilot.py --activity act43side --preset tryuhark --out-dir /tmp/arknights-ad-jobs
# 或 --preset hot ；覆盖：--id AD-5=97922
bash $SKILL/deploy_copilot_jobs.sh /tmp/arknights-ad-jobs
```

### 作业 JSON 最低要求

| 字段 | 说明 |
|---|---|
| `stage_name` | 如 `act43side_01`（地图 ID，不是 AD-1） |
| `actions` | **必填**；无则无法抄 |
| `opers` / `groups` | 干员与技能 |
| `doc.title` | 展示用，常含 `AD-1` |
| `minimum_required` | MaaCore 版本下限 |

`task_list.json` 项：`name`（导航名 **AD-1**）、`filePath`、`isChecked`、`copilotId`。

红丝绒映射：`AD-1` ↔ `act43side_01` … `AD-EX-8` ↔ `act43side_ex08`（见 `search_prts_jobs.py` / `download_prts_copilot.py`）。

### 一键

`bash $SKILL/run_copilot.sh`（下+推+LAUNCH_COPILOT，AUTO_STARTUP）

---

## 日志与排错

| 来源 | 怎么看 |
|---|---|
| 补丁 | `adb logcat -s MaaMeowPatch:V` |
| Meow | `.../debug/gui/meow_log_*.log` |
| MaaCore | `.../debug/asst.log` |

```bash
SKILL=~/.cursor/skills/maa-meow/scripts
bash $SKILL/watch_maa_logs.sh --tail 50
# 仍看不清再：
bash $SKILL/diagnose_maa.sh
```

| 现象 | 原因 | 处理 |
|---|---|---|
| `游戏进程未启动` / screencap failed | 主进程没了或 VD 空 | `AUTO_STARTUP=true` 或确认设置未关游戏；勿 force-stop Meow |
| Fight 无 `AD-OpenOpt` | AD.json 无 AD-1 | `ensure_ad_resources.sh` |
| Copilot `No stage template` + FullStageNavigation | 主界面 OCR | 改 `MODE=fight` 或先进入活动图 |
| Intent ok 但无任务 | profileId 过期 / 模块未加载 | 查 DataStore；查 patch versionName |
| 每轮游戏消失 | 「结束后关游戏」或 `CLOSEDOWN_AFTER`/`FORCE_STOP_GAME` | 全关 |

成功 Fight AD-1：`Sub: AD-1@AD-OpenOpt` → OCR `AD-1` → meow `已开始行动` / `完成任务:理智作战`。

---

## 连续任务参数

| 场景 | AUTO_STARTUP | FORCE_STOP_GAME | CLOSEDOWN_AFTER |
|---|---|---|---|
| 游戏已在，再刷 | auto/false | false | false |
| 冷启动 | auto/true | false | false |
| 强制重进游戏 | true | **true** | 按需 |
| 结束后关游戏 | 任意 | 任意 | **true** |

---

## 脚本清单（`~/.cursor/skills/maa-meow/scripts/`）

| 脚本 | 作用 |
|---|---|
| `diagnose_maa.sh` | 环境诊断（**仅排错**；勿每次任务前跑） |
| `ensure_ad_resources.sh` | 写入含 AD-1 的 AD.json（缺资源时） |
| `launch_cli_tasks.sh` | RUN_TASKS（设备 `/data/local/tmp/`） |
| `launch_copilot.sh` | LAUNCH_COPILOT |
| `maa_status.sh` / `watch_maa_logs.sh` / `maa-screenshot.sh` | 状态/日志/截图 |
| `search_prts_jobs.py` / `download_prts_copilot.py` / `deploy_copilot_jobs.sh` | 搜/下/推作业 |
| `run_copilot.sh` | 一键 |
| `selftest_scripts.sh` | 脚本自测 |

资源：`resources/AD.json`（完整红丝绒导航）。

---

## 补丁定制代码（LSP）

路径：`MaaMeowLockScreenPatch/app/.../maameowpatch/`

| 类 | 职责 |
|---|---|
| `MainHook` | 锁屏 flags、RunMode、**buildConnectConfig force_stop**、Intent 分发 |
| `CliTaskLaunchHelper` | `RUN_TASKS`/`STOP_TASKS`；Copilot params 规范化 |
| `CopilotLaunchHelper` | `LAUNCH_COPILOT`；可选 StartUp/CloseDown |
| `MeowBridge` | Koin + suspend 反射 |

`force_stop` 标志粘滞到下一次 Intent（避免 connect 晚于 start 返回被还原）。

补丁 APK 由开发者在 **Mac 仓库** 编签部署（`install_local_to_phone.sh`）；会 force-stop **仅 Meow** 重载模块 → VD 可能丢，之后需要 StartUp。手机 Agent **不负责**编译 APK。

---

## Agent 检查清单

**日常开刷（默认）：**

1. 直接 `launch_cli_tasks.sh` / 对应 Intent；确认打印 `FORCE_STOP_GAME=false`
2. 进关用 Fight；Copilot 确认 `stage_name` 与作业 `actions`
3. 不随意 force-stop Meow
4. **跳过** `diagnose_maa.sh` / `selftest` / 无必要的 `ensure_ad`

**仅当失败或用户要排错：**

5. `watch_maa_logs` → 仍不够再 `diagnose_maa.sh` / `maa-screenshot`
6. AD 缺导航时再 `ensure_ad_resources.sh`
