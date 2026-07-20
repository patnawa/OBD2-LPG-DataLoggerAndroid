package com.alpha.obd2logger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EcuIdentificationBusScopeTest {

    @Test
    public void primaryAutoBusUsesDetectedAddressWidthForEveryIsoTpBitRate() {
        EcuIdentificationReader.Target can11 = target11("HS-CAN (auto)");
        EcuIdentificationReader.Target can29 = target29("HS-CAN (auto)");
        List<EcuIdentificationReader.Target> candidates = Arrays.asList(can11, can29);

        assertEquals(Collections.singletonList(can11), filter(candidates,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500));
        assertEquals(Collections.singletonList(can11), filter(candidates,
                ObdProtocol.ISO_15765_4_CAN_11BIT_250));
        assertEquals(Collections.singletonList(can29), filter(candidates,
                ObdProtocol.ISO_15765_4_CAN_29BIT_500));
        assertEquals(Collections.singletonList(can29), filter(candidates,
                ObdProtocol.ISO_15765_4_CAN_29BIT_250));
    }

    @Test
    public void explicitTwentyNineBitBusIsOnlyTwentyNineBit500() {
        EcuIdentificationReader.Target target = target29("CAN 29-bit");

        assertEquals(Collections.singletonList(target), filter(
                Collections.singletonList(target),
                ObdProtocol.ISO_15765_4_CAN_29BIT_500));
        assertTrue(filter(Collections.singletonList(target),
                ObdProtocol.ISO_15765_4_CAN_29BIT_250).isEmpty());
        assertTrue(filter(Collections.singletonList(target),
                ObdProtocol.ISO_15765_4_CAN_11BIT_500).isEmpty());
    }

    @Test
    public void explicitElevenBit250BusIsOnlyElevenBit250() {
        EcuIdentificationReader.Target target = target11("CAN 11-bit 250k");

        assertEquals(Collections.singletonList(target), filter(
                Collections.singletonList(target),
                ObdProtocol.ISO_15765_4_CAN_11BIT_250));
        assertTrue(filter(Collections.singletonList(target),
                ObdProtocol.ISO_15765_4_CAN_11BIT_500).isEmpty());
        assertTrue(filter(Collections.singletonList(target),
                ObdProtocol.ISO_15765_4_CAN_29BIT_250).isEmpty());
    }

    @Test
    public void secondaryNonCanAndUnknownLabelsAreExcludedByExactName() {
        List<String> excludedLabels = Arrays.asList(
                "MS-CAN", "SW-CAN", "CH-CAN", "LS-CAN",
                "KWP2000 Fast", "ISO 9141-2", "J1850 PWM", "J1850 VPW",
                "HS-CAN (auto) extra", "CAN 29-bit 250k", "", "unknown");
        List<EcuIdentificationReader.Target> candidates = new ArrayList<>();
        for (String label : excludedLabels) candidates.add(target11(label));

        assertTrue(filter(candidates,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500).isEmpty());
    }

    @Test
    public void unresolvedAndNonIsoTpLiveProtocolsCannotAddressTargets() {
        List<EcuIdentificationReader.Target> candidates = Collections.singletonList(
                target11("HS-CAN (auto)"));

        assertTrue(filter(candidates, null).isEmpty());
        assertTrue(filter(candidates, ObdProtocol.AUTO).isEmpty());
        assertTrue(filter(candidates, ObdProtocol.ISO_9141_2).isEmpty());
        assertTrue(filter(candidates, ObdProtocol.SAE_J1939_CAN).isEmpty());
        assertTrue(filter(candidates, ObdProtocol.USER1_CAN).isEmpty());
    }

    @Test
    public void resultIsImmutableDefensiveSnapshotAndIgnoresNulls() {
        EcuIdentificationReader.Target target = target11("HS-CAN (auto)");
        List<EcuIdentificationReader.Target> candidates = new ArrayList<>();
        candidates.add(null);
        candidates.add(target);

        List<EcuIdentificationReader.Target> result = filter(candidates,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500);
        candidates.clear();

        assertEquals(Collections.singletonList(target), result);
        try {
            result.add(target);
            fail("Result must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        assertTrue(filter(null,
                ObdProtocol.ISO_15765_4_CAN_11BIT_500).isEmpty());
    }

    private static List<EcuIdentificationReader.Target> filter(
            List<EcuIdentificationReader.Target> targets, ObdProtocol protocol) {
        return EcuIdentificationBusScope.filterForActiveProtocol(targets, protocol);
    }

    private static EcuIdentificationReader.Target target11(String label) {
        return EcuIdentificationReader.Target.fromResponseId("7E8", "Engine", label);
    }

    private static EcuIdentificationReader.Target target29(String label) {
        return EcuIdentificationReader.Target.fromResponseId(
                "18DAF110", "Engine", label);
    }
}
