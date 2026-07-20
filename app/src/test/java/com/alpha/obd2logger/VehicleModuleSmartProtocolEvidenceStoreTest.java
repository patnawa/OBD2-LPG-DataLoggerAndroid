package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class VehicleModuleSmartProtocolEvidenceStoreTest {

    private static final String VIN = "MR0FZ29G1J1234567";
    private static final String OTHER_VIN = "MR0FZ29G1J7654321";
    private static final String V2_PREFIX = "vehicle_smart_protocol_evidence_v2_";

    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void v2RoundTripKeepsOnlyCanonicalProtocolsAndIsImmutable() throws Exception {
        VehicleModuleProfileStore.SmartProtocolEvidence saved =
                VehicleModuleProfileStore.saveSmartProtocolEvidence(
                        context, VIN, Arrays.asList(
                                ObdProtocol.ISO_15765_4_CAN_29BIT_500,
                                ObdProtocol.SAE_J1850_PWM,
                                ObdProtocol.ISO_15765_4_CAN_29BIT_500,
                                ObdProtocol.AUTO,
                                ObdProtocol.SAE_J1939_CAN,
                                ObdProtocol.USER1_CAN,
                                ObdProtocol.USER2_CAN));

        assertNotNull(saved);
        assertEquals(Arrays.asList("1", "7"), saved.getProtocolIds());
        VehicleModuleProfileStore.SmartProtocolEvidence restored =
                VehicleModuleProfileStore.getSmartProtocolEvidence(context, VIN);
        assertNotNull(restored);
        assertEquals(VIN, restored.getVin());
        assertEquals(Arrays.asList(
                ObdProtocol.SAE_J1850_PWM,
                ObdProtocol.ISO_15765_4_CAN_29BIT_500), restored.getProtocols());
        assertTrue(restored.getCapturedAtEpochMs() > 0L);

        JSONObject raw = new JSONObject(preferences.getString(V2_PREFIX + VIN, null));
        assertEquals(2, raw.getInt("schema_version"));
        assertEquals("smart_protocol_evidence", raw.getString("profile_kind"));
        assertEquals("strict_full_obd_scan", raw.getString("source"));
        assertTrue(raw.getJSONArray("protocols").getJSONObject(0)
                .getBoolean("selection_verified"));
        assertTrue(raw.getJSONArray("protocols").getJSONObject(0)
                .getBoolean("positive_module_evidence"));

        try {
            restored.getProtocolIds().add("2");
            fail("Protocol IDs must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        try {
            restored.getProtocols().add(ObdProtocol.SAE_J1850_VPW);
            fail("Protocols must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void runtimeSmartPlannerUsesV2EvidenceForTheExactVerifiedVin() {
        VehicleModuleProfileStore.SmartProtocolEvidence evidence =
                VehicleModuleProfileStore.saveSmartProtocolEvidence(
                        context, VIN, Arrays.asList(
                                ObdProtocol.SAE_J1850_PWM,
                                ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                                ObdProtocol.ISO_15765_4_CAN_29BIT_250));

        SmartDtcScanPlanner.Plan matching =
                SmartDtcScanPlanner.createPlanFromEvidence(
                        SmartDtcScanPlanner.ScanMode.SMART,
                        ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                        evidence, VIN, true, true);
        assertEquals(Arrays.asList("CURRENT", "1", "9"),
                matching.getProtocolIds());

        SmartDtcScanPlanner.Plan otherVehicle =
                SmartDtcScanPlanner.createPlanFromEvidence(
                        SmartDtcScanPlanner.ScanMode.SMART,
                        ObdProtocol.ISO_15765_4_CAN_11BIT_500,
                        evidence, OTHER_VIN, true, true);
        assertEquals(Collections.singletonList("CURRENT"),
                otherVehicle.getProtocolIds());
    }

    @Test
    public void fullScanExtractionRequiresExplicitSelectionAndPositiveModuleEvidence() {
        DtcReader.ProtocolBus current = new DtcReader.ProtocolBus(
                "Current live protocol", null, "current", false, null);
        DtcReader.ProtocolBus pwm = new DtcReader.ProtocolBus(
                "PWM", "ATSP1", "pwm", false, null);
        DtcReader.ProtocolBus vpwWithoutModule = new DtcReader.ProtocolBus(
                "VPW", "ATSP2", "vpw", false, null);
        DtcReader.ProtocolBus userProtocol = new DtcReader.ProtocolBus(
                "USER1", "ATSPB", "custom", false, null);

        DtcReader.ModuleInfo currentModule = new DtcReader.ModuleInfo(
                "7E8", 0x7E8, "ECM", current.label,
                true, true, false, false, 0, 0, 0);
        DtcReader.ModuleInfo pwmModule = new DtcReader.ModuleInfo(
                "100", 0x100, "ECM", pwm.label,
                true, true, false, false, 0, 0, 0);
        DtcReader.ModuleInfo userModule = new DtcReader.ModuleInfo(
                "101", 0x101, "ECM", userProtocol.label,
                true, true, false, false, 0, 0, 0);
        DtcReader.DtcScanResult result = new DtcReader.DtcScanResult(
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Arrays.asList(currentModule, pwmModule, userModule),
                Arrays.asList(
                        new DtcReader.ProtocolScanStatus(current, true, 1, 0),
                        new DtcReader.ProtocolScanStatus(pwm, true, 1, 0),
                        new DtcReader.ProtocolScanStatus(vpwWithoutModule, true, 0, 0),
                        new DtcReader.ProtocolScanStatus(userProtocol, true, 1, 0)));

        VehicleModuleProfileStore.SmartProtocolEvidence saved =
                VehicleModuleProfileStore.saveSmartProtocolEvidenceFromScan(
                        context, VIN, result);

        assertNotNull(saved);
        assertEquals(Collections.singletonList("1"), saved.getProtocolIds());
    }

    @Test
    public void v1BaselineIsNeitherMigratedNorModifiedByV2Refresh() {
        VehicleModuleProfileStore.Snapshot originalBaseline =
                VehicleModuleProfileStore.save(context, VIN, oneModuleScan());
        assertNotNull(originalBaseline);
        VehicleModuleProfileStore.Snapshot storedBaseline =
                VehicleModuleProfileStore.get(context, VIN);
        assertNotNull(storedBaseline);
        long baselineCapturedAt = storedBaseline.getCapturedAtEpochMs();

        assertNull("v1 must never be treated as reusable Smart evidence",
                VehicleModuleProfileStore.getSmartProtocolEvidence(context, VIN));

        VehicleModuleProfileStore.saveSmartProtocolEvidence(
                context, VIN,
                Collections.singletonList(ObdProtocol.ISO_15765_4_CAN_29BIT_500));
        VehicleModuleProfileStore.saveSmartProtocolEvidence(
                context, VIN,
                Collections.singletonList(ObdProtocol.ISO_9141_2));

        VehicleModuleProfileStore.Snapshot baselineAfterRefresh =
                VehicleModuleProfileStore.get(context, VIN);
        assertNotNull(baselineAfterRefresh);
        assertEquals(baselineCapturedAt, baselineAfterRefresh.getCapturedAtEpochMs());
        assertEquals(storedBaseline.getModules().get(0).getStableId(),
                baselineAfterRefresh.getModules().get(0).getStableId());
        assertEquals(Collections.singletonList("3"),
                VehicleModuleProfileStore.getSmartProtocolEvidence(context, VIN)
                        .getProtocolIds());
    }

    @Test
    public void emptyRefreshClearsHintsButNullInputLeavesExistingRecord() {
        VehicleModuleProfileStore.saveSmartProtocolEvidence(
                context, VIN,
                Collections.singletonList(ObdProtocol.ISO_15765_4_CAN_29BIT_500));

        assertNull(VehicleModuleProfileStore.saveSmartProtocolEvidence(
                context, VIN, null));
        assertEquals(Collections.singletonList("7"),
                VehicleModuleProfileStore.getSmartProtocolEvidence(context, VIN)
                        .getProtocolIds());

        VehicleModuleProfileStore.SmartProtocolEvidence cleared =
                VehicleModuleProfileStore.saveSmartProtocolEvidence(
                        context, VIN, Collections.emptyList());
        assertNotNull(cleared);
        assertTrue(cleared.getProtocols().isEmpty());
        assertTrue(VehicleModuleProfileStore.getSmartProtocolEvidence(context, VIN)
                .getProtocols().isEmpty());
    }

    @Test
    public void decoderRequiresV2ProvenanceAndPositivePerProtocolMarkers()
            throws Exception {
        VehicleModuleProfileStore.SmartProtocolEvidence source =
                VehicleModuleProfileStore.saveSmartProtocolEvidence(
                        context, VIN,
                        Collections.singletonList(ObdProtocol.SAE_J1850_VPW));
        JSONObject valid = VehicleModuleProfileStore.smartProtocolEvidenceToJson(source);
        assertNotNull(valid);

        JSONObject wrongSchema = new JSONObject(valid.toString());
        wrongSchema.put("schema_version", 1);
        assertNull(VehicleModuleProfileStore.smartProtocolEvidenceFromJson(wrongSchema));

        JSONObject wrongSource = new JSONObject(valid.toString());
        wrongSource.put("source", "live_deep_obd_scan");
        assertNull(VehicleModuleProfileStore.smartProtocolEvidenceFromJson(wrongSource));

        JSONObject mixed = new JSONObject(valid.toString());
        JSONArray protocols = new JSONArray();
        protocols.put(new JSONObject()
                .put("id", "2")
                .put("selection_verified", false)
                .put("positive_module_evidence", true));
        protocols.put(new JSONObject()
                .put("id", "7")
                .put("selection_verified", true));
        protocols.put(new JSONObject()
                .put("id", "A")
                .put("selection_verified", true)
                .put("positive_module_evidence", true));
        protocols.put(new JSONObject()
                .put("id", "3")
                .put("selection_verified", true)
                .put("positive_module_evidence", true));
        mixed.put("protocols", protocols);

        VehicleModuleProfileStore.SmartProtocolEvidence decoded =
                VehicleModuleProfileStore.smartProtocolEvidenceFromJson(mixed);
        assertNotNull(decoded);
        assertEquals(Collections.singletonList("3"), decoded.getProtocolIds());
    }

    @Test
    public void lookupRequiresExactVinInBothKeyAndPayload() throws Exception {
        VehicleModuleProfileStore.SmartProtocolEvidence source =
                VehicleModuleProfileStore.saveSmartProtocolEvidence(
                        context, VIN,
                        Collections.singletonList(ObdProtocol.ISO_9141_2));
        JSONObject json = VehicleModuleProfileStore.smartProtocolEvidenceToJson(source);
        preferences.edit().putString(V2_PREFIX + OTHER_VIN, json.toString()).commit();

        assertNull(VehicleModuleProfileStore.getSmartProtocolEvidence(
                context, OTHER_VIN));
        assertNotNull(VehicleModuleProfileStore.getSmartProtocolEvidence(context, VIN));
        assertNull(VehicleModuleProfileStore.getSmartProtocolEvidence(
                context, "NOT_A_VIN"));
    }

    private static DtcReader.DtcScanResult oneModuleScan() {
        DtcReader.ProtocolBus bus = new DtcReader.ProtocolBus(
                ObdProtocol.ISO_15765_4_CAN_11BIT_500.getLabel(),
                "ATSP6", "test", false, null);
        DtcReader.ProtocolScanStatus status =
                new DtcReader.ProtocolScanStatus(bus, true, 1, 0);
        DtcReader.ModuleInfo module = new DtcReader.ModuleInfo(
                "7E8", "7E0", 0x7E8, "PCM Response", bus.label,
                true, true, false, false, 0, 0, 0);
        return new DtcReader.DtcScanResult(
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.singletonList(module),
                Collections.singletonList(status));
    }
}
