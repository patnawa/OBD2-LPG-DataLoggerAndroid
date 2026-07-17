package com.alpha.obd2logger;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * BLE (Bluetooth Low Energy) driver for modern ELM327 OBD2 adapters.
 *
 * Implements the common ELM327 BLE GATT profile:
 *   Service:  0000fff0-0000-1000-8000-00805f9b34fb
 *   Write:    0000fff2-0000-1000-8000-00805f9b34fb
 *   Notify:   0000fff1-0000-1000-8000-00805f9b34fb
 *
 * Also tries alternative UUIDs used by some adapter vendors.
 *
 * sendCommand is synchronous: it writes the command and blocks on a
 * LinkedBlockingQueue that is fed by the GATT notification callback.
 */
@SuppressLint("MissingPermission")
public final class BleDriver extends ElmDriver {

    // Common ELM327 BLE UUIDs
    private static final UUID SERVICE_UUID  = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_UUID    = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY_UUID   = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Alternative UUIDs (some OBDLink / Vgate adapters)
    private static final UUID ALT_SERVICE_UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2");
    private static final UUID ALT_WRITE_UUID   = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f");
    private static final UUID ALT_NOTIFY_UUID  = UUID.fromString("bef8d6c8-9c21-4c9e-b632-bd58c1009f9f");

    private Context context;
    private volatile BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    // Access is explicitly synchronized at callback/read boundaries, so an
    // unsynchronised builder avoids StringBuffer's redundant per-call lock.
    private final StringBuilder notifyBuffer = new StringBuilder();
    private volatile boolean notifyEnabled = false;
    // Accessed only while commandLock is held by sendCommand().
    private boolean recoveryRetryInProgress;

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (g != gatt) {
                try { g.close(); } catch (Exception ignored) {}
                return;
            }
            if (!hasBluetoothConnectPermission()) {
                connected = false;
                responseQueue.offer("");
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                notifyEnabled = false;
                responseQueue.offer(""); // wake a command waiting for its prompt
                // Release the GATT client — without close() the BluetoothGatt
                // instance leaks until the next explicit disconnect().
                try {
                    g.close();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (g != gatt || status != BluetoothGatt.GATT_SUCCESS) return;
            if (!hasBluetoothConnectPermission()) return;
            // Try standard service first
            writeChar = findCharacteristic(g, SERVICE_UUID, WRITE_UUID);
            notifyChar = findCharacteristic(g, SERVICE_UUID, NOTIFY_UUID);

            // Fallback to alternative UUIDs
            if (writeChar == null) {
                writeChar = findCharacteristic(g, ALT_SERVICE_UUID, ALT_WRITE_UUID);
            }
            if (notifyChar == null) {
                notifyChar = findCharacteristic(g, ALT_SERVICE_UUID, ALT_NOTIFY_UUID);
            }

            // Try scanning all services for any write/notify pair
            if (writeChar == null || notifyChar == null) {
                for (android.bluetooth.BluetoothGattService svc : g.getServices()) {
                    for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        int props = ch.getProperties();
                        if (writeChar == null && ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                                || (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)) {
                            writeChar = ch;
                        }
                        if (notifyChar == null && (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            notifyChar = ch;
                        }
                    }
                }
            }

            if (notifyChar != null) {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor cccd = notifyChar.getDescriptor(CCCD_UUID);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                    // notifyEnabled is set in onDescriptorWrite() after the
                    // descriptor write is confirmed — setting it here would be
                    // premature because writeDescriptor is asynchronous and may
                    // fail, leaving notifications not actually enabled while
                    // connect() proceeds thinking they are.
                } else {
                    // No CCCD descriptor — notifications can't be enabled remotely.
                    // Mark as enabled anyway so connect() doesn't block forever;
                    // local notification state was set above.
                    notifyEnabled = true;
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (g != gatt) return;
            if (descriptor.getUuid().equals(CCCD_UUID)) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    notifyEnabled = true;
                }
                // On failure, notifyEnabled stays false and connect() will
                // time out and disconnect — which is the correct behavior.
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (g != gatt) return;
            byte[] data = characteristic.getValue();
            if (data == null) return;
            synchronized (notifyBuffer) {
                for (byte datum : data) {
                    if (!ElmResponseSanitizer.appendValidated(notifyBuffer, datum)) {
                        // A bounded reset prevents a noisy notification stream
                        // from retaining data indefinitely. The waiter receives
                        // an explicit recovery marker instead of timing out.
                        notifyBuffer.setLength(0);
                        responseQueue.offer("BUFFER FULL>");
                        return;
                    }
                }
                // ELM327 responses end with a prompt. Preserve any late tail
                // after the first prompt so it can be drained before the next
                // request rather than being merged into this response.
                int prompt = notifyBuffer.indexOf(">");
                if (prompt >= 0) {
                    String full = notifyBuffer.substring(0, prompt + 1);
                    notifyBuffer.delete(0, prompt + 1);
                    responseQueue.offer(ElmResponseSanitizer.sanitize(full));
                }
            }
        }
    };

    public BleDriver(LoggerConfig config) {
        super(config);
    }

    public void setContext(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    @Override
    public boolean connect() {
        if (config.bluetoothDevice == null || context == null || !hasBluetoothConnectPermission()) {
            return false;
        }

        // Clean up any previous session first — otherwise the old BluetoothGatt
        // leaks (connectGatt below overwrites the field without closing it) and
        // stale notifyEnabled/writeChar/buffer state from the prior connection
        // can make the wait loop below pass without a real link.
        disconnect();

        try {
            gatt = config.bluetoothDevice.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);

            // Wait for services to be discovered and notifications enabled
            long deadline = System.currentTimeMillis() + config.connectionTimeoutMs + 3000;
            while (!notifyEnabled && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            if (!notifyEnabled || writeChar == null) {
                disconnect();
                return false;
            }

            connected = initializeElm327();
            if (connected) {
                resetLiveness();
            } else {
                disconnect();
            }
            return connected;
        } catch (Exception e) {
            android.util.Log.e("OBD2Logger", "Bluetooth BLE connect error: " + e.getMessage(), e);
            disconnect();
            return false;
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        notifyEnabled = false;
        responseQueue.clear();
        synchronized (notifyBuffer) {
            notifyBuffer.setLength(0);
        }
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        writeChar = null;
        notifyChar = null;
    }

    @Override
    public Double queryPid(PIDDefinition pidDef) {
        String response = sendCommand(pidDef.getService() + pidDef.getPidHex());
        return queryPidResponse(pidDef, response);
    }

    @Override
    protected int getTransportPidChunkLimit() {
        // "01 03 04 05 06 07" plus CR exceeds the common 20-byte ATT
        // payload. Five PIDs remain below it even before MTU negotiation.
        return 5;
    }

    @Override
    protected String sendCommand(String command) {
        if (gatt == null || writeChar == null || !hasBluetoothConnectPermission()) {
            return "";
        }
        commandLock.lock();
        try {
            // Clear any stale response
            responseQueue.clear();
            synchronized (notifyBuffer) {
                notifyBuffer.setLength(0);
            }

            try {
                // BLE has MTU limits — split long commands if needed
                byte[] data = (command + "\r").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                // setValue() is deprecated in API 33+ but remains the most compatible
                // approach for older API levels; the GATT callback relies on it here.
                writeChar.setValue(data);
                int props = writeChar.getProperties();
                if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                    writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                } else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                }
                if (!gatt.writeCharacteristic(writeChar)) {
                    // Write was not accepted by the GATT layer — command would be lost
                    return "";
                }

                // Wait for response with '>' prompt
                String response = responseQueue.poll(config.connectionTimeoutMs, TimeUnit.MILLISECONDS);
                String result = ElmResponseSanitizer.sanitize(response != null ? response : "");
                if (ElmResponseSanitizer.needsTransportRecovery(result)
                        && !recoveryRetryInProgress) {
                    recoveryRetryInProgress = true;
                    try {
                        responseQueue.clear();
                        synchronized (notifyBuffer) {
                            notifyBuffer.setLength(0);
                        }
                        return sendCommand(command);
                    } finally {
                        recoveryRetryInProgress = false;
                    }
                }
                if (ElmResponseSanitizer.needsTransportRecovery(result)) {
                    connected = false;
                }
                trackResponseLiveness(result);
                return result;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                trackResponseLiveness("");
                return "";
            }
        } finally {
            commandLock.unlock();
        }
    }

    private BluetoothGattCharacteristic findCharacteristic(BluetoothGatt g, UUID serviceUuid, UUID charUuid) {
        if (!hasBluetoothConnectPermission()) return null;
        android.bluetooth.BluetoothGattService service = g.getService(serviceUuid);
        if (service == null) return null;
        return service.getCharacteristic(charUuid);
    }

    private boolean hasBluetoothConnectPermission() {
        return context != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED);
    }
}
