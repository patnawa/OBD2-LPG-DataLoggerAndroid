package com.alpha.obd2logger;

import android.content.Context;

import java.util.List;

/**
 * Cross-session ΔVE drift alarm — the endgame of the learned VE map.
 *
 * <p>A single session's petrol−LPG ΔVE says "LPG breathes N points worse
 * here", which is expected physics. What indicates a degrading gaseous fuel
 * system (vaporizer icing/aging, mixer fouling, gas-injector coking) is that
 * gap <em>widening over weeks</em>. This tracker records one point per
 * logging session — the average mature-cell ΔVE — into
 * {@link HealthTrendStore} and classifies the drift with
 * {@link TrendAnalysis}.
 *
 * <p>Thresholds are deliberately conservative: VE cells carry ~1-point noise,
 * so the alarm needs a sustained multi-point level shift across session
 * windows, never a two-session blip.
 */
public final class VeTrendTracker {

    static final String SERIES = "delta_ve_avg";

    /** Sessions per comparison window. */
    static final int WINDOW = 3;
    /** Level shift (VE points) that flags WATCH. */
    static final double WATCH_SHIFT = 1.5;
    /** Level shift (VE points) that flags ALARM. */
    static final double ALARM_SHIFT = 3.0;
    /** A session needs this many mature overlapping cells to produce a point. */
    static final int MIN_OVERLAP_CELLS = 3;

    public enum Status {
        /** Not enough history to judge (fewer than 2 full windows). */
        INSUFFICIENT,
        /** ΔVE level is stable. */
        OK,
        /** ΔVE has widened noticeably — start paying attention. */
        WATCH,
        /** Sustained multi-point widening — service the gaseous fuel system. */
        ALARM
    }

    /** Immutable verdict for UI/API consumption. */
    public static final class Verdict {
        public final Status status;
        /** Level shift in VE points (newest window − oldest window), or NaN. */
        public final double shiftPoints;
        /** Drift rate in VE points per day (least squares), or 0. */
        public final double slopePerDay;
        /** Sessions recorded. */
        public final int sessions;

        Verdict(Status status, double shiftPoints, double slopePerDay, int sessions) {
            this.status = status;
            this.shiftPoints = shiftPoints;
            this.slopePerDay = slopePerDay;
            this.sessions = sessions;
        }
    }

    private VeTrendTracker() {
    }

    /**
     * Record this session's ΔVE point, called once at session teardown.
     * No-op when the session produced too few mature overlapping cells —
     * a petrol-only or LPG-only drive has no ΔVE to report, and recording
     * 0.0 for it would drag the trend toward "healthy".
     *
     * @return true when a point was recorded
     */
    public static boolean recordSession(Context context, VeMapStore.VeSnapshot snapshot,
                                        long epochMs) {
        if (context == null || snapshot == null) return false;
        if (!snapshot.isComparisonAxisCompatible()) return false;
        if (snapshot.getOverlappingCellCount() < MIN_OVERLAP_CELLS) return false;
        return HealthTrendStore.append(context, SERIES, epochMs,
                snapshot.getAveragePetrolMinusLpg());
    }

    /** Classify the stored history. */
    public static Verdict evaluate(Context context) {
        return evaluate(HealthTrendStore.read(context, SERIES));
    }

    /** Pure evaluation — testable without a Context. */
    public static Verdict evaluate(List<HealthTrendStore.Point> points) {
        TrendAnalysis.Result r = TrendAnalysis.analyze(points, WINDOW);
        if (Double.isNaN(r.levelShift)) {
            return new Verdict(Status.INSUFFICIENT, Double.NaN, r.slopePerDay, r.count);
        }
        // Only a WIDENING gap (petrol−LPG growing more positive) indicates
        // gaseous-side degradation. A shrinking gap is not an LPG fault
        // signature — it points at the petrol side or sensors, and gets its
        // own investigation path rather than a false LPG service alarm.
        Status status;
        if (r.levelShift >= ALARM_SHIFT) {
            status = Status.ALARM;
        } else if (r.levelShift >= WATCH_SHIFT) {
            status = Status.WATCH;
        } else {
            status = Status.OK;
        }
        return new Verdict(status, r.levelShift, r.slopePerDay, r.count);
    }

    public static void clear(Context context) {
        HealthTrendStore.clear(context, SERIES);
    }
}
