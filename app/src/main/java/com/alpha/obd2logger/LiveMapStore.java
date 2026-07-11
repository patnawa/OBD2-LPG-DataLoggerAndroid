package com.alpha.obd2logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for live fuel-map trim data.
 *
 * <h3>Problem this solves</h3>
 * Before this class existed, fuel-map data was stored in three places:
 * <ol>
 *   <li>{@link FuelMapView} — with FLOOR binning + 4-sample debounce</li>
 *   <li>{@code MainActivity.sessionPetrolData} — a clear+putAll copy of (1), every record</li>
 *   <li>{@link ApiServer} — with ROUND binning and <b>no debounce</b></li>
 * </ol>
 * These three copies disagreed, causing the AI agent (via API/SSE) to see
 * different data than the user saw on screen.
 *
 * <h3>How it works</h3>
 * <ul>
 *   <li><b>Write path:</b> {@link #pushSample} is the <i>only</i> way data enters.
 *       It applies binning ({@link MapBinning}), debounce, and closed-loop/temp gating
 *       in one place.</li>
 *   <li><b>Read path:</b> {@link #snapshot()} returns an immutable copy that callers
 *       can iterate without locking. {@link #deltaSince(long)} returns only cells
 *       updated after a timestamp — used for SSE push events.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * Writes are synchronized (only the logger thread writes). Reads use
 * {@link ConcurrentHashMap} and return defensive copies, so the UI thread
 * and NanoHTTPD request threads can read concurrently without blocking.
 */
public final class LiveMapStore {

    // ── Trim data (shared structure, mirrors FuelMapView.TrimData) ──────

    /**
     * Accumulates trim values for one grid cell.
     * Thread-safe: methods are synchronized.
     */
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
            sum += val;
            hitCount++;
            lastUpdateMs = System.currentTimeMillis();
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
    }

    // ── Snapshot (immutable read copy) ──────────────────────────────────

    /**
     * Immutable point-in-time copy of the map data.
     * Safe to iterate on any thread without locking.
     */
    public static final class MapSnapshot {
        private final Map<String, TrimData> petrol;
        private final Map<String, TrimData> lpg;
        private final long snapshotMs;
        private final String lastCellKey;
        private final int totalRecords;

        public MapSnapshot(Map<String, TrimData> petrol,
                           Map<String, TrimData> lpg,
                           long snapshotMs,
                           String lastCellKey,
                           int totalRecords) {
            this.petrol = Collections.unmodifiableMap(new HashMap<>(petrol));
            this.lpg = Collections.unmodifiableMap(new HashMap<>(lpg));
            this.snapshotMs = snapshotMs;
            this.lastCellKey = lastCellKey;
            this.totalRecords = totalRecords;
        }

        public Map<String, TrimData> getPetrolData() { return petrol; }
        public Map<String, TrimData> getLpgData() { return lpg; }
        public long getSnapshotMs() { return snapshotMs; }
        public String getLastCellKey() { return lastCellKey; }
        public int getTotalRecords() { return totalRecords; }

        /** Number of overlapping cells with deviation data. */
        public int getOverlappingCellCount() {
            int count = 0;
            for (String key : petrol.keySet()) {
                if (lpg.containsKey(key)) count++;
            }
            return count;
        }

        /** Average absolute deviation across overlapping cells. */
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

        /** Max deviation value (signed — positive = lean, negative = rich). */
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

        /** Cell key with the largest absolute deviation, or null if none. */
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

    // ── Delta (for SSE push) ────────────────────────────────────────────

    /**
     * Changes since a given timestamp — used by SSE to push only
     * what changed instead of the full map.
     */
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

    // ── Sliding-window debounce ─────────────────────────────────────────

    /**
     * Sliding-window debounce: a sample is accepted only if the current
     * cell was seen at least once before in the window. This filters
     * transient one-off pass-through cells caused by RPM/MAP jitter
     * across cell boundaries.
     *
     * Moved here from FuelMapView so the API/SSE path gets the same
     * noise filtering as the UI.
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
         * Returns true if this (rpmCell, mapBin) should be accepted.
         */
        boolean accept(int rpmCell, float mapBin) {
            windowRpm[idx] = rpmCell;
            windowMap[idx] = mapBin;
            idx = (idx + 1) % size;
            if (fill < size) fill++;

            // Check if this cell was seen before in the window
            boolean seenBefore = false;
            int limit = Math.min(fill, size);
            for (int i = 0; i < limit; i++) {
                int j = (idx - 1 - i + size) % size;
                if (windowRpm[j] == rpmCell && Math.abs(windowMap[j] - mapBin) < 0.01f) {
                    if (i > 0) {
                        seenBefore = true;
                        break;
                    }
                }
            }

            // Accept if window not full yet (first samples), or if seen before
            return fill < size || seenBefore;
        }
    }

    // ── Store state ─────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, TrimData> petrolData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrimData> lpgData = new ConcurrentHashMap<>();
    private final SlidingWindow debounce = new SlidingWindow(4);

    private volatile long lastUpdateMs = 0;
    private volatile String lastCellKey = "";
    private volatile int totalRecords = 0;

    /**
     * The <b>only</b> write entry point. Called from the logger thread
     * for each new DataRecord.
     *
     * @param rpm           engine RPM
     * @param loadAxis      MAP kPa (or Engine Load % fallback)
     * @param trim          STFT+LTFT combined value
     * @param fuelMode      current fuel mode
     * @param isClosedLoop  true if fuel system is in closed loop
     * @param ect           engine coolant temp (°C), or null
     * @return true if the sample was accepted and stored
     */
    public synchronized boolean pushSample(double rpm, double loadAxis, double trim,
                                            FuelMode fuelMode,
                                            boolean isClosedLoop, Double ect) {
        // Gate: closed loop + warm engine
        boolean tempOk = (ect == null) || (ect >= 80.0);
        if (!isClosedLoop || !tempOk) {
            return false;
        }

        // Binning — single source of truth
        int rpmCell = MapBinning.binRpm(rpm);
        float mapBin = MapBinning.binMap(loadAxis);
        String key = MapBinning.cellKey(rpmCell, mapBin);

        // Debounce
        if (!debounce.accept(rpmCell, mapBin)) {
            return false;
        }

        // Store
        Map<String, TrimData> target = fuelMode.isGaseous() ? lpgData : petrolData;
        TrimData cell = target.get(key);
        if (cell == null) {
            cell = new TrimData();
            target.put(key, cell);
        }
        cell.addStableValue(trim);

        lastUpdateMs = System.currentTimeMillis();
        lastCellKey = key;
        totalRecords++;
        return true;
    }

    /**
     * Immutable snapshot for UI/API reads. Safe to iterate on any thread.
     */
    public MapSnapshot snapshot() {
        return new MapSnapshot(
                petrolData,
                lpgData,
                lastUpdateMs,
                lastCellKey,
                totalRecords
        );
    }

    /**
     * Delta since a timestamp — for SSE push events.
     * Returns only cells whose lastUpdateMs > sinceMs.
     */
    public MapDelta deltaSince(long sinceMs) {
        Map<String, TrimData> updatedPetrol = new HashMap<>();
        Map<String, TrimData> updatedLpg = new HashMap<>();

        for (Map.Entry<String, TrimData> e : petrolData.entrySet()) {
            if (e.getValue().getLastUpdateMs() > sinceMs) {
                updatedPetrol.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, TrimData> e : lpgData.entrySet()) {
            if (e.getValue().getLastUpdateMs() > sinceMs) {
                updatedLpg.put(e.getKey(), e.getValue());
            }
        }
        return new MapDelta(updatedPetrol, updatedLpg, sinceMs, lastUpdateMs);
    }

    // ── Direct accessors (for backward compatibility during migration) ──

    public Map<String, TrimData> getPetrolData() { return petrolData; }
    public Map<String, TrimData> getLpgData() { return lpgData; }

    public void setPetrolData(Map<String, TrimData> data) {
        petrolData.clear();
        if (data != null) petrolData.putAll(data);
    }

    public void setLpgData(Map<String, TrimData> data) {
        lpgData.clear();
        if (data != null) lpgData.putAll(data);
    }

    // ── Clear ───────────────────────────────────────────────────────────

    public void clear() {
        petrolData.clear();
        lpgData.clear();
        lastUpdateMs = 0;
        lastCellKey = "";
        totalRecords = 0;
        debounce.reset();
    }

    public void clear(FuelMode fuelMode) {
        if (fuelMode.isGaseous()) {
            lpgData.clear();
        } else {
            petrolData.clear();
        }
        debounce.reset();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    public long getLastUpdateMs() { return lastUpdateMs; }
    public String getLastCellKey() { return lastCellKey; }
    public int getTotalRecords() { return totalRecords; }

    public boolean hasAnyCorrection() {
        for (String key : petrolData.keySet()) {
            if (lpgData.containsKey(key)) return true;
        }
        return false;
    }

    public int getCellCount(FuelMode fuelMode) {
        return fuelMode.isGaseous() ? lpgData.size() : petrolData.size();
    }

    /**
     * Export the correction map as CSV.
     * Header: "MAP kPa \ RPM,500,1000,..."
     * Cells: rounded LPG-petrol deviation, or empty.
     */
    public String exportCorrectionMapCsv() {
        StringBuilder sb = new StringBuilder();

        int rpmCount = MapBinning.getRpmCount();
        int mapCount = MapBinning.MAP_BINS.length;

        // Header row
        sb.append("MAP kPa \\ RPM");
        for (int c = 0; c < rpmCount; c++) {
            sb.append(",").append(MapBinning.rpmForColumn(c));
        }
        sb.append("\n");

        // Rows
        for (int r = 0; r < mapCount; r++) {
            float mapValue = MapBinning.mapForRow(r);
            sb.append(String.format(java.util.Locale.US, "%.2f", mapValue));

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
}
