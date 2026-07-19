package com.alpha.obd2logger;

import java.util.Locale;

/**
 * ELM327 physical (point-to-point) CAN addressing.
 *
 * <p>Ordinary OBD2 requests are functional: they go to the broadcast address
 * and whichever modules implement the service answer. Reaching one specific
 * module instead means setting its request header with {@code ATSH} and
 * filtering its reply with {@code ATCRA}, then putting broadcast addressing
 * back so later polling still reaches the whole bus.
 *
 * <p>The restore half has bitten this codebase before and is easy to get wrong:
 * <ul>
 *   <li>A bare {@code ATCRA} clears the receive filter. {@code ATCRA000} instead
 *       <em>sets</em> a filter for CAN ID 0x000, so every later poll returns
 *       NO DATA.</li>
 *   <li>A bare {@code ATSH} is not a reset either — the functional header has to
 *       be written back explicitly, and it differs by address width
 *       ({@code 7DF} for 11-bit, {@code 18DB33F1} for 29-bit).</li>
 * </ul>
 *
 * <p>Collecting both halves here keeps every caller on the same sequence.
 */
final class PhysicalAddressing {

    /** An ECU answers on its request ID plus this offset (7E0 → 7E8). */
    static final int RESPONSE_OFFSET = 0x08;

    /** 11-bit functional (broadcast) request header. */
    static final String FUNCTIONAL_11_BIT = "7DF";

    /** 29-bit functional (broadcast) request header. */
    static final String FUNCTIONAL_29_BIT = "18DB33F1";

    private PhysicalAddressing() {
    }

    /**
     * Point the adapter at one ECU: {@code ATSH<tx>} then {@code ATCRA<rx>}.
     *
     * @return false if the adapter rejected the request header. That happens
     *         when the active protocol cannot carry it — an {@code ATSH7E0} on
     *         a bus that resolved to 29-bit, say — and means addressing this
     *         way will not work for any address on this bus.
     */
    static boolean applyTarget(BaseDriver driver, String txHeader, String rxFilter) {
        return applyTarget(driver, txHeader, rxFilter, 0);
    }

    /**
     * @param settleMs pause after each command. A one-shot read can afford to
     *                 let the adapter settle; a sweep over many addresses pays
     *                 this per candidate, so it passes 0.
     */
    static boolean applyTarget(BaseDriver driver, String txHeader, String rxFilter,
                               long settleMs) {
        if (driver == null || txHeader == null || rxFilter == null) return false;

        String setHeader = driver.sendCommandRaw("ATSH" + txHeader);
        if (setHeader != null && setHeader.contains("?")) {
            // STN and most clone firmware accept the whole 29-bit ID as one
            // 8-digit ATSH, but a genuine ELM327 rejects it: the datasheet form
            // is ATCP for the priority byte plus a 6-digit ATSH for the lower
            // 24 bits. Retry that split before declaring the bus unusable.
            if (txHeader.length() != 8) return false;
            String setPriority = driver.sendCommandRaw("ATCP" + txHeader.substring(0, 2));
            if (setPriority != null && setPriority.contains("?")) return false;
            pause(settleMs);
            setHeader = driver.sendCommandRaw("ATSH" + txHeader.substring(2));
            if (setHeader != null && setHeader.contains("?")) return false;
        }
        pause(settleMs);
        driver.sendCommandRaw("ATCRA" + rxFilter);
        pause(settleMs);
        return true;
    }

    /**
     * Clear the receive filter and put functional addressing back, matching the
     * address width that {@code txHeader} was using.
     */
    static void restoreFunctional(BaseDriver driver, String txHeader) {
        if (driver == null) return;
        driver.sendCommandRaw("ATCRA");
        driver.sendCommandRaw("ATAR");
        String functional = functionalHeaderFor(txHeader);
        String set = driver.sendCommandRaw("ATSH" + functional);
        if (set != null && set.contains("?") && functional.length() == 8) {
            // Genuine ELM327: 29-bit functional header via ATCP + 6-digit ATSH.
            driver.sendCommandRaw("ATCP" + functional.substring(0, 2));
            driver.sendCommandRaw("ATSH" + functional.substring(2));
        }
    }

    /**
     * The broadcast header for the width of {@code txHeader}. Anything longer
     * than a 3-character 11-bit ID is treated as 29-bit.
     */
    static String functionalHeaderFor(String txHeader) {
        return txHeader != null && txHeader.length() > 3 ? FUNCTIONAL_29_BIT : FUNCTIONAL_11_BIT;
    }

    /** Format a CAN ID as the 3-hex-character form ATSH/ATCRA expect. */
    static String toHeaderHex(int canId) {
        return String.format(Locale.US, "%03X", canId);
    }

    private static void pause(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
