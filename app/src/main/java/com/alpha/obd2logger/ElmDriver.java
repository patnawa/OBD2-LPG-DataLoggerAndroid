package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class ElmDriver extends BaseDriver {
    private static final String[] FATAL_RESPONSE_MARKERS = {
            "UNABLE TO CONNECT",
            "NO DATA",
            "STOPPED",
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
            sendCommand("ATAT2"); // Aggressive adaptive timing
            sendCommand("ATST19"); // 100ms timeout
            sendCommand("ATSP" + config.obdProtocol.getElmValue());

            // Detect vLinker device and apply firmware-specific optimizations
            vlinkerType = VLinkerOptimizer.detectDevice(this);
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

    protected abstract String sendCommand(String command);

    /**
     * Public method for sending raw OBD2/ELM327 commands (used by DTC reader,
     * VIN reader, readiness monitor). Returns the raw response string.
     */
    public String sendCommandRaw(String command) {
        return sendCommand(command);
    }

    protected Double queryPidResponse(PIDDefinition pidDef, String response) {
        String header = "4" + pidDef.getService().substring(1) + pidDef.getPidHex();
        return PIDParser.extractAndParse(pidDef, response, header);
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
        for (PIDDefinition pid : pids) {
            byMode.computeIfAbsent(pid.getService(), k -> new ArrayList<>()).add(pid);
        }

        // Use vLinker-optimized chunk size (6 for vLinker, 4 for generic clones)
        int chunkSize = VLinkerOptimizer.getRecommendedChunkSize(vlinkerType);

        for (Map.Entry<String, List<PIDDefinition>> entry : byMode.entrySet()) {
            String mode = entry.getKey();
            List<PIDDefinition> modePids = entry.getValue();

            // Group into chunks of chunkSize PIDs per query
            for (int i = 0; i < modePids.size(); i += chunkSize) {
                int end = Math.min(i + chunkSize, modePids.size());
                List<PIDDefinition> chunk = modePids.subList(i, end);

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
                    PIDParser.extractMulti(chunk, response, results);
                }

                // Retry failed PIDs individually (single-PID query) if the batch
                // returned no data for them. This handles vehicles that don't
                // support multi-PID responses well, or where some PIDs in the
                // chunk were not in the response.
                for (PIDDefinition pid : chunk) {
                    if (pid.getPidHex().contains("_")) {
                        continue; // Don't query pseudo-PIDs individually
                    }
                    if (!results.containsKey(pid.getName())) {
                        // Individual retry for this PID
                        String retryCmd = pid.getService() + " " + pid.getPidHex();
                        String retryResp = sendCommand(retryCmd);
                        if (!isFatalElmResponse(retryResp) && retryResp != null && !retryResp.isEmpty()) {
                            PIDParser.extractMulti(
                                java.util.Collections.singletonList(pid),
                                retryResp,
                                results
                            );
                        }
                    }
                }
            }
        }
        return results;
    }
}
