package com.alpha.obd2logger;

import java.util.Locale;

/**
 * Bounds and validates an ELM327 response before it reaches a PID parser.
 *
 * <p>Bluetooth adapters occasionally insert NULs, binary noise, or a late
 * tail from a timed-out command. Keeping arbitrary bytes in a growing buffer
 * turns one damaged frame into an out-of-memory or permanently desynchronised
 * command channel. This class accepts only printable ASCII and ELM line
 * separators, with a hard cap for each response.</p>
 */
public final class ElmResponseSanitizer {
    /** Enough for multi-frame VIN/DTC responses while remaining bounded. */
    public static final int MAX_RESPONSE_CHARS = 4096;

    private ElmResponseSanitizer() {
    }

    /**
     * Appends a transport byte only when it is valid ELM text.
     *
     * @return false if the response exceeded its bound; callers must discard
     * the partial frame, drain the transport, and make at most one retry.
     */
    public static boolean appendValidated(StringBuilder destination, int value) {
        if (destination == null) return false;
        if (destination.length() >= MAX_RESPONSE_CHARS) {
            destination.setLength(0);
            return false;
        }
        int unsigned = value & 0xFF;
        if (unsigned == '\r' || unsigned == '\n' || unsigned == '\t'
                || (unsigned >= 0x20 && unsigned <= 0x7E)) {
            destination.append((char) unsigned);
        }
        return true;
    }

    /** Removes binary/control noise from a callback chunk. */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sanitized = new StringBuilder(Math.min(raw.length(), MAX_RESPONSE_CHARS));
        for (int i = 0; i < raw.length() && sanitized.length() < MAX_RESPONSE_CHARS; i++) {
            char ch = raw.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == '\t' || (ch >= 0x20 && ch <= 0x7E)) {
                sanitized.append(ch);
            }
        }
        return sanitized.toString();
    }

    /**
     * A single retry after buffer draining can recover these adapter faults.
     * NO DATA is excluded because it is an ECU answer, not a transport fault;
     * retrying it at poll frequency makes a congested vehicle bus worse.
     */
    public static boolean needsTransportRecovery(String response) {
        String normalized = compactUpper(response);
        return (!normalized.isEmpty() && (response == null || response.indexOf('>') < 0))
                || normalized.contains("BUFFERFULL")
                || normalized.contains("BUFFERSMALL")
                || normalized.contains("STOPPED")
                || normalized.contains("CANERROR")
                || normalized.contains("RXERROR")
                || normalized.contains("FBERROR")
                || normalized.contains("UNABLETOCONNECT")
                || normalized.equals("?")
                || normalized.endsWith("?>");
    }

    public static boolean isNoData(String response) {
        return compactUpper(response).contains("NODATA");
    }

    private static String compactUpper(String response) {
        if (response == null || response.isEmpty()) return "";
        return response.replaceAll("\\s+", "").replace(" ", "")
                .toUpperCase(Locale.US);
    }
}
