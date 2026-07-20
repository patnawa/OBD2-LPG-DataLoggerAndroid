package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only SAE J1979 Mode 09 vehicle information reader.
 *
 * <p>Mode 09 Info Type 02 is the VIN and is handled by {@link VinReader}.
 * This class handles the support bitmap (00), calibration ID (04), and
 * calibration verification number (06). It never writes to an ECU.</p>
 */
public final class Mode09Reader {

    private Mode09Reader() {
    }

    /**
     * Read the Mode 09 support bitmap. Returned values are Mode 09 information
     * types, not Mode 01 PIDs; for example 02 is VIN, 04 is Cal-ID and 06 is CVN.
     */
    public static List<Integer> readSupportedInfoTypes(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) return new ArrayList<>();
        return parseSupportedInfoTypes(driver.sendCommandRaw("0900"));
    }

    /** Parse an Info Type 00 support bitmap from an ELM-style response. */
    static List<Integer> parseSupportedInfoTypes(String response) {
        List<Integer> supported = new ArrayList<>();
        boolean[] advertised = new boolean[33];
        for (StringBuilder payloadValue : compactMode09Payloads(response).values()) {
            String payload = payloadValue.toString();
            int headerIndex = payload.indexOf("4900");
            if (headerIndex < 0 || payload.length() < headerIndex + 12) continue;
            String bitmap = payload.substring(headerIndex + 4, headerIndex + 12);
            try {
                for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                    int value = Integer.parseInt(
                            bitmap.substring(byteIndex * 2, byteIndex * 2 + 2), 16);
                    for (int bit = 0; bit < 8; bit++) {
                        if ((value & (1 << (7 - bit))) != 0) {
                            advertised[byteIndex * 8 + bit + 1] = true;
                        }
                    }
                }
            } catch (NumberFormatException ignored) {
                // Ignore only the malformed ECU payload; another ECU may be valid.
            }
        }
        for (int infoType = 1; infoType < advertised.length; infoType++) {
            if (advertised[infoType]) supported.add(infoType);
        }
        return supported;
    }

    /** Read all calibration IDs advertised by the ECU(s), Mode 09 Info Type 04. */
    public static List<CalIdEntry> readCalIds(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) return new ArrayList<>();
        return parseCalIds(driver.sendCommandRaw("0904"));
    }

    /** Read all calibration verification numbers, Mode 09 Info Type 06. */
    public static List<CvnEntry> readCvns(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) return new ArrayList<>();
        return parseCvns(driver.sendCommandRaw("0906"));
    }

    /**
     * Read-only evidence that helps explain readiness after DTCs were cleared.
     * These standard values belong to Mode 01, rather than Mode 09.
     */
    public static final class InUsePerformance {
        public final int warmUpCyclesSinceClear; // Mode 01 PID 30
        public final int distanceSinceClearKm;   // Mode 01 PID 31
        public final int timeSinceClearMin;      // Mode 01 PID 4E

        public InUsePerformance(int warmUpCyclesSinceClear,
                                int distanceSinceClearKm, int timeSinceClearMin) {
            this.warmUpCyclesSinceClear = warmUpCyclesSinceClear;
            this.distanceSinceClearKm = distanceSinceClearKm;
            this.timeSinceClearMin = timeSinceClearMin;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "Warm-ups: %d, Distance: %d km, Engine Time: %d min",
                    warmUpCyclesSinceClear, distanceSinceClearKm, timeSinceClearMin);
        }
    }

    /** Read Mode 01 PID 30: warm-up cycles since DTCs were cleared. */
    public static int readWarmUpCyclesSinceClear(BaseDriver driver) {
        return readMode01UnsignedValue(driver, "0130", "4130", 1);
    }

    /** Read Mode 01 PID 31: distance travelled since DTCs were cleared. */
    public static int readDistanceSinceClear(BaseDriver driver) {
        return readMode01UnsignedValue(driver, "0131", "4131", 2);
    }

    /** Read Mode 01 PID 4E: engine run time since DTCs were cleared. */
    public static int readTimeSinceClear(BaseDriver driver) {
        return readMode01UnsignedValue(driver, "014E", "414E", 2);
    }

    /** Read all standard evidence-since-clear values at once. */
    public static InUsePerformance readInUsePerformance(BaseDriver driver) {
        return new InUsePerformance(
                readWarmUpCyclesSinceClear(driver),
                readDistanceSinceClear(driver),
                readTimeSinceClear(driver));
    }

    private static int readMode01UnsignedValue(BaseDriver driver, String command,
                                                String expectedHeader, int byteCount) {
        if (driver == null || !driver.isConnected()) return -1;
        return parseMode01UnsignedValue(driver.sendCommandRaw(command), expectedHeader, byteCount);
    }

    static int parseMode01UnsignedValue(String response, String expectedHeader, int byteCount) {
        if (response == null || response.isEmpty() || byteCount < 1 || byteCount > 2) return -1;
        String allHex = response.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        int index = allHex.indexOf(expectedHeader);
        int dataStart = index + expectedHeader.length();
        int dataLength = byteCount * 2;
        if (index < 0 || allHex.length() < dataStart + dataLength) return -1;
        try {
            return Integer.parseInt(allHex.substring(dataStart, dataStart + dataLength), 16);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * Parse Mode 09 Info Type 04 data. Each Cal-ID is 16 ASCII bytes and the
     * first byte after the response header states how many IDs are present.
     */
    public static List<CalIdEntry> parseCalIds(String response) {
        List<CalIdEntry> entries = new ArrayList<>();
        int ecuIndex = 0;
        for (StringBuilder payloadValue : compactMode09Payloads(response).values()) {
            String payload = payloadValue.toString();
            int headerIndex = payload.indexOf("4904");
            if (headerIndex < 0) continue;
            String data = payload.substring(headerIndex + 4);
            if (data.length() < 2) continue;
            int count;
            try {
                count = Integer.parseInt(data.substring(0, 2), 16);
            } catch (NumberFormatException ignored) {
                continue;
            }

            int position = 2;
            for (int i = 0; i < count && position + 32 <= data.length(); i++) {
                String value = asciiFromHex(data.substring(position, position + 32));
                if (!value.isEmpty()) entries.add(new CalIdEntry(ecuIndex, value));
                position += 32;
            }
            ecuIndex++;
        }
        return entries;
    }

    /**
     * Parse Mode 09 Info Type 06 data. Each CVN is four bytes and the first
     * byte after the response header states how many CVNs are present.
     */
    public static List<CvnEntry> parseCvns(String response) {
        List<CvnEntry> entries = new ArrayList<>();
        int ecuIndex = 0;
        for (StringBuilder payloadValue : compactMode09Payloads(response).values()) {
            String payload = payloadValue.toString();
            int headerIndex = payload.indexOf("4906");
            if (headerIndex < 0) continue;
            String data = payload.substring(headerIndex + 4);
            if (data.length() < 2) continue;
            int count;
            try {
                count = Integer.parseInt(data.substring(0, 2), 16);
            } catch (NumberFormatException ignored) {
                continue;
            }

            int position = 2;
            for (int i = 0; i < count && position + 8 <= data.length(); i++) {
                entries.add(new CvnEntry(ecuIndex, data.substring(position, position + 8)));
                position += 8;
            }
            ecuIndex++;
        }
        return entries;
    }

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

    private static String asciiFromHex(String hex) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i + 2 <= hex.length(); i += 2) {
            try {
                int character = Integer.parseInt(hex.substring(i, i + 2), 16);
                if (character >= 0x20 && character <= 0x7E) value.append((char) character);
            } catch (NumberFormatException ignored) {
                return "";
            }
        }
        return value.toString().trim();
    }

    /** Reassemble Mode 09 ISO-TP payloads independently for every responding ECU. */
    private static Map<String, StringBuilder> compactMode09Payloads(String response) {
        return compactIsoTpPayloads(response, "49");
    }

    /**
     * Reassemble ISO-TP payloads independently for every responding ECU.
     *
     * <p>A functional (7DF) request is answered by every ECU that implements the
     * service, and the adapter emits their frames interleaved. Concatenating the
     * lines in arrival order splices two vehicles' worth of bytes together, so
     * frames are grouped by their source CAN header before reassembly.</p>
     *
     * @param responsePrefix the positive-response service byte in hex, e.g.
     *                       {@code "49"} for Mode 09 or {@code "62"} for UDS
     *                       ReadDataByIdentifier. Used to recognise a payload
     *                       that begins without a PCI byte.
     */
    static Map<String, StringBuilder> compactIsoTpPayloads(String response, String responsePrefix) {
        Map<String, StringBuilder> payloads = new LinkedHashMap<>();
        if (response == null || response.isEmpty()) return payloads;
        String normalized = response.replace("\r\n", "\n").replace('\r', '\n');
        for (String sourceLine : normalized.split("\n")) {
            String line = sourceLine.trim().toUpperCase(Locale.US);
            if (line.isEmpty() || line.contains("SEARCHING") || line.contains("BUS")
                    || line.contains("ERR") || line.contains("STOPPED")
                    || line.contains("NODATA") || line.contains("NO DATA")) {
                continue;
            }

            // Keep a colon-suffixed CAN ID ("7E8: ...") as the source key.
            // Only a 1-2 digit prefix is an ELM row counter ("0: ...").
            // Treating 7E8 as a counter merges interleaved ECU responses.
            String colonHeader = null;
            int firstColon = line.indexOf(':');
            if (firstColon > 0) {
                String prefix = line.substring(0, firstColon).trim();
                if (isCanHeader(prefix)) {
                    colonHeader = prefix;
                    line = line.substring(firstColon + 1).trim();
                }
            }
            boolean hadFrameIndex = colonHeader == null
                    && line.matches("^[0-9A-F]{1,2}:.*");
            if (hadFrameIndex) {
                line = line.replaceFirst("^[0-9A-F]{1,2}:\\s*", "");
            }
            if (line.isEmpty()) continue;

            String ecuKey = colonHeader;
            String frameHex = null;
            String[] tokens = line.split("\\s+");
            int payloadStart = 0;
            if (ecuKey == null && tokens.length > 1 && isCanHeader(tokens[0])) {
                ecuKey = tokens[0];
                payloadStart = 1;
            }
            if (ecuKey != null) {
                // vLinker can retain its row counter after the CAN header:
                // "7E8 0: 49 02 ...", "7E8 1: ...".
                if (payloadStart < tokens.length
                        && tokens[payloadStart].matches("[0-9A-F]{1,2}:")) {
                    payloadStart++;
                }
                // Some adapters print the one-nibble CAN DLC as a separate
                // token ("7E8 8 10 14 ..."). It is metadata, not payload.
                if (payloadStart + 1 < tokens.length
                        && tokens[payloadStart].matches("[0-8]")) {
                    payloadStart++;
                }
                StringBuilder bytes = new StringBuilder();
                for (int i = payloadStart; i < tokens.length; i++) bytes.append(tokens[i]);
                frameHex = bytes.toString().replaceAll("[^0-9A-F]", "");
            } else {
                String compact = line.replaceAll("[^0-9A-F]", "");
                // A numbered continuation such as "2: 45 32 ..." can begin
                // with three valid hex digits by coincidence. It is not a CAN
                // header unless the header was a distinct token above.
                String[] split = hadFrameIndex ? null : splitUnspacedCanFrame(compact, responsePrefix);
                if (split != null) {
                    ecuKey = split[0];
                    frameHex = split[1];
                } else {
                    ecuKey = "HEADERS_OFF";
                    frameHex = compact;
                }
            }

            if (frameHex.length() <= 3 && !frameHex.startsWith(responsePrefix)) continue;
            if (!"HEADERS_OFF".equals(ecuKey)) frameHex = stripIsoTpPci(frameHex, responsePrefix);
            payloads.computeIfAbsent(ecuKey, key -> new StringBuilder()).append(frameHex);
        }
        return payloads;
    }

    private static boolean isCanHeader(String token) {
        if (token == null || !token.matches("[0-9A-F]{3}|[0-9A-F]{8}")) return false;
        try {
            if (token.length() == 3) {
                int id = Integer.parseInt(token, 16);
                return id > 0 && id <= 0x7FF;
            }
            Long.parseLong(token, 16);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String[] splitUnspacedCanFrame(String compact, String responsePrefix) {
        if (compact == null || compact.length() < 7 || compact.startsWith(responsePrefix)) return null;
        int headerLength = compact.startsWith("18") && compact.length() >= 12 ? 8 : 3;
        if (compact.length() <= headerLength + 2) return null;
        String header = compact.substring(0, headerLength);
        if (!isCanHeader(header)) return null;
        String frame = compact.substring(headerLength);
        if (!(frame.startsWith(responsePrefix) || frame.matches("^[012][0-9A-F].*"))) return null;
        return new String[] { header, frame };
    }

    private static String stripIsoTpPci(String frameHex, String responsePrefix) {
        if (frameHex == null || frameHex.length() < 2 || frameHex.startsWith(responsePrefix)) {
            return frameHex == null ? "" : frameHex;
        }
        char type = frameHex.charAt(0);
        if (type == '0') return frameHex.substring(2);
        if (type == '1' && frameHex.length() >= 4) return frameHex.substring(4);
        if (type == '2') return frameHex.substring(2);
        return frameHex;
    }
}
