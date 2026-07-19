package com.alpha.obd2logger.can;

import java.util.Locale;

/**
 * A UDS (ISO 14229) request this app is permitted to send.
 *
 * <p>Deliberately narrow. The only constructible request is
 * ReadDataByIdentifier (service 0x22), which reads a standardized identifier
 * and cannot change ECU state. Services that write, unlock, or reset —
 * SecurityAccess (0x27), WriteDataByIdentifier (0x2E), RoutineControl (0x31),
 * ECUReset (0x11) — have no factory here, so no caller can build one by
 * accident and no reviewer has to check whether one did.</p>
 *
 * <p>DiagnosticSessionControl (0x10) is also absent, on purpose. Reading
 * identification data works in the default session, and entering an extended
 * session can disable ECU functions and obliges the tester to keep sending
 * TesterPresent. Staying in the default session keeps a read-only sweep
 * genuinely read-only.</p>
 *
 * @see UdsResponseDecoder for the matching response side
 */
public final class UdsRequest {

    /** ISO 14229 ReadDataByIdentifier. */
    public static final int SERVICE_READ_DATA_BY_IDENTIFIER = 0x22;

    /** A positive response echoes the service byte raised by this offset. */
    public static final int POSITIVE_RESPONSE_OFFSET = 0x40;

    private final int service;
    private final int identifier;

    private UdsRequest(int service, int identifier) {
        this.service = service;
        this.identifier = identifier;
    }

    /**
     * Read one data identifier.
     *
     * @param identifier a 16-bit DID, e.g. {@code 0xF190} for the VIN
     * @throws IllegalArgumentException if the identifier is not 16-bit
     */
    public static UdsRequest readDataByIdentifier(int identifier) {
        if (identifier < 0 || identifier > 0xFFFF) {
            throw new IllegalArgumentException(
                    "Data identifier must be 16-bit, got 0x" + Integer.toHexString(identifier));
        }
        return new UdsRequest(SERVICE_READ_DATA_BY_IDENTIFIER, identifier);
    }

    public int getService() {
        return service;
    }

    public int getIdentifier() {
        return identifier;
    }

    /** The service byte a positive answer to this request will carry. */
    public int getExpectedResponseService() {
        return service + POSITIVE_RESPONSE_OFFSET;
    }

    /** Two hex characters naming the expected positive response service. */
    public String getExpectedResponsePrefix() {
        return String.format(Locale.US, "%02X", getExpectedResponseService());
    }

    /** The request as an ELM327 command, e.g. {@code "22F190"}. */
    public String toElmCommand() {
        return String.format(Locale.US, "%02X%04X", service, identifier);
    }

    @Override
    public String toString() {
        return toElmCommand();
    }
}
