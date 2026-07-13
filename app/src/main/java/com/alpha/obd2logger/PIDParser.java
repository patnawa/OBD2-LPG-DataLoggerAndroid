package com.alpha.obd2logger;

import java.util.List;
import java.util.Map;

public final class PIDParser {
    private PIDParser() {
    }

    public static Double extractAndParse(PIDDefinition pidDef, String response, String expectedHeader) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        String normalizedHeader = expectedHeader.toUpperCase();
        String[] lines = response.replace("\r", "\n").split("\n");

        for (String line : lines) {
            String normalizedLine = normalizeResponse(line);
            int index = normalizedLine.indexOf(normalizedHeader);
            if (index >= 0) {
                String data = normalizedLine.substring(index + normalizedHeader.length());
                if (!data.isEmpty()) {
                    Double value = parse(pidDef, data);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    public static void extractMulti(List<PIDDefinition> chunk, String response, Map<String, Double> results) {
        if (response == null || response.isEmpty() || chunk == null || chunk.isEmpty()) {
            return;
        }
        
        String modeStr = chunk.get(0).getService(); // e.g. "01"
        String respMode = positiveResponseService(modeStr); // e.g. "41"
        
        String cleanResp = normalizeResponse(response);
        
        int idx = 0;
        // Need at least a 2-char mode header + 2-char PID to read a frame.
        while (idx <= cleanResp.length() - 4) {
            int modeIdx = cleanResp.indexOf(respMode, idx);
            if (modeIdx == -1) break; // no more data
            
            idx = modeIdx + 2; // move past "41"
            
            while (idx <= cleanResp.length() - 2) {
                String pidHex = cleanResp.substring(idx, idx + 2);
                if (pidHex.equals(respMode)) {
                    break; // encountered a new frame header
                }
                
                PIDDefinition matchedPid = null;
                for (PIDDefinition p : chunk) {
                    String pHex = p.getPidHex();
                    // Skip "_B" pseudo-PIDs here; they are parsed from their parent's data below.
                    if (!pHex.contains("_") && pHex.equalsIgnoreCase(pidHex)) {
                        matchedPid = p;
                        break;
                    }
                }
                
                if (matchedPid == null) {
                    // Unknown PID: advance past its 2-char hex so the outer loop's
                    // indexOf starts from a safe position and can't match "41" inside data.
                    idx += 2;
                    break;
                }
                
                idx += 2; // move past PID hex
                int dataChars = matchedPid.getDataBytes() * 2;
                if (idx + dataChars <= cleanResp.length()) {
                    String dataHex = cleanResp.substring(idx, idx + dataChars);
                    Double value = parse(matchedPid, dataHex);
                    if (value != null && !results.containsKey(matchedPid.getName())) {
                        results.put(matchedPid.getName(), value);
                    }
                    // Parse all derived fields sharing the same parent response
                    // (for example 14_B fuel trim or 34_CD sensor current).
                    String pseudoPrefix = matchedPid.getPidHex() + "_";
                    for (PIDDefinition p : chunk) {
                        if (p.getPidHex().toUpperCase(java.util.Locale.US)
                                .startsWith(pseudoPrefix.toUpperCase(java.util.Locale.US))) {
                            Double derivedValue = parse(p, dataHex);
                            if (derivedValue != null && !results.containsKey(p.getName())) {
                                results.put(p.getName(), derivedValue);
                            }
                        }
                    }
                    idx += dataChars;
                } else {
                    break;
                }
            }
        }
    }

    public static Double parse(PIDDefinition pidDef, String dataHex) {
        try {
            if (dataHex == null || dataHex.length() < 2) {
                return null;
            }
            int requiredBytes = Math.max(1, pidDef.getDataBytes());
            if (dataHex.length() < requiredBytes * 2) {
                return null;
            }

            int rawA = parseHexByte(dataHex, 0);
            int rawB = dataHex.length() >= 4 ? parseHexByte(dataHex, 2) : 0;
            int rawC = dataHex.length() >= 6 ? parseHexByte(dataHex, 4) : 0;
            int rawD = dataHex.length() >= 8 ? parseHexByte(dataHex, 6) : 0;

            FormulaEvaluator evaluator = new FormulaEvaluator(pidDef.getFormula());
            double value = evaluator.evaluate(rawA, rawB, rawC, rawD);

            if (pidDef.getMinVal() <= value && value <= pidDef.getMaxVal()) {
                return Math.round(value * 10000.0) / 10000.0;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String positiveResponseService(String service) {
        try {
            return String.format(java.util.Locale.US, "%02X",
                    Integer.parseInt(service, 16) + 0x40);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeResponse(String response) {
        if (response == null) return "";
        String[] lines = response.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(cleanLine(line));
        }
        return sb.toString().toUpperCase();
    }

    private static String cleanLine(String line) {
        String trimmed = line.trim().toUpperCase();
        if (trimmed.isEmpty()) return "";

        // 1. Remove status and diagnostics messages
        if (trimmed.contains("SEARCHING") || trimmed.contains("BUS") || trimmed.contains("ERR") || trimmed.contains("STOPPED")) {
            return "";
        }

        // 2. Remove line headers like "0:", "1:", "0A:" from consecutive ELM327 lines (Format C/D)
        trimmed = trimmed.replaceAll("^[0-9A-F]+:\\s*", "");

        // 3. Remove CAN IDs and PCI bytes with spaces (Format A)
        // Check for 11-bit CAN ID (e.g. "7E8 ")
        if (trimmed.matches("^[0-9A-F]{3}\\s+.*")) {
            trimmed = trimmed.substring(4); // strip "7E8 "
            // Strip PCI frame headers:
            if (trimmed.matches("^10\\s+[0-9A-F]{2}\\s+.*")) {
                trimmed = trimmed.replaceAll("^10\\s+[0-9A-F]{2}\\s*", ""); // First frame "10 13 "
            } else if (trimmed.matches("^(0[0-9A-F]|2[0-9A-F])\\s+.*")) {
                trimmed = trimmed.replaceAll("^(0[0-9A-F]|2[0-9A-F])\\s*", ""); // Consecutive/Single frame "21 " / "03 "
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
        // Strip 11-bit CAN ID (7E8) + PCI byte (1013, 21, 03)
        trimmed = trimmed.replaceAll("^7E[8-F]10[0-9A-F]{2}", "");
        trimmed = trimmed.replaceAll("^7E[8-F][0-2][0-9A-F]", "");
        // Strip 29-bit CAN ID (18DAF1xx) + PCI byte (1013, 21, 03)
        trimmed = trimmed.replaceAll("^18DAF1[0-9A-F]{2}10[0-9A-F]{2}", "");
        trimmed = trimmed.replaceAll("^18DAF1[0-9A-F]{2}[0-2][0-9A-F]", "");

        // 5. Remove any non-hex characters
        trimmed = trimmed.replaceAll("[^0-9A-F]", "");

        // 6. Ignore multi-frame total length lines (e.g. "00E" or "013")
        if (trimmed.length() <= 3 && !trimmed.startsWith("41")) {
            return "";
        }

        return trimmed;
    }

    private static int parseHexByte(String dataHex, int offset) {
        return Integer.parseInt(dataHex.substring(offset, offset + 2), 16);
    }

    private static final class FormulaEvaluator {
        private final String input;
        private int pos;

        FormulaEvaluator(String input) {
            this.input = input.replace(" ", "").toUpperCase();
        }

        double evaluate(int a, int b, int c, int d) {
            double value = parseExpression(a, b, c, d);
            if (pos != input.length()) {
                throw new IllegalArgumentException("Unparsed formula tail at " + pos);
            }
            return value;
        }

        private double parseExpression(int a, int b, int c, int d) {
            double value = parseTerm(a, b, c, d);
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (ch == '+') {
                    pos++;
                    value += parseTerm(a, b, c, d);
                } else if (ch == '-') {
                    pos++;
                    value -= parseTerm(a, b, c, d);
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseTerm(int a, int b, int c, int d) {
            double value = parseFactor(a, b, c, d);
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (ch == '*') {
                    pos++;
                    value *= parseFactor(a, b, c, d);
                } else if (ch == '/') {
                    pos++;
                    value /= parseFactor(a, b, c, d);
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseFactor(int a, int b, int c, int d) {
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of formula");
            }
            char ch = input.charAt(pos);
            if (ch == '+') {
                pos++;
                return parseFactor(a, b, c, d);
            }
            if (ch == '-') {
                pos++;
                return -parseFactor(a, b, c, d);
            }
            if (ch == '(') {
                pos++;
                double value = parseExpression(a, b, c, d);
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                pos++;
                return value;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int start = pos;
                while (pos < input.length()) {
                    char ch2 = input.charAt(pos);
                    if (Character.isDigit(ch2) || ch2 == '.') {
                        pos++;
                    } else {
                        break;
                    }
                }
                return Double.parseDouble(input.substring(start, pos));
            }
            if (ch == 'A') {
                pos++;
                return a;
            }
            if (ch == 'B') {
                pos++;
                return b;
            }
            if (ch == 'C') {
                pos++;
                return c;
            }
            if (ch == 'D') {
                pos++;
                return d;
            }
            throw new IllegalArgumentException("Unexpected character: " + ch);
        }
    }
}
