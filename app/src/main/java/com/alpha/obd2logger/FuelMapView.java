package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the 2D fuel-trim map grid (RPM × MAP kPa).
 *
 * <p>Map constants and binning logic live in {@link MapBinning} — the single
 * source of truth shared with {@link ApiServer} and {@link LiveMapStore}.
 *
 * <p>Data can be pushed two ways:
 * <ul>
 *   <li>{@link #pushData} — used by {@link MainActivity#updateFuelMap} during
 *       live logging and by {@link ReviewSessionActivity} during log replay.</li>
 *   <li>{@link #syncFromStore} — replaces the entire dataset from a
 *       {@link LiveMapStore.MapSnapshot}, used when the store is the canonical
 *       source (background logging path).</li>
 * </ul>
 */
public class FuelMapView extends View {

    public enum MapMode {
        PETROL, LPG, DEVIATION, CORRECTION
    }

    private Paint gridPaint;
    private Paint textPaint;
    private Paint highlightPaint;
    private Paint badgePaint;
    private final RectF cellRect = new RectF();

    // Grid configuration — delegated to MapBinning (single source of truth).
    private static final int RPM_MIN   = MapBinning.RPM_MIN;
    private static final int RPM_MAX   = MapBinning.RPM_MAX;
    private static final int RPM_STEP  = MapBinning.RPM_STEP;
    private static final float[] MAP_BINS = MapBinning.MAP_BINS;

    // Data store: Map<GridKey, TrimData> — uses LiveMapStore.TrimData
    // so snapshots from the store can be copied directly without conversion.
    private final Map<String, LiveMapStore.TrimData> petrolData = new ConcurrentHashMap<>();
    private final Map<String, LiveMapStore.TrimData> lpgData = new ConcurrentHashMap<>();

    private MapMode currentMode = MapMode.PETROL;
    private boolean comparisonAxisCompatible = true;

    private int currentRpmCell = -1;
    private float currentMapCell = -1f;
    // Latest raw live sample is intentionally separate from learned map data.
    // It gives immediate visual feedback while debounce/safety gates are still
    // waiting, without contaminating correction export or the API map dataset.
    private int liveRpmCell = -1;
    private float liveMapCell = -1f;
    private Double liveTrim = null;
    private FuelMode liveFuelMode = FuelMode.PETROL;
    private boolean liveSampleEligible = false;

    // Debounce tracking: sliding window of last N cell positions.
    // Kept for backward compat with pushData() path (live logging + replay).
    // When using syncFromStore(), the store already debounced.
    private static final int DEBOUNCE_WINDOW = 4;
    private final int[] windowRpm = new int[DEBOUNCE_WINDOW];
    private final float[] windowMap = new float[DEBOUNCE_WINDOW];
    private int windowIdx = 0;
    private int windowFill = 0;

    // Expose MAP bins for API server / CSV export — delegates to MapBinning.
    public static float[] getMapBins() { return MapBinning.MAP_BINS; }
    public static int getRpmMin() { return MapBinning.RPM_MIN; }
    public static int getRpmMax() { return MapBinning.RPM_MAX; }
    public static int getRpmStep() { return MapBinning.RPM_STEP; }

    public Map<String, LiveMapStore.TrimData> getPetrolData() {
        return petrolData;
    }

    public Map<String, LiveMapStore.TrimData> getLpgData() {
        return lpgData;
    }

    public void setPetrolData(Map<String, LiveMapStore.TrimData> data) {
        this.petrolData.clear();
        if (data != null) this.petrolData.putAll(data);
        postInvalidate();
    }

    public void setLpgData(Map<String, LiveMapStore.TrimData> data) {
        this.lpgData.clear();
        if (data != null) this.lpgData.putAll(data);
        postInvalidate();
    }

    /**
         * Replace both petrol and LPG datasets from a {@link LiveMapStore.MapSnapshot}.
         * Preferred path when the store is the canonical source (background logging).
         *
         * <p>Also copies the active (RPM, MAP) cursor so the live highlight tracks
         * the vehicle's exact current bin — even when the latest sample was rejected
         * by debounce / open-loop gates (the store still updates the cursor).
         */
        public void syncFromStore(LiveMapStore.MapSnapshot snapshot) {
            if (snapshot == null) return;
            this.petrolData.clear();
            this.petrolData.putAll(snapshot.getPetrolData());
            this.lpgData.clear();
            this.lpgData.putAll(snapshot.getLpgData());
            this.comparisonAxisCompatible = snapshot.isComparisonAxisCompatible()
                    || snapshot.getPetrolData().isEmpty() || snapshot.getLpgData().isEmpty();
            if (snapshot.getActiveRpmCell() >= 0) {
                this.currentRpmCell = snapshot.getActiveRpmCell();
                this.currentMapCell = snapshot.getActiveMapBin();
            }
            postInvalidate();
        }

        /** Explicitly set the live highlight to a (pre-binned) cell. */
        public void setActiveCell(int rpmCell, float mapBin) {
            this.currentRpmCell = rpmCell;
            this.currentMapCell = mapBin;
            postInvalidate();
        }

        /** Render one non-persistent live sample while the learning gate settles. */
        public void setLiveSample(int rpmCell, float mapBin, Double trim,
                                  FuelMode fuelMode, boolean eligible) {
            if (rpmCell < 0 || mapBin < 0f) {
                clearLiveSample();
                return;
            }
            this.currentRpmCell = rpmCell;
            this.currentMapCell = mapBin;
            this.liveRpmCell = rpmCell;
            this.liveMapCell = mapBin;
            this.liveTrim = trim != null && Double.isFinite(trim) ? trim : null;
            this.liveFuelMode = fuelMode != null ? fuelMode : FuelMode.PETROL;
            this.liveSampleEligible = eligible;
            postInvalidate();
        }

        public void clearLiveSample() {
            this.liveRpmCell = -1;
            this.liveMapCell = -1f;
            this.liveTrim = null;
            this.liveSampleEligible = false;
            postInvalidate();
        }

    public FuelMapView(Context context) {
        super(context);
        init();
    }

    public FuelMapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.border));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.muted));
        textPaint.setTextSize(30f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        highlightPaint.setStyle(Paint.Style.FILL);

        badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgePaint.setColor(0xCCFFFFFF); // slightly transparent white
        badgePaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setMapMode(MapMode mode) {
        this.currentMode = mode;
        invalidate();
    }

    public void pushData(double rpm, double map, double trim, FuelMode fuelMode) {
        pushDataInternal(rpm, map, trim, fuelMode);
        invalidate();
    }

    /**
     * Push data without triggering a redraw. Used by background threads
     * (e.g. ReviewSessionActivity parsing) that batch many pushData calls
     * and trigger a single redraw at the end via {@link #postInvalidate()}
     * or {@link #invalidate()} on the UI thread.
     */
    /**
     * Push data without triggering a redraw, and WITHOUT debounce.
     * Used by ReviewSessionActivity / LogReplayParser when replaying a
     * saved log file. During replay, every record is a real measurement
     * from a different moment in a drive — RPM/MAP change with every
     * record, so the sliding-window debounce (designed for live jitter
     * filtering) would reject 90%+ of the data points.
     */
    public void pushDataNoInvalidate(double rpm, double map, double trim, FuelMode fuelMode) {
        int rpmCell = MapBinning.binRpm(rpm);
        float mapBinValue = MapBinning.binMap(map);

        currentRpmCell = rpmCell;
        currentMapCell = mapBinValue;

        String key = MapBinning.cellKey(rpmCell, mapBinValue);
        Map<String, LiveMapStore.TrimData> targetData = !fuelMode.isGaseous() ? petrolData : lpgData;
        LiveMapStore.TrimData data = targetData.get(key);
        if (data == null) {
            data = new LiveMapStore.TrimData();
        }
        data.addStableValue(trim);
        targetData.put(key, data);
    }

    private void pushDataInternal(double rpm, double map, double trim, FuelMode fuelMode) {
        // Binning via MapBinning (single source of truth — same as ApiServer/LiveMapStore).
        int rpmCell = MapBinning.binRpm(rpm);
        float mapBinValue = MapBinning.binMap(map);

        // Sliding-window debounce
        windowRpm[windowIdx] = rpmCell;
        windowMap[windowIdx] = mapBinValue;
        windowIdx = (windowIdx + 1) % DEBOUNCE_WINDOW;
        if (windowFill < DEBOUNCE_WINDOW) windowFill++;

        boolean seenBefore = false;
        int limit = Math.min(windowFill, DEBOUNCE_WINDOW);
        for (int i = 0; i < limit; i++) {
            int idx = (windowIdx - 1 - i + DEBOUNCE_WINDOW) % DEBOUNCE_WINDOW;
            if (windowRpm[idx] == rpmCell && Math.abs(windowMap[idx] - mapBinValue) < 0.01f) {
                if (i > 0) { seenBefore = true; break; }
            }
        }

        if (windowFill < DEBOUNCE_WINDOW && !seenBefore) {
            currentRpmCell = rpmCell;
            currentMapCell = mapBinValue;
            return;
        }

        if (!seenBefore) {
            currentRpmCell = rpmCell;
            currentMapCell = mapBinValue;
            return;
        }

        currentRpmCell = rpmCell;
        currentMapCell = mapBinValue;

        String key = MapBinning.cellKey(rpmCell, mapBinValue);
        Map<String, LiveMapStore.TrimData> targetData = !fuelMode.isGaseous() ? petrolData : lpgData;
        LiveMapStore.TrimData data = targetData.get(key);
        if (data == null) {
            data = new LiveMapStore.TrimData();
        }
        data.addStableValue(trim);
        targetData.put(key, data);
    }

    public void clearData() {
        petrolData.clear();
        lpgData.clear();
        currentRpmCell = -1;
        currentMapCell = -1f;
        liveRpmCell = -1;
        liveMapCell = -1f;
        liveTrim = null;
        liveSampleEligible = false;
        // Reset sliding-window debounce so the next session starts fresh.
        windowIdx = 0;
        windowFill = 0;
        invalidate();
    }

    /**
     * Clears only ONE fuel's data, preserving the other fuel's map.
     */
    public void clearData(FuelMode fuelMode) {
        if (fuelMode.isGaseous()) {
            lpgData.clear();
        } else {
            petrolData.clear();
        }
        currentRpmCell = -1;
        currentMapCell = -1f;
        liveRpmCell = -1;
        liveMapCell = -1f;
        liveTrim = null;
        liveSampleEligible = false;
        windowIdx = 0;
        windowFill = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int cols = (RPM_MAX - RPM_MIN) / RPM_STEP + 1;
        int rows = MAP_BINS.length;

        float width = getWidth();
        float height = getHeight();

        // Convert dp to px for padding (hardcoded approx 1dp=3px for 400dpi, better to use metrics in real app)
        float density = getResources().getDisplayMetrics().density;
        float paddingLeft = 40f * density;
        float paddingBottom = 20f * density;
        float paddingTop = 10f * density;
        float paddingRight = 10f * density;

        textPaint.setTextSize(10f * density);

        float cellWidth = (width - paddingLeft - paddingRight) / cols;
        float cellHeight = (height - paddingTop - paddingBottom) / rows;

        // Draw grid and cells
        for (int r = 0; r < rows; r++) {
            float mapValue = MAP_BINS[r];
            float yTop = paddingTop + r * cellHeight;
            float yBottom = yTop + cellHeight;
            
            // Draw MAP (kPa) Y-axis labels
            canvas.drawText(String.format(Locale.US, "%.0f", mapValue), paddingLeft / 2, yBottom - cellHeight / 4, textPaint);

            for (int c = 0; c < cols; c++) {
                int rpmValue = RPM_MIN + (c * RPM_STEP);
                float xLeft = paddingLeft + c * cellWidth;
                float xRight = xLeft + cellWidth;

                // Draw RPM X-axis labels (only on the last row)
                if (r == rows - 1) {
                    canvas.drawText(String.valueOf(rpmValue), xLeft + cellWidth / 2, height - (5f * density), textPaint);
                }

                String key = MapBinning.cellKey(rpmValue, mapValue);
                LiveMapStore.TrimData petrol = petrolData.get(key);
                LiveMapStore.TrimData lpg = lpgData.get(key);
                
                LiveMapStore.TrimData activeData = (currentMode == MapMode.PETROL) ? petrol : lpg;
                int hitCount = (activeData != null) ? activeData.getHitCount() : 0;
                boolean isLocked = (activeData != null) ? activeData.isLocked() : false;
                
                Double displayTrim = null;
                boolean showingLivePreview = false;
                if (currentMode == MapMode.PETROL && petrol != null) {
                    displayTrim = petrol.getAverage();
                } else if (currentMode == MapMode.LPG && lpg != null) {
                    displayTrim = lpg.getAverage();
                } else if (currentMode == MapMode.DEVIATION || currentMode == MapMode.CORRECTION) {
                    if (comparisonAxisCompatible && petrol != null && lpg != null) {
                        displayTrim = lpg.getAverage() - petrol.getAverage();
                        hitCount = Math.min(petrol.getHitCount(), lpg.getHitCount());
                        isLocked = petrol.isLocked() && lpg.isLocked();
                    }
                }

                boolean liveModeMatches = (currentMode == MapMode.LPG && liveFuelMode.isGaseous())
                        || (currentMode == MapMode.PETROL && !liveFuelMode.isGaseous());
                if (displayTrim == null && liveModeMatches && liveTrim != null
                        && rpmValue == liveRpmCell
                        && Math.abs(mapValue - liveMapCell) < 0.01f) {
                    displayTrim = liveTrim;
                    showingLivePreview = true;
                    hitCount = 0;
                    isLocked = false;
                }
                
                cellRect.set(xLeft, yTop, xRight, yBottom);

                if (displayTrim != null) {
                    highlightPaint.setColor(getColorForCorrection(displayTrim, hitCount,
                            showingLivePreview));
                    
                    int alpha = Math.min(255, 40 + (int)((hitCount / (float)LiveMapStore.TrimData.MAX_HITS) * 215));
                    if (isLocked) alpha = 255;
                    
                    // Highlight current cell differently
                    if (rpmValue == currentRpmCell && Math.abs(mapValue - currentMapCell) < 0.01f) {
                        highlightPaint.setAlpha(showingLivePreview
                                ? (liveSampleEligible ? 190 : 100) : 255);
                        canvas.drawRect(cellRect, highlightPaint);
                    } else {
                        highlightPaint.setAlpha(alpha);
                        canvas.drawRect(cellRect, highlightPaint);
                    }

                    // Draw the trim value text inside the cell
                    Paint.Align oldAlign = textPaint.getTextAlign();
                    int oldColor = textPaint.getColor();
                    textPaint.setColor(0xFFFFFFFF);
                    String textToDraw;
                    if (currentMode == MapMode.CORRECTION) {
                        textToDraw = (displayTrim > 0 ? "+" : "") + Math.round(displayTrim) + "%";
                    } else {
                        textToDraw = String.format(Locale.US, "%+.1f", displayTrim);
                    }

                    Double cellLambda = activeData != null ? activeData.getAverageLambda() : null;
                    boolean showLambda = (currentMode == MapMode.PETROL || currentMode == MapMode.LPG)
                            && cellLambda != null && activeData.getLambdaCount() >= 3;
                    float centerX = xLeft + cellWidth / 2;
                    if (showLambda) {
                        canvas.drawText(textToDraw, centerX, yTop + cellHeight * 0.48f, textPaint);
                        float oldSize = textPaint.getTextSize();
                        textPaint.setTextSize(7f * density);
                        textPaint.setColor(0xFFE2E8F0);
                        canvas.drawText(String.format(Locale.US, "λ%.3f", cellLambda),
                                centerX, yTop + cellHeight * 0.82f, textPaint);
                        textPaint.setTextSize(oldSize);
                    } else {
                        canvas.drawText(textToDraw, centerX, yBottom - cellHeight / 3, textPaint);
                    }
                    textPaint.setColor(oldColor);
                    textPaint.setTextAlign(oldAlign);
                    
                    // Draw hit count badge in top-right
                    if (showingLivePreview) {
                        badgePaint.setTextSize(7f * density);
                        canvas.drawText("LIVE", xRight - (4f * density),
                                yTop + (12f * density), badgePaint);
                    } else if (currentMode != MapMode.DEVIATION
                            && currentMode != MapMode.CORRECTION && hitCount > 0) {
                        badgePaint.setTextSize(8f * density);
                        canvas.drawText("x" + hitCount, xRight - (4f * density), yTop + (12f * density), badgePaint);
                    }
                }

                boolean isActive = (rpmValue == currentRpmCell && Math.abs(mapValue - currentMapCell) < 0.01f);
                if (displayTrim == null && isActive) {
                    highlightPaint.setColor(0x20FFFFFF); // semi-transparent placeholder background for active cell
                    canvas.drawRect(cellRect, highlightPaint);
                }

                // Draw cell border
                if (isActive) {
                    // Draw base border
                    gridPaint.setColor(0xFF334155);
                    gridPaint.setStrokeWidth(2f);
                    canvas.drawRect(cellRect, gridPaint);

                    // Draw pulsing neon glow border
                    Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    glowPaint.setStyle(Paint.Style.STROKE);
                    glowPaint.setStrokeWidth(3.5f * density);
                    long time = android.os.SystemClock.uptimeMillis();
                    int glowAlpha = 140 + (int)(115 * Math.sin(time * 0.008));
                    int borderCol = (currentMode == MapMode.LPG) ? 0xFFF59E0B : 0xFF38BDF8; // Amber vs Blue
                    glowPaint.setColor(borderCol);
                    glowPaint.setAlpha(glowAlpha);
                    
                    canvas.drawRect(cellRect, glowPaint);
                    
                    // Outer faint glow line
                    glowPaint.setStrokeWidth(1.5f * density);
                    glowPaint.setAlpha(glowAlpha / 2);
                    canvas.drawRect(cellRect.left - 1.5f * density, cellRect.top - 1.5f * density, 
                                   cellRect.right + 1.5f * density, cellRect.bottom + 1.5f * density, glowPaint);
                    
                    postInvalidateDelayed(40); // Request redraw to animate the pulsing glow
                } else if (isLocked) {
                    gridPaint.setColor(0xFFFDE047);
                    gridPaint.setStrokeWidth(5f);
                    canvas.drawRect(cellRect, gridPaint);
                } else {
                    gridPaint.setColor(0xFF334155);
                    gridPaint.setStrokeWidth(2f);
                    canvas.drawRect(cellRect, gridPaint);
                }

                // Reset grid paint
                gridPaint.setColor(0xFF334155);
                gridPaint.setStrokeWidth(2f);
            }
        }
    }

    private int getColorForCorrection(double correction, int hits, boolean livePreview) {
        // One/two-hit cells are provisional. Keep them neutral so a transient
        // cannot visually masquerade as a diagnosed rich/lean condition.
        if (livePreview || hits < 3) return 0xFF64748B;
        if (correction < -10) return 0xFFEF4444; // ECU removing fuel: high
        if (correction < -5) return 0xFFF59E0B;  // ECU removing fuel
        if (correction <= 5) return 0xFF22C55E;  // Low learned correction
        if (correction <= 10) return 0xFF0EA5E9; // ECU adding fuel
        return 0xFF3B82F6;                       // ECU adding fuel: high
    }

    /**
     * Checks if there is any overlapping Petrol+LPG data to produce a correction value.
     */
    public boolean hasAnyCorrection() {
        if (!comparisonAxisCompatible) return false;
        for (String key : petrolData.keySet()) {
            if (lpgData.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    /** Number of populated cells for a given fuel — used to warn before overwriting. */
    public int getCellCount(FuelMode fuelMode) {
        return fuelMode.isGaseous() ? lpgData.size() : petrolData.size();
    }

    public String exportCorrectionMapCsv() {
        StringBuilder sb = new StringBuilder();

        int rpmCount = MapBinning.getRpmCount();
        int mapCount = MAP_BINS.length;

        // Header row: RPM as Columns (Horizontal)
        sb.append("MAP kPa \\ RPM");
        for (int c = 0; c < rpmCount; c++) {
            sb.append(",").append(MapBinning.rpmForColumn(c));
        }
        sb.append("\n");

        // MAP (kPa) as Rows (Vertical)
        for (int r = 0; r < mapCount; r++) {
            float mapValue = MapBinning.mapForRow(r);
            sb.append(String.format(Locale.US, "%.2f", mapValue));

            for (int c = 0; c < rpmCount; c++) {
                int rpmValue = MapBinning.rpmForColumn(c);
                String key = MapBinning.cellKey(rpmValue, mapValue);
                LiveMapStore.TrimData petrol = petrolData.get(key);
                LiveMapStore.TrimData lpg = lpgData.get(key);

                if (petrol != null && lpg != null) {
                    double correction = lpg.getAverage() - petrol.getAverage();
                    sb.append(",").append(Math.round(correction));
                } else {
                    sb.append(",");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
