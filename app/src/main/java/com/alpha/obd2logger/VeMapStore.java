package com.alpha.obd2logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learned Volumetric Efficiency (VE) surface, binned on the same RPM×MAP grid
 * as {@link LiveMapStore}.
 *
 * <p>Where {@link LiveMapStore} accumulates fuel <em>trim</em> per operating
 * point, this accumulates measured <em>VE</em> per operating point, per fuel
 * side (petrol / gaseous). The instantaneous VE scalar already exists in
 * {@link AdvancedAirDensity#volumetricEfficiency}; this class turns that
 * throwaway number into a persisted, confidence-weighted breathing surface.</p>
 *
 * <h3>Why per-fuel — the headline diagnostic</h3>
 * LPG/CNG is gaseous and physically displaces intake air that liquid petrol
 * does not (the same effect modelled by {@link AdvancedAirDensity#vaporDisplacementFraction}).
 * So gaseous VE sits measurably <em>below</em> petrol VE at the same cell. The
 * magnitude and, crucially, the <em>drift over time</em> of that petrol−LPG
 * ΔVE gap is a fingerprint of mixer / vaporizer / injector health — a gap that
 * grows in specific load cells points at a failing gaseous fuel system before
 * the ECU ever throws a mixture DTC.
 *
 * <h3>Gating</h3>
 * VE is only physically meaningful in quasi-steady state — MAF lags badly on
 * throttle tip-in — so a transient gate plus a 2-in-window debounce mirror
 * {@link LiveMapStore}'s discipline. Unlike the trim map, VE does <em>not</em>
 * require closed loop: the most interesting VE cells (peak volumetric
 * efficiency) live at wide-open throttle in open loop. Gating is therefore on
 * cell validity, a warm engine, axis consistency and steadiness only.
 *
 * <h3>Thread safety</h3>
 * Writes are {@code synchronized}; reads use {@link ConcurrentHashMap} plus
 * defensive copies, exactly like {@link LiveMapStore}.
 */
public final class VeMapStore {

    /** Plausible VE band; outside this the sample is sensor noise or a wrong displacement. */
    static final double VE_MIN = 10.0;
    static final double VE_MAX = 200.0;

    /** Sample count at which a cell is mature (confidence 1.0 before spread penalty). */
    static final int MATURE_HITS = 12;

    /** Both sides need at least this many hits before a ΔVE comparison is trusted. */
    static final int MIN_COMPARE_HITS = 3;

    // Transient thresholds — deliberately identical to LiveMapStore's stability
    // gate so the two maps reject the same tip-in / load-step samples.
    private static final double MAX_RPM_STEP = 400.0;
    private static final double MAX_AXIS_STEP = 15.0;
    private static final double MAX_THROTTLE_STEP = 10.0;

    /**
     * One learned grid cell: a bounded-memory mean of VE with a variance and a
     * maturity count. Uses a running average until {@link #MATURE_HITS}, then a
     * bounded-memory EWMA so the surface tracks slow drift (vaporizer aging, MAF
     * fouling) instead of freezing on early-session data.
     */
    public static final class VeCell {
        private double mean;
        private double varEwma;
        private double min = Double.NaN;
        private double max = Double.NaN;
        private int count;
        private volatile long lastUpdateMs;

        public VeCell() {
        }

        synchronized void add(double ve) {
            if (count == 0) {
                mean = ve;
                varEwma = 0.0;
                min = ve;
                max = ve;
            } else {
                double alpha = count < MATURE_HITS ? 1.0 / (count + 1) : 1.0 / MATURE_HITS;
                double delta = ve - mean;
                mean += alpha * delta;
                // West's incremental EWMA variance estimator.
                varEwma = (1.0 - alpha) * (varEwma + alpha * delta * delta);
                if (ve < min) min = ve;
                if (ve > max) max = ve;
            }
            count++;
            lastUpdateMs = System.currentTimeMillis();
        }

        public synchronized double getVe() {
            return mean;
        }

        public synchronized int getCount() {
            return count;
        }

        public synchronized double getStdDev() {
            return Math.sqrt(Math.max(0.0, varEwma));
        }

        public synchronized double getMin() {
            return Double.isNaN(min) ? 0.0 : min;
        }

        public synchronized double getMax() {
            return Double.isNaN(max) ? 0.0 : max;
        }

        public long getLastUpdateMs() {
            return lastUpdateMs;
        }

        /** 0..1 maturity score minus a spread penalty — same shape as TrimData. */
        public synchronized double getConfidence() {
            double hitScore = Math.min(1.0, count / (double) MATURE_HITS);
            double spreadPenalty = Math.min(0.5, getStdDev() / 20.0);
            return Math.max(0.0, hitScore - spreadPenalty);
        }

        synchronized VeCell copy() {
            VeCell c = new VeCell();
            c.mean = mean;
            c.varEwma = varEwma;
            c.min = min;
            c.max = max;
            c.count = count;
            c.lastUpdateMs = lastUpdateMs;
            return c;
        }

        synchronized void setFromImport(double ve, int hits, double stdDev) {
            this.mean = Double.isFinite(ve) ? ve : 0.0;
            this.count = Math.max(0, hits);
            this.varEwma = Double.isFinite(stdDev) ? stdDev * stdDev : 0.0;
            this.min = this.mean;
            this.max = this.mean;
            this.lastUpdateMs = System.currentTimeMillis();
        }
    }

    /** Outcome of a push attempt — parallels {@link LiveMapStore.PushResult}. */
    public static final class VePushResult {
        public final boolean accepted;
        public final String reason;   // null when accepted
        public final String cellKey;
        public final boolean gaseous;
        public final double ve;

        VePushResult(boolean accepted, String reason, String cellKey,
                     boolean gaseous, double ve) {
            this.accepted = accepted;
            this.reason = reason;
            this.cellKey = cellKey != null ? cellKey : "";
            this.gaseous = gaseous;
            this.ve = ve;
        }
    }

    /** Immutable, deep-copied read model for UI / API / SSE. */
    public static final class VeSnapshot {
        private final Map<String, VeCell> petrol;
        private final Map<String, VeCell> lpg;
        private final long snapshotMs;
        private final String lastCellKey;
        private final int totalAccepted;
        private final String petrolAxisSource;
        private final String lpgAxisSource;

        VeSnapshot(Map<String, VeCell> petrol, Map<String, VeCell> lpg,
                   long snapshotMs, String lastCellKey, int totalAccepted,
                   String petrolAxisSource, String lpgAxisSource) {
            Map<String, VeCell> p = new HashMap<>();
            for (Map.Entry<String, VeCell> e : petrol.entrySet()) {
                p.put(e.getKey(), e.getValue().copy());
            }
            Map<String, VeCell> l = new HashMap<>();
            for (Map.Entry<String, VeCell> e : lpg.entrySet()) {
                l.put(e.getKey(), e.getValue().copy());
            }
            this.petrol = Collections.unmodifiableMap(p);
            this.lpg = Collections.unmodifiableMap(l);
            this.snapshotMs = snapshotMs;
            this.lastCellKey = lastCellKey;
            this.totalAccepted = totalAccepted;
            this.petrolAxisSource = normalizeAxis(petrolAxisSource);
            this.lpgAxisSource = normalizeAxis(lpgAxisSource);
        }

        public Map<String, VeCell> getPetrolData() {
            return petrol;
        }

        public Map<String, VeCell> getLpgData() {
            return lpg;
        }

        public long getSnapshotMs() {
            return snapshotMs;
        }

        public String getLastCellKey() {
            return lastCellKey;
        }

        public int getTotalAccepted() {
            return totalAccepted;
        }

        public String getPetrolAxisSource() {
            return petrolAxisSource;
        }

        public String getLpgAxisSource() {
            return lpgAxisSource;
        }

        public boolean isComparisonAxisCompatible() {
            return MapSampleMeta.axesCompatible(petrolAxisSource, lpgAxisSource);
        }

        private static boolean mature(VeCell c) {
            return c != null && c.getCount() >= MIN_COMPARE_HITS;
        }

        /** Cells where BOTH fuels have a trustworthy VE measurement. */
        public int getOverlappingCellCount() {
            if (!isComparisonAxisCompatible()) return 0;
            int n = 0;
            for (String key : petrol.keySet()) {
                if (mature(petrol.get(key)) && mature(lpg.get(key))) n++;
            }
            return n;
        }

        /**
         * Mean petrol−LPG VE loss (percentage points) across overlapping cells.
         * Positive is the expected direction: petrol breathes better than LPG.
         */
        public double getAveragePetrolMinusLpg() {
            if (!isComparisonAxisCompatible()) return 0.0;
            double sum = 0.0;
            int n = 0;
            for (String key : petrol.keySet()) {
                VeCell p = petrol.get(key);
                VeCell l = lpg.get(key);
                if (mature(p) && mature(l)) {
                    sum += p.getVe() - l.getVe();
                    n++;
                }
            }
            return n == 0 ? 0.0 : sum / n;
        }

        /** Cell key of the largest |petrol−LPG| VE gap, or null. */
        public String getMaxLossCell() {
            if (!isComparisonAxisCompatible()) return null;
            double maxAbs = -1.0;
            String maxKey = null;
            for (String key : petrol.keySet()) {
                VeCell p = petrol.get(key);
                VeCell l = lpg.get(key);
                if (mature(p) && mature(l)) {
                    double gap = Math.abs(p.getVe() - l.getVe());
                    if (gap > maxAbs) {
                        maxAbs = gap;
                        maxKey = key;
                    }
                }
            }
            return maxKey;
        }

        private static String normalizeAxis(String source) {
            return source != null && !source.isEmpty() ? source : MapSampleMeta.AXIS_NONE;
        }
    }

    /**
     * Sliding-window debounce — a cell is accepted only after it is seen at
     * least twice in the window, filtering one-off transit during ramps.
     * (Structurally identical to {@link LiveMapStore}'s private window.)
     */
    private static final class SlidingWindow {
        private final int size;
        private final int[] windowRpm;
        private final float[] windowMap;
        private int idx = 0;
        private int fill = 0;

        SlidingWindow(int size) {
            this.size = size;
            this.windowRpm = new int[size];
            this.windowMap = new float[size];
        }

        void reset() {
            idx = 0;
            fill = 0;
        }

        boolean accept(int rpmCell, float mapBin) {
            windowRpm[idx] = rpmCell;
            windowMap[idx] = mapBin;
            idx = (idx + 1) % size;
            if (fill < size) fill++;

            int matches = 0;
            int limit = Math.min(fill, size);
            for (int i = 0; i < limit; i++) {
                int j = (idx - 1 - i + size) % size;
                if (windowRpm[j] == rpmCell && Math.abs(windowMap[j] - mapBin) < 0.01f) {
                    matches++;
                    if (matches >= 2) return true;
                }
            }
            return false;
        }
    }

    private final ConcurrentHashMap<String, VeCell> petrolData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, VeCell> lpgData = new ConcurrentHashMap<>();
    private final SlidingWindow debounce = new SlidingWindow(4);

    private MapSampleMeta previous;
    private Boolean lastGaseous = null;
    private volatile long lastUpdateMs = 0;
    private volatile String lastCellKey = "";
    private volatile int totalAccepted = 0;
    private volatile String petrolAxisSource = MapSampleMeta.AXIS_NONE;
    private volatile String lpgAxisSource = MapSampleMeta.AXIS_NONE;

    /**
     * Single write entry: accumulate one instantaneous VE reading into the grid.
     *
     * @param meta     the same {@link MapSampleMeta} used for the fuel map, so
     *                 the two surfaces bin identically and stay cell-aligned
     * @param fuelMode active fuel, selects the petrol or gaseous side
     * @param vePct    instantaneous VE from {@link AdvancedAirDensity}, or null
     */
    public synchronized VePushResult push(MapSampleMeta meta, FuelMode fuelMode, Double vePct) {
        boolean gaseous = fuelMode != null && fuelMode.isGaseous();
        if (meta == null) {
            return rejected("null_record", "", gaseous, 0.0);
        }

        // A fuel changeover is a real transient — never let one fuel's history
        // prime the other's debounce / step gate, even in the same grid cell.
        if (lastGaseous != null && lastGaseous != gaseous) {
            resetGate();
        }
        lastGaseous = gaseous;

        if (meta.rpmCell < 0 || meta.mapBin < 0f) {
            return rejected("no_cell", meta.cellKey, gaseous, 0.0);
        }

        // Track the operating point on EVERY sample that has one, before any
        // further gating. VE is routinely absent for a cycle or two (MAF is
        // round-robin polled), and if a no_ve rejection left `previous` stale,
        // the next valid reading would be compared against a minutes-old
        // operating point and falsely rejected as "transient".
        MapSampleMeta prior = previous;
        previous = meta;

        double ve = vePct != null ? vePct : Double.NaN;
        if (!Double.isFinite(ve) || ve < VE_MIN || ve > VE_MAX) {
            return rejected("no_ve", meta.cellKey, gaseous, Double.isFinite(ve) ? ve : 0.0);
        }

        // Warm gate: require a warm engine when ECT is known; allow through when
        // the vehicle exposes no coolant PID (can't prove it's cold).
        if (meta.ect != null && !meta.warmEnough) {
            resetGate();
            return rejected("cold_engine", meta.cellKey, gaseous, ve);
        }

        // A measured-MAP axis and a load-synthesized one are different axes, not
        // two spellings of the same one — never accumulate across them.
        String targetAxis = gaseous ? lpgAxisSource : petrolAxisSource;
        if (!MapSampleMeta.AXIS_NONE.equals(targetAxis)
                && !MapSampleMeta.axesCompatible(targetAxis, meta.axisSource)) {
            resetGate();
            return rejected("axis_mismatch", meta.cellKey, gaseous, ve);
        }

        if (isTransient(prior, meta)) {
            return rejected("transient", meta.cellKey, gaseous, ve);
        }

        if (!debounce.accept(meta.rpmCell, meta.mapBin)) {
            return rejected("debounce", meta.cellKey, gaseous, ve);
        }

        Map<String, VeCell> target = gaseous ? lpgData : petrolData;
        VeCell cell = target.get(meta.cellKey);
        if (cell == null) {
            cell = new VeCell();
            target.put(meta.cellKey, cell);
        }
        cell.add(ve);

        if (gaseous) {
            lpgAxisSource = meta.axisSource;
        } else {
            petrolAxisSource = meta.axisSource;
        }
        lastUpdateMs = System.currentTimeMillis();
        lastCellKey = meta.cellKey;
        totalAccepted++;
        return new VePushResult(true, null, meta.cellKey, gaseous, ve);
    }

    private static boolean isTransient(MapSampleMeta before, MapSampleMeta now) {
        if (before == null) return false;
        if (finite(before.rpm) && finite(now.rpm)
                && Math.abs(now.rpm - before.rpm) > MAX_RPM_STEP) return true;
        if (Double.isFinite(before.loadAxis) && Double.isFinite(now.loadAxis)
                && Math.abs(now.loadAxis - before.loadAxis) > MAX_AXIS_STEP) return true;
        return finite(before.throttle) && finite(now.throttle)
                && Math.abs(now.throttle - before.throttle) > MAX_THROTTLE_STEP;
    }

    private static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private void resetGate() {
        previous = null;
        debounce.reset();
    }

    private VePushResult rejected(String reason, String cellKey, boolean gaseous, double ve) {
        return new VePushResult(false, reason, cellKey, gaseous, ve);
    }

    public VeSnapshot snapshot() {
        return new VeSnapshot(petrolData, lpgData, lastUpdateMs, lastCellKey,
                totalAccepted, petrolAxisSource, lpgAxisSource);
    }

    public Map<String, VeCell> getPetrolData() {
        return petrolData;
    }

    public Map<String, VeCell> getLpgData() {
        return lpgData;
    }

    public long getLastUpdateMs() {
        return lastUpdateMs;
    }

    public String getLastCellKey() {
        return lastCellKey;
    }

    public int getTotalAccepted() {
        return totalAccepted;
    }

    public String getPetrolAxisSource() {
        return petrolAxisSource;
    }

    public String getLpgAxisSource() {
        return lpgAxisSource;
    }

    public int getCellCount(FuelMode fuelMode) {
        return (fuelMode != null && fuelMode.isGaseous()) ? lpgData.size() : petrolData.size();
    }

    public boolean isComparisonAxisCompatible() {
        return !MapSampleMeta.AXIS_NONE.equals(petrolAxisSource)
                && petrolAxisSource.equals(lpgAxisSource);
    }

    public synchronized void clear() {
        petrolData.clear();
        lpgData.clear();
        lastUpdateMs = 0;
        lastCellKey = "";
        totalAccepted = 0;
        petrolAxisSource = MapSampleMeta.AXIS_NONE;
        lpgAxisSource = MapSampleMeta.AXIS_NONE;
        lastGaseous = null;
        resetGate();
    }

    public synchronized void clear(FuelMode fuelMode) {
        if (fuelMode != null && fuelMode.isGaseous()) {
            lpgData.clear();
            lpgAxisSource = MapSampleMeta.AXIS_NONE;
        } else {
            petrolData.clear();
            petrolAxisSource = MapSampleMeta.AXIS_NONE;
        }
        totalAccepted = 0;
        lastCellKey = "";
        lastGaseous = null;
        resetGate();
    }

    // ── Exports ─────────────────────────────────────────────────────────────

    /** Dense per-side CSV — one row per learned cell. */
    public String exportVeCsv(boolean gaseous) {
        StringBuilder sb = new StringBuilder();
        sb.append("rpm_cell,map_bin,ve,ve_stddev,ve_min,ve_max,hits,confidence\n");
        Map<String, VeCell> map = gaseous ? lpgData : petrolData;
        for (Map.Entry<String, VeCell> e : map.entrySet()) {
            String key = e.getKey();
            int us = key.indexOf('_');
            String rpm = us > 0 ? key.substring(0, us) : key;
            String mb = us > 0 ? key.substring(us + 1) : "";
            VeCell c = e.getValue();
            sb.append(rpm).append(',')
              .append(mb).append(',')
              .append(String.format(Locale.US, "%.2f", c.getVe())).append(',')
              .append(String.format(Locale.US, "%.3f", c.getStdDev())).append(',')
              .append(String.format(Locale.US, "%.2f", c.getMin())).append(',')
              .append(String.format(Locale.US, "%.2f", c.getMax())).append(',')
              .append(c.getCount()).append(',')
              .append(String.format(Locale.US, "%.3f", c.getConfidence()))
              .append('\n');
        }
        return sb.toString();
    }

    /**
     * ΔVE grid (petrol − LPG, percentage points) as a MAP×RPM matrix — the
     * headline diagnostic. Empty where either side lacks a mature measurement.
     */
    public String exportDeltaVeCsv() {
        StringBuilder sb = new StringBuilder();
        if (!isComparisonAxisCompatible()) {
            sb.append("ERROR,Petrol and LPG VE map axes are missing or incompatible\n");
            sb.append("petrol_axis,").append(petrolAxisSource).append('\n');
            sb.append("lpg_axis,").append(lpgAxisSource).append('\n');
            return sb.toString();
        }

        int rpmCount = MapBinning.getRpmCount();
        int mapCount = MapBinning.MAP_BINS.length;

        sb.append("MAP kPa \\ RPM");
        for (int c = 0; c < rpmCount; c++) {
            sb.append(',').append(MapBinning.rpmForColumn(c));
        }
        sb.append('\n');

        for (int r = 0; r < mapCount; r++) {
            float mapValue = MapBinning.mapForRow(r);
            sb.append(String.format(Locale.US, "%.2f", mapValue));
            for (int c = 0; c < rpmCount; c++) {
                int rpmValue = MapBinning.rpmForColumn(c);
                String key = MapBinning.cellKey(rpmValue, mapValue);
                VeCell petrol = petrolData.get(key);
                VeCell lpg = lpgData.get(key);
                if (petrol != null && petrol.getCount() >= MIN_COMPARE_HITS
                        && lpg != null && lpg.getCount() >= MIN_COMPARE_HITS) {
                    double delta = petrol.getVe() - lpg.getVe();
                    sb.append(',').append(String.format(Locale.US, "%.1f", delta));
                } else {
                    sb.append(',');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
