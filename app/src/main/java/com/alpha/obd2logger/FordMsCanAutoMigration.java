package com.alpha.obd2logger;

/**
 * Compatibility migration for the former Ford MS-CAN preference keys.
 * The migrated value now controls Smart scan expansion only; it does not grant
 * or infer physical secondary-bus routing capability.
 */
final class FordMsCanAutoMigration {

    static final String GLOBAL_USER_OVERRIDE_KEY =
            "pref_ford_ms_can_user_override_v2";
    private static final String HANDLED_PREFIX =
            "pref_ford_ms_can_auto_v2_handled_";
    private static final String USER_OVERRIDE_PREFIX =
            "pref_ford_ms_can_user_override_v2_";

    private FordMsCanAutoMigration() {
    }

    static Decision evaluate(VinBrandDetector.Brand brand, String vin,
                             boolean checkboxAvailable, boolean handledForVin,
                             boolean explicitUserChoice, boolean currentlyEnabled) {
        String normalizedVin = ManualVinStore.normalize(vin);
        if (brand != VinBrandDetector.Brand.FORD || normalizedVin == null
                || !checkboxAvailable || handledForVin) {
            return Decision.NONE;
        }
        // Mark the Ford VIN handled even when the user explicitly opted out or
        // the compatibility setting was already enabled. This prevents later VIN callbacks from
        // undoing a deliberate choice.
        return new Decision(true, !explicitUserChoice && !currentlyEnabled);
    }

    static String handledKey(String vin) {
        String normalizedVin = ManualVinStore.normalize(vin);
        return normalizedVin != null ? HANDLED_PREFIX + normalizedVin : null;
    }

    static String userOverrideKey(String vin) {
        String normalizedVin = ManualVinStore.normalize(vin);
        return normalizedVin != null ? USER_OVERRIDE_PREFIX + normalizedVin : null;
    }

    /** True only when a routed VIN is the VIN proven for the live connection. */
    static boolean matchesVerifiedVin(String routedVin, String verifiedVin) {
        String routed = ManualVinStore.normalize(routedVin);
        String verified = ManualVinStore.normalize(verifiedVin);
        return routed != null && routed.equals(verified);
    }

    /**
     * Propagate a UI/migration choice into the LoggerConfig objects that own
     * the current sessions. Mutating the existing instances preserves their
     * lifecycle identity while making the setting visible immediately.
     */
    static void synchronizeActiveConfigs(boolean enabled,
                                         LoggerConfig inProcessConfig,
                                         LoggerConfig serviceConfig) {
        if (inProcessConfig != null) {
            inProcessConfig.fordMsCanEnabled = enabled;
        }
        if (serviceConfig != null) {
            serviceConfig.fordMsCanEnabled = enabled;
        }
    }

    static final class Decision {
        static final Decision NONE = new Decision(false, false);

        final boolean markHandled;
        final boolean enableMsCan;

        Decision(boolean markHandled, boolean enableMsCan) {
            this.markHandled = markHandled;
            this.enableMsCan = enableMsCan;
        }
    }
}
