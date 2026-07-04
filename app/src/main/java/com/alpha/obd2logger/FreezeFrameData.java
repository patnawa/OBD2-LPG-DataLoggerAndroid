package com.alpha.obd2logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import org.json.JSONObject;
import org.json.JSONException;

/**
 * Holds snapshot (freeze frame) data associated with a DTC code.
 * Mode 02 returns sensor values at the time the DTC was triggered.
 */
public final class FreezeFrameData {
    private final Map<String, Double> values;
    private final long timestamp;

    public FreezeFrameData(Map<String, Double> values, long timestamp) {
        this.values = values != null ? values : new HashMap<>();
        this.timestamp = timestamp;
    }

    public Map<String, Double> getValues() {
        return values;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Helper to get a human-readable formatted value with unit.
     */
    public String getFormattedValue(String pidHex) {
        Double value = values.get(pidHex);
        if (value == null) {
            return "N/A";
        }
        switch (pidHex.toUpperCase(Locale.US)) {
            case "0C": return String.format(Locale.US, "%.0f rpm", value);
            case "0D": return String.format(Locale.US, "%.0f km/h", value);
            case "05": return String.format(Locale.US, "%.0f°C", value);
            case "04": return String.format(Locale.US, "%.1f%%", value);
            case "06": return String.format(Locale.US, "%+.1f%%", value);
            case "07": return String.format(Locale.US, "%+.1f%%", value);
            case "0B": return String.format(Locale.US, "%.0f kPa", value);
            case "0F": return String.format(Locale.US, "%.0f°C", value);
            default:   return String.format(Locale.US, "%.2f", value);
        }
    }

    public JSONObject toJsonObject() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("timestamp", timestamp);
            JSONObject vals = new JSONObject();
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                vals.put(entry.getKey(), entry.getValue());
            }
            obj.put("values", vals);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return obj;
    }

    public static FreezeFrameData fromJsonObject(JSONObject obj) {
        if (obj == null) return null;
        Map<String, Double> map = new HashMap<>();
        long ts = obj.optLong("timestamp", System.currentTimeMillis());
        JSONObject vals = obj.optJSONObject("values");
        if (vals != null) {
            java.util.Iterator<String> keys = vals.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    map.put(key, vals.getDouble(key));
                } catch (JSONException ignored) {}
            }
        }
        return new FreezeFrameData(map, ts);
    }
}
