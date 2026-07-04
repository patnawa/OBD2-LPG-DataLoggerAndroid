package com.alpha.obd2logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads Freeze Frame (Mode 02) data for key engine parameters.
 */
public final class FreezeFrameReader {

    private static final String[] FREEZE_FRAME_PIDS = {
        "0C", // Engine RPM
        "0D", // Vehicle Speed
        "05", // Engine Coolant Temp
        "04", // Calculated Engine Load
        "06", // Short Term Fuel Trim Bank 1
        "07", // Long Term Fuel Trim Bank 1
        "0B", // Intake Manifold Absolute Pressure
        "0F"  // Intake Air Temp
    };

    private FreezeFrameReader() {
    }

    /**
     * Read key sensor values at the time the fault occurred (Mode 02, frame 00).
     */
    public static FreezeFrameData readFreezeFrame(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return null;
        }

        Map<String, Double> values = new HashMap<>();

        for (String pidHex : FREEZE_FRAME_PIDS) {
            PIDDefinition pidDef = PIDCatalogue.getAll().stream()
                    .filter(p -> p.getPidHex().equalsIgnoreCase(pidHex))
                    .findFirst()
                    .orElse(null);

            if (pidDef != null) {
                // Command: 02 + pidHex + 00 (frame 0)
                String response = driver.sendCommandRaw("02" + pidHex + "00");
                if (response != null && !response.isEmpty()) {
                    // Expected response header: 42 + pidHex
                    Double value = PIDParser.extractAndParse(pidDef, response, "42" + pidHex);
                    if (value != null) {
                        values.put(pidHex, value);
                    }
                }
            }
        }

        return new FreezeFrameData(values, System.currentTimeMillis());
    }
}
