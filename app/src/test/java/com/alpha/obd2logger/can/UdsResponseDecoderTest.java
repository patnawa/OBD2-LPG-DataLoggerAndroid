package com.alpha.obd2logger.can;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UdsResponseDecoderTest {
    @Test
    public void decodesObservedDiagnosticSessionResponse() {
        UdsResponseDecoder.DecodedResponse response = UdsResponseDecoder.decode(
                IsoTpReassemblerTest.bytes(0x50, 0x03, 0x00, 0x32, 0x01));

        assertEquals(UdsResponseDecoder.Kind.POSITIVE_RESPONSE, response.getKind());
        assertEquals(0x10, response.getRequestService());
        assertEquals(0x03, response.getSessionType());
    }

    @Test
    public void decodesNegativeResponseWithoutAttemptingRetry() {
        UdsResponseDecoder.DecodedResponse response = UdsResponseDecoder.decode(
                IsoTpReassemblerTest.bytes(0x7F, 0x22, 0x33));

        assertEquals(UdsResponseDecoder.Kind.NEGATIVE_RESPONSE, response.getKind());
        assertEquals(0x22, response.getRequestService());
        assertEquals(0x33, response.getNegativeResponseCode());
    }
}
