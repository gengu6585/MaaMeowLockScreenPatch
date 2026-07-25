package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 同步 SSE：投递任务后推送关键日志/状态，直到本轮结束或<strong>流超时</strong>即关闭连接。
 * <p>客户端应阻塞读到 {@code event: done}/{@code error}；无需再轮询 /v1/status。
 * <p><b>流超时 / 客户端断开只关 SSE，绝不 stop 已发起的任务。</b>
 */
public final class TaskSseStreamer implements SseProducer {

    public interface StatusSource {
        JSONObject status() throws Exception;
    }

    public interface TaskStarter {
        JSONObject start(JSONObject body) throws Exception;
    }

    private final TaskEventBus bus;
    private final StatusSource statusSource;
    private final TaskStarter starter;
    private final JSONObject startBody;
    private final long timeoutMs;
    private final long pollMs;
    private final long heartbeatMs;

    public TaskSseStreamer(TaskEventBus bus, StatusSource statusSource,
            TaskStarter starter, JSONObject startBody,
            long timeoutMs, long pollMs, long heartbeatMs) {
        this.bus = bus;
        this.statusSource = statusSource;
        this.starter = starter;
        this.startBody = startBody;
        this.timeoutMs = timeoutMs <= 0 ? 3600_000L : timeoutMs;
        this.pollMs = pollMs <= 0 ? 400L : pollMs;
        this.heartbeatMs = heartbeatMs <= 0 ? 15_000L : heartbeatMs;
    }

    public static TaskSseStreamer forStart(MeowHttpBackend backend, JSONObject body,
            long timeoutMs) {
        // SSE 轮询用轻量 status（无 logcat / 全量 footer 扫）
        return new TaskSseStreamer(
                TaskEventBus.INSTANCE,
                com.tinkerlab.maameowpatch.TaskExecutor::refreshStatusLight,
                backend::startTasks, body,
                timeoutMs, 400L, 15_000L);
    }

    public static TaskSseStreamer forWatch(MeowHttpBackend backend, long timeoutMs) {
        return new TaskSseStreamer(
                TaskEventBus.INSTANCE,
                com.tinkerlab.maameowpatch.TaskExecutor::refreshStatusLight,
                null, null,
                timeoutMs, 400L, 15_000L);
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        long cursor = bus.latestSeq();
        writeJson(out, "hello", obj(
                "ok", true, "protocol", "sse", "filter", "important", "sync", true));
        out.flush();

        String expectedRunId = null;
        if (starter != null) {
            try {
                JSONObject accepted = starter.start(startBody);
                expectedRunId = emptyToNull(accepted.optString("run_id", null));
                writeJson(out, "accepted", accepted);
                out.flush();
                if (!accepted.optBoolean("accepted", false)) {
                    finish(out, "done", accepted);
                    return;
                }
            } catch (Exception e) {
                JSONObject err = obj("ok", false, "error",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                finish(out, "error", err);
                return;
            }
        }

        long startAt = System.currentTimeMillis();
        long lastBeat = startAt;
        String lastStatusKey = "";
        // 必须见过本轮 RUNNING，避免上一轮 result 导致误 done
        boolean sawThisRunActive = false;
        String busTerminalMsg = null;

        while (true) {
            long now = System.currentTimeMillis();
            if (now - startAt >= timeoutMs) {
                // 仅结束 SSE 订阅；不调用 stop，任务继续在 Meow 内跑
                JSONObject t = obj(
                        "ok", false,
                        "error", "stream_timeout",
                        "task_stopped", false,
                        "hint", "SSE stream timed out; running task was NOT stopped");
                try {
                    t.put("status", statusSource.status());
                } catch (Exception ignored) {
                }
                finish(out, "done", t);
                return;
            }

            List<JSONObject> events = bus.since(cursor);
            for (JSONObject ev : events) {
                cursor = Math.max(cursor, ev.optLong("seq", cursor));
                String type = ev.optString("type", "log");
                writeJson(out, mapEventName(type), ev);
                if ("session_end".equals(type) || "terminal".equals(type)) {
                    String rid = ev.optString("run_id", "");
                    // watch：无 expectedRunId；start：必须同 run_id（禁止空 rid 误匹配）
                    boolean match = expectedRunId == null
                            || (!expectedRunId.isEmpty() && expectedRunId.equals(rid));
                    if (match) {
                        busTerminalMsg = ev.optString("msg", type);
                        sawThisRunActive = true;
                    }
                }
            }
            if (!events.isEmpty()) out.flush();

            // 总线终态：再刷 status 后立即关流（同步调用结束）
            if (busTerminalMsg != null) {
                JSONObject st;
                try {
                    st = statusSource.status();
                } catch (Exception e) {
                    st = obj("ok", true, "result", mapFooterResult(busTerminalMsg));
                }
                if (resultOf(st).isEmpty()) {
                    try {
                        st.put("result", mapFooterResult(busTerminalMsg));
                    } catch (Exception ignored) {
                    }
                }
                finish(out, "done", st);
                return;
            }

            JSONObject st;
            try {
                st = statusSource.status();
            } catch (Exception e) {
                sleepQuiet(pollMs);
                continue;
            }

            String runId = st.optString("run_id", "");
            boolean active = st.optBoolean("active", false)
                    || st.optBoolean("task_running", false);
            String state = st.optString("state", "IDLE");
            String result = resultOf(st);
            String composition = st.optString("composition_state", "");
            long endedAt = st.optLong("ended_at", 0L);

            boolean sameRun = expectedRunId == null
                    || expectedRunId.isEmpty()
                    || expectedRunId.equals(runId);
            if (sameRun && (active || "RUNNING".equals(state) || "STARTING".equals(state)
                    || composition.contains("RUNNING") || composition.contains("STARTING"))) {
                sawThisRunActive = true;
            }

            String key = runId + "|" + active + "|" + state + "|" + result + "|" + composition;
            if (!key.equals(lastStatusKey)) {
                lastStatusKey = key;
                writeJson(out, "status", st);
                out.flush();
            }

            boolean idle = !active
                    && (state.contains("IDLE") || composition.contains("IDLE")
                    || composition.contains("ERROR") || "ERROR".equals(state)
                    || state.isEmpty());
            boolean hasResult = !result.isEmpty();
            boolean endedThisRun = endedAt <= 0L || endedAt >= startAt - 1000L;
            // 本轮已跑过且 idle+result → 同步结束
            if (sawThisRunActive && sameRun && idle && hasResult && endedThisRun) {
                finish(out, "done", st);
                return;
            }
            // watch 模式：见到终态即可
            if (starter == null && hasResult && idle && sawThisRunActive) {
                finish(out, "done", st);
                return;
            }

            if (now - lastBeat >= heartbeatMs) {
                out.write(SseFormatter.comment("ping " + LogTime.format(now))
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                lastBeat = now;
            }
            sleepQuiet(pollMs);
        }
    }

    /** COMPLETED / STOPPED / … → SUCCESS / STOPPED / FAILED */
    private static String mapFooterResult(String footerOrMsg) {
        if (footerOrMsg == null) return "FAILED";
        String s = footerOrMsg.trim();
        if (s.startsWith("SUCCESS") || "COMPLETED".equals(s) || s.contains("COMPLETED")) {
            return "SUCCESS";
        }
        if (s.startsWith("STOPPED") || s.contains("STOPPED") || s.contains("CANCELLED")) {
            return "STOPPED";
        }
        if (s.startsWith("FAILED") || s.startsWith("ERROR")) return "FAILED";
        // terminal msg 形如 "SUCCESS IDLE …"
        if (s.contains("SUCCESS")) return "SUCCESS";
        if (s.contains("STOPPED")) return "STOPPED";
        return "FAILED";
    }

    private static String mapEventName(String type) {
        if ("log".equals(type) || "run_accepted".equals(type) || "start_ok".equals(type)
                || "session_end".equals(type) || "terminal".equals(type)
                || "stop_requested".equals(type)) {
            return type;
        }
        return "log";
    }

    private static String resultOf(JSONObject st) {
        if (st == null || st.isNull("result")) return "";
        String r = st.optString("result", "").trim();
        if (r.isEmpty() || "null".equals(r)) return "";
        return r;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static void finish(OutputStream out, String event, JSONObject data)
            throws IOException {
        writeJson(out, event, data);
        out.flush();
    }

    private static void writeJson(OutputStream out, String event, JSONObject data)
            throws IOException {
        // 拷贝，避免污染 TaskEventBus 环内对象
        JSONObject o;
        try {
            o = data == null ? new JSONObject() : new JSONObject(data.toString());
        } catch (Exception e) {
            o = new JSONObject();
        }
        try {
            long ts = o.has("ts") && !o.isNull("ts")
                    ? o.optLong("ts") : System.currentTimeMillis();
            o.put("ts", ts);
            o.put("time", LogTime.format(ts));
        } catch (Exception ignored) {
        }
        out.write(SseFormatter.eventBytes(event, o.toString()));
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
