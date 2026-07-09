package com.alpha.obd2logger;

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
