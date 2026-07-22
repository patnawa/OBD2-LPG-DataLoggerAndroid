package com.alpha.obd2logger;

import android.content.Context;

import java.util.List;

/**
 * Mode 06 prognostics producer: records the catalyst monitors' worst
 * margin-to-failure into {@link HealthTrendStore} after every Full Scan.
 *
 * <p>A Mode 06 result passes while {@code min ≤ value ≤ max}. The margin is
 * how deep inside that window the value sits, normalized 0..1 by the window
 * width (0 = on a limit, 1 = dead center — clamped, since some ECUs report
 * values outside their own limits mid-drive-cycle). A catalyst aging toward
 * a P0420 shows up as this margin sliding toward 0 across months of scans —
 * the projection {@link TrendAnalysis#projectDaysToCross} turns that slide
 * into "predicted failure in ~N days".
 *
 * <p>Only catalyst MIDs (0x21–0x24, banks 1–4) are trended: they are the
 * highest-value slow-drift monitors and their limits are stable per vehicle.
 * The worst bank is recorded — one failing bank is a failure regardless of
 * how healthy the others are.
 */
public final class Mode06TrendRecorder {

    static final String SERIES = "mode06_catalyst_margin";

    /** Catalyst monitor MID range (ISO 15031-5: bank 1–4 catalyst). */
    static final int CATALYST_MID_FIRST = 0x21;
    static final int CATALYST_MID_LAST = 0x24;

    private Mode06TrendRecorder() {
    }

    /**
     * Record the worst catalyst margin from one scan's Mode 06 results.
     * No-op (returns false) when the scan carried no catalyst monitor with a
     * usable limit window — never records a fake "healthy 1.0".
     */
    public static boolean record(Context context, List<Mode06Result> results, long epochMs) {
        Double worst = worstCatalystMargin(results);
        if (context == null || worst == null) return false;
        return HealthTrendStore.append(context, SERIES, epochMs, worst);
    }

    /** Pure computation — testable without a Context. Null when no usable monitor. */
    public static Double worstCatalystMargin(List<Mode06Result> results) {
        if (results == null) return null;
        Double worst = null;
        for (Mode06Result r : results) {
            if (r == null) continue;
            int mid = r.getObdMid();
            if (mid < CATALYST_MID_FIRST || mid > CATALYST_MID_LAST) continue;
            double min = r.getScaledMin();
            double max = r.getScaledMax();
            double value = r.getScaledValue();
            double range = max - min;
            if (!Double.isFinite(range) || range <= 0 || !Double.isFinite(value)) continue;
            double margin = Math.min(value - min, max - value) / range;
            margin = Math.max(0.0, Math.min(1.0, margin));
            if (worst == null || margin < worst) worst = margin;
        }
        return worst;
    }
}
