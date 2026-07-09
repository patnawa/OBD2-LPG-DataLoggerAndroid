package com.alpha.obd2logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OBD2 Mode 08 — Bi-Directional Control of On-Board Systems.
 *
 * Mode 08 allows the scanner to command the ECU to activate/deactivate
 * specific actuators and run on-board tests. This is a key feature of
 * professional-grade scanners that basic OBD2 code readers lack.
 *
 * Common Mode 08 tests:
 *   - EVAP canister purge valve
 *   - EGR valve
 *   - Radiator fan relay
 *   - Fuel pump relay
 *   - Engine cooling fan
 *   - Fuel injector
 *   - Ignition coil
 *
 * NOTE: Mode 08 support varies by manufacturer and ECU. Not all vehicles
 * support all tests. The ELM327 sends "08XX" where XX is the Test ID (TID).
 *
 * WARNING: Some Mode 08 commands activate physical components (fuel pump,
 * radiator fan, etc.). Only run tests when safe to do so.
 */
public final class Mode08Controller {

    private Mode08Controller() {}

    /**
     * Known Mode 08 Test IDs (TIDs) for common bi-directional tests.
     * These are SAE J2190 standard TIDs; manufacturers may define additional ones.
     */
    public static final class TestId {
        public final String tid;
        public final String name;
        public final String description;

        public TestId(String tid, String name, String description) {
            this.tid = tid;
            this.name = name;
            this.description = description;
        }
    }

    /**
     * Standard SAE J2190 Mode 08 tests.
     */
    public static final Map<String, TestId> STANDARD_TESTS = new LinkedHashMap<>();
    static {
        STANDARD_TESTS.put("01", new TestId("01", "EVAP Purge Valve", "Commands EVAP canister purge solenoid on/off"));
        STANDARD_TESTS.put("02", new TestId("02", "EGR Valve", "Commands EGR valve to specified position"));
        STANDARD_TESTS.put("03", new TestId("03", "Radiator Fan Relay", "Commands engine cooling fan on/off"));
        STANDARD_TESTS.put("04", new TestId("04", "Fuel Pump Relay", "Commands fuel pump relay on/off (WARNING: fuel will flow)"));
        STANDARD_TESTS.put("05", new TestId("05", "Malfunction Indicator Lamp", "Commands MIL on/off for bulb check"));
        STANDARD_TESTS.put("06", new TestId("06", "Check Engine Lamp", "Commands check engine indicator"));
        STANDARD_TESTS.put("07", new TestId("07", "A/C Clutch Relay", "Commands A/C compressor clutch on/off"));
        STANDARD_TESTS.put("08", new TestId("08", "Engine Speed Control", "Commands engine to specified RPM"));
        STANDARD_TESTS.put("09", new TestId("09", "Fuel Injector Balance", "Commands individual fuel injector for balance test"));
        STANDARD_TESTS.put("0A", new TestId("0A", "Ignition Coil Test", "Commands individual ignition coil for output test"));
        STANDARD_TESTS.put("0B", new TestId("0B", "Idle Air Control", "Commands IAC valve to specified position"));
        STANDARD_TESTS.put("0C", new TestId("0C", "Torque Converter Lockup", "Commands torque converter clutch on/off"));
        STANDARD_TESTS.put("0D", new TestId("0D", "Glow Plug Relay", "Commands glow plug relay on/off (diesel)"));
        STANDARD_TESTS.put("0E", new TestId("0E", "Intake Manifold Heater", "Commands intake air heater on/off (diesel)"));
    }

    /**
     * Run a bi-directional test.
     *
     * @param driver  connected OBD2 driver
     * @param tidHex  2-hex-digit Test ID (e.g. "01" for EVAP purge)
     * @return true if the ECU acknowledged the command
     */
    public static boolean runTest(BaseDriver driver, String tidHex) {
        if (driver == null || !driver.isConnected()) return false;
        String response = driver.sendCommandRaw("08" + tidHex);
        if (response == null || response.isEmpty()) return false;
        // Positive response = "48" + tidHex
        String hex = response.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        return hex.contains("48" + tidHex.toUpperCase());
    }

    /**
     * Turn a test component on or off.
     * Some tests accept a control value (e.g. EGR position percentage).
     *
     * @param driver      connected OBD2 driver
     * @param tidHex      Test ID
     * @param controlByte single byte hex (00=off, FF=on, or position 00-FF)
     * @return true if acknowledged
     */
    public static boolean runTestWithValue(BaseDriver driver, String tidHex, String controlByte) {
        if (driver == null || !driver.isConnected()) return false;
        String response = driver.sendCommandRaw("08" + tidHex + controlByte);
        if (response == null || response.isEmpty()) return false;
        String hex = response.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        return hex.contains("48" + tidHex.toUpperCase());
    }

    /**
     * Stop/cancel all active Mode 08 tests.
     * Send Mode 08 TID 00 to cancel all running tests.
     */
    public static boolean cancelAllTests(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) return false;
        String response = driver.sendCommandRaw("0800");
        if (response == null || response.isEmpty()) return false;
        String hex = response.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        return hex.contains("4800");
    }

    /**
     * Get the list of supported Mode 08 tests for this vehicle.
     * Sends "0800" which returns supported TIDs in some vehicles.
     */
    public static java.util.List<String> querySupportedTests(BaseDriver driver) {
        java.util.List<String> supported = new java.util.ArrayList<>();
        if (driver == null || !driver.isConnected()) return supported;

        String response = driver.sendCommandRaw("0800");
        if (response == null || response.isEmpty()) return supported;

        String hex = response.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        int idx = hex.indexOf("4800");
        if (idx < 0) return supported;

        // Parse supported TID bitmap (each bit = one TID)
        String data = hex.substring(idx + 4);
        if (data.length() >= 2) {
            try {
                int bitmap = Integer.parseInt(data.substring(0, 2), 16);
                for (int bit = 0; bit < 8; bit++) {
                    if ((bitmap & (1 << bit)) != 0) {
                        supported.add(String.format("%02X", bit + 1));
                    }
                }
            } catch (NumberFormatException ignored) {}
        }
        return supported;
    }

    /**
     * Get the test name for a TID.
     */
    public static String getTestName(String tidHex) {
        TestId test = STANDARD_TESTS.get(tidHex != null ? tidHex.toUpperCase() : null);
        return test != null ? test.name : "Unknown Test (TID " + tidHex + ")";
    }

    /**
     * Get the description for a TID.
     */
    public static String getTestDescription(String tidHex) {
        TestId test = STANDARD_TESTS.get(tidHex != null ? tidHex.toUpperCase() : null);
        return test != null ? test.description : "No description available";
    }
}
