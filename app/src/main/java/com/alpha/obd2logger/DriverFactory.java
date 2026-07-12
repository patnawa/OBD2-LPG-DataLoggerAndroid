package com.alpha.obd2logger;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.util.List;
import java.util.Set;

public final class DriverFactory {
    private static Context appContext;

    private DriverFactory() {
    }

    public static void setAppContext(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static BaseDriver create(LoggerConfig config) {
        switch (config.transportMode) {
            case WIFI:
                return new WiFiDriver(config);
            case SERIAL:
                return new SerialDriver(config);
            case BLE:
                BleDriver bleDriver = new BleDriver(config);
                if (appContext != null) {
                    bleDriver.setContext(appContext);
                }
                return bleDriver;
            case USB:
                UsbDriver usbDriver = new UsbDriver(config);
                if (appContext != null) {
                    usbDriver.setContext(appContext);
                }
                return usbDriver;
            case AUTO:
                return createAutoDriver(config);
            case SIM:
            default:
                return new SimulationDriver(config);
        }
    }

    private static BaseDriver createAutoDriver(LoggerConfig config) {
        if (appContext != null) {
            UsbDriver usbDriver = new UsbDriver(config);
            usbDriver.setContext(appContext);
            if (usbDriver.connect()) {
                return usbDriver;
            }
        }

        WiFiDriver wifiDriver = new WiFiDriver(config);
        if (wifiDriver.connect()) {
            return wifiDriver;
        }

        if (config.bluetoothDevice != null) {
            SerialDriver serialDriver = new SerialDriver(config);
            if (serialDriver.connect()) {
                return serialDriver;
            }
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return new SimulationDriver(config);
        }

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) {
                    LoggerConfig candidate = copyConfig(config);
                    candidate.bluetoothDevice = device;
                    SerialDriver serialDriver = new SerialDriver(candidate);
                    if (serialDriver.connect()) {
                        return serialDriver;
                    }
                }
            }
        } catch (SecurityException se) {
            android.util.Log.e("OBD2Logger", "SecurityException reading bonded devices during candidate check", se);
        }

        return new SimulationDriver(config);
    }

    private static LoggerConfig copyConfig(LoggerConfig source) {
        LoggerConfig target = new LoggerConfig();
        target.transportMode = source.transportMode;
        target.port = source.port;
        target.baud = source.baud;
        target.bluetoothDevice = source.bluetoothDevice;
        target.wifiIp = source.wifiIp;
        target.wifiPort = source.wifiPort;
        target.vehicleBrand = source.vehicleBrand;
        target.fuelMode = source.fuelMode;
        target.obdProtocol = source.obdProtocol;
        target.vin = source.vin;
        target.sampleIntervalMs = source.sampleIntervalMs;
        target.warmupCoolantC = source.warmupCoolantC;
        target.lpgOnlyMode = source.lpgOnlyMode;
        target.connectionTimeoutMs = source.connectionTimeoutMs;
        target.maxRetries = source.maxRetries;
        target.enableApiServer = source.enableApiServer;
        target.fordMsCanEnabled = source.fordMsCanEnabled;
        target.showTurboBoost = source.showTurboBoost;
        target.showFuelConsumption = source.showFuelConsumption;
        target.dpfMonitorEnabled = source.dpfMonitorEnabled;
        target.showAirDensity = source.showAirDensity;
        target.engineDisplacementCC = source.engineDisplacementCC;
                target.engineDisplacementUserSet = source.engineDisplacementUserSet;
                target.ratedRPM = source.ratedRPM;
        target.customPidsEnabled = source.customPidsEnabled;
        target.context = source.context;
        return target;
    }
}
