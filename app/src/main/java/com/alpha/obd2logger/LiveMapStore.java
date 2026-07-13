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
        private int hitCount = 0;
        private volatile long lastUpdateMs = 0;
        public static final int MAX_HITS = 20;

        public TrimData() {
        }

        public TrimData(double sum, int hitCount) {
            this.sum = sum;
            this.hitCount = hitCount;
            this.lastUpdateMs = System.currentTimeMillis();
        }

        public synchronized void addStableValue(double val) {
            // Hard lock: once a cell reaches MAX_HITS, do not keep diluting the mean
            // with late-drive noise. Import uses the reconstructing constructor instead.
            if (hitCount >= MAX_HITS) {
                return;
            }
            sum += val;
            hitCount++;
            lastUpdateMs = System.currentTimeMillis();
        }

        /** Overwrite from an imported baseline with bounded automotive values. */
        public synchronized void setFromImport(double avg, int hits) {
            int n = Math.max(0, Math.min(MAX_HITS, hits));
            double boundedAverage = Double.isFinite(avg)
                    ? Math.max(-100.0, Math.min(100.0, avg)) : 0.0;
            this.hitCount = n;
            this.sum = boundedAverage * n;
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
            c.hitCount = this.hitCount;
            c.lastUpdateMs = this.lastUpdateMs;
            return c;
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

        public MapSnapshot(Map<String, TrimData> petrol,
                           Map<String, TrimData> lpg,
                           long snapshotMs,
                           String lastCellKey,
                           int totalRecords,
                           int activeRpmCell,
                           float activeMapBin,
                           String axisSource) {
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
        }

        public Map<String, TrimData> getPetrolData() { return petrol; }
        public Map<String, TrimData> getLpgData() { return lpg; }
        public long getSnapshotMs() { return snapshotMs; }
        public String getLastCellKey() { return lastCellKey; }
        public int getTotalRecords() { return totalRecords; }
        public int getActiveRpmCell() { return activeRpmCell; }
        public float getActiveMapBin() { return activeMapBin; }
        public String getAxisSource() { return axisSource; }

        public int getOverlappingCellCount() {
            int count = 0;
            for (String key : petrol.keySet()) {
                if (lpg.containsKey(key)) count++;
            }
            return count;
        }

        public double getAverageAbsoluteDeviation() {
            double sumAbs = 0;
            int common = 0;
            for (String key : petrol.keySet()) {
                TrimData p = petrol.get(key);
                TrimData l = lpg.get(key);
                if (p != null && l != null) {
                    sumAbs += Math.abs(l.getAverage() - p.getAverage());
                    common++;
                }
            }
            return common == 0 ? 0 : sumAbs / common;
        }

        public double getMaxDeviation() {
            double maxDev = 0;
            for (String key : petrol.keySet()) {
                TrimData p = petrol.get(key);
                TrimData l = lpg.get(key);
                if (p != null && l != null) {
                    double dev = l.getAverage() - p.getAverage();
                    if (Math.abs(dev) > Math.abs(maxDev)) {
                        maxDev = dev;
                    }
                }
            }
            return maxDev;
        }

        public String getMaxDeviationCell() {
            double maxAbs = 0;
            String maxKey = null;
            for (String key : petrol.keySet()) {
                TrimData p = petrol.get(key);
                TrimData l = lpg.get(key);
                if (p != null && l != null) {
                    double dev = Math.abs(l.getAverage() - p.getAverage());
                    if (dev > maxAbs) {
                        maxAbs = dev;
                        maxKey = key;
                    }
                }
            }
            return maxKey;
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

    private final ConcurrentHashMap<String, TrimData> petrolData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrimData> lpgData = new ConcurrentHashMap<>();
    private final SlidingWindow debounce = new SlidingWindow(4);

    private volatile long lastUpdateMs = 0;
    private volatile String lastCellKey = "";
    private volatile int totalRecords = 0;
    private volatile int activeRpmCell = -1;
    private volatile float activeMapBin = -1f;
    private volatile String axisSource = MapSampleMeta.AXIS_NONE;

    /**
     * Preferred write entry — uses precomputed {@link MapSampleMeta}.
     */
    public synchronized PushResult pushFromMeta(MapSampleMeta meta, FuelMode fuelMode) {
        boolean gaseous = fuelMode != null && fuelMode.isGaseous();
        if (meta == null) {
            return PushResult.rejected("null_record", null, gaseous);
        }

        // Always track cursor so the live highlight follows the right cell,
        // even when the sample is gated out or debounced away.
        if (meta.rpmCell >= 0 && meta.mapBin >= 0f) {
            activeRpmCell = meta.rpmCell;
            activeMapBin = meta.mapBin;
            lastCellKey = meta.cellKey;
            axisSource = meta.axisSource;
        }

        if (!meta.gatedEligible) {
            return PushResult.rejected(
                    meta.rejectReason != null ? meta.rejectReason : "gated",
                    meta, gaseous);
        }

        if (!debounce.accept(meta.rpmCell, meta.mapBin)) {
            return PushResult.rejected("debounce", meta, gaseous);
        }

        Map<String, TrimData> target = gaseous ? lpgData : petrolData;
        TrimData cell = target.get(meta.cellKey);
        if (cell == null) {
            cell = new TrimData();
            target.put(meta.cellKey, cell);
        }
        if (cell.isLocked()) {
            return PushResult.rejected("locked", meta, gaseous);
        }
        cell.addStableValue(meta.trimTotal);

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
        return new MapSnapshot(
                petrolData,
                lpgData,
                lastUpdateMs,
                lastCellKey,
                totalRecords,
                activeRpmCell,
                activeMapBin,
                axisSource
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
        if (replaceAll) petrolData.clear();
        if (data == null) return;
        for (Map.Entry<String, TrimData> e : data.entrySet()) {
            TrimData src = e.getValue();
            if (src == null) continue;
            petrolData.put(e.getKey(), src.copy());
        }
        lastUpdateMs = System.currentTimeMillis();
    }

    public synchronized void importLpg(Map<String, TrimData> data, boolean replaceAll) {
        if (replaceAll) lpgData.clear();
        if (data == null) return;
        for (Map.Entry<String, TrimData> e : data.entrySet()) {
            TrimData src = e.getValue();
            if (src == null) continue;
            lpgData.put(e.getKey(), src.copy());
        }
        lastUpdateMs = System.currentTimeMillis();
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
        } else {
            petrolData.put(normalized, cell);
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

    public void clear() {
        petrolData.clear();
        lpgData.clear();
        lastUpdateMs = 0;
        lastCellKey = "";
        totalRecords = 0;
        activeRpmCell = -1;
        activeMapBin = -1f;
        axisSource = MapSampleMeta.AXIS_NONE;
        debounce.reset();
    }

    public void clear(FuelMode fuelMode) {
        if (fuelMode != null && fuelMode.isGaseous()) {
            lpgData.clear();
        } else {
            petrolData.clear();
        }
        // Keep active cursor so next sample can still highlight position.
        debounce.reset();
    }

    public long getLastUpdateMs() { return lastUpdateMs; }
    public String getLastCellKey() { return lastCellKey; }
    public int getTotalRecords() { return totalRecords; }
    public int getActiveRpmCell() { return activeRpmCell; }
    public float getActiveMapBin() { return activeMapBin; }
    public String getAxisSource() { return axisSource; }

    public boolean hasAnyCorrection() {
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

                if (petrol != null && lpg != null) {
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
     * AI-friendly dense JSON-like CSV for one map side (petrol or lpg):
     * rpm_cell,map_bin,avg,hits,locked
     */
    public String exportAiCsv(boolean gaseous) {
        StringBuilder sb = new StringBuilder();
        sb.append("rpm_cell,map_bin,avg,hits,locked\n");
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
              .append(t.getHitCount()).append(',')
              .append(t.isLocked() ? 1 : 0)
              .append('\n');
        }
        return sb.toString();
    }
}
