package com.alpha.obd2logger;

import java.util.List;

/**
 * A single logged sample set.
 *
 * <p><b>The sample list is held by reference, not copied — deliberately.</b>
 * The polling pipeline builds the record, then pushes it into
 * {@link LiveMapStore} and appends the resulting {@code map_*} diagnostic
 * samples via {@link MapSampleMeta#appendLogSamples}, which mutates this list
 * after construction. That ordering is what lets the log record what the store
 * actually decided rather than a prediction of it.
 *
 * <p>The class looks immutable (final class, final fields) but is not. Adding a
 * defensive copy here — or in {@link #getSamples()} — would silently blank
 * every {@code map_*} column in the CSV and JSONL without failing a compile.
 * {@code LiveMapAndLogOutputVerificationTest} guards against that.
 */
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
