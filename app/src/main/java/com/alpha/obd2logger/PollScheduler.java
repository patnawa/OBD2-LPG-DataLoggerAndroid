package com.alpha.obd2logger;

/**
 * Fixed-rate cadence for the logging loop.
 *
 * <p>Both logging loops previously ended each cycle with
 * {@code Thread.sleep(sampleIntervalMs)}, which is sleep-<em>after</em>-work:
 * the achieved period was {@code pollDuration + interval}, so the real sample
 * rate drifted with bus latency and nothing in the log said so. A 500 ms
 * setting against a 180 ms bus actually sampled at ~1.47 Hz, not 2 Hz.
 *
 * <p>This schedules against absolute deadlines instead, so a slow cycle is
 * absorbed by the next sleep rather than added to it, and the deviation is
 * measurable.
 *
 * <p>When a cycle overruns by a full period or more, the schedule is
 * <em>resynchronised</em> rather than allowed to catch up by bursting. Bursting
 * would queue back-to-back commands onto a half-duplex ELM327 link and make the
 * backlog worse, and the missed slots are gone regardless — better to report
 * them as overrun than to pretend they can be recovered.
 *
 * <p>Not thread-safe: one instance per logging session, driven by its worker.
 */
final class PollScheduler {

    private final long intervalMs;

    /** Absolute time the current cycle was due to start; -1 until anchored. */
    private long nextDeadlineMs = -1L;
    private long lastStartMs = -1L;
    private long lastPeriodMs = -1L;
    private long lastOverrunMs = 0L;
    private long resyncCount = 0L;

    PollScheduler(long intervalMs) {
        // A non-positive interval would make every cycle "late" forever and
        // spin the worker; clamp to a sane floor for a half-duplex ELM link.
        this.intervalMs = Math.max(1L, intervalMs);
    }

    long intervalMs() {
        return intervalMs;
    }

    /**
     * Record the start of a cycle. Must be called once per cycle, before the
     * poll, so period and lateness are measured against the intended schedule.
     */
    void markCycleStart(long nowMs) {
        lastPeriodMs = lastStartMs >= 0 ? nowMs - lastStartMs : -1L;
        lastStartMs = nowMs;
        if (nextDeadlineMs < 0) {
            nextDeadlineMs = nowMs; // anchor the schedule to the first cycle
        }
        lastOverrunMs = Math.max(0L, nowMs - nextDeadlineMs);
    }

    /**
     * Sleep until the next scheduled cycle start.
     *
     * @param nowMs time the current cycle finished its work
     * @throws InterruptedException when the session is stopped mid-sleep
     */
    void sleepUntilNextCycle(long nowMs) throws InterruptedException {
        long delay = delayUntilNextCycle(nowMs);
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    /**
     * Advance the schedule and return how long to sleep. Exposed separately from
     * {@link #sleepUntilNextCycle} so the policy is testable without real time.
     */
    long delayUntilNextCycle(long nowMs) {
        if (nextDeadlineMs < 0) {
            nextDeadlineMs = nowMs;
        }
        nextDeadlineMs += intervalMs;
        long delay = nextDeadlineMs - nowMs;

        if (delay <= -intervalMs) {
            // A full period or more behind: give up on the missed slots and
            // restart the cadence from now rather than bursting to catch up.
            resyncCount++;
            nextDeadlineMs = nowMs + intervalMs;
            return intervalMs;
        }
        // Slightly late: poll immediately, the deadline already absorbed it.
        return Math.max(0L, delay);
    }

    /** Measured period of the last completed cycle, or -1 on the first cycle. */
    long lastPeriodMs() {
        return lastPeriodMs;
    }

    /**
     * Signed deviation of the achieved period from the target, or null on the
     * first cycle where no period has been observed yet.
     */
    Double lastJitterMs() {
        return lastPeriodMs < 0 ? null : (double) (lastPeriodMs - intervalMs);
    }

    /** How late the current cycle started against its deadline; 0 when on time. */
    long lastOverrunMs() {
        return lastOverrunMs;
    }

    /** Number of times the schedule had to be resynchronised this session. */
    long resyncCount() {
        return resyncCount;
    }
}
