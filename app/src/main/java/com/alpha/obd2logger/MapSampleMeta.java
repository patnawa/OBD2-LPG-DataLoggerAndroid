package com.alpha.obd2logger;

import java.util.List;
import java.util.Locale;

/**
 * Shared fuel-map sample enrichment used by live UI ({@link LiveMapStore}),
 * CSV/JSONL logs ({@link DataWriter}) and API/AI endpoints.
 *
 * <p>Always resolves the grid cell for the current sample (right cell for live
 * highlight) including cases that fail closed-loop / temperature gates. AI
 * tools can re-build maps from log columns without re-deriving binning rules.
 */
public final class MapSampleMeta {

    public static final String AXIS_MAP = "MAP";
    public static final String AXIS_LOAD = "LOAD";
    public static final String AXIS_NONE = "NONE";

    public final Double rpm;
    public final Double mapKpa;
    public final Double engineLoad;
    public final Double stft;
    public final Double ltft;
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
    public final boolean gatedEligible; // closed-loop + warm + has rpm/axis
    public final String rejectReason;   // null if gatedEligible

    private MapSampleMeta(Double rpm, Double mapKpa, Double engineLoad,
                          Double stft, Double ltft, Double ect, Double fuelSystemStatus,
                          double loadAxis, String axisSource,
                          int rpmCell, float mapBin, String cellKey,
                          double trimTotal, boolean closedLoop, boolean warmEnough,
                          boolean gatedEligible, String rejectReason) {
        this.rpm = rpm;
        this.mapKpa = mapKpa;
        this.engineLoad = engineLoad;
        this.stft = stft;
        this.ltft = ltft;
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

    /** Build metadata from one DataRecord (sensor table). */
    public static MapSampleMeta from(DataRecord record) {
        if (record == null) {
            return empty("null_record");
        }
        Double rpm = valueByKey(record, "01_0C");
        Double map = valueByKey(record, "01_0B");
        Double load = valueByKey(record, "01_04");
        Double stft = valueByKey(record, "01_06");
        Double ltft = valueByKey(record, "01_07");
        Double ect = valueByKey(record, "01_05");
        Double fuelStatus = valueByKey(record, "01_03");
        return fromValues(rpm, map, load, stft, ltft, ect, fuelStatus);
    }

    /**
     * Build metadata from the legacy pushSample signature (already-combined trim,
     * already-chosen load axis, boolean closed-loop flags).
     */
    public static MapSampleMeta fromLegacy(double rpm, double loadAxis, double trim,
                                           boolean isClosedLoop, Double ect) {
        boolean warmEnough = (ect == null) || (ect >= 80.0);

        String axisSource = AXIS_NONE;
        double axis = Double.NaN;
        if (!Double.isNaN(loadAxis)) {
            axis = loadAxis;
            // Callers already chose the axis; annotate for labels only.
            axisSource = (loadAxis > 100.0) ? AXIS_MAP : AXIS_MAP;
        }
        // Prefer MAP label for legacy path (Live path that cares uses from()).

        int rpmCell = -1;
        float mapBin = -1f;
        String cellKey = "";
        if (!Double.isNaN(rpm) && !Double.isNaN(axis)) {
            rpmCell = MapBinning.binRpm(rpm);
            mapBin = MapBinning.binMap(axis);
            cellKey = MapBinning.cellKey(rpmCell, mapBin);
        }

        String reject = null;
        boolean gated = true;
        if (Double.isNaN(rpm)) {
            gated = false;
            reject = "no_rpm";
        } else if (Double.isNaN(axis)) {
            gated = false;
            reject = "no_axis";
        } else if (!isClosedLoop) {
            gated = false;
            reject = "open_loop";
        } else if (!warmEnough) {
            gated = false;
            reject = "cold_engine";
        }

        Double fuelStatus = isClosedLoop ? 2.0 : 1.0;
        return new MapSampleMeta(
                rpm, axis, null, trim, 0.0, ect, fuelStatus,
                axis, axisSource, rpmCell, mapBin, cellKey,
                trim, isClosedLoop, warmEnough, gated, reject);
    }

    public static MapSampleMeta fromValues(Double rpm, Double map, Double load,
                                           Double stft, Double ltft, Double ect,
                                           Double fuelStatus) {
        boolean closedLoop = true;
        if (fuelStatus != null) {
            closedLoop = (fuelStatus.intValue() & 0x02) != 0;
        }

        // Warm gate: when ECT is present require ≥80°C. Missing ECT still allowed
        // (some vehicles / lpg-critical sets may omit it) but is flagged.
        boolean warmEnough = (ect == null) || (ect >= 80.0);

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
            axisSource = AXIS_MAP;
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
        boolean gated = true;
        if (rpm == null || Double.isNaN(rpm)) {
            gated = false;
            reject = "no_rpm";
        } else if (Double.isNaN(loadAxis)) {
            gated = false;
            reject = "no_axis";
        } else if (!closedLoop) {
            gated = false;
            reject = "open_loop";
        } else if (!warmEnough) {
            gated = false;
            reject = "cold_engine";
        } else if (!hasTrim) {
            gated = false;
            reject = "no_trim";
        }

        return new MapSampleMeta(rpm, map, load, stft, ltft, ect, fuelStatus,
                loadAxis, axisSource, rpmCell, mapBin, cellKey, trim,
                closedLoop, warmEnough, gated, reject);
    }

    private static MapSampleMeta empty(String reason) {
        return new MapSampleMeta(null, null, null, null, null, null, null,
                Double.NaN, AXIS_NONE, -1, -1f, "", 0.0,
                true, true, false, reason);
    }

    private static Double valueByKey(DataRecord record, String key) {
        if (record == null || record.getSamples() == null) return null;
        for (SensorSample s : record.getSamples()) {
            if (key.equals(s.getPidKey())) return s.getValue();
        }
        return null;
    }

    /**
     * Append map AI columns onto a growing sample list for CSV/JSONL.
     * Safe to call every record — values that are unresolvable stay null.
     *
     * @param accepted true if LiveMapStore actually stored this sample (after debounce/lock)
     * @param storeReject reason from store when accepted=false (debounce/locked/gate)
     */
    public void appendLogSamples(List<SensorSample> samples, boolean accepted, String storeReject) {
        if (samples == null) return;
        samples.add(new SensorSample("map_rpm_cell", "Map RPM Cell",
                rpmCell >= 0 ? (double) rpmCell : null, "rpm", "ok"));
        samples.add(new SensorSample("map_axis_value", "Map Axis Value",
                Double.isNaN(loadAxis) ? null : loadAxis,
                AXIS_MAP.equals(axisSource) ? "kPa" : "%", "ok"));
        samples.add(new SensorSample("map_axis_source", "Map Axis Source",
                axisSourceCode(axisSource), "", "ok"));
        // cell key as free-text is not numeric — AI still has rpm cell + axis
        samples.add(new SensorSample("map_trim_total", "Map Trim Total (STFT+LTFT)",
                gatedEligible || "no_trim".equals(rejectReason) ? trimTotal : trimTotal,
                "%", "ok"));
        samples.add(new SensorSample("map_closed_loop", "Map Closed Loop",
                closedLoop ? 1.0 : 0.0, "", "ok"));
        samples.add(new SensorSample("map_warm", "Map Engine Warm",
                warmEnough ? 1.0 : 0.0, "", "ok"));
        samples.add(new SensorSample("map_gated", "Map Gate Eligible",
                gatedEligible ? 1.0 : 0.0, "", "ok"));
        samples.add(new SensorSample("map_accepted", "Map Sample Accepted",
                accepted ? 1.0 : 0.0, "", "ok"));
        samples.add(new SensorSample("map_reject_code", "Map Reject Code",
                rejectCode(accepted ? null : (storeReject != null ? storeReject : rejectReason)),
                "", "ok"));
    }

    /** Numeric code for axis so CSV stays numeric-friendly for AI tools. */
    public static double axisSourceCode(String axisSource) {
        if (AXIS_MAP.equals(axisSource)) return 1.0;
        if (AXIS_LOAD.equals(axisSource)) return 2.0;
        return 0.0;
    }

    /**
     * Compact integer reject reason codes (documented for AI agents):
     * 0=accepted, 1=no_rpm, 2=no_axis未遂, 3=open_loop, 4=cold, 5=no_trim,
     * 6=debounce, 7=locked, 8=null_record, 9=other.
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
            default: return 9.0;
        }
    }

    /** Human-readable cell label for HUD / agent. */
    public String cellLabel() {
        if (cellKey == null || cellKey.isEmpty()) return "—";
        return String.format(Locale.US, "%s (%s)", cellKey, axisSource);
    }
}
