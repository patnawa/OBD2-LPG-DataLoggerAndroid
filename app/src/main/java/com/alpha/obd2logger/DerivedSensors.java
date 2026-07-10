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

    // ═══════════════════════════════════════════════════════════════
    //  Air Density (Banks iDash style — AAD / MAD / BAD)
    // ═══════════════════════════════════════════════════════════════
    //
    // Air density is the most direct measurement of oxygen molecules available
    // for combustion. Unlike boost pressure alone, it accounts for temperature
    // effects on oxygen content — a critical element for horsepower.
    //
    // Formula (Ideal Gas Law + water vapor correction):
    //   ρ = (P_d / (R_d × T)) + (P_v / (R_v × T))
    //
    // Where:
    //   T   = temperature in Kelvin (°C + 273.15)
    //   P   = total absolute pressure (Pa)
    //   P_v = water vapor partial pressure = saturation_vp × (RH/100)
    //   P_d = dry air partial pressure = P - P_v
    //   R_d = 287.058 J/(kg·K) — specific gas constant for dry air
    //   R_v = 461.495 J/(kg·K) — specific gas constant for water vapor
    //
    // Saturation vapor pressure (Magnus formula):
    //   P_sat(hPa) = 6.1078 × 10^(7.5×T / (T+237.3))
    //
    // Banks display unit: lbs/1000ft³ (typical range 0–300)
    //   SAE J1349 standard: 14.4 psia, 77°F, 0% RH → AAD = 72.2 lbs/1000ft³
    //   SAE J607 standard: AAD = 76.4 lbs/1000ft³
    //
    // Conversion: kg/m³ × 62.428 = lbs/1000ft³

    /** Specific gas constant for dry air, J/(kg·K) */
    private static final double R_DRY = 287.058;
    /** Specific gas constant for water vapor, J/(kg·K) */
    private static final double R_VAPOR = 461.495;
    /** kg/m³ → lbs/1000ft³ */
    private static final double KG_M3_TO_LBS_1000FT3 = 62.428;
    /** SAE J1349 standard ambient air density (lbs/1000ft³) */
    public static final double SAE_J1349_AAD = 72.2;
    /** SAE J607 standard ambient air density (lbs/1000ft³) */
    public static final double SAE_J607_AAD = 76.4;

    /**
     * Compute air density in kg/m³ using the ideal gas law with humidity correction.
     *
     * @param pressureKPa  Absolute pressure in kPa
     * @param tempC        Temperature in °C
     * @param humidityPct  Relative humidity (0–100%), default 50 if unknown
     * @return Air density in kg/m³, or null if inputs are invalid
     */
    public static Double airDensityKgM3(Double pressureKPa, Double tempC, Double humidityPct) {
        if (pressureKPa == null || tempC == null) return null;
        if (pressureKPa <= 0 || pressureKPa > 500) return null;
        if (tempC < -60 || tempC > 200) return null;

        double rh = (humidityPct != null && humidityPct >= 0 && humidityPct <= 100)
                ? humidityPct : 50.0;

        double tempK = tempC + 273.15;
        double pressurePa = pressureKPa * 1000.0; // kPa → Pa

        // Saturation vapor pressure (Magnus formula) in hPa
        double satVpHpa = 6.1078 * Math.pow(10.0, (7.5 * tempC) / (tempC + 237.3));
        // Actual vapor pressure in Pa
        double vaporPa = satVpHpa * (rh / 100.0) * 100.0; // hPa → Pa
        // Dry air pressure
        double dryPa = pressurePa - vaporPa;

        // ρ = (P_d / (R_d × T)) + (P_v / (R_v × T))
        double density = (dryPa / (R_DRY * tempK)) + (vaporPa / (R_VAPOR * tempK));

        // Sanity check: typical range 0.3–5.0 kg/m³
        if (density < 0.1 || density > 10.0) return null;
        return density;
    }

    /**
     * Compute air density in lbs/1000ft³ (Banks iDash display unit).
     *
     * @param pressureKPa  Absolute pressure in kPa
     * @param tempC        Temperature in °C
     * @param humidityPct  Relative humidity (0–100%)
     * @return Air density in lbs/1000ft³, or null if inputs are invalid
     */
    public static Double airDensityLbs1000ft3(Double pressureKPa, Double tempC, Double humidityPct) {
        Double kgM3 = airDensityKgM3(pressureKPa, tempC, humidityPct);
        if (kgM3 == null) return null;
        double lbs = kgM3 * KG_M3_TO_LBS_1000FT3;
        return Math.round(lbs * 10.0) / 10.0;
    }

    /**
     * Ambient Air Density (AAD) — air density around the vehicle.
     *
     * Uses:
     *   - Barometric Pressure (PID 0x33) or weather API pressure
     *   - Ambient Air Temp (PID 0x46) or weather API temp
     *   - Relative Humidity from WeatherProvider (OBD2 has no humidity PID)
     *
     * @param baroKpa       Barometric pressure (kPa) from PID 0x33 or weather API
     * @param ambientTempC  Ambient temperature (°C) from PID 0x46 or weather API
     * @param humidityPct    Relative humidity (%) from WeatherProvider
     * @return AAD in lbs/1000ft³, or null if inputs are invalid
     */
    public static Double ambientAirDensity(Double baroKpa, Double ambientTempC, Double humidityPct) {
        // Fall back to sea-level pressure if baro unavailable
        double baro = (baroKpa != null && baroKpa > 50.0) ? baroKpa : SEA_LEVEL_PRESSURE_KPA;
        double temp = (ambientTempC != null) ? ambientTempC : 25.0;
        return airDensityLbs1000ft3(baro, temp, humidityPct);
    }

    /**
     * Manifold Air Density (MAD) — air density inside the intake manifold.
     *
     * Uses:
     *   - MAP (PID 0x0B) — absolute pressure in manifold
     *   - IAT (PID 0x0F) — intake air temperature
     *   - Relative Humidity from WeatherProvider (same ambient RH, heated air
     *     in manifold has lower effective RH but this is a reasonable approximation)
     *
     * @param mapKpa       Intake manifold absolute pressure (kPa)
     * @param iatTempC     Intake air temperature (°C)
     * @param humidityPct   Relative humidity (%) from WeatherProvider
     * @return MAD in lbs/1000ft³, or null if inputs are invalid
     */
    public static Double manifoldAirDensity(Double mapKpa, Double iatTempC, Double humidityPct) {
        if (mapKpa == null) return null;
        double temp = (iatTempC != null) ? iatTempC : 25.0;
        return airDensityLbs1000ft3(mapKpa, temp, humidityPct);
    }

    /**
     * Boost Air Density (BAD) — additional density from forced induction.
     *
     * BAD = MAD - AAD
     *
     * Positive = density gained from turbo/supercharger
     * Zero or negative = no boost benefit (N/A engine or idle)
     *
     * This is a more insightful performance measurement than boost pressure alone,
     * because it accounts for temperature's effect on oxygen content.
     *
     * @param mad  Manifold Air Density (lbs/1000ft³)
     * @param aad  Ambient Air Density (lbs/1000ft³)
     * @return BAD in lbs/1000ft³ (can be negative)
     */
    public static Double boostAirDensity(Double mad, Double aad) {
        if (mad == null || aad == null) return null;
        double bad = mad - aad;
        return Math.round(bad * 10.0) / 10.0;
    }

    /**
     * Air Density Percentage — compared to SAE J1349 standard.
     *
     * 100% = standard ambient conditions (77°F, 14.4 psia, 0% RH)
     * >100% = denser than standard (more oxygen, more power)
     * <100% = thinner than standard (less oxygen, less power)
     *
     * @param aad  Ambient Air Density (lbs/1000ft³)
     * @return percentage (0–200%), or null
     */
    public static Double airDensityPercent(Double aad) {
        if (aad == null) return null;
        double pct = (aad / SAE_J1349_AAD) * 100.0;
        return Math.round(pct * 10.0) / 10.0;
    }

    /**
     * Density Altitude — the altitude at which standard atmosphere
     * would have the same air density as current conditions.
     *
     * Lower DA = denser air (better performance, like being at sea level)
     * Higher DA = thinner air (worse performance, like being at high altitude)
     *
     * Approximation formula (from NOAA/NWS):
     *   DA(ft) = 145366 × (1 - (17.326 × T / (T + 273.15 - 0.5556×(1-RH)) )^0.235 )
     *
     * Simplified when pressure is known:
     *   Use barometric pressure + temperature directly
     *
     * @param baroKpa      Barometric pressure (kPa)
     * @param tempC        Ambient temperature (°C)
     * @param humidityPct   Relative humidity (%)
     * @return Density altitude in feet, or null
     */
    public static Double densityAltitudeFt(Double baroKpa, Double tempC, Double humidityPct) {
        if (baroKpa == null || tempC == null) return null;

        double rh = (humidityPct != null && humidityPct >= 0 && humidityPct <= 100)
                ? humidityPct : 50.0;

        double tempK = tempC + 273.15;
        double pressureHpa = baroKpa * 10.0; // kPa → hPa

        // Saturation vapor pressure (hPa)
        double satVp = 6.1078 * Math.pow(10.0, (7.5 * tempC) / (tempC + 237.3));
        double vaporPressure = satVp * (rh / 100.0);

        // Density altitude using the full formula
        // DA = 145366 × [1 - (P/P0)^(0.1903) × (T/T0)^(−0.5379)]
        // where P0=1013.25 hPa, T0=288.15 K (15°C standard atmosphere)
        // But we use the simpler approximation that includes humidity:
        double virtualTemp = tempK / (1 - (vaporPressure / pressureHpa) * (1 - 0.378));
        double pressureRatio = pressureHpa / 1013.25;
        double da = 145366.0 * (1.0 - Math.pow(pressureRatio, 0.1903)
                * Math.pow(virtualTemp / 288.15, 0.5379));

        return Math.round(da);
    }

    /**
     * SAE J1349 Correction Factor — normalizes horsepower to standard conditions.
     *
     * CF = (P_std / P_actual) × sqrt(T_actual / T_std)
     *
     * Where P_std = 99.0 kPa (990 hPa, dry air at SAE J1349)
     *       T_std = 298.15 K (25°C / 77°F)
     *
     * @param baroKpa      Barometric pressure (kPa)
     * @param tempC        Ambient temperature (°C)
     * @param humidityPct   Relative humidity (%)
     * @return Correction factor (typically 0.9–1.1), or null
     */
    public static Double saeJ1349CorrectionFactor(Double baroKpa, Double tempC, Double humidityPct) {
        if (baroKpa == null || tempC == null) return null;

        double rh = (humidityPct != null && humidityPct >= 0 && humidityPct <= 100)
                ? humidityPct : 50.0;

        double tempK = tempC + 273.15;
        double pressureHpa = baroKpa * 10.0;

        // Water vapor pressure
        double satVp = 6.1078 * Math.pow(10.0, (7.5 * tempC) / (tempC + 237.3));
        double vaporPressure = satVp * (rh / 100.0);

        // SAE J1349 standard: 990 hPa dry, 298.15 K (25°C), 0% RH
        double pStd = 990.0; // hPa
        double tStd = 298.15; // K

        // Dry pressure (actual)
        double dryPressure = pressureHpa - vaporPressure;

        double cf = (pStd / dryPressure) * Math.sqrt(tempK / tStd);

        return Math.round(cf * 1000.0) / 1000.0;
    }

    /**
     * Grains of water per pound of dry air — a measure of absolute humidity
     * used in engine tuning (e.g., 60–80 grains is typical; >120 is very humid).
     *
     * @param tempC        Ambient temperature (°C)
     * @param humidityPct   Relative humidity (%)
     * @param baroKpa       Barometric pressure (kPa)
     * @return Grains H2O per lb dry air, or null
     */
    public static Double grainsH2O(Double tempC, Double humidityPct, Double baroKpa) {
        if (tempC == null || humidityPct == null) return null;

        double rh = (humidityPct >= 0 && humidityPct <= 100) ? humidityPct : 50.0;
        double pressureHpa = (baroKpa != null && baroKpa > 50.0) ? baroKpa * 10.0 : 1013.25;

        // Saturation vapor pressure (hPa)
        double satVp = 6.1078 * Math.pow(10.0, (7.5 * tempC) / (tempC + 237.3));
        double vaporPressure = satVp * (rh / 100.0);

        // Humidity ratio (kg water / kg dry air)
        double humidityRatio = 0.62198 * vaporPressure / (pressureHpa - vaporPressure);

        // Convert to grains per lb: 1 kg/kg = 7000 grains/lb
        double grains = humidityRatio * 7000.0;

        return Math.round(grains * 10.0) / 10.0;
    }
}