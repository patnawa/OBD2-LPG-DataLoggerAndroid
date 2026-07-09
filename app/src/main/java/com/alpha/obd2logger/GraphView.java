package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A custom real-time line graph view. Maintains a rolling window of data
 * points and draws them as a line chart with grid lines and labels.
 *
 * Usage: call pushValue() from the UI thread as new data arrives.
 */
public final class GraphView extends View {

    private static final int MAX_POINTS = 120;

    private final Deque<Float> dataPoints = new ArrayDeque<>(MAX_POINTS + 1);
    private float min = 0f;
    private float max = 100f;
    private String label = "Value";
    private String unit = "";
    private int lineColor;
    private int fillColor;
    private boolean autoScale = true;
    
    // Will be initialized in init()
    private int COLOR_GRID_TEXT;
    private int COLOR_VAL_TEXT;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public GraphView(Context context) {
        super(context);
        init();
    }

    public GraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        lineColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.accent);
        fillColor = androidx.core.graphics.ColorUtils.setAlphaComponent(lineColor, 38); // 15% alpha
        COLOR_GRID_TEXT = androidx.core.content.ContextCompat.getColor(getContext(), R.color.muted);
        COLOR_VAL_TEXT = androidx.core.content.ContextCompat.getColor(getContext(), R.color.text);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        linePaint.setColor(lineColor);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setShadowLayer(8f, 0, 0, lineColor);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(fillColor);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.border));

        textPaint.setTextSize(11f * getResources().getDisplayMetrics().density);
        textPaint.setColor(COLOR_GRID_TEXT);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.gauge_bg));
    }

    public void setRange(float min, float max) {
        this.min = min;
        this.max = max;
        this.autoScale = false;
        invalidate();
    }

    public void setAutoScale(boolean auto) {
        this.autoScale = auto;
        invalidate();
    }

    public void setLabel(String label, String unit) {
        this.label = label;
        this.unit = unit;
        invalidate();
    }

    public void setLineColor(int color) {
        this.lineColor = color;
        linePaint.setColor(color);
        fillPaint.setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)));
        invalidate();
    }

    /**
     * Add a new data point. Old points beyond MAX_POINTS are dropped.
     */
    public synchronized void pushValue(float value) {
         dataPoints.addLast(value);
         while (dataPoints.size() > MAX_POINTS) {
             dataPoints.removeFirst();
         }

         if (autoScale && !dataPoints.isEmpty()) {
             float dataMin = Float.MAX_VALUE;
             float dataMax = -Float.MAX_VALUE;
             for (float v : dataPoints) {
                 if (v < dataMin) dataMin = v;
                 if (v > dataMax) dataMax = v;
             }
             float padding = (dataMax - dataMin) * 0.15f;
             if (padding < 1) padding = 1;
             this.min = dataMin - padding;
             this.max = dataMax + padding;
         }

         invalidate();
     }

     public synchronized void clear() {
         dataPoints.clear();
         invalidate();
     }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float density = getResources().getDisplayMetrics().density;
        int padLeft = (int)(40 * density);
        int padRight = (int)(16 * density);
        int padTop = (int)(16 * density);
        int padBottom = (int)(24 * density);
        int graphW = w - padLeft - padRight;
        int graphH = h - padTop - padBottom;

        if (graphW <= 0 || graphH <= 0) return;

        // Background
        canvas.drawRect(padLeft, padTop, w - padRight, h - padBottom, bgPaint);

        // Grid lines
        int hLines = 5;
        for (int i = 0; i <= hLines; i++) {
            float y = padTop + (graphH * i / hLines);
            canvas.drawLine(padLeft, y, w - padRight, y, gridPaint);

            float val = max - (max - min) * i / hLines;
            String labelStr;
            if (Math.abs(val) >= 100) {
                labelStr = String.format(java.util.Locale.US, "%.0f", val);
            } else {
                labelStr = String.format(java.util.Locale.US, "%.1f", val);
            }
            canvas.drawText(labelStr, 2, y + 4, textPaint);
        }

        int vLines = 6;
        for (int i = 0; i <= vLines; i++) {
            float x = padLeft + (graphW * i / vLines);
            canvas.drawLine(x, padTop, x, h - padBottom, gridPaint);
        }

        // Label
        textPaint.setColor(COLOR_GRID_TEXT);
        canvas.drawText(label + (unit.isEmpty() ? "" : " (" + unit + ")"),
                padLeft, h - 8, textPaint);

        if (dataPoints.isEmpty()) return;

        // Draw data line
        int count = dataPoints.size();
        float range = max - min;
        if (range <= 0) range = 1;

        linePath.reset();
        fillPath.reset();

        int i = 0;
        float lastVal = 0f;
        for (Float p : dataPoints) {
            float val = p != null ? p : 0f;
            lastVal = val;
            float x = padLeft + (graphW * i / Math.max(MAX_POINTS - 1, 1));
            float normalizedY = (val - min) / range;
            if (normalizedY < 0) normalizedY = 0;
            if (normalizedY > 1) normalizedY = 1;
            float y = padTop + graphH * (1 - normalizedY);

            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, h - padBottom);
                fillPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                fillPath.lineTo(x, y);
            }

            if (i == count - 1) {
                fillPath.lineTo(x, h - padBottom);
                fillPath.close();
            }
            i++;
        }

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        // Current value indicator (last point)
        if (count > 0) {
            float lastX = padLeft + (graphW * (count - 1) / Math.max(MAX_POINTS - 1, 1));
            float lastNormY = (lastVal - min) / range;
            if (lastNormY < 0) lastNormY = 0;
            if (lastNormY > 1) lastNormY = 1;
            float lastY = padTop + graphH * (1 - lastNormY);

            dotPaint.setColor(lineColor);
            canvas.drawCircle(lastX, lastY, 6f, dotPaint);

            // Current value text
            textPaint.setColor(COLOR_VAL_TEXT);
            textPaint.setTextSize(14f * getResources().getDisplayMetrics().density);
            String valStr = String.format(java.util.Locale.US, "%.1f %s", lastVal, unit);
            canvas.drawText(valStr, w - padRight - 80, padTop + 14, textPaint);
            textPaint.setColor(COLOR_GRID_TEXT);
            textPaint.setTextSize(11f * getResources().getDisplayMetrics().density);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = 320;
        int desiredHeight = 200;
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int width = widthMode == MeasureSpec.EXACTLY ? widthSize :
                (widthMode == MeasureSpec.AT_MOST ? Math.min(desiredWidth, widthSize) : desiredWidth);
        int height = heightMode == MeasureSpec.EXACTLY ? heightSize :
                (heightMode == MeasureSpec.AT_MOST ? Math.min(desiredHeight, heightSize) : desiredHeight);

        setMeasuredDimension(width, height);
    }
}
