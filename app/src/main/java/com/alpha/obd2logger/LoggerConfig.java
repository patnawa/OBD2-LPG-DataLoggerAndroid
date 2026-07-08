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
    public FuelMode fuelMode;
    public ObdProtocol obdProtocol;
    public String vin;
    public long sampleIntervalMs;
    public double warmupCoolantC;
    public boolean lpgOnlyMode;
    public int connectionTimeoutMs;
    public int maxRetries;
    public boolean enableApiServer;
    public boolean fordMsCanEnabled;
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
        this.fordMsCanEnabled = false;
    }
}
