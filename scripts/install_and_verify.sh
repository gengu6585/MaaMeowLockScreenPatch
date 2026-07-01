#!/bin/sh
# 端到端：编译 → 自签 → 推送到手机 → 启 LSPosed 模块 → 拉起 Profile → 监控日志
# 用法: sh scripts/install_and_verify.sh "配置-1"
#  1) 在手机端打开 LSPosed 管理器，启用本模块（重启一次或热重启 framework）
#  2) 确认 MAA-Meow 已安装并至少有 1 个 Profile
#  3) 执行本脚本

set -e

PROJ_ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK_NAME=app-release-signed.apk
APK_PATH="$PROJ_ROOT/app/build/outputs/apk/release/$APK_NAME"
DEBUG_APK="$PROJ_ROOT/app/build/outputs/apk/debug/app-debug.apk"
KEYSTORE="$PROJ_ROOT/keystore/maameow-patch.jks"
PROFILE_NAME="${1:-}"

cd "$PROJ_ROOT"

# 0) adb 设备
if ! adb get-state 1>/dev/null 2>&1; then
    echo "ERROR: 未检测到 adb 设备，请先连手机 + 打开 USB 调试"
    exit 1
fi

# 1) 生成 keystore（首次）
if [ ! -f "$KEYSTORE" ]; then
    echo "[1/6] 生成自签名 keystore ..."
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkey -v \
        -keystore "$KEYSTORE" \
        -alias maameowpatch \
        -keyalg RSA -keysize 2048 \
        -validity 36500 \
        -storepass maameow123 -keypass maameow123 \
        -dname "CN=MaaMeowPatch, OU=tinkerlab, O=tinkerlab, L=local, ST=local, C=CN"
fi

# 2) 编译 release
echo "[2/6] 编译 release APK ..."
gradle :app:assembleRelease -q
if [ ! -f "$PROJ_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
    echo "ERROR: 编译失败"
    exit 1
fi

# 3) 签名
echo "[3/6] 签名 ..."
ZIPALIGN="$ANDROID_HOME/build-tools/36.0.0/zipalign"
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
if [ ! -x "$ZIPALIGN" ]; then ZIPALIGN=$(ls -d $ANDROID_HOME/build-tools/*/zipalign 2>/dev/null | tail -1); fi
if [ ! -x "$APKSIGNER" ]; then APKSIGNER=$(ls -d $ANDROID_HOME/build-tools/*/apksigner 2>/dev/null | tail -1); fi
"$ZIPALIGN" -f -p 4 "$PROJ_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk" "$APK_PATH"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:maameow123 --key-pass pass:maameow123 \
    --out "$APK_PATH" "$APK_PATH"

# 4) 推送到手机
echo "[4/6] 推送 APK 到 /data/local/tmp ..."
adb push "$APK_PATH" /data/local/tmp/maameow-patch.apk
adb shell "pm install -r -t /data/local/tmp/maameow-patch.apk" || {
    echo "ERROR: 安装失败。可能 LSPosed 未启用本模块作用域，先到 LSPosed 管理器勾选 com.tinkerlab.maameowpatch"
    exit 1
}

# 5) 引导用户启用 + 重启
echo "[5/6] 请到 LSPosed 管理器："
echo "      - 模块列表里启用 'MaaMeow 锁屏拉起补丁'"
echo "      - 作用域勾选 'android' (system_server) + 'com.aliothmoon.maameow'"
echo "      - 软重启 (LSPosed 入口里有 '软重启') 或重启手机"
echo
read -p "  按回车继续（已经启用了）..."

# 6) 推送 + 触发拉起
echo "[6/6] 推送启动脚本 ..."
adb push "$PROJ_ROOT/scripts/launch_profile.sh" /data/local/tmp/launch_profile.sh
adb shell "chmod 755 /data/local/tmp/launch_profile.sh"

if [ -n "$PROFILE_NAME" ]; then
    echo
    echo "==== 锁屏手机，然后继续（也可不锁）===="
    read -p "  锁屏后按回车开始拉起 ..."

    echo
    echo "==== 拉起 Profile: $PROFILE_NAME ===="
    adb shell "su -c \"sh /data/local/tmp/launch_profile.sh '$PROFILE_NAME'\""
fi

echo
echo "==== 开始监控日志（80s）===="
echo "  关键看:"
echo "    - 'executeRequest patched'  -> 系统层 hook 命中"
echo "    - 'MainActivity.onNewIntent' / 'onCreate action=' -> Intent 到达"
echo "    - 'coordinator.onLaunch' / 'handleLaunch ENTER' -> 业务层接到"
echo "    - 'MaaCompositionService.state -> STARTING/RUNNING' -> 任务跑起来"
echo
timeout 80 adb logcat -v time \
    MaaMeowPatch:I \
    "com.aliothmoon.maameow:V" \
    AndroidRuntime:E \
    ActivityManager:W \
    "*:S"
echo
echo "==== 完成 ===="
echo "  - 想再次拉起:  adb shell \"su -c 'sh /data/local/tmp/launch_profile.sh <Profile>\""
echo "  - 想实时看日志: adb logcat -s MaaMeowPatch:V"
