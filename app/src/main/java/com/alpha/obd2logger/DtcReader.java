package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;

/**
 * Reads and clears Diagnostic Trouble Codes via OBD2 Mode 03 (read stored)
 * and Mode 04 (clear all).
 *
 * v2.0: Ford MS-CAN support — scans both HS-CAN and MS-CAN buses when
 * enabled, with per-module detection and scan status tracking.
 *
 * Mode 03 response format:
 *   43 NN [DTC1_H DTC1_L] [DTC2_H DTC2_L] ...
 * where NN = number of DTCs, followed by 2-byte DTC codes.
 *
 * Mode 07 (pending DTCs) uses the same format but response starts with 47.
 */
public final class DtcReader {

    private DtcReader() {}

    // ── Module Detection ──────────────────────────────────────────

    /**
     * Information about a detected ECU module during DTC scan.
     */
    public static class ModuleInfo {
        public final String canId;        // e.g. "7E8", "7E9", "7EC"
        public final int ecuId;           // numeric CAN ID (e.g. 0x7E8)
        public final String moduleName;   // e.g. "Engine Control Module"
        public final boolean moduleScanned;    // responded to at least one mode
        public final boolean storedOk;    // Mode 03 responded
        public final boolean pendingOk;   // Mode 07 responded
        public final boolean permanentOk; // Mode 0A responded
        public final int storedDtcCount;
        public final int pendingDtcCount;
        public final int permanentDtcCount;

        ModuleInfo(String canId, int ecuId, String moduleName,
                   boolean moduleScanned, boolean storedOk, boolean pendingOk,
                   boolean permanentOk, int storedDtcCount, int pendingDtcCount,
                   int permanentDtcCount) {
            this.canId = canId;
            this.ecuId = ecuId;
            this.moduleName = moduleName;
            this.moduleScanned = moduleScanned;
            this.storedOk = storedOk;
            this.pendingOk = pendingOk;
            this.permanentOk = permanentOk;
            this.storedDtcCount = storedDtcCount;
            this.pendingDtcCount = pendingDtcCount;
            this.permanentDtcCount = permanentDtcCount;
        }

        /** Total active+warning DTCs across all modes */
        public int getTotalDtcCount() {
            return storedDtcCount + pendingDtcCount + permanentDtcCount;
        }

        @Override
        public String toString() {
            return canId + " (" + moduleName + ") stored=" + storedDtcCount
                + " pending=" + pendingDtcCount + " perm=" + permanentDtcCount;
        }

        // ── Builder (set fields before calling build()) ─────

        public static class Builder {
            final int ecuId;
            final String canId;
            final String busLabel;
            final String moduleName;
            boolean storedOk, pendingOk, permanentOk;
            int storedDtcCount, pendingDtcCount, permanentDtcCount;

            public Builder(int ecuId, String busLabel, boolean isMsCan) {
                this.ecuId = ecuId;
                this.canId = String.format("%03X", ecuId);
                this.busLabel = busLabel;
                this.moduleName = moduleNameForCanId(ecuId, isMsCan) + " [" + busLabel + "]";
            }

            public ModuleInfo build() {
                boolean scanned = storedOk || pendingOk || permanentOk;
                return new ModuleInfo(canId, ecuId, moduleName,
                    scanned, storedOk, pendingOk, permanentOk,
                    storedDtcCount, pendingDtcCount, permanentDtcCount);
            }
        }
    }

    /**
     * Complete DTC scan result including codes and module info.
     */
    public static class DtcScanResult {
        public final List<DtcCode> storedDtcs;
        public final List<DtcCode> pendingDtcs;
        public final List<DtcCode> permanentDtcs;
        public final List<ModuleInfo> modules;
        /** Whether MS-CAN bus was included in this scan */
        public final boolean msCanIncluded;
        /** How many modules responded on MS-CAN */
        public final int msCanModuleCount;

        DtcScanResult(List<DtcCode> stored, List<DtcCode> pending,
                      List<DtcCode> permanent, List<ModuleInfo> modules,
                      boolean msCanIncluded, int msCanModuleCount) {
            this.storedDtcs = stored;
            this.pendingDtcs = pending;
            this.permanentDtcs = permanent;
            this.modules = modules;
            this.msCanIncluded = msCanIncluded;
            this.msCanModuleCount = msCanModuleCount;
        }

        /** Empty result (no scan possible) */
        public static DtcScanResult empty() {
            return new DtcScanResult(new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), false, 0);
        }
    }

    // ── CAN ID → Module Name Mapping ─────────────────────────────

    /** Known ECU CAN IDs (11-bit) on HS-CAN (500 kbps). */
    static final Map<Integer, String> HS_CAN_ECU_NAMES = new LinkedHashMap<>();
    /** Known ECU CAN IDs on MS-CAN (125 kbps, Ford). */
    static final Map<Integer, String> MS_CAN_ECU_NAMES = new LinkedHashMap<>();

    static {
        // HS-CAN Powertrain ECUs
        HS_CAN_ECU_NAMES.put(0x7E0, "ECM — Engine Control Module");
        HS_CAN_ECU_NAMES.put(0x7E1, "TCM — Transmission Control Module");
        HS_CAN_ECU_NAMES.put(0x7E2, "TCCM — Transfer Case Control Module");
        HS_CAN_ECU_NAMES.put(0x7E3, "ABS — Anti-lock Brake System");
        HS_CAN_ECU_NAMES.put(0x7E4, "PSCM — Power Steering Control");
        HS_CAN_ECU_NAMES.put(0x7E5, "IPC — Instrument Panel Cluster");
        HS_CAN_ECU_NAMES.put(0x7E6, "RCM — Restraints Control");
        HS_CAN_ECU_NAMES.put(0x7E7, "APIM — Accessory Protocol Interface");
        HS_CAN_ECU_NAMES.put(0x7E8, "ECM (Response)");
        HS_CAN_ECU_NAMES.put(0x7E9, "TCM (Response)");
        HS_CAN_ECU_NAMES.put(0x7EA, "TCCM (Response)");
        HS_CAN_ECU_NAMES.put(0x7EB, "ABS (Response)");
        HS_CAN_ECU_NAMES.put(0x7EC, "PSCM (Response)");
        HS_CAN_ECU_NAMES.put(0x7ED, "IPC (Response)");
        HS_CAN_ECU_NAMES.put(0x7EE, "RCM (Response)");
        HS_CAN_ECU_NAMES.put(0x7EF, "APIM (Response)");
        HS_CAN_ECU_NAMES.put(0x7DF, "OBD2 Broadcast");

        // MS-CAN Body/Chassis ECUs (Ford-specific)
        MS_CAN_ECU_NAMES.put(0x726, "GEM — Generic Electronic Module");
        MS_CAN_ECU_NAMES.put(0x727, "SJB — Smart Junction Box");
        MS_CAN_ECU_NAMES.put(0x728, "BCM — Body Control Module");
        MS_CAN_ECU_NAMES.put(0x733, "IC — Instrument Cluster");
        MS_CAN_ECU_NAMES.put(0x736, "DATC — Dual Auto Temp Control");
        MS_CAN_ECU_NAMES.put(0x737, "PAM — Parking Aid Module");
        MS_CAN_ECU_NAMES.put(0x73A, "OCS — Occupant Classification");
        MS_CAN_ECU_NAMES.put(0x73B, "RCM — Restraints (MS-CAN)");
        MS_CAN_ECU_NAMES.put(0x73C, "DSM — Driver Seat Module");
        MS_CAN_ECU_NAMES.put(0x73D, "DDM — Driver Door Module");
        MS_CAN_ECU_NAMES.put(0x73E, "PDM — Passenger Door Module");
        MS_CAN_ECU_NAMES.put(0x745, "ACM — Audio Control Module");
        MS_CAN_ECU_NAMES.put(0x750, "FCIM — Front Controls Interface");
        MS_CAN_ECU_NAMES.put(0x760, "ABS/TC — ABS on MS-CAN");
    }

    /** Look up a human-readable module name from a CAN ID (hex). */
    private static String moduleNameForCanId(int ecuId, boolean isMsCan) {
        Map<Integer, String> primary = isMsCan ? MS_CAN_ECU_NAMES : HS_CAN_ECU_NAMES;
        Map<Integer, String> fallback = isMsCan ? HS_CAN_ECU_NAMES : MS_CAN_ECU_NAMES;

        String name = primary.get(ecuId);
        if (name != null) return name;
        name = fallback.get(ecuId);
        if (name != null) return name;
        return String.format("Module 0x%X", ecuId);
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Full DTC scan with module detection.
     * Scans stored (03), pending (07), permanent (0A).
     * When fordMsCan is enabled, also scans the MS-CAN bus.
     *
     * @param driver connected ELM327 driver
     * @param fordMsCan enable Ford MS-CAN secondary-bus scan
     * @return complete scan result with codes + module info
     */
    public static DtcScanResult readAllDtcs(BaseDriver driver, boolean fordMsCan) {
        if (driver == null || !driver.isConnected()) {
            return DtcScanResult.empty();
        }

        List<DtcCode> allStored = new ArrayList<>();
        List<DtcCode> allPending = new ArrayList<>();
        List<DtcCode> allPermanent = new ArrayList<>();
        List<ModuleInfo> allModules = new ArrayList<>();
        boolean msCanIncluded = false;
        int msCanModules = 0;

        // ── Scan HS-CAN (primary bus, auto protocol) ──
        BusScanResult hs = scanSingleBus(driver, "HS-CAN", false);
        allStored.addAll(hs.stored);
        allPending.addAll(hs.pending);
        allPermanent.addAll(hs.permanent);
        allModules.addAll(hs.modules);

        // ── Scan MS-CAN (Ford secondary bus) ──
        if (fordMsCan) {
            msCanIncluded = true;
            String savedProtocol = saveProtocol(driver);
            try {
                switchToMsCan(driver);
                BusScanResult ms = scanSingleBus(driver, "MS-CAN", true);
                allStored.addAll(ms.stored);
                allPending.addAll(ms.pending);
                allPermanent.addAll(ms.permanent);
                allModules.addAll(ms.modules);
                msCanModules = ms.modules.size();
            } catch (Exception e) {
                android.util.Log.e("DtcReader", "MS-CAN scan failed", e);
            } finally {
                restoreProtocol(driver, savedProtocol);
            }
        }

        return new DtcScanResult(allStored, allPending, allPermanent,
            allModules, msCanIncluded, msCanModules);
    }

    // ── Single-bus Scan ─────────────────────────────────────────

    /** Result for one bus scan. */
    private static class BusScanResult {
        final List<DtcCode> stored;
        final List<DtcCode> pending;
        final List<DtcCode> permanent;
        final List<ModuleInfo> modules;

        BusScanResult(List<DtcCode> stored, List<DtcCode> pending,
                      List<DtcCode> permanent, List<ModuleInfo> modules) {
            this.stored = stored;
            this.pending = pending;
            this.permanent = permanent;
            this.modules = modules;
        }
    }

    /**
     * Scan one CAN bus: temporarily enable headers, run Mode 03/07/0A,
     * parse per-module responses, then restore headers off.
     */
    private static BusScanResult scanSingleBus(BaseDriver driver, String busLabel, boolean isMsCan) {
        List<DtcCode> stored = new ArrayList<>();
        List<DtcCode> pending = new ArrayList<>();
        List<DtcCode> permanent = new ArrayList<>();
        Map<Integer, ModuleInfo.Builder> moduleBuilders = new LinkedHashMap<>();

        // Step 1: Enable headers temporarily so we see CAN IDs in responses
        driver.sendCommandRaw("ATH1");

        try {
            // Step 2: Read stored DTCs (Mode 03)
            String raw03 = driver.sendCommandRaw("03");
            ScanLineResult slr03 = parseWithModuleHeaders(raw03, "43", moduleBuilders, busLabel, isMsCan,
                new java.util.HashSet<>()); // store mode label for builders
            stored.addAll(slr03.codes);
            for (java.util.Map.Entry<Integer, List<DtcCode>> e : slr03.perModule.entrySet()) {
                ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(e.getKey(),
                    k -> new ModuleInfo.Builder(k, busLabel, isMsCan));
                mb.storedOk = true;
                mb.storedDtcCount = e.getValue().size();
            }

            // Step 3: Read pending DTCs (Mode 07)
            String raw07 = driver.sendCommandRaw("07");
            ScanLineResult slr07 = parseWithModuleHeaders(raw07, "47", moduleBuilders, busLabel, isMsCan, null);
            pending.addAll(slr07.codes);
            for (java.util.Map.Entry<Integer, List<DtcCode>> e : slr07.perModule.entrySet()) {
                ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(e.getKey(),
                    k -> new ModuleInfo.Builder(k, busLabel, isMsCan));
                mb.pendingOk = true;
                mb.pendingDtcCount = e.getValue().size();
            }

            // Step 4: Read permanent DTCs (Mode 0A)
            String raw0A = driver.sendCommandRaw("0A");
            ScanLineResult slr0A = parseWithModuleHeaders(raw0A, "4A", moduleBuilders, busLabel, isMsCan, null);
            permanent.addAll(slr0A.codes);
            for (java.util.Map.Entry<Integer, List<DtcCode>> e : slr0A.perModule.entrySet()) {
                ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(e.getKey(),
                    k -> new ModuleInfo.Builder(k, busLabel, isMsCan));
                mb.permanentOk = true;
                mb.permanentDtcCount = e.getValue().size();
            }

        } finally {
            // Step 5: Restore headers off
            driver.sendCommandRaw("ATH0");
        }

        // Build ModuleInfo list
        List<ModuleInfo> modules = new ArrayList<>();
        for (ModuleInfo.Builder mb : moduleBuilders.values()) {
            modules.add(mb.build());
        }

        // If no modules detected but we got DTCs, add a virtual module
        if (modules.isEmpty() && (!stored.isEmpty() || !pending.isEmpty() || !permanent.isEmpty())) {
            ModuleInfo fallback = new ModuleInfo(
                "???", 0, busLabel + " (ECU unknown)",
                true, !stored.isEmpty(), !pending.isEmpty(), !permanent.isEmpty(),
                stored.size(), pending.size(), permanent.size()
            );
            modules.add(fallback);
        }

        return new BusScanResult(stored, pending, permanent, modules);
    }

    // ── Response Parsing with Module Headers ─────────────────────

    /**
     * Intermediate result from parsing one mode response.
     */
    private static class ScanLineResult {
        final List<DtcCode> codes = new ArrayList<>();
        /** DTCs grouped by ECU CAN ID that reported them. */
        final Map<Integer, List<DtcCode>> perModule = new LinkedHashMap<>();
    }

    /**
     * Parse a mode response with AT H1 headers enabled.
     * Response lines look like:
     *   7E8 06 43 04 01 33 00 00 00 00
     *   7E9 06 47 00 (no DTCs)
     *
     * First hex token = CAN ID of responding ECU.
     */
    private static ScanLineResult parseWithModuleHeaders(
            String response, String modeHeader,
            Map<Integer, ModuleInfo.Builder> moduleBuilders,
            String busLabel, boolean isMsCan,
            java.util.Set<Integer> knownIds) {

        ScanLineResult result = new ScanLineResult();

        if (response == null || response.isEmpty()) return result;

        String[] lines = response.replace("\r", "\n").split("\n");
        for (String line : lines) {
            String clean = line.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "").trim();
            if (clean.isEmpty() || clean.matches("(?i)NODATA|NO DATA")) continue;

            // Strip frame number prefix (e.g. "0:", "1:")
            int colonIdx = clean.indexOf(':');
            if (colonIdx >= 0) {
                clean = clean.substring(colonIdx + 1);
            }

            // Tokenize: split on whitespace, each token is hex
            String[] tokens = clean.split("\\s+");
            if (tokens.length < 2) continue;

            // First token should be CAN ID (3 hex chars like "7E8")
            int ecuId;
            try {
                ecuId = Integer.parseInt(tokens[0], 16);
            } catch (NumberFormatException e) {
                // No valid CAN ID — fall back to old headerless parsing
                continue;
            }

            // Build the hex string from ONLY the data bytes (skip CAN ID,
            // skip expected frame count, keep mode header + data)
            StringBuilder hexData = new StringBuilder();
            for (int i = 1; i < tokens.length; i++) {
                hexData.append(tokens[i]);
            }
            String hexLine = hexData.toString();

            // Parse DTCs from this module's data payload
            List<DtcCode> lineCodes = parseDtcPayload(hexLine, modeHeader);
            result.codes.addAll(lineCodes);

            // Track per-module
            ModuleInfo.Builder mb = moduleBuilders.computeIfAbsent(ecuId,
                k -> new ModuleInfo.Builder(k, busLabel, isMsCan));
            result.perModule.computeIfAbsent(ecuId, k -> new ArrayList<>()).addAll(lineCodes);
        }

        return result;
    }

    /**
     * Parse DTCs from a hex payload string (no header, just mode byte + data).
     */
    private static List<DtcCode> parseDtcPayload(String hex, String modeHeader) {
        List<DtcCode> codes = new ArrayList<>();
        if (hex == null || hex.isEmpty()) return codes;

        String cleanHex = hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (cleanHex.length() < 4) return codes;

        int pos = 0;
        if (cleanHex.startsWith(modeHeader)) {
            pos = modeHeader.length();
            // Skip count byte on first frame
            if (pos + 2 <= cleanHex.length()) {
                pos += 2;
            }
        } else {
            // Consecutive ISO-TP frame — strip PCI header (21, 22, etc.)
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

    // ── MS-CAN Protocol Switching ────────────────────────────────

    /**
     * Save current protocol setting so we can restore after MS-CAN scan.
     * Returns the current ATDP response or "0" (auto) as default.
     */
    private static String saveProtocol(BaseDriver driver) {
        String resp = driver.sendCommandRaw("ATDP");
        if (resp != null && !resp.isEmpty()) {
            // Extract protocol number from response like "AUTO, ISO 15765-4 (CAN 11/500)"
            String clean = resp.trim();
            // Fallback: just use saved ATSP value
            return "0"; // restore to auto
        }
        return "0";
    }

    /**
     * Switch ELM327 to Ford MS-CAN protocol.
     * MS-CAN = CAN 11-bit 125kbps on a separate physical bus.
     *
     * Approach:
     *   1. AT SP B — User1 CAN 11-bit 500kbps (closest built-in)
     *      Some vLinker/OBDLink adapters auto-detect MS-CAN on this setting.
     *   2. If adapter supports custom baud via AT PB:
     *      AT PB 40 01 — sets Protocol B parameters for MS-CAN (125 kbps)
     *      (only works on OBDLink/vLinker with extended ST command set)
     */
    private static void switchToMsCan(BaseDriver driver) {
        // Try User1 CAN first (most adapters map this or can be configured)
        driver.sendCommandRaw("ATSPB");
        // Wait for protocol switch
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        // Try to set MS-CAN custom baud (vLinker/OBDLink extended commands)
        // AT PB <mode> <param>: set protocol B parameters
        // Mode 0x40 = CAN 11-bit, next byte = baud prescaler
        // This is silently ignored if adapter doesn't support it.
        driver.sendCommandRaw("ATPB4001");
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
    }

    /**
     * Restore protocol after MS-CAN scan. Auto-detect is fine for normal use.
     */
    private static void restoreProtocol(BaseDriver driver, String savedProtocol) {
        // Restore to auto protocol detect — the ELM327 will re-detect HS-CAN
        driver.sendCommandRaw("ATSP0");
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
    }

    // ── Backward-Compatible Simple API ──────────────────────────

    public static List<DtcCode> parseDtcResponse(String response, String modeHeader) {
        List<DtcCode> codes = new ArrayList<>();
        if (response == null || response.isEmpty()) {
            return codes;
        }

        String[] lines = response.replace("\r", "\n").split("\n");
        for (String line : lines) {
            String clean = line.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "").trim();
            if (clean.isEmpty() || clean.equals("NODATA")) continue;

            int colonIdx = clean.indexOf(':');
            if (colonIdx >= 0) {
                clean = clean.substring(colonIdx + 1);
            }

            String hex = clean.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            if (hex.length() < 4) continue;

            int pos = 0;
            if (hex.startsWith(modeHeader)) {
                pos = modeHeader.length();
                if (pos + 2 <= hex.length()) {
                    pos += 2;
                }
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

    /**
     * Read stored DTCs (Mode 03) — simple, no module tracking.
     */
    public static List<DtcCode> readStoredDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return new ArrayList<>();
        }
        String response = driver.sendCommandRaw("03");
        return parseDtcResponse(response, "43");
    }

    /**
     * Read pending DTCs (Mode 07) — simple, no module tracking.
     */
    public static List<DtcCode> readPendingDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return new ArrayList<>();
        }
        String response = driver.sendCommandRaw("07");
        return parseDtcResponse(response, "47");
    }

    /**
     * Read permanent DTCs (Mode 0A) — simple, no module tracking.
     */
    public static List<DtcCode> readPermanentDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return new ArrayList<>();
        }
        String response = driver.sendCommandRaw("0A");
        return parseDtcResponse(response, "4A");
    }

    /**
     * Clear all DTCs and reset MIL (Mode 04).
     * @return true if command was sent successfully
     */
    public static boolean clearDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return false;
        }
        String response = driver.sendCommandRaw("04");
        if (response == null || response.isEmpty()) {
            return false;
        }
        String clean = response.replaceAll("[^0-9A-Fa-f]", "");
        return clean.contains("44");
    }
