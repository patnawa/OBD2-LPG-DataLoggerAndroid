package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FordMsCanAutoMigrationTest {
    private static final String MNB_VIN = "MNBUMFF50FW123456";

    @Test
    public void upgradedMnbIgnoresLegacyGlobalDieselLatch() {
        // The old global pref_auto_detect_done is intentionally absent from
        // this policy: a newly recognized Ford VIN gets its own v2 state.
        FordMsCanAutoMigration.Decision decision = FordMsCanAutoMigration.evaluate(
                VinBrandDetector.Brand.FORD, MNB_VIN,
                true, false, false, false);

        assertTrue(decision.markHandled);
        assertTrue(decision.enableMsCan);
    }

    @Test
    public void explicitOptOutIsMarkedHandledWithoutBeingOverridden() {
        FordMsCanAutoMigration.Decision decision = FordMsCanAutoMigration.evaluate(
                VinBrandDetector.Brand.FORD, MNB_VIN,
                true, false, true, false);

        assertTrue(decision.markHandled);
        assertFalse(decision.enableMsCan);
    }

    @Test
    public void handledVinIsNeverReenabled() {
        FordMsCanAutoMigration.Decision decision = FordMsCanAutoMigration.evaluate(
                VinBrandDetector.Brand.FORD, MNB_VIN,
                true, true, false, false);

        assertFalse(decision.markHandled);
        assertFalse(decision.enableMsCan);
    }

    @Test
    public void stateKeysArePerVin() {
        String otherFordVin = "MNBUMFF50FW654321";
        assertFalse(FordMsCanAutoMigration.handledKey(MNB_VIN)
                .equals(FordMsCanAutoMigration.handledKey(otherFordVin)));
    }

    @Test
    public void inProcessRoutingRequiresVinProvenForCurrentConnection() {
        assertTrue(FordMsCanAutoMigration.matchesVerifiedVin(
                MNB_VIN.toLowerCase(java.util.Locale.US), MNB_VIN));
        assertFalse(FordMsCanAutoMigration.matchesVerifiedVin(
                MNB_VIN, "MNBUMFF50FW654321"));
        assertFalse(FordMsCanAutoMigration.matchesVerifiedVin(MNB_VIN, null));
    }

    @Test
    public void migrationAndUserChangesSynchronizeBothLiveConfigs() {
        LoggerConfig inProcessConfig = new LoggerConfig();
        LoggerConfig serviceConfig = new LoggerConfig();

        FordMsCanAutoMigration.synchronizeActiveConfigs(
                true, inProcessConfig, serviceConfig);

        assertTrue(inProcessConfig.fordMsCanEnabled);
        assertTrue(serviceConfig.fordMsCanEnabled);

        FordMsCanAutoMigration.synchronizeActiveConfigs(
                false, inProcessConfig, serviceConfig);

        assertFalse(inProcessConfig.fordMsCanEnabled);
        assertFalse(serviceConfig.fordMsCanEnabled);
    }

    @Test
    public void configSynchronizationIsNullSafe() {
        LoggerConfig serviceConfig = new LoggerConfig();

        FordMsCanAutoMigration.synchronizeActiveConfigs(
                true, null, serviceConfig);

        assertTrue(serviceConfig.fordMsCanEnabled);
        FordMsCanAutoMigration.synchronizeActiveConfigs(false, null, null);
    }
}
