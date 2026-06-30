package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provides PID support hints based on vehicle brand and manufacturing year.
 *
 * This is a <em>secondary</em> detection mechanism — the primary method is
 * {@link PidAvailabilityChecker#querySupportedPids}, which asks the vehicle
 * directly via OBD2 PID 0x00/0x20/0x40. This class is used as a fallback when
 * the live query fails (e.g. some cheap ELM327 clones return garbage for
 * bitmap queries) and as a pre-filter to reduce detection time.
 *
 * Data is derived from common automotive OBD2 implementation patterns.
 * It is intentionally conservative — when in doubt, we include a PID rather
 * than exclude it, since querying an unsupported PID is a minor latency cost
 * but skipping a supported PID means lost data.
 */
public final class BrandYearProfile {

    private BrandYearProfile() {
    }

    /**
     * Vehicle brand, detected from the VIN World Manufacturer Identifier (WMI).
     */
    public enum Brand {
        TOYOTA,
        HONDA,
        NISSAN,
        MAZDA,
        MITSUBISHI,
        SUBARU,
        SUZUKI,
        HYUNDAI,
        KIA,
        FORD,
        GM,
        CHEVROLET,
        CHRYSLER,
        DODGE,
        JEEP,
        BMW,
        MERCEDES,
        VAG,
        VOLKSWAGEN,
        AUDI,
        PORSCHE,
        VOLVO,
        PEUGEOT,
        RENAULT,
        CITROEN,
        FIAT,
        ALFA_ROMEO,
        LANCIA,
        FERRARI,
        LADA,
        TATA,
        MAHINDRA,
        ISUZU,
        HINO,
        GENERIC;
    }

    /**
     * Determine the likely brand from the VIN's WMI (first 3 chars).
     *
     * @param vin 17-character VIN, or null
     * @return detected brand, or GENERIC if unknown
     */
    public static Brand brandFromVin(String vin) {
        if (vin == null || vin.length() < 3) {
            return Brand.GENERIC;
        }
        String wmi = vin.substring(0, 3).toUpperCase();

        // Japanese manufacturers (J)
        if (wmi.startsWith("JT")) return Brand.TOYOTA;
        if (wmi.startsWith("JH") || wmi.startsWith("JHM")) return Brand.HONDA;
        if (wmi.startsWith("JN")) return Brand.NISSAN;
        if (wmi.startsWith("JM")) return Brand.MAZDA;
        if (wmi.startsWith("JA") || wmi.startsWith("JMY")
                || wmi.startsWith("MK2") || wmi.startsWith("MM3")
                || wmi.startsWith("MMB") || wmi.startsWith("MMA")
                || wmi.startsWith("4A3") || wmi.startsWith("4A4")) return Brand.MITSUBISHI;
        if (wmi.startsWith("JF")) return Brand.SUBARU;
        if (wmi.startsWith("JS")) return Brand.SUZUKI;
        if (wmi.startsWith("J")) return Brand.TOYOTA; // Default Japanese

        // Korean manufacturers (K)
        if (wmi.startsWith("KM") || wmi.startsWith("KN") || wmi.startsWith("K")) return Brand.HYUNDAI;

        // US manufacturers (1, 4)
        if (wmi.startsWith("1F") || wmi.startsWith("1FA")) return Brand.FORD;
        if (wmi.startsWith("1G") || wmi.startsWith("1G1")) return Brand.GM;
        if (wmi.startsWith("1C") || wmi.startsWith("2C")) return Brand.CHRYSLER;
        if (wmi.startsWith("1") || wmi.startsWith("4")) return Brand.GM; // US default

        // German manufacturers (W, S)
        if (wmi.startsWith("WBA") || wmi.startsWith("WBS") || wmi.startsWith("WBW")) return Brand.BMW;
        if (wmi.startsWith("WDB") || wmi.startsWith("WDD") || wmi.startsWith("WDC")) return Brand.MERCEDES;
        if (wmi.startsWith("WVW") || wmi.startsWith("WV1") || wmi.startsWith("WAU")) return Brand.VOLKSWAGEN;
        if (wmi.startsWith("W") || wmi.startsWith("S")) return Brand.VOLKSWAGEN; // German default

        // European manufacturers (V, 3)
        if (wmi.startsWith("VS") || wmi.startsWith("VSS")) return Brand.VOLVO;
        if (wmi.startsWith("V") || wmi.startsWith("3")) return Brand.PEUGEOT; // European default

        // Italian manufacturers (Z)
        if (wmi.startsWith("Z")) return Brand.FIAT;

        return Brand.GENERIC;
    }

    /**
     * Estimate the manufacturing year from the VIN's 10th character
     * (model year code per SAE/ISO standard).
     *
     * @param vin 17-character VIN
     * @return estimated year, or 0 if it cannot be determined
     */
    public static int yearFromVin(String vin) {
        if (vin == null || vin.length() < 10) {
            return 0;
        }
        char code = vin.charAt(9);
        // SAE model year codes:
        // A=2010, B=2011, ... H=2017, J=2018, K=2019, L=2020, M=2021, N=2022,
        // P=2023, R=2024, S=2025, T=2026, V=2027, W=2028, X=2029, Y=2030
        // 1=2031, 2=2032, ... 9=2039
        // Also: 5=2005, 6=2006, 7=2007, 8=2008, 9=2009 for older cars
        switch (code) {
            case 'A': return 2010;
            case 'B': return 2011;
            case 'C': return 2012;
            case 'D': return 2013;
            case 'E': return 2014;
            case 'F': return 2015;
            case 'G': return 2016;
            case 'H': return 2017;
            case 'J': return 2018;
            case 'K': return 2019;
            case 'L': return 2020;
            case 'M': return 2021;
            case 'N': return 2022;
            case 'P': return 2023;
            case 'R': return 2024;
            case 'S': return 2025;
            case 'T': return 2026;
            case 'V': return 2027;
            case 'W': return 2028;
            case 'X': return 2029;
            case 'Y': return 2030;
            case '1': return 2031;
            case '2': return 2032;
            case '3': return 2033;
            case '4': return 2034;
            case '5': return 2005;
            case '6': return 2006;
            case '7': return 2007;
            case '8': return 2008;
            case '9': return 2009;
            default: return 0;
        }
    }

    /**
     * Get a PID support profile for a given brand and year.
     * Returns the set of PID hex codes that are likely supported.
     *
     * @param brand vehicle brand
     * @param year  manufacturing year (0 if unknown)
     * @return set of supported PID hex codes, or null if brand is null
     */
    public static Set<String> getProfile(Brand brand, int year) {
        if (brand == null) {
            return null;
        }

        Set<String> pids = new HashSet<>();

        // Base PIDs supported by ALL OBD-II vehicles (1996+)
        Collections.addAll(pids,
                "00", "01", "03", "04", "05", "06", "07", "0B", "0C", "0D",
                "0E", "0F", "11", "1C", "1F", "20"
        );

        // O2 sensor PIDs (0x14-0x1B) — supported by most vehicles
        Collections.addAll(pids,
                "14", "15", "16", "17", "18", "19", "1A", "1B"
        );

        // Post-2008 vehicles: additional PIDs
        if (year >= 2008 || year == 0) {
            Collections.addAll(pids,
                    "08", "09", "0A", "10", "21", "23", "2F", "31", "33",
                    "40", "42", "43", "44", "46"
            );
        }

        // Post-2010 vehicles: even more PIDs
        if (year >= 2010 || year == 0) {
            Collections.addAll(pids, "51", "52");
        }

        // Brand-specific additions
        switch (brand) {
            case TOYOTA:
            case HONDA:
            case NISSAN:
            case MAZDA:
                // Japanese cars: usually good PID coverage
                if (year >= 2010 || year == 0) {
                    Collections.addAll(pids, "5D", "5E");
                }
                break;

            case BMW:
            case MERCEDES:
            case VAG:
            case VOLKSWAGEN:
            case AUDI:
            case PORSCHE:
                // European: wideband lambda common
                if (year >= 2008 || year == 0) {
                    Collections.addAll(pids, "34", "5D", "5E");
                }
                break;

            case FORD:
            case GM:
            case CHEVROLET:
            case CHRYSLER:
            case DODGE:
            case JEEP:
                // US domestic: usually comprehensive
                if (year >= 2010 || year == 0) {
                    Collections.addAll(pids, "5D", "5E");
                }
                break;

            case HYUNDAI:
            case KIA:
                // Korean: good coverage post-2010
                if (year >= 2010 || year == 0) {
                    Collections.addAll(pids, "5D", "5E");
                }
                break;
            default:
                // GENERIC or unknown: don't add extra PIDs beyond the base set
                break;
        }

        return pids;
    }

    /**
     * Get a PID support profile from the VIN directly.
     *
     * @param vin 17-character VIN, or null
     * @return set of supported PID hex codes, or null if VIN is unavailable
     */
    public static Set<String> getProfileFromVin(String vin) {
        if (vin == null || vin.length() < 10) {
            return null;
        }
        Brand brand = brandFromVin(vin);
        int year = yearFromVin(vin);
        return getProfile(brand, year);
    }
}