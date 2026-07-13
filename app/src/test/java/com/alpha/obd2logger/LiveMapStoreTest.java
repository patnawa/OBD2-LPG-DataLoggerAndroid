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
    public void acceptedCellKeepsCorrectionComponentsAndMeasuredLambdaSeparate() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta meta = mapMeta(1500, 95, -1.5, 8.5, 0.9998, 1.0, 34.0, "ok");

        store.pushFromMeta(meta, FuelMode.E20); // debounce prime
        LiveMapStore.PushResult result = store.pushFromMeta(meta, FuelMode.E20);

        assertTrue(result.accepted);
        LiveMapStore.TrimData cell = store.getPetrolData().get(meta.cellKey);
        assertNotNull(cell);
        assertEquals(7.0, cell.getAverage(), 0.0001);
        assertEquals(-1.5, cell.getAverageStft(), 0.0001);
        assertEquals(8.5, cell.getAverageLtft(), 0.0001);
        assertEquals(0.9998, cell.getAverageLambda(), 0.0001);
        assertEquals(1, cell.getLambdaCount());
    }

    @Test
    public void lambdaSpikeIsRejectedInsteadOfLearningFalseMixtureCondition() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta spike = mapMeta(1500, 95, 0.0, 7.0, 1.2329, 1.0, 30.0, "ok");

        LiveMapStore.PushResult result = store.pushFromMeta(spike, FuelMode.E20);

        assertFalse(result.accepted);
        assertEquals("lambda_unstable", result.reason);
        assertTrue(store.getPetrolData().isEmpty());
        assertEquals(13.0, MapSampleMeta.rejectCode(result.reason), 0.0);
    }

    @Test
    public void oscillatingShortTermTrimIsRejectedAsUnstable() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta low = mapMeta(1500, 95, -10.0, 5.0, 1.0, 1.0, 30.0, "ok");
        MapSampleMeta high = mapMeta(1500, 95, 10.0, 5.0, 1.0, 1.0, 30.0, "ok");
        store.pushFromMeta(low, FuelMode.E20);
        assertTrue(store.pushFromMeta(high, FuelMode.E20).accepted);

        LiveMapStore.PushResult result = store.pushFromMeta(low, FuelMode.E20);

        assertFalse(result.accepted);
        assertEquals("trim_unstable", result.reason);
        assertEquals(14.0, MapSampleMeta.rejectCode(result.reason), 0.0);
        assertEquals(1, store.getPetrolData().get(low.cellKey).getHitCount());
    }

    @Test
    public void rapidMovementInsideOneCellIsRejectedAsTransient() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta first = mapMeta(1000, 95, 0.0, 5.0, 1.0, 1.0, 20.0, "ok");
        MapSampleMeta jump = mapMeta(1499, 95, 0.0, 5.0, 1.0, 1.0, 20.0, "ok");

        store.pushFromMeta(first, FuelMode.PETROL);
        LiveMapStore.PushResult result = store.pushFromMeta(jump, FuelMode.PETROL);

        assertFalse(result.accepted);
        assertEquals("transient", result.reason);
        assertTrue(store.getPetrolData().isEmpty());
    }

    @Test
    public void comparisonIsDisabledWhenFuelMapsUseDifferentAxisSources() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta petrol = mapMeta(2000, 60, 0.0, 3.0, 1.0, 1.0, 25.0, "ok");
        MapSampleMeta gasSynth = mapMeta(2000, 60, 0.0, 5.0, 1.0, 1.0, 25.0, "synth");

        store.pushFromMeta(petrol, FuelMode.PETROL);
        store.pushFromMeta(petrol, FuelMode.PETROL);
        store.clear(FuelMode.LPG);
        store.pushFromMeta(gasSynth, FuelMode.LPG);
        store.pushFromMeta(gasSynth, FuelMode.LPG);

        LiveMapStore.MapSnapshot snapshot = store.snapshot();
        assertFalse(snapshot.isComparisonAxisCompatible());
        assertEquals(MapSampleMeta.AXIS_MAP, snapshot.getPetrolAxisSource());
        assertEquals(MapSampleMeta.AXIS_SYNTH_MAP, snapshot.getLpgAxisSource());
        assertEquals(0, snapshot.getOverlappingCellCount());
        assertFalse(store.hasAnyCorrection());
    }

    @Test
    public void oneFuelMapCannotSilentlyMixMeasuredAndSyntheticAxes() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta measured = mapMeta(2000, 60, 0.0, 3.0, 1.0, 1.0, 25.0, "ok");
        MapSampleMeta synthetic = mapMeta(2000, 60, 0.0, 3.0, 1.0, 1.0, 25.0, "synth");
        store.pushFromMeta(measured, FuelMode.PETROL);
        store.pushFromMeta(measured, FuelMode.PETROL);

        LiveMapStore.PushResult result = store.pushFromMeta(synthetic, FuelMode.PETROL);

        assertFalse(result.accepted);
        assertEquals("axis_mismatch", result.reason);
        assertEquals(1, store.getPetrolData().get(measured.cellKey).getHitCount());
    }

    @Test
    public void fuelChangeoverCannotReusePreviousFuelDebounceHistory() {
        LiveMapStore store = new LiveMapStore();
        MapSampleMeta stable = mapMeta(2000, 60, 0.0, 4.0, 1.0, 1.0, 25.0, "ok");
        store.pushFromMeta(stable, FuelMode.PETROL);
        assertTrue(store.pushFromMeta(stable, FuelMode.PETROL).accepted);

        LiveMapStore.PushResult firstGasSample = store.pushFromMeta(stable, FuelMode.LPG);

        assertFalse(firstGasSample.accepted);
        assertEquals("debounce", firstGasSample.reason);
        assertTrue(store.getLpgData().isEmpty());
        assertTrue(store.pushFromMeta(stable, FuelMode.LPG).accepted);
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

    @Test
    public void simulationAutoDetectionProducesLearnableLiveMapSamples() {
        SimulationDriver driver = new SimulationDriver(new LoggerConfig());
        assertTrue(driver.connect());
        java.util.List<String> supported = PidAvailabilityChecker.querySupportedPids(driver);
        java.util.List<SensorSample> samples = new java.util.ArrayList<>();
        String[] critical = {"01_0C", "01_0B", "01_06", "01_07", "01_05", "01_03"};
        for (String key : critical) {
            PIDDefinition pid = PIDDefinition.findByKey(key);
            assertNotNull(key, pid);
            assertTrue("Simulation capability must advertise " + key,
                    supported.contains(pid.getPidHex()));
            samples.add(new SensorSample(pid.key(), pid.getName(), driver.queryPid(pid),
                    pid.getUnit(), "ok"));
        }

        DataRecord record = new DataRecord("t", 1.0, "petrol", "Simulation", "SIM", samples);
        MapSampleMeta meta = MapSampleMeta.from(record);
        assertTrue("Simulation record must pass live-map safety gates", meta.gatedEligible);

        LiveMapStore store = new LiveMapStore();
        store.pushFromMeta(meta, FuelMode.PETROL); // debounce prime
        LiveMapStore.PushResult accepted = store.pushFromMeta(meta, FuelMode.PETROL);
        assertTrue(accepted.accepted);
        assertFalse(store.getPetrolData().isEmpty());
    }

    private static MapSampleMeta mapMeta(double rpm, double map, double stft, double ltft,
                                         double lambda, double commandedLambda, double throttle,
                                         String mapStatus) {
        java.util.List<SensorSample> samples = new java.util.ArrayList<>();
        samples.add(new SensorSample("01_0C", "Engine RPM", rpm, "rpm", "ok"));
        samples.add(new SensorSample("01_0B", "MAP", map, "kPa", mapStatus));
        samples.add(new SensorSample("01_06", "STFT", stft, "%", "ok"));
        samples.add(new SensorSample("01_07", "LTFT", ltft, "%", "ok"));
        samples.add(new SensorSample("01_34", "Lambda", lambda, "", "ok"));
        samples.add(new SensorSample("01_44", "Commanded Lambda", commandedLambda, "", "ok"));
        samples.add(new SensorSample("01_11", "Throttle", throttle, "%", "ok"));
        samples.add(new SensorSample("01_05", "ECT", 88.0, "C", "ok"));
        samples.add(new SensorSample("01_03", "FuelStatus", 2.0, "", "ok"));
        return MapSampleMeta.from(new DataRecord("t", 1.0, "petrol", "Test", "VIN", samples));
    }
}
