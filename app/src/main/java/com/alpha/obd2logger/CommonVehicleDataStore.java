package com.alpha.obd2logger;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Application-owned common vehicle data, captured from standard read-only OBD
 * responses. The record is a versioned snapshot that other app features can use.
 */
public final class CommonVehicleDataStore {
    private static final String PREFS = "OBD2Prefs";
    private static final String KEY_PREFIX = "common_vehicle_data_v1_";
    private static final int SCHEMA_VERSION = 1;

    private CommonVehicleDataStore() {
    }

    public static VehicleInformationReader.Snapshot save(
            Context context, VehicleInformationReader.Snapshot snapshot) {
        return save(context, snapshot == null ? null : snapshot.getVin(), snapshot);
    }

    /** Save using a live VIN when present, otherwise a structurally valid manual VIN. */
    public static VehicleInformationReader.Snapshot save(Context context, String fallbackVin,
                                                          VehicleInformationReader.Snapshot snapshot) {
        if (context == null || snapshot == null) return null;
        VehicleInformationReader.Snapshot effective = withFallbackVin(snapshot, fallbackVin);
        if (effective == null) return null;
        String vin = normalizeVin(effective.getVin());
        JSONObject json = snapshotToJson(effective);
        if (vin == null || json == null) return null;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PREFIX + vin, json.toString())
                .apply();
        return effective;
    }

    public static VehicleInformationReader.Snapshot get(Context context, String vin) {
        String normalizedVin = normalizeVin(vin);
        if (context == null || normalizedVin == null) return null;
        String raw = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + normalizedVin, null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            VehicleInformationReader.Snapshot snapshot = snapshotFromJson(new JSONObject(raw));
            return snapshot != null && normalizedVin.equals(snapshot.getVin()) ? snapshot : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Return a stable session-export object, or null when no live snapshot was captured. */
    public static JSONObject getJson(Context context, String vin) {
        return snapshotToJson(get(context, vin));
    }

    static JSONObject snapshotToJson(VehicleInformationReader.Snapshot snapshot) {
        if (snapshot == null || normalizeVin(snapshot.getVin()) == null) return null;
        try {
            JSONObject root = new JSONObject();
            root.put("schema_version", SCHEMA_VERSION);
            root.put("source", "live_obd_standard");
            root.put("captured_at_epoch_ms", snapshot.getCapturedAtEpochMs());
            root.put("vin", snapshot.getVin());
            root.put("brand", snapshot.getBrandLabel());
            root.put("model_year", snapshot.getModelYear());

            JSONArray infoTypes = new JSONArray();
            for (Integer infoType : snapshot.getSupportedInfoTypes()) infoTypes.put(infoType);
            root.put("mode09_info_types", infoTypes);

            JSONArray calibrationIds = new JSONArray();
            for (Mode09Reader.CalIdEntry entry : snapshot.getCalIds()) {
                JSONObject value = new JSONObject();
                value.put("ecu_index", entry.getEcuIndex());
                value.put("value", entry.getCalId());
                calibrationIds.put(value);
            }
            root.put("calibration_ids", calibrationIds);

            JSONArray cvns = new JSONArray();
            for (Mode09Reader.CvnEntry entry : snapshot.getCvns()) {
                JSONObject value = new JSONObject();
                value.put("ecu_index", entry.getEcuIndex());
                value.put("value", entry.getCvn());
                cvns.put(value);
            }
            root.put("cvns", cvns);
            return root;
        } catch (Exception ignored) {
            return null;
        }
    }

    static VehicleInformationReader.Snapshot snapshotFromJson(JSONObject root) {
        if (root == null || root.optInt("schema_version", -1) != SCHEMA_VERSION) return null;
        String vin = normalizeVin(root.optString("vin", null));
        if (vin == null) return null;

        List<Integer> infoTypes = new ArrayList<>();
        JSONArray storedInfoTypes = root.optJSONArray("mode09_info_types");
        if (storedInfoTypes != null) {
            for (int i = 0; i < storedInfoTypes.length(); i++) {
                int value = storedInfoTypes.optInt(i, -1);
                if (value >= 1 && value <= 0xFF && !infoTypes.contains(value)) infoTypes.add(value);
            }
        }

        List<Mode09Reader.CalIdEntry> calIds = new ArrayList<>();
        JSONArray storedCalIds = root.optJSONArray("calibration_ids");
        if (storedCalIds != null) {
            for (int i = 0; i < storedCalIds.length(); i++) {
                JSONObject value = storedCalIds.optJSONObject(i);
                if (value == null) continue;
                String calId = value.optString("value", "").trim();
                if (!calId.isEmpty()) calIds.add(new Mode09Reader.CalIdEntry(
                        Math.max(0, value.optInt("ecu_index", i)), calId));
            }
        }

        List<Mode09Reader.CvnEntry> cvns = new ArrayList<>();
        JSONArray storedCvns = root.optJSONArray("cvns");
        if (storedCvns != null) {
            for (int i = 0; i < storedCvns.length(); i++) {
                JSONObject value = storedCvns.optJSONObject(i);
                if (value == null) continue;
                String cvn = value.optString("value", "").trim().toUpperCase(Locale.US);
                if (cvn.matches("[0-9A-F]{8}")) cvns.add(new Mode09Reader.CvnEntry(
                        Math.max(0, value.optInt("ecu_index", i)), cvn));
            }
        }

        return new VehicleInformationReader.Snapshot(vin, infoTypes, calIds, cvns,
                root.optLong("captured_at_epoch_ms", 0L));
    }

    static VehicleInformationReader.Snapshot withFallbackVin(
            VehicleInformationReader.Snapshot snapshot, String fallbackVin) {
        if (snapshot == null) return null;
        String vin = normalizeVin(snapshot.getVin());
        if (vin == null) vin = normalizeVin(fallbackVin);
        if (vin == null) return null;
        if (vin.equals(snapshot.getVin())) return snapshot;
        return new VehicleInformationReader.Snapshot(vin,
                snapshot.getSupportedInfoTypes(), snapshot.getCalIds(), snapshot.getCvns(),
                snapshot.getCapturedAtEpochMs());
    }

    private static String normalizeVin(String vin) {
        if (!VinBrandDetector.isStructurallyValid(vin)) return null;
        return vin.trim().toUpperCase(Locale.US);
    }
}
