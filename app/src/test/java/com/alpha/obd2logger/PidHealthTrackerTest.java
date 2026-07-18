package com.alpha.obd2logger;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PidHealthTrackerTest {
    private static PIDDefinition optionalPid() {
        return new PIDDefinition("Optional", "01", "2F", "%", "A*100/255",
                0, 100, false, 1);
    }

    @Test
    public void transientFailuresEnterCooldownThenRetry() {
        PidHealthTracker tracker = new PidHealthTracker();
        PIDDefinition pid = optionalPid();
        for (long cycle = 1; cycle <= PidHealthTracker.FAILURE_THRESHOLD; cycle++) {
            assertTrue(tracker.shouldPoll(pid, cycle));
            tracker.recordPolled(pid, null, cycle);
        }

        assertFalse(tracker.shouldPoll(pid, PidHealthTracker.FAILURE_THRESHOLD + 1L));
        long retryCycle = PidHealthTracker.FAILURE_THRESHOLD
                + PidHealthTracker.BASE_COOLDOWN_CYCLES;
        assertTrue(tracker.shouldPoll(pid, retryCycle));
    }

    @Test
    public void successfulRetryFullyRecoversPid() {
        PidHealthTracker tracker = new PidHealthTracker();
        PIDDefinition pid = optionalPid();
        for (long cycle = 1; cycle <= PidHealthTracker.FAILURE_THRESHOLD; cycle++) {
            tracker.recordPolled(pid, null, cycle);
        }
        long retryCycle = PidHealthTracker.FAILURE_THRESHOLD
                + PidHealthTracker.BASE_COOLDOWN_CYCLES;
        tracker.recordPolled(pid, 55.0, retryCycle);
        assertTrue(tracker.shouldPoll(pid, retryCycle + 1));
        assertEquals("ok", tracker.statusFor(pid, 55.0, true));
    }

    @Test
    public void coreTelemetryIsNeverDeferred() {
        PIDDefinition rpm = PIDDefinition.findByKey("01_0C");
        PidHealthTracker tracker = new PidHealthTracker();
        for (long cycle = 1; cycle <= 50; cycle++) tracker.recordPolled(rpm, null, cycle);
        assertTrue(tracker.shouldPoll(rpm, 51));
        assertEquals(1, tracker.selectForPoll(Collections.singletonList(rpm), 51).size());
    }

    @Test
    public void admittedLiveMapPidsAreNeverDeferred() {
        String[] keys = {"01_03", "01_05", "01_06", "01_07", "01_0B", "01_0C"};
        PidHealthTracker tracker = new PidHealthTracker();
        for (String key : keys) {
            PIDDefinition pid = PIDDefinition.findByKey(key);
            for (long cycle = 1; cycle <= 50; cycle++) tracker.recordPolled(pid, null, cycle);
            assertTrue(key + " must remain live for fuel-map continuity",
                    tracker.shouldPoll(pid, 51));
        }
    }

    @Test
    public void mapInputsAreScheduledBeforeOptionalDashboardPids() {
        PidHealthTracker tracker = new PidHealthTracker();
        PIDDefinition rpm = PIDDefinition.findByKey("01_0C");
        PIDDefinition map = PIDDefinition.findByKey("01_0B");
        PIDDefinition trim = PIDDefinition.findByKey("01_06");
        PIDDefinition optional = optionalPid();

        List<PIDDefinition> selected = tracker.selectForPoll(
                Arrays.asList(optional, trim, map, rpm), 1);

        assertEquals("01_0C", selected.get(0).key());
        assertEquals("01_0B", selected.get(1).key());
        assertEquals("01_06", selected.get(2).key());
        assertEquals(optional.key(), selected.get(3).key());
    }
}
