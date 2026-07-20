package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PerformanceTimerEngineTest {

    /** Constant 10 km/h per 0.5 s sample => 100 km/h reached 5.0 s after launch. */
    @Test
    public void zeroTo100_linearRamp_interpolatesExactTime() {
        PerformanceTimerEngine e = new PerformanceTimerEngine();
        e.push(0.0, 0.0);
        double t = 0.0;
        double v = 0.0;
        while (v < 110.0) {
            t += 0.5;
            v += 10.0;
            e.push(t, v);
        }
        PerformanceTimerEngine.RunResult r = e.getLast(PerformanceTimerEngine.RunType.ZERO_TO_100);
        assertNotNull(r);
        // Launch interpolates at 0.5 km/h on the 0->10 segment (t=0.025),
        // 100 km/h is hit exactly at t=5.0.
        assertEquals(4.975, r.timeS, 0.01);
    }

    @Test
    public void eightyTo120_rollingStart_measuresOnlyTheOvertake() {
        PerformanceTimerEngine e = new PerformanceTimerEngine();
        // Cruise at 70, then accelerate 5 km/h per 0.5 s: 80 at t=1.0, 120 at t=5.0.
        e.push(0.0, 70.0);
        e.push(0.5, 75.0);
        double t = 0.5;
        double v = 75.0;
        while (v < 125.0) {
            t += 0.5;
            v += 5.0;
            e.push(t, v);
        }
        PerformanceTimerEngine.RunResult r = e.getLast(PerformanceTimerEngine.RunType.EIGHTY_TO_120);
        assertNotNull(r);
        assertEquals(4.0, r.timeS, 0.01);
    }

    @Test
    public void quarterMile_constantAcceleration_matchesKinematics() {
        PerformanceTimerEngine e = new PerformanceTimerEngine();
        // 5 m/s^2 from rest: 402.336 m at t = sqrt(2*402.336/5) = 12.686 s.
        double a = 5.0;
        e.push(0.0, 0.0);
        for (double t = 0.1; t <= 14.0; t += 0.1) {
            e.push(t, a * t * 3.6);
        }
        PerformanceTimerEngine.RunResult r = e.getLast(PerformanceTimerEngine.RunType.QUARTER_MILE);
        assertNotNull(r);
        assertEquals(12.686, r.timeS, 0.05);
        // Trap speed v = a*t = 63.43 m/s = 228.3 km/h.
        assertEquals(228.3, r.endSpeedKmh, 2.0);
    }

    @Test
    public void abandonedLaunch_slowingWithoutStopping_doesNotProduceATime() {
        PerformanceTimerEngine e = new PerformanceTimerEngine();
        e.push(0.0, 0.0);
        e.push(0.5, 20.0);
        e.push(1.0, 40.0);
        e.push(1.5, 60.0);
        e.push(2.0, 30.0); // gave up, never stopped
        for (double t = 2.5; t < 8.0; t += 0.5) {
            e.push(t, Math.min(120.0, 30.0 + (t - 2.0) * 40.0));
        }
        assertNull(e.getLast(PerformanceTimerEngine.RunType.ZERO_TO_100));
    }

    @Test
    public void sampleGap_abortsRunInsteadOfInterpolatingAcrossDropout() {
        PerformanceTimerEngine e = new PerformanceTimerEngine();
        e.push(0.0, 0.0);
        e.push(0.5, 30.0);
        e.push(1.0, 60.0);
        e.push(9.0, 110.0); // 8 s dropout while crossing 100
        assertNull(e.getLast(PerformanceTimerEngine.RunType.ZERO_TO_100));
        assertFalse(e.isRunInProgress());
    }

    @Test
    public void nullSpeed_abortsAndRecovers() {
        PerformanceTimerEngine e = new PerformanceTimerEngine();
        e.push(0.0, 0.0);
        e.push(0.5, 40.0);
        e.push(1.0, null);
        assertFalse(e.isRunInProgress());
        // A fresh standstill re-arms a clean run.
        e.push(1.5, 0.0);
        double t = 1.5;
        double v = 0.0;
        while (v < 110.0) {
            t += 0.5;
            v += 20.0;
            e.push(t, v);
        }
        assertNotNull(e.getLast(PerformanceTimerEngine.RunType.ZERO_TO_100));
    }

    @Test
    public void bestRun_keepsFastestOfSeveral() {
        PerformanceTimerEngine e = new PerformanceTimerEngine();
        runZeroTo(e, 0.0, 10.0);   // slow ramp
        runZeroTo(e, 100.0, 25.0); // fast ramp
        PerformanceTimerEngine.RunResult best = e.getBest(PerformanceTimerEngine.RunType.ZERO_TO_100);
        PerformanceTimerEngine.RunResult last = e.getLast(PerformanceTimerEngine.RunType.ZERO_TO_100);
        assertNotNull(best);
        assertNotNull(last);
        assertEquals(best.timeS, last.timeS, 1e-9);
        assertTrue(best.timeS < 3.0);
    }

    @Test
    public void reset_clearsResults() {
        PerformanceTimerEngine e = new PerformanceTimerEngine();
        runZeroTo(e, 0.0, 20.0);
        assertNotNull(e.getLast(PerformanceTimerEngine.RunType.ZERO_TO_100));
        e.reset();
        assertNull(e.getLast(PerformanceTimerEngine.RunType.ZERO_TO_100));
        assertNull(e.getBest(PerformanceTimerEngine.RunType.ZERO_TO_100));
    }

    private static void runZeroTo(PerformanceTimerEngine e, double startT, double stepKmh) {
        e.push(startT, 0.0);
        double t = startT;
        double v = 0.0;
        while (v < 115.0) {
            t += 0.5;
            v += stepKmh;
            e.push(t, v);
        }
        // Return to rest so the next launch can arm.
        e.push(t + 0.5, 0.0);
    }
}
