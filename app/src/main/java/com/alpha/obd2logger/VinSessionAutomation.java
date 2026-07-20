package com.alpha.obd2logger;

import android.content.SharedPreferences;

/**
 * Applies VIN-derived feature defaults to a session before its first scan and
 * poll-set snapshot. Durable preferences and UI state remain owned by
 * MainActivity; this synchronous policy closes the worker/UI callback race.
 */
final class VinSessionAutomation {

    private VinSessionAutomation() {
    }

    static void applyFromPreferences(LoggerConfig config, String vin,
                                     SharedPreferences preferences,
                                     boolean provenForCurrentConnection) {
        if (preferences == null || !provenForCurrentConnection) return;
        String handledKey = FordMsCanAutoMigration.handledKey(vin);
        String userOverrideKey = FordMsCanAutoMigration.userOverrideKey(vin);
        boolean smartSecondaryBusScanEnabled =
                SmartSecondaryBusScanPreferences.getEnabled(preferences);
        if (config != null && !smartSecondaryBusScanEnabled) {
            // The new setting is a global kill switch. In particular, a
            // conservatively migrated legacy false must not be re-enabled by
            // a later VIN-derived Ford default on the worker thread.
            config.fordMsCanEnabled = false;
        }
        boolean fordHandled = handledKey != null
                && preferences.getBoolean(handledKey, false);
        boolean explicitFordChoice = !smartSecondaryBusScanEnabled
                || preferences.getBoolean(
                        FordMsCanAutoMigration.GLOBAL_USER_OVERRIDE_KEY, false)
                || (userOverrideKey != null
                && preferences.getBoolean(userOverrideKey, false));
        applyForConnection(config, vin, true,
                fordHandled, explicitFordChoice,
                preferences.getBoolean("pref_auto_detect_done", false));
    }

    static void applyForConnection(LoggerConfig config, String vin,
                                   boolean provenForCurrentConnection,
                                   boolean fordMigrationHandled,
                                   boolean explicitFordChoice,
                                   boolean dieselAutoDetectHandled) {
        if (!provenForCurrentConnection) return;
        apply(config, vin, fordMigrationHandled, explicitFordChoice,
                dieselAutoDetectHandled);
    }

    static void apply(LoggerConfig config, String vin,
                      boolean fordMigrationHandled,
                      boolean explicitFordChoice,
                      boolean dieselAutoDetectHandled) {
        String normalizedVin = ManualVinStore.normalize(vin);
        if (config == null || normalizedVin == null) return;

        FordMsCanAutoMigration.Decision fordDecision =
                FordMsCanAutoMigration.evaluate(
                        VinBrandDetector.detect(normalizedVin), normalizedVin,
                        true, fordMigrationHandled, explicitFordChoice,
                        config.fordMsCanEnabled);
        if (fordDecision.enableMsCan) {
            config.fordMsCanEnabled = true;
        }

        // The legacy UI policy auto-enables DPF once for common Thai-market
        // diesel WMIs. Set the session flag before PIDCatalogue snapshots its
        // list; the UI callback later persists the same decision and latch.
        if (!dieselAutoDetectHandled && isDieselVin(normalizedVin)) {
            config.dpfMonitorEnabled = true;
        }
    }

    static boolean isDieselVin(String vin) {
        String normalizedVin = ManualVinStore.normalize(vin);
        if (normalizedVin == null) return false;
        String wmi = normalizedVin.substring(0, 3);
        return "MPA".equals(wmi) || "MNB".equals(wmi)
                || "MMB".equals(wmi) || "MR0".equals(wmi);
    }
}
