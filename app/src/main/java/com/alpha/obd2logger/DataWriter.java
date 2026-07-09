package com.alpha.obd2logger;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONException;
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

public final class DataWriter implements AutoCloseable {
    private static final String TAG = "DataWriter";
    private static final String DOWNLOAD_SUBDIR = "TunerMapPro";

    private final Context context;
    private final String vin;
    private final File csvFile;
    private final File jsonlFile;
    private final Uri csvUri;
    private final Uri jsonlUri;
    private final BufferedWriter csvWriter;
    private final BufferedWriter jsonlWriter;
    private final List<PIDDefinition> pids;
    private int recordsSinceFlush = 0;

    // Ordered column keys for the CSV/JSONL body. Built once at construction from
    // the poll PID list PLUS every derived sensor that the logging loop may emit
    // (Fuel Economy km/L + L/100km, Turbo Boost kPa + psi, DPF health/regen). The
    // record's samples are written by this key so derived values that are shown on
    // screen are also persisted to the log — previously they were dropped entirely.
    private final List<String> columnKeys = new ArrayList<>();
    private final Map<String, String> columnLabel = new LinkedHashMap<>();

    public DataWriter(Context context, String sessionId) throws IOException {
        this(context, sessionId, PIDCatalogue.getAll(), null);
    }

    public DataWriter(Context context, String sessionId, List<PIDDefinition> pids) throws IOException {
        this(context, sessionId, pids, null);
    }

    public DataWriter(Context context, String sessionId, List<PIDDefinition> pids, String vin) throws IOException {
        this.context = context.getApplicationContext();
        this.pids = pids;
        this.vin = vin;

        buildColumns(pids);

        DownloadTarget csvTarget = createDownloadTarget(sessionId + "_obd2.csv", "text/csv");
        DownloadTarget jsonlTarget = createDownloadTarget(sessionId + "_obd2.jsonl", "application/x-ndjson");

        this.csvFile = csvTarget.file;
        this.jsonlFile = jsonlTarget.file;
        this.csvUri = csvTarget.uri;
        this.jsonlUri = jsonlTarget.uri;
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
        registerDerived("derived_boost_kpa", "Turbo Boost (kPa)");
        registerDerived("derived_boost_psi", "Turbo Boost (psi)");
        registerDerived("derived_dpf_health", "DPF Health (status)");
        registerDerived("derived_dpf_regen", "DPF Regen (active)");
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

        StringBuilder csvRow = new StringBuilder();
        csvRow.append(csvEscape(record.getTimestamp()))
                .append(',').append(record.getElapsedS())
                .append(',').append(csvEscape(record.getFuelMode()))
                .append(',').append(csvEscape(loopStatus))
                .append(',').append(csvEscape(record.getVehicleBrand()))
                .append(',').append(csvEscape(record.getVin()));

        JSONObject json = new JSONObject();
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
            } catch (JSONException e) {
                throw new IOException("Failed to add PID JSON field " + key, e);
            }
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

        summary.add(record);
    }

    /**
     * Lightweight per-column aggregate (min / mean / max) computed from the numeric
     * samples in every record. Produces the session {@code _summary.json} on close so
     * users get trip stats (including fuel used) without re-parsing the whole CSV.
     */
    private static final class SummaryCollector {
        private final Map<String, Double> min = new LinkedHashMap<>();
        private final Map<String, Double> max = new LinkedHashMap<>();
        private final Map<String, Double> sum = new LinkedHashMap<>();
        private final Map<String, Integer> count = new LinkedHashMap<>();
        private int records = 0;
        private double fuelLiters = 0.0;   // accumulated from L/100km × distance
        private double distanceKm = 0.0;

        void add(DataRecord record) {
            records++;
            Map<String, SensorSample> byKey = new LinkedHashMap<>();
            for (SensorSample s : record.getSamples()) byKey.put(s.getPidKey(), s);

            for (SensorSample s : record.getSamples()) {
                Double v = s.getValue();
                if (v == null || Double.isNaN(v) || Double.isInfinite(v)) continue;
                String k = s.getPidKey();
                if (!count.containsKey(k)) { min.put(k, v); max.put(k, v); sum.put(k, 0.0); count.put(k, 0); }
                if (v < min.get(k)) min.put(k, v);
                if (v > max.get(k)) max.put(k, v);
                sum.put(k, sum.get(k) + v);
                count.put(k, count.get(k) + 1);
            }

            // Integrate fuel used if we have km/L and speed over the sample interval.
            Double kml = byKey.containsKey("derived_fuel_kmL") ? byKey.get("derived_fuel_kmL").getValue() : null;
            Double speed = byKey.containsKey("01_0D") ? byKey.get("01_0D").getValue() : null;
            if (kml != null && kml > 0 && speed != null && speed >= 0) {
                // elapsed_s is absolute; approximate dt from distance handled by caller via markDt.
                double km = speed / 3600.0; // km travelled in one second
                distanceKm += km;
                fuelLiters += km / kml;
            }
        }

        int getRecords() { return records; }
        double getDistanceKm() { return distanceKm; }
        double getFuelLiters() { return fuelLiters; }
        Set<String> keys() { return count.keySet(); }
        double avg(String k) { return count.get(k) == 0 ? 0 : sum.get(k) / count.get(k); }
        double getMin(String k) { return min.getOrDefault(k, 0.0); }
        double getMax(String k) { return max.getOrDefault(k, 0.0); }
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

    public String getCsvLocation() {
        return csvUri != null ? csvUri.toString() : csvFile.getAbsolutePath();
    }

    public String getJsonlLocation() {
        return jsonlUri != null ? jsonlUri.toString() : jsonlFile.getAbsolutePath();
    }

    public File getDownloadFolderFile() {
        String cleanVin = sanitizeVin(this.vin);
        File downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBDIR);
        if (!cleanVin.isEmpty()) {
            return new File(downloadDir, cleanVin);
        }
        return downloadDir;
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
            writeSummary();
        } catch (IOException e) {
            if (failure == null) failure = e;
            Log.w(TAG, "Failed to write session summary", e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void writeSummary() throws IOException {
        if (summary.getRecords() == 0) return;
        JSONObject root = new JSONObject();
        try {
            root.put("records", summary.getRecords());
            root.put("distance_km", Math.round(summary.getDistanceKm() * 100.0) / 100.0);
            root.put("fuel_liters", Math.round(summary.getFuelLiters() * 100.0) / 100.0);
            JSONObject stats = new JSONObject();
            for (String k : summary.keys()) {
                JSONObject s = new JSONObject();
                s.put("min", summary.getMin(k));
                s.put("avg", Math.round(summary.avg(k) * 10000.0) / 10000.0);
                s.put("max", summary.getMax(k));
                stats.put(k, s);
            }
            root.put("columns", stats);
        } catch (JSONException e) {
            throw new IOException("Failed to build summary", e);
        }

        String baseName = (csvFile != null ? csvFile.getName() : "session_obd2.csv")
                .replace("_obd2.csv", "");
        DownloadTarget target = createDownloadTarget(baseName + "_summary.json", "application/json");
        try (OutputStream out = target.openOutputStream();
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
        if (target.uri != null) markDownloadComplete(target.uri);
    }

    private DownloadTarget createDownloadTarget(String displayName, String mimeType) throws IOException {
        String savedUriStr = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE).getString("custom_log_folder_uri", null);
        if (savedUriStr != null) {
            try {
                Uri treeUri = Uri.parse(savedUriStr);
                androidx.documentfile.provider.DocumentFile tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri);
                if (tree != null && tree.exists() && tree.canWrite()) {
                    androidx.documentfile.provider.DocumentFile targetDir = tree;
                    String cleanVin = sanitizeVin(this.vin);
                    if (!cleanVin.isEmpty()) {
                        androidx.documentfile.provider.DocumentFile sub = tree.findFile(cleanVin);
                        if (sub == null || !sub.isDirectory()) {
                            sub = tree.createDirectory(cleanVin);
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
                                OutputStream stream = context.getContentResolver().openOutputStream(newFile.getUri(), "w");
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
            String cleanVin = sanitizeVin(this.vin);
            if (!cleanVin.isEmpty()) {
                path += "/" + cleanVin;
            }
            values.put(MediaStore.Downloads.RELATIVE_PATH, path);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore could not create Download entry: " + displayName);
            }
            return new DownloadTarget(null, uri) {
                @Override
                OutputStream openOutputStream() throws IOException {
                    OutputStream stream = context.getContentResolver().openOutputStream(uri, "w");
                    if (stream == null) {
                        throw new IOException("Could not open Download output stream: " + uri);
                    }
                    return stream;
                }
            };
        }

        String cleanVin = sanitizeVin(this.vin);
        File downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBDIR);
        if (!cleanVin.isEmpty()) {
            downloadDir = new File(downloadDir, cleanVin);
        }
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
        if (vin == null || vin.isEmpty() || "Unknown".equalsIgnoreCase(vin)) {
            return "";
        }
        return vin.replaceAll("[^a-zA-Z0-9_-]", "").toUpperCase(Locale.US).trim();
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
