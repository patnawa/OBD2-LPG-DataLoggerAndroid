package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReconnectBackoffTest {

    @Test
    public void followsCappedExponentialSchedule() {
        assertEquals(1_000L, ReconnectBackoff.delayForAttempt(1));
        assertEquals(2_000L, ReconnectBackoff.delayForAttempt(2));
        assertEquals(4_000L, ReconnectBackoff.delayForAttempt(3));
        assertEquals(8_000L, ReconnectBackoff.delayForAttempt(4));
        assertEquals(16_000L, ReconnectBackoff.delayForAttempt(5));
        assertEquals(30_000L, ReconnectBackoff.delayForAttempt(6));
        assertEquals(30_000L, ReconnectBackoff.delayForAttempt(99));
    }

    @Test
    public void clampsInvalidAttemptToFirstDelay() {
        assertEquals(1_000L, ReconnectBackoff.delayForAttempt(0));
        assertEquals(1_000L, ReconnectBackoff.delayForAttempt(-7));
    }
}
