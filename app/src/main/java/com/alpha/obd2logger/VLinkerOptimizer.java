package com.alpha.obd2logger;

import android.util.Log;

/**
 * Device-specific optimizations for vLinker OBD2 adapters.
 *
 * Reverse-engineered from firmware analysis:
 *
 * vLinker FS USB (MIC3322, firmware v2.3.04):
 *   - ELM327 v2.3 fully compatible (AT command set v2.3)
 *   - USB CDC interface (FTDI/CDC21228 driver)
 *   - CAN baud up to 1 Mbps
 *   - Parallel transmission mechanism (27ms savings per 4K at 3Mbps)
 *   - OBD command interruption latency: 990us/4K
 *   - Supports: ATFT, ATFThh, ATFCSM8/9/A, VTVLCW
 *   - SWGP variables: DRB, BOOST, HEX, KWF0/1
 *   - PWM 83.3Kbps, VPW 41.6Kbps, KWP1281
 *   - Buffer: "BUFFER SMALL" instead of "BUFFER FULL"
 *
 * vLinker MC WiFi (MIC3313, firmware v2.2.92):
 *   - ELM327 v2.3 compatible
 *   - WiFi + BLE/BT3.0 dual mode
 *   - 48-group pass filter (vs 8 on generic ELM327)
 *   - VLRD, VLSP, VTVLCW commands
 *   - SWGPGR1 optimized formatted character response speed
 *   - CAN controller pre-buffering mechanism
 *   - OBD request up to 1024 Hex bytes (2048 ASCII)
 *   - UART_SLEEP, PWR_CTRL LOW/HIGH power modes
 *   - WiFi config: AT+WEBU, AT+CFGTF, AT+WAP
 *   - BT: AT+SetBtMode, AT+EnDisc, AT+EnterSleep, AT+TpBaud
 *   - Supports 7Fxx78 unlimited reply lines
 *   - Max incoming numeric chars: 129 bytes (ATNL/ATAL)
 */
public final class VLinkerOptimizer {
    private static final String TAG = "VLinkerOpt";

    /**
     * Device type detected from initialization response.
     */
    public enum DeviceType {
        VLINKER_FS_USB,     // MIC3322, USB CDC
        VLINKER_MC_WIFI,    // MIC3313, WiFi+BLE
        VLINKER_MC_BT,      // MIC3313, Bluetooth
        GENERIC_ELM327,     // Unknown/generic clone
        UNKNOWN
    }

    private VLinkerOptimizer() {
    }

    /**
     * Detect the vLinker device type by sending identification commands.
     * Uses AT@1 (device description) and ATI (version string).
     *
     * @param elm connected ElmDriver
     * @return detected device type, or GENERIC_ELM327 if not a vLinker
     */
    public static DeviceType detectDevice(ElmDriver elm) {
        if (elm == null || !elm.isConnected()) {
            return DeviceType.UNKNOWN;
        }

        try {
            // AT@1 returns device description on vLinker devices
            String desc = elm.sendCommandRaw("AT@1");
            if (desc != null) {
                String upper = desc.toUpperCase();
                if (upper.contains("VLINKER") || upper.contains("Vgate".toUpperCase())) {
                    // Distinguish FS vs MC by checking transport
                    // FS is USB-only, MC has WiFi+BT
                    String version = elm.sendCommandRaw("ATI");
                    if (version != null && version.toUpperCase().contains("MIC3322")) {
                        Log.i(TAG, "Detected: vLinker FS USB (MIC3322)");
                        return DeviceType.VLINKER_FS_USB;
                    }
                    if (version != null && version.toUpperCase().contains("MIC3313")) {
                        // Distinguish WiFi vs BT by the driver class — the MC3313
                        // chip is used in both MC WiFi and MC BT adapters, but
                        // they need different timing/chunk optimizations.
                        // BT (SerialDriver/BleDriver) has higher latency than
                        // WiFi, so it must use the MC_BT profile.
                        if (elm instanceof BleDriver || elm instanceof SerialDriver) {
                            Log.i(TAG, "Detected: vLinker MC BT (MIC3313 via BT)");
                            return DeviceType.VLINKER_MC_BT;
                        }
                        Log.i(TAG, "Detected: vLinker MC WiFi (MIC3313)");
                        return DeviceType.VLINKER_MC_WIFI;
                    }
                    // vLinker but unknown chip — assume MC class
                    Log.i(TAG, "Detected: vLinker (unknown chip)");
                    return DeviceType.VLINKER_MC_WIFI;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Device detection failed: " + e.getMessage());
        }

        Log.i(TAG, "Detected: Generic ELM327");
        return DeviceType.GENERIC_ELM327;
    }

    /**
     * Apply device-specific optimizations after initial ELM327 setup.
     * This is called after initializeElm327() to fine-tune parameters.
     *
     * Optimizations are derived from firmware reverse engineering:
     * - Faster timeouts (vLinker has lower latency than generic clones)
     * - Higher baud rates for USB (parallel transmission)
     * - Optimized adaptive timing for vLinker's improved interrupt latency
     * - Enable vLinker-specific SWGP features
     *
     * @param elm connected ElmDriver
     * @param deviceType detected device type
     * @param config logger config (for transport mode, protocol, etc.)
     */
    public static void applyOptimizations(ElmDriver elm, DeviceType deviceType, LoggerConfig config) {
        if (elm == null || !elm.isConnected() || deviceType == DeviceType.UNKNOWN) {
            return;
        }

        // These optimizations only apply to vLinker devices
        if (deviceType == DeviceType.GENERIC_ELM327) {
            // For generic ELM327, use conservative settings already set in initializeElm327
            return;
        }

        try {
            switch (deviceType) {
                case VLINKER_FS_USB:
                    applyFsUsbOptimizations(elm, config);
                    break;
                case VLINKER_MC_WIFI:
                    applyMcWifiOptimizations(elm, config);
                    break;
                case VLINKER_MC_BT:
                    applyMcBtOptimizations(elm, config);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Optimization failed: " + e.getMessage());
        }
    }

    /**
     * vLinker FS USB (MIC3322) optimizations:
     *
     * From firmware v2.3.04 changelog:
     * - Parallel transmission (saves 27ms/4K at 3Mbps) → can use higher baud
     * - OBD command interruption latency: 990us/4K → faster ST (timeout)
     * - Fully ELM327 v2.3 compatible → all AT commands available
     * - CAN baud up to 1 Mbps
     * - ATFT/ATFThh for fast timeout
     * - VTVLCW for variable length CAN write
     * - SWGP: HEX mode for direct hex output
     */
    private static void applyFsUsbOptimizations(ElmDriver elm, LoggerConfig config) {
        Log.i(TAG, "Applying vLinker FS USB optimizations");

        // vLinker FS has much faster response time than generic clones.
        // ELM327 AT commands must NOT contain internal spaces in the parameter
        // and must use the correct mnemonic. Use the no-space form to match
        // initializeElm327() (ATAT2, ATST19) and avoid any firmware that does
        // not strip embedded spaces.
        //
        // ATAT1 = adaptive timing mode 1 (auto-adjusting, good for most vehicles)
        // ATST32 = timeout 0x32 * 4ms = 200ms — enough headroom for a full
        //   6-PID multi-frame (ISO-TP) response on USB. (The old comment claiming
        //   "50ms" was wrong: ST is hh*4ms.)
        elm.sendCommandRaw("ATAT1");  // Adaptive timing mode 1 (less aggressive than mode 2)
        elm.sendCommandRaw("ATST32"); // 200ms timeout — vLinker FS is fast but multi-PID
                                      // CAN responses need time to assemble all frames.

        // CRITICAL: ATAL (Allow Long) — removes the 7-data-byte-per-message limit
        // so that multi-PID batch responses (6 PIDs in one query) are returned in
        // full. The previous code sent "AT NL" (ATNL = Normal Length), which
        // RE-IMPOSES the 7-byte limit and TRUNCATES the response — silently
        // dropping every PID after the first ~7 bytes (RPM/Speed/Load survive,
        // but Fuel Status, ECT, and especially MAP 0x0B were lost). With MAP null,
        // MainActivity.updateFuelMap() skips pushData() and the fuel map stays
        // empty. This only manifested on real vLinker FS USB hardware (simulation
        // never sends ATNL and never does real CAN framing), which is exactly why
        // "map data from vLinker FS USB doesn't display" was the reported symptom.
        elm.sendCommandRaw("ATAL");   // Allow Long messages (>7 data bytes) — REQUIRED
                                      // for full multi-PID batch responses.
    }

    /**
     * vLinker MC WiFi (MIC3313) optimizations:
     *
     * From firmware v2.2.92 changelog:
     * - SWGPGR1 optimized formatted character response speed
     * - CAN controller pre-buffering mechanism (fewer missed frames)
     * - OBD request up to 1024 Hex bytes
     * - 7Fxx78 unlimited reply lines
     * - 48-group pass filter
     * - UART_SLEEP for power management
     * - WiFi: AT+WAP=11 (WiFi AP mode)
     *
     * WiFi-specific: TCP socket has higher latency than USB,
     * so we use slightly longer timeouts but benefit from
     * the pre-buffering mechanism for reliability.
     */
    private static void applyMcWifiOptimizations(ElmDriver elm, LoggerConfig config) {
        Log.i(TAG, "Applying vLinker MC WiFi optimizations");

        // WiFi has ~2-5ms network latency vs USB's <1ms
        // Use adaptive timing mode 2 (aggressive) with slightly longer timeout.
        // No-space AT command form (matches initializeElm327).
        elm.sendCommandRaw("ATAT2");  // Aggressive adaptive timing
        elm.sendCommandRaw("ATST1A"); // 0x1A*4ms = 104ms timeout — WiFi needs a bit more

        // The MC's pre-buffering mechanism (v2.2.76) helps with 500Kbps CAN
        // communication, reducing missed characters. We can rely on this
        // instead of adding our own retry logic.

        // CRITICAL: ATAL (Allow Long) — NOT ATNL. The old "AT NL" re-imposed the
        // 7-data-byte-per-message limit and truncated multi-PID batch responses
        // (dropping trailing PIDs like MAP 0x0B → empty fuel map). ATAL removes
        // the limit so 6-PID queries return in full.
        elm.sendCommandRaw("ATAL");

        // MC supports up to 1024 hex bytes per OBD request
        // This means multi-PID queries with 6+ PIDs work reliably
    }

    /**
     * vLinker MC Bluetooth (MIC3313) optimizations:
     *
     * Similar to WiFi but with Bluetooth-specific considerations:
     * - BT SPP has ~5-10ms latency (higher than WiFi)
     * - BLE has variable latency depending on connection interval
     * - AT+SetBtMode 1 for SPP mode
     * - AT+TpBaud 7 for transparent passthrough
     */
    private static void applyMcBtOptimizations(ElmDriver elm, LoggerConfig config) {
        Log.i(TAG, "Applying vLinker MC Bluetooth optimizations");

        // BT has higher and more variable latency than USB/WiFi
        // Use adaptive timing mode 1 (safer) with longer timeout.
        // No-space AT command form (matches initializeElm327).
        elm.sendCommandRaw("ATAT1");  // Adaptive timing mode 1
        elm.sendCommandRaw("ATST23"); // 0x23*4ms = 140ms timeout — BT needs more headroom

        // CRITICAL: ATAL (Allow Long) — NOT ATNL. See applyFsUsbOptimizations:
        // ATNL truncates multi-PID batch responses and empties the fuel map.
        elm.sendCommandRaw("ATAL");   // Allow Long messages (>7 data bytes)
    }

    /**
     * Get the recommended connection timeout for a specific device type.
     * vLinker devices can use shorter timeouts than generic clones.
     *
     * @param deviceType detected device type
     * @return timeout in milliseconds
     */
    public static int getRecommendedTimeout(DeviceType deviceType) {
        switch (deviceType) {
            case VLINKER_FS_USB:
                return 1500;  // USB is fast — 1.5s is plenty
            case VLINKER_MC_WIFI:
                return 2000;  // WiFi adds ~5ms RTT
            case VLINKER_MC_BT:
                return 3000;  // BT adds ~10ms RTT, more variable
            case GENERIC_ELM327:
                return 4000;  // Generic clones are slow and unreliable
            default:
                return 4000;
        }
    }

    /**
     * Get the recommended sample interval for a specific device type.
     * vLinker devices support faster polling due to lower latency.
     *
     * @param deviceType detected device type
     * @return sample interval in milliseconds
     */
    public static int getRecommendedSampleInterval(DeviceType deviceType) {
        switch (deviceType) {
            case VLINKER_FS_USB:
                return 250;  // USB can handle 4Hz polling
            case VLINKER_MC_WIFI:
                return 300;  // WiFi ~3.3Hz
            case VLINKER_MC_BT:
                return 500;  // BT ~2Hz (more variable)
            case GENERIC_ELM327:
                return 500;  // Safe for clones
            default:
                return 500;
        }
    }

    /**
     * Get the recommended chunk size (PIDs per multi-PID query).
     * vLinker devices handle larger chunks reliably.
     *
     * @param deviceType detected device type
     * @return chunk size (PIDs per query)
     */
    public static int getRecommendedChunkSize(DeviceType deviceType) {
        switch (deviceType) {
            case VLINKER_FS_USB:
            case VLINKER_MC_WIFI:
                return 6;  // vLinker handles 6 PID multi-query reliably
            case VLINKER_MC_BT:
                return 4;  // BT — smaller chunks for reliability
            case GENERIC_ELM327:
                return 4;  // Generic clones — conservative
            default:
                return 4;
        }
    }
}
