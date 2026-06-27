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
            "RX ERROR",
            "FB ERROR"
    };

    protected ElmDriver(LoggerConfig config) {
        super(config);
    }

    protected boolean initializeElm327() {
        try {
            sendCommand("ATZ");
            Thread.sleep(500L);
            sendCommand("ATE0");
            sendCommand("ATL0");
            sendCommand("AT S0"); // Spaces off (smaller payload)
            sendCommand("AT AT 2"); // Aggressive adaptive timing
            sendCommand("AT ST 19"); // 100ms timeout (faster failure on unsupported PIDs)
            sendCommand("ATSP" + config.obdProtocol.getElmValue());
            return true;
        } catch (Exception ignored) {
            disconnect();
            return false;
        }
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

        for (Map.Entry<String, List<PIDDefinition>> entry : byMode.entrySet()) {
            String mode = entry.getKey();
            List<PIDDefinition> modePids = entry.getValue();

            // Group into chunks of 6 (ELM327 recommended limit for multi-PID is usually 6 PIDs per frame)
            for (int i = 0; i < modePids.size(); i += 6) {
                int end = Math.min(i + 6, modePids.size());
                List<PIDDefinition> chunk = modePids.subList(i, end);

                StringBuilder cmd = new StringBuilder(mode);
                for (PIDDefinition pid : chunk) {
                    cmd.append(" ").append(pid.getPidHex());
                }

                String response = sendCommand(cmd.toString());
                if (!isFatalElmResponse(response)) {
                    PIDParser.extractMulti(chunk, response, results);
                }
            }
        }
        return results;
    }
}
