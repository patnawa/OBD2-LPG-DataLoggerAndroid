package com.alpha.obd2logger;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

// MainActivity verifies the runtime Bluetooth permission before this driver is created;
// all calls are also contained by the connection error handler for permission revocation.
@SuppressLint("MissingPermission")
public final class SerialDriver extends ElmDriver {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int MAX_COMMAND_ATTEMPTS = 2;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    // Guarded by commandLock. Exactly one recovery retry is permitted for a
    // damaged adapter response, avoiding an unbounded resend storm.
    private boolean recoveryRetryInProgress;

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
                // leaking the native Bluetooth socket resource. socket is null
                // when createRfcommSocketToServiceRecord itself threw.
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
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

    /**
     * Test seam: drive the response read loop over in-memory streams.
     *
     * <p>The prompt detection, stale-byte drain, byte validation and
     * single-retry recovery below are the subtlest code in the app and the
     * hardest to reproduce on a bench — every failure mode involves a
     * misbehaving adapter mid-drive. They are otherwise unreachable without a
     * real {@link BluetoothSocket}, which is why they went untested.
     */
    void attachStreamsForTest(InputStream in, OutputStream out) {
        this.inputStream = in;
        this.outputStream = out;
        this.connected = true;
    }

    @Override
    protected String sendCommandImpl(String command) {
        if (outputStream == null || inputStream == null) {
            return "";
        }
        commandLock.lock();
        try {
            // Drain any stale bytes left by a previous timed-out response so
            // old data cannot be mistaken for this command's reply (which
            // would permanently desync request/response pairing).
            while (inputStream.available() > 0 && inputStream.read() >= 0) {
                // discard
            }

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
                        if (!ElmResponseSanitizer.appendValidated(response, b)) {
                            response.append("BUFFER FULL>");
                            break;
                        }
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
            String result = ElmResponseSanitizer.sanitize(response.toString());
            if (ElmResponseSanitizer.needsTransportRecovery(result)
                    && !recoveryRetryInProgress) {
                recoveryRetryInProgress = true;
                try {
                    drainStaleBytes(100L);
                    return sendCommand(command);
                } finally {
                    recoveryRetryInProgress = false;
                }
            }
            if (ElmResponseSanitizer.needsTransportRecovery(result)) {
                // Persistent BUFFER FULL / STOPPED / CAN ERROR indicates a
                // broken adapter session, not an unsupported PID.
                connected = false;
            }
            // Track liveness: consecutive empty responses mean the BT
            // socket is half-open and needs reconnecting.
            trackResponseLiveness(result);
            return result;
        } catch (IOException failure) {
            connected = false;
            trackResponseLiveness("");
            android.util.Log.w("OBD2Logger", "Bluetooth command failed", failure);
            return "";
        } finally {
            commandLock.unlock();
        }
    }

    /**
     * Read and discard any bytes currently in the socket buffer (ATZ boot
     * banner, late tail of a timed-out response). Bounded by {@code maxMillis}.
     *
     * Holds the command lock so it cannot race with concurrent sendCommand.
     */
    @Override
    protected void drainStaleBytes(long maxMillis) {
        if (inputStream == null) {
            return;
        }
        commandLock.lock();
        try {
            long deadline = System.currentTimeMillis() + maxMillis;
            int discarded = 0;
            try {
                while (System.currentTimeMillis() < deadline && inputStream.available() > 0) {
                    if (inputStream.read() < 0) {
                        connected = false;
                        break;
                    }
                    discarded++;
                }
            } catch (IOException ioe) {
                android.util.Log.w("OBD2Logger", "drainStaleBytes error: " + ioe.getMessage());
            }
            if (discarded > 0) {
                android.util.Log.i("OBD2Logger", "drained " + discarded + " stale byte(s)");
            }
        } finally {
            commandLock.unlock();
        }
    }
}
