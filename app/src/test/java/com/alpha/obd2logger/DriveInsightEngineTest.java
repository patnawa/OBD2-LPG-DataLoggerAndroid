package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DriveInsightEngineTest {

    @Test
    public void storedDtcRemainsActionableBeforeLiveDataArrives() {
        DriveInsightEngine.Result result = DriveInsightEngine.evaluate(
                null, null, null, null, 2);
        assertEquals(DriveInsightEngine.Type.DTC, result.type);
        assertEquals(DriveInsightEngine.Destination.DIAGNOSTICS, result.destination);
    }

    @Test
    public void eachInsightRoutesToItsRelevantTool() {
        assertEquals(DriveInsightEngine.Destination.DASHBOARD,
                DriveInsightEngine.evaluate(1200.0, 106.0, 14.1, 0.0, 0).destination);
        assertEquals(DriveInsightEngine.Destination.BATTERY,
                DriveInsightEngine.evaluate(1200.0, 90.0, 12.7, 0.0, 0).destination);
        assertEquals(DriveInsightEngine.Destination.FUEL_MAP,
                DriveInsightEngine.evaluate(1200.0, 90.0, 14.1, 12.0, 0).destination);
    }

    @Test
    public void healthyDataIsStableAndMissingRpmCollects() {
        assertEquals(DriveInsightEngine.Type.STABLE,
                DriveInsightEngine.evaluate(900.0, 90.0, 14.1, 2.0, 0).type);
        assertEquals(DriveInsightEngine.Type.COLLECTING,
                DriveInsightEngine.evaluate(null, 90.0, 14.1, 2.0, 0).type);
    }
}
