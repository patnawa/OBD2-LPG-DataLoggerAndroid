package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists a user-supplied VIN for vehicles that do not answer Mode 09 PID 02.
 *
 * <p>Plenty of vehicles — pre-2005 models, most non-US-market ECUs, and a fair
 * number of LPG conversions — simply never implement Mode 09. Without a VIN the
 * app loses brand detection, the brand DTC database, per-vehicle PID caching and
 * per-vehicle log folders, so every session lands in the "General" bucket.
 *
 * <p>Storing the VIN the user types once fixes all of those consumers, and the
 * stored value is reused on later sessions so the vehicle never has to be
 * identified twice.
 */
public final class ManualVinStore {

    private static final String PREFS = "OBD2Prefs";
    private static final String KEY_MANUAL_VIN = "pref_manual_vin";

    private ManualVinStore() {
    }

    /**
     * @return the stored VIN, or null if none has been entered or the stored
     *         value is no longer structurally valid.
     */
    public static String get(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String vin = prefs.getString(KEY_MANUAL_VIN, null);
        return isUsable(vin) ? vin : null;
    }

    /**
     * Store a user-entered VIN. Passing null or an invalid VIN clears the entry.
     *
     * @return the normalized VIN that was stored, or null if it was cleared.
     */
    public static String set(Context context, String vin) {
        if (context == null) return null;
        String normalized = normalize(vin);
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (normalized == null) {
            editor.remove(KEY_MANUAL_VIN).apply();
            return null;
        }
        editor.putString(KEY_MANUAL_VIN, normalized).apply();
        return normalized;
    }

    /**
     * Uppercase and strip separators/whitespace, then validate against ISO 3779.
     *
     * @return the normalized VIN, or null if it is not a structurally valid VIN.
     */
    public static String normalize(String vin) {
        if (vin == null) return null;
        String cleaned = vin.trim().toUpperCase(java.util.Locale.US)
                .replaceAll("[\\s\\-_]", "");
        return VinBrandDetector.isStructurallyValid(cleaned) ? cleaned : null;
    }

    private static boolean isUsable(String vin) {
        return vin != null && VinBrandDetector.isStructurallyValid(vin);
    }
}
