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
        setSimState(SimState.RESTING); // Start as resting on connect
        return true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public Double queryPid(PIDDefinition pidDef) {
        String pid = pidDef.getPidHex();
        // "_B" pseudo-PIDs share the same OBD2 PID hex as their parent.
        boolean isStftPseudo = pid.contains("_B");
        if (isStftPseudo) {
            pid = pid.substring(0, pid.indexOf('_'));
        }

        long elapsed = System.currentTimeMillis() - stateStartTime;

        // RPM simulation based on SimState
        if ("0C".equals(pid)) {
            switch (simState) {
                case RESTING:
                    return 0.0;
                case CRANKING:
                    if (elapsed < 1000) {
                        return 0.0;
                    } else if (elapsed < 2200) {
                        return 180.0 + random.nextInt(60);
                    } else if (elapsed < 3500) {
                        double progress = (elapsed - 2200) / 1300.0;
                        return 1200.0 - progress * 400.0;
                    } else {
                        return 800.0 + random.nextDouble() * 30.0;
                    }
                case RUNNING_HIGH:
                    return 2800.0 + random.nextDouble() * 100.0;
                case LOADED:
                    return 780.0 + random.nextDouble() * 30.0;
                case RECOVERING:
                case RUNNING:
                default:
                    // Regular driving simulation if running
                    if (accelerating) {
                        rpm += 50.0 + random.nextDouble() * 20.0;
                        if (rpm > 5500.0) accelerating = false;
                    } else {
                        rpm -= 80.0 + random.nextDouble() * 20.0;
                        if (rpm < 800.0) accelerating = true;
                    }
                    return clamp(rpm, 800.0, 6000.0);
            }
        }

        // Control Module Voltage (PID 0x42) based on SimState
        if ("42".equals(pid)) {
            switch (simState) {
                case RESTING:
                    return 12.62 + random.nextDouble() * 0.04; // 12.62 - 12.66 V
                case CRANKING:
                    if (elapsed < 1000) {
                        return 12.60 + random.nextDouble() * 0.04;
                    } else if (elapsed < 2200) {
                        return 9.72 + random.nextDouble() * 0.15; // Cranking voltage dip
                    } else if (elapsed < 3500) {
                        double progress = (elapsed - 2200) / 1300.0;
                        return 14.40 - progress * 0.25; // overshoot to charging
                    } else {
                        return 14.12 + random.nextDouble() * 0.04;
                    }
                case RUNNING_HIGH:
                    return 14.32 + random.nextDouble() * 0.03; // Stable regulation ~14.3V
                case LOADED:
                    return 13.72 + random.nextDouble() * 0.03; // Voltage drop ~13.7V
                case RECOVERING:
                    return 14.06 + random.nextDouble() * 0.03; // Recovered voltage ~14.06V
                case RUNNING:
                default:
                    return 14.12 + random.nextDouble() * 0.03; // Normal charging ~14.1V
            }
        }

        if ("0B".equals(pid)) {
            double currentRpm = 800.0;
            if (simState == SimState.RUNNING_HIGH) currentRpm = 2800.0;
            else if (simState == SimState.RESTING) currentRpm = 0.0;
            map = 20.0 + (currentRpm / 6000.0) * 80.0 + (random.nextDouble() * 5.0);
            return clamp(map, 20.0, 105.0);
        }
        if ("05".equals(pid)) {
            if (simState == SimState.RESTING) {
                coolantTemp = Math.max(30.0, coolantTemp - 0.2);
            } else {
                coolantTemp = Math.min(90.0, coolantTemp + 0.1);
            }
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

        // Oxygen sensor simulation (PIDs 0x14-0x1B)
        if ("14".equals(pid) || "18".equals(pid)) {
            if (pidDef.getName().contains("STFT")) {
                return random.nextDouble() * 10.0 - 5.0;
            }
            return clamp(0.45 + (random.nextDouble() - 0.5) * 0.8, 0.05, 0.95);
        }
        if ("15".equals(pid) || "19".equals(pid)) {
            if (pidDef.getName().contains("STFT")) {
                return random.nextDouble() * 6.0 - 3.0;
            }
            return clamp(0.45 + (random.nextDouble() - 0.5) * 0.2, 0.05, 0.95);
        }
        if ("16".equals(pid) || "1A".equals(pid) || "17".equals(pid) || "1B".equals(pid)) {
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
                case "0C": return "42 0C " + (simState == SimState.RESTING ? "00 00" : "1A F8");
                case "0D": return "42 0D " + (simState == SimState.RESTING ? "00" : "3C");
                case "05": return "42 05 5F";
                case "04": return "42 04 " + (simState == SimState.RESTING ? "00" : "6B");
                case "06": return "42 06 96";
                case "07": return "42 07 8F";
                case "0B": return "42 0B 32";
                case "0F": return "42 0F 46";
                case "42": 
                    if (simState == SimState.RESTING) {
                        return "42 42 31 48"; // 12.616 V
                    } else if (simState == SimState.CRANKING) {
                        return "42 42 26 14"; // 9.748 V
                    } else if (simState == SimState.LOADED) {
                        return "42 42 35 9A"; // 13.722 V
                    } else {
                        return "42 42 37 14"; // 14.100 V
                    }
                default: return "42 " + pid + " 00";
            }
        }
        switch (command) {
            case "03":
                return "43 02 01 71 03 00";
            case "07":
                return "41 01 00 07";
            case "0A":
                return "4A 01 01 71";
            case "04":
                return "44";
            case "0101":
                return "41 01 82 07 65 04";
            case "0902":
                return "49 02 01 31 32 33 34 35 36 37 38 39 30 41 42 43 44 45 46 47";
            default:
                return "";
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
