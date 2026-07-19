package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Versioned OTA update system for DTC databases — 100% free, no backend.
 *
 * Flow:
 *  1. Fetch manifest.json from GitHub raw (tiny ~1KB).
 *  2. Compare manifest versions against locally stored versions.
 *  3. Download only the files whose version changed.
 *  4. Atomically replace the local file and update the stored version.
 *
 * The manifest also carries an etag field (optional) so we can issue
 * conditional GETs in the future if GitHub adds If-None-Match support
 * for raw URLs (currently raw.githubusercontent.com ignores it, but
 * we store the field for forward-compat).
 *
 * Manifest format (dtc_updates/manifest.json):
 * {
 *   "version": 3,
 *   "files": [
 *     { "name": "dtc_database.json", "version": 5 },
 *     { "name": "dtc_toyota.json",   "version": 2 },
 *     { "name": "dtc_byd.json",      "version": 1 }
 *   ]
 * }
 *
 * Storage layout (app internal getFilesDir()):
 *   dtc_database.json        ← downloaded data (used by DtcDatabase)
 *   dtc_database.json.ver    ← just the integer version string
 *   dtc_toyota.json
 *   dtc_toyota.json.ver
 *   ...
 *
 * Hosts: raw.githubusercontent.com (free, CDN-backed, unlimited).
 */
public final class DtcUpdater {
    private static final String TAG = "DtcUpdater";

    /**
     * Serial background executor for OTA checks — replaces the deprecated
     * AsyncTask.execute (same fire-and-forget, one-at-a-time semantics).
     */
    private static final java.util.concurrent.ExecutorService NETWORK_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "DtcUpdater-ota");
                t.setDaemon(true);
                return t;
            });

    /** Repo path for OTA updates. Edit this if the repo URL changes. */
    private static final String GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/patnawa/OBD2-LPG-DataLoggerAndroid/main/dtc_updates/";

    private static final String MANIFEST_FILE = "manifest.json";
    private static final String PREFS_NAME = "dtc_ota_prefs";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final long MIN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000; // 6 hours

    private DtcUpdater() {
    }

    /**
     * Check GitHub for DTC updates. Safe to call on every app start —
     * rate-limited to one network check per 6 hours.
     *
     * @param context app context
     * @param force   true to bypass the 6-hour throttle
     */
    public static void checkForUpdates(Context context, boolean force) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        long now = System.currentTimeMillis();

        if (!force && (now - lastCheck) < MIN_CHECK_INTERVAL_MS) {
            Log.d(TAG, "Skipping update check (throttled, last checked "
                    + ((now - lastCheck) / 1000 / 60) + " min ago)");
            return;
        }

        NETWORK_EXECUTOR.execute(() -> {
            try {
                String manifestJson = downloadText(GITHUB_RAW_BASE + MANIFEST_FILE);
                if (manifestJson == null) {
                    Log.d(TAG, "No manifest found at " + GITHUB_RAW_BASE);
                    return;
                }

                // Stamp the throttle only after the manifest actually
                // downloaded — a failed check (offline in the garage) must
                // not suppress retries for the next 6 hours.
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply();

                JSONObject root = new JSONObject(manifestJson);
                JSONArray files = root.optJSONArray("files");
                if (files == null) {
                    Log.w(TAG, "Manifest has no 'files' array");
                    return;
                }

                int updatedCount = 0;
                for (int i = 0; i < files.length(); i++) {
                    JSONObject entry = files.getJSONObject(i);
                    String name = entry.getString("name");
                    int remoteVersion = entry.optInt("version", -1);
                    if (remoteVersion < 0) continue;

                    int localVersion = readLocalVersion(context, name);
                    if (remoteVersion > localVersion) {
                        boolean ok = downloadAndSave(context, name, remoteVersion);
                        if (ok) {
                            updatedCount++;
                            Log.i(TAG, "Updated " + name + " v" + localVersion + " → v" + remoteVersion);
                        }
                    } else {
                        Log.d(TAG, name + " is up to date (v" + localVersion + ")");
                    }
                }
                if (updatedCount > 0) {
                    Log.i(TAG, "OTA complete: " + updatedCount + " file(s) updated.");
                    // DtcDatabase caches are static and already loaded from
                    // the previous file — a full reload will pick up the
                    // new content on next app start, or immediately if the
                    // caller triggers DtcDatabase.init() again.
                } else {
                    Log.d(TAG, "All DTC files are up to date.");
                }
            } catch (Exception e) {
                Log.w(TAG, "OTA check failed: " + e.getMessage());
            }
        });
    }

    // ── Core download helpers ──────────────────────────────────────────

    private static String downloadText(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);

            if (conn.getResponseCode() != 200) return null;

            // Accumulate raw bytes and decode once — decoding each chunk
            // separately corrupts multi-byte UTF-8 sequences that straddle
            // a read boundary (accented/Thai DTC descriptions).
            InputStream is = conn.getInputStream();
            try {
                return new String(DtcDatabase.readFully(is), StandardCharsets.UTF_8);
            } finally {
                is.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "downloadText failed for " + urlStr + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean downloadAndSave(Context context, String filename, int version) {
        String data = downloadText(GITHUB_RAW_BASE + filename);
        if (data == null) return false;

        // Validate it's real JSON before writing
        try {
            new JSONObject(data);
        } catch (Exception e) {
            Log.w(TAG, "Downloaded " + filename + " is not valid JSON, aborting.");
            return false;
        }

        File outFile = new File(context.getFilesDir(), filename);
        File tmpFile = new File(context.getFilesDir(), filename + ".tmp");

        // Write to temp file first, then atomically rename
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            fos.write(data.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Log.w(TAG, "Failed to write " + filename + ": " + e.getMessage());
            return false;
        }

        // Atomic rename
        if (!tmpFile.renameTo(outFile)) {
            // Fallback: delete and rename (renameTo can fail on some Android FS)
            if (outFile.exists()) outFile.delete();
            if (!tmpFile.renameTo(outFile)) {
                // Install failed after the old file was deleted — do NOT bump
                // the version marker, or the updater would consider the
                // (now missing) database up to date and never re-download.
                Log.w(TAG, "Failed to install " + filename + " (rename failed twice)");
                tmpFile.delete();
                return false;
            }
        }

        // Write version marker
        writeLocalVersion(context, filename, version);
        return true;
    }

    // ── Version marker helpers ─────────────────────────────────────────

    private static int readLocalVersion(Context context, String filename) {
        File verFile = new File(context.getFilesDir(), filename + ".ver");
        if (!verFile.exists()) return -1;
        try (BufferedReader br = new BufferedReader(new FileReader(verFile))) {
            String line = br.readLine();
            return line != null ? Integer.parseInt(line.trim()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void writeLocalVersion(Context context, String filename, int version) {
        File verFile = new File(context.getFilesDir(), filename + ".ver");
        try (FileOutputStream fos = new FileOutputStream(verFile)) {
            fos.write(String.valueOf(version).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Failed to write version marker for " + filename);
        }
    }

    /**
     * Force a full reload of DtcDatabase from internal storage (OTA files).
     * Call this after a successful OTA update if you want the new codes
     * available immediately without restarting the app.
     */
    public static void reloadDtcDatabase(Context context) {
        // Drop the static caches and re-init — init() alone is a no-op
        // once initialized, so it would keep serving stale descriptions.
        DtcDatabase.reload(context);
        String lastVin = getLastVin(context);
        if (lastVin != null && !lastVin.isEmpty()) {
            DtcDatabase.initForVin(context, lastVin);
        }
    }

    private static String getLastVin(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("OBD2Prefs", Context.MODE_PRIVATE);
        return prefs.getString("last_vin", null);
    }
}
