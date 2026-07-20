package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class EcuIdentificationStoreTest {
    private static final String VIN = "1HGCM82633A004352";
    private Context context;

    @Before
    public void clearStore() {
        context = RuntimeEnvironment.getApplication();
        preferences().edit().clear().commit();
    }

    @Test
    public void roundTripsEveryTargetItemCountAndRawField() throws Exception {
        EcuIdentificationReader.Target target = new EcuIdentificationReader.Target(
                "PCM / Engine", "7e0", "7e8", "HS-CAN (auto)");
        List<EcuIdentificationReader.Item> items = Arrays.asList(
                new EcuIdentificationReader.Item(
                        0xF190, "Vehicle Identity", VIN,
                        "314847434D383236333341303034333532",
                        EcuIdentificationReader.Status.POSITIVE, -1, "decoded text"),
                new EcuIdentificationReader.Item(
                        0xF187, "OEM Part Number", "Negative response NRC 0x31",
                        "7F2231", EcuIdentificationReader.Status.NEGATIVE,
                        0x31, "Request out of range"),
                new EcuIdentificationReader.Item(
                        0xF188, "OEM Software Number", "Response pending timed out",
                        "7F2278", EcuIdentificationReader.Status.RESPONSE_PENDING,
                        0x78, "P2 timeout"),
                new EcuIdentificationReader.Item(
                        0xF189, "OEM Software Version", "No valid response", "",
                        EcuIdentificationReader.Status.MALFORMED, -1,
                        "Truncated DID response"));
        EcuIdentificationReader.Snapshot source =
                new EcuIdentificationReader.Snapshot(target, items, 1_700_000_000_123L);

        JSONObject encoded = EcuIdentificationStore.toJson(
                VIN.toLowerCase(Locale.US), Collections.singletonList(source));
        List<EcuIdentificationReader.Snapshot> decoded =
                EcuIdentificationStore.fromJson(encoded);

        assertNotNull(encoded);
        assertEquals(VIN, encoded.getString("vin"));
        assertEquals(1, encoded.getInt("schema_version"));
        assertEquals(1, encoded.getInt("module_count"));
        assertEquals("314847434D383236333341303034333532",
                encoded.getJSONArray("modules").getJSONObject(0)
                        .getJSONArray("items").getJSONObject(0).getString("raw_hex"));

        assertEquals(1, decoded.size());
        EcuIdentificationReader.Snapshot restored = decoded.get(0);
        assertEquals(target.getName(), restored.getTarget().getName());
        assertEquals(target.getRequestId(), restored.getTarget().getRequestId());
        assertEquals(target.getResponseId(), restored.getTarget().getResponseId());
        assertEquals(target.getProtocol(), restored.getTarget().getProtocol());
        assertEquals(target.getStableId(), restored.getTarget().getStableId());
        assertEquals(target.getDisplayLabel(), restored.getTarget().getDisplayLabel());
        assertEquals(source.getCapturedAtEpochMs(), restored.getCapturedAtEpochMs());
        assertEquals(1, restored.getPositiveCount());
        assertEquals(2, restored.getNegativeCount());
        assertEquals(1, restored.getMalformedCount());
        assertTrue(restored.isResponded());
        assertEquals(items.size(), restored.getItems().size());

        for (int i = 0; i < items.size(); i++) {
            EcuIdentificationReader.Item expected = items.get(i);
            EcuIdentificationReader.Item actual = restored.getItems().get(i);
            assertEquals(expected.getIdentifier(), actual.getIdentifier());
            assertEquals(expected.getDidHex(), actual.getDidHex());
            assertEquals(expected.getLabel(), actual.getLabel());
            assertEquals(expected.getDisplayValue(), actual.getDisplayValue());
            assertEquals(expected.getRawHex(), actual.getRawHex());
            assertEquals(expected.getStatus(), actual.getStatus());
            assertEquals(expected.getNegativeResponseCode(), actual.getNegativeResponseCode());
            assertEquals(expected.getDetail(), actual.getDetail());
        }

        try {
            decoded.add(source);
            fail("Decoded records must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void mergeReplacesOnlyTheMatchingStableModule() {
        EcuIdentificationReader.Snapshot oldPcm = snapshot(
                "Old PCM label", "7E8", "HS-CAN", 100L, "OLD");
        EcuIdentificationReader.Snapshot tcm = snapshot(
                "TCM", "7E9", "HS-CAN", 200L, "TCM");
        EcuIdentificationReader.Snapshot newPcm = snapshot(
                "Translated PCM label", "7E8", "HS-CAN", 300L, "NEW");

        List<EcuIdentificationReader.Snapshot> merged = EcuIdentificationStore.merge(
                Arrays.asList(oldPcm, tcm), newPcm);

        assertEquals(2, merged.size());
        EcuIdentificationReader.Snapshot storedPcm = find(
                merged, newPcm.getTarget().getStableId());
        EcuIdentificationReader.Snapshot storedTcm = find(
                merged, tcm.getTarget().getStableId());
        assertSame(newPcm, storedPcm);
        assertSame("The unrelated module snapshot must be preserved", tcm, storedTcm);
        assertEquals("Translated PCM label", storedPcm.getTarget().getName());
        assertEquals("NEW", storedPcm.getItems().get(0).getDisplayValue());
        assertFalse(oldPcm.getTarget().getDisplayLabel()
                .equals(newPcm.getTarget().getDisplayLabel()));
    }

    @Test
    public void savePreservesSuccessfulSnapshotAfterMalformedAndNegativeReads() {
        EcuIdentificationReader.Snapshot successful = snapshot(
                "PCM", "7E8", "HS-CAN", 100L, "KNOWN GOOD");
        List<EcuIdentificationReader.Snapshot> first =
                EcuIdentificationStore.save(context, VIN, successful);
        assertEquals(1, first.size());

        String key = "ecu_identification_v1_" + VIN;
        String storedJson = preferences().getString(key, null);
        assertNotNull(storedJson);

        EcuIdentificationReader.Snapshot malformed = snapshotWithStatus(
                successful.getTarget(), 200L,
                EcuIdentificationReader.Status.MALFORMED, -1);
        List<EcuIdentificationReader.Snapshot> afterMalformed =
                EcuIdentificationStore.save(context, VIN, malformed);
        assertStoredSuccessful(afterMalformed, successful);
        assertEquals("Malformed read must not touch persisted JSON", storedJson,
                preferences().getString(key, null));

        EcuIdentificationReader.Snapshot negative = snapshotWithStatus(
                successful.getTarget(), 300L,
                EcuIdentificationReader.Status.NEGATIVE, 0x31);
        List<EcuIdentificationReader.Snapshot> afterNegative =
                EcuIdentificationStore.save(context, VIN, negative);
        assertStoredSuccessful(afterNegative, successful);
        assertEquals("Negative read must not touch persisted JSON", storedJson,
                preferences().getString(key, null));
        assertStoredSuccessful(EcuIdentificationStore.get(context, VIN), successful);

        try {
            afterNegative.add(successful);
            fail("Stored records must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void saveDoesNotCreateRecordFromZeroPositiveData() {
        EcuIdentificationReader.Target target =
                EcuIdentificationReader.Target.fromResponseId(
                        "7E8", "PCM", "HS-CAN");
        EcuIdentificationReader.Snapshot malformed = snapshotWithStatus(
                target, 100L, EcuIdentificationReader.Status.MALFORMED, -1);
        EcuIdentificationReader.Snapshot negative = snapshotWithStatus(
                target, 200L, EcuIdentificationReader.Status.NEGATIVE, 0x31);

        List<EcuIdentificationReader.Snapshot> afterMalformed =
                EcuIdentificationStore.save(context, VIN, malformed);
        List<EcuIdentificationReader.Snapshot> afterNegative =
                EcuIdentificationStore.save(context, VIN, negative);

        assertTrue(afterMalformed.isEmpty());
        assertTrue(afterNegative.isEmpty());
        assertTrue(EcuIdentificationStore.get(context, VIN).isEmpty());
        assertTrue("Zero-positive snapshots must not create a preference record",
                preferences().getAll().isEmpty());
    }

    @Test
    public void recordIsBoundedToTheMostRecentlyMergedModules() {
        List<EcuIdentificationReader.Snapshot> stored = Collections.emptyList();
        int total = EcuIdentificationStore.MAX_MODULES + 5;
        for (int i = 0; i < total; i++) {
            String responseId = String.format(Locale.US, "%03X", 0x700 + i);
            stored = EcuIdentificationStore.merge(stored, snapshot(
                    "Module " + i, responseId, "CAN", i, "VALUE " + i));
        }

        assertEquals(EcuIdentificationStore.MAX_MODULES, stored.size());
        assertNull(find(stored, "CAN / 700"));
        assertNotNull(find(stored, "CAN / 705"));
        assertNotNull(find(stored,
                "CAN / " + String.format(Locale.US, "%03X", 0x700 + total - 1)));
    }

    @Test
    public void invalidVinUnsupportedSchemaAndBrokenModulesAreRejected() throws Exception {
        EcuIdentificationReader.Snapshot source = snapshot(
                "PCM", "7E8", "HS-CAN", 1L, "VALUE");
        assertNull(EcuIdentificationStore.toJson(
                "NOT_A_VIN", Collections.singletonList(source)));

        JSONObject unsupported = EcuIdentificationStore.toJson(
                VIN, Collections.singletonList(source));
        unsupported.put("schema_version", 2);
        assertTrue(EcuIdentificationStore.fromJson(unsupported).isEmpty());

        JSONObject invalidVin = EcuIdentificationStore.toJson(
                VIN, Collections.singletonList(source));
        invalidVin.put("vin", "INVALID");
        assertTrue(EcuIdentificationStore.fromJson(invalidVin).isEmpty());

        JSONObject brokenModule = EcuIdentificationStore.toJson(
                VIN, Collections.singletonList(source));
        brokenModule.getJSONArray("modules").getJSONObject(0)
                .getJSONObject("target").put("response_id", "NOT_CAN");
        assertTrue(EcuIdentificationStore.fromJson(brokenModule).isEmpty());
    }

    @Test
    public void corruptPreferenceRecordIsIgnoredWithoutEscapingTheStore() {
        String key = "ecu_identification_v1_" + VIN;
        preferences().edit().putString(key, "{not valid JSON").commit();

        assertTrue(EcuIdentificationStore.get(context, VIN).isEmpty());

        EcuIdentificationReader.Snapshot replacement = snapshot(
                "PCM", "7E8", "HS-CAN", 42L, "RECOVERED");
        List<EcuIdentificationReader.Snapshot> saved =
                EcuIdentificationStore.save(context, VIN, replacement);
        assertEquals(1, saved.size());
        assertEquals("RECOVERED", saved.get(0).getItems().get(0).getDisplayValue());
        assertEquals(1, EcuIdentificationStore.get(context, VIN).size());
    }

    private static EcuIdentificationReader.Snapshot snapshot(
            String name, String responseId, String protocol, long capturedAt, String value) {
        int response = Integer.parseInt(responseId, 16);
        String requestId = String.format(Locale.US, "%03X",
                response - PhysicalAddressing.RESPONSE_OFFSET);
        EcuIdentificationReader.Target target = new EcuIdentificationReader.Target(
                name, requestId, responseId, protocol);
        List<EcuIdentificationReader.Item> items = Collections.singletonList(
                new EcuIdentificationReader.Item(
                        0xF190, "VIN", value, "AABB",
                        EcuIdentificationReader.Status.POSITIVE, -1, ""));
        return new EcuIdentificationReader.Snapshot(target, items, capturedAt);
    }

    private static EcuIdentificationReader.Snapshot snapshotWithStatus(
            EcuIdentificationReader.Target target, long capturedAt,
            EcuIdentificationReader.Status status, int negativeResponseCode) {
        List<EcuIdentificationReader.Item> items = Collections.singletonList(
                new EcuIdentificationReader.Item(
                        0xF190, "VIN", "No usable value", "",
                        status, negativeResponseCode, "test response"));
        return new EcuIdentificationReader.Snapshot(target, items, capturedAt);
    }

    private static void assertStoredSuccessful(
            List<EcuIdentificationReader.Snapshot> stored,
            EcuIdentificationReader.Snapshot expected) {
        assertEquals(1, stored.size());
        EcuIdentificationReader.Snapshot actual = stored.get(0);
        assertEquals(expected.getCapturedAtEpochMs(), actual.getCapturedAtEpochMs());
        assertEquals("KNOWN GOOD", actual.getItems().get(0).getDisplayValue());
        assertEquals(1, actual.getPositiveCount());
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE);
    }

    private static EcuIdentificationReader.Snapshot find(
            List<EcuIdentificationReader.Snapshot> snapshots, String stableId) {
        for (EcuIdentificationReader.Snapshot snapshot : snapshots) {
            if (stableId.equals(snapshot.getTarget().getStableId())) return snapshot;
        }
        return null;
    }
}
