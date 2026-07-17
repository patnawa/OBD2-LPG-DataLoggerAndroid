package com.alpha.obd2logger.can;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only CAN diagnostic pipeline for a future native/listen-only
 * transceiver. A receive callback calls {@link #offerCapturedFrame}; a
 * dedicated consumer calls {@link #drain}. No method can inject a CAN or UDS
 * request, which keeps this engine suitable for passive telemetry and offline
 * evidence analysis.
 */
public final class PassiveCanDiagnosticEngine {
    public static final class FrameEvent {
        private final CanFrame frame;
        private final List<CanAnomalyDetector.Signal> anomalySignals;
        private final IsoTpReassembler.Result transportResult;
        private final UdsResponseDecoder.DecodedResponse udsResponse;

        FrameEvent(CanFrame frame, List<CanAnomalyDetector.Signal> anomalySignals,
                   IsoTpReassembler.Result transportResult,
                   UdsResponseDecoder.DecodedResponse udsResponse) {
            this.frame = frame;
            this.anomalySignals = anomalySignals.isEmpty() ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(anomalySignals));
            this.transportResult = transportResult;
            this.udsResponse = udsResponse;
        }

        public CanFrame getFrame() { return frame; }
        public List<CanAnomalyDetector.Signal> getAnomalySignals() { return anomalySignals; }
        public IsoTpReassembler.Result getTransportResult() { return transportResult; }
        public UdsResponseDecoder.DecodedResponse getUdsResponse() { return udsResponse; }
    }

    private final CanTelemetryRingBuffer receiveBuffer;
    private final IsoTpReassembler reassembler;
    private final CanAnomalyDetector anomalyDetector;
    private final CanBusHealthMonitor busHealthMonitor;

    public PassiveCanDiagnosticEngine(int ringCapacity, long isotpTimeoutNanos,
                                      int flowControlTargetId) {
        this.receiveBuffer = new CanTelemetryRingBuffer(ringCapacity);
        this.reassembler = new IsoTpReassembler(isotpTimeoutNanos, flowControlTargetId,
                0, 0);
        this.anomalyDetector = new CanAnomalyDetector();
        this.busHealthMonitor = new CanBusHealthMonitor();
    }

    /** Called only by a native/controller receive callback. */
    public boolean offerCapturedFrame(CanFrame frame) {
        return receiveBuffer.offer(frame);
    }

    /**
     * Decode up to {@code maxFrames} queued frames on the consumer thread.
     * Complete ISO-TP payloads are decoded as observed UDS responses.
     */
    public List<FrameEvent> drain(int maxFrames) {
        if (maxFrames <= 0) throw new IllegalArgumentException("maxFrames must be positive");
        List<FrameEvent> events = new ArrayList<>(Math.min(maxFrames, receiveBuffer.size()));
        for (int i = 0; i < maxFrames; i++) {
            CanFrame frame = receiveBuffer.poll();
            if (frame == null) break;
            List<CanAnomalyDetector.Signal> signals = anomalyDetector.observe(frame);
            IsoTpReassembler.Result transport = reassembler.accept(frame);
            UdsResponseDecoder.DecodedResponse uds = transport.isComplete()
                    ? UdsResponseDecoder.decode(transport.getPayload()) : null;
            events.add(new FrameEvent(frame, signals, transport, uds));
        }
        return Collections.unmodifiableList(events);
    }

    /** Hardware drivers may report state changes; this engine does not reset controllers. */
    public CanBusHealthMonitor.Snapshot updateControllerState(
            CanBusHealthMonitor.ControllerState state, long timestampNanos) {
        return busHealthMonitor.update(state, timestampNanos);
    }

    public CanBusHealthMonitor.Snapshot getBusHealth() {
        return busHealthMonitor.getSnapshot();
    }

    public long getDroppedFrameCount() {
        return receiveBuffer.getDroppedFrameCount();
    }

    public int getQueuedFrameCount() {
        return receiveBuffer.size();
    }

    public int expireIsoTpSessions(long nowNanos) {
        return reassembler.expireOlderThan(nowNanos);
    }
}
