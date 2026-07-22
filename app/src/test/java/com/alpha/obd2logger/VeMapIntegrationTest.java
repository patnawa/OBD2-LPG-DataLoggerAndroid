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
 * End-to-end proof that VE flows from a live poll cycle into {@link VeMapStore}:
 * FakeDriver batch → PollingEngine → AirDensityMonitor (real physics) →
 * AdvancedAirDensity VE → VeMapStore cell, for both fuels, ending in a ΔVE.
 *
 * <p>Uses realistic values — 2.0 L engine, 2400 rpm, 95 kPa MAP, 38 g/s MAF —
 * for which VE computes to ≈89%, so the assertions also sanity-check the
 * physics chain, not just the plumbing.
 */
@RunWith(RobolectricTestRunner.class)
public class VeMapIntegrationTest {

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

    private static List<PIDDefinition> fullPidSet() {
        List<PIDDefinition> pids = new ArrayList<>();
        pids.add(pid("Engine RPM", "01", "0C", "rpm"));
        pids.add(pid("MAF Air Flow", "01", "10", "g/s"));
        pids.add(pid("Intake Manifold Pressure", "01", "0B", "kPa"));
        pids.add(pid("Intake Air Temp", "01", "0F", "C"));
        pids.add(pid("Barometric Pressure", "01", "33", "kPa"));
        pids.add(pid("Ambient Air Temp", "01", "46", "C"));
        pids.add(pid("Coolant Temp", "01", "05", "C"));
        return pids;
    }

    private static Map<String, Double> warmCruiseBatch(double mafGs) {
        Map<String, Double> values = new HashMap<>();
        values.put("Engine RPM", 2400.0);
        values.put("MAF Air Flow", mafGs);
        values.put("Intake Manifold Pressure", 95.0);
        values.put("Intake Air Temp", 35.0);
        values.put("Barometric Pressure", 100.0);
        values.put("Ambient Air Temp", 28.0);
        values.put("Coolant Temp", 92.0);
        return values;
    }

    private static LoggerConfig veConfig() {
        LoggerConfig config = new LoggerConfig();
        config.showAirDensity = true;          // VE lives in the AeroDensity block
        config.showTurboBoost = false;
        config.dpfMonitorEnabled = false;
        config.showFuelConsumption = false;
        config.fuelMode = FuelMode.PETROL;
        config.engineDisplacementCC = 2000;    // VE needs a real displacement
        config.engineDisplacementUserSet = true;
        config.ratedRPM = 6000;
        return config;
    }

    @Test
    public void pollCycleLearnsPlausibleVeIntoPetrolCell() throws Exception {
        LoggerConfig config = veConfig();
        PollingEngine engine = new PollingEngine(config, fullPidSet(), 0L);
        AirDensityMonitor monitor = new AirDensityMonitor(null);
        VeMapStore veStore = new VeMapStore();
        FakeDriver driver = new FakeDriver(warmCruiseBatch(38.0));

        // 3 identical polls: #1 eaten by debounce, #2 and #3 accepted.
        for (int i = 0; i < 3; i++) {
            engine.poll(driver, monitor, null, veStore);
        }

        String cellKey = MapBinning.cellKey(2400.0, 95.0);
        VeMapStore.VeCell cell = veStore.getPetrolData().get(cellKey);
        assertNotNull("poll cycles must populate the VE cell at the operating point", cell);
        assertEquals(2, cell.getCount());
        // 38 g/s at 2400 rpm / 2.0 L / ~1.07 kg/m³ manifold density → VE ≈ 89%.
        assertTrue("VE must be physically plausible, got " + cell.getVe(),
                cell.getVe() > 80.0 && cell.getVe() < 100.0);
        assertEquals(MapSampleMeta.AXIS_MAP, veStore.getPetrolAxisSource());
    }

    @Test
    public void bothFuelsLearnAndDeltaVeShowsLpgBreathingLoss() throws Exception {
        LoggerConfig config = veConfig();
        PollingEngine engine = new PollingEngine(config, fullPidSet(), 0L);
        AirDensityMonitor monitor = new AirDensityMonitor(null);
        VeMapStore veStore = new VeMapStore();

        // Petrol leg: 5 polls → 4 accepted (first debounced).
        FakeDriver petrolDriver = new FakeDriver(warmCruiseBatch(38.0));
        for (int i = 0; i < 5; i++) {
            engine.poll(petrolDriver, monitor, null, veStore);
        }

        // Fuel switchover, same operating point, less air: gaseous fuel
        // displaces intake charge, so MAF genuinely reads lower on LPG.
        config.fuelMode = FuelMode.LPG;
        FakeDriver lpgDriver = new FakeDriver(warmCruiseBatch(34.0));
        for (int i = 0; i < 5; i++) {
            engine.poll(lpgDriver, monitor, null, veStore);
        }

        VeMapStore.VeSnapshot snap = veStore.snapshot();
        assertTrue(snap.isComparisonAxisCompatible());
        assertEquals(1, snap.getOverlappingCellCount());

        String cellKey = MapBinning.cellKey(2400.0, 95.0);
        assertEquals(cellKey, snap.getMaxLossCell());
        // 38 vs 34 g/s through the same theoretical flow → ~9.4-point VE gap.
        double loss = snap.getAveragePetrolMinusLpg();
        assertTrue("ΔVE must show petrol breathing better than LPG, got " + loss,
                loss > 5.0 && loss < 15.0);

        // The ΔVE CSV must carry the gap at that grid cell.
        String csv = veStore.exportDeltaVeCsv();
        assertTrue(csv.startsWith("MAP kPa \\ RPM"));
        assertTrue("delta grid must contain a positive loss figure", csv.contains("9."));
    }

    @Test
    public void missingMafCyclesDoNotPoisonTransientGate() throws Exception {
        LoggerConfig config = veConfig();
        PollingEngine engine = new PollingEngine(config, fullPidSet(), 0L);
        AirDensityMonitor monitor = new AirDensityMonitor(null);
        VeMapStore veStore = new VeMapStore();

        // Two clean polls establish the cell (second accepted).
        FakeDriver driver = new FakeDriver(warmCruiseBatch(38.0));
        engine.poll(driver, monitor, null, veStore);
        engine.poll(driver, monitor, null, veStore);
        assertEquals(1, veStore.getTotalAccepted());

        // A stretch of cycles with MAF absent (round-robin skipped it) at a
        // DIFFERENT operating point — VE is null, nothing accepted...
        Map<String, Double> noMaf = warmCruiseBatch(38.0);
        noMaf.remove("MAF Air Flow");
        noMaf.put("Engine RPM", 3200.0);
        FakeDriver mafDropout = new FakeDriver(noMaf);
        for (int i = 0; i < 3; i++) {
            engine.poll(mafDropout, monitor, null, veStore);
        }
        assertEquals(1, veStore.getTotalAccepted());

        // ...then MAF returns at that same new operating point. The dropout
        // cycles must have kept tracking the operating point, so this is NOT a
        // transient — only the debounce (new cell) delays it one cycle.
        Map<String, Double> back = warmCruiseBatch(50.0);
        back.put("Engine RPM", 3200.0);
        FakeDriver recovered = new FakeDriver(back);
        engine.poll(recovered, monitor, null, veStore);
        engine.poll(recovered, monitor, null, veStore);

        String newCell = MapBinning.cellKey(3200.0, 95.0);
        assertNotNull("VE learning must resume immediately after a MAF dropout",
                veStore.getPetrolData().get(newCell));
    }

    @Test
    public void coldEngineNeverLearnsVe() throws Exception {
        LoggerConfig config = veConfig();
        PollingEngine engine = new PollingEngine(config, fullPidSet(), 0L);
        AirDensityMonitor monitor = new AirDensityMonitor(null);
        VeMapStore veStore = new VeMapStore();

        Map<String, Double> cold = warmCruiseBatch(38.0);
        cold.put("Coolant Temp", 45.0);
        FakeDriver driver = new FakeDriver(cold);
        for (int i = 0; i < 4; i++) {
            engine.poll(driver, monitor, null, veStore);
        }

        assertEquals(0, veStore.getTotalAccepted());
        assertNull(veStore.getPetrolData().get(MapBinning.cellKey(2400.0, 95.0)));
    }
}
