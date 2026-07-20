package com.alpha.obd2logger;

import android.content.Context;

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

    /**
     * Return an unmodifiable list with the built-in catalogue plus any
     * user-defined custom PIDs loaded from SharedPreferences.
     */
    public static List<PIDDefinition> getAllWithCustom(Context context) {
        if (context == null) return ALL;
        List<PIDDefinition> custom = CustomPidManager.load(context);
        if (custom.isEmpty()) return ALL;
        List<PIDDefinition> merged = new ArrayList<>(ALL);
        merged.addAll(custom);
        return Collections.unmodifiableList(merged);
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
     * The actual set of PIDs to poll in LPG-only mode.
     *
     * Builds the poll set as:
     *     lpgCritical ∪ dashboard ∪ every PID a derived sensor / AeroDensity needs.
     * so lpgOnly mode stays lean but never starves a feature the UI/logging relies on.
     */
    public static List<PIDDefinition> getLpgPollSet() {
        return getLpgPollSet(true);
    }

    /**
     * @param includeAirDensity when true (default), keep AeroDensity OBD deps
     *        (Ambient Air Temp / MAP / Baro / IAT / MAF / Lambda) in the poll set.
     */
    public static List<PIDDefinition> getLpgPollSet(boolean includeAirDensity) {
        List<PIDDefinition> poll = new ArrayList<>();
        for (PIDDefinition pid : ALL) {
            if (pid.isLpgCritical() || pid.isDashboard()) {
                poll.add(pid);
            }
        }
        // Derived-sensor dependencies (name-based, kept in sync with DerivedSensors):
        //   Fuel Economy  -> MAF Air Flow, Vehicle Speed
        //   Turbo Boost   -> Intake Manifold Pressure, Barometric Pressure
        //   Fuel Trim map -> (uses raw PIDs already in catalogue)
        addByName(poll, "MAF Air Flow");
        addByName(poll, "Vehicle Speed");
        addByName(poll, "Intake Manifold Pressure");
        addByName(poll, "Barometric Pressure");
        // Throttle Position / Coolant are dashboard-critical for tuning readiness.
        addByName(poll, "Throttle Position");
        if (includeAirDensity) {
            // AeroDensity Intelligence (AAD/MAD/BAD + advanced VE/TMF/OMD):
            // Ambient Air Temp was previously skipped (lpgCritical=false) → AAD fell back to 25°C.
            addByName(poll, "Ambient Air Temp");
            addByName(poll, "Intake Air Temp");
            addByName(poll, "Lambda (B1S1)");
            addByName(poll, "Commanded Equivalence Ratio");
        }
        return Collections.unmodifiableList(poll);
    }

    /**
     * Single poll-set builder used by both in-process and background logging.
     * User-defined PIDs are deliberately retained in LPG-only mode too: a
     * setting named "Custom PIDs" must not silently disappear when the user
     * also enables the lean LPG poll profile.
     */
    public static List<PIDDefinition> getConfiguredPollSet(Context context, boolean lpgOnly,
                                                             boolean includeAirDensity,
                                                             boolean includeCustom) {
        return getConfiguredPollSet(context, lpgOnly, includeAirDensity, includeCustom, false);
    }

    /**
     * Build the configured poll set, including the standard DPF measurements
     * only when the user enabled DPF monitoring.
     *
     * <p>The DPF gate is applied to both the full and LPG-critical profiles.
     * Previously the full profile always included PIDs 0x7A/0x7B while the LPG
     * profile never included them, so the DPF checkbox could have the opposite
     * of its advertised effect.</p>
     */
    public static List<PIDDefinition> getConfiguredPollSet(Context context, boolean lpgOnly,
                                                             boolean includeAirDensity,
                                                             boolean includeCustom,
                                                             boolean includeDpf) {
        List<PIDDefinition> result = new ArrayList<>(
                lpgOnly ? getLpgPollSet(includeAirDensity) : ALL);

        if (includeDpf) {
            addByKey(result, "01_7A");
            addByKey(result, "01_7B");
        } else {
            removeStandardDpfPids(result);
        }

        if (includeCustom && context != null) {
            for (PIDDefinition custom : CustomPidManager.load(context)) {
                boolean exists = false;
                for (PIDDefinition current : result) {
                    if (current.key().equals(custom.key())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) result.add(custom);
            }
        }

        // A custom definition using the same standard service/PID must not
        // bypass an explicitly disabled DPF monitor. Manufacturer-specific
        // DPF values use their own Mode 22 identifiers and remain unaffected.
        if (!includeDpf) removeStandardDpfPids(result);
        return Collections.unmodifiableList(result);
    }

    private static void addByKey(List<PIDDefinition> list, String key) {
        for (PIDDefinition current : list) {
            if (current.key().equalsIgnoreCase(key)) return;
        }
        for (PIDDefinition pid : ALL) {
            if (pid.key().equalsIgnoreCase(key)) {
                list.add(pid);
                return;
            }
        }
    }

    private static void removeStandardDpfPids(List<PIDDefinition> list) {
        list.removeIf(pid -> "01_7A".equalsIgnoreCase(pid.key())
                || "01_7B".equalsIgnoreCase(pid.key()));
    }

    private static void addByName(List<PIDDefinition> list, String name) {
        for (PIDDefinition pid : ALL) {
            if (pid.getName().equals(name) && !list.contains(pid)) {
                list.add(pid);
                return;
            }
        }
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
        list.add(new PIDDefinition("Fuel System Status", "01", "03", "", "A", 0, 255, true, 2, false));
        list.add(new PIDDefinition("Coolant Temp", "01", "05", "°C", "A-40", -40, 215, true, 1, true));
        list.add(new PIDDefinition("Intake Air Temp", "01", "0F", "°C", "A-40", -40, 215, true, 1, false));
        list.add(new PIDDefinition("Ambient Air Temp", "01", "46", "°C", "A-40", -40, 215, false, 1, false));
        list.add(new PIDDefinition("Throttle Position", "01", "11", "%", "A*100/255", 0, 100, false, 1, true));
        list.add(new PIDDefinition("MAF Air Flow", "01", "10", "g/s", "(A*256+B)/100", 0, 655.35, true, 2, true));

        // --- Fuel trim & lambda ---
        list.add(new PIDDefinition("Short Term Fuel Trim", "01", "06", "%", "(A-128)*100/128", -100, 99.22, true, 1, true));
        list.add(new PIDDefinition("Long Term Fuel Trim", "01", "07", "%", "(A-128)*100/128", -100, 99.22, true, 1, true));
        list.add(new PIDDefinition("STFT Bank 2", "01", "08", "%", "(A-128)*100/128", -100, 99.22, true, 1, false));
        list.add(new PIDDefinition("LTFT Bank 2", "01", "09", "%", "(A-128)*100/128", -100, 99.22, true, 1, false));
        // SAE J1979 PID 0x34: A,B = measured equivalence ratio; C,D = sensor current.
        // PID 0x44 is a separate two-byte COMMAND value and must never be treated as measured lambda.
        list.add(new PIDDefinition("Lambda (B1S1)", "01", "34", "", "(A*256+B)/32768", 0, 2, true, 4, false));
        list.add(new PIDDefinition("O2 Sensor B1S1 Current", "01", "34_CD", "mA", "(C*256+D)/256-128", -128, 128, false, 4, false));
        list.add(new PIDDefinition("Commanded Equivalence Ratio", "01", "44", "", "(A*256+B)/32768", 0, 2, true, 2, false));
        // --- Oxygen sensors (Mode 01 PIDs 0x14-0x1B) ---
        // Each O2 sensor PID returns 2 data bytes: A = voltage (A/200), B = short-term fuel trim.
        // B1S1 (PID 0x14) is already listed above; here we add the remaining 7 sensors.
        // Marked lpgCritical=true so they are polled in LPG mode and shown on dashboard.
        list.add(new PIDDefinition("O2 Sensor B1S1 Voltage", "01", "14", "V", "A/200", 0, 1.275, true, 2, true));
        list.add(new PIDDefinition("O2 Sensor B1S1 STFT", "01", "14_B", "%", "(B-128)*100/128", -100, 99.22, true, 2, false));
        list.add(new PIDDefinition("O2 Sensor B1S2 Voltage", "01", "15", "V", "A/200", 0, 1.275, true, 2, true));
        list.add(new PIDDefinition("O2 Sensor B1S2 STFT", "01", "15_B", "%", "(B-128)*100/128", -100, 99.22, true, 2, false));
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
        // lpgCritical=true: needed for turbo boost calculation (MAP - Baro)
        list.add(new PIDDefinition("Barometric Pressure", "01", "33", "kPa", "A", 0, 255, true, 1, false));

        // Standard PID 7A is differential pressure, not soot percentage.
        // Manufacturer soot/ash/regeneration values must come from a verified
        // brand-specific Mode 22 profile rather than being mislabeled as SAE data.
        list.add(new PIDDefinition("DPF Differential Pressure 1", "01", "7A", "kPa", "(A*256+B)/100", 0, 655.35, false, 4, false));
        list.add(new PIDDefinition("DPF Inlet Temperature 1", "01", "7B", "°C", "(B*256+C)/10-40", -40, 6513.5, false, 9, false));

        // --- Timing & fuel ---
        list.add(new PIDDefinition("Timing Advance", "01", "0E", "deg", "A/2-64", -64, 63.5, true, 1, false));
        list.add(new PIDDefinition("Fuel Inject Timing", "01", "5D", "deg", "(A*256+B)/128-210", -210, 302, true, 2, false));
        list.add(new PIDDefinition("Engine Fuel Rate", "01", "5E", "L/h", "(A*256+B)/20", 0, 3276.75, true, 2, false));

        // --- Load & misc ---
        list.add(new PIDDefinition("Absolute Load", "01", "43", "%", "(A*256+B)*100/255", 0, 25700, false, 2, false));
        list.add(new PIDDefinition("Fuel Level", "01", "2F", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Fuel Type", "01", "51", "", "A", 0, 255, false, 1, false));
        list.add(new PIDDefinition("Ethanol Fuel", "01", "52", "%", "A*100/255", 0, 100, false, 1, false));
        // lpgCritical=true: battery/alternator voltage is essential for LPG tuning —
        // a failing alternator or weak battery causes lean misfire that masquerades as
        // a fuel trim problem. Must be polled in LPG-only mode.
        list.add(new PIDDefinition("Control Module Voltage", "01", "42", "V", "(A*256+B)/1000", 0, 65.535, true, 2, true));

        // --- Extended standardized Mode 01 data ---
        list.add(new PIDDefinition("Commanded EGR", "01", "2C", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("EGR Error", "01", "2D", "%", "(A-128)*100/128", -100, 99.22, false, 1, false));
        list.add(new PIDDefinition("Warm-ups Since DTC Clear", "01", "30", "cycles", "A", 0, 255, false, 1, false));
        list.add(new PIDDefinition("Relative Throttle Position", "01", "45", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Absolute Throttle Position B", "01", "47", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Absolute Throttle Position C", "01", "48", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Accelerator Pedal Position D", "01", "49", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Accelerator Pedal Position E", "01", "4A", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Accelerator Pedal Position F", "01", "4B", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Commanded Throttle Actuator", "01", "4C", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Time With MIL On", "01", "4D", "min", "A*256+B", 0, 65535, false, 2, false));
        list.add(new PIDDefinition("Time Since DTC Cleared", "01", "4E", "min", "A*256+B", 0, 65535, false, 2, false));
        list.add(new PIDDefinition("Relative Accelerator Position", "01", "5A", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Hybrid Battery Remaining Life", "01", "5B", "%", "A*100/255", 0, 100, false, 1, false));
        list.add(new PIDDefinition("Engine Oil Temperature", "01", "5C", "C", "A-40", -40, 215, false, 1, false));
        list.add(new PIDDefinition("Driver Demand Engine Torque", "01", "61", "%", "A-125", -125, 130, false, 1, false));
        list.add(new PIDDefinition("Actual Engine Torque", "01", "62", "%", "A-125", -125, 130, false, 1, false));
        list.add(new PIDDefinition("Engine Reference Torque", "01", "63", "Nm", "A*256+B", 0, 65535, false, 2, false));

        // --- Time & distance ---
        list.add(new PIDDefinition("Run Time Since Start", "01", "1F", "s", "(A*256+B)", 0, 65535, false, 2, false));
        list.add(new PIDDefinition("Distance Since DTC Cleared", "01", "31", "km", "(A*256+B)", 0, 65535, false, 2, false));
        list.add(new PIDDefinition("Distance With MIL On", "01", "21", "km", "(A*256+B)", 0, 65535, false, 2, false));

        // --- Protocol info ---
        list.add(new PIDDefinition("OBD Standards Compliance", "01", "1C", "", "A", 0, 255, false, 1, false));

        // --- EVAP ---
        // SAE J1979 PID 0x32: two's-complement signed 16-bit, 0.25 Pa per bit
        // ("S" = signed 16-bit from bytes A,B in the formula evaluator).
        list.add(new PIDDefinition("EVAP System Vapor Pressure", "01", "32", "Pa", "S/4", -8192, 8191.75, false, 2, false));

        return list;
    }
}
