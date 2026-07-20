package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Versioned, per-VIN persistence for read-only ECU identification snapshots.
 *
 * <p>Each VIN owns one JSON record containing the most recent snapshot for
 * every observed module. Saving one module replaces only the snapshot with the
 * same {@link EcuIdentificationReader.Target#getStableId() stable identity};
 * snapshots for the vehicle's other modules remain intact.</p>
 */
public final class EcuIdentificationStore {
    private static final String PREFS = "OBD2Prefs";
    private static final String KEY_PREFIX = "ecu_identification_v1_";
    private static final int SCHEMA_VERSION = 1;

    /** Prevent a corrupt or synthetic record from growing preferences forever. */
    static final int MAX_MODULES = 64;

    private EcuIdentificationStore() {
    }

    /**
     * Merge one module snapshot into the VIN record and return the stored view.
     * Invalid VINs and unusable snapshots are rejected without touching storage.
     */
    public static synchronized List<EcuIdentificationReader.Snapshot> save(
            Context context, String vin, EcuIdentificationReader.Snapshot snapshot) {
        String normalizedVin = normalizeVin(vin);
        if (context == null || normalizedVin == null || !hasStableTarget(snapshot)) {
            return Collections.emptyList();
        }

        SharedPreferences preferences = preferences(context);
        List<EcuIdentificationReader.Snapshot> stored = read(preferences, normalizedVin);
        // An unsupported DID sweep (all negative, pending, or malformed) is not
        // useful identification data. In particular, it must never erase the
        // last known-good values for this module.
        if (!isPersistable(snapshot)) return stored;

        List<EcuIdentificationReader.Snapshot> merged = merge(
                stored, snapshot);
        JSONObject json = toJson(normalizedVin, merged);
        if (json == null) return Collections.emptyList();

        preferences.edit()
                .putString(KEY_PREFIX + normalizedVin, json.toString())
                .apply();
        return merged;
    }

    /** Return all saved module snapshots for a VIN, or an immutable empty list. */
    public static List<EcuIdentificationReader.Snapshot> get(Context context, String vin) {
        String normalizedVin = normalizeVin(vin);
        if (context == null || normalizedVin == null) return Collections.emptyList();
        return read(preferences(context), normalizedVin);
    }

    /** Pure JSON encoder kept package-visible for local JVM tests. */
    static JSONObject toJson(String vin, List<EcuIdentificationReader.Snapshot> snapshots) {
        String normalizedVin = normalizeVin(vin);
        if (normalizedVin == null) return null;
        try {
            List<EcuIdentificationReader.Snapshot> bounded = boundedCopy(snapshots);
            JSONObject root = new JSONObject();
            root.put("schema_version", SCHEMA_VERSION);
            root.put("source", "live_read_only_uds_identification");
            root.put("vin", normalizedVin);
            root.put("module_count", bounded.size());

            JSONArray modules = new JSONArray();
            for (EcuIdentificationReader.Snapshot snapshot : bounded) {
                modules.put(snapshotToJson(snapshot));
            }
            root.put("modules", modules);
            return root;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Pure JSON decoder kept package-visible for local JVM tests. */
    static List<EcuIdentificationReader.Snapshot> fromJson(JSONObject root) {
        if (root == null || root.optInt("schema_version", -1) != SCHEMA_VERSION
                || normalizeVin(root.optString("vin", null)) == null) {
            return Collections.emptyList();
        }

        JSONArray modules = root.optJSONArray("modules");
        if (modules == null) return Collections.emptyList();

        LinkedHashMap<String, EcuIdentificationReader.Snapshot> unique =
                new LinkedHashMap<>();
        for (int i = 0; i < modules.length(); i++) {
            EcuIdentificationReader.Snapshot snapshot = snapshotFromJson(
                    modules.optJSONObject(i));
            if (!isPersistable(snapshot)) continue;
            putLatest(unique, snapshot);
        }
        return immutableValues(unique);
    }

    /** Pure merge helper: replace the matching module and preserve all others. */
    static List<EcuIdentificationReader.Snapshot> merge(
            List<EcuIdentificationReader.Snapshot> stored,
            EcuIdentificationReader.Snapshot incoming) {
        LinkedHashMap<String, EcuIdentificationReader.Snapshot> unique =
                toBoundedMap(stored);
        if (isPersistable(incoming)) putLatest(unique, incoming);
        return immutableValues(unique);
    }

    private static List<EcuIdentificationReader.Snapshot> read(
            SharedPreferences preferences, String normalizedVin) {
        String raw = preferences.getString(KEY_PREFIX + normalizedVin, null);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        try {
            JSONObject root = new JSONObject(raw);
            if (!normalizedVin.equals(normalizeVin(root.optString("vin", null)))) {
                return Collections.emptyList();
            }
            return fromJson(root);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static JSONObject snapshotToJson(EcuIdentificationReader.Snapshot snapshot)
            throws org.json.JSONException {
        EcuIdentificationReader.Target target = snapshot.getTarget();
        JSONObject value = new JSONObject();
        value.put("captured_at_epoch_ms", snapshot.getCapturedAtEpochMs());
        value.put("responded", snapshot.isResponded());
        value.put("positive_count", snapshot.getPositiveCount());
        value.put("negative_count", snapshot.getNegativeCount());
        value.put("malformed_count", snapshot.getMalformedCount());

        JSONObject storedTarget = new JSONObject();
        storedTarget.put("name", target.getName());
        storedTarget.put("request_id", target.getRequestId());
        storedTarget.put("response_id", target.getResponseId());
        storedTarget.put("protocol", target.getProtocol());
        storedTarget.put("stable_id", target.getStableId());
        storedTarget.put("display_label", target.getDisplayLabel());
        value.put("target", storedTarget);

        JSONArray items = new JSONArray();
        for (EcuIdentificationReader.Item item : snapshot.getItems()) {
            if (item == null) continue;
            JSONObject storedItem = new JSONObject();
            storedItem.put("identifier", item.getIdentifier());
            storedItem.put("did_hex", item.getDidHex());
            storedItem.put("label", item.getLabel());
            storedItem.put("display_value", item.getDisplayValue());
            storedItem.put("raw_hex", item.getRawHex());
            storedItem.put("status", item.getStatus().name());
            storedItem.put("negative_response_code", item.getNegativeResponseCode());
            storedItem.put("detail", item.getDetail());
            items.put(storedItem);
        }
        value.put("items", items);
        return value;
    }

    private static EcuIdentificationReader.Snapshot snapshotFromJson(JSONObject value) {
        if (value == null) return null;
        try {
            JSONObject storedTarget = value.optJSONObject("target");
            if (storedTarget == null) return null;
            EcuIdentificationReader.Target target = new EcuIdentificationReader.Target(
                    storedTarget.optString("name", ""),
                    storedTarget.optString("request_id", ""),
                    storedTarget.optString("response_id", ""),
                    storedTarget.optString("protocol", ""));

            List<EcuIdentificationReader.Item> items = new ArrayList<>();
            JSONArray storedItems = value.optJSONArray("items");
            if (storedItems != null) {
                for (int i = 0; i < storedItems.length(); i++) {
                    EcuIdentificationReader.Item item = itemFromJson(
                            storedItems.optJSONObject(i));
                    if (item != null) items.add(item);
                }
            }
            return new EcuIdentificationReader.Snapshot(target, items,
                    value.optLong("captured_at_epoch_ms", 0L));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static EcuIdentificationReader.Item itemFromJson(JSONObject value) {
        if (value == null) return null;
        try {
            EcuIdentificationReader.Status status = EcuIdentificationReader.Status.valueOf(
                    value.optString("status", ""));
            return new EcuIdentificationReader.Item(
                    value.optInt("identifier", -1),
                    value.optString("label", ""),
                    value.optString("display_value", ""),
                    value.optString("raw_hex", ""),
                    status,
                    value.optInt("negative_response_code", -1),
                    value.optString("detail", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<EcuIdentificationReader.Snapshot> boundedCopy(
            List<EcuIdentificationReader.Snapshot> snapshots) {
        return immutableValues(toBoundedMap(snapshots));
    }

    private static LinkedHashMap<String, EcuIdentificationReader.Snapshot> toBoundedMap(
            List<EcuIdentificationReader.Snapshot> snapshots) {
        LinkedHashMap<String, EcuIdentificationReader.Snapshot> unique =
                new LinkedHashMap<>();
        if (snapshots != null) {
            for (EcuIdentificationReader.Snapshot snapshot : snapshots) {
                if (isPersistable(snapshot)) putLatest(unique, snapshot);
            }
        }
        return unique;
    }

    private static void putLatest(
            LinkedHashMap<String, EcuIdentificationReader.Snapshot> snapshots,
            EcuIdentificationReader.Snapshot snapshot) {
        String stableId = snapshot.getTarget().getStableId();
        // Remove first so replacing an old module also marks it as most recent.
        snapshots.remove(stableId);
        snapshots.put(stableId, snapshot);
        while (snapshots.size() > MAX_MODULES) {
            String eldest = snapshots.keySet().iterator().next();
            snapshots.remove(eldest);
        }
    }

    private static List<EcuIdentificationReader.Snapshot> immutableValues(
            LinkedHashMap<String, EcuIdentificationReader.Snapshot> snapshots) {
        if (snapshots.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(snapshots.values()));
    }

    private static boolean hasStableTarget(EcuIdentificationReader.Snapshot snapshot) {
        return snapshot != null && snapshot.getTarget() != null
                && snapshot.getTarget().getStableId() != null
                && !snapshot.getTarget().getStableId().trim().isEmpty();
    }

    private static boolean isPersistable(EcuIdentificationReader.Snapshot snapshot) {
        return hasStableTarget(snapshot) && snapshot.getPositiveCount() > 0;
    }

    private static SharedPreferences preferences(Context context) {
        Context application = context.getApplicationContext();
        Context owner = application != null ? application : context;
        return owner.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String normalizeVin(String vin) {
        if (!VinBrandDetector.isStructurallyValid(vin)) return null;
        return vin.trim().toUpperCase(Locale.US);
    }
}
