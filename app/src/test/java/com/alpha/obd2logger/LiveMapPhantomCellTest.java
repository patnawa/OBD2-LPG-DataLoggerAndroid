package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A cell must never be interned into the map without a real sample behind it.
 *
 * <p>{@code pushFromMeta} used to {@code put()} a fresh {@code TrimData} before
 * the value was validated, and {@code addStableSample} silently drops a
 * non-finite trim — so a NaN produced a permanent {@code hitCount == 0} cell
 * that the method still reported as accepted. Nothing downstream filters empty
 * cells and {@code getAverage()} returns 0 for them, so the phantom read as a
 * genuine "0% trim measured": it corrupted the correction export, the session
 * deviation stats, and the on-screen map.
 */
public class LiveMapPhantomCellTest {

    /** A steady, warm, closed-loop sample with the given trim. */
    private static MapSampleMeta sample(double stft, double ltft) {
        return MapSampleMeta.fromValues(
                2000.0,   // rpm
                60.0,     // map
                45.0,     // load
                stft,
                ltft,
                90.0,     // ect — warm
                2.0);     // fuel system status — closed loop
    }

    private static LiveMapStore.PushResult pushRepeatedly(LiveMapStore store,
                                                          MapSampleMeta meta, int times) {
        LiveMapStore.PushResult last = null;
        for (int i = 0; i < times; i++) {
            last = store.pushFromMeta(meta, FuelMode.PETROL);
        }
        return last;
    }

    /** Baseline: a finite trim must be accepted and counted exactly once per push. */
    @Test
    public void finiteTrimIsAcceptedAndCounted() {
        LiveMapStore store = new LiveMapStore();
        pushRepeatedly(store, sample(3.0, 5.0), 6);

        LiveMapStore.MapSnapshot snap = store.snapshot();
        assertEquals("exactly one cell should exist", 1, snap.getPetrolData().size());
        LiveMapStore.TrimData cell = snap.getPetrolData().values().iterator().next();
        assertTrue("cell must hold real samples", cell.getHitCount() > 0);
        assertEquals("STFT+LTFT is the stored trim", 8.0, cell.getAverage(), 0.001);
    }

    /**
     * The bug: a NaN trim passed every eligibility gate ({@code hasTrim} is set
     * from {@code stft != null}, never from finiteness) and reached the store.
     */
    @Test
    public void nonFiniteTrimIsRejectedAndInternsNoCell() {
        LiveMapStore store = new LiveMapStore();
        LiveMapStore.PushResult result = pushRepeatedly(store, sample(Double.NaN, 5.0), 6);

        assertNotNull(result);
        assertFalse("a sample the store cannot use must not report accepted",
                result.accepted);
        assertEquals("non_finite_trim", result.reason);
        assertTrue("no phantom cell may be left behind",
                store.snapshot().getPetrolData().isEmpty());
    }

    /** Infinity is the other non-finite case and must behave identically. */
    @Test
    public void infiniteTrimIsRejectedAndInternsNoCell() {
        LiveMapStore store = new LiveMapStore();
        LiveMapStore.PushResult result =
                pushRepeatedly(store, sample(Double.POSITIVE_INFINITY, 0.0), 6);

        assertFalse(result.accepted);
        assertTrue(store.snapshot().getPetrolData().isEmpty());
    }

    /**
     * The precise corruption the phantom caused: an empty petrol cell paired
     * with a real LPG cell made the full LPG trim print as a correction, as if
     * the petrol baseline had measured 0%.
     */
    @Test
    public void noZeroHitCellCanReachTheCorrectionExport() {
        LiveMapStore store = new LiveMapStore();
        // Petrol side: only a non-finite sample — must leave nothing behind.
        pushRepeatedly(store, sample(Double.NaN, 0.0), 6);
        // LPG side: a genuine reading in the same cell.
        for (int i = 0; i < 6; i++) {
            store.pushFromMeta(sample(6.0, 4.0), FuelMode.LPG);
        }

        LiveMapStore.MapSnapshot snap = store.snapshot();
        assertTrue("petrol side must be empty, not zero-valued",
                snap.getPetrolData().isEmpty());
        for (LiveMapStore.TrimData cell : snap.getLpgData().values()) {
            assertTrue("every stored cell must have at least one hit",
                    cell.getHitCount() > 0);
        }
    }

    /** No cell in either map may ever be published with zero hits. */
    @Test
    public void everyPublishedCellHasAtLeastOneHit() {
        LiveMapStore store = new LiveMapStore();
        pushRepeatedly(store, sample(3.0, 5.0), 4);
        pushRepeatedly(store, sample(Double.NaN, 5.0), 4);
        for (int i = 0; i < 4; i++) store.pushFromMeta(sample(2.0, 2.0), FuelMode.LPG);

        LiveMapStore.MapSnapshot snap = store.snapshot();
        for (LiveMapStore.TrimData cell : snap.getPetrolData().values()) {
            assertTrue("petrol cell with no hits was published", cell.getHitCount() > 0);
        }
        for (LiveMapStore.TrimData cell : snap.getLpgData().values()) {
            assertTrue("LPG cell with no hits was published", cell.getHitCount() > 0);
        }
    }
}
