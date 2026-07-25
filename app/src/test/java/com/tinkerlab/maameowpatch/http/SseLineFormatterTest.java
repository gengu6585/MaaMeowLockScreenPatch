package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class SseLineFormatterTest {

    @Test
    public void formatsLogWithTime() throws Exception {
        JSONObject o = new JSONObject()
                .put("time", "2026-07-26 01:51:23.456")
                .put("msg", "[ERROR] 截图失败");
        String line = SseLineFormatter.formatEvent("log", o.toString());
        assertTrue(line.startsWith("2026-07-26 01:51:23.456"));
        assertTrue(line.contains("LOG"));
        assertTrue(line.contains("截图失败"));
    }

    @Test
    public void formatsStatus() throws Exception {
        JSONObject o = new JSONObject()
                .put("time", "2026-07-26 01:51:24.000")
                .put("state", "RUNNING")
                .put("active", true)
                .put("result", JSONObject.NULL)
                .put("task_running", true)
                .put("game", StatusSnapshot.gameObject("com.hypergryph.arknights", false, -1));
        String line = SseLineFormatter.formatEvent("status", o.toString());
        assertTrue(line.contains("STATUS"));
        assertTrue(line.contains("state=RUNNING"));
        assertTrue(line.contains("game=false"));
    }
}
