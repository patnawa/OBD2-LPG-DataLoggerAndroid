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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
