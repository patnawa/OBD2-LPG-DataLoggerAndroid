package com.alpha.obd2logger;

import com.alpha.obd2logger.can.UdsDataIdentifier;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EcuIdentificationReaderTest {

    private static final String CR = "\r";

    @Test
    public void derivesElevenBitRequestAddressFromObservedResponse() {
        EcuIdentificationReader.Target target =
                EcuIdentificationReader.targetFromResponseId(
                        "0x7e8", "Engine", "HS-CAN (auto)");

        assertNotNull(target);
        assertEquals("7E0", target.getRequestId());
        assertEquals("7E8", target.getResponseId());
        assertEquals("Engine", target.getName());
        assertEquals("HS-CAN (auto) / 7E8", target.getStableId());
    }

    @Test
    public void derivesTwentyNineBitFixedAddressPair() {
        EcuIdentificationReader.Target engine =
                EcuIdentificationReader.Target.fromResponseId(
                        "18DAF110", "Engine", "CAN 29-bit");
        EcuIdentificationReader.Target transmission =
                EcuIdentificationReader.Target.fromResponseId(
                        "18daf118", "Transmission", "CAN 29-bit");

        assertNotNull(engine);
        assertEquals("18DA10F1", engine.getRequestId());
        assertEquals("18DAF110", engine.getResponseId());
        assertNotNull(transmission);
        assertEquals("18DA18F1", transmission.getRequestId());
        assertEquals("18DAF118", transmission.getResponseId());
    }

    @Test
    public void rejectsNonResponseAndMalformedAddresses() {
        assertNull(EcuIdentificationReader.Target.fromResponseId(
                "18DA10F1", "request, not response", "CAN 29-bit"));
        assertNull(EcuIdentificationReader.Target.fromResponseId(
                "18DBF110", "functional family", "CAN 29-bit"));
        assertNull(EcuIdentificationReader.Target.fromResponseId(
                "GGG", "bad", "HS-CAN"));
        assertNull(EcuIdentificationReader.Target.fromResponseId(
                "000", "would underflow", "HS-CAN"));
        assertNull(EcuIdentificationReader.Target.fromResponseId(
                "619", "unverified non-standard pair", "HS-CAN"));
    }

    @Test
    public void defaultTargetsAreConservativeAndProtocolAware() {
        List<EcuIdentificationReader.Target> automatic =
                EcuIdentificationReader.defaultTargets(ObdProtocol.AUTO);
        assertEquals(2, automatic.size());
        assertEquals("7E0", automatic.get(0).getRequestId());
        assertEquals("7E8", automatic.get(0).getResponseId());
        assertEquals("7E1", automatic.get(1).getRequestId());
        assertEquals("7E9", automatic.get(1).getResponseId());

        List<EcuIdentificationReader.Target> can29 =
                EcuIdentificationReader.defaultTargets(
                        ObdProtocol.ISO_15765_4_CAN_29BIT_500);
        assertEquals(2, can29.size());
        assertEquals("18DA10F1", can29.get(0).getRequestId());
        assertEquals("18DAF110", can29.get(0).getResponseId());
        assertEquals("18DA18F1", can29.get(1).getRequestId());
        assertEquals("18DAF118", can29.get(1).getResponseId());

        assertTrue(EcuIdentificationReader.defaultTargets(
                ObdProtocol.ISO_9141_2).isEmpty());
        assertEquals(automatic, EcuIdentificationReader.defaultTargets());
    }

    @Test
    public void observedModuleTargetsAreValidatedAndDeduplicatedPerBus() {
        List<DtcReader.ModuleInfo> modules = Arrays.asList(
                module("7E8", 0x7E8, "Engine", "HS-CAN (auto)"),
                module("7E8", 0x7E8, "duplicate", "HS-CAN (auto)"),
                module("7E8", 0x7E8, "Engine on MS", "MS-CAN"),
                module("18DAF110", 0x18DAF110, "29-bit engine", "CAN 29-bit"),
                module("18DA10F1", 0x18DA10F1, "request-shaped", "CAN 29-bit"),
                module("???", 0, "placeholder", "HS-CAN (auto)"));

        List<EcuIdentificationReader.Target> targets =
                EcuIdentificationReader.targetsFromModules(modules);

        assertEquals(3, targets.size());
        assertEquals("HS-CAN (auto) / 7E8", targets.get(0).getStableId());
        assertEquals("MS-CAN / 7E8", targets.get(1).getStableId());
        assertEquals("18DA10F1", targets.get(2).getRequestId());
    }

    @Test
    public void savedProfileTargetsUseTheSameAddressValidation() {
        List<VehicleModuleProfileStore.Module> modules = Arrays.asList(
                new VehicleModuleProfileStore.Module(
                        "7E9", "Transmission", "HS-CAN (auto)", true, false, false),
                new VehicleModuleProfileStore.Module(
                        "7E9", "duplicate", "HS-CAN (auto)", false, true, false),
                new VehicleModuleProfileStore.Module(
                        "not-a-can-id", "bad", "HS-CAN (auto)", true, false, false));
        VehicleModuleProfileStore.Snapshot profile =
                new VehicleModuleProfileStore.Snapshot(
                        "MR0FZ29G901234567", "Toyota", 1L,
                        Collections.emptyList(), modules);

        List<EcuIdentificationReader.Target> targets =
                EcuIdentificationReader.targetsFromProfile(profile);

        assertEquals(1, targets.size());
        assertEquals("7E1", targets.get(0).getRequestId());
        assertEquals("7E9", targets.get(0).getResponseId());
    }

    @Test
    public void nonStandardElevenBitAddressRequiresAProvenPhysicalPair() {
        DtcReader.ModuleInfo unverified = module(
                "619", 0x619, "Diesel ECU", "HS-CAN (auto)");
        DtcReader.ModuleInfo verified = new DtcReader.ModuleInfo(
                "619", "611", 0x619, "Diesel ECU", "HS-CAN (auto)",
                true, true, false, false, 0, 0, 0);

        assertTrue(EcuIdentificationReader.targetsFromModules(
                Collections.singletonList(unverified)).isEmpty());
        List<EcuIdentificationReader.Target> targets =
                EcuIdentificationReader.targetsFromModules(
                        Collections.singletonList(verified));
        assertEquals(1, targets.size());
        assertEquals("611", targets.get(0).getRequestId());
        assertEquals("619", targets.get(0).getResponseId());

        VehicleModuleProfileStore.Module saved =
                new VehicleModuleProfileStore.Module(
                        "619", "611", "Diesel ECU", "HS-CAN (auto)",
                        true, false, false);
        VehicleModuleProfileStore.Snapshot profile =
                new VehicleModuleProfileStore.Snapshot(
                        "MR0FZ29G901234567", "Toyota", 1L,
                        Collections.emptyList(), Collections.singletonList(saved));
        assertEquals("611", EcuIdentificationReader.targetsFromProfile(profile)
                .get(0).getRequestId());
    }

    @Test
    public void parsesPositiveNegativeMalformedAndPendingItems() {
        IdentificationDriver driver = new IdentificationDriver();
        EcuIdentificationReader.Target target =
                EcuIdentificationReader.Target.fromResponseId(
                        "7E8", "Engine", "HS-CAN (auto)");

        EcuIdentificationReader.Snapshot snapshot =
                EcuIdentificationReader.read(driver, target);

        assertEquals(UdsDataIdentifier.sweepSet().size(), snapshot.getItems().size());
        assertTrue(snapshot.isResponded());
        assertEquals(2, snapshot.getPositiveCount());
        assertEquals(2, snapshot.getNegativeCount());
        assertEquals(15, snapshot.getMalformedCount());
        assertTrue(snapshot.getCapturedAtEpochMs() > 0L);

        EcuIdentificationReader.Item ascii = snapshot.itemFor(0xF180);
        assertTrue(ascii.isPositive());
        assertEquals("AB", ascii.getDisplayValue());
        assertEquals("414200FF", ascii.getRawHex());

        EcuIdentificationReader.Item binary = snapshot.itemFor(0xF181);
        assertTrue(binary.isPositive());
        assertEquals("Hex: 01 80 02", binary.getDisplayValue());
        assertEquals("018002", binary.getRawHex());

        EcuIdentificationReader.Item malformed = snapshot.itemFor(0xF182);
        assertTrue(malformed.isMalformed());
        assertEquals("No valid response", malformed.getDisplayValue());

        EcuIdentificationReader.Item negative = snapshot.itemFor(0xF186);
        assertTrue(negative.isNegative());
        assertFalse(negative.isPending());
        assertEquals(0x31, negative.getNegativeResponseCode());
        assertEquals("7F2231", negative.getRawHex());

        EcuIdentificationReader.Item pending = snapshot.itemFor(0xF187);
        assertTrue(pending.isPending());
        assertTrue(pending.isNegative());
        assertEquals(0x78, pending.getNegativeResponseCode());
        assertEquals("Response pending timed out", pending.getDisplayValue());
        assertEquals(3, count(driver.dataCommands, "22F187"));
    }

    @Test
    public void unsafeOrMixedBytesUseLosslessHexDisplay() {
        assertEquals("PRINTABLE",
                EcuIdentificationReader.safeAsciiOrHex(
                        " PRINTABLE\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals("Hex: 41 0A 42",
                EcuIdentificationReader.safeAsciiOrHex(
                        new byte[] { 0x41, 0x0A, 0x42 }));
        assertEquals("Hex: 00 FF",
                EcuIdentificationReader.safeAsciiOrHex(
                        new byte[] { 0x00, (byte) 0xFF }));
    }

    private static DtcReader.ModuleInfo module(
            String canId, int ecuId, String name, String protocol) {
        return new DtcReader.ModuleInfo(canId, ecuId, name, protocol,
                true, true, false, false, 0, 0, 0);
    }

    private static int count(List<String> values, String wanted) {
        int count = 0;
        for (String value : values) if (wanted.equals(value)) count++;
        return count;
    }

    private static final class IdentificationDriver extends ElmDriver {
        final List<String> dataCommands = new ArrayList<>();

        IdentificationDriver() {
            super(new LoggerConfig());
            connected = true;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override
        protected String sendCommand(String command) {
            if (command.startsWith("AT")) return "OK" + CR + ">";
            dataCommands.add(command);
            switch (command) {
                case "22F180":
                    return "7E8 07 62 F1 80 41 42 00 FF" + CR + ">";
                case "22F181":
                    return "7E8 06 62 F1 81 01 80 02" + CR + ">";
                case "22F182":
                    return "7E8 02 62 F1" + CR + ">";
                case "22F186":
                    return "7E8 03 7F 22 31" + CR + ">";
                case "22F187":
                    return "7E8 03 7F 22 78" + CR + ">";
                default:
                    return "NO DATA" + CR + ">";
            }
        }
    }
}
