package com.tinkerlab.maameowpatch.http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/** 读取 jsonl 事件尾部（纯 JVM）。 */
public final class EventsTail {

    private EventsTail() {
    }

    public static JSONObject readTail(File file, int limit) throws Exception {
        JSONArray events = new JSONArray();
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("path", file == null ? JSONObject.NULL : file.getAbsolutePath());
        if (file == null || !file.isFile()) {
            out.put("events", events);
            out.put("count", 0);
            return out;
        }
        int n = Math.max(1, Math.min(500, limit));
        ArrayDeque<String> dq = new ArrayDeque<>(n);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (dq.size() >= n) dq.removeFirst();
                dq.addLast(line);
            }
        }
        for (String line : dq) {
            try {
                events.put(new JSONObject(line));
            } catch (Exception e) {
                JSONObject raw = new JSONObject();
                raw.put("raw", line);
                events.put(raw);
            }
        }
        out.put("events", events);
        out.put("count", events.length());
        return out;
    }
}
