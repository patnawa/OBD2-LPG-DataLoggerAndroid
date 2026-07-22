package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage-layer tests: VE surface disk round-trip, health-trend series
 * persistence/capping, and the poll-span time-alignment (skew) gate.
 */
@RunWith(RobolectricTestRunner.class)
public class VePersistenceAndSkewTest {

    private static MapSampleMeta warmMeta(double rpm, double mapKpa) {
        return MapSampleMeta.fromValues(rpm, mapKpa, null, 0.0, 0.0, 90.0, 2.0);
    }

    private static void learn(VeMapStore store, double rpm, double map,
                              FuelMode fuel, double ve, int times) {
        MapSampleMeta m = warmMeta(rpm, map);
        for (int i = 0; i < times; i++) {
            store.push(m, fuel, ve);
        }
    }

    // ── VE surface persistence ──────────────────────────────────────────────

    @Test
    public void veSurfaceSurvivesJsonRoundTrip() {
        VeMapStore original = new VeMapStore();
        learn(original, 2000, 40, FuelMode.PETROL, 91.0, 5);
        learn(original, 2500, 50, FuelMode.LPG, 83.0, 5);

        VeMapStore restored = new VeMapStore();
        assertTrue(restored.importFromJson(original.exportToJson()));

        String petrolKey = MapBinning.cellKey(2000.0, 40.0);
        String lpgKey = MapBinning.cellKey(2500.0, 50.0);
        assertEquals(91.0, restored.getPetrolData().get(petrolKey).getVe(), 0.01);
        assertEquals(83.0, restored.getLpgData().get(lpgKey).getVe(), 0.01);
        assertEquals(original.getPetrolData().get(petrolKey).getCount(),
                restored.getPetrolData().get(petrolKey).getCount());
        assertEquals(MapSampleMeta.AXIS_MAP, restored.getPetrolAxisSource());
        assertEquals(MapSampleMeta.AXIS_MAP, restored.getLpgAxisSource());
    }

    @Test
    public void veSurfaceSurvivesDiskRoundTrip() {
        Context context = RuntimeEnvironment.getApplication();
        VeMapStore original = new VeMapStore();
        learn(original, 3000, 60, FuelMode.PETROL, 95.0, 4);
        VeMapPersistence.save(context, original);

        VeMapStore restored = new VeMapStore();
        assertTrue(VeMapPersistence.load(context, restored));
        assertEquals(95.0, restored.getPetrolData()
                .get(MapBinning.cellKey(3000.0, 60.0)).getVe(), 0.01);

        VeMapPersistence.clear(context);
        assertFalse(VeMapPersistence.load(context, new VeMapStore()));
    }

    @Test
    public void restoredSurfaceKeepsLearning() {
        // Import must not lock the axis or the gate against live samples.
        VeMapStore original = new VeMapStore();
        learn(original, 2000, 40, FuelMode.PETROL, 91.0, 5);

        VeMapStore restored = new VeMapStore();
        restored.importFromJson(original.exportToJson());
        learn(restored, 2000, 40, FuelMode.PETROL, 92.0, 4);

        VeMapStore.VeCell cell = restored.getPetrolData()
                .get(MapBinning.cellKey(2000.0, 40.0));
        assertTrue("restored cell must keep accumulating",
                cell.getCount() > original.getPetrolData()
                        .get(MapBinning.cellKey(2000.0, 40.0)).getCount());
    }

    @Test
    public void corruptOrEmptyJsonIsRejected() {
        VeMapStore store = new VeMapStore();
        assertFalse(store.importFromJson(null));
        try {
            org.json.JSONObject wrongVersion = new org.json.JSONObject();
            wrongVersion.put("schema_version", 999);
            assertFalse(store.importFromJson(wrongVersion));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── HealthTrendStore ────────────────────────────────────────────────────

    @Test
    public void trendSeriesPersistsAndCaps() {
        Context context = RuntimeEnvironment.getApplication();
        String series = "test_series";
        HealthTrendStore.clear(context, series);

        long t = 1_700_000_000_000L;
        for (int i = 0; i < HealthTrendStore.MAX_POINTS + 10; i++) {
            assertTrue(HealthTrendStore.append(context, series, t + i, i));
        }
        List<HealthTrendStore.Point> points = HealthTrendStore.read(context, series);
        assertEquals(HealthTrendStore.MAX_POINTS, points.size());
        // Oldest fell off; newest survived.
        assertEquals(10.0, points.get(0).value, 1e-9);
        assertEquals(HealthTrendStore.MAX_POINTS + 9,
                points.get(points.size() - 1).value, 1e-9);
    }

    @Test
    public void trendSeriesRejectsClockRollback() {
        Context context = RuntimeEnvironment.getApplication();
        String series = "rollback_series";
        HealthTrendStore.clear(context, series);
        assertTrue(HealthTrendStore.append(context, series, 2_000L, 1.0));
        assertFalse("older timestamp must be dropped",
                HealthTrendStore.append(context, series, 1_000L, 2.0));
        assertEquals(1, HealthTrendStore.read(context, series).size());
    }

    // ── Poll-span skew gate ─────────────────────────────────────────────────

    /** Driver whose batch acquisition "takes" a configurable elapsed time. */
    private static class SlowDriver extends BaseDriver {
        private final Map<String, Double> values;
        private final long spanMs;

        SlowDriver(Map<String, Double> values, long spanMs) {
            super(new LoggerConfig());
            this.values = values;
            this.spanMs = spanMs;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { }
        @Override public boolean isConnected() { return true; }
        @Override public Double queryPid(PIDDefinition pidDef) { return values.get(pidDef.getName()); }

        @Override
        public Map<String, Double> queryPidBatch(List<PIDDefinition> pids) {
            // Advance the Robolectric clock — deterministic, no real sleeping.
            ShadowSystemClock.advanceBy(Duration.ofMillis(spanMs));
            Map<String, Double> out = new HashMap<>();
            for (PIDDefinition pid : pids) {
                Double v = values.get(pid.getName());
                if (v != null) out.put(pid.getName(), v);
            }
            return out;
        }
    }

    private static PIDDefinition pid(String name, String service, String hex, String unit) {
        return new PIDDefinition(name, service, hex, unit, "A", 0, 10000, false, 1);
    }

    private static PollingEngine mapEngine(LoggerConfig config) {
        config.showAirDensity = false;
        config.showTurboBoost = false;
        config.dpfMonitorEnabled = false;
        config.showFuelConsumption = false;
        List<PIDDefinition> pids = new ArrayList<>();
        pids.add(pid("Engine RPM", "01", "0C", "rpm"));
        pids.add(pid("Intake Manifold Pressure", "01", "0B", "kPa"));
        pids.add(pid("Fuel System Status", "01", "03", ""));
        pids.add(pid("Coolant Temp", "01", "05", "C"));
        pids.add(pid("STFT B1", "01", "06", "%"));
        pids.add(pid("LTFT B1", "01", "07", "%"));
        return new PollingEngine(config, pids, 0L);
    }

    private static Map<String, Double> closedLoopValues() {
        Map<String, Double> values = new HashMap<>();
        values.put("Engine RPM", 2000.0);
        values.put("Intake Manifold Pressure", 40.0);
        values.put("Fuel System Status", 2.0);
        values.put("Coolant Temp", 92.0);
        values.put("STFT B1", 1.5);
        values.put("LTFT B1", 2.0);
        return values;
    }

    @Test
    public void skewedBatchNeverReachesTheLearnedMaps() throws Exception {
        LoggerConfig config = new LoggerConfig();
        PollingEngine engine = mapEngine(config);
        LiveMapStore mapStore = new LiveMapStore();

        SlowDriver slow = new SlowDriver(closedLoopValues(),
                PollingEngine.MAX_COHERENT_SPAN_MS + 500);
        PollingEngine.PollOutcome outcome = engine.poll(slow, null, mapStore, null);

        assertNull("skewed batch must not be pushed", outcome.mapPush);
        Double rejectCode = null;
        for (SensorSample s : outcome.record.getSamples()) {
            if ("map_reject_code".equals(s.getPidKey())) rejectCode = s.getValue();
        }
        assertNotNull(rejectCode);
        assertEquals("reject code must be 16 (skew)", 16.0, rejectCode, 1e-9);
        assertEquals(0, mapStore.getPetrolData().size());
    }

    @Test
    public void fastBatchStillLearnsNormally() throws Exception {
        LoggerConfig config = new LoggerConfig();
        PollingEngine engine = mapEngine(config);
        LiveMapStore mapStore = new LiveMapStore();

        SlowDriver fast = new SlowDriver(closedLoopValues(), 200);
        PollingEngine.PollOutcome last = null;
        for (int i = 0; i < 4; i++) {
            last = engine.poll(fast, null, mapStore, null);
        }
        assertNotNull(last.mapPush);
        assertTrue("un-skewed closed-loop samples must be accepted",
                last.mapPush.accepted);
    }
}
