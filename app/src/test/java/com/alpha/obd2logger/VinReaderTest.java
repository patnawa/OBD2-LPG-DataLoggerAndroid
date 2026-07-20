package com.alpha.obd2logger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
    public void parsesCompatibilityResponseWithoutMode09RecordByte() {
        String response = "49 02 4D 52 30 46 5A 32 39 47 39 30 31 32 33 34 35 36 37";
        assertEquals("MR0FZ29G901234567", VinReader.parseVinResponse(response));
    }

    @Test
    public void rejectsVinLookingAsciiWithoutExpectedPositiveResponse() {
        String vinAscii = "4D 52 30 46 5A 32 39 47 39 30 31 32 33 34 35 36 37";

        assertNull("Bare ASCII must not be accepted as a Mode 09 response",
                VinReader.parseVinResponse(vinAscii));
        assertNull("A negative response followed by noise is not a VIN response",
                VinReader.parseVinResponse("7F 09 11 " + vinAscii));
        assertNull("Mode 09 data for another InfoType must not be parsed as VIN",
                VinReader.parseVinResponse("49 04 01 " + vinAscii));
        assertNull("UDS data for a different DID must not be parsed as VIN",
                VinReader.parseUdsVinResponse("62 F1 87 " + vinAscii));
    }

    @Test
    public void parsesColonCanHeadersWithDlcAndVlinkerRowIndexes() {
        String withDlc = "7E8: 8 10 14 49 02 01 4D 52 30\r"
                + "7E8: 8 21 46 5A 32 39 47 39 30\r"
                + "7E8: 8 22 31 32 33 34 35 36 37\r";
        assertEquals("MR0FZ29G901234567", VinReader.parseVinResponse(withDlc));

        String withRows = "7E8 0: 49 02 01 4D 52 30 46\r"
                + "7E8 1: 5A 32 39 47 39 30 31 32\r"
                + "7E8 2: 33 34 35 36 37\r";
        assertEquals("MR0FZ29G901234567", VinReader.parseVinResponse(withRows));
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

    @Test
    public void parsesVinWhenSeveralEcusAnswerTheFunctionalRequest() {
        // 7E8 (engine) and 7EA both answer a functional 0902. The adapter emits
        // their frames interleaved; grouping by CAN header keeps them separate.
        String response = "7E8 10 14 49 02 01 4D 52 30\r"
                + "7EA 10 14 49 02 01 4D 52 30\r"
                + "7E8 21 46 5A 32 39 47 39 30\r"
                + "7EA 21 46 5A 32 39 47 39 30\r"
                + "7E8 22 31 32 33 34 35 36 37\r"
                + "7EA 22 31 32 33 34 35 36 37\r>";
        assertEquals("MR0FZ29G901234567", VinReader.parseVinResponse(response));
    }

    @Test
    public void parsesUdsReadDataByIdentifierVin() {
        String response = "7E8 10 14 62 F1 90 4D 52 30\r"
                + "7E8 21 46 5A 32 39 47 39 30\r"
                + "7E8 22 31 32 33 34 35 36 37\r>";
        assertEquals("MR0FZ29G901234567", VinReader.parseUdsVinResponse(response));
    }

    @Test
    public void vinFallsBackToUdsWhenToyotaDoesNotImplementMode09() {
        LoggerConfig config = new LoggerConfig();
        UdsOnlyVinDriver driver = new UdsOnlyVinDriver(config);

        assertEquals("MR0FZ29G901234567", VinReader.readVin(driver));
        assertEquals(3, driver.mode09Attempts);
        assertTrue(driver.sent.contains("22F190"));
        assertTrue(driver.sent.contains("ATSH7E0"));
        // Polling state is restored after the UDS request too.
        assertTrue(driver.sent.contains("ATSH7DF"));
    }

    @Test
    public void udsVinTriesTransmissionEcuWhenEngineEcuRejectsTheIdentifier() {
        LoggerConfig config = new LoggerConfig();
        TransmissionUdsVinDriver driver = new TransmissionUdsVinDriver(config);

        assertEquals("MR0FZ29G901234567", VinReader.readVin(driver));
        assertTrue(driver.sent.contains("ATSH7E0"));
        assertTrue(driver.sent.contains("ATSH7E1"));
        assertTrue(driver.sent.contains("ATCRA7E9"));
    }

    @Test
    public void toyotaVinFallbackReachesGatewayAt7e2() {
        LoggerConfig config = new LoggerConfig();
        GatewayUdsVinDriver driver = new GatewayUdsVinDriver(config);

        assertEquals("MR0FZ29G901234567", VinReader.readVin(driver));
        assertTrue(driver.sent.contains("ATSH7E0"));
        assertTrue(driver.sent.contains("ATSH7E1"));
        assertTrue(driver.sent.contains("ATSH7E2"));
        assertTrue(driver.sent.contains("ATCRA7EA"));
        assertEquals("responsePending should cause one bounded retry",
                2, driver.gatewayUdsAttempts);
        assertFalse("Sweep must stop after the first valid VIN",
                driver.sent.contains("ATSH7E3"));
        assertEquals("Polling header must be restored", "7DF", driver.header);
    }

    @Test
    public void allFailPhysicalSweepIsBoundedToLegislatedAddresses() {
        BoundedSweepDriver driver = new BoundedSweepDriver(new LoggerConfig(), StopMode.NONE);

        assertNull(VinReader.readVin(driver));

        assertEquals(8, driver.physicalMode09Attempts);
        assertEquals(8, driver.physicalUdsAttempts);
        assertTrue(driver.sent.contains("ATSH7E7"));
        assertFalse(driver.sent.contains("ATSH7F0"));
        assertEquals("7DF", driver.header);
    }

    @Test
    public void physicalSweepStopsImmediatelyAfterDisconnect() {
        BoundedSweepDriver driver = new BoundedSweepDriver(
                new LoggerConfig(), StopMode.DISCONNECT);

        assertNull(VinReader.readVin(driver));

        assertEquals(1, driver.physicalMode09Attempts);
        assertEquals(0, driver.physicalUdsAttempts);
        assertFalse(driver.sent.contains("ATSH7E1"));
        assertEquals("Restore must put functional addressing back", "7DF", driver.header);
    }

    @Test
    public void physicalSweepHonorsMonotonicTimeBudget() {
        BoundedSweepDriver driver = new BoundedSweepDriver(
                new LoggerConfig(), StopMode.ADVANCE_CLOCK);

        assertNull(VinReader.readVin(driver, driver.clock::get, 10L));

        assertEquals("Budget expiry during setup must prevent the data request",
                0, driver.physicalMode09Attempts);
        assertEquals(0, driver.physicalUdsAttempts);
        assertFalse(driver.sent.contains("ATSH7E1"));
        assertEquals("Each in-flight setup command is clamped to remaining time",
                1, driver.physicalSetupTimeoutMs);
        assertTrue("Formatting cleanup remains mandatory after budget expiry",
                driver.sent.stream().filter("ATH0"::equals).count() >= 2);
        assertTrue(driver.sent.stream().filter("ATST32"::equals).count() >= 2);
        assertEquals("7DF", driver.header);
    }

    @Test
    public void interruptedPhysicalReadRestoresAdapterAndPreservesInterrupt() {
        BoundedSweepDriver driver = new BoundedSweepDriver(
                new LoggerConfig(), StopMode.INTERRUPT);
        try {
            assertNull(VinReader.readVin(driver));
            assertTrue("Caller must observe lifecycle cancellation",
                    Thread.currentThread().isInterrupted());
            assertEquals(1, driver.physicalMode09Attempts);
            assertEquals(0, driver.physicalUdsAttempts);
            assertTrue(driver.sent.contains("ATCRA"));
            assertTrue(driver.sent.contains("ATSH7DF"));
            assertEquals("7DF", driver.header);
        } finally {
            // Do not leak the deliberately-set flag into later JUnit tests.
            Thread.interrupted();
        }
    }

    /** Answers Mode 01 but has no Mode 09 at all; VIN only via UDS 22 F1 90. */
    private static final class UdsOnlyVinDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();
        int mode09Attempts;

        UdsOnlyVinDriver(LoggerConfig config) {
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
                mode09Attempts++;
                return "NO DATA\r>";
            }
            if ("22F190".equals(command)) {
                return "7E8 10 14 62 F1 90 4D 52 30\r"
                        + "7E8 21 46 5A 32 39 47 39 30\r"
                        + "7E8 22 31 32 33 34 35 36 37\r>";
            }
            return "OK\r>";
        }
    }

    /** Engine ECU returns a negative response; the TCM holds the VIN. */
    private static final class TransmissionUdsVinDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();
        private String header = "7DF";

        TransmissionUdsVinDriver(LoggerConfig config) {
            super(config);
            connected = true;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override
        protected String sendCommand(String command) {
            sent.add(command);
            if (command.startsWith("ATSH")) {
                header = command.substring(4);
                return "OK\r>";
            }
            if ("ATDPN".equals(command)) return "A6\r>";
            if ("0902".equals(command)) return "NO DATA\r>";
            if ("22F190".equals(command)) {
                // 7F 22 31 = requestOutOfRange from the engine ECU.
                if (!"7E1".equals(header)) return "7E8 03 7F 22 31\r>";
                return "7E9 10 14 62 F1 90 4D 52 30\r"
                        + "7E9 21 46 5A 32 39 47 39 30\r"
                        + "7E9 22 31 32 33 34 35 36 37\r>";
            }
            return "OK\r>";
        }
    }

    /** Toyota-style gateway at 7E2 owns DID F190; engine and TCM do not. */
    private static final class GatewayUdsVinDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();
        private String header = "7DF";
        private int gatewayUdsAttempts;

        GatewayUdsVinDriver(LoggerConfig config) {
            super(config);
            connected = true;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override
        protected String sendCommand(String command) {
            sent.add(command);
            if (command.startsWith("ATSH")) {
                header = command.substring(4);
                return "OK\r>";
            }
            if ("ATDPN".equals(command)) return "A6\r>";
            if ("0902".equals(command)) return "NO DATA\r>";
            if ("22F190".equals(command)) {
                if (!"7E2".equals(header)) return "7E8 03 7F 22 31\r>";
                gatewayUdsAttempts++;
                if (gatewayUdsAttempts == 1) return "7EA 03 7F 22 78\r>";
                return "7EA 10 14 62 F1 90 4D 52 30\r"
                        + "7EA 21 46 5A 32 39 47 39 30\r"
                        + "7EA 22 31 32 33 34 35 36 37\r>";
            }
            return "OK\r>";
        }
    }

    private enum StopMode {
        NONE,
        DISCONNECT,
        ADVANCE_CLOCK,
        INTERRUPT
    }

    /** No-data CAN adapter used to prove every physical fallback exit path. */
    private static final class BoundedSweepDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();
        final AtomicLong clock = new AtomicLong();
        final StopMode stopMode;
        String header = "7DF";
        int physicalMode09Attempts;
        int physicalUdsAttempts;
        int physicalSetupTimeoutMs = -1;
        boolean physicalSweepStarted;

        BoundedSweepDriver(LoggerConfig config, StopMode stopMode) {
            super(config);
            this.stopMode = stopMode;
            connected = true;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override
        protected String sendCommand(String command) {
            sent.add(command);
            if (command.startsWith("ATSH")) {
                header = command.substring(4);
                return "OK\r>";
            }
            if ("ATDPN".equals(command)) {
                physicalSweepStarted = true;
                return "A6\r>";
            }
            if ("ATAL".equals(command) && physicalSweepStarted
                    && stopMode == StopMode.ADVANCE_CLOCK
                    && clock.get() == 0L) {
                physicalSetupTimeoutMs = config.connectionTimeoutMs;
                clock.addAndGet(11L);
                return "OK\r>";
            }
            if ("0902".equals(command) && !"7DF".equals(header)) {
                physicalMode09Attempts++;
                stopAfterFirstPhysicalRequest();
                return "NO DATA\r>";
            }
            if ("22F190".equals(command) && !"7DF".equals(header)) {
                physicalUdsAttempts++;
                return "NO DATA\r>";
            }
            if ("0902".equals(command)) return "NO DATA\r>";
            return "OK\r>";
        }

        private void stopAfterFirstPhysicalRequest() {
            if (physicalMode09Attempts != 1) return;
            if (stopMode == StopMode.DISCONNECT) {
                connected = false;
            } else if (stopMode == StopMode.INTERRUPT) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
