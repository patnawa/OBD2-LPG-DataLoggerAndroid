package com.alpha.obd2logger.can;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CanAnomalyDetectorTest {
    @Test
    public void flagsFrequencySpikeAfterLearningBaseline() {
        CanAnomalyDetector detector = new CanAnomalyDetector();
        for (long timestamp = 0; timestamp <= 500; timestamp += 100) {
            detector.observe(IsoTpReassemblerTest.frame(0x321, timestamp, 0x01, 0x00));
        }

        List<CanAnomalyDetector.Signal> signals = detector.observe(
                IsoTpReassemblerTest.frame(0x321, 520, 0x01, 0x00));
        assertEquals(1, signals.size());
        assertEquals(CanAnomalyDetector.SignalType.FREQUENCY_SPIKE,
                signals.get(0).getType());
    }

    @Test
    public void flagsNonMonotonicTimestamp() {
        CanAnomalyDetector detector = new CanAnomalyDetector();
        detector.observe(IsoTpReassemblerTest.frame(0x321, 100, 0x01, 0x00));

        List<CanAnomalyDetector.Signal> signals = detector.observe(
                IsoTpReassemblerTest.frame(0x321, 90, 0x01, 0x00));
        assertTrue(!signals.isEmpty());
        assertEquals(CanAnomalyDetector.SignalType.NON_MONOTONIC_TIMESTAMP,
                signals.get(0).getType());
    }
}
