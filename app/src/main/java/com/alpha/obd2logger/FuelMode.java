package com.alpha.obd2logger;

/**
 * Fuel mode selection — covers all Thai fuel types.
 *
 * The value string is written to CSV/JSONL logs and must be backwards-compatible:
 *   - "petrol" and "lpg/cng" are the legacy values used by existing logs.
 *   - New fuels use their short code (G95, E20, E85, D7, B20, NGV).
 *
 * The FuelMapView groups fuels into two "sides" for comparison:
 *   - PETROL side: PETROL, PETROL_91, E20, E85 (liquid petrol-ish fuels)
 *   - LPG side: LPG, NGV (gaseous fuels)
 *   - DIESEL, B20 use their own map (no fuel trim PIDs)
 *
 * Note: "lpg/cng" is kept as the legacy value for LPG to not break old logs.
 */
public enum FuelMode {
    // Legacy values (backwards-compatible with existing CSV logs)
    LPG("lpg/cng"),        // LPG autogas (propane/butane)
    PETROL("petrol"),       // Petrol/gasohol 95 (E10, default)

    // New Thai fuel types
    PETROL_91("G91"),      // Gasohol 91 (E10)
    E20("E20"),            // Gasohol E20 (20% ethanol)
    E85("E85"),            // Gasohol E85 (85% ethanol)
    NGV("NGV"),            // Compressed natural gas
    DIESEL("D7"),          // Diesel B7
    B20("B20");            // Diesel B20

    private final String value;

    FuelMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Parse a fuel mode string from CSV/JSONL log.
     * Handles both legacy values ("petrol", "lpg/cng") and new codes.
     */
    public static FuelMode fromString(String s) {
        if (s == null || s.isEmpty()) return PETROL;
        String lower = s.toLowerCase();
        switch (lower) {
            case "lpg/cng":
            case "lpg":
                return LPG;
            case "petrol":
            case "g95":
                return PETROL;
            case "g91":
                return PETROL_91;
            case "e20":
                return E20;
            case "e85":
                return E85;
            case "ngv":
            case "cng":
                return NGV;
            case "d7":
            case "diesel":
                return DIESEL;
            case "b20":
                return B20;
            default:
                return PETROL;
        }
    }

    /**
     * Returns true if this fuel is a gaseous fuel (injected as vapor).
     * Gaseous fuels have higher vapor displacement and no evaporative cooling.
     */
    public boolean isGaseous() {
        return this == LPG || this == NGV;
    }

    /**
     * Returns true if this fuel uses fuel trim PIDs (STFT/LTFT).
     * Diesel (B7, B20) does not have fuel trim — it uses different injection.
     */
    public boolean hasFuelTrim() {
        return this != DIESEL && this != B20;
    }

    /**
     * Returns true if this is a diesel-type fuel.
     */
    public boolean isDiesel() {
        return this == DIESEL || this == B20;
    }

    /**
     * Returns true if this fuel contains ethanol (E10, E20, E85).
     */
    public boolean hasEthanol() {
        return this == E20 || this == E85 || this == PETROL || this == PETROL_91;
    }

    /**
     * Group label for the fuel map comparison view.
     * Returns "petrol" for liquid petrol fuels, "lpg" for gaseous, "diesel" for diesel.
     */
    public String mapGroup() {
        if (isDiesel()) return "diesel";
        if (isGaseous()) return "lpg";
        return "petrol";
    }
}
