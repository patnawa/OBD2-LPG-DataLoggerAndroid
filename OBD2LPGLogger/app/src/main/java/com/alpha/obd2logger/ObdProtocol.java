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
    USER1_CAN("B", "User1 CAN 11-bit ID 500 kbaud"),
    USER2_CAN("C", "User2 CAN 29-bit ID 250 kbaud");

    private final String elmValue;
    private final String label;

    ObdProtocol(String elmValue, String label) {
        this.elmValue = elmValue;
        this.label = label;
    }

    public String getElmValue() {
        return elmValue;
    }

    @Override
    public String toString() {
        return label + " (AT SP " + elmValue + ")";
    }
}
