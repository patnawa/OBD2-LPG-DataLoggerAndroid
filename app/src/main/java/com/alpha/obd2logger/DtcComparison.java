package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares two DTC scan snapshots to identify:
 *   - NEW codes (present now, not in previous scan)
 *   - CLEARED codes (present in previous scan, gone now)
 *   - PERSISTING codes (present in both scans)
 *
 * This is like professional scanners that show "3 new, 1 cleared" after a rescan.
 */
public final class DtcComparison {

    private final List<DtcCode> newCodes;
    private final List<DtcCode> clearedCodes;
    private final List<DtcCode> persistingCodes;
    private final boolean hasPreviousScan;

    public DtcComparison(List<DtcCode> newCodes,
                         List<DtcCode> clearedCodes,
                         List<DtcCode> persistingCodes,
                         boolean hasPreviousScan) {
        this.newCodes = newCodes;
        this.clearedCodes = clearedCodes;
        this.persistingCodes = persistingCodes;
        this.hasPreviousScan = hasPreviousScan;
    }

    public List<DtcCode> getNewCodes() { return newCodes; }
    public List<DtcCode> getClearedCodes() { return clearedCodes; }
    public List<DtcCode> getPersistingCodes() { return persistingCodes; }
    public boolean hasPreviousScan() { return hasPreviousScan; }
    public boolean hasChanges() { return !newCodes.isEmpty() || !clearedCodes.isEmpty(); }

    /**
     * Generate a summary string: "3 NEW, 1 CLEARED, 2 persisting"
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        if (!newCodes.isEmpty()) {
            sb.append(newCodes.size()).append(" NEW");
        }
        if (!clearedCodes.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(clearedCodes.size()).append(" CLEARED");
        }
        if (!persistingCodes.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(persistingCodes.size()).append(" persisting");
        }
        if (sb.length() == 0) {
            sb.append("No changes");
        }
        return sb.toString();
    }

    /**
     * Compare current scan vs previous scan (all DTC types combined).
     */
    public static DtcComparison compare(
            List<DtcCode> previousStored, List<DtcCode> previousPending,
            List<DtcCode> currentStored, List<DtcCode> currentPending) {

        // Combine stored + pending for comparison
        Set<String> prevCodes = new HashSet<>();
        if (previousStored != null) {
            for (DtcCode c : previousStored) prevCodes.add(c.getCode());
        }
        if (previousPending != null) {
            for (DtcCode c : previousPending) prevCodes.add(c.getCode());
        }

        Set<String> currCodes = new HashSet<>();
        for (DtcCode c : currentStored) currCodes.add(c.getCode());
        for (DtcCode c : currentPending) currCodes.add(c.getCode());

        // Build lookup maps for DtcCode objects
        java.util.Map<String, DtcCode> currMap = new java.util.LinkedHashMap<>();
        for (DtcCode c : currentStored) currMap.put(c.getCode(), c);
        for (DtcCode c : currentPending) {
            if (!currMap.containsKey(c.getCode())) currMap.put(c.getCode(), c);
        }

        java.util.Map<String, DtcCode> prevMap = new java.util.LinkedHashMap<>();
        if (previousStored != null) {
            for (DtcCode c : previousStored) prevMap.put(c.getCode(), c);
        }
        if (previousPending != null) {
            for (DtcCode c : previousPending) {
                if (!prevMap.containsKey(c.getCode())) prevMap.put(c.getCode(), c);
            }
        }

        List<DtcCode> newCodes = new ArrayList<>();
        List<DtcCode> clearedCodes = new ArrayList<>();
        List<DtcCode> persistingCodes = new ArrayList<>();

        // Current codes not in previous = NEW
        for (String code : currCodes) {
            if (!prevCodes.contains(code)) {
                newCodes.add(currMap.get(code));
            } else {
                persistingCodes.add(currMap.get(code));
            }
        }

        // Previous codes not in current = CLEARED
        for (String code : prevCodes) {
            if (!currCodes.contains(code)) {
                DtcCode c = prevMap.get(code);
                if (c != null) clearedCodes.add(c);
            }
        }

        boolean hasPrevious = previousStored != null || previousPending != null;
        return new DtcComparison(newCodes, clearedCodes, persistingCodes, hasPrevious);
    }

    /**
     * Compare using DtcHistoryDb records from the last scan.
     */
    public static DtcComparison compareWithHistory(
            List<DtcHistoryDb.DtcHistoryRecord> history,
            List<DtcCode> currentStored,
            List<DtcCode> currentPending) {
        return compareWithHistory(history, currentStored, currentPending, null);
    }

    /**
     * Compare using DtcHistoryDb records from the last scan, including
     * permanent (Mode 0A) codes on both sides of the comparison.
     */
    public static DtcComparison compareWithHistory(
            List<DtcHistoryDb.DtcHistoryRecord> history,
            List<DtcCode> currentStored,
            List<DtcCode> currentPending,
            List<DtcCode> currentPermanent) {

        if (history == null || history.isEmpty()) {
            return new DtcComparison(
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), false
            );
        }

        // Find the most recent scan date. "clean" placeholder records count:
        // they mark a real scan that found zero codes, so codes from an older
        // scan must not be resurrected as the comparison baseline.
        String latestDate = null;
        for (DtcHistoryDb.DtcHistoryRecord rec : history) {
            if (latestDate == null || rec.date.compareTo(latestDate) > 0) {
                latestDate = rec.date;
            }
        }

        if (latestDate == null) {
            return new DtcComparison(
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), false
            );
        }

        // Collect all codes from the latest scan ("clean" rows carry no code).
        // Permanent codes were present last scan, so they count toward the
        // previous-code set — otherwise a code surviving only as Mode 0A
        // permanent would be misreported as NEW on the next scan.
        List<DtcCode> prevStored = new ArrayList<>();
        List<DtcCode> prevPending = new ArrayList<>();
        for (DtcHistoryDb.DtcHistoryRecord rec : history) {
            if (latestDate.equals(rec.date) && !"clean".equals(rec.type)) {
                DtcCode c = new DtcCode(rec.code, rec.description);
                if ("pending".equals(rec.type)) prevPending.add(c);
                else prevStored.add(c);
            }
        }

        List<DtcCode> currStored = new ArrayList<>(currentStored);
        if (currentPermanent != null) currStored.addAll(currentPermanent);
        return compare(prevStored, prevPending, currStored, currentPending);
    }
}
