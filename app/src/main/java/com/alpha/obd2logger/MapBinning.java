package com.alpha.obd2logger;

import java.util.Locale;

/**
 * Single source of truth for fuel-map grid binning.
 *
 * Previously the fuel map was binned in two places that disagreed:
 *   - {@link FuelMapView} used FLOOR-based RPM binning  (749 → 500)
 *   - {@link ApiServer}    used ROUND-based RPM binning  (750 → 1000)
 *
 * This caused the AI agent (reading via /api/map) to see data in a different
 * cell than the user saw on screen — making "accurate real-time" impossible.
 *
 * Everything now goes through this class so UI, API, SSE, and CSV export
 * all agree on which cell a given (RPM, MAP) sample lands in.
 */
public final class MapBinning {

    // ── Grid configuration ──────────────────────────────────────────────
    public static final int RPM_MIN = 500;
    public static final int RPM_MAX = 6500;
    public static final int RPM_STEP = 500;

    /**
     * Y-axis: MAP (kPa) bins — directly matches what the ECU reports.
     * Range covers vacuum (idle ~30 kPa) to forced induction boost (250 kPa).
     */
    public static final float[] MAP_BINS = {
        10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f, 120f, 150f, 200f, 250f
    };

    private MapBinning() {
    }

    // ── Accessors (for API / CSV consumers) ─────────────────────────────

    public static int getRpmMin() { return RPM_MIN; }
    public static int getRpmMax() { return RPM_MAX; }
    public static int getRpmStep() { return RPM_STEP; }
    public static float[] getMapBins() { return MAP_BINS; }

    /** Number of RPM columns in the grid. */
    public static int getRpmCount() {
        return (RPM_MAX - RPM_MIN) / RPM_STEP + 1;
    }

    // ── Binning ─────────────────────────────────────────────────────────

    /**
     * FLOOR-based RPM binning — 749 → 500, 750 → 750, 1499 → 1000.
     * Clamped to [RPM_MIN, RPM_MAX].
     */
    public static int binRpm(double rpm) {
        int cell = (int) (rpm / RPM_STEP) * RPM_STEP;
        return Math.max(RPM_MIN, Math.min(RPM_MAX, cell));
    }

    /**
     * Closest-bin matching for MAP (kPa).
     * Returns the bin value (not the index).
     */
    public static float binMap(double map) {
        return MAP_BINS[findClosestBinIndex(map, MAP_BINS)];
    }

    /**
     * Canonical cell key used by FuelMapView, ApiServer, and LiveMapStore.
     * Format: "{rpm}_{map:.2f}" e.g. "1500_40.00".
     */
    public static String cellKey(int rpmCell, float mapBin) {
        return rpmCell + "_" + String.format(Locale.US, "%.2f", mapBin);
    }

    /** Convenience: bin + key in one call. */
    public static String cellKey(double rpm, double map) {
        return cellKey(binRpm(rpm), binMap(map));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Find the closest bin index for a value.
     */
    public static int findClosestBinIndex(double value, float[] bins) {
        int bestIdx = 0;
        double minDiff = Double.MAX_VALUE;
        for (int i = 0; i < bins.length; i++) {
            double diff = Math.abs(bins[i] - value);
            if (diff < minDiff) {
                minDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * RPM value for column index c (0-based).
     */
    public static int rpmForColumn(int col) {
        return RPM_MIN + (col * RPM_STEP);
    }

    /**
     * MAP value for row index r (0-based).
     */
    public static float mapForRow(int row) {
        return MAP_BINS[row];
    }
}
