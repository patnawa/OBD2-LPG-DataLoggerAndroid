package com.alpha.obd2logger.can;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CanTelemetryRingBufferTest {
    @Test
    public void preservesOrderAndMakesOverflowObservable() {
        CanTelemetryRingBuffer buffer = new CanTelemetryRingBuffer(2);
        CanFrame first = IsoTpReassemblerTest.frame(0x100, 1, 0x01, 0xAA);
        CanFrame second = IsoTpReassemblerTest.frame(0x101, 2, 0x01, 0xBB);

        assertTrue(buffer.offer(first));
        assertTrue(buffer.offer(second));
        assertFalse(buffer.offer(IsoTpReassemblerTest.frame(0x102, 3, 0x01, 0xCC)));
        assertEquals(1, buffer.getDroppedFrameCount());
        assertEquals(2, buffer.size());

        assertEquals(0x100, buffer.poll().getArbitrationId());
        assertEquals(0x101, buffer.poll().getArbitrationId());
        assertNull(buffer.poll());
    }
}
