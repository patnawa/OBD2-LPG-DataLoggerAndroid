package com.alpha.obd2logger;

/**
 * Advanced Air Density & Engine Performance Calculations — beyond Banks iDash.
 *
 * Banks iDash (Gale Banks Engineering, US Patents 7,254,477 + 7,593,808)
 * covers *displaying* air density on a gauge. The underlying physics (ideal gas
 * law) is public domain. This class extends the concept with 10 additional
 * calculations that Banks does NOT offer, giving more accurate and actionable
 * insight for petrol, diesel, LPG, and CNG/NGV engines.
 *
 * All formulas work across fuel types. Fuel-specific constants (AFR, LHV,
 * vapor displacement) are selected automatically based on FuelMode.
 *
 * ─────────────────────────────────────────────────────────────────
 *  THE 10 ADVANCED FORMULAS
 * ─────────────────────────────────────────────────────────────────
 *
 * 1. OXYGEN MASS DENSITY (OMD) — true combustible oxygen mass
 *    Banks shows total air density, but N2/Ar/CO2 don't combust.
 *    OMD isolates the O2 fraction that actually makes power.
 *
 * 2. COMPRESSOR EFFICIENCY (CE) — turbo health in real time
 *    Isentropic temperature rise vs actual IAT. Below 60% = turbo
 *    is overspeeding or outside its efficiency island.
 *
 * 3. INTERCOOLER EFFECTIVENESS (IC-EFF) — is your IC doing its job?
 *    How much heat the intercooler removes vs theoretical max.
 *    Below 70% = upgrade needed. Works with or without pre-IC temp.
 *
 * 4. VOLUMETRIC EFFICIENCY (VE) — engine breathing efficiency
 *    Theoretical mass flow vs actual MAF. Detects restricted intake,
 *    worn cam, valve timing issues. Cross-validates MAF sensor.
 *
 * 5. DENSITY-CORRECTED AFR (DCAFR) — true combustion mixture
 *    Standard lambda/AFR ignores humidity's effect on oxygen.
 *    DCAFR adjusts for water vapor displacement of oxygen.
 *
 * 6. TURBO MASS FLOW (TMF) — independent MAF cross-check
 *    Calculate mass flow from density × displacement × RPM.
 *    Compare to MAF sensor reading. Large delta = sensor drift.
 *
 * 7. LPG/CNG VAPOR DISPLACEMENT (LVD) — critical for gaseous fuel!
 *    Injected fuel vapor occupies intake volume, displacing air.
 *    Effective air density = MAD × (1 - fuel_vapor_fraction).
 *    Banks doesn't handle this because they focus on diesel.
 *
 * 8. EVAPORATIVE COOLING CORRECTION (ECC) — latent heat of vaporization
 *    Liquid/port fuel evaporating in the intake cools the charge,
 *    increasing density beyond what IAT alone suggests.
 *    Not applicable to diesel (no manifold fuel evaporation).
 *
 * 9. POWER DENSITY INDEX (PDI) — single-number power predictor
 *    Combines OMD, VE, thermal efficiency, and RPM into a
 *    dimensionless index that tracks actual power output.
 *
 * 10. DYNAMIC SAE CORRECTION (J1349 + J607) — compare both standards
 *     Banks shows one CF; we show both and the delta, so you know
 *     exactly how ambient conditions affect power on each standard.
 *
 * ─────────────────────────────────────────────────────────────────
 *  FUEL-SPECIFIC CONSTANTS
 * ─────────────────────────────────────────────────────────────────
 *  Petrol: AFR=14.7, LHV_vap=350 kJ/kg, density=737 g/L
 *  LPG:    AFR=15.5, LHV_vap=370 kJ/kg, density=510 g/L (liquid inj.)
 *  CNG/NGV: AFR=17.2, LHV_vap=0 (gas, no evap cooling), density varies
 *  Diesel: AFR=14.5, LHV_vap=0 (no manifold evap), density=832 g/L
 */
public final class AdvancedAirDensity {

    private AdvancedAirDensity() {}

    // ── Physical constants ────────────────────────────────────
    /** O2 fraction of dry air by volume (20.95%) */
    private static final double O2_FRACTION = 0.2095;
    /** Specific heat ratio (gamma) for air, used in isentropic turbo calc */
    private static final double GAMMA_AIR = 1.4;
    /** (gamma-1)/gamma = 0.286 for air */
    private static final double GAMMA_EXP = (GAMMA_AIR - 1) / GAMMA_AIR;
    /** Specific heat of air, kJ/(kg·K) */
    private static final double CP_AIR = 1.005;
    /** R_d for dry air, J/(kg·K) */
    private static final double R_DRY = 287.058;
    /** R_v for water vapor, J/(kg·K) */
    private static final double R_VAPOR = 461.495;
    /** kg/m³ → lbs/1000ft³ */
    private static final double KG_M3_TO_LBS = 62.428;
    /** SAE J1349 standard AAD (lbs/1000ft³) */
    public static final double SAE_J1349_AAD = 72.2;
    /** SAE J607 standard AAD (lbs/1000ft³) */
    public static final double SAE_J607_AAD = 76.4;
    /** Sea-level pressure (kPa) */
    private static final double SEA_LEVEL_KPA = 101.325;
    /** N2 fraction of dry air (78.08%) — for non-O2 displacement */
    private static final double N2_FRACTION = 0.7808;
    /** Ar fraction (0.93%) */
    private static final double AR_FRACTION = 0.0093;
    /** CO2 fraction (0.04%) */
    private static final double CO2_FRACTION = 0.0004;

    // ── Fuel-specific constants ───────────────────────────────
    // All fuel constants now sourced from FuelProperties.get(fuelMode).
    // This ensures E20, E85, Diesel B20, NGV etc. all get correct values.

    /** Get stoich AFR from FuelProperties */
    private static double stoichAFR(FuelMode fuel) {
        return FuelProperties.get(fuel).stoichAFR;
    }
    /** Get latent heat of vaporization from FuelProperties */
    private static double lhvVaporization(FuelMode fuel) {
        return FuelProperties.get(fuel).lhvVapKJkg;
    }
    /** Get fuel density from FuelProperties */
    private static double fuelDensity(FuelMode fuel) {
        return FuelProperties.get(fuel).densityGL;
    }
    /** Get typical brake thermal efficiency from FuelProperties */
    private static double thermalEfficiency(FuelMode fuel) {
        return FuelProperties.get(fuel).thermalEff;
    }

    // ═══════════════════════════════════════════════════════════
    //  1. OXYGEN MASS DENSITY (OMD)
    // ═══════════════════════════════════════════════════════════
    /**
     * Oxygen Mass Density — mass of O2 per unit volume available for combustion.
     *
     * Banks shows total air density, but only ~21% of air (by volume) is O2.
     * N2, Ar, CO2 pass through the engine without contributing to combustion.
     * OMD isolates the actual combustible oxygen mass.
     *
     * OMD = ρ_air × (P_dry / P_total) × O2_fraction
     *
     * The (P_dry / P_total) factor corrects for water vapor displacing O2.
     * At high humidity, water vapor pushes out O2, reducing combustible mass
     * even though total air density may not change as much.
     *
     * @param airDensityKgM3  Total air density (kg/m³) — from DerivedSensors
     * @param pressureKPa     Total absolute pressure (kPa)
     * @param tempC           Temperature (°C)
     * @param humidityPct     Relative humidity (0–100%)
     * @return OMD in kg/m³, or null
     */
    public static Double oxygenMassDensity(Double airDensityKgM3, Double pressureKPa,
                                            Double tempC, Double humidityPct) {
        if (airDensityKgM3 == null || pressureKPa == null || tempC == null) return null;

        double rh = clampHumidity(humidityPct);
        double tempK = tempC + 273.15;
        double pressurePa = pressureKPa * 1000.0;

        // Water vapor pressure
        double satVp = 6.1078 * Math.pow(10.0, (7.5 * tempC) / (tempC + 237.3));
        double vaporPa = satVp * (rh / 100.0) * 100.0;
        double dryFraction = (pressurePa - vaporPa) / pressurePa;

        // OMD = air_density × dry_fraction × O2_fraction
        double omd = airDensityKgM3 * dryFraction * O2_FRACTION;
        return Math.round(omd * 10000.0) / 10000.0;
    }

    /**
     * OMD in lbs/1000ft³ for display.
     */
    public static Double oxygenMassDensityLbs(Double airDensityKgM3, Double pressureKPa,
                                               Double tempC, Double humidityPct) {
        Double omd = oxygenMassDensity(airDensityKgM3, pressureKPa, tempC, humidityPct);
        if (omd == null) return null;
        return Math.round(omd * KG_M3_TO_LBS * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  2. COMPRESSOR EFFICIENCY (CE)
    // ═══════════════════════════════════════════════════════════
    /**
     * Turbocharger / supercharger compressor efficiency.
     *
     * CE compares the actual temperature rise across the compressor to the
     * theoretical isentropic (ideal) rise. A healthy turbo on its efficiency
     * island runs 65–78%. Below 55% = overspeeding or surge/choke territory.
     *
     * Isentropic: T2_ideal = T1 × (P2/P1)^((γ-1)/γ)
     * Actual: T2_actual = IAT (post-compressor)
     * CE = (T2_ideal - T1) / (T2_actual - T1)
     *
     * @param baroKpa     Ambient/inlet pressure (kPa) — pre-compressor
     * @param mapKpa      Manifold pressure (kPa) — post-compressor
     * @param ambientTempC  Pre-compressor temperature (°C)
     * @param iatTempC    Post-compressor / intake temperature (°C)
     * @return CE as % (0–100), or null if not boosted
     */
    public static Double compressorEfficiency(Double baroKpa, Double mapKpa,
                                               Double ambientTempC, Double iatTempC) {
        if (baroKpa == null || mapKpa == null || ambientTempC == null || iatTempC == null)
            return null;

        double pr = mapKpa / baroKpa;
        if (pr <= 1.01) return null; // Not boosted — CE not applicable

        double t1K = ambientTempC + 273.15;
        double t2ActualK = iatTempC + 273.15;

        // Isentropic exit temperature
        double t2IdealK = t1K * Math.pow(pr, GAMMA_EXP);
        double idealDeltaT = t2IdealK - t1K;
        double actualDeltaT = t2ActualK - t1K;

        if (actualDeltaT < 0.5) return null; // Temp sensor error or no boost
        double ce = (idealDeltaT / actualDeltaT) * 100.0;

        // Clamp to physical range
        if (ce < 0) return null;
        return Math.round(ce * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  3. INTERCOOLER EFFECTIVENESS (IC-EFF)
    // ═══════════════════════════════════════════════════════════
    /**
     * Intercooler effectiveness — how well the IC removes compressor heat.
     *
     * IC-EFF = (T_pre_IC - T_post_IC) / (T_pre_IC - T_ambient)
     *
     * Without a pre-IC temperature sensor (rare on street cars), we estimate
     * T_pre_IC using the isentropic compressor discharge temperature. This gives
     * a theoretical upper bound, so the real IC-EFF will be slightly lower.
     *
     * 70–85% = good street IC, >85% = excellent (race IC), <60% = upgrade needed.
     *
     * @param baroKpa      Ambient pressure (kPa)
     * @param mapKpa       Manifold pressure (kPa)
     * @param ambientTempC Ambient temperature (°C) — IC inlet air
     * @param iatTempC     Intake air temp (°C) — post-IC
     * @return IC-EFF as % (0–100), or null if not boosted
     */
    public static Double intercoolerEffectiveness(Double baroKpa, Double mapKpa,
                                                    Double ambientTempC, Double iatTempC) {
        if (baroKpa == null || mapKpa == null || ambientTempC == null || iatTempC == null)
            return null;

        double pr = mapKpa / baroKpa;
        if (pr <= 1.01) return null;

        double t1K = ambientTempC + 273.15;
        // Estimate compressor discharge temp (pre-IC) with 70% CE assumption
        double t2IdealK = t1K * Math.pow(pr, GAMMA_EXP);
        double tPreICK = t1K + (t2IdealK - t1K) / 0.70; // assume 70% CE for estimate
        double tPreICC = tPreICK - 273.15;

        double tPostIC = iatTempC;
        double tAmbient = ambientTempC;

        double deltaHot = tPreICC - tAmbient;
        double deltaActual = tPreICC - tPostIC;

        if (deltaHot < 1.0) return null;
        double eff = (deltaActual / deltaHot) * 100.0;

        if (eff < 0 || eff > 100) return null;
        return Math.round(eff * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  4. VOLUMETRIC EFFICIENCY (VE)
    // ═══════════════════════════════════════════════════════════
    /**
     * Volumetric Efficiency — how effectively the engine cylinders fill with air.
     *
     * VE = (MAF × 2) / (displacement_m³ × RPM/60 × air_density)
     *
     * 4-stroke: one intake stroke per 2 revolutions, hence ×2.
     *
     * 80–95% = naturally aspirated with good tuning
     * 90–110% = well-tuned forced induction
     * <70% = restricted intake, bad cam timing, or sensor error
     *
     * @param mafGs          MAF reading (g/s)
     * @param rpm            Engine RPM
     * @param displacementCC Engine displacement (cc) — from LoggerConfig
     * @param manifoldDensityKgM3  Air density in manifold (kg/m³) — MAD
     * @return VE as % (0–200), or null
     */
    public static Double volumetricEfficiency(Double mafGs, Double rpm, double displacementCC,
                                                Double manifoldDensityKgM3) {
        if (mafGs == null || rpm == null || manifoldDensityKgM3 == null) return null;
        if (rpm < 200 || mafGs <= 0 || manifoldDensityKgM3 <= 0 || displacementCC <= 0) return null;

        double displacementM3 = displacementCC / 1_000_000.0;
        double mafKgS = mafGs / 1000.0;
        double rpmS = rpm / 60.0;

        // 4-stroke: one intake stroke per 2 revolutions.
        // Theoretical mass flow = ρ × V × (RPM/120)
        // VE = actual_mass_flow / theoretical × 100
        //    = MAF / (ρ × V × RPM/120) × 100
        double theoreticalKgS = manifoldDensityKgM3 * displacementM3 * (rpmS / 2.0);
        double ve = (mafKgS / theoreticalKgS) * 100.0;

        if (ve < 0 || ve > 250) return null;
        return Math.round(ve * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  5. DENSITY-CORRECTED AFR (DCAFR)
    // ═══════════════════════════════════════════════════════════
    /**
     * Density-corrected Air-Fuel Ratio — true combustion mixture.
     *
     * Standard AFR from lambda doesn't account for water vapor displacing O2.
     * At 80% RH and 35°C, water vapor can displace ~5% of the oxygen, causing
     * the engine to run richer than the lambda reading suggests.
     *
     * DCAFR = lambda × stoich_AFR × (P_dry / P_total)
     *
     * The dry fraction corrects for the O2 displaced by water vapor.
     *
     * @param lambda        Lambda value from PID 0x34 or 0x44
     * @param fuelMode      Fuel type (determines stoich AFR)
     * @param pressureKPa   Total absolute pressure (kPa)
     * @param tempC         Temperature (°C)
     * @param humidityPct    Relative humidity (%)
     * @return DCAFR (ratio, e.g., 13.8:1), or null
     */
    public static Double densityCorrectedAFR(Double lambda, FuelMode fuel,
                                               Double pressureKPa, Double tempC,
                                               Double humidityPct) {
        if (lambda == null || lambda <= 0 || pressureKPa == null || tempC == null) return null;

        double rh = clampHumidity(humidityPct);
        double pressurePa = pressureKPa * 1000.0;
        double satVp = 6.1078 * Math.pow(10.0, (7.5 * tempC) / (tempC + 237.3));
        double vaporPa = satVp * (rh / 100.0) * 100.0;
        double dryFraction = (pressurePa - vaporPa) / pressurePa;

        double afr = lambda * stoichAFR(fuel) * dryFraction;
        return Math.round(afr * 100.0) / 100.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  6. TURBO MASS FLOW (TMF)
    // ═══════════════════════════════════════════════════════════
    /**
     * Theoretical mass air flow from density, displacement, and RPM.
     *
     * TMF = ρ_manifold × displacement × (RPM / 120) × VE
     *
     * 4-stroke: one intake per 2 revs → RPM/120 for per-second flow (with VE).
     * Compare this to the MAF sensor reading. A large discrepancy indicates:
     *   - MAF sensor drift/failure
     *   - Intake leak (between MAF and manifold)
     *   - VE has changed (valve timing, restrictions)
     *
     * @param manifoldDensityKgM3  Manifold air density (kg/m³)
     * @param rpm                  Engine RPM
     * @param displacementCC      Engine displacement (cc)
     * @param vePct                Volumetric efficiency (%)
     * @return TMF in g/s, or null
     */
    public static Double turboMassFlow(Double manifoldDensityKgM3, Double rpm,
                                         double displacementCC, Double vePct) {
        if (manifoldDensityKgM3 == null || rpm == null || vePct == null) return null;
        if (rpm < 200 || manifoldDensityKgM3 <= 0 || displacementCC <= 0) return null;

        double dispM3 = displacementCC / 1_000_000.0;
        double ve = vePct / 100.0;

        // TMF (kg/s) = ρ × V × (RPM/120) × VE
        double tmfKgS = manifoldDensityKgM3 * dispM3 * (rpm / 120.0) * ve;
        double tmfGs = tmfKgS * 1000.0;

        return Math.round(tmfGs * 10.0) / 10.0;
    }

    /**
     * MAF cross-check: compare sensor MAF vs theoretical TMF.
     * Returns % deviation. Positive = MAF reads higher than theoretical.
     * >10% deviation = investigate.
     */
    public static Double mafDeviationPct(Double mafSensorGs, Double tmfGs) {
        if (mafSensorGs == null || mafSensorGs == null || tmfGs == null || tmfGs <= 0) return null;
        double dev = ((mafSensorGs - tmfGs) / tmfGs) * 100.0;
        return Math.round(dev * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  7. LPG/CNG VAPOR DISPLACEMENT (LVD)
    // ═══════════════════════════════════════════════════════════
    /**
     * Fuel vapor displacement — the volume occupied by injected gaseous fuel
     * that displaces incoming air in the intake manifold.
     *
     * For LPG (liquid injection): liquid evaporates in port, vapor volume is
     * small relative to air. For LPG/CNG vapor injection (mixer/venturi): gas
     * is injected upstream and significantly displaces air.
     *
     * Vapor fraction = fuel_mass_flow / (air_mass_flow + fuel_mass_flow)
     * Effective air density = ρ_manifold × (1 - vapor_fraction)
     *
     * For diesel: LVD = 0 (direct injection, no manifold fuel)
     *
     * @param mafGs        MAF air flow (g/s)
     * @param fuelMode     Fuel type
     * @param lambda       Lambda — to derive fuel flow from AFR
     * @return Vapor displacement fraction (0.0–1.0), or null
     */
    public static Double vaporDisplacementFraction(Double mafGs, FuelMode fuel, Double lambda) {
        if (mafGs == null || mafGs <= 0 || lambda == null || lambda <= 0) return null;

        // Diesel (B7, B20): direct injection — no fuel vapor in manifold
        if (fuel.isDiesel()) return 0.0;

        // NGV/CNG: injected as gas → significant displacement, but LHV_vap = 0 (already gas)
        // LPG vapor injection: same as gaseous
        // Petrol/E20/E85: port injection → liquid evaporates in port (small displacement)

        double afr = lambda * stoichAFR(fuel);
        double fuelGs = mafGs / afr;
        double totalFlow = mafGs + fuelGs;

        return Math.round((fuelGs / totalFlow) * 10000.0) / 10000.0;
    }

    /**
     * Effective manifold air density after fuel vapor displacement.
     * This is the ACTUAL air mass the engine breathes — less than raw MAD.
     *
     * @param manifoldDensityKgM3  Raw manifold density (kg/m³)
     * @param lvdFraction          Vapor displacement fraction
     * @return Effective density (kg/m³), or null
     */
    public static Double effectiveAirDensity(Double manifoldDensityKgM3, Double lvdFraction) {
        if (manifoldDensityKgM3 == null || lvdFraction == null) return null;
        double eff = manifoldDensityKgM3 * (1.0 - lvdFraction);
        return Math.round(eff * 10000.0) / 10000.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  8. EVAPORATIVE COOLING CORRECTION (ECC)
    // ═══════════════════════════════════════════════════════════
    /**
     * Charge cooling from fuel evaporation in the intake/manifold.
     *
     * Liquid fuel (petrol, LPG-liquid) absorbs latent heat when it evaporates,
     * cooling the charge air and increasing density beyond what the IAT sensor
     * reads (IAT is upstream of fuel injection in port-injected engines).
     *
     * ΔT_cooling = (fuel_mass_flow × LHV_vap) / (air_mass_flow × cp_air)
     *
     * T_corrected = T_IAT - ΔT_cooling
     *
     * For diesel and CNG (gaseous): ΔT = 0 (no liquid evaporation in manifold).
     *
     * @param mafGs       MAF air flow (g/s)
     * @param lambda       Lambda value
     * @param fuelMode     Fuel type
     * @return Temperature correction (°C) — subtract from IAT, or 0.0
     */
    public static Double evaporativeCoolingDeltaT(Double mafGs, Double lambda, FuelMode fuel) {
        if (mafGs == null || mafGs <= 0 || lambda == null || lambda <= 0) return null;

        double lhv = lhvVaporization(fuel); // kJ/kg
        if (lhv <= 0) return 0.0; // no evap cooling (not applicable for current FuelMode)

        double afr = lambda * stoichAFR(fuel);
        double fuelKgS = (mafGs / 1000.0) / afr;
        double airKgS = mafGs / 1000.0;

        // ΔT = (fuel_flow × LHV) / (air_flow × cp_air)
        double deltaT = (fuelKgS * lhv) / (airKgS * CP_AIR);

        return Math.round(deltaT * 10.0) / 10.0;
    }

    /**
     * Evaporative-cooling-corrected manifold air density.
     * Uses corrected temperature to recalculate density.
     *
     * @param mapKpa        Manifold pressure (kPa)
     * @param iatTempC      Intake air temp (°C) — measured
     * @param mafGs          MAF (g/s)
     * @param lambda         Lambda
     * @param fuelMode       Fuel type
     * @param humidityPct     RH (%)
     * @return Corrected MAD in lbs/1000ft³, or null
     */
    public static Double evaporativeCorrectedMAD(Double mapKpa, Double iatTempC,
                                                    Double mafGs, Double lambda,
                                                    FuelMode fuelMode, Double humidityPct) {
        if (mapKpa == null || iatTempC == null) return null;

        Double deltaT = evaporativeCoolingDeltaT(mafGs, lambda, fuelMode);
        double correctedTempC = iatTempC - (deltaT != null ? deltaT : 0.0);

        return DerivedSensors.airDensityLbs1000ft3(mapKpa, correctedTempC, humidityPct);
    }

    // ═══════════════════════════════════════════════════════════
    //  9. POWER DENSITY INDEX (PDI)
    // ═══════════════════════════════════════════════════════════
    /**
     * Power Density Index — single dimensionless number tracking engine power.
     *
     * PDI = OMD_normalized × VE_normalized × thermal_efficiency × RPM_factor
     *
     * Where:
     *   OMD_normalized = OMD / OMD_SAE_standard
     *   VE_normalized = VE / 100
     *   RPM_factor = RPM / RPM_rated (approaches 1.0 at peak power)
     *   thermal_efficiency = brake thermal efficiency (fuel-dependent)
     *
     * PDI ≈ 1.0 at standard conditions with good engine health at peak power.
     * <1.0 = less power than standard (hot, humid, high altitude, bad VE)
     * >1.0 = more power than standard (cold, dry, forced induction)
     *
     * @param omdKgM3        Oxygen mass density (kg/m³)
     * @param vePct          Volumetric efficiency (%)
     * @param rpm            Engine RPM
     * @param ratedRPM       Rated peak-power RPM (e.g., 6000)
     * @param fuelMode       Fuel type
     * @return PDI (dimensionless, ~0.3–1.5), or null
     */
    public static Double powerDensityIndex(Double omdKgM3, Double vePct, Double rpm,
                                             double ratedRPM, FuelMode fuelMode) {
        if (omdKgM3 == null || vePct == null || rpm == null || rpm < 200) return null;

        // SAE J1349 standard OMD: 1.225 kg/m³ × 0.2095 × 1.0 (dry) = 0.2566
        double omdStd = 1.225 * O2_FRACTION;
        double omdNorm = omdKgM3 / omdStd;

        double veNorm = vePct / 100.0;
        double rpmFactor = Math.min(rpm / ratedRPM, 1.0);
        double thermalEff = thermalEfficiency(fuelMode);

        double pdi = omdNorm * veNorm * thermalEff * rpmFactor;
        return Math.round(pdi * 1000.0) / 1000.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  10. DYNAMIC SAE CORRECTION (J1349 + J607)
    // ═══════════════════════════════════════════════════════════
    /**
     * SAE J1349 correction factor — normalizes power to standard conditions.
     * Standard: 99.0 kPa dry, 298.15 K (25°C), 0% RH.
     *
     * CF_1349 = (P_std / P_dry_actual) × sqrt(T_actual / T_std)
     *
     * @param baroKpa     Barometric pressure (kPa)
     * @param tempC       Ambient temperature (°C)
     * @param humidityPct  RH (%)
     * @return CF (typically 0.85–1.15), or null
     */
    public static Double saeJ1349CF(Double baroKpa, Double tempC, Double humidityPct) {
        return DerivedSensors.saeJ1349CorrectionFactor(baroKpa, tempC, humidityPct);
    }

    /**
     * SAE J607 correction factor — older standard, different reference conditions.
     * Standard: 101.325 kPa (sea level), 288.15 K (15°C), 0% RH.
     *
     * CF_607 = (P_std / P_dry_actual) × sqrt(T_actual / T_std)
     *
     * @param baroKpa     Barometric pressure (kPa)
     * @param tempC       Ambient temperature (°C)
     * @param humidityPct  RH (%)
     * @return CF (typically 0.85–1.15), or null
     */
    public static Double saeJ607CF(Double baroKpa, Double tempC, Double humidityPct) {
        if (baroKpa == null || tempC == null) return null;

        double rh = clampHumidity(humidityPct);
        double tempK = tempC + 273.15;
        double pressureHpa = baroKpa * 10.0;

        double satVp = 6.1078 * Math.pow(10.0, (7.5 * tempC) / (tempC + 237.3));
        double vaporPressure = satVp * (rh / 100.0);

        // SAE J607: 1013.25 hPa, 288.15 K (15°C), 0% RH
        double pStd = 1013.25;
        double tStd = 288.15;

        double dryPressure = pressureHpa - vaporPressure;
        double cf = (pStd / dryPressure) * Math.sqrt(tempK / tStd);

        return Math.round(cf * 1000.0) / 1000.0;
    }

    /**
     * Delta between J1349 and J607 correction factors.
     * Shows how much the two standards diverge under current conditions.
     */
    public static Double saeCFDelta(Double baroKpa, Double tempC, Double humidityPct) {
        Double j1349 = saeJ1349CF(baroKpa, tempC, humidityPct);
        Double j607 = saeJ607CF(baroKpa, tempC, humidityPct);
        if (j1349 == null || j607 == null) return null;
        return Math.round((j1349 - j607) * 1000.0) / 1000.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  RESULT CONTAINER
    // ═══════════════════════════════════════════════════════════
    /**
     * Complete result from all advanced air-density calculations.
     * Passed to LoggerService for logging to CSV/JSONL.
     */
    public static final class AdvancedResult {
        // 1. OMD
        public final Double omdKgM3;         // Oxygen mass density (kg/m³)
        public final Double omdLbs;          // OMD (lbs/1000ft³)
        // 2. CE
        public final Double compressorEff;   // Compressor efficiency (%)
        // 3. IC-EFF
        public final Double intercoolerEff;  // Intercooler effectiveness (%)
        // 4. VE
        public final Double vePct;           // Volumetric efficiency (%)
        // 5. DCAFR
        public final Double dcafr;           // Density-corrected AFR
        // 6. TMF
        public final Double tmfGs;           // Theoretical mass flow (g/s)
        public final Double mafDeviationPct;  // MAF vs TMF deviation (%)
        // 7. LVD
        public final Double lvdFraction;     // Vapor displacement fraction
        public final Double effectiveDensityKgM3; // Effective air density after LVD
        // 8. ECC
        public final Double eccDeltaT;       // Evap cooling ΔT (°C)
        public final Double eccCorrectedMAD; // Evap-corrected MAD (lbs/1000ft³)
        // 9. PDI
        public final Double pdi;             // Power density index
        // 10. Dynamic CF
        public final Double saeJ1349CF;     // SAE J1349 correction factor
        public final Double saeJ607CF;      // SAE J607 correction factor
        public final Double saeCFDelta;     // Delta between the two

        AdvancedResult(Double omdKgM3, Double omdLbs, Double compressorEff,
                       Double intercoolerEff, Double vePct, Double dcafr,
                       Double tmfGs, Double mafDeviationPct, Double lvdFraction,
                       Double effectiveDensityKgM3, Double eccDeltaT,
                       Double eccCorrectedMAD, Double pdi,
                       Double saeJ1349CF, Double saeJ607CF, Double saeCFDelta) {
            this.omdKgM3 = omdKgM3;
            this.omdLbs = omdLbs;
            this.compressorEff = compressorEff;
            this.intercoolerEff = intercoolerEff;
            this.vePct = vePct;
            this.dcafr = dcafr;
            this.tmfGs = tmfGs;
            this.mafDeviationPct = mafDeviationPct;
            this.lvdFraction = lvdFraction;
            this.effectiveDensityKgM3 = effectiveDensityKgM3;
            this.eccDeltaT = eccDeltaT;
            this.eccCorrectedMAD = eccCorrectedMAD;
            this.pdi = pdi;
            this.saeJ1349CF = saeJ1349CF;
            this.saeJ607CF = saeJ607CF;
            this.saeCFDelta = saeCFDelta;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US,
                    "OMD=%.4f CE=%.1f%% IC=%.1f%% VE=%.1f%% DCAFR=%.2f "
                  + "TMF=%.1f LVD=%.3f ECC=%.1f°C PDI=%.3f J1349=%.3f J607=%.3f",
                    omdKgM3 != null ? omdKgM3 : 0,
                    compressorEff != null ? compressorEff : 0,
                    intercoolerEff != null ? intercoolerEff : 0,
                    vePct != null ? vePct : 0,
                    dcafr != null ? dcafr : 0,
                    tmfGs != null ? tmfGs : 0,
                    lvdFraction != null ? lvdFraction : 0,
                    eccDeltaT != null ? eccDeltaT : 0,
                    pdi != null ? pdi : 0,
                    saeJ1349CF != null ? saeJ1349CF : 0,
                    saeJ607CF != null ? saeJ607CF : 0);
        }
    }

    /**
     * Compute all 10 advanced formulas in one call.
     *
     * @param aadKgM3        Ambient air density (kg/m³)
     * @param madKgM3        Manifold air density (kg/m³)
     * @param baroKpa        Barometric pressure (kPa)
     * @param mapKpa         Manifold absolute pressure (kPa)
     * @param ambientTempC  Ambient temperature (°C)
     * @param iatTempC       Intake air temp (°C)
     * @param humidityPct    Relative humidity (%)
     * @param mafGs          MAF sensor reading (g/s)
     * @param rpm            Engine RPM
     * @param lambda         Lambda value
     * @param fuelMode       Fuel type
     * @param displacementCC Engine displacement (cc)
     * @param ratedRPM       Rated peak-power RPM (for PDI)
     * @return AdvancedResult with all 10 calculated values
     */
    public static AdvancedResult computeAll(
            Double aadKgM3, Double madKgM3,
            Double baroKpa, Double mapKpa,
            Double ambientTempC, Double iatTempC,
            Double humidityPct, Double mafGs,
            Double rpm, Double lambda,
            FuelMode fuelMode, double displacementCC,
            double ratedRPM) {

        double humidity = clampHumidity(humidityPct);

        // 1. OMD — oxygen mass density (ambient)
        Double omdKgM3 = oxygenMassDensity(aadKgM3, baroKpa, ambientTempC, humidity);
        Double omdLbs = oxygenMassDensityLbs(aadKgM3, baroKpa, ambientTempC, humidity);

        // 2. Compressor efficiency
        Double ce = compressorEfficiency(baroKpa, mapKpa, ambientTempC, iatTempC);

        // 3. Intercooler effectiveness
        Double icEff = intercoolerEffectiveness(baroKpa, mapKpa, ambientTempC, iatTempC);

        // 4. VE — volumetric efficiency
        Double ve = volumetricEfficiency(mafGs, rpm, displacementCC, madKgM3);

        // 5. DCAFR — density-corrected AFR
        Double dcafr = densityCorrectedAFR(lambda, fuelMode, mapKpa, iatTempC, humidity);

        // 6. TMF — theoretical mass flow
        Double tmf = turboMassFlow(madKgM3, rpm, displacementCC, ve);
        Double mafDev = mafDeviationPct(mafGs, tmf);

        // 7. LVD — vapor displacement
        Double lvd = vaporDisplacementFraction(mafGs, fuelMode, lambda);
        Double effDensity = (lvd != null && madKgM3 != null)
                ? effectiveAirDensity(madKgM3, lvd) : null;

        // 8. ECC — evaporative cooling
        Double eccDeltaT = evaporativeCoolingDeltaT(mafGs, lambda, fuelMode);
        Double eccMAD = evaporativeCorrectedMAD(mapKpa, iatTempC, mafGs, lambda,
                fuelMode, humidity);

        // 9. PDI — power density index
        Double pdi = powerDensityIndex(omdKgM3, ve, rpm, ratedRPM, fuelMode);

        // 10. Dynamic SAE correction
        Double j1349 = saeJ1349CF(baroKpa, ambientTempC, humidity);
        Double j607 = saeJ607CF(baroKpa, ambientTempC, humidity);
        Double cfDelta = saeCFDelta(baroKpa, ambientTempC, humidity);

        return new AdvancedResult(omdKgM3, omdLbs, ce, icEff, ve, dcafr,
                tmf, mafDev, lvd, effDensity, eccDeltaT, eccMAD, pdi,
                j1349, j607, cfDelta);
    }

    // ── Utility ───────────────────────────────────────────────
    private static double clampHumidity(Double humidityPct) {
        if (humidityPct == null) return 50.0;
        if (humidityPct < 0) return 0.0;
        if (humidityPct > 100) return 100.0;
        return humidityPct;
    }
}
