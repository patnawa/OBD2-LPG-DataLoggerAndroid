package com.alpha.obd2logger.can;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PassiveCanDiagnosticEngineTest {
    @Test
    public void drainsCapturedFramesIntoCompleteUdsResponse() {
        PassiveCanDiagnosticEngine engine = new PassiveCanDiagnosticEngine(8, 1_000_000L, 0x7E0);
        assertTrue(engine.offerCapturedFrame(IsoTpReassemblerTest.frame(0x7E8, 10,
                0x10, 0x0B, 0x62, 0xF1, 0x90, 0x31, 0x32, 0x33)));
        assertTrue(engine.offerCapturedFrame(IsoTpReassemblerTest.frame(0x7E8, 20,
                0x21, 0x34, 0x35, 0x36, 0x37, 0x38, 0, 0)));

        List<PassiveCanDiagnosticEngine.FrameEvent> events = engine.drain(8);
        assertEquals(2, events.size());
        assertEquals(IsoTpReassembler.Status.IN_PROGRESS,
                events.get(0).getTransportResult().getStatus());
        assertEquals(IsoTpReassembler.Status.COMPLETE,
                events.get(1).getTransportResult().getStatus());
        assertNotNull(events.get(1).getUdsResponse());
        assertEquals(UdsResponseDecoder.Kind.POSITIVE_RESPONSE,
                events.get(1).getUdsResponse().getKind());
        assertEquals(0x22, events.get(1).getUdsResponse().getRequestService());
    }

    @Test
    public void reportsBusOffWithoutRestartingHardware() {
        PassiveCanDiagnosticEngine engine = new PassiveCanDiagnosticEngine(8, 1_000_000L, 0x7E0);
        CanBusHealthMonitor.Snapshot snapshot = engine.updateControllerState(
                CanBusHealthMonitor.ControllerState.BUS_OFF, 100);

        assertEquals(CanBusHealthMonitor.ControllerState.BUS_OFF, snapshot.getState());
        assertTrue(!snapshot.canCapture());
    }
}
