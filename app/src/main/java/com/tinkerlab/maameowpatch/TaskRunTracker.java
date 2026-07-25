package com.tinkerlab.maameowpatch;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.tinkerlab.maameowpatch.http.ImportantLogFilter;
import com.tinkerlab.maameowpatch.http.LogTime;
import com.tinkerlab.maameowpatch.http.TaskEventBus;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 任务运行状态：以 Intent 查询为主，经 logcat 回传（adb/shell 可读）。
 *
 * <p>查询：{@link #ACTION_QUERY_STATUS} → logcat 标签 {@link #STATUS_LOG_TAG} 一行
 * {@code JSON:{...}}（am start 无法把 extras 直接返回给 shell，故用 logcat 作回传通道）。
 * <p>推送：状态变更 / 关键事件同样打到该 tag（{@code EVENT:{...}}）。
 * <p>可选落盘：{@link #STATUS_PATH} 仅作缓存，脚本应优先 Intent+logcat。
 */
public final class TaskRunTracker {

    public static final String STATUS_PATH = "/storage/emulated/0/maa/task_status.json";
    public static final String EVENTS_PATH = "/storage/emulated/0/maa/task_events.jsonl";
    public static final String ACTION_QUERY_STATUS =
            "com.tinkerlab.maameowpatch.action.QUERY_STATUS";
    /** logcat 回传专用 tag，脚本：adb logcat -s MaaMeowStatus:I */
    public static final String STATUS_LOG_TAG = "MaaMeowStatus";

    private static final String TAG = MainHook.TAG;
    private static volatile long sReplySeq = 0L;
    private static final String MEOW_LOG_DIR =
            "/storage/emulated/0/Android/data/com.aliothmoon.maameow/files/Maa/debug/gui";

    private static final AtomicBoolean sHooked = new AtomicBoolean(false);
    private static volatile String sRunId;
    private static volatile String sExecState = "IDLE";
    private static volatile String sResult; // SUCCESS|FAILED|STOPPED|null
    private static volatile String sFooterStatus;
    private static volatile String sMeowLog;
    private static volatile String sLastError;
    private static volatile String sTasksSummary;
    private static volatile long sStartedAt;
    private static volatile long sEndedAt;
    private static volatile boolean sActive;
    /** 用户/API 请求过 stop；用于忽略「新 run 刚启动时迟到的旧 session_end」。 */
    private static volatile boolean sStopRequested;
    /** 本轮见过「任务出错」；Meow footer=COMPLETED 时仍标 FAILED。 */
    private static volatile boolean sSawTaskError;

    private TaskRunTracker() {
    }

    public static void hook(ClassLoader cl) {
        if (!sHooked.compareAndSet(false, true)) return;
        try {
            Class<?> comp = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.domain.service.MaaCompositionService", cl);
            XposedHelpers.findAndHookMethod(comp, "setRunState",
                    "com.aliothmoon.maameow.domain.state.MaaExecutionState",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object st = param.args[0];
                            String name = st == null ? "null" : st.toString();
                            onExecState(name);
                        }
                    });
            Log.i(TAG, "hooked MaaCompositionService.setRunState");
        } catch (Throwable t) {
            Log.w(TAG, "hook setRunState failed: " + t.getMessage());
        }

        try {
            Class<?> logger = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.domain.service.MaaSessionLogger", cl);
            // endSession(String) / completeSession(String, String, LogLevel)
            XposedHelpers.findAndHookMethod(logger, "endSession", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            onSessionEnded((String) param.args[0], null);
                        }
                    });
            XposedHelpers.findAndHookMethod(logger, "completeSession",
                    String.class, String.class,
                    "com.aliothmoon.maameow.data.model.LogLevel",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            onSessionEnded((String) param.args[0], (String) param.args[1]);
                        }
                    });
            // append 关键 UI 日志到 events（过滤）
            XposedHelpers.findAndHookMethod(logger, "append",
                    "com.aliothmoon.maameow.data.model.LogItem",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object item = param.args[0];
                                if (item == null) return;
                                String content;
                                String level;
                                try {
                                    content = String.valueOf(
                                            XposedHelpers.callMethod(item, "getContent"));
                                    level = String.valueOf(
                                            XposedHelpers.callMethod(item, "getLevel"));
                                } catch (Throwable e2) {
                                    content = String.valueOf(
                                            XposedHelpers.getObjectField(item, "content"));
                                    level = String.valueOf(
                                            XposedHelpers.getObjectField(item, "level"));
                                }
                                maybeAppendEvent(level, content);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Log.i(TAG, "hooked MaaSessionLogger end/complete/append");
        } catch (Throwable t) {
            Log.w(TAG, "hook MaaSessionLogger failed: " + t.getMessage());
            sHooked.set(false);
        }

        writeStatusUnlocked(); // 初始 IDLE
    }

    public static void onRunTasksAccepted(String tasksSummary) {
        sRunId = UUID.randomUUID().toString().substring(0, 8);
        sTasksSummary = tasksSummary == null ? "" : tasksSummary;
        sResult = null;
        sFooterStatus = null;
        sLastError = null;
        sSawTaskError = false;
        sEndedAt = 0L;
        sStartedAt = System.currentTimeMillis();
        sActive = true;
        sStopRequested = false;
        sExecState = "STARTING";
        sMeowLog = null;
        appendEvent("run_accepted", sTasksSummary);
        writeStatusUnlocked();
    }

    public static void onStartResult(Object result) {
        if (result == null) {
            markTerminal("FAILED", "START_NULL", "start returned null");
            return;
        }
        String cn = result.getClass().getName();
        if (cn.endsWith("$Success") || cn.contains("Success")) {
            sExecState = "RUNNING";
            sMeowLog = findLatestMeowLog();
            appendEvent("start_ok", String.valueOf(result));
            writeStatusUnlocked();
            return;
        }
        markTerminal("FAILED", "START_REJECTED", String.valueOf(result));
    }

    public static void onStopRequested() {
        sStopRequested = true;
        appendEvent("stop_requested", "");
        // 结果由 session footer / IDLE 决定
        writeStatusUnlocked();
    }

    private static void onExecState(String state) {
        if (state == null) return;
        sExecState = state;
        appendEvent("exec_state", state);
        if (!sActive) {
            writeStatusUnlocked();
            return;
        }
        if ("ERROR".equals(state)) {
            if (sResult == null) {
                sResult = "FAILED";
                sFooterStatus = sFooterStatus == null ? "ERROR" : sFooterStatus;
                sEndedAt = System.currentTimeMillis();
                sActive = false;
            }
        } else if ("IDLE".equals(state)) {
            // 若尚未拿到 footer，稍后再由 refreshFromMeowLog 补全
            if (sResult == null) {
                refreshFromMeowLog();
                if (sResult == null && sFooterStatus == null) {
                    // 可能仍在写 footer；保持 active 直到 footer 或超时由脚本处理
                } else {
                    sEndedAt = System.currentTimeMillis();
                    sActive = false;
                }
            } else {
                sEndedAt = System.currentTimeMillis();
                sActive = false;
            }
        }
        writeStatusUnlocked();
    }

    private static void onSessionEnded(String footerStatus, String finalLog) {
        // 迟到的旧 session_end：新 run 已 accept 且未请求 stop 时忽略 STOPPED/CANCELLED
        if (!sActive) {
            appendEvent("session_end_stale", footerStatus == null ? "" : footerStatus);
            return;
        }
        if (("STOPPED".equals(footerStatus) || "CANCELLED".equals(footerStatus))
                && !sStopRequested
                && System.currentTimeMillis() - sStartedAt < 2500L) {
            appendEvent("session_end_race_ignored", footerStatus);
            return;
        }
        sFooterStatus = footerStatus;
        if (finalLog != null && !finalLog.isEmpty()) {
            sLastError = finalLog;
            maybeAppendEvent("ERROR", finalLog);
        }
        appendEvent("session_end", footerStatus == null ? "" : footerStatus);
        if ("STOPPED".equals(footerStatus) || "CANCELLED".equals(footerStatus)) {
            sResult = "STOPPED";
        } else if ("COMPLETED".equals(footerStatus) && !sSawTaskError) {
            sResult = "SUCCESS";
        } else {
            // Meow 常在 TaskChainError 后仍 footer=COMPLETED；有任务出错则 FAILED
            sResult = "FAILED";
        }
        sEndedAt = System.currentTimeMillis();
        sActive = false;
        if (sMeowLog == null) sMeowLog = findLatestMeowLog();
        writeStatusUnlocked();
    }

    /**
     * Intent QUERY_STATUS：刷新内存状态，并立刻用 logcat 回传一行 JSON。
     * 可选 extras：{@code extra_reply_token} — 原样写回 JSON，便于脚本匹配本次查询。
     */
    /** HTTP 刷新：无 Intent extras。 */
    public static void handleQueryStatus(Activity activity, ClassLoader cl) {
        handleQueryStatus(activity, null, cl);
    }

    /**
     * SSE 轻量轮询：只同步 composition 到内存，不扫 meow log、不打 logcat。
     */
    public static void syncCompositionOnly(Activity activity, ClassLoader cl) {
        if (activity == null || cl == null) return;
        try {
            applyCompositionState(MeowBridge.compositionState(activity, cl));
        } catch (Throwable t) {
            Log.w(TAG, "syncCompositionOnly: " + t.getMessage());
        }
    }

    public static void handleQueryStatus(Activity activity, Intent intent, ClassLoader cl) {
        String replyToken = intent != null ? intent.getStringExtra("extra_reply_token") : null;
        try {
            applyCompositionState(MeowBridge.compositionState(activity, cl));
        } catch (Throwable t) {
            Log.w(TAG, "query composition: " + t.getMessage());
        }
        refreshFromMeowLog();
        long seq = ++sReplySeq;
        publishStatusReply("query", replyToken, seq);
        Log.i(TAG, "QUERY_STATUS reply seq=" + seq
                + " state=" + sExecState + " result=" + sResult
                + " token=" + replyToken);
    }

    private static void applyCompositionState(String st) {
        if (st == null) return;
        if (st.contains("RUNNING")) sExecState = "RUNNING";
        else if (st.contains("STARTING")) sExecState = "STARTING";
        else if (st.contains("STOPPING")) sExecState = "STOPPING";
        else if (st.contains("ERROR")) sExecState = "ERROR";
        else if (st.contains("IDLE")) sExecState = "IDLE";
    }

    public static void handleIntent(Activity activity, Intent intent, ClassLoader cl) {
        if (intent == null || !ACTION_QUERY_STATUS.equals(intent.getAction())) return;
        handleQueryStatus(activity, intent, cl);
    }

    /** 紧凑 JSON 打到 MaaMeowStatus，供 Intent 查询回传 / 实时订阅。 */
    private static void publishStatusReply(String kind, String replyToken, long seq) {
        try {
            JSONObject o = buildStatusJson();
            o.put("kind", kind);
            o.put("seq", seq);
            if (replyToken != null && !replyToken.isEmpty()) {
                o.put("reply_token", replyToken);
            }
            // 单行，便于 logcat 解析
            Log.i(STATUS_LOG_TAG, "JSON:" + o.toString());
        } catch (Throwable t) {
            Log.e(TAG, "publishStatusReply failed", t);
        }
    }

    private static void publishEventReply(String type, String msg) {
        try {
            JSONObject o = new JSONObject();
            long now = System.currentTimeMillis();
            o.put("time", LogTime.format(now));
            o.put("run_id", sRunId == null ? JSONObject.NULL : sRunId);
            o.put("type", type);
            o.put("msg", msg == null ? "" : msg);
            o.put("state", sExecState == null ? "IDLE" : sExecState);
            o.put("result", sResult == null ? JSONObject.NULL : sResult);
            Log.i(STATUS_LOG_TAG, "EVENT:" + o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void refreshFromMeowLog() {
        String path = sMeowLog != null ? sMeowLog : findLatestMeowLog();
        if (path == null) return;
        sMeowLog = path;
        String footer = readLastFooterStatus(path);
        if (footer == null) return;
        sFooterStatus = footer;
        if ("STOPPED".equals(footer) || "CANCELLED".equals(footer)) {
            sResult = "STOPPED";
        } else if ("COMPLETED".equals(footer) && !sSawTaskError) {
            sResult = "SUCCESS";
        } else if (footer != null && !footer.isEmpty()) {
            sResult = "FAILED";
        }
        if (sEndedAt == 0L) sEndedAt = System.currentTimeMillis();
        sActive = false;
    }

    private static void markTerminal(String result, String footer, String err) {
        sResult = result;
        sFooterStatus = footer;
        sLastError = err;
        sEndedAt = System.currentTimeMillis();
        sActive = false;
        sExecState = "IDLE";
        appendEvent("terminal", result + " " + footer + " " + err);
        writeStatusUnlocked();
    }

    private static void maybeAppendEvent(String level, String content) {
        if (content != null && ("ERROR".equals(level) || content.contains("任务出错"))) {
            sSawTaskError = true;
            if (sLastError == null || sLastError.isEmpty()) {
                sLastError = ImportantLogFilter.truncate(content, 240);
            }
        }
        if (!ImportantLogFilter.isImportantMeowLog(level, content)) return;
        appendEvent("log", "[" + level + "] "
                + ImportantLogFilter.truncate(content, 240));
    }

    private static synchronized void appendEvent(String type, String msg) {
        publishEventReply(type, msg);
        try {
            TaskEventBus.INSTANCE.publish(type, msg, sRunId);
        } catch (Throwable ignored) {
        }
        // exec_state 极频繁：只推总线/logcat，不落盘以免拖慢 hook 线程
        if ("exec_state".equals(type)) return;
        try {
            File dir = new File("/storage/emulated/0/maa");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            JSONObject o = new JSONObject();
            long now = System.currentTimeMillis();
            o.put("time", LogTime.format(now));
            o.put("run_id", sRunId == null ? JSONObject.NULL : sRunId);
            o.put("type", type);
            o.put("msg", msg == null ? "" : msg);
            try (FileOutputStream fos = new FileOutputStream(EVENTS_PATH, true)) {
                fos.write((o + "\n").getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            new File(EVENTS_PATH).setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    /** HTTP / 脚本共用的当前状态快照。 */
    public static synchronized JSONObject statusJson() {
        return buildStatusJson();
    }

    private static JSONObject buildStatusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("run_id", sRunId == null ? JSONObject.NULL : sRunId);
            o.put("active", sActive);
            o.put("state", sExecState == null ? "IDLE" : sExecState);
            o.put("result", sResult == null ? JSONObject.NULL : sResult);
            o.put("footer_status", sFooterStatus == null ? JSONObject.NULL : sFooterStatus);
            o.put("started_at", sStartedAt);
            o.put("ended_at", sEndedAt);
            o.put("updated_at", System.currentTimeMillis());
            o.put("meow_log", sMeowLog == null ? JSONObject.NULL : sMeowLog);
            o.put("tasks", sTasksSummary == null ? "" : sTasksSummary);
            o.put("last_error", sLastError == null ? JSONObject.NULL : sLastError);
            o.put("status_path", STATUS_PATH);
            o.put("events_path", EVENTS_PATH);
            o.put("http_port", 17878);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static synchronized void writeStatusUnlocked() {
        try {
            File dir = new File("/storage/emulated/0/maa");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            JSONObject o = buildStatusJson();
            File f = new File(STATUS_PATH);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Throwable t) {
            Log.w(TAG, "write task_status failed: " + t.getMessage());
        }
    }

    private static String findLatestMeowLog() {
        try {
            File dir = new File(MEOW_LOG_DIR);
            File[] files = dir.listFiles((d, name) -> name.startsWith("meow_log_")
                    && name.endsWith(".log"));
            if (files == null || files.length == 0) return null;
            File best = null;
            for (File f : files) {
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
            return best == null ? null : best.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readLastFooterStatus(String path) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            String lastFooter = null;
            while ((line = br.readLine()) != null) {
                if (line.contains("\"type\":\"footer\"") || line.contains("\"type\": \"footer\"")) {
                    lastFooter = line;
                }
            }
            if (lastFooter == null) return null;
            JSONObject o = new JSONObject(lastFooter);
            return o.optString("status", null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 tasks 列表生成短摘要。 */
    public static String summarizeTasksJson(String tasksJson) {
        try {
            String t = tasksJson.trim();
            JSONArray arr = t.startsWith("{")
                    ? new JSONObject(t).getJSONArray("tasks")
                    : new JSONArray(t);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(arr.getJSONObject(i).optString("type", "?"));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "tasks";
        }
    }
}
