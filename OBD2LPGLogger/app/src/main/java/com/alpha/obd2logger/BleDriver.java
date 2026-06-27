package com.alpha.obd2logger;

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
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private final StringBuffer notifyBuffer = new StringBuffer();
    private volatile boolean notifyEnabled = false;

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
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
                        if (writeChar == null && (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                                && (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
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
                }
                notifyEnabled = true;
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null) return;
            String chunk = new String(data, java.nio.charset.StandardCharsets.US_ASCII);
            synchronized (notifyBuffer) {
                notifyBuffer.append(chunk);

                // ELM327 responses end with '>' prompt
                if (notifyBuffer.indexOf(">") != -1) {
                    String full = notifyBuffer.toString();
                    notifyBuffer.setLength(0);
                    responseQueue.offer(full);
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
        if (config.bluetoothDevice == null || context == null) {
            return false;
        }

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
            if (!connected) {
                disconnect();
            }
            return connected;
        } catch (Exception e) {
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
    protected String sendCommand(String command) {
        if (gatt == null || writeChar == null) {
            return "";
        }

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
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!gatt.writeCharacteristic(writeChar)) {
                // Write was not accepted by the GATT layer — command would be lost
                return "";
            }

            // Wait for response with '>' prompt
            String response = responseQueue.poll(config.connectionTimeoutMs, TimeUnit.MILLISECONDS);
            return response != null ? response : "";
        } catch (Exception e) {
            return "";
        }
    }

    private BluetoothGattCharacteristic findCharacteristic(BluetoothGatt g, UUID serviceUuid, UUID charUuid) {
        android.bluetooth.BluetoothGattService service = g.getService(serviceUuid);
        if (service == null) return null;
        return service.getCharacteristic(charUuid);
    }
}
