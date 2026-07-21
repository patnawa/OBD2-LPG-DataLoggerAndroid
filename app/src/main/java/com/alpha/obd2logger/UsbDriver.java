package com.alpha.obd2logger;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;

public class UsbDriver extends ElmDriver {
    private static final String TAG = "UsbDriver";
    private Context context;
    private UsbSerialPort usbSerialPort;
    private UsbDeviceConnection connection;
    // Guarded by commandLock; no more than one resend follows a corrupt frame.
    private boolean recoveryRetryInProgress;

    public UsbDriver(LoggerConfig config) {
        super(config);
    }

    public void setContext(Context context) {
        this.context = context;
    }

    @Override
    public boolean connect() {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot connect to USB.");
            return false;
        }

        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            Log.e(TAG, "No USB serial drivers found.");
            return false;
        }

        UsbSerialDriver driver = availableDrivers.get(0);
        if (!manager.hasPermission(driver.getDevice())) {
            Log.e(TAG, "No permission to access USB device.");
            return false;
        }

        connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device.");
            return false;
        }

        if (driver.getPorts().isEmpty()) {
            Log.e(TAG, "No ports found on USB device.");
            connection.close();
            connection = null;
            return false;
        }
        usbSerialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
        try {
            usbSerialPort.open(connection);
            usbSerialPort.setParameters(config.baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            
            connected = initializeElm327();
            if (connected) {
                resetLiveness();
            } else {
                disconnect();
            }
            return connected;
        } catch (IOException e) {
            Log.e(TAG, "Error opening USB serial port", e);
            disconnect();
            return false;
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException ignored) {}
            usbSerialPort = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Override
    public Double queryPid(PIDDefinition pidDef) {
        String response = sendCommand(pidDef.getService() + pidDef.getPidHex());
        return queryPidResponse(pidDef, response);
    }

    @Override
    protected String sendCommandImpl(String command) {
        if (usbSerialPort == null) {
            return "";
        }
        commandLock.lock();
        try {
            // 1. Flush any leftover data from previous command before sending.
            //    purgeHwBuffers is not supported by every driver (CDC-ACM throws
            //    UnsupportedOperationException) — fall back to a short manual drain.
            try {
                usbSerialPort.purgeHwBuffers(true, true);
            } catch (Exception purgeUnsupported) {
                try {
                    byte[] drain = new byte[256];
                    while (usbSerialPort.read(drain, 50) > 0) {
                        // discard stale bytes from a previous response
                    }
                } catch (Exception ignored) {
                }
            }

            // 2. Send command with CR (ELM327 expects \r as command terminator)
            usbSerialPort.write((command + "\r").getBytes(), 1000);

            // 3. Small delay to let ELM327 process the command before reading
            Thread.sleep(20);

            // 4. Read response — use longer per-read timeout (200ms) and overall
            //    deadline of connectionTimeoutMs (default 2000ms).
            //    The ELM327 signals end-of-response with '>' prompt.
            StringBuilder response = new StringBuilder();
            long deadline = System.currentTimeMillis() + config.connectionTimeoutMs;
            byte[] buffer = new byte[256];

            while (System.currentTimeMillis() < deadline) {
                int len = usbSerialPort.read(buffer, 200);
                if (len > 0) {
                    boolean overflow = false;
                    for (int i = 0; i < len; i++) {
                        char ch = (char) buffer[i];
                        if (!ElmResponseSanitizer.appendValidated(response, buffer[i])) {
                            response.append("BUFFER FULL>");
                            overflow = true;
                            break;
                        }
                        if (ch == '>') {
                            return finishResponse(command, response.toString());
                        }
                    }
                    if (overflow) break;
                }
            }
            return finishResponse(command, response.toString());
        } catch (IOException e) {
            connected = false;
            trackResponseLiveness("");
            Log.e(TAG, "Error sending USB command", e);
            return "";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            commandLock.unlock();
        }
    }

    /** Applies the same bounded recovery policy as Bluetooth and Wi-Fi transports. */
    private String finishResponse(String command, String rawResponse) {
        String result = ElmResponseSanitizer.sanitize(rawResponse);
        if (ElmResponseSanitizer.needsTransportRecovery(result) && !recoveryRetryInProgress) {
            recoveryRetryInProgress = true;
            try {
                try {
                    usbSerialPort.purgeHwBuffers(true, true);
                } catch (Exception ignored) {
                    // Not all CDC-ACM implementations expose hardware purge.
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
    }
}
