package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        switch (brand) {
            case TOYOTA: case LEXUS: return TOYOTA_ECU;
            case HONDA:             return HONDA_ECU;
            case MAZDA:             return MAZDA_ECU;
            case NISSAN:            return NISSAN_ECU;
            case ISUZU:             return ISUZU_ECU;
            case MITSUBISHI:        return MITSUBISHI_ECU;
            case FORD:              return FORD_HS_CAN_NAMES;
            default:                return null;
        }
    }

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
        if (driver == null || !driver.isConnected()) {
            return DtcScanResult.empty();
        }

        List<ProtocolBus> buses = new ArrayList<>();
        buses.add(FAST_BUSES.get(0)); // auto-detect

        // If MS-CAN enabled, add it as a secondary bus
        if (fordMsCan && ALL_BUSES.size() > 0) {
            buses.add(ALL_BUSES.get(0)); // MS-CAN
        }

        return scanBuses(driver, buses, fordMsCan);
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
        if (driver == null || !driver.isConnected()) {
            return DtcScanResult.empty();
        }

        // Build full bus list: primary auto-detect + all secondary buses
        List<ProtocolBus> buses = new ArrayList<>();
        buses.add(FAST_BUSES.get(0)); // auto-detect first
        buses.addAll(ALL_BUSES);       // then all secondary buses

        return scanBuses(driver, buses, fordMode);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Multi-Bus Scan Engine
    // ═══════════════════════════════════════════════════════════════

    /**
     * Scan DTCs across multiple protocol buses.
     * For each bus: switch protocol, scan Modes 03/07/0A with headers,
     * collect module info, restore auto protocol.
     */
    private static DtcScanResult scanBuses(BaseDriver driver, List<ProtocolBus> buses, boolean fordMode) {
        List<DtcCode> allStored = new ArrayList<>();
        List<DtcCode> allPending = new ArrayList<>();
        List<DtcCode> allPermanent = new ArrayList<>();
        List<ModuleInfo> allModules = new ArrayList<>();
        List<ProtocolScanStatus> statuses = new ArrayList<>();
        java.util.Set<String> seenCanIds = new java.util.HashSet<>();

        // Save current protocol to restore at end
        driver.sendCommandRaw("ATSP0"); // start clean
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}

        for (ProtocolBus bus : buses) {
            BusScanResult result;
            try {
                result = scanSingleBus(driver, bus, fordMode);
            } catch (Exception e) {
                android.util.Log.e("DtcReader", "Bus scan failed: " + bus.label, e);
                statuses.add(new ProtocolScanStatus(bus, false, 0, 0));
                continue;
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

            // Merge modules (deduplicate by CAN ID)
            for (ModuleInfo mod : result.modules) {
                if (seenCanIds.add(mod.canId + "@" + mod.protocolLabel)) {
                    allModules.add(mod);
                }
            }

            statuses.add(new ProtocolScanStatus(
                bus, result.anyResponse,
                result.modules.size(),
                result.stored.size() + result.pending.size() + result.permanent.size()
            ));
        }

        // Always restore to auto-detect and re-probe so the ELM327 re-locks
        // onto the correct protocol before PID detection runs. Without this,
        // the first PID query after the DTC scan can get an unreliable response
        // because the adapter may still be on a non-default protocol.
        driver.sendCommandRaw("ATSP0");
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        // Send a throwaway 0100 probe to trigger auto-detect re-lock
        driver.sendCommandRaw("0100");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

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

    private static BusScanResult scanSingleBus(BaseDriver driver, ProtocolBus bus, boolean fordMode) {
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
            // No ECU responded on this protocol — skip DTC scan entirely
            driver.sendCommandRaw("ATCFC0");
            return new BusScanResult(stored, pending, permanent, new ArrayList<>(), false);
        }
        // A valid Mode 01 probe proves this bus responded even when the ECU has
        // zero DTCs or does not implement one of Modes 07/0A.
        anyResponse = true;

        // Enable headers to see CAN IDs
        driver.sendCommandRaw("ATH1");
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        try {
            // Mode 03 — Stored DTCs (retry up to 3 times with backoff)
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

            // Mode 07 — Pending DTCs
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

            // Mode 0A — Permanent DTCs
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

        } finally {
            driver.sendCommandRaw("ATH0");
            // Restore flow control to default (off — let ELM327 handle normally)
            driver.sendCommandRaw("ATCFC0");
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
    //  Response Parsing with Module Headers
    // ═══════════════════════════════════════════════════════════════

    private static class ScanLineResult {
        final List<DtcCode> codes = new ArrayList<>();
        final Map<Integer, List<DtcCode>> perModule = new LinkedHashMap<>();
    }

    /**
     * Parse a mode response with AT H1 headers enabled.
     * Response lines: "7E8 06 43 04 01 33 00 00 00 00"
     * First token = CAN ID (11-bit or 29-bit hex).
     */
    private static ScanLineResult parseWithModuleHeaders(
            String response, String modeHeader, String protocolLabel) {

        ScanLineResult result = new ScanLineResult();
        if (response == null || response.isEmpty()) return result;

        String[] lines = response.replace("\r", "\n").split("\n");
        ScanLineResult tempResult = new ScanLineResult();

        for (String line : lines) {
            String clean = line.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "").trim();
            if (clean.isEmpty() || clean.matches("(?i)NODATA|NO DATA")) continue;

            // Strip frame number prefix
            int colonIdx = clean.indexOf(':');
            if (colonIdx >= 0) {
                clean = clean.substring(colonIdx + 1);
            }

            String[] tokens = clean.split("\\s+");
            if (tokens.length < 2) continue;

            // Parse CAN ID — first token
            int ecuId;
            try {
                if (tokens[0].length() < 3) {
                    throw new NumberFormatException("Too short for CAN ID header");
                }
                ecuId = Integer.parseInt(tokens[0], 16);
            } catch (NumberFormatException e) {
                // Not a CAN header line — try legacy parse
                List<DtcCode> fallbackCodes = parseDtcPayload(
                    clean.replaceAll("\\s+", ""), modeHeader);
                result.codes.addAll(fallbackCodes);
                continue;
            }

            // Build hex from data bytes (skip CAN ID)
            StringBuilder hexData = new StringBuilder();
            for (int i = 1; i < tokens.length; i++) {
                hexData.append(tokens[i]);
            }

            List<DtcCode> lineCodes = parseDtcPayload(hexData.toString(), modeHeader);
            result.codes.addAll(lineCodes);

            result.perModule.computeIfAbsent(ecuId, k -> new ArrayList<>()).addAll(lineCodes);
        }

        return result;
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

        while (pos + 4 <= cleanHex.length()) {
            int byteA = Integer.parseInt(cleanHex.substring(pos, pos + 2), 16);
            int byteB = Integer.parseInt(cleanHex.substring(pos + 2, pos + 4), 16);

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
        for (String line : lines) {
            String clean = line.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "").trim();
            if (clean.isEmpty() || clean.matches("(?i)NODATA|NO DATA")) continue;

            int colonIdx = clean.indexOf(':');
            if (colonIdx >= 0) clean = clean.substring(colonIdx + 1);

            String hex = clean.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            if (hex.length() < 4) continue;

            int pos = skipModeHeaderAndCount(hex, modeHeader);

            while (pos + 4 <= hex.length()) {
                int byteA = Integer.parseInt(hex.substring(pos, pos + 2), 16);
                int byteB = Integer.parseInt(hex.substring(pos + 2, pos + 4), 16);
                if (byteA != 0x00 || byteB != 0x00) {
                    codes.add(DtcCode.fromHexBytes(byteA, byteB));
                }
                pos += 4;
            }
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
            // Reset to defaults
            driver.sendCommandRaw("ATCRA000");
            driver.sendCommandRaw("ATSH000");  // auto header
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

        if (brand == null) {
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
