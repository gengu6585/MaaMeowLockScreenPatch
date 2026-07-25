package com.tinkerlab.maameowpatch;

import com.tinkerlab.maameowpatch.http.LiveMeowHttpBackend;
import com.tinkerlab.maameowpatch.http.MeowHttpServer;

/** 启动 Meow 进程内 HTTP（Intent 拉起 Activity 后调用）。 */
public final class MeowHttpServerBootstrap {

    public static final String VERSION = "1.2.15";

    private MeowHttpServerBootstrap() {
    }

    public static void ensureStarted() {
        MeowHttpServer.ensureStarted(new LiveMeowHttpBackend());
    }
}
