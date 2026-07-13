package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class DriverFactoryTest {

    @Test
    public void simulationReportsResolvedTransportAndProbeSummary() {
        LoggerConfig config = new LoggerConfig();
        config.transportMode = TransportMode.SIM;
        BaseDriver driver = DriverFactory.create(config);

        assertTrue(driver instanceof SimulationDriver);
        assertTrue(DriverFactory.getLastResolvedTransport().contains("Simulation"));
        assertTrue(DriverFactory.getLastProbeSummary().contains("Simulation"));
    }

    @Test
    public void nullConfigFallsBackSafely() {
        BaseDriver driver = DriverFactory.create(null);
        assertTrue(driver instanceof SimulationDriver);
        assertTrue(DriverFactory.getLastProbeSummary().contains("Invalid"));
    }
}
