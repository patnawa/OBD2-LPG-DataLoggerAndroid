package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses OBD2 Mode 01 PID 01 response to determine emission readiness
 * monitor status.
 *
 * PID 01 returns 4 bytes (A B C D) for 2 groups of monitors:
 *   Byte A: bits 0-3 = number of DTCs, bit 7 = MIL on/off
 *   Byte B: availability of 8 tests (bit = test available)
 *   Byte C: completeness of 8 tests (bit = test complete)
 *   Byte D: (for some protocols) additional test bits
 *
 * Monitors (bits in B/C) per SAE J1979:
 *   Bit 0: Reserved
 *   Bit 1: Misfire
 *   Bit 2: Fuel System
 *   Bit 3: Components
 *   Bit 4: Catalyst
 *   Bit 5: Heated Catalyst
 *   Bit 6: EVAP
 *   Bit 7: Secondary Air
 * And in D (additional monitors):
 *   D1=EGR, D2=Particulate Filter, D3=NOx/SOR, D4=O2 Sensor, D5=O2 Sensor Heater
 */
public final class ReadinessMonitor {

    private final boolean milOn;
    private final int dtcCount;
    private final boolean isDiesel;
    private final List<MonitorStatus> monitors;

    public static class MonitorStatus {
        public final String name;
        public final boolean available;
        public final boolean complete;

        public MonitorStatus(String name, boolean available, boolean complete) {
            this.name = name;
            this.available = available;
            this.complete = complete;
        }

        public boolean isReady() {
            return available && complete;
        }
    }

    public ReadinessMonitor(boolean milOn, int dtcCount, boolean isDiesel, List<MonitorStatus> monitors) {
        this.milOn = milOn;
        this.dtcCount = dtcCount;
        this.isDiesel = isDiesel;
        this.monitors = monitors;
    }

    public boolean isMilOn() { return milOn; }
    public int getDtcCount() { return dtcCount; }
    public boolean isDiesel() { return isDiesel; }
    public List<MonitorStatus> getMonitors() { return monitors; }

    public boolean isAllReady() {
        for (MonitorStatus m : monitors) {
            if (m.available && !m.complete) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parse a Mode 01 PID 01 response.
     * Response header: 41 01, followed by 4 data bytes (A B C D).
     */
    public static ReadinessMonitor parse(String response) {
        if (response == null || response.isEmpty()) {
            return new ReadinessMonitor(false, 0, false, new ArrayList<>());
        }

        String hex = response.replace("\r\n", "\n").replace('\r', '\n');
        hex = hex.replaceAll("(?i)(SEARCHING|BUSINIT|BUS INIT|\\.)", "");
        hex = hex.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();

        int idx = hex.indexOf("4101");
        if (idx < 0) {
            return new ReadinessMonitor(false, 0, false, new ArrayList<>());
        }

        String data = hex.substring(idx + 4);
        // Require at least 3 data bytes (A B C) — pre-2008 non-CAN vehicles
        // may return only 3 bytes (no byte D for Group 2 monitors).
        if (data.length() < 6) {
            return new ReadinessMonitor(false, 0, false, new ArrayList<>());
        }

        int byteA = Integer.parseInt(data.substring(0, 2), 16);
        int byteB = Integer.parseInt(data.substring(2, 4), 16);
        int byteC = Integer.parseInt(data.substring(4, 6), 16);
        int byteD = data.length() >= 8 ? Integer.parseInt(data.substring(6, 8), 16) : 0;

        boolean mil = (byteA & 0x80) != 0;
        int dtcCount = byteA & 0x7F;

        // Byte B bit 3: 0 = Spark (gasoline/LPG), 1 = Compression (diesel)
        boolean diesel = (byteB & 0x08) != 0;

        List<MonitorStatus> monitors = new ArrayList<>();
        // Group 1 (bits in B for availability, C for completeness) per SAE J1979.
        // Bit 0 is Reserved and skipped.
        // Bits 1-3: complete when the bit is ZERO in byteC.
        monitors.add(new MonitorStatus("Misfire",          (byteB & 0x02) != 0, (byteC & 0x02) == 0));
        monitors.add(new MonitorStatus("Fuel System",      (byteB & 0x04) != 0, (byteC & 0x04) == 0));
        monitors.add(new MonitorStatus("Components",       (byteB & 0x08) != 0, (byteC & 0x08) == 0));

        if (!diesel) {
            // Gasoline / Spark Ignition Monitors
            monitors.add(new MonitorStatus("Catalyst",         (byteB & 0x10) != 0, (byteC & 0x10) != 0));
            monitors.add(new MonitorStatus("Heated Catalyst",  (byteB & 0x20) != 0, (byteC & 0x20) != 0));
            monitors.add(new MonitorStatus("EVAP",             (byteB & 0x40) != 0, (byteC & 0x40) != 0));
            monitors.add(new MonitorStatus("Secondary Air",    (byteB & 0x80) != 0, (byteC & 0x80) != 0));

            // Group 2 (byte D) Gasoline
            monitors.add(new MonitorStatus("EGR/VVT System",   true, (byteD & 0x20) != 0));
            monitors.add(new MonitorStatus("O2 Sensor",        true, (byteD & 0x10) != 0));
            monitors.add(new MonitorStatus("O2 Sensor Heater", true, (byteD & 0x08) != 0));
        } else {
            // Diesel / Compression Ignition Monitors
            monitors.add(new MonitorStatus("NMHC Catalyst",    (byteB & 0x10) != 0, (byteC & 0x10) != 0));
            monitors.add(new MonitorStatus("NOx Catalyst",     (byteB & 0x20) != 0, (byteC & 0x20) != 0));
            monitors.add(new MonitorStatus("Boost Pressure",   (byteB & 0x40) != 0, (byteC & 0x40) != 0));
            monitors.add(new MonitorStatus("exhaust Gas Sensor", (byteB & 0x80) != 0, (byteC & 0x80) != 0));

            // Group 2 (byte D) Diesel
            monitors.add(new MonitorStatus("EGR/VVT System",   true, (byteD & 0x20) != 0));
            monitors.add(new MonitorStatus("PM Filter Active", true, (byteD & 0x08) != 0));
            monitors.add(new MonitorStatus("DPF/Particulate",  true, (byteD & 0x40) != 0));
            monitors.add(new MonitorStatus("NOx/SCR Monitor",  true, (byteD & 0x80) != 0));
            monitors.add(new MonitorStatus("exhaust Gas Temp",  true, (byteD & 0x10) != 0));
        }

        return new ReadinessMonitor(mil, dtcCount, diesel, monitors);
    }

    /**
     * Read readiness from a connected driver.
     */
    public static ReadinessMonitor read(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) {
            return new ReadinessMonitor(false, 0, false, new ArrayList<>());
        }
        String response = driver.sendCommandRaw("0101");
        return parse(response);
    }
}
