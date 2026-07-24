---
name: maa-meow
description: >-
  Maa-Meow（猫猫 MAA）明日方舟 Android 自动化：PRTS 搜/下作业、RUN_TASKS Intent、
  Fight/Copilot、虚拟屏截图、MaaCore 日志诊断、连续任务（默认不杀游戏）。
  在 SSH 主机 root@tutu.gugenzzz.top 使用。
---

# Maa-Meow 工具链

## 项目背景

明日方舟自动化栈在本机环境拆成三层：

| 层 | 是什么 | 仓库 / 路径 |
|---|---|---|
| **MaaCore** | C++ 识别与任务引擎（Fight/Copilot/…） | [MaaAssistantArknights](https://github.com/MaaAssistantArknights/MaaAssistantArknights) |
| **MAA-Meow** | Android 壳：虚拟屏、Koin、Profile/定时、资源包 | [Aliothmoon/MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) · 本机 `~/Code/tinkerlab/MAA-Meow-src` |
| **LockScreenPatch** | LSPosed：锁屏拉起 + **RUN_TASKS/LAUNCH_COPILOT**（当 maa-cli） | [gengu6585/MaaMeowLockScreenPatch](https://github.com/gengu6585/MaaMeowLockScreenPatch) · 本机 `~/Code/tinkerlab/MaaMeowLockScreenPatch` |

作业市场：[prts.plus](https://prts.plus) / API `https://prts.maa.plus`。

**本 skill 跑在手机 Ubuntu（`root@tutu.gugenzzz.top`）**：经 `adb -s emulator-5554` 控 HyperOS；游戏在 Meow 的 **VirtualDisplay** 上，物理屏截图看不到战斗。

开发约定：补丁在本机改 → `scripts/install_local_to_phone.sh` 部署；**不要**改 `cursor-byok`。手机 `/workspace/tinkerlab/...` 仅作对照。

**追加功能**：见同目录 [DEV.md](./DEV.md)（架构、Intent 约定、开发循环、测试清单、已知坑）。

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

---

## 一分钟流程

```bash
SKILL=~/.cursor/skills/maa-meow/scripts
export ADB='adb -s emulator-5554'

bash $SKILL/diagnose_maa.sh
bash $SKILL/ensure_ad_resources.sh          # 确保 AD-1 导航资源

# 连续刷 AD-1（有游戏则不 StartUp）
$ADB shell su -c 'MODE=fight STAGE_NAME=AD-1 AUTO_STARTUP=auto FORCE_STOP_GAME=false CLOSEDOWN_AFTER=false sh /data/local/tmp/launch_cli_tasks.sh'

bash $SKILL/watch_maa_logs.sh --tail 40
bash $SKILL/maa-screenshot.sh
```

停：`$ADB shell su -c 'sh /data/local/tmp/launch_cli_tasks.sh --stop'`

脚本自测：`bash $SKILL/selftest_scripts.sh`（加 `--live` 会发 STOP）

---

## 环境速查

| 项 | 值 |
|---|---|
| SSH | `root@tutu.gugenzzz.top` |
| ADB | `adb -s emulator-5554` |
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

依赖：`resource/tasks/Stages/AD.json` **必须含 AD-1**（上游默认可能只有 AD-3/6/7/8）。用 `ensure_ad_resources.sh`。

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
bash $SKILL/watch_maa_logs.sh --tail 50
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
| `diagnose_maa.sh` | 环境诊断 |
| `ensure_ad_resources.sh` | 写入含 AD-1 的 AD.json |
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

部署：本机 `bash scripts/install_local_to_phone.sh`（会 force-stop **仅 Meow** 以重载模块 → 之后需要 StartUp 重建 VD）。

---

## Agent 检查清单

1. `diagnose_maa.sh`  
2. `ensure_ad_resources.sh`（刷 AD 时）  
3. `launch_cli_tasks.sh` 确认打印 `WITH_STARTUP=... FORCE_STOP_GAME=false`  
4. `watch_maa_logs` + `maa-screenshot`  
5. 进关用 Fight；Copilot 确认 `stage_name` 与作业 `actions`  
6. 不随意 force-stop Meow
