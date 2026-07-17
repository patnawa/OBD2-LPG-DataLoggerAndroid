package com.alpha.obd2logger.can;

/**
 * Reports controller state supplied by a native CAN driver.
 *
 * <p>It intentionally does not perform reset/restart actions. Recovering a
 * bus-off controller is a privileged hardware operation that must be explicit
 * in a manufacturer-approved transport layer, not hidden in diagnostics code.</p>
 */
public final class CanBusHealthMonitor {
    public enum ControllerState {
        OFFLINE,
        ACTIVE,
        ERROR_WARNING,
        ERROR_PASSIVE,
        BUS_OFF,
        RECOVERING
    }

    public static final class Snapshot {
        private final ControllerState state;
        private final long changedAtNanos;

        Snapshot(ControllerState state, long changedAtNanos) {
            this.state = state;
            this.changedAtNanos = changedAtNanos;
        }

        public ControllerState getState() { return state; }
        public long getChangedAtNanos() { return changedAtNanos; }
        public boolean canCapture() {
            return state == ControllerState.ACTIVE
                    || state == ControllerState.ERROR_WARNING
                    || state == ControllerState.ERROR_PASSIVE;
        }
    }

    private volatile Snapshot snapshot = new Snapshot(ControllerState.OFFLINE, 0L);

    public Snapshot update(ControllerState state, long timestampNanos) {
        if (state == null) throw new IllegalArgumentException("state is required");
        if (timestampNanos < 0) throw new IllegalArgumentException("timestampNanos must be non-negative");
        Snapshot next = new Snapshot(state, timestampNanos);
        snapshot = next;
        return next;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }
}
