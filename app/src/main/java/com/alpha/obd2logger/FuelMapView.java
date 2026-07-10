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

public class FuelMapView extends View {

    public enum MapMode {
        PETROL, LPG, DEVIATION, CORRECTION
    }

    private Paint gridPaint;
    private Paint textPaint;
    private Paint highlightPaint;
    private Paint badgePaint;
    private final RectF cellRect = new RectF();

    // Grid configuration
    private static final int RPM_MIN = 500;
    private static final int RPM_MAX = 6500;
    private static final int RPM_STEP = 500;
    
    // Y-axis: MAP (kPa) bins — directly matches what the ECU reports.
    // Range covers vacuum (idle ~30 kPa) to forced induction boost (250 kPa).
    private static final float[] MAP_BINS = {
        10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f, 120f, 150f, 200f, 250f
    };

    // Data store: Map<GridKey, TrimData>
    private final Map<String, TrimData> petrolData = new ConcurrentHashMap<>();
    private final Map<String, TrimData> lpgData = new ConcurrentHashMap<>();
    
    private MapMode currentMode = MapMode.PETROL;
    
    private int currentRpmCell = -1;
    private float currentMapCell = -1f;

    // Debounce tracking: sliding window of last N cell positions.
    // Uses a ring buffer of the last 4 (rpmCell, mapBinValue) pairs.
    // A sample is accepted if the current cell was seen at least once
    // in the window — this tolerates brief RPM jitter across cell
    // boundaries (e.g. idle at 800 RPM in cell 500, blip to 1050 in
    // cell 1000, then back to 500) without resetting, while still
    // filtering truly transient one-off pass-through cells.
    private static final int DEBOUNCE_WINDOW = 4;
    private final int[] windowRpm = new int[DEBOUNCE_WINDOW];
    private final float[] windowMap = new float[DEBOUNCE_WINDOW];
    private int windowIdx = 0;    // next write position
    private int windowFill = 0;   // how many slots filled so far

    private static int findClosestBinIndex(double value, float[] bins) {
        int bestIdx = 0;
        double minDiff = Double.MAX_VALUE;
        for (int i = 0; i < bins.length; i++) {
            double diff = Math.abs(bins[i] - value);
            if (diff < minDiff) {
                minDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    // Expose MAP bins for API server / CSV export
    public static float[] getMapBins() { return MAP_BINS; }
    public static int getRpmMin() { return RPM_MIN; }
    public static int getRpmMax() { return RPM_MAX; }
    public static int getRpmStep() { return RPM_STEP; }

    public Map<String, TrimData> getPetrolData() {
        return petrolData;
    }

    public Map<String, TrimData> getLpgData() {
        return lpgData;
    }

    public void setPetrolData(Map<String, TrimData> data) {
        this.petrolData.clear();
        if (data != null) this.petrolData.putAll(data);
        postInvalidate();
    }

    public void setLpgData(Map<String, TrimData> data) {
        this.lpgData.clear();
        if (data != null) this.lpgData.putAll(data);
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
    public void pushDataNoInvalidate(double rpm, double map, double trim, FuelMode fuelMode) {
        pushDataInternal(rpm, map, trim, fuelMode);
    }

    private void pushDataInternal(double rpm, double map, double trim, FuelMode fuelMode) {
        // Floor-based binning: RPM 750→500, 1499→1000, 1500→1500.
        int rpmCell = (int)(rpm / RPM_STEP) * RPM_STEP;
        rpmCell = Math.max(RPM_MIN, Math.min(RPM_MAX, rpmCell));

        // MAP (kPa) closest-bin: use the actual sensor value directly.
        // No conversion — MAP kPa is what the tuner sees on a gauge.
        int mapIdx = findClosestBinIndex(map, MAP_BINS);
        float mapBinValue = MAP_BINS[mapIdx];

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

        String key = rpmCell + "_" + String.format(Locale.US, "%.2f", mapBinValue);
        Map<String, TrimData> targetData = !fuelMode.isGaseous() ? petrolData : lpgData;
        TrimData data = targetData.get(key);
        if (data == null) {
            data = new TrimData();
        }
        data.addStableValue(trim);
        targetData.put(key, data);
    }

    public void clearData() {
        petrolData.clear();
        lpgData.clear();
        currentRpmCell = -1;
        currentMapCell = -1f;
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

                String key = rpmValue + "_" + String.format(Locale.US, "%.2f", mapValue);
                TrimData petrol = petrolData.get(key);
                TrimData lpg = lpgData.get(key);
                
                TrimData activeData = (currentMode == MapMode.PETROL) ? petrol : lpg;
                int hitCount = (activeData != null) ? activeData.getHitCount() : 0;
                boolean isLocked = (activeData != null) ? activeData.isLocked() : false;
                
                Double displayTrim = null;
                if (currentMode == MapMode.PETROL && petrol != null) {
                    displayTrim = petrol.getAverage();
                } else if (currentMode == MapMode.LPG && lpg != null) {
                    displayTrim = lpg.getAverage();
                } else if (currentMode == MapMode.DEVIATION || currentMode == MapMode.CORRECTION) {
                    if (petrol != null && lpg != null) {
                        displayTrim = lpg.getAverage() - petrol.getAverage();
                        hitCount = Math.min(petrol.getHitCount(), lpg.getHitCount());
                        isLocked = petrol.isLocked() && lpg.isLocked();
                    }
                }
                
                cellRect.set(xLeft, yTop, xRight, yBottom);

                if (displayTrim != null) {
                    highlightPaint.setColor(getColorForTrim(displayTrim));
                    
                    int alpha = Math.min(255, 40 + (int)((hitCount / (float)TrimData.MAX_HITS) * 215));
                    if (isLocked) alpha = 255;
                    
                    // Highlight current cell differently
                    if (rpmValue == currentRpmCell && Math.abs(mapValue - currentMapCell) < 0.01f) {
                        highlightPaint.setAlpha(255);
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
                        textToDraw = String.format(Locale.US, "%.1f", displayTrim);
                    }
                    canvas.drawText(textToDraw, xLeft + cellWidth / 2, yBottom - cellHeight / 3, textPaint);
                    textPaint.setColor(oldColor);
                    textPaint.setTextAlign(oldAlign);
                    
                    // Draw hit count badge in top-right
                    if (currentMode != MapMode.DEVIATION && currentMode != MapMode.CORRECTION && hitCount > 0) {
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

    private int getColorForTrim(double trim) {
        if (trim < -10) return 0xFFEF4444; // Red (Very Rich)
        if (trim < -5) return 0xFFF59E0B;  // Orange (Rich)
        if (trim <= 5) return 0xFF22C55E;  // Green (Perfect)
        if (trim <= 10) return 0xFF0EA5E9; // Light Blue (Lean)
        return 0xFF3B82F6;                 // Blue (Very Lean)
    }

    public static class TrimData {
        public double sum = 0;
        public int hitCount = 0;
        public static final int MAX_HITS = 20;

        public void addStableValue(double val) {
            sum += val;
            hitCount++;
        }

        public double getAverage() {
            return hitCount == 0 ? 0 : sum / hitCount;
        }
        
        public int getHitCount() {
            return hitCount;
        }
        
        public boolean isLocked() {
            return hitCount >= MAX_HITS;
        }
    }
    
    /**
     * Checks if there is any overlapping Petrol+LPG data to produce a correction value.
     */
    public boolean hasAnyCorrection() {
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
        
        int rpmCount = (RPM_MAX - RPM_MIN) / RPM_STEP + 1;
        int mapCount = MAP_BINS.length;
        
        // Header row: RPM as Columns (Horizontal)
        sb.append("MAP (kPa) \\ RPM");
        for (int c = 0; c < rpmCount; c++) {
            int rpmValue = RPM_MIN + (c * RPM_STEP);
            sb.append(",").append(rpmValue);
        }
        sb.append("\n");
        
        // MAP (kPa) as Rows (Vertical)
        for (int r = 0; r < mapCount; r++) {
            float mapValue = MAP_BINS[r];
            sb.append(String.format(Locale.US, "%.2f", mapValue));
            
            for (int c = 0; c < rpmCount; c++) {
                int rpmValue = RPM_MIN + (c * RPM_STEP);
                String key = rpmValue + "_" + String.format(Locale.US, "%.2f", mapValue);
                TrimData petrol = petrolData.get(key);
                TrimData lpg = lpgData.get(key);
                
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
