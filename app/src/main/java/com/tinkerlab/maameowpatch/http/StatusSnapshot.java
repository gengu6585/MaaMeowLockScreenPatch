package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;

/**
 * 组装对外 status（纯 JVM 合并逻辑，便于单测）。
 * 运行时字段由调用方填入 {@code runtime}。
 */
public final class StatusSnapshot {

    private StatusSnapshot() {
    }

    /**
     * @param tracker TaskRunTracker.statusJson()
     * @param runtime meow_ready / game / composition_state / …
     */
    public static JSONObject merge(JSONObject tracker, JSONObject runtime) throws Exception {
        JSONObject out = tracker == null ? new JSONObject() : new JSONObject(tracker.toString());
        if (runtime != null) {
            JSONObject r = new JSONObject(runtime.toString());
            java.util.Iterator<String> keys = r.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, r.get(k));
            }
        }
        if (!out.has("ok")) out.put("ok", true);
        long now = System.currentTimeMillis();
        out.put("synced_at", now);
        out.put("synced_time", LogTime.format(now));
        return out;
    }

    /** 构造标准 game 子对象。 */
    public static JSONObject gameObject(String pkg, boolean running, int pid) throws Exception {
        JSONObject g = new JSONObject();
        g.put("package", pkg == null ? "" : pkg);
        g.put("running", running);
        if (pid > 0) g.put("pid", pid);
        else g.put("pid", JSONObject.NULL);
        return g;
    }
}
