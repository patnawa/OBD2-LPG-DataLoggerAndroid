package com.alpha.obd2logger;

import android.content.Context;

import org.json.JSONObject;

/**
 * Disk persistence for the learned VE surface, following the
 * {@link CommonVehicleDataStore} SharedPreferences + versioned-JSON pattern.
 *
 * <p>Without this, {@link VeMapStore} was purely in-memory: a process death or
 * reboot wiped weeks of learned cells, and the ΔVE trend feature has nothing
 * to trend. Load happens once when a session's store is created; save happens
 * at session teardown (both the background-service and in-process paths).
 *
 * <p>v1 uses a single global slot — the app is effectively single-vehicle per
 * install. The payload records nothing VIN-specific; if multi-vehicle keying
 * is ever needed, add a keyed slot like CommonVehicleDataStore's VIN prefix.
 */
public final class VeMapPersistence {
    private static final String PREFS = "OBD2Prefs";
    private static final String KEY = "ve_map_surface_v1";

    private VeMapPersistence() {
    }

    /** Persist the store's current surface. Safe no-op on null/empty state. */
    public static void save(Context context, VeMapStore store) {
        if (context == null || store == null) return;
        if (store.getPetrolData().isEmpty() && store.getLpgData().isEmpty()) return;
        JSONObject json = store.exportToJson();
        if (json == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, json.toString())
                .apply();
    }

    /**
     * Restore the persisted surface into {@code store}.
     * @return true when a surface was restored
     */
    public static boolean load(Context context, VeMapStore store) {
        if (context == null || store == null) return false;
        String raw = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null);
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            return store.importFromJson(new JSONObject(raw));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Remove the persisted surface (mirrors a full store clear). */
    public static void clear(Context context) {
        if (context == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY)
                .apply();
    }
}
