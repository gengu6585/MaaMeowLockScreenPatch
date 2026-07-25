package com.tinkerlab.maameowpatch.http;

import java.nio.charset.StandardCharsets;

/** SSE 帧格式化（纯 JVM）。 */
public final class SseFormatter {

    private SseFormatter() {
    }

    public static String event(String name, String dataJson) {
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isEmpty()) {
            sb.append("event: ").append(name).append('\n');
        }
        // data 可能多行：按 SSE 规范每行前加 data:
        String data = dataJson == null ? "{}" : dataJson;
        for (String line : data.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    public static byte[] eventBytes(String name, String dataJson) {
        return event(name, dataJson).getBytes(StandardCharsets.UTF_8);
    }

    public static String comment(String text) {
        return ": " + (text == null ? "" : text) + "\n\n";
    }
}
