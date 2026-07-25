package com.tinkerlab.maameowpatch;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CopilotParamsNormalizerTest {

    @Test
    public void inferExStageFromFilename() {
        assertEquals("AD-EX-2",
                CopilotParamsNormalizer.inferStageNameFromFilename("96311_AD-EX-2.json"));
        assertEquals("AD-1",
                CopilotParamsNormalizer.inferStageNameFromFilename("97725_AD-1.json"));
    }

    @Test
    public void skipNavigationKeepsFilename() throws Exception {
        String in = "{\"filename\":\"96311_AD-EX-2.json\",\"skip_navigation\":true}";
        String out = CopilotParamsNormalizer.normalize("Copilot", in);
        JSONObject o = new JSONObject(out);
        assertEquals("96311_AD-EX-2.json", o.getString("filename"));
        assertFalse(o.has("copilot_list"));
        assertEquals(1, o.getInt("loop_times"));
    }

    @Test
    public void normalizePromotesToCopilotList() throws Exception {
        String in = "{\"filename\":\"97725_AD-1.json\"}";
        String out = CopilotParamsNormalizer.normalize("Copilot", in);
        JSONObject o = new JSONObject(out);
        assertTrue(o.has("copilot_list"));
        JSONArray list = o.getJSONArray("copilot_list");
        assertEquals(1, list.length());
        assertEquals("AD-1", list.getJSONObject(0).getString("stage_name"));
        assertEquals("97725_AD-1.json", list.getJSONObject(0).getString("filename"));
    }

    @Test
    public void nonCopilotUnchanged() throws Exception {
        String in = "{\"stage\":\"1-7\"}";
        assertEquals(in, CopilotParamsNormalizer.normalize("Fight", in));
    }
}
