#!/system/bin/sh
# Maa-Meow Profile 拉起脚本（锁屏可用，需要 LSPosed 补丁 + root）
# 原始版来自 MAA-Meow issue #102，本脚本仅做适配 + 默认 force_start。
#
# 用法:
#   su -c "sh launch_profile.sh '配置-1' [strategy_name]"
#
# 或者把脚本放到 /data/local/tmp/ 后:
#   su -c "sh /data/local/tmp/launch_profile.sh '配置-1'"

PREF="/data/data/com.aliothmoon.maameow/files/datastore/task_chain.preferences_pb"
PKG="com.aliothmoon.maameow"
ACT="com.aliothmoon.maameow.MainActivity"
ACTION="com.aliothmoon.maameow.action.LAUNCH_PROFILE"

# 锁屏/灭屏后进程可能被 Cached Apps Freezer 或 ThanOS 等工具冻结，
# 向 frozen 进程发 Binder 会 error -74，Intent 到了但任务无法执行。
# 拉起前先解冻（进程不存在时忽略错误）。
am unfreeze "$PKG" 2>/dev/null || true
# 若曾被 ThanOS「智能冻结」(PM disable)，用 ThanOS 内置 CLI 解冻
dumpsys tv_input unfreeze "$PKG" 0 2>/dev/null || true

if [ $# -lt 1 ]; then
    echo "用法: su -c \"sh $0 <Profile名称> [strategy]\""
    echo "示例: su -c \"sh $0 剿灭\""
    echo "      su -c \"sh $0 配置-1 manual\""
    exit 1
fi

PROFILE_NAME="$1"
STRATEGY_NAME="${2:-manual}"

if [ "$(id -u)" != "0" ]; then
    echo "ERROR: 需 root，请用 su 执行"
    echo "       su -c \"sh $0 '$PROFILE_NAME' '$STRATEGY_NAME'\""
    exit 1
fi

if [ ! -f "$PREF" ]; then
    echo "ERROR: 找不到 $PREF"
    echo "       请先在 MAA-Meow 里至少创建过一个 Profile"
    exit 1
fi

PROFILE_ID="$(
tr '\000' '\n' < "$PREF" | awk -v target="$PROFILE_NAME" '
{
    all = all $0 "\n"
}

function json_get_string(obj, key,    p, q, i, c, esc, val) {
    p = index(obj, "\"" key "\"")
    if (p == 0) return ""
    p += length(key) + 2
    q = index(substr(obj, p), ":")
    if (q == 0) return ""
    p += q
    while (p <= length(obj)) {
        c = substr(obj, p, 1)
        if (c != " " && c != "\t" && c != "\r" && c != "\n") break
        p++
    }
    if (substr(obj, p, 1) != "\"") return ""
    p++
    esc = 0
    val = ""
    for (i = p; i <= length(obj); i++) {
        c = substr(obj, i, 1)
        if (esc) {
            if (c == "\"") val = val "\""
            else if (c == "\\") val = val "\\"
            else if (c == "/") val = val "/"
            else if (c == "b") val = val "\b"
            else if (c == "f") val = val "\f"
            else if (c == "n") val = val "\n"
            else if (c == "r") val = val "\r"
            else if (c == "t") val = val "\t"
            else val = val c
            esc = 0
            continue
        }
        if (c == "\\") { esc = 1; continue }
        if (c == "\"") return val
        val = val c
    }
    return val
}

END {
    idx = index(all, "profiles")
    if (idx == 0) exit 2
    s = substr(all, idx + length("profiles"))
    off = index(s, "[")
    if (off == 0) exit 3
    start = idx + length("profiles") + off - 1
    depth = 0; in_str = 0; esc = 0; end = 0
    for (i = start; i <= length(all); i++) {
        c = substr(all, i, 1)
        if (in_str) {
            if (esc) esc = 0
            else if (c == "\\") esc = 1
            else if (c == "\"") in_str = 0
            continue
        }
        if (c == "\"") { in_str = 1; continue }
        if (c == "[") depth++
        else if (c == "]") { depth--; if (depth == 0) { end = i; break } }
    }
    if (end == 0) exit 4
    arr = substr(all, start, end - start + 1)
    brace = 0; in_str = 0; esc = 0; obj_start = 0
    for (i = 1; i <= length(arr); i++) {
        c = substr(arr, i, 1)
        if (in_str) {
            if (esc) esc = 0
            else if (c == "\\") esc = 1
            else if (c == "\"") in_str = 0
            continue
        }
        if (c == "\"") { in_str = 1; continue }
        if (c == "{") {
            if (brace == 0) obj_start = i
            brace++
        } else if (c == "}") {
            brace--
            if (brace == 0) {
                obj = substr(arr, obj_start, i - obj_start + 1)
                name = json_get_string(obj, "name")
                if (name == target) {
                    id = json_get_string(obj, "id")
                    if (id != "") { print id; exit 0 }
                }
            }
        }
    }
    exit 5
}
')"

RET=$?
if [ "$RET" != "0" ] || [ -z "$PROFILE_ID" ]; then
    echo "ERROR: 未找到 Profile: $PROFILE_NAME (awk exit=$RET)"
    exit 1
fi

REQUEST_ID="$(cat /proc/sys/kernel/random/uuid)"
SCHEDULED_TIME="$(date +%s)000"

echo "Profile name:  $PROFILE_NAME"
echo "Profile id:    $PROFILE_ID"
echo "Strategy:      $STRATEGY_NAME"
echo "Request id:    $REQUEST_ID"
echo "Time:          $SCHEDULED_TIME"
echo "---"

# Android 16+ 已移除 --activity-new-task / --activity-clear-task，改用 -f 传 Intent flags：
# NEW_TASK(0x10000000) | CLEAR_TOP(0x04000000) | SINGLE_TOP(0x20000000)
# | SHOW_WHEN_LOCKED(0x00080000) | TURN_SCREEN_ON(0x04000000)
INTENT_FLAGS=0x18480000

am start -W \
    -f "$INTENT_FLAGS" \
    -n "${PKG}/${ACT}" \
    -a "$ACTION" \
    --es extra_request_id "$REQUEST_ID" \
    --es extra_strategy_id "$STRATEGY_NAME" \
    --es extra_strategy_name "$STRATEGY_NAME" \
    --es extra_profile_id "$PROFILE_ID" \
    --el extra_scheduled_time "$SCHEDULED_TIME" \
    --ez extra_force_start true

echo "---"
echo "OK. 用 adb logcat -s MaaMeowPatch 验证任务是否起来"
