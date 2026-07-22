package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-logic tests for the Tier-2/3 additions: trend math, ΔVE drift alarm,
 * battery RUL projection, Mode 06 catalyst margin, and the FDIR telemetry
 * watchdog. All Context-free — the storage layer is exercised separately.
 */
public class HealthTrendAndWatchdogTest {

    private static final long DAY_MS = 86_400_000L;

    private static List<HealthTrendStore.Point> series(double... values) {
        List<HealthTrendStore.Point> points = new ArrayList<>();
        long t = 1_700_000_000_000L;
        for (double v : values) {
            points.add(new HealthTrendStore.Point(t, v));
            t += DAY_MS; // one point per day
        }
        return points;
    }

    // ── TrendAnalysis ───────────────────────────────────────────────────────

    @Test
    public void slopeRecoversLinearDrift() {
        // 0.5 units/day exactly.
        TrendAnalysis.Result r = TrendAnalysis.analyze(
                series(10.0, 10.5, 11.0, 11.5, 12.0), 1);
        assertEquals(0.5, r.slopePerDay, 1e-9);
        assertEquals(5, r.count);
    }

    @Test
    public void levelShiftComparesWindows() {
        // First window mean 10, last window mean 14 → shift 4.
        TrendAnalysis.Result r = TrendAnalysis.analyze(
                series(10, 10, 10, 14, 14, 14), 3);
        assertEquals(10.0, r.firstWindowMean, 1e-9);
        assertEquals(14.0, r.lastWindowMean, 1e-9);
        assertEquals(4.0, r.levelShift, 1e-9);
    }

    @Test
    public void levelShiftNaNWhenWindowsWouldOverlap() {
        TrendAnalysis.Result r = TrendAnalysis.analyze(series(10, 11, 12), 2);
        assertTrue(Double.isNaN(r.levelShift));
    }

    @Test
    public void projectionReachesThresholdOnDecline() {
        // 12.0 falling 0.1/day → threshold 11.0 in ~10 days from the last point.
        List<HealthTrendStore.Point> pts = series(12.0, 11.9, 11.8, 11.7, 11.6);
        double days = TrendAnalysis.projectDaysToCross(pts, 11.0);
        assertEquals(6.0, days, 0.2);
    }

    @Test
    public void projectionNaNWhenTrendMovesAway() {
        // Rising series can never cross a lower threshold.
        double days = TrendAnalysis.projectDaysToCross(series(11.0, 11.2, 11.4), 10.0);
        assertTrue(Double.isNaN(days));
    }

    // ── VeTrendTracker ──────────────────────────────────────────────────────

    @Test
    public void veTrendInsufficientWithShortHistory() {
        assertEquals(VeTrendTracker.Status.INSUFFICIENT,
                VeTrendTracker.evaluate(series(6.0, 6.1, 6.2)).status);
    }

    @Test
    public void veTrendOkWhenGapStable() {
        VeTrendTracker.Verdict v = VeTrendTracker.evaluate(
                series(6.0, 6.2, 5.9, 6.1, 6.0, 6.1));
        assertEquals(VeTrendTracker.Status.OK, v.status);
    }

    @Test
    public void veTrendAlarmsOnSustainedWidening() {
        // Gap grows 6 → 10 points across windows: gaseous side degrading.
        VeTrendTracker.Verdict v = VeTrendTracker.evaluate(
                series(6.0, 6.1, 5.9, 9.8, 10.1, 10.2));
        assertEquals(VeTrendTracker.Status.ALARM, v.status);
        assertTrue(v.shiftPoints >= VeTrendTracker.ALARM_SHIFT);
    }

    @Test
    public void veTrendWatchOnModerateWidening() {
        VeTrendTracker.Verdict v = VeTrendTracker.evaluate(
                series(6.0, 6.0, 6.0, 8.0, 8.0, 8.0));
        assertEquals(VeTrendTracker.Status.WATCH, v.status);
    }

    @Test
    public void veTrendShrinkingGapIsNotAnLpgAlarm() {
        // ΔVE collapsing is a different investigation, not an LPG service alarm.
        VeTrendTracker.Verdict v = VeTrendTracker.evaluate(
                series(8.0, 8.0, 8.0, 4.0, 4.0, 4.0));
        assertEquals(VeTrendTracker.Status.OK, v.status);
    }

    // ── BatteryTrend ────────────────────────────────────────────────────────

    @Test
    public void batteryProjectsFailureDateOnDecline() {
        // 10.4 V falling 0.05 V/day → crosses CRANK_MIN (9.60) well inside a year.
        BatteryTrend.Prognosis p = BatteryTrend.evaluate(
                series(10.40, 10.35, 10.30, 10.25, 10.20));
        assertEquals(5, p.tests);
        assertTrue(p.slopePerDay < 0);
        assertTrue("expected a failure projection, got " + p.daysToCrankFloor,
                Double.isFinite(p.daysToCrankFloor));
        assertEquals(12.0, p.daysToCrankFloor, 1.0);
    }

    @Test
    public void batteryNoProjectionWhenHealthyOrShort() {
        assertTrue(Double.isNaN(
                BatteryTrend.evaluate(series(10.4, 10.4, 10.5, 10.5)).daysToCrankFloor));
        assertTrue(Double.isNaN(
                BatteryTrend.evaluate(series(10.4, 10.2)).daysToCrankFloor));
    }

    // ── Mode06TrendRecorder ─────────────────────────────────────────────────

    private static Mode06Result m06(int mid, double value, double min, double max) {
        return new Mode06Result(mid, 0x01, 0x0B, 0, 0, 0, value, min, max, "", true);
    }

    @Test
    public void catalystMarginUsesWorstBank() {
        List<Mode06Result> results = new ArrayList<>();
        results.add(m06(0x21, 0.5, 0.0, 1.0));  // margin 0.5
        results.add(m06(0x22, 0.9, 0.0, 1.0));  // margin 0.1 ← worst
        Double worst = Mode06TrendRecorder.worstCatalystMargin(results);
        assertNotNull(worst);
        assertEquals(0.1, worst, 1e-9);
    }

    @Test
    public void catalystMarginIgnoresOtherMonitors() {
        List<Mode06Result> results = new ArrayList<>();
        results.add(m06(0x01, 0.9, 0.0, 1.0));  // O2 monitor — not catalyst
        results.add(m06(0x35, 0.9, 0.0, 1.0));  // misfire-range MID
        assertNull(Mode06TrendRecorder.worstCatalystMargin(results));
    }

    @Test
    public void catalystMarginRejectsDegenerateLimits() {
        List<Mode06Result> results = new ArrayList<>();
        results.add(m06(0x21, 0.5, 0.0, 0.0));  // no window
        assertNull(Mode06TrendRecorder.worstCatalystMargin(results));
    }

    // ── TelemetryWatchdog ───────────────────────────────────────────────────

    private static Map<String, Double> batchWithRpm(Double rpm) {
        Map<String, Double> batch = new HashMap<>();
        if (rpm != null) batch.put("Engine RPM", rpm);
        return batch;
    }

    @Test
    public void watchdogWalksLiveStaleDeadAndDemandsOnce() {
        TelemetryWatchdog wd = new TelemetryWatchdog();
        assertEquals(TelemetryWatchdog.State.LIVE, wd.observeCycle(batchWithRpm(800.0)));

        for (int i = 0; i < TelemetryWatchdog.STALE_CYCLES; i++) {
            wd.observeCycle(batchWithRpm(null));
        }
        assertEquals(TelemetryWatchdog.State.STALE, wd.getState());
        assertFalse(wd.consumeRecoveryDemand());

        for (int i = TelemetryWatchdog.STALE_CYCLES; i < TelemetryWatchdog.DEAD_CYCLES; i++) {
            wd.observeCycle(batchWithRpm(null));
        }
        assertEquals(TelemetryWatchdog.State.DEAD, wd.getState());
        assertTrue(wd.consumeRecoveryDemand());
        // Continued silence must not spam recovery demands.
        wd.observeCycle(batchWithRpm(null));
        assertFalse(wd.consumeRecoveryDemand());
    }

    @Test
    public void watchdogRearmsAfterFreshData() {
        TelemetryWatchdog wd = new TelemetryWatchdog();
        for (int i = 0; i < TelemetryWatchdog.DEAD_CYCLES; i++) {
            wd.observeCycle(batchWithRpm(null));
        }
        assertTrue(wd.consumeRecoveryDemand());

        // Data returns → LIVE, and a later outage demands recovery again.
        assertEquals(TelemetryWatchdog.State.LIVE, wd.observeCycle(batchWithRpm(750.0)));
        for (int i = 0; i < TelemetryWatchdog.DEAD_CYCLES; i++) {
            wd.observeCycle(batchWithRpm(null));
        }
        assertTrue(wd.consumeRecoveryDemand());
    }

    @Test
    public void engineOffIgnitionOnIsAlive() {
        // RPM 0 is a real reading (KOEO) — never a dead link.
        TelemetryWatchdog wd = new TelemetryWatchdog();
        for (int i = 0; i < TelemetryWatchdog.DEAD_CYCLES + 2; i++) {
            assertEquals(TelemetryWatchdog.State.LIVE, wd.observeCycle(batchWithRpm(0.0)));
        }
        assertFalse(wd.consumeRecoveryDemand());
    }

    @Test
    public void watchdogResetClearsHistory() {
        TelemetryWatchdog wd = new TelemetryWatchdog();
        for (int i = 0; i < TelemetryWatchdog.DEAD_CYCLES; i++) {
            wd.observeCycle(batchWithRpm(null));
        }
        wd.reset();
        assertEquals(TelemetryWatchdog.State.LIVE, wd.getState());
        assertEquals(0, wd.getCorelessCycles());
        assertFalse(wd.consumeRecoveryDemand());
    }
}
