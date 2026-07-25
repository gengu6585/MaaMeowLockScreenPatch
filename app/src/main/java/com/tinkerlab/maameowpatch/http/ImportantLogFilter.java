package com.tinkerlab.maameowpatch.http;

/**
 * SSE / events 只保留关键任务日志，避免刷屏。
 */
public final class ImportantLogFilter {

    private ImportantLogFilter() {
    }

    /** Meow UI 日志是否值得推送。 */
    public static boolean isImportantMeowLog(String level, String content) {
        if (content == null || content.isEmpty()) return false;
        if ("ERROR".equals(level) || "WARNING".equals(level)) return true;
        String c = content;
        return c.contains("完成任务")
                || c.contains("任务出错")
                || c.contains("已开始行动")
                || c.contains("开始任务")
                || c.contains("StageDrops")
                || c.contains("连接")
                || c.contains("截图失败")
                || c.contains("服务异常")
                || c.contains("干员不可用")
                || c.contains("识别")
                || c.contains("导航")
                || c.contains("作战")
                || c.contains("FightBegin")
                || c.contains("UsePrts")
                || c.contains("StartButton")
                || c.contains("SideStory")
                || c.contains("AD-EX")
                || c.contains("OpenOpt")
                || c.contains("SwipeToStage")
                || c.contains("Copilot")
                || c.contains("自动战斗")
                || c.contains("编队")
                || c.contains("代理指挥");
    }

    /**
     * 事件总线里哪些 type 进入 SSE。
     * 不含频繁的 exec_state（由 streamer 对 status 去重推送）。
     */
    public static boolean isSseEventType(String type) {
        if (type == null) return false;
        switch (type) {
            case "log":
            case "run_accepted":
            case "start_ok":
            case "session_end":
            case "terminal":
            case "stop_requested":
                return true;
            default:
                return false;
        }
    }

    public static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
