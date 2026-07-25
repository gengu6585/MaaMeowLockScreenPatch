package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/**
 * /v1/* 路由（纯 JVM）。
 *
 * <pre>
 * GET  /v1/health
 * GET  /v1/status
 * GET  /v1/events?limit=50
 * GET  /v1/stream              SSE 订阅关键日志/状态直到结束
 * POST /v1/tasks               JSON 立即返回
 * POST /v1/tasks?stream=1      SSE 同步：投递后推送关键日志直到 done
 * POST /v1/stop
 * POST /v1/resource
 * POST /v1/game                start|close|kill（StartUp/CloseDown/force-stop）
 * </pre>
 */
public final class MeowHttpRouter {

    private final MeowHttpBackend backend;

    public MeowHttpRouter(MeowHttpBackend backend) {
        this.backend = backend;
    }

    public MeowHttpResponse handle(String method, String uri, String body,
            Map<String, String> query) {
        return handle(method, uri, body, query, null);
    }

    public MeowHttpResponse handle(String method, String uri, String body,
            Map<String, String> query, Map<String, String> headers) {
        String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        String path = normalizePath(uri);
        try {
            if ("GET".equals(m) && "/v1/health".equals(path)) {
                return MeowHttpResponse.ok(backend.health().toString());
            }
            if ("GET".equals(m) && "/v1/status".equals(path)) {
                return MeowHttpResponse.ok(backend.status().toString());
            }
            if ("GET".equals(m) && "/v1/events".equals(path)) {
                int limit = parseInt(query, "limit", 50);
                return MeowHttpResponse.ok(backend.events(limit).toString());
            }
            if ("GET".equals(m) && "/v1/stream".equals(path)) {
                long timeoutMs = parseLong(query, "timeout_ms", 3_600_000L);
                return MeowHttpResponse.sse(TaskSseStreamer.forWatch(backend, timeoutMs));
            }
            if ("POST".equals(m) && "/v1/tasks".equals(path)) {
                JSONObject req = parseBodyObject(body);
                if (!req.has("tasks") && !req.has("task")) {
                    return MeowHttpResponse.badRequest("body.tasks required");
                }
                if (wantStream(query, headers)) {
                    long timeoutMs = parseLong(query, "timeout_ms",
                            req.optLong("timeout_ms", 3_600_000L));
                    return MeowHttpResponse.sse(
                            TaskSseStreamer.forStart(backend, req, timeoutMs));
                }
                return MeowHttpResponse.ok(backend.startTasks(req).toString());
            }
            if ("POST".equals(m) && "/v1/stop".equals(path)) {
                return MeowHttpResponse.ok(backend.stopTasks().toString());
            }
            if ("POST".equals(m) && "/v1/resource".equals(path)) {
                JSONObject req = parseBodyObject(body);
                if (req.optString("resource_path", "").isEmpty()) {
                    return MeowHttpResponse.badRequest("resource_path required");
                }
                return MeowHttpResponse.ok(backend.loadResource(req).toString());
            }
            if ("POST".equals(m) && "/v1/game".equals(path)) {
                JSONObject req = parseBodyObject(body);
                String action = req.optString("action", "").trim();
                if (action.isEmpty()) {
                    return MeowHttpResponse.badRequest("action required: start|close|kill");
                }
                if (wantStream(query, headers)) {
                    // start/close 本质是投递 StartUp/CloseDown 任务，可走 SSE
                    long timeoutMs = parseLong(query, "timeout_ms",
                            req.optLong("timeout_ms", 3_600_000L));
                    return MeowHttpResponse.sse(new TaskSseStreamer(
                            TaskEventBus.INSTANCE,
                            backend::status,
                            b -> backend.controlGame(req),
                            req,
                            timeoutMs,
                            500L,
                            15_000L));
                }
                return MeowHttpResponse.ok(backend.controlGame(req).toString());
            }
            if ("GET".equals(m) && ("/".equals(path) || "/v1".equals(path))) {
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("service", "maameow-http");
                o.put("endpoints", new String[]{
                        "GET /v1/health", "GET /v1/status", "GET /v1/events",
                        "GET /v1/stream",
                        "POST /v1/tasks", "POST /v1/tasks?stream=1",
                        "POST /v1/stop", "POST /v1/resource", "POST /v1/game"
                });
                return MeowHttpResponse.ok(o.toString());
            }
            return MeowHttpResponse.notFound();
        } catch (IllegalArgumentException e) {
            return MeowHttpResponse.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return MeowHttpResponse.error(503, e.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return MeowHttpResponse.error(500, msg);
        }
    }

    static boolean wantStream(Map<String, String> query, Map<String, String> headers) {
        if (query != null) {
            String s = query.get("stream");
            if ("1".equals(s) || "true".equalsIgnoreCase(s) || "sse".equalsIgnoreCase(s)) {
                return true;
            }
        }
        if (headers != null) {
            String accept = headers.get("accept");
            if (accept == null) accept = headers.get("Accept");
            if (accept != null && accept.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                return true;
            }
        }
        return false;
    }

    static String normalizePath(String uri) {
        if (uri == null || uri.isEmpty()) return "/";
        String path = uri;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? "/" : path;
    }

    static JSONObject parseBodyObject(String body) throws Exception {
        if (body == null || body.trim().isEmpty()) {
            return new JSONObject();
        }
        String t = body.trim();
        if (!t.startsWith("{")) {
            throw new IllegalArgumentException("JSON object body required");
        }
        return new JSONObject(t);
    }

    private static int parseInt(Map<String, String> query, String key, int def) {
        if (query == null) return def;
        String v = query.get(key);
        if (v == null || v.isEmpty()) return def;
        try {
            return Math.max(1, Math.min(500, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(Map<String, String> query, String key, long def) {
        if (query == null) return def;
        String v = query.get(key);
        if (v == null || v.isEmpty()) return def;
        try {
            return Math.max(1000L, Long.parseLong(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
