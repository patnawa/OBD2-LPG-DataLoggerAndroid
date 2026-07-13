package com.alpha.obd2logger;

/**
 * Explicit connection failure returned by automatic transport discovery.
 *
 * <p>Production logging must never silently fall back to simulated telemetry:
 * that makes generated data look like it came from the vehicle. Simulation is
 * available only when the user explicitly selects the SIM transport.</p>
 */
public final class UnavailableDriver extends BaseDriver {
    private final String reason;

    public UnavailableDriver(LoggerConfig config, String reason) {
        super(config != null ? config : new LoggerConfig());
        this.reason = reason == null || reason.trim().isEmpty()
                ? "No compatible OBD2 adapter was detected"
                : reason.trim();
    }

    @Override
    public boolean connect() {
        connected = false;
        return false;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public Double queryPid(PIDDefinition pidDef) {
        return null;
    }

    @Override
    public boolean isStandardAdapter() {
        return false;
    }

    @Override
    public String getAdapterDetails() {
        return reason;
    }
}
