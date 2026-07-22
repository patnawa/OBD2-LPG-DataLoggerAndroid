package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heatmap of the learned Volumetric Efficiency surface — the on-screen face
 * of {@link VeMapStore}.
 *
 * <p>Deliberately leaner than {@link FuelMapView}: this view is a pure
 * <em>reader</em>. All learning, gating and debounce live in the store; the
 * view only renders {@link VeMapStore.VeSnapshot} copies (the single-writer
 * rule — UI never pushes). Tapping the view cycles the mode:
 *
 * <ul>
 *   <li><b>PETROL VE / LPG VE</b> — sequential scale, blue (low) → green
 *       (healthy NA band) → red (high/boost); immature cells fade by
 *       confidence.</li>
 *   <li><b>ΔVE</b> — petrol − LPG per cell, diverging scale centered on the
 *       expected small positive gap: green ≈ normal breathing loss, amber →
 *       red = the gap widening (gaseous-side degradation), blue = inverted
 *       gap (investigate sensors/petrol side). Only cells mature on BOTH
 *       fuels are shown — a half-measured Δ is noise, not a diagnostic.</li>
 * </ul>
 */
public class VeMapView extends View {

    public enum Mode {
        VE_PETROL, VE_LPG, VE_DELTA
    }

    private static final float[] MAP_BINS = MapBinning.MAP_BINS;
    private static final int RPM_MIN = MapBinning.RPM_MIN;
    private static final int RPM_MAX = MapBinning.RPM_MAX;
    private static final int RPM_STEP = MapBinning.RPM_STEP;

    private final Map<String, VeMapStore.VeCell> petrolData = new ConcurrentHashMap<>();
    private final Map<String, VeMapStore.VeCell> lpgData = new ConcurrentHashMap<>();
    private volatile boolean axisCompatible = true;

    private Mode mode = Mode.VE_DELTA;
    private Paint gridPaint;
    private Paint cellPaint;
    private Paint textPaint;
    private Paint headerPaint;
    private final RectF cellRect = new RectF();

    /** Optional listener so the host can mirror the mode in a card title. */
    public interface ModeChangeListener {
        void onModeChanged(Mode newMode);
    }

    @Nullable
    private ModeChangeListener modeChangeListener;

    public VeMapView(Context context) {
        super(context);
        init();
    }

    public VeMapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(androidx.core.content.ContextCompat.getColor(
                getContext(), R.color.border));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.5f);

        cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cellPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(androidx.core.content.ContextCompat.getColor(
                getContext(), R.color.muted));
        textPaint.setTextAlign(Paint.Align.CENTER);

        headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headerPaint.setColor(androidx.core.content.ContextCompat.getColor(
                getContext(), R.color.muted));
        headerPaint.setTextAlign(Paint.Align.LEFT);

        setOnClickListener(v -> cycleMode());
        setContentDescription("VE map. Tap to switch mode.");
    }

    public void setModeChangeListener(@Nullable ModeChangeListener listener) {
        this.modeChangeListener = listener;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode newMode) {
        if (newMode == null || newMode == mode) return;
        mode = newMode;
        ModeChangeListener l = modeChangeListener;
        if (l != null) l.onModeChanged(mode);
        invalidate();
    }

    private void cycleMode() {
        switch (mode) {
            case VE_PETROL:
                setMode(Mode.VE_LPG);
                break;
            case VE_LPG:
                setMode(Mode.VE_DELTA);
                break;
            default:
                setMode(Mode.VE_PETROL);
                break;
        }
    }

    /** Replace the rendered dataset from a store snapshot (the only data path). */
    public void syncFromStore(VeMapStore.VeSnapshot snapshot) {
        if (snapshot == null) return;
        petrolData.clear();
        petrolData.putAll(snapshot.getPetrolData());
        lpgData.clear();
        lpgData.putAll(snapshot.getLpgData());
        axisCompatible = snapshot.isComparisonAxisCompatible()
                || snapshot.getPetrolData().isEmpty() || snapshot.getLpgData().isEmpty();
        postInvalidate();
    }

    public void clearData() {
        petrolData.clear();
        lpgData.clear();
        postInvalidate();
    }

    public boolean hasAnyData() {
        return !petrolData.isEmpty() || !lpgData.isEmpty();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int cols = (RPM_MAX - RPM_MIN) / RPM_STEP + 1;
        int rows = MAP_BINS.length;
        float density = getResources().getDisplayMetrics().density;
        float paddingLeft = 40f * density;
        float paddingBottom = 20f * density;
        float paddingTop = 18f * density;
        float paddingRight = 10f * density;

        textPaint.setTextSize(10f * density);
        headerPaint.setTextSize(10f * density);
        canvas.drawText(headerLabel(), paddingLeft, 12f * density, headerPaint);

        float cellWidth = (getWidth() - paddingLeft - paddingRight) / cols;
        float cellHeight = (getHeight() - paddingTop - paddingBottom) / rows;

        for (int r = 0; r < rows; r++) {
            // High load on top, vacuum at the bottom — same convention as
            // FuelMapView (see its inverted-axis comment).
            float mapValue = MAP_BINS[rows - 1 - r];
            float yTop = paddingTop + r * cellHeight;
            float yBottom = yTop + cellHeight;
            canvas.drawText(String.format(Locale.US, "%.0f", mapValue),
                    paddingLeft / 2, yBottom - cellHeight / 4, textPaint);

            for (int c = 0; c < cols; c++) {
                int rpmValue = RPM_MIN + (c * RPM_STEP);
                float xLeft = paddingLeft + c * cellWidth;
                cellRect.set(xLeft, yTop, xLeft + cellWidth, yBottom);

                if (r == rows - 1) {
                    canvas.drawText(String.valueOf(rpmValue),
                            xLeft + cellWidth / 2, getHeight() - (5f * density), textPaint);
                }

                String key = MapBinning.cellKey(rpmValue, mapValue);
                drawCell(canvas, key, density, cellWidth, cellHeight, yTop, yBottom, xLeft);
                canvas.drawRect(cellRect, gridPaint);
            }
        }
    }

    private void drawCell(Canvas canvas, String key, float density,
                          float cellWidth, float cellHeight,
                          float yTop, float yBottom, float xLeft) {
        VeMapStore.VeCell petrol = petrolData.get(key);
        VeMapStore.VeCell lpg = lpgData.get(key);

        Double display = null;
        double confidence = 0.0;
        if (mode == Mode.VE_PETROL && petrol != null) {
            display = petrol.getVe();
            confidence = petrol.getConfidence();
        } else if (mode == Mode.VE_LPG && lpg != null) {
            display = lpg.getVe();
            confidence = lpg.getConfidence();
        } else if (mode == Mode.VE_DELTA && axisCompatible
                && petrol != null && lpg != null
                && petrol.getCount() >= VeMapStore.MIN_COMPARE_HITS
                && lpg.getCount() >= VeMapStore.MIN_COMPARE_HITS) {
            display = petrol.getVe() - lpg.getVe();
            confidence = Math.min(petrol.getConfidence(), lpg.getConfidence());
        }
        if (display == null) return;

        cellPaint.setColor(mode == Mode.VE_DELTA
                ? deltaColor(display) : veColor(display));
        // Confidence drives opacity: immature cells read as tentative, never
        // as authoritative as a mature measurement.
        cellPaint.setAlpha(60 + (int) (confidence * 195));
        canvas.drawRect(cellRect, cellPaint);

        int oldColor = textPaint.getColor();
        textPaint.setColor(0xFFFFFFFF);
        String label = mode == Mode.VE_DELTA
                ? String.format(Locale.US, "%+.1f", display)
                : String.format(Locale.US, "%.0f", display);
        canvas.drawText(label, xLeft + cellWidth / 2,
                yBottom - cellHeight / 3, textPaint);
        textPaint.setColor(oldColor);
    }

    private String headerLabel() {
        switch (mode) {
            case VE_PETROL:
                return "VE % — PETROL (tap to switch)";
            case VE_LPG:
                return "VE % — LPG (tap to switch)";
            default:
                return "ΔVE — petrol − LPG (tap to switch)";
        }
    }

    /** Sequential scale for absolute VE: blue (low) → green (~85–95) → red (high). */
    private static int veColor(double ve) {
        float clamped = (float) Math.max(40.0, Math.min(130.0, ve));
        // 40 → hue 220 (blue), 90 → hue 130 (green), 130 → hue 0 (red).
        float hue;
        if (clamped <= 90f) {
            hue = 220f - (clamped - 40f) / 50f * 90f;
        } else {
            hue = 130f - (clamped - 90f) / 40f * 130f;
        }
        return Color.HSVToColor(new float[]{hue, 0.75f, 0.85f});
    }

    /**
     * Diverging scale for ΔVE (petrol − LPG). The expected gap on a healthy
     * LPG conversion is a few points positive: green. As the gap widens
     * toward degradation the hue moves amber → red; a negative (inverted)
     * gap renders blue as "unexpected — investigate", not as healthy.
     */
    private static int deltaColor(double delta) {
        if (delta < 0) {
            float t = (float) Math.min(1.0, -delta / 6.0);
            return Color.HSVToColor(new float[]{210f, 0.4f + 0.4f * t, 0.85f});
        }
        // 0..4 points: green zone. 4..12: green → amber → red.
        float t = (float) Math.min(1.0, Math.max(0.0, (delta - 4.0) / 8.0));
        float hue = 130f - t * 130f;
        return Color.HSVToColor(new float[]{hue, 0.75f, 0.85f});
    }
}
