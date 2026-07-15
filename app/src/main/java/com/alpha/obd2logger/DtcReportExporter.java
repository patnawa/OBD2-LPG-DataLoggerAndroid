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

    // A4 at 72 DPI. Content past BOTTOM_LIMIT starts a new page — a scan
    // with many DTCs plus Mode 06 results easily overflows a single page.
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int BOTTOM_LIMIT = 790;
    private static final int LEFT_MARGIN = 40;

    /** Tracks the current page/canvas and starts new pages on overflow. */
    private static final class Pager {
        final PdfDocument doc;
        PdfDocument.Page page;
        Canvas canvas;
        int y;
        int pageNum = 0;

        Pager(PdfDocument doc, PdfDocument.Page firstPage, int startY) {
            this.doc = doc;
            this.page = firstPage;
            this.canvas = firstPage.getCanvas();
            this.pageNum = 1;
            this.y = startY;
        }

        void line(String text, Paint paint, int advance) {
            if (y + advance > BOTTOM_LIMIT) {
                doc.finishPage(page);
                pageNum++;
                page = doc.startPage(
                    new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create());
                canvas = page.getCanvas();
                y = 50;
            }
            canvas.drawText(text, LEFT_MARGIN, y, paint);
            y += advance;
        }

        void space(int pts) {
            y += pts;
        }
    }

    private DtcReportExporter() {
    }

    /**
     * Export diagnostic results including DTC lists and freeze frame data to a PDF file.
     * @return the generated File reference, or null on error
     */
    public static File exportReportToPdf(Context context, String vin, List<DtcCode> stored, List<DtcCode> pending, List<DtcCode> permanent, FreezeFrameData freezeFrame) {
        return exportReportToPdf(context, vin, stored, pending, permanent, freezeFrame,
            null, null, null, null, null);
    }

    /**
     * Full diagnostic report with all available data sections.
     *
     * @param readiness       Readiness monitor status (Mode 01 PID 01)
     * @param mode06Results   Mode 06 test results
     * @param modules         ECU module list from scan
     * @param protocolStatuses Protocol bus scan results
     * @param driveCycleGuide Drive cycle guidance text (for incomplete monitors)
     */
    public static File exportReportToPdf(Context context, String vin,
            List<DtcCode> stored, List<DtcCode> pending, List<DtcCode> permanent,
            FreezeFrameData freezeFrame,
            ReadinessMonitor readiness,
            List<Mode06Result> mode06Results,
            List<DtcReader.ModuleInfo> modules,
            List<DtcReader.ProtocolScanStatus> protocolStatuses,
            String driveCycleGuide) {
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create();
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

        Pager pager = new Pager(document, page, 140);

        // Sections
        Paint sectionHeaderPaint = new Paint();
        sectionHeaderPaint.setColor(Color.BLACK);
        sectionHeaderPaint.setTextSize(14);
        sectionHeaderPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        // Stored DTCs
        pager.line("Stored Active Trouble Codes (" + stored.size() + ")", sectionHeaderPaint, 20);
        if (stored.isEmpty()) {
            pager.line("  No active stored trouble codes found.", paint, 20);
        } else {
            for (DtcCode c : stored) {
                pager.line("  • " + c.getCode() + " : " + c.getDescription(), paint, 18);
            }
        }
        pager.space(10);

        // Pending DTCs
        pager.line("Pending Trouble Codes (" + pending.size() + ")", sectionHeaderPaint, 20);
        if (pending.isEmpty()) {
            pager.line("  No pending trouble codes found.", paint, 20);
        } else {
            for (DtcCode c : pending) {
                pager.line("  • " + c.getCode() + " : " + c.getDescription(), paint, 18);
            }
        }
        pager.space(10);

        // Permanent DTCs
        pager.line("Permanent Trouble Codes (" + permanent.size() + ")", sectionHeaderPaint, 20);
        if (permanent.isEmpty()) {
            pager.line("  No permanent trouble codes found.", paint, 20);
        } else {
            for (DtcCode c : permanent) {
                pager.line("  • " + c.getCode() + " : " + c.getDescription(), paint, 18);
            }
        }
        pager.space(15);

        // Freeze Frame Snapshot
        if (freezeFrame != null && !freezeFrame.getValues().isEmpty()) {
            pager.line("Engine Snapshot at Time of Fault (Freeze Frame)", sectionHeaderPaint, 20);

            Map<String, Double> ffVals = freezeFrame.getValues();
            String[] displayPids = {"0C", "0D", "05", "04", "06", "07", "0B", "0F"};
            String[] displayNames = {"Engine RPM", "Vehicle Speed", "Coolant Temp", "Calculated Load", "STFT Bank 1", "LTFT Bank 1", "Intake MAP", "Intake Air Temp"};

            for (int i = 0; i < displayPids.length; i++) {
                String pidHex = displayPids[i];
                if (ffVals.containsKey(pidHex)) {
                    pager.line("  • " + displayNames[i] + ": " + freezeFrame.getFormattedValue(pidHex), paint, 18);
                }
            }
        }

        // ── Readiness Monitor Status ──
        if (readiness != null) {
            pager.line("Emission Readiness Monitor Status", sectionHeaderPaint, 20);
            String milStr = readiness.isMilOn() ? "ON" : "OFF";
            pager.line("  MIL Status: " + milStr + "  |  DTC Count: " + readiness.getDtcCount()
                + "  |  Fuel: " + (readiness.isDiesel() ? "Diesel" : "Gasoline"), paint, 18);
            for (ReadinessMonitor.MonitorStatus m : readiness.getMonitors()) {
                String status = m.available ? (m.complete ? "Complete" : "INCOMPLETE") : "N/A";
                pager.line("  " + m.name + ": " + status, paint, 14);
            }
            pager.space(10);
        }

        // ── Mode 06 Test Results ──
        if (mode06Results != null && !mode06Results.isEmpty()) {
            pager.line("Mode 06 On-Board Monitor Test Results (" + mode06Results.size() + ")", sectionHeaderPaint, 20);
            for (Mode06Result r : mode06Results) {
                String passFail = r.isPassed() ? "PASS" : "FAIL";
                pager.line("  MID " + String.format("%02X", r.getObdMid())
                    + " TID " + String.format("%02X", r.getTid())
                    + " UASID " + String.format("%02X", r.getUasId())
                    + ": " + r.getFormattedValue() + " " + r.getUnit()
                    + " (min:" + r.getFormattedMin() + " max:" + r.getFormattedMax() + ") "
                    + passFail, paint, 14);
            }
            pager.space(10);
        }

        // ── ECU Module List ──
        if (modules != null && !modules.isEmpty()) {
            pager.line("Detected ECU Modules (" + modules.size() + ")", sectionHeaderPaint, 20);
            for (DtcReader.ModuleInfo mod : modules) {
                pager.line("  " + mod.canId + " " + mod.moduleName
                    + " [" + mod.protocolLabel + "]"
                    + " Stored:" + mod.storedDtcCount
                    + " Pending:" + mod.pendingDtcCount
                    + " Permanent:" + mod.permanentDtcCount, paint, 14);
            }
            pager.space(10);
        }

        // ── Protocol Bus Scan Results ──
        if (protocolStatuses != null && !protocolStatuses.isEmpty()) {
            pager.line("Protocol Bus Scan Results (" + protocolStatuses.size() + ")", sectionHeaderPaint, 20);
            for (DtcReader.ProtocolScanStatus ps : protocolStatuses) {
                String status = ps.responded ? "RESPONDED" : "No response";
                pager.line("  " + ps.bus.label + ": " + status
                    + "  Modules:" + ps.modulesFound
                    + "  DTCs:" + ps.totalDtcCount, paint, 14);
            }
            pager.space(10);
        }

        // ── Drive Cycle Guidance ──
        if (driveCycleGuide != null && !driveCycleGuide.isEmpty()) {
            pager.line("Drive Cycle Guidance", sectionHeaderPaint, 20);
            for (String line : driveCycleGuide.split("\n")) {
                pager.line("  " + line, paint, 12);
            }
            pager.space(10);
        }

        // Footer note on the last page
        Paint footerPaint = new Paint();
        footerPaint.setColor(Color.GRAY);
        footerPaint.setTextSize(9);
        pager.canvas.drawText("Report generated by OBD2-LPG Data Logger Android App.", LEFT_MARGIN, 810, footerPaint);

        document.finishPage(pager.page);

        File dir = context.getExternalFilesDir(null);
        File pdfFile = new File(dir, "OBD2_Diagnostic_Report_" + System.currentTimeMillis() + ".pdf");

        try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
            document.writeTo(fos);
            Log.d("DtcReportExporter", "Successfully exported report to PDF: " + pdfFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("DtcReportExporter", "Failed to write PDF file", e);
            pdfFile.delete();
            pdfFile = null;
        } finally {
            document.close();
        }

        return pdfFile;
    }
}
