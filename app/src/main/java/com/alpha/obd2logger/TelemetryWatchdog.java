package com.alpha.obd2logger;

import java.util.Map;

/**
 * FDIR-style telemetry liveness supervisor — the structural fix for the
 * "connected but no data" failure class.
 *
 * <p>Every prior bug in that family (3.31.x ATDPN misparse, silent-bus
 * restore) shared one shape: the transport stayed "connected", every poll
 * returned empty, and nothing above the driver noticed. The per-PID layers
 * ({@link PidHealthTracker}, {@link BaseDriver}'s empty-response counter)
 * manage individual PIDs; this class supervises the <em>vehicle link as a
 * whole</em> with an explicit spacecraft-style state machine:
 *
 * <pre>
 *   LIVE ──(no core data × {@link #STALE_CYCLES})──▶ STALE
 *   STALE ─(no core data × {@link #DEAD_CYCLES})──▶ DEAD → recovery demand
 *   any fresh core value ────────────────────────▶ LIVE
 * </pre>
 *
 * <p>"Core data" is Engine RPM — always polled ({@link PidHealthTracker}
 * ALWAYS_POLL), supported by effectively every vehicle, and reported as 0
 * (not null) with ignition on and engine off, so a healthy link is never
 * misread as dead at a red light.
 *
 * <p>Recovery is deliberately NOT implemented here. On DEAD the poll loop
 * throws {@link java.io.IOException}, which routes into the existing,
 * battle-tested reconnect path (backoff → disconnect → full reconnect →
 * protocol re-detect). One recovery machine, not two.
 *
 * <p>Pure Java, no Android dependencies — fully unit-testable. Not
 * thread-safe: owned by one polling worker, like {@link PollingEngine}.
 */
public final class TelemetryWatchdog {

    /** Consecutive coreless cycles before the link is declared STALE. */
    static final int STALE_CYCLES = 5;
    /** Consecutive coreless cycles before DEAD + recovery demand. */
    static final int DEAD_CYCLES = 12;

    public enum State {
        LIVE, STALE, DEAD
    }

    private State state = State.LIVE;
    private int corelessCycles = 0;
    private boolean recoveryDemanded = false;
    /** Set while a demanded recovery is outstanding, to avoid re-demand spam. */
    private boolean recoveryInFlight = false;

    /**
     * Feed one poll cycle's batch. Call exactly once per completed poll.
     *
     * @param batch the poll's PID values keyed by display name (may be empty)
     * @return the state after this observation
     */
    public State observeCycle(Map<String, Double> batch) {
        Double rpm = batch != null ? batch.get("Engine RPM") : null;
        boolean coreFresh = rpm != null && Double.isFinite(rpm);

        if (coreFresh) {
            corelessCycles = 0;
            state = State.LIVE;
            recoveryInFlight = false;
            return state;
        }

        corelessCycles++;
        if (corelessCycles >= DEAD_CYCLES) {
            state = State.DEAD;
            if (!recoveryInFlight) {
                recoveryDemanded = true;
                recoveryInFlight = true;
            }
        } else if (corelessCycles >= STALE_CYCLES) {
            state = State.STALE;
        }
        return state;
    }

    /**
     * True exactly once per DEAD transition — the caller must escalate
     * (throw into its reconnect path). Repeated coreless cycles while the
     * recovery is in flight do not re-demand; a fresh core value re-arms.
     */
    public boolean consumeRecoveryDemand() {
        boolean demanded = recoveryDemanded;
        recoveryDemanded = false;
        return demanded;
    }

    public State getState() {
        return state;
    }

    /** Cycles since the last fresh core value. */
    public int getCorelessCycles() {
        return corelessCycles;
    }

    /**
     * Reset after an external reconnect (new driver/session), so stale
     * pre-reconnect history can't instantly re-kill a fresh link.
     */
    public void reset() {
        state = State.LIVE;
        corelessCycles = 0;
        recoveryDemanded = false;
        recoveryInFlight = false;
    }
}
