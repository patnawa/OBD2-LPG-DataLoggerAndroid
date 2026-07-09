package com.alpha.obd2logger;

import android.content.Context;

/**
 * Computes fuel-trim analysis (lean/rich/ok/unknown) from STFT + LTFT.
 *
 * The pure logic lives in {@link #analyzeFuelTrim(Double, Double, FuelMode)} which
 * returns a {@link FuelTrimResult} carrying a {@link TrimVerdict} enum instead of
 * localized strings — so it is unit-testable on the plain JVM. The Context overload
 * {@link #analyzeFuelTrim(Context, Double, Double, FuelMode)} is a thin wrapper that
 * localizes the verdict into the display strings the UI expects.
 */
public final class LPGAnalyzer {

    public enum TrimVerdict { UNKNOWN, OK, LEAN, RICH }

    private LPGAnalyzer() {
    }

    /** Pure, Context-free analysis — returns a verdict enum (testable on JVM). */
    public static FuelTrimResult analyzeFuelTrim(Double stft, Double ltft, FuelMode mode) {
        if (stft == null && ltft == null) {
            return new FuelTrimResult(0.0, 0.0, 0.0, TrimVerdict.UNKNOWN, "");
        }
        if (stft == null || ltft == null) {
            // One of the trims is missing — don't fabricate a zero for it. Report
            // UNKNOWN so the UI can't show a confident OK/Lean/Rich verdict on
            // partial data (the previous code treated null as 0.0, hiding gaps).
            return new FuelTrimResult(
                    stft == null ? 0.0 : stft,
                    ltft == null ? 0.0 : ltft,
                    0.0,
                    TrimVerdict.UNKNOWN,
                    "");
        }
        double st = stft;
        double lt = ltft;
        double total = st + lt;

        TrimVerdict verdict;
        if (total > 10.0) verdict = TrimVerdict.LEAN;
        else if (total < -10.0) verdict = TrimVerdict.RICH;
        else verdict = TrimVerdict.OK;

        return new FuelTrimResult(st, lt, total, verdict, "");
    }

    /** Android wrapper — localizes the verdict into display strings. */
    public static FuelTrimResult analyzeFuelTrim(Context context, Double stft, Double ltft, FuelMode mode) {
        FuelTrimResult r = analyzeFuelTrim(stft, ltft, mode);
        String status;
        String recommendation;
        switch (r.getVerdict()) {
            case LEAN:
                status = context.getString(R.string.analyzer_lean);
                recommendation = (mode == FuelMode.PETROL)
                        ? context.getString(R.string.analyzer_petrol_lean)
                        : context.getString(R.string.analyzer_lpg_lean);
                break;
            case RICH:
                status = context.getString(R.string.analyzer_rich);
                recommendation = (mode == FuelMode.PETROL)
                        ? context.getString(R.string.analyzer_petrol_rich)
                        : context.getString(R.string.analyzer_lpg_rich);
                break;
            case OK:
                status = context.getString(R.string.analyzer_ok);
                recommendation = (mode == FuelMode.PETROL)
                        ? context.getString(R.string.analyzer_petrol_ok)
                        : context.getString(R.string.analyzer_lpg_ok);
                break;
            default: // UNKNOWN
                status = context.getString(R.string.analyzer_unknown);
                recommendation = context.getString(R.string.analyzer_no_data);
                break;
        }
        return new FuelTrimResult(r.getStft(), r.getLtft(), r.getTotal(), status, recommendation);
    }
}
