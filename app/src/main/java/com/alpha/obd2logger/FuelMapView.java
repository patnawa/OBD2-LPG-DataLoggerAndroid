package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.HashMap;
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

    // Debounce tracking
    private int lastRpmCell = -1;
    private int lastMapCell = -1;
    private int consecutiveTicks = 0;
    private static final int DWELL_THRESHOLD = 1; // Was 3 — real driving has rapid RPM/MAP
    // changes so dwell=3 almost never triggers. With dwell=1, the first sample in a
    // cell is stored immediately.

    // Grid configuration
    private static final int RPM_MIN = 500;
    private static final int RPM_MAX = 6500;
    private static final int RPM_STEP = 500;
    
    private static final int MAP_MIN = 20;
    private static final int MAP_MAX = 100;
    private static final int MAP_STEP = 10;

    // Data store: Map<GridKey, TrimData>
    private final Map<String, TrimData> petrolData = new HashMap<>();
    private final Map<String, TrimData> lpgData = new HashMap<>();
    
    private MapMode currentMode = MapMode.PETROL;
    
    private int currentRpmCell = -1;
    private int currentMapCell = -1;

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
        int rCell = (int) (Math.round(rpm / RPM_STEP) * RPM_STEP);
        int mCell = (int) (Math.round(map / MAP_STEP) * MAP_STEP);

        // Clamp
        rCell = Math.max(RPM_MIN, Math.min(RPM_MAX, rCell));
        mCell = Math.max(MAP_MIN, Math.min(MAP_MAX, mCell));

        currentRpmCell = rCell;
        currentMapCell = mCell;

        if (rCell == lastRpmCell && mCell == lastMapCell) {
            consecutiveTicks++;
            if (consecutiveTicks >= DWELL_THRESHOLD) {
                String key = rCell + "_" + mCell;
                Map<String, TrimData> targetData = (fuelMode == FuelMode.PETROL) ? petrolData : lpgData;
                TrimData data = targetData.get(key);
                if (data == null) {
                    data = new TrimData();
                }
                data.addStableValue(trim);
                targetData.put(key, data);
            }
        } else {
            lastRpmCell = rCell;
            lastMapCell = mCell;
            consecutiveTicks = 1;
        }
        
        invalidate();
    }

    public void clearData() {
        petrolData.clear();
        lpgData.clear();
        currentRpmCell = -1;
        currentMapCell = -1;
        // Reset dwell tracking so the next session starts fresh.
        lastRpmCell = -1;
        lastMapCell = -1;
        consecutiveTicks = 0;
        invalidate();
    }

    /**
     * Clears only ONE fuel's data, preserving the other fuel's map.
     *
     * This is essential for the Tune Assist / Deviation workflow: the user logs
     * Petrol first, then switches to LPG and logs again. Starting the LPG session
     * must NOT wipe the previously-collected Petrol map (and vice versa), otherwise
     * the DEVIATION/CORRECTION view can never have both fuels in the same cell and
     * always shows empty. Only the fuel about to be (re)logged is cleared so a
     * re-run of the same fuel starts fresh while the comparison fuel survives.
     */
    public void clearData(FuelMode fuelMode) {
        if (fuelMode == FuelMode.PETROL) {
            petrolData.clear();
        } else {
            lpgData.clear();
        }
        currentRpmCell = -1;
        currentMapCell = -1;
        lastRpmCell = -1;
        lastMapCell = -1;
        consecutiveTicks = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int cols = (MAP_MAX - MAP_MIN) / MAP_STEP + 1;
        int rows = (RPM_MAX - RPM_MIN) / RPM_STEP + 1;

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
            int rpmValue = RPM_MAX - (r * RPM_STEP);
            float yTop = paddingTop + r * cellHeight;
            float yBottom = yTop + cellHeight;
            
            // Draw RPM Y-axis labels
            canvas.drawText(String.valueOf(rpmValue), paddingLeft / 2, yBottom - cellHeight / 4, textPaint);

            for (int c = 0; c < cols; c++) {
                int mapValue = MAP_MIN + (c * MAP_STEP);
                float xLeft = paddingLeft + c * cellWidth;
                float xRight = xLeft + cellWidth;

                // Draw MAP X-axis labels (only on the last row)
                if (r == rows - 1) {
                    canvas.drawText(String.valueOf(mapValue), xLeft + cellWidth / 2, height - (5f * density), textPaint);
                }

                String key = rpmValue + "_" + mapValue;
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
                    if (rpmValue == currentRpmCell && mapValue == currentMapCell) {
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

                // Draw cell border
                if (rpmValue == currentRpmCell && mapValue == currentMapCell) {
                    gridPaint.setColor(0xFFFFFFFF);
                    gridPaint.setStrokeWidth(4f);
                } else if (isLocked) {
                    gridPaint.setColor(0xFFFDE047);
                    gridPaint.setStrokeWidth(5f);
                } else {
                    gridPaint.setColor(0xFF334155);
                    gridPaint.setStrokeWidth(2f);
                }
                
                canvas.drawRect(cellRect, gridPaint);
                
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

    private static class TrimData {
        private double sum = 0;
        private int hitCount = 0;
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
        return (fuelMode == FuelMode.PETROL) ? petrolData.size() : lpgData.size();
    }

    public String exportCorrectionMapCsv() {
        StringBuilder sb = new StringBuilder();
        
        int rpmCount = (RPM_MAX - RPM_MIN) / RPM_STEP + 1;
        int mapCount = (MAP_MAX - MAP_MIN) / MAP_STEP + 1;
        
        // Header row: RPM as Columns (Horizontal)
        sb.append("MAP \\ RPM");
        for (int c = 0; c < rpmCount; c++) {
            int rpmValue = RPM_MIN + (c * RPM_STEP);
            sb.append(",").append(rpmValue);
        }
        sb.append("\n");
        
        // MAP as Rows (Vertical)
        for (int r = 0; r < mapCount; r++) {
            int mapValue = MAP_MIN + (r * MAP_STEP);
            sb.append(mapValue);
            
            for (int c = 0; c < rpmCount; c++) {
                int rpmValue = RPM_MIN + (c * RPM_STEP);
                String key = rpmValue + "_" + mapValue;
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
