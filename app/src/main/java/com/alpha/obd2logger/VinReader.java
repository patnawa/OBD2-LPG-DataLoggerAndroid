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

    /**
     * Bounds the expanded physical ECU sweep.
     *
     * <p>Two real vehicles constrain this number in opposite directions:
     * <ul>
     *   <li>A 2004 Yaris with NO readable VIN — every physical request dies in
     *       an ATSTFF wait (~1.2–2 s each over Bluetooth). The original 30 s
     *       bound let the sweep block the first live poll for half a minute
     *       ("connected but no data"), which is why it was cut.</li>
     *   <li>A 2014 Thai-market Yaris whose VIN exists ONLY via UDS 22 F1 90 in
     *       this very sweep (pre-2016 Asian-market Toyotas implement no Mode
     *       09). The 3 s cut expired during the first ECU's dead 0902 attempt,
     *       so the F190 request holding the VIN was never sent — VIN detection
     *       silently regressed on every UDS-only Toyota.</li>
     * </ul>
     *
     * <p>Three mechanisms together serve both vehicles (see {@link #readVin}):
     * the F190-first sweep order, this worst-case budget, and the
     * consecutive-silent-address early exit ({@link #MAX_CONSECUTIVE_SILENT}),
     * which is what actually bounds the no-VIN car — three addresses with
     * nobody home end the sweep in ~4–6 s without waiting out the budget.
     * {@link Toyota2014YarisVinRegressionTest} locks both directions with
     * realistic per-command latency — do not retune any of the three without
     * running it.
     */
    static final long PHYSICAL_FALLBACK_BUDGET_NANOS = 10_000_000_000L;

    /**
     * Abandon the physical sweep after this many consecutive addresses with no
     * response of any kind. A silent address means nobody is home there; a
     * negative response (7F ...) proves an ECU is alive and resets the count.
     * Legislated VIN sources in practice are 7E0 (engine), 7E1 (TCM) and 7E2
     * (gateway) — three consecutive silents past the last live ECU means the
     * rest of the range is almost certainly empty too.
     */
    static final int MAX_CONSECUTIVE_SILENT = 3;

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
                //
                // ORDER MATTERS UNDER THE TIME BUDGET. By the time this sweep
                // runs, the functional 0902 has already failed twice — so on
                // the vehicles that need the sweep at all (pre-2016 Asian-
                // market Toyotas foremost), Mode 09 usually does not exist and
                // only UDS 22 F190 will answer. The sweep therefore makes a
                // full F190 pass FIRST and only then retries 0902 physically.
                // Interleaving them per-ECU (the old order) spent a dead
                // ATSTFF wait on 0902 at every address before each F190 try,
                // and under the budget that starved the exact request that
                // holds the VIN on UDS-only vehicles.
                int consecutiveSilent = 0;
                boolean anyAlive = false;
                for (String[] ecu : physicalEcus) {
                    if (!isFallbackActive(
                            elm, clock, fallbackStartedNanos, budgetNanos)) return null;
                    response = sendPhysicalWithPendingRetries(
                            elm, "22F190", "22", ecu[0], ecu[1], clock,
                            fallbackStartedNanos, budgetNanos);
                    vin = parseUdsVinResponse(response);
                    if (vin != null) {
                        return vin;
                    }
                    if (isSilent(response)) {
                        // Nobody home at this address. Three in a row and the
                        // remaining range is almost certainly empty — bail out
                        // instead of waiting out the whole budget on a vehicle
                        // that has no VIN to give (the 2004-Yaris case).
                        if (++consecutiveSilent >= MAX_CONSECUTIVE_SILENT) break;
                    } else {
                        consecutiveSilent = 0;
                        anyAlive = true;
                    }
                }
                // The full 0902 physical retry only makes sense when some
                // address proved alive. But "no address answered F190" does
                // not prove the engine ECU is absent — pre-UDS CAN ECUs can
                // ignore service 22 entirely yet still answer a physical Mode
                // 09 (the DirectToyotaVinDriver case). So an all-silent sweep
                // still spends exactly ONE bounded 0902 request on the engine
                // ECU before giving up, which keeps that vehicle class
                // readable at the cost of a single extra dead request on
                // vehicles with no VIN at all.
                String[][] mode09Targets = anyAlive
                        ? physicalEcus : new String[][]{physicalEcus[0]};
                for (String[] ecu : mode09Targets) {
                    if (!isFallbackActive(
                            elm, clock, fallbackStartedNanos, budgetNanos)) return null;
                    response = sendPhysicalWithPendingRetries(
                            elm, "0902", "09", ecu[0], ecu[1], clock,
                            fallbackStartedNanos, budgetNanos);
                    vin = parseVinResponse(response);
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

    /**
     * True when a physical request produced no ECU response at all — adapter
     * chatter like NO DATA / CAN ERROR / '?', or nothing. Any hex payload
     * (positive OR negative response) proves an ECU is alive at the address.
     */
    static boolean isSilent(String response) {
        if (response == null) return true;
        String upper = response.toUpperCase(Locale.US);
        if (upper.contains("NO DATA") || upper.contains("NODATA")
                || upper.contains("CAN ERROR") || upper.contains("ERROR")) {
            return true;
        }
        // Strip prompts/whitespace; any remaining hex means something answered.
        String compact = upper.replaceAll("[^0-9A-F]", "");
        return compact.isEmpty();
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
