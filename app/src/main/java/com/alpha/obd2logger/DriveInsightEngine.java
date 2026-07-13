package com.alpha.obd2logger;

/**
 * Deterministic priority and navigation policy for the on-device Drive Insight.
 * UI localization stays in MainActivity; this class remains pure and testable.
 */
public final class DriveInsightEngine {

    public enum Type {
        COLLECTING,
        DTC,
        COOLANT_HIGH,
        VOLTAGE,
        FUEL_TRIM,
        STABLE
    }

    public enum Destination {
        DASHBOARD,
        DIAGNOSTICS,
        BATTERY,
        FUEL_MAP
    }

    public static final class Result {
        public final Type type;
        public final Destination destination;
        public final int dtcCount;
        public final Double coolant;
        public final Double voltage;
        public final Double totalTrim;

        private Result(Type type, Destination destination, int dtcCount,
                       Double coolant, Double voltage, Double totalTrim) {
            this.type = type;
            this.destination = destination;
            this.dtcCount = dtcCount;
            this.coolant = coolant;
            this.voltage = voltage;
            this.totalTrim = totalTrim;
        }
    }

    private DriveInsightEngine() {}

    public static Result evaluate(Double rpm, Double coolant, Double voltage,
                                  Double totalTrim, int dtcCount) {
        int safeDtcCount = Math.max(0, dtcCount);
        // Stored scan results remain actionable even before a new live stream starts.
        if (safeDtcCount > 0) {
            return new Result(Type.DTC, Destination.DIAGNOSTICS, safeDtcCount,
                    coolant, voltage, totalTrim);
        }
        if (coolant != null && Double.isFinite(coolant) && coolant >= 105.0) {
            return new Result(Type.COOLANT_HIGH, Destination.DASHBOARD, 0,
                    coolant, voltage, totalTrim);
        }
        if (rpm != null && Double.isFinite(rpm) && rpm >= 500.0
                && voltage != null && Double.isFinite(voltage)
                && (voltage < 13.0 || voltage > 14.9)) {
            return new Result(Type.VOLTAGE, Destination.BATTERY, 0,
                    coolant, voltage, totalTrim);
        }
        if (totalTrim != null && Double.isFinite(totalTrim)
                && Math.abs(totalTrim) >= 10.0) {
            return new Result(Type.FUEL_TRIM, Destination.FUEL_MAP, 0,
                    coolant, voltage, totalTrim);
        }
        if (rpm == null || !Double.isFinite(rpm)) {
            return new Result(Type.COLLECTING, Destination.DASHBOARD, 0,
                    coolant, voltage, totalTrim);
        }
        return new Result(Type.STABLE, Destination.DASHBOARD, 0,
                coolant, voltage, totalTrim);
    }
}
