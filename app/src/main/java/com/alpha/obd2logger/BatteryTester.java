package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Professional 12V Lead-Acid Battery & Charging System Tester.
 *
 * Uses OBD2 PID 0x42 (Control Module Voltage) for all measurements.
 * This is the ECU-reported battery voltage available on the OBD2 port —
 * it is slightly lower than the actual terminal voltage (cable drop, diode
 * loss in the adapter) but is the most convenient for continuous monitoring.
 *
 * Implemented tests (all professional-grade, SAE J1979 + battery-tester
 * industry-standard thresholds):
 *
 *  1. Battery State-of-Charge (SoC) — open-circuit voltage → SoC% lookup
 *  2. Battery Health Classification — Excellent / Good / Fair / Poor / Replace
 *  3. Alternator / Charging Voltage — regulated output check at idle
 *  4. Voltage Drop Test — electrical load test (headlights + blower + fan)
 *  5. Voltage Recovery Test — how fast voltage returns after load dump
 *  6. Cranking Voltage Test — minimum battery voltage during engine crank
 *  7. Ripple / Diode-Health Test — AC ripple detection (requires fast polling)
 *  8. Parasitic-Drain Estimate — voltage sag rate when engine off
 *  9. Charging System Efficiency — voltage vs. engine RPM curve
 *
 * Each test produces a {@link BatteryTestResult} with pass/fail/remark and
 * a numerical score (0-100). The overall {@link BatteryReport} combines all
 * individual tests into a single health grade.
 *
 * All thresholds are calibrated for standard flooded / AGM / Gel lead-acid
 * 12V systems (6 cells, 2.1 V/cell nominal). For LiFePO4 or 24V systems the
 * constants in {@link Thresholds} should be adjusted.
 */
public final class BatteryTester {

    private BatteryTester() {}

    // ─────────────────────────────────────────────────────────────
    //  Battery Chemistry Types
    // ─────────────────────────────────────────────────────────────

    /**
     * Supported battery chemistries. Each has its own resting-voltage SoC table,
     * charging profile, and expected service life.
     */
    public enum Chemistry {
        FLOODED  ("Flooded (Standard)",  12.65, 13.8, 14.7, 42, R.string.battery_chemistry_flooded),
        AGM      ("AGM (Absorbent Glass Mat)", 12.80, 14.0, 14.8, 54, R.string.battery_chemistry_agm),
        EFB      ("EFB (Enhanced Flooded)", 12.70, 13.8, 14.7, 48, R.string.battery_chemistry_efb),
        GEL      ("Gel Cell",            12.75, 13.8, 14.4, 48, R.string.battery_chemistry_gel),
        CALCIUM  ("Calcium (Ca/Ca)",     12.75, 13.9, 14.8, 42, R.string.battery_chemistry_calcium),
        LIFePO4  ("LiFePO4 (Lithium)",   13.30, 14.0, 14.6, 120, R.string.battery_chemistry_lifepo4);

        /** English display name (fallback for non-Android contexts). */
        public final String displayName;
        /** Open-circuit voltage representing 100% SoC. */
        public final double fullRestV;
        /** Minimum acceptable alternator regulated voltage. */
        public final double altMinV;
        /** Maximum acceptable alternator regulated voltage. */
        public final double altMaxV;
        /** Expected service life to 80% SOH (months, non-tropical). */
        public final int baseLifeMonths;
        /** String resource ID for localized display name. */
        public final int stringResId;

        Chemistry(String displayName, double fullRestV, double altMinV,
                  double altMaxV, int baseLifeMonths, int stringResId) {
            this.displayName = displayName;
            this.fullRestV = fullRestV;
            this.altMinV = altMinV;
            this.altMaxV = altMaxV;
            this.baseLifeMonths = baseLifeMonths;
            this.stringResId = stringResId;
        }

        /**
         * Get localized display name using Android string resources.
         * Falls back to English displayName if Context is not available.
         */
        public String getDisplayName(android.content.Context context) {
            if (context != null) {
                try {
                    return context.getString(stringResId);
                } catch (Exception e) {
                    // Fall through to default
                }
            }
            return displayName;
        }

        /** Optimal charging voltage for this chemistry. */
        public double altOptimalV() {
            // LiFePO4: 14.2, others: midpoint of range
            if (this == LIFePO4) return 14.2;
            return (altMinV + altMaxV) / 2.0;
        }

        /** Minimum resting voltage for healthy status (50% SoC approx). */
        public double restLowV() {
            if (this == LIFePO4) return 13.0;
            return fullRestV - 0.45;  // ~75% SoC
        }

        /** Deep discharge threshold. */
        public double restDeepV() {
            if (this == LIFePO4) return 12.5;
            return fullRestV - 0.75;  // ~0-10% SoC
        }

        /** Parse a spinner selection into Chemistry. */
        public static Chemistry fromSpinner(String label) {
            if (label == null) return FLOODED;
            String upper = label.toUpperCase(java.util.Locale.US);
            if (upper.contains("AGM")) return AGM;
            if (upper.contains("EFB")) return EFB;
            if (upper.contains("GEL") || upper.contains("เจล")) return GEL;
            if (upper.contains("CALCIUM") || upper.contains("แคลเซียม")) return CALCIUM;
            if (upper.contains("LIFEPO4") || upper.contains("LITHIUM") || upper.contains("ลิเธียม")) return LIFePO4;
            return FLOODED;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Threshold constants (backward-compatible defaults = FLOODED)
    // ─────────────────────────────────────────────────────────────
    public static final class Thresholds {
        /** Minimum acceptable regulated alternator voltage at idle (warm). */
        public static final double ALT_MIN       = 13.8;
        /** Maximum acceptable regulated alternator voltage at idle (warm). */
        public static final double ALT_MAX       = 14.7;
        /** Optimal alternator voltage (sweet spot). */
        public static final double ALT_OPTIMAL   = 14.2;
        /** Below this the alternator is undercharging — battery won't reach full charge. */
        public static final double ALT_UNDER     = 13.5;
        /** Above this the alternator/voltage-regulator is overcharging — battery damage risk. */
        public static final double ALT_OVER      = 14.8;
        /** Resting (engine off) voltage for a fully charged battery. */
        public static final double REST_FULL     = 12.65;
        /** Resting voltage below which the battery is considered discharged. */
        public static final double REST_LOW      = 12.20;
        /** Resting voltage below which the battery is deeply discharged (damage risk). */
        public static final double REST_DEEP     = 11.90;
        /** Minimum cranking voltage — below this the battery can't start the engine reliably. */
        public static final double CRANK_MIN     = 9.60;
        /** Healthy cranking voltage. */
        public static final double CRANK_GOOD    = 10.50;
        /** Maximum acceptable voltage drop under electrical load. */
        public static final double DROP_MAX      = 0.50;
        /** Good voltage drop under load. */
        public static final double DROP_GOOD     = 0.30;
        /** Recovery time (seconds) to return within 0.3 V of pre-load voltage. */
        public static final double RECOVERY_MAX  = 5.0;
        /** AC ripple threshold (V peak-to-peak) for healthy rectifier. */
        public static final double RIPPLE_MAX    = 0.10;
        /** Ripple threshold indicating a likely bad diode. */
        public static final double RIPPLE_BAD     = 0.20;

        private Thresholds() {}
    }

    // ─────────────────────────────────────────────────────────────
    //  Data models
    // ─────────────────────────────────────────────────────────────

    /** Severity of a test outcome. */
    public enum Severity {
        PASS,       // green — within spec
        WARN,       // yellow — marginal, monitor
        FAIL,       // red — out of spec, action needed
        INFO        // neutral — informational only
    }

    /** Result of a single battery/charging test. */
    public static final class BatteryTestResult {
        public final String testName;
        public final String value;       // formatted value, e.g. "14.23 V"
        public final Severity severity;
        public final String remark;     // human-readable diagnosis
        public final int score;        // 0-100, 100=perfect

        public BatteryTestResult(String testName, String value, Severity severity,
                                  String remark, int score) {
            this.testName = testName;
            this.value = value;
            this.severity = severity;
            this.remark = remark;
            this.score = Math.max(0, Math.min(100, score));
        }

        @Override
        public String toString() {
            return testName + ": " + value + " [" + severity + "] " + remark + " (score=" + score + ")";
        }
    }

    /** Full battery + charging-system diagnostic report. */
    public static final class BatteryReport {
        public final List<BatteryTestResult> results;
        public final int overallScore;   // weighted average
        public final String grade;       // A+ ... F
        public final String summary;     // one-line conclusion

        public BatteryReport(List<BatteryTestResult> results, int overallScore,
                              String grade, String summary) {
            this.results = Collections.unmodifiableList(new ArrayList<>(results));
            this.overallScore = overallScore;
            this.grade = grade;
            this.summary = summary;
        }

        /** True if any test failed. */
        public boolean hasFailures() {
            for (BatteryTestResult r : results) {
                if (r.severity == Severity.FAIL) return true;
            }
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 1: State of Charge (resting / open-circuit voltage)
    // ─────────────────────────────────────────────────────────────

    /** Backward-compatible: defaults to FLOODED chemistry. */
    public static BatteryTestResult testStateOfCharge(double restingV) {
        return testStateOfCharge(restingV, Chemistry.FLOODED);
    }

    /**
     * Estimate State-of-Charge from resting voltage using chemistry-specific
     * voltage-SoC tables. Use when engine has been OFF for ≥ 2 hours.
     *
     * Each chemistry has a different resting voltage at 100% SoC:
     *   Flooded: 12.65 V, AGM: 12.80 V, LiFePO4: 13.30 V, etc.
     *
     * @param restingV   Voltage reading with engine off (V)
     * @param chem       Battery chemistry type
     */
    public static BatteryTestResult testStateOfCharge(double restingV, Chemistry chem) {
        double soc = voltageToSoC(restingV, chem);
        Severity sev;
        String remark;
        int score;

        double fullV = chem.fullRestV;
        double lowV = chem.restLowV();
        double deepV = chem.restDeepV();

        if (restingV >= fullV) {
            sev = Severity.PASS; remark = "Fully charged"; score = 100;
        } else if (restingV >= fullV - 0.20) {
            sev = Severity.PASS; remark = "Good charge level";
            score = (int)(90 + (restingV - (fullV - 0.20)) / 0.20 * 10);
        } else if (restingV >= lowV) {
            sev = Severity.WARN; remark = "Moderate discharge — recharge soon";
            score = (int)(60 + (restingV - lowV) / ((fullV - 0.20) - lowV) * 30);
        } else if (restingV >= deepV) {
            sev = Severity.FAIL; remark = "Low charge — battery may not start engine";
            score = (int)(30 + (restingV - deepV) / (lowV - deepV) * 30);
        } else {
            sev = Severity.FAIL;
            remark = chem == Chemistry.LIFePO4
                    ? "Deeply discharged — check BMS protection, charge immediately"
                    : "Deeply discharged — sulfation risk, charge immediately";
            score = 10;
        }

        return new BatteryTestResult(
                "State of Charge (" + chem.displayName + ")",
                String.format(java.util.Locale.US, "%.2f V  (%.0f%%)", restingV, soc),
                sev, remark, score);
    }

    /** Backward-compatible: defaults to FLOODED chemistry. */
    public static double voltageToSoC(double v) {
        return voltageToSoC(v, Chemistry.FLOODED);
    }

    /**
     * Convert open-circuit voltage to SoC% using chemistry-specific tables.
     * LiFePO4 has a very flat discharge curve (13.3→13.1 V is ~100%→20%),
     * so the SoC curve is completely different from lead-acid.
     */
    public static double voltageToSoC(double v, Chemistry chem) {
        switch (chem) {
            case AGM:
                // AGM: 12.80V full, steeper curve at bottom
                if (v >= 12.80) return 100.0;
                if (v <= 12.05) return 0.0;
                return interpTable(v, new double[][]{
                        {12.05, 0}, {12.15, 10}, {12.30, 25}, {12.45, 50}, {12.55, 70}, {12.65, 85}, {12.80, 100}});
            case EFB:
                // EFB: similar to flooded but slightly higher rest
                if (v >= 12.70) return 100.0;
                if (v <= 11.95) return 0.0;
                return interpTable(v, new double[][]{
                        {11.95, 0}, {12.10, 10}, {12.25, 25}, {12.40, 45}, {12.50, 60}, {12.60, 80}, {12.70, 100}});
            case GEL:
                // Gel: similar to AGM
                if (v >= 12.75) return 100.0;
                if (v <= 12.00) return 0.0;
                return interpTable(v, new double[][]{
                        {12.00, 0}, {12.12, 10}, {12.25, 25}, {12.40, 45}, {12.50, 60}, {12.62, 80}, {12.75, 100}});
            case CALCIUM:
                // Calcium: higher rest voltage
                if (v >= 12.75) return 100.0;
                if (v <= 12.00) return 0.0;
                return interpTable(v, new double[][]{
                        {12.00, 0}, {12.12, 10}, {12.28, 25}, {12.40, 45}, {12.52, 60}, {12.62, 80}, {12.75, 100}});
            case LIFePO4:
                // LiFePO4: very flat curve ~13.3-13.1V = 90-20%
                return interpTable(v, new double[][]{
                        {12.50, 0}, {12.80, 5}, {13.00, 15}, {13.10, 30}, {13.20, 80}, {13.30, 95}, {13.60, 100}});
            default: // FLOODED
                if (v >= 12.65) return 100.0;
                if (v <= 11.90) return 0.0;
                return interpTable(v, new double[][]{
                        {11.90, 0}, {12.05, 10}, {12.20, 25}, {12.35, 45}, {12.45, 60}, {12.55, 80}, {12.65, 100}});
        }
    }

    private static double interpTable(double v, double[][] table) {
        if (v <= table[0][0]) return table[0][1];
        if (v >= table[table.length - 1][0]) return table[table.length - 1][1];
        for (int i = 0; i < table.length - 1; i++) {
            if (v >= table[i][0] && v <= table[i + 1][0]) {
                double ratio = (v - table[i][0]) / (table[i + 1][0] - table[i][0]);
                return table[i][1] + ratio * (table[i + 1][1] - table[i][1]);
            }
        }
        return 0;
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 2: Battery Health Classification
    // ─────────────────────────────────────────────────────────────

    /**
     * Classify overall battery health from resting voltage + minimum cranking
     * voltage. This is a simplified version of a conductance tester — real
     * battery testers (Midtronics, OBELIS) measure internal resistance, which
     * OBD2 cannot do. We approximate from voltage behavior.
     *
     * @param restingV    Open-circuit voltage (engine off, ≥ 2 h)
     * @param crankMinV   Minimum voltage during cranking (0 if unknown)
     */
    public static BatteryTestResult testBatteryHealth(double restingV, double crankMinV) {
        return testBatteryHealth(restingV, crankMinV, Chemistry.FLOODED);
    }

    /**
     * Chemistry-aware battery health classification.
     * Uses chemistry-specific SoC tables and voltage thresholds.
     *
     * @param restingV    Open-circuit voltage (engine off, ≥ 2 h)
     * @param crankMinV   Minimum voltage during cranking (0 if unknown)
     * @param chem        Battery chemistry type
     */
    public static BatteryTestResult testBatteryHealth(double restingV, double crankMinV, Chemistry chem) {
        String grade;
        Severity sev;
        String remark;
        int score;

        double soc = voltageToSoC(restingV, chem);

        // If we have a cranking voltage, factor it in
        if (crankMinV > 0) {
            if (soc >= 80 && crankMinV >= Thresholds.CRANK_GOOD) {
                grade = "Excellent"; sev = Severity.PASS; score = 95;
                remark = "Battery capacity and CCA are excellent";
            } else if (soc >= 50 && crankMinV >= Thresholds.CRANK_MIN) {
                grade = "Good"; sev = Severity.PASS; score = 75;
                remark = "Battery serviceable, slight capacity loss";
            } else if (soc >= 15 && crankMinV >= 9.00) {
                grade = "Fair"; sev = Severity.WARN; score = 50;
                remark = "Battery aging — monitor, replace within 6 months";
            } else if (crankMinV > 0 && crankMinV < 9.00) {
                grade = "Poor"; sev = Severity.FAIL; score = 25;
                remark = "Low CCA — high internal resistance, replace soon";
            } else {
                grade = "Poor"; sev = Severity.FAIL; score = 20;
                remark = "Low voltage + weak crank — replace battery";
            }
        } else {
            // No cranking data — grade on resting voltage only
            if (soc >= 90) { grade = "Excellent (voltage only)"; sev = Severity.PASS; score = 85; remark = "Voltage is healthy; CCA not tested"; }
            else if (soc >= 60) { grade = "Good (voltage only)"; sev = Severity.PASS; score = 70; remark = "Adequate charge; CCA not tested"; }
            else if (soc >= 25) { grade = "Fair (voltage only)"; sev = Severity.WARN; score = 40; remark = "Discharged — recharge and retest"; }
            else { grade = "Poor (voltage only)"; sev = Severity.FAIL; score = 15; remark = "Deeply discharged or failed"; }
        }

        return new BatteryTestResult(
                "Battery Health",
                grade,
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 3: Alternator / Charging Voltage
    // ─────────────────────────────────────────────────────────────

    /**
     * Test alternator regulated output voltage at idle (engine running, warm,
     * no major electrical loads). Uses FLOODED thresholds.
     *
     * @param runningV  PID 0x42 voltage with engine running at idle
     */
    public static BatteryTestResult testAlternatorVoltage(double runningV) {
        return testAlternatorVoltage(runningV, Chemistry.FLOODED);
    }

    /**
     * Test alternator regulated output voltage using chemistry-specific thresholds.
     *
     * @param runningV  PID 0x42 voltage with engine running at idle
     * @param chem      Battery chemistry type (determines acceptable voltage range)
     */
    public static BatteryTestResult testAlternatorVoltage(double runningV, Chemistry chem) {
        Severity sev;
        String remark;
        int score;

        double altMin = chem.altMinV;
        double altMax = chem.altMaxV;
        double altOptimal = chem.altOptimalV();
        double altUnder = altMin - 0.3;   // 0.3V below min = fail
        double altOver = altMax + 0.1;    // 0.1V above max = fail

        if (runningV >= altMin && runningV <= altMax) {
            sev = Severity.PASS;
            double dist = Math.abs(runningV - altOptimal);
            score = (int)(100 - dist * 30);  // closer to optimal = higher score
            remark = "Alternator output within specification (" + chem.displayName + ")";
        } else if (runningV >= altUnder && runningV < altMin) {
            sev = Severity.WARN;
            score = 55;
            remark = "Slightly undercharging — battery may not reach full charge";
        } else if (runningV > altMax && runningV <= altOver) {
            sev = Severity.WARN;
            score = 50;
            remark = "Slightly overcharging — check voltage regulator, battery may overheat";
        } else if (runningV < altUnder) {
            sev = Severity.FAIL;
            score = 20;
            remark = "Undercharging — alternator or regulator likely faulty";
        } else {
            sev = Severity.FAIL;
            score = 15;
            remark = "Overcharging — regulator fault, battery damage risk!";
        }

        return new BatteryTestResult(
                "Alternator Voltage",
                String.format(java.util.Locale.US, "%.2f V", runningV),
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 4: Voltage Drop Test (Electrical Load Test)
    // ─────────────────────────────────────────────────────────────

    /**
     * Measure voltage drop when electrical loads are applied (headlights,
     * blower motor, cooling fan, rear defroster). The test compares the
     * battery voltage with no load vs. with full load, both with the engine
     * running. A healthy charging system should not drop more than 0.50 V.
     *
     * @param noLoadV    Voltage at idle, no extra loads
     * @param fullLoadV  Voltage at idle with all accessories on
     */
    public static BatteryTestResult testVoltageDrop(double noLoadV, double fullLoadV) {
        double drop = noLoadV - fullLoadV;
        Severity sev;
        String remark;
        int score;

        if (drop <= Thresholds.DROP_GOOD) {
            sev = Severity.PASS; score = 95;
            remark = "Minimal voltage drop — wiring and connections healthy";
        } else if (drop <= Thresholds.DROP_MAX) {
            sev = Severity.PASS; score = 75;
            remark = "Acceptable drop — check terminals for corrosion";
        } else if (drop <= 0.80) {
            sev = Severity.WARN; score = 45;
            remark = "High voltage drop — check battery terminals, ground strap, cables";
        } else {
            sev = Severity.FAIL; score = 20;
            remark = "Excessive drop — bad connection, loose ground, or undersized cable";
        }

        return new BatteryTestResult(
                "Voltage Drop (Load Test)",
                String.format(java.util.Locale.US, "%.2f V  (drop: %.2f V)", fullLoadV, drop),
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 5: Voltage Recovery Test
    // ─────────────────────────────────────────────────────────────

    /**
     * After a large electrical load is removed, the battery voltage should
     * recover to within 0.3 V of its pre-load level within 5 seconds.
     * Slow recovery indicates high internal resistance (aging battery).
     *
     * @param preLoadV     Voltage before the load was applied
     * @param postLoadV    Voltage immediately after load removal
     * @param recoveredV   Voltage after recovery period
     * @param recoverySec  Seconds elapsed between post-load and recovered readings
     */
    public static BatteryTestResult testVoltageRecovery(double preLoadV, double postLoadV,
                                                         double recoveredV, double recoverySec) {
        double delta = preLoadV - recoveredV;
        Severity sev;
        String remark;
        int score;

        if (delta <= 0.10) {
            sev = Severity.PASS; score = 100;
            remark = "Excellent recovery — battery internal resistance is low";
        } else if (delta <= 0.30) {
            sev = Severity.PASS; score = 80;
            remark = "Good recovery within spec";
        } else if (delta <= 0.50) {
            sev = Severity.WARN; score = 50;
            remark = "Marginal recovery — battery may be aging";
        } else {
            sev = Severity.FAIL; score = 20;
            remark = "Poor recovery — high internal resistance, battery likely weak";
        }

        // Also factor in recovery time
        if (recoverySec > Thresholds.RECOVERY_MAX && sev != Severity.FAIL) {
            sev = Severity.WARN;
            score -= 15;
            remark += " (recovery took " + String.format(java.util.Locale.US, "%.1f", recoverySec) + "s — slow)";
        }

        return new BatteryTestResult(
                "Voltage Recovery",
                String.format(java.util.Locale.US, "%.2f→%.2f V  (Δ%.2f V in %.1fs)",
                        postLoadV, recoveredV, delta, recoverySec),
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 6: Cranking Voltage Test
    // ─────────────────────────────────────────────────────────────

    /**
     * Test the minimum battery voltage during engine cranking.
     * Requires fast sampling of PID 0x42 during the crank (every 50-100 ms).
     * A healthy battery should not drop below 10.5 V during crank at 20 °C.
     *
     * @param crankMinV    Minimum voltage observed during crank
     * @param restingV     Pre-crank resting voltage (engine off, for context)
     */
    public static BatteryTestResult testCrankingVoltage(double crankMinV, double restingV) {
        Severity sev;
        String remark;
        int score;

        if (crankMinV >= Thresholds.CRANK_GOOD) {
            sev = Severity.PASS; score = 95;
            remark = "Strong cranking voltage — CCA is adequate";
        } else if (crankMinV >= Thresholds.CRANK_MIN) {
            sev = Severity.WARN; score = 60;
            remark = "Marginal cranking voltage — battery may struggle in cold weather";
        } else if (crankMinV >= 9.00) {
            sev = Severity.FAIL; score = 30;
            remark = "Low cranking voltage — battery has high internal resistance";
        } else {
            sev = Severity.FAIL; score = 10;
            remark = "Critical cranking voltage — battery cannot deliver sufficient CCA";
        }

        return new BatteryTestResult(
                "Cranking Voltage",
                String.format(java.util.Locale.US, "%.2f V  (rest: %.2f V)", crankMinV, restingV),
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 7: Ripple / Diode-Health Test
    // ─────────────────────────────────────────────────────────────

    /**
     * Detect AC ripple in the charging voltage. A bad alternator diode allows
     * AC ripple to pass, which confuses the ECU and shortens battery life.
     * Requires a burst of fast voltage samples (e.g. 20 samples at 100 ms
     * intervals = 2 seconds of data). Compute peak-to-peak ripple.
     *
     * @param samples  Fast-sampled voltage readings while engine is running
     */
    public static BatteryTestResult testRipple(List<Double> samples) {
        if (samples == null || samples.size() < 5) {
            return new BatteryTestResult("Diode / Ripple", "Insufficient data",
                    Severity.INFO, "Need at least 5 fast samples", 0);
        }

        double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        for (double v : samples) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double ripplePP = max - min;
        Severity sev;
        String remark;
        int score;

        if (ripplePP <= Thresholds.RIPPLE_MAX) {
            sev = Severity.PASS; score = 95;
            remark = "Rectifier healthy — low AC ripple";
        } else if (ripplePP <= Thresholds.RIPPLE_BAD) {
            sev = Severity.WARN; score = 55;
            remark = "Elevated ripple — one or more diodes may be failing";
        } else {
            sev = Severity.FAIL; score = 20;
            remark = "Excessive AC ripple — bad diode(s) in alternator, replace";
        }

        return new BatteryTestResult(
                "Diode / Ripple",
                String.format(java.util.Locale.US, "%.3f V p-p", ripplePP),
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 8: Parasitic Drain Estimate
    // ─────────────────────────────────────────────────────────────

    /**
     * Estimate parasitic drain by measuring the voltage decay rate when the
     * engine is off. A healthy vehicle should lose less than ~0.5% SoC per
     * hour (~0.03 V/h for a 60 Ah battery). This is an approximate test —
     * a true parasitic drain test requires an ammeter in series.
     *
     * @param vStart   Voltage at the start of the measurement
     * @param vEnd     Voltage at the end
     * @param hours    Hours elapsed between the two readings
     */
    public static BatteryTestResult testParasiticDrain(double vStart, double vEnd, double hours) {
        if (hours <= 0) {
            return new BatteryTestResult("Parasitic Drain", "Insufficient time",
                    Severity.INFO, "Need measurements at least 1 hour apart", 0);
        }

        double dropPerHour = (vStart - vEnd) / hours;
        double socLossPerHour = voltageToSoC(vStart) - voltageToSoC(vEnd);
        double socRate = socLossPerHour / hours;

        Severity sev;
        String remark;
        int score;

        if (socRate <= 0.5) {
            sev = Severity.PASS; score = 90;
            remark = "Normal standby drain";
        } else if (socRate <= 1.5) {
            sev = Severity.WARN; score = 60;
            remark = "Slightly elevated drain — check for aftermarket accessories";
        } else {
            sev = Severity.FAIL; score = 25;
            remark = "Excessive parasitic drain — battery will die overnight";
        }

        return new BatteryTestResult(
                "Parasitic Drain",
                String.format(java.util.Locale.US, "%.0f%%/h  (%.3f V/h)", socRate, dropPerHour),
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 10: State of Health (SOH)
    // ─────────────────────────────────────────────────────────────

    /**
     * Estimate State of Health (SOH) — the battery's ability to hold a charge
     * relative to when it was new. Unlike SoC (which is reversible), SOH is a
     * permanent degradation metric that only goes down over time.
     *
     * Since OBD2 cannot directly measure internal resistance or capacity, we
     * approximate SOH from multiple indirect indicators:
     *
     *  1. Resting voltage (SoC) — a deeply discharged battery that won't
     *     recover suggests cell degradation.
     *  2. Cranking voltage — the voltage dip during crank is directly related
     *     to internal resistance. Lower crank voltage = higher IR = lower SOH.
     *  3. Voltage recovery — slow recovery after load removal indicates high
     *     internal resistance (aging).
     *  4. Charging acceptance — if the alternator voltage is healthy but the
     *     battery resting voltage stays low, the battery can't hold charge.
     *
     * SOH is expressed as a percentage:
     *   100% = new battery (full capacity, low IR)
     *   80% = end of recommended service life (replace soon)
     *   <60% = failed (replace immediately)
     *
     * @param restingV     Engine-off voltage (≥ 2h rest)
     * @param crankMinV    Minimum cranking voltage (or -1 if unknown)
     * @param recoveryDelta Voltage difference (pre-load - post-recovery), V
     * @param altV         Alternator regulated voltage (or -1)
     */
    public static BatteryTestResult testStateOfHealth(double restingV, double crankMinV,
                                                      double recoveryDelta, double altV) {
        return testStateOfHealth(restingV, crankMinV, recoveryDelta, altV, Chemistry.FLOODED);
    }

    /**
     * Chemistry-aware State of Health (SOH) estimation.
     *
     * @param restingV     Engine-off voltage (≥ 2h rest)
     * @param crankMinV    Minimum cranking voltage (or -1 if unknown)
     * @param recoveryDelta Voltage difference (pre-load - post-recovery), V
     * @param altV         Alternator regulated voltage (or -1)
     * @param chem         Battery chemistry type
     */
    public static BatteryTestResult testStateOfHealth(double restingV, double crankMinV,
                                                      double recoveryDelta, double altV, Chemistry chem) {
        double soh = 100;
        List<String> factors = new ArrayList<>();

        // Factor 1: Resting voltage capacity (chemistry-specific SoC)
        double soc = voltageToSoC(restingV, chem);
        if (soc < 25) {
            soh -= 30; factors.add("deep discharge");
        } else if (soc < 50) {
            soh -= 15; factors.add("low charge retention");
        } else if (soc < 75) {
            soh -= 5; factors.add("moderate charge loss");
        }

        // Factor 2: Cranking voltage → internal resistance
        if (crankMinV > 0) {
            if (crankMinV >= Thresholds.CRANK_GOOD) {
                // Minimal IR — no penalty
            } else if (crankMinV >= Thresholds.CRANK_MIN) {
                soh -= 15; factors.add("elevated internal resistance");
            } else if (crankMinV >= 9.00) {
                soh -= 35; factors.add("high internal resistance");
            } else {
                soh -= 50; factors.add("critical internal resistance");
            }
        }

        // Factor 3: Voltage recovery (aging indicator)
        if (recoveryDelta >= 0) {
            if (recoveryDelta <= 0.10) {
                // Excellent — no penalty
            } else if (recoveryDelta <= 0.30) {
                soh -= 5; factors.add("slight recovery lag");
            } else if (recoveryDelta <= 0.50) {
                soh -= 15; factors.add("slow recovery");
            } else {
                soh -= 25; factors.add("very slow recovery (aging)");
            }
        }

        // Factor 4: Charge acceptance — if alternator is healthy but battery
        // stays low, the battery can't accept charge (sulfation/desulfation)
        double altMin = chem.altMinV;
        double lowRestThreshold = chem.restLowV() - 0.15; // e.g. 12.05V for Flooded (12.20 - 0.15)
        if (altV > 0 && altV >= altMin && restingV > 0 && restingV < lowRestThreshold) {
            soh -= 20;
            factors.add("poor charge acceptance (sulfation)");
        }

        soh = Math.max(0, Math.min(100, soh));

        String grade;
        Severity sev;
        String remark;
        int score = (int) soh;

        if (soh >= 85) {
            grade = "Excellent"; sev = Severity.PASS;
            remark = "Battery like new — full capacity available";
        } else if (soh >= 70) {
            grade = "Good"; sev = Severity.PASS;
            remark = "Battery serviceable — slight aging";
        } else if (soh >= 55) {
            grade = "Fair"; sev = Severity.WARN;
            remark = "Battery aging — replace within 6 months";
        } else if (soh >= 40) {
            grade = "Poor"; sev = Severity.FAIL;
            remark = "Battery significantly degraded — replace soon";
        } else {
            grade = "Failed"; sev = Severity.FAIL;
            remark = "Battery end-of-life — replace immediately";
        }

        if (!factors.isEmpty()) {
            remark += " (" + String.join(", ", factors) + ")";
        }

        return new BatteryTestResult(
                "State of Health (SOH)",
                String.format(java.util.Locale.US, "%.0f%%  (%s)", soh, grade),
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 11: Battery Life Estimation
    // ─────────────────────────────────────────────────────────────

    /**
     * Estimate remaining battery life (months) based on SOH and degradation
     * rate. Uses a simple linear degradation model:
     *
     *   - New flooded battery: ~36-48 months to 80% SOH
     *   - New AGM battery: ~48-60 months to 80% SOH
     *   - Tropical climate: subtract ~30% from expected life
     *
     * The estimation uses current SOH and assumes linear degradation from
     * 100% at installation. This is approximate — actual life depends on
     * cycling depth, temperature, and charging quality.
     *
     * @param soh              Current SOH (%)
     * @param ageMonths        Battery age in months (or -1 if unknown)
     * @param isAgm            True for AGM/Gel, false for flooded
     * @param tropicalClimate  True if operated in hot climate (>35°C avg summer)
     */
    /** Test 11: Battery Life Estimation */
    public static BatteryTestResult testBatteryLife(double soh, int ageMonths,
                                                     boolean isAgm, boolean tropicalClimate) {
        return testBatteryLife(soh, ageMonths, isAgm ? Chemistry.AGM : Chemistry.FLOODED, tropicalClimate);
    }

    /** Test 11: Battery Life Estimation — full chemistry support. */
    public static BatteryTestResult testBatteryLife(double soh, int ageMonths,
                                                     Chemistry chem, boolean tropicalClimate) {
        // Expected service life to reach 80% SOH (months)
        double baseLife = chem.baseLifeMonths;
        if (tropicalClimate) baseLife *= 0.70;

        double remainingMonths;

        if (ageMonths > 0) {
            double degradationRate = (100.0 - soh) / ageMonths;
            if (degradationRate <= 0) {
                remainingMonths = baseLife - ageMonths;
            } else {
                remainingMonths = (soh - 40.0) / degradationRate;
            }
            remainingMonths = Math.max(0, remainingMonths);
        } else {
            double avgRate = 100.0 / baseLife;
            double estimatedAge = (100.0 - soh) / avgRate;
            remainingMonths = baseLife - estimatedAge;
            remainingMonths = Math.max(0, remainingMonths);
        }

        Severity sev;
        String remark;
        int score;

        if (soh >= 85) {
            sev = Severity.PASS; score = 95;
            remark = String.format(java.util.Locale.US, "~%.0f months remaining", remainingMonths);
        } else if (soh >= 70) {
            sev = Severity.PASS; score = 75;
            remark = String.format(java.util.Locale.US, "~%.0f months remaining — monitor", remainingMonths);
        } else if (soh >= 55) {
            sev = Severity.WARN; score = 50;
            remark = String.format(java.util.Locale.US, "~%.0f months — plan replacement", remainingMonths);
        } else if (soh >= 40) {
            sev = Severity.FAIL; score = 25;
            remark = String.format(java.util.Locale.US, "~%.0f months — replace soon", remainingMonths);
        } else {
            sev = Severity.FAIL; score = 10;
            remark = "Replace immediately — end of life";
        }

        String batteryType = chem.displayName;
        String climateNote = tropicalClimate ? " (tropical climate)" : "";

        return new BatteryTestResult(
                "Battery Life Estimate",
                String.format(java.util.Locale.US, "~%.0f months (%s%s)",
                        remainingMonths, batteryType, climateNote),
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Test 9: Charging System Efficiency (Voltage vs RPM)
    // ─────────────────────────────────────────────────────────────

    /**
     * Check that the alternator maintains regulation across the RPM range.
     * At high RPM the voltage should stay within the regulated band; a rising
     * voltage at high RPM suggests a failing regulator.
     *
     * @param idleV     Voltage at idle (~750 rpm)
     * @param highRpmV  Voltage at 2500-3000 rpm
     */
    public static BatteryTestResult testChargingEfficiency(double idleV, double highRpmV) {
        double delta = highRpmV - idleV;
        Severity sev;
        String remark;
        int score;

        if (Math.abs(delta) <= 0.20) {
            sev = Severity.PASS; score = 95;
            remark = "Stable regulation across RPM range";
        } else if (Math.abs(delta) <= 0.40) {
            sev = Severity.PASS; score = 75;
            remark = "Minor voltage variation — acceptable";
        } else if (delta > 0.40 && highRpmV <= Thresholds.ALT_MAX) {
            sev = Severity.WARN; score = 50;
            remark = "Voltage rises with RPM — regulator may be drifting";
        } else if (delta > 0.40) {
            sev = Severity.FAIL; score = 20;
            remark = "Overcharging at high RPM — regulator faulty";
        } else {
            sev = Severity.WARN; score = 45;
            remark = "Voltage drops at high RPM — alternator may be weak or belt slipping";
        }

        return new BatteryTestResult(
                "Charging Efficiency",
                String.format(java.util.Locale.US, "%.2f→%.2f V  (Δ%.2f V)", idleV, highRpmV, delta),
                sev, remark, score);
    }

    // ─────────────────────────────────────────────────────────────
    //  Full Report Builder
    // ─────────────────────────────────────────────────────────────

    /** Backward-compat version — defaults to FLOODED chemistry. */
    public static BatteryReport buildFullReport(
            double restingV, double runningV, double crankMinV,
            double noLoadV, double fullLoadV,
            double postLoadV, double recoveredV, double recoverySec,
            double highRpmV,
            List<Double> rippleSamples,
            double drainStartV, double drainEndV, double drainHours,
            double recoveryDelta, int batteryAgeMonths,
            boolean isAgm, boolean tropicalClimate) {
        return buildFullReport(
                restingV, runningV, crankMinV,
                noLoadV, fullLoadV,
                postLoadV, recoveredV, recoverySec,
                highRpmV, rippleSamples,
                drainStartV, drainEndV, drainHours,
                recoveryDelta, batteryAgeMonths,
                isAgm ? Chemistry.AGM : Chemistry.FLOODED,
                tropicalClimate);
    }

    /**
     * Build a full battery + charging system report from all available data.
     * Any test whose input is missing (null / negative) is skipped.
     *
     * @param restingV     Engine-off voltage (or -1 if unknown)
     * @param runningV     Engine-idle voltage (or -1)
     * @param crankMinV    Minimum cranking voltage (or -1)
     * @param noLoadV      No-load idle voltage (or -1)
     * @param fullLoadV    Full-load idle voltage (or -1)
     * @param postLoadV    Voltage right after load removal (or -1)
     * @param recoveredV   Voltage after recovery period (or -1)
     * @param recoverySec  Recovery time (or -1)
     * @param highRpmV     Voltage at high RPM (or -1)
     * @param rippleSamples Fast samples for ripple test (or null)
     * @param drainStartV  Parasitic drain start voltage (or -1)
     * @param drainEndV    Parasitic drain end voltage (or -1)
     * @param drainHours   Hours between drain readings (or -1)
     * @param recoveryDelta Voltage recovery delta for SOH (or -1)
     * @param batteryAgeMonths Battery age in months (or -1)
     * @param chem         Battery chemistry type
     * @param tropicalClimate True for hot climate operation
     */
    public static BatteryReport buildFullReport(
            double restingV, double runningV, double crankMinV,
            double noLoadV, double fullLoadV,
            double postLoadV, double recoveredV, double recoverySec,
            double highRpmV,
            List<Double> rippleSamples,
            double drainStartV, double drainEndV, double drainHours,
            double recoveryDelta, int batteryAgeMonths,
            Chemistry chem, boolean tropicalClimate) {

        List<BatteryTestResult> results = new ArrayList<>();

        // Test 1: State of Charge (chemistry-aware)
        if (restingV > 0) {
            results.add(testStateOfCharge(restingV, chem));
        }

        // Test 2: Battery Health (chemistry-aware)
        if (restingV > 0) {
            double crank = crankMinV > 0 ? crankMinV : 0;
            results.add(testBatteryHealth(restingV, crank, chem));
        }

        // Test 3: Alternator Voltage (chemistry-aware thresholds)
        if (runningV > 0) {
            results.add(testAlternatorVoltage(runningV, chem));
        }

        // Test 4: Voltage Drop
        if (noLoadV > 0 && fullLoadV > 0) {
            results.add(testVoltageDrop(noLoadV, fullLoadV));
        }

        // Test 5: Voltage Recovery
        if (postLoadV > 0 && recoveredV > 0) {
            double rs = recoverySec > 0 ? recoverySec : 0;
            results.add(testVoltageRecovery(noLoadV, postLoadV, recoveredV, rs));
        }

        // Test 6: Cranking Voltage
        if (crankMinV > 0) {
            double rest = restingV > 0 ? restingV : 0;
            results.add(testCrankingVoltage(crankMinV, rest));
        }

        // Test 7: Ripple
        if (rippleSamples != null && rippleSamples.size() >= 5) {
            results.add(testRipple(rippleSamples));
        }

        // Test 8: Parasitic Drain
        if (drainStartV > 0 && drainEndV > 0 && drainHours > 0) {
            results.add(testParasiticDrain(drainStartV, drainEndV, drainHours));
        }

        // Test 9: Charging Efficiency
        if (runningV > 0 && highRpmV > 0) {
            results.add(testChargingEfficiency(runningV, highRpmV));
        }

        // Test 10: State of Health (SOH)
        double computedSoh = -1;
        if (restingV > 0) {
            double crank = crankMinV > 0 ? crankMinV : -1;
            double rDelta = recoveryDelta >= 0 ? recoveryDelta : -1;
            double alt = runningV > 0 ? runningV : -1;
            BatteryTestResult sohResult = testStateOfHealth(restingV, crank, rDelta, alt, chem);
            results.add(sohResult);
            // Extract SOH value from the result for the life estimation
            try {
                String val = sohResult.value.replaceAll("[^0-9.]", "");
                if (!val.isEmpty()) computedSoh = Double.parseDouble(val);
            } catch (NumberFormatException ignored) {}
        }

        // Test 11: Battery Life Estimate
        if (computedSoh > 0) {
            int ageMonths = batteryAgeMonths > 0 ? batteryAgeMonths : -1;
            results.add(testBatteryLife(computedSoh, ageMonths, chem, tropicalClimate));
        }

        // Compute weighted overall score
        int totalScore = 0;
        int totalWeight = 0;
        for (BatteryTestResult r : results) {
            int weight = 1;
            // SOH, Health and alternator are weighted highest
            if (r.testName.contains("Health") && !r.testName.contains("SOH")) weight = 3;
            else if (r.testName.contains("SOH")) weight = 3;
            else if (r.testName.contains("Alternator")) weight = 3;
            else if (r.testName.contains("Life")) weight = 2;
            else if (r.testName.contains("Cranking")) weight = 2;
            else if (r.testName.contains("State of Charge")) weight = 2;
            totalScore += r.score * weight;
            totalWeight += weight;
        }
        int overall = totalWeight > 0 ? totalScore / totalWeight : 0;

        // Grade
        String grade;
        if (overall >= 90) grade = "A+";
        else if (overall >= 80) grade = "A";
        else if (overall >= 70) grade = "B";
        else if (overall >= 55) grade = "C";
        else if (overall >= 40) grade = "D";
        else grade = "F";

        // Summary
        long fails = results.stream().filter(r -> r.severity == Severity.FAIL).count();
        long warns = results.stream().filter(r -> r.severity == Severity.WARN).count();
        String summary;
        if (fails > 0) {
            summary = fails + " test(s) FAILED — service required";
        } else if (warns > 0) {
            summary = warns + " test(s) need attention — monitor";
        } else {
            summary = "All tests passed — battery & charging system healthy";
        }

        return new BatteryReport(results, overall, grade, summary);
    }
}
