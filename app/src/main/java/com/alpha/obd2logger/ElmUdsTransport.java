package com.alpha.obd2logger;

import com.alpha.obd2logger.can.UdsRequest;
import com.alpha.obd2logger.can.UdsResponseDecoder;
import com.alpha.obd2logger.can.UdsDataIdentifier;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

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
 * <p><b>Cost note.</b> A one-shot call goes through
 * {@link ElmDriver#sendCommandRawToEcuWithExtendedTimeout}, which reconfigures
 * and then fully restores the adapter profile. The identification batch keeps
 * the command lock and physical target for the complete standardized DID set,
 * then restores the polling profile once.</p>
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
            return emptyExchange();
        }

        return requestPrepared(command ->
                        driver.sendCommandRawToEcuWithExtendedTimeout(command, txHeader, rxFilter),
                rxFilter, request, maxAttempts, pendingDelayMs);
    }

    /**
     * Read the complete safe ECU-identification set in one physical adapter
     * session.
     *
     * <p>The caller cannot supply commands or identifiers: every data command
     * is built here from {@link UdsDataIdentifier#sweepSet()} through
     * {@link UdsRequest#readDataByIdentifier(int)}. Consequently the only UDS
     * service this batch can transmit is read-only service {@code 0x22}.</p>
     *
     * <p>Setup, physical targeting, and restore are deliberately outside the
     * DID loop. This both avoids hundreds of redundant AT commands and keeps
     * polling from being interleaved while the adapter has a receive filter or
     * physical request header installed.</p>
     */
    static Map<Integer, Exchange> requestIdentificationBatch(
            ElmDriver driver, String txHeader, String rxFilter) {
        return requestIdentificationBatch(driver, txHeader, rxFilter,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_PENDING_DELAY_MS);
    }

    static Map<Integer, Exchange> requestIdentificationBatch(
            ElmDriver driver, String txHeader, String rxFilter,
            int maxAttempts, long pendingDelayMs) {
        return requestIdentificationBatch(driver, txHeader, rxFilter,
                maxAttempts, pendingDelayMs, null);
    }

    interface IdentificationProgressListener {
        void onIdentifierComplete(int completed, int total);
    }

    static Map<Integer, Exchange> requestIdentificationBatch(
            ElmDriver driver, String txHeader, String rxFilter,
            int maxAttempts, long pendingDelayMs,
            IdentificationProgressListener progressListener) {
        LinkedHashMap<Integer, Exchange> exchanges = new LinkedHashMap<>();
        if (driver == null || !driver.isConnected()
                || !isPhysicalHeader(txHeader) || !isPhysicalHeader(rxFilter)
                || txHeader.length() != rxFilter.length()) {
            addEmptyIdentificationExchanges(exchanges);
            return exchanges;
        }

        try {
            driver.commandLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cancelled();
        }
        int normalTimeoutMs = driver.config.connectionTimeoutMs;
        boolean adapterProfileTouched = false;
        try {
            throwIfCancelled();
            if (!driver.isConnected()) {
                throw new IllegalStateException("Adapter disconnected before ECU identification");
            }
            // Match ElmDriver's proven slow/multi-frame request profile, but
            // pay for it once for the whole DID set.
            driver.config.connectionTimeoutMs = Math.max(normalTimeoutMs, 7_000);
            adapterProfileTouched = true;
            driver.sendCommandRaw("ATAL");
            ensureBatchActive(driver);
            driver.sendCommandRaw("ATCAF1");
            ensureBatchActive(driver);
            driver.sendCommandRaw("ATCFC1");
            ensureBatchActive(driver);
            driver.sendCommandRaw("ATH1");
            ensureBatchActive(driver);
            driver.sendCommandRaw("ATS1");
            ensureBatchActive(driver);
            if (!PhysicalAddressing.applyTarget(driver, txHeader, rxFilter)) {
                addEmptyIdentificationExchanges(exchanges);
                return exchanges;
            }
            ensureBatchActive(driver);
            driver.sendCommandRaw("ATAT0");
            ensureBatchActive(driver);
            driver.sendCommandRaw("ATSTFF");
            ensureBatchActive(driver);

            int completed = 0;
            int total = UdsDataIdentifier.sweepSet().size();
            for (int identifier : UdsDataIdentifier.sweepSet()) {
                ensureBatchActive(driver);
                UdsRequest request = UdsRequest.readDataByIdentifier(identifier);
                // sendCommandRaw is safe here because the extended-timeout and
                // physical-address profile is already active under the lock.
                Exchange exchange = requestPrepared(driver::sendCommandRaw,
                        rxFilter, request, maxAttempts, pendingDelayMs);
                exchanges.put(identifier, exchange);
                ensureBatchActive(driver);
                completed++;
                if (progressListener != null) {
                    progressListener.onIdentifierComplete(completed, total);
                    throwIfCancelled();
                }
            }
            return exchanges;
        } finally {
            // Restore the Android-side timeout first so failed cleanup commands
            // cannot inherit the long diagnostic read timeout.
            driver.config.connectionTimeoutMs = normalTimeoutMs;
            boolean restoreInterrupt = Thread.interrupted();
            try {
                if (adapterProfileTouched) {
                    restoreInterrupt |= runRestoreStepUninterruptibly(
                            () -> PhysicalAddressing.restoreFunctional(driver, txHeader));
                    restoreInterrupt |= runRestoreStepUninterruptibly(
                            () -> driver.sendCommandRaw("ATH0"));
                    restoreInterrupt |= runRestoreStepUninterruptibly(
                            () -> driver.sendCommandRaw("ATS0"));
                    restoreInterrupt |= runRestoreStepUninterruptibly(
                            () -> driver.sendCommandRaw("ATAT1"));
                    restoreInterrupt |= runRestoreStepUninterruptibly(
                            () -> driver.sendCommandRaw("ATST32"));
                    restoreInterrupt |= runRestoreStepUninterruptibly(
                            () -> VLinkerOptimizer.applyOptimizations(
                                    driver, driver.vlinkerType, driver.config));
                }
            } finally {
                restoreInterrupt |= Thread.interrupted();
                driver.commandLock.unlock();
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Complete one idempotent adapter-restore step despite cancellation.
     *
     * <p>Serial/BLE command loops deliberately preserve an interrupt flag when
     * their wait is cancelled. A second lifecycle interrupt can therefore
     * arrive after the batch finally block initially cleared the flag and make
     * the remainder of the restore return immediately. Re-running an
     * interrupted AT restore step is safe and guarantees one complete,
     * uninterrupted pass. The caller restores the accumulated interrupt flag
     * after releasing the command lock.</p>
     *
     * @return true when an interrupt was consumed while completing the step
     */
    static boolean runRestoreStepUninterruptibly(Runnable restoreStep) {
        boolean interrupted = false;
        boolean interruptedThisAttempt;
        do {
            interrupted |= Thread.interrupted();
            restoreStep.run();
            interruptedThisAttempt = Thread.interrupted();
            interrupted |= interruptedThisAttempt;
        } while (interruptedThisAttempt);
        return interrupted;
    }

    private static Exchange requestPrepared(CommandSender sender, String rxFilter,
                                            UdsRequest request, int maxAttempts,
                                            long pendingDelayMs) {
        if (sender == null || request == null) return emptyExchange();

        String command = request.toElmCommand();
        String expectedPrefix = request.getExpectedResponsePrefix();
        String raw = "";
        UdsResponseDecoder.DecodedResponse decoded = UdsResponseDecoder.decode(new byte[0]);
        int attemptLimit = Math.max(1, maxAttempts);

        for (int attempt = 1; attempt <= attemptLimit; attempt++) {
            throwIfCancelled();
            raw = sender.send(command);
            throwIfCancelled();
            byte[] payload = assemblePayload(raw, rxFilter, expectedPrefix);

            if (payload.length == 0) {
                // Nothing but pending frames (or nothing at all). Only worth
                // re-asking if the ECU actually told us to wait.
                if (!containsResponsePending(raw, rxFilter)) {
                    return new Exchange(UdsResponseDecoder.decode(payload), false, attempt, raw);
                }
                decoded = pendingResponse(request);
                if (attempt < attemptLimit) {
                    pause(pendingDelayMs);
                    throwIfCancelled();
                    continue;
                }
                return new Exchange(decoded, true, attempt, raw);
            }

            decoded = UdsResponseDecoder.decode(payload);
            if (isResponsePending(decoded)) {
                if (attempt < attemptLimit) {
                    pause(pendingDelayMs);
                    throwIfCancelled();
                    continue;
                }
                return new Exchange(decoded, true, attempt, raw);
            }
            return new Exchange(decoded, false, attempt, raw);
        }
        return new Exchange(decoded, isResponsePending(decoded), attemptLimit, raw);
    }

    private interface CommandSender {
        String send(String command);
    }

    private static boolean isPhysicalHeader(String header) {
        if (header == null) return false;
        String upper = header.toUpperCase(Locale.US);
        return upper.matches("[0-7][0-9A-F]{2}")
                || upper.matches("[0-9A-F]{8}");
    }

    private static void addEmptyIdentificationExchanges(
            Map<Integer, Exchange> exchanges) {
        for (int identifier : UdsDataIdentifier.sweepSet()) {
            exchanges.put(identifier, emptyExchange());
        }
    }

    private static Exchange emptyExchange() {
        return new Exchange(UdsResponseDecoder.decode(new byte[0]), false, 0, "");
    }

    private static void ensureBatchActive(ElmDriver driver) {
        throwIfCancelled();
        if (driver == null || !driver.isConnected()) {
            throw new IllegalStateException("Adapter disconnected during ECU identification");
        }
    }

    private static void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) throw cancelled();
    }

    private static CancellationException cancelled() {
        return new CancellationException("ECU identification cancelled");
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cancelled();
        }
    }
}
