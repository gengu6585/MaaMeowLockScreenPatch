#!/bin/bash
# 编译、签名、安装补丁，并用 vector-cli 启用模块（无需重启手机）
set -euo pipefail

PROJ_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb -s emulator-5554}"
APK_NAME=app-release-signed.apk
APK_PATH="$PROJ_ROOT/app/build/outputs/apk/release/$APK_NAME"
KEYSTORE="$PROJ_ROOT/keystore/maameow-patch.jks"
MODULE_PKG=com.tinkerlab.maameowpatch
MAA_PKG=com.aliothmoon.maameow
LSP_CLI="/data/adb/lspd/cli"

cd "$PROJ_ROOT"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkey -v -keystore "$KEYSTORE" -alias maameowpatch \
    -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass maameow123 -keypass maameow123 \
    -dname "CN=MaaMeowPatch, OU=tinkerlab, O=tinkerlab, L=local, ST=local, C=CN"
fi

echo "[build] assembleRelease ..."
gradle :app:assembleRelease -q --no-daemon

BT=$(ls -d "$ANDROID_HOME"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -1)
BT_DIR=$(dirname "$BT")
"$BT" -f -p 4 "$PROJ_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk" "$APK_PATH"
"$BT_DIR/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:maameow123 --key-pass pass:maameow123 \
  --out "$APK_PATH" "$APK_PATH"

echo "[install] push & pm install ..."
$ADB push "$APK_PATH" /data/local/tmp/maameow-patch.apk
$ADB shell su -c "pm install -r -t /data/local/tmp/maameow-patch.apk"

echo "[lsp] enable module + scope ..."
$ADB push "$PROJ_ROOT/scripts/run_tasks.sh" /data/local/tmp/run_tasks.sh
$ADB push "$PROJ_ROOT/scripts/launch_profile.sh" /data/local/tmp/launch_profile.sh
$ADB shell su -c "chmod 755 /data/local/tmp/run_tasks.sh /data/local/tmp/launch_profile.sh"

$ADB shell su -c "$LSP_CLI modules enable $MODULE_PKG" || echo "WARN: vector-cli enable failed"
$ADB shell su -c "$LSP_CLI scope set $MODULE_PKG android/0 $MAA_PKG/0" || echo "WARN: vector-cli scope failed"

echo "[reload] soft reload framework ..."
$ADB shell su -c "$LSP_CLI config set scope com.tinkerlab.maameowpatch android/0,${MAA_PKG}/0" 2>/dev/null || true
# Vector: 重载目标应用即可生效
$ADB shell su -c "am force-stop $MAA_PKG; am force-stop $MODULE_PKG" || true

echo "Installed $APK_PATH"
echo "Trigger: MODE=copilot adb shell su -c 'sh /data/local/tmp/run_tasks.sh'"
