package com.alpha.obd2logger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for the saved-log replay parser used by ReviewSessionActivity.
 *
 * These lock in the three bugs that made "open a log -> show it on the Map" fail
 * with an error / empty map:
 *   (1) naive split(",") corrupted by the quoted, comma-containing timestamp
 *   (2) inverted closed-loop gate (1.0/8.0 are OPEN loop; closed = bit 0x02)
 *   (3) fuel_mode compared to "PETROL" but DataWriter writes lowercase "petrol"
 */
public class LogReplayParserTest {

    // A header line exactly as DataWriter writes it: text fields quoted, PID
    // column names quoted with units. (Fuel System Status has an empty unit.)
    private static final String HEADER =
        "\"timestamp\",elapsed_s,fuel_mode,loop_status,vehicle_brand,vin,"
        + "\"Engine RPM (rpm)\",\"Short Term Fuel Trim (%)\","
        + "\"Long Term Fuel Trim (%)\",\"Intake Manifold Pressure (kPa)\","
        + "\"Fuel System Status ()\"";

    @Test
    public void splitCsv_handlesQuotedCommaInTimestamp() {
        // The timestamp field contains a comma INSIDE its quotes — the bug that
        // broke naive split(",").
        String row = "\"2026-06-29 10:00:00,123\",1.0,\"petrol\",\"Closed\",\"Toyota\",\"JTxxx\",2000,3.5,2.0,40,2";
        String[] parts = LogReplayParser.splitCsv(row);
        assertEquals("row splits into 11 fields despite comma in timestamp", 11, parts.length);
        assertEquals("2026-06-29 10:00:00,123", parts[0]);
        assertEquals("petrol", parts[2]);
        assertEquals("2000", parts[6]);
        assertEquals("2", parts[10]);
    }

    @Test
    public void splitCsv_unescapesDoubledQuotes() {
        String[] parts = LogReplayParser.splitCsv("\"he said \"\"hi\"\"\",2");
        assertArrayEquals(new String[]{"he said \"hi\"", "2"}, parts);
    }

    @Test
    public void parseHeader_matchesQuotedColumnNames() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        assertEquals(2, c.fuelModeIdx);
        assertEquals(3, c.loopStatusIdx);
        assertEquals(6, c.rpmIdx);
        assertEquals(7, c.stftIdx);
        assertEquals(8, c.ltftIdx);
        assertEquals(9, c.mapIdx);
        assertEquals(10, c.fuelSystemStatusIdx);
        assertEquals("axis uses MAP when present", 9, c.axisIdx());
        assertTrue(c.hasRequired());
    }

    @Test
    public void parseHeader_fallsBackToEngineLoadWhenNoMap() {
        String header = "timestamp,fuel_mode,\"Engine RPM (rpm)\",\"Engine Load (%)\"";
        LogReplayParser.Columns c = LogReplayParser.parseHeader(header);
        assertEquals(-1, c.mapIdx);
        assertEquals(3, c.loadIdx);
        assertEquals("axis falls back to Engine Load", 3, c.axisIdx());
        assertTrue(c.hasRequired());
    }

    @Test
    public void parseHeader_missingRpmFailsRequired() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader("timestamp,fuel_mode,\"Engine Load (%)\"");
        assertFalse(c.hasRequired());
    }

    @Test
    public void isClosedLoop_bit0x02IsClosed_not1or8() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        // status 2 (0x02) => closed loop (this is the row that SHOULD plot)
        assertTrue(LogReplayParser.isClosedLoop(
            LogReplayParser.splitCsv("\"t\",1,\"petrol\",\"Closed\",\"b\",\"v\",2000,3,2,40,2"), c));
        // status 1 (0x01) => open loop => skip
        assertFalse(LogReplayParser.isClosedLoop(
            LogReplayParser.splitCsv("\"t\",1,\"petrol\",\"Open\",\"b\",\"v\",2000,3,2,40,1"), c));
        // status 8 (0x08) => open loop (failure) => skip — the OLD code treated 8 as closed
        assertFalse(LogReplayParser.isClosedLoop(
            LogReplayParser.splitCsv("\"t\",1,\"petrol\",\"Open\",\"b\",\"v\",2000,3,2,40,8"), c));
        // status 4 (0x04) => open loop (load) => skip
        assertFalse(LogReplayParser.isClosedLoop(
            LogReplayParser.splitCsv("\"t\",1,\"petrol\",\"Open\",\"b\",\"v\",2000,3,2,40,4"), c));
    }

    @Test
    public void parseLine_plotsClosedLoopPetrol() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        LogReplayParser.Point p = LogReplayParser.parseLine(
            "\"2026-06-29 10:00:00,123\",1.0,\"petrol\",\"Closed\",\"Toyota\",\"JT\",2000,3.5,2.0,40,2", c);
        assertNotNull("closed-loop petrol row plots", p);
        assertEquals(2000.0, p.rpm, 0.001);
        assertEquals(40.0, p.axis, 0.001);
        assertEquals("trim = STFT + LTFT", 5.5, p.trim, 0.001);
        assertEquals(FuelMode.PETROL, p.fuelMode);
    }

    @Test
    public void parseLine_recognizesLowercaseLpgValue() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        // DataWriter writes FuelMode.getValue() == "lpg/cng" (lowercase). The old
        // code compared to "PETROL" and mislabelled everything.
        LogReplayParser.Point p = LogReplayParser.parseLine(
            "\"t\",60,\"lpg/cng\",\"Closed\",\"b\",\"v\",2000,8.0,2.0,40,2", c);
        assertNotNull(p);
        assertEquals(FuelMode.LPG, p.fuelMode);
    }

    @Test
    public void parseLine_skipsOpenLoop() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        assertNull("open-loop (status 1) is skipped",
            LogReplayParser.parseLine("\"t\",3,\"petrol\",\"Open\",\"b\",\"v\",3000,9,3,60,1", c));
    }

    @Test
    public void parseLine_skipsUnparseableNumbers() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        assertNull(LogReplayParser.parseLine("\"t\",1,\"petrol\",\"Closed\",\"b\",\"v\",ABC,3,2,40,2", c));
    }

    @Test
    public void endToEnd_petrolAndLpgPlotted_openLoopDropped() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        String[] rows = {
            "\"2026-06-29 10:00:00,123\",1.0,\"petrol\",\"Closed\",\"Toyota\",\"JT\",2000,3.5,2.0,40,2",  // plot
            "\"2026-06-29 10:00:01,456\",2.0,\"petrol\",\"Closed\",\"Toyota\",\"JT\",2500,4.0,2.5,50,2",  // plot
            "\"2026-06-29 10:00:02,789\",3.0,\"petrol\",\"Open\",\"Toyota\",\"JT\",3000,9.0,3.0,60,1",    // skip (open)
            "\"2026-06-29 10:01:00,000\",60.0,\"lpg/cng\",\"Closed\",\"Toyota\",\"JT\",2000,8.0,2.0,40,2" // plot
        };
        int petrol = 0, lpg = 0;
        for (String r : rows) {
            LogReplayParser.Point p = LogReplayParser.parseLine(r, c);
            if (p == null) continue;
            if (p.fuelMode == FuelMode.PETROL) petrol++; else lpg++;
        }
        assertEquals("2 closed-loop petrol points plotted", 2, petrol);
        assertEquals("1 closed-loop lpg point plotted", 1, lpg);
    }

    // ── Lambda-based trim fallback tests ──
    // LPG/CNG vehicles often have no STFT/LTFT PIDs but DO have a wideband
    // lambda (PID 0x34 or 0x44). The parser should derive a synthetic trim
    // from lambda: trim% = (lambda - 1.0) * 100.

    private static final String HEADER_WITH_LAMBDA =
        "\"timestamp\",elapsed_s,fuel_mode,loop_status,vehicle_brand,vin,"
        + "\"Engine RPM (rpm)\",\"Intake Manifold Pressure (kPa)\","
        + "\"Lambda (B1S1) ()\",\"Fuel System Status ()\"";

    @Test
    public void parseHeader_detectsLambdaColumn() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER_WITH_LAMBDA);
        assertEquals("lambda column found", 8, c.lambdaIdx);
        assertTrue(c.hasRequired());
    }

    @Test
    public void parseHeader_doesNotTreatCommandedRatioAsMeasuredLambda() {
        String header = HEADER_WITH_LAMBDA.replace(
                "\"Lambda (B1S1) ()\"", "\"Commanded Equivalence Ratio ()\"");
        LogReplayParser.Columns c = LogReplayParser.parseHeader(header);
        assertEquals("commanded ratio must not drive map trim", -1, c.lambdaIdx);
    }

    @Test
    public void parseHeader_keepsActualLambdaWhenCommandedColumnFollows() {
        String header = HEADER_WITH_LAMBDA.replace(
                "\"Fuel System Status ()\"",
                "\"Commanded Equivalence Ratio ()\",\"Fuel System Status ()\"");
        LogReplayParser.Columns c = LogReplayParser.parseHeader(header);
        assertEquals("actual PID34 remains the selected lambda", 8, c.lambdaIdx);
    }

    @Test
    public void parseLine_lambdaFallbackWhenNoTrim() {
        // LPG log with lambda 1.05 (lean) but no STFT/LTFT columns.
        // trim should be (1.05 - 1.0) * 100 = +5.0%
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER_WITH_LAMBDA);
        LogReplayParser.Point p = LogReplayParser.parseLine(
            "\"t\",1,\"lpg/cng\",\"Closed\",\"b\",\"v\",2000,40,1.05,2", c);
        assertNotNull("lambda-only row plots", p);
        assertEquals("trim derived from lambda", 5.0, p.trim, 0.001);
        assertEquals(FuelMode.LPG, p.fuelMode);
    }

    @Test
    public void parseLine_lambdaRichGivesNegativeTrim() {
        // lambda 0.92 (rich) → trim = (0.92 - 1.0) * 100 = -8.0%
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER_WITH_LAMBDA);
        LogReplayParser.Point p = LogReplayParser.parseLine(
            "\"t\",1,\"lpg/cng\",\"Closed\",\"b\",\"v\",2500,50,0.92,2", c);
        assertNotNull(p);
        assertEquals("rich lambda → negative trim", -8.0, p.trim, 0.001);
    }

    @Test
    public void parseLine_stftTakesPriorityOverLambda() {
        // When STFT is present, lambda should NOT override it.
        String header = HEADER_WITH_LAMBDA.replace(
            "\"Lambda (B1S1) ()\",",
            "\"Short Term Fuel Trim (%)\",\"Lambda (B1S1) ()\",");
        LogReplayParser.Columns c = LogReplayParser.parseHeader(header);
        // STFT=3.5, lambda=1.05 → trim should be 3.5 (STFT wins, not 5.0 from lambda)
        LogReplayParser.Point p = LogReplayParser.parseLine(
            "\"t\",1,\"petrol\",\"Closed\",\"b\",\"v\",2000,40,3.5,1.05,2", c);
        assertNotNull(p);
        assertEquals("STFT takes priority over lambda fallback", 3.5, p.trim, 0.001);
    }

    @Test
    public void parseLine_lambdaOutOfRangeIgnored() {
        // lambda 0.0 or >3 are invalid — should not produce a synthetic trim
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER_WITH_LAMBDA);
        LogReplayParser.Point p = LogReplayParser.parseLine(
            "\"t\",1,\"lpg/cng\",\"Closed\",\"b\",\"v\",2000,40,0.0,2", c);
        assertNotNull(p);
        assertEquals("invalid lambda → trim stays 0", 0.0, p.trim, 0.001);
    }
}
