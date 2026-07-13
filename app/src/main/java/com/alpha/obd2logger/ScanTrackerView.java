package com.alpha.obd2logger;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-time visual scan tracker panel for DTC scanning.
 *
 * Shows a live checklist of protocol buses and ECU modules as they are
 * probed during a DTC scan. Each row transitions through states:
 *   PENDING (grey) → SCANNING (amber pulse) → RESPONDED (green) / NO RESPONSE (grey) / HAS_DTC (red)
 *
 * Thread-safe: all UI mutations are marshalled to the main thread via Handler.
 * Implements DtcScanProgressListener directly so it can be passed to DtcReader.
 */
public class ScanTrackerView extends LinearLayout implements DtcReader.DtcScanProgressListener {

    /** Visual state for a scan row. */
    private enum RowState {
        PENDING,    // not yet probed
        SCANNING,   // currently being probed
        RESPONDED,  // probed and responded, no DTCs
        HAS_DTC,    // probed and has DTCs
        NO_RESPONSE,// probed, no ECU responded
        ERROR       // scan failed
    }

    /** A single row in the tracker (protocol or module). */
    private static class TrackerRow {
        String key;          // unique key (bus label or bus@canId)
        String label;        // display label
        boolean isModule;    // true = ECU module row, false = protocol bus row
        RowState state = RowState.PENDING;
        String detail = "";        // extra info (DTC count, mode status, etc.)
        View view;                 // the rendered row view
        TextView iconView;
        TextView labelView;
        TextView detailView;
        LinearLayout modeChips;    // for protocol rows: Mode 03/07/0A status chips
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, TrackerRow> rows = new LinkedHashMap<>();
    private final List<TrackerRow> rowOrder = new ArrayList<>();

    private LinearLayout container;
    private TextView summaryText;
    private ProgressBar topProgress;
    private int totalBuses = 0;
    private int busesCompleted = 0;

    private static final int COLOR_PENDING = 0xFF64748B;   // slate-500
    private static final int COLOR_SCANNING = 0xFFF59E0B;  // amber-500
    private static final int COLOR_RESPONDED = 0xFF22C55E; // green-500
    private static final int COLOR_HAS_DTC = 0xFFEF4444;  // red-500
    private static final int COLOR_NO_RESPONSE = 0xFF475569; // slate-600
    private static final int COLOR_ERROR = 0xFFEF4444;
    private static final int COLOR_TEXT = 0xFFE2E8F0;     // slate-200
    private static final int COLOR_MUTED = 0xFF94A3B8;    // slate-400
    private static final int COLOR_ACCENT = 0xFF0EA5E9;   // sky-500

    public ScanTrackerView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * density);
        setPadding(pad, pad, pad, pad);
        setVisibility(GONE);

        // ── Header row: spinning progress + summary ──
        LinearLayout headerRow = new LinearLayout(getContext());
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        topProgress = new ProgressBar(getContext());
        topProgress.setIndeterminate(true);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                (int) (20 * density), (int) (20 * density));
        progLp.rightMargin = (int) (10 * density);
        topProgress.setLayoutParams(progLp);
        headerRow.addView(topProgress);

        summaryText = new TextView(getContext());
        summaryText.setText("Preparing scan...");
        summaryText.setTextColor(COLOR_MUTED);
        summaryText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summaryText.setTypeface(null, android.graphics.Typeface.BOLD);
        headerRow.addView(summaryText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        addView(headerRow);
        int headerBottom = (int) (10 * density);
        headerRow.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{
            bottomMargin = headerBottom;
        }});

        // ── Container for protocol and module rows ──
        container = new LinearLayout(getContext());
        container.setOrientation(VERTICAL);
        addView(container, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Reset the tracker for a fresh scan. Must be called on main thread. */
    public void reset() {
        mainHandler.post(() -> {
            rows.clear();
            rowOrder.clear();
            container.removeAllViews();
            busesCompleted = 0;
            totalBuses = 0;
            setVisibility(VISIBLE);
            topProgress.setVisibility(VISIBLE);
            summaryText.setText("Preparing scan...");
        });
    }

    /** Hide the tracker and stop the progress spinner. */
    public void hide() {
        mainHandler.post(() -> {
            topProgress.setVisibility(GONE);
            setVisibility(GONE);
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  DtcScanProgressListener implementation
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onProtocolProbeStart(String busLabel, String description, int busIndex, int totalBuses) {
        mainHandler.post(() -> {
            this.totalBuses = totalBuses;
            String key = "proto:" + busLabel;
            TrackerRow row = rows.get(key);
            if (row == null) {
                row = createProtocolRow(busLabel, description);
                rows.put(key, row);
                rowOrder.add(row);
                container.addView(row.view);
            }
            row.state = RowState.SCANNING;
            row.detail = "Probing...";
            updateRowVisual(row);

            int progress = busIndex + 1;
            summaryText.setText(String.format("Scanning %d/%d: %s", progress, totalBuses, busLabel));
            summaryText.setTextColor(COLOR_SCANNING);
        });
    }

    @Override
    public void onProtocolProbeResult(String busLabel, boolean responded, int modulesFound) {
        mainHandler.post(() -> {
            String key = "proto:" + busLabel;
            TrackerRow row = rows.get(key);
            if (row == null) return;

            if (!responded) {
                row.state = RowState.NO_RESPONSE;
                row.detail = "No ECU responded";
            } else if (modulesFound > 0) {
                row.state = RowState.SCANNING; // still scanning modes
                row.detail = modulesFound + " module" + (modulesFound > 1 ? "s" : "") + " detected";
            } else {
                row.state = RowState.SCANNING;
                row.detail = "Responded";
            }
            updateRowVisual(row);
        });
    }

    @Override
    public void onModeScanStart(String busLabel, String modeName) {
        mainHandler.post(() -> {
            String key = "proto:" + busLabel;
            TrackerRow row = rows.get(key);
            if (row == null) return;
            addModeChip(row, modeName, RowState.SCANNING);
            row.detail = "Scanning " + modeName + "...";
            updateRowVisual(row);
        });
    }

    @Override
    public void onModeScanComplete(String busLabel, String modeName, int dtcsFound) {
        mainHandler.post(() -> {
            String key = "proto:" + busLabel;
            TrackerRow row = rows.get(key);
            if (row == null) return;

            RowState chipState = dtcsFound > 0 ? RowState.HAS_DTC : RowState.RESPONDED;
            updateModeChip(row, modeName, chipState, dtcsFound > 0 ? dtcsFound + " DTC" : "clean");

            // Update overall row state
            int totalDtcForBus = countDtcChips(row);
            if (totalDtcForBus > 0) {
                row.state = RowState.HAS_DTC;
            } else if (row.state != RowState.NO_RESPONSE) {
                row.state = RowState.RESPONDED;
            }
            row.detail = totalDtcForBus > 0 ? totalDtcForBus + " DTCs found" : "No DTCs";
            updateRowVisual(row);
        });
    }

    @Override
    public void onModuleDetected(String busLabel, String canId, String moduleName,
                                  int storedCount, int pendingCount, int permanentCount) {
        mainHandler.post(() -> {
            String key = "module:" + busLabel + "@" + canId;
            TrackerRow row = rows.get(key);
            if (row == null) {
                String label = canId + " — " + moduleName;
                row = createModuleRow(busLabel, canId, moduleName);
                rows.put(key, row);
                rowOrder.add(row);
                container.addView(row.view);
            }

            int total = storedCount + pendingCount + permanentCount;
            if (total > 0) {
                row.state = RowState.HAS_DTC;
            } else {
                row.state = RowState.RESPONDED;
            }

            StringBuilder detail = new StringBuilder();
            if (storedCount > 0) detail.append("S:").append(storedCount).append(" ");
            if (pendingCount > 0) detail.append("P:").append(pendingCount).append(" ");
            if (permanentCount > 0) detail.append("Perm:").append(permanentCount);
            if (detail.length() == 0) detail.append("Clean");
            row.detail = detail.toString();
            updateRowVisual(row);
        });
    }

    @Override
    public void onScanComplete(int totalProtocols, int protocolsResponded, int totalDtcCount) {
        mainHandler.post(() -> {
            busesCompleted = totalProtocols;
            topProgress.setVisibility(GONE);

            // Finalize any rows still in SCANNING state
            for (TrackerRow row : rowOrder) {
                if (row.state == RowState.SCANNING) {
                    row.state = RowState.RESPONDED;
                    if (row.detail.equals("Probing...") || row.detail.contains("Scanning")) {
                        row.detail = "Done";
                    }
                    updateRowVisual(row);
                }
            }

            if (totalDtcCount > 0) {
                summaryText.setText(String.format("Scan complete: %d protocol%s, %d DTC%s found",
                        protocolsResponded, protocolsResponded != 1 ? "s" : "",
                        totalDtcCount, totalDtcCount != 1 ? "s" : ""));
                summaryText.setTextColor(COLOR_HAS_DTC);
            } else {
                summaryText.setText(String.format("Scan complete: %d protocol%s responded, no DTCs",
                        protocolsResponded, protocolsResponded != 1 ? "s" : ""));
                summaryText.setTextColor(COLOR_RESPONDED);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  Row creation and visual updates
    // ═══════════════════════════════════════════════════════════════

    private TrackerRow createProtocolRow(String busLabel, String description) {
        TrackerRow row = new TrackerRow();
        row.key = "proto:" + busLabel;
        row.label = busLabel;
        row.isModule = false;

        float density = getResources().getDisplayMetrics().density;
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(VERTICAL);
        layout.setPadding((int) (10 * density), (int) (8 * density), (int) (10 * density), (int) (8 * density));
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (4 * density);
        layout.setLayoutParams(lp);

        // Top row: icon + label + detail
        LinearLayout topRow = new LinearLayout(getContext());
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        row.iconView = new TextView(getContext());
        row.iconView.setText("○");
        row.iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.iconView.setTextColor(COLOR_PENDING);
        row.iconView.setPadding(0, 0, (int) (8 * density), 0);
        topRow.addView(row.iconView);

        row.labelView = new TextView(getContext());
        row.labelView.setText(busLabel);
        row.labelView.setTextColor(COLOR_TEXT);
        row.labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.labelView.setTypeface(null, android.graphics.Typeface.BOLD);
        topRow.addView(row.labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.detailView = new TextView(getContext());
        row.detailView.setText("");
        row.detailView.setTextColor(COLOR_MUTED);
        row.detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        topRow.addView(row.detailView);

        layout.addView(topRow);

        // Mode chips row (initially empty, populated by onModeScanStart/Complete)
        row.modeChips = new LinearLayout(getContext());
        row.modeChips.setOrientation(HORIZONTAL);
        row.modeChips.setPadding((int) (22 * density), (int) (4 * density), 0, 0);
        layout.addView(row.modeChips);

        row.view = layout;
        return row;
    }

    private TrackerRow createModuleRow(String busLabel, String canId, String moduleName) {
        TrackerRow row = new TrackerRow();
        row.key = "module:" + busLabel + "@" + canId;
        row.label = canId + " " + moduleName;
        row.isModule = true;

        float density = getResources().getDisplayMetrics().density;
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding((int) (22 * density), (int) (5 * density), (int) (10 * density), (int) (5 * density));
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (2 * density);
        layout.setLayoutParams(lp);

        row.iconView = new TextView(getContext());
        row.iconView.setText("▸");
        row.iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        row.iconView.setTextColor(COLOR_PENDING);
        row.iconView.setPadding(0, 0, (int) (6 * density), 0);
        layout.addView(row.iconView);

        row.labelView = new TextView(getContext());
        row.labelView.setText(canId + " " + moduleName);
        row.labelView.setTextColor(COLOR_TEXT);
        row.labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        layout.addView(row.labelView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.detailView = new TextView(getContext());
        row.detailView.setText("");
        row.detailView.setTextColor(COLOR_MUTED);
        row.detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        layout.addView(row.detailView);

        row.view = layout;
        return row;
    }

    private void updateRowVisual(TrackerRow row) {
        if (row.iconView == null) return;

        int color;
        String icon;
        boolean pulse = false;

        switch (row.state) {
            case PENDING:
                color = COLOR_PENDING;
                icon = "○";
                break;
            case SCANNING:
                color = COLOR_SCANNING;
                icon = "◐";
                pulse = true;
                break;
            case RESPONDED:
                color = COLOR_RESPONDED;
                icon = "✓";
                break;
            case HAS_DTC:
                color = COLOR_HAS_DTC;
                icon = "●";
                break;
            case NO_RESPONSE:
                color = COLOR_NO_RESPONSE;
                icon = "✗";
                break;
            case ERROR:
                color = COLOR_ERROR;
                icon = "!";
                break;
            default:
                color = COLOR_PENDING;
                icon = "○";
        }

        row.iconView.setText(icon);
        row.iconView.setTextColor(color);
        row.labelView.setTextColor(row.state == RowState.NO_RESPONSE ? COLOR_MUTED : COLOR_TEXT);
        row.detailView.setText(row.detail);
        row.detailView.setTextColor(color);

        if (pulse) {
            AlphaAnimation anim = new AlphaAnimation(0.4f, 1.0f);
            anim.setDuration(600);
            anim.setRepeatMode(AlphaAnimation.REVERSE);
            anim.setRepeatCount(AlphaAnimation.INFINITE);
            row.iconView.startAnimation(anim);
        } else {
            row.iconView.clearAnimation();
        }
    }

    private void addModeChip(TrackerRow row, String modeName, RowState state) {
        if (row.modeChips == null) return;
        float density = getResources().getDisplayMetrics().density;

        // Check if chip already exists
        for (int i = 0; i < row.modeChips.getChildCount(); i++) {
            View child = row.modeChips.getChildAt(i);
            if (child instanceof TextView && modeName.equals(child.getTag())) {
                return;
            }
        }

        TextView chip = new TextView(getContext());
        chip.setTag(modeName);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        chip.setPadding((int) (6 * density), (int) (2 * density), (int) (6 * density), (int) (2 * density));
        LinearLayout.LayoutParams chipLp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.rightMargin = (int) (4 * density);
        chip.setLayoutParams(chipLp);

        // Short label: "03" from "Mode 03 (Stored)"
        String shortLabel = modeName;
        if (modeName.startsWith("Mode ")) {
            String[] parts = modeName.split("[ ()]");
            if (parts.length >= 2) shortLabel = parts[1];
        }
        chip.setText(shortLabel);
        chip.setTextColor(COLOR_SCANNING);

        row.modeChips.addView(chip);
    }

    private void updateModeChip(TrackerRow row, String modeName, RowState state, String resultText) {
        if (row.modeChips == null) return;

        for (int i = 0; i < row.modeChips.getChildCount(); i++) {
            View child = row.modeChips.getChildAt(i);
            if (child instanceof TextView && modeName.equals(child.getTag())) {
                TextView chip = (TextView) child;
                int color;
                switch (state) {
                    case HAS_DTC: color = COLOR_HAS_DTC; break;
                    case RESPONDED: color = COLOR_RESPONDED; break;
                    default: color = COLOR_MUTED;
                }
                chip.setTextColor(color);
                // Append result count
                String shortLabel = chip.getText().toString().split("\\s")[0];
                chip.setText(shortLabel + ":" + resultText);
                break;
            }
        }
    }

    private int countDtcChips(TrackerRow row) {
        if (row.modeChips == null) return 0;
        int count = 0;
        for (int i = 0; i < row.modeChips.getChildCount(); i++) {
            View child = row.modeChips.getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString();
                if (text.contains("DTC")) {
                    // Parse "03:2 DTC" → 2
                    try {
                        String numPart = text.split(":")[1].split("\\s")[0];
                        count += Integer.parseInt(numPart);
                    } catch (Exception ignored) {}
                }
            }
        }
        return count;
    }
}
