package com.alpha.obd2logger;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and queries the DTC enrichment database.
 * Enrichment adds: probable causes, repair suggestions, emissions flag,
 * drive cycles to clear, severity, and system category.
 */
public final class DtcEnrichment {

    private static final String TAG = "DtcEnrichment";
    private static final Map<String, EnrichmentData> cache = new HashMap<>();
    private static boolean initialized = false;

    private DtcEnrichment() {}

    /**
     * Load enrichment data from assets. Call during application/activity startup.
     */
    public static synchronized void init(Context context) {
        if (initialized) return;
        try {
            AssetManager am = context.getAssets();
            String jsonStr;
            // Read to EOF — available() is a hint and read() may return short.
            try (InputStream is = am.open("dtc_enrichment.json")) {
                jsonStr = new String(DtcDatabase.readFully(is), StandardCharsets.UTF_8);
            }
            JSONObject json = new JSONObject(jsonStr);

            java.util.Iterator<String> iter = json.keys();
            while (iter.hasNext()) {
                String key = iter.next();
                JSONObject entry = json.getJSONObject(key);

                List<String> causes = new ArrayList<>();
                JSONArray causesArr = entry.optJSONArray("causes");
                if (causesArr != null) {
                    for (int i = 0; i < causesArr.length(); i++) {
                        causes.add(causesArr.getString(i));
                    }
                }

                List<String> fixes = new ArrayList<>();
                JSONArray fixesArr = entry.optJSONArray("fixes");
                if (fixesArr != null) {
                    for (int i = 0; i < fixesArr.length(); i++) {
                        fixes.add(fixesArr.getString(i));
                    }
                }

                boolean emissionsRelated = entry.optBoolean("emissions_related", false);
                int driveCycles = entry.optInt("drive_cycles_to_clear", -1);
                String severity = entry.optString("severity", "warning");
                String system = entry.optString("system", "");

                EnrichmentData data = new EnrichmentData(
                    causes, fixes, emissionsRelated, driveCycles, severity, system
                );
                cache.put(key.toUpperCase(), data);
            }

            initialized = true;
            Log.i(TAG, "DtcEnrichment loaded " + cache.size() + " entries");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load DTC enrichment database", e);
        }
    }

    /**
     * Get enrichment data for a DTC code.
     */
    public static EnrichmentData lookup(String code) {
        if (code == null) return null;
        return cache.get(code.toUpperCase());
    }

    /**
     * Enrichment data for a single DTC.
     */
    public static final class EnrichmentData {
        private final List<String> causes;
        private final List<String> fixes;
        private final boolean emissionsRelated;
        private final int driveCyclesToClear;
        private final String severity;
        private final String system;

        public EnrichmentData(List<String> causes, List<String> fixes,
                              boolean emissionsRelated, int driveCyclesToClear,
                              String severity, String system) {
            this.causes = causes != null ? causes : new ArrayList<>();
            this.fixes = fixes != null ? fixes : new ArrayList<>();
            this.emissionsRelated = emissionsRelated;
            this.driveCyclesToClear = driveCyclesToClear;
            this.severity = severity != null ? severity : "warning";
            this.system = system != null ? system : "";
        }

        public List<String> getCauses() { return causes; }
        public List<String> getFixes() { return fixes; }
        public boolean isEmissionsRelated() { return emissionsRelated; }
        public int getDriveCyclesToClear() { return driveCyclesToClear; }
        public String getSeverity() { return severity; }
        public String getSystem() { return system; }
    }
}
