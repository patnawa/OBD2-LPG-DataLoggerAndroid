package com.alpha.obd2logger;

import com.alpha.obd2logger.can.UdsDataIdentifier;
import com.alpha.obd2logger.can.UdsRequest;
import com.alpha.obd2logger.can.UdsResponseDecoder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElmUdsTransportTest {

    private static final String CR = "\r";

    // ── request building ───────────────────────────────────────────────

    @Test
    public void buildsReadDataByIdentifierCommand() {
        UdsRequest request = UdsRequest.readDataByIdentifier(UdsDataIdentifier.VIN);
        assertEquals("22F190", request.toElmCommand());
        assertEquals(0x62, request.getExpectedResponseService());
        assertEquals("62", request.getExpectedResponsePrefix());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOutOfRangeIdentifier() {
        UdsRequest.readDataByIdentifier(0x1F190);
    }

    // ── payload assembly ───────────────────────────────────────────────

    @Test
    public void assemblesMultiFramePositiveResponse() {
        String raw = "7E8 10 14 62 F1 90 4D 52 30" + CR
                + "7E8 21 46 5A 32 39 47 39 30" + CR
                + "7E8 22 31 32 33 34 35 36 37" + CR + ">";

        byte[] payload = ElmUdsTransport.assemblePayload(raw, "7E8", "62");
        UdsResponseDecoder.DecodedResponse decoded = UdsResponseDecoder.decode(payload);

        assertEquals(UdsResponseDecoder.Kind.POSITIVE_RESPONSE, decoded.getKind());
        assertEquals(0x22, decoded.getRequestService());
        // Data is the DID echo followed by the 17 VIN characters.
        byte[] data = decoded.getData();
        assertEquals(0xF1, data[0] & 0xFF);
        assertEquals(0x90, data[1] & 0xFF);
        assertEquals("MR0FZ29G901234567",
                new String(data, 2, 17, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    public void assemblesSingleFrameNegativeResponse() {
        byte[] payload = ElmUdsTransport.assemblePayload("7E8 03 7F 22 31" + CR, "7E8", "62");
        UdsResponseDecoder.DecodedResponse decoded = UdsResponseDecoder.decode(payload);

        assertEquals(UdsResponseDecoder.Kind.NEGATIVE_RESPONSE, decoded.getKind());
        assertEquals(0x22, decoded.getRequestService());
        assertEquals(0x31, decoded.getNegativeResponseCode());
    }

    @Test
    public void ignoresFramesFromAModuleOtherThanTheOneAddressed() {
        // 7EA talking over the reply must not be credited to 7E8.
        String raw = "7EA 10 14 62 F1 90 41 41 41" + CR
                + "7EA 21 41 41 41 41 41 41 41" + CR;

        assertArrayEquals("frames from an unaddressed module are not our answer",
                new byte[0], ElmUdsTransport.assemblePayload(raw, "7E8", "62"));
    }

    // ── response pending ───────────────────────────────────────────────

    @Test
    public void recognisesResponsePendingFrameWithAndWithoutPadding() {
        assertTrue(ElmUdsTransport.isResponsePendingFrame("7E8 03 7F 22 78", "7E8"));
        assertTrue("ELM may show the unused bytes of the frame",
                ElmUdsTransport.isResponsePendingFrame("7E8 03 7F 22 78 00 00 00", "7E8"));
        assertTrue("headers may be off", ElmUdsTransport.isResponsePendingFrame("03 7F 22 78", null));
        assertFalse("a real negative response is not pending",
                ElmUdsTransport.isResponsePendingFrame("7E8 03 7F 22 31", "7E8"));
        assertFalse("a positive response is not pending",
                ElmUdsTransport.isResponsePendingFrame("7E8 06 62 F1 90 41 42", "7E8"));
    }

    @Test
    public void keepsTheRealAnswerWhenItArrivesBehindAPendingFrame() {
        // The case that motivates stripping: appending the pending frame to the
        // payload would decode the whole exchange as a negative response.
        String raw = "7E8 03 7F 22 78" + CR
                + "7E8 10 14 62 F1 90 4D 52 30" + CR
                + "7E8 21 46 5A 32 39 47 39 30" + CR
                + "7E8 22 31 32 33 34 35 36 37" + CR;

        byte[] payload = ElmUdsTransport.assemblePayload(raw, "7E8", "62");
        UdsResponseDecoder.DecodedResponse decoded = UdsResponseDecoder.decode(payload);

        assertEquals("the pending frame must not mask the real reply",
                UdsResponseDecoder.Kind.POSITIVE_RESPONSE, decoded.getKind());
        byte[] data = decoded.getData();
        assertEquals("MR0FZ29G901234567",
                new String(data, 2, 17, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    public void retriesWhileTheEcuOnlyReportsResponsePending() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        driver.script.add("7E8 03 7F 22 78" + CR + ">");
        driver.script.add("7E8 03 7F 22 78" + CR + ">");
        driver.script.add("7E8 10 14 62 F1 90 4D 52 30" + CR
                + "7E8 21 46 5A 32 39 47 39 30" + CR
                + "7E8 22 31 32 33 34 35 36 37" + CR + ">");

        ElmUdsTransport.Exchange exchange = ElmUdsTransport.request(driver, "7E0", "7E8",
                UdsRequest.readDataByIdentifier(UdsDataIdentifier.VIN), 3, 0);

        assertTrue(exchange.isPositive());
        assertEquals(3, exchange.getAttempts());
        assertFalse(exchange.isPendingExhausted());
    }

    @Test
    public void givesUpAfterTheAttemptBudgetIsSpentOnPendingReplies() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        for (int i = 0; i < 5; i++) driver.script.add("7E8 03 7F 22 78" + CR + ">");

        ElmUdsTransport.Exchange exchange = ElmUdsTransport.request(driver, "7E0", "7E8",
                UdsRequest.readDataByIdentifier(UdsDataIdentifier.VIN), 3, 0);

        assertTrue(exchange.isPendingExhausted());
        assertEquals(3, exchange.getAttempts());
        assertEquals(0x78, exchange.getResponse().getNegativeResponseCode());
    }

    @Test
    public void doesNotRetryARealNegativeResponse() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        driver.script.add("7E8 03 7F 22 31" + CR + ">");

        ElmUdsTransport.Exchange exchange = ElmUdsTransport.request(driver, "7E0", "7E8",
                UdsRequest.readDataByIdentifier(UdsDataIdentifier.VIN), 3, 0);

        assertEquals("requestOutOfRange is a final answer, not a wait",
                1, exchange.getAttempts());
        assertTrue(exchange.isNegative());
        assertEquals(0x31, exchange.getResponse().getNegativeResponseCode());
    }

    @Test
    public void doesNotRetryASilentModule() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        driver.script.add("NO DATA" + CR + ">");

        ElmUdsTransport.Exchange exchange = ElmUdsTransport.request(driver, "7E0", "7E8",
                UdsRequest.readDataByIdentifier(UdsDataIdentifier.VIN), 3, 0);

        assertEquals("silence is not Response Pending; retrying wastes the budget",
                1, exchange.getAttempts());
        assertFalse(exchange.isPositive());
        assertFalse(exchange.isPendingExhausted());
    }

    // ── safety posture ─────────────────────────────────────────────────

    @Test
    public void onlyEverSendsReadDataByIdentifier() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        for (int did : UdsDataIdentifier.sweepSet()) {
            driver.script.add("7E8 03 7F 22 31" + CR + ">");
            ElmUdsTransport.request(driver, "7E0", "7E8",
                    UdsRequest.readDataByIdentifier(did), 1, 0);
        }

        for (String command : driver.dataCommands) {
            assertTrue("only service 0x22 may be sent, saw " + command,
                    command.startsWith("22"));
        }
        assertFalse("no session control", driver.dataCommands.contains("1003"));
        assertFalse("no security access", driver.dataCommands.stream()
                .anyMatch(c -> c.startsWith("27")));
        assertFalse("no writes", driver.dataCommands.stream()
                .anyMatch(c -> c.startsWith("2E")));
        assertFalse("no ECU reset", driver.dataCommands.stream()
                .anyMatch(c -> c.startsWith("11")));
    }

    @Test
    public void sweepSetStaysInsideTheStandardIdentificationBlock() {
        for (int did : UdsDataIdentifier.sweepSet()) {
            assertTrue("manufacturer-defined identifiers need documentation we do not have: "
                            + UdsDataIdentifier.toHex(did),
                    did >= 0xF180 && did <= 0xF19F);
            assertTrue(UdsDataIdentifier.isStandardIdentificationDid(did));
        }
    }

    /** Returns scripted responses in order; records what was asked. */
    private static final class ScriptedUdsDriver extends ElmDriver {
        final List<String> script = new ArrayList<>();
        final List<String> dataCommands = new ArrayList<>();
        private int index;

        ScriptedUdsDriver() {
            super(new LoggerConfig());
            connected = true;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override
        protected String sendCommand(String command) {
            if (command.startsWith("AT")) return "OK" + CR + ">";
            dataCommands.add(command);
            return index < script.size() ? script.get(index++) : "NO DATA" + CR + ">";
        }
    }
}
