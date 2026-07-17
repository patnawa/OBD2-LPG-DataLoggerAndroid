package com.alpha.obd2logger.can;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class IsoTpReassemblerTest {
    @Test
    public void reassemblesSingleFrame() {
        IsoTpReassembler reassembler = reassembler();
        IsoTpReassembler.Result result = reassembler.accept(frame(0x7E8, 10,
                0x03, 0x62, 0xF1, 0x90));

        assertEquals(IsoTpReassembler.Status.COMPLETE, result.getStatus());
        assertArrayEquals(bytes(0x62, 0xF1, 0x90), result.getPayload());
    }

    @Test
    public void reassemblesFirstAndConsecutiveFramesAndProducesAdviceOnly() {
        IsoTpReassembler reassembler = reassembler();
        IsoTpReassembler.Result first = reassembler.accept(frame(0x7E8, 10,
                0x10, 0x0B, 0x62, 0xF1, 0x90, 0x31, 0x32, 0x33));

        assertEquals(IsoTpReassembler.Status.IN_PROGRESS, first.getStatus());
        assertNotNull(first.getFlowControlAdvice());
        assertEquals(0x7E0, first.getFlowControlAdvice().getTargetArbitrationId());
        assertEquals(0, first.getFlowControlAdvice().getBlockSize());

        IsoTpReassembler.Result complete = reassembler.accept(frame(0x7E8, 20,
                0x21, 0x34, 0x35, 0x36, 0x37, 0x38, 0x00, 0x00));
        assertEquals(IsoTpReassembler.Status.COMPLETE, complete.getStatus());
        assertArrayEquals(bytes(0x62, 0xF1, 0x90, 0x31, 0x32, 0x33,
                0x34, 0x35, 0x36, 0x37, 0x38), complete.getPayload());
    }

    @Test
    public void rejectsUnexpectedConsecutiveSequenceAndDropsSession() {
        IsoTpReassembler reassembler = reassembler();
        reassembler.accept(frame(0x7E8, 10, 0x10, 0x08, 0x62, 1, 2, 3, 4, 5));

        IsoTpReassembler.Result result = reassembler.accept(frame(0x7E8, 20,
                0x22, 6, 7, 0, 0, 0, 0, 0));
        assertEquals(IsoTpReassembler.Status.SEQUENCE_ERROR, result.getStatus());
        assertEquals(0, reassembler.getActiveSessionCount());
    }

    @Test
    public void expiresAbandonedSessions() {
        IsoTpReassembler reassembler = reassembler();
        reassembler.accept(frame(0x7E8, 100, 0x10, 0x08, 0x62, 1, 2, 3, 4, 5));

        assertEquals(1, reassembler.expireOlderThan(1_000_101));
        assertEquals(0, reassembler.getActiveSessionCount());
    }

    private static IsoTpReassembler reassembler() {
        return new IsoTpReassembler(1_000_000L, 0x7E0, 0, 0);
    }

    static CanFrame frame(int id, long timestamp, int... data) {
        return new CanFrame(id, false, bytes(data), timestamp);
    }

    static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
}
