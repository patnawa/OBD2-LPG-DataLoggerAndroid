package com.alpha.obd2logger;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

public final class LoggerConfig {
    public TransportMode transportMode;
    public String port;
    public int baud;
    public BluetoothDevice bluetoothDevice;
    public String wifiIp;
    public int wifiPort;
    public String vehicleBrand;
    /**
     * Volatile: written from the UI thread when the user switches fuel and read
     * from the polling worker on every cycle. Without it the worker could run on
     * a stale value indefinitely, filing LPG samples into the petrol map.
     */
    public volatile FuelMode fuelMode;
    public ObdProtocol obdProtocol;
    public String vin;
    public long sampleIntervalMs;
    public double warmupCoolantC;
    public boolean lpgOnlyMode;
    public int connectionTimeoutMs;
    public int maxRetries;
    public boolean enableApiServer;
    /** Required bearer/query credential for every telemetry API endpoint except /api/ping. */
    public String apiAccessToken;
    /**
     * Compatibility field for the former Ford-only preference. It now gates
     * safe Smart DTC plan expansion and never asserts Ford MS-CAN pin routing.
     * Read by logger workers and updated from the UI.
     */
    public volatile boolean fordMsCanEnabled;

    // ── Feature toggles ──────────────────────────────────────
    /** Compute + display turbo boost pressure (MAP - Baro) */
    public boolean showTurboBoost = true;
    /** Compute + display fuel consumption (km/L, L/100km) */
    public boolean showFuelConsumption = true;
    /** Poll DPF PIDs for diesel vehicles */
    public boolean dpfMonitorEnabled = false;
    /** Compute + display air density (AAD/MAD/BAD) — AeroDensity Intelligence */
    public boolean showAirDensity = true;
    /** Engine displacement in cc — required for VE/TMF/PDI calculations */
    public int engineDisplacementCC = 1998; // default ~2.0L
    /**
     * True once the user has confirmed / edited displacement.
     * Advanced VE/TMF/PDI samples are still computed when false, but stamped status="assumed".
     */
    public boolean engineDisplacementUserSet = false;
    /** Rated peak-power RPM — used for Power Density Index normalization */
    public int ratedRPM = 6000;
    /** Load user-defined custom PIDs from SharedPreferences */
    public boolean customPidsEnabled = false;
    /** Application context — needed by WiFiDriver for ConnectivityManager
     *  to bind sockets to the WiFi network (bypasses missing route when
     *  gateway is disabled for mobile data + WiFi simultaneous use). */
    public transient Context context;

    public LoggerConfig() {
        this.transportMode = TransportMode.SIM;
        this.port = "";
        this.baud = 115200;
        this.wifiIp = "192.168.0.10";
        this.wifiPort = 35000;
        this.vehicleBrand = "auto";
        this.fuelMode = FuelMode.PETROL;
        this.obdProtocol = ObdProtocol.AUTO;
        this.vin = "UNKNOWN";
        this.sampleIntervalMs = 500;
        this.warmupCoolantC = 85.0;
        this.lpgOnlyMode = true;
        this.connectionTimeoutMs = 2000;
        this.maxRetries = 3;
        this.enableApiServer = false;
        this.apiAccessToken = "";
        this.fordMsCanEnabled = false;
    }

    /**
     * Keep an explicit user-selected brand intact, but replace an automatic
     * placeholder once a VIN produces a trustworthy manufacturer result.
     */
    public boolean applyDetectedVehicleBrand(String detectedBrand) {
        if (!isAutoVehicleBrand(vehicleBrand) || detectedBrand == null) return false;
        String normalized = detectedBrand.trim();
        if (normalized.isEmpty() || "unknown".equalsIgnoreCase(normalized)) return false;
        vehicleBrand = normalized;
        return true;
    }

    public static boolean isAutoVehicleBrand(String brand) {
        return brand == null || brand.trim().isEmpty()
                || "auto".equalsIgnoreCase(brand.trim())
                || "unknown".equalsIgnoreCase(brand.trim());
    }
}
