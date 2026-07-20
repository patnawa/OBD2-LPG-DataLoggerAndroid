package com.alpha.obd2logger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Continuous GPS capture for the duration of a logging session, feeding
 * {@link PollingEngine.LocationSupplier} so each record carries the fix that
 * was current when it was polled.
 *
 * <p>Fail-quiet by design: if fine-location permission is missing or the
 * provider is disabled, {@link #start} simply returns false and the session
 * logs without location columns — logging must never be blocked by GPS.
 */
final class RouteRecorder implements PollingEngine.LocationSupplier, LocationListener {

    private static final String TAG = "RouteRecorder";
    private static final long MIN_TIME_MS = 1000;
    private static final float MIN_DISTANCE_M = 0f;

    private final Context appContext;
    private LocationManager locationManager;
    private volatile double[] lastFix; // [lat, lon, accuracyM, elapsedRealtimeMs of fix]
    private boolean started;

    RouteRecorder(Context context) {
        this.appContext = context.getApplicationContext();
    }

    static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Begin listening for GPS updates on the main looper.
     *
     * @return true when updates were actually requested
     */
    synchronized boolean start() {
        if (started) return true;
        if (!hasPermission(appContext)) {
            Log.i(TAG, "No fine-location permission; session logs without GPS");
            return false;
        }
        try {
            locationManager = (LocationManager)
                    appContext.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null
                    || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.i(TAG, "GPS provider unavailable; session logs without GPS");
                return false;
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS, MIN_DISTANCE_M, this,
                    appContext.getMainLooper());
            started = true;
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "GPS updates unavailable", e);
            return false;
        }
    }

    synchronized void stop() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (SecurityException ignored) {
            }
        }
        started = false;
        lastFix = null;
    }

    @Override
    public double[] latest() {
        double[] fix = lastFix;
        if (fix == null) return null;
        long ageMs = SystemClock.elapsedRealtime() - (long) fix[3];
        return new double[]{fix[0], fix[1], fix[2], ageMs};
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        lastFix = new double[]{location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : -1.0,
                SystemClock.elapsedRealtime()};
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
        lastFix = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
    }
}
