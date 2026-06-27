package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and clears Diagnostic Trouble Codes via OBD2 Mode 03 (read stored)
 * and Mode 04 (clear all).
 *
 * Mode 03 response format:
 *   43 NN [DTC1_H DTC1_L] [DTC2_H DTC2_L] ...
 * where NN = number of DTCs, followed by 2-byte DTC codes.
 *
 * Mode 07 (pending DTCs) uses the same format but response starts with 47.
 */
public final class DtcReader {

    private DtcReader() {
    }

    public static List<DtcCode> parseDtcResponse(String response, String modeHeader) {
        List<DtcCode> codes = new ArrayList<>();
        if (response == null || response.isEmpty()) {
            return codes;
        }

        String[] lines = response.replace("\r", "\n").split("\n");
        for (String line : lines) {
            String clean = line.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "").trim();
            if (clean.isEmpty() || clean.equals("NODATA")) continue;
            
            // Remove CAN frame numbers (e.g. "0:", "1:", or just "0" "1" if colon was omitted)
            // But usually ELM327 multi-frame looks like:
            // 014
            // 0: 43 04 01 ...
            // We just strip any non-hex first.
            // Wait, if it has "0:" we can just take everything after ":"
            int colonIdx = clean.indexOf(':');
            if (colonIdx >= 0) {
                clean = clean.substring(colonIdx + 1);
            }
            
            String hex = clean.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            if (hex.length() < 4) continue;

            int pos = 0;
            // Check if this line starts with the header
            if (hex.startsWith(modeHeader)) {
                pos = modeHeader.length();
                // Skip the count byte (1 byte = 2 hex chars) on the FIRST frame
                if (pos + 2 <= hex.length()) {
                    pos += 2;
                }
            } else {
                // Consecutive CAN frame, might start with PCI byte like "21", "22"
                // But ELM327 with ATSP0 auto formatting might strip PCI bytes if headers are off.
                // We just parse all 2-byte pairs in the line that aren't 0000.
                // It's safer to just parse 4-hex-char chunks.
                // If the line didn't have a header, it's a continuation line.
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
     * Read stored DTCs (Mode 03).
     */
    public static List<DtcCode> readStoredDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return new ArrayList<>();
        }
        if (driver instanceof ElmDriver) {
            String response = ((ElmDriver) driver).sendCommandRaw("03");
            return parseDtcResponse(response, "43");
        }
        return new ArrayList<>();
    }

    /**
     * Read pending DTCs (Mode 07).
     */
    public static List<DtcCode> readPendingDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return new ArrayList<>();
        }
        if (driver instanceof ElmDriver) {
            String response = ((ElmDriver) driver).sendCommandRaw("07");
            return parseDtcResponse(response, "47");
        }
        return new ArrayList<>();
    }

    /**
     * Clear all DTCs and reset MIL (Mode 04).
     * @return true if command was sent successfully
     */
    public static boolean clearDtcs(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return false;
        }
        if (driver instanceof ElmDriver) {
            String response = ((ElmDriver) driver).sendCommandRaw("04");
            return response != null && !response.isEmpty();
        }
        return false;
    }

    private static String normalizeHex(String response) {
        String normalized = response.replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "");
        normalized = normalized.replaceAll("[^0-9A-Fa-f]", "");
        return normalized.toUpperCase();
    }
}
