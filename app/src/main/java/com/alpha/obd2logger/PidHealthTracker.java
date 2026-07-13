package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adaptive PID polling health policy.
 *
 * <p>A PID is temporarily cooled down after repeated missing responses and is
 * automatically retried later. This replaces permanent session blacklisting
 * after only three nulls, which caused valid but slow/intermittent PIDs to
 * disappear for the rest of a drive.</p>
 */
public final class PidHealthTracker {
    static final int FAILURE_THRESHOLD = 5;
    static final int BASE_COOLDOWN_CYCLES = 10;
    static final int MAX_COOLDOWN_CYCLES = 160;

    private static final Set<String> ALWAYS_POLL = new HashSet<>();
    static {
        ALWAYS_POLL.add("01_0C"); // RPM
        ALWAYS_POLL.add("01_0D"); // speed
        ALWAYS_POLL.add("01_04"); // engine load
        ALWAYS_POLL.add("01_05"); // coolant
        ALWAYS_POLL.add("01_42"); // control module voltage
    }

    private static final class State {
        int consecutiveFailures;
        int suspensions;
        long retryAtCycle;
    }

    private final Map<String, State> states = new HashMap<>();

    public List<PIDDefinition> selectForPoll(List<PIDDefinition> catalogue, long cycle) {
        List<PIDDefinition> selected = new ArrayList<>();
        if (catalogue == null) return selected;

        Set<String> selectedParents = new HashSet<>();
        for (PIDDefinition pid : catalogue) {
            if (pid.getPidHex().contains("_")) continue;
            if (shouldPoll(pid, cycle)) {
                selected.add(pid);
                selectedParents.add(pid.getService() + "_" + pid.getPidHex());
            }
        }
        for (PIDDefinition pid : catalogue) {
            int suffix = pid.getPidHex().indexOf('_');
            if (suffix < 0) continue;
            String parentKey = pid.getService() + "_" + pid.getPidHex().substring(0, suffix);
            if (selectedParents.contains(parentKey) && shouldPoll(pid, cycle)) {
                selected.add(pid);
            }
        }
        return selected;
    }

    public boolean shouldPoll(PIDDefinition pid, long cycle) {
        if (pid == null || ALWAYS_POLL.contains(pid.key())) return true;
        State state = states.get(pid.key());
        return state == null || cycle >= state.retryAtCycle;
    }

    public void recordPolled(PIDDefinition pid, Double value, long cycle) {
        if (pid == null || ALWAYS_POLL.contains(pid.key())) return;
        State state = states.computeIfAbsent(pid.key(), ignored -> new State());
        if (value != null && Double.isFinite(value)) {
            state.consecutiveFailures = 0;
            state.suspensions = 0;
            state.retryAtCycle = 0;
            return;
        }

        state.consecutiveFailures++;
        if (state.consecutiveFailures >= FAILURE_THRESHOLD) {
            state.consecutiveFailures = 0;
            state.suspensions++;
            int shift = Math.min(4, state.suspensions - 1);
            int cooldown = Math.min(MAX_COOLDOWN_CYCLES, BASE_COOLDOWN_CYCLES << shift);
            state.retryAtCycle = cycle + cooldown;
        }
    }

    public String statusFor(PIDDefinition pid, Double value, boolean wasPolled) {
        if (!wasPolled) return "retry_wait";
        return value == null || !Double.isFinite(value) ? "err" : "ok";
    }
}
