package com.alpha.obd2logger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ElmDriverSlowProtocolDiscoveryTest {
    @Test
    public void autoProtocolAllowsSlowVehicleDiscoveryWithoutChangingSteadyStateTimeout() {
        LoggerConfig config = new LoggerConfig();
        config.obdProtocol = ObdProtocol.AUTO;
        config.connectionTimeoutMs = 2_000;
        SlowDiscoveryElmDriver driver = new SlowDiscoveryElmDriver(config);

        assertTrue("AUTO protocol discovery must outlive the normal 2 second command timeout",
                driver.initializeElm327());
        assertTrue(driver.timeoutSeenByVehicleProbe >= 10_000);
        assertEquals("the longer timeout is initialization-only", 2_000, config.connectionTimeoutMs);
    }

    private static final class SlowDiscoveryElmDriver extends ElmDriver {
        final List<String> commands = new ArrayList<>();
        int timeoutSeenByVehicleProbe;

        SlowDiscoveryElmDriver(LoggerConfig config) {
            super(config);
        }

        @Override public boolean connect() { return initializeElm327(); }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }
        @Override protected void drainStaleBytes(long maxMillis) { }

        @Override protected String sendCommand(String command) {
            commands.add(command);
            if ("ATZ".equals(command)) return "ELM327 v1.5\r>";
            if ("ATI".equals(command)) return "ELM327 v1.5\r>";
            if ("AT@1".equals(command)) return "OBDII to RS232 Interpreter\r>";
            if ("0100".equals(command)) {
                timeoutSeenByVehicleProbe = Math.max(timeoutSeenByVehicleProbe,
                        config.connectionTimeoutMs);
                return config.connectionTimeoutMs >= 10_000
                        ? "SEARCHING...\r41 00 BE 3F A8 13\r>"
                        : "SEARCHING...\r>";
            }
            return "OK\r>";
        }
    }
}
