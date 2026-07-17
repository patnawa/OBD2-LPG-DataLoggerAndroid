package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ElmResponseSanitizerTest {

    @Test
    public void removesBinaryNoiseButKeepsElmPromptAndHex() {
        StringBuilder frame = new StringBuilder();
        int[] bytes = { '4', '1', '0', 'C', '\r', 0x00, 0xFF, '\n', '>', 0x01 };
        for (int value : bytes) {
            assertTrue(ElmResponseSanitizer.appendValidated(frame, value));
        }
        assertEquals("410C\r\n>", frame.toString());
    }

    @Test
    public void overlongFrameIsClearedAndRejected() {
        StringBuilder frame = new StringBuilder();
        for (int i = 0; i < ElmResponseSanitizer.MAX_RESPONSE_CHARS; i++) {
            assertTrue(ElmResponseSanitizer.appendValidated(frame, 'A'));
        }
        assertFalse(ElmResponseSanitizer.appendValidated(frame, 'A'));
        assertEquals(0, frame.length());
    }

    @Test
    public void onlyAdapterFaultsRequestOneRecoveryRetry() {
        assertTrue(ElmResponseSanitizer.needsTransportRecovery("BUFFER FULL>"));
        assertTrue(ElmResponseSanitizer.needsTransportRecovery("STOPPED\r>"));
        assertTrue(ElmResponseSanitizer.needsTransportRecovery("?>"));
        assertTrue(ElmResponseSanitizer.needsTransportRecovery("410C1AF8"));
        assertFalse(ElmResponseSanitizer.needsTransportRecovery("NO DATA>"));
        assertTrue(ElmResponseSanitizer.isNoData("NO DATA>"));
    }
}
