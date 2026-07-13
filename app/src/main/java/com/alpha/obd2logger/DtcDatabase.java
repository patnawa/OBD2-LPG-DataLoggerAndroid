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
    private static final Map<String, String> genericCache = new HashMap<>();
    private static final Map<String, String> brandCache = new HashMap<>();
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
        loadDatabase(context, "dtc_database.json", genericCache);
        initialized = true;
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

        // Clear previous brand cache
        brandCache.clear();

        String assetFile = VinBrandDetector.getDtcDatabaseAsset(brand);
        if (assetFile != null) {
            loadDatabase(context, assetFile, brandCache);
        }
        currentBrand = brand;
        // Propagate brand to DtcReader so ECU module names use the correct
        // manufacturer labels (Toyota vs Nissan vs Mazda, etc.) instead of
        // whatever brand was last to put() into the shared map.
        DtcReader.setBrand(brand);
        String brandName = VinBrandDetector.getBrandName(brand);
        if (!brandCache.isEmpty()) {
            Log.i(TAG, "Loaded " + brandCache.size() + " brand-specific codes for " + brandName);
        }
        return brandName;
    }

    private static void loadDatabase(Context context, String assetFile, Map<String, String> cache) {
        try {
            InputStream is = null;
            java.io.File localUpdate = new java.io.File(context.getFilesDir(), assetFile);
            
            // 1. Try to load the OTA updated file from internal storage first
            if (localUpdate.exists() && localUpdate.length() > 0) {
                is = new java.io.FileInputStream(localUpdate);
                Log.i(TAG, "Loading OTA updated database: " + assetFile);
            } else {
                // 2. Fallback to the bundled app assets
                is = context.getAssets().open(assetFile);
            }
            
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String jsonStr = new String(buffer, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonStr);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                cache.put(key.toUpperCase(), json.getString(key));
            }
            Log.i(TAG, "Loaded " + cache.size() + " codes from " + assetFile);
        } catch (Exception e) {
            Log.w(TAG, "Could not load " + assetFile + ": " + e.getMessage());
        }
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
