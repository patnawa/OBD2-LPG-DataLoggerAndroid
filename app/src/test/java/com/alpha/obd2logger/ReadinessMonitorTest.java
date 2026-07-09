package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

public class ReadinessMonitorTest {

    @Test
    public void testParseGasolineAllReady() {
        // Mode 01 PID 01 response: 41 01 A B C D
        // A = 00 (MIL off, 0 DTCs)
        // B = 07 (binary 0000 0111 -> spark engine, misfire/fuel/components supported and complete)
        // C = EF (binary 1110 1111 -> Catalyst, Heated Cat, EVAP, Sec Air, EGR, O2, O2 Htr supported)
        // D = 00 (all non-continuous complete)
        String response = "4101 00 07 EF 00";
        ReadinessMonitor rm = ReadinessMonitor.parse(response);

        assertFalse(rm.isMilOn());
        assertEquals(0, rm.getDtcCount());
        assertFalse(rm.isDiesel());
        assertTrue(rm.isAllReady());

        List<ReadinessMonitor.MonitorStatus> monitors = rm.getMonitors();
        // Spark has 3 continuous + 7 non-continuous = 10 monitors
        assertEquals(10, monitors.size());

        // Check Misfire
        ReadinessMonitor.MonitorStatus misfire = findMonitor(monitors, "Misfire");
        assertNotNull(misfire);
        assertTrue(misfire.available);
        assertTrue(misfire.complete);
        assertTrue(misfire.isReady());

        // Check Catalyst
        ReadinessMonitor.MonitorStatus catalyst = findMonitor(monitors, "Catalyst");
        assertNotNull(catalyst);
        assertTrue(catalyst.available);
        assertTrue(catalyst.complete);

        // Check O2 Sensor
        ReadinessMonitor.MonitorStatus o2 = findMonitor(monitors, "O2 Sensor");
        assertNotNull(o2);
        assertTrue(o2.available);
        assertTrue(o2.complete);
    }

    @Test
    public void testParseGasolineSomeNotReady() {
        // A = 82 (MIL on, 2 DTCs)
        // B = 07 (spark engine, misfire/fuel/components supported and complete)
        // C = EF (Catalyst, Heated Cat, EVAP, Sec Air, EGR, O2, O2 Htr supported)
        // D = 83 (binary 1000 0011 -> EGR, Catalyst, Heated Cat incomplete)
        String response = "41 01 82 07 EF 83";
        ReadinessMonitor rm = ReadinessMonitor.parse(response);

        assertTrue(rm.isMilOn());
        assertEquals(2, rm.getDtcCount());
        assertFalse(rm.isDiesel());
        assertFalse(rm.isAllReady());

        List<ReadinessMonitor.MonitorStatus> monitors = rm.getMonitors();

        // EGR/VVT System (bit 7)
        ReadinessMonitor.MonitorStatus egr = findMonitor(monitors, "EGR/VVT System");
        assertNotNull(egr);
        assertTrue(egr.available);
        assertFalse(egr.complete);

        // Catalyst (bit 0)
        ReadinessMonitor.MonitorStatus catalyst = findMonitor(monitors, "Catalyst");
        assertNotNull(catalyst);
        assertTrue(catalyst.available);
        assertFalse(catalyst.complete);

        // EVAP (bit 2) - D bit 2 is 0, so complete
        ReadinessMonitor.MonitorStatus evap = findMonitor(monitors, "EVAP");
        assertNotNull(evap);
        assertTrue(evap.available);
        assertTrue(evap.complete);
    }

    @Test
    public void testParseDieselAllReady() {
        // A = 00
        // B = 0F (binary 0000 1111 -> compression engine, continuous supported & complete)
        // C = ED (binary 1110 1101 -> NMHC Cat, NOx Cat, Boost, Exhaust Gas, EGR, PM supported)
        // D = 00 (all complete)
        String response = "4101000FED00";
        ReadinessMonitor rm = ReadinessMonitor.parse(response);

        assertFalse(rm.isMilOn());
        assertTrue(rm.isDiesel());
        assertTrue(rm.isAllReady());

        List<ReadinessMonitor.MonitorStatus> monitors = rm.getMonitors();

        ReadinessMonitor.MonitorStatus nmhc = findMonitor(monitors, "NMHC Catalyst");
        assertNotNull(nmhc);
        assertTrue(nmhc.available);
        assertTrue(nmhc.complete);

        ReadinessMonitor.MonitorStatus pm = findMonitor(monitors, "PM Filter Active");
        assertNotNull(pm);
        assertTrue(pm.available);
        assertTrue(pm.complete);
    }

    @Test
    public void testEmptyOrNoiseResponses() {
        ReadinessMonitor empty = ReadinessMonitor.parse("");
        assertFalse(empty.isMilOn());
        assertTrue(empty.getMonitors().isEmpty());

        ReadinessMonitor noise = ReadinessMonitor.parse("SEARCHING...\rNODATA\r");
        assertFalse(noise.isMilOn());
        assertTrue(noise.getMonitors().isEmpty());
    }

    @Test
    public void testParseWithCANHeaders() {
        String response = "7E8 06 41 01 00 07 EF 00";
        ReadinessMonitor rm = ReadinessMonitor.parse(response);
        assertFalse(rm.isMilOn());
        assertEquals(0, rm.getDtcCount());
        assertFalse(rm.isDiesel());
        assertTrue(rm.isAllReady());
    }

    @Test
    public void testParseWithFrameIndexes() {
        String response = "0: 41 01 00 07 EF 00";
        ReadinessMonitor rm = ReadinessMonitor.parse(response);
        assertFalse(rm.isMilOn());
        assertEquals(0, rm.getDtcCount());
        assertFalse(rm.isDiesel());
        assertTrue(rm.isAllReady());
    }

    private ReadinessMonitor.MonitorStatus findMonitor(List<ReadinessMonitor.MonitorStatus> list, String name) {
        for (ReadinessMonitor.MonitorStatus s : list) {
            if (s.name.equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }
}
