package com.alpha.obd2logger;

import java.util.Random;

public final class SimulationDriver extends BaseDriver {
    private final Random random = new Random();
    private double rpm = 800.0;
    private double map = 30.0;
    private boolean accelerating = true;
    private double coolantTemp = 80.0;

    public enum SimState {
        RESTING,
        CRANKING,
        RUNNING,
        RUNNING_HIGH,
        LOADED,
        RECOVERING
    }

    private SimState simState = SimState.RUNNING;
    private long stateStartTime = 0;

    public SimulationDriver(LoggerConfig config) {
        super(config);
        stateStartTime = System.currentTimeMillis();
    }

    public void setSimState(SimState state) {
        this.simState = state;
        this.stateStartTime = System.currentTimeMillis();
    }

    public SimState getSimState() {
        return this.simState;
    }

    @Override
    public boolean connect() {
        connected = true;
        setSimState(SimState.RUNNING);
        return true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public Double queryPid(PIDDefinition pidDef) {
        String pid = pidDef.getPidHex();
        boolean isStftPseudo = pid.contains("_B");
        if (isStftPseudo) {
            pid = pid.substring(0, pid.indexOf('_'));
        }

        long elapsed = System.currentTimeMillis() - stateStartTime;

        if ("0C".equals(pid)) {
            switch (simState) {
                case RESTING:        return 0.0;
                case CRANKING:
                    if (elapsed < 1000)        return 0.0;
                    else if (elapsed < 2200)   return 180.0 + random.nextInt(60);
                    else if (elapsed < 3500)   { double p = (elapsed - 2200) / 1300.0; return 1200.0 - p * 400.0; }
                    else                       return 800.0 + random.nextDouble() * 30.0;
                case RUNNING_HIGH:   return 2800.0 + random.nextDouble() * 100.0;
                case LOADED:         return 780.0 + random.nextDouble() * 30.0;
                default:
                    if (accelerating) { rpm += 50.0 + random.nextDouble() * 20.0; if (rpm > 5500.0) accelerating = false; }
                    else              { rpm -= 80.0 + random.nextDouble() * 20.0; if (rpm < 800.0)  accelerating = true; }
                    return clamp(rpm, 800.0, 6000.0);
            }
        }

        if ("42".equals(pid)) {
            switch (simState) {
                case RESTING:       return 12.62 + random.nextDouble() * 0.04;
                case CRANKING:
                    if (elapsed < 1000)      return 12.60 + random.nextDouble() * 0.04;
                    else if (elapsed < 2200) return 9.72 + random.nextDouble() * 0.15;
                    else if (elapsed < 3500) { double p = (elapsed - 2200) / 1300.0; return 14.40 - p * 0.25; }
                    else                     return 14.12 + random.nextDouble() * 0.04;
                case RUNNING_HIGH:   return 14.32 + random.nextDouble() * 0.03;
                case LOADED:         return 13.72 + random.nextDouble() * 0.03;
                case RECOVERING:     return 14.06 + random.nextDouble() * 0.03;
                default:             return 14.12 + random.nextDouble() * 0.03;
            }
        }

        if ("0B".equals(pid)) {
            double crpm = simState == SimState.RUNNING_HIGH ? 2800.0
                        : simState == SimState.RESTING ? 0.0
                        : rpm;
            map = 20.0 + (crpm / 6000.0) * 80.0 + random.nextDouble() * 5.0;
            return clamp(map, 20.0, 105.0);
        }
        if ("05".equals(pid)) {
            if (simState == SimState.RESTING) coolantTemp = Math.max(30.0, coolantTemp - 0.2);
            else                              coolantTemp = Math.min(90.0, coolantTemp + 0.1);
            return coolantTemp;
        }
        if ("06".equals(pid)) return random.nextDouble() * 6.0 - 3.0;
        if ("07".equals(pid)) return 2.0 + random.nextDouble() * 3.0;
        if ("34".equals(pid)) return 0.98 + random.nextDouble() * 0.04;

        if ("14".equals(pid) || "18".equals(pid)) {
            if (pidDef.getName().contains("STFT")) return random.nextDouble() * 10.0 - 5.0;
            return clamp(0.45 + (random.nextDouble() - 0.5) * 0.8, 0.05, 0.95);
        }
        if ("15".equals(pid) || "19".equals(pid)) {
            if (pidDef.getName().contains("STFT")) return random.nextDouble() * 6.0 - 3.0;
            return clamp(0.45 + (random.nextDouble() - 0.5) * 0.2, 0.05, 0.95);
        }
        if ("16".equals(pid) || "1A".equals(pid) || "17".equals(pid) || "1B".equals(pid))
            return clamp(0.3 + (random.nextDouble() - 0.5) * 0.1, 0.05, 0.95);

        if ("0D".equals(pid)) {
            if (simState == SimState.RESTING || simState == SimState.CRANKING) return 0.0;
            return clamp((rpm / 6000.0) * 120.0 + random.nextDouble() * 2.0, 0.0, 255.0);
        }
        if ("04".equals(pid)) {
            if (simState == SimState.RESTING)      return 0.0;
            if (simState == SimState.CRANKING)     return 65.0;
            if (simState == SimState.RUNNING_HIGH) return 75.0 + random.nextDouble() * 5.0;
            if (simState == SimState.LOADED)       return 85.0 + random.nextDouble() * 5.0;
            return clamp(15.0 + (rpm / 6000.0) * 45.0 + random.nextDouble() * 5.0, 0.0, 100.0);
        }
        if ("03".equals(pid)) {
            if (simState == SimState.RESTING)          return 0.0;
            if (simState == SimState.CRANKING || coolantTemp < 50.0) return 1.0;
            if (simState == SimState.LOADED)           return 4.0;
            return 2.0;
        }
        if ("0F".equals(pid)) return 35.0 + random.nextDouble() * 1.0;
        if ("46".equals(pid)) return 25.0;
        if ("11".equals(pid)) {
            if (simState == SimState.RESTING)  return 0.0;
            if (simState == SimState.CRANKING) return 15.0;
            return clamp(8.0 + (rpm / 6000.0) * 72.0 + random.nextDouble() * 2.0, 0.0, 100.0);
        }
        if ("10".equals(pid)) {
            if (simState == SimState.RESTING) return 0.0;
            return clamp((rpm * map) / 12000.0, 0.0, 655.35);
        }
        if ("0E".equals(pid)) {
            if (simState == SimState.RESTING) return 0.0;
            return clamp(10.0 + (rpm / 6000.0) * 25.0 + random.nextDouble() * 2.0, -64.0, 63.5);
        }
        if ("2F".equals(pid)) return 55.0;
        if ("51".equals(pid)) return 1.0;
        if ("52".equals(pid)) return 10.0;
        if ("0A".equals(pid)) return simState == SimState.RESTING ? 0.0 : 300.0 + random.nextDouble() * 10.0;
        if ("23".equals(pid)) return simState == SimState.RESTING ? 0.0 : 4000.0 + random.nextDouble() * 100.0;
        if ("33".equals(pid)) return 101.0;  // Barometric (sea-level)
        if ("5D".equals(pid)) return simState == SimState.RESTING ? 0.0 : 2.0;
        if ("5E".equals(pid)) return simState == SimState.RESTING ? 0.0 : (rpm / 1000.0) * 1.2 + random.nextDouble() * 0.2;
        if ("43".equals(pid)) {
            if (simState == SimState.RESTING) return 0.0;
            return clamp(12.0 + (rpm / 6000.0) * 55.0 + random.nextDouble() * 5.0, 0.0, 100.0);
        }
        if ("1F".equals(pid)) return (double) (elapsed / 1000L);
        if ("31".equals(pid)) return 120.0;
        if ("21".equals(pid)) return 0.0;
        if ("08".equals(pid)) return random.nextDouble() * 6.0 - 3.0;
        if ("09".equals(pid)) return 2.0 + random.nextDouble() * 3.0;
        if ("44".equals(pid)) return 0.98 + random.nextDouble() * 0.04;

        // --- DPF simulation (diesel) ---
        if ("7A".equals(pid)) return 15.0 + random.nextDouble() * 5.0;   // Soot Load ~15%
        if ("7B".equals(pid)) return 250.0 + random.nextDouble() * 50.0; // Temperature ~250°C
        if ("85".equals(pid)) return 0.5 + random.nextDouble() * 0.5;    // Delta P ~0.5kPa
        if ("8C".equals(pid)) return 0.0;                                 // Regen idle
        if ("8B".equals(pid)) return 5.0 + random.nextDouble() * 2.0;    // Ash ~5g

        return pidDef.getMinVal() + random.nextDouble() * (pidDef.getMaxVal() - pidDef.getMinVal());
    }

    @Override
    public String sendCommandRaw(String command) {
        if (command == null) return "";
        if (command.startsWith("02") && command.length() >= 4) {
            String pid = command.substring(2, 4);
            switch (pid) {
                case "0C": return "42 0C " + (simState == SimState.RESTING ? "00 00" : "1A F8");
                case "0D": return "42 0D " + (simState == SimState.RESTING ? "00" : "3C");
                case "05": return "42 05 5F";
                case "04": return "42 04 " + (simState == SimState.RESTING ? "00" : "6B");
                case "06": return "42 06 96";
                case "07": return "42 07 8F";
                case "0B": return "42 0B 32";
                case "0F": return "42 0F 46";
                case "42":
                    if (simState == SimState.RESTING)      return "42 42 31 48";
                    else if (simState == SimState.CRANKING) return "42 42 26 14";
                    else if (simState == SimState.LOADED)   return "42 42 35 9A";
                    else                                    return "42 42 37 14";
                default: return "42 " + pid + " 00";
            }
        }
        switch (command) {
            case "03":   return "43 02 01 71 03 00";
            case "07":   return "47 01 00 07 00 00";
            case "0A":   return "4A 01 01 71";
            case "04":   return "44";
            case "0101": return "41 01 82 07 65 04";
            case "0902": return "49 02 01 31 32 33 34 35 36 37 38 39 30 41 42 43 44 45 46 47";
            default:     return "";
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}