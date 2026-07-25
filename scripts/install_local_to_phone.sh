#!/bin/bash
# 本机编译 → 推到手机 adb 安装（手机上的 adb 设备 emulator-5554）
set -euo pipefail

PROJ_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PHONE_SSH="${PHONE_SSH:-root@tutu.gugenzzz.top}"
ADB_REMOTE="${ADB_REMOTE:-adb -s emulator-5554}"
APK_NAME=app-release-signed.apk
APK_PATH="$PROJ_ROOT/app/build/outputs/apk/release/$APK_NAME"
KEYSTORE="$PROJ_ROOT/keystore/maameow-patch.jks"
MODULE_PKG=com.tinkerlab.maameowpatch
MAA_PKG=com.aliothmoon.maameow
LSP_CLI="/data/adb/lspd/cli"

cd "$PROJ_ROOT"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# 兼容 local.properties
if [ -f local.properties ]; then
  sdk_line=$(grep -E '^sdk\.dir=' local.properties | head -1 || true)
  if [ -n "$sdk_line" ]; then
    sdk_dir="${sdk_line#sdk.dir=}"
    sdk_dir="${sdk_dir//\\/}"
    [ -d "$sdk_dir" ] && export ANDROID_HOME="$sdk_dir"
  fi
fi
# 若 /opt/android-sdk 不存在则写回本机 SDK
if [ ! -d "${ANDROID_HOME}" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
echo "sdk.dir=$ANDROID_HOME" > local.properties

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkey -v -keystore "$KEYSTORE" -alias maameowpatch \
    -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass maameow123 -keypass maameow123 \
    -dname "CN=MaaMeowPatch, OU=tinkerlab, O=tinkerlab, L=local, ST=local, C=CN"
fi

echo "[build] assembleRelease (ANDROID_HOME=$ANDROID_HOME) ..."
gradle :app:assembleRelease --no-daemon

BT=$(ls -d "$ANDROID_HOME"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -1)
BT_DIR=$(dirname "$BT")
UNSIGNED="$PROJ_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
"$BT" -f -p 4 "$UNSIGNED" "$APK_PATH"
"$BT_DIR/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:maameow123 --key-pass pass:maameow123 \
  --out "$APK_PATH" "$APK_PATH"

echo "[deploy] scp + install on $PHONE_SSH ..."
scp "$APK_PATH" "$PHONE_SSH:/tmp/maameow-patch.apk"
cp "$PROJ_ROOT/skill/maa-meow/scripts/run_tasks.sh" "$PROJ_ROOT/scripts/run_tasks.sh"
scp "$PROJ_ROOT/scripts/run_tasks.sh" "$PHONE_SSH:/tmp/run_tasks.sh"
rsync -az "$PROJ_ROOT/skill/maa-meow/" "$PHONE_SSH:/root/.cursor/skills/maa-meow/"

ssh "$PHONE_SSH" bash -s <<REMOTE
set -euo pipefail
ADB='$ADB_REMOTE'
\$ADB push /tmp/maameow-patch.apk /data/local/tmp/maameow-patch.apk
\$ADB shell su -c 'pm install -r -t /data/local/tmp/maameow-patch.apk'
\$ADB push /tmp/run_tasks.sh /data/local/tmp/run_tasks.sh
\$ADB shell su -c 'chmod 755 /data/local/tmp/run_tasks.sh'
\$ADB shell su -c '$LSP_CLI modules enable $MODULE_PKG' || echo 'WARN: lsp enable failed'
\$ADB shell su -c '$LSP_CLI scope set $MODULE_PKG android/0 $MAA_PKG/0' || echo 'WARN: lsp scope failed'
# 仅重载 Meow 使模块生效；不杀明日方舟
\$ADB shell su -c 'am force-stop $MAA_PKG' || true
echo OK
REMOTE

echo "Installed 1.2.14+. Example:"
echo "  ssh $PHONE_SSH \"adb -s emulator-5554 shell su -c 'curl -s http://127.0.0.1:17878/v1/health'\""
echo "  NOTE: install force-stops Meow only (may interrupt running tasks / VD)."
