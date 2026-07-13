package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoggerConfigTest {

    @Test
    public void detectedVinBrandReplacesAutomaticPlaceholder() {
        LoggerConfig config = new LoggerConfig();

        assertTrue(config.applyDetectedVehicleBrand("Honda"));
        assertEquals("Honda", config.vehicleBrand);
    }

    @Test
    public void detectedVinBrandNeverOverridesExplicitSelection() {
        LoggerConfig config = new LoggerConfig();
        config.vehicleBrand = "Toyota";

        assertFalse(config.applyDetectedVehicleBrand("Honda"));
        assertEquals("Toyota", config.vehicleBrand);
    }

    @Test
    public void unknownDetectedBrandDoesNotReplaceAuto() {
        LoggerConfig config = new LoggerConfig();

        assertFalse(config.applyDetectedVehicleBrand("Unknown"));
        assertEquals("auto", config.vehicleBrand);
    }
}
