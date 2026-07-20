package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VinSessionAutomationTest {

    private static final String FORD_MNB = "MNBUMFF50FW123456";
    private static final String TOYOTA_MR0 = "MR0FZ29G901234567";

    @Test
    public void newMnbSessionEnablesFordAndDpfBeforeFirstSnapshots() {
        LoggerConfig config = new LoggerConfig();

        VinSessionAutomation.apply(config, FORD_MNB,
                false, false, false);

        assertTrue(config.fordMsCanEnabled);
        assertTrue(config.dpfMonitorEnabled);
    }

    @Test
    public void priorUserAndDieselDecisionsAreNotOverridden() {
        LoggerConfig config = new LoggerConfig();

        VinSessionAutomation.apply(config, FORD_MNB,
                true, true, true);

        assertFalse(config.fordMsCanEnabled);
        assertFalse(config.dpfMonitorEnabled);
    }

    @Test
    public void toyotaDieselGetsDpfButNeverFordMsCan() {
        LoggerConfig config = new LoggerConfig();

        VinSessionAutomation.apply(config, TOYOTA_MR0,
                false, false, false);

        assertFalse(config.fordMsCanEnabled);
        assertTrue(config.dpfMonitorEnabled);
    }

    @Test
    public void staleRememberedManualVinCannotConfigureAnotherConnection() {
        LoggerConfig config = new LoggerConfig();

        VinSessionAutomation.applyForConnection(config, FORD_MNB,
                false, false, false, false);

        assertFalse(config.fordMsCanEnabled);
        assertFalse(config.dpfMonitorEnabled);
    }
}
