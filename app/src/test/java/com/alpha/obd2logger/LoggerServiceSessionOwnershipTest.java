package com.alpha.obd2logger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression coverage for rapid foreground-service stop/start ownership. */
public class LoggerServiceSessionOwnershipTest {

    @Test
    public void runningFlagReturningTrueDoesNotReviveOldWorker() {
        long oldToken = 41L;
        long newToken = 42L;

        assertTrue(LoggerService.sameActiveSession(true, oldToken, oldToken));
        assertFalse("a stopped session owns no work",
                LoggerService.sameActiveSession(false, oldToken, oldToken));
        assertFalse("new running=true must not revive the old worker",
                LoggerService.sameActiveSession(true, newToken, oldToken));
        assertFalse("zero remains the no-session sentinel",
                LoggerService.sameActiveSession(true, 0L, 0L));
    }
}
