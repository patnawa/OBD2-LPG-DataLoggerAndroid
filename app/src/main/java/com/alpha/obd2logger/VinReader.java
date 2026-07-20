package com.alpha.obd2logger;

import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

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

    /** Bounds the expanded physical sweep when an adapter is half-open. */
    private static final long PHYSICAL_FALLBACK_BUDGET_NANOS = 30_000_000_000L;

    private VinReader() {
    }

    /**
     * Read VIN from the vehicle via Mode 09 PID 02.
     * @return the 17-character VIN string, or null if unavailable
     */
    public static String readVin(BaseDriver driver) {
        return readVin(driver, System::nanoTime, PHYSICAL_FALLBACK_BUDGET_NANOS);
    }

    /** Testable overload whose clock and physical-fallback budget use nanoseconds. */
    static String readVin(BaseDriver driver, LongSupplier monotonicClock,
                          long physicalFallbackBudgetNanos) {
        if (driver == null || !driver.isConnected()) {
            return null;
        }
        if (Thread.currentThread().isInterrupted()) return null;
        LongSupplier clock = monotonicClock == null ? System::nanoTime : monotonicClock;

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
            if (!isFallbackActive(elm, clock, 0L, Long.MAX_VALUE)) return null;
            response = elm.sendCommandRawWithExtendedTimeout("0902");
            vin = parseVinResponse(response);
            if (vin != null) {
                return vin;
            }
            if (!elm.isConnected() || Thread.currentThread().isInterrupted()) return null;

            // Some ECUs answer Mode 01 through the standard functional address,
            // yet only return their multi-frame VIN when the request is
            // addressed to the engine ECU directly. The physical address form
            // depends on the active bus: 7E0/7E8 for 11-bit CAN, 18DAxxF1 for
            // 29-bit. K-line vehicles get neither — physical CAN addressing is
            // not valid there.
            long fallbackStartedNanos = clock.getAsLong();
            long budgetNanos = Math.max(0L, physicalFallbackBudgetNanos);
            if (!isFallbackActive(elm, clock, fallbackStartedNanos, budgetNanos)) return null;
            String activeProtocol = elm.sendCommandRaw("ATDPN");
            if (!isFallbackActive(elm, clock, fallbackStartedNanos, budgetNanos)) return null;
            String[][] physicalEcus = null;
            if (ElmDriver.isElevenBitCanProtocol(activeProtocol)) {
                physicalEcus = UDS_VIN_ECUS_11BIT;
            } else if (ElmDriver.isTwentyNineBitCanProtocol(activeProtocol)) {
                physicalEcus = UDS_VIN_ECUS_29BIT;
            }
            if (physicalEcus != null) {
                // A physical OBD request can be answered by any legislated ECU
                // address in the 7E0-7E7 range. Toyota gateways and Ford
                // instrument/powertrain layouts do not always publish the VIN
                // from 7E0, so try every conservative request/response pair
                // instead of assuming the engine ECU owns Info Type 02.
                for (String[] ecu : physicalEcus) {
                    if (!isFallbackActive(
                            elm, clock, fallbackStartedNanos, budgetNanos)) return null;
                    response = sendPhysicalWithPendingRetries(
                            elm, "0902", "09", ecu[0], ecu[1], clock,
                            fallbackStartedNanos, budgetNanos);
                    vin = parseVinResponse(response);
                    if (vin != null) {
                        return vin;
                    }
                    if (!isFallbackActive(
                            elm, clock, fallbackStartedNanos, budgetNanos)) return null;

                    // Many vehicles — Asian-market Toyotas especially — do not
                    // implement Mode 09 Info Type 02 on every OBD-facing ECU.
                    // Try read-only UDS DID F190 at the same address before
                    // moving on, which avoids a full slow sweep when the VIN is
                    // available from an early gateway/ECU.
                    response = sendPhysicalWithPendingRetries(
                            elm, "22F190", "22", ecu[0], ecu[1], clock,
                            fallbackStartedNanos, budgetNanos);
                    vin = parseUdsVinResponse(response);
                    if (vin != null) {
                        return vin;
                    }
                }
            }
        }
        return null;
    }

    /** Retry a bounded physical read when an ECU explicitly says responsePending. */
    private static String sendPhysicalWithPendingRetries(ElmDriver elm, String command,
                                                          String requestService,
                                                          String txHeader, String rxFilter,
                                                          LongSupplier clock,
                                                          long fallbackStartedNanos,
                                                          long budgetNanos) {
        String response = "";
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!isFallbackActive(
                    elm, clock, fallbackStartedNanos, budgetNanos)) break;
            response = elm.sendCommandRawToEcuWithExtendedTimeout(
                    command, txHeader, rxFilter, clock,
                    fallbackStartedNanos, budgetNanos);
            if (!isResponsePending(response, requestService)) break;
        }
        return response;
    }

    private static boolean isResponsePending(String response, String requestService) {
        if (response == null || requestService == null) return false;
        String compact = response.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        return compact.contains("7F" + requestService + "78");
    }

    private static boolean isFallbackActive(BaseDriver driver, LongSupplier clock,
                                             long startedNanos, long budgetNanos) {
        if (driver == null || !driver.isConnected()
                || Thread.currentThread().isInterrupted()) return false;
        if (budgetNanos == Long.MAX_VALUE) return true;
        long elapsed = clock.getAsLong() - startedNanos;
        return elapsed >= 0L && elapsed < budgetNanos;
    }

    /**
     * Legislated physical OBD request/response pairs for 11-bit CAN.
     *
     * <p>ISO 15765 reserves 7E0-7E7 for tester requests and 7E8-7EF for their
     * paired responses. Keeping the fallback inside that bounded range avoids
     * guessing manufacturer body-module addresses while covering vehicles that
     * expose VIN through a gateway, hybrid controller, or instrument module.</p>
     */
    private static final String[][] UDS_VIN_ECUS_11BIT = {
            {"7E0", "7E8"},
            {"7E1", "7E9"},
            {"7E2", "7EA"},
            {"7E3", "7EB"},
            {"7E4", "7EC"},
            {"7E5", "7ED"},
            {"7E6", "7EE"},
            {"7E7", "7EF"},
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
        // Strip only service + PID. The standard record byte is normally 01
        // and is rejected naturally by extractVinCharacters(). A few adapters
        // omit that record byte; consuming one byte unconditionally would then
        // delete the first VIN character and make an otherwise valid Toyota or
        // Ford response look truncated.
        return parseVinPayloads(response, "49", "4902");
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
     * @param headerPattern exact service/identifier bytes to strip out
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
            // Accept bytes only after this parser's exact positive-response
            // marker. Physical sweeps can encounter adapter chatter, a
            // negative response, or data for another service; a bare
            // 17-character ASCII run is not proof that the ECU returned a VIN.
            int headerIndex = hex.indexOf(headerPattern);
            if (headerIndex < 0) {
                continue;
            }

            // Strip repeated service/identifier markers after the first one.
            // Some non-CAN transports repeat 49 02 for each numbered record,
            // while ISO-TP normally carries it only in the first frame.
            String payloadHex = hex.substring(headerIndex + headerPattern.length())
                    .replace(headerPattern, "");
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
