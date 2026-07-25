package com.tinkerlab.maameowpatch.http;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class LogTimeTest {

    @Test
    public void formatPattern() {
        String s = LogTime.format(0L);
        assertTrue(s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
    }
}
