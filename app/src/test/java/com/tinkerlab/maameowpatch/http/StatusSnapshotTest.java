package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StatusSnapshotTest {

    @Test
    public void mergeAddsRuntimeAndSyncedAt() throws Exception {
        JSONObject tracker = new JSONObject()
                .put("state", "RUNNING")
                .put("active", true)
                .put("run_id", "abc");
        JSONObject runtime = new JSONObject()
                .put("meow_ready", true)
                .put("composition_state", "RUNNING")
                .put("task_running", true)
                .put("game", StatusSnapshot.gameObject("com.hypergryph.arknights", true, 42))
                .put("meow", StatusSnapshot.gameObject("com.aliothmoon.maameow", true, 99));
        JSONObject out = StatusSnapshot.merge(tracker, runtime);
        assertTrue(out.getBoolean("ok"));
        assertTrue(out.getBoolean("meow_ready"));
        assertTrue(out.getBoolean("task_running"));
        assertEquals("RUNNING", out.getString("composition_state"));
        assertTrue(out.getJSONObject("game").getBoolean("running"));
        assertEquals(42, out.getJSONObject("game").getInt("pid"));
        assertEquals(99, out.getJSONObject("meow").getInt("pid"));
        assertTrue(out.has("synced_at"));
        assertTrue(out.getString("synced_time")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
    }

    @Test
    public void gameNotRunningNullPid() throws Exception {
        JSONObject g = StatusSnapshot.gameObject("com.hypergryph.arknights", false, -1);
        assertFalse(g.getBoolean("running"));
        assertTrue(g.isNull("pid"));
    }
}
