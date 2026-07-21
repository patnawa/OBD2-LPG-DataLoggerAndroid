package com.alpha.obd2logger;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * TEMPORARY diagnostic trace of every ELM327 command/response pair.
 *
 * <p>Added to diagnose the field report "adapter connects but no live PID data
 * ever arrives". Every {@code sendCommand} TX/RX pair funnels through
 * {@link ElmDriver#sendCommand(String)} and is echoed here to logcat under the
 * {@code OBD2Trace} tag, and also kept in a small ring buffer for in-app dump.
 *
 * <p>Compiled active only in debug builds: {@link #ENABLED} is
 * {@code BuildConfig.DEBUG}, so release builds pay nothing and emit nothing.
 *
 * <p>Capture over USB with:  {@code adb logcat -s OBD2Trace:I}
 *
 * <p>TODO(diagnostics): delete this class and the trace wrapper in
 * {@link ElmDriver} once the no-data root cause is found and fixed.
 */
final class CommandTrace {
    static final boolean ENABLED = BuildConfig.DEBUG;
    private static final String TAG = "OBD2Trace";
    private static final int MAX_ENTRIES = 500;
    private static final Deque<String> RING = new ArrayDeque<>();

    private CommandTrace() {}

    /** Record one command and the raw response it produced (may be null). */
    static void record(String command, String response) {
        if (!ENABLED) return;
        String tx = command == null ? "<null>" : command.trim();
        String rx = response == null ? "<null>"
                : response.replace('\r', ' ').replace('\n', ' ').trim();
        if (rx.isEmpty()) rx = "<empty>";
        String line = "TX[" + tx + "] -> RX[" + rx + "]";
        Log.i(TAG, line);
        synchronized (RING) {
            if (RING.size() >= MAX_ENTRIES) RING.removeFirst();
            RING.addLast(line);
        }
    }

    /** The full retained trace, oldest first, newline-separated. */
    static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (RING) {
            for (String line : RING) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
