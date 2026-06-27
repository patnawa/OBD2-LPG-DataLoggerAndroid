package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.List;

public final class SensorSample {
    private final String pidKey;
    private final String name;
    private final Double value;
    private final String unit;
    private final String status;

    public SensorSample(String pidKey, String name, Double value, String unit, String status) {
        this.pidKey = pidKey;
        this.name = name;
        this.value = value;
        this.unit = unit;
        this.status = status;
    }

    public String getPidKey() {
        return pidKey;
    }

    public String getName() {
        return name;
    }

    public Double getValue() {
        return value;
    }

    public String getUnit() {
        return unit;
    }

    public String getStatus() {
        return status;
    }

    public static List<SensorSample> emptyList() {
        return new ArrayList<>();
    }
}
