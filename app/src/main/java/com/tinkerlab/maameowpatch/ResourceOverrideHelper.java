package com.tinkerlab.maameowpatch;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 外部资源注入：仅当本次 Intent 带 {@link ResourcePathConfig#EXTRA_RESOURCE_PATH} 时调用。
 * Meow 常规 load() 不读 conf、不自动叠加。
 */
public final class ResourceOverrideHelper {

    private static final String TAG = MainHook.TAG;

    private static final AtomicBoolean sHooked = new AtomicBoolean(false);
    /** 仅 replace 模式、且正在执行 Intent 触发的 reload 时非空 */
    private static final AtomicReference<ResourcePathConfig> sReplaceSession = new AtomicReference<>();

    private ResourceOverrideHelper() {
    }

    public static void hook(ClassLoader cl) {
        if (!sHooked.compareAndSet(false, true)) return;
        try {
            Class<?> loaderCls = XposedHelpers.findClass(
                    "com.aliothmoon.maameow.domain.service.MaaResourceLoader", cl);
            XposedHelpers.findAndHookMethod(loaderCls, "loadResIfExists",
                    "com.aliothmoon.maameow.MaaCoreService", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ResourcePathConfig cfg = sReplaceSession.get();
                            if (cfg == null || !cfg.isReplace() || cfg.resourcePath == null) return;
                            String parent = (String) param.args[1];
                            if (parent == null) return;
                            if (!isMeowRootParent(param.thisObject, parent)) return;
                            if (!ResourcePathConfig.hasResourceDir(cfg.resourcePath)) {
                                Log.w(TAG, "replace skipped, no resource/: " + cfg.resourcePath);
                                return;
                            }
                            Log.i(TAG, "LoadResource replace " + parent + " → " + cfg.resourcePath);
                            param.args[1] = cfg.resourcePath;
                        }
                    });
            Log.i(TAG, "hooked loadResIfExists (replace session only)");
        } catch (Throwable t) {
            sHooked.set(false);
            Log.e(TAG, "hookResourceLoader failed", t);
        }
    }

    /**
     * RUN_TASKS / RELOAD_RESOURCE：若 Intent 带 extra_resource_path 则注入；否则 no-op。
     *
     * @return true 若本次应用了外部资源
     */
    public static boolean applyIfRequested(Activity activity, Intent intent, ClassLoader cl) {
        ResourcePathConfig cfg = ResourcePathConfig.fromIntentOrNull(intent);
        if (cfg == null) return false;

        // append 默认只叠加 LoadResource；replace 或显式 extra_reload_resource 才 reset+reload
        boolean wantReload = cfg.isReplace()
                || intent.getBooleanExtra(ResourcePathConfig.EXTRA_RELOAD_RESOURCE, false);
        try {
            if (wantReload) {
                try {
                    reloadBuiltinThenMaybeReplace(activity, cl, cfg);
                } catch (Throwable t) {
                    Log.w(TAG, "builtin reload failed, still try Intent LoadResource", t);
                    sReplaceSession.set(null);
                }
            }
            loadExternalParents(cl, cfg);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "apply external resource failed", t);
            return false;
        } finally {
            sReplaceSession.set(null);
        }
    }

    public static void handleReloadIntent(Activity activity, Intent intent, ClassLoader cl) {
        if (intent == null || !intent.hasExtra(ResourcePathConfig.EXTRA_RESOURCE_PATH)) {
            Log.e(TAG, "RELOAD_RESOURCE requires extra_resource_path");
            return;
        }
        // RELOAD_RESOURCE：默认只注入外部；要连内置一起重载需显式 extra_reload_resource=true
        applyIfRequested(activity, intent, cl);
    }

    private static void reloadBuiltinThenMaybeReplace(Activity activity, ClassLoader cl,
            ResourcePathConfig cfg) throws Exception {
        Object loader = MeowBridge.resolveService(activity, cl,
                "com.aliothmoon.maameow.domain.service.MaaResourceLoader");
        if (cfg.isReplace()) {
            sReplaceSession.set(cfg);
        } else {
            sReplaceSession.set(null);
        }
        XposedHelpers.callMethod(loader, "reset");
        // ensureLoaded → 内部 load()；避免直接反射 load(String) 签名差异
        MeowBridge.invokeSuspend(cl, loader, "ensureLoaded");
        Log.i(TAG, "resource reload via ensureLoaded replace=" + cfg.isReplace());
    }

    private static void loadExternalParents(ClassLoader cl, ResourcePathConfig cfg) {
        List<String> parents = cfg.loadParents();
        if (parents.isEmpty()) {
            Log.w(TAG, "no valid external resource parents");
            return;
        }
        Object maa = resolveMaaCore(cl);
        if (maa == null) {
            Log.e(TAG, "MaaCoreService unavailable, cannot LoadResource");
            return;
        }
        StringBuilder marker = new StringBuilder();
        marker.append("ts=").append(System.currentTimeMillis()).append('\n');
        marker.append("mode=").append(cfg.mode).append('\n');
        for (String parent : parents) {
            // replace 时主路径已在 load() 中加载，避免重复；overrides 仍要加载
            if (cfg.isReplace() && parent.equals(cfg.resourcePath)) {
                marker.append("skip_already_replaced=").append(parent).append('\n');
                continue;
            }
            boolean ok = false;
            String err = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    if (maa == null) {
                        maa = resolveMaaCore(cl);
                    }
                    if (maa == null) {
                        err = "MaaCoreService null";
                        Thread.sleep(400L * attempt);
                        continue;
                    }
                    ok = (boolean) XposedHelpers.callMethod(maa, "LoadResource", parent);
                    Log.i(TAG, "Intent LoadResource(" + parent + ") = " + ok
                            + (attempt > 1 ? " attempt=" + attempt : ""));
                    err = null;
                    break;
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    Log.w(TAG, "LoadResource failed attempt=" + attempt + " path=" + parent
                            + " err=" + err);
                    maa = null; // DeadObject 后强制重取
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (err != null) {
                Log.e(TAG, "LoadResource gave up: " + parent + " " + err);
                marker.append("LoadResource ").append(parent).append(" = ERROR ")
                        .append(err).append('\n');
            } else {
                marker.append("LoadResource ").append(parent).append(" = ").append(ok).append('\n');
            }
        }
        writeLastLoadMarker(marker.toString());
    }

    /** 便于脚本确认本次 Intent 是否真的注入了外置资源（不依赖 logcat 缓冲）。 */
    private static void writeLastLoadMarker(String body) {
        try {
            java.io.File dir = new java.io.File("/storage/emulated/0/maa");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "last_intent_resource.log");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Throwable t) {
            Log.w(TAG, "write last_intent_resource.log failed: " + t.getMessage());
        }
    }

    private static boolean isMeowRootParent(Object loader, String parent) {
        try {
            Object pathConfig = XposedHelpers.getObjectField(loader, "pathConfig");
            String root = String.valueOf(XposedHelpers.callMethod(pathConfig, "getRootDir"));
            return parent.equals(root);
        } catch (Throwable t) {
            return parent.endsWith("/Maa") && !parent.contains("/overrides")
                    && !parent.contains("/cache");
        }
    }

    private static Object resolveMaaCore(ClassLoader cl) {
        if (cl == null) return null;
        try {
            Class<?> mgrCls = cl.loadClass("com.aliothmoon.maameow.manager.RemoteServiceManager");
            Object mgr = XposedHelpers.getStaticObjectField(mgrCls, "INSTANCE");
            Object remote = XposedHelpers.callMethod(mgr, "getInstanceOrNull");
            if (remote == null) return null;
            return XposedHelpers.callMethod(remote, "getMaaCoreService");
        } catch (Throwable t) {
            return null;
        }
    }
}
