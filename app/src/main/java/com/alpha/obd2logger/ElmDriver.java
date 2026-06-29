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

    protected ElmDriver(LoggerConfig config) {
        super(config);
    }

    protected boolean initializeElm327() {
        try {
            sendCommand("ATZ");
            Thread.sleep(500L);
            sendCommand("ATE0");  // Echo off
            sendCommand("ATL0"); // Linefeeds off
            sendCommand("ATS0"); // Spaces off (compact response)
            sendCommand("ATH0"); // Headers off — critical! Without this, ELM327 sends
                                 // CAN IDs (7E8, 18DAF110) that confuse the parser when
                                 // spaces are also off. Must be ATH0, not just ATL0.
            sendCommand("ATAL"); // Allow Long messages (>7 data bytes per message).
                                 // CRITICAL for multi-PID batch queries: without this,
                                 // ELM327 defaults to the 7-byte Normal-Length limit and
                                 // TRUNCATES long batch responses, silently dropping
                                 // trailing PIDs (e.g. MAP 0x0B) → empty fuel map. Set
                                 // here as a baseline for ALL adapters (generic + vLinker).
            sendCommand("ATAT2"); // Aggressive adaptive timing
            sendCommand("ATST19"); // 100ms timeout (faster failure on unsupported PIDs)
            sendCommand("ATSP" + config.obdProtocol.getElmValue());

            // Detect vLinker device and apply firmware-specific optimizations
            // (derived from reverse-engineering MIC3322 v2.3.04 and MIC3313 v2.2.92 firmware)
            vlinkerType = VLinkerOptimizer.detectDevice(this);
            VLinkerOptimizer.applyOptimizations(this, vlinkerType, config);

            return true;
        } catch (Exception ignored) {
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
