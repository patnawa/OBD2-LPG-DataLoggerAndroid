package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

public abstract class ElmDriver extends BaseDriver {
    // These markers indicate a genuine adapter/protocol failure that
    // should trigger reconnection logic. "NO DATA" and "STOPPED" are
    // deliberately excluded — they are normal ELM327 responses meaning
    // "ECU has no value for this PID right now", NOT a connection error.
    private static final String[] FATAL_RESPONSE_MARKERS = {
            "UNABLE TO CONNECT",
            "CAN ERROR",
            "BUFFER FULL",
            "BUFFER SMALL", // vLinker uses "BUFFER SMALL" instead of "BUFFER FULL"
            "RX ERROR",
            "FB ERROR"
    };

    /**
     * Detected vLinker device type (set during initializeElm327).
     * Used to optimize multi-PID chunk size and timing.
     */
    protected VLinkerOptimizer.DeviceType vlinkerType = VLinkerOptimizer.DeviceType.UNKNOWN;
    protected boolean isStandard = true;
    protected String adapterDetails = "Generic ELM327";

    /**
     * The concrete protocol the adapter locked on (from ATDPN after the vehicle
     * probe), or null before connection / when the adapter would not say.
     * Unlike {@code config.obdProtocol}, this is never AUTO.
     */
    private volatile ObdProtocol detectedProtocol;

    /**
     * Explicit ATSP ladder walked when the adapter's automatic search fails.
     * Many clones ship a broken ATSP0 implementation yet talk fine once the
     * right bus is forced. Ordered by real-world likelihood: modern CAN first,
     * then K-line, then the legacy J1850 pair.
     */
    private static final String[] PROTOCOL_LADDER = {
            "6", // CAN 11-bit 500k — the vast majority of 2008+ vehicles
            "8", // CAN 11-bit 250k
            "7", // CAN 29-bit 500k
            "9", // CAN 29-bit 250k
            "5", // KWP2000 fast init — pre-CAN Toyota/Isuzu/European
            "3", // ISO 9141-2 — older Asian imports
            "4", // KWP2000 5-baud init
            "1", // J1850 PWM — older Ford
            "2", // J1850 VPW — older GM
    };

    protected ElmDriver(LoggerConfig config) {
        super(config);
    }

    @Override
    public boolean isStandardAdapter() {
        return isStandard;
    }

    @Override
    public String getAdapterDetails() {
        return adapterDetails;
    }

    protected boolean initializeElm327() {
        try {
            // ATZ is destructive: the ELM327 chip resets itself and pushes a
            // multi-line boot banner followed by a `>` prompt. We must wait
            // long enough for that banner to arrive AND drain it from the
            // socket buffer before sending any further command — otherwise
            // ATI/AT@1 would race against the still-arriving boot data and
            // either time out or get garbage mixed into their response
            // (the stray `>` from the boot prompt would prematurely close
            // the recv loop, dropping ATI's actual response and making the
            // adapter look like a non-standard clone).
            String atzRes = sendCommand("ATZ");
            if (atzRes == null || atzRes.trim().isEmpty()) {
                android.util.Log.e("OBD2Logger", "ELM327 ATZ failed — no response from adapter");
                disconnect();
                return false;
            }
            Thread.sleep(1500L);

            // Drain any leftover bytes from the boot (banner + prompt that
            // may have arrived after our read deadline) before probing again.
            drainStaleBytes(500L);

            // Clone check queries
            String atiRes = sendCommand("ATI");
            String at1Res = sendCommand("AT@1");

            if (atiRes == null || atiRes.trim().isEmpty()) {
                android.util.Log.e("OBD2Logger", "ELM327 ATI failed — no response from adapter");
                disconnect();
                return false;
            }

            boolean hasAtiErr = atiRes == null || atiRes.contains("?") || atiRes.trim().isEmpty();
            boolean hasAt1Err = at1Res == null || at1Res.contains("?");
            boolean isVersion21Clone = atiRes != null && atiRes.contains("2.1") && !atiRes.contains("vLinker");

            if (hasAtiErr || hasAt1Err || isVersion21Clone) {
                isStandard = false;
                if (isVersion21Clone) {
                    adapterDetails = "Buggy ELM327 v2.1 Clone";
                } else if (hasAtiErr) {
                    adapterDetails = "Invalid ELM327 Chip";
                } else {
                    adapterDetails = "Non-standard Clone";
                }
            } else {
                isStandard = true;
                adapterDetails = atiRes.trim().replace("\r", " ").replace("\n", " ");
            }

            sendCommand("ATE0");  // Echo off
            sendCommand("ATL0"); // Linefeeds off
            sendCommand("ATS0"); // Spaces off (compact response)
            sendCommand("ATH0"); // Headers off — critical!
            sendCommand("ATAL"); // Allow Long messages
            // Start conservatively. A 100 ms ELM timeout is too short for a
            // number of diesel PCMs (including some Ford powertrains), and can
            // make the adapter appear connected while every OBD request returns
            // NO DATA. Device-specific optimization may shorten this later.
            sendCommand("ATAT1"); // Adaptive timing, conservative first lock
            sendCommand("ATST32"); // 200ms ECU response timeout
            applyConfiguredProtocol();

            // Detect the vLinker device type. The firmware-specific timing
            // optimizations are applied AFTER the vehicle probe below, because
            // probeVehicle()'s finally block restores conservative ATAT1/ATST32
            // and would clobber them.
            vlinkerType = VLinkerOptimizer.detectDevice(this);

            // A working AT command channel only proves that Android reached the
            // adapter. Require a positive Mode 01 response before reporting a
            // vehicle connection; otherwise the UI can say "Connected" forever
            // while all gauges remain empty.
            if (!probeVehicle()) {
                // Last resort for AUTO: many clones have a broken automatic
                // search yet communicate fine once the right bus is forced.
                if (config.obdProtocol != ObdProtocol.AUTO || !tryProtocolLadder()) {
                    android.util.Log.e("OBD2Logger",
                            "ELM327 adapter responded, but vehicle ECU did not answer PID 0100");
                    disconnect();
                    return false;
                }
            }

            // Ask the adapter which protocol it actually locked, remember it
            // for this adapter so the next AUTO connect can try it first, and
            // surface it alongside the adapter identification.
            resolveDetectedProtocol();

            // Apply firmware-specific optimizations now that probeVehicle()
            // has finished restoring its conservative timing.
            VLinkerOptimizer.applyOptimizations(this, vlinkerType, config);

            return true;
        } catch (Exception e) {
            android.util.Log.e("OBD2Logger", "ELM327 initialization error", e);
            disconnect();
            return false;
        }
    }

    /**
     * Get the detected vLinker device type (or GENERIC_ELM327/UNKNOWN).
     */
    public VLinkerOptimizer.DeviceType getVlinkerType() {
        return vlinkerType;
    }

    /**
     * The concrete protocol the adapter locked on during connection, or null
     * before connection / when the adapter would not report one. Never AUTO.
     */
    public ObdProtocol getDetectedProtocol() {
        return detectedProtocol;
    }

    /**
     * Select the configured protocol, seeding AUTO with this adapter's last
     * locked bus when one is remembered.
     *
     * <p>{@code ATSP A6} means "automatic, but try protocol 6 first": a correct
     * hint locks instantly, a stale one (adapter moved to a different car)
     * falls back to the normal full search. Adapters too old for the
     * {@code A<h>} form answer {@code ?}, in which case plain ATSP0 is issued.
     */
    private void applyConfiguredProtocol() {
        if (config.obdProtocol == ObdProtocol.AUTO) {
            String hint = ProtocolMemory.loadHint(config);
            if (hint != null) {
                String response = sendCommand("ATSPA" + hint);
                if (response != null && !response.contains("?")) {
                    return;
                }
            }
        }
        sendCommand("ATSP" + config.obdProtocol.getElmValue());
    }

    /**
     * Read ATDPN once after a successful vehicle probe: cache the concrete
     * protocol, persist it as this adapter's AUTO hint, and append it to the
     * adapter details string shown in the UI.
     */
    private void resolveDetectedProtocol() {
        ObdProtocol resolved = ObdProtocol.fromDpnResponse(sendCommand("ATDPN"));
        detectedProtocol = resolved;
        if (resolved == null) return;
        ProtocolMemory.saveHint(config, resolved);
        android.util.Log.i("OBD2Logger", "Protocol locked: " + resolved.getLabel());
        if (adapterDetails != null && !adapterDetails.contains(resolved.getLabel())) {
            adapterDetails = adapterDetails + " • " + resolved.getLabel();
        }
    }

    /**
     * Force each protocol in turn and probe PID 0100 — the rescue path when
     * automatic search failed. {@code ATTP} (try protocol) is used so a
     * non-responding bus does not overwrite the adapter's stored protocol;
     * only a bus that actually answered is made current with ATSP.
     *
     * @return true when some bus answered 0100 and was selected with ATSP
     */
    private boolean tryProtocolLadder() {
        int normalTimeoutMs = config.connectionTimeoutMs;
        try {
            sendCommand("ATAT0");
            sendCommand("ATSTFF");
            for (String code : PROTOCOL_LADDER) {
                // K-line 5-baud init alone takes ~2.5 s before the first byte;
                // CAN either answers quickly or not at all.
                boolean kLine = "3".equals(code) || "4".equals(code) || "5".equals(code);
                config.connectionTimeoutMs = Math.max(normalTimeoutMs, kLine ? 9_000 : 4_000);

                String tryResponse = sendCommand("ATTP" + code);
                if (tryResponse != null && tryResponse.contains("?")) {
                    continue; // adapter does not implement this protocol
                }
                String response = sendCommand("0100");
                if (PidAvailabilityChecker.hasPositiveResponse(response, "4100")) {
                    sendCommand("ATSP" + code);
                    android.util.Log.i("OBD2Logger",
                            "Protocol ladder rescued connection on ATSP" + code);
                    return true;
                }
            }
            // Leave the adapter in automatic mode rather than on the last
            // failed rung, so a later manual retry starts from a sane state.
            sendCommand("ATSP0");
            return false;
        } finally {
            config.connectionTimeoutMs = normalTimeoutMs;
            sendCommand("ATAT1");
            sendCommand("ATST32");
        }
    }

    protected abstract String sendCommand(String command);

    /**
     * Upper bound for Mode-01 PIDs sent in one request on this transport.
     * Classic serial/Wi-Fi can use the adapter optimum; BLE must stay below
     * the default ATT payload until an MTU upgrade is known to be complete.
     */
    protected int getTransportPidChunkLimit() {
        return Integer.MAX_VALUE;
    }

    /**
     * Public method for sending raw OBD2/ELM327 commands (used by DTC reader,
     * VIN reader, readiness monitor). Returns the raw response string.
     */
    public String sendCommandRaw(String command) {
        return sendCommand(command);
    }

    /**
     * Run one slow/multi-frame diagnostic request with an extended ECU timeout.
     *
     * <p>Mode 09 VIN responses are much slower than ordinary Mode 01 polling on
     * some ECUs.  The retry also exposes CAN headers and spaces: with headers
     * off, an ELM-compatible adapter is allowed to reformat a multi-frame reply
     * into numbered data lines, but some vLinker firmware revisions lose part of
     * that formatted reply.  Raw ISO-TP frames give {@link VinReader} the exact
     * first/consecutive-frame boundaries to reassemble.</p>
     *
     * <p>The entire setup/request/restore sequence holds the reentrant command
     * lock so the logger cannot interleave a PID request after ATSTFF. Polling
     * formatting and timing are restored before returning.</p>
     */
    String sendCommandRawWithExtendedTimeout(String command) {
        return sendCommandRawWithExtendedTimeout(
                command, null, null, System::nanoTime, 0L, Long.MAX_VALUE);
    }

    /**
     * Run a slow multi-frame request against one 11-bit CAN ECU rather than
     * the functional broadcast address. This is a VIN fallback for ECUs that
     * answer ordinary Mode 01 broadcasts but ignore functional Mode 09.
     */
    String sendCommandRawToEcuWithExtendedTimeout(String command, String txHeader,
                                                   String rxFilter) {
        return sendCommandRawToEcuWithExtendedTimeout(
                command, txHeader, rxFilter, System::nanoTime, 0L, Long.MAX_VALUE);
    }

    /**
     * VIN-sweep variant that clamps every setup/data command to the remaining
     * monotonic budget. Mandatory polling-profile restore is deliberately
     * outside that budget so cancellation cannot strand a receive filter or
     * physical header on the adapter.
     */
    String sendCommandRawToEcuWithExtendedTimeout(
            String command, String txHeader, String rxFilter,
            LongSupplier monotonicClock, long startedNanos, long budgetNanos) {
        // 3 hex chars = 11-bit CAN ID, 8 hex chars = 29-bit (18DAxxF1 form).
        if (!isPhysicalHeader(txHeader) || !isPhysicalHeader(rxFilter)) {
            return "";
        }
        return sendCommandRawWithExtendedTimeout(
                command, txHeader, rxFilter, monotonicClock,
                startedNanos, budgetNanos);
    }

    private static boolean isPhysicalHeader(String header) {
        return header != null && (header.length() == 3 || header.length() == 8);
    }

    private String sendCommandRawWithExtendedTimeout(
            String command, String txHeader, String rxFilter,
            LongSupplier monotonicClock, long startedNanos, long budgetNanos) {
        try {
            commandLock.lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        }
        int normalTimeoutMs = config.connectionTimeoutMs;
        int extendedTimeoutMs = Math.max(normalTimeoutMs, 7_000);
        boolean physicalAddressing = txHeader != null && rxFilter != null;
        boolean adapterProfileTouched = false;
        try {
            if (!prepareExtendedRequestStep(
                    monotonicClock, startedNanos, budgetNanos,
                    extendedTimeoutMs)) return "";
            adapterProfileTouched = true;
            sendCommand("ATAL");
            if (!prepareExtendedRequestStep(
                    monotonicClock, startedNanos, budgetNanos,
                    extendedTimeoutMs)) return "";
            sendCommand("ATCAF1");
            if (!prepareExtendedRequestStep(
                    monotonicClock, startedNanos, budgetNanos,
                    extendedTimeoutMs)) return "";
            sendCommand("ATCFC1");
            if (!prepareExtendedRequestStep(
                    monotonicClock, startedNanos, budgetNanos,
                    extendedTimeoutMs)) return "";
            // VIN is an ISO-TP multi-frame response.  Preserve the CAN ID and
            // PCI byte during this one request so parser behavior does not rely
            // on adapter-specific automatic formatting (0:/1:/2: lines).
            sendCommand("ATH1");
            if (!prepareExtendedRequestStep(
                    monotonicClock, startedNanos, budgetNanos,
                    extendedTimeoutMs)) return "";
            sendCommand("ATS1");
            if (!prepareExtendedRequestStep(
                    monotonicClock, startedNanos, budgetNanos,
                    extendedTimeoutMs)) return "";
            if (physicalAddressing && !applyPhysicalTargetWithinBudget(
                    txHeader, rxFilter, monotonicClock, startedNanos,
                    budgetNanos, extendedTimeoutMs)) {
                return "";
            }
            if (!prepareExtendedRequestStep(
                    monotonicClock, startedNanos, budgetNanos,
                    extendedTimeoutMs)) return "";
            sendCommand("ATAT0");
            if (!prepareExtendedRequestStep(
                    monotonicClock, startedNanos, budgetNanos,
                    extendedTimeoutMs)) return "";
            sendCommand("ATSTFF"); // 0xFF * 4 ms = about 1.02 s maximum ECU wait
            if (!prepareExtendedRequestStep(
                    monotonicClock, startedNanos, budgetNanos,
                    extendedTimeoutMs)) return "";
            return sendCommand(command);
        } finally {
            // Restore the Android-side timeout first so a failed AT command does
            // not block cleanup for seven seconds.
            config.connectionTimeoutMs = normalTimeoutMs;
            boolean restoreInterrupt = Thread.interrupted();
            try {
                if (adapterProfileTouched && physicalAddressing) {
                    // Restore automatic reception and the broadcast header
                    // before the next live PID request.
                    restoreInterrupt |= ElmUdsTransport.runRestoreStepUninterruptibly(
                            () -> PhysicalAddressing.restoreFunctional(this, txHeader));
                }
                if (adapterProfileTouched) {
                    // Serial and BLE transports preserve lifecycle interrupts.
                    // Finish every idempotent restore step with the flag
                    // temporarily cleared, then put it back for the caller.
                    restoreInterrupt |= ElmUdsTransport.runRestoreStepUninterruptibly(
                            () -> sendCommand("ATH0"));
                    restoreInterrupt |= ElmUdsTransport.runRestoreStepUninterruptibly(
                            () -> sendCommand("ATS0"));
                    restoreInterrupt |= ElmUdsTransport.runRestoreStepUninterruptibly(
                            () -> sendCommand("ATAT1"));
                    restoreInterrupt |= ElmUdsTransport.runRestoreStepUninterruptibly(
                            () -> sendCommand("ATST32"));
                    restoreInterrupt |= ElmUdsTransport.runRestoreStepUninterruptibly(
                            () -> VLinkerOptimizer.applyOptimizations(
                                    this, vlinkerType, config));
                }
            } finally {
                restoreInterrupt |= Thread.interrupted();
                commandLock.unlock();
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isExtendedRequestActive() {
        return isConnected() && !Thread.currentThread().isInterrupted();
    }

    /** Prepare one bounded setup/data command and clamp its transport timeout. */
    private boolean prepareExtendedRequestStep(
            LongSupplier clock, long startedNanos, long budgetNanos,
            int extendedTimeoutMs) {
        if (!isExtendedRequestActive()) return false;
        if (budgetNanos == Long.MAX_VALUE) {
            config.connectionTimeoutMs = extendedTimeoutMs;
            return true;
        }
        LongSupplier safeClock = clock == null ? System::nanoTime : clock;
        long elapsedNanos = safeClock.getAsLong() - startedNanos;
        if (elapsedNanos < 0L || elapsedNanos >= budgetNanos) return false;
        long remainingNanos = budgetNanos - elapsedNanos;
        long remainingMillis = remainingNanos / 1_000_000L;
        if (remainingNanos % 1_000_000L != 0L) remainingMillis++;
        config.connectionTimeoutMs = (int) Math.max(1L,
                Math.min((long) extendedTimeoutMs, remainingMillis));
        return true;
    }

    /** Physical targeting with a deadline check before every ELM command. */
    private boolean applyPhysicalTargetWithinBudget(
            String txHeader, String rxFilter, LongSupplier clock,
            long startedNanos, long budgetNanos, int extendedTimeoutMs) {
        if (!prepareExtendedRequestStep(
                clock, startedNanos, budgetNanos, extendedTimeoutMs)) return false;
        String setHeader = sendCommand("ATSH" + txHeader);
        if (setHeader != null && setHeader.contains("?")) {
            if (txHeader.length() != 8) return false;
            if (!prepareExtendedRequestStep(
                    clock, startedNanos, budgetNanos, extendedTimeoutMs)) return false;
            String setPriority = sendCommand("ATCP" + txHeader.substring(0, 2));
            if (setPriority != null && setPriority.contains("?")) return false;
            if (!prepareExtendedRequestStep(
                    clock, startedNanos, budgetNanos, extendedTimeoutMs)) return false;
            setHeader = sendCommand("ATSH" + txHeader.substring(2));
            if (setHeader != null && setHeader.contains("?")) return false;
        }
        if (!prepareExtendedRequestStep(
                clock, startedNanos, budgetNanos, extendedTimeoutMs)) return false;
        sendCommand("ATCRA" + rxFilter);
        return true;
    }

    /** True only when ATDPN reports an active 11-bit CAN protocol. */
    static boolean isElevenBitCanProtocol(String atDpnResponse) {
        if (atDpnResponse == null || atDpnResponse.isEmpty()) return false;
        String[] lines = atDpnResponse.toUpperCase(java.util.Locale.US)
                .replace('\r', '\n').split("\\n");
        for (String line : lines) {
            String code = line.replaceAll("[^0-9A-F]", "");
            // ATDPN returns A6/A8 when auto selected CAN 11-bit, or 6/8
            // when those protocols were selected explicitly. B is User1 CAN.
            if ("6".equals(code) || "8".equals(code)
                    || "A6".equals(code) || "A8".equals(code)
                    || "B".equals(code) || "AB".equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** True only when ATDPN reports an active 29-bit ISO 15765-4 CAN protocol. */
    static boolean isTwentyNineBitCanProtocol(String atDpnResponse) {
        if (atDpnResponse == null || atDpnResponse.isEmpty()) return false;
        String[] lines = atDpnResponse.toUpperCase(java.util.Locale.US)
                .replace('\r', '\n').split("\\n");
        for (String line : lines) {
            String code = line.replaceAll("[^0-9A-F]", "");
            // 7/9 are the legislated 29-bit OBD CAN variants. J1939 ("A") is
            // deliberately excluded — its addressing is not ISO-TP UDS.
            if ("7".equals(code) || "9".equals(code)
                    || "A7".equals(code) || "A9".equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** Restore normal PID polling after a DTC/deep-bus scan. */
    void restorePollingState() {
        // ATD clears receive filters, custom headers, programmable-bus state,
        // and scan-only options without performing the long hardware reset.
        sendCommand("ATD");
        sendCommand("ATE0");
        sendCommand("ATL0");
        sendCommand("ATS0");
        sendCommand("ATH0");
        sendCommand("ATAL");
        sendCommand("ATCFC1");
        sendCommand("ATAT1");
        sendCommand("ATST32");
        // Re-select the protocol that this session actually locked on rather
        // than re-running a full AUTO search after every deep scan — and, for
        // clones whose automatic search is broken, ATSP0 here would never
        // reconnect at all.
        ObdProtocol resolved = detectedProtocol;
        if (config.obdProtocol == ObdProtocol.AUTO && resolved != null) {
            sendCommand("ATSP" + resolved.getElmValue());
        } else {
            sendCommand("ATSP" + config.obdProtocol.getElmValue());
        }

        probeVehicle();
        // Re-apply safe performance settings for known vLinker hardware —
        // after probeVehicle(), whose finally block restores conservative
        // ATAT1/ATST32 and would otherwise clobber them.
        VLinkerOptimizer.applyOptimizations(this, vlinkerType, config);
    }

    private boolean probeVehicle() {
        // ATSP0 may need to try every supported bus before it finds the car.
        // ATSTFF only extends the ELM's ECU wait; each transport also enforces
        // config.connectionTimeoutMs, whose 2 s default previously cut off the
        // SEARCHING response on slow Ford diesel and legacy K-line vehicles.
        int normalTimeoutMs = config.connectionTimeoutMs;
        if (config.obdProtocol == ObdProtocol.AUTO) {
            config.connectionTimeoutMs = Math.max(normalTimeoutMs, 12_000);
        }

        try {
            String response = sendCommand("0100");
            if (PidAvailabilityChecker.hasPositiveResponse(response, "4100")) {
                return true;
            }

            // Retry once with the maximum ELM timeout. The longer Android-side
            // timeout above is equally important: otherwise this response is
            // abandoned before auto protocol discovery can finish.
            sendCommand("ATAT0");
            sendCommand("ATSTFF");
            response = sendCommand("0100");
            return PidAvailabilityChecker.hasPositiveResponse(response, "4100");
        } finally {
            // Restore the Android-side timeout before issuing cleanup commands;
            // a missing ELM prompt must not make each cleanup wait 12 seconds.
            config.connectionTimeoutMs = normalTimeoutMs;
            sendCommand("ATAT1");
            sendCommand("ATST32");
        }
    }

    protected Double queryPidResponse(PIDDefinition pidDef, String response) {
        // Positive OBD response = request service + 0x40.  Building it as
        // "4" + the last character only worked for Mode 01..0F and made
        // manufacturer services such as 22 F405 look for 42F405 instead of
        // the real 62F405 response.
        String header = positiveResponseService(pidDef.getService()) + pidDef.getPidHex();
        return PIDParser.extractAndParse(pidDef, response, header);
    }

    private static String positiveResponseService(String service) {
        try {
            return String.format(java.util.Locale.US, "%02X",
                    Integer.parseInt(service, 16) + 0x40);
        } catch (Exception ignored) {
            return "";
        }
    }

    protected boolean isFatalElmResponse(String response) {
        if (response == null) {
            return false;
        }
        String normalized = response.toUpperCase();
        for (String marker : FATAL_RESPONSE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }
    @Override
    public Map<String, Double> queryPidBatch(List<PIDDefinition> pids) {
        Map<String, Double> results = new LinkedHashMap<>();

        // Group PIDs by Service (Mode)
        Map<String, List<PIDDefinition>> byMode = new HashMap<>();
        Map<String, List<PIDDefinition>> pseudoByMode = new HashMap<>();
        List<PIDDefinition> individualOnly = new ArrayList<>();
        for (PIDDefinition pid : pids) {
            // Multi-PID responses identify standard PIDs with one byte. A
            // manufacturer DID (for example 22 F405) has a multi-byte ID,
            // so send it individually and parse its complete positive header.
            // Keep pseudo PIDs in the regular chunk; they are derived from a
            // parent PID response and must never be sent on their own.
            if (pid.getPidHex().contains("_")) {
                pseudoByMode.computeIfAbsent(pid.getService(), k -> new ArrayList<>()).add(pid);
            } else if (pid.getPidHex().length() != 2) {
                individualOnly.add(pid);
            } else {
                byMode.computeIfAbsent(pid.getService(), k -> new ArrayList<>()).add(pid);
            }
        }

        // Use vLinker-optimized chunk size (6 for vLinker, 4 for generic clones)
        int chunkSize = Math.min(VLinkerOptimizer.getRecommendedChunkSize(vlinkerType),
                getTransportPidChunkLimit());

        for (Map.Entry<String, List<PIDDefinition>> entry : byMode.entrySet()) {
            String mode = entry.getKey();
            List<PIDDefinition> modePids = entry.getValue();

            // Group into chunks of chunkSize PIDs per query
            for (int i = 0; i < modePids.size(); i += chunkSize) {
                int end = Math.min(i + chunkSize, modePids.size());
                List<PIDDefinition> chunk = modePids.subList(i, end);
                List<PIDDefinition> parseChunk = new ArrayList<>(chunk);
                for (PIDDefinition pseudo : pseudoByMode.getOrDefault(mode,
                        java.util.Collections.emptyList())) {
                    String parent = pseudo.getPidHex().substring(0, pseudo.getPidHex().indexOf('_'));
                    for (PIDDefinition real : chunk) {
                        if (real.getPidHex().equalsIgnoreCase(parent)) {
                            parseChunk.add(pseudo);
                            break;
                        }
                    }
                }

                // Build command with only real PIDs (skip pseudo-PIDs like "14_B"
                // which are not valid hex and would cause ELM327 to reject the
                // entire command).  Pseudo-PIDs are still in the chunk list so
                // that extractMulti can parse them from their parent's data.
                StringBuilder cmd = new StringBuilder(mode);
                for (PIDDefinition pid : chunk) {
                    if (!pid.getPidHex().contains("_")) {
                        cmd.append(" ").append(pid.getPidHex());
                    }
                }

                String response = sendCommand(cmd.toString());
                if (!isFatalElmResponse(response) && response != null && !response.isEmpty()) {
                    PIDParser.extractMulti(parseChunk, response, results);
                }

                // Retry only missing fuel-map inputs individually.  Retrying
                // every absent optional PID turns one flaky Bluetooth ELM batch
                // into dozens of serial round trips.  That starves the next map
                // sample and then its RPM/MAP/trim values are too far apart for
                // the stability gate to accept. Optional failures are reported
                // to PidHealthTracker below, where they cool down and retry
                // later; the map inputs must instead be recovered immediately
                // so every learned cell is based on a complete, coherent set.
                for (PIDDefinition pid : chunk) {
                    if (pid.getPidHex().contains("_")) {
                        continue; // Don't query pseudo-PIDs individually
                    }
                    if (PidHealthTracker.isMapInput(pid)
                            && !results.containsKey(pid.getName())) {
                        // Individual retry for this PID
                        String retryCmd = pid.getService() + " " + pid.getPidHex();
                        String retryResp = sendCommand(retryCmd);
                        if (!isFatalElmResponse(retryResp) && retryResp != null && !retryResp.isEmpty()) {
                            List<PIDDefinition> retryParse = new ArrayList<>();
                            retryParse.add(pid);
                            for (PIDDefinition pseudo : pseudoByMode.getOrDefault(mode,
                                    java.util.Collections.emptyList())) {
                                if (pseudo.getPidHex().startsWith(pid.getPidHex() + "_")) {
                                    retryParse.add(pseudo);
                                }
                            }
                            PIDParser.extractMulti(retryParse, retryResp, results);
                        }
                    }
                }
            }
        }
        for (PIDDefinition pid : individualOnly) {
            Double value = queryPid(pid);
            if (value != null) results.put(pid.getName(), value);
        }
        return results;
    }
}
