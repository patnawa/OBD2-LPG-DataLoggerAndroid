package com.alpha.obd2logger;

/** Deterministic, capped exponential backoff for adapter reconnection. */
public final class ReconnectBackoff {
    public static final long INITIAL_DELAY_MS = 1_000L;
    public static final long MAX_DELAY_MS = 30_000L;

    private ReconnectBackoff() {
    }

    /** Attempt 1..n becomes 1s, 2s, 4s, 8s and then caps at 30s. */
    public static long delayForAttempt(int attempt) {
        int safeAttempt = Math.max(1, attempt);
        int exponent = Math.min(5, safeAttempt - 1);
        return Math.min(MAX_DELAY_MS, INITIAL_DELAY_MS << exponent);
    }
}
