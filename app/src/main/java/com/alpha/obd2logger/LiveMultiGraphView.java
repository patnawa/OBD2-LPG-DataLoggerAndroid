package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;

/** Compact normalized multi-series chart for the Home scanner cockpit. */
public final class LiveMultiGraphView extends View {
    private static final int MAX_POINTS = 90;
    private final Deque<Float> rpm = new ArrayDeque<>(MAX_POINTS + 1);
    private final Deque<Float> speed = new ArrayDeque<>(MAX_POINTS + 1);
    private final Deque<Float> boost = new ArrayDeque<>(MAX_POINTS + 1);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labels = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rpmPaint = makePaint(0xFF29A8FF);
    private final Paint speedPaint = makePaint(0xFF39D98A);
    private final Paint boostPaint = makePaint(0xFFA855F7);
    private final Path path = new Path();
    private boolean paused;
    private boolean showRpm = true, showSpeed = true, showBoost = true;
    private int inspectIndex = -1;

    public LiveMultiGraphView(Context context) { super(context); init(); }
    public LiveMultiGraphView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public LiveMultiGraphView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(1f, d));
        grid.setColor(Color.argb(70, 120, 150, 185));
        labels.setTextSize(9f * d);
        labels.setColor(Color.argb(180, 180, 198, 220));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    private static Paint makePaint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.4f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        p.setShadowLayer(5f, 0f, 0f, color);
        return p;
    }

    public synchronized void pushValues(Float rpmValue, Float speedValue, Float boostValue) {
        if (paused) return;
        add(rpm, rpmValue); add(speed, speedValue); add(boost, boostValue);
        invalidate();
    }

    private static void add(Deque<Float> values, Float value) {
        values.addLast(value == null ? Float.NaN : value);
        while (values.size() > MAX_POINTS) values.removeFirst();
    }

    public synchronized void clear() {
        rpm.clear(); speed.clear(); boost.clear(); inspectIndex = -1; invalidate();
    }

    public void setPaused(boolean value) { paused = value; if (!value) inspectIndex = -1; invalidate(); }
    public boolean isPaused() { return paused; }

    public void toggleSeries(int series) {
        if (series == 0) showRpm = !showRpm;
        else if (series == 1) showSpeed = !showSpeed;
        else if (series == 2) showBoost = !showBoost;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float left = 30f * d, right = 10f * d, top = 8f * d, bottom = 20f * d;
        float w = getWidth() - left - right, h = getHeight() - top - bottom;
        if (w <= 0 || h <= 0) return;

        for (int i = 0; i <= 4; i++) {
            float y = top + h * i / 4f;
            canvas.drawLine(left, y, left + w, y, grid);
            labels.setTextAlign(Paint.Align.RIGHT);
            labels.setColor(Color.argb(170, 180, 198, 220));
            canvas.drawText(String.valueOf(4 - i), left - 5f * d, y + 3f * d, labels);
        }
        for (int i = 0; i <= 6; i++) {
            float x = left + w * i / 6f;
            canvas.drawLine(x, top, x, top + h, grid);
        }
        labels.setTextAlign(Paint.Align.LEFT);
        labels.setColor(Color.argb(160, 180, 198, 220));
        canvas.drawText("-60s", left, getHeight() - 4f * d, labels);
        labels.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(paused ? "paused" : "now", left + w, getHeight() - 4f * d, labels);

        if (showRpm) drawSeries(canvas, rpm, rpmPaint, 8000f, left, top, w, h);
        if (showSpeed) drawSeries(canvas, speed, speedPaint, 240f, left, top, w, h);
        if (showBoost) drawSeries(canvas, boost, boostPaint, 30f, left, top, w, h);

        if (inspectIndex >= 0 && !rpm.isEmpty()) {
            int index = Math.min(inspectIndex, rpm.size() - 1);
            float x = left + w * index / Math.max(MAX_POINTS - 1, 1);
            Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
            cursor.setColor(Color.argb(190, 235, 245, 255));
            cursor.setStrokeWidth(Math.max(1f, d));
            canvas.drawLine(x, top, x, top + h, cursor);

            float boxX = Math.min(x + 6f * d, getWidth() - 112f * d);
            float boxY = top + 2f * d;
            float boxW = 100f * d;
            float boxH = 46f * d;
            
            Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bubblePaint.setColor(Color.argb(215, 15, 23, 42)); // Slate 900, 85% opacity
            bubblePaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(boxX - 6f * d, boxY, boxX + boxW, boxY + boxH, 8f * d, 8f * d, bubblePaint);

            Paint bubbleBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
            bubbleBorder.setColor(Color.argb(40, 255, 255, 255)); // Subtle white border
            bubbleBorder.setStyle(Paint.Style.STROKE);
            bubbleBorder.setStrokeWidth(1f * d);
            canvas.drawRoundRect(boxX - 6f * d, boxY, boxX + boxW, boxY + boxH, 8f * d, 8f * d, bubbleBorder);

            labels.setTextAlign(Paint.Align.LEFT);
            labels.setTextSize(10f * d);
            labels.setColor(Color.WHITE);
            canvas.drawText("RPM " + format(valueAt(rpm, index), 0), boxX, top + 14f * d, labels);
            canvas.drawText("SPD " + format(valueAt(speed, index), 0), boxX, top + 27f * d, labels);
            canvas.drawText("BST " + format(valueAt(boost, index), 1), boxX, top + 40f * d, labels);
        }
    }

    private void drawSeries(Canvas canvas, Deque<Float> values, Paint paint, float max,
                            float left, float top, float width, float height) {
        if (values.isEmpty()) return;
        path.reset();
        int index = 0;
        boolean started = false;
        for (Float raw : values) {
            if (raw == null || raw.isNaN()) { index++; continue; }
            float normalized = Math.max(0f, Math.min(1f, raw / max));
            float x = left + width * index / Math.max(MAX_POINTS - 1, 1);
            float y = top + height * (1f - normalized);
            if (!started) { path.moveTo(x, y); started = true; }
            else path.lineTo(x, y);
            index++;
        }
        if (started) canvas.drawPath(path, paint);
    }

    private static Float valueAt(Deque<Float> values, int index) {
        int i = 0;
        for (Float value : values) { if (i++ == index) return value; }
        return Float.NaN;
    }

    private static String format(Float value, int decimals) {
        if (value == null || value.isNaN()) return "--";
        return String.format(java.util.Locale.US, decimals == 0 ? "%.0f" : "%.1f", value);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            float d = getResources().getDisplayMetrics().density;
            float left = 30f * d, right = 10f * d, width = getWidth() - left - right;
            if (width > 0 && !rpm.isEmpty()) {
                inspectIndex = Math.max(0, Math.min(rpm.size() - 1,
                        Math.round((event.getX() - left) / width * (MAX_POINTS - 1))));
                invalidate();
            }
            return true;
        }
        return true;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int desired = Math.round(132f * getResources().getDisplayMetrics().density);
        int mode = MeasureSpec.getMode(heightSpec);
        int height = mode == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec)
                : mode == MeasureSpec.AT_MOST ? Math.min(desired, MeasureSpec.getSize(heightSpec)) : desired;
        setMeasuredDimension(width, height);
    }
}
