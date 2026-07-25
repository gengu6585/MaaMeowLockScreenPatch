package com.tinkerlab.maameowpatch;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Copilot 参数规范化（纯 JVM，可供本地单测）。
 */
public final class CopilotParamsNormalizer {

    private CopilotParamsNormalizer() {
    }

    public static String normalize(String type, String paramsJson) throws Exception {
        if (!isCopilotType(type)) return paramsJson;
        JSONObject p = new JSONObject(paramsJson);

        if (p.has("copilot_list") || p.has("list")) {
            return paramsJson;
        }

        String filename = p.optString("filename", "");
        if (filename.isEmpty()) return paramsJson;

        boolean skipNav = p.optBoolean("skip_navigation", false)
                || p.optBoolean("skip_nav", false);
        String stageName = p.optString("stage_name", "").trim();
        if (skipNav) {
            JSONObject out = new JSONObject(p.toString());
            out.remove("skip_navigation");
            out.remove("skip_nav");
            out.remove("stage_name");
            if (!out.has("loop_times")) out.put("loop_times", 1);
            return out.toString();
        }

        if (stageName.isEmpty()) {
            stageName = inferStageNameFromFilename(filename);
        }
        if (stageName.isEmpty()) {
            return paramsJson;
        }

        JSONObject item = new JSONObject();
        item.put("id", 0);
        item.put("filename", filename);
        item.put("stage_name", stageName);
        item.put("is_raid", p.optBoolean("is_raid", false));

        JSONArray list = new JSONArray();
        list.put(item);

        JSONObject out = new JSONObject(p.toString());
        out.remove("filename");
        out.remove("stage_name");
        out.remove("is_raid");
        out.remove("skip_navigation");
        out.remove("skip_nav");
        out.put("copilot_list", list);
        if (!out.has("loop_times")) out.put("loop_times", 1);
        return out.toString();
    }

    public static boolean isCopilotType(String type) {
        String t = type == null ? "" : type.trim();
        return "Copilot".equalsIgnoreCase(t)
                || "COPILOT".equals(t)
                || "SSSCopilot".equalsIgnoreCase(t)
                || "SSS_COPILOT".equals(t);
    }

    public static String inferStageNameFromFilename(String filename) {
        String base = filename;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.endsWith(".json")) base = base.substring(0, base.length() - 5);
        java.util.regex.Matcher ex = java.util.regex.Pattern
                .compile("(?:^|[_-])([A-Za-z]{1,6}-EX-\\d+)(?:$|[_-])")
                .matcher(base);
        if (ex.find()) return ex.group(1);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|[_-])([A-Za-z]{1,6}-\\d+(?:-[A-Za-z0-9]+)*)(?:$|[_-])")
                .matcher(base);
        String last = "";
        while (m.find()) last = m.group(1);
        if (!last.isEmpty()) return last;
        if (base.matches("[A-Za-z0-9]+-(?:EX-)?\\d+(?:-[A-Za-z0-9]+)*")) return base;
        return "";
    }
}
