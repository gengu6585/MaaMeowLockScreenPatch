package com.tinkerlab.maameowpatch;

import android.app.Activity;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedHelpers;

/** 反射桥：解析 Meow Koin 服务并调用 suspend 方法。 */
public final class MeowBridge {

    private static final String TAG = MainHook.TAG;

    private MeowBridge() {
    }

    public static Object resolveService(Activity activity, ClassLoader cl, String className)
            throws Exception {
        if ("com.aliothmoon.maameow.domain.service.MaaCompositionService".equals(className)) {
            try {
                return getDelegateValue(activity, "compositionService");
            } catch (Throwable t) {
                Log.w(TAG, "compositionService delegate: " + t.getMessage());
            }
        }

        Class<?> target = cl.loadClass(className);
        Class<?> jvmMapping = cl.loadClass("kotlin.jvm.JvmClassMappingKt");
        Object kClass = jvmMapping.getMethod("getKotlinClass", Class.class).invoke(null, target);
        Class<?> extKt = cl.loadClass("org.koin.android.ext.android.ComponentCallbackExtKt");

        try {
            Object lazy = XposedHelpers.callStaticMethod(extKt, "inject", activity, kClass);
            Object value = XposedHelpers.callMethod(lazy, "getValue");
            if (value != null) return value;
        } catch (Throwable t) {
            Log.w(TAG, "Koin inject " + className + ": " + t.getMessage());
        }

        Object koin = XposedHelpers.callStaticMethod(extKt, "getKoin", activity);
        if (koin != null) {
            for (Method m : koin.getClass().getMethods()) {
                if (!"get".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                try {
                    Object value;
                    if (params.length == 1 && params[0].getName().contains("KClass")) {
                        value = m.invoke(koin, kClass);
                    } else if (params.length == 3 && params[0].getName().contains("KClass")) {
                        value = m.invoke(koin, kClass, null, null);
                    } else {
                        continue;
                    }
                    if (value != null) return value;
                } catch (Throwable ignored) {
                }
            }
            try {
                Object scope = XposedHelpers.callMethod(koin, "getScope");
                Object value = XposedHelpers.callMethod(scope, "get", kClass, null, null);
                if (value != null) return value;
            } catch (Throwable ignored) {
            }
        }
        throw new IllegalStateException("Cannot resolve service: " + className);
    }

    public static Object getDelegateValue(Activity activity, String baseName) throws Exception {
        Field f = activity.getClass().getDeclaredField(baseName + "$delegate");
        f.setAccessible(true);
        Object lazy = f.get(activity);
        if (lazy == null) throw new IllegalStateException(baseName + " delegate null");
        return lazy.getClass().getMethod("getValue").invoke(lazy);
    }

    public static Object invokeSuspend(ClassLoader cl, Object instance, String methodName,
            Object... args) throws Exception {
        Class<?> buildersKt = cl.loadClass("kotlinx.coroutines.BuildersKt");
        Class<?> emptyCtxCls = cl.loadClass("kotlin.coroutines.EmptyCoroutineContext");
        Object emptyCtx = emptyCtxCls.getField("INSTANCE").get(null);
        final Object[] holder = new Object[1];
        final Exception[] err = new Exception[1];
        Class<?> fn2 = cl.loadClass("kotlin.jvm.functions.Function2");
        Object block = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{fn2},
                (proxy, method, invokeArgs) -> {
                    if ("invoke".equals(method.getName())) {
                        try {
                            holder[0] = callSuspendMethod(instance, methodName, args);
                        } catch (Exception e) {
                            err[0] = e;
                        }
                    }
                    return null;
                });
        buildersKt.getMethod("runBlocking",
                cl.loadClass("kotlin.coroutines.CoroutineContext"), fn2)
                .invoke(null, emptyCtx, block);
        if (err[0] != null) throw err[0];
        return holder[0];
    }

    private static Object callSuspendMethod(Object instance, String methodName, Object... args)
            throws Exception {
        Method target = null;
        for (Method m : instance.getClass().getMethods()) {
            if (!methodName.equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != args.length + 1) continue;
            if (!params[params.length - 1].getName().contains("Continuation")) continue;
            target = m;
            break;
        }
        if (target == null) {
            throw new NoSuchMethodException("suspend " + methodName + " not found");
        }

        final Object[] result = new Object[1];
        final Exception[] err = new Exception[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Class<?> contCls = target.getParameterTypes()[target.getParameterTypes().length - 1];
        ClassLoader cl = contCls.getClassLoader();
        Class<?> emptyCtxCls = Class.forName("kotlin.coroutines.EmptyCoroutineContext", true, cl);
        final Object emptyCtx = emptyCtxCls.getField("INSTANCE").get(null);
        Object continuation = java.lang.reflect.Proxy.newProxyInstance(
                cl, new Class<?>[]{contCls},
                (proxy, method, invokeArgs) -> {
                    String name = method.getName();
                    if ("getContext".equals(name)) {
                        return emptyCtx;
                    }
                    if ("resumeWith".equals(name)) {
                        try {
                            unwrapResumeValue(invokeArgs[0], result, err);
                        } catch (Exception e) {
                            err[0] = e;
                        } finally {
                            latch.countDown();
                        }
                    }
                    return null;
                });
        Object[] invokeArgs = new Object[args.length + 1];
        System.arraycopy(args, 0, invokeArgs, 0, args.length);
        invokeArgs[args.length] = continuation;
        target.invoke(instance, invokeArgs);
        if (!latch.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("suspend " + methodName + " timed out");
        }
        if (err[0] != null) throw err[0];
        return result[0];
    }

    private static void unwrapResumeValue(Object res, Object[] resultHolder, Exception[] errHolder) {
        if (res == null) {
            resultHolder[0] = null;
            return;
        }
        if (res instanceof Throwable) {
            errHolder[0] = new Exception((Throwable) res);
            return;
        }
        if ("kotlin.Result".equals(res.getClass().getName())) {
            try {
                Object ex = res.getClass().getMethod("exceptionOrNull").invoke(res);
                if (ex instanceof Throwable) {
                    errHolder[0] = new Exception((Throwable) ex);
                } else {
                    resultHolder[0] = res.getClass().getMethod("getOrNull").invoke(res);
                }
            } catch (Exception e) {
                errHolder[0] = e;
            }
            return;
        }
        resultHolder[0] = res;
    }

    public static Object buildTaskParams(ClassLoader cl, String typeName, String paramsJson)
            throws Exception {
        Class<?> taskTypeClass = cl.loadClass("com.aliothmoon.maameow.maa.task.MaaTaskType");
        Object type = resolveTaskType(taskTypeClass, typeName);
        Class<?> taskParamsClass = cl.loadClass("com.aliothmoon.maameow.maa.task.MaaTaskParams");
        return taskParamsClass.getConstructor(taskTypeClass, String.class, String.class)
                .newInstance(type, paramsJson == null ? "{}" : paramsJson, "");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object resolveTaskType(Class<?> taskTypeClass, String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            throw new IllegalArgumentException("task type empty");
        }
        // enum name: START_UP / FIGHT
        try {
            return Enum.valueOf((Class<Enum>) taskTypeClass, typeName);
        } catch (IllegalArgumentException ignored) {
        }
        // MaaCore / maa-cli value: StartUp / Fight
        Object[] constants = taskTypeClass.getEnumConstants();
        if (constants != null) {
            for (Object c : constants) {
                String value = String.valueOf(XposedHelpers.callMethod(c, "getValue"));
                if (typeName.equalsIgnoreCase(value) || typeName.equalsIgnoreCase(c.toString())) {
                    return c;
                }
            }
        }
        throw new IllegalArgumentException("unknown MaaTaskType: " + typeName);
    }

    public static void logStartResult(Object result) {
        if (result == null) {
            Log.w(TAG, "start returned null");
            return;
        }
        String cn = result.getClass().getName();
        if (cn.endsWith("$Success")) {
            Log.i(TAG, "start Success: " + result);
            return;
        }
        if (cn.contains("StartResult")) {
            Log.e(TAG, "start failed result: " + result);
        }
    }

    public static String getClientType(Activity activity, ClassLoader cl) throws Exception {
        Object taskChainState = resolveService(activity, cl,
                "com.aliothmoon.maameow.data.preferences.TaskChainState");
        Object client = XposedHelpers.callMethod(taskChainState, "getClientType");
        return client == null ? "Official" : client.toString();
    }

    public static String compositionState(Activity activity, ClassLoader cl) {
        try {
            Object service = resolveService(activity, cl,
                    "com.aliothmoon.maameow.domain.service.MaaCompositionService");
            Object stateFlow = XposedHelpers.callMethod(service, "getState");
            Object value = XposedHelpers.callMethod(stateFlow, "getValue");
            return value == null ? "" : value.toString();
        } catch (Throwable t) {
            return "UNKNOWN:" + t.getMessage();
        }
    }

    public static boolean isRemoteServiceConnecting(ClassLoader cl) {
        try {
            Class<?> mgrCls = cl.loadClass("com.aliothmoon.maameow.manager.RemoteServiceManager");
            Object stateFlow = XposedHelpers.getStaticObjectField(mgrCls, "state");
            Object value = XposedHelpers.callMethod(stateFlow, "getValue");
            String s = value == null ? "" : value.toString();
            return s.contains("Connecting");
        } catch (Throwable t) {
            return false;
        }
    }

    /** RemoteService 实例是否已绑定（比仅看 Connecting 更可靠）。 */
    public static boolean isRemoteServiceBound(ClassLoader cl) {
        try {
            Class<?> mgrCls = cl.loadClass("com.aliothmoon.maameow.manager.RemoteServiceManager");
            Object mgr = XposedHelpers.getStaticObjectField(mgrCls, "INSTANCE");
            Object service = XposedHelpers.callMethod(mgr, "getInstanceOrNull");
            return service != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 等待远程服务可下发任务：
     * 1) 脱离 Connecting；2) 若超时仍未 Connecting，只要已 bound 也放行。
     */
    public static void waitServiceReady(ClassLoader cl, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0L);
        while (System.currentTimeMillis() < deadline) {
            boolean connecting = isRemoteServiceConnecting(cl);
            if (!connecting) {
                if (isRemoteServiceBound(cl) || timeoutMs <= 500L) {
                    return;
                }
                // 未 Connecting 也未 bound：再给短暂窗口（冷启动）
                Thread.sleep(200);
                if (!isRemoteServiceConnecting(cl)) return;
            } else {
                Thread.sleep(300);
            }
        }
        Log.w(TAG, "waitServiceReady timed out after " + timeoutMs
                + "ms connecting=" + isRemoteServiceConnecting(cl)
                + " bound=" + isRemoteServiceBound(cl));
    }
}
