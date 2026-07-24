# MaaMeowLockScreenPatch

LSPosed 模块：解锁 `com.aliothmoon.maameow`（MAA-Meow，明日方舟小助手 Android 版）
在 **锁屏 / 灭屏** 状态下无法被 `am start` / MacroDroid / Tasker 拉起执行任务的限制。

## 目录

```
MaaMeowLockScreenPatch/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml            # xposedscope = android + maa-meow
│       ├── assets/xposed_init             # 入口 = com.tinkerlab.maameowpatch.MainHook
│       ├── java/com/tinkerlab/maameowpatch/MainHook.java
│       └── res/values/{strings,arrays}.xml
├── scripts/
│   ├── install_and_verify.sh              # 端到端：编译→签名→推送→拉起→看日志
│   ├── launch_profile.sh                  # 锁屏可用、按 Profile 名拉起
│   └── watch_logs.sh                      # logcat 实时监控
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties                       # sdk.dir = /Users/gugen/Library/Android/sdk
└── README.md
```

## 问题根因（双层）

| 层 | 类 | 限制 |
|---|---|---|
| 系统层 | `com.android.server.wm.ActivityStarter.executeRequest` (Android 12+) <br> `com.android.server.am.ActivityStarter.executeRequest` (10-11) | Keyguard 锁定 + `am`/`shell` 进程属于「受管控的后台启动」，AMS 直接拒发 Activity。 |
| 应用层 | `com.aliothmoon.maameow.schedule.service.ScheduledLaunchCoordinator.handleLaunch` (L128-135) | `if (isForegroundMode && !allowForeground) reject(...)` —— `am start` 拉起的 `MainActivity` 一定在前台，硬校验失败。 |

虚拟屏路径（`RunMode.BACKGROUND` / `VirtualDisplay`）是 `MaaCompositionService` 内部启的，
从外部 Intent 入口绕不过去。**两个都要 patch。**

## Patch 概览

| 层 | 做法 |
|---|---|
| 系统层 | hook `ActivityStarter.executeRequest(Request)` + `ActivityTaskManagerService.startActivityAsUser`，对 `com.aliothmoon.maameow/.MainActivity` 的 Intent 强制加 `FLAG_ACTIVITY_SHOW_WHEN_LOCKED \| FLAG_ACTIVITY_TURN_SCREEN_ON \| NEW_TASK \| CLEAR_TOP \| SINGLE_TOP`，并把 `Request` 的 `allowBackgroundActivityStart` / `callerIsForeground` / `isBackgroundActivityStartAllowed` / `ignoreKeyguard`（多 ROM 兼容名）全部置 `true` |
| 应用层 | hook `AppSettingsManager.runMode`（`StateFlow<RunMode>.getValue()`）一律返回 `RunMode.BACKGROUND`，从而让 `ScheduledLaunchCoordinator.handleLaunch` 的 `isForegroundMode && !allowForeground` 永远短路为 false |
| 全程日志 | 在 `coordinator.onLaunch / handleLaunch / promote / onPageReady` 与 `MainActivity.onCreate / onNewIntent` 与 `MaaCompositionService.state` 变化处打 log（tag=`MaaMeowPatch`），便于锁屏下确认任务执行 |

## v1.2 RUN_TASKS（maa-cli）

把 Meow 当命令行任务引擎：

| Action | 说明 |
|---|---|
| `com.tinkerlab.maameowpatch.action.RUN_TASKS` | 任意 `MaaTaskType` 任务链 |
| `com.tinkerlab.maameowpatch.action.STOP_TASKS` | 只停任务 |
| `com.tinkerlab.maameowpatch.action.LAUNCH_COPILOT` | 作业列表（可选 StartUp） |

**默认不杀游戏**（`extra_force_stop_game=false`、`extra_closedown_after=false`）。  
脚本：`launch_cli_tasks.sh`（`AUTO_STARTUP=auto`）。完整说明见 `skill/maa-meow/SKILL.md`。

```bash
adb shell su -c 'MODE=fight STAGE_NAME=AD-1 AUTO_STARTUP=auto FORCE_STOP_GAME=false sh /data/local/tmp/launch_cli_tasks.sh'
```

### 一键脚本

```bash
# 1) 推送 16 关 prts 作业 + task_list.json + config.json
sh scripts/deploy_copilot_jobs.sh

# 2) 锁屏/后台触发（虚拟屏 Copilot 列表模式）
adb shell su -c 'sh /data/local/tmp/launch_copilot.sh'
```

### 直发 am

```bash
am start -n com.aliothmoon.maameow/.MainActivity \
  -a com.tinkerlab.maameowpatch.action.LAUNCH_COPILOT \
  -f 0x28800000 \
  --es extra_task_list_path "/storage/emulated/0/Android/data/com.aliothmoon.maameow/files/Maa/copilot/task_list.json" \

  --es extra_config_path "/storage/emulated/0/Android/data/com.aliothmoon.maameow/files/Maa/copilot/config.json" \
  --ei extra_tab_index 0 \
  --ez extra_force_start true
```

成功日志应出现：

```
MaaMeowPatch: LAUNCH_COPILOT taskList=...
MaaMeowPatch: LAUNCH_COPILOT startCopilot invoked
MaaMeowPatch: MaaCompositionService.state -> RUNNING
```

### Vector / LSPosed CLI（无需重启手机）

安装/升级模块后，在 LSPosed 管理器勾选作用域，或用 root CLI：

```bash
/data/adb/lspd/cli modules enable com.tinkerlab.maameowpatch
/data/adb/lspd/cli scope set com.tinkerlab.maameowpatch android/0 com.aliothmoon.maameow/0
# 然后 force-stop MAA 让 hook 重新注入
am force-stop com.aliothmoon.maameow
```

若 CLI 报 `Connection refused`，在 LSPosed 管理器里手动启用模块并「软重启」一次。

---

## 编译

```bash
# 一次性
cd /Users/gugen/Code/tinkerlab/MaaMeowLockScreenPatch

# debug（直接给 LSPosed 加载）
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk (~25KB)

# release（自签后推送）
gradle :app:assembleRelease
# 产物: app/build/outputs/apk/release/app-release-unsigned.apk
```

本机已验证：`./gradlew` 不可用时（wrapper 拉不到 8.10.2），
直接用系统 `gradle 9.6.0` + `compileSdk=36` 也能编。`local.properties` 已指向
`/Users/gugen/Library/Android/sdk`。

## 部署

### 一键端到端

```bash
sh scripts/install_and_verify.sh "配置-1"
```

它会做：
1. 生成自签 keystore（`keystore/maameow-patch.jks`，密码 `maameow123`）
2. `gradle :app:assembleRelease` + `zipalign` + `apksigner`
3. `adb push` → `pm install -r`
4. 引导到 LSPosed 管理器勾选作用域（`android` + `com.aliothmoon.maameow`），提示软重启
5. 推送 `launch_profile.sh` 到 `/data/local/tmp/`
6. 提示锁屏后按回车，自动以 `am start -W ... LAUNCH_PROFILE` 拉起
7. 实时 `logcat` 80s，关键看 tag `MaaMeowPatch`

### 手动部署

1. **LSPosed**（KernelSU / Magisk + Zygisk / APatch 均可）装好后：
   - 安装 debug 或自签后的 release APK。
   - LSPosed 管理器 → 模块列表 → 勾选「MaaMeow 锁屏拉起补丁」。
   - 作用域勾选 `android (system_server)` + `com.aliothmoon.maameow`。
   - 软重启 / 重启 framework。

2. **推送拉起脚本**：
   ```bash
   adb push scripts/launch_profile.sh /data/local/tmp/
   adb shell "chmod 755 /data/local/tmp/launch_profile.sh"
   ```

3. **锁屏下触发**（也可不锁，正常用也行）：
   ```bash
   adb shell "su -c \"sh /data/local/tmp/launch_profile.sh '配置-1'\""
   ```
   传一个 Profile **显示名**（不是 ID），脚本会从
   `/data/data/com.aliothmoon.maameow/files/datastore/task_chain.preferences_pb`
   里反查 UUID。

### 直发 Intent（不用脚本）

```bash
adb shell am start -W \
    -n com.aliothmoon.maameow/.MainActivity \
    -a com.aliothmoon.maameow.action.LAUNCH_PROFILE \
    --es extra_profile_id "<UUID>" \
    --ez extra_force_start true \
    --activity-new-task
```

`--activity-new-task` 在锁屏时是必须的（系统层 patch 会自动加一次，重复无害）。

## 验证 / 日志

```bash
# 单独监控
adb logcat -v time -s MaaMeowPatch:V com.aliothmoon.maameow:V
```

**成功**的日志序列（按时间顺序）：

```
MaaMeowPatch: ATMS.startActivityAsUser patched flags=0x...
MaaMeowPatch: executeRequest patched for com.aliothmoon.maameow/.MainActivity flags=0x...
MaaMeowPatch: AppSettingsManager constructed, runMode field = ... class=...
MaaMeowPatch: StateFlow<RunMode> -> forced BACKGROUND for instance ...
MaaMeowPatch: MainActivity.onNewIntent action=com.aliothmoon.maameow.action.LAUNCH_PROFILE extras=...
MaaMeowPatch: coordinator.onLaunch profile=<UUID> strategy=manual req=<UUID>
MaaMeowPatch: coordinator.handleLaunch ENTER profile=<UUID>
MaaMeowPatch: coordinator.promote profile=<UUID>
MaaMeowPatch: coordinator.onPageReady req=<UUID>
MaaMeowPatch: MaaCompositionService.state -> STARTING
MaaMeowPatch: MaaCompositionService.state -> RUNNING
...
MaaMeowPatch: MaaCompositionService.state -> IDLE (任务结束)
```

如果只看到 `executeRequest patched` 但没 `coordinator.onLaunch`，说明
系统层 patch 成功但应用层模块作用域没勾上；反之只看到应用层日志但 Activity
没起，说明系统层作用域没勾上。

## 关键参数 / 兼容点

- **ROM 字段名差异**：`ActivityStarter$Request` 在 AOSP/LineageOS 叫
  `allowBackgroundActivityStart`，MIUI/HyperOS/ColorOS 可能是
  `callerIsForeground` / `isCallingUidForeground` / `isBackgroundActivityStartAllowed` /
  `ignoreKeyguard` / `mIgnoreKeyguard`。`MainHook.setIfExists` 会逐一尝试，
  命中就 patch，没命中静默跳过。
- **AGP / Gradle**：本仓库 `compileSdk=36`、`AGP=8.7.3`、`Gradle=9.6.0`。
- **LSPosed minVersion**：metadata 声明 93。
- **签名**：自签 RSA-20400，36500 天有效（`keystore/maameow-patch.jks`，
  密码 `maameow123`，alias `maameowpatch`）。

## 已知问题

- **Android 16 `am start` 参数变化**：`--activity-new-task` 已移除，脚本改用 `-f 0x18480000` 传 Intent flags。
- **Cached Apps Freezer / ThanOS 等冻结**：锁屏后进程可能被冻结，Binder error -74，Intent 到了但业务不跑。脚本已加 `am unfreeze`；长期方案是在 ThanOS/系统里把 MaaMeow 加入不冻结白名单。
- 部分 ROM（OPPO ColorOS 13+ 实测）的 `Request.ignoreKeyguard` 字段存在但只在 `WindowManager` 层生效，`am start` 路径仍然被 KeyguardServiceDelegate 拦截。
  解决：见 `MainHook.hookSystemActivityStart` 的 `setIfExists` 列表追加字段。
- `FLAG_TURN_SCREEN_ON` 在部分 WearOS / 旧设备上被屏蔽——只要 Activity 成功
  onCreate，本模块就让它跑后台虚拟屏，任务会正常下发。
- Profile 名称含空格 / 特殊字符时 `launch_profile.sh` 的 awk 反查会失败，
  改成从 `Settings` → `Profile` 长按查看 UUID 直接传入 `extra_profile_id` 即可。

## License

MIT.
