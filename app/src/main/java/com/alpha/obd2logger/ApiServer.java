package com.alpha.obd2logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

public class ApiServer extends NanoHTTPD {

    private volatile DataRecord latestData;
    private volatile boolean isLogging;
    
    public interface DtcProvider {
        java.util.List<DtcCode> getStoredDtcs();
        java.util.List<DtcCode> getPendingDtcs();
        boolean triggerClearDtcs();
    }
    
    private volatile DtcProvider dtcProvider;

    public void setDtcProvider(DtcProvider provider) {
        this.dtcProvider = provider;
    }

    // Fuel Map Constants matching FuelMapView
    private static final int RPM_MIN = 500;
    private static final int RPM_MAX = 6500;
    private static final int RPM_STEP = 500;
    
    private static final float[] T_INJ_BINS = {
        2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 6.0f, 8.0f, 10.0f, 12.0f, 14.0f, 16.0f, 18.0f
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
            updateLiveMap(data);
        }
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

            double tinj = mapLoadToTinj(loadAxis);
            int tinjIdx = findClosestBinIndex(tinj, T_INJ_BINS);
            float tinjBinValue = T_INJ_BINS[tinjIdx];

            String key = rpmCell + "_" + String.format(Locale.US, "%.2f", tinjBinValue);
            
            boolean isLpg = "lpg/cng".equalsIgnoreCase(record.getFuelMode());
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

    private static float mapLoadToTinj(double loadOrMap) {
        if (loadOrMap <= 20) return 2.0f;
        if (loadOrMap >= 100) {
            double ratio = (loadOrMap - 100) / 60.0;
            return (float) Math.min(18.0, 12.0 + ratio * 6.0);
        }
        double ratio = (loadOrMap - 20) / 80.0;
        return (float) (2.0 + ratio * 10.0);
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
            obj.put("timestamp", latestData.getTimestamp());
            obj.put("elapsedS", latestData.getElapsedS());
            obj.put("fuelMode", latestData.getFuelMode());
            
            JSONObject sensors = new JSONObject();
            for (SensorSample s : latestData.getSamples()) {
                sensors.put(s.getName(), s.getValue());
            }
            obj.put("sensors", sensors);
            
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

            JSONArray tinjArray = new JSONArray();
            for (float bin : T_INJ_BINS) {
                tinjArray.put(bin);
            }
            obj.put("tinjBins", tinjArray);

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
        int tinjCount = T_INJ_BINS.length;
        
        // Header row
        sb.append("T.inj \\ RPM");
        for (int c = 0; c < rpmCount; c++) {
            int rpmValue = RPM_MIN + (c * RPM_STEP);
            sb.append(",").append(rpmValue);
        }
        sb.append("\n");
        
        // Rows
        for (int r = 0; r < tinjCount; r++) {
            float tinjValue = T_INJ_BINS[r];
            sb.append(String.format(Locale.US, "%.2f", tinjValue));
            
            for (int c = 0; c < rpmCount; c++) {
                int rpmValue = RPM_MIN + (c * RPM_STEP);
                String key = rpmValue + "_" + String.format(Locale.US, "%.2f", tinjValue);
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
                        storedArr.put(dtcObj);
                    }
                }
                java.util.List<DtcCode> pending = dtcProvider.getPendingDtcs();
                if (pending != null) {
                    for (DtcCode dtc : pending) {
                        JSONObject dtcObj = new JSONObject();
                        dtcObj.put("code", dtc.getCode());
                        dtcObj.put("description", dtc.getDescription());
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
}
