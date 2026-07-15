package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class Mode06ReaderTest {

    @Test
    public void testParseSingleResult() {
        // MID=0x21 (Catalyst B1), TID=0x0A (Conversion Efficiency),
        // UAS=0x20 (ratio, 0.00390625 per bit — SAE J1979 UAS table),
        // value=0x007D (125*0.00390625=0.4883), min=0x0000 (0), max=0x00FA (0.9766)
        String response = "46 21 0A 20 00 7D 00 00 00 FA";
        List<Mode06Result> results = Mode06Reader.parseResponse(response);
        assertEquals(1, results.size());
        Mode06Result r = results.get(0);
        assertEquals(0x21, r.getObdMid());
        assertEquals(0x0A, r.getTid());
        assertEquals(0x20, r.getUasId());
        assertEquals(125, r.getRawValue());
        assertEquals(0, r.getRawMin());
        assertEquals(250, r.getRawMax());
        assertEquals(0.4883, r.getScaledValue(), 0.01);
        assertEquals(0.0, r.getScaledMin(), 0.01);
        assertEquals(0.9766, r.getScaledMax(), 0.01);
        assertTrue(r.isPassed()); // 0.4883 is between 0 and 0.9766
        assertEquals("ratio", r.getUnit());
        assertEquals("Catalyst B1", r.getMonitorName());
        assertEquals("Catalyst Conversion Efficiency", r.getTestName());
    }

    @Test
    public void testParseMultipleResults() {
        // Two results: Catalyst B1 (MID=0x21) + O2 Sensor B1S1 (MID=0x01)
        String response = "46 21 0A 07 00 7D 00 00 00 FA 01 0A 08 00 32 00 00 00 64";
        List<Mode06Result> results = Mode06Reader.parseResponse(response);
        assertEquals(2, results.size());

        assertEquals("Catalyst B1", results.get(0).getMonitorName());
        assertEquals("O2 Sensor B1S1", results.get(1).getMonitorName());
    }

    @Test
    public void testParseMultiFrameResponse() {
        // Simulated multi-frame CAN response
        String response = "0: 46 21 0A 07 00 7D\r\n1: 00 00 00 FA\r\n";
        List<Mode06Result> results = Mode06Reader.parseResponse(response);
        assertEquals(1, results.size());
        assertEquals("Catalyst B1", results.get(0).getMonitorName());
    }

    @Test
    public void testParseWithSearchingNoise() {
        String response = "SEARCHING...\r46 21 0A 07 00 7D 00 00 00 FA\r\n";
        List<Mode06Result> results = Mode06Reader.parseResponse(response);
        assertEquals(1, results.size());
    }

    @Test
    public void testParseEmptyResponse() {
        assertEquals(0, Mode06Reader.parseResponse(null).size());
        assertEquals(0, Mode06Reader.parseResponse("").size());
        assertEquals(0, Mode06Reader.parseResponse("NODATA").size());
    }

    @Test
    public void testParseZeroPaddingSkipped() {
        // All zeros should be skipped
        String response = "46 00 00 00 00 00 00 00 00 00";
        List<Mode06Result> results = Mode06Reader.parseResponse(response);
        assertEquals(0, results.size());
    }

    @Test
    public void testFailResult() {
        // Value 0x01F4 = 500, min=0x0000=0, max=0x00C8=200
        // With UAS 0x01 (raw count, 1 per bit): value=500, min=0, max=200 => FAIL
        String response = "46 21 0A 01 01 F4 00 00 00 C8";
        List<Mode06Result> results = Mode06Reader.parseResponse(response);
        assertEquals(1, results.size());
        assertFalse(results.get(0).isPassed());
    }
}
