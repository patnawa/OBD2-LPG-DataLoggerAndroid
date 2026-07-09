package com.alpha.obd2logger;

/**
 * Computes derived sensor values from raw OBD2 PID data.
 *
 * Derived sensors don't have their own OBD2 PID hex — they are
 * calculated from existing PIDs in real-time. This avoids polluting
 * the PID catalogue with virtual entries.
 *
 * Currently computes:
 *   - Fuel Consumption (km/L) — from MAF Air Flow + Vehicle Speed
 *   - Fuel Consumption (L/100km) — inverse of km/L
 *   - Turbo Boost Pressure (kPa/psi/bar) — MAP minus Barometric Pressure
 */
public final class DerivedSensors {

    // ── Fuel constants ──────────────────────────────────────────
    /** Petrol density (g/L) at 15°C */
    private static final double PETROL_DENSITY_GL = 737.0;
    /** LPG density (g/L) at 15°C (propane/butane mix) */
    private static final double LPG_DENSITY_GL = 510.0;
    /** CNG density (g/L) compressed — approximate */
    private static final double CNG_DENSITY_GL = 0.72; // g/L at STP — not meaningful for mass-flow calc
    /** Stoichiometric air-fuel ratio for petrol */
    private static final double AFR_PETROL = 14.7;
    /** Stoichiometric air-fuel ratio for LPG (propane/butane) */
    private static final double AFR_LPG = 15.5;

    /** Sea-level atmospheric pressure (kPa) — fallback when Baro PID unavailable */
    private static final double SEA_LEVEL_PRESSURE_KPA = 101.325;

    private DerivedSensors() {}

    // ═══════════════════════════════════════════════════════════════
    //  Fuel Consumption
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute instantaneous fuel consumption in km/L.
     *
     * Formula:
     *   fuel_g_s = MAF_g_s / AFR
     *   fuel_L_h = fuel_g_s * 3600 / fuel_density
     *   km_L     = speed_kmh / fuel_L_h
     *
     * Simplified one-step:
     *   km_L = speed_kmh * fuel_density * AFR / (MAF_g_s * 3600)
     *
     * @param mafGs    Mass Air Flow (g/s) — from PID 0x10
     * @param speedKmh Vehicle Speed (km/h) — from PID 0x0D
     * @param fuelMode PETROL or LPG/CNG — determines AFR + density
     * @return km/L, or null if inputs are invalid (speed too low, MAF zero, etc.)
     */
    public static Double fuelConsumptionKmL(Double mafGs, Double speedKmh, FuelMode fuelMode) {
        if (mafGs == null || speedKmh == null) return null;
        if (mafGs <= 0) return null;
        if (speedKmh < 2.0) return null; // avoid divide-by-zero / absurd values at idle

        double afr;
        double density;
        if (fuelMode == FuelMode.LPG) {
            afr = AFR_LPG;
            density = LPG_DENSITY_GL;
        } else {
            afr = AFR_PETROL;
            density = PETROL_DENSITY_GL;
        }

        // km/L = speed * density * AFR / (MAF * 3600)
        double kml = (speedKmh * density * afr) / (mafGs * 3600.0);

        // Sanity check: reasonable range is 2–30 km/L
        if (kml < 1.0 || kml > 50.0) return null;
        return Math.round(kml * 100.0) / 100.0;
    }

    /**
     * Compute fuel consumption in L/100km (more common display format).
     */
    public static Double fuelConsumptionL100km(Double mafGs, Double speedKmh, FuelMode fuelMode) {
        Double kml = fuelConsumptionKmL(mafGs, speedKmh, fuelMode);
        if (kml == null || kml <= 0) return null;
        double l100 = 100.0 / kml;
        return Math.round(l100 * 100.0) / 100.0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Turbo Boost Pressure
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute turbo/supercharger boost pressure.
     *
     * Formula: Boost = MAP - Barometric Pressure
     *
     * Positive = boost (turbo spooling)
     * Zero or negative = vacuum (N/A engine or idle)
     *
     * @param mapKpa  Intake Manifold Absolute Pressure (kPa) — from PID 0x0B
     * @param baroKpa Barometric Pressure (kPa) — from PID 0x33, or null for sea-level fallback
     * @return boost in kPa, or null if MAP is unavailable
     */
    public static Double boostPressureKpa(Double mapKpa, Double baroKpa) {
        if (mapKpa == null) return null;
        double baro = (baroKpa != null && baroKpa > 50.0) ? baroKpa : SEA_LEVEL_PRESSURE_KPA;
        double boost = mapKpa - baro;
        // Reasonable range: -30 kPa (idle vacuum) to +250 kPa (big turbo)
        if (boost < -50.0 || boost > 400.0) return null;
        return Math.round(boost * 10.0) / 10.0;
    }

    /**
     * Boost in PSI (common aftermarket gauge unit).
     */
    public static Double boostPressurePsi(Double mapKpa, Double baroKpa) {
        Double boostKpa = boostPressureKpa(mapKpa, baroKpa);
        if (boostKpa == null) return null;
        double psi = boostKpa * 0.145038;
        return Math.round(psi * 100.0) / 100.0;
    }

    /**
     * Boost in BAR (European unit).
     */
    public static Double boostPressureBar(Double mapKpa, Double baroKpa) {
        Double boostKpa = boostPressureKpa(mapKpa, baroKpa);
        if (boostKpa == null) return null;
        double bar = boostKpa / 100.0;
        return Math.round(bar * 1000.0) / 1000.0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  DPF (Diesel Particulate Filter)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Interpret DPF regeneration status from PID 0x8C raw value.
     * @param rawValue raw byte A from PID 0x8C response
     * @return human-readable status string
     */
    public static String dpfRegenStatus(Double rawValue) {
        if (rawValue == null) return "N/A";
        int val = rawValue.intValue();
        if (val == 0) return "Idle";
        if (val == 1) return "Regen Active";
        if (val == 2) return "Regen Requested";
        if (val == 3) return "Regen Blocked";
        return "Unknown (" + val + ")";
    }

    /**
     * DPF health assessment from soot load + ash load.
     * @param sootPct soot load percentage (PID 0x7A)
     * @param ashGrams ash accumulation in grams (PID 0x8B) — optional
     * @return "Clean" / "Moderate" / "Warning" / "Critical"
     */
    public static String dpfHealthStatus(Double sootPct, Double ashGrams) {
        if (sootPct == null) return "N/A";
        if (sootPct < 40.0) return "Clean";
        if (sootPct < 70.0) return "Moderate";
        if (sootPct < 90.0) return "Warning";
        return "Critical";
    }
}