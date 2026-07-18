package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Locks the behaviour that the background and in-process logging paths must
 * share. Both used to carry their own copy of this pipeline and had silently
 * diverged — the in-process copy lost the NGV km/kg branch and never told
 * AirDensityMonitor that MAP had been synthesized. These tests fail if either
 * regression is reintroduced, in either host.
 */
@RunWith(RobolectricTestRunner.class)
public class PollingEngineTest {

    /** Minimal driver that replays a fixed batch; no transport involved. */
    private static class FakeDriver extends BaseDriver {
        private final Map<String, Double> values;

        FakeDriver(Map<String, Double> values) {
            super(new LoggerConfig());
            this.values = values;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { }
        @Override public boolean isConnected() { return true; }
        @Override public Double queryPid(PIDDefinition pidDef) { return values.get(pidDef.getName()); }

        @Override
        public Map<String, Double> queryPidBatch(List<PIDDefinition> pids) {
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

    private static SensorSample find(DataRecord record, String pidKey) {
        for (SensorSample s : record.getSamples()) {
            if (pidKey.equals(s.getPidKey())) return s;
        }
        return null;
    }

    private static LoggerConfig baseConfig() {
        LoggerConfig config = new LoggerConfig();
        // Air density needs a Context and network; exercised elsewhere.
        config.showAirDensity = false;
        config.showTurboBoost = false;
        config.dpfMonitorEnabled = false;
        config.showFuelConsumption = true;
        return config;
    }

    /**
     * NGV density is gas-phase (0.72 g/L), so the liquid km/L formula always
     * yields < 1 and gets discarded. NGV must report km/kg instead, or fuel
     * economy is silently absent for every NGV session.
     */
    @Test
    public void ngvReportsFuelEconomyInKmPerKg() throws Exception {
        LoggerConfig config = baseConfig();
        config.fuelMode = FuelMode.NGV;

        List<PIDDefinition> pids = new ArrayList<>();
        pids.add(pid("MAF Air Flow", "01", "10", "g/s"));
        pids.add(pid("Vehicle Speed", "01", "0D", "km/h"));

        Map<String, Double> values = new HashMap<>();
        values.put("MAF Air Flow", 12.0);
        values.put("Vehicle Speed", 90.0);

        PollingEngine engine = new PollingEngine(config, pids, 0L);
        DataRecord record = engine.poll(new FakeDriver(values), null, null).record;

        SensorSample kmkg = find(record, "derived_fuel_kmkg");
        assertNotNull("NGV must emit km/kg fuel economy", kmkg);
        assertEquals("km/kg", kmkg.getUnit());
        assertTrue("km/kg must be a usable positive figure", kmkg.getValue() > 0.0);

        assertNull("NGV must not emit the liquid km/L series", find(record, "derived_fuel_kmL"));
    }

    /** Petrol keeps the liquid km/L + L/100km pair. */
    @Test
    public void petrolReportsFuelEconomyInKmPerLitre() throws Exception {
        LoggerConfig config = baseConfig();
        config.fuelMode = FuelMode.PETROL;

        List<PIDDefinition> pids = new ArrayList<>();
        pids.add(pid("MAF Air Flow", "01", "10", "g/s"));
        pids.add(pid("Vehicle Speed", "01", "0D", "km/h"));

        Map<String, Double> values = new HashMap<>();
        values.put("MAF Air Flow", 12.0);
        values.put("Vehicle Speed", 90.0);

        PollingEngine engine = new PollingEngine(config, pids, 0L);
        DataRecord record = engine.poll(new FakeDriver(values), null, null).record;

        assertNotNull(find(record, "derived_fuel_kmL"));
        assertNull(find(record, "derived_fuel_kmkg"));
    }

    /**
     * MAF-only vehicles don't support PID 0x0B. MAP is synthesized from Engine
     * Load so the fuel map keeps a load axis, but it must be tagged "synth" and
     * reported via the outcome flag — otherwise an invented MAP is consumed
     * downstream as a real sensor reading.
     */
    @Test
    public void synthesizedMapIsFlaggedAndTagged() throws Exception {
        LoggerConfig config = baseConfig();

        List<PIDDefinition> pids = new ArrayList<>();
        pids.add(pid("Intake Manifold Pressure", "01", "0B", "kPa"));
        pids.add(pid("Engine Load", "01", "04", "%"));

        Map<String, Double> values = new HashMap<>();
        values.put("Engine Load", 25.0);   // MAP deliberately absent

        PollingEngine engine = new PollingEngine(config, pids, 0L);
        PollingEngine.PollOutcome outcome = engine.poll(new FakeDriver(values), null, null);

        assertTrue("outcome must report MAP as synthesized", outcome.mapSynthesized);

        SensorSample map = find(outcome.record, "01_0B");
        assertNotNull(map);
        assertEquals("synthesized MAP must not be tagged as a measured value",
                "synth", map.getStatus());
        // 30 + (101.3 - 30) * 0.25 = 47.8 kPa — a realistic idle manifold pressure.
        assertEquals(47.8, map.getValue(), 0.05);
    }

    /** A real MAP reading must be left untouched and never flagged synthetic. */
    @Test
    public void measuredMapIsNotSynthesized() throws Exception {
        LoggerConfig config = baseConfig();

        List<PIDDefinition> pids = new ArrayList<>();
        pids.add(pid("Intake Manifold Pressure", "01", "0B", "kPa"));
        pids.add(pid("Engine Load", "01", "04", "%"));

        Map<String, Double> values = new HashMap<>();
        values.put("Intake Manifold Pressure", 62.0);
        values.put("Engine Load", 25.0);

        PollingEngine engine = new PollingEngine(config, pids, 0L);
        PollingEngine.PollOutcome outcome = engine.poll(new FakeDriver(values), null, null);

        assertTrue("measured MAP must not be reported as synthesized", !outcome.mapSynthesized);
        SensorSample map = find(outcome.record, "01_0B");
        assertNotNull(map);
        assertEquals(62.0, map.getValue(), 0.001);
    }

    /** A half-open link must surface as IOException, not as a partial record. */
    @Test(expected = java.io.IOException.class)
    public void disconnectedAdapterMidBatchThrows() throws Exception {
        LoggerConfig config = baseConfig();
        List<PIDDefinition> pids = new ArrayList<>();
        pids.add(pid("Engine RPM", "01", "0C", "rpm"));

        BaseDriver dropping = new FakeDriver(new HashMap<>()) {
            @Override public boolean isConnected() { return false; }
        };

        new PollingEngine(config, pids, 0L).poll(dropping, null, null);
    }
}
