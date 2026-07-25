package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertTrue;

public class TaskSseStreamerTest {

    private TaskEventBus bus;

    @Before
    public void setUp() {
        bus = new TaskEventBus(64);
    }

    @Test
    public void endsWhenThisRunIdleWithResult() throws Exception {
        AtomicInteger statusCalls = new AtomicInteger();
        TaskSseStreamer.StatusSource status = () -> {
            int n = statusCalls.incrementAndGet();
            JSONObject o = new JSONObject();
            o.put("run_id", "abc123");
            o.put("composition_state", n < 3 ? "RUNNING" : "IDLE");
            o.put("task_running", n < 3);
            if (n < 3) {
                o.put("active", true);
                o.put("state", "RUNNING");
                o.put("result", JSONObject.NULL);
            } else {
                o.put("active", false);
                o.put("state", "IDLE");
                o.put("result", "SUCCESS");
            }
            return o;
        };
        TaskSseStreamer.TaskStarter starter = body -> {
            bus.publish("run_accepted", "Fight", "abc123");
            bus.publish("log", "[INFO] 开始任务 Fight", "abc123");
            JSONObject a = new JSONObject();
            a.put("accepted", true);
            a.put("run_id", "abc123");
            a.put("active", true);
            a.put("state", "STARTING");
            return a;
        };

        TaskSseStreamer streamer = new TaskSseStreamer(
                bus, status, starter, new JSONObject().put("tasks", new org.json.JSONArray()),
                5_000L, 30L, 60_000L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        streamer.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8.name());

        assertTrue(text.contains("event: done"));
        assertTrue(text.contains("SUCCESS"));
        assertTrue(text.contains("\"sync\":true") || text.contains("sync"));
    }

    @Test
    public void endsOnSessionEndEventEvenIfResultLate() throws Exception {
        AtomicInteger statusCalls = new AtomicInteger();
        TaskSseStreamer.StatusSource status = () -> {
            int n = statusCalls.incrementAndGet();
            JSONObject o = new JSONObject();
            o.put("run_id", "r2");
            // status 仍可能短暂 active；session_end 应直接关流
            o.put("composition_state", "RUNNING");
            o.put("active", true);
            o.put("task_running", true);
            o.put("state", "RUNNING");
            o.put("result", JSONObject.NULL);
            if (n == 2) {
                bus.publish("session_end", "COMPLETED", "r2");
            }
            return o;
        };
        TaskSseStreamer.TaskStarter starter = body -> {
            JSONObject a = new JSONObject();
            a.put("accepted", true);
            a.put("run_id", "r2");
            return a;
        };

        TaskSseStreamer streamer = new TaskSseStreamer(
                bus, status, starter, new JSONObject().put("tasks", new org.json.JSONArray()),
                5_000L, 30L, 60_000L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        streamer.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8.name());
        assertTrue(text.contains("event: session_end"));
        assertTrue(text.contains("event: done"));
        assertTrue(text.contains("SUCCESS"));
    }

    @Test
    public void streamTimeoutDoesNotImplyTaskStop() throws Exception {
        TaskSseStreamer.StatusSource status = () -> {
            JSONObject o = new JSONObject();
            o.put("run_id", "to1");
            o.put("active", true);
            o.put("task_running", true);
            o.put("state", "RUNNING");
            o.put("composition_state", "RUNNING");
            o.put("result", JSONObject.NULL);
            return o;
        };
        TaskSseStreamer.TaskStarter starter = body -> {
            JSONObject a = new JSONObject();
            a.put("accepted", true);
            a.put("run_id", "to1");
            a.put("active", true);
            return a;
        };
        // 极短超时；status 一直 RUNNING → 只关流，payload 标明未 stop
        TaskSseStreamer streamer = new TaskSseStreamer(
                bus, status, starter, new JSONObject().put("tasks", new org.json.JSONArray()),
                80L, 20L, 60_000L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        streamer.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8.name());
        assertTrue(text.contains("event: done"));
        assertTrue(text.contains("stream_timeout"));
        assertTrue(text.contains("\"task_stopped\":false") || text.contains("\"task_stopped\": false"));
    }

    @Test
    public void sessionEndWithEmptyRunIdDoesNotEndStartedRun() throws Exception {
        AtomicInteger statusCalls = new AtomicInteger();
        TaskSseStreamer.StatusSource status = () -> {
            int n = statusCalls.incrementAndGet();
            JSONObject o = new JSONObject();
            o.put("run_id", "keep1");
            o.put("active", true);
            o.put("task_running", true);
            o.put("state", "RUNNING");
            o.put("composition_state", "RUNNING");
            o.put("result", JSONObject.NULL);
            if (n == 2) {
                // 空 run_id 的 session_end 不得误关本轮
                bus.publish("session_end", "COMPLETED", "");
            }
            if (n >= 4) {
                o.put("active", false);
                o.put("task_running", false);
                o.put("state", "IDLE");
                o.put("result", "SUCCESS");
            }
            return o;
        };
        TaskSseStreamer.TaskStarter starter = body -> {
            JSONObject a = new JSONObject();
            a.put("accepted", true);
            a.put("run_id", "keep1");
            return a;
        };
        TaskSseStreamer streamer = new TaskSseStreamer(
                bus, status, starter, new JSONObject().put("tasks", new org.json.JSONArray()),
                5_000L, 30L, 60_000L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        streamer.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8.name());
        assertTrue(text.contains("event: done"));
        assertTrue(statusCalls.get() >= 4);
    }

    @Test
    public void startErrorEmitsErrorEvent() throws Exception {
        TaskSseStreamer streamer = new TaskSseStreamer(
                bus,
                () -> new JSONObject().put("state", "IDLE"),
                body -> {
                    throw new IllegalStateException("Meow not ready");
                },
                new JSONObject().put("tasks", new org.json.JSONArray()),
                2_000L, 30L, 60_000L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        streamer.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8.name());
        assertTrue(text.contains("event: error"));
        assertTrue(text.contains("Meow not ready"));
    }
}
