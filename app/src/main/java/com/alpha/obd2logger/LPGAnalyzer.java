package com.alpha.obd2logger;

import android.content.Context;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Professional OBD2 fuel-trim analyzer for petrol baseline and LPG/CNG tune work.
 *
 * <h3>Algorithm (shop-grade)</h3>
 * <ol>
 *   <li>Gate on closed-loop + warm ECT (≥80 °C). Cold/open-loop → UNKNOWN.</li>
 *   <li>Use LTFT as primary drift metric (map multiplier). STFT is secondary
 *       (oscillation / transport lag).</li>
 *   <li>Merge Bank1 / Bank2 when both present (average) with imbalance warning.</li>
 *   <li>Mode thresholds:
 *       Petrol prep tighter (|LTFT|≤5 OK), LPG/CNG looser (|LTFT|≤8 OK).</li>
 *   <li>STFT std-dev over a short window → UNSTABLE (don't log cell averages).</li>
 * </ol>
 *
 * Pure path is JVM-testable. Context overload localizes status/advice and keeps
 * the {@link TrimVerdict} enum intact (Thai "PERFECT" must stay OK).
 */
public final class LPGAnalyzer {

    public enum TrimVerdict { UNKNOWN, OK, LEAN, RICH, UNSTABLE }

    public enum GateReason {
        NONE,
        NO_DATA,
        PARTIAL_DATA,
        OPEN_LOOP,
        COLD_ENGINE,
        DIESEL_NO_TRIM,
        UNSTABLE_TRIM
    }

    // ── Thresholds (percent) ──────────────────────────────────────────
    /** Petrol baseline — safe to start LPG map work only if within this. */
    public static final double PETROL_OK = 5.0;
    public static final double PETROL_WARN = 8.0;
    /** LPG/CNG while mapping. */
    public static final double LPG_OK = 8.0;
    public static final double LPG_WARN = 12.0;
    /** STFT std over the sample window above this → UNSTABLE. */
    public static final double STFT_STD_UNSTABLE = 6.0;
    /** |Bank1 − Bank2| LTFT above this → imbalance flag. */
    public static final double BANK_IMBALANCE = 5.0;
    public static final double WARM_ECT_C = 80.0;

    private static final int STFT_WINDOW = 12; // ~6 s at 500 ms sample

    private static final ArrayDeque<Double> stftWindow = new ArrayDeque<>(STFT_WINDOW);

    private LPGAnalyzer() {}

    /** Reset EWMA / STFT window (call on Start logging / fuel switch). */
    public static synchronized void resetHistory() {
        stftWindow.clear();
    }

    // ── Live snapshot API ─────────────────────────────────────────────

    /**
     * Full analysis from live OBD values. Null PIDs allowed.
     *
     * @param stftB1 Bank1 STFT (01_06)
     * @param ltftB1 Bank1 LTFT (01_07)
     * @param stftB2 Bank2 STFT (01_08) or null
     * @param ltftB2 Bank2 LTFT (01_09) or null
     * @param fuelStatus PID 03 value (null = assume closed)
     * @param ect Coolant °C (null = skip warm gate)
     * @param mode Fuel mode
     */
    public static FuelTrimResult analyze(Double stftB1, Double ltftB1,
                                         Double stftB2, Double ltftB2,
                                         Double fuelStatus, Double ect,
                                         FuelMode mode) {
        if (mode != null && mode.isDiesel()) {
            return gated(0, 0, Double.NaN, Double.NaN, 0, Double.NaN,
                    TrimVerdict.UNKNOWN, GateReason.DIESEL_NO_TRIM, 0,
                    false, false, false);
        }

        boolean closedLoop = true;
        if (fuelStatus != null) {
            closedLoop = (fuelStatus.intValue() & 0x02) != 0;
        }
        boolean warm = (ect == null) || (ect >= WARM_ECT_C);

        if (!closedLoop) {
            return gated(nz(stftB1), nz(ltftB1), nanz(stftB2), nanz(ltftB2),
                    0, Double.NaN, TrimVerdict.UNKNOWN, GateReason.OPEN_LOOP, 0,
                    false, stftB1 != null, ltftB1 != null);
        }
        if (!warm) {
            return gated(nz(stftB1), nz(ltftB1), nanz(stftB2), nanz(ltftB2),
                    0, Double.NaN, TrimVerdict.UNKNOWN, GateReason.COLD_ENGINE, 0,
                    false, stftB1 != null, ltftB1 != null);
        }

        boolean hasStft = stftB1 != null || stftB2 != null;
        boolean hasLtft = ltftB1 != null || ltftB2 != null;
        if (!hasStft && !hasLtft) {
            return gated(0, 0, Double.NaN, Double.NaN, 0, Double.NaN,
                    TrimVerdict.UNKNOWN, GateReason.NO_DATA, 0, false, false, false);
        }

        // Merge banks: preferred average when both present
        Double stft = mergeBanks(stftB1, stftB2);
        Double ltft = mergeBanks(ltftB1, ltftB2);
        boolean imbalance = false;
        if (ltftB1 != null && ltftB2 != null && Math.abs(ltftB1 - ltftB2) >= BANK_IMBALANCE) {
            imbalance = true;
        }
        if (stftB1 != null && stftB2 != null && Math.abs(stftB1 - stftB2) >= BANK_IMBALANCE) {
            imbalance = true;
        }

        // Partial: only one of STFT/LTFT
        if ((stft == null) ^ (ltft == null)) {
            double alone = stft != null ? stft : ltft;
            return gated(nz(stft), nz(ltft), nanz(stftB2), nanz(ltftB2),
                    alone, Double.NaN, TrimVerdict.UNKNOWN, GateReason.PARTIAL_DATA,
                    30, imbalance, hasStft, hasLtft);
        }

        // STFT stability window
        double stftStd = pushAndStd(stft);

        double st = stft;
        double lt = ltft;
        // Primary drift metric = LTFT; STFT contributes soft total for display
        double total = st + lt;

        boolean gaseous = mode != null && mode.isGaseous();
        double okBand = gaseous ? LPG_OK : PETROL_OK;
        double warnBand = gaseous ? LPG_WARN : PETROL_WARN;

        TrimVerdict verdict;
        GateReason reason = GateReason.NONE;
        int confidence = 80;

        if (!Double.isNaN(stftStd) && stftStd >= STFT_STD_UNSTABLE) {
            verdict = TrimVerdict.UNSTABLE;
            reason = GateReason.UNSTABLE_TRIM;
            confidence = 55;
        } else {
            // LTFT-primary with STFT assist for severe transients
            double drift = lt;
            // If LTFT mild but STFT large and same sign, escalate
            if (Math.abs(lt) < okBand && Math.abs(st) >= warnBand && Math.signum(st) == Math.signum(lt == 0 ? st : lt)) {
                drift = st; // STFT dominating
                confidence = 60;
            }
            if (drift > warnBand) {
                verdict = TrimVerdict.LEAN;
            } else if (drift < -warnBand) {
                verdict = TrimVerdict.RICH;
            } else if (drift > okBand) {
                verdict = TrimVerdict.LEAN; // mild lean inside warn — still LEAN, lower conf
                confidence = 65;
            } else if (drift < -okBand) {
                verdict = TrimVerdict.RICH;
                confidence = 65;
            } else {
                verdict = TrimVerdict.OK;
                confidence = 90;
            }
        }
        if (imbalance) confidence = Math.max(40, confidence - 15);

        return gated(st, lt, nanz(stftB2), nanz(ltftB2), total, stftStd,
                verdict, reason, confidence, imbalance, true, true);
    }

    /** Backward-compatible pure API (Bank1 only, assume closed/warm). */
    public static FuelTrimResult analyzeFuelTrim(Double stft, Double ltft, FuelMode mode) {
        return analyze(stft, ltft, null, null, /*fuelStatus*/ 2.0, /*ect*/ 90.0, mode);
    }

    /** Android wrapper — localizes while preserving the enum verdict. */
    public static FuelTrimResult analyzeFuelTrim(Context context, Double stft, Double ltft, FuelMode mode) {
        FuelTrimResult r = analyzeFuelTrim(stft, ltft, mode);
        return localize(context, r, mode);
    }

    /** Full live analysis + localization. */
    public static FuelTrimResult analyzeLocalized(Context context,
                                                  Double stftB1, Double ltftB1,
                                                  Double stftB2, Double ltftB2,
                                                  Double fuelStatus, Double ect,
                                                  FuelMode mode) {
        FuelTrimResult r = analyze(stftB1, ltftB1, stftB2, ltftB2, fuelStatus, ect, mode);
        return localize(context, r, mode);
    }

    public static FuelTrimResult localize(Context context, FuelTrimResult r, FuelMode mode) {
        if (context == null) return r;
        boolean gaseous = mode != null && mode.isGaseous();
        String status;
        String advice;
        switch (r.getVerdict()) {
            case LEAN:
                status = context.getString(R.string.analyzer_lean);
                advice = gaseous
                        ? context.getString(R.string.analyzer_lpg_lean)
                        : context.getString(R.string.analyzer_petrol_lean);
                advice = appendMagnitude(advice, r.getLtft(), gaseous, true);
                break;
            case RICH:
                status = context.getString(R.string.analyzer_rich);
                advice = gaseous
                        ? context.getString(R.string.analyzer_lpg_rich)
                        : context.getString(R.string.analyzer_petrol_rich);
                advice = appendMagnitude(advice, r.getLtft(), gaseous, false);
                break;
            case OK:
                status = context.getString(R.string.analyzer_ok);
                advice = gaseous
                        ? context.getString(R.string.analyzer_lpg_ok)
                        : context.getString(R.string.analyzer_petrol_ok);
                break;
            case UNSTABLE:
                status = context.getString(R.string.analyzer_unstable);
                advice = context.getString(R.string.analyzer_unstable_advice);
                break;
            default: // UNKNOWN
                status = context.getString(R.string.analyzer_unknown);
                advice = adviceForGate(context, r.getGateReason());
                break;
        }
        if (r.isBankImbalance()) {
            advice = advice + " " + context.getString(R.string.analyzer_bank_imbalance);
        }
        return new FuelTrimResult(
                r.getStft(), r.getLtft(), r.getStftB2(), r.getLtftB2(),
                r.getTotal(), r.getStftStd(),
                status, advice, r.getVerdict(), r.getGateReason(),
                r.getConfidence(), r.isBankImbalance(),
                r.hasStft(), r.hasLtft());
    }

    private static String adviceForGate(Context ctx, GateReason g) {
        if (g == null) return ctx.getString(R.string.analyzer_no_data);
        switch (g) {
            case OPEN_LOOP: return ctx.getString(R.string.analyzer_open_loop);
            case COLD_ENGINE: return ctx.getString(R.string.analyzer_cold_engine);
            case PARTIAL_DATA: return ctx.getString(R.string.analyzer_partial_data);
            case DIESEL_NO_TRIM: return ctx.getString(R.string.analyzer_diesel_no_trim);
            case UNSTABLE_TRIM: return ctx.getString(R.string.analyzer_unstable_advice);
            case NO_DATA:
            default: return ctx.getString(R.string.analyzer_no_data);
        }
    }

    private static String appendMagnitude(String base, double ltft, boolean gaseous, boolean lean) {
        if (Double.isNaN(ltft)) return base;
        double abs = Math.abs(ltft);
        String dir = gaseous
                ? (lean ? String.format(Locale.US, " (+%.1f%% map up)", abs)
                        : String.format(Locale.US, " (%.1f%% map down)", -abs))
                : String.format(Locale.US, " (LTFT %.1f%%)", ltft);
        return base + dir;
    }

    private static FuelTrimResult gated(double stft, double ltft, double stftB2, double ltftB2,
                                        double total, double stftStd,
                                        TrimVerdict verdict, GateReason reason,
                                        int confidence, boolean imbalance,
                                        boolean hasStft, boolean hasLtft) {
        return new FuelTrimResult(stft, ltft, stftB2, ltftB2, total, stftStd,
                verdict.name(), "", verdict, reason, confidence, imbalance, hasStft, hasLtft);
    }

    private static Double mergeBanks(Double b1, Double b2) {
        if (b1 != null && b2 != null) return (b1 + b2) / 2.0;
        if (b1 != null) return b1;
        return b2;
    }

    private static synchronized double pushAndStd(Double stft) {
        if (stft == null) return Double.NaN;
        stftWindow.addLast(stft);
        while (stftWindow.size() > STFT_WINDOW) stftWindow.removeFirst();
        if (stftWindow.size() < 4) return Double.NaN;
        double sum = 0;
        for (double v : stftWindow) sum += v;
        double mean = sum / stftWindow.size();
        double var = 0;
        for (double v : stftWindow) {
            double d = v - mean;
            var += d * d;
        }
        return Math.sqrt(var / stftWindow.size());
    }

    private static double nz(Double d) { return d == null ? 0.0 : d; }
    private static double nanz(Double d) { return d == null ? Double.NaN : d; }
}
