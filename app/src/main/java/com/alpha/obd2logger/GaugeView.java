package com.alpha.obd2logger;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Professional-grade analog gauge view.
 *
 * Features:
 *   - Tapered needle (wide at hub, sharp at tip)
 *   - Number labels around the arc
 *   - Smooth needle animation with easing
 *   - Peak value indicator (small triangle)
 *   - Per-gauge color themes
 *   - 3D hub cap
 *   - Outer bezel ring
 *   - Minor + major tick marks
 *   - Gradient arc with warning zone
 *   - Clean text layout (label top, value center, unit bottom)
 */
public final class GaugeView extends View {

    // --- Configurable properties ---
    private float min = 0f;
    private float max = 8000f;
    private float value = 0f;
    private float animatedValue = 0f;
    private float peakValue = Float.MIN_VALUE;
    private String label = "RPM";
    private String unit = "rpm";
    private int majorTicks = 8;        // number of major divisions (labels)
    private int minorTicksPerMajor = 4; // minor ticks between each major

    // --- Color theme ---
    private int arcColor;          // main arc color
    private int arcColorDim;       // dim arc (background track)
    private int needleBaseColor;   // needle base
    private int needleTipColor;    // needle tip highlight
    private int warningColor;      // warning zone color
    private int peakColor;         // peak indicator color
    private float warningStart = 0.8f; // fraction where warning zone begins

    // --- Paints ---
    private final Paint bezelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bezelInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warningArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needleShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path needlePath = new Path();
    private final Path peakPath = new Path();
    private final RectF arcRect = new RectF();

    // --- Animation ---
    private ValueAnimator needleAnimator;

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
        // Default gauge accents are supplied by the active Light/Dark resource
        // set. Per-gauge arc/needle colors are applied later by setFullColors.
        arcColor = 0xFF00E5FF;       // cyan
        arcColorDim = getResources().getColor(R.color.gauge_bg);
        needleBaseColor = 0xFFFF2A55; // red
        needleTipColor = getResources().getColor(R.color.gauge_needle_tip);
        warningColor = 0xFFFF2A55;    // red
        peakColor = 0xFFFFD600;       // yellow

        // Bezel (outer ring)
        bezelPaint.setStyle(Paint.Style.STROKE);
        bezelPaint.setStrokeWidth(4f);
        bezelPaint.setColor(getResources().getColor(R.color.gauge_bezel));
        bezelInnerPaint.setStyle(Paint.Style.STROKE);
        bezelInnerPaint.setStrokeWidth(2f);
        bezelInnerPaint.setColor(getResources().getColor(R.color.gauge_bezel_inner));

        // Arc background track
        bgArcPaint.setStyle(Paint.Style.STROKE);
        bgArcPaint.setStrokeCap(Paint.Cap.ROUND);
        bgArcPaint.setColor(arcColorDim);

        // Main arc
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        // Warning arc
        warningArcPaint.setStyle(Paint.Style.STROKE);
        warningArcPaint.setStrokeCap(Paint.Cap.ROUND);

        // Ticks
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setColor(getResources().getColor(R.color.gauge_tick));
        minorTickPaint.setStyle(Paint.Style.STROKE);
        minorTickPaint.setColor(getResources().getColor(R.color.gauge_tick_minor));

        // Tick labels
        tickLabelPaint.setTextAlign(Paint.Align.CENTER);
        tickLabelPaint.setColor(getResources().getColor(R.color.gauge_tick));

        // Needle
        needlePaint.setStyle(Paint.Style.FILL);
        needleShadowPaint.setStyle(Paint.Style.FILL);
        needleShadowPaint.setColor(getResources().getColor(R.color.gauge_shadow));

        // Hub
        hubPaint.setStyle(Paint.Style.FILL);
        hubHighlightPaint.setStyle(Paint.Style.FILL);

        // Value text
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setColor(getResources().getColor(R.color.gauge_text));

        // Unit text
        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setColor(getResources().getColor(R.color.gauge_unit));

        // Peak indicator
        peakPaint.setStyle(Paint.Style.FILL);
        peakPaint.setColor(peakColor);
    }

    // ===== Public API =====

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

    /**
     * Set gauge color theme.
     * @param arc      Main arc color
     * @param needle   Needle/hub color
     */
    public void setColors(int arc, int needle) {
        this.arcColor = arc;
        this.needleBaseColor = needle;
        this.warningColor = adjustBrightness(needle, 1.2f);
        this.peakColor = this.warningColor;
        peakPaint.setColor(this.peakColor);
        invalidate();
    }

    /**
     * Set full color theme with all accents.
     */
    public void setFullColors(int arc, int needle, int warning) {
        this.arcColor = arc;
        this.needleBaseColor = needle;
        this.warningColor = warning;
        this.peakColor = warning;
        peakPaint.setColor(this.peakColor);
        invalidate();
    }

    public void setWarningStart(float fraction) {
        this.warningStart = fraction;
        invalidate();
    }

    public void setValue(float newValue) {
        newValue = Math.max(min, Math.min(max, newValue));

        // Track peak
        if (newValue > peakValue) {
            peakValue = newValue;
        }

        float oldAnimated = animatedValue;
        if (needleAnimator != null && needleAnimator.isRunning()) {
            needleAnimator.cancel();
        }

        needleAnimator = ValueAnimator.ofFloat(oldAnimated, newValue);
        needleAnimator.setDuration(300);
        needleAnimator.setInterpolator(new DecelerateInterpolator(2f));
        needleAnimator.addUpdateListener(a -> {
            animatedValue = (float) a.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        needleAnimator.start();

        this.value = newValue;
    }

    /**
     * Reset peak indicator.
     */
    public void resetPeak() {
        peakValue = Float.MIN_VALUE;
        invalidate();
    }

    // ===== Drawing =====

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float density = getResources().getDisplayMetrics().density;
        int w = getWidth();
        int h = getHeight();

        float padding = 8 * density;
        float cx = w / 2f;
        // Center the arc in the upper 62% of the view, leaving room for value/unit below
        float cy = h * 0.48f;

        float maxRadiusX = (w / 2f) - padding;
        float maxRadiusY = cy - padding;
        float maxRadiusFromBottom = (h * 0.68f - padding - cy) / 0.707f;
        float radius = Math.min(Math.min(maxRadiusX, maxRadiusY), maxRadiusFromBottom);
        if (radius < 40 * density) radius = 40 * density;

        float startAngle = 135f;
        float sweepAngle = 270f;

        // Keep the track color synchronized with the active resource theme.
        // This was previously left at Paint's default black.
        bgArcPaint.setColor(arcColorDim);

        // Arc thickness scales with size
        float arcStroke = Math.max(6 * density, radius * 0.06f);
        float bezelStroke = Math.max(2 * density, radius * 0.02f);

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // --- 1. Outer bezel ring ---
        bezelPaint.setStrokeWidth(bezelStroke * 2);
        canvas.drawCircle(cx, cy, radius + arcStroke + bezelStroke, bezelPaint);
        bezelInnerPaint.setStrokeWidth(bezelStroke);
        canvas.drawCircle(cx, cy, radius + arcStroke * 0.5f, bezelInnerPaint);

        // --- 2. Background arc track ---
        bgArcPaint.setStrokeWidth(arcStroke);
        canvas.drawArc(arcRect, startAngle, sweepAngle, false, bgArcPaint);

        // --- 3. Warning zone (dim background) ---
        float warnFraction = warningStart;
        float warnStartAngle = startAngle + sweepAngle * warnFraction;
        float warnSweep = sweepAngle * (1f - warnFraction);
        if (warnSweep > 0) {
            warningArcPaint.setStrokeWidth(arcStroke);
            warningArcPaint.setColor(warningColor);
            warningArcPaint.setAlpha(40);
            canvas.drawArc(arcRect, warnStartAngle, warnSweep, false, warningArcPaint);
            warningArcPaint.setAlpha(255);
        }

        // --- 4. Value arc (colored progress) ---
        float fraction = clampFraction(animatedValue);
        arcPaint.setStrokeWidth(arcStroke);

        if (fraction > warnFraction) {
            // Normal portion
            arcPaint.setColor(arcColor);
            canvas.drawArc(arcRect, startAngle, sweepAngle * warnFraction, false, arcPaint);
            // Warning portion
            warningArcPaint.setColor(warningColor);
            warningArcPaint.setAlpha(200);
            canvas.drawArc(arcRect, warnStartAngle,
                    sweepAngle * (fraction - warnFraction), false, warningArcPaint);
            warningArcPaint.setAlpha(255);
        } else {
            arcPaint.setColor(arcColor);
            canvas.drawArc(arcRect, startAngle, sweepAngle * fraction, false, arcPaint);
        }

        // --- 5. Tick marks & labels ---
        float labelRadius = radius - arcStroke * 2.2f;
        float majorTickOuter = radius - arcStroke * 0.5f;
        float majorTickInner = radius - arcStroke * 1.8f;
        float minorTickOuter = radius - arcStroke * 0.5f;
        float minorTickInner = radius - arcStroke * 1.2f;

        tickPaint.setStrokeWidth(Math.max(1.5f * density, arcStroke * 0.15f));
        minorTickPaint.setStrokeWidth(Math.max(1f * density, arcStroke * 0.08f));
        tickLabelPaint.setTextSize(Math.max(8 * density, radius * 0.1f));

        int totalMinorTicks = majorTicks * minorTicksPerMajor;

        for (int i = 0; i <= totalMinorTicks; i++) {
            float tickFraction = (float) i / totalMinorTicks;
            float angle = (float) Math.toRadians(startAngle + sweepAngle * tickFraction);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            if (i % minorTicksPerMajor == 0) {
                // Major tick
                canvas.drawLine(
                        cx + cos * majorTickInner, cy + sin * majorTickInner,
                        cx + cos * majorTickOuter, cy + sin * majorTickOuter,
                        tickPaint);

                // Number label
                float labelValue = min + (max - min) * tickFraction;
                String labelText;
                if (labelValue == (int) labelValue) {
                    labelText = String.valueOf((int) labelValue);
                } else {
                    labelText = String.format(java.util.Locale.US, "%.0f", labelValue);
                }
                canvas.drawText(labelText,
                        cx + cos * labelRadius,
                        cy + sin * labelRadius + tickLabelPaint.getTextSize() * 0.35f,
                        tickLabelPaint);
            } else {
                // Minor tick
                canvas.drawLine(
                        cx + cos * minorTickInner, cy + sin * minorTickInner,
                        cx + cos * minorTickOuter, cy + sin * minorTickOuter,
                        minorTickPaint);
            }
        }

        // --- 6. Peak value indicator ---
        if (peakValue > min && peakValue != Float.MIN_VALUE) {
            float peakFrac = clampFraction(peakValue);
            float peakAngleDeg = startAngle + sweepAngle * peakFrac;
            float peakAngleRad = (float) Math.toRadians(peakAngleDeg);
            float peakTipR = radius + arcStroke + bezelStroke * 2.5f;
            float peakBaseR = radius + arcStroke + bezelStroke * 0.5f;
            float peakCos = (float) Math.cos(peakAngleRad);
            float peakSin = (float) Math.sin(peakAngleRad);

            // Perpendicular for triangle base width
            float perpCos = (float) Math.cos(peakAngleRad + Math.PI / 2);
            float perpSin = (float) Math.sin(peakAngleRad + Math.PI / 2);
            float halfBase = 3 * density;

            peakPath.reset();
            peakPath.moveTo(cx + peakCos * peakTipR, cy + peakSin * peakTipR);
            peakPath.lineTo(cx + peakCos * peakBaseR + perpCos * halfBase,
                            cy + peakSin * peakBaseR + perpSin * halfBase);
            peakPath.lineTo(cx + peakCos * peakBaseR - perpCos * halfBase,
                            cy + peakSin * peakBaseR - perpSin * halfBase);
            peakPath.close();
            canvas.drawPath(peakPath, peakPaint);
        }

        // --- 7. Tapered needle ---
        float needleAngleDeg = startAngle + sweepAngle * clampFraction(animatedValue);
        float needleAngleRad = (float) Math.toRadians(needleAngleDeg);
        float needleLen = radius - arcStroke * 3f;
        float needleBaseWidth = radius * 0.045f;
        float needleTipWidth = radius * 0.01f;

        float cos = (float) Math.cos(needleAngleRad);
        float sin = (float) Math.sin(needleAngleRad);
        float perpCos = (float) Math.cos(needleAngleRad + Math.PI / 2);
        float perpSin = (float) Math.sin(needleAngleRad + Math.PI / 2);

        // Tip (narrow end)
        float tipX = cx + cos * needleLen;
        float tipY = cy + sin * needleLen;
        // Base (wide end, slightly behind center)
        float baseX = cx - cos * needleLen * 0.15f;
        float baseY = cy - sin * needleLen * 0.15f;

        needlePath.reset();
        needlePath.moveTo(tipX + perpCos * needleTipWidth,
                          tipY + perpSin * needleTipWidth);
        needlePath.lineTo(baseX + perpCos * needleBaseWidth,
                          baseY + perpSin * needleBaseWidth);
        needlePath.lineTo(baseX - perpCos * needleBaseWidth,
                          baseY - perpSin * needleBaseWidth);
        needlePath.lineTo(tipX - perpCos * needleTipWidth,
                          tipY - perpSin * needleTipWidth);
        needlePath.close();

        // Needle shadow
        canvas.save();
        canvas.translate(1.5f * density, 1.5f * density);
        needleShadowPaint.setAlpha(60);
        canvas.drawPath(needlePath, needleShadowPaint);
        canvas.restore();

        // Needle body
        needlePaint.setColor(needleBaseColor);
        canvas.drawPath(needlePath, needlePaint);

        // White tip dot
        hubHighlightPaint.setColor(needleTipColor);
        hubHighlightPaint.setAlpha(180);
        canvas.drawCircle(tipX, tipY, needleTipWidth * 1.5f, hubHighlightPaint);

        // --- 8. Hub (center cap) ---
        float hubRadius = radius * 0.08f;
        // Outer ring
        hubPaint.setColor(darken(needleBaseColor, 0.6f));
        canvas.drawCircle(cx, cy, hubRadius, hubPaint);
        // Inner highlight
        hubHighlightPaint.setColor(needleBaseColor);
        hubHighlightPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, hubRadius * 0.7f, hubHighlightPaint);
        // Specular highlight (small white dot offset up-left)
        hubHighlightPaint.setColor(0x80FFFFFF);
        canvas.drawCircle(cx - hubRadius * 0.2f, cy - hubRadius * 0.2f,
                hubRadius * 0.3f, hubHighlightPaint);

        // --- 9. Digital readout ---
        // Label (above value)
        unitPaint.setTextSize(Math.max(10 * density, radius * 0.12f));
        canvas.drawText(label, cx, cy + radius * 0.35f, unitPaint);

        // Large value
        valuePaint.setTextSize(Math.max(20 * density, radius * 0.28f));
        String valueStr;
        if (animatedValue == (int) animatedValue) {
            valueStr = String.valueOf((int) animatedValue);
        } else {
            valueStr = String.format(java.util.Locale.US, "%.1f", animatedValue);
        }
        canvas.drawText(valueStr, cx, cy + radius * 0.62f, valuePaint);

        // Unit (below value)
        unitPaint.setTextSize(Math.max(9 * density, radius * 0.1f));
        canvas.drawText(unit, cx, cy + radius * 0.80f, unitPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = 320;
        int desiredHeight = 300;
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int width = widthMode == MeasureSpec.EXACTLY ? widthSize :
                (widthMode == MeasureSpec.AT_MOST ? Math.min(desiredWidth, widthSize) : desiredWidth);
        int height;
        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else {
            // The dial reserves space below the arc for the value and unit.
            // Derive height from measured width so two-column cards scale on
            // small phones, foldables, and tablets without a fixed 340dp box.
            int responsiveHeight = Math.round(width * 1.8f);
            int minHeight = Math.round(240 * getResources().getDisplayMetrics().density);
            int maxHeight = Math.round(360 * getResources().getDisplayMetrics().density);
            responsiveHeight = Math.max(minHeight, Math.min(maxHeight, responsiveHeight));
            height = heightMode == MeasureSpec.AT_MOST
                    ? Math.min(responsiveHeight, heightSize)
                    : responsiveHeight;
        }

        setMeasuredDimension(width, height);
    }

    // ===== Helpers =====

    private float clampFraction(float val) {
        float f = (val - min) / (max - min);
        return Math.max(0f, Math.min(1f, f));
    }

    private static int adjustBrightness(int color, float factor) {
        int r = Math.min(255, (int) (Color.red(color) * factor));
        int g = Math.min(255, (int) (Color.green(color) * factor));
        int b = Math.min(255, (int) (Color.blue(color) * factor));
        return Color.rgb(r, g, b);
    }

    private static int darken(int color, float factor) {
        int r = (int) (Color.red(color) * factor);
        int g = (int) (Color.green(color) * factor);
        int b = (int) (Color.blue(color) * factor);
        return Color.rgb(r, g, b);
    }
}
