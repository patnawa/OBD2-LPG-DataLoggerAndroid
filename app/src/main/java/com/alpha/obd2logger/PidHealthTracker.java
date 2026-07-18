package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.Comparator;
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
        // Live fuel-map safety/learning inputs need consecutive samples. Once
        // ECU capability discovery has admitted them to the poll catalogue,
        // never put them into adaptive cooldown or the map appears to freeze.
        ALWAYS_POLL.add("01_03"); // fuel system / closed-loop status
        ALWAYS_POLL.add("01_06"); // STFT bank 1
        ALWAYS_POLL.add("01_07"); // LTFT bank 1
        ALWAYS_POLL.add("01_0B"); // intake manifold pressure
        ALWAYS_POLL.add("01_11"); // throttle: reject pedal transients promptly
        ALWAYS_POLL.add("01_42"); // control module voltage
    }

    /**
     * Ordered inputs that must describe the same near-instant of engine
     * operation before a fuel-map sample can be trusted.  They are issued
     * ahead of gauges and optional diagnostics, which matters on Bluetooth
     * adapters where every ELM request is a serial round trip.
     */
    private static final String[] MAP_INPUT_ORDER = {
            "01_0C", // RPM
            "01_0B", // MAP (or Engine Load is used to synthesize it)
            "01_04", // engine load
            "01_03", // closed-loop status
            "01_05", // coolant temperature
            "01_06", // STFT B1
            "01_07", // LTFT B1
            "01_11"  // throttle position
    };

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
        // Keep the batch order deterministic, but put the map's mutually
        // dependent inputs first.  A real Bluetooth ELM may take hundreds of
        // milliseconds to walk optional PIDs (or time them out), and sampling
        // RPM/trim/MAP at opposite ends of that walk creates a value that is
        // both late and physically incoherent. Simulation does not expose that
        // transport cost because all of its values arrive immediately.
        selected.sort(Comparator.comparingInt(PidHealthTracker::pollPriority));
        return selected;
    }

    /** Package-visible so ElmDriver can apply the same recovery policy. */
    static boolean isMapInput(PIDDefinition pid) {
        if (pid == null) return false;
        String key = pid.key();
        for (String mapKey : MAP_INPUT_ORDER) {
            if (mapKey.equals(key)) return true;
        }
        return false;
    }

    private static int pollPriority(PIDDefinition pid) {
        if (pid == null) return Integer.MAX_VALUE;
        String key = pid.key();
        for (int i = 0; i < MAP_INPUT_ORDER.length; i++) {
            if (MAP_INPUT_ORDER[i].equals(key)) return i;
        }
        // Keep always-on dashboard/safety inputs ahead of optional data, but
        // after the coherent map group.
        return ALWAYS_POLL.contains(key) ? MAP_INPUT_ORDER.length : MAP_INPUT_ORDER.length + 1;
    }

    public boolean shouldPoll(PIDDefinition pid, long cycle) {
        if (pid == null || isAlwaysPoll(pid)) return true;
        State state = states.get(pid.key());
        return state == null || cycle >= state.retryAtCycle;
    }

    public void recordPolled(PIDDefinition pid, Double value, long cycle) {
        if (pid == null || isAlwaysPoll(pid)) return;
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

    private static boolean isAlwaysPoll(PIDDefinition pid) {
        return pid != null && ALWAYS_POLL.contains(pid.key());
    }
}
