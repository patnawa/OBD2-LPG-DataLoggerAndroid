package com.alpha.obd2logger;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fetches live weather data from the Open-Meteo API (free, no API key required).
 *
 * Provides:
 *   - Relative Humidity (%)     — not available via OBD2
 *   - Ambient Temperature (°C)  — fallback for PID 0x46
 *   - Sea-level Pressure (hPa)  — fallback for PID 0x33
 *   - Wind Speed (km/h)         — bonus, useful for ram-air / vehicle aero
 *
 * Data is cached for 10 minutes (humidity changes slowly). GPS location is used
 * to get accurate local weather. If GPS is unavailable, falls back to a default
 * location (Bangkok).
 *
 * API: https://api.open-meteo.com/v1/forecast
 *   ?latitude=..&longitude=..
 *   &current=relative_humidity_2m,temperature_2m,pressure_msl,wind_speed_10m
 *
 * No API key needed. Free for non-commercial use. ~10,000 calls/day.
 */
public final class WeatherProvider {

    private static final String TAG = "WeatherProvider";

    private static final String API_BASE =
            "https://api.open-meteo.com/v1/forecast";

    /** Cache TTL: 10 minutes. Humidity/pressure changes slowly. */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** Default location: Bangkok (used when GPS unavailable) */
    private static final double DEFAULT_LAT = 13.7563;
    private static final double DEFAULT_LON = 100.5018;

    public static final class WeatherData {
        public final double humidity;       // % relative humidity
        public final double temperatureC;    // °C
        public final double pressureHpa;    // hPa (= mbar)
        public final double windSpeedKmh;   // km/h
        public final long timestamp;        // System.currentTimeMillis()

        WeatherData(double humidity, double temperatureC, double pressureHpa, double windSpeedKmh) {
            this.humidity = humidity;
            this.temperatureC = temperatureC;
            this.pressureHpa = pressureHpa;
            this.windSpeedKmh = windSpeedKmh;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US,
                    "RH=%.0f%%, T=%.1f°C, P=%.0fhPa, Wind=%.0fkm/h",
                    humidity, temperatureC, pressureHpa, windSpeedKmh);
        }
    }

    private static volatile WeatherData cached = null;
    private static volatile long lastFetchMs = 0;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface WeatherCallback {
        void onWeatherData(WeatherData data);
        void onWeatherError(String message);
    }

    private WeatherProvider() {}

    /**
     * Get cached weather data if fresh enough, otherwise returns null
     * (caller should call fetchAsync() to refresh).
     */
    public static WeatherData getCached() {
        if (cached == null) return null;
        if (System.currentTimeMillis() - lastFetchMs > CACHE_TTL_MS) return null;
        return cached;
    }

    /**
     * Check if cached data is still valid.
     */
    public static boolean hasValidCache() {
        return cached != null && System.currentTimeMillis() - lastFetchMs <= CACHE_TTL_MS;
    }

    /**
     * Get humidity percentage from cache, or default 50% if no data.
     */
    public static double getHumidityOrDefault() {
        WeatherData d = getCached();
        return d != null ? d.humidity : 50.0;
    }

    /**
     * Asynchronously fetch weather data from Open-Meteo API.
     * Uses GPS if available, otherwise falls back to Bangkok default.
     *
     * @param context Android context for LocationManager
     * @param callback Called on main thread with result or error
     */
    @SuppressLint("MissingPermission")
    public static void fetchAsync(Context context, WeatherCallback callback) {
        // Return cached if still valid
        if (hasValidCache()) {
            if (callback != null) {
                mainHandler.post(() -> callback.onWeatherData(cached));
            }
            return;
        }

        executor.submit(() -> {
            try {
                double[] latLon = getLastKnownLocation(context);
                double lat = latLon[0];
                double lon = latLon[1];

                String urlStr = API_BASE +
                        "?latitude=" + String.format(java.util.Locale.US, "%.4f", lat) +
                        "&longitude=" + String.format(java.util.Locale.US, "%.4f", lon) +
                        "&current=relative_humidity_2m,temperature_2m,pressure_msl,wind_speed_10m" +
                        "&timezone=auto";

                Log.i(TAG, "Fetching weather from: " + urlStr);

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    String msg = "Open-Meteo API returned HTTP " + responseCode;
                    Log.w(TAG, msg);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onWeatherError(msg));
                    }
                    return;
                }

                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                conn.disconnect();

                JSONObject json = new JSONObject(response.toString());
                JSONObject current = json.getJSONObject("current");

                double humidity = current.optDouble("relative_humidity_2m", 50.0);
                double tempC = current.optDouble("temperature_2m", 25.0);
                double pressure = current.optDouble("pressure_msl", 1013.25);
                double wind = current.optDouble("wind_speed_10m", 0.0);

                WeatherData data = new WeatherData(humidity, tempC, pressure, wind);
                cached = data;
                lastFetchMs = System.currentTimeMillis();

                Log.i(TAG, "Weather fetched: " + data);

                if (callback != null) {
                    mainHandler.post(() -> callback.onWeatherData(data));
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to parse weather JSON", e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onWeatherError("Parse error: " + e.getMessage()));
                }
            } catch (IOException e) {
                Log.e(TAG, "Weather API request failed", e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onWeatherError("Network error: " + e.getMessage()));
                }
            } catch (Exception e) {
                Log.e(TAG, "Weather fetch failed", e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onWeatherError("Error: " + e.getMessage()));
                }
            }
        });
    }

    /**
     * Synchronous fetch — blocks until data is received or timeout.
     * Used by LoggerService in background thread.
     */
    public static WeatherData fetchSync(Context context) {
        if (hasValidCache()) return cached;

        double[] latLon = getLastKnownLocation(context);
        double lat = latLon[0];
        double lon = latLon[1];

        try {
            String urlStr = API_BASE +
                    "?latitude=" + String.format(java.util.Locale.US, "%.4f", lat) +
                    "&longitude=" + String.format(java.util.Locale.US, "%.4f", lon) +
                    "&current=relative_humidity_2m,temperature_2m,pressure_msl,wind_speed_10m" +
                    "&timezone=auto";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "Weather API returned " + conn.getResponseCode());
                conn.disconnect();
                return null;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            conn.disconnect();

            JSONObject json = new JSONObject(response.toString());
            JSONObject current = json.getJSONObject("current");

            double humidity = current.optDouble("relative_humidity_2m", 50.0);
            double tempC = current.optDouble("temperature_2m", 25.0);
            double pressure = current.optDouble("pressure_msl", 1013.25);
            double wind = current.optDouble("wind_speed_10m", 0.0);

            WeatherData data = new WeatherData(humidity, tempC, pressure, wind);
            cached = data;
            lastFetchMs = System.currentTimeMillis();

            Log.i(TAG, "Weather fetched (sync): " + data);
            return data;
        } catch (Exception e) {
            Log.e(TAG, "Sync weather fetch failed", e);
            return null;
        }
    }

    /**
     * Try to get last known GPS location. Falls back to Bangkok if unavailable.
     * Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission.
     */
    @SuppressLint("MissingPermission")
    private static double[] getLastKnownLocation(Context context) {
        if (context == null) return new double[]{DEFAULT_LAT, DEFAULT_LON};

        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return new double[]{DEFAULT_LAT, DEFAULT_LON};

            // Try GPS first (most accurate for outdoor driving)
            Location gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (gpsLoc != null) {
                return new double[]{gpsLoc.getLatitude(), gpsLoc.getLongitude()};
            }

            // Fall back to network provider
            Location netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (netLoc != null) {
                return new double[]{netLoc.getLatitude(), netLoc.getLongitude()};
            }
        } catch (SecurityException e) {
            Log.w(TAG, "No location permission — using default (Bangkok)");
        } catch (Exception e) {
            Log.w(TAG, "Location lookup failed", e);
        }

        return new double[]{DEFAULT_LAT, DEFAULT_LON};
    }

    /**
     * Force a refresh regardless of cache age.
     */
    public static void invalidateCache() {
        cached = null;
        lastFetchMs = 0;
    }
}
