package com.alpha.obd2logger;

import android.content.Context;

public final class LPGAnalyzer {
    private LPGAnalyzer() {
    }

    public static FuelTrimResult analyzeFuelTrim(Context context, Double stft, Double ltft, FuelMode mode) {
        if (stft == null && ltft == null) {
            return new FuelTrimResult(0.0, 0.0, 0.0, context.getString(R.string.analyzer_unknown), context.getString(R.string.analyzer_no_data));
        }
        double st = stft == null ? 0.0 : stft;
        double lt = ltft == null ? 0.0 : ltft;
        double total = st + lt;
        String status;
        String recommendation;

        if (mode == FuelMode.PETROL) {
            if (total > 10.0) {
                status = context.getString(R.string.analyzer_lean);
                recommendation = context.getString(R.string.analyzer_petrol_lean);
            } else if (total < -10.0) {
                status = context.getString(R.string.analyzer_rich);
                recommendation = context.getString(R.string.analyzer_petrol_rich);
            } else {
                status = context.getString(R.string.analyzer_ok);
                recommendation = context.getString(R.string.analyzer_petrol_ok);
            }
        } else {
            // LPG Mode
            if (total > 10.0) {
                status = context.getString(R.string.analyzer_lean);
                recommendation = context.getString(R.string.analyzer_lpg_lean);
            } else if (total < -10.0) {
                status = context.getString(R.string.analyzer_rich);
                recommendation = context.getString(R.string.analyzer_lpg_rich);
            } else {
                status = context.getString(R.string.analyzer_ok);
                recommendation = context.getString(R.string.analyzer_lpg_ok);
            }
        }

        return new FuelTrimResult(st, lt, total, status, recommendation);
    }
}
