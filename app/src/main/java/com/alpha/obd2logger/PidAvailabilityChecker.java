package com.alpha.obd2logger;

import android.util.Log;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Queries the vehicle's ECU to determine which OBD2 Mode 01 PIDs are supported,
 * using the standard SAE J1979 PID availability bitmaps (PIDs 0x00, 0x20, 0x40).
 *
 * Each bitmap PID returns 4 bytes (32 bits). Bit N being set means PID
 * (bitmapBase + N + 1) is supported.  For example, PID 0x00 bit 0 = PID 0x01,
 * bit 1 = PID 0x02, etc.
 *
 * This is the <em>primary</em> detection mechanism. If the vehicle doesn't
 * support the bitmap queries (rare, but possible on some clones), the caller
 * falls back to {@link BrandYearProfile} or the full catalogue.
 */
public final class PidAvailabilityChecker {

    private static final String TAG = "PidAvailability";

    private PidAvailabilityChecker() {
    }

    /**
     * Query the vehicle for supported Mode 01 PIDs.
     *
     * @param driver a connected {@code ElmDriver} (or any BaseDriver; simulation
     *               returns a synthetic profile)
     * @return a set of supported PID hex codes (e.g. "0C", "0D", "05"), or
     *         {@code null} if detection failed and the caller should fall back.
     */
    public static List<String> querySupportedPids(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return null;
        }

        // Simulation driver: return a realistic profile
        if (driver instanceof SimulationDriver) {
            return getSimulationProfile();
        }

        if (!(driver instanceof ElmDriver)) {
            return null;
        }

        ElmDriver elm = (ElmDriver) driver;
        List<String> supported = new ArrayList<>();
        boolean anyResponse = false;

        // Query PID 0x00, 0x20, 0x40, 0x60 — each covers a 32-PID range
        String[] bitmapPids = {"0100", "0120", "0140", "0160"};
        int[] bitmapBases = {0x00, 0x20, 0x40, 0x60};

        for (int i = 0; i < bitmapPids.length; i++) {
            String response = elm.sendCommandRaw(bitmapPids[i]);
            if (response == null || response.isEmpty()) {
                continue;
            }

            // Expected response header: "41" + pidHex (e.g. "4100", "4120", "4140")
            String expectedHeader = "41" + bitmapPids[i].substring(2);
            String hexData = extractHexData(response, expectedHeader);

            if (hexData == null || hexData.length() < 8) {
                // This bitmap range is not supported — skip it
                continue;
            }

            anyResponse = true;

            // Parse 4 bytes (8 hex chars) as a 32-bit bitmap
            try {
                int byteA = parseHexByte(hexData, 0);
                int byteB = parseHexByte(hexData, 2);
                int byteC = parseHexByte(hexData, 4);
                int byteD = parseHexByte(hexData, 6);

                int[] bytes = {byteA, byteB, byteC, byteD};
                int base = bitmapBases[i];

                for (int byteIdx = 0; byteIdx < 4; byteIdx++) {
                    for (int bit = 0; bit < 8; bit++) {
                        if ((bytes[byteIdx] & (1 << (7 - bit))) != 0) {
                            // Bit N set → PID (base + byteIdx*8 + bit + 1) is supported
                            int pidNum = base + byteIdx * 8 + bit + 1;
                            supported.add(String.format("%02X", pidNum));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse bitmap for " + bitmapPids[i] + ": " + e.getMessage());
            }
        }

        // PID 0x00 itself is always "supported" (it's the query we just sent)
        if (anyResponse && !supported.contains("00")) {
            supported.add(0, "00");
        }

        if (!anyResponse) {
            Log.w(TAG, "No bitmap responses received — detection failed");
            return null;
        }

        Log.i(TAG, "Detected " + supported.size() + " supported PIDs");
        return supported;
    }

    /**
     * Filter the full PID catalogue down to only supported PIDs.
     * Pseudo-PIDs (with "_B" suffix) are kept if their parent PID is supported.
     *
     * @param supportedHex list of supported PID hex codes (e.g. "0C", "0D")
     * @param catalogue the full PID catalogue to filter
     * @return filtered list, or the original catalogue if filtering fails
     */
    public static List<PIDDefinition> filterCatalogue(List<String> supportedHex, List<PIDDefinition> catalogue) {
        if (supportedHex == null || supportedHex.isEmpty()) {
            return catalogue;
        }

        // Build a set for O(1) lookup
        java.util.Set<String> supportedSet = new java.util.HashSet<>(supportedHex);

        // Force-include core PIDs critical for basic engine diagnostics and LPG tuning
        supportedSet.add("03"); // Fuel System Status
        supportedSet.add("04"); // Engine Load
        supportedSet.add("05"); // Coolant Temp
        supportedSet.add("06"); // Short Term Fuel Trim
        supportedSet.add("07"); // Long Term Fuel Trim
        supportedSet.add("0B"); // MAP (falls back to Load if vehicle lacks MAP sensor)
        supportedSet.add("0C"); // Engine RPM

        List<PIDDefinition> filtered = new ArrayList<>();
        for (PIDDefinition pid : catalogue) {
            String hex = pid.getPidHex();
            // Strip "_B" suffix for pseudo-PIDs — check parent
            String baseHex = hex.contains("_") ? hex.substring(0, hex.indexOf('_')) : hex;
            if (supportedSet.contains(baseHex)) {
                filtered.add(pid);
            }
        }
        return filtered;
    }

    /**
     * Extract and merge hex data from ALL ECU responses for a given PID query.
     *
     * Multi-ECU vehicles (e.g., Toyota Sienta 2014) return multiple responses
     * to PID availability queries — one per ECU (engine, transmission, etc.).
     * Each ECU reports a different bitmask of supported PIDs. We must OR all
     * bitmasks together to get the full picture.
     *
     * Example raw response from a Toyota with CAN protocol:
     *   7E8 06 41 00 BE 3F A8 13   ← Engine ECU
     *   7E9 06 41 00 80 00 00 00   ← Transmission ECU
     *
     * We parse BOTH and OR: BE3FA813 | 80000000 = BE3FA813 (engine wins).
     * Without this fix, if 7E9 appears first, we'd only get 80000000 and
     * miss STFT, LTFT, O2, MAP, and many other PIDs.
     */
    private static String extractHexData(String response, String expectedHeader) {
        if (response == null || response.isEmpty()) return null;
        
        String header = expectedHeader.toUpperCase();
        
        // Split into lines BEFORE stripping non-hex characters,
        // so each ECU response stays on its own line
        String[] rawLines = response.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        
        long mergedBits = 0;
        boolean found = false;
        
        for (String rawLine : rawLines) {
            // Clean this single line
            String cleaned = rawLine.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "");
            cleaned = cleaned.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            
            if (cleaned.isEmpty()) continue;
            
            int idx = cleaned.indexOf(header);
            if (idx < 0) continue;
            
            String payload = cleaned.substring(idx + header.length());
            if (payload.length() < 8) continue;
            
            try {
                long bits = Long.parseLong(payload.substring(0, 8), 16);
                mergedBits |= bits;
                found = true;
                Log.d(TAG, "ECU response for " + header + ": " + payload.substring(0, 8) 
                        + " (merged=" + String.format("%08X", mergedBits) + ")");
            } catch (NumberFormatException e) {
                Log.w(TAG, "Bad hex in line: " + cleaned);
            }
        }
        
        if (!found) return null;
        return String.format("%08X", mergedBits);
    }

    private static int parseHexByte(String hex, int offset) {
        return Integer.parseInt(hex.substring(offset, offset + 2), 16);
    }

    /**
     * Return a realistic PID profile for simulation mode.
     * Simulates a typical 2010+ vehicle with standard PIDs.
     */
    private static List<String> getSimulationProfile() {
        // Common Mode 01 PIDs supported by most post-2008 vehicles
        String[] pids = {
                "00", // PID availability [0x01-0x20]
                "04", // Engine Load
                "05", // Coolant Temp
                "06", // STFT B1
                "07", // LTFT B1
                "08", // STFT B2
                "09", // LTFT B2
                "0A", // Fuel Pressure
                "0B", // Intake Manifold Pressure
                "0C", // Engine RPM
                "0D", // Vehicle Speed
                "0E", // Timing Advance
                "0F", // Intake Air Temp
                "10", // MAF Air Flow
                "11", // Throttle Position
                "14", "15", "16", "17", "18", "19", "1A", "1B", // O2 Sensors
                "1C", // OBD Standards
                "1F", // Run Time
                "21", // Distance with MIL
                "23", // Fuel Rail Pressure
                "2F", // Fuel Level
                "31", // Distance Since DTC Cleared
                "33", // Barometric Pressure
                "42", // Control Module Voltage
                "43", // Absolute Load
                "44", // Wideband Lambda
                "46", // Ambient Air Temp
                "51", // Fuel Type
                "52", // Ethanol Fuel
                "20", // PID availability [0x21-0x40]
                "40", // PID availability [0x41-0x60]
                "5D", // Fuel Inject Timing
                "5E", // Engine Fuel Rate
        };
        List<String> result = new ArrayList<>();
        for (String p : pids) result.add(p);
        return result;
    }
}