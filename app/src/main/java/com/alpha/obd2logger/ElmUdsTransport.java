package com.alpha.obd2logger;

import com.alpha.obd2logger.can.UdsRequest;
import com.alpha.obd2logger.can.UdsResponseDecoder;

import java.util.Locale;
import java.util.Map;

/**
 * Sends one {@link UdsRequest} to one ECU through an ELM327 and decodes the
 * reply.
 *
 * <p>This is the bridge between the ELM's line-oriented ASCII output and
 * {@link UdsResponseDecoder}, which works on raw bytes. Reassembly is delegated
 * to {@link Mode09Reader#compactIsoTpPayloads} so multi-frame answers are
 * grouped by source CAN header rather than concatenated in arrival order.</p>
 *
 * <p><b>Response pending.</b> An ECU that needs longer than P2 answers
 * {@code 7F <service> 78} and sends the real reply afterwards. Depending on
 * adapter and timing, a single read can return the pending frame alone, the
 * real reply alone, or both. Both is the dangerous case: appended to the real
 * payload the pending frame decodes as a negative response and the answer is
 * lost. Pending frames are therefore removed before reassembly, and the request
 * is retried only when nothing else came back.</p>
 *
 * <p><b>Cost note.</b> Each call goes through
 * {@link ElmDriver#sendCommandRawToEcuWithExtendedTimeout}, which reconfigures
 * and then fully restores the adapter profile. That is right for a one-shot
 * read but is roughly a dozen AT commands of overhead per request; a sweep of
 * many identifiers against many modules should batch the setup instead.</p>
 */
final class ElmUdsTransport {

    /** Attempts made when an ECU keeps answering Response Pending. */
    static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** Pause before re-asking an ECU that reported Response Pending. */
    static final long DEFAULT_PENDING_DELAY_MS = 250;

    private static final int NRC_RESPONSE_PENDING = 0x78;

    private ElmUdsTransport() {
    }

    /** Outcome of one request/response exchange. */
    static final class Exchange {
        private final UdsResponseDecoder.DecodedResponse response;
        private final boolean pendingExhausted;
        private final int attempts;
        private final String raw;

        private Exchange(UdsResponseDecoder.DecodedResponse response, boolean pendingExhausted,
                         int attempts, String raw) {
            this.response = response;
            this.pendingExhausted = pendingExhausted;
            this.attempts = attempts;
            this.raw = raw == null ? "" : raw;
        }

        /** Decoded reply. MALFORMED when the ECU said nothing intelligible. */
        UdsResponseDecoder.DecodedResponse getResponse() {
            return response;
        }

        /** True when every attempt returned Response Pending and time ran out. */
        boolean isPendingExhausted() {
            return pendingExhausted;
        }

        int getAttempts() {
            return attempts;
        }

        String getRaw() {
            return raw;
        }

        /** True when the ECU answered this identifier with real data. */
        boolean isPositive() {
            return response != null
                    && response.getKind() == UdsResponseDecoder.Kind.POSITIVE_RESPONSE;
        }

        /**
         * True when the ECU answered, but said it cannot serve this request —
         * which still proves the module exists and speaks UDS.
         */
        boolean isNegative() {
            return response != null
                    && response.getKind() == UdsResponseDecoder.Kind.NEGATIVE_RESPONSE;
        }
    }

    static Exchange request(ElmDriver driver, String txHeader, String rxFilter,
                            UdsRequest request) {
        return request(driver, txHeader, rxFilter, request,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_PENDING_DELAY_MS);
    }

    static Exchange request(ElmDriver driver, String txHeader, String rxFilter,
                            UdsRequest request, int maxAttempts, long pendingDelayMs) {
        if (driver == null || !driver.isConnected() || request == null) {
            return new Exchange(UdsResponseDecoder.decode(new byte[0]), false, 0, "");
        }

        String command = request.toElmCommand();
        String expectedPrefix = request.getExpectedResponsePrefix();
        String raw = "";
        UdsResponseDecoder.DecodedResponse decoded = UdsResponseDecoder.decode(new byte[0]);

        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            raw = driver.sendCommandRawToEcuWithExtendedTimeout(command, txHeader, rxFilter);
            byte[] payload = assemblePayload(raw, rxFilter, expectedPrefix);

            if (payload.length == 0) {
                // Nothing but pending frames (or nothing at all). Only worth
                // re-asking if the ECU actually told us to wait.
                if (!containsResponsePending(raw, rxFilter)) {
                    return new Exchange(UdsResponseDecoder.decode(payload), false, attempt, raw);
                }
                decoded = pendingResponse(request);
                if (attempt < maxAttempts) {
                    pause(pendingDelayMs);
                    continue;
                }
                return new Exchange(decoded, true, attempt, raw);
            }

            decoded = UdsResponseDecoder.decode(payload);
            if (isResponsePending(decoded)) {
                if (attempt < maxAttempts) {
                    pause(pendingDelayMs);
                    continue;
                }
                return new Exchange(decoded, true, attempt, raw);
            }
            return new Exchange(decoded, false, attempt, raw);
        }
        return new Exchange(decoded, isResponsePending(decoded), maxAttempts, raw);
    }

    /**
     * Reassemble the ISO-TP payload this ECU sent, with Response Pending frames
     * removed.
     *
     * @param rxFilter the CAN ID the ECU was told to answer on. Only frames
     *                 from that ID are used: if an adapter ignores ATH1 or
     *                 another module talks over the reply, returning nothing is
     *                 safer than crediting one module with another's data.
     */
    static byte[] assemblePayload(String raw, String rxFilter, String expectedPrefix) {
        if (raw == null || raw.isEmpty() || rxFilter == null) return new byte[0];

        String withoutPending = stripResponsePendingFrames(raw, rxFilter);
        if (withoutPending.trim().isEmpty()) return new byte[0];

        Map<String, StringBuilder> perEcu =
                Mode09Reader.compactIsoTpPayloads(withoutPending, expectedPrefix);
        StringBuilder payload = perEcu.get(rxFilter.toUpperCase(Locale.US));
        return payload == null ? new byte[0] : hexToBytes(payload.toString());
    }

    /** Drop {@code 7F xx 78} single frames, keeping everything else intact. */
    static String stripResponsePendingFrames(String raw, String rxFilter) {
        if (raw == null) return "";
        StringBuilder kept = new StringBuilder();
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (isResponsePendingFrame(line, rxFilter)) continue;
            if (kept.length() > 0) kept.append('\n');
            kept.append(line);
        }
        return kept.toString();
    }

    static boolean containsResponsePending(String raw, String rxFilter) {
        if (raw == null) return false;
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (isResponsePendingFrame(line, rxFilter)) return true;
        }
        return false;
    }

    /**
     * True for a single frame carrying only {@code 7F <service> 78}, with or
     * without a CAN header and with any trailing ELM padding.
     */
    static boolean isResponsePendingFrame(String line, String rxFilter) {
        if (line == null) return false;
        String compact = line.trim().toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        if (compact.isEmpty()) return false;

        if (rxFilter != null) {
            String filter = rxFilter.toUpperCase(Locale.US);
            if (compact.startsWith(filter)) compact = compact.substring(filter.length());
        }
        // Single frame (PCI 0x), negative response 7F, any service, NRC 78,
        // then however many padding bytes the adapter chose to show.
        return compact.matches("^0[0-9A-F]7F[0-9A-F]{2}78(00)*$");
    }

    private static boolean isResponsePending(UdsResponseDecoder.DecodedResponse decoded) {
        return decoded != null
                && decoded.getKind() == UdsResponseDecoder.Kind.NEGATIVE_RESPONSE
                && decoded.getNegativeResponseCode() == NRC_RESPONSE_PENDING;
    }

    /** Synthesise the pending answer the ECU gave us as a bare frame. */
    private static UdsResponseDecoder.DecodedResponse pendingResponse(UdsRequest request) {
        return UdsResponseDecoder.decode(new byte[] {
                (byte) 0x7F, (byte) request.getService(), (byte) NRC_RESPONSE_PENDING });
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        int length = clean.length() / 2;
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static void pause(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
