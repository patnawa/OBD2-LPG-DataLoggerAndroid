package com.alpha.obd2logger;

public final class FuelTrimResult {
    private final double stft;
    private final double ltft;
    private final double totalCorrection;
    private final String status;
    private final String recommendation;

    public FuelTrimResult(double stft, double ltft, double totalCorrection, String status, String recommendation) {
        this.stft = stft;
        this.ltft = ltft;
        this.totalCorrection = totalCorrection;
        this.status = status;
        this.recommendation = recommendation;
    }

    public double getStft() {
        return stft;
    }

    public double getLtft() {
        return ltft;
    }

    public double getTotalCorrection() {
        return totalCorrection;
    }

    public String getStatus() {
        return status;
    }

    public String getRecommendation() {
        return recommendation;
    }
}
