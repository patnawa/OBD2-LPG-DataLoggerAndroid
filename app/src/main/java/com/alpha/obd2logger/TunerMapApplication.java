package com.alpha.obd2logger;

import android.app.Application;

/**
 * Exists so crash reporting is installed before any component starts. A
 * handler installed in an Activity would miss crashes on the logging worker,
 * the transport read loops and the foreground service — which is where this
 * app spends nearly all of its runtime.
 */
public final class TunerMapApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
        // Load before any VIN can be read, so the first detection already uses
        // the data-driven rules rather than the legacy Java fallback.
        BrandProfile.load(this);
    }
}
