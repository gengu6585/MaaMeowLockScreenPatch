package com.tinkerlab.maameowpatch;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MAA-Meow 锁屏/后台 Intent 启动补丁：
 * 1. 系统层：允许锁屏下启动 MainActivity
 * 2. 应用层：跳过 30s 倒计时；promote 后自动触发 onScheduledExecutionPageReady（绕过 Compose 不渲染）
 */
public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "MaaMeowPatch";
    public static final String TARGET_PKG = "com.aliothmoon.maameow";
    public static final String TARGET_ACTIVITY = TARGET_PKG + ".MainActivity";
    public static final String ACTION_LAUNCH = TARGET_PKG + ".action.LAUNCH_PROFILE";
    public static final String ACTION_SHOW_SCHEDULE =
            TARGET_PKG + ".action.SHOW_SCHEDULE_EXECUTION";
    public static final String ACTION_SCHEDULE_TRIGGER = TARGET_PKG + ".SCHEDULE_TRIGGER";
    /** 外部触发抄作业列表（由本模块扩展，非 MAA 官方 action） */
    public static final String ACTION_LAUNCH_COPILOT = CopilotLaunchHelper.ACTION_LAUNCH_COPILOT;
    /** 通用任务链（maa-cli 风格 AsstAppendTask） */
    public static final String ACTION_RUN_TASKS = CliTaskLaunchHelper.ACTION_RUN_TASKS;
    public static final String ACTION_STOP_TASKS = CliTaskLaunchHelper.ACTION_STOP_TASKS;
    /** 仅配置/重载外部 resource（不跑任务） */
    public static final String ACTION_RELOAD_RESOURCE = CliTaskLaunchHelper.ACTION_RELOAD_RESOURCE;
    public static final String ACTION_QUERY_STATUS = TaskRunTracker.ACTION_QUERY_STATUS;

    private static final String ACTION_PAGE_READY_ALARM =
            "com.tinkerlab.maameowpatch.action.PAGE_READY_ALARM";
    private static final String EXTRA_PAGE_READY_REQUEST_ID = "extra_page_ready_request_id";

    private static volatile Object sBackgroundTaskViewModel;
    private static volatile Context sAppContext;
    private static volatile ClassLoader sTargetClassLoader;
    /** ScheduleExecutionService 运行期间绕过 KeyguardManager 锁屏检查 */
    private static volatile boolean sScheduleExecActive;
    private static volatile String sPendingPageReadyId;
    private static volatile String sInvokedPageReadyId;
    private static Runnable sPageReadyRunnable;
    private static volatile boolean sBlockExternalPageReady;
    private static PowerManager.WakeLock sPageReadyWakeLock;
    private static PendingIntent sPageReadyAlarmPi;

    /** 等 MainActivity / 虚拟屏就绪后再启动任务，避免过早 forceStop 游戏 */
    private static final long PAGE_READY_SERVICE_DELAY_MS = 800L;
    /** Doze 下 Handler 可能被冻结，AlarmManager 兜底（主路径成功后会被 cancel） */
    private static final long PAGE_READY_ALARM_MS = 2500L;
    private static final long PAGE_READY_WAKE_TIMEOUT_MS = 60_000L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || lpparam.packageName == null) return;

        if ("android".equals(lpparam.packageName)) {
            hookSystemActivityStart(lpparam.classLoader);
            return;
        }

        if (TARGET_PKG.equals(lpparam.packageName)) {
            hookApp(lpparam.classLoader);
        }
    }

    /* ========================================================================
     *  System layer
     * ====================================================================== */
    private void hookSystemActivityStart(ClassLoader cl) {
        String[] candidates = {
                "com.android.server.wm.ActivityStarter",
                "com.android.server.am.ActivityStarter",
        };
        for (String clsName : candidates) {
            try {
                final Class<?> starter = XposedHelpers.findClass(clsName, cl);
                final Class<?> requestCls = XposedHelpers.findClass(clsName + "$Request", cl);
                XposedHelpers.findAndHookMethod(starter, "executeRequest",
                        requestCls, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                patchLaunchIntent(param.args[0]);
                            }
                        });
                Log.i(TAG, "hooked " + clsName + ".executeRequest");
            } catch (Throwable t) {
                Log.w(TAG, "skip " + clsName + ": " + t.getMessage());
            }
        }

        try {
            final Class<?> atms = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerService", cl);
            XposedHelpers.findAndHookMethod(atms, "startActivityAsUser",
                    XposedHelpers.findClass("android.app.IApplicationThread", cl),
                    String.class, String.class, Intent.class, String.class,
                    "android.os.IBinder", String.class, int.class, int.class,
                    "android.app.ProfilerInfo", "android.os.Bundle",
                    int.class, "android.app.IUserHandle", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Intent intent = (Intent) param.args[3];
                            if (intent == null) return;
                            ComponentName cp = intent.getComponent();
                            if (cp == null || !TARGET_PKG.equals(cp.getPackageName())) return;
                            addLockScreenFlags(intent);
                            XposedBridge.log(TAG + ": ATMS patched flags=0x"
                                    + Integer.toHexString(intent.getFlags()));
                        }
                    });
            Log.i(TAG, "hooked ActivityTaskManagerService.startActivityAsUser");
        } catch (Throwable t) {
            Log.w(TAG, "ATMS hook skipped: " + t.getMessage());
        }
    }

    private static void patchLaunchIntent(Object req) {
        if (req == null) return;
        try {
            Intent intent = (Intent) XposedHelpers.getObjectField(req, "intent");
            if (intent == null) return;
            ComponentName cp = intent.getComponent();
            if (cp == null || !TARGET_PKG.equals(cp.getPackageName())) return;
            String cls = cp.getClassName();
            if (!TARGET_ACTIVITY.equals(cls) && !".MainActivity".equals(cls)) return;

            addLockScreenFlags(intent);
            setIfExists(req, "allowBackgroundActivityStart", true);
            setIfExists(req, "callerIsForeground", true);
            setIfExists(req, "isCallingUidForeground", true);
            setIfExists(req, "isBackgroundActivityStartAllowed", true);
            setIfExists(req, "ignoreKeyguard", true);
            setIfExists(req, "mIgnoreKeyguard", true);

            XposedBridge.log(TAG + ": executeRequest patched flags=0x"
                    + Integer.toHexString(intent.getFlags()));
        } catch (Throwable ignored) {
        }
    }

    private static void addLockScreenFlags(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | 0x00080000  // FLAG_ACTIVITY_SHOW_WHEN_LOCKED
                | 0x4000000); // FLAG_ACTIVITY_TURN_SCREEN_ON
    }

    private static void setIfExists(Object obj, String field, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Throwable ignored) {
        }
    }

    /* ========================================================================
     *  App layer
     * ====================================================================== */
    private void hookApp(ClassLoader cl) {
        sTargetClassLoader = cl;
        TaskRunTracker.hook(cl);
        ResourceOverrideHelper.hook(cl);
        hookScheduleExecutionService(cl);
        hookScheduleLockscreenBypass(cl);
        hookScheduleLaunchIntent(cl);
        hookSkipForegroundScheduleDelay(cl);
        hookScheduledExecutionRequest(cl);
        hookBackgroundTaskViewModel(cl);
        hookCoordinator(cl);
        hookStartAppSkipForceStop(cl);
        hookConnectForceStopFlag(cl);
        hookMainActivity(cl);
    }

    /**
     * MaaCompositionService.buildConnectConfig 默认 force_stop=true 会杀游戏。
     * RUN_TASKS 可通过 extra_force_stop_game=false 改写为 false，便于热调试。
     */
    private void hookConnectForceStopFlag(ClassLoader cl) {
        try {
            Class<?> svc = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.domain.service.MaaCompositionService", cl);
            XposedHelpers.findAndHookMethod(svc, "buildConnectConfig",
                    int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (CliTaskLaunchHelper.shouldForceStopGame()) return;
                            Object result = param.getResult();
                            if (!(result instanceof String)) return;
                            String json = (String) result;
                            if (!json.contains("\"force_stop\"")) return;
                            String patched = json
                                    .replace("\"force_stop\":true", "\"force_stop\":false")
                                    .replace("\"force_stop\": true", "\"force_stop\": false");
                            if (!patched.equals(json)) {
                                param.setResult(patched);
                                Log.i(TAG, "buildConnectConfig force_stop=false");
                            }
                        }
                    });
            Log.i(TAG, "hooked buildConnectConfig force_stop flag");
        } catch (Throwable t) {
            Log.e(TAG, "hookConnectForceStopFlag failed", t);
        }
    }

    /**
     * 内置定时入口：Alarm → ScheduleReceiver → ScheduleExecutionService.handleTrigger。
     * 官方在 L112 锁屏直接 SKIPPED_LOCKED；此处标记 Service 生命周期并配合 Keyguard hook。
     */
    private void hookScheduleExecutionService(ClassLoader cl) {
        try {
            Class<?> svc = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.schedule.service.ScheduleExecutionService", cl);
            XposedHelpers.findAndHookMethod(svc, "onStartCommand",
                    Intent.class, int.class, int.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            Intent in = (Intent) param.args[0];
                            if (in != null && ACTION_PAGE_READY_ALARM.equals(in.getAction())) {
                                return handlePageReadyAlarmStart((Service) param.thisObject, in);
                            }
                            Object result = XposedBridge.invokeOriginalMethod(
                                    param.method, param.thisObject, param.args);
                            if (in != null && ACTION_SCHEDULE_TRIGGER.equals(in.getAction())) {
                                sScheduleExecActive = true;
                                rememberAppContext((Context) param.thisObject);
                                Log.i(TAG, "ScheduleExecutionService started (internal alarm)");
                            }
                            return result;
                        }
                    });
            for (String m : new String[]{"shutdownService", "onDestroy"}) {
                try {
                    XposedHelpers.findAndHookMethod(svc, m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sScheduleExecActive = false;
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "hooked ScheduleExecutionService lifecycle");
        } catch (Throwable t) {
            Log.e(TAG, "hookScheduleExecutionService failed", t);
        }
    }

    /** 内置定时 toLaunchIntent / shell startActivity 补上锁屏 flags */
    private void hookScheduleLaunchIntent(ClassLoader cl) {
        try {
            Class<?> reqCls = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.schedule.model.ScheduledExecutionRequest", cl);
            XposedHelpers.findAndHookMethod(reqCls, "toLaunchIntent",
                    android.content.Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            patchScheduleIntent((Intent) param.getResult());
                        }
                    });
            Class<?> utils = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.remote.internal.ActivityUtils", cl);
            XC_MethodHook patchStart = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Intent) {
                        patchScheduleIntent((Intent) param.args[0]);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(utils, "startActivity", Intent.class, patchStart);
            try {
                XposedHelpers.findAndHookMethod(utils, "startActivity",
                        Intent.class, int.class, patchStart);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "hooked toLaunchIntent + ActivityUtils.startActivity");
        } catch (Throwable t) {
            Log.e(TAG, "hookScheduleLaunchIntent failed", t);
        }
    }

    private static void patchScheduleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        ComponentName cp = intent.getComponent();
        if (!ACTION_SHOW_SCHEDULE.equals(action)
                && (cp == null || !TARGET_ACTIVITY.equals(cp.getClassName()))) {
            return;
        }
        addLockScreenFlags(intent);
    }

    /**
     * ScheduleExecutionService.handleTrigger 内 isKeyguardLocked → 视为未锁屏。
     */
    private void hookScheduleLockscreenBypass(ClassLoader cl) {
        XC_MethodHook bypass = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (shouldBypassKeyguardForSchedule()) {
                    param.setResult(false);
                    Log.i(TAG, "schedule lockscreen bypass");
                }
            }
        };
        try {
            Class<?> kgm = XposedHelpers.findClass("android.app.KeyguardManager", cl);
            XposedHelpers.findAndHookMethod(kgm, "isKeyguardLocked", bypass);
            try {
                XposedHelpers.findAndHookMethod(kgm, "isDeviceLocked", bypass);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "hooked KeyguardManager for internal schedule");
        } catch (Throwable t) {
            Log.e(TAG, "hookScheduleLockscreenBypass failed", t);
        }
    }

    private static boolean shouldBypassKeyguardForSchedule() {
        return sScheduleExecActive || isScheduleExecutionContext();
    }

    /** 前台静默定时路径 ForegroundScheduleStarter 里仍有 30s delay，一并跳过 */
    private void hookSkipForegroundScheduleDelay(ClassLoader cl) {
        try {
            Class<?> delayKt = XposedHelpers.findClass("kotlinx.coroutines.DelayKt", cl);
            XposedBridge.hookAllMethods(delayKt, "delay", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isForegroundScheduleStarterContext()) return;
                    if (param.args.length < 1) return;
                    long ms = toLong(param.args[0]);
                    if (ms >= 5_000L) {
                        param.args[0] = 1L;
                        Log.i(TAG, "ForegroundScheduleStarter delay " + ms + "ms -> 1ms");
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "hookSkipForegroundScheduleDelay skipped: " + t.getMessage());
        }
    }

    private static boolean isScheduleExecutionContext() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String cn = e.getClassName();
            if (cn.contains("ScheduleExecutionService")
                    || cn.contains("ScheduleReceiver")
                    || cn.contains("ForegroundScheduleStarter")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isForegroundScheduleStarterContext() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if (e.getClassName().contains("ForegroundScheduleStarter")) return true;
        }
        return false;
    }

    private static long toLong(Object v) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        return Long.parseLong(String.valueOf(v));
    }

    /**
     * fromExternalIntent 每次 randomUUID()，Activity relaunch 会产生新 requestId 导致重复/错乱。
     * 改用 Intent 里脚本传入的 extra_request_id，使 isDuplicate 能正确去重。
     */
    private void hookScheduledExecutionRequest(ClassLoader cl) {
        try {
            Class<?> reqCls = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.schedule.model.ScheduledExecutionRequest", cl);
            Class<?> companionCls = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.schedule.model.ScheduledExecutionRequest$Companion", cl);
            final Class<?> requestClass = reqCls;
            XC_MethodHook fixRequestId = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    patchExternalIntentResult(requestClass, param.args[0], param);
                }
            };
            XposedHelpers.findAndHookMethod(companionCls, "fromExternalIntent", Intent.class, fixRequestId);
            Log.i(TAG, "hooked ScheduledExecutionRequest.Companion.fromExternalIntent");
        } catch (Throwable t) {
            Log.e(TAG, "hookScheduledExecutionRequest failed", t);
        }
    }

    private static void patchExternalIntentResult(Class<?> reqCls, Object intentObj,
            XC_MethodHook.MethodHookParam param) {
        if (!(intentObj instanceof Intent)) return;
        Intent intent = (Intent) intentObj;
        if (param.getResult() == null || !ACTION_LAUNCH.equals(intent.getAction())) return;
        String reqId = intent.getStringExtra("extra_request_id");
        if (reqId == null || reqId.isEmpty()) return;
        String profileId = intent.getStringExtra("extra_profile_id");
        if (profileId == null) return;
        long scheduledTime = intent.getLongExtra("extra_scheduled_time", 0L);
        if (scheduledTime <= 0L) scheduledTime = System.currentTimeMillis();
        boolean forceStart = intent.getBooleanExtra("extra_force_start", false);
        try {
            Object fixed = XposedHelpers.newInstance(reqCls,
                    reqId, "", "外部触发", profileId, scheduledTime, forceStart);
            param.setResult(fixed);
            Log.i(TAG, "fromExternalIntent use stable requestId=" + reqId);
        } catch (Throwable t) {
            Log.w(TAG, "patchExternalIntentResult failed", t);
        }
    }

    private void hookBackgroundTaskViewModel(ClassLoader cl) {
        try {
            Class<?> vmCls = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.presentation.viewmodel.BackgroundTaskViewModel", cl);
            Class<?> reqCls = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.schedule.model.ScheduledExecutionRequest", cl);

            XposedHelpers.findAndHookMethod(vmCls, "onScheduledLaunch", reqCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sBackgroundTaskViewModel = param.thisObject;
                    rememberAppContext(readContextField(param.thisObject, "application"));
                    Object r = param.args[0];
                    Log.i(TAG, "onScheduledLaunch profile=" + readStringField(r, "profileId")
                            + " req=" + readStringField(r, "requestId"));
                }
            });

            XposedHelpers.findAndHookMethod(vmCls, "onServiceReconnected",
                    XposedHelpers.findClass("com.aliothmoon.maameow.RemoteService", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (sBlockExternalPageReady && sPendingPageReadyId != null) {
                                Log.i(TAG, "RemoteService connected, trigger pageReady soon");
                                triggerPageReadyAfterServiceReady();
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(vmCls, "onScheduledExecutionPageReady",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (sBlockExternalPageReady) {
                                Log.i(TAG, "blocked premature pageReady req=" + param.args[0]);
                                param.setResult(null);
                                return;
                            }
                            Log.i(TAG, "onScheduledExecutionPageReady req=" + param.args[0]);
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hookBackgroundTaskViewModel failed", t);
        }
    }

    private void hookCoordinator(ClassLoader cl) {
        try {
            Class<?> coord = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.schedule.service.ScheduledLaunchCoordinator", cl);
            Class<?> reqCls = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.schedule.model.ScheduledExecutionRequest", cl);

            XposedHelpers.findAndHookMethod(coord, "onLaunch", reqCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object r = param.args[0];
                    Log.i(TAG, "coordinator.onLaunch strategy=" + readStringField(r, "strategyName")
                            + " profile=" + readStringField(r, "profileId"));
                }
            });

            // 跳过 30s 倒计时，直接进入 promote
            XposedHelpers.findAndHookMethod(coord, "startCountdown", reqCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object self = param.thisObject;
                    Object request = param.args[0];
                    Log.i(TAG, "startCountdown skipped -> promote profile="
                            + readStringField(request, "profileId"));
                    XposedHelpers.callMethod(self, "promote", request);
                    param.setResult(null);
                }
            });

            // promote 后 UI 在锁屏下不会调用 onScheduledExecutionPageReady，由 patch 代劳
            XposedHelpers.findAndHookMethod(coord, "promote", reqCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object request = param.args[0];
                    final String reqId = readStringField(request, "requestId");
                    Object coordSelf = param.thisObject;
                    if (reqId.equals(readObjectFieldString(coordSelf, "startingRequestId"))) {
                        Log.i(TAG, "promote skipped pageReady, already starting req=" + reqId);
                        return;
                    }
                    sBlockExternalPageReady = true;
                    if (!reqId.equals(sInvokedPageReadyId)) {
                        sInvokedPageReadyId = null;
                    }
                    Log.i(TAG, "coordinator.promote done, scheduling pageReady req=" + reqId);
                    triggerPageReady(reqId);
                }
            });

            XposedHelpers.findAndHookMethod(coord, "onPageReady",
                    String.class,
                    XposedHelpers.findClass("kotlin.jvm.functions.Function2", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "coordinator.onPageReady req=" + param.args[0]);
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "hookCoordinator failed", t);
        }
    }

    private static void triggerPageReadyAfterServiceReady() {
        Object vm = sBackgroundTaskViewModel;
        if (vm == null || sPendingPageReadyId == null) return;
        schedulePageReadyOnMain(vm, sPendingPageReadyId, PAGE_READY_SERVICE_DELAY_MS,
                "RemoteService connected");
    }

    private static void invokePageReady(Object viewModel, String id) {
        if (id == null || id.equals(sInvokedPageReadyId)) return;
        cancelPageReadyCallbacks();
        sInvokedPageReadyId = id;
        sBlockExternalPageReady = false;
        sPendingPageReadyId = null;
        releasePageReadyWakeLock();
        try {
            XposedHelpers.callMethod(viewModel, "onScheduledExecutionPageReady", id);
            Log.i(TAG, "triggerPageReady invoked req=" + id);
        } catch (Throwable t) {
            Log.e(TAG, "triggerPageReady failed req=" + id, t);
        }
    }

    private static void triggerPageReady(String requestId) {
        Object vm = sBackgroundTaskViewModel;
        if (vm == null) {
            Log.w(TAG, "triggerPageReady: ViewModel not ready, req=" + requestId);
            return;
        }
        sPendingPageReadyId = requestId;
        acquirePageReadyWakeLock();
        schedulePageReadyAlarm(requestId);

        schedulePageReadyOnMain(vm, requestId, PAGE_READY_SERVICE_DELAY_MS, "promote");
        Log.i(TAG, "pageReady scheduled in " + PAGE_READY_SERVICE_DELAY_MS + "ms req=" + requestId
                + " remoteConnected=" + isRemoteServiceConnected());
    }

    /**
     * StartGameTask 默认 forceStop 会杀掉虚拟屏上已运行的游戏。
     * 若游戏已在目标 display 上运行，跳过 forceStop 避免「起来就挂」。
     */
    private void hookStartAppSkipForceStop(ClassLoader cl) {
        XC_MethodHook skipForceStop = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                maybeSkipForceStop(param.args);
            }
        };
        try {
            Class<?> utils = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.remote.internal.ActivityUtils", cl);
            XposedHelpers.findAndHookMethod(utils, "startApp",
                    String.class, int.class, boolean.class, skipForceStop);
            try {
                XposedHelpers.findAndHookMethod(utils, "startApp",
                        String.class, int.class, boolean.class, boolean.class, skipForceStop);
            } catch (Throwable ignored) {
            }
            Class<?> driver = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.maa.DriverClass", cl);
            XposedHelpers.findAndHookMethod(driver, "startApp",
                    String.class, int.class, boolean.class, skipForceStop);
            Log.i(TAG, "hooked startApp skip forceStop when on display");
        } catch (Throwable t) {
            Log.e(TAG, "hookStartAppSkipForceStop failed", t);
        }
    }

    private static void maybeSkipForceStop(Object[] args) {
        if (args.length < 3 || !(args[0] instanceof String)) return;
        if (!(args[2] instanceof Boolean) || !((Boolean) args[2])) return;
        String packageName = (String) args[0];
        int displayId = args[1] instanceof Integer ? (Integer) args[1] : 0;
        if (displayId <= 0 || sTargetClassLoader == null) return;
        try {
            Class<?> utils = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.remote.internal.ActivityUtils", sTargetClassLoader);
            Boolean onDisplay = (Boolean) XposedHelpers.callStaticMethod(
                    utils, "isAppOnDisplay", packageName, displayId);
            if (Boolean.TRUE.equals(onDisplay)) {
                args[2] = false;
                Log.i(TAG, "startApp skip forceStop pkg=" + packageName
                        + " displayId=" + displayId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "maybeSkipForceStop failed: " + t.getMessage());
        }
    }

    private static void schedulePageReadyOnMain(Object viewModel, String requestId,
            long delayMs, String reason) {
        Handler h = new Handler(Looper.getMainLooper());
        if (sPageReadyRunnable != null) {
            h.removeCallbacks(sPageReadyRunnable);
        }
        final Object vm = viewModel;
        final String id = requestId;
        sPageReadyRunnable = () -> {
            if (id.equals(sInvokedPageReadyId)) return;
            Log.i(TAG, "pageReady main callback (" + reason + ") req=" + id);
            invokePageReady(vm, id);
        };
        if (delayMs <= 0L) {
            h.post(sPageReadyRunnable);
        } else {
            h.postDelayed(sPageReadyRunnable, delayMs);
        }
    }

    private static void cancelPageReadyCallbacks() {
        if (sPageReadyRunnable != null) {
            new Handler(Looper.getMainLooper()).removeCallbacks(sPageReadyRunnable);
            sPageReadyRunnable = null;
        }
        cancelPageReadyAlarm();
    }

    private static boolean isRemoteServiceConnected() {
        ClassLoader cl = sTargetClassLoader;
        if (cl == null && sBackgroundTaskViewModel != null) {
            cl = sBackgroundTaskViewModel.getClass().getClassLoader();
        }
        if (cl == null) return false;
        try {
            Class<?> mgrCls = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.manager.RemoteServiceManager", cl);
            Object mgr = XposedHelpers.getStaticObjectField(mgrCls, "INSTANCE");
            Object service = XposedHelpers.callMethod(mgr, "getInstanceOrNull");
            return service != null;
        } catch (Throwable t) {
            Log.w(TAG, "isRemoteServiceConnected check failed: " + t.getMessage());
            return false;
        }
    }

    private static int handlePageReadyAlarmStart(Service service, Intent in) {
        final String reqId = in.getStringExtra(EXTRA_PAGE_READY_REQUEST_ID);
        rememberAppContext(service);
        try {
            XposedHelpers.callMethod(service, "ensureNotificationChannel");
            Object notification = XposedHelpers.callMethod(service, "buildPreparingNotification");
            XposedHelpers.callMethod(service, "startAsForeground", notification);
        } catch (Throwable t) {
            Log.w(TAG, "pageReady alarm FGS bootstrap failed", t);
        }
        if (reqId == null || reqId.equals(sInvokedPageReadyId)) {
            Log.i(TAG, "pageReady alarm ignored, already handled req=" + reqId);
            try {
                XposedHelpers.callMethod(service, "shutdownService");
            } catch (Throwable ignored) {
            }
            return Service.START_NOT_STICKY;
        }
        final Object vm = sBackgroundTaskViewModel;
        final Service svc = service;
        Log.i(TAG, "pageReady alarm FGS started req=" + reqId);
        new Handler(Looper.getMainLooper()).post(() -> {
            invokePageReady(vm, reqId);
            try {
                XposedHelpers.callMethod(svc, "shutdownService");
            } catch (Throwable ignored) {
            }
        });
        return Service.START_NOT_STICKY;
    }

    private static void rememberAppContext(Context ctx) {
        if (ctx == null) return;
        sAppContext = ctx.getApplicationContext();
    }

    private static Context readContextField(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(obj);
            return v instanceof Context ? (Context) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void acquirePageReadyWakeLock() {
        Context ctx = sAppContext;
        if (ctx == null) return;
        releasePageReadyWakeLock();
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "MaaMeowPatch:PageReady");
            wl.acquire(PAGE_READY_WAKE_TIMEOUT_MS);
            sPageReadyWakeLock = wl;
            Log.i(TAG, "pageReady wake lock acquired");
        } catch (Throwable t) {
            Log.w(TAG, "acquirePageReadyWakeLock failed", t);
        }
    }

    private static void releasePageReadyWakeLock() {
        PowerManager.WakeLock wl = sPageReadyWakeLock;
        sPageReadyWakeLock = null;
        if (wl == null) return;
        try {
            if (wl.isHeld()) wl.release();
            Log.i(TAG, "pageReady wake lock released");
        } catch (Throwable t) {
            Log.w(TAG, "releasePageReadyWakeLock failed", t);
        }
    }

    private static void schedulePageReadyAlarm(String requestId) {
        Context ctx = sAppContext;
        if (ctx == null) {
            Log.w(TAG, "schedulePageReadyAlarm: no app context");
            return;
        }
        try {
            cancelPageReadyAlarm();
            Intent intent = new Intent(ACTION_PAGE_READY_ALARM);
            intent.setClassName(TARGET_PKG,
                    "com.aliothmoon.maameow.schedule.service.ScheduleExecutionService");
            intent.putExtra(EXTRA_PAGE_READY_REQUEST_ID, requestId);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                sPageReadyAlarmPi = PendingIntent.getForegroundService(ctx, 1, intent, piFlags);
            } else {
                sPageReadyAlarmPi = PendingIntent.getService(ctx, 1, intent, piFlags);
            }
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long triggerWall = System.currentTimeMillis() + PAGE_READY_ALARM_MS;
            Intent showIntent = new Intent(ctx, XposedHelpers.findClass(TARGET_ACTIVITY,
                    sTargetClassLoader != null ? sTargetClassLoader : ctx.getClassLoader()));
            showIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent showPi = PendingIntent.getActivity(ctx, 2, showIntent, piFlags);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerWall, showPi),
                    sPageReadyAlarmPi);
            Log.i(TAG, "pageReady alarmClock scheduled in " + PAGE_READY_ALARM_MS
                    + "ms req=" + requestId);
        } catch (Throwable t) {
            Log.w(TAG, "schedulePageReadyAlarm failed", t);
        }
    }

    private static void cancelPageReadyAlarm() {
        Context ctx = sAppContext;
        PendingIntent pi = sPageReadyAlarmPi;
        sPageReadyAlarmPi = null;
        if (ctx == null || pi == null) return;
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pi);
        } catch (Throwable ignored) {
        }
    }

    private void hookMainActivity(ClassLoader cl) {
        try {
            Class<?> act = XposedHelpers.findClass(TARGET_ACTIVITY, cl);
            XposedHelpers.findAndHookMethod(act, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    rememberAppContext(a);
                    MeowRuntime.attach(a, cl);
                    MeowHttpServerBootstrap.ensureStarted();
                    logLaunchIntent(a, "onCreate");
                    maybeHandleExternalActions(a, a.getIntent(), cl);
                }
            });
            XposedHelpers.findAndHookMethod(act, "onNewIntent", Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    Intent in = (Intent) param.args[0];
                    a.setIntent(in);
                    MeowRuntime.attach(a, cl);
                    MeowHttpServerBootstrap.ensureStarted();
                    logLaunchIntent(a, "onNewIntent");
                    maybeHandleExternalActions(a, in, cl);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "hookMainActivity failed", t);
        }
    }

    private static void logLaunchIntent(Activity a, String where) {
        Intent in = a.getIntent();
        if (in == null) return;
        String action = in.getAction();
        if (ACTION_LAUNCH.equals(action) || ACTION_SHOW_SCHEDULE.equals(action)
                || ACTION_LAUNCH_COPILOT.equals(action)
                || ACTION_RUN_TASKS.equals(action)
                || ACTION_STOP_TASKS.equals(action)
                || ACTION_RELOAD_RESOURCE.equals(action)
                || ACTION_QUERY_STATUS.equals(action)) {
            Log.i(TAG, "MainActivity." + where + " " + describeIntent(in));
        }
    }

    private static void maybeHandleExternalActions(Activity activity, Intent intent, ClassLoader cl) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (ACTION_LAUNCH_COPILOT.equals(action)) {
            CopilotLaunchHelper.handleLaunchIntent(activity, intent, cl);
            return;
        }
        if (ACTION_QUERY_STATUS.equals(action)) {
            TaskRunTracker.handleIntent(activity, intent, cl);
            return;
        }
        if (ACTION_RUN_TASKS.equals(action) || ACTION_STOP_TASKS.equals(action)
                || ACTION_RELOAD_RESOURCE.equals(action)) {
            CliTaskLaunchHelper.handleIntent(activity, intent, cl);
        }
    }

    private static String readStringField(Object obj, String... names) {
        if (obj == null) return "null";
        for (String n : names) {
            String v = readObjectFieldString(obj, n);
            if (v != null) return v;
        }
        return "?";
    }

    private static String readObjectFieldString(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(obj);
            return v == null ? null : v.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describeIntent(Intent in) {
        StringBuilder sb = new StringBuilder();
        sb.append("action=").append(in.getAction());
        sb.append(" cmp=").append(in.getComponent());
        sb.append(" flags=0x").append(Integer.toHexString(in.getFlags()));
        if (in.getExtras() != null) {
            for (String k : in.getExtras().keySet()) {
                sb.append(" ").append(k).append("=").append(in.getExtras().get(k));
            }
        }
        return sb.toString();
    }
}
