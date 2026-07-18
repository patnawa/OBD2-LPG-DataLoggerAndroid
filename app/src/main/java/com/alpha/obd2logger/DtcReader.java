package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;

/**
 * Reads and clears Diagnostic Trouble Codes via OBD2 Mode 03/07/0A.
 *
 * v3.0: Multi-brand, multi-protocol support for Thai-market vehicles.
 *   - Exhaustive deep scan across all OBD2 protocol buses
 *   - ECU name database: Toyota, Honda, Mazda, Isuzu, Nissan, Ford + generic
 *   - Per-protocol scan status tracking
 *   - Backward-compatible simple API preserved
 */
public final class DtcReader {

    private DtcReader() {}

    /** Current vehicle brand — set via setBrand() from DtcDatabase.initForVin(). */
    private static volatile VinBrandDetector.Brand currentBrand = null;

    /**
     * Set the current vehicle brand so ECU module names use the correct
     * manufacturer labels instead of whatever brand was last to put() into
     * the shared map.
     */
    public static void setBrand(VinBrandDetector.Brand brand) {
        currentBrand = brand;
    }

    /** Get the current vehicle brand (for moduleNameForCanId). */
    public static VinBrandDetector.Brand getBrand() {
        return currentBrand;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Protocol Bus Definitions
    // ═══════════════════════════════════════════════════════════════

    /** A protocol bus that can be scanned for DTCs. */
    public static class ProtocolBus {
        public final String label;           // "HS-CAN (auto)", "MS-CAN", "CAN 29-bit"
        public final String atSpCommand;     // "ATSP0", "ATSPB", "ATSP7", etc.
        public final String description;     // User-visible description
        public final boolean isMsCan;        // true = MS-CAN body bus
        public final String atPbParams;      // optional: AT PB custom baud params, null if none

        public ProtocolBus(String label, String atSpCommand, String description,
                           boolean isMsCan, String atPbParams) {
            this.label = label;
            this.atSpCommand = atSpCommand;
            this.description = description;
            this.isMsCan = isMsCan;
            this.atPbParams = atPbParams;
        }

        @Override
        public String toString() { return label + " (" + description + ")"; }
    }

    /** All protocol buses scanned in a deep scan. Ordered by likelihood. */
    static final List<ProtocolBus> ALL_BUSES = new ArrayList<>();

    /** Fast scan: auto-detect only (for auto-scan on logger start). */
    static final List<ProtocolBus> FAST_BUSES = new ArrayList<>();

    static {
        // ── Primary buses (always scanned) ──
        // ATSP0 = auto-detect → covers 90% of vehicles
        FAST_BUSES.add(new ProtocolBus("HS-CAN (auto)", "ATSP0",
            "Auto-detect: CAN 11/500, CAN 29/500, KWP, ISO9141",
            false, null));

        // ── Secondary buses (deep scan only) ──
        // MS-CAN — Ford/Mazda body modules
        ALL_BUSES.add(new ProtocolBus("MS-CAN", "ATSPB",
            "Ford/Mazda medium-speed CAN 125kbps — Body/GEM/IC modules",
            true, "ATPB4001"));
        // CAN 29-bit 500kbps — Isuzu D-Max, trucks, J1939
        ALL_BUSES.add(new ProtocolBus("CAN 29-bit", "ATSP7",
            "Isuzu D-Max, trucks, SAE J1939 — 29-bit CAN IDs",
            false, null));
        // CAN 11-bit 250kbps — older Mazda, Ford, European
        ALL_BUSES.add(new ProtocolBus("CAN 11-bit 250k", "ATSP8",
            "Older Mazda/Ford/European — CAN 250 kbps",
            false, null));
        // ISO 14230 KWP2000 fast init — older Toyota diesel (Hilux Vigo, Fortuner)
        ALL_BUSES.add(new ProtocolBus("KWP2000 Fast", "ATSP5",
            "Older Toyota Diesel (Hilux Vigo, Fortuner 2004-2011) — ISO 14230 KWP fast",
            false, null));
        // ISO 9141-2 — older Honda, Nissan (Civic EG/EK, Sunny N16)
        ALL_BUSES.add(new ProtocolBus("ISO 9141-2", "ATSP3",
            "Older Honda (Civic EG/EK), Nissan (Sunny N16) — 5-baud init",
            false, null));
        // SAE J1850 VPW — some older GM/Isuzu (MU-7, D-Max 2004)
                ALL_BUSES.add(new ProtocolBus("J1850 VPW", "ATSP2",
                    "Older Isuzu MU-7, D-Max 2004 — SAE J1850 VPW 10.4kbps",
                    false, null));

                // ── Extended CAN buses (vLinker MS 5-channel support) ──
                // These unlock modules on GM/Lexus/Chrysler vehicles that the
                // standard 7-protocol deep scan misses entirely. Requires a
                // vLinker MS or equivalent adapter with 5 CAN channel support.

                // SW-CAN — Single-Wire CAN 33.3kbps (GM/Lexus body modules)
                ALL_BUSES.add(new ProtocolBus("SW-CAN", "ATSPA",
                    "GM/Lexus single-wire CAN 33.3kbps — Body/Door/Lighting/Seat modules",
                    false, null));

                // CH-CAN — Chrysler High-Speed CAN (FCA/Jeep/Ram/Chrysler)
                ALL_BUSES.add(new ProtocolBus("CH-CAN", "ATSPC",
                    "Chrysler/FCA/Jeep/Ram — CAN 11-bit 500kbps Chrysler-specific",
                    false, null));

                // LS-CAN — Low-Speed CAN 125kbps (GM/Chrysler comfort modules)
                ALL_BUSES.add(new ProtocolBus("LS-CAN", "ATSPD",
                    "GM/Chrysler low-speed CAN 125kbps — Comfort/BCM/Lighting modules",
                    false, null));
            }

    // ═══════════════════════════════════════════════════════════════
    //  Module Detection (ECU CAN ID → Name)
    // ═══════════════════════════════════════════════════════════════

    /** ECU CAN IDs — generic defaults (non-brand-specific IDs). */
    static final Map<Integer, String> ECU_NAMES = new LinkedHashMap<>();

    /** Per-brand ECU name maps — checked before the generic ECU_NAMES so
     *  the correct manufacturer label is shown for shared CAN IDs like
     *  0x7E0 (Toyota, Mazda, and Nissan all use it). */
    static final Map<Integer, String> TOYOTA_ECU = new LinkedHashMap<>();
    static final Map<Integer, String> HONDA_ECU  = new LinkedHashMap<>();
    static final Map<Integer, String> MAZDA_ECU  = new LinkedHashMap<>();
    static final Map<Integer, String> NISSAN_ECU = new LinkedHashMap<>();
    static final Map<Integer, String> ISUZU_ECU  = new LinkedHashMap<>();
    static final Map<Integer, String> MITSUBISHI_ECU = new LinkedHashMap<>();

    /** GM/Chevrolet ECU names — HS-CAN + SW-CAN + LS-CAN modules. */
    static final Map<Integer, String> GM_ECU = new LinkedHashMap<>();

    /** Chrysler/Jeep/FCA ECU names — CH-CAN + LS-CAN modules. */
    static final Map<Integer, String> CHRYSLER_ECU = new LinkedHashMap<>();

    /** Lexus SW-CAN module names — supplements Toyota map for SW-CAN bus. */
    static final Map<Integer, String> LEXUS_ECU = new LinkedHashMap<>();

    /** Ford HS-CAN names — override shared IDs when Ford mode is active. */
    static final Map<Integer, String> FORD_HS_CAN_NAMES = new LinkedHashMap<>();

    static {
        // ── Ford HS-CAN (overrides shared 0x7E0-0x7EF when Ford Mode active) ──
        FORD_HS_CAN_NAMES.put(0x7E0, "PCM — Powertrain Control (Ford)");
        FORD_HS_CAN_NAMES.put(0x7E1, "TCM — Transmission (Ford)");
        FORD_HS_CAN_NAMES.put(0x7E2, "ABS Module (Ford)");
        FORD_HS_CAN_NAMES.put(0x7E3, "RCM — Restraints Control (Ford)");
        FORD_HS_CAN_NAMES.put(0x7E4, "IPC — Instrument Panel (Ford)");
        FORD_HS_CAN_NAMES.put(0x7E5, "PSCM — Power Steering (Ford)");
        FORD_HS_CAN_NAMES.put(0x7E6, "HVAC — Climate Control (Ford)");
        FORD_HS_CAN_NAMES.put(0x7E7, "APIM — Sync/Infotainment (Ford)");
        FORD_HS_CAN_NAMES.put(0x7E8, "PCM Response");
        FORD_HS_CAN_NAMES.put(0x7E9, "TCM Response");
        FORD_HS_CAN_NAMES.put(0x7EA, "ABS Module Response");
        FORD_HS_CAN_NAMES.put(0x7EB, "RCM Response");
        FORD_HS_CAN_NAMES.put(0x7EC, "IPC Response");
        FORD_HS_CAN_NAMES.put(0x7ED, "PSCM Response");
        FORD_HS_CAN_NAMES.put(0x7EE, "HVAC Response");
        FORD_HS_CAN_NAMES.put(0x7EF, "APIM Response");

        // ── Toyota ──
        TOYOTA_ECU.put(0x7E0, "ECM — Engine Control (Toyota)");
        TOYOTA_ECU.put(0x7E1, "TCM — Transmission (Toyota)");
        TOYOTA_ECU.put(0x7E2, "ABS/VSC — Brakes (Toyota)");
        TOYOTA_ECU.put(0x7E3, "SRS — Airbag (Toyota)");
        TOYOTA_ECU.put(0x7E4, "HV ECU — Hybrid Vehicle (Toyota)");
        TOYOTA_ECU.put(0x7E5, "EPS — Power Steering (Toyota)");
        TOYOTA_ECU.put(0x7E6, "A/C — Air Conditioner (Toyota)");
        TOYOTA_ECU.put(0x7E7, "BCM — Body Control (Toyota)");
        TOYOTA_ECU.put(0x7E8, "ECM Response");
        TOYOTA_ECU.put(0x7E9, "TCM Response");
        TOYOTA_ECU.put(0x7EA, "ABS Response");
        TOYOTA_ECU.put(0x7EB, "SRS Response");
        TOYOTA_ECU.put(0x7EC, "HV ECU Response");
        TOYOTA_ECU.put(0x7ED, "EPS Response");
        TOYOTA_ECU.put(0x7EE, "A/C Response");
        TOYOTA_ECU.put(0x7EF, "BCM Response");

        // ── Honda ──
        HONDA_ECU.put(0x7C0, "PGM-FI — Fuel Injection (Honda)");
        HONDA_ECU.put(0x7C1, "AT — Auto Transmission (Honda)");
        HONDA_ECU.put(0x7C2, "VSA — Stability Assist (Honda)");
        HONDA_ECU.put(0x7C3, "SRS — Airbag (Honda)");
        HONDA_ECU.put(0x7C8, "PGM-FI Response");

        // ── Mazda ── (shares Ford architecture)
        MAZDA_ECU.put(0x7E0, "PCM — Powertrain (Mazda)");
        MAZDA_ECU.put(0x7E1, "TCM — Transmission (Mazda)");
        MAZDA_ECU.put(0x7E2, "ABS/DSC (Mazda)");
        MAZDA_ECU.put(0x7E3, "EPS — Steering (Mazda)");
        MAZDA_ECU.put(0x7E8, "PCM Response");
        MAZDA_ECU.put(0x7E9, "TCM Response");

        // ── Nissan ──
        NISSAN_ECU.put(0x7E0, "ECM — Engine (Nissan)");
        NISSAN_ECU.put(0x7E1, "TCM — CVT/Auto (Nissan)");
        NISSAN_ECU.put(0x7E2, "ABS/VDC (Nissan)");
        NISSAN_ECU.put(0x7E3, "SRS — Airbag (Nissan)");
        NISSAN_ECU.put(0x7E8, "ECM Response");
        NISSAN_ECU.put(0x7E9, "TCM Response");

        // ── Isuzu D-Max (29-bit CAN) ──
        ISUZU_ECU.put(0x18DA00F1, "ECM — Engine (Isuzu D-Max)");
        ISUZU_ECU.put(0x18DA00F2, "TCM — Transmission (Isuzu)");
        ISUZU_ECU.put(0x18DA00F3, "ABS — Brakes (Isuzu)");

        // ── Mitsubishi (unique CAN IDs) ──
        MITSUBISHI_ECU.put(0x762, "AWC/S-AWC — All Wheel Control (Mitsubishi)");
        MITSUBISHI_ECU.put(0x763, "ASC — Active Stability (Mitsubishi)");
        MITSUBISHI_ECU.put(0x764, "ETACS — Body Control/BCM (Mitsubishi)");
        MITSUBISHI_ECU.put(0x765, "EPS — Steering (Mitsubishi)");
        MITSUBISHI_ECU.put(0x76A, "KOS/OSS — Keyless Operation (Mitsubishi)");
        MITSUBISHI_ECU.put(0x76B, "TPMS — Tire Pressure (Mitsubishi)");
        MITSUBISHI_ECU.put(0x72E, "4WD — Transfer Case (Mitsubishi Triton/Pajero)");
        MITSUBISHI_ECU.put(0x72F, "AFS — Adaptive Front Light (Mitsubishi)");
        MITSUBISHI_ECU.put(0x744, "MMCS — Multimedia/Navi (Mitsubishi)");
        MITSUBISHI_ECU.put(0x611, "Engine-ECU Diesel (Mitsubishi 4D56/4N15)");
        MITSUBISHI_ECU.put(0x619, "Engine-ECU Response (Mitsubishi Diesel)");

                // ── GM/Chevrolet (HS-CAN + SW-CAN + LS-CAN) ──
                // HS-CAN powertrain (shares 0x7E0-0x7EF with other brands)
                GM_ECU.put(0x7E0, "ECM — Engine Control (GM)");
                GM_ECU.put(0x7E1, "TCM — Transmission (GM)");
                GM_ECU.put(0x7E2, "EBCM — Electronic Brake (GM)");
                GM_ECU.put(0x7E3, "SDM — Airbag/SIR (GM)");
                GM_ECU.put(0x7E4, "HVAC — Climate (GM)");
                GM_ECU.put(0x7E5, "BCM — Body Control (GM)");
                GM_ECU.put(0x7E8, "ECM Response");
                GM_ECU.put(0x7E9, "TCM Response");
                GM_ECU.put(0x7EA, "EBCM Response");
                GM_ECU.put(0x7EB, "SDM Response");
                // SW-CAN body modules (GM single-wire CAN 33.3kbps)
                GM_ECU.put(0x640, "Driver Door Module (GM SW-CAN)");
                GM_ECU.put(0x641, "Passenger Door Module (GM SW-CAN)");
                GM_ECU.put(0x642, "Rear Left Door Module (GM SW-CAN)");
                GM_ECU.put(0x643, "Rear Right Door Module (GM SW-CAN)");
                GM_ECU.put(0x644, "Driver Seat Module (GM SW-CAN)");
                GM_ECU.put(0x645, "Memory Seat Module (GM SW-CAN)");
                // LS-CAN comfort modules (GM low-speed CAN 125kbps)
                GM_ECU.put(0x680, "Dash/IPC — Instrument Panel (GM LS-CAN)");
                GM_ECU.put(0x681, "Radio/Infotainment (GM LS-CAN)");
                GM_ECU.put(0x682, "HVAC Control (GM LS-CAN)");
                GM_ECU.put(0x683, "Lighting Control (GM LS-CAN)");

                // ── Chrysler/Jeep/FCA (CH-CAN + LS-CAN) ──
                // CH-CAN powertrain
                CHRYSLER_ECU.put(0x7E0, "PCM — Powertrain (Chrysler)");
                CHRYSLER_ECU.put(0x7E1, "TCM — Transmission (Chrysler)");
                CHRYSLER_ECU.put(0x7E2, "ABS — Brakes (Chrysler)");
                CHRYSLER_ECU.put(0x7E3, "ORC — Occupant Restraint (Chrysler)");
                CHRYSLER_ECU.put(0x7E4, "HVAC — Climate (Chrysler)");
                CHRYSLER_ECU.put(0x7E8, "PCM Response");
                CHRYSLER_ECU.put(0x7E9, "TCM Response");
                // LS-CAN comfort/body
                CHRYSLER_ECU.put(0x710, "BCM — Body Control (Chrysler LS-CAN)");
                CHRYSLER_ECU.put(0x711, "AMP — Audio Amplifier (Chrysler LS-CAN)");
                CHRYSLER_ECU.put(0x712, "HVAC Control (Chrysler LS-CAN)");
                CHRYSLER_ECU.put(0x713, "Radio/Infotainment (Chrysler LS-CAN)");
                CHRYSLER_ECU.put(0x714, "Instrument Cluster (Chrysler LS-CAN)");

                // ── Lexus SW-CAN (supplements Toyota for SW-CAN bus) ──
                LEXUS_ECU.put(0x7E0, "ECM — Engine Control (Lexus)");
                LEXUS_ECU.put(0x7E2, "ABS/VSC — Brakes (Lexus)");
                LEXUS_ECU.put(0x7E3, "SRS — Airbag (Lexus)");
                LEXUS_ECU.put(0x7E5, "EPS — Power Steering (Lexus)");
                // SW-CAN body
                LEXUS_ECU.put(0x740, "Door Control (Lexus SW-CAN)");
                LEXUS_ECU.put(0x741, "Lighting Control (Lexus SW-CAN)");
                LEXUS_ECU.put(0x742, "Seat Control (Lexus SW-CAN)");
                LEXUS_ECU.put(0x743, "Air Conditioning (Lexus SW-CAN)");

                // ── Generic ECU names (non-brand-specific IDs) ──
        ECU_NAMES.put(0x726, "GEM — Generic Electronic Module (Ford)");
        ECU_NAMES.put(0x727, "SJB — Smart Junction Box");
        ECU_NAMES.put(0x728, "BCM — Body Control (Ford)");
        ECU_NAMES.put(0x733, "IC — Instrument Cluster");
        ECU_NAMES.put(0x736, "DATC — Climate Control");
        ECU_NAMES.put(0x737, "PAM — Parking Aid");
        ECU_NAMES.put(0x73C, "DSM — Driver Seat");
        ECU_NAMES.put(0x73D, "DDM — Driver Door");
        ECU_NAMES.put(0x73E, "PDM — Passenger Door");
        ECU_NAMES.put(0x745, "ACM — Audio");
        ECU_NAMES.put(0x750, "FCIM — Front Controls");
    }

    /**
     * Look up ECU name — checks brand-specific map first so shared CAN IDs
     * like 0x7E0 show the correct manufacturer (Toyota vs Nissan vs Mazda).
     * Falls back to the generic ECU_NAMES map, then a heuristic.
     */
    private static String moduleNameForCanId(int ecuId, boolean fordMode) {
        // Ford mode: Ford HS-CAN overrides take priority
        if (fordMode) {
            String fordName = FORD_HS_CAN_NAMES.get(ecuId);
            if (fordName != null) return fordName;
        }

        // Brand-specific map — set via setBrand() from DtcDatabase.initForVin()
        Map<Integer, String> brandMap = getBrandEcuMap(currentBrand);
        if (brandMap != null) {
            String brandName = brandMap.get(ecuId);
            if (brandName != null) return brandName;
        }

        // Generic ECU names (non-brand-specific IDs like Ford MS-CAN body modules)
        String name = ECU_NAMES.get(ecuId);
        if (name != null) return name;

        // Generic fallback based on common patterns
        if (ecuId >= 0x7E0 && ecuId <= 0x7EF) {
            return String.format("ECU 0x%03X (HS-CAN)", ecuId);
        }
        if (ecuId >= 0x720 && ecuId <= 0x7FF) {
            return String.format("Module 0x%03X (MS-CAN)", ecuId);
        }
        return String.format("Module 0x%X", ecuId);
    }

    /** Get the per-brand ECU name map for the detected vehicle brand. */
    private static Map<Integer, String> getBrandEcuMap(VinBrandDetector.Brand brand) {
            if (brand == null) return null;
            // brand_profiles.json is authoritative when present so a new brand's
            // module names ship over the air; the switch is the offline fallback.
            Map<Integer, String> fromProfile = BrandProfile.ecuNamesFor(brand);
            if (fromProfile != null) return fromProfile;
            switch (brand) {
                case TOYOTA: case LEXUS: return TOYOTA_ECU;
                case HONDA:             return HONDA_ECU;
                case MAZDA:             return MAZDA_ECU;
                case NISSAN:            return NISSAN_ECU;
                case ISUZU:             return ISUZU_ECU;
                case MITSUBISHI:        return MITSUBISHI_ECU;
                case FORD:              return FORD_HS_CAN_NAMES;
                case CHEVROLET:         return GM_ECU;
                case CHRYSLER: case JEEP: case DODGE: return CHRYSLER_ECU;
                default:                return null;
            }
        }

    // ═══════════════════════════════════════════════════════════════
    //  Progress Listener (real-time scan status callbacks)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Receives real-time updates as the DTC scan progresses through each
     * protocol bus, mode, and module. All callbacks fire on the scanning
     * thread — the UI layer must marshal to the main thread.
     */
    public interface DtcScanProgressListener {

        /** A protocol bus is being probed (ATSP set, 0100 sent). */
        void onProtocolProbeStart(String busLabel, String description, int busIndex, int totalBuses);

        /** The protocol probe completed — did any ECU respond? */
        void onProtocolProbeResult(String busLabel, boolean responded, int modulesFound);

        /** Starting to scan a specific DTC mode on this bus. */
        void onModeScanStart(String busLabel, String modeName);

        /** A specific ECU module was detected with its DTC counts. */
        void onModuleDetected(String busLabel, String canId, String moduleName,
                              int storedCount, int pendingCount, int permanentCount);

        /** A mode scan completed for this bus. */
        void onModeScanComplete(String busLabel, String modeName, int dtcsFound);

        /** The entire scan finished. */
        void onScanComplete(int totalProtocols, int protocolsResponded, int totalDtcCount);
    }

    /** No-op listener to keep scanBuses simple. */
    private static final DtcScanProgressListener NULL_LISTENER = new DtcScanProgressListener() {
        @Override public void onProtocolProbeStart(String l, String d, int bi, int tb) {}
        @Override public void onProtocolProbeResult(String l, boolean r, int m) {}
        @Override public void onModeScanStart(String l, String m) {}
        @Override public void onModuleDetected(String l, String c, String n, int s, int p, int per) {}
        @Override public void onModeScanComplete(String l, String m, int d) {}
        @Override public void onScanComplete(int tp, int pr, int td) {}
    };

    // ═══════════════════════════════════════════════════════════════
    //  Data Types
    // ═══════════════════════════════════════════════════════════════

    /** Information about a detected ECU module during DTC scan. */
    public static class ModuleInfo {
        public final String canId;              // e.g. "7E8", "18DA00F1"
        public final int ecuId;                 // numeric CAN ID
        public final String moduleName;         // human-readable
        public final String protocolLabel;      // "MS-CAN", "HS-CAN (auto)", etc.
        public final boolean moduleScanned;     // responded to at least one mode
        public final boolean storedOk, pendingOk, permanentOk;
        public final int storedDtcCount, pendingDtcCount, permanentDtcCount;

        ModuleInfo(String canId, int ecuId, String moduleName, String protocolLabel,
                   boolean moduleScanned, boolean storedOk, boolean pendingOk,
                   boolean permanentOk, int storedDtcCount, int pendingDtcCount,
                   int permanentDtcCount) {
            this.canId = canId;
            this.ecuId = ecuId;
            this.moduleName = moduleName;
            this.protocolLabel = protocolLabel;
            this.moduleScanned = moduleScanned;
            this.storedOk = storedOk;
            this.pendingOk = pendingOk;
            this.permanentOk = permanentOk;
            this.storedDtcCount = storedDtcCount;
            this.pendingDtcCount = pendingDtcCount;
            this.permanentDtcCount = permanentDtcCount;
        }

        public int getTotalDtcCount() {
            return storedDtcCount + pendingDtcCount + permanentDtcCount;
        }

        @Override
        public String toString() {
            return canId + " (" + moduleName + ") [" + protocolLabel + "]";
        }

        // ── Builder ──────────────────────────────────────────────

        public static class Builder {
            final int ecuId;
            final String canId;
            final String protocolLabel;
            final String moduleName;
            boolean storedOk, pendingOk, permanentOk;
            int storedDtcCount, pendingDtcCount, permanentDtcCount;

            public Builder(int ecuId, String protocolLabel, boolean fordMode) {
                this.ecuId = ecuId;
                this.canId = ecuId > 0xFFFF
                    ? String.format("%08X", ecuId)
                    : String.format("%03X", ecuId);
                this.protocolLabel = protocolLabel;
                this.moduleName = moduleNameForCanId(ecuId, fordMode);
            }

            public ModuleInfo build() {
                boolean scanned = storedOk || pendingOk || permanentOk;
                return new ModuleInfo(canId, ecuId, moduleName, protocolLabel,
                    scanned, storedOk, pendingOk, permanentOk,
                    storedDtcCount, pendingDtcCount, permanentDtcCount);
            }
        }
    }

    /** Status of a single protocol bus after scanning. */
    public static class ProtocolScanStatus {
        public final ProtocolBus bus;
        public final boolean responded;       // got any response from this bus
        public final int modulesFound;        // distinct ECUs that responded
        public final int totalDtcCount;       // DTCs found on this bus

        ProtocolScanStatus(ProtocolBus bus, boolean responded, int modulesFound, int totalDtcCount) {
            this.bus = bus;
            this.responded = responded;
            this.modulesFound = modulesFound;
            this.totalDtcCount = totalDtcCount;
        }
    }

    /** Complete DTC scan result. */
    public static class DtcScanResult {
        public final List<DtcCode> storedDtcs;
        public final List<DtcCode> pendingDtcs;
        public final List<DtcCode> permanentDtcs;
        public final List<ModuleInfo> modules;
        public final List<ProtocolScanStatus> protocolStatuses;
        public final int protocolsScanned;
        public final int protocolsResponded;

        DtcScanResult(List<DtcCode> stored, List<DtcCode> pending,
                      List<DtcCode> permanent, List<ModuleInfo> modules,
                      List<ProtocolScanStatus> protocolStatuses) {
            this.storedDtcs = stored;
            this.pendingDtcs = pending;
            this.permanentDtcs = permanent;
            this.modules = modules;
            this.protocolStatuses = protocolStatuses;
            this.protocolsScanned = protocolStatuses.size();
            int responded = 0;
            for (ProtocolScanStatus s : protocolStatuses) {
                if (s.responded) responded++;
            }
            this.protocolsResponded = responded;
        }

        public static DtcScanResult empty() {
            return new DtcScanResult(new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fast DTC scan: auto-detect only (one protocol bus).
     * Used by auto-scan on logger start.
     */
    public static DtcScanResult readAllDtcs(BaseDriver driver, boolean fordMsCan) {
        return readAllDtcs(driver, fordMsCan, null);
    }

    /**
     * Fast DTC scan with real-time progress callbacks.
     */
    public static DtcScanResult readAllDtcs(BaseDriver driver, boolean fordMsCan,
                                            DtcScanProgressListener listener) {
        if (driver == null || !driver.isConnected()) {
            if (listener != null) listener.onScanComplete(0, 0, 0);
            return DtcScanResult.empty();
        }

        List<ProtocolBus> buses = new ArrayList<>();
        buses.add(FAST_BUSES.get(0)); // auto-detect

        // If MS-CAN enabled, add it as a secondary bus
        if (fordMsCan && ALL_BUSES.size() > 0) {
            buses.add(ALL_BUSES.get(0)); // MS-CAN
        }

        return scanBuses(driver, buses, fordMsCan, false,
                listener != null ? listener : NULL_LISTENER);
    }

    /**
     * Deep DTC scan: try ALL protocol buses sequentially.
     * Covers every protocol for Thai-market vehicles.
     * Use for manual "Deep Scan" button — slower but exhaustive.
     *
     * @param fordMode use Ford-specific ECU names on HS-CAN
     * @return comprehensive scan result with per-protocol status
     */
    public static DtcScanResult readAllDtcsDeep(BaseDriver driver, boolean fordMode) {
        return readAllDtcsDeep(driver, fordMode, null);
    }

    /**
     * Deep DTC scan with real-time progress callbacks.
     */
    public static DtcScanResult readAllDtcsDeep(BaseDriver driver, boolean fordMode,
                                                DtcScanProgressListener listener) {
        if (driver == null || !driver.isConnected()) {
            if (listener != null) listener.onScanComplete(0, 0, 0);
            return DtcScanResult.empty();
        }

        // Build full bus list: primary auto-detect + all secondary buses
        List<ProtocolBus> buses = new ArrayList<>();
        buses.add(FAST_BUSES.get(0)); // auto-detect first
        buses.addAll(ALL_BUSES);       // then all secondary buses

        // Deep scan additionally sweeps physical ECU addresses.
        return scanBuses(driver, buses, fordMode, true,
                listener != null ? listener : NULL_LISTENER);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Multi-Bus Scan Engine
    // ═══════════════════════════════════════════════════════════════

    /**
     * Scan DTCs across multiple protocol buses.
     * For each bus: switch protocol, scan Modes 03/07/0A with headers,
     * collect module info, restore auto protocol.
     */
    private static DtcScanResult scanBuses(BaseDriver driver, List<ProtocolBus> buses,
                                           boolean physicalSweep,
                                           boolean fordMode, DtcScanProgressListener listener) {
        List<DtcCode> allStored = new ArrayList<>();
        List<DtcCode> allPending = new ArrayList<>();
        List<DtcCode> allPermanent = new ArrayList<>();
        List<ModuleInfo> allModules = new ArrayList<>();
        List<ProtocolScanStatus> statuses = new ArrayList<>();
        java.util.Set<String> seenCanIds = new java.util.HashSet<>();

        // Save current protocol to restore at end
        driver.sendCommandRaw("ATSP0"); // start clean
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}

        int totalBuses = buses.size();
        for (int busIdx = 0; busIdx < buses.size(); busIdx++) {
            ProtocolBus bus = buses.get(busIdx);
            listener.onProtocolProbeStart(bus.label, bus.description, busIdx, totalBuses);

            BusScanResult result;
            try {
                result = scanSingleBus(driver, bus, fordMode, physicalSweep, listener);
            } catch (Exception e) {
                android.util.Log.e("DtcReader", "Bus scan failed: " + bus.label, e);
                statuses.add(new ProtocolScanStatus(bus, false, 0, 0));
                listener.onProtocolProbeResult(bus.label, false, 0);
                continue;
            }

            listener.onProtocolProbeResult(bus.label, result.anyResponse, result.modules.size());

            // Fire module-detected callbacks for newly seen modules
            for (ModuleInfo mod : result.modules) {
                if (seenCanIds.add(mod.canId + "@" + mod.protocolLabel)) {
                    allModules.add(mod);
                    listener.onModuleDetected(bus.label, mod.canId, mod.moduleName,
                            mod.storedDtcCount, mod.pendingDtcCount, mod.permanentDtcCount);
                }
            }

            // Merge DTCs (deduplicate by code string)
            for (DtcCode c : result.stored) {
                if (!containsCode(allStored, c)) allStored.add(c);
            }
            for (DtcCode c : result.pending) {
                if (!containsCode(allPending, c)) allPending.add(c);
            }
            for (DtcCode c : result.permanent) {
                if (!containsCode(allPermanent, c)) allPermanent.add(c);
            }

            statuses.add(new ProtocolScanStatus(
                bus, result.anyResponse,
                result.modules.size(),
                result.stored.size() + result.pending.size() + result.permanent.size()
            ));
        }

        // Restore the exact live-polling state. In particular, do not leave
        // ATCFC0 behind and do not overwrite an explicitly selected protocol
        // with ATSP0. Both caused "connected but empty" data after scans.
        if (driver instanceof ElmDriver) {
            ((ElmDriver) driver).restorePollingState();
        } else {
            driver.sendCommandRaw("ATCFC1");
            driver.sendCommandRaw("ATH0");
            driver.sendCommandRaw("ATSP0");
            driver.sendCommandRaw("0100");
        }
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        int totalDtcCount = allStored.size() + allPending.size() + allPermanent.size();
        int protocolsResponded = 0;
        for (ProtocolScanStatus s : statuses) {
            if (s.responded) protocolsResponded++;
        }
        listener.onScanComplete(totalBuses, protocolsResponded, totalDtcCount);

        return new DtcScanResult(allStored, allPending, allPermanent, allModules, statuses);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Single-Bus Scan
    // ═══════════════════════════════════════════════════════════════

    private static class BusScanResult {
        final List<DtcCode> stored, pending, permanent;
        final List<ModuleInfo> modules;
        final boolean anyResponse;

        BusScanResult(List<DtcCode> stored, List<DtcCode> pending,
                      List<DtcCode> permanent, List<ModuleInfo> modules,
                      boolean anyResponse) {
            this.stored = stored;
            this.pending = pending;
            this.permanent = permanent;
            this.modules = modules;
            this.anyResponse = anyResponse;
        }
    }

    private static BusScanResult scanSingleBus(BaseDriver driver, ProtocolBus bus,
                                                   boolean fordMode, boolean physicalSweep,
                                                   DtcScanProgressListener listener) {
            List<DtcCode> stored = new ArrayList<>();
            List<DtcCode> pending = new ArrayList<>();
            List<DtcCode> permanent = new ArrayList<>();
            Map<Integer, ModuleInfo.Builder> moduleBuilders = new LinkedHashMap<>();
            boolean anyResponse = false;

            // Switch to this protocol
            driver.sendCommandRaw(bus.atSpCommand);
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}

            // Apply custom baud params if needed (MS-CAN: AT PB 40 01)
            if (bus.atPbParams != null) {
                driver.sendCommandRaw(bus.atPbParams);
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            // ── ISO-TP flow control: enable auto flow control for multi-frame responses ──
            // Without this, ECUs that return many DTCs in a single multi-frame response
            // will be truncated at the first frame (7 bytes = ~3 DTCs max).
            driver.sendCommandRaw("ATCFC1");
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            // ── Protocol probe: send 0100 to check if any ECU is alive on this bus ──
            // This avoids false "NO DATA" results from a bus that has no ECU at all.
            String probe = sendWithRetry(driver, "0100", 2, 150);
            if (!isValidResponse(probe)) {
                // Mode 01 PID 00 is a powertrain-emissions service. The body and
                // comfort buses (MS-CAN, SW-CAN, LS-CAN) carry BCM / IPC / door /
                // seat modules that never implement it but do answer Mode 03 —
                // so gating solely on 0100 skipped exactly the buses this scan
                // was added to reach. Try a DTC-service probe before giving up.
                probe = sendWithRetry(driver, "03", 2, 150);
                if (!isValidResponse(probe)) {
                    // Nothing on this protocol at all — skip the DTC scan.
                    driver.sendCommandRaw("ATCFC0");
                    return new BusScanResult(stored, pending, permanent, new ArrayList<>(), false);
                }
            }
            // A valid Mode 01 probe proves this bus responded even when the ECU has
            // zero DTCs or does not implement one of Modes 07/0A.
            anyResponse = true;

            // Enable headers to see CAN IDs.
            driver.sendCommandRaw("ATH1");
            // Spaces MUST be on for the header parser to separate the CAN ID
            // from the payload. Polling runs with ATS0 (compact) for speed, and
            // enabling headers without re-enabling spaces yields "7E80643..." —
            // one token — which the parser cannot split, so every CAN frame was
            // dropped and no module or DTC was ever reported. Restored to ATS0
            // in the finally block below.
            driver.sendCommandRaw("ATS1");
            // Polling runs at ATST32 (200 ms) because a PID reply comes from one
            // ECU. A functionally-addressed Mode 03 must collect replies from
            // every module on the bus, and gatewayed body/ABS/SRS modules answer
            // well past 200 ms — they were being cut off. Adaptive timing stays
            // on (ATAT1 from init), so this raises the ceiling to ~1.02 s
            // without making fast buses wait for it. Restored in the finally.
            driver.sendCommandRaw("ATSTFF");
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            try {
                // Mode 03 — Stored DTCs (retry up to 3 times with backoff)
                listener.onModeScanStart(bus.label, "Mode 03 (Stored)");
                String raw03 = sendWithRetry(driver, "03", 3, 200);
                if (isValidResponse(raw03)) {
                    ScanLineResult slr = parseWithModuleHeaders(raw03, "43", bus.label);
                    stored.addAll(slr.codes);
                    for (Map.Entry<Integer, List<DtcCode>> e : slr.perModule.entrySet()) {
                        ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(e.getKey(),
                            k -> new ModuleInfo.Builder(k, bus.label, fordMode));
                        mb.storedOk = true;
                        mb.storedDtcCount = e.getValue().size();
                    }
                    if (!slr.codes.isEmpty()) anyResponse = true;
                }
                listener.onModeScanComplete(bus.label, "Mode 03 (Stored)", stored.size());

                // Mode 07 — Pending DTCs
                listener.onModeScanStart(bus.label, "Mode 07 (Pending)");
                String raw07 = sendWithRetry(driver, "07", 3, 200);
                if (isValidResponse(raw07)) {
                    ScanLineResult slr = parseWithModuleHeaders(raw07, "47", bus.label);
                    pending.addAll(slr.codes);
                    for (Map.Entry<Integer, List<DtcCode>> e : slr.perModule.entrySet()) {
                        ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(e.getKey(),
                            k -> new ModuleInfo.Builder(k, bus.label, fordMode));
                        mb.pendingOk = true;
                        mb.pendingDtcCount = e.getValue().size();
                    }
                }
                listener.onModeScanComplete(bus.label, "Mode 07 (Pending)", pending.size());

                // Mode 0A — Permanent DTCs
                listener.onModeScanStart(bus.label, "Mode 0A (Permanent)");
                String raw0A = sendWithRetry(driver, "0A", 3, 200);
                if (isValidResponse(raw0A)) {
                    ScanLineResult slr = parseWithModuleHeaders(raw0A, "4A", bus.label);
                    permanent.addAll(slr.codes);
                    for (Map.Entry<Integer, List<DtcCode>> e : slr.perModule.entrySet()) {
                        ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(e.getKey(),
                            k -> new ModuleInfo.Builder(k, bus.label, fordMode));
                        mb.permanentOk = true;
                        mb.permanentDtcCount = e.getValue().size();
                    }
                }
                listener.onModeScanComplete(bus.label, "Mode 0A (Permanent)", permanent.size());

                // Functional addressing only reaches modules that answer the
                // broadcast. Sweep physical addresses to find the rest.
                if (physicalSweep) {
                    sweepPhysicalAddresses(driver, bus, fordMode, moduleBuilders,
                            stored, pending, permanent, listener);
                }

            } finally {
            driver.sendCommandRaw("ATH0");
            // Restore the compact (spaces-off) format the polling loop expects.
            driver.sendCommandRaw("ATS0");
            // Restore the fast polling timeout raised for the scan above.
            driver.sendCommandRaw("ATST32");
            // Restore automatic ISO-TP flow control for normal PID polling.
            driver.sendCommandRaw("ATCFC1");
        }

        // Build module list
        List<ModuleInfo> modules = new ArrayList<>();
        for (ModuleInfo.Builder mb : moduleBuilders.values()) {
            modules.add(mb.build());
        }

        // If we didn't get headers but got DTCs, create a synthetic module entry
        if (modules.isEmpty() && (!stored.isEmpty() || !pending.isEmpty() || !permanent.isEmpty())) {
            ModuleInfo fallback = new ModuleInfo("???", 0,
                bus.label + " (no header info)", bus.label,
                true, !stored.isEmpty(), !pending.isEmpty(), !permanent.isEmpty(),
                stored.size(), pending.size(), permanent.size());
            modules.add(fallback);
        }

        return new BusScanResult(stored, pending, permanent, modules, anyResponse);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Physical Address Sweep
    // ═══════════════════════════════════════════════════════════════

    /** Standard 11-bit OBD request addresses; responses are request + 8. */
    private static final int PHYSICAL_SWEEP_FIRST = 0x7E0;
    private static final int PHYSICAL_SWEEP_LAST = 0x7E7;

    /** 11-bit UDS convention: an ECU answers 8 above its request address. */
    private static final int RESPONSE_OFFSET = 0x08;

    /**
     * ELM protocols that carry 11-bit CAN and therefore accept an
     * {@code ATSH7Ex} request header. 29-bit (ATSP7) and the K-line / VPW
     * protocols use different addressing and are deliberately excluded rather
     * than swept with headers that cannot apply to them.
     */
    static boolean supportsElevenBitPhysicalAddressing(ProtocolBus bus) {
        if (bus == null || bus.atSpCommand == null) return false;
        switch (bus.atSpCommand) {
            case "ATSP0":   // auto — usually resolves to 11-bit CAN
            case "ATSP6":
            case "ATSP8":
            case "ATSPA":   // SW-CAN
            case "ATSPB":   // MS-CAN
            case "ATSPC":   // CH-CAN
            case "ATSPD":   // LS-CAN
                return true;
            default:
                return false;
        }
    }

    /**
     * Query each ECU address directly instead of relying on the broadcast.
     *
     * <p>The scan previously used functional addressing ({@code 7DF}) only, so a
     * module was found solely if it chose to answer the broadcast. Gatewayed
     * body, ABS and SRS modules routinely do not, which is why the module list
     * was far shorter than the vehicle's real module count — the ECU name table
     * knows ~60 CAN IDs but only 6 were ever queried.
     *
     * <p>Cost is bounded: a missing address costs one Mode 03 request, because
     * Modes 07/0A are only sent to addresses that actually answered. Addresses
     * already discovered functionally are skipped entirely.
     */
    private static void sweepPhysicalAddresses(BaseDriver driver, ProtocolBus bus,
                                               boolean fordMode,
                                               Map<Integer, ModuleInfo.Builder> moduleBuilders,
                                               List<DtcCode> stored, List<DtcCode> pending,
                                               List<DtcCode> permanent,
                                               DtcScanProgressListener listener) {
        if (!supportsElevenBitPhysicalAddressing(bus)) return;

        // Standard powertrain block plus every request-side ID the brand table
        // knows about, so brand-specific modules (Honda 7C0, Mitsubishi 762…)
        // are reached too.
        java.util.LinkedHashSet<Integer> candidates = new java.util.LinkedHashSet<>();
        for (int id = PHYSICAL_SWEEP_FIRST; id <= PHYSICAL_SWEEP_LAST; id++) {
            candidates.add(id);
        }
        Map<Integer, String> brandMap = getBrandEcuMap(currentBrand);
        if (brandMap != null) {
            for (Integer id : brandMap.keySet()) {
                // Keep request addresses only — a response ID is what an ECU
                // replies on, addressing it as a request reaches nothing.
                if (id != null && id > 0 && id <= 0x7FF
                        && !isLikelyResponseAddress(id, brandMap)) {
                    candidates.add(id);
                }
            }
        }

        listener.onModeScanStart(bus.label, "Physical address sweep");
        int found = 0;
        try {
            for (Integer tx : candidates) {
                int rx = tx + RESPONSE_OFFSET;
                // Already answered the broadcast — nothing to gain.
                if (moduleBuilders.containsKey(rx)) continue;

                String txHex = String.format(Locale.US, "%03X", tx);
                String rxHex = String.format(Locale.US, "%03X", rx);
                String setHeader = driver.sendCommandRaw("ATSH" + txHex);
                if (setHeader != null && setHeader.contains("?")) {
                    // Adapter rejected the header (e.g. ATSP0 resolved to 29-bit).
                    // Sweeping the rest would fail identically, so stop here.
                    break;
                }
                driver.sendCommandRaw("ATCRA" + rxHex);

                // One probe decides whether this address exists at all.
                String raw03 = sendWithRetry(driver, "03", 1, 100);
                if (!isValidResponse(raw03)) continue;

                List<DtcCode> ecuStored = parseDtcResponse(raw03, "43");
                ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(rx,
                        k -> new ModuleInfo.Builder(k, bus.label, fordMode));
                mb.storedOk = true;
                mb.storedDtcCount = ecuStored.size();
                addNewCodes(stored, ecuStored);
                found++;

                String raw07 = sendWithRetry(driver, "07", 1, 100);
                if (isValidResponse(raw07)) {
                    List<DtcCode> ecuPending = parseDtcResponse(raw07, "47");
                    mb.pendingOk = true;
                    mb.pendingDtcCount = ecuPending.size();
                    addNewCodes(pending, ecuPending);
                }

                String raw0A = sendWithRetry(driver, "0A", 1, 100);
                if (isValidResponse(raw0A)) {
                    List<DtcCode> ecuPermanent = parseDtcResponse(raw0A, "4A");
                    mb.permanentOk = true;
                    mb.permanentDtcCount = ecuPermanent.size();
                    addNewCodes(permanent, ecuPermanent);
                }

                // No onModuleDetected here on purpose: these builders flow into
                // the bus result, and scanBuses fires the callback for every
                // module it contains (deduplicated by CAN ID + protocol).
                // Announcing here as well would double every swept module.
            }
        } finally {
            // Bare ATCRA clears the filter; restore functional addressing so the
            // next bus (and the polling loop) is not left talking to one ECU.
            driver.sendCommandRaw("ATCRA");
            driver.sendCommandRaw("ATSH7DF");
        }
        listener.onModeScanComplete(bus.label, "Physical address sweep", found);
    }

    /**
     * A brand table lists both request and response IDs. Only request IDs are
     * worth sweeping, and the response entries are the ones whose name says so
     * or that sit {@link #RESPONSE_OFFSET} above a listed request.
     */
    static boolean isLikelyResponseAddress(int id, Map<Integer, String> brandMap) {
        String name = brandMap.get(id);
        if (name != null && name.toLowerCase(Locale.US).contains("response")) return true;
        return brandMap.containsKey(id - RESPONSE_OFFSET);
    }

    /** Append only codes not already present, preserving existing dedup. */
    private static void addNewCodes(List<DtcCode> target, List<DtcCode> incoming) {
        for (DtcCode code : incoming) {
            if (!target.contains(code)) target.add(code);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Response Parsing with Module Headers
    // ═══════════════════════════════════════════════════════════════

    // Package-private so the header parser can be regression-tested.
    static class ScanLineResult {
        final List<DtcCode> codes = new ArrayList<>();
        final Map<Integer, List<DtcCode>> perModule = new LinkedHashMap<>();
    }

    /**
     * Parse a mode response with AT H1 headers enabled.
     * Response lines: "7E8 06 43 04 01 33 00 00 00 00"
     * First token = CAN ID (11-bit or 29-bit hex).
     *
     * Frames are reassembled per ECU (keyed by CAN ID) before parsing so a
     * DTC that straddles an ISO-TP frame boundary is decoded correctly, and
     * the ISO-TP PCI byte(s) after the CAN ID are stripped so they are never
     * decoded as phantom DTCs (e.g. "06 43 ..." → P0643).
     */
    /**
     * Split a CAN ID off an unspaced response line.
     *
     * <p>With {@code ATH1} but {@code ATS0} the adapter emits one token per
     * frame ({@code 7E80643040133...}) instead of the spaced form the parser
     * documents. The scan now sets {@code ATS1}, but several ELM327 clones
     * ignore it, so this keeps module attribution working regardless.
     *
     * <p>A line that starts with the mode header is a headers-off response and
     * returns null so the caller falls through to the legacy branch. That check
     * is what stops {@code "4304013300"} being misread as CAN ID {@code 0x430}.
     *
     * @return {@code {canIdHex, payloadHex}}, or null when this is not a header line
     */
    static String[] splitUnspacedHeader(String line, String modeHeader) {
        if (line == null) return null;
        String hex = line.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hex.isEmpty()) return null;
        // Headers-off payload — the mode byte leads the line.
        if (modeHeader != null && hex.startsWith(modeHeader)) return null;

        // 29-bit addressing (e.g. 18DAF110) uses an 8-char header.
        int headerLen = hex.startsWith("18") && hex.length() >= 10 ? 8 : 3;
        if (hex.length() < headerLen + 2) return null;

        String header = hex.substring(0, headerLen);
        String payload = hex.substring(headerLen);
        // Payload must be whole bytes; an odd length means this isn't a frame.
        if (payload.length() % 2 != 0) return null;

        try {
            int canId = Integer.parseInt(header, 16);
            // 11-bit IDs are bounded by 0x7FF; reject anything outside so a
            // stray hex run isn't promoted to a module.
            if (headerLen == 3 && (canId <= 0 || canId > 0x7FF)) return null;
        } catch (NumberFormatException notAHeader) {
            return null;
        }
        return new String[] { header, payload };
    }

    static ScanLineResult parseWithModuleHeaders(
            String response, String modeHeader, String protocolLabel) {

        ScanLineResult result = new ScanLineResult();
        if (response == null || response.isEmpty()) return result;

        String[] lines = response.replace("\r", "\n").split("\n");
        Map<Integer, StringBuilder> perEcuHex = new LinkedHashMap<>();
        List<StringBuilder> fallbackSegments = new ArrayList<>();

        for (String line : lines) {
            String clean = line.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "").trim();
            if (clean.isEmpty() || clean.matches("(?i)NODATA|NO DATA")) continue;

            // Strip frame number prefix
            int colonIdx = clean.indexOf(':');
            if (colonIdx >= 0) {
                clean = clean.substring(colonIdx + 1);
            }

            String[] tokens = clean.trim().split("\\s+");
            if (tokens.length < 2) {
                // Spaces-off output ("7E80643040133..."): the CAN ID is not a
                // separate token. Split the header off by width so the scan
                // still works against an adapter that ignores ATS1 — several
                // ELM327 clones do. Falls through to the headers-off branch
                // below when no plausible header is present.
                String[] split = splitUnspacedHeader(clean.trim(), modeHeader);
                // Not a header line — hand the whole blob to the headers-off
                // branch below (an empty first token fails the width check).
                tokens = split != null ? split : new String[] { "", clean };
            }

            // Parse CAN ID — first token
            int ecuId;
            try {
                if (tokens[0].length() < 3) {
                    throw new NumberFormatException("Too short for CAN ID header");
                }
                ecuId = Integer.parseInt(tokens[0], 16);
            } catch (NumberFormatException e) {
                // Not a CAN header line — legacy / headers-off fallback.
                String hex = clean.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
                // Drop ELM327 multi-frame total-length lines (e.g. "010")
                if (hex.length() <= 3 && !hex.startsWith(modeHeader)) continue;
                // 3-byte K-line header (e.g. "48 6B 10 43 ..."): mode byte is
                // followed directly by DTC pairs (no CAN count byte) and the
                // line ends with a checksum byte.
                if (!hex.startsWith(modeHeader)
                        && hex.length() >= 10
                        && hex.startsWith(modeHeader, 6)
                        && hex.substring(0, 2).matches("48|68|8[0-9A-F]|C[0-9A-F]")) {
                    result.codes.addAll(parseDtcPairs(
                        hex.substring(6 + modeHeader.length(), hex.length() - 2)));
                    continue;
                }
                // Reassemble continuation lines onto the previous segment so a
                // DTC straddling a line boundary is not dropped or misaligned.
                if (hex.startsWith(modeHeader) || fallbackSegments.isEmpty()) {
                    fallbackSegments.add(new StringBuilder(hex));
                } else {
                    fallbackSegments.get(fallbackSegments.size() - 1).append(hex);
                }
                continue;
            }

            // Build hex from data bytes (skip CAN ID), then strip the ISO-TP
            // PCI byte(s) before accumulating this ECU's payload.
            StringBuilder hexData = new StringBuilder();
            for (int i = 1; i < tokens.length; i++) {
                hexData.append(tokens[i]);
            }
            String payload = stripIsoTpPci(
                hexData.toString().replaceAll("[^0-9A-Fa-f]", "").toUpperCase(), modeHeader);
            perEcuHex.computeIfAbsent(ecuId, k -> new StringBuilder()).append(payload);
        }

        for (Map.Entry<Integer, StringBuilder> e : perEcuHex.entrySet()) {
            List<DtcCode> ecuCodes = parseDtcPayload(e.getValue().toString(), modeHeader);
            result.codes.addAll(ecuCodes);
            result.perModule.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(ecuCodes);
        }
        for (StringBuilder segment : fallbackSegments) {
            result.codes.addAll(parseDtcPayload(segment.toString(), modeHeader));
        }

        return result;
    }

    /**
     * Strip the ISO-TP PCI byte(s) that follow the CAN ID when AT H1 headers
     * are enabled: single frame "0L", first frame "1L LL", consecutive "2N".
     * Without this, the PCI + mode bytes get decoded as phantom DTCs.
     */
    private static String stripIsoTpPci(String hex, String modeHeader) {
        if (hex.length() < 2 || hex.startsWith(modeHeader)) return hex;
        char pciType = hex.charAt(0);
        if (pciType == '0' && hex.startsWith(modeHeader, 2)) {
            return hex.substring(2);   // single frame
        }
        if (pciType == '1' && hex.length() >= 4 && hex.startsWith(modeHeader, 4)) {
            return hex.substring(4);   // first frame (PCI = 1L LL)
        }
        if (pciType == '2') {
            return hex.substring(2);   // consecutive frame
        }
        return hex;
    }

    /**
     * Parse DTCs from a hex payload string (mode byte + data, no CAN ID header).
     *
     * SAE J1979 specifies a 1-byte count after the mode header (e.g. "43 02 01 71 03 00"
     * = 2 DTCs). However, some ECUs (notably certain Honda/Nissan models) omit the count
     * byte and return just mode + DTC bytes (e.g. "43 01 71 03 00"). The old code always
     * skipped 1 byte after the mode header, silently dropping the first DTC.
     *
     * Heuristic: after the mode header, check if the next byte is a plausible count
     * (count × 4 hex chars == remaining data length). If it doesn't match, treat the
     * byte as the first DTC byte instead.
     */
    private static List<DtcCode> parseDtcPayload(String hex, String modeHeader) {
        List<DtcCode> codes = new ArrayList<>();
        if (hex == null || hex.isEmpty()) return codes;

        String cleanHex = hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (cleanHex.length() < 4) return codes;

        int pos = skipModeHeaderAndCount(cleanHex, modeHeader);
        codes.addAll(parseDtcPairs(cleanHex.substring(pos)));
        return codes;
    }

    /** Parse consecutive 2-byte DTC pairs, skipping "00 00" padding. */
    private static List<DtcCode> parseDtcPairs(String hex) {
        List<DtcCode> codes = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= hex.length()) {
            int byteA = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            int byteB = Integer.parseInt(hex.substring(pos + 2, pos + 4), 16);

            if (byteA != 0x00 || byteB != 0x00) {
                codes.add(DtcCode.fromHexBytes(byteA, byteB));
            }
            pos += 4;
        }
        return codes;
    }

    /**
     * Determine the starting position for DTC parsing, after the mode header and
     * the 1-byte DTC count.
     *
     * SAE J1979 Mode 03/07/0A responses include a 1-byte count after the mode
     * header: e.g. "43 02 01 71 03 00" = mode 43, count 2, DTC1=P0171, DTC2=P0300.
     *
     * The count byte is trusted as long as it's plausible (≤ 0x0F). ECUs rarely
     * report more than 15 DTCs in a single frame. If the count exceeds the
     * available data, it's treated as a malformed response and the count is still
     * skipped (the while-loop in the caller will safely stop at the end).
     *
     * The "no count byte" case is extremely rare in practice. The old code always
     * skipped the count byte, and we keep that behavior — the count byte is
     * standard SAE J1979 and virtually all ECUs include it.
     *
     * Cases:
     * 1. Mode header + count byte → skip header + count.
     * 2. ISO-TP consecutive frame (starts with 2x) → skip 2-char frame-control byte.
     * 3. No recognizable header → start from 0.
     */
    private static int skipModeHeaderAndCount(String cleanHex, String modeHeader) {
        int pos = 0;
        if (cleanHex.startsWith(modeHeader)) {
            pos = modeHeader.length();
            // Always skip the count byte (SAE J1979 standard)
            if (pos + 2 <= cleanHex.length()) {
                pos += 2;
            }
        } else {
            // ISO-TP consecutive frame control byte (21, 22, etc.)
            if (cleanHex.length() >= 2 && cleanHex.substring(0, 2).matches("2[0-9A-F]")) {
                pos = 2;
            }
        }
        return pos;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Backward-Compatible Simple API
    // ═══════════════════════════════════════════════════════════════

    public static List<DtcCode> parseDtcResponse(String response, String modeHeader) {
        List<DtcCode> codes = new ArrayList<>();
        if (response == null || response.isEmpty()) return codes;

        String[] lines = response.replace("\r", "\n").split("\n");
        // Reassemble multi-line responses before parsing: a new segment starts
        // whenever a line begins with the mode header; every other line is an
        // ISO-TP continuation of the previous segment. This keeps a DTC that
        // straddles a line boundary intact instead of dropping/misaligning it.
        List<StringBuilder> segments = new ArrayList<>();
        for (String line : lines) {
            String clean = line.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "").trim();
            if (clean.isEmpty() || clean.matches("(?i)NODATA|NO DATA")) continue;

            int colonIdx = clean.indexOf(':');
            if (colonIdx >= 0) clean = clean.substring(colonIdx + 1);

            String hex = clean.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            // Drop ELM327 multi-frame total-length lines (e.g. "010")
            if (hex.length() <= 3 && !hex.startsWith(modeHeader)) continue;

            if (hex.startsWith(modeHeader) || segments.isEmpty()) {
                segments.add(new StringBuilder(hex));
            } else {
                segments.get(segments.size() - 1).append(hex);
            }
        }

        for (StringBuilder segment : segments) {
            codes.addAll(parseDtcPayload(segment.toString(), modeHeader));
        }
        return codes;
    }

    public static List<DtcCode> readStoredDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) return new ArrayList<>();
        return parseDtcResponse(driver.sendCommandRaw("03"), "43");
    }

    public static List<DtcCode> readPendingDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) return new ArrayList<>();
        return parseDtcResponse(driver.sendCommandRaw("07"), "47");
    }

    public static List<DtcCode> readPermanentDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) return new ArrayList<>();
        return parseDtcResponse(driver.sendCommandRaw("0A"), "4A");
    }

    public static boolean clearDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) return false;
        // Send Mode 04 (Clear DTCs)
        String response = driver.sendCommandRaw("04");
        if (response == null || response.isEmpty()) return false;
        boolean acked = response.replaceAll("[^0-9A-Fa-f]", "").contains("44");
        if (!acked) return false;

        // Wait for ECU to process the clear command
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        // ── Post-clear verification: rescan Mode 03 to confirm all DTCs are gone ──
        // Professional scanners verify the clear actually worked by rescanning.
        String rescan = sendWithRetry(driver, "03", 2, 200);
        if (isValidResponse(rescan)) {
            List<DtcCode> remaining = parseDtcResponse(rescan, "43");
            // Permanent DTCs (Mode 0A) cannot be cleared by Mode 04 — they only
            // clear themselves after the condition no longer exists for N drive
            // cycles. So we only verify that stored DTCs are cleared.
            return remaining.isEmpty();
        }
        // If rescan failed (no response), trust the 44 ack
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private static boolean containsCode(List<DtcCode> list, DtcCode code) {
        for (DtcCode c : list) {
            if (c.getCode() != null && c.getCode().equals(code.getCode())) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Retry & Validation Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Check if an OBD2 response is valid (non-empty, not NO DATA, not ERROR).
     */
    static boolean isValidResponse(String response) {
        if (response == null || response.isEmpty()) return false;
        String clean = response.trim().toUpperCase();
        if (clean.contains("NODATA") || clean.contains("NO DATA")) return false;
        if (clean.contains("ERROR") || clean.contains("UNABLE")) return false;
        if (clean.contains("?")) return false;  // ELM327 unknown command
        // Must contain at least some hex data
        return clean.replaceAll("[^0-9A-Fa-f]", "").length() >= 2;
    }

    /**
     * Send an OBD2 command with retry and exponential backoff.
     * Many ECUs don't respond immediately, especially on the first query after
     * a protocol switch. Professional scanners retry 2-3 times.
     *
     * @param command    OBD2 command (e.g. "03", "07", "0A")
     * @param maxRetries number of attempts (1 = no retry, 3 = recommended)
     * @param baseDelay  base delay between retries in ms (actual = baseDelay * attempt)
     * @return best response, or empty string if all retries failed
     */
    static String sendWithRetry(BaseDriver driver, String command, int maxRetries, int baseDelay) {
        String bestResponse = "";
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                String response = driver.sendCommandRaw(command);
                if (isValidResponse(response)) {
                    return response;
                }
                // Keep last non-null response for debugging
                if (response != null && !response.isEmpty()) {
                    bestResponse = response;
                }
            } catch (Exception e) {
                android.util.Log.w("DtcReader", "Retry " + (attempt + 1) + "/" + maxRetries
                    + " for " + command + " failed: " + e.getMessage());
            }
            if (attempt < maxRetries - 1) {
                try { Thread.sleep(baseDelay * (attempt + 1)); } catch (InterruptedException ignored) {
                    break;  // interrupted = shutdown requested
                }
            }
        }
        return bestResponse;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Per-ECU Physical Addressing Scan
    // ═══════════════════════════════════════════════════════════════

    /**
     * Known ECU transmit/receive header pairs for physical addressing.
     * Used by scanWithPhysicalAddressing() to query each ECU individually
     * instead of relying on functional (broadcast) addressing.
     *
     * Key = TX header (ATSH), Value = RX filter (ATCRA)
     */
    static final java.util.Map<String, String> ECU_TX_RX_PAIRS = new LinkedHashMap<>();
    static {
        // Toyota / generic HS-CAN
        ECU_TX_RX_PAIRS.put("7E0", "7E8");  // ECM
        ECU_TX_RX_PAIRS.put("7E1", "7E9");  // TCM
        ECU_TX_RX_PAIRS.put("7E2", "7EA");  // ABS
        ECU_TX_RX_PAIRS.put("7E3", "7EB");  // SRS
        // Honda
        ECU_TX_RX_PAIRS.put("7C0", "7C8");  // PGM-FI
        // Mitsubishi
        ECU_TX_RX_PAIRS.put("611", "619");  // Engine Diesel
    }

    /**
     * Scan a specific ECU by setting its TX header and RX filter,
     * then querying Mode 03/07/0A. This avoids collisions when multiple
     * ECUs respond simultaneously on functional addressing.
     *
     * @param txHeader 3 or 8 hex chars (e.g. "7E0" or "18DA00F1")
     * @param rxFilter 3 or 8 hex chars (e.g. "7E8")
     * @return DTCs from this specific ECU, or empty if not responding
     */
    public static List<DtcCode> scanEcuDirectly(BaseDriver driver, String txHeader, String rxFilter) {
        List<DtcCode> codes = new ArrayList<>();
        if (driver == null || !driver.isConnected()) return codes;

        // Save current header/filter state
        driver.sendCommandRaw("ATSH" + txHeader);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        driver.sendCommandRaw("ATCRA" + rxFilter);
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        try {
            // Mode 03
            String raw03 = sendWithRetry(driver, "03", 2, 150);
            if (isValidResponse(raw03)) {
                codes.addAll(parseDtcResponse(raw03, "43"));
            }
            // Mode 07
            String raw07 = sendWithRetry(driver, "07", 2, 150);
            if (isValidResponse(raw07)) {
                codes.addAll(parseDtcResponse(raw07, "47"));
            }
            // Mode 0A
            String raw0A = sendWithRetry(driver, "0A", 2, 150);
            if (isValidResponse(raw0A)) {
                codes.addAll(parseDtcResponse(raw0A, "4A"));
            }
        } finally {
            // Clear the receive filter. Bare "ATCRA" removes the filter;
            // "ATCRA000" would instead SET the filter to CAN ID 0x000 and
            // make every subsequent poll return NO DATA.
            driver.sendCommandRaw("ATCRA");
            driver.sendCommandRaw("ATAR");  // restore automatic receive addressing
            // Restore the functional (broadcast) request header for normal
            // polling — "ATSH000" would set a literal 000 header, not a default.
            if (txHeader != null && txHeader.length() > 3) {
                driver.sendCommandRaw("ATSH18DB33F1");  // 29-bit functional address
            } else {
                driver.sendCommandRaw("ATSH7DF");       // 11-bit functional address
            }
        }
        return codes;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Enhanced / Manufacturer-Specific Mode Scanning
    // ═══════════════════════════════════════════════════════════════

    /**
     * Scan enhanced (manufacturer-specific) DTC modes.
     * These are beyond the standard OBD2 Mode 03/07/0A and capture
     * codes that the standard modes don't expose (body, chassis, network).
     *
     * WARNING: Not all ELM327 clones support these. If the adapter returns
     * "?" or "ERROR", the mode is unsupported and results will be empty.
     *
     * Supported enhanced modes by manufacturer:
     *   Toyota: Mode 21 (enhanced DTCs), Mode 22 (extended PID query)
     *   Honda:  Mode 21 (enhanced DTCs)
     *   Nissan: Mode 1A (enhanced DTCs)
     *   Ford:   Mode 27 (manufacturer-specific)
     *
     * @param modeHex 2-hex-digit mode (e.g. "21", "22", "1A", "27")
     * @param responseHeader expected response mode header (e.g. "61", "62", "5A", "67")
     * @return list of DTCs from enhanced mode, or empty if unsupported
     */
    public static List<DtcCode> scanEnhancedMode(BaseDriver driver, String modeHex, String responseHeader) {
        List<DtcCode> codes = new ArrayList<>();
        if (driver == null || !driver.isConnected()) return codes;

        String command = modeHex;
        String response = sendWithRetry(driver, command, 2, 200);
        if (!isValidResponse(response)) return codes;

        // Parse: enhanced mode responses are similar to Mode 03
        // Format: [responseHeader] [count] [DTC1_hi] [DTC1_lo] [DTC2_hi] [DTC2_lo] ...
        String hex = response.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        int idx = hex.indexOf(responseHeader);
        if (idx < 0) return codes;

        // Skip response header + count byte
        int pos = idx + responseHeader.length();
        if (pos + 2 <= hex.length()) pos += 2;  // skip count byte

        while (pos + 4 <= hex.length()) {
            int byteA = Integer.parseInt(hex.substring(pos, pos + 2), 16);
            int byteB = Integer.parseInt(hex.substring(pos + 2, pos + 4), 16);
            if (byteA != 0x00 || byteB != 0x00) {
                codes.add(DtcCode.fromHexBytes(byteA, byteB));
            }
            pos += 4;
        }
        return codes;
    }

    /**
     * Scan all known enhanced modes for a given manufacturer.
     * @param brand Brand enum, or null for all known modes
     * @deprecated Use {@link #scanEnhancedForBrand(BaseDriver, VinBrandDetector.Brand)} instead
     */
    @Deprecated
    public static List<DtcCode> scanEnhancedForBrand(BaseDriver driver, String brand) {
        if (driver == null || !driver.isConnected()) return new ArrayList<>();
        if (brand == null) return scanEnhancedForBrand(driver, (VinBrandDetector.Brand) null);
        // Map common string names to Brand enum
        String b = brand.toLowerCase();
        for (VinBrandDetector.Brand bd : VinBrandDetector.Brand.values()) {
            if (bd.name().equalsIgnoreCase(b)) return scanEnhancedForBrand(driver, bd);
        }
        return scanEnhancedForBrand(driver, (VinBrandDetector.Brand) null);
    }

    /**
     * Scan all known enhanced modes for a given manufacturer.
     *
     * Enhanced modes by manufacturer:
     *   Toyota/Lexus: Mode 21 (61) — enhanced DTCs
     *   Honda:        Mode 21 (61)
     *   Nissan:       Mode 1A (5A) — enhanced DTCs
     *   Ford:         Mode 27 (67) — manufacturer-specific
     *   Mitsubishi:   Mode 21 (61)
     *   Mazda:        Mode 21 (61)
     *   Suzuki:       Mode 21 (61)
     *   Hyundai/Kia:  Mode 21 (61)
     *   Chevrolet:    Mode 2C (6C) — manufacturer DTCs
     *   Volvo:        Mode 21 (61)
     *   BMW:          Mode 21 (61) + Mode 22 (62)
     *   Mercedes:     Mode 22 (62) — UDS
     *   BYD/GWM/NETA/AION/DEEPAL/MG: Mode 22 (62) — ISO 14229 UDS
     *   Tesla:         Mode 22 (62)
     *   Unknown/null: try all unique mode/header pairs
     *
     * @param driver connected OBD2 driver
     * @param brand Brand enum, or null to scan all known enhanced modes
     * @return deduplicated list of DTCs from enhanced modes
     */
    public static List<DtcCode> scanEnhancedForBrand(BaseDriver driver, VinBrandDetector.Brand brand) {
        List<DtcCode> all = new ArrayList<>();
        if (driver == null || !driver.isConnected()) return all;

        // Build the set of (mode, responseHeader) pairs to query for this brand.
        // Using a LinkedHashSet preserves order while preventing duplicate queries
        // (e.g. Toyota and Honda both use Mode 21/61 — should only be sent once).
        java.util.Set<String> modeHeaderPairs = new java.util.LinkedHashSet<>();
        // Each entry is "mode,responseHeader" e.g. "21,61"

        List<String> profileModes = BrandProfile.enhancedModesFor(brand);
        if (profileModes != null) {
            // brand_profiles.json is authoritative when present, so a new brand's
            // enhanced modes ship over the air rather than in a Play release.
            modeHeaderPairs.addAll(profileModes);
        } else if (brand == null) {
            // Unknown VIN — try all unique enhanced mode pairs
            modeHeaderPairs.add("21,61");
            modeHeaderPairs.add("1A,5A");
            modeHeaderPairs.add("27,67");
            modeHeaderPairs.add("2C,6C");
            modeHeaderPairs.add("22,62");
        } else {
            switch (brand) {
                case TOYOTA:
                case LEXUS:
                case HONDA:
                case MITSUBISHI:
                case MAZDA:
                case SUZUKI:
                case HYUNDAI:
                case KIA:
                case VOLVO:
                    modeHeaderPairs.add("21,61");
                    break;
                case NISSAN:
                    modeHeaderPairs.add("1A,5A");
                    break;
                case FORD:
                    modeHeaderPairs.add("27,67");
                    break;
                case CHEVROLET:
                    modeHeaderPairs.add("2C,6C");
                    break;
                case BMW:
                    modeHeaderPairs.add("21,61");
                    modeHeaderPairs.add("22,62");
                    break;
                case MERCEDES:
                case BYD:
                case GWM:
                case NETA:
                case AION:
                case DEEPAL:
                case MG:
                case TESLA:
                    modeHeaderPairs.add("22,62");
                    break;
                case ISUZU:
                    modeHeaderPairs.add("21,61");
                    break;
                default:
                    // Unknown — try all
                    modeHeaderPairs.add("21,61");
                    modeHeaderPairs.add("1A,5A");
                    modeHeaderPairs.add("27,67");
                    modeHeaderPairs.add("2C,6C");
                    modeHeaderPairs.add("22,62");
                    break;
            }
        }

        for (String pair : modeHeaderPairs) {
            String[] parts = pair.split(",");
            all.addAll(scanEnhancedMode(driver, parts[0], parts[1]));
        }

        // Deduplicate
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<DtcCode> deduped = new ArrayList<>();
        for (DtcCode c : all) {
            if (seen.add(c.getCode())) deduped.add(c);
        }
        return deduped;
    }
}
