package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DriverConnectorTest {
    @Test
    public void explicitSimulationConnectsWithinBound() {
        LoggerConfig config = new LoggerConfig();
        config.transportMode = TransportMode.SIM;
        DriverConnector.Result result = DriverConnector.connect(config, 1_000L);
        assertTrue(result.isConnected());
        assertTrue(result.getDriver() instanceof SimulationDriver);
        result.getDriver().disconnect();
    }

    @Test
    public void unavailableDriverNeverProducesTelemetry() {
        UnavailableDriver driver = new UnavailableDriver(new LoggerConfig(), "probe failed");
        assertFalse(driver.connect());
        assertFalse(driver.isConnected());
        assertTrue(driver.getAdapterDetails().contains("probe failed"));
    }

    @Test
    public void reconnectUsesExistingDriver() {
        SimulationDriver driver = new SimulationDriver(new LoggerConfig());
        DriverConnector.Result result = DriverConnector.reconnect(driver, 1_000L);
        assertTrue(result.isConnected());
        assertTrue(result.getDriver() == driver);
        driver.disconnect();
    }
}
