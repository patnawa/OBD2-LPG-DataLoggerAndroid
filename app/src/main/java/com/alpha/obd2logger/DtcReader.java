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

    /** ECU CAN IDs on HS-CAN (500 kbps) — all brands. */
    static final Map<Integer, String> ECU_NAMES = new LinkedHashMap<>();

    static {
        // ── Toyota ──
        ECU_NAMES.put(0x7E0, "ECM — Engine Control (Toyota)");
        ECU_NAMES.put(0x7E1, "TCM — Transmission (Toyota)");
        ECU_NAMES.put(0x7E2, "ABS/VSC — Brakes (Toyota)");
        ECU_NAMES.put(0x7E3, "SRS — Airbag (Toyota)");
        ECU_NAMES.put(0x7E4, "HV ECU — Hybrid Vehicle (Toyota)");
        ECU_NAMES.put(0x7E5, "EPS — Power Steering (Toyota)");
        ECU_NAMES.put(0x7E6, "A/C — Air Conditioner (Toyota)");
        ECU_NAMES.put(0x7E7, "BCM — Body Control (Toyota)");
        ECU_NAMES.put(0x7E8, "ECM Response");
        ECU_NAMES.put(0x7E9, "TCM Response");
        ECU_NAMES.put(0x7EA, "ABS Response");
        ECU_NAMES.put(0x7EB, "SRS Response");
        ECU_NAMES.put(0x7EC, "HV ECU Response");
        ECU_NAMES.put(0x7ED, "EPS Response");
        ECU_NAMES.put(0x7EE, "A/C Response");
        ECU_NAMES.put(0x7EF, "BCM Response");

        // ── Honda ──
        ECU_NAMES.put(0x7C0, "PGM-FI — Fuel Injection (Honda)");
        ECU_NAMES.put(0x7C1, "AT — Auto Transmission (Honda)");
        ECU_NAMES.put(0x7C2, "VSA — Stability Assist (Honda)");
        ECU_NAMES.put(0x7C3, "SRS — Airbag (Honda)");
        ECU_NAMES.put(0x7C8, "PGM-FI Response");

        // ── Mazda ── (shares Ford architecture)
        ECU_NAMES.put(0x7E0, "PCM — Powertrain (Mazda)");
        ECU_NAMES.put(0x7E1, "TCM — Transmission (Mazda)");
        ECU_NAMES.put(0x7E2, "ABS/DSC (Mazda)");
        ECU_NAMES.put(0x7E3, "EPS — Steering (Mazda)");

        // ── Isuzu D-Max (29-bit CAN) ──
        ECU_NAMES.put(0x18DA00F1, "ECM — Engine (Isuzu D-Max)");
        ECU_NAMES.put(0x18DA00F2, "TCM — Transmission (Isuzu)");
        ECU_NAMES.put(0x18DA00F3, "ABS — Brakes (Isuzu)");

        // ── Nissan ──
        ECU_NAMES.put(0x7E0, "ECM — Engine (Nissan)");
        ECU_NAMES.put(0x7E1, "TCM — CVT/Auto (Nissan)");
        ECU_NAMES.put(0x7E2, "ABS/VDC (Nissan)");
        ECU_NAMES.put(0x7E3, "SRS — Airbag (Nissan)");

        // ── Mitsubishi (unique CAN IDs; shared 7E0-7EF covered by Toyota) ──
        ECU_NAMES.put(0x762, "AWC/S-AWC — All Wheel Control (Mitsubishi)");
        ECU_NAMES.put(0x763, "ASC — Active Stability (Mitsubishi)");
        ECU_NAMES.put(0x764, "ETACS — Body Control/BCM (Mitsubishi)");
        ECU_NAMES.put(0x765, "EPS — Steering (Mitsubishi)");
        ECU_NAMES.put(0x76A, "KOS/OSS — Keyless Operation (Mitsubishi)");
        ECU_NAMES.put(0x76B, "TPMS — Tire Pressure (Mitsubishi)");
        ECU_NAMES.put(0x72E, "4WD — Transfer Case (Mitsubishi Triton/Pajero)");
        ECU_NAMES.put(0x72F, "AFS — Adaptive Front Light (Mitsubishi)");
        ECU_NAMES.put(0x744, "MMCS — Multimedia/Navi (Mitsubishi)");
        ECU_NAMES.put(0x611, "Engine-ECU Diesel (Mitsubishi 4D56/4N15)");
        ECU_NAMES.put(0x619, "Engine-ECU Response (Mitsubishi Diesel)");
        // MUT protocol (ISO 14230 KWP) — older Mitsubishi (pre-2008 Lancer, Pajero Sport, Strada)
        // Uses functional addressing 0x33, but ELM327 handles this transparently via ATSP auto.

        // ── Ford MS-CAN ──
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

    /** Look up a human-readable module name from a CAN ID. */
    private static String moduleNameForCanId(int ecuId) {
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

            public Builder(int ecuId, String protocolLabel) {
                this.ecuId = ecuId;
                this.canId = ecuId > 0xFFFF
                    ? String.format("%08X", ecuId)
                    : String.format("%03X", ecuId);
                this.protocolLabel = protocolLabel;
                this.moduleName = moduleNameForCanId(ecuId);
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

        return scanBuses(driver, buses);
    }

    /**
     * Deep DTC scan: try ALL protocol buses sequentially.
     * Covers every protocol for Thai-market vehicles.
     * Use for manual "Deep Scan" button — slower but exhaustive.
     *
     * @return comprehensive scan result with per-protocol status
     */
    public static DtcScanResult readAllDtcsDeep(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return DtcScanResult.empty();
        }

        // Build full bus list: primary auto-detect + all secondary buses
        List<ProtocolBus> buses = new ArrayList<>();
        buses.add(FAST_BUSES.get(0)); // auto-detect first
        buses.addAll(ALL_BUSES);       // then all secondary buses

        return scanBuses(driver, buses);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Multi-Bus Scan Engine
    // ═══════════════════════════════════════════════════════════════

    /**
     * Scan DTCs across multiple protocol buses.
     * For each bus: switch protocol, scan Modes 03/07/0A with headers,
     * collect module info, restore auto protocol.
     */
    private static DtcScanResult scanBuses(BaseDriver driver, List<ProtocolBus> buses) {
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
                result = scanSingleBus(driver, bus);
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

        // Always restore to auto-detect
        driver.sendCommandRaw("ATSP0");
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}

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

    private static BusScanResult scanSingleBus(BaseDriver driver, ProtocolBus bus) {
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

        // Enable headers to see CAN IDs
        driver.sendCommandRaw("ATH1");
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        try {
            // Mode 03 — Stored DTCs
            String raw03 = driver.sendCommandRaw("03");
            if (raw03 != null && !raw03.isEmpty()) {
                ScanLineResult slr = parseWithModuleHeaders(raw03, "43", bus.label);
                stored.addAll(slr.codes);
                for (Map.Entry<Integer, List<DtcCode>> e : slr.perModule.entrySet()) {
                    ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(e.getKey(),
                        k -> new ModuleInfo.Builder(k, bus.label));
                    mb.storedOk = true;
                    mb.storedDtcCount = e.getValue().size();
                }
                if (!slr.codes.isEmpty()) anyResponse = true;
            }

            // Mode 07 — Pending DTCs
            String raw07 = driver.sendCommandRaw("07");
            if (raw07 != null && !raw07.isEmpty()) {
                ScanLineResult slr = parseWithModuleHeaders(raw07, "47", bus.label);
                pending.addAll(slr.codes);
                for (Map.Entry<Integer, List<DtcCode>> e : slr.perModule.entrySet()) {
                    ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(e.getKey(),
                        k -> new ModuleInfo.Builder(k, bus.label));
                    mb.pendingOk = true;
                    mb.pendingDtcCount = e.getValue().size();
                }
            }

            // Mode 0A — Permanent DTCs
            String raw0A = driver.sendCommandRaw("0A");
            if (raw0A != null && !raw0A.isEmpty()) {
                ScanLineResult slr = parseWithModuleHeaders(raw0A, "4A", bus.label);
                permanent.addAll(slr.codes);
                for (Map.Entry<Integer, List<DtcCode>> e : slr.perModule.entrySet()) {
                    ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(e.getKey(),
                        k -> new ModuleInfo.Builder(k, bus.label));
                    mb.permanentOk = true;
                    mb.permanentDtcCount = e.getValue().size();
                }
            }

            // A bus "responded" even if we just got "NO DATA" back (means it connected)
            // Check if any response came back at all (raw03 non-null means bus exists)
            if (raw03 != null) anyResponse = true;

        } finally {
            driver.sendCommandRaw("ATH0");
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
     */
    private static List<DtcCode> parseDtcPayload(String hex, String modeHeader) {
        List<DtcCode> codes = new ArrayList<>();
        if (hex == null || hex.isEmpty()) return codes;

        String cleanHex = hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (cleanHex.length() < 4) return codes;

        int pos = 0;
        if (cleanHex.startsWith(modeHeader)) {
            pos = modeHeader.length();
            if (pos + 2 <= cleanHex.length()) pos += 2;
        } else {
            if (cleanHex.length() >= 2 && cleanHex.substring(0, 2).matches("2[0-9A-F]")) {
                pos = 2;
            }
        }

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

    // ═══════════════════════════════════════════════════════════════
    //  Backward-Compatible Simple API
    // ═══════════════════════════════════════════════════════════════

    public static List<DtcCode> parseDtcResponse(String response, String modeHeader) {
        List<DtcCode> codes = new ArrayList<>();
        if (response == null || response.isEmpty()) return codes;

        String[] lines = response.replace("\r", "\n").split("\n");
        for (String line : lines) {
            String clean = line.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "").trim();
            if (clean.isEmpty() || clean.equals("NODATA")) continue;

            int colonIdx = clean.indexOf(':');
            if (colonIdx >= 0) clean = clean.substring(colonIdx + 1);

            String hex = clean.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            if (hex.length() < 4) continue;

            int pos = 0;
            if (hex.startsWith(modeHeader)) {
                pos = modeHeader.length();
                if (pos + 2 <= hex.length()) pos += 2;
            } else {
                if (hex.length() >= 2 && hex.substring(0, 2).matches("2[0-9A-F]")) {
                    pos = 2;
                }
            }

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
        String response = driver.sendCommandRaw("04");
        if (response == null || response.isEmpty()) return false;
        return response.replaceAll("[^0-9A-Fa-f]", "").contains("44");
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
}
