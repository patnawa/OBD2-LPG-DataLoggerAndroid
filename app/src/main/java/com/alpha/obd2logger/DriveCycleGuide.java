package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides drive cycle guidance for incomplete readiness monitors.
 *
 * After clearing DTCs or disconnecting the battery, the OBD2 readiness
 * monitors need to complete before the vehicle can pass an emissions test.
 * This class tells the user exactly how to drive to complete each monitor.
 *
 * Based on SAE J1979 drive cycle requirements and EPA IM240 guidance.
 */
public final class DriveCycleGuide {

    private DriveCycleGuide() {}

    /**
     * A single step in the drive cycle for a specific monitor.
     */
    public static final class DriveCycleStep {
        public final String monitorName;
        public final String instruction;
        public final int estimatedMinutes;

        public DriveCycleStep(String monitorName, String instruction, int estimatedMinutes) {
            this.monitorName = monitorName;
            this.instruction = instruction;
            this.estimatedMinutes = estimatedMinutes;
        }
    }

    /**
     * Generate drive cycle guidance for all incomplete monitors.
     *
     * @param readiness the ReadinessMonitor from PID 01
     * @return list of steps to complete, or empty if all monitors are ready
     */
    public static List<DriveCycleStep> getGuidance(ReadinessMonitor readiness) {
        List<DriveCycleStep> steps = new ArrayList<>();
        if (readiness == null) return steps;

        for (ReadinessMonitor.MonitorStatus m : readiness.getMonitors()) {
            if (!m.available || m.complete) continue;

            String instruction = getInstruction(m.name, readiness.isDiesel());
            if (instruction != null) {
                int minutes = getEstimatedMinutes(m.name);
                steps.add(new DriveCycleStep(m.name, instruction, minutes));
            }
        }
        return steps;
    }

    /**
     * Get a summary string for UI display.
     */
    public static String getSummary(ReadinessMonitor readiness) {
        List<DriveCycleStep> steps = getGuidance(readiness);
        if (steps.isEmpty()) {
            return "All monitors ready — vehicle can pass emissions inspection.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(steps.size()).append(" monitor(s) need completion:\n\n");
        for (DriveCycleStep step : steps) {
            sb.append("  ").append(step.monitorName).append(" (~")
              .append(step.estimatedMinutes).append(" min):\n")
              .append("    ").append(step.instruction).append("\n\n");
        }
        return sb.toString();
    }

    private static String getInstruction(String monitorName, boolean isDiesel) {
        if (monitorName == null) return null;

        switch (monitorName) {
            case "Misfire":
                return "Drive at steady 48-64 km/h for 5 min, then decelerate to 0 without braking for 10s. Repeat 3 times.";

            case "Fuel System":
                return "Cold start (engine off >8h), idle 2 min, then drive at 48-80 km/h for 10 min with steady throttle.";

            case "Components":
                return "Complete a full drive cycle: cold start → idle → accelerate → cruise → decelerate → idle. 10 min total.";

            case "Catalyst":
                return "Drive at steady 64-80 km/h on level road for 5-8 min. Avoid sudden throttle changes.";

            case "Heated Catalyst":
                return "Cold start, idle 90s, then drive at 48-64 km/h for 5 min. Catalyst must reach operating temp.";

            case "EVAP":
                return "Park vehicle 6-8 hours (cold soak), then start and drive at 48-96 km/h within 5 min. Avoid refueling before test.";

            case "Secondary Air":
                return "Cold start (engine off >8h). Within 30s of start, idle 2 min. SAI pump should activate during cold start.";

            case "O2 Sensor":
                return "Drive at 48-64 km/h for 3-5 min, then decelerate to 0 with throttle closed for 10s. Repeat twice.";

            case "O2 Sensor Heater":
                return "Cold start (engine off >8h). Idle 2 min, then drive at 48-64 km/h for 3 min. Heater must warm up O2 sensor.";

            case "EGR/VVT System":
                return "Drive at 48-80 km/h for 5 min with moderate load. EGR must cycle between open/closed positions.";

            // Diesel-specific monitors
            case "NMHC Catalyst":
                return "Drive at 64-80 km/h for 10-15 min with moderate load. Catalyst must reach operating temperature.";

            case "NOx Catalyst":
                return "Drive at 64-96 km/h for 10 min. Perform 2-3 accelerations from 48 to 80 km/h.";

            case "Boost Pressure":
                return "Accelerate from 32 to 80 km/h at full throttle, then maintain 80 km/h for 3 min. Repeat twice.";

            case "exhaust Gas Sensor":
                return "Drive at 48-80 km/h for 5 min. Perform 2 decelerations from 80 to 32 km/h with throttle closed.";

            case "PM Filter Active":
                return "Drive at 80-100 km/h for 15-20 min to initiate DPF regeneration. Avoid short trips.";

            case "exhaust Gas Temp":
                return "Drive at 64-80 km/h for 10 min with moderate load to reach exhaust temperature threshold.";

            default:
                return "Complete a full drive cycle: cold start → idle → accelerate → cruise → decelerate. 10-15 min total.";
        }
    }

    private static int getEstimatedMinutes(String monitorName) {
        if (monitorName == null) return 10;
        switch (monitorName) {
            case "Misfire":          return 15;
            case "Fuel System":      return 12;
            case "Components":       return 10;
            case "Catalyst":         return 8;
            case "Heated Catalyst":   return 7;
            case "EVAP":             return 15;
            case "Secondary Air":     return 5;
            case "O2 Sensor":         return 10;
            case "O2 Sensor Heater":  return 5;
            case "EGR/VVT System":   return 5;
            case "NMHC Catalyst":    return 15;
            case "NOx Catalyst":      return 10;
            case "Boost Pressure":   return 10;
            case "exhaust Gas Sensor": return 8;
            case "PM Filter Active": return 20;
            case "exhaust Gas Temp": return 10;
            default:                 return 10;
        }
    }
}
