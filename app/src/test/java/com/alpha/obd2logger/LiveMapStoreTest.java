package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for live map cell targeting, debounce, hard-lock, import.
 */
public class LiveMapStoreTest {

    @Test
    public void floorBinningUses500WideCellsNotRound() {
        // FLOOR: cell = (int)(rpm/500)*500, clamped to [500, 6500].
        // 749 and 750 both land in the 500 cell (range [500, 1000)).
        // Previously ROUND put 750 at 1000, making the live highlight 250 RPM ahead.
        assertEquals(500, MapBinning.binRpm(500));
        assertEquals(500, MapBinning.binRpm(749));
        assertEquals(500, MapBinning.binRpm(750));
        assertEquals(500, MapBinning.binRpm(999));
        assertEquals(1000, MapBinning.binRpm(1000));
        assertEquals(1000, MapBinning.binRpm(1499));
        assertEquals(1500, MapBinning.binRpm(1500));
    }

    @Test
    public void cellKeyIsCanonical() {
        String k = MapBinning.cellKey(2000, 40f);
        assertEquals("2000_40.00", k);
        assertEquals("2000_40.00", LiveMapStore.normalizeCellKey("2000_40"));
        assertEquals("2000_40.00", LiveMapStore.normalizeCellKey("2000_40.0"));
        // re-bin legacy values
        assertEquals(MapBinning.cellKey(MapBinning.binRpm(2100), MapBinning.binMap(41)),
                LiveMapStore.normalizeCellKey("2100_41"));
    }

    @Test
    public void activeCellTracksEvenWhenDebounced() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta m = MapSampleMeta.fromLegacy(2000, 40, 3.0, true, 90.0);
        // First sample is always debounced (need ≥2 hits in window)
        LiveMapStore.PushResult r1 = store.pushFromMeta(m, FuelMode.PETROL);
        assertFalse(r1.accepted);
        assertEquals("debounce", r1.reason);
        // Cursor must still track right cell for UI highlight
        assertEquals(m.rpmCell, store.getActiveRpmCell());
        assertEquals(m.mapBin, store.getActiveMapBin(), 0.01f);
        assertEquals(m.cellKey, store.getLastCellKey());

        // Second consecutive sample of same cell is accepted
        LiveMapStore.PushResult r2 = store.pushFromMeta(m, FuelMode.PETROL);
        assertTrue(r2.accepted);
        assertEquals(1, store.getPetrolData().get(m.cellKey).getHitCount());
    }

    @Test
    public void openLoopRejectedButCursorUpdates() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta open = MapSampleMeta.fromLegacy(2500, 50, 8.0, false, 90.0);
        LiveMapStore.PushResult r = store.pushFromMeta(open, FuelMode.PETROL);
        assertFalse(r.accepted);
        assertEquals("open_loop", r.reason);
        assertEquals(open.rpmCell, store.getActiveRpmCell());
        assertTrue(store.getPetrolData().isEmpty());
    }

    @Test
    public void hardLockStopsAfterMaxHits() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta m = MapSampleMeta.fromLegacy(1500, 30, 1.0, true, 90.0);
        // Two priming samples for debounce
        store.pushFromMeta(m, FuelMode.LPG);
        for (int i = 0; i < LiveMapStore.TrimData.MAX_HITS + 5; i++) {
            store.pushFromMeta(m, FuelMode.LPG);
        }
        LiveMapStore.TrimData cell = store.getLpgData().get(m.cellKey);
        assertNotNull(cell);
        assertEquals(LiveMapStore.TrimData.MAX_HITS, cell.getHitCount());
        assertTrue(cell.isLocked());
    }

    @Test
    public void importNormalizesKeysAndEnablesCompare() {
        LiveMapStore store = new LiveMapStore();
        // Import petrol baseline with legacy key form
        store.putImportedCell(false, "2000_40", -2.0, 10);
        assertTrue(store.getPetrolData().containsKey("2000_40.00"));
        assertEquals(-2.0, store.getPetrolData().get("2000_40.00").getAverage(), 0.001);

        // Live-drive LPG onto reverse of gate (need 2 same-cell)
        MapSampleMeta m = MapSampleMeta.fromLegacy(2000, 40, 4.0, true, 90.0);
        store.pushFromMeta(m, FuelMode.LPG);
        store.pushFromMeta(m, FuelMode.LPG);
        assertTrue(store.hasAnyCorrection());
        LiveMapStore.MapSnapshot snap = store.snapshot();
        assertEquals(1, snap.getOverlappingCellCount());
        // LPG avg ≈ 4, petrol -2 → deviation +6
        assertEquals(6.0, snap.getMaxDeviation(), 0.01);
    }

    @Test
    public void snapshotIncludesActiveCursor() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta m = MapSampleMeta.fromLegacy(3000, 80, 2.5, true, 95.0);
        store.pushFromMeta(m, FuelMode.PETROL);
        LiveMapStore.MapSnapshot snap = store.snapshot();
        assertEquals(m.rpmCell, snap.getActiveRpmCell());
        assertEquals(m.mapBin, snap.getActiveMapBin(), 0.01f);
        assertEquals(m.cellKey, snap.getLastCellKey());
    }

    @Test
    public void metaFromRecordDetectsClosedLoop() {
        java.util.List<SensorSample> samples = new java.util.ArrayList<>();
        samples.add(new SensorSample("01_0C", "Engine RPM", 2000.0, "rpm", "ok"));
        samples.add(new SensorSample("01_0B", "MAP", 45.0, "kPa", "ok"));
        samples.add(new SensorSample("01_06", "STFT", 2.0, "%", "ok"));
        samples.add(new SensorSample("01_07", "LTFT", 1.0, "%", "ok"));
        samples.add(new SensorSample("01_05", "ECT", 88.0, "C", "ok"));
        samples.add(new SensorSample("01_03", "FuelStatus", 2.0, "", "ok"));
        DataRecord rec = new DataRecord("t", 1.0, "petrol", "Toyota", "VIN", samples);
        MapSampleMeta meta = MapSampleMeta.from(rec);
        assertTrue(meta.gatedEligible);
        assertEquals(3.0, meta.trimTotal, 0.001);
        assertEquals(MapSampleMeta.AXIS_MAP, meta.axisSource);
        assertEquals(MapBinning.cellKey(MapBinning.binRpm(2000), MapBinning.binMap(45)), meta.cellKey);
    }

    @Test
    public void synthesizedMapIsExplicitAndMissingSafetySignalsAreRejected() {
        java.util.List<SensorSample> samples = new java.util.ArrayList<>();
        samples.add(new SensorSample("01_0C", "Engine RPM", 2000.0, "rpm", "ok"));
        samples.add(new SensorSample("01_0B", "MAP", 45.0, "kPa", "synth"));
        samples.add(new SensorSample("01_06", "STFT", 2.0, "%", "ok"));
        DataRecord rec = new DataRecord("t", 1.0, "petrol", "Toyota", "VIN", samples);

        MapSampleMeta meta = MapSampleMeta.from(rec);

        assertFalse(meta.gatedEligible);
        assertEquals("no_fuel_status", meta.rejectReason);
        assertEquals(MapSampleMeta.AXIS_SYNTH_MAP, meta.axisSource);
        assertEquals(3.0, MapSampleMeta.axisSourceCode(meta.axisSource), 0.0);
    }

    @Test
    public void missingCoolantAndTrimStayMissingInLogMetadata() {
        java.util.List<SensorSample> samples = new java.util.ArrayList<>();
        samples.add(new SensorSample("01_0C", "Engine RPM", 2000.0, "rpm", "ok"));
        samples.add(new SensorSample("01_0B", "MAP", 45.0, "kPa", "ok"));
        samples.add(new SensorSample("01_03", "FuelStatus", 2.0, "", "ok"));
        DataRecord rec = new DataRecord("t", 1.0, "petrol", "Toyota", "VIN", samples);

        MapSampleMeta meta = MapSampleMeta.from(rec);
        assertFalse(meta.gatedEligible);
        assertEquals("no_coolant", meta.rejectReason);
        meta.appendLogSamples(samples, false, meta.rejectReason);

        SensorSample trim = null;
        SensorSample warm = null;
        for (SensorSample sample : samples) {
            if ("map_trim_total".equals(sample.getPidKey())) trim = sample;
            if ("map_warm".equals(sample.getPidKey())) warm = sample;
        }
        assertNotNull(trim);
        assertNull(trim.getValue());
        assertEquals("unavailable", trim.getStatus());
        assertNotNull(warm);
        assertNull(warm.getValue());
        assertEquals(9.0, MapSampleMeta.rejectCode(meta.rejectReason), 0.0);
    }

    @Test
    public void clearRemovesBothFuelMapsAndCursor() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta sample = MapSampleMeta.fromLegacy(2000, 40, 2.0, true, 90.0);
        store.pushFromMeta(sample, FuelMode.PETROL);
        store.pushFromMeta(sample, FuelMode.PETROL);
        store.pushFromMeta(sample, FuelMode.LPG);
        store.pushFromMeta(sample, FuelMode.LPG);
        assertFalse(store.getPetrolData().isEmpty());
        assertFalse(store.getLpgData().isEmpty());

        store.clear();
        assertTrue(store.getPetrolData().isEmpty());
        assertTrue(store.getLpgData().isEmpty());
        assertEquals(-1, store.getActiveRpmCell());
        assertEquals("", store.getLastCellKey());
    }
}
