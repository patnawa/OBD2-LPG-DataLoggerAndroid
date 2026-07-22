package com.alpha.obd2logger;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single poll → derive → map-push pipeline shared by background
 * ({@link LoggerService}) and in-process ({@link MainActivity}) logging.
 *
 * <p>Both paths previously carried their own copy of this logic and had
 * silently diverged: the in-process copy was missing the NGV km/kg branch
 * (so NGV fuel economy was always null) and passed no {@code mapSynthesized}
 * flag to {@link AirDensityMonitor}, causing MAP invented from Engine Load to
 * be treated as measured. Keeping one implementation is what prevents that
 * class of drift, so callers must not reimplement any of it.
 *
 * <p>Deliberately excluded: writing, UI/callback publication, DTC monitoring
 * and connection recovery. Those legitimately differ between the two hosts.
 *
 * <p>Not thread-safe by design — one instance belongs to one logging session
 * on one worker thread, mirroring how the loops already ran.
 */
final class PollingEngine {

    private static final String TAG = "PollingEngine";

    /** Sea-level barometric fallback when the vehicle reports no baro PID. */
    private static final double DEFAULT_BARO_KPA = 101.3;

    /** Result of one poll cycle. */
    static final class PollOutcome {
        final DataRecord record;
        /** Raw PID values keyed by display name; MAP may have been synthesized. */
        final Map<String, Double> batch;
        /** True when Intake Manifold Pressure was derived from Engine Load. */
        final boolean mapSynthesized;
        /** Null only when no map store was supplied. */
        final LiveMapStore.PushResult mapPush;

        PollOutcome(DataRecord record, Map<String, Double> batch,
                    boolean mapSynthesized, LiveMapStore.PushResult mapPush) {
            this.record = record;
            this.batch = batch;
            this.mapSynthesized = mapSynthesized;
            this.mapPush = mapPush;
        }
    }

    /**
     * Optional source of the latest GPS fix. Kept as an interface so the engine
     * stays free of Android location APIs and JVM-testable.
     */
    interface LocationSupplier {
        /** Latest fix as [latDeg, lonDeg, accuracyM, ageMs], or null when none. */
        double[] latest();
    }

    /** Fixes older than this are dropped rather than logged as current. */
    private static final long MAX_FIX_AGE_MS = 10_000;

    private final LoggerConfig config;
    private final List<PIDDefinition> pids;
    private LocationSupplier locationSupplier;
    private final PidHealthTracker pidHealth = new PidHealthTracker();
    private final SimpleDateFormat iso =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
    private final long startedElapsedMs;
    private final PollScheduler scheduler;
    private long pollCycle;

    PollingEngine(LoggerConfig config, List<PIDDefinition> pids, long startedElapsedMs) {
        this.config = config;
        this.pids = pids;
        this.startedElapsedMs = startedElapsedMs;
        this.scheduler = new PollScheduler(config.sampleIntervalMs);
    }

    /**
     * Sleep until the next fixed-rate cycle. Callers must use this instead of
     * sleeping for the sample interval directly, or the achieved period becomes
     * {@code pollDuration + interval} and the configured rate is never met.
     */
    /** Attach a GPS fix source; null (the default) logs no location columns. */
    void setLocationSupplier(LocationSupplier supplier) {
        this.locationSupplier = supplier;
    }

    void awaitNextCycle() throws InterruptedException {
        scheduler.sleepUntilNextCycle(SystemClock.elapsedRealtime());
    }

    PollScheduler scheduler() {
        return scheduler;
    }

    /**
     * Run one full poll cycle.
     *
     * @param driver     connected transport; checked for liveness after the batch
     * @param airDensity optional AAD/MAD/BAD monitor, null when disabled
     * @param mapStore   optional fuel-map store, null when the caller owns no map
     * @param veMapStore optional VE-map store, null when the caller owns no VE map
     * @throws IOException when the adapter stopped responding mid-batch
     */
    /** Back-compat entry for callers that own no VE map (tests, legacy paths). */
    PollOutcome poll(BaseDriver driver, AirDensityMonitor airDensity, LiveMapStore mapStore)
            throws IOException {
        return poll(driver, airDensity, mapStore, null);
    }

    PollOutcome poll(BaseDriver driver, AirDensityMonitor airDensity, LiveMapStore mapStore,
                     VeMapStore veMapStore)
            throws IOException {
        pollCycle++;
        long cycleStartMs = SystemClock.elapsedRealtime();
        scheduler.markCycleStart(cycleStartMs);

        List<PIDDefinition> polledPids = pidHealth.selectForPoll(pids, pollCycle);
        Map<String, Double> batch = driver.queryPidBatch(polledPids);
        long acquisitionSpanMs = SystemClock.elapsedRealtime() - cycleStartMs;
        // queryPidBatch can return a partial map on a half-open link, so the
        // connection is re-checked before any of it is treated as vehicle data.
        if (!driver.isConnected()) {
            throw new IOException("Adapter stopped responding");
        }

        Set<String> polledKeys = new HashSet<>();
        for (PIDDefinition polled : polledPids) polledKeys.add(polled.key());

        List<SensorSample> samples = new ArrayList<>();
        for (PIDDefinition pid : pids) {
            Double value = batch.get(pid.getName());
            boolean wasPolled = polledKeys.contains(pid.key());
            if (wasPolled) pidHealth.recordPolled(pid, value, pollCycle);
            samples.add(new SensorSample(pid.key(), pid.getName(), value, pid.getUnit(),
                    pidHealth.statusFor(pid, value, wasPolled)));
        }

        // Read the fuel mode ONCE per cycle. It is mutated from the UI thread,
        // and reading it separately for the record stamp and the map push let a
        // mid-cycle switch label a record one fuel while filing its sample into
        // the other fuel's map.
        final FuelMode fuelMode = config.fuelMode;

        Double mafValue = batch.get("MAF Air Flow");
        Double speedValue = batch.get("Vehicle Speed");
        Double baroValue = batch.get("Barometric Pressure");

        boolean mapSynthesized = synthesizeMapIfMissing(batch, baroValue, samples);
        Double mapValue = batch.get("Intake Manifold Pressure");

        appendFuelEconomy(samples, mafValue, speedValue, fuelMode);
        appendTurboBoost(samples, mapValue, baroValue);
        appendDpf(samples, batch);
        AdvancedAirDensity.AdvancedResult advanced =
                appendAirDensity(samples, batch, airDensity, mafValue, mapSynthesized, fuelMode);
        appendTiming(samples, acquisitionSpanMs);
        appendLocation(samples);

        DataRecord record = new DataRecord(
                iso.format(new Date()),
                (SystemClock.elapsedRealtime() - startedElapsedMs) / 1000.0,
                fuelMode.getValue(),
                config.vehicleBrand,
                config.vin,
                samples);

        // Single write path into LiveMapStore, before the record is written, so
        // the UI can only ever snapshot (never push), hits cannot double-count,
        // and the map_* columns describe what the store actually accepted.
        MapSampleMeta mapMeta = MapSampleMeta.from(record);
        LiveMapStore.PushResult mapPush = null;
        if (mapStore != null) {
            mapPush = mapStore.pushFromMeta(mapMeta, fuelMode);
        }
        mapMeta.appendLogSamples(samples,
                mapPush != null && mapPush.accepted,
                mapPush != null ? mapPush.reason : mapMeta.rejectReason);

        // Fold the instantaneous VE into the learned VE surface, reusing the
        // fuel map's cell binning and provenance so the two grids stay aligned.
        if (veMapStore != null) {
            Double vePct = advanced != null ? advanced.vePct : null;
            veMapStore.push(mapMeta, fuelMode, vePct);
        }

        return new PollOutcome(record, batch, mapSynthesized, mapPush);
    }

    /**
     * Many MAF-based vehicles (Toyota/Honda/Mazda) don't support PID 0x0B, which
     * leaves the fuel map's Y-axis empty and Tune Assist without a load axis.
     * Engine Load (0x04) is near-universal and tracks manifold pressure, so MAP
     * is synthesized as {@code 30 + (baro - 30) * load/100} — idle (~25% load)
     * gives ~48 kPa and full throttle approaches barometric, both realistic.
     *
     * <p>The value is tagged {@code status="synth"} rather than {@code "ok"} so
     * an invented MAP is never mistaken for a sensor reading downstream.
     *
     * @return true when MAP was synthesized
     */
    private boolean synthesizeMapIfMissing(Map<String, Double> batch, Double baroValue,
                                           List<SensorSample> samples) {
        if (batch.get("Intake Manifold Pressure") != null) return false;
        Double engineLoad = batch.get("Engine Load");
        if (engineLoad == null) return false;

        double baroForSynth = baroValue != null ? baroValue : DEFAULT_BARO_KPA;
        double synthMap = 30.0 + (baroForSynth - 30.0) * (engineLoad / 100.0);
        synthMap = Math.round(synthMap * 10.0) / 10.0;
        batch.put("Intake Manifold Pressure", synthMap);

        for (int i = 0; i < samples.size(); i++) {
            if ("01_0B".equals(samples.get(i).getPidKey())) {
                samples.set(i, new SensorSample("01_0B", "Intake Manifold Pressure",
                        synthMap, "kPa", "synth"));
                break;
            }
        }
        return true;
    }

    private void appendFuelEconomy(List<SensorSample> samples, Double mafValue,
                                   Double speedValue, FuelMode fuelMode) {
        if (!config.showFuelConsumption || mafValue == null || speedValue == null) return;

        if (fuelMode == FuelMode.NGV) {
            // NGV densityGL is gas-phase (0.72 g/L), so the liquid km/L formula
            // always yields < 1 km/L and is discarded — NGV fuel economy was
            // silently always null. NGV is dispensed and priced per kilogram.
            Double kmkg = DerivedSensors.fuelConsumptionKmKg(mafValue, speedValue, fuelMode);
            if (kmkg != null) {
                samples.add(new SensorSample("derived_fuel_kmkg", "Fuel Economy", kmkg, "km/kg", "ok"));
            }
            return;
        }

        Double kml = DerivedSensors.fuelConsumptionKmL(mafValue, speedValue, fuelMode);
        if (kml == null) return;
        samples.add(new SensorSample("derived_fuel_kmL", "Fuel Economy", kml, "km/L", "ok"));
        Double l100 = DerivedSensors.fuelConsumptionL100km(mafValue, speedValue, fuelMode);
        if (l100 != null) {
            samples.add(new SensorSample("derived_fuel_l100", "Fuel Economy", l100, "L/100km", "ok"));
        }
    }

    private void appendTurboBoost(List<SensorSample> samples, Double mapValue, Double baroValue) {
        if (!config.showTurboBoost || mapValue == null) return;
        Double boostKpa = DerivedSensors.boostPressureKpa(mapValue, baroValue);
        if (boostKpa == null) return;
        samples.add(new SensorSample("derived_boost_kpa", "Turbo Boost", boostKpa, "kPa", "ok"));
        Double boostPsi = DerivedSensors.boostPressurePsi(mapValue, baroValue);
        if (boostPsi != null) {
            samples.add(new SensorSample("derived_boost_psi", "Turbo Boost", boostPsi, "psi", "ok"));
        }
    }

    private void appendDpf(List<SensorSample> samples, Map<String, Double> batch) {
        if (!config.dpfMonitorEnabled) return;
        Double dpfSoot = batch.get("DPF Soot Load");
        Double dpfAsh = batch.get("DPF Ash Load");
        Double dpfRegen = batch.get("DPF Regen Status");

        if (dpfSoot != null) {
            String dpfHealth = DerivedSensors.dpfHealthStatus(dpfSoot, dpfAsh);
            samples.add(new SensorSample("derived_dpf_health", "DPF Health",
                    "Clean".equals(dpfHealth) ? 1.0 : "Moderate".equals(dpfHealth) ? 2.0
                            : "Warning".equals(dpfHealth) ? 3.0 : 4.0, "status", "ok"));
        }
        if (dpfRegen != null) {
            String regen = DerivedSensors.dpfRegenStatus(dpfRegen);
            double regenCode = "Regen Active".equals(regen) ? 1.0 : 0.0;
            samples.add(new SensorSample("derived_dpf_regen", "DPF Regen", regenCode,
                    "active=" + regen, "ok"));
        }
    }

    private AdvancedAirDensity.AdvancedResult appendAirDensity(List<SensorSample> samples,
                                  Map<String, Double> batch,
                                  AirDensityMonitor airDensity, Double mafValue,
                                  boolean mapSynthesized, FuelMode fuelMode) {
        if (!config.showAirDensity || airDensity == null) {
            AirDensityMonitor.appendAfrSamples(samples,
                    batch.get("Lambda (B1S1)"),
                    batch.get("Commanded Equivalence Ratio"),
                    fuelMode);
            return null;
        }

        // The monitor must be told whether MAP is measured or synthesized —
        // otherwise invented MAP is treated as real and MAD/BAD/VE/TMF are
        // logged as measured quantities.
        airDensity.onObdBatch(batch, mapSynthesized);
        try {
            return airDensity.appendSamples(samples, mafValue,
                    batch.get("Engine RPM"),
                    batch.get("Lambda (B1S1)"),
                    batch.get("Commanded Equivalence Ratio"),
                    fuelMode,
                    config.engineDisplacementCC,
                    config.ratedRPM,
                    config.engineDisplacementUserSet);
        } catch (Exception densityEx) {
            Log.w(TAG, "Air density sample append failed non-fatally", densityEx);
            return null;
        }
    }

    /**
     * Emit the cycle's timing so the log is self-describing about its own
     * sample rate. Without these the record's {@code elapsedS} is the only
     * clue that a cycle ran late, and nothing distinguishes "the bus was slow"
     * from "the phone was busy".
     *
     * <p>{@code poll_span_ms} bounds the intra-record skew: every PID in the
     * record was acquired somewhere inside that window, so correlating two
     * channels within one record is only valid to that resolution. Note the
     * skew is <em>per chunk</em>, not per PID — {@code queryPidBatch} sends
     * each chunk as one multi-PID command, so PIDs sharing a chunk genuinely
     * share an acquisition instant and only chunk boundaries introduce skew.
     */
    private void appendTiming(List<SensorSample> samples, long acquisitionSpanMs) {
        samples.add(new SensorSample("derived_poll_span_ms", "Poll Acquisition Span",
                (double) acquisitionSpanMs, "ms", "ok"));

        Double jitter = scheduler.lastJitterMs();
        samples.add(new SensorSample("derived_poll_jitter_ms", "Poll Jitter",
                jitter, "ms", jitter == null ? "warmup" : "ok"));

        samples.add(new SensorSample("derived_poll_overrun_ms", "Poll Overrun",
                (double) scheduler.lastOverrunMs(), "ms", "ok"));
    }

    /**
     * Log the latest GPS fix alongside each record so a session can be replayed
     * as a route. A fix older than {@link #MAX_FIX_AGE_MS} (tunnel, parking
     * garage) is skipped entirely — repeating the last known point would draw a
     * false stationary cluster on the route map.
     */
    private void appendLocation(List<SensorSample> samples) {
        LocationSupplier supplier = locationSupplier;
        if (supplier == null) return;
        double[] fix = supplier.latest();
        if (fix == null || fix.length < 4 || fix[3] > MAX_FIX_AGE_MS) return;
        samples.add(new SensorSample("gps_lat", "GPS Latitude", fix[0], "deg", "ok"));
        samples.add(new SensorSample("gps_lon", "GPS Longitude", fix[1], "deg", "ok"));
        samples.add(new SensorSample("gps_accuracy_m", "GPS Accuracy", fix[2], "m", "ok"));
    }
}
