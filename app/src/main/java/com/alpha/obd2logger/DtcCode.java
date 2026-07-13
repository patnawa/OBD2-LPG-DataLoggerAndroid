package com.alpha.obd2logger;

import java.util.Locale;

/**
 * Represents a single Diagnostic Trouble Code (DTC).
 * DTC format: 1 letter + 4 hex digits (e.g. P0301 = cylinder 1 misfire).
 */
public final class DtcCode {
    private final String code;
    private final String description;

    public DtcCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public enum Severity {
        CRITICAL,
        WARNING,
        INFO
    }

    public Severity getSeverity() {
        if (code == null || code.isEmpty()) {
            return Severity.INFO;
        }
        // 1) Prefer enrichment database (has per-code severity from experts)
        try {
            DtcEnrichment.EnrichmentData enrichment = DtcEnrichment.lookup(code);
            if (enrichment != null) {
                String sev = enrichment.getSeverity();
                if (sev != null) {
                    switch (sev.toLowerCase(Locale.US)) {
                        case "critical": return Severity.CRITICAL;
                        case "warning":  return Severity.WARNING;
                        case "info":     return Severity.INFO;
                    }
                }
            }
        } catch (Exception ignored) {
            // Enrichment may not be initialised yet — fall through to heuristic
        }

        // 2) Heuristic fallback based on code prefix and known critical patterns
        char prefix = code.charAt(0);
        // Airbag / restraint codes are always critical regardless of enrichment
        if (prefix == 'B' && code.length() >= 2) {
            // B0xxx, B00xx = SRS/airbag → critical; other B = warning
            char second = code.charAt(1);
            if (second == '0') return Severity.CRITICAL;
            return Severity.WARNING;
        }
        // Network codes can strand the vehicle
        if (prefix == 'U') return Severity.WARNING;
        // Chassis codes (ABS, brakes, steering) = warning at minimum
        if (prefix == 'C') return Severity.WARNING;
        // Powertrain: misfire, fuel system, catalytic = critical; others = warning
        if (prefix == 'P' && code.length() >= 4) {
            String num = code.substring(1, 4);
            if (num.startsWith("03") || num.startsWith("01")   // misfire, fuel trim
                || num.startsWith("02")                        // fuel/air metering
                || num.startsWith("04")                        // EGR/aux emission
                || num.startsWith("05")                        // idle/speed
                || num.startsWith("06")) {                     // computer
                return Severity.CRITICAL;
            }
            return Severity.WARNING;
        }
        return Severity.INFO;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DtcCode dtcCode = (DtcCode) o;
        return code != null ? code.equals(dtcCode.code) : dtcCode.code == null;
    }

    @Override
    public int hashCode() {
        return code != null ? code.hashCode() : 0;
    }

    /**
     * Decode a 2-byte DTC from raw hex into the standard P/C/B/U format.
     * First 2 bits of byte A encode the system letter:
     *   00 = P (Powertrain), 01 = C (Chassis), 10 = B (Body), 11 = U (Network)
     * Next 2 bits + 4 bits of hex = the 4-digit code.
     */
    public static DtcCode fromHexBytes(int byteA, int byteB) {
        char system;
        switch ((byteA >> 6) & 0x03) {
            case 0x00: system = 'P'; break;
            case 0x01: system = 'C'; break;
            case 0x02: system = 'B'; break;
            case 0x03: system = 'U'; break;
            default:   system = 'P'; break;
        }
        int digit1 = (byteA >> 4) & 0x03;
        int digit2 = byteA & 0x0F;
        int digit3 = (byteB >> 4) & 0x0F;
        int digit4 = byteB & 0x0F;

        String code = String.format(Locale.US, "%c%X%X%X%X", system, digit1, digit2, digit3, digit4);
        String desc = DtcDatabase.lookup(code);
        if (desc == null) {
            desc = lookupDescription(code);
            TelemetryClient.reportUnknownDtc(DtcDatabase.getAppContext(),
                    code, DtcDatabase.getCurrentBrand());
        }
        return new DtcCode(code, desc);
    }

    /**
     * Basic DTC description lookup for common powertrain codes.
     */
    private static String lookupDescription(String code) {
        if (code == null || code.length() < 5) return "Unknown DTC";
        // Common codes
        switch (code) {
            case "P0100": return "MAF Sensor Circuit Malfunction";
            case "P0101": return "MAF Range/Performance Problem";
            case "P0102": return "MAF Circuit Low Input";
            case "P0103": return "MAF Circuit High Input";
            case "P0110": return "IAT Sensor Circuit Malfunction";
            case "P0115": return "Engine Coolant Temp Circuit Malfunction";
            case "P0116": return "ECT Range/Performance";
            case "P0117": return "ECT Circuit Low Input";
            case "P0118": return "ECT Circuit High Input";
            case "P0120": return "TPS Circuit Malfunction";
            case "P0121": return "TPS Range/Performance";
            case "P0122": return "TPS Circuit Low Input";
            case "P0123": return "TPS Circuit High Input";
            case "P0130": return "O2 Sensor Circuit Malfunction (Bank 1 Sensor 1)";
            case "P0131": return "O2 Sensor Low Voltage (B1S1)";
            case "P0132": return "O2 Sensor High Voltage (B1S1)";
            case "P0133": return "O2 Sensor Slow Response (B1S1)";
            case "P0134": return "O2 Sensor No Activity (B1S1)";
            case "P0170": return "Fuel Trim Malfunction (Bank 1)";
            case "P0171": return "System Too Lean (Bank 1)";
            case "P0172": return "System Too Rich (Bank 1)";
            case "P0173": return "Fuel Trim Malfunction (Bank 2)";
            case "P0174": return "System Too Lean (Bank 2)";
            case "P0175": return "System Too Rich (Bank 2)";
            case "P0200": return "Injector Circuit Malfunction";
            case "P0230": return "Fuel Pump Primary Circuit Malfunction";
            case "P0300": return "Random/Multiple Cylinder Misfire";
            case "P0301": return "Cylinder 1 Misfire Detected";
            case "P0302": return "Cylinder 2 Misfire Detected";
            case "P0303": return "Cylinder 3 Misfire Detected";
            case "P0304": return "Cylinder 4 Misfire Detected";
            case "P0305": return "Cylinder 5 Misfire Detected";
            case "P0306": return "Cylinder 6 Misfire Detected";
            case "P0307": return "Cylinder 7 Misfire Detected";
            case "P0308": return "Cylinder 8 Misfire Detected";
            case "P0325": return "Knock Sensor 1 Circuit (Bank 1)";
            case "P0335": return "Crankshaft Position Sensor Circuit";
            case "P0340": return "Camshaft Position Sensor Circuit";
            case "P0400": return "EGR Flow Malfunction";
            case "P0401": return "EGR Insufficient Flow Detected";
            case "P0420": return "Catalyst System Efficiency Below Threshold (Bank 1)";
            case "P0421": return "Warm Up Catalyst Efficiency Below Threshold (Bank 1)";
            case "P0430": return "Catalyst System Efficiency Below Threshold (Bank 2)";
            case "P0440": return "EVAP System Malfunction";
            case "P0441": return "EVAP Incorrect Purge Flow";
            case "P0442": return "EVAP Small Leak Detected";
            case "P0443": return "EVAP Purge Control Circuit";
            case "P0446": return "EVAP Vent Control Circuit";
            case "P0455": return "EVAP Large Leak Detected";
            case "P0500": return "Vehicle Speed Sensor Malfunction";
            case "P0505": return "Idle Air Control System Malfunction";
            case "P0560": return "System Voltage Malfunction";
            case "P0600": return "Serial Communication Link Malfunction";
            case "P0601": return "Internal Control Module Memory Check Sum Error";
            case "P0700": return "TCM Request MIL Illumination";
            case "P0720": return "Output Speed Sensor Circuit";
            case "P0730": return "Incorrect Gear Ratio";
            default:
                if (code.startsWith("P")) return "Powertrain DTC";
                if (code.startsWith("C")) return "Chassis DTC";
                if (code.startsWith("B")) return "Body DTC";
                if (code.startsWith("U")) return "Network Communication DTC";
                return "Unknown DTC";
        }
    }

    @Override
    public String toString() {
        return code + " — " + description;
    }
}
