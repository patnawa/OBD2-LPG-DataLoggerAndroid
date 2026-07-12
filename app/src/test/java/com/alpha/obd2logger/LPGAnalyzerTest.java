package com.alpha.obd2logger;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Professional OBD2 fuel-trim analyzer regression tests.
 */
public class LPGAnalyzerTest {

    @Before
    public void reset() {
        LPGAnalyzer.resetHistory();
    }

    @Test
    public void bothMissing_isUnknownNoData() {
        FuelTrimResult r = LPGAnalyzer.analyze(null, null, null, null, 2.0, 90.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, r.getVerdict());
        assertEquals(LPGAnalyzer.GateReason.NO_DATA, r.getGateReason());
    }

    @Test
    public void partialStftOnly_isUnknownPartial() {
        FuelTrimResult r = LPGAnalyzer.analyze(2.0, null, null, null, 2.0, 90.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, r.getVerdict());
        assertEquals(LPGAnalyzer.GateReason.PARTIAL_DATA, r.getGateReason());
        assertTrue(r.hasStft());
        assertFalse(r.hasLtft());
    }

    @Test
    public void openLoop_isUnknownEvenIfTrimLean() {
        FuelTrimResult r = LPGAnalyzer.analyze(12.0, 5.0, null, null, 1.0, 90.0, FuelMode.LPG);
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, r.getVerdict());
        assertEquals(LPGAnalyzer.GateReason.OPEN_LOOP, r.getGateReason());
    }

    @Test
    public void coldEngine_isUnknown() {
        FuelTrimResult r = LPGAnalyzer.analyze(1.0, 1.0, null, null, 2.0, 40.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, r.getVerdict());
        assertEquals(LPGAnalyzer.GateReason.COLD_ENGINE, r.getGateReason());
    }

    @Test
    public void petrol_mildLtft5_isOk() {
        FuelTrimResult r = LPGAnalyzer.analyze(1.0, 3.0, null, null, 2.0, 90.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.OK, r.getVerdict());
    }

    @Test
    public void petrol_ltft7_isLean_stricterThanOld10Pct() {
        // Old rule STFT+LTFT: 1+7=8 still OK. New shop-grade LTFT primary steals LEAN for petrol prep.
        FuelTrimResult r = LPGAnalyzer.analyze(1.0, 7.0, null, null, 2.0, 90.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.LEAN, r.getVerdict());
    }

    @Test
    public void lpg_ltft7_isStillOk_looserBand() {
        FuelTrimResult r = LPGAnalyzer.analyze(1.0, 7.0, null, null, 2.0, 90.0, FuelMode.LPG);
        assertEquals(LPGAnalyzer.TrimVerdict.OK, r.getVerdict());
    }

    @Test
    public void lpg_ltft13_isLean() {
        FuelTrimResult r = LPGAnalyzer.analyze(0.0, 13.0, null, null, 2.0, 90.0, FuelMode.LPG);
        assertEquals(LPGAnalyzer.TrimVerdict.LEAN, r.getVerdict());
    }

    @Test
    public void richNegativeLtft() {
        FuelTrimResult r = LPGAnalyzer.analyze(0.0, -13.0, null, null, 2.0, 90.0, FuelMode.LPG);
        assertEquals(LPGAnalyzer.TrimVerdict.RICH, r.getVerdict());
    }

    @Test
    public void bankMerge_averagesAndFlagsImbalance() {
        FuelTrimResult r = LPGAnalyzer.analyze(0.0, 2.0, 0.0, 10.0, 2.0, 90.0, FuelMode.PETROL);
        // avg LTFT = 6 → petrol LEAN; imbalance |2-10|=8 ≥ 5
        assertEquals(LPGAnalyzer.TrimVerdict.LEAN, r.getVerdict());
        assertTrue(r.isBankImbalance());
        assertEquals(6.0, r.getLtft(), 0.01);
    }

    @Test
    public void diesel_isUnknown() {
        FuelTrimResult r = LPGAnalyzer.analyze(1.0, 1.0, null, null, 2.0, 90.0, FuelMode.DIESEL);
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, r.getVerdict());
        assertEquals(LPGAnalyzer.GateReason.DIESEL_NO_TRIM, r.getGateReason());
    }

    @Test
    public void localizeKeepsVerdictWithThaiPerfectString() {
        // Simulate the localization path given TH status string containing PERFECT not OK
        FuelTrimResult pure = LPGAnalyzer.analyze(1.0, 1.0, null, null, 2.0, 90.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.OK, pure.getVerdict());
        FuelTrimResult localized = new FuelTrimResult(
                pure.getStft(), pure.getLtft(), pure.getStftB2(), pure.getLtftB2(),
                pure.getTotal(), pure.getStftStd(),
                "สมบูรณ์ (PERFECT)", pure.getRecommendation(),
                pure.getVerdict(), pure.getGateReason(),
                pure.getConfidence(), pure.isBankImbalance(),
                pure.hasStft(), pure.hasLtft());
        assertEquals("Thai PERFECT label must keep final OK verdict",
                LPGAnalyzer.TrimVerdict.OK, localized.getVerdict());
        assertEquals("ok", localized.colorRole());
    }

    @Test
    public void colorRole_unknownIsMutedNotRich() {
        FuelTrimResult r = LPGAnalyzer.analyze(null, null, null, null, 2.0, 90.0, FuelMode.PETROL);
        assertEquals("muted", r.colorRole());
    }

    @Test
    public void legacyAnalyzeFuelTrimStillWorks() {
        LPGAnalyzer.resetHistory();
        FuelTrimResult lean = LPGAnalyzer.analyzeFuelTrim(6.0, 6.0, FuelMode.PETROL);
        // total would be 12; LTFT 6 > petrol OK 5 → LEAN
        assertEquals(LPGAnalyzer.TrimVerdict.LEAN, lean.getVerdict());
        FuelTrimResult ok = LPGAnalyzer.analyzeFuelTrim(1.0, 1.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.OK, ok.getVerdict());
        FuelTrimResult missing = LPGAnalyzer.analyzeFuelTrim(2.0, null, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, missing.getVerdict());
    }

    @Test
    public void closedLoopBitRequires0x02() {
        // fuelStatus=4 (open load) must NOT pass gate
        FuelTrimResult r = LPGAnalyzer.analyze(1.0, 1.0, null, null, 4.0, 90.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.GateReason.OPEN_LOOP, r.getGateReason());
        // fuelStatus=2 closed → OK
        FuelTrimResult r2 = LPGAnalyzer.analyze(1.0, 1.0, null, null, 2.0, 90.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.OK, r2.getVerdict());
    }
}
