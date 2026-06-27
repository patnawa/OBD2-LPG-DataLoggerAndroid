package com.alpha.obd2logger;

import java.util.List;

public final class DataRecord {
    private final String timestamp;
    private final double elapsedS;
    private final String fuelMode;
    private final String vehicleBrand;
    private final String vin;
    private final List<SensorSample> samples;

    public DataRecord(String timestamp, double elapsedS, String fuelMode, String vehicleBrand,
                      String vin, List<SensorSample> samples) {
        this.timestamp = timestamp;
        this.elapsedS = elapsedS;
        this.fuelMode = fuelMode;
        this.vehicleBrand = vehicleBrand;
        this.vin = vin;
        this.samples = samples;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public double getElapsedS() {
        return elapsedS;
    }

    public String getFuelMode() {
        return fuelMode;
    }

    public String getVehicleBrand() {
        return vehicleBrand;
    }

    public String getVin() {
        return vin;
    }

    public List<SensorSample> getSamples() {
        return samples;
    }
}
