package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Zero-backend crowdsourced DTC collector.
 *
 * When the app encounters a DTC not in the local database, it reports it
 * to the project's GitHub repo as a new Issue — 100% free, no server needed.
 *
 * GitHub Issues API (free, 5000 req/hour unauthenticated, 15000 with token):
 *   POST https://api.github.com/repos/{owner}/{repo}/issues
 *
 * Rate-limiting & privacy:
 *  - Each unique DTC code is reported at most once per 7 days.
 *  - No VIN is sent — only the 3-char WMI (manufacturer country+code).
 *  - No location, no user identifiers.
 *  - All network calls are async, fire-and-forget.
 *
 * You review collected issues in your GitHub repo's Issues tab, research the
 * code, and then push an updated dtc_*.json to the dtc_updates/ folder.
 * The DtcUpdater will deliver it to all users via OTA.
 *
 * If you want to use a GitHub token (raises rate limit to 15000/hour and
 * avoids the "Create branch" issue body being shown on the web UI), store
 * it in BuildConfig.GITHUB_TOKEN via gradle:
 *   buildConfigField "String", "GITHUB_TOKEN", '"ghp_xxx"'
 * Leave empty for unauthenticated (fine for a single-user/thailand app).
 */
public final class TelemetryClient {
    private static final String TAG = "TelemetryClient";

    /**
     * Serial background executor for issue reporting — replaces the deprecated
     * AsyncTask.execute (same fire-and-forget, one-at-a-time semantics).
     */
    private static final java.util.concurrent.ExecutorService NETWORK_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "TelemetryClient-report");
                t.setDaemon(true);
                return t;
            });

    private static final String GITHUB_API =
            "https://api.github.com/repos/patnawa/OBD2-LPG-DataLoggerAndroid/issues";

    private static final String PREFS_NAME = "dtc_telemetry_prefs";
    private static final String KEY_REPORTED = "reported_codes";
    private static final long RE_REPORT_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

    private TelemetryClient() {
    }

    /**
     * Report an unknown DTC encountered during a scan.
     * Fire-and-forget, fully async, swallows all exceptions.
     *
     * @param code   the DTC code (e.g. "P1234")
     * @param brand  detected vehicle brand (may be null / UNKNOWN)
     */
    public static void reportUnknownDtc(Context context, String code, VinBrandDetector.Brand brand) {
        if (code == null || code.isEmpty()) return;
        if (context == null) return; // safety: no context (e.g. unit tests)

        String brandName = (brand != null) ? brand.name() : "UNKNOWN";

        // Rate-limit: don't re-report the same code within 7 days
        if (wasRecentlyReported(context, code)) {
            Log.d(TAG, "Skipping " + code + " (already reported recently)");
            return;
        }

        NETWORK_EXECUTOR.execute(() -> {
            try {
                String title = "[DTC] " + code + " — " + brandName;

                // Body includes enough context for you to research, but no VIN.
                StringBuilder body = new StringBuilder();
                body.append("## Unknown DTC Reported\n");
                body.append("- **Code:** `").append(code).append("`\n");
                body.append("- **Brand:** ").append(brandName).append("\n");
                body.append("- **App version:** ").append(BuildConfig.VERSION_NAME).append("\n");
                body.append("- **Timestamp:** ").append(new java.util.Date().toString()).append("\n\n");
                body.append("_Auto-reported by OBD2LPGLogger telemetry. ");
                body.append("No VIN or personal data included._\n\n");
                body.append("---\n");
                body.append("If you know the description for this code, ");
                body.append("please add it to the appropriate `dtc_*.json` file ");

                JSONObject payload = new JSONObject();
                payload.put("title", title);
                payload.put("body", body.toString());
                payload.put("labels", new org.json.JSONArray(
                        "[\"unknown-dtc\",\"auto-reported\"]"));

                URL url = new URL(GITHUB_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "OBD2LPGLogger-Android");

                // Optional: use GitHub token for higher rate limits
                String token = getGithubToken();
                if (token != null && !token.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                }

                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 201) {
                    Log.i(TAG, "Reported unknown DTC: " + code + " (GitHub issue created)");
                    markReported(context, code);
                } else if (responseCode == 422) {
                    // Validation error — labels might not exist yet. Retry without labels.
                    Log.w(TAG, "GitHub returned 422, retrying without labels");
                    retryWithoutLabels(payload);
                    markReported(context, code);
                } else {
                    Log.w(TAG, "GitHub API returned " + responseCode + " for " + code);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Error reporting DTC: " + e.getMessage());
            }
        });
    }

    /** Retry the issue creation without the labels array (labels must be pre-created). */
    private static void retryWithoutLabels(JSONObject originalPayload) {
        HttpURLConnection conn = null;
        try {
            JSONObject retry = new JSONObject();
            retry.put("title", originalPayload.getString("title"));
            retry.put("body", originalPayload.getString("body"));

            URL url = new URL(GITHUB_API);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "OBD2LPGLogger-Android");

            String token = getGithubToken();
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            conn.setDoOutput(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(retry.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            Log.i(TAG, "Retry without labels: HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "Retry failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Rate-limit storage ─────────────────────────────────────────────

    private static boolean wasRecentlyReported(Context context, String code) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = code + "_time";
        long lastReported = prefs.getLong(key, 0);
        return (System.currentTimeMillis() - lastReported) < RE_REPORT_INTERVAL_MS;
    }

    private static void markReported(Context context, String code) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(code + "_time", System.currentTimeMillis()).apply();
    }

    // ── Token ──────────────────────────────────────────────────────────

    /**
     * Returns a GitHub token from BuildConfig if configured, otherwise null.
     * To enable: add to build.gradle:
     *   buildConfigField "String", "GITHUB_TOKEN", '"ghp_your_token"'
     */
    private static String getGithubToken() {
        return BuildConfig.GITHUB_TOKEN != null ? BuildConfig.GITHUB_TOKEN : "";
    }
}
