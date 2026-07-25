package com.tinkerlab.maameowpatch.http;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 本地起 NanoHTTPD，用 HttpURLConnection 打真实 TCP（不依赖设备）。
 */
public class MeowHttpServerTest {

    private MeowHttpServer server;
    private int port;

    @Before
    public void setUp() throws Exception {
        port = freePort();
        MeowHttpRouterTest.FakeBackend fake = new MeowHttpRouterTest.FakeBackend();
        fake.status.put("state", "IDLE");
        server = new MeowHttpServer("127.0.0.1", port, new MeowHttpRouter(fake));
        server.start(5000, false);
    }

    @After
    public void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    public void getHealthOverTcp() throws Exception {
        JSONObject o = getJson("/v1/health");
        assertTrue(o.getBoolean("ok"));
    }

    @Test
    public void getStatusOverTcp() throws Exception {
        JSONObject o = getJson("/v1/status");
        assertEquals("IDLE", o.getString("state"));
    }

    @Test
    public void postTasksOverTcp() throws Exception {
        String body = "{\"tasks\":[{\"type\":\"Fight\",\"params\":{\"stage\":\"1-7\"}}]}";
        JSONObject o = postJson("/v1/tasks", body);
        assertTrue(o.getBoolean("accepted"));
    }

    @Test
    public void postStopOverTcp() throws Exception {
        JSONObject o = postJson("/v1/stop", "{}");
        assertEquals("IDLE", o.getString("state"));
    }

    @Test
    public void postTasksSseOverTcp() throws Exception {
        // FakeBackend.start 立即 accepted；status 保持 IDLE+无 result → 用短 timeout 结束
        String body = "{\"tasks\":[{\"type\":\"Fight\",\"params\":{\"stage\":\"1-7\"}}],\"timeout_ms\":1500}";
        URL url = new URL("http://127.0.0.1:" + port + "/v1/tasks?stream=1&timeout_ms=1500");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }
        assertEquals(200, c.getResponseCode());
        assertTrue(c.getContentType().contains("text/event-stream"));
        String text;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            text = sb.toString();
        }
        assertTrue(text.contains("event: hello"));
        assertTrue(text.contains("event: accepted") || text.contains("event: error")
                || text.contains("event: done"));
    }

    private JSONObject getJson(String path) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        c.setRequestMethod("GET");
        assertEquals(200, c.getResponseCode());
        return read(c);
    }

    private JSONObject postJson(String path, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }
        assertEquals(200, c.getResponseCode());
        return read(c);
    }

    private static JSONObject read(HttpURLConnection c) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            return ss.getLocalPort();
        }
    }
}
