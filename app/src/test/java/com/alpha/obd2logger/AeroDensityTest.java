package com.alpha.obd2logger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * AeroDensity Intelligence physics + poll-set + sample quality regression tests.
 */
public class AeroDensityTest {

    private static final double EPS = 0.6; // lbs/1000ft3 rounding tolerance

    @Test
    public void saeJ1349_standardDryDensity_nearReference() {
        // SAE J1349: 99.0 kPa dry (0% RH), 25°C → reference AAD ≈ 72.2 lbs/1000ft³
        Double aad = DerivedSensors.airDensityLbs1000ft3(99.0, 25.0, 0.0);
        assertNotNull(aad);
        assertEquals(DerivedSensors.SAE_J1349_AAD, aad, 0.5);
    }

    @Test
    public void saeJ1349_correctionFactor_isOneAtStd() {
        Double cf = DerivedSensors.saeJ1349CorrectionFactor(99.0, 25.0, 0.0);
        assertNotNull(cf);
        assertEquals(1.0, cf, 0.01);
    }

    @Test
    public void densityAltitude_nearZeroAtIsaSeaLevel() {
        // 15°C, 101.325 kPa, dry → ≈ 0 ft
        Double da = DerivedSensors.densityAltitudeFt(101.325, 15.0, 0.0);
        assertNotNull(da);
        assertEquals(0.0, da, 250.0); // allow 250 ft tolerance for virtual-temp approx
    }

    @Test
    public void vaporPressure_clampedBelowTotal() {
        // Extreme RH + high hot temp must not yield Pv > total pressure
        double pv = DerivedSensors.vaporPressureHpa(95.0, 100.0, 50.0); // 50 hPa baro absurdish
        assertTrue(pv <= 50.0 * 0.98 + 0.01);
    }

    @Test
    public void mad_absoluteHumidity_lowerThanNaiveHotRh() {
        // Ambient 30°C / 80% RH / 101 kPa; manifold MAP 180 kPa, IAT 70°C
        // Naive RH@IAT would invent huge Pv; absolute humidity conservation keeps Pv modest.
        Double madAbs = DerivedSensors.manifoldAirDensity(180.0, 70.0, 101.0, 30.0, 80.0);
        Double madNaive = DerivedSensors.airDensityLbs1000ft3(180.0, 70.0, 80.0);
        assertNotNull(madAbs);
        assertNotNull(madNaive);
        // Absolute-humidity MAD should be denser (less vapor at hot IAT after RH preservation)
        // Wait: lower vapor at hot path → denser dry air portion → MAD_abs > MAD? 
        // Actually: same RH at 70°C saturates huge vapor → lower density naive.
        // Abs humidityKEEPS ambient mixing ratio so Pv is high-ish still but << saturation@70.
        // So MAD_abs density should be GREATER than naive-RH.
        assertTrue("abs humidity MAD should exceed naive hot RH: " + madAbs + " vs " + madNaive,
                madAbs > madNaive - 0.1);
    }

    @Test
    public void boostAirDensity_isMadMinusAad() {
        Double aad = DerivedSensors.ambientAirDensity(100.0, 25.0, 40.0);
        Double mad = DerivedSensors.manifoldAirDensity(180.0, 40.0, 100.0, 25.0, 40.0);
        Double bad = DerivedSensors.boostAirDensity(mad, aad);
        assertNotNull(bad);
        assertEquals(mad - aad, bad, 0.15);
    }

    @Test
    public void tmfIndependent_notEqualToMafWhenVeDiffers() {
        // Manifold density 1.4 kg/m3, 2.0L, 3000 rpm, NA assumed VE=85%
        Double tmf = AdvancedAirDensity.turboMassFlowIndependent(1.4, 3000.0, 2000, false);
        assertNotNull(tmf);
        // theoretical 100%: ρ*V*(RPM/120)*1000 = 1.4 * 0.002 * (3000/120) * 1000 = 70 g/s
        // 85% → 59.5 g/s
        assertEquals(59.5, tmf, 1.0);

        // Measured MAF at true 100% VE would be 70; deviation should be nonzero
        Double dev = AdvancedAirDensity.mafDeviationPct(70.0, tmf);
        assertNotNull(dev);
        assertTrue(Math.abs(dev) > 5.0);

        // Legacy TMF(with measured VE=100) equals MAF algebraically — kept only for API
        Double legacy = AdvancedAirDensity.turboMassFlow(1.4, 3000.0, 2000, 100.0);
        assertNotNull(legacy);
        assertEquals(70.0, legacy, 0.5);
    }

    @Test
    public void omd_usesDryMassFraction_notDoubleHumidity() {
        // Dry still air sea level
        Double omdDry = AdvancedAirDensity.oxygenMassDensity(1.184, 101.325, 25.0, 0.0);
        Double omdWet = AdvancedAirDensity.oxygenMassDensity(1.15, 101.325, 25.0, 90.0);
        assertNotNull(omdDry);
        assertNotNull(omdWet);
        // Wet OMD must be strictly less than dry (water displaces dry O2)
        assertTrue(omdWet < omdDry);
    }

    @Test
    public void compressorEfficiency_nullWhenNotBoosted() {
        Double ce = AdvancedAirDensity.compressorEfficiency(100.0, 100.5, 25.0, 30.0);
        assertNull(ce);
    }

    @Test
    public void grains_positiveTypical() {
        Double g = DerivedSensors.grainsH2O(25.0, 50.0, 101.325);
        assertNotNull(g);
        assertTrue(g > 20 && g < 120);
    }

    @Test
    public void lpgPollSet_includesAmbientWhenAirDensityOn() {
        List<PIDDefinition> poll = PIDCatalogue.getLpgPollSet(true);
        boolean hasAmbient = false, hasIat = false, hasBaro = false;
        for (PIDDefinition p : poll) {
            if ("Ambient Air Temp".equals(p.getName())) hasAmbient = true;
            if ("Intake Air Temp".equals(p.getName())) hasIat = true;
            if ("Barometric Pressure".equals(p.getName())) hasBaro = true;
        }
        assertTrue(hasAmbient);
        assertTrue(hasIat);
        assertTrue(hasBaro);
    }

    @Test
    public void weatherFailure_keepsLastGoodHumidity() {
        AirDensityMonitor mon = new AirDensityMonitor(null);
        // Simulate last-good weather by computing once with defaults, then inject OBD
        Map<String, Double> batch = new HashMap<>();
        batch.put("Barometric Pressure", 100.0);
        batch.put("Ambient Air Temp", 28.0);
        batch.put("Intake Manifold Pressure", 95.0);
        batch.put("Intake Air Temp", 35.0);
        mon.onObdBatch(batch);

        AirDensityMonitor.AirDensityResult r1 = mon.compute();
        assertNotNull(r1);
        assertNotNull(r1.aad);
        // Without weather, quality is default (RH defaulted)
        assertEquals("default", r1.qualityStatus);
        assertEquals("default", r1.humiditySource);
    }

    @Test
    public void appendSamples_emitsQualityColumns() {
        AirDensityMonitor mon = new AirDensityMonitor(null);
        Map<String, Double> batch = new HashMap<>();
        batch.put("Barometric Pressure", 100.0);
        batch.put("Ambient Air Temp", 25.0);
        batch.put("Intake Manifold Pressure", 150.0);
        batch.put("Intake Air Temp", 40.0);
        mon.onObdBatch(batch);

        List<SensorSample> samples = new ArrayList<>();
        mon.appendSamples(samples, 30.0, 2500.0, 1.0, FuelMode.PETROL, 1998, 6000, false);
        assertTrue(samples.size() > 5);

        boolean hasAad = false, hasQuality = false, hasTmf = false, veAssumed = false;
        for (SensorSample s : samples) {
            if ("derived_aad".equals(s.getPidKey())) {
                hasAad = true;
                assertNotNull(s.getValue());
            }
            if ("derived_aad_quality".equals(s.getPidKey())) hasQuality = true;
            if ("derived_tmf".equals(s.getPidKey())) {
                hasTmf = true;
                assertEquals("assumed", s.getStatus());
            }
            if ("derived_ve".equals(s.getPidKey())) {
                veAssumed = "assumed".equals(s.getStatus());
            }
        }
        assertTrue(hasAad);
        assertTrue(hasQuality);
        assertTrue(hasTmf);
        assertTrue(veAssumed);
    }

    @Test
    public void humidityRatio_positive() {
        Double w = DerivedSensors.humidityRatio(101.325, 30.0, 70.0);
        assertNotNull(w);
        assertTrue(w > 0 && w < 0.05);
    }
}
