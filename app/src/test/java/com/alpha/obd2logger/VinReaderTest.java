package com.alpha.obd2logger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class VinReaderTest {

    @Test
    public void testParseStandardVin() {
        // Mode 09 PID 02 response with headers off, single line or merged
        String response = "49 02 01 31 46 4D 43 55 30 47 31 31 46 4B 30 30 30 30 30 31";
        String vin = VinReader.parseVinResponse(response);
        assertNotNull(vin);
        assertEquals("1FMCU0G11FK000001", vin);
    }

    @Test
    public void testParseMultiFrameVinWithHeaders() {
        // CAN multi-frame response with headers: e.g. 7E8 10 14 49 02 01 followed by consecutive frames
        String response = "7E8 10 14 49 02 01 31 46 4D\r7E8 21 43 55 30 47 31 31 46\r7E8 22 4B 30 30 30 30 30 31\r";
        String vin = VinReader.parseVinResponse(response);
        assertNotNull(vin);
        assertEquals("1FMCU0G11FK000001", vin);
    }

    @Test
    public void testParseMultiFrameVinWithoutHeaders() {
        // Multi-frame response without headers (Format C/D): prefixed with "0:", "1:", "2:"
        String response = "0: 49 02 01 31 46 4D 43 55 30\r\n1: 47 31 31 46 4B 30 30 30\r\n2: 30 30 31\r\n";
        String vin = VinReader.parseVinResponse(response);
        assertNotNull(vin);
        assertEquals("1FMCU0G11FK000001", vin);
    }

    @Test
    public void testParseToyotaCompactNumberedResponse() {
        String response = "014\r0:4902014D523046\r1:5A3239473930313233\r2:34353637\r>";
        assertEquals("MR0FZ29G901234567", VinReader.parseVinResponse(response));
        assertEquals(VinBrandDetector.Brand.TOYOTA,
                VinBrandDetector.detect("MR0FZ29G901234567"));
    }

    @Test
    public void testRejectsForbiddenVinCharacters() {
        String response = "49 02 01 4D 52 30 46 5A 32 39 47 39 30 31 32 33 34 35 49";
        assertNull("VIN alphabet excludes I/O/Q", VinReader.parseVinResponse(response));
    }

    @Test
    public void slowToyotaVinRetriesWithExtendedTimeoutAndRestoresPolling() {
        LoggerConfig config = new LoggerConfig();
        config.connectionTimeoutMs = 2_000;
        SlowVinDriver driver = new SlowVinDriver(config);

        assertEquals("MR0FZ29G901234567", VinReader.readVin(driver));
        assertEquals(2, driver.vinAttempts);
        assertTrue(driver.sent.contains("ATSTFF"));
        assertTrue(driver.sent.contains("ATCAF1"));
        assertTrue(driver.sent.contains("ATCFC1"));
        assertTrue(driver.sent.contains("ATH1"));
        assertTrue(driver.sent.contains("ATS1"));
        assertTrue(driver.sent.contains("ATH0"));
        assertTrue(driver.sent.contains("ATS0"));
        assertTrue(driver.sent.contains("ATST32"));
        assertEquals(2_000, config.connectionTimeoutMs);
    }

    @Test
    public void vinFallsBackToToyotaEngineEcuWhenFunctionalMode09IsIgnored() {
        LoggerConfig config = new LoggerConfig();
        DirectToyotaVinDriver driver = new DirectToyotaVinDriver(config);

        assertEquals("MR0FZ29G901234567", VinReader.readVin(driver));
        assertEquals(3, driver.vinAttempts);
        assertTrue(driver.sent.contains("ATDPN"));
        assertTrue(driver.sent.contains("ATSH7E0"));
        assertTrue(driver.sent.contains("ATCRA7E8"));
        assertTrue(driver.sent.contains("ATCRA"));
        assertTrue(driver.sent.contains("ATAR"));
        assertTrue(driver.sent.contains("ATSH7DF"));
    }

    @Test
    public void detectsOnlyElevenBitCanForPhysicalVinFallback() {
        assertTrue(ElmDriver.isElevenBitCanProtocol("A6\r>"));
        assertTrue(ElmDriver.isElevenBitCanProtocol("8\r>"));
        assertFalse(ElmDriver.isElevenBitCanProtocol("A7\r>"));
        assertFalse(ElmDriver.isElevenBitCanProtocol("5\r>"));
    }

    private static final class SlowVinDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();
        int vinAttempts;

        SlowVinDriver(LoggerConfig config) {
            super(config);
            connected = true;
            vlinkerType = VLinkerOptimizer.DeviceType.VLINKER_MC_BT;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override
        protected String sendCommand(String command) {
            sent.add(command);
            if ("0902".equals(command)) {
                vinAttempts++;
                if (vinAttempts == 1) return "NO DATA\r>";
                // The VIN retry now uses ATH1 + ATS1, so it must parse raw
                // CAN/ISO-TP frames rather than adapter-specific numbered rows.
                return "7E8 10 14 49 02 01 4D 52 30\r"
                        + "7E8 21 46 5A 32 39 47 39 30\r"
                        + "7E8 22 31 32 33 34 35 36 37\r>";
            }
            return "OK\r>";
        }
    }

    private static final class DirectToyotaVinDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();
        int vinAttempts;

        DirectToyotaVinDriver(LoggerConfig config) {
            super(config);
            connected = true;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override
        protected String sendCommand(String command) {
            sent.add(command);
            if ("ATDPN".equals(command)) return "A6\r>";
            if ("0902".equals(command)) {
                vinAttempts++;
                if (vinAttempts < 3) return "NO DATA\r>";
                return "7E8 10 14 49 02 01 4D 52 30\r"
                        + "7E8 21 46 5A 32 39 47 39 30\r"
                        + "7E8 22 31 32 33 34 35 36 37\r>";
            }
            return "OK\r>";
        }
    }
}
