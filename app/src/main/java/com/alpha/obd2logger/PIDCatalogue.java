package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PIDCatalogue {
    private static final List<PIDDefinition> ALL = Collections.unmodifiableList(buildAll());

    private PIDCatalogue() {
    }

    public static List<PIDDefinition> getAll() {
        return ALL;
    }

    public static List<PIDDefinition> getLpgCritical() {
        List<PIDDefinition> critical = new ArrayList<>();
        for (PIDDefinition pid : ALL) {
            if (pid.isLpgCritical()) {
                critical.add(pid);
            }
        }
        return Collections.unmodifiableList(critical);
    }

    /**
     * Return PIDs suitable for the dashboard gauges.
     */
    public static List<PIDDefinition> getDashboard() {
        List<PIDDefinition> dash = new ArrayList<>();
        for (PIDDefinition pid : ALL) {
            if (pid.isDashboard()) {
                dash.add(pid);
            }
        }
        return Collections.unmodifiableList(dash);
    }

    private static List<PIDDefinition> buildAll() {
        List<PIDDefinition> list = new ArrayList<>();
        // --- Core engine PIDs ---
        list.add(new PIDDefinition("Engine RPM", "01", "0C", "rpm", "(A*256+B)/4", 0, 16383.75, true, 2, true));
        list.add(new PIDDefinition("Vehicle Speed", "01", "0D", "km/h", "A", 0, 255, false, 1, true));
        list.add(new PIDDefinition("Engine Load", "01", "04", "%", "A*100/255", 0, 100, true, 1, true));
        list.add(new PIDDefinition("Coolant Temp", "01", "05", "°C", "A-40", -40, 215, true, 1, true));
        list.add(new PIDDefinition("Intake Air Temp", "01", "0F", "°C", "A-40", -40, 215, true, 1, false));
        list.add(new PIDDefinition("Ambient Air Temp", "01", "46", "°C", "A-40", -40, 215, false, 1, false));
        list.add(new PIDDefinition("Throttle Position", "01", "11", "%", "A*100/255", 0, 100, false, 1, true));
        list.add(new PIDDefinition("MAF Air Flow", "01", "10", "g/s", "(A*256+B)/100", 0, 655.35, true, 2, true));

        // --- Fuel trim & lambda ---
        list.add(new PIDDefinition("Short Term Fuel Trim", "01", "06", "%", "(A-128)*100/128", -100, 99.2, true, 1, true));
        list.add(new PIDDefinition("Long Term Fuel Trim", "01", "07", "%", "(A-128)*100/128", -100, 99.2, true, 1, true));
        list.add(new PIDDefinition("STFT Bank 2", "01", "08", "%", "(A-128)*100/128", -100, 99.2, true, 1, false));
        list.add(new PIDDefinition("LTFT Bank 2", "01", "09", "%", "(A-128)*100/128", -100, 99.2, true, 1, false));
        list.add(new PIDDefinition("Lambda (B1S1)", "01", "34", "", "(A*256+B)/32768", 0, 2, true, 2, false));
        list.add(new PIDDefinition("Wideband Lambda (B1S1)", "01", "44", "", "(A*256+B)/32768", 0, 2, true, 2, false));
        // --- Oxygen sensors (Mode 01 PIDs 0x14-0x1B) ---
        // Each O2 sensor PID returns 2 data bytes: A = voltage (A/200), B = short-term fuel trim.
        // B1S1 (PID 0x14) is already listed above; here we add the remaining 7 sensors.
        // Marked lpgCritical=true so they are polled in LPG mode and shown on dashboard.
        list.add(new PIDDefinition("O2 Sensor B1S1 Voltage", "01", "14", "V", "A/200", 0, 1.275, true, 2, true));
        list.add(new PIDDefinition("O2 Sensor B1S1 STFT", "01", "14_B", "%", "(B-128)*100/128", -100, 99.2, true, 2, false));
        list.add(new PIDDefinition("O2 Sensor B1S2 Voltage", "01", "15", "V", "A/200", 0, 1.275, true, 2, true));
        list.add(new PIDDefinition("O2 Sensor B1S2 STFT", "01", "15_B", "%", "(B-128)*100/128", -100, 99.2, true, 2, false));
        list.add(new PIDDefinition("O2 Sensor B1S3 Voltage", "01", "16", "V", "A/200", 0, 1.275, false, 2, false));
        list.add(new PIDDefinition("O2 Sensor B1S4 Voltage", "01", "17", "V", "A/200", 0, 1.275, false, 2, false));
        list.add(new PIDDefinition("O2 Sensor B2S1 Voltage", "01", "18", "V", "A/200", 0, 1.275, false, 2, false));
        list.add(new PIDDefinition("O2 Sensor B2S2 Voltage", "01", "19", "V", "A/200", 0, 1.275, false, 2, false));
        list.add(new PIDDefinition("O2 Sensor B2S3 Voltage", "01", "1A", "V", "A/200", 0, 1.275, false, 2, false));
        list.add(new PIDDefinition("O2 Sensor B2S4 Voltage", "01", "1B", "V", "A/200", 0, 1.275, false, 2, false));

        // --- Pressure ---
        list.add(new PIDDefinition("Intake Manifold Pressure", "01", "0B", "kPa", "A", 0, 255, true, 1, true));
        list.add(new PIDDefinition("Fuel Pressure", "01", "0A", "kPa", "A*3", 0, 765, false, 1, false));
        list.add(new PIDDefinition("Fuel Rail Pressure", "01", "23", "kPa", "(A*256+B)*10", 0, 655350, false, 2, false));
        list.add(new PIDDefinition("Barometric Pressure", "01", "33", "kPa", "A", 0, 255, false, 1, false));

        // --- Timing & fuel ---
        list.add(new PIDDefinition("Timing Advance", "01", "0E", "deg", "A/2-64", -64, 63.5, true, 1, false));
        list.add(new PIDDefinition("Fuel Inject Timing", "01", "5D", "deg", "(A*256+B)/128-210", -210, 302, true, 2, false));
        list.add(new PIDDefinition("Engine Fuel Rate", "01", "5E", "L/h", "(A*256+B)/20", 0, 3276, true, 2, false));

        // --- Load & misc ---
        list.add(new PIDDefinition("Absolute Load", "01", "43", "%", "(A*256+B)*100/255", 0, 25700, false, 2, false));
        list.add(new PIDDefinition("Fuel Level", "01", "2F", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Fuel Type", "01", "51", "", "A", 0, 255, false, 1, false));
        list.add(new PIDDefinition("Ethanol Fuel", "01", "52", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Control Module Voltage", "01", "42", "V", "(A*256+B)/1000", 0, 65.535, false, 2, true));

        // --- Time & distance ---
        list.add(new PIDDefinition("Run Time Since Start", "01", "1F", "s", "(A*256+B)", 0, 65535, false, 2, false));
        list.add(new PIDDefinition("Distance Since DTC Cleared", "01", "31", "km", "(A*256+B)", 0, 65535, false, 2, false));
        list.add(new PIDDefinition("Distance With MIL On", "01", "21", "km", "(A*256+B)", 0, 65535, false, 2, false));

        // --- Protocol info ---
        list.add(new PIDDefinition("OBD Standards Compliance", "01", "1C", "", "A", 0, 255, false, 1, false));

        // --- EVAP ---
        list.add(new PIDDefinition("EVAP System Vapor Pressure", "01", "32", "Pa", "(A*256+B)/4-8192", -8192, 8192, false, 2, false));

        return list;
    }
}
