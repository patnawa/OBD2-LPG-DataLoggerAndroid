package com.alpha.obd2logger;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class SerialDriver extends ElmDriver {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    public SerialDriver(LoggerConfig config) {
        super(config);
    }

    @Override
    public boolean connect() {
        try {
            if (config.bluetoothDevice == null && (config.port == null || config.port.trim().isEmpty())) {
                return false;
            }
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                return false;
            }

            if (socket != null) {
                disconnect();
            }

            BluetoothDevice device = config.bluetoothDevice;
            if (device == null) {
                device = bluetoothAdapter.getRemoteDevice(config.port.trim());
            }

            // Cancel discovery before connecting as recommended by Android SDK
            try {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
            } catch (SecurityException se) {
                android.util.Log.w("OBD2Logger", "Cannot cancel discovery: missing permission", se);
            }

            // Attempt RFCOMM socket connection with reflection fallback
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
            } catch (IOException e) {
                android.util.Log.w("OBD2Logger", "Standard RFCOMM connection failed, trying reflection fallback...", e);
                // Close the failed socket before creating a new one to avoid
                // leaking the native Bluetooth socket resource.
                try { socket.close(); } catch (IOException ignored) {}
                try {
                    socket = (BluetoothSocket) device.getClass().getMethod("createRfcommSocket", int.class).invoke(device, 1);
                    socket.connect();
                } catch (Exception e2) {
                    android.util.Log.e("OBD2Logger", "Bluetooth fallback connection failed", e2);
                    throw e; // throw original IOException to outer catch
                }
            }

            // Note: BluetoothSocket does not support setSoTimeout() — inputStream.read()
            // blocks until data arrives, the ELM327 '>' prompt is received, or the stream
            // is closed (returns -1 or throws IOException).
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            connected = initializeElm327();
            if (connected) {
                resetLiveness();
            } else {
                disconnect();
            }
            return connected;
        } catch (Exception e) {
            android.util.Log.e("OBD2Logger", "Bluetooth SPP connect error: " + e.getMessage(), e);
            disconnect();
            return false;
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        inputStream = null;
        outputStream = null;
        socket = null;
    }

    @Override
    public Double queryPid(PIDDefinition pidDef) {
        String response = sendCommand(pidDef.getService() + pidDef.getPidHex());
        return queryPidResponse(pidDef, response);
    }

    @Override
    protected String sendCommand(String command) {
        if (outputStream == null || inputStream == null) {
            return "";
        }
        commandLock.lock();
        try {
            outputStream.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();

            StringBuilder response = new StringBuilder();
            long deadline = System.currentTimeMillis() + config.connectionTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (inputStream.available() > 0) {
                        int b = inputStream.read();
                        if (b < 0) {
                            connected = false;
                            break;
                        }
                        char ch = (char) b;
                        response.append(ch);
                        if (ch == '>') {
                            break;
                        }
                    } else {
                        Thread.sleep(10);
                    }
                } catch (IOException ioe) {
                    // Stream is closed or broken — stop spinning and bail out
                    connected = false;
                    break;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            String result = response.toString();
            // Track liveness: consecutive empty responses mean the BT
            // socket is half-open and needs reconnecting.
            trackResponseLiveness(result);
            return result;
        } catch (IOException ignored) {
            return "";
        } finally {
            commandLock.unlock();
        }
    }
}
