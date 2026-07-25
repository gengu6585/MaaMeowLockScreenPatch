package com.tinkerlab.maameowpatch;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

/** Meow 进程内运行时句柄（供 HTTP / Intent 共用）。 */
public final class MeowRuntime {

    private static final String TAG = MainHook.TAG;

    private static volatile Activity sActivity;
    private static volatile Context sAppContext;
    private static volatile ClassLoader sClassLoader;

    private MeowRuntime() {
    }

    public static void attach(Activity activity, ClassLoader cl) {
        if (activity != null) {
            sActivity = activity;
            sAppContext = activity.getApplicationContext();
        }
        if (cl != null) sClassLoader = cl;
        Log.i(TAG, "MeowRuntime attached activity="
                + (activity == null ? "null" : activity.getClass().getName()));
    }

    public static void attachApp(Context context, ClassLoader cl) {
        if (context != null) sAppContext = context.getApplicationContext();
        if (cl != null) sClassLoader = cl;
    }

    public static Activity activity() {
        return sActivity;
    }

    public static Context appContext() {
        return sAppContext;
    }

    public static ClassLoader classLoader() {
        return sClassLoader;
    }

    public static boolean ready() {
        return sActivity != null && sClassLoader != null && !sActivity.isFinishing();
    }
}
