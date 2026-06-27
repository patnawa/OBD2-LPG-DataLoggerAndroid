package com.alpha.obd2logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseDriver {
    protected final LoggerConfig config;
    protected boolean connected;

    protected BaseDriver(LoggerConfig config) {
        this.config = config;
    }

    public abstract boolean connect();

    public abstract void disconnect();

    public abstract Double queryPid(PIDDefinition pidDef);

    public Map<String, Double> queryPidBatch(List<PIDDefinition> pids) {
        Map<String, Double> results = new LinkedHashMap<>();
        for (PIDDefinition pid : pids) {
            results.put(pid.getName(), queryPid(pid));
        }
        return results;
    }

    public boolean isConnected() {
        return connected;
    }
}
