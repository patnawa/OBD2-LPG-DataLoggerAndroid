package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * VE-map accumulation, gating and petrol−LPG ΔVE diagnostic tests.
 *
 * <p>The store reuses {@link MapSampleMeta} binning, so a sample is built with
 * {@link MapSampleMeta#fromValues(Double, Double, Double, Double, Double, Double, Double)}
 * exactly as the poll path does.
 */
public class VeMapStoreTest {

    /** Warm, closed-loop sample at (rpm, map) — VE gating ignores trim/loop. */
    private static MapSampleMeta meta(double rpm, double mapKpa) {
        return MapSampleMeta.fromValues(rpm, mapKpa, null, 0.0, 0.0, 90.0, 2.0);
    }

    /** Push the same cell enough times to clear the 2-in-window debounce and mature it. */
    private static void primeCell(VeMapStore store, MapSampleMeta m, FuelMode fuel,
                                  double ve, int times) {
        for (int i = 0; i < times; i++) {
            store.push(m, fuel, ve);
        }
    }

    @Test
    public void accumulatesStableCellAfterDebounce() {
        VeMapStore store = new VeMapStore();
        MapSampleMeta m = meta(2000, 40);

        // First push is always eaten by the debounce (needs a prior match).
        VeMapStore.VePushResult first = store.push(m, FuelMode.PETROL, 90.0);
        assertFalse(first.accepted);
        assertEquals("debounce", first.reason);

        // Second identical push clears the window and lands.
        VeMapStore.VePushResult second = store.push(m, FuelMode.PETROL, 90.0);
        assertTrue(second.accepted);

        VeMapStore.VeCell cell = store.getPetrolData().get(m.cellKey);
        assertNotNull(cell);
        assertEquals(90.0, cell.getVe(), 1e-6);
        assertEquals(1, cell.getCount());
    }

    @Test
    public void rejectsOutOfRangeVe() {
        VeMapStore store = new VeMapStore();
        MapSampleMeta m = meta(2000, 40);
        VeMapStore.VePushResult r = store.push(m, FuelMode.PETROL, 500.0);
        assertFalse(r.accepted);
        assertEquals("no_ve", r.reason);
        assertNull(store.getPetrolData().get(m.cellKey));
    }

    @Test
    public void rejectsNullVe() {
        VeMapStore store = new VeMapStore();
        MapSampleMeta m = meta(2000, 40);
        VeMapStore.VePushResult r = store.push(m, FuelMode.PETROL, null);
        assertFalse(r.accepted);
        assertEquals("no_ve", r.reason);
    }

    @Test
    public void rejectsColdEngine() {
        VeMapStore store = new VeMapStore();
        // ECT below the 80°C warm gate.
        MapSampleMeta cold = MapSampleMeta.fromValues(2000.0, 40.0, null, 0.0, 0.0, 40.0, 2.0);
        VeMapStore.VePushResult r = store.push(cold, FuelMode.PETROL, 90.0);
        assertFalse(r.accepted);
        assertEquals("cold_engine", r.reason);
    }

    @Test
    public void rejectsTransientRpmStep() {
        VeMapStore store = new VeMapStore();
        // Seed a previous sample, then jump RPM by more than the step threshold.
        store.push(meta(2000, 40), FuelMode.PETROL, 90.0);
        VeMapStore.VePushResult r = store.push(meta(3000, 40), FuelMode.PETROL, 90.0);
        assertFalse(r.accepted);
        assertEquals("transient", r.reason);
    }

    @Test
    public void rejectsSampleWithoutCell() {
        VeMapStore store = new VeMapStore();
        // No RPM → no grid cell.
        MapSampleMeta noRpm = MapSampleMeta.fromValues(null, 40.0, null, 0.0, 0.0, 90.0, 2.0);
        VeMapStore.VePushResult r = store.push(noRpm, FuelMode.PETROL, 90.0);
        assertFalse(r.accepted);
        assertEquals("no_cell", r.reason);
    }

    @Test
    public void separatesPetrolAndGaseousSides() {
        VeMapStore store = new VeMapStore();
        MapSampleMeta m = meta(2500, 50);
        primeCell(store, m, FuelMode.PETROL, 92.0, 4);
        primeCell(store, m, FuelMode.LPG, 84.0, 4);

        assertEquals(92.0, store.getPetrolData().get(m.cellKey).getVe(), 1e-6);
        assertEquals(84.0, store.getLpgData().get(m.cellKey).getVe(), 1e-6);
    }

    @Test
    public void deltaVe_reportsPetrolMinusLpgLoss() {
        VeMapStore store = new VeMapStore();
        MapSampleMeta m = meta(2500, 50);
        // Mature both fuels in the same cell: 4 pushes → 3 accepted (>= MIN_COMPARE_HITS).
        primeCell(store, m, FuelMode.PETROL, 92.0, 4);
        primeCell(store, m, FuelMode.LPG, 84.0, 4);

        VeMapStore.VeSnapshot snap = store.snapshot();
        assertTrue(snap.isComparisonAxisCompatible());
        assertEquals(1, snap.getOverlappingCellCount());
        // Petrol breathes ~8 points better than LPG at this cell.
        assertEquals(8.0, snap.getAveragePetrolMinusLpg(), 1e-6);
        assertEquals(m.cellKey, snap.getMaxLossCell());
    }

    @Test
    public void deltaVe_ignoresImmatureOverlap() {
        VeMapStore store = new VeMapStore();
        MapSampleMeta m = meta(2500, 50);
        // Petrol matured, LPG only one accepted hit (< MIN_COMPARE_HITS) → not compared.
        primeCell(store, m, FuelMode.PETROL, 92.0, 4);
        primeCell(store, m, FuelMode.LPG, 84.0, 2);

        VeMapStore.VeSnapshot snap = store.snapshot();
        assertEquals(0, snap.getOverlappingCellCount());
        assertEquals(0.0, snap.getAveragePetrolMinusLpg(), 1e-6);
    }

    @Test
    public void confidenceGrowsWithHits() {
        VeMapStore store = new VeMapStore();
        MapSampleMeta m = meta(3000, 60);
        primeCell(store, m, FuelMode.PETROL, 88.0, 3);
        double low = store.getPetrolData().get(m.cellKey).getConfidence();
        primeCell(store, m, FuelMode.PETROL, 88.0, 12);
        double high = store.getPetrolData().get(m.cellKey).getConfidence();
        assertTrue("confidence should rise with more hits", high > low);
        assertTrue(high <= 1.0);
    }

    @Test
    public void clearByFuelLeavesOtherSideIntact() {
        VeMapStore store = new VeMapStore();
        MapSampleMeta m = meta(2000, 40);
        primeCell(store, m, FuelMode.PETROL, 90.0, 4);
        primeCell(store, m, FuelMode.LPG, 82.0, 4);

        store.clear(FuelMode.LPG);
        assertNotNull(store.getPetrolData().get(m.cellKey));
        assertNull(store.getLpgData().get(m.cellKey));
    }

    @Test
    public void deltaCsvExportsGridWhenAxesCompatible() {
        VeMapStore store = new VeMapStore();
        MapSampleMeta m = meta(2500, 50);
        primeCell(store, m, FuelMode.PETROL, 92.0, 4);
        primeCell(store, m, FuelMode.LPG, 84.0, 4);

        String csv = store.exportDeltaVeCsv();
        assertTrue(csv.startsWith("MAP kPa \\ RPM"));
        // The matured cell prints its 8.0-point petrol−LPG gap somewhere in the grid.
        assertTrue(csv.contains("8.0"));
    }
}
