package com.alpha.obd2logger;

/**
 * Immutable fuel-trim analysis result.
 * Always carries an explicit {@link LPGAnalyzer.TrimVerdict} enum — never reverse-engineered
 * from a localized status string (that broke Thai "PERFECT" → UNKNOWN).
 */
public final class FuelTrimResult {
    private final double stft;
    private final double ltft;
    private final double stftB2;
    private final double ltftB2;
    private final double totalCorrection;
    private final double stftStd;
    private final String status;
    private final String recommendation;
    private final LPGAnalyzer.TrimVerdict verdict;
    private final LPGAnalyzer.GateReason gateReason;
    private final int confidence; // 0–100
    private final boolean bankImbalance;
    private final boolean hasStft;
    private final boolean hasLtft;

    public FuelTrimResult(double stft, double ltft, double totalCorrection,
                          LPGAnalyzer.TrimVerdict verdict, String recommendation) {
        this(stft, ltft, Double.NaN, Double.NaN, totalCorrection, Double.NaN,
                verdict != null ? verdict.name() : "UNKNOWN",
                recommendation != null ? recommendation : "",
                verdict != null ? verdict : LPGAnalyzer.TrimVerdict.UNKNOWN,
                LPGAnalyzer.GateReason.NONE, 50, false,
                !Double.isNaN(stft), !Double.isNaN(ltft));
    }

    public FuelTrimResult(double stft, double ltft, double totalCorrection,
                          String status, String recommendation) {
        // Legacy string ctor — only for tests / compatibility. Prefer enum ctor.
        LPGAnalyzer.TrimVerdict derived = LPGAnalyzer.TrimVerdict.UNKNOWN;
        if (status != null) {
            String s = status.toUpperCase();
            if (s.contains("LEAN")) derived = LPGAnalyzer.TrimVerdict.LEAN;
            else if (s.contains("RICH")) derived = LPGAnalyzer.TrimVerdict.RICH;
            else if (s.contains("OK") || s.contains("PERFECT")) derived = LPGAnalyzer.TrimVerdict.OK;
            else if (s.contains("UNSTABLE") || s.contains("INTERMIT")) derived = LPGAnalyzer.TrimVerdict.UNSTABLE;
            else if (s.contains("WARM") || s.contains("OPEN") || s.contains("COLD") || s.contains("UNKNOWN"))
                derived = LPGAnalyzer.TrimVerdict.UNKNOWN;
        }
        this.stft = stft;
        this.ltft = ltft;
        this.stftB2 = Double.NaN;
        this.ltftB2 = Double.NaN;
        this.totalCorrection = totalCorrection;
        this.stftStd = Double.NaN;
        this.status = status;
        this.recommendation = recommendation;
        this.verdict = derived;
        this.gateReason = LPGAnalyzer.GateReason.NONE;
        this.confidence = 50;
        this.bankImbalance = false;
        this.hasStft = true;
        this.hasLtft = true;
    }

    public FuelTrimResult(double stft, double ltft, double stftB2, double ltftB2,
                          double totalCorrection, double stftStd,
                          String status, String recommendation,
                          LPGAnalyzer.TrimVerdict verdict,
                          LPGAnalyzer.GateReason gateReason,
                          int confidence, boolean bankImbalance,
                          boolean hasStft, boolean hasLtft) {
        this.stft = stft;
        this.ltft = ltft;
        this.stftB2 = stftB2;
        this.ltftB2 = ltftB2;
        this.totalCorrection = totalCorrection;
        this.stftStd = stftStd;
        this.status = status != null ? status : "";
        this.recommendation = recommendation != null ? recommendation : "";
        this.verdict = verdict != null ? verdict : LPGAnalyzer.TrimVerdict.UNKNOWN;
        this.gateReason = gateReason != null ? gateReason : LPGAnalyzer.GateReason.NONE;
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.bankImbalance = bankImbalance;
        this.hasStft = hasStft;
        this.hasLtft = hasLtft;
    }

    public double getStft() { return stft; }
    public double getLtft() { return ltft; }
    public double getStftB2() { return stftB2; }
    public double getLtftB2() { return ltftB2; }
    public double getTotal() { return totalCorrection; }
    public double getTotalCorrection() { return totalCorrection; }
    public double getStftStd() { return stftStd; }
    public LPGAnalyzer.TrimVerdict getVerdict() { return verdict; }
    public LPGAnalyzer.GateReason getGateReason() { return gateReason; }
    public int getConfidence() { return confidence; }
    public boolean isBankImbalance() { return bankImbalance; }
    public boolean hasStft() { return hasStft; }
    public boolean hasLtft() { return hasLtft; }
    public String getStatus() { return status; }
    public String getRecommendation() { return recommendation; }

    /** Color role for professional UI: "ok" | "lean" | "rich" | "warn" | "muted". */
    public String colorRole() {
        switch (verdict) {
            case OK: return "ok";
            case LEAN: return "lean";
            case RICH: return "rich";
            case UNSTABLE: return "warn";
            case UNKNOWN:
            default: return "muted";
        }
    }
}
