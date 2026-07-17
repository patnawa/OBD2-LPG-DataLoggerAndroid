package com.alpha.obd2logger.can;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Passive timing-based CAN anomaly detector.
 *
 * <p>Signals are triage hints, not proof that a frame is malicious. The
 * detector never blocks, alters, or transmits a frame; it only compares each
 * ID's observed period with its learned EWMA baseline.</p>
 */
public final class CanAnomalyDetector {
    public enum SignalType {
        FREQUENCY_SPIKE,
        FREQUENCY_GAP,
        NON_MONOTONIC_TIMESTAMP
    }

    public static final class Signal {
        private final SignalType type;
        private final int arbitrationId;
        private final long observedIntervalNanos;
        private final double expectedIntervalNanos;

        Signal(SignalType type, int arbitrationId, long observedIntervalNanos,
               double expectedIntervalNanos) {
            this.type = type;
            this.arbitrationId = arbitrationId;
            this.observedIntervalNanos = observedIntervalNanos;
            this.expectedIntervalNanos = expectedIntervalNanos;
        }

        public SignalType getType() { return type; }
        public int getArbitrationId() { return arbitrationId; }
        public long getObservedIntervalNanos() { return observedIntervalNanos; }
        public double getExpectedIntervalNanos() { return expectedIntervalNanos; }
    }

    private static final class TimingState {
        long lastTimestampNanos = -1;
        double ewmaIntervalNanos;
        int learnedIntervals;
    }

    private static final int MIN_LEARNED_INTERVALS = 4;
    private static final double SPIKE_FACTOR = 0.40;
    private static final double GAP_FACTOR = 2.50;
    private static final double EWMA_ALPHA = 0.20;

    private final Map<Long, TimingState> states = new HashMap<>();

    public List<Signal> observe(CanFrame frame) {
        if (frame == null) return Collections.emptyList();
        TimingState state = states.computeIfAbsent(frame.sessionKey(), ignored -> new TimingState());
        if (state.lastTimestampNanos < 0) {
            state.lastTimestampNanos = frame.getTimestampNanos();
            return Collections.emptyList();
        }

        long delta = frame.getTimestampNanos() - state.lastTimestampNanos;
        state.lastTimestampNanos = frame.getTimestampNanos();
        if (delta <= 0) {
            return Collections.singletonList(new Signal(SignalType.NON_MONOTONIC_TIMESTAMP,
                    frame.getArbitrationId(), delta, state.ewmaIntervalNanos));
        }

        List<Signal> signals = new ArrayList<>(1);
        boolean anomalous = false;
        if (state.learnedIntervals >= MIN_LEARNED_INTERVALS) {
            if (delta < state.ewmaIntervalNanos * SPIKE_FACTOR) {
                signals.add(new Signal(SignalType.FREQUENCY_SPIKE, frame.getArbitrationId(),
                        delta, state.ewmaIntervalNanos));
                anomalous = true;
            } else if (delta > state.ewmaIntervalNanos * GAP_FACTOR) {
                signals.add(new Signal(SignalType.FREQUENCY_GAP, frame.getArbitrationId(),
                        delta, state.ewmaIntervalNanos));
                anomalous = true;
            }
        }

        // Do not let one anomalous interval redefine the normal traffic baseline.
        if (!anomalous) {
            if (state.learnedIntervals == 0) state.ewmaIntervalNanos = delta;
            else state.ewmaIntervalNanos = EWMA_ALPHA * delta
                    + (1.0 - EWMA_ALPHA) * state.ewmaIntervalNanos;
            state.learnedIntervals++;
        }
        return signals.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(signals);
    }

    public void clear() {
        states.clear();
    }
}
