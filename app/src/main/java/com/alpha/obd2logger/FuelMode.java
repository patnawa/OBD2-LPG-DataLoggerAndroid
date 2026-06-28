package com.alpha.obd2logger;

public enum FuelMode {
    LPG("lpg/cng"),
    PETROL("petrol");

    private final String value;

    FuelMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
