package com.alpha.obd2logger;

/**
 * Reads the Vehicle Identification Number (VIN) via OBD2 Mode 09 PID 02.
 *
 * VIN response is a multi-frame ISO-15765-2 (ISO-TP) message:
 *   First frame: 49 02 01 [17 bytes of VIN chars]
 *   Consecutive frames: 49 02 02 [16 bytes] ...
 *
 * The VIN is 17 ASCII characters. We parse out the printable ASCII chars.
 */
public final class VinReader {

    private VinReader() {
    }

    /**
     * Read VIN from the vehicle via Mode 09 PID 02.
     * @return the 17-character VIN string, or null if unavailable
     */
    public static String readVin(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return null;
        }

        // Send 0902 with headers off — ELM327 handles ISO-TP reassembly
        String response = driver.sendCommandRaw("0902");
        if (response == null || response.isEmpty()) {
            return null;
        }

        return parseVinResponse(response);
    }

    /**
     * Parse the VIN from a Mode 09 PID 02 response.
     * The response may contain multiple frames. We extract all printable
     * ASCII characters after the header bytes.
     */
    static String parseVinResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        String[] lines = response.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(cleanVinLine(line));
        }

        String hex = sb.toString().toUpperCase();
        if (hex.length() < 10) {
            return null;
        }

        // Find "4902" header
        int idx = hex.indexOf("4902");
        if (idx < 0) {
            // Try finding "490201" pattern
            idx = hex.indexOf("490201");
            if (idx >= 0) idx += 6;
        } else {
            idx += 4;
            // Skip frame index byte (01, 02, 03...)
            if (idx + 2 <= hex.length()) {
                idx += 2;
            }
        }

        if (idx < 0 || idx >= hex.length()) {
            return null;
        }

        // Extract ASCII characters from remaining hex bytes
        StringBuilder vin = new StringBuilder();
        while (idx + 2 <= hex.length()) {
            String byteHex = hex.substring(idx, idx + 2);
            try {
                int charVal = Integer.parseInt(byteHex, 16);
                if (charVal >= 0x20 && charVal <= 0x7E) {
                    vin.append((char) charVal);
                }
            } catch (NumberFormatException ignored) {
                // Skip non-hex
            }
            idx += 2;
        }

        String result = vin.toString().trim();
        // VIN is exactly 17 characters
        if (result.length() >= 17) {
            return result.substring(0, 17);
        }
        if (result.length() >= 5) {
            return result;
        }
        return null;
    }

    private static String cleanVinLine(String line) {
        String trimmed = line.trim().toUpperCase();
        if (trimmed.isEmpty()) return "";

        // 1. Remove status and diagnostics messages
        if (trimmed.contains("SEARCHING") || trimmed.contains("BUS") || trimmed.contains("ERR") || trimmed.contains("STOPPED") || trimmed.contains("NODATA") || trimmed.contains("NO DATA")) {
            return "";
        }

        // 2. Remove line headers like "0:", "1:", "0A:" from consecutive ELM327 lines
        trimmed = trimmed.replaceAll("^[0-9A-F]+:\\s*", "");

        // 3. Remove CAN IDs and PCI bytes with spaces
        // Check for 11-bit CAN ID (e.g. "7E8 ")
        if (trimmed.matches("^[0-9A-F]{3}\\s+.*")) {
            trimmed = trimmed.substring(4); // strip "7E8 "
            // Strip PCI frame headers:
            if (trimmed.matches("^10\\s+[0-9A-F]{2}\\s+.*")) {
                trimmed = trimmed.replaceAll("^10\\s+[0-9A-F]{2}\\s*", ""); // First frame "10 14 "
            } else if (trimmed.matches("^(0[0-9A-F]|2[0-9A-F])\\s+.*")) {
                trimmed = trimmed.replaceAll("^(0[0-9A-F]|2[0-9A-F])\\s*", ""); // Consecutive/Single frame "21 "
            }
        }
        // Check for 29-bit CAN ID (e.g. "18DAF110 ")
        else if (trimmed.matches("^[0-9A-F]{8}\\s+.*")) {
            trimmed = trimmed.substring(9); // strip "18DAF110 "
            // Strip PCI frame headers:
            if (trimmed.matches("^10\\s+[0-9A-F]{2}\\s+.*")) {
                trimmed = trimmed.replaceAll("^10\\s+[0-9A-F]{2}\\s*", "");
            } else if (trimmed.matches("^(0[0-9A-F]|2[0-9A-F])\\s+.*")) {
                trimmed = trimmed.replaceAll("^(0[0-9A-F]|2[0-9A-F])\\s*", "");
            }
        }

        // 4. Remove CAN IDs and PCI bytes without spaces (Format B)
        // Strip 11-bit CAN ID (7E8) + PCI byte (1014, 21, 03)
        trimmed = trimmed.replaceAll("^7E[8-F]10[0-9A-F]{2}", "");
        trimmed = trimmed.replaceAll("^7E[8-F][0-2][0-9A-F]", "");
        // Strip 29-bit CAN ID (18DAF1xx) + PCI byte (1014, 21, 03)
        trimmed = trimmed.replaceAll("^18DAF1[0-9A-F]{2}10[0-9A-F]{2}", "");
        trimmed = trimmed.replaceAll("^18DAF1[0-9A-F]{2}[0-2][0-9A-F]", "");

        // 5. Remove any non-hex characters
        return trimmed.replaceAll("[^0-9A-Fa-f]", "");
    }
}
