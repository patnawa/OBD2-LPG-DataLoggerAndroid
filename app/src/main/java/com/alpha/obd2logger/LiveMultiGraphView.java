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
        add(rpm, rpmValue);
        add(speed, speedValue);
        add(boost, boostValue);
        invalidate();
    }

    private static void add(Deque<Float> values, Float value) {
        values.addLast(value == null ? Float.NaN : value);
        while (values.size() > MAX_POINTS) values.removeFirst();
    }

    public synchronized void clear() {
        rpm.clear(); speed.clear(); boost.clear(); invalidate();
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
        canvas.drawText("now", left + w, getHeight() - 4f * d, labels);

        drawSeries(canvas, rpm, rpmPaint, 8000f, left, top, w, h);
        drawSeries(canvas, speed, speedPaint, 240f, left, top, w, h);
        drawSeries(canvas, boost, boostPaint, 30f, left, top, w, h);
    }

    private void drawSeries(Canvas canvas, Deque<Float> values, Paint paint, float max,
                            float left, float top, float width, float height) {
        if (values.isEmpty()) return;
        path.reset();
        int index = 0;
        boolean started = false;
        int count = values.size();
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

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int desired = Math.round(132f * getResources().getDisplayMetrics().density);
        int mode = MeasureSpec.getMode(heightSpec);
        int height = mode == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec)
                : mode == MeasureSpec.AT_MOST ? Math.min(desired, MeasureSpec.getSize(heightSpec)) : desired;
        setMeasuredDimension(width, height);
    }
}
