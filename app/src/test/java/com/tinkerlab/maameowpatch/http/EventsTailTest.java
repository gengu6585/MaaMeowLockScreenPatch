package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EventsTailTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void missingFileEmpty() throws Exception {
        JSONObject o = EventsTail.readTail(new File(tmp.getRoot(), "nope.jsonl"), 10);
        assertTrue(o.getBoolean("ok"));
        assertEquals(0, o.getInt("count"));
    }

    @Test
    public void readsLastN() throws Exception {
        File f = tmp.newFile("events.jsonl");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("{\"i\":").append(i).append("}\n");
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject o = EventsTail.readTail(f, 2);
        assertEquals(2, o.getInt("count"));
        assertEquals(3, o.getJSONArray("events").getJSONObject(0).getInt("i"));
        assertEquals(4, o.getJSONArray("events").getJSONObject(1).getInt("i"));
    }
}
