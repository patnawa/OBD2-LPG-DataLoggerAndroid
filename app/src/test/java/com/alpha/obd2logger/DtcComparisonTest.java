package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DtcComparisonTest {

    @Test
    public void testNewCodesDetected() {
        List<DtcCode> prev = Arrays.asList(
            new DtcCode("P0300", "Random Misfire"),
            new DtcCode("P0171", "Lean Bank 1")
        );
        List<DtcCode> curr = Arrays.asList(
            new DtcCode("P0300", "Random Misfire"),
            new DtcCode("P0171", "Lean Bank 1"),
            new DtcCode("P0420", "Catalyst")
        );

        DtcComparison comp = DtcComparison.compare(prev, new ArrayList<>(), curr, new ArrayList<>());
        assertTrue(comp.hasPreviousScan());
        assertTrue(comp.hasChanges());
        assertEquals(1, comp.getNewCodes().size());
        assertEquals("P0420", comp.getNewCodes().get(0).getCode());
        assertEquals(0, comp.getClearedCodes().size());
        assertEquals(2, comp.getPersistingCodes().size());
        assertTrue(comp.getSummary().contains("1 NEW"));
    }

    @Test
    public void testClearedCodesDetected() {
        List<DtcCode> prev = Arrays.asList(
            new DtcCode("P0300", "Random Misfire"),
            new DtcCode("P0171", "Lean Bank 1")
        );
        List<DtcCode> curr = Arrays.asList(
            new DtcCode("P0171", "Lean Bank 1")
        );

        DtcComparison comp = DtcComparison.compare(prev, new ArrayList<>(), curr, new ArrayList<>());
        assertTrue(comp.hasChanges());
        assertEquals(0, comp.getNewCodes().size());
        assertEquals(1, comp.getClearedCodes().size());
        assertEquals("P0300", comp.getClearedCodes().get(0).getCode());
        assertTrue(comp.getSummary().contains("1 CLEARED"));
    }

    @Test
    public void testNoChanges() {
        List<DtcCode> prev = Arrays.asList(new DtcCode("P0300", "Random Misfire"));
        List<DtcCode> curr = Arrays.asList(new DtcCode("P0300", "Random Misfire"));

        DtcComparison comp = DtcComparison.compare(prev, new ArrayList<>(), curr, new ArrayList<>());
        assertFalse(comp.hasChanges());
        assertEquals(0, comp.getNewCodes().size());
        assertEquals(0, comp.getClearedCodes().size());
        assertEquals(1, comp.getPersistingCodes().size());
        assertTrue(comp.getSummary().contains("persisting"));
    }

    @Test
    public void testNoPreviousScan() {
        DtcComparison comp = DtcComparison.compare(null, null,
            Arrays.asList(new DtcCode("P0300", "Misfire")), new ArrayList<>());
        assertFalse(comp.hasPreviousScan());
        // When no previous scan, current codes appear as "new" but the UI
        // uses hasPreviousScan() to decide whether to show comparison
        assertTrue(comp.hasChanges()); // current codes are "new" vs empty previous
        assertEquals(1, comp.getNewCodes().size());
    }

    @Test
    public void testMixedNewAndCleared() {
        List<DtcCode> prev = Arrays.asList(
            new DtcCode("P0300", "Random Misfire"),
            new DtcCode("P0171", "Lean Bank 1")
        );
        List<DtcCode> curr = Arrays.asList(
            new DtcCode("P0300", "Random Misfire"),
            new DtcCode("P0420", "Catalyst")
        );

        DtcComparison comp = DtcComparison.compare(prev, new ArrayList<>(), curr, new ArrayList<>());
        assertTrue(comp.hasChanges());
        assertEquals(1, comp.getNewCodes().size());
        assertEquals("P0420", comp.getNewCodes().get(0).getCode());
        assertEquals(1, comp.getClearedCodes().size());
        assertEquals("P0171", comp.getClearedCodes().get(0).getCode());
        assertEquals(1, comp.getPersistingCodes().size());
        assertTrue(comp.getSummary().contains("1 NEW"));
        assertTrue(comp.getSummary().contains("1 CLEARED"));
    }
}
