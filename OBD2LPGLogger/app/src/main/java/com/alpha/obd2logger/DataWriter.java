package com.alpha.obd2logger;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DataWriter implements AutoCloseable {
    private static final String DOWNLOAD_SUBDIR = "OBD2LPGLogger";

    private final Context context;
    private final File csvFile;
    private final File jsonlFile;
    private final Uri csvUri;
    private final Uri jsonlUri;
    private final BufferedWriter csvWriter;
    private final BufferedWriter jsonlWriter;
    private final List<PIDDefinition> pids;
    private int recordsSinceFlush = 0;

    public DataWriter(Context context, String sessionId) throws IOException {
        this(context, sessionId, PIDCatalogue.getAll());
    }

    public DataWriter(Context context, String sessionId, List<PIDDefinition> pids) throws IOException {
        this.context = context.getApplicationContext();
        this.pids = pids;

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
        header.append("timestamp,elapsed_s,fuel_mode,vehicle_brand,vin");
        for (PIDDefinition pid : pids) {
            header.append(',').append(csvEscape(pid.getName() + " (" + pid.getUnit() + ")"));
        }
        csvWriter.write(header.toString());
        csvWriter.newLine();
    }

    public void writeRecord(DataRecord record) throws IOException {
        StringBuilder csvRow = new StringBuilder();
        csvRow.append(csvEscape(record.getTimestamp()))
                .append(',').append(record.getElapsedS())
                .append(',').append(csvEscape(record.getFuelMode()))
                .append(',').append(csvEscape(record.getVehicleBrand()))
                .append(',').append(csvEscape(record.getVin()));

        JSONObject json = new JSONObject();
        try {
            json.put("timestamp", record.getTimestamp());
            json.put("elapsed_s", record.getElapsedS());
            json.put("fuel_mode", record.getFuelMode());
            json.put("vehicle_brand", record.getVehicleBrand());
            json.put("vin", record.getVin());
        } catch (JSONException e) {
            throw new IOException("Failed to build JSON record", e);
        }

        Map<String, SensorSample> sampleMap = new HashMap<>();
        for (SensorSample sample : record.getSamples()) {
            sampleMap.put(sample.getName(), sample);
        }
        for (PIDDefinition pid : pids) {
            SensorSample sample = sampleMap.get(pid.getName());
            Double value = sample != null ? sample.getValue() : null;
            csvRow.append(',').append(value == null ? "" : value);
            try {
                json.put(pid.key(), value == null ? JSONObject.NULL : value);
            } catch (JSONException e) {
                throw new IOException("Failed to add PID JSON field " + pid.key(), e);
            }
        }

        csvWriter.write(csvRow.toString());
        csvWriter.newLine();
        jsonlWriter.write(json.toString());
        jsonlWriter.newLine();

        recordsSinceFlush++;
        if (recordsSinceFlush % 10 == 0) {
            csvWriter.flush();
            jsonlWriter.flush();
        }
    }

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
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBDIR);
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
        if (failure != null) {
            throw failure;
        }
    }

    private DownloadTarget createDownloadTarget(String displayName, String mimeType) throws IOException {
        String savedUriStr = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE).getString("custom_log_folder_uri", null);
        if (savedUriStr != null) {
            try {
                Uri treeUri = Uri.parse(savedUriStr);
                androidx.documentfile.provider.DocumentFile tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri);
                if (tree != null && tree.exists() && tree.canWrite()) {
                    androidx.documentfile.provider.DocumentFile newFile = tree.createFile(mimeType, displayName);
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
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR);
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

        File downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_SUBDIR);
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
