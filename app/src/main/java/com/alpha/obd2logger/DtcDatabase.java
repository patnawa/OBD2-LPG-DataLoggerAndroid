package com.alpha.obd2logger;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Loads and queries the OBD2 DTC offline description database.
 * Supports both the generic database (dtc_database.json) and
 * manufacturer-specific databases (dtc_toyota.json, dtc_honda.json, etc.)
 * loaded dynamically based on detected VIN brand.
 */
public final class DtcDatabase {
    private static final String TAG = "DtcDatabase";
    // Published as complete, never-mutated maps so lookup() can read them
    // from scan threads without locking while init/initForVin swap them.
    private static volatile Map<String, String> genericCache = java.util.Collections.emptyMap();
    private static volatile Map<String, String> brandCache = java.util.Collections.emptyMap();
    private static boolean initialized = false;
    private static VinBrandDetector.Brand currentBrand = null;
    private static Context appContext = null;

    private DtcDatabase() {
    }

    /**
     * Load the generic DTC database from assets.
     * Call during application/activity startup.
     */
    public static VinBrandDetector.Brand getCurrentBrand() {
        return currentBrand;
    }

    public static Context getAppContext() {
        return appContext;
    }

    public static synchronized void init(Context context) {
        if (initialized) return;
        appContext = context.getApplicationContext();
        genericCache = loadDatabase(context, "dtc_database.json");
        initialized = true;
    }

    /**
     * Drop all cached databases and reload from storage. Needed after an
     * OTA update: init() alone is a no-op once initialized, so without
     * this the process would keep serving stale descriptions until killed.
     */
    public static synchronized void reload(Context context) {
        initialized = false;
        currentBrand = null;
        brandCache = java.util.Collections.emptyMap();
        init(context);
    }

    /**
     * Load a manufacturer-specific DTC database based on detected VIN brand.
     * This is called after VIN is read. Brand-specific codes (P1xxx, P3xxx)
     * are merged on top of the generic database.
     *
     * @param context  app context
     * @param vin      vehicle VIN (used to detect brand)
     * @return the detected brand name, or null if unknown
     */
    public static synchronized String initForVin(Context context, String vin) {
        if (!initialized) init(context);
        if (vin == null || vin.isEmpty()) return null;

        VinBrandDetector.Brand brand = VinBrandDetector.detect(vin);
        if (brand == currentBrand) {
            return VinBrandDetector.getBrandName(brand);
        }

        // Swap in the new brand cache atomically so concurrent lookup()
        // calls never observe a half-populated or cleared map.
        String assetFile = VinBrandDetector.getDtcDatabaseAsset(brand);
        brandCache = assetFile != null
                ? loadDatabase(context, assetFile)
                : java.util.Collections.<String, String>emptyMap();
        currentBrand = brand;
        // Propagate brand to DtcReader so ECU module names use the correct
        // manufacturer labels (Toyota vs Nissan vs Mazda, etc.) instead of
        // whatever brand was last to put() into the shared map.
        DtcReader.setBrand(brand);
        String brandName = VinBrandDetector.getBrandName(brand);
        if (!brandCache.isEmpty()) {
            Log.i(TAG, "Loaded " + brandCache.size() + " brand-specific codes for " + brandName);
        } else if (assetFile == null && brand != VinBrandDetector.Brand.UNKNOWN) {
            // Several brands are recognised by VIN but ship no manufacturer DTC
            // file, so their P1xxx/U1xxx codes fall back to generic text with no
            // indication that anything is missing. Say so, rather than letting a
            // thin scan look like a clean one.
            Log.w(TAG, "No manufacturer DTC database for " + brandName
                    + " — manufacturer-specific codes will show generic descriptions only");
        }
        return brandName;
    }

    private static Map<String, String> loadDatabase(Context context, String assetFile) {
        Map<String, String> cache = new HashMap<>();
        InputStream is = null;
        try {
            java.io.File localUpdate = new java.io.File(context.getFilesDir(), assetFile);

            // 1. Try to load the OTA updated file from internal storage first
            if (localUpdate.exists() && localUpdate.length() > 0) {
                is = new java.io.FileInputStream(localUpdate);
                Log.i(TAG, "Loading OTA updated database: " + assetFile);
            } else {
                // 2. Fallback to the bundled app assets
                is = context.getAssets().open(assetFile);
            }

            // available() is only a hint and a single read() may return
            // short — read to EOF so large/compressed streams load fully.
            String jsonStr = new String(readFully(is), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                cache.put(key.toUpperCase(), json.getString(key));
            }
            Log.i(TAG, "Loaded " + cache.size() + " codes from " + assetFile);
        } catch (Exception e) {
            Log.w(TAG, "Could not load " + assetFile + ": " + e.getMessage());
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
        return cache;
    }

    static byte[] readFully(InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    /**
     * Retrieve the description for a DTC code.
     * Checks brand-specific database first, then falls back to generic.
     */
    public static String lookup(String code) {
        if (code == null) return null;
        String upper = code.toUpperCase();
        // Brand-specific first (P1xxx, P3xxx are usually manufacturer-specific)
        String result = brandCache.get(upper);
        if (result != null) return result;
        // Generic fallback
        return genericCache.get(upper);
    }
}
