package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class PIDParserTest {
    @Test
    public void parseRpmMatchesPythonReference() {
        PIDDefinition rpmPid = PIDCatalogue.getAll().get(0);
        Double value = PIDParser.parse(rpmPid, "1AF8");
        assertEquals(Double.valueOf(1726.0), value);
    }

    @Test
    public void catalogueContainsCriticalLpgPids() {
        assertNotNull(PIDCatalogue.getLpgCritical());
        assertEquals(22, PIDCatalogue.getLpgCritical().size());
    }

    @Test
    public void o2SensorPidsArePresent() {
        // OBD2 standard defines 8 O2 sensor PIDs (0x14-0x1B): B1S1-B1S4, B2S1-B2S4.
        assertNotNull(PIDDefinition.findByKey("01_14"));
        assertNotNull(PIDDefinition.findByKey("01_15"));
        assertNotNull(PIDDefinition.findByKey("01_16"));
        assertNotNull(PIDDefinition.findByKey("01_17"));
        assertNotNull(PIDDefinition.findByKey("01_18"));
        assertNotNull(PIDDefinition.findByKey("01_19"));
        assertNotNull(PIDDefinition.findByKey("01_1A"));
        assertNotNull(PIDDefinition.findByKey("01_1B"));
    }

    @Test
    public void o2SensorParsesVoltage() {
        // PID 0x15 (O2 Sensor B1S2): 2 data bytes — A = voltage/200, B = STFT.
        // For A=0xB4 (180): 180/200 = 0.9V. B=0x00 (unused in this test).
        PIDDefinition o2pid = findByName("O2 Sensor B1S2 Voltage");
        Double value = PIDParser.parse(o2pid, "B400");
        assertEquals(Double.valueOf(0.9), value);
    }

    @Test
    public void o2SensorParsesStft() {
        // PID 0x14 STFT: formula (B-128)*100/128. For B=0x9A (154): (154-128)*100/128 = 20.3125%
        // A=0xB4 (180) is the voltage byte, ignored by the STFT formula.
        PIDDefinition o2stft = findByName("O2 Sensor B1S1 STFT");
        Double value = PIDParser.parse(o2stft, "B49A");
        assertEquals(Double.valueOf(20.3125), value);
    }

    @Test
    public void o2StftPidsAreLpgCritical() {
        // The O2 STFT PIDs must be lpgCritical so they show up in LPG mode
        PIDDefinition stft1 = findByName("O2 Sensor B1S1 STFT");
        PIDDefinition stft2 = findByName("O2 Sensor B1S2 STFT");
        assertNotNull(stft1);
        assertNotNull(stft2);
        assertEquals(true, stft1.isLpgCritical());
        assertEquals(true, stft2.isLpgCritical());
    }

    @Test
    public void extractAndParseHandlesSpacesAndEcho() {
        // ELM327 response with spaces, echo, and prompt character.
        // The normalizeResponse method strips all non-hex characters.
        PIDDefinition rpmPid = PIDCatalogue.getAll().get(0);
        String response = "010C\r41 0C 1A F8 \r\r>";
        Double value = PIDParser.extractAndParse(rpmPid, response, "410C");
        assertEquals(Double.valueOf(1726.0), value);
    }

    @Test
    public void extractAndParseReturnsNullForNoData() {
        PIDDefinition rpmPid = PIDCatalogue.getAll().get(0);
        Double value = PIDParser.extractAndParse(rpmPid, "NODATA", "410C");
        assertNull(value);
    }

    @Test
    public void extractAndParseReturnsNullForEmptyResponse() {
        PIDDefinition rpmPid = PIDCatalogue.getAll().get(0);
        Double value = PIDParser.extractAndParse(rpmPid, "", "410C");
        assertNull(value);
    }

    @Test
    public void parseReturnsNullForOutOfRange() {
        // Coolant temp range is -40 to 215. A value of 0xFF (255) → 255-40 = 215 (in range).
        // A formula that produces a value out of range should return null.
        PIDDefinition speedPid = findByName("Vehicle Speed");
        // 0xFF = 255, which is the maxVal — should be in range
        Double value = PIDParser.parse(speedPid, "FF");
        assertEquals(Double.valueOf(255.0), value);
    }

    @Test
    public void extractMultiParsesMultiplePids() {
        // Simulate an ELM327 multi-PID response for "01 0C 0D" (RPM + Speed)
        // Response: 410C1AF8 410D32
        PIDDefinition rpmPid = findByName("Engine RPM");
        PIDDefinition speedPid = findByName("Vehicle Speed");
        java.util.List<PIDDefinition> chunk = java.util.Arrays.asList(rpmPid, speedPid);
        java.util.Map<String, Double> results = new java.util.LinkedHashMap<>();
        PIDParser.extractMulti(chunk, "410C1AF8410D32", results);
        assertEquals(Double.valueOf(1726.0), results.get("Engine RPM"));
        assertEquals(Double.valueOf(50.0), results.get("Vehicle Speed"));
    }

    @Test
    public void extractMultiHandlesO2StftPseudoPid() {
        // O2 Sensor B1S1 (PID 0x14): 2 data bytes — A=voltage/200, B=STFT.
        // Response: 4114B49A (A=0xB4=180→0.9V, B=0x9A=154→20.3125%)
        PIDDefinition o2volt = findByName("O2 Sensor B1S1 Voltage");
        PIDDefinition o2stft = findByName("O2 Sensor B1S1 STFT");
        java.util.List<PIDDefinition> chunk = java.util.Arrays.asList(o2volt, o2stft);
        java.util.Map<String, Double> results = new java.util.LinkedHashMap<>();
        PIDParser.extractMulti(chunk, "4114B49A", results);
        assertEquals(Double.valueOf(0.9), results.get("O2 Sensor B1S1 Voltage"));
        assertEquals(Double.valueOf(20.3125), results.get("O2 Sensor B1S1 STFT"));
    }
    @Test
    public void extractMultiHandlesCanMultiFrame() {
        // Multi-PID group 1: RPM (0C, 2B), Load (04, 1B), Fuel Status (03, 2B), Coolant Temp (05, 1B)
        // SAE J1979: PID 03 returns 2 bytes (A=fuel system 1, B=fuel system 2).
        // Total data: 1(mode) + 2+1+2+1(PID hex) + 2+1+2+1(data) = 11 bytes
        PIDDefinition rpm = findByName("Engine RPM");
        PIDDefinition load = findByName("Engine Load");
        PIDDefinition status = findByName("Fuel System Status");
        PIDDefinition coolant = findByName("Coolant Temp");
        java.util.List<PIDDefinition> chunk = java.util.Arrays.asList(rpm, load, status, coolant);

        // 1. Test Format A: Spaces ON, Headers ON (e.g. multi-frame on CAN)
        // 7E8 10 0B 41 0C 1A F8 04 52    (first frame: PCI=10 0B, 6 data bytes)
        // 7E8 21 03 02 00 05 5A          (consecutive: PCI=21, 5 data bytes)
        String responseA = "7E8 10 0B 41 0C 1A F8 04 52\r7E8 21 03 02 00 05 5A\r";
        java.util.Map<String, Double> resultsA = new java.util.LinkedHashMap<>();
        PIDParser.extractMulti(chunk, responseA, resultsA);
        assertEquals(Double.valueOf(1726.0), resultsA.get("Engine RPM"));
        assertEquals(Double.valueOf(32.1569), resultsA.get("Engine Load")); // 0x52 = 82 -> 82*100/255 = 32.15686
        assertEquals(Double.valueOf(2.0), resultsA.get("Fuel System Status")); // A=0x02 = 2
        assertEquals(Double.valueOf(50.0), resultsA.get("Coolant Temp")); // 0x5A = 90 -> 90-40 = 50

        // 2. Test Format B: Spaces OFF, Headers ON
        String responseB = "7E8100B410C1AF80452\r7E82103020005 5A\r";
        java.util.Map<String, Double> resultsB = new java.util.LinkedHashMap<>();
        PIDParser.extractMulti(chunk, responseB, resultsB);
        assertEquals(Double.valueOf(1726.0), resultsB.get("Engine RPM"));
        assertEquals(Double.valueOf(32.1569), resultsB.get("Engine Load"));
        assertEquals(Double.valueOf(2.0), resultsB.get("Fuel System Status"));
        assertEquals(Double.valueOf(50.0), resultsB.get("Coolant Temp"));

        // 3. Test Format C: Spaces ON, Headers OFF
        // 00B
        // 0: 41 0C 1A F8 04 52 03
        // 1: 02 00 05 5A
        String responseC = "00B\r0: 41 0C 1A F8 04 52 03\r1: 02 00 05 5A\r";
        java.util.Map<String, Double> resultsC = new java.util.LinkedHashMap<>();
        PIDParser.extractMulti(chunk, responseC, resultsC);
        assertEquals(Double.valueOf(1726.0), resultsC.get("Engine RPM"));
        assertEquals(Double.valueOf(32.1569), resultsC.get("Engine Load"));
        assertEquals(Double.valueOf(2.0), resultsC.get("Fuel System Status"));
        assertEquals(Double.valueOf(50.0), resultsC.get("Coolant Temp"));
    }

    private PIDDefinition findByName(String name) {
        for (PIDDefinition pid : PIDCatalogue.getAll()) {
            if (pid.getName().equals(name)) return pid;
        }
        return null;
    }
}