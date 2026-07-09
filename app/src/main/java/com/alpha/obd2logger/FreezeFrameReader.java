package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads Freeze Frame (Mode 02) data for key engine parameters.
 *
 * Supports:
 *   - Frame 00 (generic/default snapshot)
 *   - Per-DTC freeze frames (frame number = DTC occurrence)
 *
 * OBD2 Mode 02 format:
 *   Request: 02 [PID] [FrameNumber]
 *   Response: 42 [PID] [Data...]
 *
 * When a DTC triggers, the ECU saves a snapshot of sensor values.
 * Each stored DTC has its own freeze frame (up to frame numbers 00-07).
 * Frame 00 is always the "most recent" freeze frame.
 *
 * To find which frame belongs to which DTC, we first read Mode 03 DTCs,
 * then request Mode 02 PID 02 (which DTC triggered each frame), then
 * read each frame's PIDs.
 */
public final class FreezeFrameReader {

    // Core freeze frame PIDs — always try these
    private static final String[] CORE_PIDS = {
        "0C", // Engine RPM
        "0D", // Vehicle Speed
        "05", // Engine Coolant Temp
        "04", // Calculated Engine Load
        "06", // Short Term Fuel Trim Bank 1
        "07", // Long Term Fuel Trim Bank 1
        "0B", // Intake Manifold Absolute Pressure
        "0F"  // Intake Air Temp
    };

    // Extended freeze frame PIDs — additional for professional diagnostics
    private static final String[] EXTENDED_PIDS = {
        "0E", // Timing Advance
        "11", // Throttle Position
        "14", // O2 Sensor B1S1 Voltage & STFT
        "15", // O2 Sensor B1S2 Voltage & STFT
        "21", // Distance Traveled with MIL on
        "2F", // Fuel Tank Level
        "31", // Distance since codes cleared
        "4D", // Time run with MIL on
        "4E", // Time since codes cleared
    };

    private FreezeFrameReader() {}

    /**
     * Read key sensor values at the time the fault occurred (Mode 02, frame 00).
     * This is the default — reads the most recent freeze frame.
     */
    public static FreezeFrameData readFreezeFrame(BaseDriver driver) {
        return readFreezeFrameForFrame(driver, 0);
    }

    /**
     * Read freeze frame data for a specific frame number (0-7).
     * Frame 0 is the most recent; each stored DTC has its own frame.
     *
     * v2: Queries Mode 02 PID 00 first to find supported freeze frame PIDs,
     * then reads only those PIDs. Falls back to hardcoded list if query fails.
     */
    public static FreezeFrameData readFreezeFrameForFrame(BaseDriver driver, int frameNumber) {
        if (driver == null || !driver.isConnected()) {
            return null;
        }

        Map<String, Double> values = new HashMap<>();

        // ── Query supported PIDs in freeze frame (Mode 02 PID 00) ──
        // This tells us which PIDs the ECU actually has freeze frame data for,
        // instead of blindly querying hardcoded PIDs that may be unsupported.
        Set<String> supportedPids = querySupportedFreezeFramePids(driver, frameNumber);

        if (supportedPids != null && !supportedPids.isEmpty()) {
            // Read only supported PIDs
            for (String pidHex : supportedPids) {
                Double value = readFreezeFramePid(driver, pidHex, frameNumber);
                if (value != null) {
                    values.put(pidHex, value);
                }
            }
        } else {
            // Fallback: read hardcoded core PIDs
            for (String pidHex : CORE_PIDS) {
                Double value = readFreezeFramePid(driver, pidHex, frameNumber);
                if (value != null) {
                    values.put(pidHex, value);
                }
            }
        }

        // Always try extended PIDs — don't fail if unsupported
        for (String pidHex : EXTENDED_PIDS) {
            if (!values.containsKey(pidHex)) {
                Double value = readFreezeFramePid(driver, pidHex, frameNumber);
                if (value != null) {
                    values.put(pidHex, value);
                }
            }
        }

        return new FreezeFrameData(values, System.currentTimeMillis());
    }

    /**
     * Read freeze frame for ALL stored DTCs by mapping frame numbers to DTC codes.
     *
     * How it works:
     *   1. Read stored DTCs (Mode 03)
     *   2. For each DTC, try frame 0, 1, 2... until we get no more data
     *   3. In practice, most ECUs store freeze frames for frames 0..N-1
     *      where N = number of stored DTCs
     *   4. Mode 02 PID 02 returns the DTC that triggered each frame (if supported)
     *
     * @return list of FreezeFrameEntry (DTC code + freeze frame data), one per DTC
     */
    public static List<FreezeFrameEntry> readAllFreezeFrames(BaseDriver driver) {
        List<FreezeFrameEntry> entries = new ArrayList<>();
        if (driver == null || !driver.isConnected()) {
            return entries;
        }

        // Read stored DTCs
        List<DtcCode> stored = DtcReader.readStoredDtcs(driver);
        if (stored.isEmpty()) {
            return entries;
        }

        // Try to map frames to DTCs using Mode 02 PID 02 (DTC that triggered frame)
        // This PID returns: 42 02 [DTC_hi] [DTC_lo]
        Map<Integer, String> frameToDtc = new HashMap<>();
        for (int frame = 0; frame < stored.size() && frame < 8; frame++) {
            String response = driver.sendCommandRaw("0202" + String.format(java.util.Locale.US, "%02X", frame));
            if (response != null) {
                String hex = response.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
                int idx = hex.indexOf("4202");
                if (idx >= 0 && idx + 8 <= hex.length()) {
                    try {
                        String tcHex = hex.substring(idx + 4, idx + 8);
                        int byteA = Integer.parseInt(tcHex.substring(0, 2), 16);
                        int byteB = Integer.parseInt(tcHex.substring(2, 4), 16);
                        if (byteA != 0 || byteB != 0) {
                            DtcCode code = DtcCode.fromHexBytes(byteA, byteB);
                            frameToDtc.put(frame, code.getCode());
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // Now read freeze frames for each stored DTC
        for (int i = 0; i < stored.size() && i < 8; i++) {
            String dtcCode = stored.get(i).getCode();

            // Try to find the matching frame number
            int frameNum = -1;
            for (Map.Entry<Integer, String> e : frameToDtc.entrySet()) {
                if (e.getValue().equals(dtcCode)) {
                    frameNum = e.getKey();
                    break;
                }
            }

            // If not found via PID 02, try sequential frame numbers
            if (frameNum < 0) {
                frameNum = i;
            }

            FreezeFrameData ffData = readFreezeFrameForFrame(driver, frameNum);
            if (ffData != null && !ffData.getValues().isEmpty()) {
                entries.add(new FreezeFrameEntry(dtcCode, frameNum, ffData));
            }
        }

        return entries;
    }

    /**
     * Query which PIDs are supported in freeze frame (Mode 02 PID 00).
     * Returns a bitmap where each bit = one PID is supported.
     *
     * Response format: 42 00 [4 bytes bitmap]
     *   Bit 0 = PID 01 supported, bit 1 = PID 02, ..., bit 31 = PID 20
     *
     * @return set of supported PID hex strings (e.g. {"04","05","0B","0C","0D"}),
     *         or null if query failed
     */
    private static Set<String> querySupportedFreezeFramePids(BaseDriver driver, int frameNumber) {
        try {
            String cmd = "0200" + String.format(java.util.Locale.US, "%02X", frameNumber);
            String response = driver.sendCommandRaw(cmd);
            if (response == null || response.isEmpty()) return null;

            String hex = response.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            int idx = hex.indexOf("4200");
            if (idx < 0) return null;

            String bitmapHex = hex.substring(idx + 4);
            if (bitmapHex.length() < 8) return null;

            Set<String> pids = new TreeSet<>();
            // Parse 4 bytes (32 bits) of PID support bitmap
            for (int byteIdx = 0; byteIdx < 4 && byteIdx * 2 + 2 <= bitmapHex.length(); byteIdx++) {
                int bits = Integer.parseInt(bitmapHex.substring(byteIdx * 2, byteIdx * 2 + 2), 16);
                for (int bit = 0; bit < 8; bit++) {
                    if ((bits & (1 << (7 - bit))) != 0) {
                        int pidNum = byteIdx * 8 + bit + 1;
                        pids.add(String.format("%02X", pidNum));
                    }
                }
            }
            return pids;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Read a single PID from a specific freeze frame.
     * @return parsed value or null if not available
     */
    private static Double readFreezeFramePid(BaseDriver driver, String pidHex, int frameNumber) {
        PIDDefinition pidDef = PIDCatalogue.getAll().stream()
                .filter(p -> p.getPidHex().equalsIgnoreCase(pidHex))
                .findFirst()
                .orElse(null);

        if (pidDef == null) return null;

        // Command: 02 + pidHex + frameNumber (2 hex digits)
        String cmd = "02" + pidHex + String.format(java.util.Locale.US, "%02X", frameNumber);
        String response = driver.sendCommandRaw(cmd);
        if (response == null || response.isEmpty()) return null;

        // Expected response header: 42 + pidHex
        return PIDParser.extractAndParse(pidDef, response, "42" + pidHex);
    }

    /**
     * A freeze frame entry associated with a specific DTC.
     */
    public static final class FreezeFrameEntry {
        private final String dtcCode;
        private final int frameNumber;
        private final FreezeFrameData data;

        public FreezeFrameEntry(String dtcCode, int frameNumber, FreezeFrameData data) {
            this.dtcCode = dtcCode;
            this.frameNumber = frameNumber;
            this.data = data;
        }

        public String getDtcCode() { return dtcCode; }
        public int getFrameNumber() { return frameNumber; }
        public FreezeFrameData getData() { return data; }
    }
}
