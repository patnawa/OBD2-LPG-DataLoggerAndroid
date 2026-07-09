package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages user-defined custom OBD2 PIDs stored in SharedPreferences.
 *
 * Each custom PID is a JSON object with:
 *   - name: display name
 *   - service: "01", "02", "09", etc.
 *   - pidHex: "0C", "5E", etc.
 *   - unit: "°C", "%", "kPa", etc.
 *   - formula: "A-40", "(A*256+B)/4", etc.
 *   - minVal: minimum valid value
 *   - maxVal: maximum valid value
 *   - dataBytes: number of data bytes in response (1-8)
 *   - dashboard: show on dashboard (true/false)
 *
 * Stored as a JSON array string in SharedPreferences under key "custom_pids".
 */
public final class CustomPidManager {
    private static final String TAG = "CustomPidManager";
    private static final String PREFS_KEY = "custom_pids";

    private CustomPidManager() {}

    /**
     * Parse all stored custom PIDs into PIDDefinition objects.
     * Returns empty list if none stored or parse error.
     */
    public static List<PIDDefinition> load(Context context) {
        List<PIDDefinition> list = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE);
        String json = prefs.getString(PREFS_KEY, null);
        if (json == null || json.trim().isEmpty()) return list;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String name = obj.optString("name", "Custom PID " + i);
                String service = obj.optString("service", "01");
                String pidHex = obj.optString("pidHex", "");
                String unit = obj.optString("unit", "");
                String formula = obj.optString("formula", "A");
                double minVal = obj.optDouble("minVal", 0);
                double maxVal = obj.optDouble("maxVal", 65535);
                int dataBytes = obj.optInt("dataBytes", 1);
                boolean dashboard = obj.optBoolean("dashboard", false);

                if (pidHex.isEmpty()) {
                    Log.w(TAG, "Skipping custom PID with empty pidHex: " + name);
                    continue;
                }

                PIDDefinition pid = new PIDDefinition(name, service, pidHex, unit, formula,
                    minVal, maxVal, false, dataBytes, dashboard);
                list.add(pid);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse custom PIDs JSON", e);
        }
        return list;
    }

    /**
     * Convert a list of PIDDefinition back to JSON and persist.
     */
    public static void save(Context context, List<PIDDefinition> pids) {
        SharedPreferences prefs = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE);
        if (pids == null || pids.isEmpty()) {
            prefs.edit().remove(PREFS_KEY).apply();
            return;
        }

        JSONArray arr = new JSONArray();
        for (PIDDefinition pid : pids) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", pid.getName());
                obj.put("service", pid.getService());
                obj.put("pidHex", pid.getPidHex());
                obj.put("unit", pid.getUnit());
                obj.put("formula", pid.getFormula());
                obj.put("minVal", pid.getMinVal());
                obj.put("maxVal", pid.getMaxVal());
                obj.put("dataBytes", pid.getDataBytes());
                obj.put("dashboard", pid.isDashboard());
                arr.put(obj);
            } catch (Exception e) {
                Log.e(TAG, "Failed to serialize PID: " + pid.getName(), e);
            }
        }
        prefs.edit().putString(PREFS_KEY, arr.toString()).apply();
    }

    /**
     * Add a single custom PID definition.
     */
    public static void add(Context context, PIDDefinition pid) {
        List<PIDDefinition> existing = load(context);
        // Avoid duplicates by key
        String key = pid.key();
        for (int i = 0; i < existing.size(); i++) {
            if (existing.get(i).key().equals(key)) {
                existing.set(i, pid); // replace
                save(context, existing);
                return;
            }
        }
        existing.add(pid);
        save(context, existing);
    }

    /**
     * Remove a custom PID by its key (e.g. "01_0C").
     */
    public static void remove(Context context, String pidKey) {
        List<PIDDefinition> existing = load(context);
        for (int i = 0; i < existing.size(); i++) {
            if (existing.get(i).key().equals(pidKey)) {
                existing.remove(i);
                save(context, existing);
                return;
            }
        }
    }

    /**
     * Clear all custom PIDs.
     */
    public static void clear(Context context) {
        context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE)
            .edit().remove(PREFS_KEY).apply();
    }

    /**
     * Build a map of PID name → example value for testing/validation.
     * Useful for preview: user enters a formula, sees what raw hex bytes produce.
     */
    public static Double testFormula(String formula, String rawHex) {
        if (rawHex == null || rawHex.isEmpty()) return null;
        try {
            // Parse raw hex into bytes
            int len = rawHex.length();
            int a = (len >= 2) ? Integer.parseInt(rawHex.substring(0, 2), 16) : 0;
            int b = (len >= 4) ? Integer.parseInt(rawHex.substring(2, 4), 16) : 0;
            int c = (len >= 6) ? Integer.parseInt(rawHex.substring(4, 6), 16) : 0;
            int d = (len >= 8) ? Integer.parseInt(rawHex.substring(6, 8), 16) : 0;

            // Use PIDParser's formula evaluator
            PIDDefinition tempPid = new PIDDefinition("test", "01", "FF", "", formula, -999999, 999999, false, 4);
            return PIDParser.parse(tempPid, rawHex);
        } catch (Exception e) {
            return null;
        }
    }
}