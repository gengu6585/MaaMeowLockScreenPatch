package com.tinkerlab.maameowpatch.http;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImportantLogFilterTest {

    @Test
    public void keepsErrorsAndKeyPhrases() {
        assertTrue(ImportantLogFilter.isImportantMeowLog("ERROR", "anything"));
        assertTrue(ImportantLogFilter.isImportantMeowLog("INFO", "完成任务 Fight"));
        assertTrue(ImportantLogFilter.isImportantMeowLog("INFO", "已开始行动"));
        assertFalse(ImportantLogFilter.isImportantMeowLog("INFO", "心跳 ok"));
        assertFalse(ImportantLogFilter.isImportantMeowLog("DEBUG", "frame 123"));
    }

    @Test
    public void sseTypesExcludeExecState() {
        assertTrue(ImportantLogFilter.isSseEventType("log"));
        assertTrue(ImportantLogFilter.isSseEventType("run_accepted"));
        assertTrue(ImportantLogFilter.isSseEventType("terminal"));
        assertFalse(ImportantLogFilter.isSseEventType("exec_state"));
    }
}
