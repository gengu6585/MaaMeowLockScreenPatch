package com.tinkerlab.maameowpatch;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.tinkerlab.maameowpatch.http.MeowHttpServer;
import com.tinkerlab.maameowpatch.http.StatusSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一任务启停（Intent / HTTP 共用）。须在拿到 Activity+ClassLoader 后调用。
 * <p>启动游戏进虚拟屏：与 Intent 一致，走任务链里的 {@code StartUp}（Meow AsyncConnect），
 * 或 {@link #controlGame} 的 {@code start}/{@code close}。
 */
public final class TaskExecutor {

    private static final String TAG = MainHook.TAG;

    private TaskExecutor() {
    }

    public static JSONObject startTasks(final JSONObject req) throws Exception {
        final Activity activity = MeowRuntime.activity();
        final ClassLoader cl = MeowRuntime.classLoader();
        if (activity == null || cl == null) {
            throw new IllegalStateException("Meow not ready (need launch app first)");
        }

        final boolean forceStart = req.optBoolean("force_start", true);
        final boolean forceStopGame = req.optBoolean("force_stop_game", false);
        final boolean closedownAfter = req.optBoolean("closedown_after", false);
        final long waitReadyMs = req.optLong("wait_ready_ms", 15_000L);
        final String clientTypeOverride = req.optString("client_type", "");
        final JSONArray tasksArr = req.has("tasks") ? req.getJSONArray("tasks") : req.optJSONArray("task");
        if (tasksArr == null || tasksArr.length() == 0) {
            throw new IllegalArgumentException("body.tasks required");
        }
        final String tasksJson = tasksArr.toString();

        TaskRunTracker.onRunTasksAccepted(TaskRunTracker.summarizeTasksJson(tasksJson));
        CliTaskLaunchHelper.setForceStopGame(forceStopGame);

        final AtomicReference<Object> startResult = new AtomicReference<>();
        final AtomicReference<Exception> err = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);

        Runnable work = () -> {
            try {
                MeowBridge.waitServiceReady(cl, waitReadyMs);

                if (req.has("resource_path") && req.optString("resource_path", "").length() > 0) {
                    android.content.Intent fake = new android.content.Intent();
                    fake.putExtra(ResourcePathConfig.EXTRA_RESOURCE_PATH, req.optString("resource_path"));
                    if (req.has("resource_mode")) {
                        fake.putExtra(ResourcePathConfig.EXTRA_RESOURCE_MODE, req.optString("resource_mode"));
                    }
                    if (req.has("resource_overrides")) {
                        fake.putExtra(ResourcePathConfig.EXTRA_RESOURCE_OVERRIDES,
                                req.optString("resource_overrides"));
                    }
                    if (req.optBoolean("reload_resource", false)) {
                        fake.putExtra(ResourcePathConfig.EXTRA_RELOAD_RESOURCE, true);
                    }
                    ResourceOverrideHelper.applyIfRequested(activity, fake, cl);
                    MeowBridge.waitServiceReady(cl, Math.min(waitReadyMs, 10_000L));
                }

                if (forceStart) {
                    stopIfRunning(activity, cl);
                    MeowBridge.waitServiceReady(cl, Math.min(waitReadyMs, 10_000L));
                }

                String clientType = clientTypeOverride != null && !clientTypeOverride.isEmpty()
                        ? clientTypeOverride
                        : MeowBridge.getClientType(activity, cl);
                List<Object> tasks = parseTasks(cl, tasksJson, closedownAfter, clientType);
                Object compositionService = MeowBridge.resolveService(activity, cl,
                        "com.aliothmoon.maameow.domain.service.MaaCompositionService");
                Object result = MeowBridge.invokeSuspend(
                        cl, compositionService, "start", tasks, clientType, false, null);
                MeowBridge.logStartResult(result);
                TaskRunTracker.onStartResult(result);
                startResult.set(result);
            } catch (Exception e) {
                err.set(e);
                TaskRunTracker.onStartResult(null);
            } finally {
                done.countDown();
            }
        };

        runOnMain(work, done, 180, "startTasks");
        if (err.get() != null) throw err.get();

        JSONObject out = refreshStatus();
        Object r = startResult.get();
        out.put("start_result", r == null ? JSONObject.NULL : String.valueOf(r));
        out.put("accepted", isStartSuccess(r));
        return out;
    }

    static boolean isStartSuccess(Object r) {
        if (r == null) return false;
        String cn = r.getClass().getName();
        return cn.endsWith("$Success") || cn.endsWith(".Success");
    }

    public static JSONObject stopTasks() throws Exception {
        final Activity activity = MeowRuntime.activity();
        final ClassLoader cl = MeowRuntime.classLoader();
        if (activity == null || cl == null) {
            throw new IllegalStateException("Meow not ready");
        }
        TaskRunTracker.onStopRequested();
        final AtomicReference<Object> stopResult = new AtomicReference<>();
        final AtomicReference<Exception> err = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Runnable work = () -> {
            try {
                Object service = MeowBridge.resolveService(activity, cl,
                        "com.aliothmoon.maameow.domain.service.MaaCompositionService");
                stopResult.set(MeowBridge.invokeSuspend(cl, service, "stop"));
            } catch (Exception e) {
                err.set(e);
            } finally {
                done.countDown();
            }
        };
        runOnMain(work, done, 60, "stop");
        if (err.get() != null) throw err.get();
        JSONObject out = refreshStatus();
        out.put("stop_result", String.valueOf(stopResult.get()));
        return out;
    }

    /**
     * 游戏进程控制（与 Intent 语义对齐）：
     * <ul>
     *   <li>{@code start} — 仅 StartUp（Meow 连接并拉起游戏进虚拟屏）</li>
     *   <li>{@code close} — CloseDown（优雅关游戏，不 force-stop）</li>
     *   <li>{@code kill} — am force-stop 游戏（显式危险操作，默认任务不用）</li>
     * </ul>
     * 日常任务请把 StartUp 放进 tasks 链，不必单独调本接口。
     */
    public static JSONObject controlGame(JSONObject req) throws Exception {
        String action = req.optString("action", "").trim().toLowerCase(Locale.ROOT);
        String clientType = req.optString("client_type", "Official");
        if (clientType.isEmpty()) clientType = "Official";
        boolean forceStopGame = req.optBoolean("force_stop_game", false);
        long waitReadyMs = req.optLong("wait_ready_ms", 15_000L);

        if ("kill".equals(action) || "force_stop".equals(action)) {
            Context ctx = MeowRuntime.appContext();
            String pkg = req.optString("package", ProcessProbe.GAME_PKG);
            boolean ok = ProcessProbe.forceStopPackage(ctx, pkg);
            JSONObject out = refreshStatus();
            out.put("ok", ok);
            out.put("action", "kill");
            out.put("package", pkg);
            return out;
        }

        JSONObject body = new JSONObject();
        JSONArray tasks = new JSONArray();
        JSONObject item = new JSONObject();
        if ("start".equals(action) || "startup".equals(action)) {
            item.put("type", "StartUp");
            JSONObject p = new JSONObject();
            p.put("client_type", clientType);
            p.put("start_game_enabled", true);
            p.put("account_name", req.optString("account_name", ""));
            item.put("params", p);
        } else if ("close".equals(action) || "closedown".equals(action) || "stop_game".equals(action)) {
            item.put("type", "CloseDown");
            item.put("params", new JSONObject().put("client_type", clientType));
        } else {
            throw new IllegalArgumentException("action must be start|close|kill");
        }
        tasks.put(item);
        body.put("tasks", tasks);
        body.put("force_start", true);
        body.put("force_stop_game", forceStopGame);
        body.put("closedown_after", false);
        body.put("wait_ready_ms", waitReadyMs);
        body.put("client_type", clientType);
        JSONObject out = startTasks(body);
        out.put("action", action);
        return out;
    }

    public static JSONObject loadResource(JSONObject req) throws Exception {
        Activity activity = MeowRuntime.activity();
        ClassLoader cl = MeowRuntime.classLoader();
        if (activity == null || cl == null) {
            throw new IllegalStateException("Meow not ready");
        }
        android.content.Intent fake = new android.content.Intent();
        fake.putExtra(ResourcePathConfig.EXTRA_RESOURCE_PATH, req.optString("resource_path", ""));
        if (req.has("resource_mode")) {
            fake.putExtra(ResourcePathConfig.EXTRA_RESOURCE_MODE, req.optString("resource_mode"));
        }
        if (req.has("resource_overrides")) {
            fake.putExtra(ResourcePathConfig.EXTRA_RESOURCE_OVERRIDES,
                    req.optString("resource_overrides"));
        }
        fake.putExtra(ResourcePathConfig.EXTRA_RELOAD_RESOURCE, req.optBoolean("reload", false));
        final AtomicReference<Exception> err = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Runnable work = () -> {
            try {
                MeowBridge.waitServiceReady(cl, 15_000L);
                ResourceOverrideHelper.applyIfRequested(activity, fake, cl);
            } catch (Exception e) {
                err.set(e);
            } finally {
                done.countDown();
            }
        };
        runOnMain(work, done, 120, "loadResource");
        if (err.get() != null) throw err.get();
        JSONObject out = refreshStatus();
        out.put("ok", true);
        out.put("resource_path", req.optString("resource_path", ""));
        return out;
    }

    /** GET /v1/status：完整同步（含 footer / Intent 兼容路径）。 */
    public static JSONObject refreshStatus() {
        Activity activity = MeowRuntime.activity();
        ClassLoader cl = MeowRuntime.classLoader();
        if (activity != null && cl != null) {
            TaskRunTracker.handleQueryStatus(activity, cl);
        }
        return mergeStatus();
    }

    /** SSE 轮询：只读 composition + 运行时字段，不打 logcat、不全量扫 footer。 */
    public static JSONObject refreshStatusLight() {
        Activity activity = MeowRuntime.activity();
        ClassLoader cl = MeowRuntime.classLoader();
        if (activity != null && cl != null) {
            TaskRunTracker.syncCompositionOnly(activity, cl);
        }
        return mergeStatus();
    }

    private static JSONObject mergeStatus() {
        try {
            return StatusSnapshot.merge(TaskRunTracker.statusJson(), buildRuntimeExtras());
        } catch (Exception e) {
            return TaskRunTracker.statusJson();
        }
    }

    private static JSONObject buildRuntimeExtras() throws Exception {
        JSONObject runtime = new JSONObject();
        runtime.put("meow_ready", MeowRuntime.ready());
        runtime.put("force_stop_game", CliTaskLaunchHelper.shouldForceStopGame());
        runtime.put("http_port", MeowHttpServer.port());
        runtime.put("http_running", MeowHttpServer.isRunning());
        runtime.put("api_version", MeowHttpServerBootstrap.VERSION);

        Context ctx = MeowRuntime.appContext();
        int gamePid = ProcessProbe.pid(ctx, ProcessProbe.GAME_PKG);
        int meowPid = ProcessProbe.pid(ctx, ProcessProbe.MEOW_PKG);
        runtime.put("game", StatusSnapshot.gameObject(
                ProcessProbe.GAME_PKG, gamePid > 0, gamePid));
        runtime.put("meow", StatusSnapshot.gameObject(
                ProcessProbe.MEOW_PKG, meowPid > 0, meowPid));

        Activity activity = MeowRuntime.activity();
        ClassLoader cl = MeowRuntime.classLoader();
        if (activity != null && cl != null) {
            // 与 UI/内部状态对齐：每次 status 都读 composition
            String comp = MeowBridge.compositionState(activity, cl);
            runtime.put("composition_state", comp);
            runtime.put("remote_bound", MeowBridge.isRemoteServiceBound(cl));
            runtime.put("remote_connecting", MeowBridge.isRemoteServiceConnecting(cl));
            try {
                runtime.put("client_type", MeowBridge.getClientType(activity, cl));
            } catch (Throwable t) {
                runtime.put("client_type", JSONObject.NULL);
            }
            // 便捷布尔：任务链是否在跑（同步 Meow 内部）
            runtime.put("task_running",
                    comp != null && (comp.contains("RUNNING") || comp.contains("STARTING")));
        } else {
            runtime.put("composition_state", "UNAVAILABLE");
            runtime.put("remote_bound", false);
            runtime.put("remote_connecting", false);
            runtime.put("task_running", false);
        }
        return runtime;
    }

    private static void runOnMain(Runnable work, CountDownLatch done, long timeoutSec, String tag)
            throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            work.run();
        } else {
            new Handler(Looper.getMainLooper()).post(work);
            if (!done.await(timeoutSec, TimeUnit.SECONDS)) {
                throw new IllegalStateException(tag + " timed out on main thread");
            }
        }
    }

    private static void stopIfRunning(Activity activity, ClassLoader cl) {
        try {
            String state = MeowBridge.compositionState(activity, cl);
            if (state != null
                    && (state.contains("RUNNING") || state.contains("STARTING"))) {
                Object service = MeowBridge.resolveService(activity, cl,
                        "com.aliothmoon.maameow.domain.service.MaaCompositionService");
                MeowBridge.invokeSuspend(cl, service, "stop");
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopIfRunning: " + t.getMessage());
        }
    }

    private static List<Object> parseTasks(ClassLoader cl, String raw, boolean closedownAfter,
            String clientType) throws Exception {
        JSONArray arr;
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            arr = new JSONObject(trimmed).getJSONArray("tasks");
        } else {
            arr = new JSONArray(trimmed);
        }
        List<Object> out = new ArrayList<>(arr.length() + 1);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);
            String type = item.optString("type", item.optString("name", ""));
            if (type.isEmpty()) throw new IllegalArgumentException("tasks[" + i + "] missing type");
            String paramsJson = normalizeParams(item.opt("params"));
            paramsJson = CliTaskLaunchHelper.normalizeCopilotParamsPublic(type, paramsJson);
            out.add(MeowBridge.buildTaskParams(cl, type, paramsJson));
        }
        if (closedownAfter) {
            out.add(MeowBridge.buildTaskParams(cl, "CloseDown",
                    "{\"client_type\":\"" + clientType + "\"}"));
        }
        return out;
    }

    private static String normalizeParams(Object params) throws Exception {
        if (params == null || params == JSONObject.NULL) return "{}";
        if (params instanceof JSONObject) return params.toString();
        if (params instanceof JSONArray) return params.toString();
        String s = String.valueOf(params).trim();
        if (s.isEmpty()) return "{}";
        if (s.startsWith("{") || s.startsWith("[")) return s;
        return new JSONObject().put("value", s).toString();
    }
}
