package com.alpha.obd2logger;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;

import java.util.List;
import java.util.Set;

public final class DriverFactory {
    private static Context appContext;
    private static volatile String lastResolvedTransport = "Not connected";
    private static volatile String lastProbeSummary = "No connection attempt yet";

    private DriverFactory() {
    }

    public static void setAppContext(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static BaseDriver create(LoggerConfig config) {
        if (config == null || config.transportMode == null) {
            lastProbeSummary = "Invalid connection configuration";
            return new SimulationDriver(config != null ? config : new LoggerConfig());
        }
        switch (config.transportMode) {
            case WIFI:
                lastResolvedTransport = "Wi-Fi TCP";
                lastProbeSummary = "Direct Wi-Fi connection selected";
                return new WiFiDriver(config);
            case SERIAL:
                lastResolvedTransport = "Bluetooth SPP";
                lastProbeSummary = "Direct Bluetooth SPP connection selected";
                return new SerialDriver(config);
            case BLE:
                lastResolvedTransport = "Bluetooth BLE";
                lastProbeSummary = "Direct Bluetooth BLE connection selected";
                BleDriver bleDriver = new BleDriver(config);
                if (appContext != null) {
                    bleDriver.setContext(appContext);
                }
                return bleDriver;
            case USB:
                lastResolvedTransport = "USB Serial";
                lastProbeSummary = "Direct USB Serial connection selected";
                UsbDriver usbDriver = new UsbDriver(config);
                if (appContext != null) {
                    usbDriver.setContext(appContext);
                }
                return usbDriver;
            case AUTO:
                return createAutoDriver(config);
            case SIM:
            default:
                lastResolvedTransport = "Simulation";
                lastProbeSummary = "Simulation mode selected";
                return new SimulationDriver(config);
        }
    }

    private static BaseDriver createAutoDriver(LoggerConfig config) {
        StringBuilder trace = new StringBuilder("AUTO: ");
        if (appContext != null) {
            UsbDriver usbDriver = new UsbDriver(config);
            usbDriver.setContext(appContext);
            if (usbDriver.connect()) {
                lastResolvedTransport = "USB Serial";
                lastProbeSummary = trace.append("USB connected").toString();
                return usbDriver;
            }
            trace.append("USB unavailable → ");
        }

        WiFiDriver wifiDriver = new WiFiDriver(config);
        if (wifiDriver.connect()) {
            lastResolvedTransport = "Wi-Fi TCP";
            lastProbeSummary = trace.append("Wi-Fi connected").toString();
            return wifiDriver;
        }
        trace.append("Wi-Fi unavailable → ");

        if (config.bluetoothDevice != null) {
            BaseDriver bluetooth = tryBluetoothCandidate(config, config.bluetoothDevice);
            if (bluetooth != null) {
                lastResolvedTransport = bluetooth instanceof BleDriver ? "Bluetooth BLE" : "Bluetooth SPP";
                lastProbeSummary = trace.append(lastResolvedTransport).append(" connected").toString();
                return bluetooth;
            }
            trace.append("selected Bluetooth unavailable → ");
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            lastResolvedTransport = "Simulation fallback";
            lastProbeSummary = trace.append("Bluetooth unavailable; simulation started").toString();
            return new SimulationDriver(config);
        }

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                int attempts = 0;
                for (BluetoothDevice device : bonded) {
                    if (config.bluetoothDevice != null && config.bluetoothDevice.equals(device)) continue;
                    if (attempts++ >= 3) break; // bound AUTO probe time
                    LoggerConfig candidate = copyConfig(config);
                    candidate.bluetoothDevice = device;
                    BaseDriver bluetooth = tryBluetoothCandidate(candidate, device);
                    if (bluetooth != null) {
                        lastResolvedTransport = bluetooth instanceof BleDriver ? "Bluetooth BLE" : "Bluetooth SPP";
                        lastProbeSummary = trace.append(lastResolvedTransport).append(" connected").toString();
                        return bluetooth;
                    }
                }
            }
        } catch (SecurityException se) {
            android.util.Log.e("OBD2Logger", "SecurityException reading bonded devices during candidate check", se);
        }

        lastResolvedTransport = "Simulation fallback";
        lastProbeSummary = trace.append("hardware probe failed; simulation started").toString();
        return new SimulationDriver(config);
    }

    public static String getLastResolvedTransport() { return lastResolvedTransport; }
    public static String getLastProbeSummary() { return lastProbeSummary; }
    public static void markConnectionFailure(String reason) {
        lastProbeSummary = lastResolvedTransport + " failed"
                + (reason == null || reason.isEmpty() ? "" : " • " + reason);
    }

    /** Try both classic SPP and BLE for one adapter; many Vgate devices expose only one. */
    private static BaseDriver tryBluetoothCandidate(LoggerConfig base, BluetoothDevice device) {
        LoggerConfig candidate = copyConfig(base);
        candidate.bluetoothDevice = device;

        SerialDriver serial = new SerialDriver(candidate);
        if (serial.connect()) return serial;

        if (appContext != null) {
            BleDriver ble = new BleDriver(candidate);
            ble.setContext(appContext);
            if (ble.connect()) return ble;
        }
        return null;
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
