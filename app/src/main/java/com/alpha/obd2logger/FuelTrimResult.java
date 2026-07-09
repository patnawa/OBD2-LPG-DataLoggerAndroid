package com.alpha.obd2logger;

public final class FuelTrimResult {
    private final double stft;
    private final double ltft;
    private final double totalCorrection;
    private final String status;
    private final String recommendation;
    private final LPGAnalyzer.TrimVerdict verdict;

    public FuelTrimResult(double stft, double ltft, double totalCorrection,
                           LPGAnalyzer.TrimVerdict verdict, String recommendation) {
        this.stft = stft;
        this.ltft = ltft;
        this.totalCorrection = totalCorrection;
        this.status = verdict.name();
        this.recommendation = recommendation;
        this.verdict = verdict;
    }

    public FuelTrimResult(double stft, double ltft, double totalCorrection, String status, String recommendation) {
        this.stft = stft;
        this.ltft = ltft;
        this.totalCorrection = totalCorrection;
        this.status = status;
        this.recommendation = recommendation;
        // Best-effort derive the verdict from a localized status string so callers
        // that only have the String form can still reason about the verdict.
        LPGAnalyzer.TrimVerdict derived = LPGAnalyzer.TrimVerdict.UNKNOWN;
        if (status != null) {
            String s = status.toUpperCase();
            if (s.contains("LEAN")) derived = LPGAnalyzer.TrimVerdict.LEAN;
            else if (s.contains("RICH")) derived = LPGAnalyzer.TrimVerdict.RICH;
            else if (s.contains("OK")) derived = LPGAnalyzer.TrimVerdict.OK;
        }
        this.verdict = derived;
    }

    public double getStft() {
        return stft;
    }

    public double getLtft() {
        return ltft;
    }

    public double getTotal() {
        return totalCorrection;
    }

    public double getTotalCorrection() {
        return totalCorrection;
    }

    public LPGAnalyzer.TrimVerdict getVerdict() {
        return verdict;
    }

    public String getStatus() {
        return status;
    }

    public String getRecommendation() {
        return recommendation;
    }
}
