package com.alpha.obd2logger;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Robolectric-backed test for DataWriter (v3.5.2, BUG#2 fix):
 * derived sensors must be persisted to the CSV under their own unique columns
 * (keyed by pidKey) instead of being dropped or overwritten.
 */
@RunWith(RobolectricTestRunner.class)
public class DataWriterTest {

    @Test
    public void keyedColumns_persistDerivedSensors() throws Exception {
        PIDDefinition rpm = new PIDDefinition("Engine RPM", "01", "0C", "rpm", "(A*256+B)/4", 0, 16383, true, 2, true);
        List<PIDDefinition> pids = Collections.singletonList(rpm);

        DataWriter w = new DataWriter(RuntimeEnvironment.getApplication(),
                "unittest_" + System.nanoTime(), pids, "TESTVIN");

        SensorSample rpmSample = new SensorSample("01_0C", "Engine RPM", 2500.0, "rpm", "ok");
        SensorSample fuelKmL = new SensorSample("derived_fuel_kmL", "Fuel Economy", 12.5, "km/L", "ok");
        SensorSample fuelL100 = new SensorSample("derived_fuel_l100", "Fuel Economy", 8.0, "L/100km", "ok");
        SensorSample boostKpa = new SensorSample("derived_boost_kpa", "Turbo Boost", 120.0, "kPa", "ok");
        SensorSample boostPsi = new SensorSample("derived_boost_psi", "Turbo Boost", 17.4, "psi", "ok");

        DataRecord rec = new DataRecord("2026-07-09T10:00:00.000", 1.0, "petrol", "Toyota", "TESTVIN",
                Arrays.asList(rpmSample, fuelKmL, fuelL100, boostKpa, boostPsi));
        w.writeRecord(rec);
        w.close();

        String csv = readFile(w.getCsvFile());
        assertTrue("CSV has km/L column", csv.contains("Fuel Economy (km/L)"));
        assertTrue("CSV has L/100km column", csv.contains("Fuel Economy (L/100km)"));
        assertTrue("CSV has boost kPa column", csv.contains("Turbo Boost (kPa)"));
        assertTrue("CSV has boost psi column", csv.contains("Turbo Boost (psi)"));

        // The values must actually appear in the data row (no longer dropped).
        String dataRow = csv.split("\n")[1];
        assertTrue("km/L value written", dataRow.contains("12.5"));
        assertTrue("L/100km value written", dataRow.contains("8.0"));
        assertTrue("boost kPa value written", dataRow.contains("120.0"));
        assertTrue("boost psi value written", dataRow.contains("17.4"));
        // Distinct columns: header must carry all four derived labels.
        assertFalse("four distinct derived columns present",
                countOccurrences(csv.split("\n")[0], ',') < 6 + 4);
    }

    @Test
    public void summaryV2_usesElapsedTimeCoverageSourcesAndSessionFilename() throws Exception {
        PIDDefinition speed = new PIDDefinition("Vehicle Speed", "01", "0D", "km/h",
                "A", 0, 255, true, 1, true);
        PIDDefinition map = new PIDDefinition("Intake Manifold Pressure", "01", "0B", "kPa",
                "A", 0, 255, true, 1, true);
        PIDDefinition fuelRate = new PIDDefinition("Engine Fuel Rate", "01", "5E", "L/h",
                "(A*256+B)/20", 0, 3276.75, true, 2, false);
        String session = "summary_v2_" + System.nanoTime();
        LoggerConfig config = new LoggerConfig();
        config.transportMode = TransportMode.SIM;
        config.sampleIntervalMs = 500;
        DataWriter writer = new DataWriter(RuntimeEnvironment.getApplication(), session,
                Arrays.asList(speed, map, fuelRate), "TESTVIN", config, "Test Adapter");

        writer.writeRecord(record(0.0, 36.0, 50.0, "measured", 3.0));
        writer.writeRecord(record(2.0, 36.0, null, "err", 3.0));
        writer.writeRecord(record(5.0, 36.0, 60.0, "synth", 3.0));
        writer.close();

        File summaryFile = writer.getSummaryFile();
        assertNotNull(summaryFile);
        assertEquals(session + "_summary.json", summaryFile.getName());
        JSONObject root = new JSONObject(readFile(summaryFile));
        assertEquals(2, root.getInt("schema_version"));
        assertTrue(root.getBoolean("complete"));
        assertEquals(3, root.getInt("records"));
        assertEquals(5.0, root.getDouble("duration_s"), 0.0001);

        JSONObject trip = root.getJSONObject("trip");
        assertEquals("elapsed_time_trapezoidal", trip.getString("method"));
        assertEquals(0.05, trip.getDouble("distance_km"), 0.0001);
        assertEquals(0.0042, trip.getDouble("fuel_liters"), 0.0001);
        assertEquals(5.0, trip.getDouble("fuel_rate_pid_seconds"), 0.0001);

        JSONObject mapStats = root.getJSONObject("columns").getJSONObject("01_0B");
        assertEquals(2, mapStats.getInt("count"));
        assertEquals(1, mapStats.getInt("null_count"));
        assertEquals(66.67, mapStats.getDouble("coverage_pct"), 0.001);
        assertEquals(1, mapStats.getJSONObject("status_counts").getInt("synth"));

        JSONObject sourceStats = root.getJSONObject("columns").getJSONObject("map_value_source");
        assertEquals(3, sourceStats.getInt("count"));
        assertEquals(1, sourceStats.getJSONObject("status_counts").getInt("measured"));
        assertEquals(1, sourceStats.getJSONObject("status_counts").getInt("synthesized"));
        assertEquals(1, sourceStats.getJSONObject("status_counts").getInt("unavailable"));

        String jsonl = readFile(writer.getJsonlFile());
        assertTrue("JSONL preserves synthesized source", jsonl.contains("\"01_0B\":\"synth\""));
        assertTrue("JSONL has quality metadata", jsonl.contains("\"_quality\""));
    }

    @Test
    public void summaryCheckpoint_existsBeforeGracefulClose() throws Exception {
        PIDDefinition rpm = new PIDDefinition("Engine RPM", "01", "0C", "rpm",
                "(A*256+B)/4", 0, 16383, true, 2, true);
        String session = "checkpoint_" + System.nanoTime();
        DataWriter writer = new DataWriter(RuntimeEnvironment.getApplication(), session,
                Collections.singletonList(rpm), "TESTVIN");
        for (int i = 1; i <= 10; i++) {
            SensorSample sample = new SensorSample("01_0C", "Engine RPM", 1000.0 + i,
                    "rpm", "ok");
            writer.writeRecord(new DataRecord("2026-07-13T10:00:00.000", i,
                    "petrol", "Toyota", "TESTVIN", Collections.singletonList(sample)));
        }

        JSONObject checkpoint = new JSONObject(readFile(writer.getSummaryFile()));
        assertFalse(checkpoint.getBoolean("complete"));
        assertEquals(10, checkpoint.getInt("records"));
        writer.close();
        JSONObject completed = new JSONObject(readFile(writer.getSummaryFile()));
        assertTrue(completed.getBoolean("complete"));
    }

    private static DataRecord record(double elapsed, Double speed, Double map,
                                     String mapStatus, Double fuelRate) {
        return new DataRecord("2026-07-13T10:00:00.000", elapsed, "lpg/cng",
                "Toyota", "TESTVIN", Arrays.asList(
                new SensorSample("01_0D", "Vehicle Speed", speed, "km/h", "ok"),
                new SensorSample("01_0B", "Intake Manifold Pressure", map, "kPa", mapStatus),
                new SensorSample("01_5E", "Engine Fuel Rate", fuelRate, "L/h", "ok")));
    }

    private static int countOccurrences(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    private static String readFile(File f) throws Exception {
        try (Scanner sc = new Scanner(new FileInputStream(f), "UTF-8")) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        }
    }
}
