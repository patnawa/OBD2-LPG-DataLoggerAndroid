package com.alpha.obd2logger;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class CommonVehicleDataStoreTest {

    @Test
    public void roundTripsAReadOnlyVehicleSnapshot() throws Exception {
        VehicleInformationReader.Snapshot source = new VehicleInformationReader.Snapshot(
                "1HGCM82633A004352", Arrays.asList(2, 4, 6),
                Arrays.asList(new Mode09Reader.CalIdEntry(0, "CAL-123")),
                Arrays.asList(new Mode09Reader.CvnEntry(0, "AABBCCDD")), 1_700_000_000_000L);

        JSONObject encoded = CommonVehicleDataStore.snapshotToJson(source);
        VehicleInformationReader.Snapshot decoded = CommonVehicleDataStore.snapshotFromJson(encoded);

        assertNotNull(decoded);
        assertEquals(source.getVin(), decoded.getVin());
        assertEquals(source.getSupportedInfoTypes(), decoded.getSupportedInfoTypes());
        assertEquals("CAL-123", decoded.getCalIds().get(0).getCalId());
        assertEquals("AABBCCDD", decoded.getCvns().get(0).getCvn());
        assertEquals(source.getCapturedAtEpochMs(), decoded.getCapturedAtEpochMs());
    }

    @Test
    public void appliesManualVinWhenMode09VinIsUnavailable() {
        VehicleInformationReader.Snapshot withoutVin = new VehicleInformationReader.Snapshot(
                null, Arrays.asList(4, 6),
                Arrays.asList(new Mode09Reader.CalIdEntry(0, "CAL-456")),
                Arrays.asList(new Mode09Reader.CvnEntry(0, "11223344")), 1234L);

        VehicleInformationReader.Snapshot effective = CommonVehicleDataStore.withFallbackVin(
                withoutVin, "1HGCM82633A004352");

        assertNotNull(effective);
        assertEquals("1HGCM82633A004352", effective.getVin());
        assertEquals("CAL-456", effective.getCalIds().get(0).getCalId());
        assertEquals(1234L, effective.getCapturedAtEpochMs());
        assertNull(CommonVehicleDataStore.withFallbackVin(withoutVin, "NOT_A_VIN"));
    }
}
