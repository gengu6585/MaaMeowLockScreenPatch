package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;

/** HTTP API 后端（生产接 TaskExecutor；单测用 Fake）。 */
public interface MeowHttpBackend {

    JSONObject health() throws Exception;

    JSONObject status() throws Exception;

    JSONObject events(int limit) throws Exception;

    JSONObject startTasks(JSONObject body) throws Exception;

    JSONObject stopTasks() throws Exception;

    JSONObject loadResource(JSONObject body) throws Exception;

    /** start|close|kill — 见 TaskExecutor.controlGame */
    JSONObject controlGame(JSONObject body) throws Exception;
}
