package com.alpha.obd2logger;

/**
 * Pure, JVM-testable performance timer fed one (elapsedS, speedKmh) sample per
 * poll cycle. Computes 0–100 km/h, 80–120 km/h and standing quarter-mile times.
 *
 * <h3>Method</h3>
 * <ul>
 *   <li>Crossing times are linearly interpolated between the two samples that
 *       straddle a threshold, so the 2 Hz default poll rate quantizes far less
 *       than one sample period.</li>
 *   <li>The quarter-mile distance is integrated trapezoidally from launch and
 *       the finish time interpolated at 402.336 m.</li>
 *   <li>A gap of more than {@link #MAX_GAP_S} between samples aborts any run in
 *       progress: interpolating across a dropout would fabricate a time.</li>
 * </ul>
 *
 * Not thread-safe; one instance per session, fed from the record callback.
 */
public final class PerformanceTimerEngine {

    public enum RunType { ZERO_TO_100, EIGHTY_TO_120, QUARTER_MILE }

    /** Speed at or below this counts as standing still (1 km/h PID resolution). */
    public static final double STANDSTILL_KMH = 0.5;
    /** Dropout guard: samples further apart than this abort active runs. */
    public static final double MAX_GAP_S = 3.0;
    public static final double QUARTER_MILE_M = 402.336;
    /** Rolling-start run resets if speed falls this far back below its arm threshold. */
    private static final double ROLLING_HYSTERESIS_KMH = 2.0;

    /** Immutable completed-run result. */
    public static final class RunResult {
        public final RunType type;
        public final double timeS;
        /** Trap speed at the finish line; only meaningful for QUARTER_MILE. */
        public final double endSpeedKmh;

        RunResult(RunType type, double timeS, double endSpeedKmh) {
            this.type = type;
            this.timeS = timeS;
            this.endSpeedKmh = endSpeedKmh;
        }
    }

    private Double prevT;
    private Double prevSpeed;

    // Standing-start state (0-100 and quarter mile share one launch).
    private boolean launchArmed;
    private double launchT;
    private boolean run100Active;
    private boolean quarterActive;
    private double quarterDistanceM;
    private double runPeakSpeed;
    /** Falling this far below the run's peak speed means the attempt was abandoned. */
    private static final double ABANDON_DROP_KMH = 10.0;

    // Rolling 80-120 state.
    private boolean rollingActive;
    private double rollingStartT;

    private RunResult last100, best100;
    private RunResult last80120, best80120;
    private RunResult lastQuarter, bestQuarter;

    /** Feed one speed sample. Null/invalid speed aborts runs (sensor dropout). */
    public void push(double elapsedS, Double speedKmh) {
        if (speedKmh == null || !Double.isFinite(speedKmh) || speedKmh < 0.0) {
            abortAll();
            prevT = null;
            prevSpeed = null;
            return;
        }
        double speed = speedKmh;
        if (prevT != null && (elapsedS <= prevT || elapsedS - prevT > MAX_GAP_S)) {
            abortAll();
            prevT = null;
            prevSpeed = null;
        }

        if (speed <= STANDSTILL_KMH) {
            // A standstill both arms the next launch and invalidates runs in
            // progress (a 0-100 attempt that returned to rest never finished).
            abortStandingRuns();
            rollingActive = false;
            launchArmed = true;
        } else if (prevT != null && prevSpeed != null) {
            if (launchArmed && prevSpeed <= STANDSTILL_KMH) {
                launchT = interpolateCrossing(prevT, prevSpeed, elapsedS, speed, STANDSTILL_KMH);
                launchArmed = false;
                run100Active = true;
                quarterActive = true;
                quarterDistanceM = 0.0;
                runPeakSpeed = speed;
            }
            if (run100Active || quarterActive) {
                runPeakSpeed = Math.max(runPeakSpeed, speed);
                if (speed < runPeakSpeed - ABANDON_DROP_KMH) {
                    abortStandingRuns();
                }
            }
            if (quarterActive) {
                double dt = elapsedS - prevT;
                double segmentM = (prevSpeed + speed) / 2.0 / 3.6 * dt;
                double before = quarterDistanceM;
                quarterDistanceM += segmentM;
                if (quarterDistanceM >= QUARTER_MILE_M && segmentM > 0.0) {
                    double frac = (QUARTER_MILE_M - before) / segmentM;
                    double t = prevT + frac * dt - launchT;
                    double trap = prevSpeed + frac * (speed - prevSpeed);
                    lastQuarter = new RunResult(RunType.QUARTER_MILE, t, trap);
                    if (bestQuarter == null || t < bestQuarter.timeS) bestQuarter = lastQuarter;
                    quarterActive = false;
                }
            }
            if (run100Active && prevSpeed < 100.0 && speed >= 100.0) {
                double cross = interpolateCrossing(prevT, prevSpeed, elapsedS, speed, 100.0);
                double t = cross - launchT;
                last100 = new RunResult(RunType.ZERO_TO_100, t, 100.0);
                if (best100 == null || t < best100.timeS) best100 = last100;
                run100Active = false;
            }

            // Rolling 80-120: arm on upward crossing of 80, finish at 120,
            // abort if speed falls back below the hysteresis band.
            if (!rollingActive && prevSpeed < 80.0 && speed >= 80.0) {
                rollingActive = true;
                rollingStartT = interpolateCrossing(prevT, prevSpeed, elapsedS, speed, 80.0);
            }
            if (rollingActive) {
                if (speed < 80.0 - ROLLING_HYSTERESIS_KMH) {
                    rollingActive = false;
                } else if (prevSpeed < 120.0 && speed >= 120.0) {
                    double cross = interpolateCrossing(prevT, prevSpeed, elapsedS, speed, 120.0);
                    double t = cross - rollingStartT;
                    last80120 = new RunResult(RunType.EIGHTY_TO_120, t, 120.0);
                    if (best80120 == null || t < best80120.timeS) best80120 = last80120;
                    rollingActive = false;
                }
            }
        }

        prevT = elapsedS;
        prevSpeed = speed;
    }

    public RunResult getLast(RunType type) {
        switch (type) {
            case ZERO_TO_100: return last100;
            case EIGHTY_TO_120: return last80120;
            default: return lastQuarter;
        }
    }

    public RunResult getBest(RunType type) {
        switch (type) {
            case ZERO_TO_100: return best100;
            case EIGHTY_TO_120: return best80120;
            default: return bestQuarter;
        }
    }

    /** True while any timed run is between its start and finish thresholds. */
    public boolean isRunInProgress() {
        return run100Active || quarterActive || rollingActive;
    }

    /** Clear run state and results (new session). */
    public void reset() {
        abortAll();
        launchArmed = false;
        prevT = null;
        prevSpeed = null;
        last100 = best100 = null;
        last80120 = best80120 = null;
        lastQuarter = bestQuarter = null;
    }

    private void abortStandingRuns() {
        run100Active = false;
        quarterActive = false;
    }

    private void abortAll() {
        abortStandingRuns();
        rollingActive = false;
        launchArmed = false;
    }

    private static double interpolateCrossing(double t0, double v0, double t1, double v1,
                                              double threshold) {
        if (v1 == v0) return t1;
        double frac = (threshold - v0) / (v1 - v0);
        if (frac < 0.0) frac = 0.0;
        if (frac > 1.0) frac = 1.0;
        return t0 + frac * (t1 - t0);
    }
}
