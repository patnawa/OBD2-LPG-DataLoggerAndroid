package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Pins the fixed-rate cadence policy. Uses the virtual-time
 * {@code delayUntilNextCycle} entry point so the tests assert scheduling
 * decisions rather than racing real clocks.
 */
public class PollSchedulerTest {

    /**
     * The bug this replaces: sleeping a full interval after the work made the
     * achieved period {@code work + interval}. A 200 ms cycle on a 500 ms
     * setting must sleep 300 ms, not 500 ms.
     */
    @Test
    public void sleepAbsorbsWorkDurationInsteadOfAddingToIt() {
        PollScheduler s = new PollScheduler(500);
        s.markCycleStart(1000);
        assertEquals(300, s.delayUntilNextCycle(1200)); // 200 ms of work absorbed
    }

    /** Successive on-time cycles hold the configured period exactly. */
    @Test
    public void holdsTargetPeriodAcrossCycles() {
        PollScheduler s = new PollScheduler(500);
        long now = 1000;
        s.markCycleStart(now);
        assertEquals(400, s.delayUntilNextCycle(now + 100));

        now = 1500; // slept to deadline
        s.markCycleStart(now);
        assertEquals(500, s.lastPeriodMs());
        assertEquals(Double.valueOf(0.0), s.lastJitterMs());
        assertEquals(0, s.lastOverrunMs());
    }

    /** A cycle slightly over budget polls immediately rather than sleeping. */
    @Test
    public void slightOverrunPollsImmediately() {
        PollScheduler s = new PollScheduler(500);
        s.markCycleStart(1000);
        // Work took 600 ms — past the 1500 deadline but under a full period.
        assertEquals(0, s.delayUntilNextCycle(1600));
        assertEquals(0, s.resyncCount());
    }

    /**
     * Falling a full period behind must resynchronise, not burst. Bursting
     * would queue back-to-back commands onto a half-duplex link and deepen the
     * backlog, and the missed slots are unrecoverable either way.
     */
    @Test
    public void fullPeriodBehindResynchronisesInsteadOfBursting() {
        PollScheduler s = new PollScheduler(500);
        s.markCycleStart(1000);
        // Work took 1.4 s — more than two periods late.
        assertEquals(500, s.delayUntilNextCycle(2400));
        assertEquals(1, s.resyncCount());

        // The new schedule is anchored to the resync, so the next cycle is
        // on time rather than instantly "late" again.
        s.markCycleStart(2900);
        assertEquals(0, s.lastOverrunMs());
    }

    /** Lateness is reported, not silently absorbed. */
    @Test
    public void overrunIsMeasuredAgainstTheDeadline() {
        PollScheduler s = new PollScheduler(500);
        s.markCycleStart(1000);
        s.delayUntilNextCycle(1100);      // deadline now 1500
        s.markCycleStart(1650);           // started 150 ms late
        assertEquals(150, s.lastOverrunMs());
    }

    /** Jitter is the signed deviation of achieved period from target. */
    @Test
    public void jitterIsSignedDeviationFromTarget() {
        PollScheduler s = new PollScheduler(500);
        s.markCycleStart(1000);
        assertNull("no period observed on the first cycle", s.lastJitterMs());

        s.markCycleStart(1620);
        assertEquals(Double.valueOf(120.0), s.lastJitterMs());

        s.markCycleStart(2050);
        assertEquals(Double.valueOf(-70.0), s.lastJitterMs());
    }

    /** A non-positive interval must not turn the worker into a spin loop. */
    @Test
    public void nonPositiveIntervalIsClampedToAFloor() {
        PollScheduler s = new PollScheduler(0);
        assertEquals(1, s.intervalMs());
        s.markCycleStart(1000);
        assertEquals(1, s.delayUntilNextCycle(1000));
    }
}
