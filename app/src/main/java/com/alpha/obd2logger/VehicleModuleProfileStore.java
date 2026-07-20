package com.alpha.obd2logger;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static final String SMART_EVIDENCE_KEY_PREFIX =
            "vehicle_smart_protocol_evidence_v2_";
    private static final int SMART_EVIDENCE_SCHEMA_VERSION = 2;
    private static final String SMART_EVIDENCE_KIND = "smart_protocol_evidence";
    private static final String SMART_EVIDENCE_SOURCE = "strict_full_obd_scan";

    private static final ObdProtocol[] STANDARD_OBD_PROTOCOLS = {
            ObdProtocol.SAE_J1850_PWM,
            ObdProtocol.SAE_J1850_VPW,
            ObdProtocol.ISO_9141_2,
            ObdProtocol.ISO_14230_4_KWP_5_BAUD,
            ObdProtocol.ISO_14230_4_KWP_FAST,
            ObdProtocol.ISO_15765_4_CAN_11BIT_500,
            ObdProtocol.ISO_15765_4_CAN_29BIT_500,
            ObdProtocol.ISO_15765_4_CAN_11BIT_250,
            ObdProtocol.ISO_15765_4_CAN_29BIT_250
    };

    private VehicleModuleProfileStore() {
    }

    public static final class Module {
        private final String canId;
        private final String requestCanId;
        private final String name;
        private final String protocol;
        private final boolean storedResponded;
        private final boolean pendingResponded;
        private final boolean permanentResponded;

        Module(String canId, String name, String protocol, boolean storedResponded,
               boolean pendingResponded, boolean permanentResponded) {
            this(canId, null, name, protocol, storedResponded,
                    pendingResponded, permanentResponded);
        }

        Module(String canId, String requestCanId, String name, String protocol,
               boolean storedResponded, boolean pendingResponded,
               boolean permanentResponded) {
            this.canId = canId;
            this.requestCanId = requestCanId;
            this.name = name;
            this.protocol = protocol;
            this.storedResponded = storedResponded;
            this.pendingResponded = pendingResponded;
            this.permanentResponded = permanentResponded;
        }

        public String getCanId() { return canId; }
        public String getRequestCanId() { return requestCanId; }
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
     * Refreshable protocol hints that are intentionally separate from the
     * immutable v1 module-comparison baseline.
     *
     * <p>Only canonical OBD-II protocols 1-9 can be represented. The producer
     * must supply protocols for which a strict Full scan both confirmed the
     * selected protocol and observed a positive Mode 03/07/0A module response.
     * Legacy v1 profiles are never converted into this type.</p>
     */
    public static final class SmartProtocolEvidence {
        private final String vin;
        private final long capturedAtEpochMs;
        private final List<String> protocolIds;
        private final List<ObdProtocol> protocols;

        SmartProtocolEvidence(String vin, long capturedAtEpochMs,
                              List<ObdProtocol> protocols) {
            this.vin = vin;
            this.capturedAtEpochMs = Math.max(0L, capturedAtEpochMs);
            List<ObdProtocol> protocolCopy = canonicalStandardProtocols(protocols);
            this.protocols = Collections.unmodifiableList(protocolCopy);
            List<String> ids = new ArrayList<>();
            for (ObdProtocol protocol : protocolCopy) {
                ids.add(protocol.getElmValue());
            }
            this.protocolIds = Collections.unmodifiableList(ids);
        }

        public String getVin() { return vin; }
        public long getCapturedAtEpochMs() { return capturedAtEpochMs; }
        public List<String> getProtocolIds() { return protocolIds; }
        public List<ObdProtocol> getProtocols() { return protocols; }
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

    /**
     * Replace the reusable Smart-scan hints for one verified VIN.
     *
     * <p>This v2 record is refreshable by design. It never reads, rewrites, or
     * migrates the immutable v1 comparison baseline. Passing an empty
     * collection stores an empty, valid record and therefore clears stale
     * reusable protocol hints. Passing {@code null} is treated as invalid and
     * leaves any existing record unchanged.</p>
     *
     * @param verifiedProtocols only protocols backed by strict selection and
     *                          positive module-response evidence
     */
    static SmartProtocolEvidence saveSmartProtocolEvidence(
            Context context, String vin, Collection<ObdProtocol> verifiedProtocols) {
        if (context == null || verifiedProtocols == null) return null;
        String normalizedVin = normalizeVin(vin);
        if (normalizedVin == null) return null;
        SmartProtocolEvidence evidence = new SmartProtocolEvidence(
                normalizedVin, System.currentTimeMillis(),
                canonicalStandardProtocols(verifiedProtocols));
        JSONObject json = smartProtocolEvidenceToJson(evidence);
        if (json == null) return null;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(SMART_EVIDENCE_KEY_PREFIX + normalizedVin, json.toString())
                .apply();
        return evidence;
    }

    /**
     * Extract reusable evidence from a strict Full scan. Only explicitly
     * selected protocols with both a responding status and a positive
     * Mode 03/07/0A module result are retained. CURRENT is excluded because it
     * was not independently selected and verified by ATDPN during this scan.
     */
    public static SmartProtocolEvidence saveSmartProtocolEvidenceFromScan(
            Context context, String vin, DtcReader.DtcScanResult result) {
        if (result == null) return null;
        LinkedHashSet<ObdProtocol> verified = new LinkedHashSet<>();
        for (DtcReader.ProtocolScanStatus status : result.protocolStatuses) {
            if (status == null || !status.responded || status.bus == null
                    || status.bus.atSpCommand == null || status.bus.protocol == null) {
                continue;
            }
            String id = status.bus.protocol.getElmValue();
            if (id.length() != 1 || id.charAt(0) < '1' || id.charAt(0) > '9') continue;
            for (DtcReader.ModuleInfo module : result.modules) {
                if (module != null && status.bus.label.equals(module.protocolLabel)
                        && (module.storedOk || module.pendingOk || module.permanentOk)) {
                    verified.add(status.bus.protocol);
                    break;
                }
            }
        }
        return saveSmartProtocolEvidence(context, vin, verified);
    }

    /**
     * Read only v2 strict Full-scan protocol evidence for {@code vin}.
     *
     * <p>No fallback to the v1 module profile is allowed: those records predate
     * strict protocol-selection provenance and are display/comparison data
     * only.</p>
     */
    public static SmartProtocolEvidence getSmartProtocolEvidence(
            Context context, String vin) {
        if (context == null) return null;
        String normalizedVin = normalizeVin(vin);
        if (normalizedVin == null) return null;
        String raw = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SMART_EVIDENCE_KEY_PREFIX + normalizedVin, null);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            SmartProtocolEvidence evidence = smartProtocolEvidenceFromJson(
                    new JSONObject(raw));
            return evidence != null && normalizedVin.equals(evidence.getVin())
                    ? evidence : null;
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
            modules.add(new Module(clean(module.canId), cleanNullable(module.requestCanId),
                    clean(module.moduleName), clean(module.protocolLabel), module.storedOk,
                    module.pendingOk, module.permanentOk));
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
                if (module.getRequestCanId() != null) {
                    value.put("request_can_id", module.getRequestCanId());
                }
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
                String requestCanId = cleanNullable(
                        value.optString("request_can_id", null));
                String name = clean(value.optString("name", null));
                String protocol = clean(value.optString("protocol", null));
                if (canId.isEmpty() || name.isEmpty() || protocol.isEmpty()) continue;
                modules.add(new Module(canId, requestCanId, name, protocol,
                        value.optBoolean("stored_responded"),
                        value.optBoolean("pending_responded"),
                        value.optBoolean("permanent_responded")));
            }
        }

        return new Snapshot(vin, clean(root.optString("brand", "Unknown")),
                Math.max(0L, root.optLong("captured_at_epoch_ms", 0L)), protocols, modules);
    }

    static JSONObject smartProtocolEvidenceToJson(SmartProtocolEvidence evidence) {
        if (evidence == null || normalizeVin(evidence.getVin()) == null
                || evidence.getCapturedAtEpochMs() <= 0L) {
            return null;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("schema_version", SMART_EVIDENCE_SCHEMA_VERSION);
            root.put("profile_kind", SMART_EVIDENCE_KIND);
            root.put("source", SMART_EVIDENCE_SOURCE);
            root.put("captured_at_epoch_ms", evidence.getCapturedAtEpochMs());
            root.put("vin", evidence.getVin());

            JSONArray protocols = new JSONArray();
            for (ObdProtocol protocol : evidence.getProtocols()) {
                if (!isStandardObdProtocol(protocol)) continue;
                JSONObject value = new JSONObject();
                value.put("id", protocol.getElmValue());
                value.put("selection_verified", true);
                value.put("positive_module_evidence", true);
                protocols.put(value);
            }
            root.put("protocols", protocols);
            return root;
        } catch (Exception ignored) {
            return null;
        }
    }

    static SmartProtocolEvidence smartProtocolEvidenceFromJson(JSONObject root) {
        if (root == null
                || root.optInt("schema_version", -1) != SMART_EVIDENCE_SCHEMA_VERSION
                || !SMART_EVIDENCE_KIND.equals(root.optString("profile_kind", null))
                || !SMART_EVIDENCE_SOURCE.equals(root.optString("source", null))) {
            return null;
        }
        String vin = normalizeVin(root.optString("vin", null));
        long capturedAt = root.optLong("captured_at_epoch_ms", 0L);
        JSONArray values = root.optJSONArray("protocols");
        if (vin == null || capturedAt <= 0L || values == null) return null;

        List<ObdProtocol> verified = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value == null
                    || !value.optBoolean("selection_verified", false)
                    || !value.optBoolean("positive_module_evidence", false)) {
                continue;
            }
            ObdProtocol protocol = standardProtocolForId(
                    clean(value.optString("id", null)));
            if (protocol != null) verified.add(protocol);
        }
        return new SmartProtocolEvidence(
                vin, capturedAt, canonicalStandardProtocols(verified));
    }

    private static List<ObdProtocol> canonicalStandardProtocols(
            Collection<ObdProtocol> protocols) {
        Set<ObdProtocol> requested = new LinkedHashSet<>();
        if (protocols != null) requested.addAll(protocols);
        List<ObdProtocol> canonical = new ArrayList<>();
        for (ObdProtocol standard : STANDARD_OBD_PROTOCOLS) {
            if (requested.contains(standard)) canonical.add(standard);
        }
        return canonical;
    }

    private static boolean isStandardObdProtocol(ObdProtocol protocol) {
        if (protocol == null) return false;
        String id = protocol.getElmValue();
        return id != null && id.length() == 1
                && id.charAt(0) >= '1' && id.charAt(0) <= '9';
    }

    private static ObdProtocol standardProtocolForId(String id) {
        if (id == null || id.length() != 1
                || id.charAt(0) < '1' || id.charAt(0) > '9') {
            return null;
        }
        for (ObdProtocol protocol : STANDARD_OBD_PROTOCOLS) {
            if (id.equals(protocol.getElmValue())) return protocol;
        }
        return null;
    }

    private static String normalizeVin(String vin) {
        if (!VinBrandDetector.isStructurallyValid(vin)) return null;
        return vin.trim().toUpperCase(Locale.US);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanNullable(String value) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? null : cleaned;
    }
}
