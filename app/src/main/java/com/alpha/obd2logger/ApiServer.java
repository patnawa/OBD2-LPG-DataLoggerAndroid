package com.alpha.obd2logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import fi.iki.elonen.NanoHTTPD;

public class ApiServer extends NanoHTTPD {

    private volatile DataRecord latestData;
    private volatile boolean isLogging;
    private volatile boolean adapterConnected;
    private volatile String transportMode;
    private volatile String vehicleBrand;
    private volatile int recordCount;
    private volatile long sessionStartMs;
    
    public interface DtcProvider {
        java.util.List<DtcCode> getStoredDtcs();
        java.util.List<DtcCode> getPendingDtcs();
        boolean triggerClearDtcs();
    }
    
    private volatile DtcProvider dtcProvider;

    public void setDtcProvider(DtcProvider provider) {
        this.dtcProvider = provider;
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSE (Server-Sent Events) — Real-time data streaming
    // ═══════════════════════════════════════════════════════════════

    /**
     * Tracks active SSE client streams. Each client gets a PipedOutputStream
     * that we write to from setLatestData(). The corresponding PipedInputStream
     * feeds NanoHTTPD's chunked response.
     */
    private final Set<SseClient> sseClients = new CopyOnWriteArraySet<>();

    private static class SseClient {
        final PipedOutputStream out;
        final PipedInputStream in;
        volatile boolean closed = false;

        SseClient() throws IOException {
            // 16KB pipe buffer — enough for ~100 SSE events before backpressure
            this.in = new PipedInputStream(16384);
            this.out = new PipedOutputStream(in);
        }

        void send(String event) {
            if (closed) return;
            try {
                out.write(event.getBytes("UTF-8"));
                out.flush();
            } catch (IOException e) {
                closed = true;
            }
        }

        void close() {
            closed = true;
            try { out.close(); } catch (IOException ignored) {}
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Called from LoggerService.publishRecord → setLatestData.
     * Pushes the new record as an SSE event to all connected clients.
     */
    private void broadcastSse(DataRecord record) {
        if (sseClients.isEmpty()) return;

        String json = recordToSseJson(record);
        String event = "data: " + json + "\n\n";

        for (SseClient client : sseClients) {
            client.send(event);
        }

        // Remove closed clients
        sseClients.removeIf(c -> c.closed);
    }

    /**
     * Convert a DataRecord to compact JSON for SSE streaming.
     */
    private String recordToSseJson(DataRecord record) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ts", record.getTimestamp());
            obj.put("elapsed", Math.round(record.getElapsedS() * 1000.0) / 1000.0);
            obj.put("fuel", record.getFuelMode());
            obj.put("vin", record.getVin() != null ? record.getVin() : "");
            obj.put("vehicleBrand", record.getVehicleBrand() != null ? record.getVehicleBrand() : "");

            // Use pidKey as the JSON key to avoid collisions (multiple
            // samples share the same display name — e.g. "Fuel Economy").
            JSONObject sensors = new JSONObject();
            for (SensorSample s : record.getSamples()) {
                JSONObject sensorObj = new JSONObject();
                sensorObj.put("name", s.getName());
                sensorObj.put("value", s.getValue());
                sensorObj.put("unit", s.getUnit());
                sensorObj.put("status", s.getStatus());
                sensors.put(s.getPidKey(), sensorObj);
            }
            obj.put("sensors", sensors);
        } catch (JSONException ignored) {}
        return obj.toString();
    }

    // Fuel Map Constants matching FuelMapView (MAP kPa bins)
    private static final int RPM_MIN = 500;
    private static final int RPM_MAX = 6500;
    private static final int RPM_STEP = 500;
    
    private static final float[] MAP_BINS = {
        10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f, 120f, 150f, 200f, 250f
    };

    // Thread-safe map tracking
    public static class MapTrimData {
        private double sum = 0;
        private int hitCount = 0;

        public MapTrimData() {}

        public MapTrimData(double sum, int hitCount) {
            this.sum = sum;
            this.hitCount = hitCount;
        }

        public synchronized void add(double val) {
            sum += val;
            hitCount++;
        }

        public synchronized double getAverage() {
            return hitCount == 0 ? 0 : sum / hitCount;
        }

        public synchronized int getHitCount() {
            return hitCount;
        }

        public synchronized double getSum() {
            return sum;
        }
    }

    private final Map<String, MapTrimData> petrolMap = new ConcurrentHashMap<>();
    private final Map<String, MapTrimData> lpgMap = new ConcurrentHashMap<>();

    public ApiServer(int port) {
        super(port);
    }

    public Map<String, MapTrimData> getPetrolMap() {
        return petrolMap;
    }

    public Map<String, MapTrimData> getLpgMap() {
        return lpgMap;
    }

    public void setLatestData(DataRecord data, boolean isLogging) {
        this.latestData = data;
        this.isLogging = isLogging;
        if (data != null) {
            if (recordCount == 0 || sessionStartMs == 0) {
                sessionStartMs = System.currentTimeMillis();
            }
            recordCount++;
            updateLiveMap(data);
            broadcastSse(data);
        }
    }

    public void setAdapterConnected(boolean connected) {
        this.adapterConnected = connected;
    }

    public void setTransportMode(String mode) {
        this.transportMode = mode;
    }

    public void setVehicleBrand(String brand) {
        this.vehicleBrand = brand;
    }

    public void resetSession() {
        recordCount = 0;
        sessionStartMs = 0;
        petrolMap.clear();
        lpgMap.clear();
    }

    private void updateLiveMap(DataRecord record) {
        Double rpm = valueByKey(record, "01_0C");
        Double map = valueByKey(record, "01_0B");
        Double stft = valueByKey(record, "01_06");
        Double ltft = valueByKey(record, "01_07");
        Double ect = valueByKey(record, "01_05");
        Double fuelStatus = valueByKey(record, "01_03");

        double loadAxis;
        if (map != null) {
            loadAxis = map;
        } else {
            Double load = valueByKey(record, "01_04");
            loadAxis = (load != null) ? load : Double.NaN;
        }

        boolean isClosedLoop = true;
        if (fuelStatus != null) {
            isClosedLoop = (fuelStatus.intValue() & 0x02) != 0;
        }
        
        boolean tempOk = (ect == null) || (ect >= 80.0);

        if (rpm != null && !Double.isNaN(loadAxis) && isClosedLoop && tempOk) {
            double trim = 0;
            if (stft != null) {
                trim = stft + (ltft != null ? ltft : 0);
            } else if (ltft != null) {
                trim = ltft;
            }

            int rpmCell = (int) (Math.round(rpm / RPM_STEP) * RPM_STEP);
            rpmCell = Math.max(RPM_MIN, Math.min(RPM_MAX, rpmCell));

            // Use MAP (kPa) directly — no T.inj conversion.
            int mapIdx = findClosestBinIndex(loadAxis, MAP_BINS);
            float mapBinValue = MAP_BINS[mapIdx];

            String key = rpmCell + "_" + String.format(Locale.US, "%.2f", mapBinValue);
            
            boolean isLpg = FuelMode.fromString(record.getFuelMode()).isGaseous();
            Map<String, MapTrimData> targetMap = isLpg ? lpgMap : petrolMap;
            
            MapTrimData cellData = targetMap.get(key);
            if (cellData == null) {
                cellData = new MapTrimData();
                targetMap.put(key, cellData);
            }
            cellData.add(trim);
        }
    }

    private Double valueByKey(DataRecord record, String key) {
        for (SensorSample sample : record.getSamples()) {
            if (sample.getPidKey().equals(key)) return sample.getValue();
        }
        return null;
    }

    private static int findClosestBinIndex(double value, float[] bins) {
        int bestIdx = 0;
        double minDiff = Double.MAX_VALUE;
        for (int i = 0; i < bins.length; i++) {
            double diff = Math.abs(bins[i] - value);
            if (diff < minDiff) {
                minDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        
        Response response = null;
        
        // CORS preflight
        if (Method.OPTIONS.equals(method)) {
            response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
        } else if (Method.GET.equals(method)) {
            if ("/api/ping".equals(uri)) {
                response = handlePing();
            } else if ("/api/status".equals(uri)) {
                response = handleStatus();
            } else if ("/api/data".equals(uri)) {
                response = handleData();
            } else if ("/api/map".equals(uri)) {
                response = handleMap(session);
            } else if ("/api/map/summary".equals(uri)) {
                response = handleMapSummary();
            } else if ("/api/map/export".equals(uri)) {
                response = handleMapExport();
            } else if ("/api/dtc".equals(uri)) {
                response = handleGetDtc();
            } else if ("/api/stream".equals(uri) || "/api/events".equals(uri)) {
                response = handleSseStream(session);
            } else if ("/api/agent".equals(uri)) {
                response = handleAgent();
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            }
        } else if (Method.DELETE.equals(method)) {
            if ("/api/map".equals(uri)) {
                response = handleMapClear();
            } else if ("/api/dtc".equals(uri)) {
                response = handleDeleteDtc();
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            }
        } else if (Method.POST.equals(method)) {
            if ("/api/map/import".equals(uri)) {
                response = handleMapImport(session);
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            }
        } else {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed");
        }

        if (response != null) {
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Headers", "origin, accept, content-type");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS, HEAD");
        }
        
        return response;
    }

    private Response handlePing() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("status", "pong");
            obj.put("timestamp", System.currentTimeMillis());
            obj.put("uptimeMs", android.os.SystemClock.elapsedRealtime());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private Response handleStatus() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("status", "ok");
            obj.put("logging", isLogging);
            if (latestData != null) {
                obj.put("fuelMode", latestData.getFuelMode());
                obj.put("vin", latestData.getVin());
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private Response handleData() {
        if (latestData == null) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{}");
        }
        
        JSONObject obj = new JSONObject();
        try {
            // Session metadata
            obj.put("timestamp", latestData.getTimestamp());
            obj.put("elapsedS", latestData.getElapsedS());
            obj.put("fuelMode", latestData.getFuelMode());
            obj.put("vehicleBrand", latestData.getVehicleBrand() != null ? latestData.getVehicleBrand() : "");
            obj.put("vin", latestData.getVin() != null ? latestData.getVin() : "");
            obj.put("recordCount", recordCount);
            obj.put("adapterConnected", adapterConnected);
            obj.put("transportMode", transportMode != null ? transportMode : "UNKNOWN");
            if (sessionStartMs > 0) {
                obj.put("sessionDurationMs", System.currentTimeMillis() - sessionStartMs);
            }
            
            // Detailed sensors: array of objects with pidKey, name, value, unit, status.
            // Previous version used name as key — duplicates (multiple "Fuel Economy",
            // "Turbo Boost") silently overwrote each other, losing derived sensor data.
            JSONArray sensorsArr = new JSONArray();
            for (SensorSample s : latestData.getSamples()) {
                JSONObject sensorObj = new JSONObject();
                sensorObj.put("pidKey", s.getPidKey());
                sensorObj.put("name", s.getName());
                sensorObj.put("value", s.getValue());
                sensorObj.put("unit", s.getUnit());
                sensorObj.put("status", s.getStatus());
                sensorsArr.put(sensorObj);
            }
            obj.put("sensors", sensorsArr);
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private Response handleMap(IHTTPSession session) {
        String minHitsStr = session.getParms().get("min_hits");
        int minHits = 1;
        if (minHitsStr != null) {
            try {
                minHits = Integer.parseInt(minHitsStr);
            } catch (NumberFormatException ignored) {}
        }

        JSONObject obj = new JSONObject();
        try {
            JSONArray rpmArray = new JSONArray();
            for (int r = RPM_MIN; r <= RPM_MAX; r += RPM_STEP) {
                rpmArray.put(r);
            }
            obj.put("rpmBins", rpmArray);

            JSONArray mapArray = new JSONArray();
            for (float bin : MAP_BINS) {
                mapArray.put(bin);
            }
            obj.put("mapBins", mapArray);

            JSONObject petrolJson = new JSONObject();
            for (Map.Entry<String, MapTrimData> entry : petrolMap.entrySet()) {
                if (entry.getValue().getHitCount() >= minHits) {
                    JSONObject cell = new JSONObject();
                    cell.put("avg", entry.getValue().getAverage());
                    cell.put("hits", entry.getValue().getHitCount());
                    petrolJson.put(entry.getKey(), cell);
                }
            }
            obj.put("petrolMap", petrolJson);

            JSONObject lpgJson = new JSONObject();
            for (Map.Entry<String, MapTrimData> entry : lpgMap.entrySet()) {
                if (entry.getValue().getHitCount() >= minHits) {
                    JSONObject cell = new JSONObject();
                    cell.put("avg", entry.getValue().getAverage());
                    cell.put("hits", entry.getValue().getHitCount());
                    lpgJson.put(entry.getKey(), cell);
                }
            }
            obj.put("lpgMap", lpgJson);

            JSONObject deviationJson = new JSONObject();
            JSONObject tuneAssistJson = new JSONObject();
            
            for (String key : petrolMap.keySet()) {
                if (lpgMap.containsKey(key)) {
                    MapTrimData pData = petrolMap.get(key);
                    MapTrimData lData = lpgMap.get(key);
                    if (pData != null && lData != null && 
                        pData.getHitCount() >= minHits && lData.getHitCount() >= minHits) {
                        double dev = lData.getAverage() - pData.getAverage();
                        deviationJson.put(key, dev);
                        tuneAssistJson.put(key, Math.round(dev));
                    }
                }
            }
            obj.put("deviationMap", deviationJson);
            obj.put("tuneAssistMap", tuneAssistJson);

        } catch (JSONException e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\": \"" + e.getMessage() + "\"}");
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private Response handleMapSummary() {
        JSONObject obj = new JSONObject();
        try {
            int petrolCount = petrolMap.size();
            int lpgCount = lpgMap.size();
            
            double sumAbsDev = 0;
            int commonCells = 0;
            double maxDev = 0;
            String maxDevCell = "None";

            for (String key : petrolMap.keySet()) {
                if (lpgMap.containsKey(key)) {
                    MapTrimData pData = petrolMap.get(key);
                    MapTrimData lData = lpgMap.get(key);
                    if (pData != null && lData != null) {
                        double dev = lData.getAverage() - pData.getAverage();
                        double absDev = Math.abs(dev);
                        sumAbsDev += absDev;
                        commonCells++;
                        if (absDev > Math.abs(maxDev)) {
                            maxDev = dev;
                            maxDevCell = key;
                        }
                    }
                }
            }

            double avgAbsDev = commonCells == 0 ? 0 : sumAbsDev / commonCells;

            obj.put("petrolCellsCount", petrolCount);
            obj.put("lpgCellsCount", lpgCount);
            obj.put("overlappingCellsCount", commonCells);
            obj.put("averageAbsoluteDeviation", avgAbsDev);
            obj.put("maxDeviationValue", maxDev);
            obj.put("maxDeviationCell", maxDevCell);

            String recommendation;
            if (commonCells == 0) {
                recommendation = "Insufficient overlapping data. Please drive on both Petrol and LPG to collect comparison points.";
            } else {
                if (avgAbsDev <= 5.0) {
                    recommendation = String.format(Locale.US, "Excellent calibration! Average deviation is %.1f%%, which is within the target +/-5%% range.", avgAbsDev);
                } else if (maxDev > 10.0) {
                    recommendation = String.format(Locale.US, "LPG is running significantly LEAN at %s (Deviation: +%.1f%%). Increase gas multiplier factors in high-load/RPM zones.", maxDevCell, maxDev);
                } else if (maxDev < -10.0) {
                    recommendation = String.format(Locale.US, "LPG is running significantly RICH at %s (Deviation: %.1f%%). Decrease gas multiplier factors in high-load/RPM zones.", maxDevCell, maxDev);
                } else {
                    recommendation = String.format(Locale.US, "Moderate calibration. Average deviation is %.1f%%. Adjust multiplier map slightly to align trims.", avgAbsDev);
                }
            }
            obj.put("recommendation", recommendation);

        } catch (JSONException e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\": \"" + e.getMessage() + "\"}");
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private Response handleMapExport() {
        StringBuilder sb = new StringBuilder();
        
        int rpmCount = (RPM_MAX - RPM_MIN) / RPM_STEP + 1;
        int mapCount = MAP_BINS.length;
        
        // Header row
        sb.append("T.inj \\ RPM");
        for (int c = 0; c < rpmCount; c++) {
            int rpmValue = RPM_MIN + (c * RPM_STEP);
            sb.append(",").append(rpmValue);
        }
        sb.append("\n");
        
        // Rows
        for (int r = 0; r < mapCount; r++) {
            float mapValue = MAP_BINS[r];
            sb.append(String.format(Locale.US, "%.2f", mapValue));
            
            for (int c = 0; c < rpmCount; c++) {
                int rpmValue = RPM_MIN + (c * RPM_STEP);
                String key = rpmValue + "_" + String.format(Locale.US, "%.2f", mapValue);
                MapTrimData petrol = petrolMap.get(key);
                MapTrimData lpg = lpgMap.get(key);
                
                if (petrol != null && lpg != null) {
                    double correction = lpg.getAverage() - petrol.getAverage();
                    sb.append(",").append(Math.round(correction));
                } else {
                    sb.append(",");
                }
            }
            sb.append("\n");
        }
        
        Response response = newFixedLengthResponse(Response.Status.OK, "text/csv", sb.toString());
        response.addHeader("Content-Disposition", "attachment; filename=\"tune_assist_map.csv\"");
        return response;
    }

    private Response handleMapClear() {
        petrolMap.clear();
        lpgMap.clear();
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"cleared\"}");
    }

    private Response handleMapImport(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        try {
            session.parseBody(files);
            String body = files.get("postData");
            if (body == null || body.trim().isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\": \"Empty body\"}");
            }
            
            JSONObject root = new JSONObject(body);
            
            if (root.has("petrolMap")) {
                JSONObject petrolJson = root.getJSONObject("petrolMap");
                java.util.Iterator<String> keys = petrolJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject cell = petrolJson.getJSONObject(key);
                    double avg = cell.getDouble("avg");
                    int hits = cell.getInt("hits");
                    petrolMap.put(key, new MapTrimData(avg * hits, hits));
                }
            }
            
            if (root.has("lpgMap")) {
                JSONObject lpgJson = root.getJSONObject("lpgMap");
                java.util.Iterator<String> keys = lpgJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject cell = lpgJson.getJSONObject(key);
                    double avg = cell.getDouble("avg");
                    int hits = cell.getInt("hits");
                    lpgMap.put(key, new MapTrimData(avg * hits, hits));
                }
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"imported\"}");
            
        } catch (IOException | ResponseException | JSONException e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSE Stream Handler
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handle GET /api/stream — opens an SSE connection that pushes
     * real-time OBD2 data as it arrives from the vehicle.
     *
     * Response format (text/event-stream):
     *   data: {"ts":"...","elapsed":1.23,"fuel":"PETROL","sensors":{"RPM":2500,...}}
     *   data: {"ts":"...","elapsed":1.73,"fuel":"PETROL","sensors":{"RPM":2510,...}}
     *   ...
     *
     * Agent usage:
     *   curl -N http://192.168.0.x:8080/api/stream
     *   Python: requests.get(url, stream=True) → for line in r.iter_lines()
     *   JS: new EventSource("http://192.168.0.x:8080/api/stream")
     */
    private Response handleSseStream(IHTTPSession session) {
        try {
            final SseClient client = new SseClient();
            sseClients.add(client);

            // Send initial hello event so the client knows it's connected
            client.send("event: connected\ndata: {\"status\":\"streaming\",\"clients\":" + sseClients.size() + "}\n\n");

            // If we have latest data, send it immediately
            if (latestData != null) {
                String json = recordToSseJson(latestData);
                client.send("data: " + json + "\n\n");
            }

            // NanoHTTPD chunked response reads from the InputStream
            Response response = newChunkedResponse(Response.Status.OK, "text/event-stream", client.in);

            // Add cache-control + connection headers
            response.addHeader("Cache-Control", "no-cache");
            response.addHeader("Connection", "keep-alive");
            response.addHeader("X-Accel-Buffering", "no");  // Disable nginx buffering

            // Clean up when client disconnects — NanoHTTPD will close the input stream
            // when the HTTP connection is terminated. The SseClient.closed flag will
            // be set by the IOException in send() on the next write attempt.
            return response;

        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                "{\"error\":\"Failed to create SSE stream: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Clean up SSE clients on server stop.
     */
    @Override
    public void stop() {
        for (SseClient client : sseClients) {
            client.close();
        }
        sseClients.clear();
        super.stop();
    }

    private Response handleGetDtc() {
        JSONObject obj = new JSONObject();
        try {
            JSONArray storedArr = new JSONArray();
            JSONArray pendingArr = new JSONArray();
            if (dtcProvider != null) {
                java.util.List<DtcCode> stored = dtcProvider.getStoredDtcs();
                if (stored != null) {
                    for (DtcCode dtc : stored) {
                            JSONObject dtcObj = new JSONObject();
                            dtcObj.put("code", dtc.getCode());
                            dtcObj.put("description", dtc.getDescription());
                            dtcObj.put("severity", dtc.getSeverity() != null ? dtc.getSeverity().name() : "UNKNOWN");
                            storedArr.put(dtcObj);
                        }
                }
                java.util.List<DtcCode> pending = dtcProvider.getPendingDtcs();
                if (pending != null) {
                    for (DtcCode dtc : pending) {
                            JSONObject dtcObj = new JSONObject();
                            dtcObj.put("code", dtc.getCode());
                            dtcObj.put("description", dtc.getDescription());
                            dtcObj.put("severity", dtc.getSeverity() != null ? dtc.getSeverity().name() : "UNKNOWN");
                            pendingArr.put(dtcObj);
                        }
                }
            }
            obj.put("stored", storedArr);
            obj.put("pending", pendingArr);
            obj.put("timestamp", System.currentTimeMillis());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private Response handleDeleteDtc() {
        boolean success = false;
        if (dtcProvider != null) {
            success = dtcProvider.triggerClearDtcs();
        }
        JSONObject obj = new JSONObject();
        try {
            obj.put("success", success);
            obj.put("message", success ? "Clear DTC command triggered" : "DTC provider unavailable");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Aggregate /api/agent — everything an AI Agent needs in one call
    // ═══════════════════════════════════════════════════════════════

    /**
     * GET /api/agent — returns a complete snapshot for AI Agent integration:
     *   - Connection & session metadata
     *   - Latest sensor data (detailed, keyed by pidKey)
     *   - Fuel map summary
     *   - DTC codes with severity
     * This avoids the need for multiple round-trips.
     */
    private Response handleAgent() {
        JSONObject obj = new JSONObject();
        try {
            // ── Status & session ──
            JSONObject status = new JSONObject();
            status.put("logging", isLogging);
            status.put("adapterConnected", adapterConnected);
            status.put("transportMode", transportMode != null ? transportMode : "UNKNOWN");
            status.put("recordCount", recordCount);
            if (sessionStartMs > 0) {
                status.put("sessionDurationMs", System.currentTimeMillis() - sessionStartMs);
            }
            if (latestData != null) {
                status.put("fuelMode", latestData.getFuelMode());
                status.put("vin", latestData.getVin() != null ? latestData.getVin() : "");
                status.put("vehicleBrand", latestData.getVehicleBrand() != null ? latestData.getVehicleBrand() : "");
                status.put("timestamp", latestData.getTimestamp());
                status.put("elapsedS", latestData.getElapsedS());
            }
            obj.put("status", status);

            // ── Sensors (detailed array, keyed by pidKey) ──
            if (latestData != null) {
                JSONArray sensorsArr = new JSONArray();
                for (SensorSample s : latestData.getSamples()) {
                    JSONObject sensorObj = new JSONObject();
                    sensorObj.put("pidKey", s.getPidKey());
                    sensorObj.put("name", s.getName());
                    sensorObj.put("value", s.getValue());
                    sensorObj.put("unit", s.getUnit());
                    sensorObj.put("status", s.getStatus());
                    sensorsArr.put(sensorObj);
                }
                obj.put("sensors", sensorsArr);
            }

            // ── Fuel map summary ──
            JSONObject mapSummary = new JSONObject();
            int petrolCount = petrolMap.size();
            int lpgCount = lpgMap.size();
            double sumAbsDev = 0;
            int commonCells = 0;
            double maxDev = 0;
            String maxDevCell = "None";
            for (String key : petrolMap.keySet()) {
                if (lpgMap.containsKey(key)) {
                    MapTrimData pData = petrolMap.get(key);
                    MapTrimData lData = lpgMap.get(key);
                    if (pData != null && lData != null) {
                        double dev = lData.getAverage() - pData.getAverage();
                        sumAbsDev += Math.abs(dev);
                        commonCells++;
                        if (Math.abs(dev) > Math.abs(maxDev)) {
                            maxDev = dev;
                            maxDevCell = key;
                        }
                    }
                }
            }
            mapSummary.put("petrolCellsCount", petrolCount);
            mapSummary.put("lpgCellsCount", lpgCount);
            mapSummary.put("overlappingCellsCount", commonCells);
            mapSummary.put("averageAbsoluteDeviation", commonCells == 0 ? 0 : sumAbsDev / commonCells);
            mapSummary.put("maxDeviationValue", maxDev);
            mapSummary.put("maxDeviationCell", maxDevCell);
            obj.put("mapSummary", mapSummary);

            // ── DTC codes with severity ──
            if (dtcProvider != null) {
                JSONArray dtcArr = new JSONArray();
                java.util.List<DtcCode> stored = dtcProvider.getStoredDtcs();
                if (stored != null) {
                    for (DtcCode dtc : stored) {
                        JSONObject dtcObj = new JSONObject();
                        dtcObj.put("code", dtc.getCode());
                        dtcObj.put("description", dtc.getDescription());
                        dtcObj.put("severity", dtc.getSeverity() != null ? dtc.getSeverity().name() : "UNKNOWN");
                        dtcObj.put("type", "stored");
                        dtcArr.put(dtcObj);
                    }
                }
                java.util.List<DtcCode> pending = dtcProvider.getPendingDtcs();
                if (pending != null) {
                    for (DtcCode dtc : pending) {
                        JSONObject dtcObj = new JSONObject();
                        dtcObj.put("code", dtc.getCode());
                        dtcObj.put("description", dtc.getDescription());
                        dtcObj.put("severity", dtc.getSeverity() != null ? dtc.getSeverity().name() : "UNKNOWN");
                        dtcObj.put("type", "pending");
                        dtcArr.put(dtcObj);
                    }
                }
                obj.put("dtcs", dtcArr);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }
}
