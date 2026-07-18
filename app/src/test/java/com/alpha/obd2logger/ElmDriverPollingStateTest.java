package com.alpha.obd2logger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElmDriverPollingStateTest {
    @Test
    public void restorePollingStateRestoresConfiguredProtocolAndFlowControl() {
        LoggerConfig config = new LoggerConfig();
        config.obdProtocol = ObdProtocol.ISO_15765_4_CAN_11BIT_500;
        RecordingElmDriver driver = new RecordingElmDriver(config);

        driver.restorePollingState();

        assertTrue(driver.commands.contains("ATD"));
        assertTrue(driver.commands.contains("ATCFC1"));
        assertTrue(driver.commands.contains("ATH0"));
        assertTrue(driver.commands.contains("ATSP6"));
        assertTrue(driver.commands.contains("0100"));
    }

    @Test
    public void partialBatchRetriesMapInputButNotMissingOptionalPid() {
        PartialBatchElmDriver driver = new PartialBatchElmDriver(new LoggerConfig());
        PIDDefinition rpm = PIDDefinition.findByKey("01_0C");
        PIDDefinition map = PIDDefinition.findByKey("01_0B");
        PIDDefinition optional = PIDDefinition.findByKey("01_2F");

        Map<String, Double> values = driver.queryPidBatch(Arrays.asList(rpm, map, optional));

        assertTrue("missing MAP must be recovered before learning a cell",
                values.containsKey(map.getName()));
        assertTrue("MAP recovery must use an individual command", driver.commands.contains("01 0B"));
        assertFalse("missing optional data must not stall every Bluetooth cycle",
                driver.commands.contains("01 2F"));
    }

    private static final class RecordingElmDriver extends ElmDriver {
        final List<String> commands = new ArrayList<>();

        RecordingElmDriver(LoggerConfig config) {
            super(config);
            connected = true;
            vlinkerType = VLinkerOptimizer.DeviceType.GENERIC_ELM327;
        }

        @Override public boolean connect() { connected = true; return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override protected String sendCommand(String command) {
            commands.add(command);
            return "0100".equals(command) ? "41 00 BE 3F A8 13" : "OK";
        }
    }

    private static final class PartialBatchElmDriver extends ElmDriver {
        final List<String> commands = new ArrayList<>();

        PartialBatchElmDriver(LoggerConfig config) {
            super(config);
            connected = true;
            vlinkerType = VLinkerOptimizer.DeviceType.VLINKER_MC_BT;
        }

        @Override public boolean connect() { connected = true; return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override protected String sendCommand(String command) {
            commands.add(command);
            // The multi-PID response contains RPM only, like a partial reply
            // from a real ECU.  The map retry below supplies MAP; Fuel Level
            // remains absent and must not trigger a slow retry.
            if (command.startsWith("01 ")) {
                if ("01 0B".equals(command)) return "41 0B 3C";
                return "41 0C 1A F8";
            }
            return "OK";
        }
    }
}
