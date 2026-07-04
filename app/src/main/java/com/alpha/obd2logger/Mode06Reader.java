package com.alpha.obd2logger;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads OBD2 Mode 06 (On-Board Monitor Test Results).
 *
 * Mode 06 response format per result (9 bytes after header):
 *   [OBDMID] [TID] [UASID] [TestValueHi] [TestValueLo] [MinLimitHi] [MinLimitLo] [MaxLimitHi] [MaxLimitLo]
 *
 * Multi-frame CAN responses pack multiple results; ISO/KWP may return one
 * result per response line.
 *
 * Strategy:
 *   1. Send "0600" to get list of supported MIDs (optional — not all ECUs support this)
 *   2. Send "06" and parse all results that come back
 *   3. Filter out zeros/padding
 */
public final class Mode06Reader {

    private static final String TAG = "Mode06Reader";

    private Mode06Reader() {}

    /**
     * Read all Mode 06 test results from the vehicle.
     * This sends "06" and parses all returned test results.
     */
    public static List<Mode06Result> readAll(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return new ArrayList<>();
        }

        String response = driver.sendCommandRaw("06");
        return parseResponse(response);
    }

    /**
     * Read Mode 06 results for a specific OBDMID.
     * @param mid 0x00 to list supported, or 0x01-0xFF for specific monitor
     */
    public static List<Mode06Result> readForMid(BaseDriver driver, int mid) {
        if (driver == null || !driver.isConnected()) {
            return new ArrayList<>();
        }

        String cmd = String.format(java.util.Locale.US, "06%02X", mid);
        String response = driver.sendCommandRaw(cmd);
        return parseResponse(response);
    }

    /**
     * Read Mode 06 results for MIDs relevant to common DTCs.
     * Focus on monitors that are most useful for diagnosis:
     *   - Catalysts (0x21, 0x22)
     *   - O2 Sensors (0x01-0x08)
     *   - O2 Heaters (0x09-0x10)
     *   - EGR (0x31, 0x32)
     *   - EVAP (0x3E, 0x3F)
     *   - Misfire (0x50-0x5C)
     */
    public static List<Mode06Result> readDiagnostic(BaseDriver driver) {
        List<Mode06Result> allResults = new ArrayList<>();
        if (driver == null || !driver.isConnected()) {
            return allResults;
        }

        // First try: just send "06" — most CAN vehicles return all results at once
        String response = driver.sendCommandRaw("06");
        List<Mode06Result> results = parseResponse(response);
        if (!results.isEmpty()) {
            return results;
        }

        // Fallback: try "0600" for supported MIDs list
        String midListResponse = driver.sendCommandRaw("0600");
        List<Integer> supportedMids = parseSupportedMids(midListResponse);

        if (!supportedMids.isEmpty()) {
            // Query each supported MID individually
            for (int mid : supportedMids) {
                List<Mode06Result> midResults = readForMid(driver, mid);
                allResults.addAll(midResults);
            }
        } else {
            // Last resort: probe common MIDs individually
            int[] commonMids = {
                0x01, 0x02, 0x03, 0x05, 0x06,  // O2 sensors
                0x09, 0x0A, 0x0B, 0x0D, 0x0E,   // O2 heaters
                0x21, 0x22,                        // Catalysts
                0x31, 0x32,                        // EGR
                0x3E, 0x3F,                        // EVAP
                0x50, 0x51, 0x52, 0x53, 0x54      // Misfire
            };
            for (int mid : commonMids) {
                List<Mode06Result> midResults = readForMid(driver, mid);
                allResults.addAll(midResults);
            }
        }

        return allResults;
    }

    /**
     * Parse the response to "06" or "06XX" command.
     *
     * ELM327 formats vary:
     *   CAN:  "46 05 0A 07 00 7D 00 00 00 FA\r\n" (header + 9 data bytes)
     *   ISO:  "46 05\r\n0A 07 00 7D 00 00 00 FA\r\n" (split across lines)
     *   Multi-frame CAN:
     *     "0: 46 01 0A 07 00 5A 00\r\n"
     *     "1: 00 00 FA 05 0A 07 01\r\n"
     *     "2: 2C 00 00 00 FA\r\n"
     */
    public static List<Mode06Result> parseResponse(String response) {
        List<Mode06Result> results = new ArrayList<>();
        if (response == null || response.isEmpty()) {
            return results;
        }

        // Normalize: remove SEARCHING, BUSINIT, etc., collapse whitespace
        String normalized = response.replace("\r", "\n");
        normalized = normalized.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "");
        normalized = normalized.trim();

        // Extract all hex digits from the response
        // Handle multi-frame: strip line prefixes like "0:", "1:", "2:"
        StringBuilder hexBuf = new StringBuilder();
        for (String line : normalized.split("\\n")) {
            String clean = line.trim();
            if (clean.isEmpty()) continue;

            // Strip NODATA / NO DATA
            if (clean.matches("(?i)NODATA|NO DATA")) continue;

            // Strip frame number prefix: "0:", "1:", "2:" etc.
            int colonIdx = clean.indexOf(':');
            if (colonIdx >= 0) {
                clean = clean.substring(colonIdx + 1);
            }

            // Remove everything except hex digits
            String hex = clean.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            hexBuf.append(hex);
        }

        String allHex = hexBuf.toString();
        if (allHex.isEmpty()) {
            return results;
        }

        // Find "46" header (Mode 06 positive response)
        // The response starts with "46" followed by data
        int headerIdx = allHex.indexOf("46");
        if (headerIdx < 0) {
            return results;
        }

        // Skip the "46" header
        String data = allHex.substring(headerIdx + 2);
        if (data.isEmpty()) {
            return results;
        }

        // Parse 9-byte records: MID(1-2) + TID(1) + UASID(1) + value(2) + min(2) + max(2)
        // Note: MID can be 1 byte (0x00-0xFF) for standard Mode 06
        // We need at least 8 bytes after the header for one result
        // Total hex chars needed: 8 bytes = 16 hex chars minimum

        int pos = 0;
        while (pos + 16 <= data.length()) {
            try {
                int mid = Integer.parseInt(data.substring(pos, pos + 2), 16);
                pos += 2;

                int tid = Integer.parseInt(data.substring(pos, pos + 2), 16);
                pos += 2;

                int uasId = Integer.parseInt(data.substring(pos, pos + 2), 16);
                pos += 2;

                int rawValue = Integer.parseInt(data.substring(pos, pos + 4), 16);
                pos += 4;

                int rawMin = Integer.parseInt(data.substring(pos, pos + 4), 16);
                pos += 4;

                int rawMax = Integer.parseInt(data.substring(pos, pos + 4), 16);
                pos += 4;

                // Skip all-zero records (padding/padding bytes)
                if (mid == 0 && tid == 0 && uasId == 0 &&
                    rawValue == 0 && rawMin == 0 && rawMax == 0) {
                    continue;
                }

                // Skip if mid is 0 (not a valid monitor ID for test data)
                if (mid == 0) {
                    continue;
                }

                // Scale the raw values
                double scaledValue = UasDecoder.scale(uasId, rawValue);
                double scaledMin = UasDecoder.scale(uasId, rawMin);
                double scaledMax = UasDecoder.scale(uasId, rawMax);
                String unit = UasDecoder.unitFor(uasId);

                // Determine pass/fail: value must be between min and max
                boolean passed = (scaledValue >= scaledMin) && (scaledValue <= scaledMax);
                // Handle inverted ranges (some ECUs swap min/max)
                if (!passed) {
                    passed = (scaledValue >= scaledMax) && (scaledValue <= scaledMin);
                    if (passed) {
                        // Swap min/max for display
                        double tmp = scaledMin;
                        scaledMin = scaledMax;
                        scaledMax = tmp;
                    }
                }

                Mode06Result result = new Mode06Result(
                    mid, tid, uasId,
                    rawValue, rawMin, rawMax,
                    scaledValue, scaledMin, scaledMax,
                    unit, passed
                );
                results.add(result);

            } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                Log.w(TAG, "Parse error at pos " + pos + " in Mode 06 response", e);
                break;
            }
        }

        return results;
    }

    /**
     * Parse supported MIDs from "0600" response.
     * Response format: 4600 [MID1] [TID1] [UAS1] [val] [min] [max]
     * The MID value in the first result is the highest supported MID.
     * We need to query each MID from 01 to that max.
     *
     * Some ECUs just return the supported MIDs directly:
     *   46 01 0A 07 00 5A 00 00 00 FA  (supports MID 01)
     *   46 05 0A 07 00 7D 00 00 00 FA  (supports MID 05)
     *
     * In practice, "0600" often returns test results directly, not a list.
     * We just parse whatever comes back as normal Mode 06 data.
     */
    private static List<Integer> parseSupportedMids(String response) {
        List<Integer> mids = new ArrayList<>();
        List<Mode06Result> results = parseResponse(response);
        for (Mode06Result r : results) {
            if (!mids.contains(r.getObdMid())) {
                mids.add(r.getObdMid());
            }
        }
        return mids;
    }
}
