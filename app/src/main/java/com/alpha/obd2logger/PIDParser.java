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
        String respMode = "4" + modeStr.substring(1); // e.g. "41"
        
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
                    // Parse any "_B" pseudo-PID that shares this PID's hex (e.g. O2 sensor STFT).
                    String bKey = matchedPid.getPidHex() + "_B";
                    for (PIDDefinition p : chunk) {
                        if (p.getPidHex().equalsIgnoreCase(bKey)) {
                            Double bValue = parse(p, dataHex);
                            if (bValue != null && !results.containsKey(p.getName())) {
                                results.put(p.getName(), bValue);
                            }
                            break;
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

            FormulaEvaluator evaluator = new FormulaEvaluator(pidDef.getFormula());
            double value = evaluator.evaluate(rawA, rawB, rawC);

            if (pidDef.getMinVal() <= value && value <= pidDef.getMaxVal()) {
                return Math.round(value * 10000.0) / 10000.0;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeResponse(String response) {
        String normalized = response.replace("\r\n", "\n").replace('\r', '\n');
        normalized = normalized.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "");
        normalized = normalized.replaceAll("[^0-9A-Fa-f]", "");
        return normalized.toUpperCase();
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

        double evaluate(int a, int b, int c) {
            double value = parseExpression(a, b, c);
            if (pos != input.length()) {
                throw new IllegalArgumentException("Unparsed formula tail at " + pos);
            }
            return value;
        }

        private double parseExpression(int a, int b, int c) {
            double value = parseTerm(a, b, c);
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (ch == '+') {
                    pos++;
                    value += parseTerm(a, b, c);
                } else if (ch == '-') {
                    pos++;
                    value -= parseTerm(a, b, c);
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseTerm(int a, int b, int c) {
            double value = parseFactor(a, b, c);
            while (pos < input.length()) {
                char ch = input.charAt(pos);
                if (ch == '*') {
                    pos++;
                    value *= parseFactor(a, b, c);
                } else if (ch == '/') {
                    pos++;
                    value /= parseFactor(a, b, c);
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseFactor(int a, int b, int c) {
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of formula");
            }
            char ch = input.charAt(pos);
            if (ch == '+') {
                pos++;
                return parseFactor(a, b, c);
            }
            if (ch == '-') {
                pos++;
                return -parseFactor(a, b, c);
            }
            if (ch == '(') {
                pos++;
                double value = parseExpression(a, b, c);
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
            throw new IllegalArgumentException("Unexpected character: " + ch);
        }
    }
}
