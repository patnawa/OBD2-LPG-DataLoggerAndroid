package com.alpha.obd2logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseDriver {
    protected final LoggerConfig config;
    protected volatile boolean connected;

    /**
     * Consecutive empty-response counter for liveness detection.
     * If sendCommand returns empty/null too many times in a row, we
     * assume the connection is dead (half-open socket, BT radio drop,
     * etc.) and set connected=false so the logger's reconnect path fires.
     */
    private int consecutiveEmptyResponses = 0;
    private static final int MAX_CONSECUTIVE_EMPTIES = 5;

    /**
     * Lock that serializes all OBD2 command I/O. The logger thread and DTC
     * executor thread both call sendCommand() concurrently on the same driver.
     * Without this lock, writes/reads interleave and corrupt OBD responses.
     */
    protected final java.util.concurrent.locks.ReentrantLock commandLock =
            new java.util.concurrent.locks.ReentrantLock();

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

    public String sendCommandRaw(String command) {
        return "";
    }

    /**
     * Drain any bytes that may have accumulated in the transport buffer
     * (e.g. ATZ boot banner, stale prompt from a previous session). Subclasses
     * with a real stream (WiFi, USB, BT) should override this; the default
     * no-op is safe for stateless drivers (Simulation).
     *
     * @param maxMillis total time budget for draining
     */
    protected void drainStaleBytes(long maxMillis) {
        // Default no-op — subclasses override.
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Called by subclass sendCommand implementations to track empty/null
     * responses. After MAX_CONSECUTIVE_EMPTIES in a row, marks the
     * connection as dead so the logger's reconnect logic fires.
     */
    protected void trackResponseLiveness(String response) {
        if (response == null || response.trim().isEmpty()) {
            consecutiveEmptyResponses++;
            if (consecutiveEmptyResponses >= MAX_CONSECUTIVE_EMPTIES && connected) {
                android.util.Log.w("OBD2Logger", "Connection liveness check failed: "
                    + consecutiveEmptyResponses + " consecutive empty responses — marking disconnected");
                connected = false;
            }
        } else {
            consecutiveEmptyResponses = 0;
        }
    }

    /**
     * Reset liveness counter — called by subclasses on successful connect.
     */
    protected void resetLiveness() {
        consecutiveEmptyResponses = 0;
    }

    public boolean isStandardAdapter() {
        return true;
    }

    public String getAdapterDetails() {
        return "Standard Driver";
    }
}
