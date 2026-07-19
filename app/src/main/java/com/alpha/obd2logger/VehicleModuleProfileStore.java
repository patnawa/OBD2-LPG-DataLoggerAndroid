package com.alpha.obd2logger;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Per-vehicle, app-owned inventory created from a successful read-only deep scan.
 *
 * <p>Profiles deliberately contain only observed bus/module identities and Mode
 * 03/07/0A response status. They contain no proprietary command definitions,
 * security material, programming data, or raw ECU payloads.</p>
 */
public final class VehicleModuleProfileStore {
    private static final String PREFS = "OBD2Prefs";
    private static final String KEY_PREFIX = "vehicle_module_profile_v1_";
    private static final int SCHEMA_VERSION = 1;

    private VehicleModuleProfileStore() {
    }

    public static final class Module {
        private final String canId;
        private final String name;
        private final String protocol;
        private final boolean storedResponded;
        private final boolean pendingResponded;
        private final boolean permanentResponded;

        Module(String canId, String name, String protocol, boolean storedResponded,
               boolean pendingResponded, boolean permanentResponded) {
            this.canId = canId;
            this.name = name;
            this.protocol = protocol;
            this.storedResponded = storedResponded;
            this.pendingResponded = pendingResponded;
            this.permanentResponded = permanentResponded;
        }

        public String getCanId() { return canId; }
        public String getName() { return name; }
        public String getProtocol() { return protocol; }
        public boolean isStoredResponded() { return storedResponded; }
        public boolean isPendingResponded() { return pendingResponded; }
        public boolean isPermanentResponded() { return permanentResponded; }

        /** Stable identity: display-name changes must not create a new module. */
        public String getStableId() {
            return protocol + " / " + canId;
        }

        public String getDisplayLabel() {
            return getStableId() + " (" + name + ")";
        }
    }

    public static final class Protocol {
        private final String label;
        private final boolean responded;

        Protocol(String label, boolean responded) {
            this.label = label;
            this.responded = responded;
        }

        public String getLabel() { return label; }
        public boolean responded() { return responded; }
    }

    public static final class Snapshot {
        private final String vin;
        private final String brand;
        private final long capturedAtEpochMs;
        private final List<Protocol> protocols;
        private final List<Module> modules;

        Snapshot(String vin, String brand, long capturedAtEpochMs,
                 List<Protocol> protocols, List<Module> modules) {
            this.vin = vin;
            this.brand = brand;
            this.capturedAtEpochMs = capturedAtEpochMs;
            this.protocols = Collections.unmodifiableList(new ArrayList<>(protocols));
            this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
        }

        public String getVin() { return vin; }
        public String getBrand() { return brand; }
        public long getCapturedAtEpochMs() { return capturedAtEpochMs; }
        public List<Protocol> getProtocols() { return protocols; }
        public List<Module> getModules() { return modules; }
    }

    /**
     * Capture one deep scan and initialize the VIN baseline once.
     *
     * <p>The returned snapshot is always the current scan. The stored baseline
     * is intentionally not overwritten by later scans, otherwise one transient
     * adapter timeout would become the new definition of the vehicle.</p>
     */
    public static Snapshot save(Context context, String vin, DtcReader.DtcScanResult result) {
        if (context == null || result == null) return null;
        String normalizedVin = normalizeVin(vin);
        if (normalizedVin == null) return null;
        Snapshot snapshot = fromScan(normalizedVin, result);
        JSONObject json = toJson(snapshot);
        if (json == null) return null;
        android.content.SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = KEY_PREFIX + normalizedVin;
        if (!snapshot.getModules().isEmpty() && get(context, normalizedVin) == null) {
            preferences.edit().putString(key, json.toString()).apply();
        }
        return snapshot;
    }

    public static Snapshot get(Context context, String vin) {
        if (context == null) return null;
        String normalizedVin = normalizeVin(vin);
        if (normalizedVin == null) return null;
        String raw = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + normalizedVin, null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            Snapshot snapshot = fromJson(new JSONObject(raw));
            return snapshot != null && normalizedVin.equals(snapshot.getVin()) ? snapshot : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static Snapshot fromScan(String normalizedVin, DtcReader.DtcScanResult result) {
        List<Protocol> protocols = new ArrayList<>();
        for (DtcReader.ProtocolScanStatus status : result.protocolStatuses) {
            if (status == null || status.bus == null) continue;
            protocols.add(new Protocol(status.bus.label, status.responded));
        }

        List<Module> modules = new ArrayList<>();
        for (DtcReader.ModuleInfo module : result.modules) {
            if (module == null || !module.moduleScanned) continue;
            modules.add(new Module(clean(module.canId), clean(module.moduleName), clean(module.protocolLabel),
                    module.storedOk, module.pendingOk, module.permanentOk));
        }
        VinBrandDetector.Brand detected = VinBrandDetector.detect(normalizedVin);
        return new Snapshot(normalizedVin, VinBrandDetector.getBrandName(detected),
                System.currentTimeMillis(), protocols, modules);
    }

    static JSONObject toJson(Snapshot snapshot) {
        if (snapshot == null || normalizeVin(snapshot.getVin()) == null) return null;
        try {
            JSONObject root = new JSONObject();
            root.put("schema_version", SCHEMA_VERSION);
            root.put("source", "live_deep_obd_scan");
            root.put("captured_at_epoch_ms", snapshot.getCapturedAtEpochMs());
            root.put("vin", snapshot.getVin());
            root.put("brand", snapshot.getBrand());

            JSONArray protocols = new JSONArray();
            for (Protocol protocol : snapshot.getProtocols()) {
                JSONObject value = new JSONObject();
                value.put("label", protocol.getLabel());
                value.put("responded", protocol.responded());
                protocols.put(value);
            }
            root.put("protocols", protocols);

            JSONArray modules = new JSONArray();
            for (Module module : snapshot.getModules()) {
                JSONObject value = new JSONObject();
                value.put("can_id", module.getCanId());
                value.put("name", module.getName());
                value.put("protocol", module.getProtocol());
                value.put("stored_responded", module.isStoredResponded());
                value.put("pending_responded", module.isPendingResponded());
                value.put("permanent_responded", module.isPermanentResponded());
                modules.put(value);
            }
            root.put("modules", modules);
            return root;
        } catch (Exception ignored) {
            return null;
        }
    }

    static Snapshot fromJson(JSONObject root) {
        if (root == null || root.optInt("schema_version", -1) != SCHEMA_VERSION) return null;
        String vin = normalizeVin(root.optString("vin", null));
        if (vin == null) return null;

        List<Protocol> protocols = new ArrayList<>();
        JSONArray protocolValues = root.optJSONArray("protocols");
        if (protocolValues != null) {
            for (int i = 0; i < protocolValues.length(); i++) {
                JSONObject value = protocolValues.optJSONObject(i);
                if (value == null) continue;
                String label = clean(value.optString("label", null));
                if (!label.isEmpty()) protocols.add(new Protocol(label, value.optBoolean("responded")));
            }
        }

        List<Module> modules = new ArrayList<>();
        JSONArray moduleValues = root.optJSONArray("modules");
        if (moduleValues != null) {
            for (int i = 0; i < moduleValues.length(); i++) {
                JSONObject value = moduleValues.optJSONObject(i);
                if (value == null) continue;
                String canId = clean(value.optString("can_id", null));
                String name = clean(value.optString("name", null));
                String protocol = clean(value.optString("protocol", null));
                if (canId.isEmpty() || name.isEmpty() || protocol.isEmpty()) continue;
                modules.add(new Module(canId, name, protocol,
                        value.optBoolean("stored_responded"),
                        value.optBoolean("pending_responded"),
                        value.optBoolean("permanent_responded")));
            }
        }

        return new Snapshot(vin, clean(root.optString("brand", "Unknown")),
                Math.max(0L, root.optLong("captured_at_epoch_ms", 0L)), protocols, modules);
    }

    private static String normalizeVin(String vin) {
        if (!VinBrandDetector.isStructurallyValid(vin)) return null;
        return vin.trim().toUpperCase(Locale.US);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
