package com.alpha.obd2logger;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent time series of health metrics — the shared storage layer behind
 * long-horizon prognostics (ΔVE drift, battery crank minimum, Mode 06
 * catalyst/O2 monitor values).
 *
 * <p>Each named series is a capped list of {@code (epochMs, value)} points in
 * SharedPreferences, following the {@link CommonVehicleDataStore} JSON
 * pattern. Analysis lives in {@link TrendAnalysis}; this class only stores.
 *
 * <p>Points are appended monotonically (a point older than the last stored
 * one is dropped rather than sorted in — clock rollback must not fabricate
 * history). Series are capped at {@link #MAX_POINTS}; the oldest points fall
 * off, which is fine because every consumer cares about recent drift, not
 * ancient absolutes.
 */
public final class HealthTrendStore {
    private static final String PREFS = "OBD2Prefs";
    private static final String KEY_PREFIX = "health_trend_v1_";
    static final int MAX_POINTS = 120;

    /** One observation. Immutable. */
    public static final class Point {
        public final long epochMs;
        public final double value;

        public Point(long epochMs, double value) {
            this.epochMs = epochMs;
            this.value = value;
        }
    }

    private HealthTrendStore() {
    }

    /**
     * Append one observation to a named series.
     *
     * @return true when the point was stored (finite value, non-regressing
     * timestamp)
     */
    public static boolean append(Context context, String series, long epochMs, double value) {
        if (context == null || series == null || series.isEmpty()) return false;
        if (!Double.isFinite(value) || epochMs <= 0) return false;

        List<Point> points = read(context, series);
        if (!points.isEmpty() && epochMs < points.get(points.size() - 1).epochMs) {
            return false;
        }
        points.add(new Point(epochMs, value));
        while (points.size() > MAX_POINTS) {
            points.remove(0);
        }
        write(context, series, points);
        return true;
    }

    /** Read a series oldest-first. Never null. */
    public static List<Point> read(Context context, String series) {
        List<Point> points = new ArrayList<>();
        if (context == null || series == null) return points;
        String raw = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + series, null);
        if (raw == null || raw.trim().isEmpty()) return points;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                long t = p.optLong("t", 0);
                double v = p.optDouble("v", Double.NaN);
                if (t > 0 && Double.isFinite(v)) points.add(new Point(t, v));
            }
        } catch (Exception ignored) {
            // Corrupt series: treat as empty rather than crash health reporting.
        }
        return points;
    }

    public static void clear(Context context, String series) {
        if (context == null || series == null) return;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_PREFIX + series)
                .apply();
    }

    private static void write(Context context, String series, List<Point> points) {
        try {
            JSONArray arr = new JSONArray();
            for (Point p : points) {
                JSONObject o = new JSONObject();
                o.put("t", p.epochMs);
                o.put("v", p.value);
                arr.put(o);
            }
            context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_PREFIX + series, arr.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }
}
