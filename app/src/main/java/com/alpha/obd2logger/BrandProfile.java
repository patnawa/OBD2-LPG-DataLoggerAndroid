package com.alpha.obd2logger;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Brand knowledge loaded from {@code brand_profiles.json} — WMI prefixes, ECU
 * CAN-ID names, and manufacturer enhanced-mode pairs.
 *
 * <p>This replaces three overlapping hand-ordered rule layers (two in
 * {@link VinBrandDetector}, one in {@link BrandYearProfile}) in which a broad
 * rule placed above a specific one silently made the specific one unreachable.
 * Editing those dead rules changed nothing, which is why brand detection
 * appeared unfixable.
 *
 * <p><b>Matching is longest-prefix-wins</b>, so specificity is a property of the
 * data rather than of source ordering: {@code JTH} beats {@code JT} no matter
 * where either appears. Adding a brand therefore cannot break an existing one.
 *
 * <p>Loads from the OTA directory first (same channel as the DTC databases),
 * falling back to the bundled asset, so a new brand ships without an app
 * release. If both fail, callers fall back to the Java rules.
 */
final class BrandProfile {

    private static final String TAG = "BrandProfile";
    private static final String ASSET = "brand_profiles.json";

    private BrandProfile() {
    }

    /** WMI prefix (uppercase) → brand. Sorted longest-first at lookup. */
    private static volatile Map<String, VinBrandDetector.Brand> wmiIndex;
    private static volatile Map<VinBrandDetector.Brand, Map<Integer, String>> ecuNames;
    private static volatile Map<VinBrandDetector.Brand, List<String>> enhancedModes;
    private static volatile boolean loadAttempted;

    /** Longest WMI prefix present, so lookup only tries plausible widths. */
    private static volatile int longestWmi = 3;

    static synchronized void load(Context context) {
        if (loadAttempted) return;
        loadAttempted = true;
        try {
            String json = readProfileJson(context);
            if (json == null) {
                Log.w(TAG, "brand_profiles.json unavailable — using built-in Java rules");
                return;
            }
            parse(new JSONObject(json));
            Log.i(TAG, "Loaded " + wmiIndex.size() + " WMI prefixes for "
                    + ecuNames.size() + " brands with ECU maps");
        } catch (Exception e) {
            // Never let a malformed profile take down brand detection — the Java
            // fallback still works, just without OTA-updatable data.
            Log.e(TAG, "Failed to parse brand_profiles.json — using built-in rules", e);
            wmiIndex = null;
        }
    }

    /** Test seam: parse a profile document directly. */
    static synchronized void loadFrom(JSONObject root) throws Exception {
        loadAttempted = true;
        parse(root);
    }

    static synchronized void resetForTest() {
        loadAttempted = false;
        wmiIndex = null;
        ecuNames = null;
        enhancedModes = null;
        longestWmi = 3;
    }

    private static void parse(JSONObject root) throws Exception {
        JSONObject brands = root.getJSONObject("brands");
        Map<String, VinBrandDetector.Brand> wmi = new HashMap<>();
        Map<VinBrandDetector.Brand, Map<Integer, String>> ecus = new LinkedHashMap<>();
        Map<VinBrandDetector.Brand, List<String>> modes = new LinkedHashMap<>();
        int longest = 0;

        for (Iterator<String> it = brands.keys(); it.hasNext(); ) {
            String brandKey = it.next();
            VinBrandDetector.Brand brand;
            try {
                brand = VinBrandDetector.Brand.valueOf(brandKey);
            } catch (IllegalArgumentException unknownBrand) {
                // A newer profile may name a brand this build has no enum for.
                // Skipping keeps the rest of the file usable.
                Log.w(TAG, "Unknown brand in profile, skipped: " + brandKey);
                continue;
            }
            JSONObject entry = brands.getJSONObject(brandKey);

            JSONArray wmiArray = entry.optJSONArray("wmi");
            if (wmiArray != null) {
                for (int i = 0; i < wmiArray.length(); i++) {
                    String prefix = wmiArray.getString(i).trim().toUpperCase(Locale.US);
                    if (prefix.isEmpty()) continue;
                    VinBrandDetector.Brand clash = wmi.put(prefix, brand);
                    if (clash != null && clash != brand) {
                        // Two brands claiming the same prefix is a data error the
                        // validation test catches; log it rather than pick silently.
                        Log.w(TAG, "WMI '" + prefix + "' claimed by both "
                                + clash + " and " + brand);
                    }
                    longest = Math.max(longest, prefix.length());
                }
            }

            JSONObject ecuObject = entry.optJSONObject("ecu_names");
            if (ecuObject != null) {
                Map<Integer, String> map = new LinkedHashMap<>();
                for (Iterator<String> ids = ecuObject.keys(); ids.hasNext(); ) {
                    String id = ids.next();
                    try {
                        map.put((int) Long.parseLong(id, 16), ecuObject.getString(id));
                    } catch (NumberFormatException badId) {
                        Log.w(TAG, "Bad CAN ID '" + id + "' for " + brandKey);
                    }
                }
                if (!map.isEmpty()) ecus.put(brand, Collections.unmodifiableMap(map));
            }

            JSONArray modeArray = entry.optJSONArray("enhanced_modes");
            if (modeArray != null) {
                java.util.List<String> pairs = new java.util.ArrayList<>();
                for (int i = 0; i < modeArray.length(); i++) {
                    pairs.add(modeArray.getString(i));
                }
                if (!pairs.isEmpty()) modes.put(brand, Collections.unmodifiableList(pairs));
            }
        }

        wmiIndex = Collections.unmodifiableMap(wmi);
        ecuNames = Collections.unmodifiableMap(ecus);
        enhancedModes = Collections.unmodifiableMap(modes);
        longestWmi = Math.max(3, longest);
    }

    private static String readProfileJson(Context context) {
        // OTA copy first — same directory DtcUpdater writes brand databases to.
        try {
            File updated = new File(context.getFilesDir(), "dtc_updates/" + ASSET);
            if (updated.exists()) {
                try (InputStream in = new FileInputStream(updated)) {
                    return readAll(in);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "OTA brand profile unreadable, falling back to asset", e);
        }
        try (InputStream in = context.getAssets().open(ASSET)) {
            return readAll(in);
        } catch (Exception e) {
            Log.w(TAG, "Bundled brand profile unreadable", e);
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** True when profile data is available; false means callers use Java rules. */
    static boolean isLoaded() {
        return wmiIndex != null;
    }

    /**
     * Resolve a brand by longest matching WMI prefix.
     *
     * @return the brand, or null when no prefix matches or no profile is loaded
     */
    static VinBrandDetector.Brand brandForWmi(String wmi) {
        Map<String, VinBrandDetector.Brand> index = wmiIndex;
        if (index == null || wmi == null) return null;
        String key = wmi.trim().toUpperCase(Locale.US);
        // Longest first so a specific prefix always beats a broad one, without
        // depending on declaration order anywhere.
        for (int len = Math.min(longestWmi, key.length()); len >= 1; len--) {
            VinBrandDetector.Brand brand = index.get(key.substring(0, len));
            if (brand != null) return brand;
        }
        return null;
    }

    /** ECU CAN-ID → human name for a brand, or null when the brand has none. */
    static Map<Integer, String> ecuNamesFor(VinBrandDetector.Brand brand) {
        Map<VinBrandDetector.Brand, Map<Integer, String>> map = ecuNames;
        return map == null || brand == null ? null : map.get(brand);
    }

    /** Manufacturer enhanced-mode pairs, or null when the brand has none. */
    static List<String> enhancedModesFor(VinBrandDetector.Brand brand) {
        Map<VinBrandDetector.Brand, List<String>> map = enhancedModes;
        return map == null || brand == null ? null : map.get(brand);
    }
}
