package com.tinkerlab.maameowpatch;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 Maa-Meow 当 maa-cli 用：外部 Intent 下发任意 AsstAppendTask 任务链。
 *
 * <pre>
 * Action: com.tinkerlab.maameowpatch.action.RUN_TASKS
 * Extras:
 *   extra_tasks_json / extra_tasks_path
 *     JSON array 或 {"tasks":[...]}，每项:
 *       {"type":"StartUp"|"Fight"|"Copilot"|..., "params":{...}|"{...}"}
 *     type 可用 MaaTaskType.value（StartUp）或 enum 名（START_UP）
 *   extra_force_start      默认 true：先 stop 当前 MAA 任务（不杀 APK）
 *   extra_force_stop_game  默认 false：AsyncConnect 是否 force_stop 游戏进程
 *   extra_wait_ready_ms    默认 15000：等待 RemoteService Connecting 结束
 *   extra_closedown_after  默认 false：任务链末尾追加 CloseDown
 *   extra_client_type      可选，覆盖 TaskChainState.clientType
 *
 * Action: com.tinkerlab.maameowpatch.action.STOP_TASKS
 *   仅 stop 当前 MAA 任务链，不杀 APK / 默认不杀游戏
 * </pre>
 */
public final class CliTaskLaunchHelper {

    public static final String ACTION_RUN_TASKS =
            "com.tinkerlab.maameowpatch.action.RUN_TASKS";
    public static final String ACTION_STOP_TASKS =
            "com.tinkerlab.maameowpatch.action.STOP_TASKS";

    public static final String EXTRA_TASKS_JSON = "extra_tasks_json";
    public static final String EXTRA_TASKS_PATH = "extra_tasks_path";
    public static final String EXTRA_FORCE_START = "extra_force_start";
    public static final String EXTRA_FORCE_STOP_GAME = "extra_force_stop_game";
    public static final String EXTRA_WAIT_READY_MS = "extra_wait_ready_ms";
    public static final String EXTRA_CLOSEDOWN_AFTER = "extra_closedown_after";
    public static final String EXTRA_CLIENT_TYPE = "extra_client_type";

    private static final String TAG = MainHook.TAG;
    /** 默认 false：热调试/连续任务不杀游戏；仅 RUN_TASKS/LAUNCH_COPILOT 显式 true 时才 force_stop。 */
    private static final AtomicBoolean FORCE_STOP_GAME = new AtomicBoolean(false);

    private CliTaskLaunchHelper() {
    }

    /** 供 MainHook 改写 buildConnectConfig 的 force_stop。 */
    public static boolean shouldForceStopGame() {
        return FORCE_STOP_GAME.get();
    }

    /** LAUNCH_COPILOT / RUN_TASKS 共用；返回旧值便于 finally 还原。 */
    public static boolean setForceStopGame(boolean forceStop) {
        return FORCE_STOP_GAME.getAndSet(forceStop);
    }

    public static void handleIntent(Activity activity, Intent intent, ClassLoader cl) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (ACTION_RUN_TASKS.equals(action)) {
            handleRunTasks(activity, intent, cl);
        } else if (ACTION_STOP_TASKS.equals(action)) {
            handleStopTasks(activity, cl);
        }
    }

    private static void handleStopTasks(Activity activity, ClassLoader cl) {
        Log.i(TAG, "STOP_TASKS requested");
        runOnMainEventually(activity, () -> {
            try {
                Object service = MeowBridge.resolveService(activity, cl,
                        "com.aliothmoon.maameow.domain.service.MaaCompositionService");
                Object result = MeowBridge.invokeSuspend(cl, service, "stop");
                Log.i(TAG, "STOP_TASKS result=" + result);
            } catch (Throwable t) {
                Log.e(TAG, "STOP_TASKS failed", t);
            }
        }, 200);
    }

    private static void handleRunTasks(Activity activity, Intent intent, ClassLoader cl) {
        final boolean forceStart = intent.getBooleanExtra(EXTRA_FORCE_START, true);
        final boolean forceStopGame = intent.getBooleanExtra(EXTRA_FORCE_STOP_GAME, false);
        final boolean closedownAfter = intent.getBooleanExtra(EXTRA_CLOSEDOWN_AFTER, false);
        final long waitReadyMs = intent.getLongExtra(EXTRA_WAIT_READY_MS, 15_000L);
        final String clientTypeOverride = intent.getStringExtra(EXTRA_CLIENT_TYPE);
        final String tasksJson;
        try {
            tasksJson = loadTasksJson(intent);
        } catch (Throwable t) {
            Log.e(TAG, "RUN_TASKS load tasks failed", t);
            return;
        }

        Log.i(TAG, "RUN_TASKS forceStart=" + forceStart
                + " forceStopGame=" + forceStopGame
                + " closedownAfter=" + closedownAfter
                + " waitReadyMs=" + waitReadyMs
                + " tasksLen=" + tasksJson.length());

        runOnMainEventually(activity, () -> {
            // 粘滞到下次 Intent：connect 可能在 start 返回后仍发生，不能 finally 立刻还原
            setForceStopGame(forceStopGame);
            try {
                MeowBridge.waitServiceReady(cl, waitReadyMs);
                if (forceStart) {
                    stopIfRunning(activity, cl);
                    // stop 后可能短暂 Connecting，再等一下
                    MeowBridge.waitServiceReady(cl, Math.min(waitReadyMs, 10_000L));
                }

                String clientType = clientTypeOverride != null && !clientTypeOverride.isEmpty()
                        ? clientTypeOverride
                        : MeowBridge.getClientType(activity, cl);
                List<Object> tasks = parseTasks(cl, tasksJson, closedownAfter, clientType);
                if (tasks.isEmpty()) {
                    throw new IllegalStateException("tasks empty");
                }

                Object compositionService = MeowBridge.resolveService(activity, cl,
                        "com.aliothmoon.maameow.domain.service.MaaCompositionService");
                Log.i(TAG, "RUN_TASKS appending " + tasks.size()
                        + " task(s), clientType=" + clientType
                        + ", state=" + MeowBridge.compositionState(activity, cl));
                Object result = MeowBridge.invokeSuspend(
                        cl, compositionService, "start", tasks, clientType, false, null);
                MeowBridge.logStartResult(result);
                Log.i(TAG, "RUN_TASKS start invoked");
            } catch (Throwable t) {
                Log.e(TAG, "RUN_TASKS failed", t);
            }
        }, 800);
    }

    private static void stopIfRunning(Activity activity, ClassLoader cl) {
        try {
            String state = MeowBridge.compositionState(activity, cl);
            if (state.contains("RUNNING") || state.contains("STARTING")) {
                Log.i(TAG, "RUN_TASKS stop current task, state=" + state);
                Object service = MeowBridge.resolveService(activity, cl,
                        "com.aliothmoon.maameow.domain.service.MaaCompositionService");
                MeowBridge.invokeSuspend(cl, service, "stop");
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopIfRunning skipped: " + t.getMessage());
        }
    }

    private static String loadTasksJson(Intent intent) throws Exception {
        String inline = intent.getStringExtra(EXTRA_TASKS_JSON);
        if (inline != null && !inline.trim().isEmpty()) {
            return inline.trim();
        }
        String path = intent.getStringExtra(EXTRA_TASKS_PATH);
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException(
                    "need " + EXTRA_TASKS_JSON + " or " + EXTRA_TASKS_PATH);
        }
        return readFile(path).trim();
    }

    private static List<Object> parseTasks(ClassLoader cl, String raw, boolean closedownAfter,
            String clientType) throws Exception {
        JSONArray arr;
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            JSONObject root = new JSONObject(trimmed);
            arr = root.getJSONArray("tasks");
        } else {
            arr = new JSONArray(trimmed);
        }

        List<Object> out = new ArrayList<>(arr.length() + 1);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);
            String type = item.optString("type", item.optString("name", ""));
            if (type.isEmpty()) {
                throw new IllegalArgumentException("tasks[" + i + "] missing type");
            }
            String paramsJson = normalizeParams(item.opt("params"));
            paramsJson = normalizeCopilotParams(type, paramsJson);
            out.add(MeowBridge.buildTaskParams(cl, type, paramsJson));
        }
        if (closedownAfter) {
            String closeParams = "{\"client_type\":\"" + clientType + "\"}";
            out.add(MeowBridge.buildTaskParams(cl, "CloseDown", closeParams));
        }
        return out;
    }

    private static String normalizeParams(Object params) throws Exception {
        if (params == null || params == JSONObject.NULL) return "{}";
        if (params instanceof JSONObject) return params.toString();
        if (params instanceof JSONArray) return params.toString();
        String s = String.valueOf(params).trim();
        if (s.isEmpty()) return "{}";
        // 已是 JSON 字符串
        if (s.startsWith("{") || s.startsWith("[")) return s;
        // 纯字符串参数不合法，包一层
        return new JSONObject().put("value", s).toString();
    }

    /**
     * 单作业 filename 模式若不带 stage_name / copilot_list，MaaCore 会跳过关卡导航。
     * 尽量对齐官方 UI：升成 copilot_list，stage_name 取显式字段或从文件名推断（如 97725_AD-1.json → AD-1）。
     */
    private static String normalizeCopilotParams(String type, String paramsJson) throws Exception {
        if (!isCopilotType(type)) return paramsJson;
        JSONObject p = new JSONObject(paramsJson);
        if (p.has("copilot_list") || p.has("list")) return paramsJson;

        String filename = p.optString("filename", "");
        if (filename.isEmpty()) return paramsJson;

        String stageName = p.optString("stage_name", "").trim();
        if (stageName.isEmpty()) {
            stageName = inferStageNameFromFilename(filename);
        }
        if (stageName.isEmpty()) {
            Log.w(TAG, "Copilot filename without stage_name; navigation may be skipped: " + filename);
            return paramsJson;
        }

        JSONObject item = new JSONObject();
        item.put("id", 0);
        item.put("filename", filename);
        item.put("stage_name", stageName);
        item.put("is_raid", p.optBoolean("is_raid", false));

        JSONArray list = new JSONArray();
        list.put(item);

        JSONObject out = new JSONObject(p.toString());
        out.remove("filename");
        out.remove("stage_name");
        out.remove("is_raid");
        out.put("copilot_list", list);
        if (!out.has("loop_times")) out.put("loop_times", 1);
        Log.i(TAG, "normalized Copilot → copilot_list stage_name=" + stageName);
        return out.toString();
    }

    private static boolean isCopilotType(String type) {
        String t = type == null ? "" : type.trim();
        return "Copilot".equalsIgnoreCase(t)
                || "COPILOT".equals(t)
                || "SSSCopilot".equalsIgnoreCase(t)
                || "SSS_COPILOT".equals(t);
    }

    private static String inferStageNameFromFilename(String filename) {
        String base = filename;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.endsWith(".json")) base = base.substring(0, base.length() - 5);
        // 97725_AD-1 / AD-1 / H5-1-Hard
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|[_-])((?:[A-Za-z]{1,6}-)?\\d+(?:-[A-Za-z0-9]+)*)(?:$|[_-])")
                .matcher(base);
        String last = "";
        while (m.find()) last = m.group(1);
        if (!last.isEmpty()) return last;
        // 纯 AD-1.json
        if (base.matches("[A-Za-z0-9]+-\\d+(?:-[A-Za-z0-9]+)*")) return base;
        return "";
    }

    private static String readFile(String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static void runOnMainEventually(Activity activity, Runnable work, long delayMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).postDelayed(work, delayMs);
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // 仍延迟，等 Compose/Koin 就绪
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        work.run();
                    } finally {
                        latch.countDown();
                    }
                }, delayMs);
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(180, TimeUnit.SECONDS)) {
                Log.e(TAG, "RUN_TASKS/STOP timed out waiting for main");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
