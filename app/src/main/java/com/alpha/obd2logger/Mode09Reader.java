package com.alpha.obd2logger;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads OBD2 Mode 09 (Vehicle Information) data:
 *   - PID 02: Calibration ID (Cal-ID) — ECU software identifier
 *   - PID 04: Calibration Verification Number (CVN) — ECU software checksum
 *   - PID 06: In-Use Performance Tracking (optional)
 *
 * Cal-ID and CVN are critical for:
 *   - Emissions inspections (smog checks)
 *   - Detecting ECU reflashing/tuning
 *   - Verifying correct ECU software version for the vehicle
 *
 * Mode 09 response format:
 *   Request: 0902 (for Cal-ID)
 *   Response: 49 02 [count] [Cal-ID1...] [Cal-ID2...] ...
 *
 * Each Cal-ID is 16 ASCII characters (4 groups of 4).
 * CVN is 4 bytes hex per ECU.
 */
public final class Mode09Reader {

    private static final String TAG = "Mode09Reader";

    private Mode09Reader() {}

    /**
     * Read all calibration IDs from the vehicle.
     * @return list of CalID entries (one per ECU)
     */
    public static List<CalIdEntry> readCalIds(BaseDriver driver) {
        List<CalIdEntry> entries = new ArrayList<>();
        if (driver == null || !driver.isConnected()) {
            return entries;
        }

        String response = driver.sendCommandRaw("0902");
        return parseCalIds(response);
    }

    /**
     * Read all Calibration Verification Numbers (CVNs).
     */
    public static List<CvnEntry> readCvns(BaseDriver driver) {
        List<CvnEntry> entries = new ArrayList<>();
        if (driver == null || !driver.isConnected()) {
            return entries;
        }

        String response = driver.sendCommandRaw("0904");
        return parseCvns(response);
    }

    /**
     * Parse Mode 09 PID 02 (Cal-ID) response.
     *
     * Format: 49 02 [count] [16 ASCII chars per Cal-ID]
     * Multiple Cal-IDs are returned if multiple ECUs respond.
     * The count byte indicates number of Cal-IDs.
     *
     * ELM327 may return:
     *   49 02 01 00 33 39 30 33 36 2D 53 31 42 2D 41 45 32
     *   = header "4902", count=01, Cal-ID in ASCII: "39036-S1B-AE2" (padded with 00)
     */
    public static List<CalIdEntry> parseCalIds(String response) {
        List<CalIdEntry> entries = new ArrayList<>();
        if (response == null || response.isEmpty()) return entries;

        // Normalize
        String hex = response.replace("\r", "\n").replace("\r\n", "\n");
        StringBuilder hexBuf = new StringBuilder();
        for (String line : hex.split("\\n")) {
            hexBuf.append(cleanMode09Line(line));
        }

        String allHex = hexBuf.toString();
        int headerIdx = allHex.indexOf("4902");
        if (headerIdx < 0) return entries;

        String data = allHex.substring(headerIdx + 4);
        if (data.length() < 2) return entries;

        // Count byte
        int count;
        try {
            count = Integer.parseInt(data.substring(0, 2), 16);
        } catch (NumberFormatException e) {
            return entries;
        }

        // Each Cal-ID is 16 ASCII characters = 32 hex chars
        int pos = 2;
        for (int i = 0; i < count && pos + 32 <= data.length(); i++) {
            String calIdHex = data.substring(pos, pos + 32);
            StringBuilder calId = new StringBuilder();
            for (int j = 0; j < 32; j += 2) {
                int ch = Integer.parseInt(calIdHex.substring(j, j + 2), 16);
                if (ch >= 0x20 && ch <= 0x7E) { // printable ASCII
                    calId.append((char) ch);
                }
            }
            String id = calId.toString().trim();
            if (!id.isEmpty()) {
                entries.add(new CalIdEntry(i, id));
            }
            pos += 32;
        }

        return entries;
    }

    /**
     * Parse Mode 09 PID 04 (CVN) response.
     *
     * Format: 49 04 [count] [4 bytes CVN per ECU]
     * The CVN is a 4-byte hex value displayed as 8 hex characters.
     */
    public static List<CvnEntry> parseCvns(String response) {
        List<CvnEntry> entries = new ArrayList<>();
        if (response == null || response.isEmpty()) return entries;

        String hex = response.replace("\r", "\n").replace("\r\n", "\n");
        StringBuilder hexBuf = new StringBuilder();
        for (String line : hex.split("\\n")) {
            hexBuf.append(cleanMode09Line(line));
        }

        String allHex = hexBuf.toString();
        int headerIdx = allHex.indexOf("4904");
        if (headerIdx < 0) return entries;

        String data = allHex.substring(headerIdx + 4);
        if (data.length() < 2) return entries;

        int count;
        try {
            count = Integer.parseInt(data.substring(0, 2), 16);
        } catch (NumberFormatException e) {
            return entries;
        }

        // Each CVN is 4 bytes = 8 hex chars
        int pos = 2;
        for (int i = 0; i < count && pos + 8 <= data.length(); i++) {
            String cvn = data.substring(pos, pos + 8);
            entries.add(new CvnEntry(i, cvn));
            pos += 8;
        }

        return entries;
    }

    /**
     * Calibration ID entry.
     */
    public static final class CalIdEntry {
        private final int ecuIndex;
        private final String calId;

        public CalIdEntry(int ecuIndex, String calId) {
            this.ecuIndex = ecuIndex;
            this.calId = calId;
        }

        public int getEcuIndex() { return ecuIndex; }
        public String getCalId() { return calId; }

        @Override
        public String toString() {
            return "ECU " + ecuIndex + ": " + calId;
        }
    }

    /**
     * Calibration Verification Number entry.
     */
    public static final class CvnEntry {
        private final int ecuIndex;
        private final String cvn;

        public CvnEntry(int ecuIndex, String cvn) {
            this.ecuIndex = ecuIndex;
            this.cvn = cvn;
        }

        public int getEcuIndex() { return ecuIndex; }
        public String getCvn() { return cvn; }

        @Override
        public String toString() {
            return "ECU " + ecuIndex + ": " + cvn;
        }
    }

    private static String cleanMode09Line(String line) {
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
