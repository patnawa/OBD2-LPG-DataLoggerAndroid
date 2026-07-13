package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Versioned cache for capability data measured from a specific vehicle. */
public final class PidSupportCache {
    private static final String PREFS = "OBD2Prefs";
    private static final String PREFIX = "pid_caps_v2_";
    private static final long MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;

    private PidSupportCache() {}

    public static List<String> get(Context context, String vin) {
        String key = keyFor(vin);
        if (context == null || key == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long savedAt = prefs.getLong(key + "_time", 0L);
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > MAX_AGE_MS) return null;
        String csv = prefs.getString(key, null);
        if (csv == null || csv.trim().isEmpty()) return null;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : csv.split(",")) {
            String pid = value.trim().toUpperCase(Locale.US);
            if (pid.matches("[0-9A-F]{2}")) unique.add(pid);
        }
        return unique.isEmpty() ? null : new ArrayList<>(unique);
    }

    public static void put(Context context, String vin, List<String> pids) {
        String key = keyFor(vin);
        if (context == null || key == null || pids == null || pids.isEmpty()) return;
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String value : pids) {
            if (value == null) continue;
            String pid = value.trim().toUpperCase(Locale.US);
            if (pid.matches("[0-9A-F]{2}")) clean.add(pid);
        }
        if (clean.isEmpty()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(key, android.text.TextUtils.join(",", clean))
                .putLong(key + "_time", System.currentTimeMillis())
                .apply();
    }

    private static String keyFor(String vin) {
        if (!VinBrandDetector.isStructurallyValid(vin)) return null;
        return PREFIX + vin.trim().toUpperCase(Locale.US);
    }
}
