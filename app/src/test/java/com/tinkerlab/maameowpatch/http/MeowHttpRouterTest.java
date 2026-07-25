package com.tinkerlab.maameowpatch.http;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MeowHttpRouterTest {

    private FakeBackend fake;
    private MeowHttpRouter router;

    @Before
    public void setUp() {
        fake = new FakeBackend();
        router = new MeowHttpRouter(fake);
    }

    @Test
    public void healthOk() throws Exception {
        MeowHttpResponse r = router.handle("GET", "/v1/health", null, null);
        assertEquals(200, r.status);
        assertTrue(new JSONObject(r.body).getBoolean("ok"));
    }

    @Test
    public void statusOk() throws Exception {
        fake.status.put("state", "RUNNING");
        MeowHttpResponse r = router.handle("GET", "/v1/status", null, null);
        assertEquals(200, r.status);
        assertEquals("RUNNING", new JSONObject(r.body).getString("state"));
    }

    @Test
    public void tasksRequiresBody() {
        MeowHttpResponse r = router.handle("POST", "/v1/tasks", "{}", null);
        assertEquals(400, r.status);
        assertTrue(r.body.contains("tasks"));
    }

    @Test
    public void tasksAccepted() throws Exception {
        String body = "{\"tasks\":[{\"type\":\"Fight\",\"params\":{\"stage\":\"1-7\"}}]}";
        MeowHttpResponse r = router.handle("POST", "/v1/tasks", body, null);
        assertEquals(200, r.status);
        assertEquals(1, fake.startCalls.get());
        assertTrue(new JSONObject(r.body).getBoolean("accepted"));
    }

    @Test
    public void stopOk() throws Exception {
        MeowHttpResponse r = router.handle("POST", "/v1/stop", "", null);
        assertEquals(200, r.status);
        assertEquals(1, fake.stopCalls.get());
    }

    @Test
    public void resourceRequiresPath() {
        MeowHttpResponse r = router.handle("POST", "/v1/resource", "{}", null);
        assertEquals(400, r.status);
    }

    @Test
    public void resourceOk() throws Exception {
        String body = "{\"resource_path\":\"/storage/emulated/0/maa/MaaResource\"}";
        MeowHttpResponse r = router.handle("POST", "/v1/resource", body, null);
        assertEquals(200, r.status);
        assertTrue(new JSONObject(r.body).getBoolean("ok"));
    }

    @Test
    public void notReadyReturns503() throws Exception {
        fake.notReady = true;
        String body = "{\"tasks\":[{\"type\":\"Fight\"}]}";
        MeowHttpResponse r = router.handle("POST", "/v1/tasks", body, null);
        assertEquals(503, r.status);
        assertFalse(new JSONObject(r.body).optBoolean("ok", true));
    }

    @Test
    public void eventsLimit() throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("limit", "2");
        MeowHttpResponse r = router.handle("GET", "/v1/events", null, q);
        assertEquals(200, r.status);
        assertEquals(2, new JSONObject(r.body).getInt("count"));
    }

    @Test
    public void unknown404() {
        MeowHttpResponse r = router.handle("GET", "/v1/nope", null, Collections.emptyMap());
        assertEquals(404, r.status);
    }

    @Test
    public void normalizePathStripsQueryAndSlash() {
        assertEquals("/v1/status", MeowHttpRouter.normalizePath("/v1/status/?x=1"));
        assertEquals("/v1/health", MeowHttpRouter.normalizePath("/v1/health"));
    }

    @Test
    public void tasksStreamReturnsSse() {
        String body = "{\"tasks\":[{\"type\":\"Fight\",\"params\":{\"stage\":\"1-7\"}}]}";
        Map<String, String> q = new HashMap<>();
        q.put("stream", "1");
        MeowHttpResponse r = router.handle("POST", "/v1/tasks", body, q, null);
        assertEquals(200, r.status);
        assertTrue(r.isSse());
        assertTrue(r.contentType.contains("text/event-stream"));
    }

    @Test
    public void acceptHeaderTriggersStream() {
        String body = "{\"tasks\":[{\"type\":\"Fight\"}]}";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "text/event-stream");
        MeowHttpResponse r = router.handle("POST", "/v1/tasks", body, null, headers);
        assertTrue(r.isSse());
    }

    @Test
    public void getStreamReturnsSse() {
        MeowHttpResponse r = router.handle("GET", "/v1/stream", null, null, null);
        assertTrue(r.isSse());
    }

    @Test
    public void gameRequiresAction() {
        MeowHttpResponse r = router.handle("POST", "/v1/game", "{}", null, null);
        assertEquals(400, r.status);
    }

    @Test
    public void gameStartOk() throws Exception {
        MeowHttpResponse r = router.handle("POST", "/v1/game",
                "{\"action\":\"start\"}", null, null);
        assertEquals(200, r.status);
        assertEquals("start", new JSONObject(r.body).getString("action"));
    }

    static final class FakeBackend implements MeowHttpBackend {
        final JSONObject status = new JSONObject();
        final AtomicInteger startCalls = new AtomicInteger();
        final AtomicInteger stopCalls = new AtomicInteger();
        boolean notReady;

        FakeBackend() {
            try {
                status.put("state", "IDLE");
                status.put("active", false);
            } catch (Exception ignored) {
            }
        }

        @Override
        public JSONObject health() throws Exception {
            return new JSONObject().put("ok", true).put("service", "fake");
        }

        @Override
        public JSONObject status() throws Exception {
            return status;
        }

        @Override
        public JSONObject events(int limit) throws Exception {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < limit; i++) {
                arr.put(new JSONObject().put("i", i));
            }
            return new JSONObject().put("ok", true).put("events", arr).put("count", arr.length());
        }

        @Override
        public JSONObject startTasks(JSONObject body) throws Exception {
            if (notReady) throw new IllegalStateException("Meow not ready");
            startCalls.incrementAndGet();
            return new JSONObject().put("accepted", true).put("state", "STARTING");
        }

        @Override
        public JSONObject stopTasks() throws Exception {
            stopCalls.incrementAndGet();
            return new JSONObject().put("state", "IDLE");
        }

        @Override
        public JSONObject loadResource(JSONObject body) throws Exception {
            return new JSONObject().put("ok", true);
        }

        @Override
        public JSONObject controlGame(JSONObject body) throws Exception {
            return new JSONObject()
                    .put("ok", true)
                    .put("action", body.optString("action"))
                    .put("accepted", true)
                    .put("state", "STARTING");
        }
    }
}
