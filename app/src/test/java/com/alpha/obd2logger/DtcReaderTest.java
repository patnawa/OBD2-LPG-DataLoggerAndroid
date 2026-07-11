package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class DtcReaderTest {

    @Test
    public void testDtcParsingWithoutHeaders() {
        // Mock a driver that responds to DTC requests
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override
            public boolean connect() {
                connected = true;
                return true;
            }

            @Override
            public void disconnect() {
                connected = false;
            }

            @Override
            public Double queryPid(PIDDefinition pidDef) {
                return null;
            }

            @Override
            public String sendCommandRaw(String command) {
                // Protocol probe — must return a valid response so scanSingleBus
                // proceeds to the DTC scan. "41" = Mode 01 response prefix.
                if ("0100".equals(command)) {
                    return "41 04 00 00 00 00"; // PIDs 01-20 supported
                }
                // Mode 03 — Stored DTCs
                if ("03".equals(command)) {
                    // Standard response: mode 43, 2 DTCs: P0171 (01 71) and P0300 (03 00)
                    return "43 02 01 71 03 00";
                }
                return "";
            }
        };
        mockDriver.connect();

        // Perform fast scan
        DtcReader.DtcScanResult result = DtcReader.readAllDtcs(mockDriver, false);

        // Verify that the parser extracted P0171 and P0300
        List<DtcCode> stored = result.storedDtcs;
        assertEquals("Should extract exactly 2 stored DTCs", 2, stored.size());
        assertEquals("First DTC should be P0171", "P0171", stored.get(0).getCode());
        assertEquals("Second DTC should be P0300", "P0300", stored.get(1).getCode());
    }

    @Test
    public void testNoDtcsResponse() {
        // Response with count=0 and all-zero padding — should produce no DTCs
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override
            public String sendCommandRaw(String command) {
                if ("0100".equals(command)) return "41 04 00 00 00 00";
                if ("03".equals(command)) return "43 00 00 00 00 00";
                return "";
            }
        };
        mockDriver.connect();
        DtcReader.DtcScanResult result = DtcReader.readAllDtcs(mockDriver, false);
        assertTrue("No DTCs should be extracted from 43 00 00 00 00 00", result.storedDtcs.isEmpty());
    }

    @Test
    public void testCountByteHeuristic_noCountByte() {
        // Some ECUs (certain Honda/Nissan) omit the count byte: "43 01 71 03 00"
        // Without the heuristic, the old code would skip "01" (thinking it's a count),
        // then parse "71 03" as a DTC and drop the real P0171.
        // With the heuristic, count byte doesn't match (1*4 != 8 remaining), so
        // parsing starts from "01 71" → P0171, then "03 00" → P0300.
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override
            public String sendCommandRaw(String command) {
                if ("0100".equals(command)) return "41 04 00 00 00 00";
                // No count byte: 43 + 01 71 03 00 (4 data bytes = 2 DTCs)
                if ("03".equals(command)) return "43 01 71 03 00";
                return "";
            }
        };
        mockDriver.connect();
        DtcReader.DtcScanResult result = DtcReader.readAllDtcs(mockDriver, false);
        List<DtcCode> stored = result.storedDtcs;
        assertEquals("Should extract 2 DTCs even without count byte", 2, stored.size());
        assertEquals("First DTC should be P0171", "P0171", stored.get(0).getCode());
        assertEquals("Second DTC should be P0300", "P0300", stored.get(1).getCode());
    }

    @Test
    public void testCountByteHeuristic_validCountByte() {
        // Standard response WITH valid count byte: "43 02 01 71 03 00"
        // count=2, 2*4=8 hex chars remaining = correct → skip count byte
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override
            public String sendCommandRaw(String command) {
                if ("0100".equals(command)) return "41 04 00 00 00 00";
                if ("03".equals(command)) return "43 02 01 71 03 00";
                return "";
            }
        };
        mockDriver.connect();
        DtcReader.DtcScanResult result = DtcReader.readAllDtcs(mockDriver, false);
        List<DtcCode> stored = result.storedDtcs;
        assertEquals("Should extract 2 DTCs with valid count byte", 2, stored.size());
        assertEquals("P0171", stored.get(0).getCode());
        assertEquals("P0300", stored.get(1).getCode());
    }

    @Test
    public void testBrandAwareEcuNames_toyotaNotOverwrittenByNissan() {
        // When brand is set to TOYOTA, 0x7E0 should show "Toyota", not "Nissan".
        // Before the fix, Nissan's put() at static init would overwrite Toyota's.
        DtcReader.setBrand(VinBrandDetector.Brand.TOYOTA);
        // moduleNameForCanId is private — test via ModuleInfo.Builder which calls it
        DtcReader.ModuleInfo module = new DtcReader.ModuleInfo.Builder(0x7E0, "HS-CAN (auto)", false).build();
        assertNotNull(module.moduleName);
        assertTrue("Toyota brand should show Toyota ECU name, got: " + module.moduleName,
                module.moduleName.contains("Toyota"));

        // Switch to Nissan and verify the name changes
        DtcReader.setBrand(VinBrandDetector.Brand.NISSAN);
        DtcReader.ModuleInfo nissanModule = new DtcReader.ModuleInfo.Builder(0x7E0, "HS-CAN (auto)", false).build();
        assertTrue("Nissan brand should show Nissan ECU name, got: " + nissanModule.moduleName,
                nissanModule.moduleName.contains("Nissan"));

        // Reset for other tests
        DtcReader.setBrand(null);
    }

    @Test
    public void testParseDtcResponseLegacy_noDtcs() {
        // The legacy parseDtcResponse should handle "43 00 00 00 00 00" → empty list
        List<DtcCode> codes = DtcReader.parseDtcResponse("43 00 00 00 00 00", "43");
        assertNotNull(codes);
        assertTrue("No DTCs from 43 00 00 00 00 00", codes.isEmpty());
    }

    @Test
    public void testParseDtcResponseLegacy_noDataWithSpace() {
        // "NO DATA" (with space) should be filtered, not parsed as hex
        List<DtcCode> codes = DtcReader.parseDtcResponse("NO DATA\n43 02 01 71 03 00", "43");
        assertEquals("Should parse 2 DTCs from the valid line, ignoring NO DATA", 2, codes.size());
    }
}
