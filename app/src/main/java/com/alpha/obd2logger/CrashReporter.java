package com.alpha.obd2logger;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Process-wide crash capture.
 *
 * <p>Previously the only crash handling in the app was a {@code try/catch
 * (Throwable)} wrapped around {@code MainActivity.onCreate}. Anything that
 * failed elsewhere — the logging worker, a transport read loop, a DTC scan,
 * the API server — died with nothing written down, which is the worst place to
 * be blind: those run unattended for hours in a moving vehicle.
 *
 * <p>This appends a report and then <em>chains to the previous handler</em>, so
 * the platform still terminates the process and reports normally. Swallowing
 * the throwable would leave a half-dead process holding a wakelock and an open
 * Bluetooth socket.
 *
 * <p>Reports append to {@code crash_log.txt} in the app's external files dir,
 * the same file the old onCreate catch used, so existing user instructions for
 * retrieving it still hold.
 */
final class CrashReporter {

    private static final String TAG = "CrashReporter";
    private static final String CRASH_FILE = "crash_log.txt";

    /** Keeps the file bounded; a boot loop must not fill the user's storage. */
    private static final long MAX_CRASH_FILE_BYTES = 512 * 1024;

    private CrashReporter() {
    }

    // ── Breadcrumbs ────────────────────────────────────────────────────────
    // Deliberately a few volatile scalars rather than a queue: this is written
    // from the logging worker on a hot path, and a crash report only needs to
    // answer "what was it doing when it died".

    private static volatile String transport = "none";
    private static volatile String sessionId = "none";
    private static volatile int recordCount = 0;

    static void noteSession(String sessionIdValue, String transportValue) {
        if (sessionIdValue != null) sessionId = sessionIdValue;
        if (transportValue != null) transport = transportValue;
    }

    static void noteRecordCount(int count) {
        recordCount = count;
    }

    static void noteSessionEnded() {
        transport = "none";
        sessionId = "none";
    }

    /**
     * Install the handler. Safe to call more than once — the second call is a
     * no-op rather than chaining this handler to itself, which would double
     * every report.
     */
    static void install(Context context) {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof ReportingHandler) return;

        Context appContext = context.getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler(new ReportingHandler(appContext, previous));
    }

    private static final class ReportingHandler implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler previous;

        ReportingHandler(Context context, Thread.UncaughtExceptionHandler previous) {
            this.context = context;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            // Never let reporting failure mask the original crash.
            try {
                write(context, thread, throwable);
            } catch (Throwable reportingFailure) {
                Log.e(TAG, "Failed to write crash report", reportingFailure);
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        }
    }

    private static void write(Context context, Thread thread, Throwable throwable) throws Exception {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        File file = new File(dir, CRASH_FILE);

        // Truncate rather than rotate: the newest crash is the useful one and a
        // second file would just be another thing to explain to the user.
        if (file.exists() && file.length() > MAX_CRASH_FILE_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }

        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        Writer stack = new StringWriter();
        try (PrintWriter pw = new PrintWriter(stack)) {
            throwable.printStackTrace(pw);
        }

        String report = "\n===== CRASH " + stamp + " =====\n"
                + "version : " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "android : " + android.os.Build.VERSION.SDK_INT
                + " on " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n"
                + "thread  : " + thread.getName() + "\n"
                + "session : " + sessionId + "\n"
                + "transport: " + transport + "\n"
                + "records : " + recordCount + "\n"
                + stack + "\n";

        Files.write(file.toPath(), report.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
