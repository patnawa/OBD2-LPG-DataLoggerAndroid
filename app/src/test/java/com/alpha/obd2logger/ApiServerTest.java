package com.alpha.obd2logger;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;

public class ApiServerTest {

    private static class FakeHTTPSession implements IHTTPSession {
        private final Method method;
        private final String uri;
        private final Map<String, String> parms = new HashMap<>();
        private final Map<String, String> headers = new HashMap<>();
        private String body = "";

        FakeHTTPSession(Method method, String uri) {
            this.method = method;
            this.uri = uri;
        }

        void setParam(String key, String value) {
            parms.put(key, value);
        }

        void setBody(String body) {
            this.body = body;
        }

        @Override
        public void execute() throws IOException {}

        @Override
        public NanoHTTPD.CookieHandler getCookies() {
            return null;
        }

        @Override
        public Map<String, String> getHeaders() {
            return headers;
        }

        @Override
        public InputStream getInputStream() {
            return null;
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Map<String, String> getParms() {
            return parms;
        }

        @Override
        public String getQueryParameterString() {
            return "";
        }

        @Override
        public String getUri() {
            return uri;
        }

        @Override
        public void parseBody(Map<String, String> files) throws IOException, NanoHTTPD.ResponseException {
            files.put("postData", body);
        }

        @Override
        public String getRemoteIpAddress() {
            return "127.0.0.1";
        }

        @Override
        public String getRemoteHostName() {
            return "localhost";
        }

        @Override
        public Map<String, List<String>> getParameters() {
            Map<String, List<String>> result = new HashMap<>();
            for (Map.Entry<String, String> entry : parms.entrySet()) {
                List<String> list = new ArrayList<>();
                list.add(entry.getValue());
                result.put(entry.getKey(), list);
            }
            return result;
        }
    }

    @Test
    public void testPingEndpoint() throws Exception {
        ApiServer server = new ApiServer(8080);
        FakeHTTPSession session = new FakeHTTPSession(Method.GET, "/api/ping");
        Response response = server.serve(session);
        assertEquals(Response.Status.OK, response.getStatus());
        assertEquals("application/json", response.getMimeType());
    }

    @Test
    public void testStatusAndDataEndpoints() throws Exception {
        ApiServer server = new ApiServer(8080);
        
        // Initial state
        FakeHTTPSession sessionStatus = new FakeHTTPSession(Method.GET, "/api/status");
        Response respStatus1 = server.serve(sessionStatus);
        assertEquals(Response.Status.OK, respStatus1.getStatus());

        // Update data
        List<SensorSample> samples = new ArrayList<>();
        samples.add(new SensorSample("01_0C", "Engine RPM", 2000.0, "rpm", "ok"));
        samples.add(new SensorSample("01_0B", "Intake Manifold Pressure", 50.0, "kPa", "ok"));
        samples.add(new SensorSample("01_06", "Short Term Fuel Trim", 3.0, "%", "ok"));
        samples.add(new SensorSample("01_07", "Long Term Fuel Trim", 5.0, "%", "ok"));
        samples.add(new SensorSample("01_05", "Engine Coolant Temp", 90.0, "C", "ok"));
        samples.add(new SensorSample("01_03", "Fuel System Status", 2.0, "", "ok")); // Closed Loop

        DataRecord record = new DataRecord("2026-07-02T22:30:00.000", 12.0, "petrol", "Toyota", "TESTVIN12345", samples);
        server.setLatestData(record, true);

        // Check status updated
        Response respStatus2 = server.serve(sessionStatus);
        assertEquals(Response.Status.OK, respStatus2.getStatus());

        // Check data updated
        FakeHTTPSession sessionData = new FakeHTTPSession(Method.GET, "/api/data");
        Response respData = server.serve(sessionData);
        assertEquals(Response.Status.OK, respData.getStatus());
    }

    @Test
    public void testMapImportExportAndClear() throws Exception {
        ApiServer server = new ApiServer(8080);

        // Test Import
        String importJson = "{"
            + "\"petrolMap\": {\"2000_3.00\": {\"avg\": -2.5, \"hits\": 10}},"
            + "\"lpgMap\": {\"2000_3.00\": {\"avg\": 1.5, \"hits\": 15}}"
            + "}";
        FakeHTTPSession importSession = new FakeHTTPSession(Method.POST, "/api/map/import");
        importSession.setBody(importJson);
        Response importResp = server.serve(importSession);
        assertEquals(Response.Status.OK, importResp.getStatus());

        // Verify import in internal map
        assertEquals(1, server.getPetrolMap().size());
        assertEquals(1, server.getLpgMap().size());

        // Test GET /api/map
        FakeHTTPSession mapSession = new FakeHTTPSession(Method.GET, "/api/map");
        Response mapResp = server.serve(mapSession);
        assertEquals(Response.Status.OK, mapResp.getStatus());

        // Test min_hits filter
        mapSession.setParam("min_hits", "12"); // petrol has 10, so it should be excluded
        Response mapFilteredResp = server.serve(mapSession);
        assertEquals(Response.Status.OK, mapFilteredResp.getStatus());

        // Test export CSV
        FakeHTTPSession exportSession = new FakeHTTPSession(Method.GET, "/api/map/export");
        Response exportResp = server.serve(exportSession);
        assertEquals(Response.Status.OK, exportResp.getStatus());
        assertEquals("text/csv", exportResp.getMimeType());

        // Test summary
        FakeHTTPSession summarySession = new FakeHTTPSession(Method.GET, "/api/map/summary");
        Response summaryResp = server.serve(summarySession);
        assertEquals(Response.Status.OK, summaryResp.getStatus());

        // Test Clear
        FakeHTTPSession clearSession = new FakeHTTPSession(Method.DELETE, "/api/map");
        Response clearResp = server.serve(clearSession);
        assertEquals(Response.Status.OK, clearResp.getStatus());
        assertEquals(0, server.getPetrolMap().size());
        assertEquals(0, server.getLpgMap().size());
    }
}
