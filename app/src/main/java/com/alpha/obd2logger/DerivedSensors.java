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
 *   - AeroDensity Intelligence (AAD / MAD / BAD + SAE CF / DA / grains)
 */
public final class DerivedSensors {

    // ── Fuel constants ──────────────────────────────────────────
    // Now sourced from FuelProperties for multi-fuel support.
    // Legacy constants kept for backwards compatibility with any
    // external references, but all internal calculations use
    // FuelProperties.get(fuelMode) for accuracy.

    /** Sea-level atmospheric pressure (kPa) — fallback when Baro PID unavailable */
    public static final double SEA_LEVEL_PRESSURE_KPA = 101.325;

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

        FuelProperties.Props props = FuelProperties.get(fuelMode);
        double afr = props.stoichAFR;
        double density = props.densityGL;

        // km/L = speed * density * AFR / (MAF * 3600)
        double kml = (speedKmh * density * afr) / (mafGs * 3600.0);

        // Sanity check: reasonable range is 2–30 km/L (diesel can be higher)
        if (kml < 1.0 || kml > 60.0) return null;
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

    // ═══════════════════════════════════════════════════════════════
    //  Air Density (AeroDensity Intelligence — AAD / MAD / BAD)
    // ═══════════════════════════════════════════════════════════════
    //
    // Ideal gas + water vapor correction:
    //   ρ = (P_d / (R_d × T)) + (P_v / (R_v × T))
    //
    // Magnus saturation vapor pressure (hPa):
    //   P_sat = 6.1078 × 10^(7.5×T / (T+237.3))
    //
    // MAD uses absolute humidity conserved from ambient conditions
    // (RH ambient + T ambient + P ambient) → mixing ratio → Pv at MAP.
    // Applying ambient RH at hot IAT is physically wrong under boost.
    //
    // AeroDensity display unit: lbs/1000ft³ (kg/m³ × 62.428)
    //   SAE J1349: 99.0 kPa dry, 25°C → ~72.2 lbs/1000ft³
    //   SAE J607:  101.325 kPa dry, 15°C ≈ 76.4 lbs/1000ft³

    /** Specific gas constant for dry air, J/(kg·K) */
    public static final double R_DRY = 287.058;
    /** Specific gas constant for water vapor, J/(kg·K) */
    public static final double R_VAPOR = 461.495;
    /** kg/m³ → lbs/1000ft³ */
    public static final double KG_M3_TO_LBS_1000FT3 = 62.428;
    /** SAE J1349 standard ambient air density (lbs/1000ft³) */
    public static final double SAE_J1349_AAD = 72.2;
    /** SAE J607 standard ambient air density (lbs/1000ft³) */
    public static final double SAE_J607_AAD = 76.4;
    /** O2 mass fraction of dry air (~23.14%) — used by OMD */
    public static final double O2_MASS_FRACTION_DRY = 0.2314;
    /** Max vapor partial pressure fraction of total pressure */
    private static final double MAX_VAPOR_FRACTION = 0.98;

    /**
     * Magnus saturation vapor pressure in hPa for a dry-bulb temperature °C.
     * Clamps extreme colder/hotter inputs to keep the formula stable.
     */
    public static double saturationVaporPressureHpa(double tempC) {
        double t = tempC;
        if (t < -60.0) t = -60.0;
        if (t > 100.0) t = 100.0;
        return 6.1078 * Math.pow(10.0, (7.5 * t) / (t + 237.3));
    }

    /**
     * Clamp humidity to 0–100, defaulting missing to the provided fallback.
     */
    public static double clampHumidity(Double humidityPct, double fallback) {
        if (humidityPct == null) return fallback;
        if (humidityPct < 0) return 0.0;
        if (humidityPct > 100) return 100.0;
        return humidityPct;
    }

    /**
     * Actual vapor pressure (hPa) from RH + temperature, clamped so Pv ≤ maxFrac * P.
     */
    public static double vaporPressureHpa(double tempC, double humidityPct, double totalPressureHpa) {
        double rh = clampHumidity(humidityPct, 50.0);
        double sat = saturationVaporPressureHpa(tempC);
        double pv = sat * (rh / 100.0);
        double maxPv = Math.max(0.1, totalPressureHpa * MAX_VAPOR_FRACTION);
        if (pv > maxPv) pv = maxPv;
        if (pv < 0) pv = 0;
        return pv;
    }

    /**
     * Humidity ratio w = kg water / kg dry air (dimensionless mixing ratio).
     */
    public static Double humidityRatio(Double pressureKPa, Double tempC, Double humidityPct) {
        if (pressureKPa == null || tempC == null) return null;
        if (pressureKPa <= 0) return null;
        double pHpa = pressureKPa * 10.0;
        double pv = vaporPressureHpa(tempC, clampHumidity(humidityPct, 50.0), pHpa);
        double dry = pHpa - pv;
        if (dry <= 0.1) return null;
        return 0.62198 * pv / dry;
    }

    /**
     * Air density in kg/m³ from absolute pressure, temperature and relative humidity.
     */
    public static Double airDensityKgM3(Double pressureKPa, Double tempC, Double humidityPct) {
        if (pressureKPa == null || tempC == null) return null;
        if (pressureKPa <= 0 || pressureKPa > 500) return null;
        if (tempC < -60 || tempC > 200) return null;

        double pHpa = pressureKPa * 10.0;
        double pvHpa = vaporPressureHpa(tempC, clampHumidity(humidityPct, 50.0), pHpa);
        return airDensityKgM3FromVapor(pressureKPa, tempC, pvHpa);
    }

    /**
     * Air density in kg/m³ given vapor pressure in hPa (absolute humidity path).
     */
    public static Double airDensityKgM3FromVapor(Double pressureKPa, Double tempC, double vaporPressureHpa) {
        if (pressureKPa == null || tempC == null) return null;
        if (pressureKPa <= 0 || pressureKPa > 500) return null;
        if (tempC < -60 || tempC > 200) return null;

        double tempK = tempC + 273.15;
        double pressurePa = pressureKPa * 1000.0;
        double vaporPa = vaporPressureHpa * 100.0;
        double maxVaporPa = pressurePa * MAX_VAPOR_FRACTION;
        if (vaporPa > maxVaporPa) vaporPa = maxVaporPa;
        if (vaporPa < 0) vaporPa = 0;
        double dryPa = pressurePa - vaporPa;
        if (dryPa <= 0) return null;

        double density = (dryPa / (R_DRY * tempK)) + (vaporPa / (R_VAPOR * tempK));
        if (density < 0.1 || density > 10.0) return null;
        return density;
    }

    /**
     * Dry-air-only density (kg/m³) after removing water vapor by partial pressure.
     * Used by Oxygen Mass Density (no double humidity correction).
     */
    public static Double dryAirDensityKgM3(Double pressureKPa, Double tempC, Double humidityPct) {
        if (pressureKPa == null || tempC == null) return null;
        if (pressureKPa <= 0 || pressureKPa > 500) return null;
        double pHpa = pressureKPa * 10.0;
        double pvHpa = vaporPressureHpa(tempC, clampHumidity(humidityPct, 50.0), pHpa);
        double dryHpa = pHpa - pvHpa;
        if (dryHpa <= 0.1) return null;
        double tempK = tempC + 273.15;
        double dryPa = dryHpa * 100.0;
        return dryPa / (R_DRY * tempK);
    }

    /**
     * Air density in lbs/1000ft³ (standard display unit).
     */
    public static Double airDensityLbs1000ft3(Double pressureKPa, Double tempC, Double humidityPct) {
        Double kgM3 = airDensityKgM3(pressureKPa, tempC, humidityPct);
        if (kgM3 == null) return null;
        double lbs = kgM3 * KG_M3_TO_LBS_1000FT3;
        return Math.round(lbs * 10.0) / 10.0;
    }

    public static Double airDensityLbs1000ft3FromVapor(Double pressureKPa, Double tempC, double vaporPressureHpa) {
        Double kgM3 = airDensityKgM3FromVapor(pressureKPa, tempC, vaporPressureHpa);
        if (kgM3 == null) return null;
        double lbs = kgM3 * KG_M3_TO_LBS_1000FT3;
        return Math.round(lbs * 10.0) / 10.0;
    }

    /**
     * Ambient Air Density (AAD) — air density around the vehicle.
     * Callers should pass real baro/ambient when available; nulls indicate fallback.
     */
    public static Double ambientAirDensity(Double baroKpa, Double ambientTempC, Double humidityPct) {
        double baro = (baroKpa != null && baroKpa > 50.0) ? baroKpa : SEA_LEVEL_PRESSURE_KPA;
        double temp = (ambientTempC != null) ? ambientTempC : 25.0;
        return airDensityLbs1000ft3(baro, temp, humidityPct);
    }

    /**
     * Manifold Air Density (MAD) using ambient absolute humidity conservation.
     *
     * Mixing ratio is computed at ambient conditions, then converted to a vapor
     * partial pressure at manifold absolute pressure. RH is NOT reused at IAT —
     * heated boost air has the same absolute humidity (to first order) but much
     * lower relative humidity.
     *
     * @param mapKpa         Intake manifold absolute pressure (kPa)
     * @param iatTempC       Intake air temperature (°C)
     * @param ambientBaroKpa Ambient / station barometric pressure (kPa)
     * @param ambientTempC   Ambient temperature (°C)
     * @param ambientRhPct   Ambient relative humidity (%)
     */
    public static Double manifoldAirDensity(Double mapKpa, Double iatTempC,
                                            Double ambientBaroKpa, Double ambientTempC,
                                            Double ambientRhPct) {
        if (mapKpa == null) return null;
        double temp = (iatTempC != null) ? iatTempC : ((ambientTempC != null) ? ambientTempC : 25.0);
        double ambBaro = (ambientBaroKpa != null && ambientBaroKpa > 50.0)
                ? ambientBaroKpa : SEA_LEVEL_PRESSURE_KPA;
        double ambTemp = (ambientTempC != null) ? ambientTempC : temp;
        Double w = humidityRatio(ambBaro, ambTemp, ambientRhPct);
        if (w == null) {
            // Fallback: direct RH path (legacy behaviour)
            return airDensityLbs1000ft3(mapKpa, temp, ambientRhPct);
        }
        // Pv_map = w * P_map / (0.62198 + w)
        double mapHpa = mapKpa * 10.0;
        double pvMap = (w * mapHpa) / (0.62198 + w);
        // Also never exceed saturation at IAT (condensation drop)
        double satIat = saturationVaporPressureHpa(temp);
        if (pvMap > satIat) pvMap = satIat;
        return airDensityLbs1000ft3FromVapor(mapKpa, temp, pvMap);
    }

    /**
     * Legacy MAD signature kept for older call sites / tests: approximates with RH at IAT.
     * Prefer the absolute-humidity overload.
     */
    public static Double manifoldAirDensity(Double mapKpa, Double iatTempC, Double humidityPct) {
        // Delegate via absolute-humidity using MAP as ambient proxy (degraded but compatible).
        return manifoldAirDensity(mapKpa, iatTempC, mapKpa, iatTempC, humidityPct);
    }


    /**
     * Manifold density kg/m³ with absolute-humidity conservation.
     */
    public static Double manifoldAirDensityKgM3(Double mapKpa, Double iatTempC,
                                                Double ambientBaroKpa, Double ambientTempC,
                                                Double ambientRhPct) {
        Double lbs = manifoldAirDensity(mapKpa, iatTempC, ambientBaroKpa, ambientTempC, ambientRhPct);
        if (lbs == null) return null;
        return lbs / KG_M3_TO_LBS_1000FT3;
    }

    /**
     * Boost Air Density (BAD) — additional density from forced induction.
     * BAD = MAD - AAD
     */
    public static Double boostAirDensity(Double mad, Double aad) {
        if (mad == null || aad == null) return null;
        double bad = mad - aad;
        return Math.round(bad * 10.0) / 10.0;
    }

    /**
     * Air Density Percentage — compared to SAE J1349 standard (72.2 lbs/1000ft³).
     */
    public static Double airDensityPercent(Double aad) {
        if (aad == null) return null;
        double pct = (aad / SAE_J1349_AAD) * 100.0;
        return Math.round(pct * 10.0) / 10.0;
    }

    /**
     * Density Altitude (ft) using pressure altitude + virtual-temperature ISA offset.
     *
     * Steps:
     *   1) Pressure altitude (ft) from barometric station pressure
     *   2) Virtual temperature from humidity
     *   3) ISA temp at PA and 120 ft/°C approx density-altitude correction
     *
     * At ISA sea level dry 15°C / 1013.25 hPa → ≈ 0 ft.
     */
    public static Double densityAltitudeFt(Double baroKpa, Double tempC, Double humidityPct) {
        if (baroKpa == null || tempC == null) return null;
        if (baroKpa <= 0) return null;

        double rh = clampHumidity(humidityPct, 50.0);
        double pressureHpa = baroKpa * 10.0;

        // Pressure altitude (ICAO troposphere approximation)
        double ratio = pressureHpa / 1013.25;
        if (ratio <= 0) return null;
        double pressureAltFt = 145366.45 * (1.0 - Math.pow(ratio, 0.190284));

        // Virtual temperature (°C) for humidity correction
        double pv = vaporPressureHpa(tempC, rh, pressureHpa);
        double tempK = tempC + 273.15;
        double virtualK = tempK / (1.0 - (pv / pressureHpa) * 0.378);
        double virtualC = virtualK - 273.15;

        // ISA temperature at pressure altitude (°C): 15 − 1.98 per 1000 ft
        double isaTempC = 15.0 - 1.98 * (pressureAltFt / 1000.0);

        // ~120 ft per °C density altitude rule of thumb with virtual temperature
        double da = pressureAltFt + 120.0 * (virtualC - isaTempC);
        return (double) Math.round(da);
    }

    /**
     * SAE J1349 Correction Factor — normalizes horsepower to 99.0 kPa dry / 25°C.
     * CF = (P_std / P_dry_actual) × sqrt(T_actual / T_std)
     */
    public static Double saeJ1349CorrectionFactor(Double baroKpa, Double tempC, Double humidityPct) {
        if (baroKpa == null || tempC == null) return null;
        if (baroKpa <= 0) return null;

        double rh = clampHumidity(humidityPct, 50.0);
        double tempK = tempC + 273.15;
        double pressureHpa = baroKpa * 10.0;
        double vaporPressure = vaporPressureHpa(tempC, rh, pressureHpa);
        double dryPressure = pressureHpa - vaporPressure;
        if (dryPressure < 50.0) return null; // absurd

        double pStd = 990.0; // hPa
        double tStd = 298.15; // K
        double cf = (pStd / dryPressure) * Math.sqrt(tempK / tStd);
        if (cf < 0.5 || cf > 1.5) return null;
        return Math.round(cf * 1000.0) / 1000.0;
    }

    /**
     * Grains of water per pound of dry air (absolute humidity).
     * Typical engine-tuning range 20–120 grains.
     */
    public static Double grainsH2O(Double tempC, Double humidityPct, Double baroKpa) {
        if (tempC == null) return null;
        double rh = clampHumidity(humidityPct, 50.0);
        double pressureHpa = (baroKpa != null && baroKpa > 50.0) ? baroKpa * 10.0 : 1013.25;
        double vaporPressure = vaporPressureHpa(tempC, rh, pressureHpa);
        double dry = pressureHpa - vaporPressure;
        if (dry <= 0.1) return null;

        // Humidity ratio (kg water / kg dry air) → grains/lb (×7000)
        double humidityRatio = 0.62198 * vaporPressure / dry;
        double grains = humidityRatio * 7000.0;
        return Math.round(grains * 10.0) / 10.0;
    }
}
