package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Professional voltage timeline graph for the Battery Tester panel.
 * Shows real-time PID 0x42 voltage samples overlaid with diagnostic
 * threshold lines (resting, charging, cranking, overcharge).
 *
 * Features:
 *  - Smooth scrolling waveform (last N samples)
 *  - Color-coded threshold bands (green/yellow/red zones)
 *  - Min/max/avg indicators
 *  - Grid + axis labels
 *  - Cranking-voltage dip detection (marks the lowest point)
 *  - Dark-theme aware (uses dynamic colors)
 */
public class BatteryTestView extends View {

    // Voltage range to display (12V system)
    private static final float V_MIN = 8.0f;
    private static final float V_MAX = 16.0f;

    // Threshold lines to draw
    private static final float[] THRESHOLDS = {
        9.6f,   // Cranking minimum
        12.20f, // Resting low
        12.65f, // Resting full
        13.8f,  // Alternator min
        14.2f,  // Alternator optimal
        14.7f,  // Alternator max
    };
    private static final int[] THRESHOLD_COLORS = {
        0xFFEF5350, // red    — crank min
        0xFFFFB74D, // amber  — resting low
        0xFF66BB6A, // green  — resting full
        0xFF66BB6A, // green  — alt min
        0xFF42A5F5, // blue   — alt optimal
        0xFFEF5350, // red    — alt max
    };
    private static final String[] THRESHOLD_LABELS = {
        "Crank 9.6V", "Rest 12.2V", "Full 12.65V",
        "Alt 13.8V", "Opt 14.2V", "Alt 14.7V"
    };

    private final List<Float> samples = new ArrayList<>();
    private static final int MAX_SAMPLES = 300; // ~30 seconds at 100ms interval

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thresholdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minMaxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float minV = Float.MAX_VALUE;
    private float maxV = Float.MIN_VALUE;
    private float avgV = 0;
    private float crankMinV = Float.MAX_VALUE;  // lowest sample (cranking detection)

    private boolean isDarkTheme = false;

    public BatteryTestView(Context context) {
        super(context);
        init();
    }

    public BatteryTestView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BatteryTestView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(0xFF42A5F5);

        fillPaint.setStyle(Paint.Style.FILL);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.8f * density);
        gridPaint.setColor(0x30FFFFFF);

        thresholdPaint.setStyle(Paint.Style.STROKE);
        thresholdPaint.setStrokeWidth(1.0f * density);
        thresholdPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{6f * density, 3f * density}, 0));

        labelPaint.setTextSize(9.5f * density);
        labelPaint.setFakeBoldText(true);

        textPaint.setTextSize(10f * density);
        textPaint.setColor(0xCCFFFFFF);

        minMaxPaint.setTextSize(9.5f * density);
        minMaxPaint.setFakeBoldText(true);

        bgPaint.setColor(0xFF1A1A2E);
    }

    public void setDarkTheme(boolean dark) {
        this.isDarkTheme = dark;
        bgPaint.setColor(dark ? 0xFF1A1A2E : 0xFFF5F5F5);
        gridPaint.setColor(dark ? 0x30FFFFFF : 0x20000000);
        textPaint.setColor(dark ? 0xCCFFFFFF : 0xCC000000);
        labelPaint.setColor(dark ? 0xAAFFFFFF : 0xAA000000);
        invalidate();
    }

    /**
     * Add a voltage sample to the timeline.
     */
    public void addSample(float voltage) {
        samples.add(voltage);
        if (samples.size() > MAX_SAMPLES) {
            samples.remove(0);
        }

        if (voltage < minV) minV = voltage;
        if (voltage > maxV) maxV = voltage;
        if (voltage < crankMinV) crankMinV = voltage;

        float sum = 0;
        for (float s : samples) sum += s;
        avgV = sum / samples.size();

        postInvalidate();
    }

    /** Clear all samples and reset statistics. */
    public void clearSamples() {
        samples.clear();
        minV = Float.MAX_VALUE;
        maxV = Float.MIN_VALUE;
        avgV = 0;
        crankMinV = Float.MAX_VALUE;
        invalidate();
    }

    public float getMinV() { return minV == Float.MAX_VALUE ? 0 : minV; }
    public float getMaxV() { return maxV == Float.MIN_VALUE ? 0 : maxV; }
    public float getAvgV() { return avgV; }
    public float getCrankMinV() { return crankMinV == Float.MAX_VALUE ? 0 : crankMinV; }
    public int getSampleCount() { return samples.size(); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float density = getResources().getDisplayMetrics().density;

        float padLeft = 45f * density;
        float padRight = 16f * density;
        float padTop = 32f * density;
        float padBottom = 24f * density;
        float plotW = w - padLeft - padRight;
        float plotH = h - padTop - padBottom;

        // Background
        canvas.drawRect(0, 0, w, h, bgPaint);

        // Horizontal grid lines (every 1V)
        gridPaint.setColor(isDarkTheme ? 0x20FFFFFF : 0x15000000);
        for (float v = (int) V_MIN; v <= V_MAX; v += 1.0f) {
            float y = padTop + (1 - (v - V_MIN) / (V_MAX - V_MIN)) * plotH;
            canvas.drawLine(padLeft, y, w - padRight, y, gridPaint);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(java.util.Locale.US, "%.0fV", v), padLeft - 6 * density, y + 4 * density, textPaint);
        }

        // Threshold lines
        for (int i = 0; i < THRESHOLDS.length; i++) {
            float v = THRESHOLDS[i];
            if (v < V_MIN || v > V_MAX) continue;
            float y = padTop + (1 - (v - V_MIN) / (V_MAX - V_MIN)) * plotH;
            thresholdPaint.setColor(THRESHOLD_COLORS[i]);
            canvas.drawLine(padLeft, y, w - padRight, y, thresholdPaint);

            // Label at right edge
            labelPaint.setColor(THRESHOLD_COLORS[i]);
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(THRESHOLD_LABELS[i], w - padRight - 4 * density, y - 3 * density, labelPaint);
        }

        // Voltage waveform
        if (samples.size() >= 2) {
            float stepX = plotW / Math.max(1, MAX_SAMPLES - 1);
            int startIdx = Math.max(0, samples.size() - MAX_SAMPLES);

            // Filled area under curve
            Path fillPath = new Path();
            float firstX = padLeft;
            float firstY = padTop + (1 - (samples.get(startIdx) - V_MIN) / (V_MAX - V_MIN)) * plotH;
            fillPath.moveTo(firstX, padTop + plotH);
            fillPath.lineTo(firstX, firstY);

            Path linePath = new Path();
            linePath.moveTo(firstX, firstY);

            for (int i = startIdx + 1; i < samples.size(); i++) {
                float x = padLeft + (i - startIdx) * stepX;
                float y = padTop + (1 - (samples.get(i) - V_MIN) / (V_MAX - V_MIN)) * plotH;
                fillPath.lineTo(x, y);
                linePath.lineTo(x, y);
            }
            float lastX = padLeft + (samples.size() - 1 - startIdx) * stepX;
            fillPath.lineTo(lastX, padTop + plotH);
            fillPath.close();

            // Gradient fill
            fillPaint.setShader(new LinearGradient(0, padTop, 0, padTop + plotH,
                    0x4042A5F5, 0x0542A5F5, Shader.TileMode.CLAMP));
            canvas.drawPath(fillPath, fillPaint);

            // Line on top
            canvas.drawPath(linePath, linePaint);

            // Current value dot
            float currV = samples.get(samples.size() - 1);
            float currY = padTop + (1 - (currV - V_MIN) / (V_MAX - V_MIN)) * plotH;
            linePaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(lastX, currY, 4f * density, linePaint);
            linePaint.setColor(0xFF42A5F5);
            canvas.drawCircle(lastX, currY, 2.5f * density, linePaint);

            // Current value text
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setFakeBoldText(true);
            canvas.drawText(String.format(java.util.Locale.US, "%.2f V", currV), lastX + 6 * density, currY - 4 * density, textPaint);
            textPaint.setFakeBoldText(false);
        } else {
            // "Waiting for data" text
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Waiting for voltage data...", w / 2, h / 2, textPaint);
        }

        // Min/Max/Avg stats (top-left)
        if (samples.size() > 0) {
            float statY = padTop - 10 * density;
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setFakeBoldText(true);
            textPaint.setColor(0xFFEF5350);
            canvas.drawText(String.format(java.util.Locale.US, "Min: %.2fV", getMinV()), padLeft, statY, textPaint);
            textPaint.setColor(0xFF66BB6A);
            canvas.drawText(String.format(java.util.Locale.US, "Max: %.2fV", getMaxV()), padLeft + 100 * density, statY, textPaint);
            textPaint.setColor(0xFF42A5F5);
            canvas.drawText(String.format(java.util.Locale.US, "Avg: %.2fV", getAvgV()), padLeft + 200 * density, statY, textPaint);
            textPaint.setFakeBoldText(false);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = MeasureSpec.getSize(widthMeasureSpec);
        float density = getResources().getDisplayMetrics().density;
        int desiredHeight = (int) (220 * density);
        setMeasuredDimension(desiredWidth, desiredHeight);
    }
}
