package com.alpha.obd2logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps DTC codes to the readiness monitors they affect.
 *
 * When a DTC is stored, the corresponding readiness monitor typically becomes
 * incomplete. Professional scanners show this correlation so the technician
 * knows which monitors will fail after the DTC is cleared.
 *
 * Example: P0420 → Catalyst monitor will be incomplete
 *         P0171 → Fuel System monitor will be incomplete
 */
public final class DtcMonitorCorrelation {

    private DtcMonitorCorrelation() {}

    /**
     * DTC prefix → affected monitor name.
     * This covers the most common SAE J2010 DTC-to-monitor mappings.
     */
    private static final Map<String, String> PREFIX_TO_MONITOR = new LinkedHashMap<>();

    /**
     * Specific DTC code → affected monitor name (overrides prefix lookup).
     */
    private static final Map<String, String> CODE_TO_MONITOR = new LinkedHashMap<>();

    static {
        // ── Specific code → monitor mappings ──
        // Misfire
        for (int i = 0; i <= 8; i++) {
            CODE_TO_MONITOR.put(String.format("P030%d", i), "Misfire");
        }
        CODE_TO_MONITOR.put("P0300", "Misfire");

        // Fuel System
        CODE_TO_MONITOR.put("P0170", "Fuel System");
        CODE_TO_MONITOR.put("P0171", "Fuel System");
        CODE_TO_MONITOR.put("P0172", "Fuel System");
        CODE_TO_MONITOR.put("P0173", "Fuel System");
        CODE_TO_MONITOR.put("P0174", "Fuel System");
        CODE_TO_MONITOR.put("P0175", "Fuel System");

        // Catalyst
        CODE_TO_MONITOR.put("P0420", "Catalyst");
        CODE_TO_MONITOR.put("P0421", "Catalyst");
        CODE_TO_MONITOR.put("P0422", "Catalyst");
        CODE_TO_MONITOR.put("P0423", "Catalyst");
        CODE_TO_MONITOR.put("P0424", "Catalyst");
        CODE_TO_MONITOR.put("P0430", "Catalyst");
        CODE_TO_MONITOR.put("P0431", "Catalyst");
        CODE_TO_MONITOR.put("P0432", "Catalyst");
        CODE_TO_MONITOR.put("P0433", "Catalyst");
        CODE_TO_MONITOR.put("P0434", "Catalyst");

        // Heated Catalyst
        CODE_TO_MONITOR.put("P0435", "Heated Catalyst");
        CODE_TO_MONITOR.put("P0436", "Heated Catalyst");
        CODE_TO_MONITOR.put("P0437", "Heated Catalyst");
        CODE_TO_MONITOR.put("P0438", "Heated Catalyst");
        CODE_TO_MONITOR.put("P0439", "Heated Catalyst");
        CODE_TO_MONITOR.put("P0440", "EVAP");
        CODE_TO_MONITOR.put("P0441", "EVAP");
        CODE_TO_MONITOR.put("P0442", "EVAP");
        CODE_TO_MONITOR.put("P0443", "EVAP");
        CODE_TO_MONITOR.put("P0444", "EVAP");
        CODE_TO_MONITOR.put("P0445", "EVAP");
        CODE_TO_MONITOR.put("P0446", "EVAP");
        CODE_TO_MONITOR.put("P0447", "EVAP");
        CODE_TO_MONITOR.put("P0448", "EVAP");
        CODE_TO_MONITOR.put("P0449", "EVAP");
        CODE_TO_MONITOR.put("P0450", "EVAP");
        CODE_TO_MONITOR.put("P0451", "EVAP");
        CODE_TO_MONITOR.put("P0452", "EVAP");
        CODE_TO_MONITOR.put("P0453", "EVAP");
        CODE_TO_MONITOR.put("P0454", "EVAP");
        CODE_TO_MONITOR.put("P0455", "EVAP");
        CODE_TO_MONITOR.put("P0456", "EVAP");
        CODE_TO_MONITOR.put("P0457", "EVAP");
        CODE_TO_MONITOR.put("P0458", "EVAP");
        CODE_TO_MONITOR.put("P0459", "EVAP");

        // Secondary Air
        CODE_TO_MONITOR.put("P0491", "Secondary Air");
        CODE_TO_MONITOR.put("P0492", "Secondary Air");
        CODE_TO_MONITOR.put("P0493", "Secondary Air");
        CODE_TO_MONITOR.put("P0494", "Secondary Air");
        CODE_TO_MONITOR.put("P0495", "Secondary Air");

        // O2 Sensor
        CODE_TO_MONITOR.put("P0130", "O2 Sensor");
        CODE_TO_MONITOR.put("P0131", "O2 Sensor");
        CODE_TO_MONITOR.put("P0132", "O2 Sensor");
        CODE_TO_MONITOR.put("P0133", "O2 Sensor");
        CODE_TO_MONITOR.put("P0134", "O2 Sensor");
        CODE_TO_MONITOR.put("P0135", "O2 Sensor Heater");
        CODE_TO_MONITOR.put("P0150", "O2 Sensor");
        CODE_TO_MONITOR.put("P0151", "O2 Sensor");
        CODE_TO_MONITOR.put("P0152", "O2 Sensor");
        CODE_TO_MONITOR.put("P0153", "O2 Sensor");
        CODE_TO_MONITOR.put("P0154", "O2 Sensor");
        CODE_TO_MONITOR.put("P0155", "O2 Sensor Heater");

        // EGR
        CODE_TO_MONITOR.put("P0400", "EGR/VVT System");
        CODE_TO_MONITOR.put("P0401", "EGR/VVT System");
        CODE_TO_MONITOR.put("P0402", "EGR/VVT System");
        CODE_TO_MONITOR.put("P0403", "EGR/VVT System");
        CODE_TO_MONITOR.put("P0404", "EGR/VVT System");
        CODE_TO_MONITOR.put("P0405", "EGR/VVT System");
        CODE_TO_MONITOR.put("P0406", "EGR/VVT System");

        // ── Diesel-specific ──
        // NOx
        CODE_TO_MONITOR.put("P2033", "NOx Catalyst");
        CODE_TO_MONITOR.put("P2034", "NOx Catalyst");
        CODE_TO_MONITOR.put("P2035", "NOx Catalyst");

        // DPF / PM Filter
        CODE_TO_MONITOR.put("P2002", "PM Filter Active");
        CODE_TO_MONITOR.put("P2463", "PM Filter Active");

        // ── Prefix-based fallbacks ──
        PREFIX_TO_MONITOR.put("P03", "Misfire");         // P0300-P0309
        PREFIX_TO_MONITOR.put("P01", "Fuel System");      // P0100-P0129 (MAF, O2, fuel trim)
        PREFIX_TO_MONITOR.put("P04", "EVAP");             // P0400-P0499 (EGR, EVAP, Catalyst)
        PREFIX_TO_MONITOR.put("P05", "Components");       // P0500-P0599 (VSS, ISC)
    }

    /**
     * Get the monitor name affected by a DTC code.
     * @param dtcCode the 5-character DTC code (e.g. "P0420")
     * @return monitor name, or null if no correlation is known
     */
    public static String getMonitorForCode(String dtcCode) {
        if (dtcCode == null || dtcCode.length() < 5) return null;

        // Exact match first
        String monitor = CODE_TO_MONITOR.get(dtcCode.toUpperCase());
        if (monitor != null) return monitor;

        // Prefix match
        String prefix = dtcCode.substring(0, 3).toUpperCase();
        return PREFIX_TO_MONITOR.get(prefix);
    }

    /**
     * Check if a DTC code affects a specific readiness monitor.
     */
    public static boolean affectsMonitor(String dtcCode, String monitorName) {
        String monitor = getMonitorForCode(dtcCode);
        if (monitor == null || monitorName == null) return false;
        return monitor.equalsIgnoreCase(monitorName);
    }

    /**
     * Generate a description of which monitors will be incomplete
     * given the current set of DTCs.
     *
     * @param dtcs list of DTC codes
     * @return human-readable description, e.g. "Catalyst, Fuel System will be incomplete"
     */
    public static String describeAffectedMonitors(java.util.List<DtcCode> dtcs) {
        if (dtcs == null || dtcs.isEmpty()) return "No monitors affected.";
        java.util.Set<String> affected = new java.util.LinkedHashSet<>();
        for (DtcCode c : dtcs) {
            String monitor = getMonitorForCode(c.getCode());
            if (monitor != null) affected.add(monitor);
        }
        if (affected.isEmpty()) return "No known monitor correlation for these codes.";
        return String.join(", ", affected) + " monitor(s) will be incomplete.";
    }
}
