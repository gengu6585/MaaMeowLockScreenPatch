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
 *   extra_resource_path    可选；仅当本 Intent 携带此参时才 LoadResource 外部目录
 *                          （Meow 内部调用始终用内置资源，不读 conf、不粘滞）
 *   extra_resource_mode    append|replace（默认 append）
 *   extra_resource_overrides 可选，本地补丁 parent
 *   extra_reload_resource  true：注入前先 reset+load（replace 默认会 reload）
 *
 * Action: com.tinkerlab.maameowpatch.action.STOP_TASKS
 *   仅 stop 当前 MAA 任务链，不杀 APK / 默认不杀游戏
 *
 * Action: com.tinkerlab.maameowpatch.action.RELOAD_RESOURCE
 *   必须带 extra_resource_path；仅重载/注入外部资源，不跑任务
 * </pre>
 */
public final class CliTaskLaunchHelper {

    public static final String ACTION_RUN_TASKS =
            "com.tinkerlab.maameowpatch.action.RUN_TASKS";
    public static final String ACTION_STOP_TASKS =
            "com.tinkerlab.maameowpatch.action.STOP_TASKS";
    public static final String ACTION_RELOAD_RESOURCE =
            "com.tinkerlab.maameowpatch.action.RELOAD_RESOURCE";

    public static final String EXTRA_TASKS_JSON = "extra_tasks_json";
    public static final String EXTRA_TASKS_PATH = "extra_tasks_path";
    public static final String EXTRA_FORCE_START = "extra_force_start";
    public static final String EXTRA_FORCE_STOP_GAME = "extra_force_stop_game";
    public static final String EXTRA_WAIT_READY_MS = "extra_wait_ready_ms";
    public static final String EXTRA_CLOSEDOWN_AFTER = "extra_closedown_after";
    public static final String EXTRA_CLIENT_TYPE = "extra_client_type";
    public static final String EXTRA_RESOURCE_PATH = ResourcePathConfig.EXTRA_RESOURCE_PATH;
    public static final String EXTRA_RESOURCE_MODE = ResourcePathConfig.EXTRA_RESOURCE_MODE;
    public static final String EXTRA_RESOURCE_OVERRIDES = ResourcePathConfig.EXTRA_RESOURCE_OVERRIDES;
    public static final String EXTRA_RELOAD_RESOURCE = ResourcePathConfig.EXTRA_RELOAD_RESOURCE;

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
        if (ACTION_RELOAD_RESOURCE.equals(action)) {
            handleReloadResource(activity, intent, cl);
        } else if (ACTION_RUN_TASKS.equals(action)) {
            handleRunTasks(activity, intent, cl);
        } else if (ACTION_STOP_TASKS.equals(action)) {
            handleStopTasks(activity, cl);
        }
    }

    private static void handleReloadResource(Activity activity, Intent intent, ClassLoader cl) {
        Log.i(TAG, "RELOAD_RESOURCE → TaskExecutor (HTTP 同路径)");
        MeowRuntime.attach(activity, cl);
        runOnMainEventually(activity, () -> {
            try {
                JSONObject req = new JSONObject();
                req.put("resource_path", intent.getStringExtra(EXTRA_RESOURCE_PATH));
                if (intent.hasExtra(EXTRA_RESOURCE_MODE)) {
                    req.put("resource_mode", intent.getStringExtra(EXTRA_RESOURCE_MODE));
                }
                if (intent.hasExtra(EXTRA_RESOURCE_OVERRIDES)) {
                    req.put("resource_overrides", intent.getStringExtra(EXTRA_RESOURCE_OVERRIDES));
                }
                req.put("reload", intent.getBooleanExtra(EXTRA_RELOAD_RESOURCE, false));
                TaskExecutor.loadResource(req);
            } catch (Throwable t) {
                Log.e(TAG, "RELOAD_RESOURCE failed", t);
            }
        }, 500);
    }

    private static void handleStopTasks(Activity activity, ClassLoader cl) {
        Log.i(TAG, "STOP_TASKS → TaskExecutor (HTTP 同路径)");
        MeowRuntime.attach(activity, cl);
        runOnMainEventually(activity, () -> {
            try {
                TaskExecutor.stopTasks();
            } catch (Throwable t) {
                Log.e(TAG, "STOP_TASKS failed", t);
            }
        }, 200);
    }

    private static void handleRunTasks(Activity activity, Intent intent, ClassLoader cl) {
        MeowRuntime.attach(activity, cl);
        MeowHttpServerBootstrap.ensureStarted();
        final JSONObject req;
        try {
            req = intentToTaskRequest(intent);
        } catch (Throwable t) {
            Log.e(TAG, "RUN_TASKS load tasks failed", t);
            return;
        }
        Log.i(TAG, "RUN_TASKS → TaskExecutor force_start=" + req.optBoolean("force_start")
                + " force_stop_game=" + req.optBoolean("force_stop_game")
                + " resource=" + req.has("resource_path"));
        // 冷启动稍等 Koin；执行路径与 HTTP POST /v1/tasks 完全一致
        runOnMainEventually(activity, () -> {
            try {
                JSONObject out = TaskExecutor.startTasks(req);
                Log.i(TAG, "RUN_TASKS done accepted=" + out.optBoolean("accepted")
                        + " state=" + out.optString("state"));
            } catch (Throwable t) {
                Log.e(TAG, "RUN_TASKS failed", t);
            }
        }, 800);
    }

    /** Intent extras → HTTP 同构 JSON（保证能力一致）。 */
    static JSONObject intentToTaskRequest(Intent intent) throws Exception {
        String tasksJson = loadTasksJson(intent);
        JSONObject req = new JSONObject();
        String trimmed = tasksJson.trim();
        if (trimmed.startsWith("{")) {
            JSONObject root = new JSONObject(trimmed);
            req.put("tasks", root.getJSONArray("tasks"));
        } else {
            req.put("tasks", new JSONArray(trimmed));
        }
        req.put("force_start", intent.getBooleanExtra(EXTRA_FORCE_START, true));
        req.put("force_stop_game", intent.getBooleanExtra(EXTRA_FORCE_STOP_GAME, false));
        req.put("closedown_after", intent.getBooleanExtra(EXTRA_CLOSEDOWN_AFTER, false));
        req.put("wait_ready_ms", intent.getLongExtra(EXTRA_WAIT_READY_MS, 15_000L));
        String clientType = intent.getStringExtra(EXTRA_CLIENT_TYPE);
        if (clientType != null && !clientType.isEmpty()) {
            req.put("client_type", clientType);
        }
        if (intent.hasExtra(EXTRA_RESOURCE_PATH)) {
            req.put("resource_path", intent.getStringExtra(EXTRA_RESOURCE_PATH));
            if (intent.hasExtra(EXTRA_RESOURCE_MODE)) {
                req.put("resource_mode", intent.getStringExtra(EXTRA_RESOURCE_MODE));
            }
            if (intent.hasExtra(EXTRA_RESOURCE_OVERRIDES)) {
                req.put("resource_overrides", intent.getStringExtra(EXTRA_RESOURCE_OVERRIDES));
            }
            if (intent.getBooleanExtra(EXTRA_RELOAD_RESOURCE, false)) {
                req.put("reload_resource", true);
            }
        }
        return req;
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

    /** HTTP / Intent 共用 Copilot 参数规范化。 */
    public static String normalizeCopilotParamsPublic(String type, String paramsJson) throws Exception {
        return CopilotParamsNormalizer.normalize(type, paramsJson);
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
