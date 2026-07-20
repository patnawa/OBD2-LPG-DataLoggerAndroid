package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FuelSwitchoverEngineTest {

    private static final double CLOSED_LOOP = 2.0;
    private static final double WARM = 90.0;

    @Test
    public void steadyTrims_reportStableWithBaselineStats() {
        FuelSwitchoverEngine e = new FuelSwitchoverEngine();
        FuelSwitchoverEngine.Result r = null;
        for (int i = 0; i < 30; i++) {
            r = e.push(i * 0.5, 1.0, 2.0, CLOSED_LOOP, WARM);
        }
        assertNotNull(r);
        assertEquals(FuelSwitchoverEngine.State.STABLE, r.state);
        assertEquals(0, r.switchCount);
        assertNotNull(r.current);
        assertEquals(1.0, r.current.meanStft, 0.01);
        assertEquals(2.0, r.current.meanLtft, 0.01);
    }

    @Test
    public void trimStep_detectsSwitchoverAndSplitsSegments() {
        FuelSwitchoverEngine e = new FuelSwitchoverEngine();
        double t = 0;
        // Petrol baseline: total trim ~ +2%.
        for (int i = 0; i < 40; i++) {
            e.push(t, 1.0, 1.0, CLOSED_LOOP, WARM);
            t += 0.5;
        }
        // LPG regime: total trim ~ +12% (a 10% step). The reseeded segment can
        // carry up to SHORT_WINDOW-1 boundary samples from the old regime, so
        // its mean converges toward the new level rather than landing exactly.
        FuelSwitchoverEngine.Result r = null;
        for (int i = 0; i < 60; i++) {
            r = e.push(t, 4.0, 8.0, CLOSED_LOOP, WARM);
            t += 0.5;
        }
        assertNotNull(r);
        assertEquals(FuelSwitchoverEngine.State.SWITCHED, r.state);
        assertEquals(1, r.switchCount);
        assertNotNull(r.previous);
        assertEquals(1.0, r.previous.meanLtft, 0.2);
        assertEquals(8.0, r.current.meanLtft, 0.4);
        assertEquals(10.0, r.deltaTotal, 0.75);
        assertFalse(r.divergent); // LTFT gap is 7%, just under the 8% warn band
    }

    @Test
    public void smallTrimWander_doesNotFalseTrigger() {
        FuelSwitchoverEngine e = new FuelSwitchoverEngine();
        FuelSwitchoverEngine.Result r = null;
        double t = 0;
        for (int i = 0; i < 200; i++) {
            // Wander +/-3% around zero, below the 7% step threshold.
            double s = 3.0 * Math.sin(i / 5.0);
            r = e.push(t, s, 0.0, CLOSED_LOOP, WARM);
            t += 0.5;
        }
        assertNotNull(r);
        assertEquals(0, r.switchCount);
    }

    @Test
    public void openLoopBlipDuringSwitchover_stillDetectsStep() {
        FuelSwitchoverEngine e = new FuelSwitchoverEngine();
        double t = 0;
        for (int i = 0; i < 40; i++) {
            e.push(t, 0.0, 1.0, CLOSED_LOOP, WARM);
            t += 0.5;
        }
        // Open-loop blip while the LPG ECU takes over.
        for (int i = 0; i < 4; i++) {
            e.push(t, 0.0, 1.0, 1.0 /* open loop */, WARM);
            t += 0.5;
        }
        FuelSwitchoverEngine.Result r = null;
        for (int i = 0; i < 20; i++) {
            r = e.push(t, 5.0, 9.0, CLOSED_LOOP, WARM);
            t += 0.5;
        }
        assertNotNull(r);
        assertEquals(1, r.switchCount);
        assertTrue(r.divergent); // 8% LTFT gap
    }

    @Test
    public void coldEngine_isGatedUntilWarm() {
        FuelSwitchoverEngine e = new FuelSwitchoverEngine();
        FuelSwitchoverEngine.Result r = e.push(0.0, 1.0, 1.0, CLOSED_LOOP, 40.0);
        assertEquals(FuelSwitchoverEngine.State.GATED, r.state);
        for (int i = 1; i <= 30; i++) {
            r = e.push(i * 0.5, 1.0, 1.0, CLOSED_LOOP, WARM);
        }
        assertEquals(FuelSwitchoverEngine.State.STABLE, r.state);
    }

    @Test
    public void similarTrimsAfterSwitch_notFlaggedDivergent() {
        FuelSwitchoverEngine e = new FuelSwitchoverEngine();
        double t = 0;
        for (int i = 0; i < 40; i++) {
            e.push(t, -4.0, 0.0, CLOSED_LOOP, WARM);
            t += 0.5;
        }
        // Step of 8% in total trim but mostly via STFT; LTFT gap only 3%.
        FuelSwitchoverEngine.Result r = null;
        for (int i = 0; i < 20; i++) {
            r = e.push(t, 1.0, 3.0, CLOSED_LOOP, WARM);
            t += 0.5;
        }
        assertNotNull(r);
        assertEquals(1, r.switchCount);
        assertFalse(r.divergent);
    }

    @Test
    public void reset_returnsToGated() {
        FuelSwitchoverEngine e = new FuelSwitchoverEngine();
        for (int i = 0; i < 30; i++) {
            e.push(i * 0.5, 1.0, 1.0, CLOSED_LOOP, WARM);
        }
        e.reset();
        FuelSwitchoverEngine.Result r = e.push(100.0, null, null, CLOSED_LOOP, WARM);
        assertEquals(FuelSwitchoverEngine.State.GATED, r.state);
    }
}
