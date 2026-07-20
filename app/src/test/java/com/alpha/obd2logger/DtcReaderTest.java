package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
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
    public void testCountByteHeuristic_noDtcs() {
        // Response with count=0 and padding — should produce no DTCs
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override
            public String sendCommandRaw(String command) {
                if ("0100".equals(command)) return "41 04 00 00 00 00";
                // count=0, then padding
                if ("03".equals(command)) return "43 00 00 00 00 00";
                return "";
            }
        };
        mockDriver.connect();
        DtcReader.DtcScanResult result = DtcReader.readAllDtcs(mockDriver, false);
        assertTrue("count=0 should produce no DTCs", result.storedDtcs.isEmpty());
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
    public void fordFastScanUsesFordLabelsWithoutPhysicalAddressSweep() {
        List<String> commands = new ArrayList<>();
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                if ("0100".equals(command)) return "41 00 BE 3E B8 13";
                if ("03".equals(command)) return "7E8 06 43 04 01 33 00 00 00 00";
                return "";
            }
        };
        mockDriver.connect();
        DtcReader.setBrand(null);

        DtcReader.DtcScanResult result = DtcReader.readAllDtcs(mockDriver, true);

        assertTrue("Ford mode must apply Ford ECU labels",
                result.modules.stream().anyMatch(m -> m.moduleName.contains("PCM Response")));
        assertFalse("Fast scan must not turn on the deep physical-address sweep",
                commands.stream().anyMatch(c -> c.startsWith("ATSH7E")));
    }

    @Test
    public void msCanPreferenceDoesNotOverrideAKnownNonFordBrand() {
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("0100".equals(command)) return "41 00 BE 3E B8 13";
                if ("03".equals(command)) return "7E8 02 43 00";
                return "";
            }
        };
        mockDriver.connect();
        DtcReader.setBrand(VinBrandDetector.Brand.TOYOTA);
        try {
            DtcReader.DtcScanResult result = DtcReader.readAllDtcs(mockDriver, true);
            assertTrue(result.modules.stream().anyMatch(m -> "ECM Response".equals(m.moduleName)));
            assertFalse(result.modules.stream().anyMatch(m -> "PCM Response".equals(m.moduleName)));
        } finally {
            DtcReader.setBrand(null);
        }
    }

    @Test
    public void fordEnhancedScanNeverSendsSecurityAccess() {
        List<String> commands = new ArrayList<>();
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                return "";
            }
        };
        mockDriver.connect();

        assertTrue(DtcReader.scanEnhancedForBrand(mockDriver, VinBrandDetector.Brand.FORD).isEmpty());
        assertFalse("SecurityAccess service must never be sent by the read-only scanner",
                commands.contains("27"));
    }

    @Test
    public void nonFordDeepScanDoesNotApplyFordLabels() {
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("0100".equals(command)) return "41 00 BE 3E B8 13";
                if ("03".equals(command)) return "7E8 06 43 04 01 33 00 00 00 00";
                return "";
            }
        };
        mockDriver.connect();
        DtcReader.setBrand(null);

        DtcReader.DtcScanResult result = DtcReader.readAllDtcsDeep(mockDriver, false);

        assertTrue("A non-Ford deep scan must retain generic labels",
                result.modules.stream().anyMatch(m -> "ECU 0x7E8 (HS-CAN)".equals(m.moduleName)));
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

    @Test
    public void testScanSeparatesStoredPendingAndPermanentCodes() {
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("0100".equals(command)) return "41 00 BE 3E B8 13";
                if ("03".equals(command)) return "43 01 01 71";
                if ("07".equals(command)) return "47 01 03 01";
                if ("0A".equals(command)) return "4A 01 04 20";
                return "OK";
            }
        };
        mockDriver.connect();

        DtcReader.DtcScanResult result = DtcReader.readAllDtcs(mockDriver, false);

        assertEquals("P0171", result.storedDtcs.get(0).getCode());
        assertEquals("P0301", result.pendingDtcs.get(0).getCode());
        assertEquals("P0420", result.permanentDtcs.get(0).getCode());
        assertEquals(1, result.protocolsResponded);
    }

    @Test
    public void quickPlanKeepsTheConnectedProtocolAndRejectsStatusText() {
        List<String> commands = new ArrayList<>();
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                if ("0100".equals(command) || "03".equals(command)) return "STOPPED";
                return "OK";
            }
        };
        driver.connect();
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.QUICK,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                null, null, true, true);

        DtcReader.DtcScanResult result = DtcReader.readDtcs(driver, plan);

        assertFalse("Quick must not restart AUTO protocol discovery",
                commands.contains("ATSP0"));
        assertEquals(0, result.protocolsResponded);
        assertTrue(result.modules.isEmpty());
        assertTrue(result.storedDtcs.isEmpty());
    }

    @Test
    public void mode01OnlyResponseCannotBePublishedAsCleanDtcState() {
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("0100".equals(command)) return "41 00 BE 3E B8 13";
                if ("03".equals(command) || "07".equals(command)
                        || "0A".equals(command)) return "NO DATA";
                return "OK";
            }
        };
        driver.connect();
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.QUICK,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                null, null, false, false);

        DtcReader.DtcScanResult result = DtcReader.readDtcs(driver, plan);

        assertEquals("the bus itself did answer Mode 01", 1, result.protocolsResponded);
        assertFalse("without positive Mode 03/07/0A, empty codes are not a clean result",
                result.hasValidatedDtcResponse());
    }

    @Test
    public void modeSpecificCompletenessDoesNotTurnTimeoutsIntoZeroCodes() {
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("0100".equals(command)) return "41 00 BE 3E B8 13";
                if ("03".equals(command)) return "43 00 00 00";
                if ("07".equals(command) || "0A".equals(command)) return "NO DATA";
                return "OK";
            }
        };
        driver.connect();
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.QUICK,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                null, null, false, false);

        DtcReader.DtcScanResult result = DtcReader.readDtcs(driver, plan);

        assertTrue(result.scanCompleted);
        assertFalse(result.scanCancelled);
        assertTrue("Mode 03 was positively verified",
                result.hasCompleteStoredSnapshot(true));
        assertFalse("Mode 07 timeout remains unknown, not zero",
                result.hasCompletePendingSnapshot(true));
        assertFalse("Mode 0A timeout remains unknown, not zero",
                result.hasCompletePermanentSnapshot(true));
    }

    @Test
    public void interruptedScanIsMarkedCancelledAndNeverComplete() {
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("0100".equals(command)) {
                    Thread.currentThread().interrupt();
                    return "41 00 BE 3E B8 13";
                }
                return "NO DATA";
            }
        };
        driver.connect();
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.QUICK,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                null, null, false, false);

        try {
            DtcReader.DtcScanResult result = DtcReader.readDtcs(driver, plan);

            assertTrue(result.scanCancelled);
            assertFalse(result.scanCompleted);
            assertFalse(result.hasCompleteStoredSnapshot(true));
        } finally {
            // Preserve the production assertion while keeping JUnit's worker clean.
            Thread.interrupted();
        }
    }

    @Test
    public void rejectedProtocolSelectionIsNeverProbedOrMislabelled() {
        List<String> commands = new ArrayList<>();
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                if (command.startsWith("ATSP")) return "?";
                if ("0100".equals(command)) return "41 00 BE 3E B8 13";
                if ("03".equals(command)) return "43 00";
                return "OK";
            }
        };
        driver.connect();
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.FULL,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                null, null, true, true);

        DtcReader.DtcScanResult result = DtcReader.readDtcs(driver, plan);

        int firstRejected = commands.indexOf("ATSP1");
        int nextSelection = commands.indexOf("ATSP2");
        assertTrue(firstRejected >= 0 && nextSelection > firstRejected);
        assertFalse("A rejected ATSP1 must not run an OBD probe on the old bus",
                commands.subList(firstRejected + 1, nextSelection).contains("0100"));
        assertEquals("Only CURRENT can respond; rejected protocols stay non-responding",
                1, result.protocolsResponded);
        assertFalse(commands.stream().anyMatch(c -> c.matches("ATSP[A-D]")));
        assertTrue("Full scan must include the previously omitted KWP slow protocol",
                commands.contains("ATSP4"));
        assertTrue(commands.contains("ATSP9"));
    }

    @Test
    public void unverifiedProtocolSelectionIsNeverUsedAsReusableEvidence() {
        List<String> commands = new ArrayList<>();
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                if (command.startsWith("ATSP")) return "OK";
                if ("ATDPN".equals(command)) return "OK"; // missing protocol number
                if ("0100".equals(command)) return "41 00 BE 3E B8 13";
                if ("03".equals(command)) return "43 00";
                return "OK";
            }
        };
        driver.connect();
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.FULL,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                null, null, true, true);

        DtcReader.DtcScanResult result = DtcReader.readDtcs(driver, plan);

        assertEquals("Only CURRENT is proven when ATDPN is unparseable",
                1, result.protocolsResponded);
        int dpn = commands.indexOf("ATDPN");
        int nextSelection = commands.indexOf("ATSP2");
        assertTrue(dpn >= 0 && nextSelection > dpn);
        assertFalse("No OBD request may follow an unverified ATSP selection",
                commands.subList(dpn + 1, nextSelection).contains("0100"));
    }

    @Test
    public void reconnectDuringScanCancelsBeforePublishingOldConnectionData() {
        List<String> commands = new ArrayList<>();
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() {
                connected = true;
                markPhysicalConnectionEstablished();
                return true;
            }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                if ("0100".equals(command)) {
                    // Same Java object, different physical connection.
                    markPhysicalConnectionEstablished();
                    return "41 00 BE 3E B8 13";
                }
                return "OK";
            }
        };
        driver.connect();
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.QUICK,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                null, null, false, false);

        DtcReader.DtcScanResult result = DtcReader.readDtcs(driver, plan);

        assertEquals(0, result.protocolsResponded);
        assertFalse("old-connection scan must stop before Mode 03", commands.contains("03"));
        assertFalse("old scan must not restore settings into the new connection",
                commands.contains("ATSP0"));
    }

    @Test
    public void interruptedScanStopsBeforeSendingCommandsAndPreservesInterrupt() {
        List<String> commands = new ArrayList<>();
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                return "OK";
            }
        };
        driver.connect();
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.QUICK,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                null, null, false, false);

        Thread.currentThread().interrupt();
        try {
            DtcReader.DtcScanResult result = DtcReader.readDtcs(driver, plan);
            assertEquals(0, result.protocolsResponded);
            assertTrue(commands.isEmpty());
            assertTrue("cancellation must preserve the interrupt flag",
                    Thread.currentThread().isInterrupted());
        } finally {
            // Do not leak the deliberate interrupt into the JUnit worker.
            Thread.interrupted();
        }
    }

    @Test
    public void setupAcknowledgementAndPositiveMarkersAreStrict() {
        assertTrue(DtcReader.isAcceptedAtCommandResponse("ATSP6\rOK\r>"));
        assertFalse(DtcReader.isAcceptedAtCommandResponse("?"));
        assertFalse(DtcReader.isAcceptedAtCommandResponse("CAN ERROR"));
        assertFalse(DtcReader.isAcceptedAtCommandResponse(""));
        assertTrue(DtcReader.hasPositiveResponse("7E8 06 41 00 BE 3E B8 13", "4100"));
        assertTrue(DtcReader.hasPositiveResponse("7E8 02 43 00", "43"));
        assertTrue("colon-suffixed CAN header",
                DtcReader.hasPositiveResponse("7E8: 02 43 00", "43"));
        assertTrue("row index after CAN header",
                DtcReader.hasPositiveResponse("7E8 0: 02 43 00", "43"));
        assertTrue("one-nibble DLC before ISO-TP data",
                DtcReader.hasPositiveResponse("7E8 8 06 41 00 BE 3E B8 13", "4100"));
        assertTrue("combined colon header, row index, and DLC",
                DtcReader.hasPositiveResponse("7E8: 0: 8 02 43 00", "43"));
        assertFalse(DtcReader.hasPositiveResponse("SEARCHING...", "4100"));
        assertFalse(DtcReader.hasPositiveResponse("7E8 03 7F 03 11", "43"));
        assertFalse("DLC metadata must not weaken negative-response rejection",
                DtcReader.hasPositiveResponse("7E8: 8 03 7F 03 11", "43"));
        assertFalse("43 in the CAN ID is not a positive Mode 03 payload",
                DtcReader.hasPositiveResponse("743 03 7F 03 11", "43"));
        assertFalse("43 later in a negative payload is not a positive response",
                DtcReader.hasPositiveResponse("7E8 04 7F 03 43 00", "43"));
        assertFalse("4100 may not be assembled across a CAN header boundary",
                DtcReader.hasPositiveResponse("410 03 7F 01 11", "4100"));
    }

    @Test
    public void testClearDtcsRequiresAckAndVerifiesStoredCodesAreGone() {
        BaseDriver clearedDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("04".equals(command)) return "44";
                if ("03".equals(command)) return "43 00 00 00";
                return "";
            }
        };
        clearedDriver.connect();
        assertTrue(DtcReader.clearDtcs(clearedDriver));

        BaseDriver unclearedDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("04".equals(command)) return "44";
                if ("03".equals(command)) return "43 01 01 71";
                return "";
            }
        };
        unclearedDriver.connect();
        assertFalse(DtcReader.clearDtcs(unclearedDriver));
    }

    @Test
    public void testClearDtcsRejectsAckWhenVerificationTimesOut() {
        BaseDriver unverifiedDriver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("04".equals(command)) return "44";
                if ("03".equals(command)) return "NO DATA";
                return "";
            }
        };
        unverifiedDriver.connect();

        assertFalse(DtcReader.clearDtcs(unverifiedDriver));
    }
}
