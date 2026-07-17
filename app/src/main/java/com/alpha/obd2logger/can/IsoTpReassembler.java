package com.alpha.obd2logger.can;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Passive ISO 15765-2 reassembler for classic CAN capture streams.
 *
 * <p>The class never writes a frame. For a First Frame it returns a
 * {@link FlowControlAdvice} describing the Flow Control parameters a trusted,
 * separately-authorized transport <em>could</em> use. Keeping the decision and
 * transmission separate prevents this diagnostic layer from injecting traffic
 * onto a vehicle bus.</p>
 */
public final class IsoTpReassembler {
    public enum Status {
        COMPLETE,
        IN_PROGRESS,
        IGNORED_FLOW_CONTROL,
        MALFORMED,
        ORPHAN_CONSECUTIVE_FRAME,
        SEQUENCE_ERROR
    }

    public static final class FlowControlAdvice {
        private final int targetArbitrationId;
        private final boolean extendedId;
        private final int blockSize;
        private final int separationTimeByte;

        FlowControlAdvice(int targetArbitrationId, boolean extendedId,
                          int blockSize, int separationTimeByte) {
            this.targetArbitrationId = targetArbitrationId;
            this.extendedId = extendedId;
            this.blockSize = blockSize;
            this.separationTimeByte = separationTimeByte;
        }

        public int getTargetArbitrationId() { return targetArbitrationId; }
        public boolean isExtendedId() { return extendedId; }
        public int getBlockSize() { return blockSize; }
        public int getSeparationTimeByte() { return separationTimeByte; }
    }

    public static final class Result {
        private final Status status;
        private final int sourceArbitrationId;
        private final byte[] payload;
        private final FlowControlAdvice flowControlAdvice;
        private final String detail;

        private Result(Status status, int sourceArbitrationId, byte[] payload,
                       FlowControlAdvice flowControlAdvice, String detail) {
            this.status = status;
            this.sourceArbitrationId = sourceArbitrationId;
            this.payload = payload != null ? Arrays.copyOf(payload, payload.length) : null;
            this.flowControlAdvice = flowControlAdvice;
            this.detail = detail;
        }

        public Status getStatus() { return status; }
        public int getSourceArbitrationId() { return sourceArbitrationId; }
        public byte[] getPayload() {
            return payload != null ? Arrays.copyOf(payload, payload.length) : null;
        }
        public FlowControlAdvice getFlowControlAdvice() { return flowControlAdvice; }
        public String getDetail() { return detail; }
        public boolean isComplete() { return status == Status.COMPLETE; }
    }

    private static final class Session {
        final int totalLength;
        final ByteArrayOutputStream assembled;
        final long startedNanos;
        int expectedSequence = 1;

        Session(int totalLength, byte[] firstPayload, long startedNanos) {
            this.totalLength = totalLength;
            this.assembled = new ByteArrayOutputStream(totalLength);
            this.assembled.write(firstPayload, 0, firstPayload.length);
            this.startedNanos = startedNanos;
        }
    }

    private final Map<Long, Session> sessions = new HashMap<>();
    private final long timeoutNanos;
    private final int flowControlTargetId;
    private final int recommendedBlockSize;
    private final int recommendedSeparationTimeByte;

    /**
     * @param flowControlTargetId destination an authorized transport would use
     *                            for a response; this class does not send it
     * @param recommendedBlockSize 0 means sender may continue without blocks
     * @param recommendedSeparationTimeByte ISO-TP STmin byte (0..0x7F or F1..F9)
     */
    public IsoTpReassembler(long timeoutNanos, int flowControlTargetId,
                            int recommendedBlockSize, int recommendedSeparationTimeByte) {
        if (timeoutNanos <= 0) throw new IllegalArgumentException("timeoutNanos must be positive");
        if (flowControlTargetId < 0 || flowControlTargetId > 0x1FFFFFFF) {
            throw new IllegalArgumentException("Invalid flow-control target ID");
        }
        if (recommendedBlockSize < 0 || recommendedBlockSize > 0xFF) {
            throw new IllegalArgumentException("block size must fit in one byte");
        }
        if (!isValidSeparationTime(recommendedSeparationTimeByte)) {
            throw new IllegalArgumentException("Invalid ISO-TP STmin byte");
        }
        this.timeoutNanos = timeoutNanos;
        this.flowControlTargetId = flowControlTargetId;
        this.recommendedBlockSize = recommendedBlockSize;
        this.recommendedSeparationTimeByte = recommendedSeparationTimeByte;
    }

    public Result accept(CanFrame frame) {
        if (frame == null || frame.getDataLength() == 0) {
            return result(Status.MALFORMED, frame, null, null, "Missing ISO-TP PCI byte");
        }
        expireOlderThan(frame.getTimestampNanos());
        int pci = frame.getUnsignedByte(0);
        int type = (pci >>> 4) & 0x0F;
        switch (type) {
            case 0: return acceptSingleFrame(frame, pci & 0x0F);
            case 1: return acceptFirstFrame(frame, pci & 0x0F);
            case 2: return acceptConsecutiveFrame(frame, pci & 0x0F);
            case 3: return result(Status.IGNORED_FLOW_CONTROL, frame, null, null,
                    "Observed Flow Control frame; passive engine does not transmit");
            default: return result(Status.MALFORMED, frame, null, null,
                    "Unsupported ISO-TP PCI type " + type);
        }
    }

    /** Remove abandoned sessions and return how many were expired. */
    public int expireOlderThan(long nowNanos) {
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> nowNanos - entry.getValue().startedNanos > timeoutNanos);
        return before - sessions.size();
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    private Result acceptSingleFrame(CanFrame frame, int payloadLength) {
        if (payloadLength > frame.getDataLength() - 1) {
            return result(Status.MALFORMED, frame, null, null,
                    "Single Frame length exceeds DLC");
        }
        byte[] payload = payload(frame, 1, payloadLength);
        return result(Status.COMPLETE, frame, payload, null, null);
    }

    private Result acceptFirstFrame(CanFrame frame, int highLengthNibble) {
        if (frame.getDataLength() < 2) {
            return result(Status.MALFORMED, frame, null, null, "First Frame is missing length byte");
        }
        int totalLength = (highLengthNibble << 8) | frame.getUnsignedByte(1);
        int available = frame.getDataLength() - 2;
        if (totalLength <= available) {
            return result(Status.MALFORMED, frame, null, null,
                    "First Frame does not require a consecutive frame");
        }
        byte[] firstPayload = payload(frame, 2, Math.min(totalLength, available));
        sessions.put(frame.sessionKey(), new Session(totalLength, firstPayload, frame.getTimestampNanos()));
        FlowControlAdvice advice = new FlowControlAdvice(flowControlTargetId, frame.isExtendedId(),
                recommendedBlockSize, recommendedSeparationTimeByte);
        return result(Status.IN_PROGRESS, frame, null, advice,
                "Awaiting " + (totalLength - firstPayload.length) + " payload byte(s)");
    }

    private Result acceptConsecutiveFrame(CanFrame frame, int sequence) {
        Session session = sessions.get(frame.sessionKey());
        if (session == null) {
            return result(Status.ORPHAN_CONSECUTIVE_FRAME, frame, null, null,
                    "No active First Frame for source ID");
        }
        if (sequence != session.expectedSequence) {
            sessions.remove(frame.sessionKey());
            return result(Status.SEQUENCE_ERROR, frame, null, null,
                    "Expected CF sequence " + session.expectedSequence + " but received " + sequence);
        }
        int remaining = session.totalLength - session.assembled.size();
        int copied = Math.min(remaining, frame.getDataLength() - 1);
        byte[] segment = payload(frame, 1, copied);
        session.assembled.write(segment, 0, segment.length);
        session.expectedSequence = (session.expectedSequence + 1) & 0x0F;
        if (session.assembled.size() == session.totalLength) {
            sessions.remove(frame.sessionKey());
            return result(Status.COMPLETE, frame, session.assembled.toByteArray(), null, null);
        }
        return result(Status.IN_PROGRESS, frame, null, null,
                "Awaiting " + (session.totalLength - session.assembled.size()) + " payload byte(s)");
    }

    private static byte[] payload(CanFrame frame, int offset, int length) {
        byte[] value = new byte[length];
        for (int i = 0; i < length; i++) value[i] = (byte) frame.getUnsignedByte(offset + i);
        return value;
    }

    private Result result(Status status, CanFrame frame, byte[] payload,
                          FlowControlAdvice advice, String detail) {
        return new Result(status, frame != null ? frame.getArbitrationId() : -1,
                payload, advice, detail);
    }

    private static boolean isValidSeparationTime(int value) {
        return (value >= 0 && value <= 0x7F) || (value >= 0xF1 && value <= 0xF9);
    }
}
