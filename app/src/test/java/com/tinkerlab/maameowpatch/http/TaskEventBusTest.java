package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TaskEventBusTest {

    @Test
    public void keepsRepeatedScreenshotErrors() {
        TaskEventBus bus = new TaskEventBus(32);
        bus.publish("log", "[ERROR] 截图失败，如反复出现请尝试重启！", "r1");
        bus.publish("log", "[ERROR] 截图失败，如反复出现请尝试重启！", "r1");
        bus.publish("log", "[ERROR] 截图失败，如反复出现请尝试重启！", "r1");
        assertEquals(3, bus.since(0).size());
    }

    @Test
    public void eventHasFormattedTime() throws Exception {
        TaskEventBus bus = new TaskEventBus(32);
        bus.publish("log", "[INFO] 开始任务", "r1");
        List<JSONObject> list = bus.since(0);
        assertEquals(1, list.size());
        String time = list.get(0).getString("time");
        assertTrue(time.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
    }
}
