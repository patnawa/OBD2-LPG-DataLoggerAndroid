package com.alpha.obd2logger;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;

/**
 * Versioned preference bridge for the Smart secondary-bus scan setting.
 *
 * <p>The old UI stored the same underlying permission as
 * {@code pref_ford_ms_can}. Existing installs may have deliberately disabled
 * that value, so migration preserves any valid legacy boolean. A genuinely
 * new install defaults to Smart scan enabled. After migration the new key is
 * authoritative, while every write is mirrored to the legacy key until all
 * scan consumers have moved to the new name.</p>
 */
final class SmartSecondaryBusScanPreferences {

    static final String ENABLED_KEY = "pref_smart_secondary_bus_scan_v1";
    static final String MIGRATED_KEY = "pref_smart_secondary_bus_scan_migrated_v1";
    static final String LEGACY_ENABLED_KEY = "pref_ford_ms_can";
    static final boolean DEFAULT_ENABLED = true;

    private SmartSecondaryBusScanPreferences() {
    }

    /** Read, migrate and repair the mirrored legacy value when necessary. */
    static boolean getEnabled(SharedPreferences preferences) {
        if (preferences == null) return DEFAULT_ENABLED;

        Map<String, ?> values = preferences.getAll();
        if (values == null) values = Collections.emptyMap();
        Object smartValue = values.get(ENABLED_KEY);
        Object legacyValue = values.get(LEGACY_ENABLED_KEY);

        // Once present, the new key wins. Otherwise preserve a valid value
        // written by an older release. Invalid/corrupt types are treated like
        // an absent key and repaired to the safe new-install default.
        boolean enabled = smartValue instanceof Boolean
                ? (Boolean) smartValue
                : (legacyValue instanceof Boolean
                    ? (Boolean) legacyValue : DEFAULT_ENABLED);

        boolean synchronizedValues = Boolean.valueOf(enabled).equals(smartValue)
                && Boolean.valueOf(enabled).equals(legacyValue)
                && Boolean.TRUE.equals(values.get(MIGRATED_KEY));
        if (!synchronizedValues) {
            putEnabled(preferences.edit(), enabled).apply();
        }
        return enabled;
    }

    /** Persist an explicit choice to both the new and compatibility keys. */
    static void setEnabled(SharedPreferences preferences, boolean enabled) {
        if (preferences == null) return;
        putEnabled(preferences.edit(), enabled).apply();
    }

    /** Add the mirrored values to an existing atomic preference transaction. */
    static SharedPreferences.Editor putEnabled(
            SharedPreferences.Editor editor, boolean enabled) {
        if (editor == null) return null;
        return editor.putBoolean(ENABLED_KEY, enabled)
                .putBoolean(LEGACY_ENABLED_KEY, enabled)
                .putBoolean(MIGRATED_KEY, true);
    }
}
