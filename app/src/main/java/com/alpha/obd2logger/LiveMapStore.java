package com.alpha.obd2logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for live fuel-map trim data.
 *
 * <h3>Write path</h3>
 * {@link #pushSample} / {@link #pushFromMeta} is the only way data enters.
 * Debounce, closed-loop/temp gates, and hard lock after {@link TrimData#MAX_HITS}
 * all live here so UI, API/SSE and export always agree.
 *
 * <h3>Read path</h3>
 * {@link #snapshot()} returns an immutable copy (includes active cell for the live highlight).
 * {@link #deltaSince(long)} returns only cells updated after a timestamp — used for SSE.
 *
 * <h3>Thread safety</h3>
 * Writes are synchronized. Reads use {@link ConcurrentHashMap} + defensive copies.
 */
public final class LiveMapStore {

    public static final class TrimData {
        private double sum = 0;
        private double sumSquares = 0;
        private double stftSum = 0;
        private int stftCount = 0;
        private double ltftSum = 0;
        private int ltftCount = 0;
        private double lambdaSum = 0;
        private double lambdaSumSquares = 0;
        private int lambdaCount = 0;
        private int hitCount = 0;
        private volatile long lastUpdateMs = 0;
        public static final int MAX_HITS = 20;

        public TrimData() {
        }

        public TrimData(double sum, int hitCount) {
            // Clamp like setFromImport already does. Unclamped, this could mint
            // a cell that is born past MAX_HITS and therefore permanently
            // locked against ever learning from the vehicle.
            int bounded = Math.max(0, Math.min(MAX_HITS, hitCount));
            double average = hitCount > 0 ? sum / hitCount : 0.0;
            this.hitCount = bounded;
            this.sum = average * bounded;
            this.sumSquares = average * average * bounded;
            this.lastUpdateMs = System.currentTimeMillis();
        }

        public synchronized void addStableValue(double val) {
            // Hard lock: once a cell reaches MAX_HITS, do not keep diluting the mean
            // with late-drive noise. Import uses the reconstructing constructor instead.
            if (hitCount >= MAX_HITS) {
                return;
            }
            sum += val;
            sumSquares += val * val;
            hitCount++;
            lastUpdateMs = System.currentTimeMillis();
        }

        /**
         * Store one quality-gated sample together with its diagnostic components.
         *
         * @return true when the sample was stored; false when it was dropped as
         * null, non-finite, or past the hit cap. Callers must not treat a
         * dropped sample as accepted — see pushFromMeta.
         */
        public synchronized boolean addStableSample(MapSampleMeta meta) {
            if (meta == null || hitCount >= MAX_HITS || !Double.isFinite(meta.trimTotal)) {
                return false;
            }
            sum += meta.trimTotal;
            sumSquares += meta.trimTotal * meta.trimTotal;
            hitCount++;
            if (finite(meta.stft)) {
                stftSum += meta.stft;
                stftCount++;
            }
            if (finite(meta.ltft)) {
                ltftSum += meta.ltft;
                ltftCount++;
            }
            if (finite(meta.lambda) && meta.lambda > 0.0 && meta.lambda < 2.0) {
                lambdaSum += meta.lambda;
                lambdaSumSquares += meta.lambda * meta.lambda;
                lambdaCount++;
            }
            lastUpdateMs = System.currentTimeMillis();
            return true;
        }

        /** Overwrite from an imported baseline with bounded automotive values. */
        public synchronized void setFromImport(double avg, int hits) {
            int n = Math.max(0, Math.min(MAX_HITS, hits));
            double boundedAverage = Double.isFinite(avg)
                    ? Math.max(-100.0, Math.min(100.0, avg)) : 0.0;
            this.hitCount = n;
            this.sum = boundedAverage * n;
            this.sumSquares = boundedAverage * boundedAverage * n;
            this.stftSum = 0;
            this.stftCount = 0;
            this.ltftSum = 0;
            this.ltftCount = 0;
            this.lambdaSum = 0;
            this.lambdaSumSquares = 0;
            this.lambdaCount = 0;
            this.lastUpdateMs = System.currentTimeMillis();
        }

        public synchronized double getAverage() {
            return hitCount == 0 ? 0 : sum / hitCount;
        }

        public synchronized int getHitCount() {
            return hitCount;
        }

        public synchronized double getSum() {
            return sum;
        }

        public synchronized double getStandardDeviation() {
            if (hitCount < 2) return 0.0;
            double variance = (sumSquares - (sum * sum / hitCount)) / (hitCount - 1);
            return Math.sqrt(Math.max(0.0, variance));
        }

        public synchronized Double getAverageStft() {
            return stftCount > 0 ? stftSum / stftCount : null;
        }

        public synchronized Double getAverageLtft() {
            return ltftCount > 0 ? ltftSum / ltftCount : null;
        }

        public synchronized Double getAverageLambda() {
            return lambdaCount > 0 ? lambdaSum / lambdaCount : null;
        }

        public synchronized double getLambdaStandardDeviation() {
            if (lambdaCount < 2) return 0.0;
            double variance = (lambdaSumSquares - (lambdaSum * lambdaSum / lambdaCount))
                    / (lambdaCount - 1);
            return Math.sqrt(Math.max(0.0, variance));
        }

        public synchronized int getLambdaCount() {
            return lambdaCount;
        }

        /** 0..1 score used by UI/API; five stable hits is the minimum mature cell. */
        public synchronized double getConfidence() {
            double hitScore = Math.min(1.0, hitCount / 5.0);
            double spreadPenalty = Math.min(0.5, getStandardDeviation() / 20.0);
            return Math.max(0.0, hitScore - spreadPenalty);
        }

        public long getLastUpdateMs() {
            return lastUpdateMs;
        }

        public boolean isLocked() {
            return hitCount >= MAX_HITS;
        }

        /** Defensive copy so snapshots / imports never share mutable state. */
        public synchronized TrimData copy() {
            TrimData c = new TrimData();
            c.sum = this.sum;
            c.sumSquares = this.sumSquares;
            c.stftSum = this.stftSum;
            c.stftCount = this.stftCount;
            c.ltftSum = this.ltftSum;
            c.ltftCount = this.ltftCount;
            c.lambdaSum = this.lambdaSum;
            c.lambdaSumSquares = this.lambdaSumSquares;
            c.lambdaCount = this.lambdaCount;
            c.hitCount = this.hitCount;
            c.lastUpdateMs = this.lastUpdateMs;
            return c;
        }

        private static boolean finite(Double value) {
            return value != null && Double.isFinite(value);
        }
    }

    /**
     * Result of a push attempt — used for AI logging and diagnostics.
     */
    public static final class PushResult {
        public final boolean accepted;
        public final String reason;          // null when accepted
        public final String cellKey;
        public final int rpmCell;
        public final float mapBin;
        public final double trim;
        public final boolean isGaseous;

        public PushResult(boolean accepted, String reason, String cellKey,
                          int rpmCell, float mapBin, double trim, boolean isGaseous) {
            this.accepted = accepted;
            this.reason = reason;
            this.cellKey = cellKey != null ? cellKey : "";
            this.rpmCell = rpmCell;
            this.mapBin = mapBin;
            this.trim = trim;
            this.isGaseous = isGaseous;
        }

        public static PushResult rejected(String reason, MapSampleMeta meta, boolean isGaseous) {
            if (meta == null) {
                return new PushResult(false, reason, "", -1, -1f, 0, isGaseous);
            }
            return new PushResult(false, reason, meta.cellKey, meta.rpmCell, meta.mapBin,
                    meta.trimTotal, isGaseous);
        }
    }

    public static final class MapSnapshot {
        private final Map<String, TrimData> petrol;
        private final Map<String, TrimData> lpg;
        private final long snapshotMs;
        private final String lastCellKey;
        private final int totalRecords;
        private final int activeRpmCell;
        private final float activeMapBin;
        private final String axisSource;
        private final String petrolAxisSource;
        private final String lpgAxisSource;

        public MapSnapshot(Map<String, TrimData> petrol,
                           Map<String, TrimData> lpg,
                           long snapshotMs,
                           String lastCellKey,
                           int totalRecords,
                           int activeRpmCell,
                           float activeMapBin,
                           String axisSource,
                           String petrolAxisSource,
                           String lpgAxisSource) {
            // Deep-copy TrimData so later write-side mutations don't mutate this snapshot.
            Map<String, TrimData> pCopy = new HashMap<>();
            for (Map.Entry<String, TrimData> e : petrol.entrySet()) {
                pCopy.put(e.getKey(), e.getValue().copy());
            }
            Map<String, TrimData> lCopy = new HashMap<>();
            for (Map.Entry<String, TrimData> e : lpg.entrySet()) {
                lCopy.put(e.getKey(), e.getValue().copy());
            }
            this.petrol = Collections.unmodifiableMap(pCopy);
            this.lpg = Collections.unmodifiableMap(lCopy);
            this.snapshotMs = snapshotMs;
            this.lastCellKey = lastCellKey;
            this.totalRecords = totalRecords;
            this.activeRpmCell = activeRpmCell;
            this.activeMapBin = activeMapBin;
            this.axisSource = axisSource != null ? axisSource : MapSampleMeta.AXIS_NONE;
            this.petrolAxisSource = normalizeAxis(petrolAxisSource);
            this.lpgAxisSource = normalizeAxis(lpgAxisSource);
        }

        public Map<String, TrimData> getPetrolData() { return petrol; }
        public Map<String, TrimData> getLpgData() { return lpg; }
        public long getSnapshotMs() { return snapshotMs; }
        public String getLastCellKey() { return lastCellKey; }
        public int getTotalRecords() { return totalRecords; }
        public int getActiveRpmCell() { return activeRpmCell; }
        public float getActiveMapBin() { return activeMapBin; }
        public String getAxisSource() { return axisSource; }
        public String getPetrolAxisSource() { return petrolAxisSource; }
        public String getLpgAxisSource() { return lpgAxisSource; }

        public boolean isComparisonAxisCompatible() {
            // axesCompatible() rejects NONE on either side, so imported data of
            // unknown provenance is never silently compared against a live map.
            return MapSampleMeta.axesCompatible(petrolAxisSource, lpgAxisSource);
        }

        /**
         * A cell with no hits carries no measurement, but {@code getAverage()}
         * returns 0 for it — so an empty cell reads as a genuine "0% trim"
         * unless it is filtered here. {@code putImportedCell} still accepts
         * {@code hits == 0}, so this guard is needed regardless of the write
         * path being fixed.
         */
        private static boolean hasData(TrimData cell) {
            return cell != null && cell.getHitCount() > 0;
        }

        public int getOverlappingCellCount() {
            if (!isComparisonAxisCompatible()) return 0;
            int count = 0;
            for (String key : petrol.keySet()) {
                if (lpg.containsKey(key)) count++;
            }
            return count;
        }

        public double getAverageAbsoluteDeviation() {
            if (!isComparisonAxisCompatible()) return 0;
            double sumAbs = 0;
            int common = 0;
            for (String key : petrol.keySet()) {
                TrimData p = petrol.get(key);
                TrimData l = lpg.get(key);
                if (hasData(p) && hasData(l)) {
                    sumAbs += Math.abs(l.getAverage() - p.getAverage());
                    common++;
                }
            }
            return common == 0 ? 0 : sumAbs / common;
        }

        public double getMaxDeviation() {
            if (!isComparisonAxisCompatible()) return 0;
            double maxDev = 0;
            for (String key : petrol.keySet()) {
                TrimData p = petrol.get(key);
                TrimData l = lpg.get(key);
                if (hasData(p) && hasData(l)) {
                    double dev = l.getAverage() - p.getAverage();
                    if (Math.abs(dev) > Math.abs(maxDev)) {
                        maxDev = dev;
                    }
                }
            }
            return maxDev;
        }

        public String getMaxDeviationCell() {
            if (!isComparisonAxisCompatible()) return null;
            double maxAbs = 0;
            String maxKey = null;
            for (String key : petrol.keySet()) {
                TrimData p = petrol.get(key);
                TrimData l = lpg.get(key);
                if (hasData(p) && hasData(l)) {
                    double dev = Math.abs(l.getAverage() - p.getAverage());
                    if (dev > maxAbs) {
                        maxAbs = dev;
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

    public static final class MapDelta {
        public final Map<String, TrimData> updatedPetrol;
        public final Map<String, TrimData> updatedLpg;
        public final long fromMs;
        public final long toMs;

        public MapDelta(Map<String, TrimData> updatedPetrol,
                        Map<String, TrimData> updatedLpg,
                        long fromMs, long toMs) {
            this.updatedPetrol = Collections.unmodifiableMap(updatedPetrol);
            this.updatedLpg = Collections.unmodifiableMap(updatedLpg);
            this.fromMs = fromMs;
            this.toMs = toMs;
        }

        public boolean isEmpty() {
            return updatedPetrol.isEmpty() && updatedLpg.isEmpty();
        }
    }

    /**
     * Sliding-window debounce: a sample is accepted only if the current
     * cell has been seen at least twice in the window. This filters
     * one-off transit through cells during RPM/MAP ramps.
     *
     * <p>NOTE: the first sample in a new cell is always rejected by design
     * (require prior match with i > 0). That used to incorrectly accept
     * first samples while the window was still filling.
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

        /**
         * Returns true if this (rpmCell, mapBin) should be accepted (seen ≥2 times
         * including the just-appended sample).
         */
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

    /**
     * Rejects transient samples before they can become tune corrections. The
     * thresholds are deliberately wider than normal closed-loop oscillation;
     * they remove pedal/load steps and sensor-lag spikes without requiring a
     * vehicle-specific calibration.
     */
    private static final class SampleStabilityGate {
        private static final double MAX_RPM_STEP = 400.0;
        private static final double MAX_AXIS_STEP = 15.0;
        private static final double MAX_THROTTLE_STEP = 10.0;
        private static final double MAX_LAMBDA_ERROR = 0.08;
        private static final double MAX_STFT_STDDEV = 6.0;
        private static final double MAX_TOTAL_TRIM = 50.0;
        private static final int STFT_WINDOW = 4;

        private MapSampleMeta previous;
        private final double[] stftWindow = new double[STFT_WINDOW];
        private int stftIndex = 0;
        private int stftFill = 0;

        String evaluate(MapSampleMeta meta) {
            if (meta == null) return "null_record";
            String reason = null;

            boolean sameCell = previous != null && meta.cellKey.equals(previous.cellKey);
            // The STFT window is per-cell, so a cell change still resets it.
            if (!sameCell) resetStft();

            // The step test is NOT restricted to same-cell samples. It used to
            // be, which meant a hard pedal step large enough to move the
            // operating point into a different cell skipped the check entirely —
            // exactly the moment fuel-trim lag makes a reading least
            // trustworthy. Only the 2-of-4 debounce stood between that sample
            // and the map.
            if (previous != null && isTransient(previous, meta)) {
                reason = "transient";
                resetStft();
            }

            if (finite(meta.stft)) addStft(meta.stft);
            if (reason == null && Math.abs(meta.trimTotal) > MAX_TOTAL_TRIM) {
                reason = "trim_unstable";
            }
            // Three samples minimum, deliberately. A standard deviation over
            // two points is dominated by noise and would reject legitimate
            // steady-state pairs; the 2-of-4 debounce is the first-level filter
            // and this gate takes over from the third sample in a cell onward.
            if (reason == null && stftFill >= 3 && stftStdDev() > MAX_STFT_STDDEV) {
                reason = "trim_unstable";
            }
            if (reason == null && finite(meta.lambda) && finite(meta.commandedLambda)
                    && meta.lambda > 0.5 && meta.lambda < 1.5
                    && meta.commandedLambda > 0.5 && meta.commandedLambda < 1.5
                    && Math.abs(meta.lambda - meta.commandedLambda) > MAX_LAMBDA_ERROR) {
                reason = "lambda_unstable";
            }

            previous = meta;
            return reason;
        }

        void reset() {
            previous = null;
            resetStft();
        }

        private static boolean isTransient(MapSampleMeta before, MapSampleMeta now) {
            if (finite(before.rpm) && finite(now.rpm)
                    && Math.abs(now.rpm - before.rpm) > MAX_RPM_STEP) return true;
            if (Double.isFinite(before.loadAxis) && Double.isFinite(now.loadAxis)
                    && Math.abs(now.loadAxis - before.loadAxis) > MAX_AXIS_STEP) return true;
            return finite(before.throttle) && finite(now.throttle)
                    && Math.abs(now.throttle - before.throttle) > MAX_THROTTLE_STEP;
        }

        private void resetStft() {
            stftIndex = 0;
            stftFill = 0;
        }

        private void addStft(double value) {
            stftWindow[stftIndex] = value;
            stftIndex = (stftIndex + 1) % STFT_WINDOW;
            if (stftFill < STFT_WINDOW) stftFill++;
        }

        private double stftStdDev() {
            if (stftFill < 2) return 0.0;
            double sum = 0.0;
            for (int i = 0; i < stftFill; i++) sum += stftWindow[i];
            double mean = sum / stftFill;
            double squares = 0.0;
            for (int i = 0; i < stftFill; i++) {
                double delta = stftWindow[i] - mean;
                squares += delta * delta;
            }
            return Math.sqrt(squares / (stftFill - 1));
        }

        private static boolean finite(Double value) {
            return value != null && Double.isFinite(value);
        }
    }

    private final ConcurrentHashMap<String, TrimData> petrolData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrimData> lpgData = new ConcurrentHashMap<>();
    private final SlidingWindow debounce = new SlidingWindow(4);
    private final SampleStabilityGate stabilityGate = new SampleStabilityGate();

    private volatile long lastUpdateMs = 0;
    private volatile String lastCellKey = "";
    private volatile int totalRecords = 0;
    /**
     * RPM cell and MAP bin of the live cursor, published as one value.
     *
     * <p>These were two independent volatiles, so a reader could pair sample
     * N's RPM cell with sample N+1's MAP bin and highlight a cell the vehicle
     * was never in. An immutable holder makes the pair atomic.
     */
    private static final class ActiveCell {
        final int rpmCell;
        final float mapBin;

        ActiveCell(int rpmCell, float mapBin) {
            this.rpmCell = rpmCell;
            this.mapBin = mapBin;
        }
    }

    private static final ActiveCell NO_ACTIVE_CELL = new ActiveCell(-1, -1f);
    private volatile ActiveCell activeCell = NO_ACTIVE_CELL;
    private volatile String axisSource = MapSampleMeta.AXIS_NONE;
    private volatile String petrolAxisSource = MapSampleMeta.AXIS_NONE;
    private volatile String lpgAxisSource = MapSampleMeta.AXIS_NONE;
    private Boolean lastFuelSideGaseous = null;

    /**
     * Preferred write entry — uses precomputed {@link MapSampleMeta}.
     */
    public synchronized PushResult pushFromMeta(MapSampleMeta meta, FuelMode fuelMode) {
        boolean gaseous = fuelMode != null && fuelMode.isGaseous();
        if (meta == null) {
            return PushResult.rejected("null_record", null, gaseous);
        }

        // Fuel changeover is a real transient. Never let petrol history prime
        // the LPG debounce window (or vice versa), even in the same grid cell.
        if (lastFuelSideGaseous != null && lastFuelSideGaseous != gaseous) {
            debounce.reset();
            stabilityGate.reset();
        }
        lastFuelSideGaseous = gaseous;

        // Always track cursor so the live highlight follows the right cell,
        // even when the sample is gated out or debounced away.
        if (meta.rpmCell >= 0 && meta.mapBin >= 0f) {
            activeCell = new ActiveCell(meta.rpmCell, meta.mapBin);
            lastCellKey = meta.cellKey;
            axisSource = meta.axisSource;
        }

        if (!meta.gatedEligible) {
            // Never bridge debounce/stability history across open-loop, cold or
            // missing-signal periods.
            debounce.reset();
            stabilityGate.reset();
            return PushResult.rejected(
                    meta.rejectReason != null ? meta.rejectReason : "gated",
                    meta, gaseous);
        }

        String targetAxis = gaseous ? lpgAxisSource : petrolAxisSource;
        // Strict: a measured MAP axis and a load-synthesized one are different
        // axes, not two spellings of the same one. Resetting the window here is
        // correct — the entries it holds were binned on the other axis and are
        // not comparable.
        if (!MapSampleMeta.AXIS_NONE.equals(targetAxis)
                && !MapSampleMeta.axesCompatible(targetAxis, meta.axisSource)) {
            debounce.reset();
            stabilityGate.reset();
            return PushResult.rejected("axis_mismatch", meta, gaseous);
        }

        String stabilityReason = stabilityGate.evaluate(meta);
        if (stabilityReason != null) {
            return PushResult.rejected(stabilityReason, meta, gaseous);
        }

        if (!debounce.accept(meta.rpmCell, meta.mapBin)) {
            return PushResult.rejected("debounce", meta, gaseous);
        }

        Map<String, TrimData> target = gaseous ? lpgData : petrolData;
        TrimData existing = target.get(meta.cellKey);
        if (existing != null && existing.isLocked()) {
            return PushResult.rejected("locked", meta, gaseous);
        }

        // Populate BEFORE publishing, and only publish if the sample was
        // actually stored. Interning the cell first had two consequences:
        //
        //  1. addStableSample silently drops a non-finite trim, so a NaN
        //     produced a permanent hitCount==0 cell that this method still
        //     reported as accepted. Nothing downstream filters empty cells, and
        //     getAverage() returns 0 for them, so the phantom read as a real
        //     "0% trim measured" — corrupting the correction export and the
        //     session deviation stats.
        //  2. snapshot() is not synchronized, so a reader could observe the
        //     cell in the window between the put and the value being added.
        TrimData cell = existing != null ? existing : new TrimData();
        if (!cell.addStableSample(meta)) {
            return PushResult.rejected("non_finite_trim", meta, gaseous);
        }
        if (existing == null) {
            target.put(meta.cellKey, cell);
        }

        if (gaseous) lpgAxisSource = meta.axisSource;
        else petrolAxisSource = meta.axisSource;

        lastUpdateMs = System.currentTimeMillis();
        totalRecords++;
        return new PushResult(true, null, meta.cellKey, meta.rpmCell, meta.mapBin,
                meta.trimTotal, gaseous);
    }

    /**
     * Compatibility write entry used by existing callers.
     * @return true if the sample was accepted and stored
     */
    public synchronized boolean pushSample(double rpm, double loadAxis, double trim,
                                           FuelMode fuelMode,
                                           boolean isClosedLoop, Double ect) {
        MapSampleMeta meta = MapSampleMeta.fromLegacy(rpm, loadAxis, trim, isClosedLoop, ect);
        return pushFromMeta(meta, fuelMode).accepted;
    }

    public MapSnapshot snapshot() {
        // Read the cursor once so both coordinates come from the same sample.
        ActiveCell cursor = activeCell;
        return new MapSnapshot(
                petrolData,
                lpgData,
                lastUpdateMs,
                lastCellKey,
                totalRecords,
                cursor.rpmCell,
                cursor.mapBin,
                axisSource,
                petrolAxisSource,
                lpgAxisSource
        );
    }

    public MapDelta deltaSince(long sinceMs) {
        Map<String, TrimData> updatedPetrol = new HashMap<>();
        Map<String, TrimData> updatedLpg = new HashMap<>();

        for (Map.Entry<String, TrimData> e : petrolData.entrySet()) {
            if (e.getValue().getLastUpdateMs() > sinceMs) {
                updatedPetrol.put(e.getKey(), e.getValue().copy());
            }
        }
        for (Map.Entry<String, TrimData> e : lpgData.entrySet()) {
            if (e.getValue().getLastUpdateMs() > sinceMs) {
                updatedLpg.put(e.getKey(), e.getValue().copy());
            }
        }
        return new MapDelta(updatedPetrol, updatedLpg, sinceMs, lastUpdateMs);
    }

    public Map<String, TrimData> getPetrolData() { return petrolData; }
    public Map<String, TrimData> getLpgData() { return lpgData; }

    /**
     * Import / restore petrol map. Replaces cells by key; does not clear other cells
     * unless {@code replaceAll} is true.
     */
    public synchronized void importPetrol(Map<String, TrimData> data, boolean replaceAll) {
        importPetrol(data, replaceAll, MapSampleMeta.AXIS_NONE);
    }

    /**
     * @param axisSource the axis the imported cells were binned on, or
     * {@link MapSampleMeta#AXIS_NONE} when the payload does not say.
     *
     * <p>An undeclared axis stays {@code NONE} rather than being assumed to be
     * {@code MAP}. Asserting MAP was actively harmful: a baseline captured on a
     * vehicle with no MAP PID is binned on engine-load %, and relabelling it kPa
     * let the correction export subtract %-binned cells from kPa-binned ones
     * with no warning. It also locked the axis, so every subsequent live sample
     * on a LOAD-axis vehicle was rejected as {@code axis_mismatch} and the map
     * never learned again.
     */
    public synchronized void importPetrol(Map<String, TrimData> data, boolean replaceAll,
                                          String axisSource) {
        if (replaceAll) {
            petrolData.clear();
            petrolAxisSource = MapSampleMeta.AXIS_NONE;
        }
        if (data == null) return;
        for (Map.Entry<String, TrimData> e : data.entrySet()) {
            TrimData src = e.getValue();
            if (src == null) continue;
            petrolData.put(e.getKey(), src.copy());
        }
        if (!data.isEmpty() && MapSampleMeta.AXIS_NONE.equals(petrolAxisSource)) {
            petrolAxisSource = resolveImportedAxis(axisSource, "petrol");
        }
        lastUpdateMs = System.currentTimeMillis();
    }

    public synchronized void importLpg(Map<String, TrimData> data, boolean replaceAll) {
        importLpg(data, replaceAll, MapSampleMeta.AXIS_NONE);
    }

    /** @see #importPetrol(Map, boolean, String) */
    public synchronized void importLpg(Map<String, TrimData> data, boolean replaceAll,
                                       String axisSource) {
        if (replaceAll) {
            lpgData.clear();
            lpgAxisSource = MapSampleMeta.AXIS_NONE;
        }
        if (data == null) return;
        for (Map.Entry<String, TrimData> e : data.entrySet()) {
            TrimData src = e.getValue();
            if (src == null) continue;
            lpgData.put(e.getKey(), src.copy());
        }
        if (!data.isEmpty() && MapSampleMeta.AXIS_NONE.equals(lpgAxisSource)) {
            lpgAxisSource = resolveImportedAxis(axisSource, "lpg");
        }
        lastUpdateMs = System.currentTimeMillis();
    }

    /**
     * Axis for imported cells: the declared one when the payload carries it,
     * otherwise the historical {@code MAP} assumption.
     *
     * <p>The assumption is kept for compatibility with existing payloads and
     * saved baselines, but it is now logged rather than silent, because it is
     * only correct for vehicles that actually reported PID 0x0B. A baseline
     * captured on a MAF-only vehicle was binned on engine-load %, and calling
     * that kPa lets the correction export subtract %-binned cells from
     * kPa-binned ones. Callers that know the axis must pass it.
     */
    private static String resolveImportedAxis(String declared, String side) {
        // Validate here, not only at the API boundary: the store owns the axis
        // invariant, and an unrecognised name stored verbatim would lock the map
        // to an axis no live sample can ever match, silently ending learning.
        if (MapSampleMeta.AXIS_MAP.equals(declared)
                || MapSampleMeta.AXIS_SYNTH_MAP.equals(declared)
                || MapSampleMeta.AXIS_LOAD.equals(declared)) {
            return declared;
        }
        if (declared != null && !MapSampleMeta.AXIS_NONE.equals(declared)) {
            android.util.Log.w("LiveMapStore", "Ignoring unrecognised axis source '"
                    + declared + "' on " + side + " import");
        }
        android.util.Log.w("LiveMapStore", "Imported " + side + " map declares no axis source; "
                + "assuming " + MapSampleMeta.AXIS_MAP + ". If this baseline was captured on a "
                + "vehicle without a MAP sensor it was binned on engine load and must not be "
                + "compared against a MAP-axis map.");
        return MapSampleMeta.AXIS_MAP;
    }

    /** Backward-compat: replace all. */
    public void setPetrolData(Map<String, TrimData> data) {
        importPetrol(data, true);
    }

    public void setLpgData(Map<String, TrimData> data) {
        importLpg(data, true);
    }

    /**
     * Put one imported cell (avg + hits) into the given fuel side.
     * Used by POST /api/map/import. Keys must use {@link MapBinning#cellKey}.
     */
    public synchronized void putImportedCell(boolean gaseous, String key, double avg, int hits) {
        if (key == null || key.isEmpty() || !Double.isFinite(avg) || hits < 0) return;
        String normalized = normalizeCellKey(key);
        if (!normalized.matches("\\d+_-?\\d+(?:\\.\\d+)?")) return;
        TrimData cell = new TrimData();
        cell.setFromImport(avg, hits);
        if (gaseous) {
            lpgData.put(normalized, cell);
            if (MapSampleMeta.AXIS_NONE.equals(lpgAxisSource)) lpgAxisSource = MapSampleMeta.AXIS_MAP;
        } else {
            petrolData.put(normalized, cell);
            if (MapSampleMeta.AXIS_NONE.equals(petrolAxisSource)) petrolAxisSource = MapSampleMeta.AXIS_MAP;
        }
        lastUpdateMs = System.currentTimeMillis();
    }

    /**
     * Normalize legacy cell keys to canonical MapBinning format.
     * Accepts "2000_40" / "2000_40.0" / "2000_40.00" → "2000_40.00".
     * Also re-bins RPM to the FLOOR grid so remaining ROUND-era data aligns.
     */
    public static String normalizeCellKey(String key) {
        if (key == null) return "";
        int us = key.indexOf('_');
        if (us <= 0 || us >= key.length() - 1) return key;
        try {
            double rpm = Double.parseDouble(key.substring(0, us));
            double map = Double.parseDouble(key.substring(us + 1));
            return MapBinning.cellKey(MapBinning.binRpm(rpm), MapBinning.binMap(map));
        } catch (NumberFormatException e) {
            return key;
        }
    }

    public synchronized void clear() {
        petrolData.clear();
        lpgData.clear();
        lastUpdateMs = 0;
        lastCellKey = "";
        totalRecords = 0;
        activeCell = NO_ACTIVE_CELL;
        axisSource = MapSampleMeta.AXIS_NONE;
        petrolAxisSource = MapSampleMeta.AXIS_NONE;
        lpgAxisSource = MapSampleMeta.AXIS_NONE;
        lastFuelSideGaseous = null;
        debounce.reset();
        stabilityGate.reset();
    }

    public synchronized void clear(FuelMode fuelMode) {
        if (fuelMode != null && fuelMode.isGaseous()) {
            lpgData.clear();
            lpgAxisSource = MapSampleMeta.AXIS_NONE;
        } else {
            petrolData.clear();
            petrolAxisSource = MapSampleMeta.AXIS_NONE;
        }
        // Keep active cursor so next sample can still highlight position.
        // totalRecords counts accepted samples for the fuel side being cleared,
        // so leaving it made it accumulate across every session and fuel switch
        // — useless as the per-session acceptance counter it is read as.
        totalRecords = 0;
        lastCellKey = "";
        lastFuelSideGaseous = null;
        debounce.reset();
        stabilityGate.reset();
    }

    public long getLastUpdateMs() { return lastUpdateMs; }
    public String getLastCellKey() { return lastCellKey; }
    public int getTotalRecords() { return totalRecords; }
    public int getActiveRpmCell() { return activeCell.rpmCell; }
    public float getActiveMapBin() { return activeCell.mapBin; }
    public String getAxisSource() { return axisSource; }
    public String getPetrolAxisSource() { return petrolAxisSource; }
    public String getLpgAxisSource() { return lpgAxisSource; }

    public boolean isComparisonAxisCompatible() {
        return !MapSampleMeta.AXIS_NONE.equals(petrolAxisSource)
                && petrolAxisSource.equals(lpgAxisSource);
    }

    public boolean hasAnyCorrection() {
        if (!isComparisonAxisCompatible()) return false;
        for (String key : petrolData.keySet()) {
            if (lpgData.containsKey(key)) return true;
        }
        return false;
    }

    public int getCellCount(FuelMode fuelMode) {
        return (fuelMode != null && fuelMode.isGaseous()) ? lpgData.size() : petrolData.size();
    }

    public String exportCorrectionMapCsv() {
        StringBuilder sb = new StringBuilder();

        if (!isComparisonAxisCompatible()) {
            sb.append("ERROR,Petrol and LPG map axes are missing or incompatible\n");
            sb.append("petrol_axis,").append(petrolAxisSource).append('\n');
            sb.append("lpg_axis,").append(lpgAxisSource).append('\n');
            return sb.toString();
        }

        int rpmCount = MapBinning.getRpmCount();
        int mapCount = MapBinning.MAP_BINS.length;

        sb.append("MAP kPa \\ RPM");
        for (int c = 0; c < rpmCount; c++) {
            sb.append(",").append(MapBinning.rpmForColumn(c));
        }
        sb.append("\n");

        for (int r = 0; r < mapCount; r++) {
            float mapValue = MapBinning.mapForRow(r);
            sb.append(String.format(Locale.US, "%.2f", mapValue));

            for (int c = 0; c < rpmCount; c++) {
                int rpmValue = MapBinning.rpmForColumn(c);
                String key = MapBinning.cellKey(rpmValue, mapValue);
                TrimData petrol = petrolData.get(key);
                TrimData lpg = lpgData.get(key);

                // Both sides must hold a real measurement. An empty cell
                // averages to 0, which would print the full LPG trim as a
                // correction against a petrol baseline that never existed.
                if (petrol != null && petrol.getHitCount() > 0
                        && lpg != null && lpg.getHitCount() > 0) {
                    double correction = lpg.getAverage() - petrol.getAverage();
                    sb.append(",").append(Math.round(correction));
                } else {
                    sb.append(",");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * AI-friendly dense CSV for one map side (petrol or lpg). The legacy
     * {@code avg} column is retained as an alias of {@code correction_avg}.
     */
    public String exportAiCsv(boolean gaseous) {
        StringBuilder sb = new StringBuilder();
        sb.append("rpm_cell,map_bin,avg,correction_avg,stft_avg,ltft_avg,lambda_avg,lambda_stddev,hits,confidence,locked\n");
        Map<String, TrimData> map = gaseous ? lpgData : petrolData;
        for (Map.Entry<String, TrimData> e : map.entrySet()) {
            String key = e.getKey();
            int us = key.indexOf('_');
            String rpm = us > 0 ? key.substring(0, us) : key;
            String mb = us > 0 ? key.substring(us + 1) : "";
            TrimData t = e.getValue();
            sb.append(rpm).append(',')
              .append(mb).append(',')
              .append(String.format(Locale.US, "%.4f", t.getAverage())).append(',')
              .append(String.format(Locale.US, "%.4f", t.getAverage())).append(',')
              .append(nullableNumber(t.getAverageStft())).append(',')
              .append(nullableNumber(t.getAverageLtft())).append(',')
              .append(nullableNumber(t.getAverageLambda())).append(',')
              .append(t.getLambdaCount() > 1
                      ? String.format(Locale.US, "%.5f", t.getLambdaStandardDeviation()) : "").append(',')
              .append(t.getHitCount()).append(',')
              .append(String.format(Locale.US, "%.3f", t.getConfidence())).append(',')
              .append(t.isLocked() ? 1 : 0)
              .append('\n');
        }
        return sb.toString();
    }

    private static String nullableNumber(Double value) {
        return value != null && Double.isFinite(value)
                ? String.format(Locale.US, "%.5f", value) : "";
    }
}
