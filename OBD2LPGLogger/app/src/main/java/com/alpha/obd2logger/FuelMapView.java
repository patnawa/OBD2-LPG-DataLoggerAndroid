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
        PETROL, LPG, DEVIATION
    }

    private Paint gridPaint;
    private Paint textPaint;
    private Paint highlightPaint;
    private final RectF cellRect = new RectF();

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

        String key = rCell + "_" + mCell;
        Map<String, TrimData> targetData = (fuelMode == FuelMode.PETROL) ? petrolData : lpgData;
        TrimData data = targetData.get(key);
        if (data == null) {
            data = new TrimData();
        }
        data.addValue(trim);
        targetData.put(key, data);
        
        currentRpmCell = rCell;
        currentMapCell = mCell;
        
        invalidate();
    }

    public void clearData() {
        petrolData.clear();
        lpgData.clear();
        currentRpmCell = -1;
        currentMapCell = -1;
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
                
                Double displayTrim = null;
                if (currentMode == MapMode.PETROL && petrol != null) {
                    displayTrim = petrol.getAverage();
                } else if (currentMode == MapMode.LPG && lpg != null) {
                    displayTrim = lpg.getAverage();
                } else if (currentMode == MapMode.DEVIATION && petrol != null && lpg != null) {
                    displayTrim = lpg.getAverage() - petrol.getAverage();
                }
                
                cellRect.set(xLeft, yTop, xRight, yBottom);

                if (displayTrim != null) {
                    highlightPaint.setColor(getColorForTrim(displayTrim));
                    
                    // Highlight current cell differently
                    if (rpmValue == currentRpmCell && mapValue == currentMapCell) {
                        highlightPaint.setAlpha(255);
                        canvas.drawRect(cellRect, highlightPaint);
                        
                        gridPaint.setColor(0xFFFFFFFF);
                        gridPaint.setStrokeWidth(4f);
                        canvas.drawRect(cellRect, gridPaint);
                        gridPaint.setColor(0xFF334155);
                        gridPaint.setStrokeWidth(2f);
                    } else {
                        highlightPaint.setAlpha(180);
                        canvas.drawRect(cellRect, highlightPaint);
                    }

                    // Draw the trim value text inside the cell
                    Paint.Align oldAlign = textPaint.getTextAlign();
                    int oldColor = textPaint.getColor();
                    textPaint.setColor(0xFFFFFFFF);
                    canvas.drawText(String.format(Locale.US, "%.1f", displayTrim), xLeft + cellWidth / 2, yBottom - cellHeight / 3, textPaint);
                    textPaint.setColor(oldColor);
                    textPaint.setTextAlign(oldAlign);
                }

                // Draw cell border
                canvas.drawRect(cellRect, gridPaint);
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
        private int count = 0;

        public void addValue(double val) {
            sum += val;
            count++;
        }

        public double getAverage() {
            return count == 0 ? 0 : sum / count;
        }
    }
}
