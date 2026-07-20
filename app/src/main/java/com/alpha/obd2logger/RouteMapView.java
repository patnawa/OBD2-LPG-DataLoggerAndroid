package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dependency-free route renderer: draws the session's GPS trace as a polyline
 * with each segment colored by a telemetry channel (speed or boost), in the
 * same hand-drawn-View tradition as FuelMapView / GraphView.
 *
 * <p>Deliberately not a tile map — no network, no SDK key, works offline. The
 * trace is projected with a local equirectangular projection (lon scaled by
 * cos(midLat)), which is exact enough for a drive session's extent.
 */
public class RouteMapView extends View {

    public enum ColorMode { SPEED, BOOST }

    private final List<LogReplayParser.RoutePoint> points = new ArrayList<>();
    private final Paint pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ColorMode colorMode = ColorMode.SPEED;
    private String emptyText = "";

    private int colorLow;
    private int colorMid;
    private int colorHigh;
    private int colorText;
    private int colorMuted;

    public RouteMapView(Context context) {
        this(context, null);
    }

    public RouteMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeCap(Paint.Cap.ROUND);
        pathPaint.setStrokeJoin(Paint.Join.ROUND);
        markerPaint.setStyle(Paint.Style.FILL);
        colorLow = ContextCompat.getColor(context, R.color.accent);
        colorMid = ContextCompat.getColor(context, R.color.warning);
        colorHigh = ContextCompat.getColor(context, R.color.danger);
        colorText = ContextCompat.getColor(context, R.color.text);
        colorMuted = ContextCompat.getColor(context, R.color.muted);
        textPaint.setColor(colorMuted);
        textPaint.setTextSize(dp(11));
    }

    /** Replace the route. Call from the UI thread (or post + invalidate). */
    public void setRoute(List<LogReplayParser.RoutePoint> route) {
        points.clear();
        if (route != null) points.addAll(route);
        invalidate();
    }

    public void setColorMode(ColorMode mode) {
        if (mode != null && mode != colorMode) {
            colorMode = mode;
            invalidate();
        }
    }

    public void setEmptyText(String text) {
        emptyText = text != null ? text : "";
        invalidate();
    }

    public boolean hasRoute() {
        return points.size() >= 2;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (points.size() < 2) {
            textPaint.setColor(colorMuted);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(emptyText, w / 2f, h / 2f, textPaint);
            return;
        }

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (LogReplayParser.RoutePoint p : points) {
            minLat = Math.min(minLat, p.lat);
            maxLat = Math.max(maxLat, p.lat);
            minLon = Math.min(minLon, p.lon);
            maxLon = Math.max(maxLon, p.lon);
        }
        double midLat = Math.toRadians((minLat + maxLat) / 2.0);
        double lonScale = Math.cos(midLat);
        double spanX = Math.max((maxLon - minLon) * lonScale, 1e-6);
        double spanY = Math.max(maxLat - minLat, 1e-6);

        float pad = dp(18);
        float usableW = w - 2 * pad;
        float usableH = h - 2 * pad - dp(20); // legend strip at the bottom
        // Preserve aspect so the route is not stretched.
        float scale = (float) Math.min(usableW / spanX, usableH / spanY);
        float drawnW = (float) (spanX * scale);
        float drawnH = (float) (spanY * scale);
        float offX = pad + (usableW - drawnW) / 2f;
        float offY = pad + (usableH - drawnH) / 2f;

        double[] metric = new double[points.size()];
        double metricMin = Double.MAX_VALUE, metricMax = -Double.MAX_VALUE;
        boolean anyMetric = false;
        for (int i = 0; i < points.size(); i++) {
            Double v = colorMode == ColorMode.SPEED
                    ? points.get(i).speedKmh : points.get(i).boostPsi;
            if (v != null && Double.isFinite(v)) {
                metric[i] = v;
                metricMin = Math.min(metricMin, v);
                metricMax = Math.max(metricMax, v);
                anyMetric = true;
            } else {
                metric[i] = Double.NaN;
            }
        }
        if (!anyMetric || metricMax - metricMin < 1e-9) {
            metricMin = 0;
            metricMax = 1;
        }

        pathPaint.setStrokeWidth(dp(4));
        float prevX = 0, prevY = 0;
        boolean hasPrev = false;
        for (int i = 0; i < points.size(); i++) {
            LogReplayParser.RoutePoint p = points.get(i);
            float x = offX + (float) ((p.lon - minLon) * lonScale * scale);
            float y = offY + drawnH - (float) ((p.lat - minLat) * scale);
            if (hasPrev) {
                double v = Double.isNaN(metric[i]) ? metricMin : metric[i];
                float frac = (float) ((v - metricMin) / (metricMax - metricMin));
                pathPaint.setColor(rampColor(frac));
                if (Double.isNaN(metric[i])) pathPaint.setAlpha(90);
                canvas.drawLine(prevX, prevY, x, y, pathPaint);
                pathPaint.setAlpha(255);
            }
            prevX = x;
            prevY = y;
            hasPrev = true;
        }

        // Start (low color) and end (high color) markers.
        LogReplayParser.RoutePoint first = points.get(0);
        LogReplayParser.RoutePoint last = points.get(points.size() - 1);
        markerPaint.setColor(colorLow);
        canvas.drawCircle(offX + (float) ((first.lon - minLon) * lonScale * scale),
                offY + drawnH - (float) ((first.lat - minLat) * scale), dp(6), markerPaint);
        markerPaint.setColor(colorHigh);
        canvas.drawCircle(offX + (float) ((last.lon - minLon) * lonScale * scale),
                offY + drawnH - (float) ((last.lat - minLat) * scale), dp(6), markerPaint);

        // Legend: metric range for the active color ramp.
        String unit = colorMode == ColorMode.SPEED ? "km/h" : "psi";
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(colorText);
        canvas.drawText(String.format(Locale.US, "%.0f %s", metricMin, unit),
                pad, h - dp(6), textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(String.format(Locale.US, "%.0f %s", metricMax, unit),
                w - pad, h - dp(6), textPaint);
        // Gradient bar between the two labels.
        float barLeft = pad + dp(52);
        float barRight = w - pad - dp(52);
        float barY = h - dp(9);
        if (barRight > barLeft) {
            pathPaint.setStrokeWidth(dp(5));
            int steps = 24;
            for (int i = 0; i < steps; i++) {
                float f0 = i / (float) steps;
                float f1 = (i + 1) / (float) steps;
                pathPaint.setColor(rampColor((f0 + f1) / 2f));
                canvas.drawLine(barLeft + f0 * (barRight - barLeft), barY,
                        barLeft + f1 * (barRight - barLeft), barY, pathPaint);
            }
        }
    }

    /** Green → amber → red ramp using the theme's status swatches. */
    private int rampColor(float frac) {
        frac = Math.max(0f, Math.min(1f, frac));
        if (frac < 0.5f) return blend(colorLow, colorMid, frac * 2f);
        return blend(colorMid, colorHigh, (frac - 0.5f) * 2f);
    }

    private static int blend(int c1, int c2, float t) {
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.rgb(r, g, b);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
