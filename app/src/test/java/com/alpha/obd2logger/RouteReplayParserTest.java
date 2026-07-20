package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RouteReplayParserTest {

    private static final String HEADER =
            "timestamp,elapsed_s,fuel_mode,loop_status,vehicle_brand,vin,"
            + "\"Engine RPM (rpm)\",\"Vehicle Speed (km/h)\",\"Turbo Boost (psi)\","
            + "\"GPS Latitude (deg)\",\"GPS Longitude (deg)\",\"GPS Accuracy (m)\"";

    @Test
    public void header_resolvesRouteColumns() {
        LogReplayParser.RouteColumns c = LogReplayParser.parseRouteHeader(HEADER);
        assertTrue(c.hasRoute());
        assertEquals(1, c.elapsedIdx);
        assertEquals(7, c.speedIdx);
        assertEquals(8, c.boostPsiIdx);
        assertEquals(9, c.latIdx);
        assertEquals(10, c.lonIdx);
    }

    @Test
    public void legacyHeaderWithoutGps_hasNoRoute() {
        LogReplayParser.RouteColumns c = LogReplayParser.parseRouteHeader(
                "timestamp,elapsed_s,fuel_mode,loop_status,vehicle_brand,vin,"
                + "\"Engine RPM (rpm)\",\"Vehicle Speed (km/h)\"");
        assertFalse(c.hasRoute());
        assertNull(LogReplayParser.parseRouteLine("\"x\",1.0,Petrol,Closed,Toyota,V,800,42", c));
    }

    @Test
    public void line_withFix_parsesAllFields() {
        LogReplayParser.RouteColumns c = LogReplayParser.parseRouteHeader(HEADER);
        LogReplayParser.RoutePoint p = LogReplayParser.parseRouteLine(
                "\"2026-07-20T10:00:00.000\",12.5,Petrol,Closed,Toyota,VIN,"
                + "2500,88,4.2,13.7563,100.5018,3.5", c);
        assertNotNull(p);
        assertEquals(13.7563, p.lat, 1e-9);
        assertEquals(100.5018, p.lon, 1e-9);
        assertEquals(12.5, p.elapsedS, 1e-9);
        assertEquals(88.0, p.speedKmh, 1e-9);
        assertEquals(4.2, p.boostPsi, 1e-9);
    }

    @Test
    public void line_withoutFix_returnsNullNotZeroZero() {
        LogReplayParser.RouteColumns c = LogReplayParser.parseRouteHeader(HEADER);
        assertNull(LogReplayParser.parseRouteLine(
                "\"2026-07-20T10:00:00.000\",12.5,Petrol,Closed,Toyota,VIN,"
                + "2500,88,4.2,,,", c));
    }

    @Test
    public void line_withMissingTelemetry_stillCarriesTheFix() {
        LogReplayParser.RouteColumns c = LogReplayParser.parseRouteHeader(HEADER);
        LogReplayParser.RoutePoint p = LogReplayParser.parseRouteLine(
                "\"2026-07-20T10:00:00.000\",3.0,LPG,Closed,Toyota,VIN,"
                + ",,,13.75,100.50,5.0", c);
        assertNotNull(p);
        assertNull(p.speedKmh);
        assertNull(p.boostPsi);
    }

    @Test
    public void line_withOutOfRangeCoordinates_isRejected() {
        LogReplayParser.RouteColumns c = LogReplayParser.parseRouteHeader(HEADER);
        assertNull(LogReplayParser.parseRouteLine(
                "\"t\",1.0,Petrol,Closed,Toyota,VIN,2500,88,4.2,913.75,100.50,5.0", c));
    }
}
