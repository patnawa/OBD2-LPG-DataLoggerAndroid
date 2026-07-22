package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Session-review VE replay: rebuilding the learned VE surface from the
 * ve_map_* CSV columns that 3.33.0 logs carry. Locks the field bug where the
 * review screen showed no VE data for a log that plainly contained it.
 */
public class VeMapReplayTest {

    /** Header exactly as DataWriter emits it (display labels, quoted style irrelevant). */
    private static final String HEADER =
            "timestamp,elapsed_s,fuel_mode,loop_status,vehicle_brand,vin,"
            + "Engine RPM (rpm),Intake Manifold Pressure (kPa),"
            + "Map RPM Cell (rpm),Map Axis Value,Map Axis Source (1=MAP 2=LOAD 3=SYNTH_MAP),"
            + "Map Sample Accepted (1/0),Map Reject Code,"
            + "VE Map Sample Accepted (1/0),VE Map Reject Code,"
            + "VE Map Cell VE (%),VE Map Cell Hits,VE Map Cell Confidence (0-1)";

    private static String row(String fuel, double rpm, double map, int rpmCell,
                              double axisVal, String cellVe, String hits) {
        return "2026-07-22T10:00:00,1.0," + fuel + ",Closed,TOYOTA,MR2KT9F30E1234567,"
                + rpm + "," + map + ","
                + rpmCell + "," + axisVal + ",1,"
                + "1,0,"
                + "1,0,"
                + cellVe + "," + hits + ",0.5";
    }

    @Test
    public void headerResolvesVeColumns() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        assertTrue(c.hasVeColumns());
        assertTrue(c.veCellVeIdx > 0);
        assertTrue(c.veCellHitsIdx > 0);
        assertTrue(c.mapAxisSourceIdx > 0);
        // The regression this guards: "Map Sample Accepted (1/0)" must resolve
        // (old "map accepted" pattern never matched), and the VE variant must
        // not steal it.
        assertEquals(11, c.mapAcceptedIdx);
    }

    @Test
    public void parsesVeCellFromRow() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        LogReplayParser.VeCellSample v = LogReplayParser.parseVeCell(
                row("petrol", 2400, 95, 2500, 90.00, "89.1", "7"), c);
        assertNotNull(v);
        assertEquals(FuelMode.PETROL, v.fuelMode);
        assertEquals(2500, v.rpmCell);
        assertEquals(90.0, v.axisValue, 1e-9);
        assertEquals(89.1, v.cellVe, 1e-9);
        assertEquals(7, v.cellHits);
        assertEquals(1, v.axisSourceCode);
    }

    @Test
    public void blankVeCellRowsAreSkipped() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        assertNull(LogReplayParser.parseVeCell(
                row("petrol", 2400, 95, 2500, 90.00, "", ""), c));
    }

    @Test
    public void logsWithoutVeColumnsYieldNothing() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(
                "timestamp,elapsed_s,fuel_mode,Engine RPM (rpm)");
        assertNull(LogReplayParser.parseVeCell("t,1.0,petrol,2400", c));
    }

    @Test
    public void replayRebuildsSurfaceWithLastRowWinningPerCell() {
        LogReplayParser.Columns c = LogReplayParser.parseHeader(HEADER);
        VeMapStore store = new VeMapStore();

        // Same petrol cell three times with a converging running mean, then an
        // LPG row in the same cell — a two-fuel session replay.
        String[] lines = {
                row("petrol", 2400, 95, 2500, 90.00, "88.0", "1"),
                row("petrol", 2400, 95, 2500, 90.00, "88.9", "2"),
                row("petrol", 2400, 95, 2500, 90.00, "89.1", "3"),
                row("lpg/cng", 2400, 95, 2500, 90.00, "80.2", "3"),
        };
        for (String line : lines) {
            LogReplayParser.VeCellSample v = LogReplayParser.parseVeCell(line, c);
            assertNotNull(v);
            store.putReplayCell(v.fuelMode.isGaseous(), v.rpmCell, v.axisValue,
                    v.cellVe, v.cellHits, v.axisSourceCode);
        }

        String key = MapBinning.cellKey(2500, 90.0);
        assertEquals("last row per cell must win (running mean)",
                89.1, store.getPetrolData().get(key).getVe(), 1e-9);
        assertEquals(3, store.getPetrolData().get(key).getCount());
        assertEquals(80.2, store.getLpgData().get(key).getVe(), 1e-9);

        // Both sides restored on the same axis → the ΔVE view can compare.
        VeMapStore.VeSnapshot snap = store.snapshot();
        assertTrue(snap.isComparisonAxisCompatible());
        assertEquals(1, snap.getOverlappingCellCount());
        assertEquals(8.9, snap.getAveragePetrolMinusLpg(), 1e-6);
    }

    @Test
    public void replayRejectsImplausibleCellValues() {
        VeMapStore store = new VeMapStore();
        store.putReplayCell(false, 2500, 90.0, 500.0, 3, 1); // outside VE range
        store.putReplayCell(false, 2500, 90.0, 90.0, 0, 1);  // zero hits
        assertTrue(store.getPetrolData().isEmpty());
    }
}
