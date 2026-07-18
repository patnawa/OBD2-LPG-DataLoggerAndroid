package com.alpha.obd2logger;

import java.util.List;
import java.util.Locale;

/**
 * Shared fuel-map sample enrichment used by the live UI, CSV/JSONL logs and
 * API/AI endpoints.
 *
 * <p>The current grid cell is resolved even when a sample fails a safety gate,
 * so the UI can keep its cursor responsive without learning an unsafe tune
 * correction.</p>
 */
public final class MapSampleMeta {

    public static final String AXIS_MAP = "MAP";
    public static final String AXIS_SYNTH_MAP = "SYNTH_MAP";
    public static final String AXIS_LOAD = "LOAD";
    public static final String AXIS_NONE = "NONE";

    /**
     * True when two axis sources may be compared or accumulated together.
     *
     * <p>Deliberately strict. {@link #AXIS_SYNTH_MAP} shares the kPa grid with
     * {@link #AXIS_MAP} but is computed from engine load
     * ({@code 30 + (baro-30)*load/100}), i.e. an estimate rather than a reading.
     * Binning measured trim against an estimated axis files samples into cells
     * they may not belong to, so the two are never merged — the map would
     * degrade silently and nothing downstream could tell.
     *
     * <p>{@link #AXIS_NONE} means unknown and never matches a known axis.
     */
    public static boolean axesCompatible(String a, String b) {
        if (a == null || b == null) return false;
        if (AXIS_NONE.equals(a) || AXIS_NONE.equals(b)) return false;
        return a.equals(b);
    }

    public final Double rpm;
    public final Double mapKpa;
    public final Double engineLoad;
    public final Double stft;
    public final Double ltft;
    public final Double lambda;
    public final Double commandedLambda;
    public final Double throttle;
    public final Double ect;
    public final Double fuelSystemStatus;

    public final double loadAxis;
    public final String axisSource;
    public final int rpmCell;
    public final float mapBin;
    public final String cellKey;
    public final double trimTotal;
    public final boolean closedLoop;
    public final boolean warmEnough;
    public final boolean gatedEligible;
    public final String rejectReason;

    private MapSampleMeta(Double rpm, Double mapKpa, Double engineLoad,
                          Double stft, Double ltft, Double lambda, Double commandedLambda,
                          Double throttle, Double ect, Double fuelSystemStatus,
                          double loadAxis, String axisSource,
                          int rpmCell, float mapBin, String cellKey,
                          double trimTotal, boolean closedLoop, boolean warmEnough,
                          boolean gatedEligible, String rejectReason) {
        this.rpm = rpm;
        this.mapKpa = mapKpa;
        this.engineLoad = engineLoad;
        this.stft = stft;
        this.ltft = ltft;
        this.lambda = lambda;
        this.commandedLambda = commandedLambda;
        this.throttle = throttle;
        this.ect = ect;
        this.fuelSystemStatus = fuelSystemStatus;
        this.loadAxis = loadAxis;
        this.axisSource = axisSource;
        this.rpmCell = rpmCell;
        this.mapBin = mapBin;
        this.cellKey = cellKey;
        this.trimTotal = trimTotal;
        this.closedLoop = closedLoop;
        this.warmEnough = warmEnough;
        this.gatedEligible = gatedEligible;
        this.rejectReason = rejectReason;
    }

    /** Build metadata from one DataRecord and preserve MAP provenance. */
    public static MapSampleMeta from(DataRecord record) {
        if (record == null) return empty("null_record");

        SensorSample mapSample = sampleByKey(record, "01_0B");
        boolean synthesizedMap = mapSample != null
                && ("synth".equalsIgnoreCase(mapSample.getStatus())
                || "synthesized".equalsIgnoreCase(mapSample.getStatus()));
        return fromValues(
                valueByKey(record, "01_0C"),
                mapSample != null ? mapSample.getValue() : null,
                valueByKey(record, "01_04"),
                valueByKey(record, "01_06"),
                valueByKey(record, "01_07"),
                valueByKey(record, "01_34"),
                valueByKey(record, "01_44"),
                valueByKey(record, "01_11"),
                valueByKey(record, "01_05"),
                valueByKey(record, "01_03"),
                synthesizedMap);
    }

    /**
     * Compatibility entry for callers that already combined trim and selected
     * an axis. A missing ECT remains allowed here because the old signature
     * cannot distinguish unsupported ECT from omitted ECT.
     */
    public static MapSampleMeta fromLegacy(double rpm, double loadAxis, double trim,
                                           boolean isClosedLoop, Double ect) {
        boolean warmEnough = ect == null || ect >= 80.0;
        double axis = Double.isNaN(loadAxis) ? Double.NaN : loadAxis;
        String axisSource = Double.isNaN(axis) ? AXIS_NONE : AXIS_MAP;

        int rpmCell = -1;
        float mapBin = -1f;
        String cellKey = "";
        if (!Double.isNaN(rpm) && !Double.isNaN(axis)) {
            rpmCell = MapBinning.binRpm(rpm);
            mapBin = MapBinning.binMap(axis);
            cellKey = MapBinning.cellKey(rpmCell, mapBin);
        }

        String reject = null;
        if (Double.isNaN(rpm)) reject = "no_rpm";
        else if (Double.isNaN(axis)) reject = "no_axis";
        else if (!isClosedLoop) reject = "open_loop";
        else if (!warmEnough) reject = "cold_engine";

        Double fuelStatus = isClosedLoop ? 2.0 : 1.0;
        return new MapSampleMeta(rpm, axis, null, trim, 0.0,
                null, null, null, ect, fuelStatus,
                axis, axisSource, rpmCell, mapBin, cellKey, trim,
                isClosedLoop, warmEnough, reject == null, reject);
    }

    public static MapSampleMeta fromValues(Double rpm, Double map, Double load,
                                           Double stft, Double ltft, Double ect,
                                           Double fuelStatus) {
        return fromValues(rpm, map, load, stft, ltft,
                null, null, null, ect, fuelStatus, false);
    }

    private static MapSampleMeta fromValues(Double rpm, Double map, Double load,
                                            Double stft, Double ltft,
                                            Double lambda, Double commandedLambda, Double throttle,
                                            Double ect,
                                            Double fuelStatus, boolean synthesizedMap) {
        boolean closedLoop = fuelStatus != null && (fuelStatus.intValue() & 0x02) != 0;
        boolean warmEnough = ect != null && ect >= 80.0;

        double trim = 0.0;
        boolean hasTrim = false;
        if (stft != null) {
            trim = stft + (ltft != null ? ltft : 0.0);
            hasTrim = true;
        } else if (ltft != null) {
            trim = ltft;
            hasTrim = true;
        }

        String axisSource = AXIS_NONE;
        double loadAxis = Double.NaN;
        if (map != null && !Double.isNaN(map)) {
            loadAxis = map;
            axisSource = synthesizedMap ? AXIS_SYNTH_MAP : AXIS_MAP;
        } else if (load != null && !Double.isNaN(load)) {
            loadAxis = load;
            axisSource = AXIS_LOAD;
        }

        int rpmCell = -1;
        float mapBin = -1f;
        String cellKey = "";
        if (rpm != null && !Double.isNaN(rpm) && !Double.isNaN(loadAxis)) {
            rpmCell = MapBinning.binRpm(rpm);
            mapBin = MapBinning.binMap(loadAxis);
            cellKey = MapBinning.cellKey(rpmCell, mapBin);
        }

        String reject = null;
        if (rpm == null || Double.isNaN(rpm)) reject = "no_rpm";
        else if (Double.isNaN(loadAxis)) reject = "no_axis";
        else if (fuelStatus == null) reject = "no_fuel_status";
        else if (!closedLoop) reject = "open_loop";
        else if (ect == null) reject = "no_coolant";
        else if (!warmEnough) reject = "cold_engine";
        else if (!hasTrim) reject = "no_trim";

        return new MapSampleMeta(rpm, map, load, stft, ltft,
                lambda, commandedLambda, throttle, ect, fuelStatus,
                loadAxis, axisSource, rpmCell, mapBin, cellKey, trim,
                closedLoop, warmEnough, reject == null, reject);
    }

    private static MapSampleMeta empty(String reason) {
        return new MapSampleMeta(null, null, null, null, null,
                null, null, null, null, null,
                Double.NaN, AXIS_NONE, -1, -1f, "", 0.0,
                false, false, false, reason);
    }

    private static Double valueByKey(DataRecord record, String key) {
        SensorSample sample = sampleByKey(record, key);
        return sample != null ? sample.getValue() : null;
    }

    private static SensorSample sampleByKey(DataRecord record, String key) {
        if (record == null || record.getSamples() == null) return null;
        for (SensorSample sample : record.getSamples()) {
            if (key.equals(sample.getPidKey())) return sample;
        }
        return null;
    }

    /** Append numeric, provenance-aware map columns to CSV/JSONL samples. */
    public void appendLogSamples(List<SensorSample> samples, boolean accepted, String storeReject) {
        if (samples == null) return;
        samples.add(new SensorSample("map_rpm_cell", "Map RPM Cell",
                rpmCell >= 0 ? (double) rpmCell : null, "rpm", "ok"));
        samples.add(new SensorSample("map_axis_value", "Map Axis Value",
                Double.isNaN(loadAxis) ? null : loadAxis,
                isMapAxis(axisSource) ? "kPa" : "%", "ok"));
        samples.add(new SensorSample("map_axis_source", "Map Axis Source",
                axisSourceCode(axisSource), "", "ok"));

        boolean hasTrim = stft != null || ltft != null;
        samples.add(new SensorSample("map_trim_total", "Map Trim Total (STFT+LTFT)",
                hasTrim ? trimTotal : null, "%", hasTrim ? "ok" : "unavailable"));
        samples.add(new SensorSample("map_lambda", "Map Measured Lambda",
                finite(lambda) ? lambda : null, "", finite(lambda) ? "measured" : "unavailable"));
        samples.add(new SensorSample("map_commanded_lambda", "Map Commanded Lambda",
                finite(commandedLambda) ? commandedLambda : null, "",
                finite(commandedLambda) ? "commanded" : "unavailable"));
        Double lambdaError = finite(lambda) && finite(commandedLambda)
                ? lambda - commandedLambda : null;
        samples.add(new SensorSample("map_lambda_error", "Map Lambda Error",
                lambdaError, "", lambdaError != null ? "ok" : "unavailable"));
        samples.add(new SensorSample("map_closed_loop", "Map Closed Loop",
                fuelSystemStatus != null ? (closedLoop ? 1.0 : 0.0) : null,
                "", fuelSystemStatus != null ? "ok" : "unavailable"));
        samples.add(new SensorSample("map_warm", "Map Engine Warm",
                ect != null ? (warmEnough ? 1.0 : 0.0) : null,
                "", ect != null ? "ok" : "unavailable"));
        samples.add(new SensorSample("map_gated", "Map Gate Eligible",
                gatedEligible ? 1.0 : 0.0, "", "ok"));
        samples.add(new SensorSample("map_accepted", "Map Sample Accepted",
                accepted ? 1.0 : 0.0, "", "ok"));
        samples.add(new SensorSample("map_reject_code", "Map Reject Code",
                rejectCode(accepted ? null : (storeReject != null ? storeReject : rejectReason)),
                "", "ok"));
    }

    /** 0=none, 1=measured MAP, 2=load, 3=synthesized MAP. */
    public static double axisSourceCode(String axisSource) {
        if (AXIS_MAP.equals(axisSource)) return 1.0;
        if (AXIS_LOAD.equals(axisSource)) return 2.0;
        if (AXIS_SYNTH_MAP.equals(axisSource)) return 3.0;
        return 0.0;
    }

    private static boolean isMapAxis(String axisSource) {
        return AXIS_MAP.equals(axisSource) || AXIS_SYNTH_MAP.equals(axisSource);
    }

    private static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    /**
     * 0=accepted, 1=no_rpm, 2=no_axis, 3=open_loop, 4=cold, 5=no_trim,
     * 6=debounce, 7=locked, 8=null_record, 9=no_coolant,
     * 10=no_fuel_status, 11=axis_mismatch, 12=transient,
     * 13=lambda_unstable, 14=trim_unstable, 15=non_finite_trim, 99=other.
     */
    public static double rejectCode(String reason) {
        if (reason == null || reason.isEmpty()) return 0.0;
        switch (reason) {
            case "no_rpm": return 1.0;
            case "no_axis": return 2.0;
            case "open_loop": return 3.0;
            case "cold_engine": return 4.0;
            case "no_trim": return 5.0;
            case "debounce": return 6.0;
            case "locked": return 7.0;
            case "null_record": return 8.0;
            case "no_coolant": return 9.0;
            case "no_fuel_status": return 10.0;
            case "axis_mismatch": return 11.0;
            case "transient": return 12.0;
            case "lambda_unstable": return 13.0;
            case "trim_unstable": return 14.0;
            case "non_finite_trim": return 15.0;
            default: return 99.0;
        }
    }

    public String cellLabel() {
        if (cellKey == null || cellKey.isEmpty()) return "—";
        return String.format(Locale.US, "%s (%s)", cellKey, axisSource);
    }
}
