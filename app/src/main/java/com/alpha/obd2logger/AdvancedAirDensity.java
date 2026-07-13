package com.alpha.obd2logger;

/**
 * Advanced Air Density & Engine Performance Calculations — beyond standard AAD/MAD/BAD.
 *
 * All formulas work across fuel types. Fuel-specific constants (AFR, LHV,
 * vapor displacement) are selected automatically based on FuelMode.
 *
 * v3.15 phys corrections:
 *  - OMD uses dry-air density × O2 mass fraction (no double humidity tax)
 *  - TMF is independent of measured MAF (assumed VE by aspirated/forced)
 *  - Compressor/IC efficiency flagged as ESTIMATE when only post-IC IAT is available
 *  - Vapor partial pressure always clamped under total pressure
 */
public final class AdvancedAirDensity {

    private AdvancedAirDensity() {}

    // ── Physical constants ────────────────────────────────────
    /** O2 volume fraction of dry air (20.95%) — kept for compatibility */
    private static final double O2_VOLUME_FRACTION = 0.2095;
    /** O2 mass fraction of dry air (~23.14%) — used for OMD */
    private static final double O2_MASS_FRACTION = DerivedSensors.O2_MASS_FRACTION_DRY;
    /** Specific heat ratio (gamma) for air */
    private static final double GAMMA_AIR = 1.4;
    /** (gamma-1)/gamma = 0.286 for air */
    private static final double GAMMA_EXP = (GAMMA_AIR - 1) / GAMMA_AIR;
    /** Specific heat of air, kJ/(kg·K) */
    private static final double CP_AIR = 1.005;
    /** kg/m³ → lbs/1000ft³ */
    private static final double KG_M3_TO_LBS = DerivedSensors.KG_M3_TO_LBS_1000FT3;
    /** SAE J1349 standard AAD (lbs/1000ft³) */
    public static final double SAE_J1349_AAD = DerivedSensors.SAE_J1349_AAD;
    /** SAE J607 standard AAD (lbs/1000ft³) */
    public static final double SAE_J607_AAD = DerivedSensors.SAE_J607_AAD;

    /** Assumed volumetric efficiency for NA mass-flow estimate (%) */
    public static final double ASSUMED_VE_NA = 85.0;
    /** Assumed volumetric efficiency for FI mass-flow estimate (%) */
    public static final double ASSUMED_VE_FI = 95.0;

    // ── Fuel helpers via FuelProperties ───────────────────────
    private static double stoichAFR(FuelMode fuel) {
        return FuelProperties.get(fuel).stoichAFR;
    }

    /** Convert an equivalence ratio to AFR for the selected fuel. */
    public static Double airFuelRatio(Double lambda, FuelMode fuel) {
        if (lambda == null || !Double.isFinite(lambda) || lambda <= 0 || lambda >= 3) {
            return null;
        }
        return Math.round(lambda * stoichAFR(fuel) * 100.0) / 100.0;
    }
    private static double lhvVaporization(FuelMode fuel) {
        return FuelProperties.get(fuel).lhvVapKJkg;
    }
    private static double thermalEfficiency(FuelMode fuel) {
        return FuelProperties.get(fuel).thermalEff;
    }

    // ═══════════════════════════════════════════════════════════
    //  1. OXYGEN MASS DENSITY (OMD)
    // ═══════════════════════════════════════════════════════════

    /**
     * Oxygen Mass Density — mass of O2 per unit volume available for combustion.
     *
     * Correct path: ρ_dry × O2_mass_fraction.
     * (NOT total-air × dry_mole_fraction × O2_vol_fraction — that double-counts humidity.)
     */
    public static Double oxygenMassDensity(Double airDensityKgM3, Double pressureKPa,
                                           Double tempC, Double humidityPct) {
        // airDensityKgM3 parameter kept for API compatibility but we recompute dry density.
        if (pressureKPa == null || tempC == null) return null;
        Double dry = DerivedSensors.dryAirDensityKgM3(pressureKPa, tempC, humidityPct);
        if (dry == null || dry <= 0) {
            // Fall back: if only total density known, rough dry fraction correction
            if (airDensityKgM3 == null) return null;
            double rh = clampHumidity(humidityPct);
            double pHpa = pressureKPa * 10.0;
            double pv = DerivedSensors.vaporPressureHpa(tempC, rh, pHpa);
            double dryFrac = Math.max(0.5, (pHpa - pv) / pHpa);
            dry = airDensityKgM3 * dryFrac * (R_DRY_RATIO_GUESS);
        }
        double omd = dry * O2_MASS_FRACTION;
        return Math.round(omd * 10000.0) / 10000.0;
    }

    // Approximate mass(dry)/mass(total) when only total density is known — near 1
    private static final double R_DRY_RATIO_GUESS = 1.0;

    public static Double oxygenMassDensityLbs(Double airDensityKgM3, Double pressureKPa,
                                              Double tempC, Double humidityPct) {
        Double omd = oxygenMassDensity(airDensityKgM3, pressureKPa, tempC, humidityPct);
        if (omd == null) return null;
        return Math.round(omd * KG_M3_TO_LBS * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  2. COMPRESSOR EFFICIENCY (CE) — estimate when only post-IC IAT
    // ═══════════════════════════════════════════════════════════

    /**
     * Proxy compressor efficiency using baro/MAP and ambient / post-IC IAT.
     * On street cars PID 0x0F is usually AFTER the intercooler, so this is an
     * upper-bound estimate only (overstates CE). Prefer null if not boosted.
     */
    public static Double compressorEfficiency(Double baroKpa, Double mapKpa,
                                              Double ambientTempC, Double iatTempC) {
        if (baroKpa == null || mapKpa == null || ambientTempC == null || iatTempC == null)
            return null;

        double pr = mapKpa / baroKpa;
        if (pr <= 1.05) return null; // not meaningfully boosted

        double t1K = ambientTempC + 273.15;
        double t2ActualK = iatTempC + 273.15;
        double t2IdealK = t1K * Math.pow(pr, GAMMA_EXP);
        double idealDeltaT = t2IdealK - t1K;
        double actualDeltaT = t2ActualK - t1K;

        // Post-IC IAT is cooler than compressor discharge → actualDelta may be too small.
        // If IAT is cooler than ambient (sensor glitch / ambient heat soak), reject.
        if (actualDeltaT < 1.0) return null;
        double ce = (idealDeltaT / actualDeltaT) * 100.0;
        // Clamp: street cars with only post-IC IAT often synthesize >100%
        if (ce < 10.0 || ce > 95.0) {
            // Cap display to physical compressor island range
            if (ce > 95.0) ce = 95.0;
            if (ce < 10.0) return null;
        }
        return Math.round(ce * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  3. INTERCOOLER EFFECTIVENESS (estimate)
    // ═══════════════════════════════════════════════════════════

    /**
     * Estimated intercooler effectiveness assuming ~70% compressor efficiency
     * for pre-IC discharge temperature. Treat as estimate only when no pre-IC sensor.
     */
    public static Double intercoolerEffectiveness(Double baroKpa, Double mapKpa,
                                                  Double ambientTempC, Double iatTempC) {
        if (baroKpa == null || mapKpa == null || ambientTempC == null || iatTempC == null)
            return null;

        double pr = mapKpa / baroKpa;
        if (pr <= 1.05) return null;

        double t1K = ambientTempC + 273.15;
        double t2IdealK = t1K * Math.pow(pr, GAMMA_EXP);
        // Estimated pre-IC compressor discharge assuming 70% isentropic efficiency
        double tPreICK = t1K + (t2IdealK - t1K) / 0.70;
        double tPreICC = tPreICK - 273.15;

        double deltaHot = tPreICC - ambientTempC;
        double deltaActual = tPreICC - iatTempC;
        if (deltaHot < 1.0) return null;
        double eff = (deltaActual / deltaHot) * 100.0;
        if (eff < 0 || eff > 100) return null;
        return Math.round(eff * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  4. VOLUMETRIC EFFICIENCY (VE)
    // ═══════════════════════════════════════════════════════════

    /**
     * VE from MAF vs theoretical density×displacement fill (4-stroke).
     * VE = MAF / (ρ × V × RPM/120) × 100
     */
    public static Double volumetricEfficiency(Double mafGs, Double rpm, double displacementCC,
                                              Double manifoldDensityKgM3) {
        if (mafGs == null || rpm == null || manifoldDensityKgM3 == null) return null;
        if (rpm < 200 || mafGs <= 0 || manifoldDensityKgM3 <= 0 || displacementCC <= 0) return null;

        double displacementM3 = displacementCC / 1_000_000.0;
        double mafKgS = mafGs / 1000.0;
        // theoretical mass flow at 100% VE
        double theoreticalKgS = manifoldDensityKgM3 * displacementM3 * (rpm / 120.0);
        if (theoreticalKgS <= 0) return null;
        double ve = (mafKgS / theoreticalKgS) * 100.0;
        if (ve < 0 || ve > 250) return null;
        return Math.round(ve * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  5. DENSITY-CORRECTED AFR (DCAFR)
    // ═══════════════════════════════════════════════════════════

    /**
     * Density-corrected AFR: lambda × stoich × dry_mole_fraction.
     * Accounts for oxygen displaced by water vapor.
     */
    public static Double densityCorrectedAFR(Double lambda, FuelMode fuel,
                                             Double pressureKPa, Double tempC,
                                             Double humidityPct) {
        if (lambda == null || lambda <= 0 || pressureKPa == null || tempC == null) return null;

        double rh = clampHumidity(humidityPct);
        double pHpa = pressureKPa * 10.0;
        double pv = DerivedSensors.vaporPressureHpa(tempC, rh, pHpa);
        double dryFraction = (pHpa - pv) / pHpa;
        if (dryFraction <= 0) return null;

        double afr = lambda * stoichAFR(fuel) * dryFraction;
        return Math.round(afr * 100.0) / 100.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  6. THEORETICAL MASS FLOW (TMF) — independent of measured MAF
    // ═══════════════════════════════════════════════════════════

    /**
     * Independent theoretical mass flow using assumed VE (not measured MAF).
     * TMF_gs = ρ × V × (RPM/120) × VE_assumed × 1000
     *
     * Forced induction (MAP/baro > 1.05) uses ASSUMED_VE_FI, NA uses ASSUMED_VE_NA.
     * Compare against measured MAF with {@link #mafDeviationPct}.
     */
    public static Double turboMassFlowIndependent(Double manifoldDensityKgM3, Double rpm,
                                                  double displacementCC, boolean forcedInduction) {
        if (manifoldDensityKgM3 == null || rpm == null) return null;
        if (rpm < 200 || manifoldDensityKgM3 <= 0 || displacementCC <= 0) return null;

        double vePct = forcedInduction ? ASSUMED_VE_FI : ASSUMED_VE_NA;
        double dispM3 = displacementCC / 1_000_000.0;
        double ve = vePct / 100.0;
        double tmfKgS = manifoldDensityKgM3 * dispM3 * (rpm / 120.0) * ve;
        double tmfGs = tmfKgS * 1000.0;
        return Math.round(tmfGs * 10.0) / 10.0;
    }

    /**
     * Legacy TMF that multiplies by measured VE — algebraically equals MAF.
     * Kept for API stability; prefer {@link #turboMassFlowIndependent}.
     */
    public static Double turboMassFlow(Double manifoldDensityKgM3, Double rpm,
                                       double displacementCC, Double vePct) {
        if (manifoldDensityKgM3 == null || rpm == null || vePct == null) return null;
        if (rpm < 200 || manifoldDensityKgM3 <= 0 || displacementCC <= 0) return null;
        double dispM3 = displacementCC / 1_000_000.0;
        double ve = vePct / 100.0;
        double tmfKgS = manifoldDensityKgM3 * dispM3 * (rpm / 120.0) * ve;
        return Math.round(tmfKgS * 1000.0 * 10.0) / 10.0;
    }

    /**
     * MAF vs independent theoretical mass-flow deviation (%).
     * Positive = MAF reads higher than theoretical.
     * |dev| > ~15% warrants investigation (leak / sensor drift / wrong displ).
     */
    public static Double mafDeviationPct(Double mafSensorGs, Double tmfGs) {
        if (mafSensorGs == null || tmfGs == null || tmfGs <= 0) return null;
        if (mafSensorGs <= 0) return null;
        double dev = ((mafSensorGs - tmfGs) / tmfGs) * 100.0;
        return Math.round(dev * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  7. LPG/CNG VAPOR DISPLACEMENT (LVD)
    // ═══════════════════════════════════════════════════════════

    public static Double vaporDisplacementFraction(Double mafGs, FuelMode fuel, Double lambda) {
        if (mafGs == null || mafGs <= 0 || lambda == null || lambda <= 0) return null;
        if (fuel != null && fuel.isDiesel()) return 0.0;

        double afr = lambda * stoichAFR(fuel);
        if (afr <= 0) return null;
        double fuelGs = mafGs / afr;
        double totalFlow = mafGs + fuelGs;
        if (totalFlow <= 0) return null;
        return Math.round((fuelGs / totalFlow) * 10000.0) / 10000.0;
    }

    public static Double effectiveAirDensity(Double manifoldDensityKgM3, Double lvdFraction) {
        if (manifoldDensityKgM3 == null || lvdFraction == null) return null;
        double clamped = Math.max(0.0, Math.min(0.5, lvdFraction));
        double eff = manifoldDensityKgM3 * (1.0 - clamped);
        return Math.round(eff * 10000.0) / 10000.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  8. EVAPORATIVE COOLING CORRECTION (ECC)
    // ═══════════════════════════════════════════════════════════

    public static Double evaporativeCoolingDeltaT(Double mafGs, Double lambda, FuelMode fuel) {
        if (mafGs == null || mafGs <= 0 || lambda == null || lambda <= 0) return null;

        double lhv = lhvVaporization(fuel); // kJ/kg
        if (lhv <= 0) return 0.0;

        double afr = lambda * stoichAFR(fuel);
        if (afr <= 0) return null;
        double fuelKgS = (mafGs / 1000.0) / afr;
        double airKgS = mafGs / 1000.0;
        if (airKgS <= 0) return null;
        double deltaT = (fuelKgS * lhv) / (airKgS * CP_AIR);
        return Math.round(deltaT * 10.0) / 10.0;
    }

    public static Double evaporativeCorrectedMAD(Double mapKpa, Double iatTempC,
                                                 Double mafGs, Double lambda,
                                                 FuelMode fuelMode, Double humidityPct,
                                                 Double ambientBaroKpa, Double ambientTempC) {
        if (mapKpa == null || iatTempC == null) return null;
        Double deltaT = evaporativeCoolingDeltaT(mafGs, lambda, fuelMode);
        double correctedTempC = iatTempC - (deltaT != null ? deltaT : 0.0);
        // Prefer absolute humidity path
        return DerivedSensors.manifoldAirDensity(mapKpa, correctedTempC,
                ambientBaroKpa, ambientTempC, humidityPct);
    }

    /** Legacy ECC MAD without ambient absolute-humidity inputs. */
    public static Double evaporativeCorrectedMAD(Double mapKpa, Double iatTempC,
                                                 Double mafGs, Double lambda,
                                                 FuelMode fuelMode, Double humidityPct) {
        return evaporativeCorrectedMAD(mapKpa, iatTempC, mafGs, lambda, fuelMode,
                humidityPct, mapKpa, iatTempC);
    }

    // ═══════════════════════════════════════════════════════════
    //  9. POWER DENSITY INDEX (PDI)
    // ═══════════════════════════════════════════════════════════

    public static Double powerDensityIndex(Double omdKgM3, Double vePct, Double rpm,
                                           double ratedRPM, FuelMode fuelMode) {
        if (omdKgM3 == null || vePct == null || rpm == null || rpm < 200) return null;
        if (ratedRPM <= 0) ratedRPM = 6000;

        // Std dry air 1.225 kg/m³ × O2 mass fraction
        double omdStd = 1.225 * O2_MASS_FRACTION;
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

    public static Double saeJ1349CF(Double baroKpa, Double tempC, Double humidityPct) {
        return DerivedSensors.saeJ1349CorrectionFactor(baroKpa, tempC, humidityPct);
    }

    public static Double saeJ607CF(Double baroKpa, Double tempC, Double humidityPct) {
        if (baroKpa == null || tempC == null) return null;

        double rh = clampHumidity(humidityPct);
        double tempK = tempC + 273.15;
        double pressureHpa = baroKpa * 10.0;
        double vaporPressure = DerivedSensors.vaporPressureHpa(tempC, rh, pressureHpa);
        double dryPressure = pressureHpa - vaporPressure;
        if (dryPressure < 50.0) return null;

        double pStd = 1013.25;
        double tStd = 288.15;
        double cf = (pStd / dryPressure) * Math.sqrt(tempK / tStd);
        if (cf < 0.5 || cf > 1.5) return null;
        return Math.round(cf * 1000.0) / 1000.0;
    }

    public static Double saeCFDelta(Double baroKpa, Double tempC, Double humidityPct) {
        Double j1349 = saeJ1349CF(baroKpa, tempC, humidityPct);
        Double j607 = saeJ607CF(baroKpa, tempC, humidityPct);
        if (j1349 == null || j607 == null) return null;
        return Math.round((j1349 - j607) * 1000.0) / 1000.0;
    }

    // ═══════════════════════════════════════════════════════════
    //  RESULT CONTAINER
    // ═══════════════════════════════════════════════════════════

    public static final class AdvancedResult {
        public final Double omdKgM3;
        public final Double omdLbs;
        public final Double compressorEff;
        public final Double intercoolerEff;
        public final Double vePct;
        public final Double dcafr;
        public final Double tmfGs;
        public final Double mafDeviationPct;
        public final Double lvdFraction;
        public final Double effectiveDensityKgM3;
        public final Double eccDeltaT;
        public final Double eccCorrectedMAD;
        public final Double pdi;
        public final Double saeJ1349CF;
        public final Double saeJ607CF;
        public final Double saeCFDelta;
        /** True when CE/IC used post-IC IAT proxy (no compressor-out sensor). */
        public final boolean ceIsEstimate;

        AdvancedResult(Double omdKgM3, Double omdLbs, Double compressorEff,
                       Double intercoolerEff, Double vePct, Double dcafr,
                       Double tmfGs, Double mafDeviationPct, Double lvdFraction,
                       Double effectiveDensityKgM3, Double eccDeltaT,
                       Double eccCorrectedMAD, Double pdi,
                       Double saeJ1349CF, Double saeJ607CF, Double saeCFDelta,
                       boolean ceIsEstimate) {
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
            this.ceIsEstimate = ceIsEstimate;
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
     * Compute all advanced formulas.
     *
     * @param aadKgM3 ambient density kg/m³ (optional; OMD recomputes dry ρ)
     * @param madKgM3 manifold density kg/m³ (absolute-humidity preferred)
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
        boolean forced = (baroKpa != null && mapKpa != null && mapKpa / baroKpa > 1.05);

        // 1. OMD — oxygen mass density from dry air at ambient
        Double omdKgM3 = oxygenMassDensity(aadKgM3, baroKpa, ambientTempC, humidity);
        Double omdLbs = oxygenMassDensityLbs(aadKgM3, baroKpa, ambientTempC, humidity);

        // 2/3. CE / IC — always estimates with post-IC IAT only
        Double ce = compressorEfficiency(baroKpa, mapKpa, ambientTempC, iatTempC);
        Double icEff = intercoolerEffectiveness(baroKpa, mapKpa, ambientTempC, iatTempC);
        boolean ceIsEstimate = (ce != null || icEff != null);

        // 4. VE — needs measured MAF + true MAD
        Double ve = volumetricEfficiency(mafGs, rpm, displacementCC, madKgM3);

        // 5. DCAFR at manifold conditions
        Double dcafr = densityCorrectedAFR(lambda, fuelMode, mapKpa, iatTempC, humidity);

        // 6. Independent TMF (assumed VE) + deviation vs MAF
        Double tmf = turboMassFlowIndependent(madKgM3, rpm, displacementCC, forced);
        Double mafDev = mafDeviationPct(mafGs, tmf);

        // 7. LVD
        Double lvd = vaporDisplacementFraction(mafGs, fuelMode, lambda);
        Double effDensity = (lvd != null && madKgM3 != null)
                ? effectiveAirDensity(madKgM3, lvd) : null;

        // 8. ECC
        Double eccDeltaT = evaporativeCoolingDeltaT(mafGs, lambda, fuelMode);
        Double eccMAD = evaporativeCorrectedMAD(mapKpa, iatTempC, mafGs, lambda,
                fuelMode, humidity, baroKpa, ambientTempC);

        // 9. PDI
        Double pdi = powerDensityIndex(omdKgM3, ve, rpm, ratedRPM, fuelMode);

        // 10. Dynamic SAE CF
        Double j1349 = saeJ1349CF(baroKpa, ambientTempC, humidity);
        Double j607 = saeJ607CF(baroKpa, ambientTempC, humidity);
        Double cfDelta = saeCFDelta(baroKpa, ambientTempC, humidity);

        return new AdvancedResult(omdKgM3, omdLbs, ce, icEff, ve, dcafr,
                tmf, mafDev, lvd, effDensity, eccDeltaT, eccMAD, pdi,
                j1349, j607, cfDelta, ceIsEstimate);
    }

    private static double clampHumidity(Double humidityPct) {
        return DerivedSensors.clampHumidity(humidityPct, 50.0);
    }
}
