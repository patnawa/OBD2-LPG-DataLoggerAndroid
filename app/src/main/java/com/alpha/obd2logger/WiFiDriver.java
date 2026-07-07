package com.alpha.obd2logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public final class WiFiDriver extends ElmDriver {
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    /** True between TCP connect and end of initializeElm327() — used to give
     *  ATZ/ATI/AT@1 a longer per-read timeout since ELM327 needs ~500ms-1.5s
     *  to produce its first prompt after a reset. */
    private volatile boolean initializing = false;

    public WiFiDriver(LoggerConfig config) {
        super(config);
    }

    @Override
    public boolean connect() {
        try {
            if (socket != null) {
                disconnect();
            }
            socket = new Socket();
            // Use connection timeout for the TCP handshake (vLinker MC WiFi
            // through Android hotspot can take 2-5s — previous default 2s was
            // sometimes too tight; the per-read SO_TIMEOUT below covers steady
            // state). We bump to 5s as a safer floor for slow networks.
            int connectTimeoutMs = Math.max(config.connectionTimeoutMs, 5000);
            socket.connect(new InetSocketAddress(config.wifiIp, config.wifiPort), connectTimeoutMs);
            // Give the ELM327/vLinker time to finish booting after TCP accept
            // before we start banging ATZ at it. Without this, ATZ races the
            // boot sequence and the prompt `>` arrives after our read timeout.
            try { Thread.sleep(500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            // Set a longer SO_TIMEOUT for the initial probe (ATZ / ATI / AT@1)
            // — ELM327 reset can take 500ms-1.5s to produce its first `>`.
            // We restore a tighter timeout in steady state once init is done.
            int initSoTimeout = Math.max(config.connectionTimeoutMs, 2000);
            socket.setSoTimeout(initSoTimeout);
            initializing = true;
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            connected = initializeElm327();
            if (connected) {
                // Init succeeded — tighten per-read timeout so a dead adapter
                // doesn't freeze the whole loop, but still long enough for
                // multi-frame responses (VIN, DTC list, Mode 06).
                int steadySoTimeout = Math.max(config.connectionTimeoutMs / 4, 500);
                socket.setSoTimeout(steadySoTimeout);
            } else {
                disconnect();
            }
            return connected;
        } catch (Exception e) {
            android.util.Log.e("WiFiDriver", "connect failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            disconnect();
            return false;
        } finally {
            initializing = false;
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
                int b;
                try {
                    b = inputStream.read();
                } catch (SocketTimeoutException timeout) {
                    // If we already got a partial response and are in steady
                    // state, treat the timeout as end-of-message. During init
                    // (ATZ / ATI / AT@1), keep waiting — ELM327 may need up
                    // to a full second to produce the first prompt after reset.
                    if (!initializing && response.length() > 0) {
                        break;
                    }
                    continue;
                }
                if (b < 0) {
                    connected = false;
                    break;
                }
                char ch = (char) b;
                response.append(ch);
                if (ch == '>') {
                    break;
                }
            }
            return response.toString();
        } catch (IOException e) {
            android.util.Log.w("WiFiDriver", "sendCommand '" + command + "' failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return "";
        } finally {
            commandLock.unlock();
        }
    }
}
