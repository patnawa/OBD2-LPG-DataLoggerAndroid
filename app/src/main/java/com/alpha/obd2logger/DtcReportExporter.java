package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates vehicle diagnostic reports in PDF format.
 */
public final class DtcReportExporter {

    private DtcReportExporter() {
    }

    /**
     * Export diagnostic results including DTC lists and freeze frame data to a PDF file.
     * @return the generated File reference, or null on error
     */
    public static File exportReportToPdf(Context context, String vin, List<DtcCode> stored, List<DtcCode> pending, List<DtcCode> permanent, FreezeFrameData freezeFrame) {
        PdfDocument document = new PdfDocument();
        // Standard A4 dimensions at 72 DPI: 595 x 842 points
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();

        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(12);

        // Header Title
        Paint titlePaint = new Paint();
        titlePaint.setColor(Color.DKGRAY);
        titlePaint.setTextSize(20);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText("OBD2 Vehicle Diagnostic Report", 40, 50, titlePaint);

        // Subheader details
        Paint metaPaint = new Paint();
        metaPaint.setColor(Color.GRAY);
        metaPaint.setTextSize(10);
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        canvas.drawText("Date: " + dateStr, 40, 80, metaPaint);
        canvas.drawText("VIN: " + (vin != null && !vin.isEmpty() ? vin : "UNKNOWN"), 40, 95, metaPaint);

        // Horizontal line separator
        Paint linePaint = new Paint();
        linePaint.setColor(Color.LTGRAY);
        linePaint.setStrokeWidth(1);
        canvas.drawLine(40, 110, 555, 110, linePaint);

        int y = 140;

        // Sections
        Paint sectionHeaderPaint = new Paint();
        sectionHeaderPaint.setColor(Color.BLACK);
        sectionHeaderPaint.setTextSize(14);
        sectionHeaderPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        // Stored DTCs
        canvas.drawText("Stored Active Trouble Codes (" + stored.size() + ")", 40, y, sectionHeaderPaint);
        y += 20;
        if (stored.isEmpty()) {
            canvas.drawText("  No active stored trouble codes found.", 40, y, paint);
            y += 20;
        } else {
            for (DtcCode c : stored) {
                canvas.drawText("  • " + c.getCode() + " : " + c.getDescription(), 40, y, paint);
                y += 18;
            }
        }
        y += 10;

        // Pending DTCs
        canvas.drawText("Pending Trouble Codes (" + pending.size() + ")", 40, y, sectionHeaderPaint);
        y += 20;
        if (pending.isEmpty()) {
            canvas.drawText("  No pending trouble codes found.", 40, y, paint);
            y += 20;
        } else {
            for (DtcCode c : pending) {
                canvas.drawText("  • " + c.getCode() + " : " + c.getDescription(), 40, y, paint);
                y += 18;
            }
        }
        y += 10;

        // Permanent DTCs
        canvas.drawText("Permanent Trouble Codes (" + permanent.size() + ")", 40, y, sectionHeaderPaint);
        y += 20;
        if (permanent.isEmpty()) {
            canvas.drawText("  No permanent trouble codes found.", 40, y, paint);
            y += 20;
        } else {
            for (DtcCode c : permanent) {
                canvas.drawText("  • " + c.getCode() + " : " + c.getDescription(), 40, y, paint);
                y += 18;
            }
        }
        y += 15;

        // Freeze Frame Snapshot
        if (freezeFrame != null && !freezeFrame.getValues().isEmpty()) {
            canvas.drawText("Engine Snapshot at Time of Fault (Freeze Frame)", 40, y, sectionHeaderPaint);
            y += 20;
            
            Map<String, Double> ffVals = freezeFrame.getValues();
            String[] displayPids = {"0C", "0D", "05", "04", "06", "07", "0B", "0F"};
            String[] displayNames = {"Engine RPM", "Vehicle Speed", "Coolant Temp", "Calculated Load", "STFT Bank 1", "LTFT Bank 1", "Intake MAP", "Intake Air Temp"};
            
            for (int i = 0; i < displayPids.length; i++) {
                String pidHex = displayPids[i];
                if (ffVals.containsKey(pidHex)) {
                    canvas.drawText("  • " + displayNames[i] + ": " + freezeFrame.getFormattedValue(pidHex), 40, y, paint);
                    y += 18;
                }
            }
        }

        // Footer note
        Paint footerPaint = new Paint();
        footerPaint.setColor(Color.GRAY);
        footerPaint.setTextSize(9);
        canvas.drawText("Report generated by OBD2-LPG Data Logger Android App.", 40, 800, footerPaint);

        document.finishPage(page);

        File dir = context.getExternalFilesDir(null);
        File pdfFile = new File(dir, "OBD2_Diagnostic_Report_" + System.currentTimeMillis() + ".pdf");
        
        try {
            FileOutputStream fos = new FileOutputStream(pdfFile);
            document.writeTo(fos);
            fos.close();
            Log.d("DtcReportExporter", "Successfully exported report to PDF: " + pdfFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("DtcReportExporter", "Failed to write PDF file", e);
            pdfFile = null;
        } finally {
            document.close();
        }

        return pdfFile;
    }
}
