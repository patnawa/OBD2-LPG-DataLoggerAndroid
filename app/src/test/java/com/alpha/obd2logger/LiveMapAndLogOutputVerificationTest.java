package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * End-to-end verification of the live fuel-map update path and the log it
 * produces: fake adapter → {@link PollingEngine} → {@link LiveMapStore} +
 * {@link DataWriter} → read the CSV/JSONL back off disk and check it.
 *
 * <p>Exercises the real classes rather than asserting on intermediate objects,
 * because the failure modes that matter here are integration-shaped: a map push
 * that never reaches the store, {@code map_*} columns registered but never
 * populated, or a row whose field count drifts from the header.
 */
@RunWith(RobolectricTestRunner.class)
public class LiveMapAndLogOutputVerificationTest {

    // Values chosen to sit inside every acceptance gate: warm, closed loop,
    // steady cell, measurable trim.
    private static final double RPM = 2000.0;
    private static final double MAP_KPA = 60.0;
    private static final double STFT = 3.0;
    private static final double LTFT = 5.0;
    private static final double ECT_WARM = 90.0;
    private static final double FUEL_STATUS_CLOSED_LOOP = 2.0;   // bit 1 set

    private static final class FakeDriver extends BaseDriver {
        private final Map<String, Double> values;

        FakeDriver(Map<String, Double> values) {
            super(new LoggerConfig());
            this.values = values;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { }
        @Override public boolean isConnected() { return true; }
        @Override public Double queryPid(PIDDefinition p) { return values.get(p.getName()); }

        @Override
        public Map<String, Double> queryPidBatch(List<PIDDefinition> pids) {
            Map<String, Double> out = new HashMap<>();
            for (PIDDefinition p : pids) {
                Double v = values.get(p.getName());
                if (v != null) out.put(p.getName(), v);
            }
            return out;
        }
    }

    private static PIDDefinition pid(String name, String hex, String unit) {
        return new PIDDefinition(name, "01", hex, unit, "A", -1000, 20000, false, 1);
    }

    /** The PID set MapSampleMeta.from() reads, by key. */
    private static List<PIDDefinition> mapPids() {
        return new ArrayList<>(Arrays.asList(
                pid("Engine RPM", "0C", "rpm"),
                pid("Intake Manifold Pressure", "0B", "kPa"),
                pid("Engine Load", "04", "%"),
                pid("Short Term Fuel Trim B1", "06", "%"),
                pid("Long Term Fuel Trim B1", "07", "%"),
                pid("Lambda (B1S1)", "34", ""),
                pid("Commanded Equivalence Ratio", "44", ""),
                pid("Throttle Position", "11", "%"),
                pid("Engine Coolant Temp", "05", "C"),
                pid("Fuel System Status", "03", "")));
    }

    private static Map<String, Double> steadyClosedLoopValues() {
        Map<String, Double> v = new HashMap<>();
        v.put("Engine RPM", RPM);
        v.put("Intake Manifold Pressure", MAP_KPA);
        v.put("Engine Load", 45.0);
        v.put("Short Term Fuel Trim B1", STFT);
        v.put("Long Term Fuel Trim B1", LTFT);
        v.put("Lambda (B1S1)", 1.02);
        v.put("Commanded Equivalence Ratio", 1.0);
        v.put("Throttle Position", 22.0);
        v.put("Engine Coolant Temp", ECT_WARM);
        v.put("Fuel System Status", FUEL_STATUS_CLOSED_LOOP);
        return v;
    }

    private static LoggerConfig mapConfig() {
        LoggerConfig c = new LoggerConfig();
        c.showAirDensity = false;      // needs Context + network
        c.showTurboBoost = false;
        c.showFuelConsumption = false;
        c.dpfMonitorEnabled = false;
        c.fuelMode = FuelMode.PETROL;
        c.vin = "TESTVIN0000000001";
        return c;
    }

    private static String read(File f) throws Exception {
        try (Scanner sc = new Scanner(new FileInputStream(f), "UTF-8")) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        }
    }

    /**
     * The CSV header carries human-readable labels, not the internal sample
     * keys — the JSONL mirror is what uses keys.
     */
    private static int columnIndex(String headerLine, String label) {
        String[] cols = headerLine.split(",", -1);
        for (int i = 0; i < cols.length; i++) {
            if (cols[i].trim().replace("\"", "").equals(label)) return i;
        }
        return -1;
    }

    private static final String COL_RPM_CELL = "Map RPM Cell (rpm)";
    private static final String COL_TRIM_TOTAL = "Map Trim Total STFT+LTFT (%)";
    private static final String COL_CLOSED_LOOP = "Map Closed Loop (1/0)";
    private static final String COL_WARM = "Map Engine Warm (1/0)";
    private static final String COL_ACCEPTED = "Map Sample Accepted (1/0)";

    /** Runs the real pipeline for {@code cycles} polls and returns the writer. */
    private static DataWriter runPipeline(LoggerConfig config, LiveMapStore store,
                                          Map<String, Double> values, int cycles)
            throws Exception {
        List<PIDDefinition> pids = mapPids();
        DataWriter writer = new DataWriter(RuntimeEnvironment.getApplication(),
                "mapverify_" + System.nanoTime(), pids, config.vin);
        PollingEngine engine = new PollingEngine(config, pids, 0L);
        FakeDriver driver = new FakeDriver(values);
        for (int i = 0; i < cycles; i++) {
            writer.writeRecord(engine.poll(driver, null, store).record);
        }
        writer.close();
        return writer;
    }

    // ── Live map update logic ──────────────────────────────────────────────

    /**
     * The debounce requires the same (rpmCell, mapBin) to be seen repeatedly
     * before a cell is committed, so a single transient sample cannot write the
     * map. A steady cruise must eventually be accepted.
     */
    @Test
    public void steadyClosedLoopSampleIsEventuallyAcceptedIntoTheMap() throws Exception {
        LiveMapStore store = new LiveMapStore();
        LoggerConfig config = mapConfig();
        List<PIDDefinition> pids = mapPids();
        PollingEngine engine = new PollingEngine(config, pids, 0L);
        FakeDriver driver = new FakeDriver(steadyClosedLoopValues());

        List<Boolean> accepted = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            accepted.add(engine.poll(driver, null, store).mapPush.accepted);
        }

        assertFalse("the very first sample must not commit a cell — that is what "
                + "the debounce window exists to prevent", accepted.get(0));
        assertTrue("a steady cell must be accepted once the window fills: " + accepted,
                accepted.contains(Boolean.TRUE));

        LiveMapStore.MapSnapshot snap = store.snapshot();
        assertFalse("petrol map must hold the committed cell",
                snap.getPetrolData().isEmpty());
        assertTrue("LPG map must stay empty in petrol mode",
                snap.getLpgData().isEmpty());
    }

    /** An open-loop sample must never reach the map — trims are meaningless there. */
    @Test
    public void openLoopSamplesAreRejected() throws Exception {
        LiveMapStore store = new LiveMapStore();
        Map<String, Double> openLoop = steadyClosedLoopValues();
        openLoop.put("Fuel System Status", 1.0);   // bit 1 clear = open loop

        PollingEngine engine = new PollingEngine(mapConfig(), mapPids(), 0L);
        FakeDriver driver = new FakeDriver(openLoop);
        for (int i = 0; i < 6; i++) {
            PollingEngine.PollOutcome out = engine.poll(driver, null, store);
            assertFalse("open loop must never be accepted", out.mapPush.accepted);
            assertNotNull("a rejection must carry a reason", out.mapPush.reason);
        }
        assertTrue("map must remain empty", store.snapshot().getPetrolData().isEmpty());
    }

    /** A cold engine must be rejected — trims have not stabilised yet. */
    @Test
    public void coldEngineSamplesAreRejected() throws Exception {
        LiveMapStore store = new LiveMapStore();
        Map<String, Double> cold = steadyClosedLoopValues();
        cold.put("Engine Coolant Temp", 40.0);

        PollingEngine engine = new PollingEngine(mapConfig(), mapPids(), 0L);
        FakeDriver driver = new FakeDriver(cold);
        for (int i = 0; i < 6; i++) {
            assertFalse("cold engine must never be accepted",
                    engine.poll(driver, null, store).mapPush.accepted);
        }
        assertTrue(store.snapshot().getPetrolData().isEmpty());
    }

    /** Petrol and LPG maps must stay separate so a baseline survives a run. */
    @Test
    public void lpgSamplesLandInTheLpgMapOnly() throws Exception {
        LiveMapStore store = new LiveMapStore();
        LoggerConfig config = mapConfig();
        config.fuelMode = FuelMode.LPG;

        PollingEngine engine = new PollingEngine(config, mapPids(), 0L);
        FakeDriver driver = new FakeDriver(steadyClosedLoopValues());
        for (int i = 0; i < 6; i++) engine.poll(driver, null, store);

        LiveMapStore.MapSnapshot snap = store.snapshot();
        assertFalse("LPG map must hold the cell", snap.getLpgData().isEmpty());
        assertTrue("petrol map must stay untouched", snap.getPetrolData().isEmpty());
    }

    // ── Output log ─────────────────────────────────────────────────────────

    /**
     * DataRecord holds its sample list by reference, so the map columns appended
     * after the record is constructed must still reach the writer. If that ever
     * became a defensive copy, every map_* column would silently write empty.
     */
    @Test
    public void mapColumnsArePopulatedInTheCsv() throws Exception {
        LiveMapStore store = new LiveMapStore();
        DataWriter writer = runPipeline(mapConfig(), store, steadyClosedLoopValues(), 6);

        String[] lines = read(writer.getCsvFile()).split("\n");
        assertTrue("expected a header plus data rows", lines.length > 6);
        String header = lines[0];

        for (String column : new String[] {
                COL_RPM_CELL, "Map Axis Value", COL_TRIM_TOTAL,
                COL_CLOSED_LOOP, COL_WARM, "Map Gate Eligible (1/0)",
                COL_ACCEPTED, "Map Reject Code" }) {
            assertTrue("CSV header missing " + column, header.contains(column));
        }

        int rpmCellIdx = columnIndex(header, COL_RPM_CELL);
        int trimIdx = columnIndex(header, COL_TRIM_TOTAL);
        int closedIdx = columnIndex(header, COL_CLOSED_LOOP);
        int warmIdx = columnIndex(header, COL_WARM);
        int acceptedIdx = columnIndex(header, COL_ACCEPTED);
        assertTrue("map columns must be locatable in the header",
                rpmCellIdx > 0 && trimIdx > 0 && closedIdx > 0 && warmIdx > 0 && acceptedIdx > 0);

        String[] lastRow = lines[lines.length - 1].split(",", -1);
        assertEquals("STFT+LTFT must be logged as the total trim",
                STFT + LTFT, Double.parseDouble(lastRow[trimIdx]), 0.001);
        assertEquals("closed loop must be logged as 1", 1.0,
                Double.parseDouble(lastRow[closedIdx]), 0.001);
        assertEquals("warm must be logged as 1", 1.0,
                Double.parseDouble(lastRow[warmIdx]), 0.001);
        assertFalse("rpm cell must not be blank on a gated sample",
                lastRow[rpmCellIdx].trim().isEmpty());
    }

    /**
     * Schema integrity: the column set is fixed at construction, so every row
     * must have exactly as many fields as the header. A drift here silently
     * shifts every downstream value by one column.
     */
    @Test
    public void everyCsvRowMatchesTheHeaderWidth() throws Exception {
        LiveMapStore store = new LiveMapStore();
        DataWriter writer = runPipeline(mapConfig(), store, steadyClosedLoopValues(), 8);

        String[] lines = read(writer.getCsvFile()).split("\n");
        int expected = lines[0].split(",", -1).length;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            assertEquals("row " + i + " field count differs from header",
                    expected, lines[i].split(",", -1).length);
        }
    }

    /** The accept/reject decision in the log must match what the store did. */
    @Test
    public void loggedAcceptFlagMatchesTheStoreDecision() throws Exception {
        LiveMapStore store = new LiveMapStore();
        LoggerConfig config = mapConfig();
        List<PIDDefinition> pids = mapPids();
        DataWriter writer = new DataWriter(RuntimeEnvironment.getApplication(),
                "mapaccept_" + System.nanoTime(), pids, config.vin);
        PollingEngine engine = new PollingEngine(config, pids, 0L);
        FakeDriver driver = new FakeDriver(steadyClosedLoopValues());

        List<Boolean> decisions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            PollingEngine.PollOutcome out = engine.poll(driver, null, store);
            decisions.add(out.mapPush.accepted);
            writer.writeRecord(out.record);
        }
        writer.close();

        String[] lines = read(writer.getCsvFile()).split("\n");
        int acceptedIdx = columnIndex(lines[0], COL_ACCEPTED);
        for (int i = 0; i < decisions.size(); i++) {
            double logged = Double.parseDouble(lines[i + 1].split(",", -1)[acceptedIdx]);
            assertEquals("row " + i + " map_accepted must match the store's decision",
                    decisions.get(i) ? 1.0 : 0.0, logged, 0.001);
        }

        int acceptedCount = 0;
        for (Boolean b : decisions) if (b) acceptedCount++;
        int hits = 0;
        for (LiveMapStore.TrimData cell : store.snapshot().getPetrolData().values()) {
            hits += cell.getHitCount();
        }
        assertEquals("store hit count must equal the number of accepted samples — "
                + "a mismatch means double-counting or a lost push", acceptedCount, hits);
    }

    /** Timing columns added for cadence auditing must actually be written. */
    @Test
    public void pollTimingColumnsAreWritten() throws Exception {
        LiveMapStore store = new LiveMapStore();
        DataWriter writer = runPipeline(mapConfig(), store, steadyClosedLoopValues(), 4);
        String header = read(writer.getCsvFile()).split("\n")[0];

        assertTrue(header.contains("Poll Acquisition Span (ms)"));
        assertTrue(header.contains("Poll Jitter"));
        assertTrue(header.contains("Poll Overrun"));

        // A comma inside any label would shift every later column for consumers
        // that split on "," instead of parsing quoted CSV.
        for (String col : header.split(",", -1)) {
            assertFalse("column label must not contain a comma: " + col,
                    col.chars().filter(c -> c == '"').count() == 1);
        }
    }

    /** The JSONL mirror must stay parseable and carry per-PID quality flags. */
    @Test
    public void jsonlRowsParseAndCarryQualityMetadata() throws Exception {
        LiveMapStore store = new LiveMapStore();
        DataWriter writer = runPipeline(mapConfig(), store, steadyClosedLoopValues(), 5);

        String[] lines = read(writer.getJsonlFile()).split("\n");
        int parsed = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            JSONObject row = new JSONObject(line);
            assertTrue("row must carry map_accepted", row.has("map_accepted"));
            assertTrue("row must carry a quality block", row.has("_quality"));
            parsed++;
        }
        assertEquals("every written record must appear in the JSONL", 5, parsed);
    }
}
