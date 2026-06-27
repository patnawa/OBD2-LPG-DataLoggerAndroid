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
        if (!(driver instanceof ElmDriver)) {
            return null;
        }

        // Send 0902 with headers off — ELM327 handles ISO-TP reassembly
        String response = ((ElmDriver) driver).sendCommandRaw("0902");
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

        // Normalise: strip spaces, non-hex noise
        String hex = response.replace("\r\n", "\n").replace('\r', '\n');
        hex = hex.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "");
        hex = hex.replaceAll("[^0-9A-Fa-f]", "");
        hex = hex.toUpperCase();

        if (hex.length() < 10) {
            return null;
        }

        // The response contains 49 02 01 followed by ASCII hex bytes.
        // In multi-frame, we see: 490201 <17 hex bytes>
        // ELM327 with ATSP auto + headers off usually concatenates all frames.

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
}
