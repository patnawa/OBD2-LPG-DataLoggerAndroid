package com.alpha.obd2logger;

public enum ObdProtocol {
    AUTO("0", "Auto"),
    SAE_J1850_PWM("1", "SAE J1850 PWM 41.6 kbaud"),
    SAE_J1850_VPW("2", "SAE J1850 VPW 10.4 kbaud"),
    ISO_9141_2("3", "ISO 9141-2 5 baud init"),
    ISO_14230_4_KWP_5_BAUD("4", "ISO 14230-4 KWP 5 baud init"),
    ISO_14230_4_KWP_FAST("5", "ISO 14230-4 KWP fast init"),
    ISO_15765_4_CAN_11BIT_500("6", "ISO 15765-4 CAN 11-bit ID 500 kbaud"),
    ISO_15765_4_CAN_29BIT_500("7", "ISO 15765-4 CAN 29-bit ID 500 kbaud"),
    ISO_15765_4_CAN_11BIT_250("8", "ISO 15765-4 CAN 11-bit ID 250 kbaud"),
    ISO_15765_4_CAN_29BIT_250("9", "ISO 15765-4 CAN 29-bit ID 250 kbaud"),
    SAE_J1939_CAN("A", "SAE J1939 CAN 29-bit ID 250 kbaud"),
    USER1_CAN("B", "User1 CAN 11-bit ID 125 kbaud (default)"),
    USER2_CAN("C", "User2 CAN 11-bit ID 50 kbaud (default)");

    private final String elmValue;
    private final String label;

    ObdProtocol(String elmValue, String label) {
        this.elmValue = elmValue;
        this.label = label;
    }

    public String getElmValue() {
        return elmValue;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Resolve an ATDPN response to the protocol the adapter actually locked.
     *
     * <p>ATDPN ("Describe Protocol by Number") answers with the single protocol
     * digit, prefixed with {@code A} when the adapter chose it via automatic
     * search — {@code A6} means "auto-selected ISO 15765-4 CAN 11/500". The
     * response may carry echo remnants, prompt characters or CR/LF depending on
     * the adapter, so everything except the trailing code is ignored.
     *
     * @return the resolved protocol, or null when the response is empty,
     *         unparseable, or reports 0 (no protocol selected yet). Never
     *         returns {@link #AUTO} — the point of ATDPN is the concrete result.
     */
    public static ObdProtocol fromDpnResponse(String atDpnResponse) {
        if (atDpnResponse == null) return null;
        String cleaned = atDpnResponse.toUpperCase(java.util.Locale.US)
                .replaceAll("[^0-9A-F]", "");
        if (cleaned.isEmpty()) return null;
        // The code is the LAST 1-2 chars: an echoed "ATDPN" prefix compacts to
        // "ADN"-free hex ("AD") which must not be mistaken for the code itself,
        // so scan from the end: optional 'A' auto marker + one protocol digit.
        char code = cleaned.charAt(cleaned.length() - 1);
        for (ObdProtocol protocol : values()) {
            if (protocol != AUTO && protocol.elmValue.charAt(0) == code) {
                return protocol;
            }
        }
        return null;
    }

    /** True for the 29-bit ISO 15765-4 CAN variants (500k and 250k). */
    public boolean isTwentyNineBitCan() {
        return this == ISO_15765_4_CAN_29BIT_500 || this == ISO_15765_4_CAN_29BIT_250;
    }

    /** True for the standard 11-bit CAN variants. */
    public boolean isElevenBitCan() {
        return this == ISO_15765_4_CAN_11BIT_500 || this == ISO_15765_4_CAN_11BIT_250
                || this == USER1_CAN || this == USER2_CAN;
    }

    @Override
    public String toString() {
        return label + " (AT SP " + elmValue + ")";
    }
}
