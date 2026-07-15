package com.alpha.obd2logger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

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
}
