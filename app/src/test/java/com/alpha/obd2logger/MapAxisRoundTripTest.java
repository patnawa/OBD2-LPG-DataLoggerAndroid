package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * A fuel map must carry the axis its cells were binned on.
 *
 * <p>Import used to assert {@code MAP} for any incoming map. A baseline captured
 * on a vehicle without PID 0x0B is binned on engine-load %, so relabelling it
 * kPa let the correction export subtract %-binned cells from kPa-binned ones
 * with no warning — and locked the axis, so every later live sample on that
 * vehicle was rejected as {@code axis_mismatch} and the map never learned again.
 */
public class MapAxisRoundTripTest {

    private static Map<String, LiveMapStore.TrimData> oneCell(double avg, int hits) {
        Map<String, LiveMapStore.TrimData> data = new HashMap<>();
        data.put(MapBinning.cellKey(2000, 60f), new LiveMapStore.TrimData(avg * hits, hits));
        return data;
    }

    /** A declared axis must be stored as given, not overwritten with MAP. */
    @Test
    public void declaredLoadAxisSurvivesImport() {
        LiveMapStore store = new LiveMapStore();
        store.importPetrol(oneCell(-2.5, 10), true, MapSampleMeta.AXIS_LOAD);

        assertEquals(MapSampleMeta.AXIS_LOAD, store.getPetrolAxisSource());
    }

    @Test
    public void declaredSynthAxisSurvivesImport() {
        LiveMapStore store = new LiveMapStore();
        store.importLpg(oneCell(1.5, 12), true, MapSampleMeta.AXIS_SYNTH_MAP);

        assertEquals(MapSampleMeta.AXIS_SYNTH_MAP, store.getLpgAxisSource());
    }

    /**
     * The corruption the fabrication allowed: a LOAD-binned petrol baseline and
     * a MAP-binned LPG map must never be compared, because the numbers are on
     * different scales even though the cell keys collide.
     */
    @Test
    public void loadBaselineIsNotComparedAgainstAMapAxisMap() {
        LiveMapStore store = new LiveMapStore();
        store.importPetrol(oneCell(-2.5, 10), true, MapSampleMeta.AXIS_LOAD);
        store.importLpg(oneCell(6.0, 12), true, MapSampleMeta.AXIS_MAP);

        LiveMapStore.MapSnapshot snap = store.snapshot();
        assertFalse("axes differ — comparison must be refused",
                snap.isComparisonAxisCompatible());
        assertEquals("no deviation may be reported across incompatible axes",
                0.0, snap.getAverageAbsoluteDeviation(), 0.0001);
        assertEquals(0, snap.getOverlappingCellCount());
    }

    /** Matching declared axes stay comparable. */
    @Test
    public void matchingDeclaredAxesRemainComparable() {
        LiveMapStore store = new LiveMapStore();
        store.importPetrol(oneCell(-2.0, 10), true, MapSampleMeta.AXIS_LOAD);
        store.importLpg(oneCell(4.0, 12), true, MapSampleMeta.AXIS_LOAD);

        LiveMapStore.MapSnapshot snap = store.snapshot();
        assertTrue(snap.isComparisonAxisCompatible());
        assertEquals(6.0, snap.getAverageAbsoluteDeviation(), 0.0001);
    }

    /**
     * Payloads written before the axis field existed must keep working. The
     * legacy MAP assumption is retained deliberately — but it is now logged,
     * and callers that know better can override it.
     */
    @Test
    public void undeclaredAxisFallsBackToTheLegacyMapAssumption() {
        LiveMapStore store = new LiveMapStore();
        store.importPetrol(oneCell(-2.5, 10), true);

        assertEquals(MapSampleMeta.AXIS_MAP, store.getPetrolAxisSource());
    }

    /** A live LOAD-axis vehicle must keep learning after a LOAD baseline import. */
    @Test
    public void loadAxisImportDoesNotLockOutLoadAxisLearning() {
        LiveMapStore store = new LiveMapStore();
        store.importPetrol(oneCell(-2.0, 3), true, MapSampleMeta.AXIS_LOAD);

        // Live sample from a vehicle with no MAP PID: axis is LOAD.
        MapSampleMeta live = MapSampleMeta.fromValues(
                2500.0, null, 45.0, 3.0, 2.0, 90.0, 2.0);
        assertEquals(MapSampleMeta.AXIS_LOAD, live.axisSource);

        store.pushFromMeta(live, FuelMode.PETROL);
        LiveMapStore.PushResult second = store.pushFromMeta(live, FuelMode.PETROL);

        assertTrue("a LOAD-axis vehicle must still learn after a LOAD import: "
                + second.reason, second.accepted);
    }

    /** An unknown axis name must not lock the map to something nothing matches. */
    @Test
    public void unknownAxisNameIsTreatedAsUndeclared() {
        LiveMapStore store = new LiveMapStore();
        store.importPetrol(oneCell(-2.5, 10), true, "TORQUE");

        assertEquals("an unrecognised axis must not be stored verbatim",
                MapSampleMeta.AXIS_MAP, store.getPetrolAxisSource());
    }
}
