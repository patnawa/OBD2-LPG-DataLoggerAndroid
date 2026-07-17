package com.alpha.obd2logger.can;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bounded single-producer/single-consumer queue for a CAN receive callback and
 * a decoder thread.
 *
 * <p>Frames are never silently discarded: a full queue makes {@link #offer}
 * return {@code false} and increments {@link #getDroppedFrameCount()}. The
 * caller can surface that loss to the UI/log instead of claiming zero loss.</p>
 */
public final class CanTelemetryRingBuffer {
    private final AtomicReferenceArray<CanFrame> slots;
    private final int capacity;
    private final AtomicLong producerSequence = new AtomicLong();
    private final AtomicLong consumerSequence = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();

    public CanTelemetryRingBuffer(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2");
        }
        this.capacity = capacity;
        this.slots = new AtomicReferenceArray<>(capacity);
    }

    /** Must be called by one producer thread only. */
    public boolean offer(CanFrame frame) {
        if (frame == null) throw new IllegalArgumentException("frame is required");
        long write = producerSequence.get();
        long read = consumerSequence.get();
        if (write - read >= capacity) {
            droppedFrames.incrementAndGet();
            return false;
        }
        slots.set((int) (write % capacity), frame);
        producerSequence.set(write + 1);
        return true;
    }

    /** Must be called by one consumer thread only. Returns {@code null} when empty. */
    public CanFrame poll() {
        long read = consumerSequence.get();
        if (read >= producerSequence.get()) return null;
        int slot = (int) (read % capacity);
        CanFrame frame = slots.getAndSet(slot, null);
        if (frame == null) return null;
        consumerSequence.set(read + 1);
        return frame;
    }

    public int size() {
        long value = producerSequence.get() - consumerSequence.get();
        return (int) Math.max(0L, Math.min((long) capacity, value));
    }

    public int getCapacity() {
        return capacity;
    }

    public long getDroppedFrameCount() {
        return droppedFrames.get();
    }
}
