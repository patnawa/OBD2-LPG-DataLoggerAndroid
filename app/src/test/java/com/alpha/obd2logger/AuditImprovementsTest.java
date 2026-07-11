package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

/**
 * Regression tests for the bug-hunt fixes and improvements (v3.5.2). These cover the
 * pure-JVM logic that needs no Android Context:
 *   - BUG#1: getLpgPollSet() must include Vehicle Speed (fuel economy dep) + Throttle
 *   - BUG#3: LPGAnalyzer reports UNKNOWN when one trim is missing
 *   - BUG#4: LogReplayParser.isClosedLoop() defaults to OPEN when loop state unknown
 *   - IDEA#3: DerivedSensors math sanity (fuel km/L, boost)
 */
public class AuditImprovementsTest {

    // ── BUG#1 — lpgOnly mode must poll the PIDs the dashboard/derived sensors need
    @Test
    public void getLpgPollSet_includesVehicleSpeedAndThrottle() {
        List<PIDDefinition> poll = PIDCatalogue.getLpgPollSet();
        boolean hasSpeed = false, hasThrottle = false, hasMaf = false, hasMap = false;
        for (PIDDefinition p : poll) {
            if ("Vehicle Speed".equals(p.getName())) hasSpeed = true;
            if ("Throttle Position".equals(p.getName())) hasThrottle = true;
            if ("MAF Air Flow".equals(p.getName())) hasMaf = true;
            if ("Intake Manifold Pressure".equals(p.getName())) hasMap = true;
        }
        assertTrue("Vehicle Speed required for fuel economy (was dropped by old getLpgCritical)", hasSpeed);
        assertTrue("Throttle Position required by dashboard/tuning", hasThrottle);
        assertTrue("MAF required for fuel economy", hasMaf);
        assertTrue("MAP required for boost", hasMap);
        // LpgPollSet should be a strict superset of the old lpgCritical set.
        assertTrue("poll set no smaller than lpgCritical", poll.size() >= PIDCatalogue.getLpgCritical().size());
    }

    // ── BUG#3 — missing one trim => UNKNOWN, not a fabricated OK
    @Test
    public void analyzeFuelTrim_missingOneTrim_isUnknown() {
        FuelTrimResult r = LPGAnalyzer.analyzeFuelTrim(2.0, null, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, r.getVerdict());
    }

    @Test
    public void analyzeFuelTrim_bothMissing_isUnknown() {
        FuelTrimResult r = LPGAnalyzer.analyzeFuelTrim(null, null, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.UNKNOWN, r.getVerdict());
    }

    @Test
    public void analyzeFuelTrim_bothPresent_givesVerdict() {
        // total = 12 > 10 => LEAN
        FuelTrimResult r = LPGAnalyzer.analyzeFuelTrim(6.0, 6.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.LEAN, r.getVerdict());
        // negative total => RICH
        FuelTrimResult r2 = LPGAnalyzer.analyzeFuelTrim(-6.0, -6.0, FuelMode.LPG);
        assertEquals(LPGAnalyzer.TrimVerdict.RICH, r2.getVerdict());
        // within ±10 => OK
        FuelTrimResult r3 = LPGAnalyzer.analyzeFuelTrim(1.0, 1.0, FuelMode.PETROL);
        assertEquals(LPGAnalyzer.TrimVerdict.OK, r3.getVerdict());
    }

    // ── BUG#4 — unknown loop state defaults to CLOSED (plot), not OPEN (skip)
    // This was intentionally changed from false→true so that logs without loop-state
    // columns still plot on the fuel map instead of showing "No valid tuning points
    // found". The old behavior (defaulting to OPEN/skip) made the compare/replay
    // feature appear broken for older logs.
    @Test
    public void isClosedLoop_unknownColumn_returnsTrue() {
        // Header with neither a numeric Fuel System Status column nor a loop_status text column.
        String header = "timestamp,fuel_mode,\"Engine RPM (rpm)\",\"Intake Manifold Pressure (kPa)\"";
        LogReplayParser.Columns c = LogReplayParser.parseHeader(header);
        String line = "\"2026-07-09 10:00:00,000\",1.0,2500,120";
        assertTrue("unknown loop state defaults to CLOSED (rows still plot)",
                LogReplayParser.isClosedLoop(LogReplayParser.splitCsv(line), c));
        // And therefore the point is plotted (not skipped).
        assertNotNull("row with unknown loop state is plotted",
                LogReplayParser.parseLine(line, c));
    }

    // ── IDEA#3 — DerivedSensors math
    @Test
    public void fuelConsumption_knownValue() {
        // MAF 20 g/s, speed 100 km/h, petrol 95 (E10: AFR=14.23, density=741)
        // km/L = 100 * 741 * 14.23 / (20 * 3600) = 14.645...
        Double kml = DerivedSensors.fuelConsumptionKmL(20.0, 100.0, FuelMode.PETROL);
        assertTrue(kml != null);
        assertEquals(14.65, kml, 0.1);
    }

    @Test
    public void fuelConsumption_idleReturnsNull() {
        // speed < 2 km/h => null (avoids absurd divide-by-zero)
        assertNull(DerivedSensors.fuelConsumptionKmL(20.0, 0.0, FuelMode.PETROL));
    }

    @Test
    public void boostPressure_seaLevelFallback() {
        // MAP 90 kPa, no baro (null) => sea-level 101.325 fallback => boost -11.3 kPa (vacuum)
        Double boost = DerivedSensors.boostPressureKpa(90.0, null);
        assertTrue(boost != null);
        assertEquals(-11.3, boost, 0.5);
    }
}
