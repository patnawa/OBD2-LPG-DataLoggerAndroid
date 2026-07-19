package com.alpha.obd2logger;

import java.util.Locale;
import java.util.Map;

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

        // First try the normal polling profile. Most vehicles answer immediately.
        String response = driver.sendCommandRaw("0902");
        String vin = parseVinResponse(response);
        if (vin != null) {
            return vin;
        }

        // Toyota and other ECUs can take longer to produce the multi-frame Mode
        // 09 response than the live-PID timeout permits. Retry once with CAN
        // flow control, raw ISO-TP frame visibility and an extended ELM timeout.
        // The ElmDriver helper restores the compact live-polling profile before
        // it returns.
        if (driver instanceof ElmDriver) {
            ElmDriver elm = (ElmDriver) driver;
            response = elm.sendCommandRawWithExtendedTimeout("0902");
            vin = parseVinResponse(response);
            if (vin != null) {
                return vin;
            }

            // Some ECUs answer Mode 01 through the standard functional address,
            // yet only return their multi-frame VIN when the request is
            // addressed to the engine ECU directly. The physical address form
            // depends on the active bus: 7E0/7E8 for 11-bit CAN, 18DAxxF1 for
            // 29-bit. K-line vehicles get neither — physical CAN addressing is
            // not valid there.
            String activeProtocol = elm.sendCommandRaw("ATDPN");
            String[][] physicalEcus = null;
            if (ElmDriver.isElevenBitCanProtocol(activeProtocol)) {
                physicalEcus = UDS_VIN_ECUS_11BIT;
            } else if (ElmDriver.isTwentyNineBitCanProtocol(activeProtocol)) {
                physicalEcus = UDS_VIN_ECUS_29BIT;
            }
            if (physicalEcus != null) {
                response = elm.sendCommandRawToEcuWithExtendedTimeout(
                        "0902", physicalEcus[0][0], physicalEcus[0][1]);
                vin = parseVinResponse(response);
                if (vin != null) {
                    return vin;
                }

                // Many vehicles — Asian-market and pre-2016 Toyotas especially —
                // do not implement Mode 09 Info Type 02 on the OBD port at all.
                // Their VIN is only readable through the UDS ReadDataByIdentifier
                // service, DID F190. Ask the engine ECU, then the transmission
                // ECU, which is where some models publish it instead.
                for (String[] ecu : physicalEcus) {
                    response = elm.sendCommandRawToEcuWithExtendedTimeout(
                            "22F190", ecu[0], ecu[1]);
                    vin = parseUdsVinResponse(response);
                    if (vin != null) {
                        return vin;
                    }
                }
            }
        }
        return null;
    }

    /** Engine and transmission ECU {tx header, rx filter} pairs, 11-bit CAN. */
    private static final String[][] UDS_VIN_ECUS_11BIT = {
            {"7E0", "7E8"},
            {"7E1", "7E9"},
    };

    /**
     * Engine and transmission ECU pairs for 29-bit CAN (ISO 15765-4 extended
     * addressing): request 18DA&lt;target&gt;F1, response 18DAF1&lt;target&gt;.
     * 0x10 is the engine ECM, 0x18 the TCM.
     */
    private static final String[][] UDS_VIN_ECUS_29BIT = {
            {"18DA10F1", "18DAF110"},
            {"18DA18F1", "18DAF118"},
    };

    /**
     * Parse the VIN from a Mode 09 PID 02 response.
     *
     * <p>The VIN response is a multi-frame ISO-15765-2 (ISO-TP) message.
     * Each frame starts with {@code 49 02 0X} where {@code 0X} is the
     * frame sequence number (01 = first, 02 = second, etc.).
     *
     * <p>Frames are grouped by their source CAN header before reassembly, then
     * the {@code 49 02 0X} bytes are stripped and the remainder read as ASCII.
     */
    static String parseVinResponse(String response) {
        return parseVinPayloads(response, "49", "4902[0-9A-F]{2}");
    }

    /**
     * Parse the VIN from a UDS ReadDataByIdentifier (service 22) response for
     * DID F190. The positive response is {@code 62 F1 90} followed by the 17
     * VIN characters, carried over ISO-TP exactly like the Mode 09 reply.
     */
    static String parseUdsVinResponse(String response) {
        return parseVinPayloads(response, "62", "62F190");
    }

    /**
     * @param servicePrefix positive-response service byte, used to recognise a
     *                      payload that starts without an ISO-TP PCI byte
     * @param headerPattern regex for the service/identifier bytes to strip out
     *                      before the payload is read as ASCII
     */
    private static String parseVinPayloads(String response, String servicePrefix,
                                           String headerPattern) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        // A functional Mode 09 request is answered by every ECU that implements
        // it, and the adapter interleaves their frames. Reassembling per source
        // CAN header keeps one ECU's consecutive frames from being spliced into
        // another's, which previously produced a garbage 17-character window.
        Map<String, StringBuilder> perEcuPayloads =
                Mode09Reader.compactIsoTpPayloads(response, servicePrefix);

        String best = null;
        int bestScore = -1;
        for (StringBuilder payload : perEcuPayloads.values()) {
            String hex = payload.toString().toUpperCase(Locale.US);
            if (hex.length() < 10) {
                continue;
            }
            // Strip the service/identifier bytes, leaving only VIN characters.
            // No byte of the pattern is itself a legal VIN character, so an
            // unanchored replace cannot eat real payload.
            String payloadHex = hex.replaceAll(headerPattern, "");
            if (payloadHex.length() < 2) {
                continue;
            }

            String candidates = extractVinCharacters(payloadHex);

            // Search every 17-character window and keep the highest-ranked one.
            // A strict '>' means ties resolve to the earliest window, matching
            // the historical first-match behavior.
            for (int start = 0; start + 17 <= candidates.length(); start++) {
                String candidate = candidates.substring(start, start + 17);
                if (!VinBrandDetector.isStructurallyValid(candidate)) continue;
                int score = rankCandidate(candidate);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        // Reject anything shorter/invalid — don't propagate garbage VINs
        // that would create wrong folder names or break PID caching.
        return best;
    }

    /**
     * Rank a structurally valid 17-character window. A recognized WMI is the
     * strongest signal (2 points): stray ASCII around the true VIN cannot fake
     * it. A valid ISO check digit adds 1 more — it separates the real VIN from
     * a shifted window over the same bytes. The check digit alone is only worth
     * 1 point because most non-North-American VINs (including the Thai market)
     * do not populate position 9, so its absence must never outweigh a WMI hit.
     */
    private static int rankCandidate(String candidate) {
        int score = 0;
        if (VinBrandDetector.detect(candidate) != VinBrandDetector.Brand.UNKNOWN) score += 2;
        if (VinBrandDetector.hasValidCheckDigit(candidate)) score += 1;
        return score;
    }

    private static String extractVinCharacters(String payloadHex) {
        StringBuilder vin = new StringBuilder();
        for (int i = 0; i + 2 <= payloadHex.length(); i += 2) {
            try {
                int charVal = Integer.parseInt(payloadHex.substring(i, i + 2), 16);
                if (isVinCharacter(charVal)) {
                    vin.append((char) charVal);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return vin.toString();
    }

    private static boolean isVinCharacter(int value) {
        return (value >= '0' && value <= '9')
                || (value >= 'A' && value <= 'Z'
                && value != 'I' && value != 'O' && value != 'Q');
    }

}
