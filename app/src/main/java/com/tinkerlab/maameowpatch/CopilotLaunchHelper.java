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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Intent 触发 MAA-Meow 抄作业列表（虚拟屏 Copilot）。
 * 热启动语义与 {@link CliTaskLaunchHelper} 对齐：默认不杀游戏、可选 StartUp/CloseDown。
 */
public final class CopilotLaunchHelper {

    public static final String ACTION_LAUNCH_COPILOT =
            "com.tinkerlab.maameowpatch.action.LAUNCH_COPILOT";
    public static final String EXTRA_TASK_LIST_PATH = "extra_task_list_path";
    public static final String EXTRA_CONFIG_PATH = "extra_config_path";
    public static final String EXTRA_TAB_INDEX = "extra_tab_index";
    public static final String EXTRA_FORCE_START = "extra_force_start";
    /** 是否前置 StartUp；默认 false。 */
    public static final String EXTRA_WITH_STARTUP = "extra_with_startup";
    /** AsyncConnect force_stop；默认 false。 */
    public static final String EXTRA_FORCE_STOP_GAME = "extra_force_stop_game";
    /** 任务链末尾 CloseDown；默认 false。 */
    public static final String EXTRA_CLOSEDOWN_AFTER = "extra_closedown_after";
    public static final String EXTRA_WAIT_READY_MS = "extra_wait_ready_ms";

    private static final String TAG = MainHook.TAG;
    private static final String MAA_PKG = MainHook.TARGET_PKG;
    private static final String DEFAULT_COPILOT_DIR =
            "/storage/emulated/0/Android/data/" + MAA_PKG + "/files/Maa/copilot";

    private CopilotLaunchHelper() {
    }

    public static void handleLaunchIntent(Activity activity, Intent intent, ClassLoader cl) {
        if (intent == null || !ACTION_LAUNCH_COPILOT.equals(intent.getAction())) {
            return;
        }

        final String taskListPath = intent.getStringExtra(EXTRA_TASK_LIST_PATH) != null
                ? intent.getStringExtra(EXTRA_TASK_LIST_PATH)
                : DEFAULT_COPILOT_DIR + "/task_list.json";
        final String configPath = intent.getStringExtra(EXTRA_CONFIG_PATH) != null
                ? intent.getStringExtra(EXTRA_CONFIG_PATH)
                : DEFAULT_COPILOT_DIR + "/config.json";
        final int tabIndex = intent.getIntExtra(EXTRA_TAB_INDEX, 0);
        final boolean forceStart = intent.getBooleanExtra(EXTRA_FORCE_START, true);
        final boolean withStartup = intent.getBooleanExtra(EXTRA_WITH_STARTUP, false);
        final boolean forceStopGame = intent.getBooleanExtra(EXTRA_FORCE_STOP_GAME, false);
        final boolean closedownAfter = intent.getBooleanExtra(EXTRA_CLOSEDOWN_AFTER, false);
        final long waitReadyMs = intent.getLongExtra(EXTRA_WAIT_READY_MS, 15_000L);

        Log.i(TAG, "LAUNCH_COPILOT taskList=" + taskListPath
                + " config=" + configPath + " tab=" + tabIndex
                + " force=" + forceStart + " withStartup=" + withStartup
                + " forceStopGame=" + forceStopGame
                + " closedownAfter=" + closedownAfter
                + " waitReadyMs=" + waitReadyMs);

        // 与 RUN_TASKS 一致：延迟到主线程，等 Koin/RemoteService；force_stop 粘滞到下次 Intent
        CliTaskLaunchHelper.setForceStopGame(forceStopGame);
        runOnMainEventually(activity, () -> {
            try {
                MeowBridge.waitServiceReady(cl, waitReadyMs);
                if (forceStart) {
                    stopIfRunning(activity, cl);
                    MeowBridge.waitServiceReady(cl, Math.min(waitReadyMs, 10_000L));
                }
                Object result = launchCopilotList(activity, cl, taskListPath, configPath, tabIndex,
                        withStartup, closedownAfter);
                MeowBridge.logStartResult(result);
                Log.i(TAG, "LAUNCH_COPILOT startCopilot invoked");
            } catch (Throwable t) {
                Log.e(TAG, "LAUNCH_COPILOT failed", t);
            }
        }, 800);
    }

    private static void stopIfRunning(Activity activity, ClassLoader cl) {
        try {
            String state = MeowBridge.compositionState(activity, cl);
            if (state.contains("RUNNING") || state.contains("STARTING")) {
                Log.i(TAG, "stopping running task before copilot, state=" + state);
                Object service = MeowBridge.resolveService(activity, cl,
                        "com.aliothmoon.maameow.domain.service.MaaCompositionService");
                MeowBridge.invokeSuspend(cl, service, "stop");
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopIfRunning skipped: " + t.getMessage());
        }
    }

    private static Object launchCopilotList(Activity activity, ClassLoader cl, String taskListPath,
            String configPath, int tabIndex, boolean withStartup, boolean closedownAfter)
            throws Exception {
        List<Object> items = loadTaskList(cl, taskListPath);
        Object config = loadConfig(cl, configPath);
        if (items.isEmpty()) {
            throw new IllegalStateException("task_list empty or missing: " + taskListPath);
        }

        Object manager = MeowBridge.resolveService(activity, cl,
                "com.aliothmoon.maameow.domain.service.CopilotManager");
        Class<?> configClass = cl.loadClass("com.aliothmoon.maameow.data.model.CopilotConfig");
        Method buildMethod = manager.getClass().getMethod(
                "buildListTask", int.class, List.class, configClass);
        @SuppressWarnings("unchecked")
        List<Object> tasks = (List<Object>) buildMethod.invoke(manager, tabIndex, items, config);
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalStateException("buildListTask returned empty");
        }

        String clientType = MeowBridge.getClientType(activity, cl);
        List<Object> allTasks = new ArrayList<>(tasks.size() + 2);
        if (withStartup) {
            allTasks.add(MeowBridge.buildTaskParams(cl, "StartUp",
                    "{\"client_type\":\"" + clientType
                            + "\",\"start_game_enabled\":true,\"account_name\":\"\"}"));
        }
        allTasks.addAll(tasks);
        if (closedownAfter) {
            allTasks.add(MeowBridge.buildTaskParams(cl, "CloseDown",
                    "{\"client_type\":\"" + clientType + "\"}"));
        }
        Log.i(TAG, "LAUNCH_COPILOT tasks: "
                + (withStartup ? "StartUp + " : "")
                + tasks.size() + " copilot item(s)"
                + (closedownAfter ? " + CloseDown" : ""));

        Object compositionService = MeowBridge.resolveService(activity, cl,
                "com.aliothmoon.maameow.domain.service.MaaCompositionService");
        return MeowBridge.invokeSuspend(cl, compositionService, "start",
                allTasks, clientType, false, null);
    }

    private static List<Object> loadTaskList(ClassLoader cl, String path) throws Exception {
        JSONArray arr = new JSONArray(readFile(path));
        Class<?> itemClass = cl.loadClass("com.aliothmoon.maameow.data.model.copilot.CopilotListItem");
        List<Object> list = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String name = o.optString("name", "");
            String filePath = o.optString("filePath", o.optString("file_path", ""));
            if (name.isEmpty() || filePath.isEmpty()) continue;
            if (!o.optBoolean("isChecked", o.optBoolean("is_checked", true))) {
                Log.i(TAG, "skip unchecked copilot item: " + name);
                continue;
            }
            Object item = itemClass.getConstructor(
                    String.class, String.class, boolean.class, int.class, boolean.class, String.class
            ).newInstance(
                    name, filePath,
                    o.optBoolean("isRaid", o.optBoolean("is_raid", false)),
                    o.optInt("copilotId", o.optInt("copilot_id", 0)),
                    true,
                    o.optString("source", "web")
            );
            list.add(item);
        }
        return list;
    }

    private static Object loadConfig(ClassLoader cl, String path) throws Exception {
        JSONObject o = new JSONObject(readFile(path));
        Class<?> configClass = cl.loadClass("com.aliothmoon.maameow.data.model.CopilotConfig");
        return configClass.getConstructor(
                boolean.class, boolean.class, boolean.class, boolean.class,
                int.class, boolean.class, int.class, boolean.class,
                boolean.class, int.class, boolean.class, String.class
        ).newInstance(
                o.optBoolean("formation", true),
                o.optBoolean("addTrust", o.optBoolean("add_trust", false)),
                o.optBoolean("ignoreRequirements", o.optBoolean("ignore_requirements", true)),
                o.optBoolean("useSanityPotion", o.optBoolean("use_sanity_potion", false)),
                o.optInt("supportUnitUsage", o.optInt("support_unit_usage", 0)),
                o.optBoolean("useSupportUnit", o.optBoolean("use_support_unit", false)),
                o.optInt("loopTimes", o.optInt("loop_times", 1)),
                o.optBoolean("loop", false),
                o.optBoolean("useFormation", o.optBoolean("use_formation", false)),
                o.optInt("formationIndex", o.optInt("formation_index", 1)),
                o.optBoolean("addUserAdditional", o.optBoolean("add_user_additional", false)),
                o.optString("userAdditional", o.optString("user_additional", ""))
        );
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
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    work.run();
                } finally {
                    latch.countDown();
                }
            }, delayMs);
        });
        try {
            if (!latch.await(180, TimeUnit.SECONDS)) {
                Log.e(TAG, "LAUNCH_COPILOT timed out waiting for main");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
