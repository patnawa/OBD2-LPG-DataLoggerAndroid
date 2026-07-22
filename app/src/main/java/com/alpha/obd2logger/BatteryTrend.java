package com.alpha.obd2logger;

import android.content.Context;

import java.util.List;

/**
 * Battery remaining-useful-life estimate from the crank-minimum trend.
 *
 * <p>A single crank test says "the battery cranked at X volts today" —
 * {@link BatteryTester} already grades that. What it cannot say is <em>where
 * the battery is heading</em>: crank minimum declines slowly and near-linearly
 * over months as plates sulfate, then falls off a cliff. Trending the minimum
 * across tests ({@link HealthTrendStore} series {@link #SERIES}, one point per
 * crank test) and projecting the least-squares line onto
 * {@link BatteryTester.Thresholds#CRANK_MIN} turns the grade into
 * "predicted to fail cranking in ~N days" — condition-based replacement
 * instead of a roadside surprise.
 */
public final class BatteryTrend {

    static final String SERIES = "battery_crank_min_v";

    /** Fewer points than this → no projection (two winter cranks ≠ a trend). */
    static final int MIN_POINTS = 4;
    /** Projections beyond this horizon are reported as "no concern". */
    static final double MAX_HORIZON_DAYS = 365.0;

    /** Immutable prognosis for UI/API consumption. */
    public static final class Prognosis {
        /** Crank tests recorded. */
        public final int tests;
        /** Newest crank minimum (V), or NaN when empty. */
        public final double latestV;
        /** Drift in V/day (negative = declining), 0 when < 2 points. */
        public final double slopePerDay;
        /**
         * Days until the fitted line crosses the cranking floor
         * ({@link BatteryTester.Thresholds#CRANK_MIN}); NaN when the trend is
         * flat/improving, history is too short, or the crossing is beyond
         * {@link #MAX_HORIZON_DAYS}.
         */
        public final double daysToCrankFloor;

        Prognosis(int tests, double latestV, double slopePerDay, double daysToCrankFloor) {
            this.tests = tests;
            this.latestV = latestV;
            this.slopePerDay = slopePerDay;
            this.daysToCrankFloor = daysToCrankFloor;
        }
    }

    private BatteryTrend() {
    }

    public static Prognosis evaluate(Context context) {
        return evaluate(HealthTrendStore.read(context, SERIES));
    }

    /** Pure evaluation — testable without a Context. */
    public static Prognosis evaluate(List<HealthTrendStore.Point> points) {
        int n = points == null ? 0 : points.size();
        double latest = n > 0 ? points.get(n - 1).value : Double.NaN;
        TrendAnalysis.Result r = TrendAnalysis.analyze(points, 1);

        double days = Double.NaN;
        // Only project a DECLINING trend with enough history; an improving or
        // flat battery has no failure date, and a short series is winter noise.
        if (n >= MIN_POINTS && r.slopePerDay < 0) {
            double projected = TrendAnalysis.projectDaysToCross(
                    points, BatteryTester.Thresholds.CRANK_MIN);
            if (Double.isFinite(projected) && projected <= MAX_HORIZON_DAYS) {
                days = projected;
            }
        }
        return new Prognosis(n, latest, r.slopePerDay, days);
    }

    public static void clear(Context context) {
        HealthTrendStore.clear(context, SERIES);
    }
}
