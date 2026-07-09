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
        BYD, GWM, NETA, AION, DEEPAL, MG, TESLA, UNKNOWN
    }

    /** Detect brand from VIN. Returns UNKNOWN if not recognized. */
    public static Brand detect(String vin) {
        if (vin == null || vin.length() < 3) return Brand.UNKNOWN;
        String wmi = vin.substring(0, 3).toUpperCase();

        // ── Toyota ──
        // Thailand: JTH, JTN, MNH, MNT | Japan: JT | Turkey: NLT | France: JTE
        if (wmi.startsWith("JT") || wmi.startsWith("MN") || wmi.startsWith("NL")) {
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
        if (wmi.startsWith("LG")) return Brand.BYD;

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
        if (wmi.startsWith("LSG") || wmi.startsWith("LSJ") || wmi.startsWith("LJS")) return Brand.MG;

        // ── Tesla ──
        // USA: 5YJ, 7SA | China: LRW | Germany: XP7
        if (wmi.equals("5YJ") || wmi.equals("7SA") || wmi.equals("LRW") || wmi.equals("XP7")) return Brand.TESLA;

        // ── Generic China (L-prefix not yet matched) ──
        if (wmi.startsWith("L")) return Brand.UNKNOWN;

        return Brand.UNKNOWN;
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
