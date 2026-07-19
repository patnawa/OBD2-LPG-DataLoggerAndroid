package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Protocol resolution (ATDPN parsing, bus-width classification) and the
 * 29-bit physical addressing paths added for the VIN fallback chain.
 */
public class ProtocolDetectionTest {

    // ── ObdProtocol.fromDpnResponse ───────────────────────────────────

    @Test
    public void dpn_autoSelectedCan11_500() {
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                ObdProtocol.fromDpnResponse("A6\r>"));
    }

    @Test
    public void dpn_explicitlySelectedProtocols() {
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11BIT_500, ObdProtocol.fromDpnResponse("6"));
        assertEquals(ObdProtocol.ISO_15765_4_CAN_29BIT_500, ObdProtocol.fromDpnResponse("7"));
        assertEquals(ObdProtocol.ISO_9141_2, ObdProtocol.fromDpnResponse("3"));
        assertEquals(ObdProtocol.ISO_14230_4_KWP_FAST, ObdProtocol.fromDpnResponse("5"));
    }

    @Test
    public void dpn_autoSelectedJ1939_isNotMistakenForAutoMarker() {
        // "AA" = auto-selected protocol A (J1939); a single "A" is protocol A.
        assertEquals(ObdProtocol.SAE_J1939_CAN, ObdProtocol.fromDpnResponse("AA\r>"));
        assertEquals(ObdProtocol.SAE_J1939_CAN, ObdProtocol.fromDpnResponse("A"));
    }

    @Test
    public void dpn_neverReturnsAuto() {
        // "0" means "no protocol selected yet" — not a resolved protocol.
        assertNull(ObdProtocol.fromDpnResponse("0"));
        assertNull(ObdProtocol.fromDpnResponse("A0"));
    }

    @Test
    public void dpn_garbageAndEmptyAreNull() {
        assertNull(ObdProtocol.fromDpnResponse(null));
        assertNull(ObdProtocol.fromDpnResponse(""));
        assertNull(ObdProtocol.fromDpnResponse("?"));
        assertNull(ObdProtocol.fromDpnResponse("OK"));
        assertNull(ObdProtocol.fromDpnResponse("STOPPED"));
    }

    @Test
    public void protocolWidthFlags() {
        assertTrue(ObdProtocol.ISO_15765_4_CAN_29BIT_500.isTwentyNineBitCan());
        assertTrue(ObdProtocol.ISO_15765_4_CAN_29BIT_250.isTwentyNineBitCan());
        assertFalse(ObdProtocol.ISO_15765_4_CAN_11BIT_500.isTwentyNineBitCan());
        assertTrue(ObdProtocol.ISO_15765_4_CAN_11BIT_500.isElevenBitCan());
        assertTrue(ObdProtocol.USER1_CAN.isElevenBitCan());
        assertFalse(ObdProtocol.SAE_J1939_CAN.isElevenBitCan());
    }

    // ── ElmDriver 29-bit ATDPN classification ─────────────────────────

    @Test
    public void detectsTwentyNineBitCan() {
        assertTrue(ElmDriver.isTwentyNineBitCanProtocol("A7\r>"));
        assertTrue(ElmDriver.isTwentyNineBitCanProtocol("9\r>"));
        assertFalse(ElmDriver.isTwentyNineBitCanProtocol("A6\r>"));
        assertFalse(ElmDriver.isTwentyNineBitCanProtocol("5\r>"));
        // J1939 is 29-bit on the wire but not ISO-TP UDS addressable.
        assertFalse(ElmDriver.isTwentyNineBitCanProtocol("A\r>"));
        assertFalse(ElmDriver.isTwentyNineBitCanProtocol(null));
    }

    // ── VIN fallback on a 29-bit bus ──────────────────────────────────

    @Test
    public void vinFallsBackToUdsOverTwentyNineBitAddressing() {
        LoggerConfig config = new LoggerConfig();
        TwentyNineBitUdsVinDriver driver = new TwentyNineBitUdsVinDriver(config);

        assertEquals("MR0FZ29G901234567", VinReader.readVin(driver));
        assertTrue(driver.sent.contains("ATSH18DA10F1"));
        assertTrue(driver.sent.contains("ATCRA18DAF110"));
        // Functional restore must use the 29-bit broadcast header.
        assertTrue(driver.sent.contains("ATSH18DB33F1"));
        assertTrue(driver.sent.contains("22F190"));
    }

    /** Mode 09 absent; VIN only via UDS 22 F1 90 on the 29-bit engine ECU. */
    private static final class TwentyNineBitUdsVinDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();
        private String header = "18DB33F1";

        TwentyNineBitUdsVinDriver(LoggerConfig config) {
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
            if ("ATDPN".equals(command)) return "A7\r>";
            if ("0902".equals(command)) return "NO DATA\r>";
            if ("22F190".equals(command) && "18DA10F1".equals(header)) {
                return "18DAF110 10 14 62 F1 90 4D 52 30\r"
                        + "18DAF110 21 46 5A 32 39 47 39 30\r"
                        + "18DAF110 22 31 32 33 34 35 36 37\r>";
            }
            return "OK\r>";
        }
    }

    // ── Genuine-ELM327 29-bit header fallback (ATCP + 6-digit ATSH) ───

    @Test
    public void physicalAddressing_fallsBackToAtcpSplitWhenEightDigitAtshRejected() {
        LoggerConfig config = new LoggerConfig();
        GenuineElmDriver driver = new GenuineElmDriver(config);

        assertTrue(PhysicalAddressing.applyTarget(driver, "18DA10F1", "18DAF110"));
        assertTrue(driver.sent.contains("ATCP18"));
        assertTrue(driver.sent.contains("ATSHDA10F1"));
        assertTrue(driver.sent.contains("ATCRA18DAF110"));
    }

    @Test
    public void physicalAddressing_elevenBitRejectionDoesNotTryAtcp() {
        LoggerConfig config = new LoggerConfig();
        GenuineElmDriver driver = new GenuineElmDriver(config);

        assertFalse(PhysicalAddressing.applyTarget(driver, "7E0", "7E8"));
        assertFalse(driver.sent.contains("ATCP7E"));
    }

    /** Rejects any ATSH longer than 6 hex digits, like a genuine ELM327. */
    private static final class GenuineElmDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();

        GenuineElmDriver(LoggerConfig config) {
            super(config);
            connected = true;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override
        protected String sendCommand(String command) {
            sent.add(command);
            if (command.startsWith("ATSH") && command.length() > 4 + 6) {
                return "?\r>";
            }
            // The 11-bit rejection test wants every ATSH refused.
            if ("ATSH7E0".equals(command)) return "?\r>";
            return "OK\r>";
        }
    }

    // ── Check-digit-ranked VIN window selection ───────────────────────

    @Test
    public void vinWindowWithValidCheckDigitBeatsEarlierShiftedWindow() {
        // Payload carries 20 VIN characters: a 3-char echo of the WMI ("JT2")
        // followed by the true VIN. The first 17-char window "JT2JT2BF22K3X0123"
        // is structurally valid AND has a recognized Toyota WMI — but only the
        // true VIN "JT2BF22K3X0123456" also carries a valid check digit.
        String vinChars = "JT2" + "JT2BF22K3X0123456";
        StringBuilder response = new StringBuilder("490201");
        for (char c : vinChars.toCharArray()) {
            response.append(String.format("%02X", (int) c));
        }

        assertEquals("JT2BF22K3X0123456", VinReader.parseVinResponse(response.toString()));
    }

    @Test
    public void vinWithoutCheckDigitStillWins_thaiMarketVinsDoNotPopulateIt() {
        // "MR0FZ29G901234567" (Thai-built Toyota) has no valid check digit;
        // a recognized WMI alone must still be accepted.
        assertTrue(VinBrandDetector.isStructurallyValid("MR0FZ29G901234567"));
        assertFalse(VinBrandDetector.hasValidCheckDigit("MR0FZ29G901234567"));
        String response = "49 02 01 4D 52 30 46 5A 32 39 47 39 30 31 32 33 34 35 36 37";
        assertEquals("MR0FZ29G901234567", VinReader.parseVinResponse(response));
    }
}
