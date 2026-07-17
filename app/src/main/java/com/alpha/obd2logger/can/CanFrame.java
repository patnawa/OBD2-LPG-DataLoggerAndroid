package com.alpha.obd2logger.can;

import java.util.Arrays;

/**
 * Immutable classic-CAN frame captured from a passive controller.
 *
 * <p>This value object deliberately contains no transmit operation. Hardware
 * adapters must create it only from received frames, preferably while their
 * controller is in listen-only mode.</p>
 */
public final class CanFrame {
    public static final int CLASSIC_CAN_MAX_DATA_LENGTH = 8;

    private final int arbitrationId;
    private final boolean extendedId;
    private final byte[] data;
    private final long timestampNanos;

    public CanFrame(int arbitrationId, boolean extendedId, byte[] data, long timestampNanos) {
        int maxIdentifier = extendedId ? 0x1FFFFFFF : 0x7FF;
        if (arbitrationId < 0 || arbitrationId > maxIdentifier) {
            throw new IllegalArgumentException("Invalid CAN arbitration ID: " + arbitrationId);
        }
        if (data == null || data.length > CLASSIC_CAN_MAX_DATA_LENGTH) {
            throw new IllegalArgumentException("Classic CAN payload must contain 0..8 bytes");
        }
        if (timestampNanos < 0) {
            throw new IllegalArgumentException("timestampNanos must be non-negative");
        }
        this.arbitrationId = arbitrationId;
        this.extendedId = extendedId;
        this.data = Arrays.copyOf(data, data.length);
        this.timestampNanos = timestampNanos;
    }

    public int getArbitrationId() {
        return arbitrationId;
    }

    public boolean isExtendedId() {
        return extendedId;
    }

    public int getDataLength() {
        return data.length;
    }

    public int getUnsignedByte(int index) {
        if (index < 0 || index >= data.length) {
            throw new IndexOutOfBoundsException("CAN data index: " + index);
        }
        return data[index] & 0xFF;
    }

    public byte[] getData() {
        return Arrays.copyOf(data, data.length);
    }

    public long getTimestampNanos() {
        return timestampNanos;
    }

    /** Stable session key; standard and extended IDs never share reassembly state. */
    long sessionKey() {
        return ((long) arbitrationId << 1) | (extendedId ? 1L : 0L);
    }
}
