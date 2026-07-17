package com.alpha.obd2logger;

/**
 * Detects vehicle brand from VIN prefix (WMI — World Manufacturer Identifier).
 * Covers all major brands sold in the Thai market, including Chinese EVs.
 *
 * VIN WMI is the first 3 characters of the VIN:
 *   Position 1: Country (J=Japan, T=Taiwan, L=China, W=Germany, 1/4=USA, K=Korea, etc.)
 *   Position 2-3: Manufacturer code
 */
public final class VinBrandDetector {

    private VinBrandDetector() {}

    public enum Brand {
        TOYOTA, LEXUS, HONDA, ISUZU, NISSAN, MITSUBISHI, MAZDA,
        SUZUKI, FORD, CHEVROLET, HYUNDAI, KIA, VOLVO, BMW, MERCEDES,
        SUBARU, VOLKSWAGEN, AUDI, PORSCHE, RENAULT, PEUGEOT, CITROEN,
        FIAT, JEEP, DODGE, CHRYSLER, LAND_ROVER, TATA, MAHINDRA, HINO,
        BYD, GWM, NETA, AION, DEEPAL, MG, TESLA, UNKNOWN
    }

    /** Detect brand from VIN. Returns UNKNOWN if not recognized. */
    public static Brand detect(String vin) {
        // WMI identification also accepts a partial VIN; the full VIN reader
        // separately enforces the 17-character structure before persistence.
        if (vin == null || vin.trim().length() < 3) return Brand.UNKNOWN;
        String wmi = getWmi(vin);

        // High-specificity rules precede legacy families. This prevents broad
        // prefixes (JM, MM, KN and LG) from stealing another make's WMI.
        if (isOneOf(wmi, "JTH", "JTJ", "2T2")) return Brand.LEXUS;
        if (isOneOf(wmi, "MNT", "MNF", "SNJ")) return Brand.NISSAN;
        if (isOneOf(wmi, "JM1", "JM3", "JM7", "JMZ", "MM6", "MM7", "MM8")) return Brand.MAZDA;
        if (wmi.startsWith("KN") || isOneOf(wmi, "5XX", "5XY")) return Brand.KIA;
        if (isOneOf(wmi, "LGW", "LGB", "LGA")) return Brand.GWM;
        if (isOneOf(wmi, "L6G", "LGG")) return Brand.AION;
        if (isOneOf(wmi, "LGX")) return Brand.BYD;
        if (wmi.startsWith("JF") || isOneOf(wmi, "4S3", "4S4")) return Brand.SUBARU;
        if (isOneOf(wmi, "WAU", "WUA", "TRU")) return Brand.AUDI;
        if (isOneOf(wmi, "WP0", "WP1")) return Brand.PORSCHE;
        if (wmi.startsWith("WVW") || isOneOf(wmi, "WV1", "WV2", "LFV")) return Brand.VOLKSWAGEN;
        if (isOneOf(wmi, "VF1")) return Brand.RENAULT;
        if (isOneOf(wmi, "VF3")) return Brand.PEUGEOT;
        if (isOneOf(wmi, "VF7")) return Brand.CITROEN;
        if (isOneOf(wmi, "ZFA", "3C3")) return Brand.FIAT;
        if (wmi.startsWith("1J") || isOneOf(wmi, "1C4")) return Brand.JEEP;
        if (wmi.startsWith("1D") || wmi.startsWith("2D") || wmi.startsWith("3D")) return Brand.DODGE;
        if (wmi.startsWith("1C") || wmi.startsWith("2C")) return Brand.CHRYSLER;
        if (isOneOf(wmi, "SAL", "SAD")) return Brand.LAND_ROVER;
        if (isOneOf(wmi, "MAT")) return Brand.TATA;
        if (isOneOf(wmi, "MA1")) return Brand.MAHINDRA;
        if (isOneOf(wmi, "JH5")) return Brand.HINO;

        // ── Toyota ──
        // Thailand: MR0/MNH | Japan: JT | Turkey: NMT | other major Toyota plants.
        // MR0 is especially common on Thai-built Hilux/Fortuner vehicles.
        if (wmi.startsWith("JT") || wmi.startsWith("MN") || wmi.startsWith("NL")
                || wmi.equals("MR0")) {
            if (wmi.equals("JTH") || wmi.equals("JTF")) return Brand.LEXUS;
            return Brand.TOYOTA;
        }
        // ── Honda ──
        // Thailand: MHR, MRH | Japan: JHM, JHN
        if (wmi.startsWith("JH") || wmi.equals("MHR") || wmi.equals("MRH")) return Brand.HONDA;

        // ── Isuzu ──
        // Thailand: MPA, MPB | Japan: JAL, JAA
        if (wmi.startsWith("JA") || wmi.equals("MPA") || wmi.equals("MPB")) return Brand.ISUZU;

        // ── Nissan ──
        // Thailand: MNT (shared with Toyota sometimes), JN1, JN3
        if (wmi.startsWith("JN")) return Brand.NISSAN;
        if (wmi.equals("MNF") || wmi.equals("SNJ")) return Brand.NISSAN;

        // ── Mitsubishi ──
        // Thailand: MMF, MMA | Japan: JMB, JMY
        if (wmi.startsWith("JM") || wmi.startsWith("MM")) return Brand.MITSUBISHI;

        // ── Mazda ──
        // Thailand: MMF (shared), JM1, JM3
        if (wmi.equals("JM1") || wmi.equals("JM3")) return Brand.MAZDA;

        // ── Suzuki ──
        // Japan: JS1, JS3, JS4 | Thailand: MA3
        if (wmi.startsWith("JS") || wmi.equals("MA3")) return Brand.SUZUKI;

        // ── Ford ──
        // Thailand: MAF, MLA | USA: 1FA | Germany: WF0
        if (wmi.equals("MAF") || wmi.equals("MLA") || wmi.startsWith("1F") || wmi.equals("WF0")) return Brand.FORD;

        // ── Chevrolet ──
        // Thailand: MMX, 1G1 | Korea: KL1
        if (wmi.startsWith("1G") || wmi.equals("KL1") || wmi.equals("MMX")) return Brand.CHEVROLET;

        // ── Hyundai ──
        // Korea: KMH, KNA, KMH | Thailand: MMR
        if (wmi.startsWith("KM") || wmi.startsWith("KN") || wmi.equals("MMR")) return Brand.HYUNDAI;

        // ── Kia ──
        // Korea: KNA, KNB, KND
        if (wmi.startsWith("KN") && (wmi.charAt(2) >= 'A' && wmi.charAt(2) <= 'Z')) return Brand.KIA;

        // ── Volvo ──
        // Sweden: YV1, YV4 | Belgium: VYV
        if (wmi.startsWith("YV") || wmi.startsWith("VY")) return Brand.VOLVO;

        // ── BMW ──
        // Germany: WBA, WBS | Thailand: MLB
        if (wmi.startsWith("WB") || wmi.equals("MLB")) return Brand.BMW;

        // ── Mercedes-Benz ──
        // Germany: WDB, WDD, WME
        if (wmi.startsWith("WD") || wmi.equals("WME")) return Brand.MERCEDES;

        // ── BYD ──
        // China: LGXCHE, LGX | VIN starts with LGX
        if (wmi.equals("LGX")) return Brand.BYD;

        // ── GWM (Great Wall Motors / Haval / Ora) ──
        // China: LGB, LGA
        if (wmi.startsWith("LGB") || wmi.equals("LGA")) return Brand.GWM;

        // ── NETA (Hozon Auto) ──
        // China: LUN
        if (wmi.startsWith("LUN") || wmi.equals("LZ1")) return Brand.NETA;

        // ── AION (GAC) ──
        // China: L6G, LGG
        if (wmi.startsWith("L6G") || wmi.startsWith("LGG")) return Brand.AION;

        // ── Deepal (Changan) ──
        // China: LSC, LSC
        if (wmi.startsWith("LSC")) return Brand.DEEPAL;

        // ── MG (SAIC) ──
        // China: LSG, LJS
        if (wmi.startsWith("LSJ") || wmi.startsWith("LJS")) return Brand.MG;

        // ── Tesla ──
        // USA: 5YJ, 7SA | China: LRW | Germany: XP7
        if (wmi.equals("5YJ") || wmi.equals("7SA") || wmi.equals("LRW") || wmi.equals("XP7")) return Brand.TESLA;

        // ── Generic China (L-prefix not yet matched) ──
        if (wmi.startsWith("L")) return Brand.UNKNOWN;

        return Brand.UNKNOWN;
    }

    public static String getWmi(String vin) {
        if (vin == null || vin.length() < 3) return "";
        return vin.substring(0, 3).trim().toUpperCase(java.util.Locale.US);
    }

    /** Validates the 17-character VIN alphabet. Check-digit validation is kept
     * separate because position 9 is not mandatory in every sales region. */
    public static boolean isStructurallyValid(String vin) {
        if (vin == null) return false;
        String normalized = vin.trim().toUpperCase(java.util.Locale.US);
        return normalized.length() == 17 && normalized.matches("[A-HJ-NPR-Z0-9]{17}");
    }

    /** Validates the ISO/North-American VIN check digit at position 9. */
    public static boolean hasValidCheckDigit(String vin) {
        if (!isStructurallyValid(vin)) return false;
        String s = vin.trim().toUpperCase(java.util.Locale.US);
        int[] weights = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < s.length(); i++) {
            int value = vinValue(s.charAt(i));
            if (value < 0) return false;
            sum += value * weights[i];
        }
        int remainder = sum % 11;
        char expected = remainder == 10 ? 'X' : (char) ('0' + remainder);
        return s.charAt(8) == expected;
    }

    private static int vinValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        switch (c) {
            case 'A': case 'J': return 1;
            case 'B': case 'K': case 'S': return 2;
            case 'C': case 'L': case 'T': return 3;
            case 'D': case 'M': case 'U': return 4;
            case 'E': case 'N': case 'V': return 5;
            case 'F': case 'W': return 6;
            case 'G': case 'P': case 'X': return 7;
            case 'H': case 'Y': return 8;
            case 'R': case 'Z': return 9;
            default: return -1;
        }
    }

    private static boolean isOneOf(String value, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(value)) return true;
        }
        return false;
    }

    /** Get the DTC database asset filename for a brand. */
    public static String getDtcDatabaseAsset(Brand brand) {
        switch (brand) {
            case TOYOTA:     return "dtc_toyota.json";
            case LEXUS:      return "dtc_toyota.json";      // shares Toyota codes
            case HONDA:      return "dtc_honda.json";
            case ISUZU:      return "dtc_isuzu.json";
            case NISSAN:     return "dtc_nissan.json";
            case MITSUBISHI: return "dtc_mitsubishi.json";
            case MAZDA:      return "dtc_ford.json";         // shares Ford architecture
            case SUZUKI:     return "dtc_suzuki.json";
            case FORD:       return "dtc_ford.json";
            case CHEVROLET:  return "dtc_chevrolet.json";
            case HYUNDAI:    return "dtc_hyundai.json";
            case KIA:        return "dtc_hyundai.json";      // shares Hyundai codes
            case VOLVO:      return "dtc_volvo.json";
            case BMW:        return "dtc_bmw.json";
            case MERCEDES:   return "dtc_mercedes.json";
            case BYD:        return "dtc_byd.json";
            case GWM:        return "dtc_gwm.json";
            case NETA:       return "dtc_neta.json";
            case AION:       return "dtc_aion.json";
            case DEEPAL:     return "dtc_deepal.json";
            case MG:         return "dtc_mg.json";
            case TESLA:      return "dtc_tesla.json";
            default:         return null;
        }
    }

    /** Human-readable brand name. */
    public static String getBrandName(Brand brand) {
        switch (brand) {
            case TOYOTA:     return "Toyota";
            case LEXUS:      return "Lexus";
            case HONDA:      return "Honda";
            case ISUZU:      return "Isuzu";
            case NISSAN:     return "Nissan";
            case MITSUBISHI: return "Mitsubishi";
            case MAZDA:      return "Mazda";
            case SUZUKI:     return "Suzuki";
            case FORD:       return "Ford";
            case CHEVROLET:  return "Chevrolet";
            case HYUNDAI:    return "Hyundai";
            case KIA:        return "Kia";
            case VOLVO:      return "Volvo";
            case BMW:        return "BMW";
            case MERCEDES:   return "Mercedes-Benz";
            case SUBARU:     return "Subaru";
            case VOLKSWAGEN: return "Volkswagen";
            case AUDI:       return "Audi";
            case PORSCHE:    return "Porsche";
            case RENAULT:    return "Renault";
            case PEUGEOT:    return "Peugeot";
            case CITROEN:    return "Citroën";
            case FIAT:       return "Fiat";
            case JEEP:       return "Jeep";
            case DODGE:      return "Dodge";
            case CHRYSLER:   return "Chrysler";
            case LAND_ROVER: return "Land Rover";
            case TATA:       return "Tata";
            case MAHINDRA:   return "Mahindra";
            case HINO:       return "Hino";
            case BYD:        return "BYD";
            case GWM:        return "GWM (Haval/Ora)";
            case NETA:       return "NETA (Hozon)";
            case AION:       return "AION (GAC)";
            case DEEPAL:     return "Deepal (Changan)";
            case MG:         return "MG (SAIC)";
            case TESLA:      return "Tesla";
            default:         return "Unknown";
        }
    }

    /** Detect brand from VIN and return the DTC database asset filename. */
    public static String getDtcDatabaseForVin(String vin) {
        Brand brand = detect(vin);
        return getDtcDatabaseAsset(brand);
    }
}
