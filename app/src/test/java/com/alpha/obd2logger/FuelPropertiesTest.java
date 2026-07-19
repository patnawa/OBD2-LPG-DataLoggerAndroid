package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * FuelMode → property-set mapping and by-code lookup (log replay path).
 *
 * The AFR/density constants feed fuel-consumption and air-density math, so a
 * wrong mapping (e.g. NGV resolving to petrol) shifts every derived number.
 */
public class FuelPropertiesTest {

    // ── get(FuelMode) mapping ─────────────────────────────────────────

    @Test
    public void everyFuelMode_mapsToItsOwnProps() {
        assertSame(FuelProperties.LPG, FuelProperties.get(FuelMode.LPG));
        assertSame(FuelProperties.NGV, FuelProperties.get(FuelMode.NGV));
        assertSame(FuelProperties.E20, FuelProperties.get(FuelMode.E20));
        assertSame(FuelProperties.E85, FuelProperties.get(FuelMode.E85));
        assertSame(FuelProperties.DIESEL_B7, FuelProperties.get(FuelMode.DIESEL));
        assertSame(FuelProperties.DIESEL_B20, FuelProperties.get(FuelMode.B20));
        assertSame(FuelProperties.PETROL_91, FuelProperties.get(FuelMode.PETROL_91));
        assertSame(FuelProperties.PETROL_95, FuelProperties.get(FuelMode.PETROL));
    }

    @Test
    public void nullMode_defaultsToPetrol95() {
        assertSame(FuelProperties.PETROL_95, FuelProperties.get(null));
    }

    // ── getByCode (log replay) ────────────────────────────────────────

    @Test
    public void byCode_roundTripsTheShortCodes() {
        assertSame(FuelProperties.LPG, FuelProperties.getByCode("LPG"));
        assertSame(FuelProperties.NGV, FuelProperties.getByCode("NGV"));
        assertSame(FuelProperties.E20, FuelProperties.getByCode("E20"));
        assertSame(FuelProperties.E85, FuelProperties.getByCode("E85"));
        assertSame(FuelProperties.DIESEL_B7, FuelProperties.getByCode("D7"));
        assertSame(FuelProperties.DIESEL_B20, FuelProperties.getByCode("B20"));
        assertSame(FuelProperties.PETROL_91, FuelProperties.getByCode("G91"));
        assertSame(FuelProperties.PETROL_95, FuelProperties.getByCode("G95"));
    }

    @Test
    public void byCode_isCaseInsensitive() {
        assertSame(FuelProperties.LPG, FuelProperties.getByCode("lpg"));
        assertSame(FuelProperties.DIESEL_B7, FuelProperties.getByCode("d7"));
    }

    @Test
    public void byCode_unknownOrNull_defaultsToPetrol95() {
        assertSame(FuelProperties.PETROL_95, FuelProperties.getByCode(null));
        assertSame(FuelProperties.PETROL_95, FuelProperties.getByCode("JET-A1"));
        assertSame(FuelProperties.PETROL_95, FuelProperties.getByCode("PETROL"));
    }

    @Test
    public void propsShortCode_matchesTheLookupCode() {
        // Ensures DataWriter's shortCode output stays replay-parseable
        for (FuelMode mode : FuelMode.values()) {
            FuelProperties.Props p = FuelProperties.get(mode);
            assertSame("shortCode " + p.shortCode + " must round-trip",
                    p, FuelProperties.getByCode(p.shortCode));
        }
    }

    // ── Physical sanity ───────────────────────────────────────────────

    @Test
    public void gaseousFlag_matchesFuelModeIsGaseous() {
        for (FuelMode mode : FuelMode.values()) {
            assertEquals("isGaseous mismatch for " + mode,
                    mode.isGaseous(), FuelProperties.get(mode).isGaseous);
        }
    }

    @Test
    public void dieselHasNoFuelTrim_othersDo() {
        assertFalse(FuelProperties.DIESEL_B7.hasFuelTrim);
        assertFalse(FuelProperties.DIESEL_B20.hasFuelTrim);
        assertTrue(FuelProperties.LPG.hasFuelTrim);
        assertTrue(FuelProperties.NGV.hasFuelTrim);
        assertTrue(FuelProperties.PETROL_95.hasFuelTrim);
    }

    @Test
    public void stoichAfr_orderingIsPhysicallyPlausible() {
        // More ethanol → lower stoich AFR; gaseous fuels sit above petrol
        assertTrue(FuelProperties.E85.stoichAFR < FuelProperties.E20.stoichAFR);
        assertTrue(FuelProperties.E20.stoichAFR < FuelProperties.PETROL_95.stoichAFR);
        assertTrue(FuelProperties.LPG.stoichAFR > FuelProperties.PETROL_95.stoichAFR);
        assertTrue(FuelProperties.NGV.stoichAFR > FuelProperties.LPG.stoichAFR);
    }
}
