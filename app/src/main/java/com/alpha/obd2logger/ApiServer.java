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

    /**
     * The canonical fuel-map store. Set by {@link LoggerService} when the
     * server starts. All map endpoints read from this — there is no longer
     * a duplicate petrolMap/lpgMap inside ApiServer.
     */
    private volatile LiveMapStore liveMapStore;

    public void setLiveMapStore(LiveMapStore store) {
        this.liveMapStore = store;
    }

    public interface DtcProvider {
        java.util.List<DtcCode> getStoredDtcs();
        java.util.List<DtcCode> getPendingDtcs();
        boolean triggerClearDtcs();
    }
    
    private volatile DtcProvider dtcProvider;

    public void setDtcProvider(DtcProvider provider) {
        this.dtcProvider = provider;
    }

    // ── Snapshot cache for /api/agent (avoids recomputing on every request) ──
    private volatile LiveMapStore.MapSnapshot cachedSnapshot = null;
    private volatile long snapshotRefreshedMs = 0;
    private static final long SNAPSHOT_TTL_MS = 500; // refresh at most every 500ms

    private LiveMapStore.MapSnapshot getFreshSnapshot() {
        LiveMapStore store = liveMapStore;
        if (store == null) return null;
        long now = System.currentTimeMillis();
        if (cachedSnapshot == null || (now - snapshotRefreshedMs) > SNAPSHOT_TTL_MS) {
            cachedSnapshot = store.snapshot();
            snapshotRefreshedMs = now;
        }
        return cachedSnapshot;
    }

    /**
     * SSE: push map delta + summary events to all connected clients.
     * Called from setLatestData() after each record.
     *
     * - map_update: pushed every record (lightweight — just last cell + hit count)
     * - map_summary: pushed every 5 records (aggregated stats)
     */
    private void broadcastMapSse(int recordCount) {
        if (sseClients.isEmpty()) return;

        LiveMapStore store = liveMapStore;
        if (store == null) return;

        // map_update — lightweight per-record push
        String lastCell = store.getLastCellKey();
        if (lastCell != null && !lastCell.isEmpty()) {
            try {
                JSONObject updateObj = new JSONObject();
                updateObj.put("cell", lastCell);
                updateObj.put("recordCount", recordCount);
                updateObj.put("timestamp", System.currentTimeMillis());
                // Include deviation if available
                LiveMapStore.MapSnapshot snap = store.snapshot();
                LiveMapStore.TrimData petrol = snap.getPetrolData().get(lastCell);
                LiveMapStore.TrimData lpg = snap.getLpgData().get(lastCell);
                if (petrol != null) {
                    updateObj.put("petrolAvg", Math.round(petrol.getAverage() * 10) / 10.0);
                    updateObj.put("petrolHits", petrol.getHitCount());
                }
                if (lpg != null) {
                    updateObj.put("lpgAvg", Math.round(lpg.getAverage() * 10) / 10.0);
                    updateObj.put("lpgHits", lpg.getHitCount());
                }
                if (petrol != null && lpg != null) {
                    double dev = lpg.getAverage() - petrol.getAverage();
                    updateObj.put("deviation", Math.round(dev * 10) / 10.0);
                }
                String updateEvent = "event: map_update\ndata: " + updateObj.toString() + "\n\n";
                for (SseClient client : sseClients) {
                    client.send(updateEvent);
                }
            } catch (JSONException ignored) {}
        }

        // map_summary — every 5 records
        if (recordCount % 5 == 0) {
            try {
                LiveMapStore.MapSnapshot snap = store.snapshot();
                JSONObject summaryObj = new JSONObject();
                summaryObj.put("petrolCells", snap.getPetrolData().size());
                summaryObj.put("lpgCells", snap.getLpgData().size());
                summaryObj.put("overlappingCells", snap.getOverlappingCellCount());
                summaryObj.put("averageDeviation", Math.round(snap.getAverageAbsoluteDeviation() * 10) / 10.0);
                summaryObj.put("maxDeviation", Math.round(snap.getMaxDeviation() * 10) / 10.0);
                summaryObj.put("maxDeviationCell", snap.getMaxDeviationCell() != null ? snap.getMaxDeviationCell() : "");
                summaryObj.put("timestamp", System.currentTimeMillis());
                String summaryEvent = "event: map_summary\ndata: " + summaryObj.toString() + "\n\n";
                for (SseClient client : sseClients) {
                    client.send(summaryEvent);
                }
            } catch (JSONException ignored) {}
        }

        sseClients.removeIf(c -> c.closed);
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

    // ═══════════════════════════════════════════════════════════════
    //  Fuel Map — delegated to LiveMapStore (single source of truth)
    // ═══════════════════════════════════════════════════════════════
    //
    // Previously ApiServer kept its own petrolMap/lpgMap and binned samples
    // with Math.round (different from FuelMapView's floor-based binning).
    // This caused the AI agent to see data in a different cell than the UI.
    // Now all map data lives in LiveMapStore, and ApiServer just reads
    // snapshots from it.

    public ApiServer(int port) {
        super(port);
    }

    /**
     * Returns the live store's petrol data (for backward compat with
     * MainActivity session copy logic during migration).
     */
    public Map<String, LiveMapStore.TrimData> getPetrolMap() {
        LiveMapStore store = liveMapStore;
        return store != null ? store.getPetrolData() : java.util.Collections.emptyMap();
    }

    public Map<String, LiveMapStore.TrimData> getLpgMap() {
        LiveMapStore store = liveMapStore;
        return store != null ? store.getLpgData() : java.util.Collections.emptyMap();
    }

    /**
         * Publish latest live data for /api/data and SSE.
         *
         * <p>Does NOT write the fuel map — {@link LoggerService} (or in-process
         * MainActivity) is the single writer to {@link LiveMapStore}. Writing here
         * used to double-count hits whenever UI callbacks also called pushSample.
         */
        public void setLatestData(DataRecord data, boolean isLogging) {
            this.latestData = data;
            this.isLogging = isLogging;
            if (data != null) {
                if (recordCount == 0 || sessionStartMs == 0) {
                    sessionStartMs = System.currentTimeMillis();
                }
                recordCount++;
                broadcastSse(data);
                broadcastMapSse(recordCount);
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
        LiveMapStore store = liveMapStore;
        if (store != null) store.clear();
    }

    /**
     * Push a DataRecord into the LiveMapStore — the single write path.
     * Replaces the old updateLiveMap() which had different binning + no debounce.
     */
    /**
         * Push a DataRecord into the LiveMapStore.
         * Primary write path for background logging when API server is enabled
         * (LoggerService only calls setLatestData when API is on). In-process UI
         * also writes via MainActivity when the service store is shared.
         *
         * Always updates the active cell cursor so SSE + map highlight stay live
         * even for gated/debounced samples.
         */
        private void pushToLiveMapStore(DataRecord record) {
            LiveMapStore store = liveMapStore;
            if (store == null || record == null) return;
            MapSampleMeta meta = MapSampleMeta.from(record);
            FuelMode mode = FuelMode.fromString(record.getFuelMode());
            store.pushFromMeta(meta, mode);
        }

    private Double valueByKey(DataRecord record, String key) {
        for (SensorSample sample : record.getSamples()) {
            if (sample.getPidKey().equals(key)) return sample.getValue();
        }
        return null;
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

        LiveMapStore store = liveMapStore;
        Map<String, LiveMapStore.TrimData> petrolMap = store != null ? store.getPetrolData() : java.util.Collections.emptyMap();
        Map<String, LiveMapStore.TrimData> lpgMap = store != null ? store.getLpgData() : java.util.Collections.emptyMap();

        JSONObject obj = new JSONObject();
        try {
            JSONArray rpmArray = new JSONArray();
            for (int r = MapBinning.RPM_MIN; r <= MapBinning.RPM_MAX; r += MapBinning.RPM_STEP) {
                rpmArray.put(r);
            }
            obj.put("rpmBins", rpmArray);

            JSONArray mapArray = new JSONArray();
            for (float bin : MapBinning.MAP_BINS) {
                mapArray.put(bin);
            }
            obj.put("mapBins", mapArray);

            JSONObject petrolJson = new JSONObject();
            for (Map.Entry<String, LiveMapStore.TrimData> entry : petrolMap.entrySet()) {
                if (entry.getValue().getHitCount() >= minHits) {
                    JSONObject cell = new JSONObject();
                    cell.put("avg", entry.getValue().getAverage());
                    cell.put("hits", entry.getValue().getHitCount());
                    petrolJson.put(entry.getKey(), cell);
                }
            }
            obj.put("petrolMap", petrolJson);

            JSONObject lpgJson = new JSONObject();
            for (Map.Entry<String, LiveMapStore.TrimData> entry : lpgMap.entrySet()) {
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
                    LiveMapStore.TrimData pData = petrolMap.get(key);
                    LiveMapStore.TrimData lData = lpgMap.get(key);
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
                        if (store != null) {
                            obj.put("activeCellKey", store.getLastCellKey() != null ? store.getLastCellKey() : "");
                            obj.put("activeRpmCell", store.getActiveRpmCell());
                            obj.put("activeMapBin", store.getActiveMapBin());
                            obj.put("axisSource", store.getAxisSource());
                            obj.put("totalAcceptedSamples", store.getTotalRecords());
                        }

                    } catch (JSONException e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\": \"" + e.getMessage() + "\"}");
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString());
    }

    private Response handleMapSummary() {
        LiveMapStore store = liveMapStore;
        LiveMapStore.MapSnapshot snap = store != null ? store.snapshot() : null;

        JSONObject obj = new JSONObject();
        try {
            int petrolCount = snap != null ? snap.getPetrolData().size() : 0;
            int lpgCount = snap != null ? snap.getLpgData().size() : 0;
            int commonCells = snap != null ? snap.getOverlappingCellCount() : 0;
            double avgAbsDev = snap != null ? snap.getAverageAbsoluteDeviation() : 0;
            double maxDev = snap != null ? snap.getMaxDeviation() : 0;
            String maxDevCell = snap != null ? snap.getMaxDeviationCell() : null;
            if (maxDevCell == null) maxDevCell = "None";

            obj.put("petrolCellsCount", petrolCount);
            obj.put("lpgCellsCount", lpgCount);
            obj.put("overlappingCellsCount", commonCells);
            obj.put("averageAbsoluteDeviation", avgAbsDev);
            obj.put("maxDeviationValue", maxDev);
                        obj.put("maxDeviationCell", maxDevCell);
                        if (snap != null) {
                            obj.put("activeCellKey", snap.getLastCellKey() != null ? snap.getLastCellKey() : "");
                            obj.put("activeRpmCell", snap.getActiveRpmCell());
                            obj.put("activeMapBin", snap.getActiveMapBin());
                            obj.put("axisSource", snap.getAxisSource());
                            obj.put("totalAcceptedSamples", snap.getTotalRecords());
                        }

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
        LiveMapStore store = liveMapStore;
        Map<String, LiveMapStore.TrimData> petrolMap = store != null ? store.getPetrolData() : java.util.Collections.emptyMap();
        Map<String, LiveMapStore.TrimData> lpgMap = store != null ? store.getLpgData() : java.util.Collections.emptyMap();

        StringBuilder sb = new StringBuilder();

        int rpmCount = MapBinning.getRpmCount();
        int mapCount = MapBinning.MAP_BINS.length;

        // Header row
        sb.append("MAP kPa \\ RPM");
        for (int c = 0; c < rpmCount; c++) {
            sb.append(",").append(MapBinning.rpmForColumn(c));
        }
        sb.append("\n");

        // Rows
        for (int r = 0; r < mapCount; r++) {
            float mapValue = MapBinning.mapForRow(r);
            sb.append(String.format(Locale.US, "%.2f", mapValue));

            for (int c = 0; c < rpmCount; c++) {
                int rpmValue = MapBinning.rpmForColumn(c);
                String key = MapBinning.cellKey(rpmValue, mapValue);
                LiveMapStore.TrimData petrol = petrolMap.get(key);
                LiveMapStore.TrimData lpg = lpgMap.get(key);

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
        LiveMapStore store = liveMapStore;
        if (store != null) store.clear();
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"cleared\"}");
    }

    /**
         * POST /api/map/import
         * Body JSON (same shape as GET /api/map):
         * {
         *   "petrolMap": { "2000_40.00": { "avg": -2.5, "hits": 10 }, ... },
         *   "lpgMap":    { "2000_40.00": { "avg":  1.5, "hits": 12 }, ... },
         *   "replace": true|false   // default true for petrol/lpg sides present
         * }
         *
         * Keys are normalized through MapBinning so legacy ROUND keys (e.g. 750_40)
         * land on the same cells as the live FLOOR grid. Enables AI/baseline compare:
         * import a prior petrol session, then live-drive LPG and read /api/map deviation.
         */
        private Response handleMapImport(IHTTPSession session) {
            LiveMapStore store = liveMapStore;
            if (store == null) {
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "application/json",
                    "{\"error\": \"LiveMapStore not initialized\"}");
            }

            Map<String, String> files = new HashMap<>();
            try {
                session.parseBody(files);
                String body = files.get("postData");
                if (body == null || body.trim().isEmpty()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                            "{\"error\": \"Empty body\"}");
                }

                JSONObject root = new JSONObject(body);
                boolean replace = root.optBoolean("replace", true);
                int petrolImported = 0;
                int lpgImported = 0;
                int skipped = 0;

                if (root.has("petrolMap")) {
                    if (replace) store.importPetrol(java.util.Collections.emptyMap(), true);
                    JSONObject petrolJson = root.getJSONObject("petrolMap");
                    java.util.Iterator<String> keys = petrolJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject cell = petrolJson.optJSONObject(key);
                        if (cell == null) { skipped++; continue; }
                        double avg = cell.optDouble("avg", Double.NaN);
                        int hits = cell.optInt("hits", 0);
                        if (Double.isNaN(avg) || hits < 0) { skipped++; continue; }
                        store.putImportedCell(false, key, avg, hits);
                        petrolImported++;
                    }
                }

                if (root.has("lpgMap")) {
                    if (replace) store.importLpg(java.util.Collections.emptyMap(), true);
                    JSONObject lpgJson = root.getJSONObject("lpgMap");
                    java.util.Iterator<String> keys = lpgJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject cell = lpgJson.optJSONObject(key);
                        if (cell == null) { skipped++; continue; }
                        double avg = cell.optDouble("avg", Double.NaN);
                        int hits = cell.optInt("hits", 0);
                        if (Double.isNaN(avg) || hits < 0) { skipped++; continue; }
                        store.putImportedCell(true, key, avg, hits);
                        lpgImported++;
                    }
                }

                // Also accept compact AI export format: array of {rpm, map, avg, hits, fuel}
                if (root.has("cells")) {
                    org.json.JSONArray arr = root.getJSONArray("cells");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject cell = arr.optJSONObject(i);
                        if (cell == null) { skipped++; continue; }
                        double rpm = cell.optDouble("rpm", Double.NaN);
                        double map = cell.optDouble("map", Double.NaN);
                        double avg = cell.optDouble("avg", Double.NaN);
                        int hits = cell.optInt("hits", 1);
                        String fuel = cell.optString("fuel", "petrol");
                        if (Double.isNaN(rpm) || Double.isNaN(map) || Double.isNaN(avg)) {
                            skipped++;
                            continue;
                        }
                        String key = MapBinning.cellKey(rpm, map);
                        boolean gaseous = FuelMode.fromString(fuel).isGaseous();
                        store.putImportedCell(gaseous, key, avg, hits);
                        if (gaseous) lpgImported++; else petrolImported++;
                    }
                }

                JSONObject resp = new JSONObject();
                resp.put("status", "imported");
                resp.put("petrolCells", petrolImported);
                resp.put("lpgCells", lpgImported);
                resp.put("skipped", skipped);
                resp.put("overlappingCells", store.snapshot().getOverlappingCellCount());
                return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString());

            } catch (IOException | ResponseException | JSONException e) {
                e.printStackTrace();
                String msg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "import_failed";
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                        "{\"error\": \"" + msg + "\"}");
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
     *   - Fuel map summary (from cached LiveMapStore snapshot)
     *   - Map zone analysis (idle/cruise/acceleration/fullLoad)
     *   - Hotspot cells (|deviation| > 5%, sorted by severity)
     *   - DTC codes with severity
     *
     * Uses a cached snapshot (refreshed every 500ms) so frequent polling
     * by AI agents doesn't recompute the full map analysis each time.
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

            // ── Fuel map summary (from cached snapshot) ──
            LiveMapStore.MapSnapshot snap = getFreshSnapshot();
            JSONObject mapSummary = new JSONObject();
            if (snap != null) {
                mapSummary.put("petrolCellsCount", snap.getPetrolData().size());
                mapSummary.put("lpgCellsCount", snap.getLpgData().size());
                mapSummary.put("overlappingCellsCount", snap.getOverlappingCellCount());
                mapSummary.put("averageAbsoluteDeviation", snap.getAverageAbsoluteDeviation());
                double maxDev = snap.getMaxDeviation();
                mapSummary.put("maxDeviationValue", maxDev);
                String maxCell = snap.getMaxDeviationCell();
                mapSummary.put("maxDeviationCell", maxCell != null ? maxCell : "None");
                mapSummary.put("lastUpdateMs", snap.getSnapshotMs());
                mapSummary.put("lastCellKey", snap.getLastCellKey());
            } else {
                mapSummary.put("petrolCellsCount", 0);
                mapSummary.put("lpgCellsCount", 0);
                mapSummary.put("overlappingCellsCount", 0);
                mapSummary.put("averageAbsoluteDeviation", 0);
                mapSummary.put("maxDeviationValue", 0);
                mapSummary.put("maxDeviationCell", "None");
            }
            obj.put("mapSummary", mapSummary);

            // ── Zone analysis (idle / cruise / acceleration / fullLoad) ──
            if (snap != null) {
                obj.put("zones", buildZoneAnalysis(snap));
                obj.put("hotspots", buildHotspots(snap));
            }

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

    // ── Zone analysis + hotspot helpers for /api/agent ──────────────────

    /**
     * Builds a zone-by-zone breakdown of the fuel map for the AI agent.
     * Zones: idle (500-1000), cruise (1500-3000), acceleration (2500-4500),
     * fullLoad (4000-6500). Each zone reports cell count, avg deviation,
     * and a confidence level based on average hit count.
     */
    private JSONObject buildZoneAnalysis(LiveMapStore.MapSnapshot snap) {
        JSONObject zones = new JSONObject();
        try {
            int[][] zoneRanges = {
                {500, 1000},    // idle
                {1500, 3000},   // cruise
                {2500, 4500},   // acceleration
                {4000, 6500}    // fullLoad
            };
            String[] zoneNames = {"idle", "cruise", "acceleration", "fullLoad"};

            for (int z = 0; z < zoneNames.length; z++) {
                int rpmLo = zoneRanges[z][0];
                int rpmHi = zoneRanges[z][1];
                double sumAbsDev = 0;
                int cells = 0;
                int totalHits = 0;

                for (String key : snap.getPetrolData().keySet()) {
                    LiveMapStore.TrimData lpg = snap.getLpgData().get(key);
                    LiveMapStore.TrimData petrol = snap.getPetrolData().get(key);
                    if (petrol == null || lpg == null) continue;

                    // Parse RPM from key: "1500_40.00"
                    int sep = key.indexOf('_');
                    if (sep < 0) continue;
                    int rpm;
                    try {
                        rpm = Integer.parseInt(key.substring(0, sep));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (rpm < rpmLo || rpm > rpmHi) continue;

                    double dev = lpg.getAverage() - petrol.getAverage();
                    sumAbsDev += Math.abs(dev);
                    totalHits += Math.min(petrol.getHitCount(), lpg.getHitCount());
                    cells++;
                }

                JSONObject zoneObj = new JSONObject();
                zoneObj.put("rpmRange", new JSONArray().put(rpmLo).put(rpmHi));
                zoneObj.put("cells", cells);
                zoneObj.put("avgDeviation", cells == 0 ? 0 : Math.round((sumAbsDev / cells) * 10) / 10.0);
                int avgHits = cells == 0 ? 0 : totalHits / cells;
                zoneObj.put("avgHits", avgHits);
                zoneObj.put("confidence", avgHits >= 20 ? "HIGH" : (avgHits >= 10 ? "MEDIUM" : (avgHits > 0 ? "LOW" : "NONE")));
                zones.put(zoneNames[z], zoneObj);
            }
        } catch (JSONException ignored) {}
        return zones;
    }

    /**
     * Builds a list of hotspot cells — overlapping cells where
     * |deviation| > 5%, sorted by absolute deviation (descending).
     * Each hotspot includes RPM, load, deviation, hits, verdict, and
     * a suggested correction percentage for the AI to relay to the user.
     */
    private JSONArray buildHotspots(LiveMapStore.MapSnapshot snap) {
        JSONArray hotspots = new JSONArray();

        // Collect, then sort by |deviation| descending
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        for (String key : snap.getPetrolData().keySet()) {
            LiveMapStore.TrimData petrol = snap.getPetrolData().get(key);
            LiveMapStore.TrimData lpg = snap.getLpgData().get(key);
            if (petrol == null || lpg == null) continue;

            double dev = lpg.getAverage() - petrol.getAverage();
            if (Math.abs(dev) <= 5.0) continue; // only report significant deviations

            try {
                JSONObject h = new JSONObject();
                h.put("cell", key);
                // Parse RPM and MAP from key
                int sep = key.indexOf('_');
                if (sep > 0) {
                    try {
                        h.put("rpm", Integer.parseInt(key.substring(0, sep)));
                    } catch (NumberFormatException ignored) {}
                    try {
                        h.put("load", Double.parseDouble(key.substring(sep + 1)));
                    } catch (NumberFormatException ignored) {}
                }
                h.put("deviation", Math.round(dev * 10) / 10.0);
                int minHits = Math.min(petrol.getHitCount(), lpg.getHitCount());
                h.put("hits", minHits);
                h.put("verdict", dev > 0 ? "LEAN" : "RICH");
                h.put("suggestion", (dev > 0 ? "+" : "") + Math.round(dev) + "% gas multiplier");
                h.put("confidence", minHits >= 20 ? "HIGH" : (minHits >= 10 ? "MEDIUM" : "LOW"));
                list.add(h);
            } catch (JSONException ignored) {}
        }

        // Sort by |deviation| descending
        list.sort((a, b) -> {
            try {
                double da = Math.abs(a.getDouble("deviation"));
                double db = Math.abs(b.getDouble("deviation"));
                return Double.compare(db, da);
            } catch (JSONException e) {
                return 0;
            }
        });

        // Limit to top 20 hotspots to keep payload reasonable
        int limit = Math.min(list.size(), 20);
        for (int i = 0; i < limit; i++) {
            hotspots.put(list.get(i));
        }
        return hotspots;
    }
}
