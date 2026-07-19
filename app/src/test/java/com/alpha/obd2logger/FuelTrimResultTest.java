package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * FuelTrimResult constructor contracts: the legacy string→verdict derivation
 * (kept for old callers/tests) and the defensive clamping/null handling of
 * the full ctor. The verdict enum drives UI color roles, so a wrong mapping
 * paints a lean engine green.
 */
public class FuelTrimResultTest {

    // ── Legacy string ctor derivation ─────────────────────────────────

    @Test
    public void legacyCtor_derivesVerdictFromStatusKeywords() {
        assertEquals(LPGAnalyzer.TrimVerdict.LEAN,
                new FuelTrimResult(6, 8, 14, "TOO LEAN", "").getVerdict());
        assertEquals(LPGAnalyzer.TrimVerdict.RICH,
                new FuelTrimResult(-6, -8, -14, "Running RICH", "").getVerdict());
        assertEquals(LPGAnalyzer.TrimVerdict.OK,
                new FuelTrimResult(1, 1, 2, "PERFECT", "").getVerdict());
        assertEquals(LPGAnalyzer.TrimVerdict.OK,
                new FuelTrimResult(1, 1, 2, "OK", "").getVerdict());
        assertEquals(LPGAnalyzer.TrimVerdict.UNSTABLE,
                new FuelTrimResult(0, 0, 0, "UNSTABLE trims", "").getVerdict());
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN,
                new FuelTrimResult(0, 0, 0, "COLD engine", "").getVerdict());
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN,
                new FuelTrimResult(0, 0, 0, (String) null, "").getVerdict());
    }

    @Test
    public void legacyCtor_isCaseInsensitive() {
        assertEquals(LPGAnalyzer.TrimVerdict.LEAN,
                new FuelTrimResult(6, 8, 14, "lean mixture", "").getVerdict());
    }

    // ── Enum ctor null handling ───────────────────────────────────────

    @Test
    public void enumCtor_nullVerdict_becomesUnknown() {
        FuelTrimResult r = new FuelTrimResult(1, 2, 3, (LPGAnalyzer.TrimVerdict) null, null);
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, r.getVerdict());
        assertEquals("UNKNOWN", r.getStatus());
        assertEquals("", r.getRecommendation());
    }

    @Test
    public void enumCtor_tracksNaNInputsInHasFlags() {
        FuelTrimResult r = new FuelTrimResult(Double.NaN, 5,  5,
                LPGAnalyzer.TrimVerdict.OK, "");
        assertFalse(r.hasStft());
        assertTrue(r.hasLtft());
    }

    // ── Full ctor clamping/defaults ───────────────────────────────────

    @Test
    public void fullCtor_clampsConfidenceTo0_100() {
        FuelTrimResult high = full(150);
        FuelTrimResult low = full(-20);
        assertEquals(100, high.getConfidence());
        assertEquals(0, low.getConfidence());
    }

    @Test
    public void fullCtor_nullStringsAndEnums_getSafeDefaults() {
        FuelTrimResult r = new FuelTrimResult(0, 0, Double.NaN, Double.NaN, 0, Double.NaN,
                null, null, null, null, 50, false, true, true);
        assertEquals("", r.getStatus());
        assertEquals("", r.getRecommendation());
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, r.getVerdict());
        assertEquals(LPGAnalyzer.GateReason.NONE, r.getGateReason());
    }

    // ── colorRole mapping ─────────────────────────────────────────────

    @Test
    public void colorRole_mapsEveryVerdict() {
        assertEquals("ok", withVerdict(LPGAnalyzer.TrimVerdict.OK).colorRole());
        assertEquals("lean", withVerdict(LPGAnalyzer.TrimVerdict.LEAN).colorRole());
        assertEquals("rich", withVerdict(LPGAnalyzer.TrimVerdict.RICH).colorRole());
        assertEquals("warn", withVerdict(LPGAnalyzer.TrimVerdict.UNSTABLE).colorRole());
        assertEquals("muted", withVerdict(LPGAnalyzer.TrimVerdict.UNKNOWN).colorRole());
    }

    private static FuelTrimResult withVerdict(LPGAnalyzer.TrimVerdict v) {
        return new FuelTrimResult(0, 0, 0, v, "");
    }

    private static FuelTrimResult full(int confidence) {
        return new FuelTrimResult(1, 2, Double.NaN, Double.NaN, 3, 0.5,
                "OK", "", LPGAnalyzer.TrimVerdict.OK, LPGAnalyzer.GateReason.NONE,
                confidence, false, true, true);
    }
}
