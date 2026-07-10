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
        public int axisIdx() { return (mapIdx != -1) ? mapIdx : loadIdx; }
        public boolean hasRequired() { return rpmIdx != -1 && axisIdx() != -1; }
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
            else if (h.contains("engine rpm")) c.rpmIdx = i;
            else if (h.contains("manifold pressure")) c.mapIdx = i;
            else if (h.contains("engine load")) c.loadIdx = i;
            else if (h.contains("short term fuel trim")) c.stftIdx = i;
            else if (h.contains("long term fuel trim")) c.ltftIdx = i;
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
        // Neither column present — loop state is genuinely unknown. Previously this
        // defaulted to CLOSED (true), plotting open-loop samples into the tuning map.
        // Default to OPEN (false) so unknown rows are skipped rather than assumed good.
        return false;
    }

    /**
     * Parse a single data line into a plottable Point, or null if the line should
     * be skipped (too short, open loop, or unparseable numbers).
     */
    public static Point parseLine(String line, Columns c) {
        String[] parts = splitCsv(line);
        if (parts.length <= Math.max(c.rpmIdx, c.axisIdx())) return null;

        FuelMode mode = FuelMode.PETROL;
        if (c.fuelModeIdx != -1 && parts.length > c.fuelModeIdx) {
            // DataWriter writes FuelMode.getValue(): "petrol", "lpg/cng", "G91", "E20", etc.
            mode = FuelMode.fromString(cell(parts, c.fuelModeIdx));
        }

        if (!isClosedLoop(parts, c)) return null; // skip open-loop for tuning

        try {
            double rpm = Double.parseDouble(cell(parts, c.rpmIdx));
            double axis = Double.parseDouble(cell(parts, c.axisIdx()));

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
            return new Point(rpm, axis, stft + ltft, mode);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
