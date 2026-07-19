package com.alpha.obd2logger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers the OBD protocol that a specific adapter/vehicle last locked on,
 * so the next AUTO connect can try it first instead of re-running the full
 * ELM327 bus search (which costs 5–12 s on K-line and slow diesel ECUs).
 *
 * <p>The hint is only ever used as {@code ATSP A<h>} — "automatic, try h
 * first" — so a stale hint (adapter moved to a different car) merely falls
 * back to the normal full search instead of failing the connection.
 *
 * <p>Keys are per adapter identity (Bluetooth MAC, Wi-Fi host:port, or serial
 * port name), because the adapter is the thing that travels between vehicles.
 */
final class ProtocolMemory {

    private static final String PREFS_NAME = "OBD2Prefs";
    private static final String KEY_PREFIX = "protocol_hint_v1_";

    private ProtocolMemory() {
    }

    /**
     * The remembered protocol ELM code ("6", "8", ...) for this adapter, or
     * null when nothing was stored or no context/identity is available.
     */
    static String loadHint(LoggerConfig config) {
        SharedPreferences prefs = prefs(config);
        String key = key(config);
        if (prefs == null || key == null) return null;
        String hint = prefs.getString(key, null);
        // Never hint AUTO ("0") — that would be a no-op — and ignore anything
        // that is not a single ELM protocol digit.
        if (hint == null || hint.length() != 1 || "0".equals(hint)) return null;
        return hint;
    }

    /** Persist the locked protocol code for this adapter. AUTO is ignored. */
    static void saveHint(LoggerConfig config, ObdProtocol lockedProtocol) {
        if (lockedProtocol == null || lockedProtocol == ObdProtocol.AUTO) return;
        SharedPreferences prefs = prefs(config);
        String key = key(config);
        if (prefs == null || key == null) return;
        prefs.edit().putString(key, lockedProtocol.getElmValue()).apply();
    }

    private static SharedPreferences prefs(LoggerConfig config) {
        Context context = config != null ? config.context : null;
        if (context == null) return null;
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Stable adapter identity, or null when the transport has none. */
    static String key(LoggerConfig config) {
        String identity = adapterIdentity(config);
        return identity == null ? null : KEY_PREFIX + identity;
    }

    private static String adapterIdentity(LoggerConfig config) {
        if (config == null || config.transportMode == null) return null;
        // Classic Bluetooth rides on SERIAL/AUTO with a bluetoothDevice set, so
        // prefer the device MAC whenever one exists — it is the most stable
        // identity an ELM327 dongle has.
        if (config.bluetoothDevice != null) {
            return "BT_" + config.bluetoothDevice.getAddress();
        }
        switch (config.transportMode) {
            case WIFI:
                return config.wifiIp != null
                        ? "WIFI_" + config.wifiIp + ":" + config.wifiPort
                        : null;
            case USB:
            case SERIAL:
            case AUTO:
                return config.port != null
                        ? config.transportMode.name() + "_" + config.port
                        : null;
            default:
                return null; // SIM has no bus to remember
        }
    }
}
