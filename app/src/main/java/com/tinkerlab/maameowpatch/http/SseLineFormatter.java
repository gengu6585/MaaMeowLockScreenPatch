package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;

/**
 * SSE 人类可读行（脚本/控制台）。纯 JVM。
 * <pre>
 * 2026-07-26 01:51:23.456  LOG       [ERROR] 截图失败…
 * 2026-07-26 01:51:24.001  STATUS    state=RUNNING active=true
 * 2026-07-26 01:51:30.100  DONE      result=SUCCESS
 * </pre>
 */
public final class SseLineFormatter {

    private SseLineFormatter() {
    }

    public static String formatEvent(String eventName, String dataJson) {
        String name = eventName == null ? "message" : eventName.trim();
        long now = System.currentTimeMillis();
        String time = LogTime.format(now);
        String payload = dataJson == null ? "" : dataJson.trim();
        try {
            JSONObject o = new JSONObject(payload.isEmpty() ? "{}" : payload);
            if (o.has("time") && !o.isNull("time")) {
                time = o.optString("time", time);
            } else if (o.has("ts") && !o.isNull("ts")) {
                time = LogTime.format(o.optLong("ts", now));
            }
            String tag = pad(name.toUpperCase(java.util.Locale.ROOT), 10);
            switch (name) {
                case "log":
                case "run_accepted":
                case "start_ok":
                case "session_end":
                case "terminal":
                case "stop_requested":
                    return time + "  " + tag + "  " + o.optString("msg", payload);
                case "status":
                case "accepted":
                case "done":
                    return time + "  " + tag + "  "
                            + "state=" + o.optString("state", "?")
                            + " active=" + o.optBoolean("active", false)
                            + " result=" + (o.isNull("result") ? "-" : o.optString("result", "-"))
                            + " game=" + gameRunning(o)
                            + " task_running=" + o.optBoolean("task_running", false);
                case "error":
                    return time + "  " + tag + "  " + o.optString("error", payload);
                case "hello":
                    return time + "  " + tag + "  " + "sse ready filter="
                            + o.optString("filter", "important");
                default:
                    return time + "  " + tag + "  " + payload;
            }
        } catch (Exception e) {
            return time + "  " + pad(name.toUpperCase(java.util.Locale.ROOT), 10) + "  " + payload;
        }
    }

    private static String gameRunning(JSONObject o) {
        try {
            if (!o.has("game") || o.isNull("game")) return "?";
            return String.valueOf(o.getJSONObject("game").optBoolean("running", false));
        } catch (Exception e) {
            return "?";
        }
    }

    private static String pad(String s, int n) {
        if (s.length() >= n) return s.substring(0, n);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }
}
