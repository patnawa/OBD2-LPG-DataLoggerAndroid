package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SmartDtcScanPlannerTest {

    private static final String FORD_VIN = "MNBUMFF50FW123456";
    private static final String TOYOTA_VIN = "MR0FZ29G1J1234567";

    @Test
    public void quickAlwaysUsesOnlyCurrentAndFordOnlyChangesLabels() {
        VehicleModuleProfileStore.SmartProtocolEvidence fordEvidence =
                evidence(FORD_VIN);

        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.QUICK,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                fordEvidence, FORD_VIN, true, true);

        assertEquals(SmartDtcScanPlanner.ScanMode.QUICK, plan.getScanMode());
        assertEquals(Collections.singletonList("CURRENT"), plan.getProtocolIds());
        assertEquals(Collections.singletonList(
                ObdProtocol.ISO_15765_4_CAN_11BIT_500), plan.getProtocols());
        assertTrue(plan.usesFordLabels());
    }

    @Test
    public void fullUsesCurrentThenOneThroughNineAndDeduplicatesActive() {
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.FULL,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                null, null, false, false);

        assertEquals(Arrays.asList(
                "CURRENT", "1", "2", "3", "4", "5", "7", "8", "9"),
                plan.getProtocolIds());
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                plan.getProtocols().get(0));
        assertFalse(plan.getProtocols().contains(ObdProtocol.AUTO));
        assertFalse(plan.usesFordLabels());
    }

    @Test
    public void smartUsesOnlyPositiveModuleEvidenceInCanonicalOrder() {
        VehicleModuleProfileStore.SmartProtocolEvidence evidence = evidence(
                TOYOTA_VIN,
                ObdProtocol.ISO_15765_4_CAN_29BIT_250,
                ObdProtocol.ISO_14230_4_KWP_FAST,
                ObdProtocol.ISO_15765_4_CAN_29BIT_500,
                ObdProtocol.SAE_J1850_PWM,
                ObdProtocol.ISO_15765_4_CAN_29BIT_500);

        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.SMART,
                ObdProtocol.ISO_14230_4_KWP_FAST,
                evidence, TOYOTA_VIN, true, true);

        assertEquals(Arrays.asList("CURRENT", "1", "7", "9"),
                plan.getProtocolIds());
        assertEquals(Arrays.asList(
                ObdProtocol.ISO_14230_4_KWP_FAST,
                ObdProtocol.SAE_J1850_PWM,
                ObdProtocol.ISO_15765_4_CAN_29BIT_500,
                ObdProtocol.ISO_15765_4_CAN_29BIT_250),
                plan.getProtocols());
        assertFalse(plan.usesFordLabels());
    }

    @Test
    public void smartRequiresMatchingValidVinEnabledAndCapableAdapter() {
        VehicleModuleProfileStore.SmartProtocolEvidence evidence = evidence(
                TOYOTA_VIN, ObdProtocol.ISO_15765_4_CAN_29BIT_500);

        assertCurrentOnly(SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.SMART, ObdProtocol.AUTO,
                evidence, "MR0FZ29G1J7654321", true, true));
        assertCurrentOnly(SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.SMART, ObdProtocol.AUTO,
                evidence, "NOT_A_VIN", true, true));
        assertCurrentOnly(SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.SMART, ObdProtocol.AUTO,
                evidence, TOYOTA_VIN, false, true));
        assertCurrentOnly(SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.SMART, ObdProtocol.AUTO,
                evidence, TOYOTA_VIN, true, false));
    }

    @Test
    public void smartRecognizesAllCanonicalIdsButNeverCustomProtocolIds() {
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                SmartDtcScanPlanner.ScanMode.SMART, ObdProtocol.AUTO,
                evidence(TOYOTA_VIN, ObdProtocol.values()),
                TOYOTA_VIN, true, true);

        assertEquals(Arrays.asList(
                "CURRENT", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
                plan.getProtocolIds());
    }

    @Test
    public void planCollectionsAreImmutableAndNullModeFallsBackToQuick() {
        SmartDtcScanPlanner.Plan plan = SmartDtcScanPlanner.createPlanFromEvidence(
                null, null, null, null, true, true);

        assertEquals(SmartDtcScanPlanner.ScanMode.QUICK, plan.getScanMode());
        assertEquals(Collections.singletonList("CURRENT"), plan.getProtocolIds());
        assertEquals(Collections.singletonList(ObdProtocol.AUTO), plan.getProtocols());
        try {
            plan.getProtocolIds().add("1");
            fail("Protocol IDs must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        try {
            plan.getProtocols().add(ObdProtocol.SAE_J1850_PWM);
            fail("Protocols must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static VehicleModuleProfileStore.SmartProtocolEvidence evidence(
            String vin, ObdProtocol... protocols) {
        return new VehicleModuleProfileStore.SmartProtocolEvidence(
                vin, 1000L, Arrays.asList(protocols));
    }

    private static void assertCurrentOnly(SmartDtcScanPlanner.Plan plan) {
        assertEquals(Collections.singletonList("CURRENT"), plan.getProtocolIds());
        assertEquals(1, plan.getProtocols().size());
    }
}
