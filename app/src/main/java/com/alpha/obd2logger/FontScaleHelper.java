package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Per-app font scale control for Android 6 (API 23) through 16 (API 36+).
 *
 * Lets the user pick a font size preset (Small 0.85x, Normal 1.0x, Large 1.15x,
 * Extra Large 1.3x) independent of the Android system font setting. The chosen
 * scale is applied in {@link MainActivity#attachBaseContext} via
 * {@link #wrap(Context)} and takes effect immediately on {@code recreate()}.
 *
 * SharedPreferences key: "font_size_index" (int 0-3) in "OBD2Prefs".
 * Default: 1 (Normal 1.0x) — matches pre-existing behavior.
 */
public final class FontScaleHelper {

    public static final int SIZE_SMALL  = 0; // 0.85x
    public static final int SIZE_NORMAL = 1; // 1.0x  (default)
    public static final int SIZE_LARGE  = 2; // 1.15x
    public static final int SIZE_XLARGE = 3; // 1.3x

    private static final String PREF_NAME  = "OBD2Prefs";
    private static final String PREF_KEY    = "font_size_index";
    private static final float[] SCALES = { 0.85f, 1.0f, 1.15f, 1.3f };

    private FontScaleHelper() {}

    /** Returns the saved font-size preset index (0-3). Default 1 = Normal. */
    public static int getSavedIndex(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_KEY, SIZE_NORMAL);
    }

    /** Returns the float scale corresponding to the saved preset. */
    public static float getSavedScale(Context context) {
        return indexToScale(getSavedIndex(context));
    }

    /** Persists a new preset index. Call {@code recreate()} afterwards. */
    public static void saveIndex(Context context, int index) {
        if (index < 0 || index >= SCALES.length) index = SIZE_NORMAL;
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(PREF_KEY, index).apply();
    }

    /** Converts a preset index to its float scale. */
    public static float indexToScale(int index) {
        if (index < 0 || index >= SCALES.length) return 1.0f;
        return SCALES[index];
    }

    /**
     * Wraps the given context with a {@link Configuration} whose fontScale
     * is set to the user's saved preset. Call from {@code attachBaseContext}.
     */
    public static Context wrap(Context context) {
        float scale = getSavedScale(context);
        if (scale == 1.0f) return context; // no-op for default

        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.fontScale = scale;
        return context.createConfigurationContext(configuration);
    }
}
