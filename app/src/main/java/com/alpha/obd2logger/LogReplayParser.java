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
            // ≥3.33 learned-VE-map columns (ve_map_*). The logged cell state is
            // a running mean, so the LAST row per cell is the final learned value.
            public int mapAxisSourceIdx = -1;
            public int veCellVeIdx = -1, veCellHitsIdx = -1;
            public boolean hasVeColumns() {
                return veCellVeIdx != -1 && mapRpmCellIdx != -1 && mapAxisValueIdx != -1;
            }
            // Axis source decided ONCE per file (on the first row with a usable
            // axis value): FALSE = MAP column, TRUE = Engine Load fallback.
            // Deciding per row would mix kPa-binned and %-binned points in one map.
            public Boolean axisFromLoad = null;
            public int axisIdx() { return (mapIdx != -1) ? mapIdx : loadIdx; }
            public boolean hasRequired() {
                if (rpmIdx != -1 && axisIdx() != -1) return true;
                // New logs can satisfy with pre-binned map columns alone
                return mapRpmCellIdx != -1 && mapAxisValueIdx != -1;
            }
        }

    /** One GPS route sample with the telemetry used to color the segment. */
    /**
     * One row's learned-VE-cell state from a ≥3.33 log. The logged value is
     * the cell's running mean AFTER that row's push, so replaying rows in
     * order and keeping the last value per (fuel, cell) reconstructs the
     * exact learned surface at end of session.
     */
    public static final class VeCellSample {
        public final FuelMode fuelMode;
        public final int rpmCell;
        public final double axisValue;
        public final double cellVe;
        public final int cellHits;
        /** MapSampleMeta axis-source code: 1=MAP 2=LOAD 3=SYNTH_MAP, 0 unknown. */
        public final int axisSourceCode;

        VeCellSample(FuelMode fuelMode, int rpmCell, double axisValue,
                     double cellVe, int cellHits, int axisSourceCode) {
            this.fuelMode = fuelMode;
            this.rpmCell = rpmCell;
            this.axisValue = axisValue;
            this.cellVe = cellVe;
            this.cellHits = cellHits;
            this.axisSourceCode = axisSourceCode;
        }
    }

    /**
     * Extract the learned VE cell state from one data line, or null when the
     * row carries none (no VE columns, blank cell state, unparseable numbers).
     */
    public static VeCellSample parseVeCell(String line, Columns c) {
        if (!c.hasVeColumns()) return null;
        String[] parts = splitCsv(line);
        String veStr = cell(parts, c.veCellVeIdx);
        String rpmStr = cell(parts, c.mapRpmCellIdx);
        String axisStr = cell(parts, c.mapAxisValueIdx);
        if (veStr.isEmpty() || rpmStr.isEmpty() || axisStr.isEmpty()) return null;
        try {
            double ve = Double.parseDouble(veStr);
            int rpmCell = (int) Double.parseDouble(rpmStr);
            double axis = Double.parseDouble(axisStr);
            if (!Double.isFinite(ve) || !Double.isFinite(axis) || rpmCell < 0) return null;
            int hits = 1;
            String hitsStr = cell(parts, c.veCellHitsIdx);
            if (!hitsStr.isEmpty()) {
                hits = Math.max(1, (int) Double.parseDouble(hitsStr));
            }
            int axisCode = 0;
            String axisSrcStr = cell(parts, c.mapAxisSourceIdx);
            if (!axisSrcStr.isEmpty()) {
                axisCode = (int) Double.parseDouble(axisSrcStr);
            }
            FuelMode mode = FuelMode.PETROL;
            if (c.fuelModeIdx != -1 && parts.length > c.fuelModeIdx) {
                mode = FuelMode.fromString(cell(parts, c.fuelModeIdx));
            }
            return new VeCellSample(mode, rpmCell, axis, ve, hits, axisCode);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static final class RoutePoint {
        public final double lat;
        public final double lon;
        public final double elapsedS;
        public final Double speedKmh;
        public final Double boostPsi;

        public RoutePoint(double lat, double lon, double elapsedS,
                          Double speedKmh, Double boostPsi) {
            this.lat = lat;
            this.lon = lon;
            this.elapsedS = elapsedS;
            this.speedKmh = speedKmh;
            this.boostPsi = boostPsi;
        }
    }

    /** Resolved column indices for route replay (gps_* columns are ≥3.31). */
    public static final class RouteColumns {
        public int latIdx = -1, lonIdx = -1, elapsedIdx = -1;
        public int speedIdx = -1, boostPsiIdx = -1;

        public boolean hasRoute() {
            return latIdx != -1 && lonIdx != -1;
        }
    }

    /** Resolve route-related column indices from a (possibly quoted) header line. */
    public static RouteColumns parseRouteHeader(String headerLine) {
        RouteColumns c = new RouteColumns();
        String[] headers = splitCsv(headerLine);
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].toLowerCase().replace("\"", "").trim();
            if (h.equals("elapsed_s")) c.elapsedIdx = i;
            else if (h.contains("gps latitude")) c.latIdx = i;
            else if (h.contains("gps longitude")) c.lonIdx = i;
            else if (h.contains("vehicle speed")) c.speedIdx = i;
            else if (h.contains("turbo boost (psi)")) c.boostPsiIdx = i;
        }
        return c;
    }

    /**
     * Parse a data line into a RoutePoint, or null when the row has no fix
     * (blank gps cells mean "no fix", never (0,0)) or unparseable numbers.
     */
    public static RoutePoint parseRouteLine(String line, RouteColumns c) {
        if (!c.hasRoute()) return null;
        String[] parts = splitCsv(line);
        String latStr = cell(parts, c.latIdx);
        String lonStr = cell(parts, c.lonIdx);
        if (latStr.isEmpty() || lonStr.isEmpty()) return null;
        try {
            double lat = Double.parseDouble(latStr);
            double lon = Double.parseDouble(lonStr);
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
            double elapsed = 0;
            String elapsedStr = cell(parts, c.elapsedIdx);
            if (!elapsedStr.isEmpty()) elapsed = Double.parseDouble(elapsedStr);
            Double speed = parseOptional(cell(parts, c.speedIdx));
            Double boost = parseOptional(cell(parts, c.boostPsiIdx));
            return new RoutePoint(lat, lon, elapsed, speed, boost);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double parseOptional(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
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
                else if (h.contains("map axis source")) c.mapAxisSourceIdx = i;
                else if (h.contains("ve map cell ve")) c.veCellVeIdx = i;
                else if (h.contains("ve map cell hits")) c.veCellHitsIdx = i;
                // The CSV label is "Map Sample Accepted (1/0)"; the old bare
                // "map accepted" pattern never matched it, so the replay
                // silently fell back to closed-loop gating instead of the
                // store's actual accept decisions. The ve-map guard keeps
                // "VE Map Sample Accepted" from stealing this index.
                else if (!h.contains("ve map")
                        && (h.contains("map accepted") || h.contains("map sample accepted")))
                    c.mapAcceptedIdx = i;
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
                    if (c.mapIdx != -1 && c.loadIdx != -1) {
                        // Decide the axis source once per file. Engine Load is used
                        // only when the MAP column is effectively empty (MAF-based
                        // vehicles logged before the MAP synthesis fix — those files
                        // have an empty MAP column throughout). Once decided, stick
                        // with that source; a MAP dropout row is skipped rather than
                        // mixing a %-binned point into a kPa-binned map.
                        if (c.axisFromLoad == null) {
                            if (!axisStr.isEmpty()) c.axisFromLoad = Boolean.FALSE;
                            else if (!cell(parts, c.loadIdx).isEmpty()) c.axisFromLoad = Boolean.TRUE;
                        }
                        if (Boolean.TRUE.equals(c.axisFromLoad)) {
                            axisStr = cell(parts, c.loadIdx);
                        }
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
                boolean stftPresent = false;
                if (c.stftIdx != -1 && parts.length > c.stftIdx) {
                    String s = cell(parts, c.stftIdx);
                    if (!s.isEmpty()) { stft = Double.parseDouble(s); stftPresent = true; }
                }
                double ltft = 0.0;
                boolean ltftPresent = false;
                if (c.ltftIdx != -1 && parts.length > c.ltftIdx) {
                    String s = cell(parts, c.ltftIdx);
                    if (!s.isEmpty()) { ltft = Double.parseDouble(s); ltftPresent = true; }
                }
                trim = stft + ltft;
                // Lambda fallback: LPG/CNG vehicles often have no STFT/LTFT PIDs but
                // DO have measured wideband lambda (PID 0x34). Only when both trim
                // CELLS are absent/empty — a measured 0.0% trim is healthy data, not
                // missing data — derive a "synthetic trim" from lambda deviation:
                //   trim% = (lambda - 1.0) * 100
                if (!stftPresent && !ltftPresent && c.lambdaIdx != -1 && parts.length > c.lambdaIdx) {
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
