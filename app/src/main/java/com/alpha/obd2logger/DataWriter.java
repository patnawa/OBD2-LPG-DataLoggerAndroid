package com.alpha.obd2logger;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.LinkedHashSet;

public final class DataWriter implements AutoCloseable {
    private static final String TAG = "DataWriter";
    private static final String DOWNLOAD_SUBDIR = "TunerMapPro";
    private static final String NO_VIN_SUBDIR = "General";
    private static final int SUMMARY_SCHEMA_VERSION = 2;
    /**
     * Summary checkpointing is time-based, not record-based.
     *
     * <p>It used to fire every 10 records, which tied a full JSON
     * re-serialisation plus truncate-and-rewrite (cost scaling with the ~60
     * column count) to the sample rate: at the 500 ms default that is every
     * 5 s, and at a 100 ms interval it would fire every second — the faster you
     * ask the logger to sample, the more of each cycle it spends rewriting a
     * file that is only a convenience artifact. The raw CSV/JSONL streams are
     * the source of truth and still flush every 5 records, so a staler summary
     * costs nothing on a crash.
     */
    private static final long SUMMARY_CHECKPOINT_INTERVAL_MS = 30_000L;

    /**
     * One early checkpoint so a session that dies in its first seconds still
     * leaves a summary carrying real records rather than the empty one written
     * at construction. Paid once per session, unlike the old every-10-records
     * cadence.
     */
    private static final int EARLY_CHECKPOINT_RECORDS = 10;

    private final Context context;
    private final String vin;
    private final File csvFile;
    private final File jsonlFile;
    private final Uri csvUri;
    private final Uri jsonlUri;
    private final File summaryFile;
    private final Uri summaryUri;
    private final DownloadTarget summaryTarget;
    private final BufferedWriter csvWriter;
    private final BufferedWriter jsonlWriter;
    private final List<PIDDefinition> pids;
    private final String sessionId;
    private final String transport;
    private final String protocol;
    private final String adapter;
    private final long configuredSampleIntervalMs;
    private int recordsSinceFlush = 0;
    private long lastCheckpointElapsedMs = android.os.SystemClock.elapsedRealtime();
    private int checkpointSequence = 0;

    // Ordered column keys for the CSV/JSONL body. Built once at construction from
    // the poll PID list PLUS every derived sensor that the logging loop may emit
    // (Fuel Economy km/L + L/100km, Turbo Boost kPa + psi, DPF health/regen). The
    // record's samples are written by this key so derived values that are shown on
    // screen are also persisted to the log — previously they were dropped entirely.
    private final List<String> columnKeys = new ArrayList<>();
    private final Map<String, String> columnLabel = new LinkedHashMap<>();

    public DataWriter(Context context, String sessionId) throws IOException {
        this(context, sessionId, PIDCatalogue.getAll(), null, null, null);
    }

    public DataWriter(Context context, String sessionId, List<PIDDefinition> pids) throws IOException {
        this(context, sessionId, pids, null, null, null);
    }

    public DataWriter(Context context, String sessionId, List<PIDDefinition> pids, String vin) throws IOException {
        this(context, sessionId, pids, vin, null, null);
    }

    public DataWriter(Context context, String sessionId, List<PIDDefinition> pids, String vin,
                      LoggerConfig config, String adapterDetails) throws IOException {
        this.context = context.getApplicationContext();
        this.pids = pids;
        this.vin = vin;
        this.sessionId = (sessionId == null || sessionId.trim().isEmpty())
                ? "session_" + System.currentTimeMillis() : sessionId.trim();
        this.transport = resolvedTransport(config);
        this.protocol = config != null && config.obdProtocol != null
                ? config.obdProtocol.toString() : "unknown";
        this.adapter = adapterDetails == null || adapterDetails.trim().isEmpty()
                ? "unknown" : adapterDetails.trim();
        this.configuredSampleIntervalMs = config != null ? config.sampleIntervalMs : 0L;

        buildColumns(pids);

        DownloadTarget csvTarget = createDownloadTarget(this.sessionId + "_obd2.csv", "text/csv");
        DownloadTarget jsonlTarget = createDownloadTarget(this.sessionId + "_obd2.jsonl", "application/x-ndjson");
        DownloadTarget createdSummaryTarget = null;
        try {
            createdSummaryTarget = createDownloadTarget(this.sessionId + "_summary.json", "application/json");
        } catch (IOException e) {
            // A summary is valuable but must never prevent raw logs from starting.
            Log.w(TAG, "Could not create summary target; raw logging will continue", e);
        }

        this.csvFile = csvTarget.file;
        this.jsonlFile = jsonlTarget.file;
        this.csvUri = csvTarget.uri;
        this.jsonlUri = jsonlTarget.uri;
        this.summaryTarget = createdSummaryTarget;
        this.summaryFile = createdSummaryTarget != null ? createdSummaryTarget.file : null;
        this.summaryUri = createdSummaryTarget != null ? createdSummaryTarget.uri : null;
        this.csvWriter = new BufferedWriter(new OutputStreamWriter(csvTarget.openOutputStream(), StandardCharsets.UTF_8));
        try {
            this.jsonlWriter = new BufferedWriter(new OutputStreamWriter(jsonlTarget.openOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            this.csvWriter.close();
            throw e;
        }

        StringBuilder header = new StringBuilder();
        header.append("timestamp,elapsed_s,fuel_mode,loop_status,vehicle_brand,vin");
        for (String key : columnKeys) {
            header.append(',').append(csvEscape(columnLabel.get(key)));
        }
        csvWriter.write(header.toString());
        csvWriter.newLine();
        csvWriter.flush();
        try {
            writeSummaryCheckpoint(false);
        } catch (IOException checkpointError) {
            Log.w(TAG, "Initial summary checkpoint failed; raw logging will continue",
                    checkpointError);
        }
    }

    private static String resolvedTransport(LoggerConfig config) {
        String actual = DriverFactory.getLastResolvedTransport();
        if (actual != null && !actual.trim().isEmpty()
                && !"UNKNOWN".equalsIgnoreCase(actual)) {
            return actual;
        }
        return config != null && config.transportMode != null
                ? config.transportMode.getValue() : "unknown";
    }

    public static String describeAdapter(BaseDriver driver) {
        if (driver == null) return "unknown";
        if (driver instanceof ElmDriver) {
            String details = ((ElmDriver) driver).getAdapterDetails();
            if (details != null && !details.trim().isEmpty()) return details.trim();
        }
        return driver.getClass().getSimpleName();
    }

    /**
     * Build the stable column list (and labels) used for every row. Combines the
     * polled PIDs with the derived sensors the logging loop can emit. Columns are
     * keyed by the SensorSample.pidKey (unique) rather than by display name, so the
     * two derived samples that share a display name ("Fuel Economy" km/L + L/100km,
     * "Turbo Boost" kPa + psi) each get their own column instead of the second
     * silently overwriting the first.
     */
    private void buildColumns(List<PIDDefinition> pids) {
        for (PIDDefinition pid : pids) {
            String key = pid.key();
            if (!columnKeys.contains(key)) {
                columnKeys.add(key);
                columnLabel.put(key, pid.getName() + " (" + pid.getUnit() + ")");
            }
        }
        // Derived sensors appended by LoggerService / MainActivity logging loops.
        registerDerived("derived_fuel_kmL", "Fuel Economy (km/L)");
        registerDerived("derived_fuel_l100", "Fuel Economy (L/100km)");
        registerDerived("derived_fuel_kmkg", "Fuel Economy (km/kg)");
        registerDerived("derived_boost_kpa", "Turbo Boost (kPa)");
        registerDerived("derived_boost_psi", "Turbo Boost (psi)");
        registerDerived("derived_dpf_health", "DPF Health (status)");
        registerDerived("derived_dpf_regen", "DPF Regen (active)");
        // Loop timing — makes the log self-describing about its own sample rate.
        // Span bounds intra-record skew; jitter and overrun expose cycles that
        // missed the configured cadence instead of hiding them in elapsed_s.
        registerDerived("derived_poll_span_ms", "Poll Acquisition Span (ms)");
        // No comma inside a column label: every other label in this file avoids
        // one, so a consumer splitting on "," (rather than parsing quoted CSV)
        // stays correct. A single comma here would shift every later column.
        registerDerived("derived_poll_jitter_ms", "Poll Jitter (ms achieved-target)");
        registerDerived("derived_poll_overrun_ms", "Poll Overrun (ms late vs deadline)");
        // Air Density (AeroDensity Intelligence)
        registerDerived("derived_aad", "Ambient Air Density (lbs/1000ft3)");
        registerDerived("derived_mad", "Manifold Air Density (lbs/1000ft3)");
        registerDerived("derived_bad", "Boost Air Density (lbs/1000ft3)");
        registerDerived("derived_density_pct", "Air Density % (SAE J1349)");
        registerDerived("derived_density_alt", "Density Altitude (ft)");
        registerDerived("derived_sae_cf", "SAE J1349 Correction Factor");
        registerDerived("derived_grains", "Grains H2O (grains/lb)");
        registerDerived("derived_humidity", "Relative Humidity (%)");
        registerDerived("derived_aad_quality", "AeroDensity Quality (0=ok 1=est 2=default)");
        registerDerived("derived_baro_src", "Baro Source (1=obd 2=sensor 3=weather 4=default)");
        registerDerived("derived_rh_src", "RH Source (1=sensor 2=weather 3=default)");
        // Advanced Air Density (beyond standard AAD/MAD/BAD)
        registerDerived("derived_omd", "Oxygen Mass Density (lbs/1000ft3)");
        registerDerived("derived_compressor_eff", "Compressor Efficiency (%)");
        registerDerived("derived_intercooler_eff", "Intercooler Effectiveness (%)");
        registerDerived("derived_ve", "Volumetric Efficiency (%)");
        registerDerived("derived_actual_afr", "Actual AFR (:1)");
        registerDerived("derived_commanded_afr", "Commanded AFR (:1)");
        registerDerived("derived_lambda_source", "Actual Lambda Source (1=PID34 0=unavailable)");
        registerDerived("derived_afr_quality", "Actual AFR Quality (1=measured 0=unavailable)");
        registerDerived("derived_dcafr", "Density-Corrected AFR");
        registerDerived("derived_tmf", "Theoretical Mass Flow (g/s)");
        registerDerived("derived_maf_dev", "MAF Deviation (%)");
        registerDerived("derived_lvd", "Vapor Displacement (fraction)");
        registerDerived("derived_eff_density", "Effective Air Density (kg/m3)");
        registerDerived("derived_ecc_dt", "Evap Cooling DeltaT (C)");
        registerDerived("derived_ecc_mad", "Evap-Corrected MAD (lbs/1000ft3)");
        registerDerived("derived_pdi", "Power Density Index");
        registerDerived("derived_sae_j607", "STD Correction Factor (J607 + humidity)");
        registerDerived("derived_sae_cf_delta", "SAE CF Delta (J1349-STD)");
        // Fuel-map AI columns: cell targeting + accept/reject codes so agents can
        // rebuild LiveMapStore-quality maps from the log alone (without re-deriving
        // binning / closed-loop / warm rules). Numeric only for easy ML ingestion.
        registerDerived("map_rpm_cell", "Map RPM Cell (rpm)");
        registerDerived("map_axis_value", "Map Axis Value");
        registerDerived("map_axis_source", "Map Axis Source (1=MAP 2=LOAD 3=SYNTH_MAP)");
        registerDerived("map_value_source", "MAP Value Source (0=missing 1=measured 2=synthesized)");
        registerDerived("map_trim_total", "Map Trim Total STFT+LTFT (%)");
        registerDerived("map_lambda", "Map Measured Lambda");
        registerDerived("map_commanded_lambda", "Map Commanded Lambda");
        registerDerived("map_lambda_error", "Map Lambda Error (Measured-Commanded)");
        registerDerived("map_closed_loop", "Map Closed Loop (1/0)");
        registerDerived("map_warm", "Map Engine Warm (1/0)");
        registerDerived("map_gated", "Map Gate Eligible (1/0)");
        registerDerived("map_accepted", "Map Sample Accepted (1/0)");
        registerDerived("map_reject_code", "Map Reject Code");
    }

    private void registerDerived(String key, String label) {
        if (!columnKeys.contains(key)) {
            columnKeys.add(key);
            columnLabel.put(key, label);
        }
    }

    public void writeRecord(DataRecord record) throws IOException {
        String loopStatus = "Unknown";
        for (SensorSample sample : record.getSamples()) {
            if ("01_03".equals(sample.getPidKey())) {
                Double val = sample.getValue();
                if (val != null) {
                    // SAE J1979 PID 03 Fuel System Status (byte A, bit flags):
                    //   0x01 = Open loop (insufficient engine temp)
                    //   0x02 = Closed loop (using O2 sensor feedback) ← CLOSED
                    //   0x04 = Open loop (engine load)
                    //   0x08 = Open loop (system failure)
                    // The OLD code here treated 1.0 and 8.0 as "Closed" — both are
                    // actually OPEN loop — and labelled the real closed-loop value
                    // (0x02) as "Open", inverting the loop_status column written to
                    // the CSV. This mirrors the bug that was already fixed in
                    // LogReplayParser.isClosedLoop but survived here.
                    loopStatus = ((val.intValue() & 0x02) != 0) ? "Closed" : "Open";
                }
                break;
            }
        }

        // Index the record's samples by their unique pidKey so derived values
        // (keyed differently from catalogue PIDs) are matched correctly.
        Map<String, SensorSample> sampleMap = new LinkedHashMap<>();
        for (SensorSample sample : record.getSamples()) {
            sampleMap.put(sample.getPidKey(), sample);
        }
        SensorSample mapSample = sampleMap.get("01_0B");
        double mapSourceCode = 0.0;
        String mapSourceStatus = "unavailable";
        if (mapSample != null && mapSample.getValue() != null) {
            if ("synth".equalsIgnoreCase(mapSample.getStatus())
                    || "synthesized".equalsIgnoreCase(mapSample.getStatus())) {
                mapSourceCode = 2.0;
                mapSourceStatus = "synthesized";
            } else {
                mapSourceCode = 1.0;
                mapSourceStatus = "measured";
            }
        }
        sampleMap.put("map_value_source", new SensorSample("map_value_source",
                "MAP Value Source", mapSourceCode, "code", mapSourceStatus));

        StringBuilder csvRow = new StringBuilder();
        csvRow.append(csvEscape(record.getTimestamp()))
                .append(',').append(record.getElapsedS())
                .append(',').append(csvEscape(record.getFuelMode()))
                .append(',').append(csvEscape(loopStatus))
                .append(',').append(csvEscape(record.getVehicleBrand()))
                .append(',').append(csvEscape(record.getVin()));

        JSONObject json = new JSONObject();
        JSONObject quality = new JSONObject();
        try {
            json.put("timestamp", record.getTimestamp());
            json.put("elapsed_s", record.getElapsedS());
            json.put("fuel_mode", record.getFuelMode());
            json.put("loop_status", loopStatus);
            json.put("vehicle_brand", record.getVehicleBrand());
            json.put("vin", record.getVin());
        } catch (JSONException e) {
            throw new IOException("Failed to build JSON record", e);
        }

        for (String key : columnKeys) {
            SensorSample sample = sampleMap.get(key);
            Double value = sample != null ? sample.getValue() : null;
            csvRow.append(',').append(value == null ? "" : value);
            try {
                json.put(key, value == null ? JSONObject.NULL : value);
                if (sample != null && sample.getStatus() != null
                        && !sample.getStatus().isEmpty()
                        && !"ok".equalsIgnoreCase(sample.getStatus())) {
                    quality.put(key, sample.getStatus());
                }
            } catch (JSONException e) {
                throw new IOException("Failed to add PID JSON field " + key, e);
            }
        }
        try {
            if (quality.length() > 0) json.put("_quality", quality);
        } catch (JSONException e) {
            throw new IOException("Failed to add JSON quality metadata", e);
        }

        csvWriter.write(csvRow.toString());
        csvWriter.newLine();
        jsonlWriter.write(json.toString());
        jsonlWriter.newLine();

        recordsSinceFlush++;
        if (recordsSinceFlush % 5 == 0) {
            csvWriter.flush();
            jsonlWriter.flush();
        }

        summary.add(record, sampleMap, loopStatus);
        long nowMs = android.os.SystemClock.elapsedRealtime();
        if (nowMs - lastCheckpointElapsedMs >= SUMMARY_CHECKPOINT_INTERVAL_MS
                || summary.getRecords() == EARLY_CHECKPOINT_RECORDS) {
            lastCheckpointElapsedMs = nowMs;
            checkpointSequence++;
            try {
                writeSummaryCheckpoint(false);
            } catch (IOException checkpointError) {
                // Raw CSV/JSONL is the source of truth; a checkpoint failure
                // must not stop a live vehicle logging session.
                Log.w(TAG, "Summary checkpoint failed non-fatally", checkpointError);
            }
        }
    }

    /**
     * Lightweight per-column aggregate (min / mean / max) computed from the numeric
     * samples in every record. Produces the session {@code _summary.json} on close so
     * users get trip stats (including fuel used) without re-parsing the whole CSV.
     */
    private static final class ColumnStats {
        String name = "";
        String unit = "";
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        double sumSquares = 0.0;
        int count = 0;
        final Map<String, Integer> statuses = new LinkedHashMap<>();

        void add(SensorSample sample) {
            if (sample == null) return;
            if (sample.getName() != null && !sample.getName().isEmpty()) name = sample.getName();
            if (sample.getUnit() != null && !sample.getUnit().isEmpty()) unit = sample.getUnit();
            String status = sample.getStatus();
            if (status != null && !status.isEmpty()) {
                statuses.put(status, statuses.getOrDefault(status, 0) + 1);
            }
            Double value = sample.getValue();
            if (value == null || !Double.isFinite(value)) return;
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
            sumSquares += value * value;
            count++;
        }

        double average() { return count == 0 ? 0.0 : sum / count; }

        double standardDeviation() {
            if (count < 2) return 0.0;
            double variance = (sumSquares - (sum * sum / count)) / (count - 1);
            return Math.sqrt(Math.max(0.0, variance));
        }
    }

    private static final class SummaryCollector {
        private static final double MAX_INTEGRATION_GAP_S = 30.0;
        private final Map<String, ColumnStats> columns = new LinkedHashMap<>();
        private final Set<String> fuelModes = new LinkedHashSet<>();
        private final Set<String> brands = new LinkedHashSet<>();
        private final Set<String> vins = new LinkedHashSet<>();
        private int records = 0;
        private int closedLoopRecords = 0;
        private int openLoopRecords = 0;
        private int unknownLoopRecords = 0;
        private int integrationGapCount = 0;
        private double distanceKm = 0.0;
        private double fuelLiters = 0.0;
        private double distanceIntegratedSeconds = 0.0;
        private double fuelIntegratedSeconds = 0.0;
        private double fuelRatePidSeconds = 0.0;
        private double fuelEconomyFallbackSeconds = 0.0;
        private Double previousElapsedS = null;
        private Double previousSpeedKmh = null;
        private Double previousFuelLph = null;
        private String previousFuelSource = "unavailable";
        private String startedAt = "";
        private String endedAt = "";
        private double lastElapsedS = 0.0;

        void add(DataRecord record, Map<String, SensorSample> byKey, String loopStatus) {
            if (record == null) return;
            records++;
            if (records == 1) startedAt = safe(record.getTimestamp());
            endedAt = safe(record.getTimestamp());
            lastElapsedS = Math.max(lastElapsedS, record.getElapsedS());
            addNonPlaceholder(fuelModes, record.getFuelMode());
            addNonPlaceholder(brands, record.getVehicleBrand());
            addNonPlaceholder(vins, record.getVin());

            if ("Closed".equalsIgnoreCase(loopStatus)) closedLoopRecords++;
            else if ("Open".equalsIgnoreCase(loopStatus)) openLoopRecords++;
            else unknownLoopRecords++;

            for (Map.Entry<String, SensorSample> entry : byKey.entrySet()) {
                columns.computeIfAbsent(entry.getKey(), ignored -> new ColumnStats())
                        .add(entry.getValue());
            }

            Double speed = value(byKey, "01_0D");
            Double directFuelLph = value(byKey, "01_5E");
            Double kml = value(byKey, "derived_fuel_kmL");
            Double fuelLph = validNonNegative(directFuelLph) ? directFuelLph : null;
            String fuelSource = fuelLph != null ? "engine_fuel_rate_pid" : "unavailable";
            if (fuelLph == null && validNonNegative(speed) && kml != null && kml > 0) {
                fuelLph = speed / kml;
                fuelSource = "speed_kml_fallback";
            }

            if (previousElapsedS != null) {
                double dt = record.getElapsedS() - previousElapsedS;
                if (dt > 0 && dt <= MAX_INTEGRATION_GAP_S) {
                    Double avgSpeed = averageAvailable(previousSpeedKmh, speed);
                    if (avgSpeed != null && avgSpeed >= 0) {
                        distanceKm += avgSpeed * dt / 3600.0;
                        distanceIntegratedSeconds += dt;
                    }
                    Double avgFuelLph = averageAvailable(previousFuelLph, fuelLph);
                    if (avgFuelLph != null && avgFuelLph >= 0) {
                        fuelLiters += avgFuelLph * dt / 3600.0;
                        fuelIntegratedSeconds += dt;
                        if ("engine_fuel_rate_pid".equals(previousFuelSource)
                                || "engine_fuel_rate_pid".equals(fuelSource)) {
                            fuelRatePidSeconds += dt;
                        } else {
                            fuelEconomyFallbackSeconds += dt;
                        }
                    }
                } else if (dt > MAX_INTEGRATION_GAP_S) {
                    integrationGapCount++;
                }
            }
            previousElapsedS = record.getElapsedS();
            previousSpeedKmh = validNonNegative(speed) ? speed : null;
            previousFuelLph = validNonNegative(fuelLph) ? fuelLph : null;
            previousFuelSource = fuelSource;
        }

        private static Double value(Map<String, SensorSample> samples, String key) {
            SensorSample sample = samples.get(key);
            return sample != null ? sample.getValue() : null;
        }

        private static boolean validNonNegative(Double value) {
            return value != null && Double.isFinite(value) && value >= 0;
        }

        private static Double averageAvailable(Double a, Double b) {
            if (validNonNegative(a) && validNonNegative(b)) return (a + b) / 2.0;
            if (validNonNegative(a)) return a;
            if (validNonNegative(b)) return b;
            return null;
        }

        private static void addNonPlaceholder(Set<String> values, String value) {
            if (value == null) return;
            String clean = value.trim();
            if (clean.isEmpty() || "unknown".equalsIgnoreCase(clean)
                    || "auto".equalsIgnoreCase(clean)) return;
            values.add(clean);
        }

        private static String safe(String value) { return value == null ? "" : value; }

        int getRecords() { return records; }
        double getDistanceKm() { return distanceKm; }
        double getFuelLiters() { return fuelLiters; }
        double getDurationS() { return lastElapsedS; }
        String getStartedAt() { return startedAt; }
        String getEndedAt() { return endedAt; }
        Set<String> getFuelModes() { return fuelModes; }
        Set<String> getBrands() { return brands; }
        Set<String> getVins() { return vins; }
        Set<String> keys() { return columns.keySet(); }
        ColumnStats stats(String key) { return columns.get(key); }
    }

    private final SummaryCollector summary = new SummaryCollector();

    public File getCsvFile() {
        return csvFile;
    }

    public File getJsonlFile() {
        return jsonlFile;
    }

    public Uri getCsvUri() {
        return csvUri;
    }

    public Uri getJsonlUri() {
        return jsonlUri;
    }

    public File getSummaryFile() {
        return summaryFile;
    }

    public Uri getSummaryUri() {
        return summaryUri;
    }

    public String getCsvLocation() {
        return csvUri != null ? csvUri.toString() : csvFile.getAbsolutePath();
    }

    public String getJsonlLocation() {
        return jsonlUri != null ? jsonlUri.toString() : jsonlFile.getAbsolutePath();
    }

    public File getDownloadFolderFile() {
        File downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBDIR);
        return new File(downloadDir, storageDirectoryName(this.vin));
    }

    public String getDownloadFolderPath() {
        return getDownloadFolderFile().getAbsolutePath();
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            csvWriter.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            jsonlWriter.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            }
        }
        if (csvUri != null) {
            markDownloadComplete(csvUri);
        }
        if (jsonlUri != null) {
            markDownloadComplete(jsonlUri);
        }
        try {
            writeSummaryCheckpoint(true);
        } catch (IOException e) {
            Log.w(TAG, "Failed to write session summary", e);
        }
        if (summaryUri != null) markDownloadComplete(summaryUri);
        if (failure != null) {
            throw failure;
        }
    }

    private synchronized void writeSummaryCheckpoint(boolean complete) throws IOException {
        if (summaryTarget == null) return;
        JSONObject root = new JSONObject();
        try {
            root.put("schema_version", SUMMARY_SCHEMA_VERSION);
            root.put("app_version", BuildConfig.VERSION_NAME);
            root.put("session_id", sessionId);
            root.put("complete", complete);
            root.put("checkpoint_sequence", checkpointSequence);
            root.put("updated_at", isoTimestampNow());
            root.put("records", summary.getRecords());
            root.put("started_at", summary.getStartedAt());
            root.put("ended_at", summary.getEndedAt());
            root.put("duration_s", round(summary.getDurationS(), 3));

            JSONObject files = new JSONObject();
            files.put("csv", sessionId + "_obd2.csv");
            files.put("jsonl", sessionId + "_obd2.jsonl");
            files.put("summary", sessionId + "_summary.json");
            root.put("files", files);
            root.put("output_directory", storageDirectoryName(vin));
            root.put("output_directory_type", sanitizeVin(vin).isEmpty() ? "GENERAL_NO_VIN" : "VIN");

            JSONObject vehicle = new JSONObject();
            vehicle.put("configured_vin", vin == null ? "UNKNOWN" : vin);
            vehicle.put("observed_vins", jsonArray(summary.getVins()));
            vehicle.put("observed_brands", jsonArray(summary.getBrands()));
            vehicle.put("fuel_modes", jsonArray(summary.getFuelModes()));
            root.put("vehicle", vehicle);

            JSONObject connection = new JSONObject();
            connection.put("transport", transport);
            connection.put("protocol", protocol);
            connection.put("adapter", adapter);
            connection.put("configured_sample_interval_ms", configuredSampleIntervalMs);
            root.put("connection", connection);

            JSONObject trip = new JSONObject();
            trip.put("distance_km", round(summary.getDistanceKm(), 4));
            trip.put("fuel_liters", round(summary.getFuelLiters(), 4));
            trip.put("average_km_l", summary.getFuelLiters() > 0
                    ? round(summary.getDistanceKm() / summary.getFuelLiters(), 3)
                    : JSONObject.NULL);
            trip.put("distance_integrated_seconds", round(summary.distanceIntegratedSeconds, 3));
            trip.put("fuel_integrated_seconds", round(summary.fuelIntegratedSeconds, 3));
            trip.put("fuel_rate_pid_seconds", round(summary.fuelRatePidSeconds, 3));
            trip.put("fuel_economy_fallback_seconds", round(summary.fuelEconomyFallbackSeconds, 3));
            trip.put("integration_gap_count", summary.integrationGapCount);
            trip.put("method", "elapsed_time_trapezoidal");
            root.put("trip", trip);

            JSONObject loop = new JSONObject();
            loop.put("closed_records", summary.closedLoopRecords);
            loop.put("open_records", summary.openLoopRecords);
            loop.put("unknown_records", summary.unknownLoopRecords);
            root.put("loop_status", loop);

            JSONObject stats = new JSONObject();
            JSONArray emptyColumns = new JSONArray();
            long validValues = 0;
            int activeColumns = 0;
            for (String k : columnKeys) {
                ColumnStats cs = summary.stats(k);
                if (cs == null) {
                    emptyColumns.put(k);
                    continue;
                }
                activeColumns++;
                validValues += cs.count;
                JSONObject s = new JSONObject();
                s.put("name", cs.name.isEmpty() ? columnLabel.get(k) : cs.name);
                s.put("unit", cs.unit);
                s.put("count", cs.count);
                s.put("null_count", Math.max(0, summary.getRecords() - cs.count));
                s.put("coverage_pct", summary.getRecords() > 0
                        ? round(cs.count * 100.0 / summary.getRecords(), 2) : 0.0);
                if (cs.count > 0) {
                    s.put("min", cs.min);
                    s.put("avg", round(cs.average(), 4));
                    s.put("max", cs.max);
                    s.put("stddev", round(cs.standardDeviation(), 4));
                }
                JSONObject statuses = new JSONObject();
                for (Map.Entry<String, Integer> status : cs.statuses.entrySet()) {
                    statuses.put(status.getKey(), status.getValue());
                }
                if (statuses.length() > 0) s.put("status_counts", statuses);
                stats.put(k, s);
                if (cs.count == 0) emptyColumns.put(k);
            }
            root.put("columns", stats);

            JSONObject quality = new JSONObject();
            quality.put("declared_columns", columnKeys.size());
            quality.put("observed_columns", activeColumns);
            quality.put("empty_columns", emptyColumns);
            quality.put("valid_numeric_values", validValues);
            long possibleValues = (long) summary.getRecords() * activeColumns;
            quality.put("overall_coverage_pct", possibleValues > 0
                    ? round(validValues * 100.0 / possibleValues, 2) : 0.0);
            quality.put("formula_profile", "tunermap_summary_v2_sae_j1979");
            root.put("data_quality", quality);
        } catch (JSONException e) {
            throw new IOException("Failed to build summary", e);
        }

        try (OutputStream out = summaryTarget.openOutputStream();
             Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            String json;
            try {
                json = root.toString(2);
            } catch (JSONException e) {
                throw new IOException("Failed to serialize summary", e);
            }
            w.write(json);
            w.flush();
        }
    }

    private static JSONArray jsonArray(Set<String> values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (String value : values) array.put(value);
        }
        return array;
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10.0, decimals);
        return Math.round(value * factor) / factor;
    }

    /** RFC 3339 timestamp compatible with Android API 23 (pattern X needs API 24). */
    private static String isoTimestampNow() {
        String raw = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date());
        if (raw.length() >= 5) {
            return raw.substring(0, raw.length() - 2) + ":" + raw.substring(raw.length() - 2);
        }
        return raw;
    }

    /**
     * Open a content URI for writing with guaranteed truncation. Mode "w" alone
     * does not truncate on every provider (SAF/MediaStore providers may keep the
     * old length), so rewriting a SHORTER summary JSON checkpoint could leave
     * trailing bytes of the previous version and make the file unparseable.
     * Try "wt" first; fall back to "w" for providers that reject the mode.
     */
    private OutputStream openTruncatedOutputStream(Uri uri) throws IOException {
        OutputStream stream = null;
        try {
            stream = context.getContentResolver().openOutputStream(uri, "wt");
        } catch (Exception modeRejected) {
            Log.w(TAG, "Provider rejected \"wt\" mode for " + uri + "; retrying with \"w\"",
                    modeRejected);
        }
        if (stream == null) {
            stream = context.getContentResolver().openOutputStream(uri, "w");
        }
        return stream;
    }

    private DownloadTarget createDownloadTarget(String displayName, String mimeType) throws IOException {
        String savedUriStr = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE).getString("custom_log_folder_uri", null);
        if (savedUriStr != null) {
            try {
                Uri treeUri = Uri.parse(savedUriStr);
                androidx.documentfile.provider.DocumentFile tree =
                        androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri);
                if (tree != null && tree.exists() && tree.canWrite()) {
                    androidx.documentfile.provider.DocumentFile targetDir = tree;
                    String storageDir = storageDirectoryName(this.vin);
                    // If the user selected the VIN/General folder itself, do
                    // not create a misleading nested VIN/VIN directory.
                    boolean treeIsTarget = tree.getName() != null
                            && storageDir.equalsIgnoreCase(tree.getName().trim());
                    if (!treeIsTarget) {
                        androidx.documentfile.provider.DocumentFile sub = tree.findFile(storageDir);
                        if (sub == null || !sub.isDirectory()) {
                            sub = tree.createDirectory(storageDir);
                        }
                        if (sub != null) {
                            targetDir = sub;
                        }
                    }
                    androidx.documentfile.provider.DocumentFile newFile = targetDir.createFile(mimeType, displayName);
                    if (newFile != null) {
                        return new DownloadTarget(null, newFile.getUri()) {
                            @Override
                            OutputStream openOutputStream() throws IOException {
                                OutputStream stream = openTruncatedOutputStream(newFile.getUri());
                                if (stream == null) {
                                    throw new IOException("Could not open custom folder output stream");
                                }
                                return stream;
                            }
                        };
                    }
                }
            } catch (Exception e) {
                // Fallback to default if tree access fails
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            String path = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR;
            path += "/" + storageDirectoryName(this.vin);
            values.put(MediaStore.Downloads.RELATIVE_PATH, path);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                // Validate the provider now. Some restricted profiles return a
                // non-null URI but throw when it is opened; logging must fall back
                // to a file instead of failing after the session has started.
                try (OutputStream probe = context.getContentResolver().openOutputStream(uri, "w")) {
                    if (probe != null) {
                        return new DownloadTarget(null, uri) {
                            @Override
                            OutputStream openOutputStream() throws IOException {
                                OutputStream stream = openTruncatedOutputStream(uri);
                                if (stream == null) {
                                    throw new IOException("Could not open Download output stream: " + uri);
                                }
                                return stream;
                            }
                        };
                    }
                } catch (Exception providerError) {
                    Log.w(TAG, "MediaStore URI was not writable; using file fallback", providerError);
                    try { context.getContentResolver().delete(uri, null, null); }
                    catch (Exception ignored) {}
                }
            }
            // MediaStore.insert() returned null (some emulators / restricted storage
            // profiles do this). Fall through to a direct file under Downloads so
            // logging still works instead of throwing on the first record.
            Log.w(TAG, "MediaStore insert returned null for " + displayName
                    + "; falling back to direct file path");
        }
        return localFileTarget(displayName);
    }

    private DownloadTarget localFileTarget(String displayName) throws IOException {
        File downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBDIR);
        downloadDir = new File(downloadDir, storageDirectoryName(this.vin));
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            throw new IOException("Failed to create download directory: " + downloadDir.getAbsolutePath());
        }
        File file = new File(downloadDir, displayName);
        return new DownloadTarget(file, null) {
            @Override
            OutputStream openOutputStream() throws IOException {
                return new FileOutputStream(file, false);
            }
        };
    }

    private void markDownloadComplete(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        try {
            context.getContentResolver().update(uri, values, null, null);
        } catch (Exception ignored) {
            // Non-fatal metadata update. The file content has already been flushed.
        }
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private static String sanitizeVin(String vin) {
        if (vin == null) {
            return "";
        }
        String normalized = vin.trim().toUpperCase(Locale.US);
        if (normalized.isEmpty() || isVinPlaceholder(normalized)) {
            return "";
        }
        return normalized.replaceAll("[^A-Z0-9_-]", "").trim();
    }

    private static String storageDirectoryName(String vin) {
        String cleanVin = sanitizeVin(vin);
        return cleanVin.isEmpty() ? NO_VIN_SUBDIR : cleanVin;
    }

    private static boolean isVinPlaceholder(String value) {
        return "UNKNOWN".equals(value)
                || "UNKNOWN_VIN".equals(value)
                || "UNKNOWNVIN".equals(value)
                || "N/A".equals(value)
                || "NA".equals(value)
                || "NULL".equals(value)
                || "NONE".equals(value)
                || "NO_DATA".equals(value)
                || "NODATA".equals(value)
                || "NOT_AVAILABLE".equals(value)
                || "NOTAVAILABLE".equals(value)
                || "UNAVAILABLE".equals(value)
                || "UNDEFINED".equals(value);
    }

    private abstract static class DownloadTarget {
        private final File file;
        private final Uri uri;

        private DownloadTarget(File file, Uri uri) {
            this.file = file;
            this.uri = uri;
        }

        abstract OutputStream openOutputStream() throws IOException;
    }
}
