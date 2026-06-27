package com.alpha.obd2logger;

public enum TransportMode {
    SIM("sim"),
    SERIAL("serial"),
    WIFI("wifi"),
    BLE("ble"),
    USB("usb"),
    AUTO("auto");

    private final String value;

    TransportMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
