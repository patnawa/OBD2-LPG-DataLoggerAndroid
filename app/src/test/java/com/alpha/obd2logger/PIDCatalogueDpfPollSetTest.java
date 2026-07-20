package com.alpha.obd2logger;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for the DPF-monitor poll-set gate. */
public class PIDCatalogueDpfPollSetTest {

    @Test
    public void normalProfileOmitsStandardDpfPidsWhenDisabled() {
        List<PIDDefinition> poll = configured(false, false);

        assertFalse(contains(poll, "01_7A"));
        assertFalse(contains(poll, "01_7B"));
    }

    @Test
    public void normalProfileIncludesStandardDpfPidsWhenEnabled() {
        List<PIDDefinition> poll = configured(false, true);

        assertTrue(contains(poll, "01_7A"));
        assertTrue(contains(poll, "01_7B"));
    }

    @Test
    public void lpgOnlyProfileOmitsStandardDpfPidsWhenDisabled() {
        List<PIDDefinition> poll = configured(true, false);

        assertFalse(contains(poll, "01_7A"));
        assertFalse(contains(poll, "01_7B"));
    }

    @Test
    public void lpgOnlyProfileIncludesStandardDpfPidsWhenEnabled() {
        List<PIDDefinition> poll = configured(true, true);

        assertTrue(contains(poll, "01_7A"));
        assertTrue(contains(poll, "01_7B"));
    }

    private static List<PIDDefinition> configured(boolean lpgOnly, boolean includeDpf) {
        return PIDCatalogue.getConfiguredPollSet(
                null, lpgOnly, true, false, includeDpf);
    }

    private static boolean contains(List<PIDDefinition> poll, String key) {
        for (PIDDefinition pid : poll) {
            if (key.equals(pid.key())) return true;
        }
        return false;
    }
}
