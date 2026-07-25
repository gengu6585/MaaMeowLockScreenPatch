package com.tinkerlab.maameowpatch;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/** 探测包进程是否在跑（游戏 / Meow）。 */
public final class ProcessProbe {

    public static final String GAME_PKG = "com.hypergryph.arknights";
    public static final String MEOW_PKG = "com.aliothmoon.maameow";

    private ProcessProbe() {
    }

    public static boolean isRunning(Context ctx, String pkg) {
        return pid(ctx, pkg) > 0;
    }

    /** 主进程 pid；找不到返回 -1。忽略 :pushcore 等子进程名不精确匹配。 */
    public static int pid(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return -1;
        try {
            if (ctx != null) {
                ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
                    if (list != null) {
                        for (ActivityManager.RunningAppProcessInfo p : list) {
                            if (p == null || p.processName == null) continue;
                            if (pkg.equals(p.processName)) return p.pid;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return pidof(pkg);
    }

    private static int pidof(String pkg) {
        java.lang.Process proc = null;
        try {
            proc = new ProcessBuilder("pidof", pkg).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line = br.readLine();
                if (line == null || line.trim().isEmpty()) return -1;
                String first = line.trim().split("\\s+")[0];
                return Integer.parseInt(first);
            }
        } catch (Throwable t) {
            return -1;
        } finally {
            if (proc != null) proc.destroy();
        }
    }

    /** 显式杀进程（仅 /v1/game action=kill；默认任务路径不用）。 */
    public static boolean forceStopPackage(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            java.lang.Process p = new ProcessBuilder("am", "force-stop", pkg)
                    .redirectErrorStream(true).start();
            p.waitFor();
            return !isRunning(ctx, pkg);
        } catch (Throwable t) {
            return false;
        }
    }
}
