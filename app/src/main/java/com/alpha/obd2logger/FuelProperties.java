package com.alpha.obd2logger;

/**
 * Central fuel properties for all Thai fuel types.
 *
 * Thailand has a diverse fuel market:
 *   Petrol:  91 (RON91, E10), 95 (RON95, E10), E20 (RON95, 20% ethanol), E85 (85% ethanol)
 *   LPG:     Propane/butane mix (autogas)
 *   NGV/CNG: Compressed natural gas (mostly methane)
 *   Diesel:  B7, B10, B20 (biodiesel blend %)
 *
 * Each fuel has different:
 *   - Stoichiometric AFR (affects fuel consumption calc + DCAFR)
 *   - Density (affects volume/mass conversion)
 *   - Latent heat of vaporization (affects evaporative cooling)
 *   - Ethanol content (affects O2 in exhaust, AFR learning)
 *   - Energy density (affects power output per liter)
 *
 * For air density calculations, the key impacts are:
 *   1. AFR → vapor displacement fraction (LVD) changes
 *   2. LHV_vap → evaporative cooling correction (ECC) changes
 *   3. Thermal efficiency → PDI normalization changes
 *
 * For fuel consumption:
 *   - Different AFR → different fuel mass flow for same MAF
 *   - Different density → different volume per mass
 *
 * For fuel map:
 *   - E20/E85 have different fuel trims than pure petrol
 *   - Diesel doesn't use fuel trim (no STFT/LTFT PID)
 *   - NGV uses different AFR → different trim baseline
 *
 * References:
 *   - DOE AFDC fuel properties database
 *   - SAE J1829 fuel composition standards
 *   - Thailand DEDB fuel specifications
 */
public final class FuelProperties {

    private FuelProperties() {}

    /**
     * Complete fuel property set for one fuel type.
     */
    public static final class Props {
        public final String displayName;
        public final String shortCode;       // for CSV/log (compact)
        public final double stoichAFR;       // stoichiometric air-fuel ratio
        public final double densityGL;        // density (g/L) at 15°C, 1 atm
        public final double molarMassGmol;   // avg fuel vapor molar mass (g/mol) — for molar LVD
        public final double lhvVapKJkg;      // latent heat of vaporization (kJ/kg)
        public final double ethanolPct;      // ethanol content (0–100%)
        public final double thermalEff;      // typical brake thermal efficiency
        public final double energyDensityMJL; // lower heating value (MJ/L)
        public final boolean isGaseous;       // true = injected as vapor (NGV, LPG vapor)
        public final boolean hasFuelTrim;    // false = diesel (no STFT/LTFT PID)

        Props(String displayName, String shortCode, double stoichAFR, double densityGL,
              double molarMassGmol, double lhvVapKJkg, double ethanolPct, double thermalEff,
              double energyDensityMJL, boolean isGaseous, boolean hasFuelTrim) {
            this.displayName = displayName;
            this.shortCode = shortCode;
            this.stoichAFR = stoichAFR;
            this.densityGL = densityGL;
            this.molarMassGmol = molarMassGmol;
            this.lhvVapKJkg = lhvVapKJkg;
            this.ethanolPct = ethanolPct;
            this.thermalEff = thermalEff;
            this.energyDensityMJL = energyDensityMJL;
            this.isGaseous = isGaseous;
            this.hasFuelTrim = hasFuelTrim;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  FUEL DATABASE
    // ═══════════════════════════════════════════════════════════
    //
    // Sources: DOE AFDC, SAE J1829, Thailand DEDB
    // AFR values are stoichiometric (mass basis)
    // Density at 15°C, 1 atm unless noted
    // LHV_vap: 0 for diesel (DI, no manifold evap) and NGV (already gas)

    // molarMassGmol: average molar mass of the fuel vapor (g/mol), used for the
    // molar/volumetric vapor-displacement fraction (LVD). Blends use the
    // mass-harmonic average M = 1 / Σ(w_i / M_i). Air is 28.97 g/mol.

    /** Petrol RON91 (E10) — 91 octane, up to 10% ethanol */
    public static final Props PETROL_91 = new Props(
        "Gasohol 91", "G91", 14.23, 741.0, 100.0, 340.0, 10.0, 0.28, 32.2, false, true);

    /** Petrol RON95 (E10) — 95 octane, up to 10% ethanol */
    public static final Props PETROL_95 = new Props(
        "Gasohol 95", "G95", 14.23, 741.0, 100.0, 340.0, 10.0, 0.28, 32.2, false, true);

    /** E20 — RON95, 20% ethanol (popular in Thailand) */
    public static final Props E20 = new Props(
        "Gasohol E20", "E20", 13.75, 745.0, 81.0, 330.0, 20.0, 0.29, 30.8, false, true);

    /** E85 — 85% ethanol (flex-fuel vehicles) */
    public static final Props E85 = new Props(
        "Gasohol E85", "E85", 9.77, 783.0, 50.0, 280.0, 85.0, 0.30, 25.8, false, true);

    /** LPG autogas — propane/butane mix (Thai ratio ~60:40 → ~50 g/mol) */
    public static final Props LPG = new Props(
        "LPG", "LPG", 15.5, 510.0, 50.0, 370.0, 0.0, 0.30, 25.0, true, true);

    /** NGV/CNG — compressed natural gas (mostly methane, ~16.7 g/mol) */
    public static final Props NGV = new Props(
        "NGV/CNG", "NGV", 17.2, 0.72, 16.7, 0.0, 0.0, 0.30, 8.8, true, true);

    /** Diesel B7 — 7% biodiesel blend */
    public static final Props DIESEL_B7 = new Props(
        "Diesel B7", "D7", 14.45, 833.0, 200.0, 0.0, 0.0, 0.38, 35.8, false, false);

    /** Diesel B20 — 20% biodiesel blend */
    public static final Props DIESEL_B20 = new Props(
        "Diesel B20", "B20", 14.30, 835.0, 205.0, 0.0, 0.0, 0.38, 35.6, false, false);

    /**
     * Get fuel properties for a FuelMode.
     * Maps the app's FuelMode enum to the full property set.
     */
    public static Props get(FuelMode mode) {
        if (mode == null) return PETROL_95; // safe default
        switch (mode) {
            case LPG:    return LPG;
            case NGV:    return NGV;
            case E20:    return E20;
            case E85:    return E85;
            case DIESEL: return DIESEL_B7;
            case B20:    return DIESEL_B20;
            case PETROL_91: return PETROL_91;
            case PETROL:
            default:     return PETROL_95;
        }
    }

    /**
     * Get fuel properties by short code (for log replay parsing).
     */
    public static Props getByCode(String code) {
        if (code == null) return PETROL_95;
        switch (code.toUpperCase()) {
            case "LPG":  return LPG;
            case "NGV":  return NGV;
            case "E20":  return E20;
            case "E85":  return E85;
            case "D7":   return DIESEL_B7;
            case "B20":  return DIESEL_B20;
            case "G91":  return PETROL_91;
            case "G95":
            case "PETROL":
            default:     return PETROL_95;
        }
    }
}
