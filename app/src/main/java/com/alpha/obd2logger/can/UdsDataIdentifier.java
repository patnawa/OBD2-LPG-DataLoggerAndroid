package com.alpha.obd2logger.can;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The standardized ECU identification data identifiers from ISO 14229-1.
 *
 * <p>Only the {@code F180}–{@code F19F} identification block is listed. Those
 * identifiers have the same meaning on every conforming ECU, so reading them
 * needs no manufacturer-specific knowledge. Identifiers outside this block are
 * manufacturer-defined: their meaning cannot be known without the maker's own
 * documentation, and sweeping them blind is how modules get upset.</p>
 */
public final class UdsDataIdentifier {

    /** VIN — also the cheapest identifier to probe a module with. */
    public static final int VIN = 0xF190;

    /** ECU serial number — the usual fallback when a module has no VIN. */
    public static final int ECU_SERIAL_NUMBER = 0xF18C;

    private static final Map<Integer, String> LABELS = buildLabels();

    private UdsDataIdentifier() {
    }

    private static Map<Integer, String> buildLabels() {
        Map<Integer, String> labels = new LinkedHashMap<>();
        labels.put(0xF180, "Boot Software Identification");
        labels.put(0xF181, "Application Software Identification");
        labels.put(0xF182, "Application Data Identification");
        labels.put(0xF186, "Active Diagnostic Session");
        labels.put(0xF187, "Manufacturer Spare Part Number");
        labels.put(0xF188, "Manufacturer ECU Software Number");
        labels.put(0xF189, "Manufacturer ECU Software Version");
        labels.put(0xF18A, "System Supplier Identifier");
        labels.put(0xF18B, "ECU Manufacturing Date");
        labels.put(0xF18C, "ECU Serial Number");
        labels.put(0xF18E, "Manufacturer Kit Assembly Part Number");
        labels.put(0xF190, "VIN");
        labels.put(0xF191, "Manufacturer ECU Hardware Number");
        labels.put(0xF192, "Supplier ECU Hardware Number");
        labels.put(0xF193, "Supplier ECU Hardware Version");
        labels.put(0xF194, "Supplier ECU Software Number");
        labels.put(0xF195, "Supplier ECU Software Version");
        labels.put(0xF197, "System Name or Engine Type");
        labels.put(0xF19D, "ECU Installation Date");
        return Collections.unmodifiableMap(labels);
    }

    /** Every identifier this app will request, in sweep order. */
    public static List<Integer> sweepSet() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(LABELS.keySet()));
    }

    /** Human-readable name, or a hex fallback for anything unlisted. */
    public static String labelFor(int identifier) {
        String label = LABELS.get(identifier);
        return label != null ? label : toHex(identifier);
    }

    /** True when this app is willing to request the identifier. */
    public static boolean isStandardIdentificationDid(int identifier) {
        return LABELS.containsKey(identifier);
    }

    public static String toHex(int identifier) {
        return String.format(Locale.US, "%04X", identifier);
    }
}
