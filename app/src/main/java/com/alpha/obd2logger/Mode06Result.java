package com.alpha.obd2logger;

/**
 * Represents a single Mode 06 (On-Board Monitor) test result.
 *
 * Each result contains:
 *   - OBDMID: which monitor (e.g. 0x05 = Catalyst Bank 1)
 *   - TID:    which test within that monitor (e.g. 0x0A = Catalyst Conversion)
 *   - UASID:  unit-and-scaling ID (determines how to interpret raw value)
 *   - rawValue, rawMin, rawMax: the unscaled 2-byte values
 *   - scaledValue, scaledMin, scaledMax: human-readable values after applying UAS
 *   - unit: the engineering unit string (%, ratio, kPa, etc.)
 *   - passed: whether scaledMin <= scaledValue <= scaledMax
 */
public final class Mode06Result {

    private final int obdMid;   // On-Board Diagnostic Monitor ID
    private final int tid;      // Test ID
    private final int uasId;    // Unit and Scaling ID

    private final int rawValue; // 2-byte test value
    private final int rawMin;   // 2-byte min limit
    private final int rawMax;   // 2-byte max limit

    private final double scaledValue;
    private final double scaledMin;
    private final double scaledMax;
    private final String unit;
    private final boolean passed;

    public Mode06Result(int obdMid, int tid, int uasId,
                        int rawValue, int rawMin, int rawMax,
                        double scaledValue, double scaledMin, double scaledMax,
                        String unit, boolean passed) {
        this.obdMid = obdMid;
        this.tid = tid;
        this.uasId = uasId;
        this.rawValue = rawValue;
        this.rawMin = rawMin;
        this.rawMax = rawMax;
        this.scaledValue = scaledValue;
        this.scaledMin = scaledMin;
        this.scaledMax = scaledMax;
        this.unit = unit;
        this.passed = passed;
    }

    public int getObdMid() { return obdMid; }
    public int getTid() { return tid; }
    public int getUasId() { return uasId; }
    public int getRawValue() { return rawValue; }
    public int getRawMin() { return rawMin; }
    public int getRawMax() { return rawMax; }
    public double getScaledValue() { return scaledValue; }
    public double getScaledMin() { return scaledMin; }
    public double getScaledMax() { return scaledMax; }
    public String getUnit() { return unit; }
    public boolean isPassed() { return passed; }

    /**
     * Human-readable monitor name from OBDMID.
     */
    public String getMonitorName() {
        return MonitorNames.get(obdMid);
    }

    /**
     * Human-readable test name from TID (within context of the MID).
     */
    public String getTestName() {
        return TestNames.get(tid, obdMid);
    }

    @Override
    public String toString() {
        String monitor = getMonitorName();
        String test = getTestName();
        String status = passed ? "PASS" : "FAIL";
        if (unit != null && !unit.isEmpty()) {
            return String.format(java.util.Locale.US,
                    "[%s] %s — %s: %.2f %s (%.2f ~ %.2f) %s",
                    status, monitor, test, scaledValue, unit, scaledMin, scaledMax, status);
        }
        return String.format(java.util.Locale.US,
                "[%s] %s — %s: %.2f (%.2f ~ %.2f) %s",
                status, monitor, test, scaledValue, scaledMin, scaledMax, status);
    }

    // ============================================================
    // Monitor name lookup (OBDMID -> name)
    // Based on SAE J1979 / ISO 15031-5 tables
    // ============================================================
    public static final class MonitorNames {
        public static String get(int mid) {
            switch (mid) {
                // O2 Sensors — Bank 1
                case 0x01: return "O2 Sensor B1S1";
                case 0x02: return "O2 Sensor B1S2";
                case 0x03: return "O2 Sensor B1S3";
                case 0x04: return "O2 Sensor B1S4";
                // O2 Sensors — Bank 2
                case 0x05: return "O2 Sensor B2S1";
                case 0x06: return "O2 Sensor B2S2";
                case 0x07: return "O2 Sensor B2S3";
                case 0x08: return "O2 Sensor B2S4";
                // Catalyst — Bank 1
                case 0x21: return "Catalyst B1";
                case 0x22: return "Catalyst B2";
                // EGR
                case 0x31: return "EGR System";
                case 0x32: return "EGR/VVT B2";
                // EVAP
                case 0x3E: return "EVAP 0.020\"";
                case 0x3F: return "EVAP 0.040\"";
                // Boost Pressure
                case 0xA0: return "Boost Pressure A";
                // O2 Sensor Heater — Bank 1
                case 0x09: return "O2 Heater B1S1";
                case 0x0A: return "O2 Heater B1S2";
                case 0x0B: return "O2 Heater B1S3";
                case 0x0C: return "O2 Heater B1S4";
                // O2 Sensor Heater — Bank 2
                case 0x0D: return "O2 Heater B2S1";
                case 0x0E: return "O2 Heater B2S2";
                case 0x0F: return "O2 Heater B2S3";
                case 0x10: return "O2 Heater B2S4";
                // Misfire — General and Cylinder
                case 0x50: return "Misfire — General";
                case 0x51: return "Misfire Cyl 1";
                case 0x52: return "Misfire Cyl 2";
                case 0x53: return "Misfire Cyl 3";
                case 0x54: return "Misfire Cyl 4";
                case 0x55: return "Misfire Cyl 5";
                case 0x56: return "Misfire Cyl 6";
                case 0x57: return "Misfire Cyl 7";
                case 0x58: return "Misfire Cyl 8";
                case 0x59: return "Misfire Cyl 9";
                case 0x5A: return "Misfire Cyl 10";
                case 0x5B: return "Misfire Cyl 11";
                case 0x5C: return "Misfire Cyl 12";
                // PM Filter
                case 0x80: return "PM Filter B1";
                case 0x81: return "PM Filter B2";
                // Fuel System
                case 0x60: return "Fuel System B1";
                case 0x61: return "Fuel System B2";
                // NOx Catalyst / SCR
                case 0xA1: return "NOx Catalyst";
                default:
                    return String.format(java.util.Locale.US, "Monitor 0x%02X", mid);
            }
        }
    }

    // ============================================================
    // Test name lookup (TID + context MID -> name)
    // ============================================================
    public static final class TestNames {
        public static String get(int tid, int mid) {
            // TID meaning depends on monitor type. We use context from mid
            // to select the right table.
            int midGroup = classifyMid(mid);

            switch (midGroup) {
                case GROUP_O2_SENSOR:
                    return o2TestName(tid);
                case GROUP_O2_HEATER:
                    return o2HeaterTestName(tid);
                case GROUP_CATALYST:
                    return catalystTestName(tid);
                case GROUP_EGR:
                    return egrTestName(tid);
                case GROUP_EVAP:
                    return evapTestName(tid);
                case GROUP_MISFIRE:
                    return misfireTestName(tid);
                case GROUP_FUEL:
                    return fuelTestName(tid);
                default:
                    return genericTestName(tid);
            }
        }

        // Mid grouping constants
        private static final int GROUP_O2_SENSOR = 1;
        private static final int GROUP_O2_HEATER = 2;
        private static final int GROUP_CATALYST = 3;
        private static final int GROUP_EGR = 4;
        private static final int GROUP_EVAP = 5;
        private static final int GROUP_MISFIRE = 6;
        private static final int GROUP_FUEL = 7;
        private static final int GROUP_OTHER = 0;

        private static int classifyMid(int mid) {
            if (mid >= 0x01 && mid <= 0x08) return GROUP_O2_SENSOR;
            if (mid >= 0x09 && mid <= 0x10) return GROUP_O2_HEATER;
            if (mid == 0x21 || mid == 0x22) return GROUP_CATALYST;
            if (mid == 0x31 || mid == 0x32) return GROUP_EGR;
            if (mid >= 0x3E && mid <= 0x3F) return GROUP_EVAP;
            if (mid >= 0x50 && mid <= 0x5C) return GROUP_MISFIRE;
            if (mid == 0x60 || mid == 0x61) return GROUP_FUEL;
            return GROUP_OTHER;
        }

        private static String o2TestName(int tid) {
            switch (tid) {
                case 0x0A: return "Rich-to-Lean Sensor Threshold";
                case 0x0B: return "Lean-to-Rich Sensor Threshold";
                case 0x0C: return "Low Voltage Switch Time";
                case 0x0D: return "High Voltage Switch Time";
                case 0x0E: return "Rich-to-Lean Transition Time";
                case 0x0F: return "Lean-to-Rich Transition Time";
                case 0x10: return "Minimum Sensor Voltage";
                case 0x11: return "Maximum Sensor Voltage";
                case 0x12: return "Transition Time";
                default:  return genericTestName(tid);
            }
        }

        private static String o2HeaterTestName(int tid) {
            switch (tid) {
                case 0x0A: return "Sensor Heater Resistance";
                case 0x0B: return "Sensor Heater Current";
                default:  return genericTestName(tid);
            }
        }

        private static String catalystTestName(int tid) {
            switch (tid) {
                case 0x0A: return "Catalyst Conversion Efficiency";
                case 0x0B: return "O2 Storage Capacity";
                case 0x0C: return "Catalyst Monitor Completion";
                default:  return genericTestName(tid);
            }
        }

        private static String egrTestName(int tid) {
            switch (tid) {
                case 0x0A: return "EGR Flow Rate";
                case 0x0B: return "EGR Error (Closed)";
                case 0x0C: return "EGR Duty Cycle";
                case 0x0D: return "EGR Temperature";
                default:  return genericTestName(tid);
            }
        }

        private static String evapTestName(int tid) {
            switch (tid) {
                case 0x0A: return "EVAP Vapor Pressure";
                case 0x0B: return "EVAP Vapor Pressure (Purge)";
                case 0x0C: return "EVAP Leak Detection";
                case 0x10: return "EVAP Small Leak Test";
                default:  return genericTestName(tid);
            }
        }

        private static String misfireTestName(int tid) {
            switch (tid) {
                case 0x0A: return "Misfire Count (Current)";
                case 0x0B: return "Misfire Count (Last 10 drive cycles)";
                case 0x0C: return "Misfire RPM Range";
                case 0x0D: return "Misfire Load Range";
                default:  return genericTestName(tid);
            }
        }

        private static String fuelTestName(int tid) {
            switch (tid) {
                case 0x0A: return "Fuel Trim Correction";
                case 0x0B: return "Fuel System Rich/Lean";
                default:  return genericTestName(tid);
            }
        }

        private static String genericTestName(int tid) {
            return String.format(java.util.Locale.US, "Test 0x%02X", tid);
        }
    }
}
