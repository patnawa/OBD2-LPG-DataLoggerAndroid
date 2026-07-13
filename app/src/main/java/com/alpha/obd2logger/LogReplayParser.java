package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure (no-Android) parser for replaying a saved OBD2 log CSV onto the FuelMapView.
 *
 * Extracted from ReviewSessionActivity so the column-matching, CSV-quoting and
 * closed-loop gating logic can be unit-tested on the plain JVM (the Activity
 * itself needs the Android runtime / a content URI and can't be instantiated in
 * a JUnit test, and this project has no Robolectric).
 *
 * The CSV shape is whatever {@link DataWriter} produces:
 *   timestamp,elapsed_s,fuel_mode,loop_status,vehicle_brand,vin,&lt;PID columns…&gt;
 * where every text field is wrapped in double quotes ("" escapes an inner quote),
 * the timestamp may contain a comma inside its quotes, PID column headers are
 * quoted like "Engine RPM (rpm)", and PID values are written raw (or empty).
 */
public final class LogReplayParser {

    /** One plottable data point, or a skip/parse marker. */
    public static final class Point {
        public final double rpm;
        public final double axis;   // MAP, or Engine Load fallback
        public final double trim;   // STFT + LTFT
        public final FuelMode fuelMode;
        public Point(double rpm, double axis, double trim, FuelMode fuelMode) {
            this.rpm = rpm; this.axis = axis; this.trim = trim; this.fuelMode = fuelMode;
        }
    }

    /** Resolved column indices from a header line. axisIdx = MAP, else Engine Load. */
        public static final class Columns {
            public int fuelModeIdx = -1, loopStatusIdx = -1, fuelSystemStatusIdx = -1;
            public int rpmIdx = -1, mapIdx = -1, loadIdx = -1, stftIdx = -1, ltftIdx = -1;
            public int lambdaIdx = -1;  // measured PID 0x34 only; never commanded PID 0x44
            // Optional ≥3.13 AI map columns (used when present to prefer accepted samples)
            public int mapRpmCellIdx = -1, mapAxisValueIdx = -1, mapAcceptedIdx = -1, mapTrimTotalIdx = -1;
            public int axisIdx() { return (mapIdx != -1) ? mapIdx : loadIdx; }
            public boolean hasRequired() {
                if (rpmIdx != -1 && axisIdx() != -1) return true;
                // New logs can satisfy with pre-binned map columns alone
                return mapRpmCellIdx != -1 && mapAxisValueIdx != -1;
            }
        }

    private LogReplayParser() {}

    /**
     * RFC-4180-style CSV line splitter. Handles double-quoted fields, "" escapes,
     * and commas inside quoted fields. A naive String.split(",") corrupts the
     * quoted timestamp (which contains a comma) and shifts every column index.
     */
    public static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    out.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    /** Bounds-safe, trimmed cell accessor. */
    public static String cell(String[] parts, int idx) {
        if (idx < 0 || idx >= parts.length || parts[idx] == null) return "";
        return parts[idx].trim();
    }

    /** Resolve column indices from a (possibly quoted) header line. */
        public static Columns parseHeader(String headerLine) {
            Columns c = new Columns();
            String[] headers = splitCsv(headerLine);
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].toLowerCase().replace("\"", "").trim();
                if (h.equals("fuel_mode")) c.fuelModeIdx = i;
                else if (h.equals("loop_status")) c.loopStatusIdx = i;
                else if (h.contains("fuel system status")) c.fuelSystemStatusIdx = i;
                else if (h.contains("engine rpm") && !h.contains("map rpm")) c.rpmIdx = i;
                else if (h.contains("manifold pressure")) c.mapIdx = i;
                else if (h.contains("engine load")) c.loadIdx = i;
                // Only Bank 1 trims for the map (Bank 2 columns would overwrite incorrectly)
                else if (h.contains("short term fuel trim") && !h.contains("bank 2") && c.stftIdx == -1) c.stftIdx = i;
                else if (h.contains("long term fuel trim") && !h.contains("bank 2") && c.ltftIdx == -1) c.ltftIdx = i;
                // PID 0x44 is a commanded target, not a sensor measurement. Using it
                // here would manufacture a fuel trim that merely tracks ECU intent.
                else if (!h.contains("commanded")
                        && (h.contains("lambda (b1s1)") || h.contains("actual lambda"))
                        && c.lambdaIdx == -1) c.lambdaIdx = i;
                // Optional precomputed map fields from ≥3.13 logs (AI-friendly)
                else if (h.contains("map rpm cell")) c.mapRpmCellIdx = i;
                else if (h.contains("map axis value")) c.mapAxisValueIdx = i;
                else if (h.contains("map accepted")) c.mapAcceptedIdx = i;
                else if (h.contains("map trim total")) c.mapTrimTotalIdx = i;
            }
            return c;
        }

    /**
     * Closed-loop gating per SAE J1979 PID 03 byte A bit flags:
     *   0x01 = open loop (temp), 0x02 = CLOSED loop (O2 feedback),
     *   0x04 = open loop (load), 0x08 = open loop (failure).
     * Closed loop is bit 0x02 set. Falls back to the textual loop_status column
     * ("Closed"/"Open") when the numeric Fuel System Status column is absent.
     * Defaults to closed (true) when neither is available so data still plots.
     */
    public static boolean isClosedLoop(String[] parts, Columns c) {
        if (c.fuelSystemStatusIdx != -1 && parts.length > c.fuelSystemStatusIdx) {
            String val = cell(parts, c.fuelSystemStatusIdx);
            if (!val.isEmpty()) {
                try {
                    int ds = (int) Double.parseDouble(val);
                    return (ds & 0x02) != 0;
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
            return true;
        } else if (c.loopStatusIdx != -1 && parts.length > c.loopStatusIdx) {
            String ls = cell(parts, c.loopStatusIdx);
            return !ls.equalsIgnoreCase("Open");
        }
        // Neither column present — loop state is genuinely unknown. Default
        // to CLOSED (true) so data still plots, matching the live logging
        // path (MainActivity.java defaults isClosedLoop=true when PID 03
        // is absent). Previously this was false, which skipped ALL rows in
        // logs without loop-state columns, making the compare/replay feature
        // appear broken ("No valid tuning points found").
        return true;
    }

    /**
         * Parse a single data line into a plottable Point, or null if the line should
         * be skipped (too short, open loop, or unparseable numbers).
         *
         * If the MAP column value is empty (common in logs from MAF-based vehicles
         * before the MAP synthesis fix), falls back to Engine Load as the Y-axis —
         * mirrors the live logging path in updateFuelMap().
         *
         * When ≥3.13 AI map columns are present ({@code map_accepted} /
         * {@code map_trim_total} / binned axis), prefer those so compare/import
         * replays exactly what LiveMapStore accepted during live logging.
         */
        public static Point parseLine(String line, Columns c) {
            String[] parts = splitCsv(line);

            FuelMode mode = FuelMode.PETROL;
            if (c.fuelModeIdx != -1 && parts.length > c.fuelModeIdx) {
                mode = FuelMode.fromString(cell(parts, c.fuelModeIdx));
            }

            // Prefer explicit map_accepted flag when present (1 = LiveMapStore took it)
            if (c.mapAcceptedIdx != -1 && parts.length > c.mapAcceptedIdx) {
                String acc = cell(parts, c.mapAcceptedIdx);
                if (!acc.isEmpty()) {
                    try {
                        if (Double.parseDouble(acc) < 0.5) return null; // rejected
                    } catch (NumberFormatException ignored) { /* fall through */ }
                }
            } else if (!isClosedLoop(parts, c)) {
                return null; // skip open-loop for tuning
            }

            try {
                double rpm;
                double axis;
                double trim;

                boolean usedAiCols = false;
                if (c.mapRpmCellIdx != -1 && c.mapAxisValueIdx != -1
                        && parts.length > Math.max(c.mapRpmCellIdx, c.mapAxisValueIdx)) {
                    String rpmCellStr = cell(parts, c.mapRpmCellIdx);
                    String axisStr = cell(parts, c.mapAxisValueIdx);
                    if (!rpmCellStr.isEmpty() && !axisStr.isEmpty()) {
                        rpm = Double.parseDouble(rpmCellStr);
                        axis = Double.parseDouble(axisStr);
                        usedAiCols = true;
                    } else {
                        rpm = Double.NaN;
                        axis = Double.NaN;
                    }
                } else {
                    rpm = Double.NaN;
                    axis = Double.NaN;
                }

                if (!usedAiCols) {
                    int need = Math.max(c.rpmIdx, c.axisIdx());
                    if (need < 0 || parts.length <= need) return null;
                    rpm = Double.parseDouble(cell(parts, c.rpmIdx));

                    String axisStr = cell(parts, c.axisIdx());
                    if (axisStr.isEmpty() && c.mapIdx != -1 && c.loadIdx != -1) {
                        axisStr = cell(parts, c.loadIdx);
                    }
                    if (axisStr.isEmpty()) return null;
                    axis = Double.parseDouble(axisStr);
                }

                if (c.mapTrimTotalIdx != -1 && parts.length > c.mapTrimTotalIdx) {
                    String t = cell(parts, c.mapTrimTotalIdx);
                    if (!t.isEmpty()) {
                        trim = Double.parseDouble(t);
                        return new Point(rpm, axis, trim, mode);
                    }
                }

                double stft = 0.0;
                if (c.stftIdx != -1 && parts.length > c.stftIdx) {
                    String s = cell(parts, c.stftIdx);
                    if (!s.isEmpty()) stft = Double.parseDouble(s);
                }
                double ltft = 0.0;
                if (c.ltftIdx != -1 && parts.length > c.ltftIdx) {
                    String s = cell(parts, c.ltftIdx);
                    if (!s.isEmpty()) ltft = Double.parseDouble(s);
                }
                trim = stft + ltft;
                // Lambda fallback: LPG/CNG vehicles often have no STFT/LTFT PIDs but
                // DO have measured wideband lambda (PID 0x34). When both trims are
                // absent (0), derive a "synthetic trim" from lambda deviation:
                //   trim% = (lambda - 1.0) * 100
                if (stft == 0.0 && ltft == 0.0 && c.lambdaIdx != -1 && parts.length > c.lambdaIdx) {
                    String lamStr = cell(parts, c.lambdaIdx);
                    if (!lamStr.isEmpty()) {
                        double lambda = Double.parseDouble(lamStr);
                        if (lambda > 0 && lambda < 3) {
                            trim = (lambda - 1.0) * 100.0;
                        }
                    }
                }
                return new Point(rpm, axis, trim, mode);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
