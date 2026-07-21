package com.alpha.obd2logger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the field failure "app says Connected but no data
 * ever arrives". Simulates an ELM327 adapter attached to an 11-bit 500k CAN
 * vehicle (protocol 6) and drives the real connect / deep-scan restore /
 * batch-poll path, sweeping adapter ATDPN response variants: a non-protocol
 * ATDPN reply must never re-route polling to a bus the car does not speak.
 */
public class ConnectedButNoDataDiagnosisTest {

    private static PIDDefinition rpm() {
        PIDDefinition pid = PIDDefinition.findByKey("01_0C");
        assertNotNull("RPM PID definition must exist", pid);
        return pid;
    }

    private static LoggerConfig autoConfig() {
        LoggerConfig config = new LoggerConfig();
        config.obdProtocol = ObdProtocol.AUTO;
        config.connectionTimeoutMs = 2_000;
        return config;
    }

    // ------------------------------------------------------------------
    // Baseline: normal adapter, normal car — data must flow.
    // ------------------------------------------------------------------
    @Test
    public void baseline_connectThenPoll_dataFlows() {
        SimulatedCarElmDriver driver = new SimulatedCarElmDriver(autoConfig(), "A6");
        assertTrue("connect must succeed", driver.connect());

        Map<String, Double> values = driver.queryPidBatch(Collections.singletonList(rpm()));
        assertNotNull("RPM must be polled after connect: " + driver.commands,
                values.get(rpm().getName()));
    }

    // ------------------------------------------------------------------
    // Deep-scan restore path (runs after every DTC / ECU-ID scan).
    // ------------------------------------------------------------------
    @Test
    public void restoreAfterDeepScan_cleanDpn_dataStillFlows() {
        SimulatedCarElmDriver driver = new SimulatedCarElmDriver(autoConfig(), "A6");
        assertTrue(driver.connect());

        driver.restorePollingState();

        Map<String, Double> values = driver.queryPidBatch(Collections.singletonList(rpm()));
        assertNotNull("RPM must still be polled after deep-scan restore: " + driver.commands,
                values.get(rpm().getName()));
    }

    @Test
    public void restoreAfterDeepScan_dpnUnsupported_dataStillFlows() {
        // Clone that answers "?" to ATDPN: no protocol is remembered, restore
        // must fall back to a full AUTO search and keep polling alive.
        SimulatedCarElmDriver driver = new SimulatedCarElmDriver(autoConfig(), "?");
        assertTrue(driver.connect());

        driver.restorePollingState();

        Map<String, Double> values = driver.queryPidBatch(Collections.singletonList(rpm()));
        assertNotNull("RPM must still be polled after deep-scan restore: " + driver.commands,
                values.get(rpm().getName()));
    }

    @Test
    public void restoreAfterDeepScan_dpnAnswersVersionString_dataStillFlows() {
        // Clone that answers an identification string instead of a protocol
        // digit. The trailing '5' must NOT be mistaken for KWP fast-init.
        SimulatedCarElmDriver driver = new SimulatedCarElmDriver(autoConfig(), "ELM327 v1.5");
        assertTrue(driver.connect());
        driver.restorePollingState();

        Map<String, Double> values = driver.queryPidBatch(Collections.singletonList(rpm()));
        assertNotNull("garbage ATDPN reply must not re-route polling to K-line: "
                + driver.commands, values.get(rpm().getName()));
    }

    @Test
    public void restoreAfterDeepScan_dpnAnswersNoData_dataStillFlows() {
        // Adapter that answers "NO DATA" to ATDPN. The trailing 'A' must NOT
        // be mistaken for SAE J1939.
        SimulatedCarElmDriver driver = new SimulatedCarElmDriver(autoConfig(), "NO DATA");
        assertTrue(driver.connect());
        driver.restorePollingState();

        Map<String, Double> values = driver.queryPidBatch(Collections.singletonList(rpm()));
        assertNotNull("NO DATA from ATDPN must not re-route polling to J1939: "
                + driver.commands, values.get(rpm().getName()));
    }

    @Test
    public void restoreAfterDeepScan_dpnReportsWrongProtocol_recoversViaAutoSearch() {
        // Adapter reports a well-formed but wrong protocol (29-bit CAN on an
        // 11-bit car). The restore probe fails on that bus; polling must be
        // rescued by falling back to the automatic search.
        SimulatedCarElmDriver driver = new SimulatedCarElmDriver(autoConfig(), "A7");
        assertTrue(driver.connect());
        driver.restorePollingState();

        Map<String, Double> values = driver.queryPidBatch(Collections.singletonList(rpm()));
        assertNotNull("a dead remembered lock must fall back to ATSP0: " + driver.commands,
                values.get(rpm().getName()));
    }

    // ------------------------------------------------------------------
    // Parser-level checks for the same defect (fast, no simulator).
    // ------------------------------------------------------------------
    @Test
    public void fromDpnResponse_acceptsRealResponses() {
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11BIT_500, ObdProtocol.fromDpnResponse("A6"));
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11BIT_500, ObdProtocol.fromDpnResponse("6"));
        assertEquals(ObdProtocol.ISO_15765_4_CAN_29BIT_500, ObdProtocol.fromDpnResponse("A7\r\r>"));
    }

    @Test
    public void fromDpnResponse_rejectsNonProtocolReplies() {
        assertEquals("'?' is not a protocol", null, ObdProtocol.fromDpnResponse("?"));
        assertEquals("version banner is not a protocol", null,
                ObdProtocol.fromDpnResponse("ELM327 v1.5"));
        assertEquals("NO DATA is not a protocol", null, ObdProtocol.fromDpnResponse("NO DATA"));
        assertEquals("STOPPED is not a protocol", null, ObdProtocol.fromDpnResponse("STOPPED"));
    }

    // ==================================================================
    // Simulated ELM327 adapter + 11-bit/500k CAN vehicle.
    // ==================================================================
    private static final class SimulatedCarElmDriver extends ElmDriver {
        /** Protocol the simulated CAR actually speaks. */
        private static final String CAR_PROTOCOL = "6";

        final List<String> commands = new ArrayList<>();
        private final String dpnResponse;
        /** ELM protocol selection: "0" = automatic search. */
        private String selectedProtocol = "0";
        /** Pending ATTP trial protocol, or null. */
        private String triedProtocol;

        SimulatedCarElmDriver(LoggerConfig config, String dpnResponse) {
            super(config);
            this.dpnResponse = dpnResponse;
        }

        @Override public boolean connect() {
            connected = true;
            boolean ok = initializeElm327();
            connected = ok;
            return ok;
        }

        @Override public void disconnect() { connected = false; }

        @Override public Double queryPid(PIDDefinition pidDef) {
            return queryPidResponse(pidDef, sendCommand(
                    pidDef.getService() + " " + pidDef.getPidHex()));
        }

        @Override protected void drainStaleBytes(long maxMillis) { }

        @Override protected String sendCommand(String command) {
            commands.add(command);
            String c = command.toUpperCase(java.util.Locale.US).replace(" ", "");

            if (c.equals("ATZ") || c.equals("ATI")) return "ELM327 v1.5";
            if (c.equals("AT@1")) return "OBDII to RS232 Interpreter";
            if (c.equals("ATDPN")) return dpnResponse;
            if (c.startsWith("ATSPA") && c.length() > 5) {
                // "ATSP A h" — automatic with a preferred protocol; behaves
                // like auto for a car the hint matches or not.
                selectedProtocol = "0";
                triedProtocol = null;
                return "OK";
            }
            if (c.startsWith("ATSP")) {
                selectedProtocol = c.substring(4);
                triedProtocol = null;
                return "OK";
            }
            if (c.startsWith("ATTP")) {
                triedProtocol = c.substring(4);
                return "OK";
            }
            if (c.startsWith("AT")) return "OK";

            // --- OBD request reaches the simulated vehicle ---
            String active = triedProtocol != null ? triedProtocol : selectedProtocol;
            boolean reachesCar = "0".equals(active) || CAR_PROTOCOL.equals(active);
            if (!reachesCar) {
                // Wrong bus forced: K-line init fails, CAN at wrong rate
                // yields errors, 29-bit gets no answer.
                if ("3".equals(active) || "4".equals(active) || "5".equals(active)) {
                    return "BUS INIT: ...ERROR";
                }
                return "NO DATA";
            }
            if (c.startsWith("0100")) return "SEARCHING...\r41 00 BE 3F A8 13";
            if (c.contains("010C")) return "41 0C 1A F8";
            if (c.startsWith("01")) return "NO DATA";
            return "?";
        }
    }
}
