package com.alpha.obd2logger;

import java.util.ArrayDeque;

/**
 * Detects the petrol&#8596;LPG switchover of an aftermarket bi-fuel conversion from
 * the OEM ECU's fuel-trim signature and keeps per-fuel-segment trim statistics
 * so the two fuels can be compared side by side.
 *
 * <h3>Why trims, not PID 0x51</h3>
 * Aftermarket LPG ECUs intercept the petrol injectors; the OEM ECU never learns
 * the fuel changed, so Fuel Type (0x51) stays "Gasoline". What does change is
 * the closed-loop correction the OEM ECU applies: the switchover appears as an
 * abrupt, sustained step in STFT+LTFT, often with a brief open-loop blip.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Gate on closed loop and warm engine — open-loop/cold trims are noise.</li>
 *   <li>Maintain the running mean of total trim for the current segment and a
 *       short rolling window ({@link #SHORT_WINDOW} samples) of recent trim.</li>
 *   <li>When the short-window mean departs from the segment mean by at least
 *       {@link #STEP_THRESHOLD_PCT} (with both minimally established), declare
 *       a switchover: freeze the segment as "previous", start a new one seeded
 *       from the short window.</li>
 * </ol>
 *
 * Segments are labelled by trim signature, not by wire: the engine cannot know
 * which physical fuel is flowing, only that the fueling regime changed. The
 * delta between consecutive segments is the tuner's signal — a large LTFT gap
 * between the two regimes means the LPG map diverges from the petrol baseline
 * (reducer aging, injector drift) long before a DTC appears.
 *
 * Not thread-safe; one instance per session, fed from the record callback.
 */
public final class FuelSwitchoverEngine {

    public enum State {
        /** Not enough gated samples yet to establish a baseline. */
        COLLECTING,
        /** Gated out: open loop, cold engine, or missing trim PIDs. */
        GATED,
        /** Baseline established, no regime change seen this segment. */
        STABLE,
        /** A switchover was detected at least once; comparison available. */
        SWITCHED
    }

    /** Sustained step in short-window mean total trim that flags a switchover. */
    public static final double STEP_THRESHOLD_PCT = 7.0;
    /** Consecutive-segment LTFT gap beyond this suggests the LPG map has drifted. */
    public static final double DIVERGENCE_WARN_PCT = 8.0;
    /** Samples in the rolling short window (~4 s at the 500 ms default rate). */
    public static final int SHORT_WINDOW = 8;
    /** Segment must hold this many samples before a step can be declared. */
    public static final int MIN_SEGMENT_SAMPLES = 12;

    /** Immutable per-segment trim statistics. */
    public static final class Segment {
        public final int samples;
        public final double meanStft;
        public final double meanLtft;
        public final double durationS;

        Segment(int samples, double meanStft, double meanLtft, double durationS) {
            this.samples = samples;
            this.meanStft = meanStft;
            this.meanLtft = meanLtft;
            this.durationS = durationS;
        }

        public double meanTotal() {
            return meanStft + meanLtft;
        }
    }

    /** Immutable snapshot returned by {@link #push}. */
    public static final class Result {
        public final State state;
        public final int switchCount;
        /** Stats for the regime currently in effect; null while COLLECTING/GATED. */
        public final Segment current;
        /** Stats for the regime before the last switchover; null until SWITCHED. */
        public final Segment previous;
        /** current.meanTotal() - previous.meanTotal(); NaN until SWITCHED. */
        public final double deltaTotal;
        /** True when |LTFT gap| across the last switchover exceeds the warn band. */
        public final boolean divergent;

        Result(State state, int switchCount, Segment current, Segment previous) {
            this.state = state;
            this.switchCount = switchCount;
            this.current = current;
            this.previous = previous;
            this.deltaTotal = (current != null && previous != null)
                    ? current.meanTotal() - previous.meanTotal() : Double.NaN;
            this.divergent = current != null && previous != null
                    && Math.abs(current.meanLtft - previous.meanLtft) >= DIVERGENCE_WARN_PCT;
        }
    }

    private final ArrayDeque<double[]> shortWindow = new ArrayDeque<>(SHORT_WINDOW);

    private int segSamples;
    private double segStftSum;
    private double segLtftSum;
    private double segTotalSum;
    private double segStartS = Double.NaN;
    private double lastSampleS = Double.NaN;

    private Segment previous;
    private int switchCount;

    /**
     * Feed one poll cycle. Trim inputs are Bank 1 (or pre-merged) percentages.
     *
     * @param elapsedS   monotonic session time of the sample
     * @param stft       short-term fuel trim %, null if not reported
     * @param ltft       long-term fuel trim %, null if not reported
     * @param fuelStatus PID 01_03 raw value, null = assume closed loop
     * @param ect        coolant &#176;C, null = skip the warm gate
     */
    public Result push(double elapsedS, Double stft, Double ltft,
                       Double fuelStatus, Double ect) {
        boolean closedLoop = fuelStatus == null || (fuelStatus.intValue() & 0x02) != 0;
        boolean warm = ect == null || ect >= LPGAnalyzer.WARM_ECT_C;
        if (stft == null || ltft == null || !closedLoop || !warm) {
            // Do not accumulate through a gate, but keep segment stats: the
            // open-loop blip during a real switchover must not erase the
            // baseline we need to detect it once trims come back.
            shortWindow.clear();
            return snapshot(stateWhenGated());
        }

        double total = stft + ltft;
        shortWindow.addLast(new double[]{stft, ltft, total});
        while (shortWindow.size() > SHORT_WINDOW) shortWindow.removeFirst();

        if (segSamples == 0) segStartS = elapsedS;
        segSamples++;
        segStftSum += stft;
        segLtftSum += ltft;
        segTotalSum += total;

        // Baseline for step detection is the segment WITHOUT the short window:
        // window samples arriving after a real switchover would otherwise
        // dilute the segment mean and shrink the very step being measured.
        // Every window sample is (now) also in the segment sums, so subtracting
        // the window's exact sums leaves the pure pre-window baseline.
        int w = shortWindow.size();
        int baseN = segSamples - w;
        if (baseN >= MIN_SEGMENT_SAMPLES && w >= SHORT_WINDOW) {
            double winStft = windowSum(0);
            double winLtft = windowSum(1);
            double winTotal = windowSum(2);
            double baseMeanTotal = (segTotalSum - winTotal) / baseN;
            if (Math.abs(winTotal / w - baseMeanTotal) >= STEP_THRESHOLD_PCT) {
                previous = new Segment(baseN, (segStftSum - winStft) / baseN,
                        (segLtftSum - winLtft) / baseN,
                        Math.max(0, elapsedS - segStartS));
                switchCount++;
                segSamples = w;
                segStftSum = winStft;
                segLtftSum = winLtft;
                segTotalSum = winTotal;
                segStartS = elapsedS;
            }
        }
        lastSampleS = elapsedS;

        State state;
        if (switchCount > 0) {
            state = State.SWITCHED;
        } else if (segSamples >= MIN_SEGMENT_SAMPLES) {
            state = State.STABLE;
        } else {
            state = State.COLLECTING;
        }
        return snapshot(state);
    }

    /** Clear all state (new session or manual reset). */
    public void reset() {
        shortWindow.clear();
        segSamples = 0;
        segStftSum = segLtftSum = segTotalSum = 0;
        segStartS = Double.NaN;
        lastSampleS = Double.NaN;
        previous = null;
        switchCount = 0;
    }

    private State stateWhenGated() {
        if (switchCount > 0) return State.SWITCHED;
        if (segSamples >= MIN_SEGMENT_SAMPLES) return State.STABLE;
        return segSamples == 0 ? State.GATED : State.COLLECTING;
    }

    private Result snapshot(State state) {
        Segment current = segSamples == 0 ? null
                : new Segment(segSamples, segStftSum / segSamples,
                        segLtftSum / segSamples, Math.max(0, lastSampleS - segStartS));
        return new Result(state, switchCount, current, previous);
    }

    private double windowSum(int idx) {
        double sum = 0;
        for (double[] s : shortWindow) sum += s[idx];
        return sum;
    }
}
