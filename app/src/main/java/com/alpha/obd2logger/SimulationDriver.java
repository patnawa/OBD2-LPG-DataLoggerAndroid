package com.alpha.obd2logger;

import java.util.Random;

public final class SimulationDriver extends BaseDriver {
    private final Random random = new Random();
    private double rpm = 800.0;
    private double map = 30.0;
    private boolean accelerating = true;
    private double coolantTemp = 80.0;

    public SimulationDriver(LoggerConfig config) {
        super(config);
    }

    @Override
    public boolean connect() {
        connected = true;
        return true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public Double queryPid(PIDDefinition pidDef) {
        String pid = pidDef.getPidHex();
        // "_B" pseudo-PIDs (e.g. O2 sensor STFT) share the same OBD2 PID hex as their
        // parent. Strip the suffix so the simulation logic below matches the base PID.
        boolean isStftPseudo = pid.contains("_B");
        if (isStftPseudo) {
            pid = pid.substring(0, pid.indexOf('_'));
        }
        if ("0C".equals(pid)) {
            if (accelerating) {
                rpm += 50.0 + random.nextDouble() * 20.0;
                if (rpm > 5500.0) accelerating = false;
            } else {
                rpm -= 80.0 + random.nextDouble() * 20.0;
                if (rpm < 800.0) accelerating = true;
            }
            return clamp(rpm, 800.0, 6000.0);
        }
        if ("0B".equals(pid)) {
            // MAP correlates with RPM/Load roughly in a simulation
            map = 20.0 + (rpm / 6000.0) * 80.0 + (random.nextDouble() * 5.0);
            return clamp(map, 20.0, 105.0);
        }
        if ("05".equals(pid)) {
            coolantTemp = Math.min(90.0, coolantTemp + 0.1);
            return coolantTemp;
        }
        if ("06".equals(pid)) {
            return random.nextDouble() * 6.0 - 3.0;
        }
        if ("07".equals(pid)) {
            return 2.0 + random.nextDouble() * 3.0;
        }
        if ("34".equals(pid)) {
            return 0.98 + random.nextDouble() * 0.04;
        }
        // --- Control Module Voltage (PID 0x42) ---
        // Simulate alternator charging voltage: ~13.8-14.4V when engine running.
        // A healthy alternator produces 13.8-14.4V; a weak battery/alternator drops below 12.6V.
        if ("42".equals(pid)) {
            return 13.8 + random.nextDouble() * 0.6; // 13.8-14.4V
        }
        // --- Oxygen sensor simulation (PIDs 0x14-0x1B) ---
        // Narrowband O2 sensors swing between ~0.1V (lean) and ~0.9V (rich).
        // Upstream sensors (B1S1, B2S1) oscillate actively; downstream (S2-S4) stay steadier.
        // For STFT PIDs (same PID hex, different formula), return a separate trim value.
        if ("14".equals(pid) || "18".equals(pid)) {
            // Upstream: oscillate around 0.45V with ±0.4V swing
            if (pidDef.getName().contains("STFT")) {
                return random.nextDouble() * 10.0 - 5.0;
            }
            return clamp(0.45 + (random.nextDouble() - 0.5) * 0.8, 0.05, 0.95);
        }
        if ("15".equals(pid) || "19".equals(pid)) {
            // Downstream S2: steadier, sits near 0.45V with small noise
            if (pidDef.getName().contains("STFT")) {
                return random.nextDouble() * 6.0 - 3.0;
            }
            return clamp(0.45 + (random.nextDouble() - 0.5) * 0.2, 0.05, 0.95);
        }
        if ("16".equals(pid) || "1A".equals(pid) || "17".equals(pid) || "1B".equals(pid)) {
            // S3/S4 sensors: rare on most vehicles, return steadier low values
            return clamp(0.3 + (random.nextDouble() - 0.5) * 0.1, 0.05, 0.95);
        }
        return pidDef.getMinVal() + random.nextDouble() * (pidDef.getMaxVal() - pidDef.getMinVal());
    }

    @Override
    public String sendCommandRaw(String command) {
        if (command == null) return "";
        if (command.startsWith("02") && command.length() >= 4) {
            String pid = command.substring(2, 4);
            switch (pid) {
                case "0C": return "42 0C 1A F8"; // RPM: 1726
                case "0D": return "42 0D 3C";    // Speed: 60 km/h
                case "05": return "42 05 5F";    // Coolant: 55 C
                case "04": return "42 04 6B";    // Load: 42%
                case "06": return "42 06 96";    // STFT B1: +17.2%
                case "07": return "42 07 8F";    // LTFT B1: +11.7%
                case "0B": return "42 0B 32";    // MAP: 50 kPa
                case "0F": return "42 0F 46";    // IAT: 30 C
                case "42": return "42 42 37 14"; // Battery: (0x37=55, 0x14=20) → (55*256+20)/1000 = 14.10V
                default: return "42 " + pid + " 00";
            }
        }
        switch (command) {
            case "03":
                // Mode 03 (stored codes): returns P0171 (System too lean Bank 1) and P0300 (Random misfire)
                return "43 02 01 71 03 00";
            case "07":
                // Mode 07 (pending codes): returns P0301 (Cylinder 1 misfire)
                return "47 01 03 01";
            case "0A":
                // Mode 0A (permanent codes): returns P0171 (System too lean Bank 1)
                return "4A 01 01 71";
            case "04":
                // Mode 04 (clear codes)
                return "44";
            case "0101":
                // Mode 01 PID 01 (readiness status)
                // MIL on, 2 DTCs, various readiness monitors complete
                return "41 01 82 07 65 04";
            case "0902":
                // Mode 09 PID 02 (VIN): returns "1234567890ABCDEFG" in hex
                return "49 02 01 31 32 33 34 35 36 37 38 39 30 41 42 43 44 45 46 47";
            default:
                return "";
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
