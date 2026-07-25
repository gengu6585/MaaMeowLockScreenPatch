package com.tinkerlab.maameowpatch.http;

import com.tinkerlab.maameowpatch.MeowHttpServerBootstrap;
import com.tinkerlab.maameowpatch.MeowRuntime;
import com.tinkerlab.maameowpatch.TaskExecutor;
import com.tinkerlab.maameowpatch.TaskRunTracker;

import org.json.JSONObject;

import java.io.File;

/** 生产后端：TaskExecutor + TaskRunTracker（与 Intent 同路径）。 */
public final class LiveMeowHttpBackend implements MeowHttpBackend {

    @Override
    public JSONObject health() throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("service", "maameow-http");
        o.put("ready", MeowRuntime.ready());
        o.put("http_port", MeowHttpServer.port());
        o.put("http_running", MeowHttpServer.isRunning());
        o.put("version", MeowHttpServerBootstrap.VERSION);
        return o;
    }

    @Override
    public JSONObject status() {
        return TaskExecutor.refreshStatus();
    }

    @Override
    public JSONObject events(int limit) throws Exception {
        return EventsTail.readTail(new File(TaskRunTracker.EVENTS_PATH), limit);
    }

    @Override
    public JSONObject startTasks(JSONObject body) throws Exception {
        return TaskExecutor.startTasks(body);
    }

    @Override
    public JSONObject stopTasks() throws Exception {
        return TaskExecutor.stopTasks();
    }

    @Override
    public JSONObject loadResource(JSONObject body) throws Exception {
        return TaskExecutor.loadResource(body);
    }

    @Override
    public JSONObject controlGame(JSONObject body) throws Exception {
        return TaskExecutor.controlGame(body);
    }
}
