package com.tinkerlab.maameowpatch.http;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SseFormatterTest {

    @Test
    public void formatsNamedEvent() {
        String s = SseFormatter.event("log", "{\"msg\":\"hi\"}");
        assertTrue(s.startsWith("event: log\n"));
        assertTrue(s.contains("data: {\"msg\":\"hi\"}\n"));
        assertTrue(s.endsWith("\n\n") || s.endsWith("\n"));
        assertEquals("event: log\ndata: {\"msg\":\"hi\"}\n\n", s);
    }

    @Test
    public void commentPing() {
        assertEquals(": ping\n\n", SseFormatter.comment("ping"));
    }
}
