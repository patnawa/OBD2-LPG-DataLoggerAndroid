package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BatteryTesterTest {

    @Test
    public void testStateOfChargeChemistries() {
        // Test Flooded
        BatteryTester.BatteryTestResult floodedFull = BatteryTester.testStateOfCharge(12.65, BatteryTester.Chemistry.FLOODED);
        assertTrue(floodedFull.value.contains("100%"));
        assertEquals(BatteryTester.Severity.PASS, floodedFull.severity);

        BatteryTester.BatteryTestResult floodedLow = BatteryTester.testStateOfCharge(12.00, BatteryTester.Chemistry.FLOODED);
        assertTrue(floodedLow.value.contains("7%"));
        assertEquals(BatteryTester.Severity.FAIL, floodedLow.severity);

        // Test AGM
        BatteryTester.BatteryTestResult agmFull = BatteryTester.testStateOfCharge(12.80, BatteryTester.Chemistry.AGM);
        assertTrue(agmFull.value.contains("100%"));
        assertEquals(BatteryTester.Severity.PASS, agmFull.severity);

        // Test LiFePO4
        BatteryTester.BatteryTestResult lithiumFull = BatteryTester.testStateOfCharge(13.30, BatteryTester.Chemistry.LIFePO4);
        assertTrue(lithiumFull.value.contains("95%")); // 13.30 is 95% SoC per curve
        assertEquals(BatteryTester.Severity.PASS, lithiumFull.severity);

        // Smooth interpolation check (13.25V is halfway between 13.20V [80%] and 13.30V [95%] -> 87.5% -> rounds to 88%)
        BatteryTester.BatteryTestResult lithiumInterp = BatteryTester.testStateOfCharge(13.25, BatteryTester.Chemistry.LIFePO4);
        assertTrue(lithiumInterp.value.contains("88%"));

        BatteryTester.BatteryTestResult lithiumLow = BatteryTester.testStateOfCharge(12.70, BatteryTester.Chemistry.LIFePO4);
        assertTrue(lithiumLow.value.contains("3%"));
        assertEquals(BatteryTester.Severity.FAIL, lithiumLow.severity);

        // Verify chemistry-aware health grading for LiFePO4 at 13.15V (SoC is 55% -> Fair, not Excellent/Good)
        BatteryTester.BatteryTestResult lithiumHealth = BatteryTester.testBatteryHealth(13.15, -1, BatteryTester.Chemistry.LIFePO4);
        assertTrue(lithiumHealth.value.contains("Fair")); // 55% SoC should be Fair (voltage only)
        assertEquals(BatteryTester.Severity.WARN, lithiumHealth.severity);
    }

    @Test
    public void testAlternatorVoltageChemistries() {
        // Flooded charging (13.8V - 14.7V)
        assertEquals(BatteryTester.Severity.PASS, BatteryTester.testAlternatorVoltage(14.2, BatteryTester.Chemistry.FLOODED).severity);
        assertEquals(BatteryTester.Severity.FAIL, BatteryTester.testAlternatorVoltage(13.0, BatteryTester.Chemistry.FLOODED).severity);
        assertEquals(BatteryTester.Severity.FAIL, BatteryTester.testAlternatorVoltage(15.0, BatteryTester.Chemistry.FLOODED).severity);

        // AGM charging (14.0V - 14.8V)
        assertEquals(BatteryTester.Severity.PASS, BatteryTester.testAlternatorVoltage(14.4, BatteryTester.Chemistry.AGM).severity);
        assertEquals(BatteryTester.Severity.WARN, BatteryTester.testAlternatorVoltage(13.8, BatteryTester.Chemistry.AGM).severity); // Flooded would pass 13.8V, AGM warns!

        // LiFePO4 charging (14.0V - 14.6V)
        assertEquals(BatteryTester.Severity.PASS, BatteryTester.testAlternatorVoltage(14.2, BatteryTester.Chemistry.LIFePO4).severity);
        assertEquals(BatteryTester.Severity.FAIL, BatteryTester.testAlternatorVoltage(14.8, BatteryTester.Chemistry.LIFePO4).severity); // Overcharging for lithium!
    }

    @Test
    public void testVoltageDropAndRecovery() {
        // Voltage drop test
        BatteryTester.BatteryTestResult dropOk = BatteryTester.testVoltageDrop(14.2, 14.0);
        assertEquals(BatteryTester.Severity.PASS, dropOk.severity);
        
        BatteryTester.BatteryTestResult dropFail = BatteryTester.testVoltageDrop(14.2, 13.2);
        assertEquals(BatteryTester.Severity.FAIL, dropFail.severity);

        // Voltage recovery test
        BatteryTester.BatteryTestResult recOk = BatteryTester.testVoltageRecovery(14.2, 13.5, 14.15, 2.0);
        assertEquals(BatteryTester.Severity.PASS, recOk.severity);

        BatteryTester.BatteryTestResult recLag = BatteryTester.testVoltageRecovery(14.2, 13.5, 13.6, 6.0);
        assertEquals(BatteryTester.Severity.FAIL, recLag.severity); // Slow recovery + large delta = fail
    }

    @Test
    public void testStateOfHealthChemistries() {
        // High resting voltage + strong crank = Excellent SOH
        BatteryTester.BatteryTestResult floodedSoh = BatteryTester.testStateOfHealth(12.65, 11.0, 0.05, 14.2, BatteryTester.Chemistry.FLOODED);
        assertTrue(floodedSoh.value.contains("100%"));
        assertEquals(BatteryTester.Severity.PASS, floodedSoh.severity);

        // AGM SOH
        BatteryTester.BatteryTestResult agmSoh = BatteryTester.testStateOfHealth(12.80, 11.0, 0.05, 14.4, BatteryTester.Chemistry.AGM);
        assertTrue(agmSoh.value.contains("100%"));
        assertEquals(BatteryTester.Severity.PASS, agmSoh.severity);

        // Low resting voltage for AGM (12.10V) -> SOH should penalize
        BatteryTester.BatteryTestResult agmSohLow = BatteryTester.testStateOfHealth(12.10, 11.0, 0.05, 14.4, BatteryTester.Chemistry.AGM);
        assertTrue(agmSohLow.score < 100);
    }

    @Test
    public void testBatteryLife() {
        // Expected flooded life: 42 months base, tropical climate: 42 * 0.7 = 29.4 months
        BatteryTester.BatteryTestResult lifeStandard = BatteryTester.testBatteryLife(100.0, -1, BatteryTester.Chemistry.FLOODED, false);
        assertTrue(lifeStandard.value.contains("42 months"));

        BatteryTester.BatteryTestResult lifeTropical = BatteryTester.testBatteryLife(100.0, -1, BatteryTester.Chemistry.FLOODED, true);
        assertTrue(lifeTropical.value.contains("29 months")); // 42 * 0.7 = 29.4
    }

    @Test
    public void testFullReportBuilder() {
        List<Double> ripple = Arrays.asList(14.20, 14.21, 14.19, 14.22, 14.20);
        BatteryTester.BatteryReport report = BatteryTester.buildFullReport(
                12.60, 14.2, 10.8,
                14.2, 14.0,
                13.5, 14.1, 2.0,
                14.3, ripple,
                -1, -1, -1,
                0.1, 12,
                BatteryTester.Chemistry.FLOODED, false
        );

        assertNotNull(report);
        assertTrue(report.overallScore > 0);
        assertFalse(report.results.isEmpty());
    }
}
