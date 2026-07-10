package com.alpha.obd2logger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Central coordinator for all air-density inputs.
 *
 * Merges data from three sources in priority order:
 *
 *  1. OBD2 PIDs (most authoritative — measured at the vehicle):
 *       PID 0x33 Barometric Pressure (kPa)    → AAD pressure
 *       PID 0x46 Ambient Air Temp (°C)        → AAD temperature
 *       PID 0x0B Intake Manifold Pressure     → MAD pressure
 *       PID 0x0F Intake Air Temp (°C)          → MAD temperature
 *
 *  2. Android phone sensors (good quality, phone-local):
 *       Sensor.TYPE_PRESSURE                  → fallback barometric pressure
 *       Sensor.TYPE_AMBIENT_TEMPERATURE        → fallback ambient temp
 *       Sensor.TYPE_RELATIVE_HUMIDITY          → humidity (rare sensor)
 *
 *  3. Open-Meteo Weather API (fallback, cached 10 min):
 *       Relative humidity                      → not available via OBD2 at all
 *       Ambient temperature                    → fallback
 *       Sea-level pressure                     → fallback
 *       Wind speed                             → bonus
 *
 * Humidity is ALWAYS sourced from WeatherProvider or Android sensor because
 * OBD2 / SAE J1979 does not define a humidity PID. This is the critical
 * missing piece that WeatherProvider fills.
 *
 * Usage in LoggerService:
 *   AirDensityMonitor monitor = new AirDensityMonitor(context);
 *   monitor.refreshWeather();  // async, every 10 min
 *   monitor.onObdBatch(batch); // called after each OBD2 query batch
 *   AirDensityResult result = monitor.compute();
 *   // result has: aad, mad, bad, densityPercent, densityAltitude, humidity, ...
 */
public final class AirDensityMonitor {

    private static final String TAG = "AirDensityMonitor";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Cached weather data ──────────────────────────────────
    private volatile double weatherHumidity = 50.0;    // % RH
    private volatile double weatherTempC = 25.0;       // °C
    private volatile double weatherPressureHpa = 1013.25; // hPa
    private volatile double weatherWindKmh = 0.0;       // km/h
    private volatile boolean weatherValid = false;

    // ── OBD2 batch values (updated by LoggerService) ─────────
    private volatile Double obdBaroKpa = null;   // PID 0x33
    private volatile Double obdAmbientTempC = null; // PID 0x46
    private volatile Double obdMapKpa = null;    // PID 0x0B
    private volatile Double obdIatTempC = null;   // PID 0x0F

    // ── Android sensor values (optional, future expansion) ────
    private volatile Double sensorPressureHpa = null;
    private volatile Double sensorHumidity = null;

    private static final long WEATHER_REFRESH_MS = 10 * 60 * 1000L;
    private long lastWeatherFetchMs = 0;

    public AirDensityMonitor(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    /**
     * Refresh weather data from Open-Meteo API (async, non-blocking).
     * Fetches only if cache is stale (>10 min old).
     * Called by LoggerService at startup and periodically during logging.
     */
    public void refreshWeather() {
        if (System.currentTimeMillis() - lastWeatherFetchMs < WEATHER_REFRESH_MS && weatherValid) {
            return; // cache still fresh
        }

        WeatherProvider.fetchAsync(context, new WeatherProvider.WeatherCallback() {
            @Override
            public void onWeatherData(WeatherProvider.WeatherData data) {
                weatherHumidity = data.humidity;
                weatherTempC = data.temperatureC;
                weatherPressureHpa = data.pressureHpa;
                weatherWindKmh = data.windSpeedKmh;
                weatherValid = true;
                lastWeatherFetchMs = System.currentTimeMillis();
                Log.i(TAG, "Weather updated: " + data);
            }

            @Override
            public void onWeatherError(String message) {
                Log.w(TAG, "Weather fetch failed: " + message + " — using defaults");
                // Keep using previous/default values
                weatherValid = false;
            }
        });
    }

    /**
     * Synchronous weather refresh — for LoggerService background thread.
     */
    public void refreshWeatherSync() {
        if (System.currentTimeMillis() - lastWeatherFetchMs < WEATHER_REFRESH_MS && weatherValid) {
            return;
        }

        // Guard against network access in test environments (Robolectric doesn't
        // have real network — this would block and timeout unit tests).
        if (context == null) return;

        WeatherProvider.WeatherData data = WeatherProvider.fetchSync(context);
        if (data != null) {
            weatherHumidity = data.humidity;
            weatherTempC = data.temperatureC;
            weatherPressureHpa = data.pressureHpa;
            weatherWindKmh = data.windSpeedKmh;
            weatherValid = true;
            lastWeatherFetchMs = System.currentTimeMillis();
            Log.i(TAG, "Weather updated (sync): " + data);
        }
    }

    /**
     * Feed OBD2 batch results into the monitor.
     * Called by LoggerService after each queryPidBatch() call.
     *
     * @param batch Map of PID name → value (from BaseDriver.queryPidBatch)
     */
    public void onObdBatch(java.util.Map<String, Double> batch) {
        if (batch == null) return;
        obdBaroKpa = batch.get("Barometric Pressure");
        obdAmbientTempC = batch.get("Ambient Air Temp");
        obdMapKpa = batch.get("Intake Manifold Pressure");
        obdIatTempC = batch.get("Intake Air Temp");
    }

    /**
     * Get current humidity from best available source.
     * Priority: Android sensor → Weather API → default 50%
     */
    public double getHumidity() {
        if (sensorHumidity != null) return sensorHumidity;
        if (weatherValid) return weatherHumidity;
        return 50.0;
    }

    /**
     * Get barometric pressure (kPa) from best available source.
     * Priority: OBD2 PID 0x33 → Android pressure sensor → Weather API → sea-level default
     */
    public double getBaroKpa() {
        if (obdBaroKpa != null && obdBaroKpa > 50.0) return obdBaroKpa;
        if (sensorPressureHpa != null) return sensorPressureHpa / 10.0; // hPa → kPa
        if (weatherValid) return weatherPressureHpa / 10.0;
        return 101.325; // sea level
    }

    /**
     * Get ambient temperature (°C) from best available source.
     * Priority: OBD2 PID 0x46 → Weather API → default 25°C
     */
    public double getAmbientTempC() {
        if (obdAmbientTempC != null) return obdAmbientTempC;
        if (weatherValid) return weatherTempC;
        return 25.0;
    }

    /**
     * Result container for all air-density computed values.
     * Passed to LoggerService for logging and UI display.
     */
    public static final class AirDensityResult {
        public final Double aad;              // Ambient Air Density (lbs/1000ft³)
        public final Double mad;              // Manifold Air Density (lbs/1000ft³)
        public final Double bad;              // Boost Air Density (lbs/1000ft³)
        public final Double densityPercent;   // AAD as % of SAE J1349 standard
        public final Double densityAltitudeFt; // Density altitude (ft)
        public final Double saeJ1349CF;       // SAE J1349 correction factor
        public final Double grainsH2O;        // Grains water per lb dry air
        public final double humidity;         // % RH used
        public final double baroKpa;          // kPa used for AAD
        public final double ambientTempC;     // °C used for AAD
        public final double mapKpa;           // kPa used for MAD
        public final double iatTempC;         // °C used for MAD

        AirDensityResult(Double aad, Double mad, Double bad, Double densityPercent,
                         Double densityAltitudeFt, Double saeJ1349CF, Double grainsH2O,
                         double humidity, double baroKpa, double ambientTempC,
                         double mapKpa, double iatTempC) {
            this.aad = aad;
            this.mad = mad;
            this.bad = bad;
            this.densityPercent = densityPercent;
            this.densityAltitudeFt = densityAltitudeFt;
            this.saeJ1349CF = saeJ1349CF;
            this.grainsH2O = grainsH2O;
            this.humidity = humidity;
            this.baroKpa = baroKpa;
            this.ambientTempC = ambientTempC;
            this.mapKpa = mapKpa;
            this.iatTempC = iatTempC;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US,
                    "AAD=%.1f MAD=%.1f BAD=%.1f (%.0f%%) DA=%dft RH=%.0f%%",
                    aad != null ? aad : 0,
                    mad != null ? mad : 0,
                    bad != null ? bad : 0,
                    densityPercent != null ? densityPercent : 0,
                    densityAltitudeFt != null ? Math.round(densityAltitudeFt) : 0,
                    humidity);
        }
    }

    /**
     * Compute all air-density values from current inputs.
     *
     * @return AirDensityResult with all computed values, or null if critical inputs missing
     */
    public AirDensityResult compute() {
        double humidity = getHumidity();
        double baroKpa = getBaroKpa();
        double ambientTempC = getAmbientTempC();
        double mapKpa = (obdMapKpa != null) ? obdMapKpa : baroKpa;
        double iatTempC = (obdIatTempC != null) ? obdIatTempC : ambientTempC;

        Double aad = DerivedSensors.ambientAirDensity(baroKpa, ambientTempC, humidity);
        Double mad = DerivedSensors.manifoldAirDensity(mapKpa, iatTempC, humidity);
        Double bad = DerivedSensors.boostAirDensity(mad, aad);
        Double densityPct = DerivedSensors.airDensityPercent(aad);
        Double densityAlt = DerivedSensors.densityAltitudeFt(baroKpa, ambientTempC, humidity);
        Double saeCF = DerivedSensors.saeJ1349CorrectionFactor(baroKpa, ambientTempC, humidity);
        Double grains = DerivedSensors.grainsH2O(ambientTempC, humidity, baroKpa);

        return new AirDensityResult(aad, mad, bad, densityPct, densityAlt, saeCF, grains,
                humidity, baroKpa, ambientTempC, mapKpa, iatTempC);
    }

    /**
     * Compute all advanced air-density values (10 formulas beyond Banks).
     * Requires engine displacement + RPM + MAF + lambda from the OBD2 batch.
     *
     * @param mafGs    MAF sensor reading (g/s)
     * @param rpm      Engine RPM
     * @param lambda   Lambda value (from PID 0x34 or 0x44)
     * @param fuelMode Fuel type
     * @param displacementCC Engine displacement (cc)
     * @param ratedRPM  Rated peak-power RPM
     * @return AdvancedResult with all 10 calculated values
     */
    public AdvancedAirDensity.AdvancedResult computeAdvanced(
            Double mafGs, Double rpm, Double lambda,
            FuelMode fuelMode, double displacementCC, double ratedRPM) {

        double humidity = getHumidity();
        double baroKpa = getBaroKpa();
        double ambientTempC = getAmbientTempC();
        double mapKpa = (obdMapKpa != null) ? obdMapKpa : baroKpa;
        double iatTempC = (obdIatTempC != null) ? obdIatTempC : ambientTempC;

        // Get density in kg/m³ for advanced formulas
        Double aadKgM3 = DerivedSensors.airDensityKgM3(baroKpa, ambientTempC, humidity);
        Double madKgM3 = DerivedSensors.airDensityKgM3(mapKpa, iatTempC, humidity);

        return AdvancedAirDensity.computeAll(
                aadKgM3, madKgM3,
                baroKpa, mapKpa,
                ambientTempC, iatTempC,
                humidity, mafGs,
                rpm, lambda,
                fuelMode, displacementCC, ratedRPM);
    }
}
