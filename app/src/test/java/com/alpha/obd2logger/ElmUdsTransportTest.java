package com.alpha.obd2logger;

import com.alpha.obd2logger.can.UdsDataIdentifier;
import com.alpha.obd2logger.can.UdsRequest;
import com.alpha.obd2logger.can.UdsResponseDecoder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    // -- batched physical identification session -----------------------

    @Test
    public void identificationBatchSetsUpAndRestoresExactlyOnce() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        for (int ignored : UdsDataIdentifier.sweepSet()) {
            driver.script.add("7E8 03 7F 22 31" + CR + ">");
        }
        int originalTimeout = driver.config.connectionTimeoutMs;

        Map<Integer, ElmUdsTransport.Exchange> exchanges =
                ElmUdsTransport.requestIdentificationBatch(
                        driver, "7E0", "7E8", 1, 0);

        assertEquals(UdsDataIdentifier.sweepSet(), new ArrayList<>(exchanges.keySet()));
        assertEquals(UdsDataIdentifier.sweepSet().size(), driver.dataCommands.size());
        assertEquals(1, count(driver.commands, "ATH1"));
        assertEquals(1, count(driver.commands, "ATH0"));
        assertEquals(1, count(driver.commands, "ATSH7E0"));
        assertEquals(1, count(driver.commands, "ATCRA7E8"));
        assertEquals(1, count(driver.commands, "ATCRA"));
        assertEquals(1, count(driver.commands, "ATSH7DF"));
        assertTrue(driver.commands.indexOf("ATH1") < firstDataCommand(driver.commands));
        assertTrue(driver.commands.lastIndexOf("ATH0") > lastDataCommand(driver.commands));
        assertEquals("Android-side timeout must also be restored",
                originalTimeout, driver.config.connectionTimeoutMs);
    }

    @Test
    public void identificationBatchCanOnlyTransmitService22FromTheSafeSweepSet() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        for (int ignored : UdsDataIdentifier.sweepSet()) {
            driver.script.add("NO DATA" + CR + ">");
        }

        ElmUdsTransport.requestIdentificationBatch(driver, "7E0", "7E8", 1, 0);

        List<String> expected = new ArrayList<>();
        for (int did : UdsDataIdentifier.sweepSet()) {
            expected.add("22" + UdsDataIdentifier.toHex(did));
        }
        assertEquals(expected, driver.dataCommands);
        for (String command : driver.dataCommands) {
            assertTrue(command.startsWith("22"));
            assertFalse(command.startsWith("10"));
            assertFalse(command.startsWith("11"));
            assertFalse(command.startsWith("27"));
            assertFalse(command.startsWith("2E"));
            assertFalse(command.startsWith("31"));
        }
    }

    @Test
    public void identificationBatchReportsMonotonicProgress() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        for (int ignored : UdsDataIdentifier.sweepSet()) {
            driver.script.add("NO DATA" + CR + ">");
        }
        List<Integer> completedValues = new ArrayList<>();
        List<Integer> totalValues = new ArrayList<>();

        ElmUdsTransport.requestIdentificationBatch(
                driver, "7E0", "7E8", 1, 0,
                (completed, total) -> {
                    completedValues.add(completed);
                    totalValues.add(total);
                });

        int total = UdsDataIdentifier.sweepSet().size();
        assertEquals(total, completedValues.size());
        for (int i = 0; i < total; i++) {
            assertEquals(i + 1, (int) completedValues.get(i));
            assertEquals(total, (int) totalValues.get(i));
        }
    }

    @Test
    public void identificationBatchRetriesPendingInsideTheSameSetup() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        driver.script.add("7E8 03 7F 22 78" + CR + ">");
        driver.script.add("7E8 03 7F 22 78" + CR + ">");
        driver.script.add("7E8 05 62 F1 80 41 42" + CR + ">");
        for (int i = 1; i < UdsDataIdentifier.sweepSet().size(); i++) {
            driver.script.add("7E8 03 7F 22 31" + CR + ">");
        }

        Map<Integer, ElmUdsTransport.Exchange> exchanges =
                ElmUdsTransport.requestIdentificationBatch(
                        driver, "7E0", "7E8", 3, 0);

        ElmUdsTransport.Exchange first = exchanges.get(0xF180);
        assertTrue(first.isPositive());
        assertEquals(3, first.getAttempts());
        assertEquals(Arrays.asList("22F180", "22F180", "22F180"),
                driver.dataCommands.subList(0, 3));
        assertEquals("pending retry must not repeat adapter setup",
                1, count(driver.commands, "ATSH7E0"));
        assertEquals(1, count(driver.commands, "ATCRA7E8"));
    }

    @Test
    public void rejectedPhysicalHeaderStillRestoresFunctionalPolling() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        driver.rejectPhysicalHeader = true;

        Map<Integer, ElmUdsTransport.Exchange> exchanges =
                ElmUdsTransport.requestIdentificationBatch(
                        driver, "7E0", "7E8", 1, 0);

        assertTrue(driver.dataCommands.isEmpty());
        assertEquals(UdsDataIdentifier.sweepSet().size(), exchanges.size());
        for (ElmUdsTransport.Exchange exchange : exchanges.values()) {
            assertEquals(UdsResponseDecoder.Kind.MALFORMED,
                    exchange.getResponse().getKind());
        }
        assertTrue(driver.commands.contains("ATCRA"));
        assertTrue(driver.commands.contains("ATSH7DF"));
        assertTrue(driver.commands.contains("ATH0"));
    }

    @Test
    public void interruptionStopsTheSweepAndRestoresTheAdapter() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        driver.interruptAfterFirstData = true;
        driver.script.add("7E8 03 7F 22 31" + CR + ">");
        int originalTimeout = driver.config.connectionTimeoutMs;

        try {
            ElmUdsTransport.requestIdentificationBatch(
                    driver, "7E0", "7E8", 1, 0);
            fail("Interrupted identification must be cancelled");
        } catch (CancellationException expected) {
            assertTrue("interrupt status must survive mandatory restore",
                    Thread.interrupted());
        }

        assertEquals(1, driver.dataCommands.size());
        assertTrue(driver.commands.contains("ATCRA"));
        assertTrue(driver.commands.contains("ATSH7DF"));
        assertTrue(driver.commands.contains("ATH0"));
        assertEquals(originalTimeout, driver.config.connectionTimeoutMs);
    }

    @Test
    public void repeatedLifecycleInterruptsCannotTruncateMandatoryRestore() {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        driver.interruptAfterFirstData = true;
        driver.interruptRestoreAttempts = 4;
        driver.script.add("7E8 03 7F 22 31" + CR + ">");

        try {
            ElmUdsTransport.requestIdentificationBatch(
                    driver, "7E0", "7E8", 1, 0);
            fail("Interrupted identification must be cancelled");
        } catch (CancellationException expected) {
            assertTrue("interrupt status must be restored to the caller",
                    Thread.interrupted());
        }

        assertFalse("the fake transport must exercise interrupted restore writes",
                driver.interruptedRestoreCommands.isEmpty());
        List<String> completeRestore = Arrays.asList(
                "ATCRA", "ATAR", "ATSH7DF", "ATH0", "ATS0", "ATAT1", "ATST32");
        assertEquals("one complete restore pass must finish after repeated interrupts",
                completeRestore,
                driver.commands.subList(
                        driver.commands.size() - completeRestore.size(),
                        driver.commands.size()));
    }

    @Test
    public void interruptionWhileWaitingForCommandLockSendsNothing() throws Exception {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                ElmUdsTransport.requestIdentificationBatch(
                        driver, "7E0", "7E8", 1, 0);
            } catch (Throwable error) {
                outcome.set(error);
            }
        }, "uds-lock-cancel-test");

        driver.commandLock.lock();
        try {
            worker.start();
            waitUntilQueued(driver, worker);
            worker.interrupt();
        } finally {
            driver.commandLock.unlock();
        }
        worker.join(2_000L);

        assertFalse(worker.isAlive());
        assertTrue(outcome.get() instanceof CancellationException);
        assertTrue(driver.commands.isEmpty());
    }

    @Test
    public void disconnectWhileWaitingForCommandLockIsRevalidated() throws Exception {
        ScriptedUdsDriver driver = new ScriptedUdsDriver();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                ElmUdsTransport.requestIdentificationBatch(
                        driver, "7E0", "7E8", 1, 0);
            } catch (Throwable error) {
                outcome.set(error);
            }
        }, "uds-lock-disconnect-test");

        driver.commandLock.lock();
        try {
            worker.start();
            waitUntilQueued(driver, worker);
            driver.disconnect();
        } finally {
            driver.commandLock.unlock();
        }
        worker.join(2_000L);

        assertFalse(worker.isAlive());
        assertTrue(outcome.get() instanceof IllegalStateException);
        assertTrue(driver.commands.isEmpty());
    }

    private static int count(List<String> commands, String wanted) {
        int count = 0;
        for (String command : commands) if (wanted.equals(command)) count++;
        return count;
    }

    private static int firstDataCommand(List<String> commands) {
        for (int i = 0; i < commands.size(); i++) {
            if (!commands.get(i).startsWith("AT")) return i;
        }
        return -1;
    }

    private static int lastDataCommand(List<String> commands) {
        for (int i = commands.size() - 1; i >= 0; i--) {
            if (!commands.get(i).startsWith("AT")) return i;
        }
        return -1;
    }

    private static void waitUntilQueued(ScriptedUdsDriver driver, Thread worker)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!driver.commandLock.hasQueuedThread(worker)
                && worker.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue("Worker did not queue for the command lock",
                driver.commandLock.hasQueuedThread(worker));
    }

    /** Returns scripted responses in order; records what was asked. */
    private static final class ScriptedUdsDriver extends ElmDriver {
        final List<String> script = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<String> dataCommands = new ArrayList<>();
        boolean rejectPhysicalHeader;
        boolean interruptAfterFirstData;
        int interruptRestoreAttempts;
        boolean restoreStarted;
        final List<String> interruptedRestoreCommands = new ArrayList<>();
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
            if (restoreStarted && Thread.currentThread().isInterrupted()) {
                interruptedRestoreCommands.add(command);
                return "";
            }
            if ("ATCRA".equals(command)) restoreStarted = true;
            commands.add(command);
            if (restoreStarted && interruptRestoreAttempts > 0) {
                interruptRestoreAttempts--;
                Thread.currentThread().interrupt();
            }
            if (rejectPhysicalHeader && "ATSH7E0".equals(command)) return "?" + CR + ">";
            if (command.startsWith("AT")) return "OK" + CR + ">";
            dataCommands.add(command);
            if (interruptAfterFirstData && dataCommands.size() == 1) {
                Thread.currentThread().interrupt();
            }
            return index < script.size() ? script.get(index++) : "NO DATA" + CR + ">";
        }
    }
}
