package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class VehicleModuleProfileStoreTest {

    private static final String VIN = "1FAFP3X12X1234567";

    @Test
    public void deepScanProfileRoundTripsWithoutRawEcuPayloads() throws Exception {
        DtcReader.ProtocolBus bus = new DtcReader.ProtocolBus("HS-CAN (auto)", "ATSP0",
                "test", false, null);
        DtcReader.ProtocolScanStatus status = new DtcReader.ProtocolScanStatus(bus, true, 2, 1);
        DtcReader.ModuleInfo pcm = new DtcReader.ModuleInfo("7E8", "7E0", 0x7E8,
                "PCM Response", "HS-CAN (auto)", true,
                true, true, false, 1, 0, 0);
        DtcReader.ModuleInfo notScanned = new DtcReader.ModuleInfo("7EA", 0x7EA,
                "ABS Response", "HS-CAN (auto)", false,
                false, false, false, 0, 0, 0);
        DtcReader.DtcScanResult result = new DtcReader.DtcScanResult(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Arrays.asList(pcm, notScanned), Collections.singletonList(status));

        VehicleModuleProfileStore.Snapshot profile = VehicleModuleProfileStore.fromScan(VIN, result);
        JSONObject json = VehicleModuleProfileStore.toJson(profile);
        VehicleModuleProfileStore.Snapshot restored = VehicleModuleProfileStore.fromJson(json);

        assertNotNull(restored);
        assertEquals(VIN, restored.getVin());
        assertEquals("Ford", restored.getBrand());
        assertEquals(1, restored.getProtocols().size());
        assertEquals(1, restored.getModules().size());
        assertEquals("7E8", restored.getModules().get(0).getCanId());
        assertEquals("7E0", restored.getModules().get(0).getRequestCanId());
        assertTrue(restored.getModules().get(0).isStoredResponded());
        assertTrue(restored.getModules().get(0).isPendingResponded());
        assertFalse(restored.getModules().get(0).isPermanentResponded());
        assertFalse("Profiles must not store raw ECU replies", json.toString().contains("raw_response"));
    }

    @Test
    public void invalidOrWrongSchemaProfileIsRejected() throws Exception {
        DtcReader.DtcScanResult result = DtcReader.DtcScanResult.empty();
        assertNull(VehicleModuleProfileStore.toJson(
                VehicleModuleProfileStore.fromScan("NOT_A_VIN", result)));

        JSONObject unsupported = new JSONObject();
        unsupported.put("schema_version", 2);
        unsupported.put("vin", VIN);
        assertNull(VehicleModuleProfileStore.fromJson(unsupported));
    }

    @Test
    public void stableModuleIdentityDoesNotIncludeTheDisplayName() {
        VehicleModuleProfileStore.Module oldLabel = new VehicleModuleProfileStore.Module(
                "7E8", "PCM Response", "HS-CAN", true, true, false);
        VehicleModuleProfileStore.Module translatedLabel = new VehicleModuleProfileStore.Module(
                "7E8", "ECM — Engine", "HS-CAN", true, true, false);

        assertEquals(oldLabel.getStableId(), translatedLabel.getStableId());
        assertFalse(oldLabel.getDisplayLabel().equals(translatedLabel.getDisplayLabel()));
    }
}
