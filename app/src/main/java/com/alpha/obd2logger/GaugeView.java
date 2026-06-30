package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * A custom analog gauge view drawn on Canvas. Renders a 270° semicircle arc
 * with a needle, tick marks, and a digital readout in the centre.
 *
 * Usage: set min/max/value/unit/label, then call setValue() on the UI thread.
 */
public final class GaugeView extends View {

    private float min = 0f;
    private float max = 8000f;
    private float value = 0f;
    private String label = "RPM";
    private String unit = "rpm";
    private int arcColor;
    private int needleColor;
    private int warningColor;
    private float warningStart = 0.8f; // fraction of max where warning zone begins

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warningArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    public GaugeView(Context context) {
        super(context);
        init();
    }

    public GaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GaugeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        arcColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.primary);
        needleColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.accent);
        warningColor = androidx.core.content.ContextCompat.getColor(getContext(), R.color.danger);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(16f);
        arcPaint.setColor(arcColor);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        // Glow effect for neon style
        arcPaint.setShadowLayer(8f, 0, 0, arcColor);

        bgArcPaint.setStyle(Paint.Style.STROKE);
        bgArcPaint.setStrokeWidth(16f);
        bgArcPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.surface2));
        bgArcPaint.setStrokeCap(Paint.Cap.ROUND);

        warningArcPaint.setStyle(Paint.Style.STROKE);
        warningArcPaint.setStrokeWidth(16f);
        warningArcPaint.setColor(warningColor);
        warningArcPaint.setStrokeCap(Paint.Cap.ROUND);
        warningArcPaint.setShadowLayer(8f, 0, 0, warningColor);

        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(8f);
        needlePaint.setColor(needleColor);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        needlePaint.setShadowLayer(6f, 0, 0, needleColor);

        textPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.text));
        textPaint.setTextAlign(Paint.Align.CENTER);

        labelPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.muted));
        labelPaint.setTextAlign(Paint.Align.CENTER);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(2f);
        tickPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.border));
    }

    public void setRange(float min, float max) {
        this.min = min;
        this.max = max;
        invalidate();
    }

    public void setLabel(String label) {
        this.label = label;
        invalidate();
    }

    public void setUnit(String unit) {
        this.unit = unit;
        invalidate();
    }

    public void setColors(int arc, int needle) {
        this.arcColor = arc;
        this.needleColor = needle;
        arcPaint.setColor(arc);
        needlePaint.setColor(needle);
        invalidate();
    }

    public void setWarningStart(float fraction) {
        this.warningStart = fraction;
        invalidate();
    }

    public void setValue(float value) {
        this.value = Math.max(min, Math.min(max, value));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        
        float density = getResources().getDisplayMetrics().density;
        int padding = (int)(16 * density);
        
        int cx = w / 2;
        int cy = (int)(h * 0.6); // Center is a bit below the middle
        
        int maxRadiusX = (w / 2) - padding;
        int maxRadiusY = cy - padding; // Prevent clipping at the top
        int maxRadiusBottom = (h - cy) + padding; // The arc only goes down 45 degrees below center (135 to 405). The lowest point is at angle 90 (bottom), but the arc starts at 135 (bottom left). Actually, it goes from 135 (-45) up to 270 (top), to 360 (right), to 405 (bottom right). The lowest Y is at the start/end points, which is cy + radius * sin(45) = cy + radius * 0.707. So we need cy + radius * 0.707 <= h - padding.
        // radius <= (h - padding - cy) / 0.707
        int maxRadiusFromBottom = (int)((h - padding - cy) / 0.707);

        int radius = Math.min(Math.min(maxRadiusX, maxRadiusY), maxRadiusFromBottom);
        if (radius < 50) radius = 50;

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 270° arc: from 135° to 405° (i.e. bottom-left to bottom-right)
        float startAngle = 135f;
        float sweepAngle = 270f;

        // Background arc
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, bgArcPaint);

        // Value arc
        float fraction = (value - min) / (max - min);
        if (fraction < 0) fraction = 0;
        if (fraction > 1) fraction = 1;

        // Warning zone
        float warnFraction = warningStart;
        float warnStartAngle = startAngle + (float)(sweepAngle * warnFraction);
        float warnSweep = (float)(sweepAngle * (1.0 - warnFraction));

        // Draw warning zone BACKGROUND first (dim, full sweep). This must be
        // painted BEFORE the value arc so that when the current value enters the
        // warning zone (fraction > warnFraction) the bright warning portion of
        // the value arc is drawn ON TOP of the dim background. The old code drew
        // the background after the value arc, which overwrote the value's warning
        // segment with the 60-alpha paint and made the gauge look like it stopped
        // at the warning threshold.
        if (warnSweep > 0) {
            warningArcPaint.setAlpha(60);
            canvas.drawArc(arcRect, warnStartAngle, warnSweep, false, warningArcPaint);
            warningArcPaint.setAlpha(255);
        }

        if (fraction > warnFraction) {
            // Normal portion
            canvas.drawArc(arcRect, startAngle, (float)(sweepAngle * warnFraction), false, arcPaint);
            // Warning portion up to current value
            canvas.drawArc(arcRect, warnStartAngle,
                    (float)(sweepAngle * (fraction - warnFraction)), false, warningArcPaint);
        } else {
            canvas.drawArc(arcRect, startAngle, (float)(sweepAngle * fraction), false, arcPaint);
        }

        // Tick marks
        int tickCount = 10;
        for (int i = 0; i <= tickCount; i++) {
            float tickFraction = (float) i / tickCount;
            float angle = (float) Math.toRadians(startAngle + sweepAngle * tickFraction);
            float innerR = radius - 12;
            float outerR = radius + 2;
            float x1 = cx + (float) Math.cos(angle) * innerR;
            float y1 = cy + (float) Math.sin(angle) * innerR;
            float x2 = cx + (float) Math.cos(angle) * outerR;
            float y2 = cy + (float) Math.sin(angle) * outerR;
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }

        // Needle
        float needleAngle = (float) Math.toRadians(startAngle + sweepAngle * fraction);
        float needleLen = radius - 20;
        canvas.drawLine(cx, cy,
                cx + (float) Math.cos(needleAngle) * needleLen,
                cy + (float) Math.sin(needleAngle) * needleLen,
                needlePaint);

        // Centre hub
        hubPaint.setColor(needleColor);
        canvas.drawCircle(cx, cy, 8f, hubPaint);

        // Digital readout
        textPaint.setTextSize(36f * density);
        String valueStr;
        if (value == (int) value) {
            valueStr = String.valueOf((int) value);
        } else {
            valueStr = String.format(java.util.Locale.US, "%.1f", value);
        }
        canvas.drawText(valueStr, cx, cy + (20 * density), textPaint);

        // Label
        labelPaint.setTextSize(14f * density);
        canvas.drawText(label, cx, cy - (10 * density), labelPaint);

        // Unit
        labelPaint.setTextSize(12f * density);
        canvas.drawText(unit, cx, cy + (40 * density), labelPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = 300;
        int desiredHeight = 280;
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
