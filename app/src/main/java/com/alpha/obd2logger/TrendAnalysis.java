package com.alpha.obd2logger;

import java.util.List;

/**
 * Pure-math analysis over a {@link HealthTrendStore} series: least-squares
 * drift rate, windowed level shift, and time-to-threshold projection.
 *
 * <p>Deliberately free of Android and storage dependencies so every consumer
 * (ΔVE drift, battery crank RUL, Mode 06 catalyst trending) shares one tested
 * implementation, and tests can feed synthetic series directly.
 */
public final class TrendAnalysis {

    private static final double MS_PER_DAY = 86_400_000.0;

    /** Result of {@link #analyze}. Immutable. */
    public static final class Result {
        /** Number of points analyzed. */
        public final int count;
        /** Least-squares slope in value-units per day. 0 when count < 2. */
        public final double slopePerDay;
        /** Mean of the oldest window. NaN when count < 2×window. */
        public final double firstWindowMean;
        /** Mean of the newest window. NaN when count < 2×window. */
        public final double lastWindowMean;
        /**
         * lastWindowMean − firstWindowMean; the robust "has the level moved"
         * signal — NaN when windows are unavailable. Less noise-sensitive than
         * the slope for short series with outliers.
         */
        public final double levelShift;

        Result(int count, double slopePerDay,
               double firstWindowMean, double lastWindowMean) {
            this.count = count;
            this.slopePerDay = slopePerDay;
            this.firstWindowMean = firstWindowMean;
            this.lastWindowMean = lastWindowMean;
            this.levelShift = (Double.isNaN(firstWindowMean) || Double.isNaN(lastWindowMean))
                    ? Double.NaN : lastWindowMean - firstWindowMean;
        }
    }

    private TrendAnalysis() {
    }

    /**
     * @param points oldest-first series
     * @param window points per comparison window (≥1); windows never overlap —
     *               when the series is shorter than 2×window the level shift
     *               is reported NaN rather than computed from shared points
     */
    public static Result analyze(List<HealthTrendStore.Point> points, int window) {
        int n = points == null ? 0 : points.size();
        if (n == 0) return new Result(0, 0.0, Double.NaN, Double.NaN);
        int w = Math.max(1, window);

        double slope = 0.0;
        if (n >= 2) {
            // Least squares on (days since first point, value).
            long t0 = points.get(0).epochMs;
            double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
            for (HealthTrendStore.Point p : points) {
                double x = (p.epochMs - t0) / MS_PER_DAY;
                sumX += x;
                sumY += p.value;
                sumXY += x * p.value;
                sumXX += x * x;
            }
            double denom = n * sumXX - sumX * sumX;
            // Sub-millisecond total span → no usable time base; report 0 drift.
            slope = Math.abs(denom) < 1e-12 ? 0.0 : (n * sumXY - sumX * sumY) / denom;
        }

        double first = Double.NaN;
        double last = Double.NaN;
        if (n >= 2 * w) {
            first = windowMean(points, 0, w);
            last = windowMean(points, n - w, w);
        }
        return new Result(n, slope, first, last);
    }

    /**
     * Days until the least-squares line crosses {@code threshold}, from the
     * newest point's fitted value. Returns NaN when the trend does not move
     * toward the threshold (or is flat) — a projection in the wrong direction
     * is prognostic noise, not a prediction.
     */
    public static double projectDaysToCross(List<HealthTrendStore.Point> points,
                                            double threshold) {
        int n = points == null ? 0 : points.size();
        if (n < 2) return Double.NaN;
        Result r = analyze(points, 1);
        if (r.slopePerDay == 0.0 || !Double.isFinite(r.slopePerDay)) return Double.NaN;

        long t0 = points.get(0).epochMs;
        double lastX = (points.get(n - 1).epochMs - t0) / MS_PER_DAY;
        // Fitted value at the newest point (intercept from the same fit).
        double meanX = 0, meanY = 0;
        for (HealthTrendStore.Point p : points) {
            meanX += (p.epochMs - t0) / MS_PER_DAY;
            meanY += p.value;
        }
        meanX /= n;
        meanY /= n;
        double fittedNow = meanY + r.slopePerDay * (lastX - meanX);

        double days = (threshold - fittedNow) / r.slopePerDay;
        return days > 0 ? days : Double.NaN;
    }

    private static double windowMean(List<HealthTrendStore.Point> points, int from, int count) {
        double sum = 0;
        for (int i = from; i < from + count; i++) {
            sum += points.get(i).value;
        }
        return sum / count;
    }
}
