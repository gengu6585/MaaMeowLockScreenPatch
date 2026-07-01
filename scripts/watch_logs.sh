#!/bin/sh
# 监控 MAA-Meow 任务执行日志（锁屏拉起验证用）
# 用法: adb shell "sh watch_logs.sh"   或  sh watch_logs.sh
# 也可 adb logcat -s MaaMeowPatch:V  adb logcat -s MaaMeowPatch

set -e

PID_FILE=/data/local/tmp/maameow_patch_lastpid
PKG=com.aliothmoon.maameow

# 1) 启动一条实时 logcat 过滤本补丁 tag + maa-meow 进程
echo "==== 实时跟踪 MaaMeowPatch + $PKG ===="
echo "    关键节点: onLaunch / handleLaunch / promote / onPageReady / state ->"
echo "    Ctrl-C 退出"
echo

# 清空旧 buffer
logcat -c

# 跟踪 logcat，按时间顺序打印
(
    logcat -v time MaaMeowPatch:I "$PKG:V" AndroidRuntime:E ActivityManager:W "*:S" 2>&1
) &
LOG_PID=$!
trap "kill $LOG_PID 2>/dev/null; exit 0" INT TERM

# 2) 同时监控 maa-meow 进程存活
(
    while true; do
        PID=$(pidof "$PKG" 2>/dev/null | tr -d '\r\n')
        if [ -n "$PID" ]; then
            if [ ! -f "$PID_FILE" ] || [ "$(cat "$PID_FILE" 2>/dev/null)" != "$PID" ]; then
                echo "$PID" > "$PID_FILE"
                echo "[watch] $PKG pid=$PID alive"
            fi
        else
            if [ -f "$PID_FILE" ]; then
                echo "[watch] $PKG died (prev pid=$(cat "$PID_FILE"))"
                rm -f "$PID_FILE"
            fi
        fi
        sleep 2
    done
) &
WATCH_PID=$!
trap "kill $LOG_PID $WATCH_PID 2>/dev/null; exit 0" INT TERM

wait $LOG_PID
