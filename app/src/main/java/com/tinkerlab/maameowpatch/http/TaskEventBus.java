package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 进程内关键事件环（供 SSE 实时拉取）。纯 JVM。
 * <p>不过滤重复日志：MAA 反复「截图失败」等应原样推送。
 */
public final class TaskEventBus {

    public static final TaskEventBus INSTANCE = new TaskEventBus(256);

    private final int capacity;
    private final ArrayDeque<JSONObject> ring;
    private long seq;

    public TaskEventBus(int capacity) {
        this.capacity = Math.max(16, capacity);
        this.ring = new ArrayDeque<>(this.capacity);
        this.seq = 0L;
    }

    public synchronized long publish(String type, String msg, String runId) {
        if (!ImportantLogFilter.isSseEventType(type)) return seq;
        try {
            long now = System.currentTimeMillis();
            JSONObject o = new JSONObject();
            long id = ++seq;
            o.put("seq", id);
            o.put("ts", now);
            o.put("time", LogTime.format(now));
            o.put("type", type);
            o.put("msg", msg == null ? "" : ImportantLogFilter.truncate(msg, 240));
            if (runId != null) o.put("run_id", runId);
            if (ring.size() >= capacity) ring.removeFirst();
            ring.addLast(o);
            return id;
        } catch (Exception e) {
            return seq;
        }
    }

    /** 返回 seq &gt; afterSeq 的事件（按序）。 */
    public synchronized List<JSONObject> since(long afterSeq) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : ring) {
            if (o.optLong("seq", 0L) > afterSeq) out.add(o);
        }
        return out;
    }

    public synchronized long latestSeq() {
        return seq;
    }

    public synchronized void clear() {
        ring.clear();
        seq = 0L;
    }
}
