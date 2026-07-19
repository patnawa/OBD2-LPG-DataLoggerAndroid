package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FordModuleLiveDataReaderTest {

    @Test
    public void directLayoutsAreLimitedToVerifiedBrandProfiles() {
        assertTrue(FordModuleLiveDataReader.hasDirectModuleProfile(VinBrandDetector.Brand.FORD));
        assertTrue(FordModuleLiveDataReader.hasDirectModuleProfile(VinBrandDetector.Brand.TOYOTA));
        assertTrue(FordModuleLiveDataReader.hasDirectModuleProfile(VinBrandDetector.Brand.MAZDA));
        assertTrue(FordModuleLiveDataReader.hasDirectModuleProfile(VinBrandDetector.Brand.NISSAN));
        assertFalse(FordModuleLiveDataReader.hasDirectModuleProfile(VinBrandDetector.Brand.MITSUBISHI));
        assertTrue(FordModuleLiveDataReader.hasDirectModuleProfile(VinBrandDetector.Brand.HONDA));
        assertTrue(FordModuleLiveDataReader.hasDirectModuleProfile(VinBrandDetector.Brand.CHEVROLET));
        assertFalse(FordModuleLiveDataReader.hasDirectModuleProfile(VinBrandDetector.Brand.BMW));
        assertFalse(FordModuleLiveDataReader.hasDirectModuleProfile(VinBrandDetector.Brand.UNKNOWN));
    }

    @Test
    public void readsPhysicallyAddressedPcmValuesAndModuleStatus() {
        List<String> commands = new ArrayList<>();
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            private String header = "";

            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                if (command.startsWith("ATSH")) {
                    header = command.substring(4);
                    return "OK";
                }
                if ("03".equals(command)) {
                    if ("7E0".equals(header)) return "7E8 02 43 00";
                    if ("7E1".equals(header)) return "7E9 02 43 00";
                    if ("7E2".equals(header)) return "7EA 02 43 00";
                }
                if ("7E0".equals(header)) {
                    if ("0105".equals(command)) return "7E8 03 41 05 5A"; // 50 C
                    if ("010F".equals(command)) return "7E8 03 41 0F 32"; // 10 C
                    if ("015C".equals(command)) return "7E8 03 41 5C 64"; // 60 C
                    if ("010C".equals(command)) return "7E8 04 41 0C 1F 40"; // 2000 rpm
                    if ("010D".equals(command)) return "7E8 03 41 0D 64"; // 100 km/h
                }
                return "";
            }
        };
        driver.connect();

        FordModuleLiveDataReader.Snapshot snapshot = FordModuleLiveDataReader.read(driver);

        FordModuleLiveDataReader.ModuleStatus pcm = snapshot.moduleNamed("PCM — Powertrain");
        FordModuleLiveDataReader.ModuleStatus tcm = snapshot.moduleNamed("TCM — Transmission");
        FordModuleLiveDataReader.ModuleStatus abs = snapshot.moduleNamed("ABS — Brakes");
        assertNotNull(pcm);
        assertTrue(pcm.responded());
        assertTrue(tcm.responded());
        assertTrue(abs.responded());
        assertEquals("CAN header/PCI bytes must not become phantom DTCs",
                0, pcm.getStoredDtcCount());
        assertEquals(0, tcm.getStoredDtcCount());
        assertEquals(0, abs.getStoredDtcCount());
        assertEquals(50.0, pcm.getMetrics().get(0).getValue(), 0.001);
        assertEquals(10.0, pcm.getMetrics().get(1).getValue(), 0.001);
        assertEquals(60.0, pcm.getMetrics().get(2).getValue(), 0.001);
        assertEquals(2000.0, pcm.getMetrics().get(3).getValue(), 0.001);
        assertEquals(100.0, pcm.getMetrics().get(4).getValue(), 0.001);
        assertTrue(commands.contains("ATSH7E0"));
        assertTrue(commands.contains("ATSH7E1"));
        assertTrue(commands.contains("ATSH7E2"));
        assertTrue(commands.contains("ATCRA7E8"));
        assertTrue(commands.contains("ATCRA7E9"));
        assertTrue(commands.contains("ATCRA7EA"));
        assertFalse("The read-only monitor must not enter a UDS diagnostic session",
                commands.contains("10"));
        assertFalse("The read-only monitor must not request security access",
                commands.contains("27"));
        assertFalse("The read-only monitor must not write ECU data",
                commands.contains("2E"));
        assertFalse("The monitor must not issue unverified UDS DID reads",
                commands.stream().anyMatch(c -> c.startsWith("22")));
    }

    @Test
    public void unavailablePcmPidIsReportedInsteadOfInventingAValue() {
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if ("03".equals(command)) return "NO DATA";
                return "";
            }
        };
        driver.connect();

        FordModuleLiveDataReader.ModuleStatus pcm = FordModuleLiveDataReader.read(driver)
                .moduleNamed("PCM — Powertrain");

        assertNotNull(pcm);
        assertFalse(pcm.responded());
        for (FordModuleLiveDataReader.Metric metric : pcm.getMetrics()) {
            assertFalse(metric.isAvailable());
        }
    }

    @Test
    public void mitsubishiFallsBackToStandardObdInsteadOfGuessingDieselAddress() {
        List<String> commands = new ArrayList<>();
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) {
                return "05".equals(pidDef.getPidHex()) ? 72.0 : null;
            }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                return "";
            }
        };
        driver.connect();

        FordModuleLiveDataReader.Snapshot snapshot = FordModuleLiveDataReader.read(driver,
                VinBrandDetector.Brand.MITSUBISHI);

        assertEquals(1, snapshot.getModules().size());
        assertNotNull(snapshot.moduleNamed("OBD-II Powertrain"));
        assertFalse("Do not infer a Mitsubishi diesel address from brand alone",
                commands.stream().anyMatch(c -> c.startsWith("ATSH")));
    }

    @Test
    public void countsDtcsOnlyAfterRemovingExpectedCanAndIsoTpHeaders() {
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            private String header = "";
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if (command.startsWith("ATSH")) {
                    header = command.substring(4);
                    return "OK";
                }
                if ("03".equals(command) && "7E0".equals(header)) {
                    return "7E8 04 43 01 01 71";
                }
                return "";
            }
        };
        driver.connect();

        FordModuleLiveDataReader.ModuleStatus pcm = FordModuleLiveDataReader.read(driver)
                .moduleNamed("PCM — Powertrain");

        assertNotNull(pcm);
        assertTrue(pcm.responded());
        assertEquals(1, pcm.getStoredDtcCount());
    }

    @Test
    public void rejectsAServiceReplyFromTheWrongResponseCanId() {
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            private String header = "";
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) { return null; }
            @Override public String sendCommandRaw(String command) {
                if (command.startsWith("ATSH")) {
                    header = command.substring(4);
                    return "OK";
                }
                if ("03".equals(command) && "7E0".equals(header)) {
                    return "7E9 02 43 00";
                }
                return "";
            }
        };
        driver.connect();

        FordModuleLiveDataReader.ModuleStatus pcm = FordModuleLiveDataReader.read(driver)
                .moduleNamed("PCM — Powertrain");

        assertNotNull(pcm);
        assertFalse("7E0 requests must be confirmed only by 7E8 replies", pcm.responded());
        assertEquals(0, pcm.getStoredDtcCount());
    }

    @Test
    public void unknownBrandUsesFunctionalStandardObdWithoutPhysicalModuleCommands() {
        List<String> commands = new ArrayList<>();
        BaseDriver driver = new BaseDriver(new LoggerConfig()) {
            @Override public boolean connect() { connected = true; return true; }
            @Override public void disconnect() { connected = false; }
            @Override public Double queryPid(PIDDefinition pidDef) {
                if ("05".equals(pidDef.getPidHex())) return 88.0;
                if ("0C".equals(pidDef.getPidHex())) return 750.0;
                return null;
            }
            @Override public String sendCommandRaw(String command) {
                commands.add(command);
                return "";
            }
        };
        driver.connect();

        FordModuleLiveDataReader.Snapshot snapshot = FordModuleLiveDataReader.read(driver,
                VinBrandDetector.Brand.UNKNOWN);

        FordModuleLiveDataReader.ModuleStatus powertrain = snapshot.moduleNamed("OBD-II Powertrain");
        assertNotNull(powertrain);
        assertTrue(powertrain.responded());
        assertEquals(88.0, powertrain.getMetrics().get(0).getValue(), 0.001);
        assertEquals(750.0, powertrain.getMetrics().get(3).getValue(), 0.001);
        assertFalse("Generic fallback must not guess a physical CAN header",
                commands.stream().anyMatch(c -> c.startsWith("ATSH")));
    }
}
