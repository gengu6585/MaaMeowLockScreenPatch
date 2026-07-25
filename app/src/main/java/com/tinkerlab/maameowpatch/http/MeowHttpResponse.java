package com.tinkerlab.maameowpatch.http;

/** 纯数据 HTTP 响应（不依赖 NanoHTTPD，便于单测）。 */
public final class MeowHttpResponse {

    public final int status;
    public final String body;
    public final String contentType;
    /** 非 null 时为 SSE 流式响应。 */
    public final SseProducer sse;

    public MeowHttpResponse(int status, String body) {
        this(status, body, "application/json; charset=utf-8", null);
    }

    public MeowHttpResponse(int status, String body, String contentType) {
        this(status, body, contentType, null);
    }

    public MeowHttpResponse(int status, String body, String contentType, SseProducer sse) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.contentType = contentType == null ? "text/plain" : contentType;
        this.sse = sse;
    }

    public boolean isSse() {
        return sse != null;
    }

    public static MeowHttpResponse json(int status, String body) {
        return new MeowHttpResponse(status, body);
    }

    public static MeowHttpResponse ok(String body) {
        return json(200, body);
    }

    public static MeowHttpResponse sse(SseProducer producer) {
        return new MeowHttpResponse(200, "", "text/event-stream; charset=utf-8", producer);
    }

    public static MeowHttpResponse badRequest(String msg) {
        return json(400, "{\"ok\":false,\"error\":" + quote(msg) + "}");
    }

    public static MeowHttpResponse notFound() {
        return json(404, "{\"ok\":false,\"error\":\"not found\"}");
    }

    public static MeowHttpResponse error(int status, String msg) {
        return json(status, "{\"ok\":false,\"error\":" + quote(msg) + "}");
    }

    /** JSON 字符串字面量（含引号）。 */
    public static String quote(String s) {
        if (s == null) return "null";
        String escaped = s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }
}
