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
 */
public final class DtcDatabase {
    private static final String TAG = "DtcDatabase";
    private static final Map<String, String> cache = new HashMap<>();
    private static boolean initialized = false;

    private DtcDatabase() {
    }

    /**
     * Load the JSON DTC database from assets. Call during application/activity startup.
     */
    public static synchronized void init(Context context) {
        if (initialized) return;
        try {
            AssetManager am = context.getAssets();
            InputStream is = am.open("dtc_database.json");
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
            initialized = true;
            Log.i(TAG, "DtcDatabase loaded " + cache.size() + " codes successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load DTC database from assets", e);
        }
    }

    /**
     * Retrieve the description for a DTC code, or null if not found.
     */
    public static String lookup(String code) {
        if (code == null) return null;
        return cache.get(code.toUpperCase());
    }
}
