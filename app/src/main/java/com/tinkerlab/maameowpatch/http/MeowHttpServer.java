package com.tinkerlab.maameowpatch.http;

import android.util.Log;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Meow 进程内 HTTP（默认仅绑定 127.0.0.1）。
 * <p>SSE：{@code curl -N -H 'Accept: text/event-stream' -d '...' http://127.0.0.1:17878/v1/tasks?stream=1}
 */
public final class MeowHttpServer extends NanoHTTPD {

    public static final int DEFAULT_PORT = 17878;
    public static final String DEFAULT_HOST = "127.0.0.1";

    private static final String TAG = "MaaMeowPatch";
    private static volatile MeowHttpServer sInstance;

    private final MeowHttpRouter router;

    public MeowHttpServer(String hostname, int port, MeowHttpRouter router) {
        super(hostname, port);
        this.router = router;
    }

    /** 生产环境：接真实后端，幂等启动。 */
    public static synchronized void ensureStarted(MeowHttpBackend backend) {
        if (sInstance != null && sInstance.isAlive()) return;
        try {
            MeowHttpRouter r = new MeowHttpRouter(backend);
            MeowHttpServer s = new MeowHttpServer(DEFAULT_HOST, DEFAULT_PORT, r);
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            sInstance = s;
            Log.i(TAG, "MeowHttpServer listening http://" + DEFAULT_HOST + ":" + DEFAULT_PORT);
        } catch (IOException e) {
            Log.e(TAG, "MeowHttpServer start failed: " + e.getMessage(), e);
        }
    }

    public static synchronized void stopServer() {
        if (sInstance != null) {
            sInstance.stop();
            sInstance = null;
        }
    }

    public static boolean isRunning() {
        MeowHttpServer s = sInstance;
        return s != null && s.isAlive();
    }

    public static int port() {
        return DEFAULT_PORT;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String method = session.getMethod() == null ? "GET" : session.getMethod().name();
        String uri = session.getUri();
        Map<String, String> query = session.getParms() == null
                ? new HashMap<String, String>()
                : session.getParms();
        Map<String, String> headers = session.getHeaders() == null
                ? new HashMap<String, String>()
                : session.getHeaders();
        String body = "";
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                if (files.containsKey("postData")) {
                    body = files.get("postData");
                } else {
                    body = query.get("postData");
                    if (body == null) body = "";
                }
            } catch (Exception e) {
                MeowHttpResponse err = MeowHttpResponse.badRequest(
                        "parse body: " + e.getMessage());
                return toNano(err);
            }
        }
        MeowHttpResponse r = router.handle(method, uri, body, query, headers);
        return toNano(r);
    }

    private static Response toNano(MeowHttpResponse r) {
        if (r.isSse()) {
            return toSse(r);
        }
        Response.Status st = Response.Status.lookup(r.status);
        if (st == null) st = Response.Status.INTERNAL_ERROR;
        return newFixedLengthResponse(st, r.contentType, r.body);
    }

    private static Response toSse(MeowHttpResponse r) {
        try {
            final PipedInputStream pin = new PipedInputStream(64 * 1024);
            final PipedOutputStream pout = new PipedOutputStream(pin);
            final SseProducer producer = r.sse;
            Thread t = new Thread(() -> {
                try {
                    producer.writeTo(pout);
                } catch (Throwable e) {
                    try {
                        String msg = e.getMessage() == null ? e.getClass().getSimpleName()
                                : e.getMessage();
                        byte[] bytes = SseFormatter.eventBytes("error",
                                "{\"ok\":false,\"error\":"
                                        + MeowHttpResponse.quote(msg) + "}");
                        pout.write(bytes);
                        pout.flush();
                    } catch (Throwable ignored) {
                    }
                } finally {
                    try {
                        pout.close();
                    } catch (IOException ignored) {
                    }
                }
            }, "meow-sse");
            t.setDaemon(true);
            t.start();
            Response resp = newChunkedResponse(Response.Status.OK, r.contentType, pin);
            resp.addHeader("Cache-Control", "no-cache");
            resp.addHeader("Connection", "keep-alive");
            resp.addHeader("X-Accel-Buffering", "no");
            return resp;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json",
                    "{\"ok\":false,\"error\":\"sse pipe: " + e.getMessage() + "\"}");
        }
    }
}
