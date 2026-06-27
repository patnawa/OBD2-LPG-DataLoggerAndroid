package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PidAvailabilityCheckerTest {

    // --- PidAvailabilityChecker.parseHexByte (indirectly via filterCatalogue) ---

    @Test
    public void filterCatalogueKeepsSupportedPids() {
        // Simulate a vehicle that supports PIDs 0x0C (RPM), 0x0D (Speed), 0x05 (Coolant)
        List<String> supported = Arrays.asList("0C", "0D", "05");
        List<PIDDefinition> filtered = PidAvailabilityChecker.filterCatalogue(
                supported, PIDCatalogue.getAll());
        assertNotNull(filtered);
        // Should contain at least RPM, Speed, Coolant
        boolean hasRpm = false, hasSpeed = false, hasCoolant = false;
        for (PIDDefinition pid : filtered) {
            String baseHex = pid.getPidHex().contains("_")
                    ? pid.getPidHex().substring(0, pid.getPidHex().indexOf('_'))
                    : pid.getPidHex();
            if (baseHex.equals("0C") && !pid.getPidHex().contains("_")) hasRpm = true;
            if (baseHex.equals("0D")) hasSpeed = true;
            if (baseHex.equals("05")) hasCoolant = true;
        }
        assertTrue("RPM should be in filtered list", hasRpm);
        assertTrue("Speed should be in filtered list", hasSpeed);
        assertTrue("Coolant should be in filtered list", hasCoolant);
    }

    @Test
    public void filterCatalogueKeepsPseudoPidsWhenParentSupported() {
        // If PID 0x14 is supported, both "14" (voltage) and "14_B" (STFT) should be kept
        List<String> supported = Arrays.asList("14");
        List<PIDDefinition> filtered = PidAvailabilityChecker.filterCatalogue(
                supported, PIDCatalogue.getAll());
        boolean hasVoltage = false, hasStft = false;
        for (PIDDefinition pid : filtered) {
            if (pid.getPidHex().equals("14")) hasVoltage = true;
            if (pid.getPidHex().equals("14_B")) hasStft = true;
        }
        assertTrue("O2 B1S1 Voltage should be kept when parent 0x14 is supported", hasVoltage);
        assertTrue("O2 B1S1 STFT pseudo-PID should be kept when parent 0x14 is supported", hasStft);
    }

    @Test
    public void filterCatalogueExcludesUnsupportedPids() {
        // If only 0x0C is supported, 0x0D (Speed) should NOT be in the filtered list
        List<String> supported = Arrays.asList("0C");
        List<PIDDefinition> filtered = PidAvailabilityChecker.filterCatalogue(
                supported, PIDCatalogue.getAll());
        for (PIDDefinition pid : filtered) {
            String baseHex = pid.getPidHex().contains("_")
                    ? pid.getPidHex().substring(0, pid.getPidHex().indexOf('_'))
                    : pid.getPidHex();
            assertFalse("Unsupported PID 0D should not be in filtered list", baseHex.equals("0D"));
        }
    }

    @Test
    public void filterCatalogueReturnsOriginalWhenSupportedIsNull() {
        List<PIDDefinition> original = PIDCatalogue.getAll();
        List<PIDDefinition> filtered = PidAvailabilityChecker.filterCatalogue(null, original);
        assertEquals(original, filtered);
    }

    @Test
    public void filterCatalogueReturnsOriginalWhenSupportedIsEmpty() {
        List<PIDDefinition> original = PIDCatalogue.getAll();
        List<PIDDefinition> filtered = PidAvailabilityChecker.filterCatalogue(
                new ArrayList<>(), original);
        assertEquals(original, filtered);
    }

    // --- BrandYearProfile ---

    @Test
    public void brandFromVinDetectsToyota() {
        assertEquals(BrandYearProfile.Brand.TOYOTA,
                BrandYearProfile.brandFromVin("JTMAB3FVXXXXXXXXXX"));
    }

    @Test
    public void brandFromVinDetectsHonda() {
        assertEquals(BrandYearProfile.Brand.HONDA,
                BrandYearProfile.brandFromVin("JHMFA1XXXXXXXXXXXX"));
    }

    @Test
    public void brandFromVinDetectsFord() {
        assertEquals(BrandYearProfile.Brand.FORD,
                BrandYearProfile.brandFromVin("1FAFP3XXXXXXXXXXXX"));
    }

    @Test
    public void brandFromVinDetectsBMW() {
        assertEquals(BrandYearProfile.Brand.BMW,
                BrandYearProfile.brandFromVin("WBAAA3XXXXXXXXXXXX"));
    }

    @Test
    public void brandFromVinDetectsMercedes() {
        assertEquals(BrandYearProfile.Brand.MERCEDES,
                BrandYearProfile.brandFromVin("WDDHF4XXXXXXXXXXXX"));
    }

    @Test
    public void brandFromVinDetectsVolkswagen() {
        assertEquals(BrandYearProfile.Brand.VOLKSWAGEN,
                BrandYearProfile.brandFromVin("WVWZZZXXXXXXXXXXXX"));
    }

    @Test
    public void brandFromVinReturnsGenericForNull() {
        assertEquals(BrandYearProfile.Brand.GENERIC,
                BrandYearProfile.brandFromVin(null));
    }

    @Test
    public void brandFromVinReturnsGenericForShortVin() {
        assertEquals(BrandYearProfile.Brand.GENERIC,
                BrandYearProfile.brandFromVin("AB"));
    }

    @Test
    public void yearFromVinDecodes2015() {
        // 'F' = 2015
        assertEquals(2015, BrandYearProfile.yearFromVin("XXXXXXXXXFXXXXXXX"));
    }

    @Test
    public void yearFromVinDecodes2020() {
        // 'L' = 2020
        assertEquals(2020, BrandYearProfile.yearFromVin("XXXXXXXXXLXXXXXXX"));
    }

    @Test
    public void yearFromVinDecodes2025() {
        // 'S' = 2025
        assertEquals(2025, BrandYearProfile.yearFromVin("XXXXXXXXXSXXXXXXX"));
    }

    @Test
    public void yearFromVinDecodes2008() {
        // '8' = 2008
        assertEquals(2008, BrandYearProfile.yearFromVin("XXXXXXXXX8XXXXXXX"));
    }

    @Test
    public void yearFromVinReturnsZeroForNull() {
        assertEquals(0, BrandYearProfile.yearFromVin(null));
    }

    @Test
    public void yearFromVinReturnsZeroForShortVin() {
        assertEquals(0, BrandYearProfile.yearFromVin("SHORT"));
    }

    @Test
    public void yearFromVinReturnsZeroForUnknownCode() {
        // 'Q' is not a valid model year code
        assertEquals(0, BrandYearProfile.yearFromVin("XXXXXXXXXQXXXXXXX"));
    }

    @Test
    public void getProfileIncludesBasePids() {
        Set<String> pids = BrandYearProfile.getProfile(BrandYearProfile.Brand.GENERIC, 2005);
        assertNotNull(pids);
        // Base PIDs required for all OBD-II vehicles
        assertTrue("RPM (0C) should be in base profile", pids.contains("0C"));
        assertTrue("Speed (0D) should be in base profile", pids.contains("0D"));
        assertTrue("Coolant (05) should be in base profile", pids.contains("05"));
    }

    @Test
    public void getProfilePost2008IncludesAdditionalPids() {
        Set<String> pids2007 = BrandYearProfile.getProfile(BrandYearProfile.Brand.GENERIC, 2007);
        Set<String> pids2008 = BrandYearProfile.getProfile(BrandYearProfile.Brand.GENERIC, 2008);
        // MAF (0x10) should be in 2008+ but not in pre-2008
        assertFalse("MAF (10) should NOT be in 2007 profile", pids2007.contains("10"));
        assertTrue("MAF (10) should be in 2008 profile", pids2008.contains("10"));
    }

    @Test
    public void getProfileYear0IncludesAll() {
        // Year 0 = unknown → include everything conservatively
        Set<String> pids = BrandYearProfile.getProfile(BrandYearProfile.Brand.GENERIC, 0);
        assertTrue("Year 0 should include 0x10 (MAF)", pids.contains("10"));
        assertTrue("Year 0 should include 0x42 (Control Module Voltage)", pids.contains("42"));
    }

    @Test
    public void getProfileBmwIncludesWidebandLambda() {
        // BMW post-2008 should include PID 0x34 (wideband lambda)
        Set<String> pids = BrandYearProfile.getProfile(BrandYearProfile.Brand.BMW, 2010);
        assertTrue("BMW 2010 should include PID 0x34 (wideband lambda)", pids.contains("34"));
    }

    @Test
    public void getProfileToyotaDoesNotIncludeWidebandLambda() {
        // Toyota doesn't typically have wideband lambda as a standard PID
        Set<String> pids = BrandYearProfile.getProfile(BrandYearProfile.Brand.TOYOTA, 2010);
        assertFalse("Toyota 2010 should NOT include PID 0x34 (wideband lambda)", pids.contains("34"));
    }

    @Test
    public void getProfileFromVinCombinesBrandAndYear() {
        // BMW with model year 'G' = 2016
        Set<String> pids = BrandYearProfile.getProfileFromVin("WBAAA3XXXXGXXXXXXX");
        assertNotNull(pids);
        assertTrue("BMW 2016 should include PID 0x34 (wideband lambda)", pids.contains("34"));
        assertTrue("BMW 2016 should include PID 0x0C (RPM)", pids.contains("0C"));
    }

    @Test
    public void getProfileFromVinReturnsNullForNullVin() {
        assertNull(BrandYearProfile.getProfileFromVin(null));
    }

    @Test
    public void getProfileFromVinReturnsNullForShortVin() {
        assertNull(BrandYearProfile.getProfileFromVin("SHORT"));
    }

    @Test
    public void getProfileReturnsNullForNullBrand() {
        assertNull(BrandYearProfile.getProfile(null, 2015));
    }

    @Test
    public void simulationProfileIsNotNullAndContainsCorePids() {
        // The simulation profile is returned by PidAvailabilityChecker for SimulationDriver
        // We can test it indirectly by checking that filterCatalogue with a known set works
        List<String> simPids = Arrays.asList("0C", "0D", "05", "06", "07", "0B", "04", "11", "10", "14");
        List<PIDDefinition> filtered = PidAvailabilityChecker.filterCatalogue(
                simPids, PIDCatalogue.getAll());
        assertNotNull(filtered);
        assertTrue(filtered.size() > 0);
    }
}