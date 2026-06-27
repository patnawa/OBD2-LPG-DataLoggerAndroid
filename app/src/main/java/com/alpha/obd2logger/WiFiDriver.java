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
            socket.setSoTimeout(250);
            socket.connect(new InetSocketAddress(config.wifiIp, config.wifiPort), config.connectionTimeoutMs);
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            connected = initializeElm327();
            if (!connected) {
                disconnect();
            }
            return connected;
        } catch (Exception ignored) {
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
                    // Prevent busy-waiting: setSoTimeout(250) means we spin every
                    // 250ms for the entire connectionTimeoutMs window otherwise.
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
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
        } catch (IOException ignored) {
            return "";
        }
    }
}
