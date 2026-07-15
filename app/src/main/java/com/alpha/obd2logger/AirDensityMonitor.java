package com.alpha.obd2logger;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * Central coordinator for AeroDensity Intelligence inputs.
 *
 * Priority of sources:
 *   1. OBD2 PIDs (vehicle station baro, ambient temp, MAP, IAT)
 *   2. Android phone sensors (pressure / rare humidity)
 *   3. Open-Meteo Weather API last-good cache (humidity critical)
 *   4. Hard defaults (flagged as default — never disguised as measured)
 *
 * Weather failures KEEP last good cache rather than silently degrading
 * humidity to 50% and producing polished-but-wrong density numbers.
 */
public final class AirDensityMonitor implements SensorEventListener {

    private static final String TAG = "AirDensityMonitor";

    public static final String SRC_OBD = "obd";
    public static final String SRC_SENSOR = "sensor";
    public static final String SRC_WEATHER = "weather";
    public static final String SRC_DEFAULT = "default";
    public static final String SRC_MIXED = "mixed";

    private final Context context;

    // ── Cached weather data (last-good retained across failures) ──
    private volatile double weatherHumidity = 50.0;
    private volatile double weatherTempC = 25.0;
    private volatile double weatherPressureHpa = 1013.25;
    private volatile double weatherWindKmh = 0.0;
    private volatile boolean weatherEverSucceeded = false;
    private volatile boolean weatherLastAttemptOk = false;
    private volatile long lastWeatherFetchMs = 0;
    private volatile long lastWeatherAttemptMs = 0;
    private volatile String lastWeatherError = null;

    // ── OBD2 batch values ─────────────────────────────────────
    private volatile Double obdBaroKpa = null;       // PID 0x33
    private volatile Double obdAmbientTempC = null; // PID 0x46
    private volatile Double obdMapKpa = null;        // PID 0x0B
    private volatile Double obdIatTempC = null;      // PID 0x0F
    /** True when obdMapKpa was synthesized from Engine Load, not measured. */
    private volatile boolean obdMapSynthesized = false;

    // ── Android sensors ───────────────────────────────────────
    private volatile Double sensorPressureHpa = null;
    private volatile Double sensorHumidity = null;
    private SensorManager sensorManager;
    private boolean sensorsRegistered = false;

    private static final long WEATHER_REFRESH_MS = 10 * 60 * 1000L;
    private static final long WEATHER_RETRY_MS = 2 * 60 * 1000L; // retry sooner after failure

    public AirDensityMonitor(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
        // Prefer static WeatherProvider cache if still warm
        WeatherProvider.WeatherData cached = WeatherProvider.getCached();
        if (cached != null) {
            weatherHumidity = cached.humidity;
            weatherTempC = cached.temperatureC;
            weatherPressureHpa = cached.pressureHpa;
            weatherWindKmh = cached.windSpeedKmh;
            weatherEverSucceeded = true;
            weatherLastAttemptOk = true;
            lastWeatherFetchMs = cached.timestamp;
        }
    }

    // ── Phone sensors ─────────────────────────────────────────

    /** Register TYPE_PRESSURE / TYPE_RELATIVE_HUMIDITY when available. Safe no-op if none. */
    public void startPhoneSensors() {
        if (context == null || sensorsRegistered) return;
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) return;
            Sensor pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
            Sensor humidity = sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY);
            if (pressure != null) {
                sensorManager.registerListener(this, pressure, SensorManager.SENSOR_DELAY_NORMAL);
                sensorsRegistered = true;
            }
            if (humidity != null) {
                sensorManager.registerListener(this, humidity, SensorManager.SENSOR_DELAY_NORMAL);
                sensorsRegistered = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Phone air sensors unavailable", e);
        }
    }

    public void stopPhoneSensors() {
        if (sensorManager != null && sensorsRegistered) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Exception ignored) {}
        }
        sensorsRegistered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length == 0) return;
        if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
            sensorPressureHpa = (double) event.values[0];
        } else if (event.sensor.getType() == Sensor.TYPE_RELATIVE_HUMIDITY) {
            sensorHumidity = (double) event.values[0];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }

    // ── Status getters ────────────────────────────────────────

    public boolean isWeatherValid() {
        return weatherEverSucceeded && (System.currentTimeMillis() - lastWeatherFetchMs) < WEATHER_REFRESH_MS * 3;
    }

    public boolean hasLastGoodWeather() {
        return weatherEverSucceeded;
    }

    public long getLastWeatherFetchMs() {
        return lastWeatherFetchMs;
    }

    public String getLastWeatherError() {
        return lastWeatherError;
    }

    public boolean isWeatherLastAttemptOk() {
        return weatherLastAttemptOk;
    }

    /**
     * Humidity data source label for UI.
     */
    public String getWeatherSource() {
        if (sensorHumidity != null) return "Android Sensor";
        if (weatherEverSucceeded) {
            long age = System.currentTimeMillis() - lastWeatherFetchMs;
            if (age > WEATHER_REFRESH_MS * 3) return "Open-Meteo (stale)";
            return "Open-Meteo API";
        }
        return "Default (50%)";
    }

    public double getWeatherWindKmh() {
        return weatherWindKmh;
    }

    /** True if a non-blocking fetch should run now. */
    public boolean needsWeatherRefresh() {
        long now = System.currentTimeMillis();
        if (!weatherEverSucceeded) {
            return now - lastWeatherAttemptMs > WEATHER_RETRY_MS;
        }
        return now - lastWeatherFetchMs >= WEATHER_REFRESH_MS
                && now - lastWeatherAttemptMs > WEATHER_RETRY_MS;
    }

    /**
     * Async weather refresh. NEVER clears last-good values on failure.
     */
    public void refreshWeather() {
        if (!needsWeatherRefresh() && weatherLastAttemptOk && weatherEverSucceeded) {
            // TTL not expired and last attempt ok
            if (System.currentTimeMillis() - lastWeatherFetchMs < WEATHER_REFRESH_MS) return;
        }
        lastWeatherAttemptMs = System.currentTimeMillis();
        WeatherProvider.fetchAsync(context, new WeatherProvider.WeatherCallback() {
            @Override
            public void onWeatherData(WeatherProvider.WeatherData data) {
                applyWeather(data);
            }

            @Override
            public void onWeatherError(String message) {
                weatherLastAttemptOk = false;
                lastWeatherError = message;
                Log.w(TAG, "Weather fetch failed (keeping last-good): " + message);
                // Do NOT set weatherEverSucceeded=false — last-good retained
            }
        });
    }

    /**
     * Force an async refresh even if cache is fresh (for UI Refresh button).
     */
    public void forceRefreshWeather() {
        lastWeatherAttemptMs = System.currentTimeMillis();
        WeatherProvider.invalidateCache();
        WeatherProvider.fetchAsync(context, new WeatherProvider.WeatherCallback() {
            @Override
            public void onWeatherData(WeatherProvider.WeatherData data) {
                applyWeather(data);
            }

            @Override
            public void onWeatherError(String message) {
                weatherLastAttemptOk = false;
                lastWeatherError = message;
                Log.w(TAG, "Forced weather refresh failed (keeping last-good): " + message);
            }
        });
    }

    /**
     * Synchronous weather refresh — for LoggerService startup only.
     * Never destroys last-good cache.
     */
    public void refreshWeatherSync() {
        if (System.currentTimeMillis() - lastWeatherFetchMs < WEATHER_REFRESH_MS && weatherEverSucceeded) {
            return;
        }
        if (context == null) return;
        lastWeatherAttemptMs = System.currentTimeMillis();
        WeatherProvider.WeatherData data = WeatherProvider.fetchSync(context);
        if (data != null) {
            applyWeather(data);
        } else {
            weatherLastAttemptOk = false;
            lastWeatherError = "sync fetch returned null";
            Log.w(TAG, "Sync weather fetch failed — keeping last-good / defaults");
        }
    }

    private void applyWeather(WeatherProvider.WeatherData data) {
        weatherHumidity = data.humidity;
        weatherTempC = data.temperatureC;
        weatherPressureHpa = data.pressureHpa;
        weatherWindKmh = data.windSpeedKmh;
        weatherEverSucceeded = true;
        weatherLastAttemptOk = true;
        lastWeatherError = null;
        lastWeatherFetchMs = System.currentTimeMillis();
        Log.i(TAG, "Weather updated: " + data);
    }

    public void onObdBatch(java.util.Map<String, Double> batch) {
        onObdBatch(batch, false);
    }

    /**
     * @param mapSynthesized true when "Intake Manifold Pressure" in this batch was
     *        synthesized from Engine Load (LoggerService MAP fallback) rather than
     *        measured by PID 0x0B. The value is still used, but the derived
     *        density samples that depend on it are stamped "est" instead of
     *        being logged as measured.
     */
    public void onObdBatch(java.util.Map<String, Double> batch, boolean mapSynthesized) {
        if (batch == null) return;
        // Only overwrite with non-null so a single failed PID doesn't wipe prior sample
        Double baro = batch.get("Barometric Pressure");
        Double amb = batch.get("Ambient Air Temp");
        Double map = batch.get("Intake Manifold Pressure");
        Double iat = batch.get("Intake Air Temp");
        if (baro != null) obdBaroKpa = baro;
        if (amb != null) obdAmbientTempC = amb;
        if (map != null) {
            obdMapKpa = map;
            obdMapSynthesized = mapSynthesized;
        }
        if (iat != null) obdIatTempC = iat;
    }

    /** Feed raw PID values when a UI record has not yet been density-enriched. */
    public void onObdValues(Double baroKpa, Double ambientTempC,
                            Double mapKpa, Double iatTempC) {
        if (baroKpa != null && !Double.isNaN(baroKpa)) obdBaroKpa = baroKpa;
        if (ambientTempC != null && !Double.isNaN(ambientTempC)) obdAmbientTempC = ambientTempC;
        if (mapKpa != null && !Double.isNaN(mapKpa)) {
            obdMapKpa = mapKpa;
            obdMapSynthesized = false;
        }
        if (iatTempC != null && !Double.isNaN(iatTempC)) obdIatTempC = iatTempC;
    }

    /** Best humidity: phone → last-good weather → default 50%. */
    public double getHumidity() {
        if (sensorHumidity != null) return DerivedSensors.clampHumidity(sensorHumidity, 50.0);
        if (weatherEverSucceeded) return DerivedSensors.clampHumidity(weatherHumidity, 50.0);
        return 50.0;
    }

    public String getHumiditySource() {
        if (sensorHumidity != null) return SRC_SENSOR;
        if (weatherEverSucceeded) return SRC_WEATHER;
        return SRC_DEFAULT;
    }

    public double getBaroKpa() {
        if (obdBaroKpa != null && obdBaroKpa > 50.0) return obdBaroKpa;
        if (sensorPressureHpa != null && sensorPressureHpa > 500.0) {
            return sensorPressureHpa / 10.0; // hPa → kPa
        }
        // Prefer station-ish weather only when we never got OBD baro.
        // Open-Meteo pressure is MSL; OBD is station. Prefer MSL*1 only as last resort.
        if (weatherEverSucceeded) return weatherPressureHpa / 10.0;
        return DerivedSensors.SEA_LEVEL_PRESSURE_KPA;
    }

    public String getBaroSource() {
        if (obdBaroKpa != null && obdBaroKpa > 50.0) return SRC_OBD;
        if (sensorPressureHpa != null && sensorPressureHpa > 500.0) return SRC_SENSOR;
        if (weatherEverSucceeded) return SRC_WEATHER;
        return SRC_DEFAULT;
    }

    public double getAmbientTempC() {
        if (obdAmbientTempC != null) return obdAmbientTempC;
        if (weatherEverSucceeded) return weatherTempC;
        return 25.0;
    }

    public String getAmbientTempSource() {
        if (obdAmbientTempC != null) return SRC_OBD;
        if (weatherEverSucceeded) return SRC_WEATHER;
        return SRC_DEFAULT;
    }

    public Double getMapKpaOrNull() {
        return obdMapKpa;
    }

    public Double getIatTempCOrNull() {
        return obdIatTempC;
    }

    /**
     * Quality rollup for SensorSample status:
     *   ok      — baro + ambient from OBD, humidity measured
     *   est     — humidity or ambient from weather/sensor (still trustworthy)
     *   default — critical ambient inputs synthesized (season-day 25°C / 101.3)
     */
    public String qualityStatus() {
        String baroSrc = getBaroSource();
        String ambSrc = getAmbientTempSource();
        String rhSrc = getHumiditySource();
        if (SRC_DEFAULT.equals(baroSrc) || SRC_DEFAULT.equals(ambSrc) || SRC_DEFAULT.equals(rhSrc)) {
            return "default";
        }
        if (SRC_OBD.equals(baroSrc) && SRC_OBD.equals(ambSrc)
                && (SRC_WEATHER.equals(rhSrc) || SRC_SENSOR.equals(rhSrc))) {
            // OBD baro/temp + weather RH is the normal healthy path
            return "ok";
        }
        return "est";
    }

    /**
     * Result container for all air-density computed values.
     */
    public static final class AirDensityResult {
        public final Double aad;
        public final Double mad;
        public final Double bad;
        public final Double densityPercent;
        public final Double densityAltitudeFt;
        public final Double saeJ1349CF;
        public final Double grainsH2O;
        public final double humidity;
        public final double baroKpa;
        public final double ambientTempC;
        public final double mapKpa;
        public final double iatTempC;
        public final String qualityStatus;
        public final String humiditySource;
        public final String baroSource;
        public final String ambientTempSource;
        public final boolean mapFromObd;
        public final boolean iatFromObd;

        AirDensityResult(Double aad, Double mad, Double bad, Double densityPercent,
                         Double densityAltitudeFt, Double saeJ1349CF, Double grainsH2O,
                         double humidity, double baroKpa, double ambientTempC,
                         double mapKpa, double iatTempC,
                         String qualityStatus, String humiditySource,
                         String baroSource, String ambientTempSource,
                         boolean mapFromObd, boolean iatFromObd) {
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
            this.qualityStatus = qualityStatus;
            this.humiditySource = humiditySource;
            this.baroSource = baroSource;
            this.ambientTempSource = ambientTempSource;
            this.mapFromObd = mapFromObd;
            this.iatFromObd = iatFromObd;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US,
                    "AAD=%.1f MAD=%.1f BAD=%.1f (%.0f%%) DA=%dft RH=%.0f%% q=%s",
                    aad != null ? aad : 0,
                    mad != null ? mad : 0,
                    bad != null ? bad : 0,
                    densityPercent != null ? densityPercent : 0,
                    densityAltitudeFt != null ? Math.round(densityAltitudeFt) : 0,
                    humidity,
                    qualityStatus);
        }
    }

    public AirDensityResult compute() {
        double humidity = getHumidity();
        double baroKpa = getBaroKpa();
        double ambientTempC = getAmbientTempC();
        // MAP synthesized from Engine Load counts as NOT measured — the value
        // is still used, but dependent samples degrade to "est" quality.
        boolean mapFromObd = obdMapKpa != null && !obdMapSynthesized;
        boolean iatFromObd = obdIatTempC != null;
        double mapKpa = (obdMapKpa != null) ? obdMapKpa : baroKpa;
        double iatTempC = iatFromObd ? obdIatTempC : ambientTempC;

        Double aad = DerivedSensors.ambientAirDensity(baroKpa, ambientTempC, humidity);
        // Absolute humidity conservation for MAD
        Double mad = DerivedSensors.manifoldAirDensity(
                mapKpa, iatTempC, baroKpa, ambientTempC, humidity);
        Double bad = DerivedSensors.boostAirDensity(mad, aad);
        Double densityPct = DerivedSensors.airDensityPercent(aad);
        Double densityAlt = DerivedSensors.densityAltitudeFt(baroKpa, ambientTempC, humidity);
        Double saeCF = DerivedSensors.saeJ1349CorrectionFactor(baroKpa, ambientTempC, humidity);
        Double grains = DerivedSensors.grainsH2O(ambientTempC, humidity, baroKpa);

        return new AirDensityResult(aad, mad, bad, densityPct, densityAlt, saeCF, grains,
                humidity, baroKpa, ambientTempC, mapKpa, iatTempC,
                qualityStatus(), getHumiditySource(), getBaroSource(), getAmbientTempSource(),
                mapFromObd, iatFromObd);
    }

    public AdvancedAirDensity.AdvancedResult computeAdvanced(
            Double mafGs, Double rpm, Double lambda,
            FuelMode fuelMode, double displacementCC, double ratedRPM) {

        double humidity = getHumidity();
        double baroKpa = getBaroKpa();
        double ambientTempC = getAmbientTempC();
        double mapKpa = (obdMapKpa != null) ? obdMapKpa : baroKpa;
        double iatTempC = (obdIatTempC != null) ? obdIatTempC : ambientTempC;

        Double aadKgM3 = DerivedSensors.airDensityKgM3(baroKpa, ambientTempC, humidity);
        Double madKgM3 = DerivedSensors.manifoldAirDensityKgM3(
                mapKpa, iatTempC, baroKpa, ambientTempC, humidity);

        return AdvancedAirDensity.computeAll(
                aadKgM3, madKgM3,
                baroKpa, mapKpa,
                ambientTempC, iatTempC,
                humidity, mafGs,
                rpm, lambda,
                fuelMode, displacementCC, ratedRPM);
    }

    // ── Sample emission (shared between LoggerService + MainActivity) ──

    private static double sourceCode(String src) {
        if (SRC_OBD.equals(src)) return 1.0;
        if (SRC_SENSOR.equals(src)) return 2.0;
        if (SRC_WEATHER.equals(src)) return 3.0;
        return 4.0; // default
    }

    private static double qualityCode(String quality) {
        if ("ok".equals(quality)) return 0.0;
        if ("est".equals(quality)) return 1.0;
        return 2.0; // default
    }

    /**
     * Append AAD/MAD/BAD + advanced AeroDensity samples into the logger record.
     * Uses quality-aware statuses: ok / est / default / assumed / estimate.
     *
     * @param samples record sample list
     * @param mafGs MAF from batch
     * @param rpm Engine RPM from batch
     * @param lambda Lambda from batch
     * @param fuelMode active fuel
     * @param displacementCC engine displacement
     * @param ratedRPM rated peak power RPM
     * @param displacementUserSet if false, VE/TMF/PDI stamped "assumed"
     */
    public void appendSamples(java.util.List<SensorSample> samples,
                              Double mafGs, Double rpm, Double lambda,
                              FuelMode fuelMode, double displacementCC, double ratedRPM,
                              boolean displacementUserSet) {
        appendSamples(samples, mafGs, rpm, lambda, null, fuelMode,
                displacementCC, ratedRPM, displacementUserSet);
    }

    /**
     * Emit density and AFR samples while keeping measured and commanded lambda
     * semantically separate. Commanded equivalence ratio is useful as a target,
     * but is never allowed to drive actual-AFR, ECC, or density calculations.
     */
    public void appendSamples(java.util.List<SensorSample> samples,
                              Double mafGs, Double rpm, Double actualLambda,
                              Double commandedLambda, FuelMode fuelMode,
                              double displacementCC, double ratedRPM,
                              boolean displacementUserSet) {
        if (samples == null) return;

        // Soft async weather refresh if TTL expired (never blocks OBD thread)
        if (needsWeatherRefresh()) {
            refreshWeather();
        }

        AirDensityResult dr;
        try {
            dr = compute();
        } catch (Exception e) {
            Log.w(TAG, "Air density compute failed non-fatally", e);
            return;
        }
        if (dr == null) return;

        String q = dr.qualityStatus != null ? dr.qualityStatus : "default";
        if (dr.aad != null) {
            samples.add(new SensorSample("derived_aad", "Ambient Air Density",
                    dr.aad, "lbs/1000ft3", q));
        }
        // MAD needs MAP; degrade status if MAP not from OBD
        String madStatus = q;
        if (!dr.mapFromObd && !"default".equals(q)) madStatus = "est";
        if (dr.mad != null) {
            samples.add(new SensorSample("derived_mad", "Manifold Air Density",
                    dr.mad, "lbs/1000ft3", madStatus));
        }
        if (dr.bad != null) {
            samples.add(new SensorSample("derived_bad", "Boost Air Density",
                    dr.bad, "lbs/1000ft3", madStatus));
        }
        if (dr.densityPercent != null) {
            samples.add(new SensorSample("derived_density_pct", "Air Density %",
                    dr.densityPercent, "%", q));
        }
        if (dr.densityAltitudeFt != null) {
            samples.add(new SensorSample("derived_density_alt", "Density Altitude",
                    (double) dr.densityAltitudeFt, "ft", q));
        }
        if (dr.saeJ1349CF != null) {
            samples.add(new SensorSample("derived_sae_cf", "SAE J1349 CF",
                    dr.saeJ1349CF, "", q));
        }
        if (dr.grainsH2O != null) {
            samples.add(new SensorSample("derived_grains", "Grains H2O",
                    dr.grainsH2O, "grains/lb", q));
        }
        samples.add(new SensorSample("derived_humidity", "Relative Humidity",
                dr.humidity, "%", SRC_DEFAULT.equals(dr.humiditySource) ? "default" : "ok"));
        samples.add(new SensorSample("derived_aad_quality", "AeroDensity Quality",
                qualityCode(q), "code", "ok"));
        samples.add(new SensorSample("derived_baro_src", "Baro Source",
                sourceCode(dr.baroSource), "code", "ok"));
        samples.add(new SensorSample("derived_rh_src", "RH Source",
                sourceCode(dr.humiditySource), "code", "ok"));

        appendAfrSamples(samples, actualLambda, commandedLambda, fuelMode);

        // Advanced block. Only measured lambda may drive these calculations.
        try {
            AdvancedAirDensity.AdvancedResult ar = computeAdvanced(
                    mafGs, rpm, actualLambda, fuelMode, displacementCC, ratedRPM);
            if (ar == null) return;

            String advBase = q;
            // Samples whose math depends on manifold pressure inherit the MAD
            // status, so a MAP synthesized from Engine Load degrades them to
            // "est" instead of logging them as measured.
            String mapDepStatus = madStatus;
            String displStatus = displacementUserSet ? mapDepStatus : "assumed";
            String ceStatus = ar.ceIsEstimate ? "estimate" : mapDepStatus;

            if (ar.omdLbs != null) {
                samples.add(new SensorSample("derived_omd", "Oxygen Mass Density",
                        ar.omdLbs, "lbs/1000ft3", advBase));
            }
            if (ar.compressorEff != null) {
                samples.add(new SensorSample("derived_compressor_eff",
                        "Compressor Efficiency", ar.compressorEff, "%", ceStatus));
            }
            if (ar.intercoolerEff != null) {
                samples.add(new SensorSample("derived_intercooler_eff",
                        "Intercooler Effectiveness", ar.intercoolerEff, "%", ceStatus));
            }
            if (ar.vePct != null) {
                samples.add(new SensorSample("derived_ve",
                        "Volumetric Efficiency", ar.vePct, "%", displStatus));
            }
            if (ar.dcafr != null) {
                samples.add(new SensorSample("derived_dcafr",
                        "Density-Corrected AFR", ar.dcafr, "", mapDepStatus));
            }
            if (ar.tmfGs != null) {
                samples.add(new SensorSample("derived_tmf",
                        "Theoretical Mass Flow", ar.tmfGs, "g/s", displStatus));
            }
            if (ar.mafDeviationPct != null) {
                samples.add(new SensorSample("derived_maf_dev",
                        "MAF Deviation", ar.mafDeviationPct, "%", displStatus));
            }
            if (ar.lvdFraction != null) {
                samples.add(new SensorSample("derived_lvd",
                        "Vapor Displacement", ar.lvdFraction, "fraction", advBase));
            }
            if (ar.effectiveDensityKgM3 != null) {
                samples.add(new SensorSample("derived_eff_density",
                        "Effective Air Density", ar.effectiveDensityKgM3, "kg/m3", mapDepStatus));
            }
            if (ar.eccDeltaT != null) {
                samples.add(new SensorSample("derived_ecc_dt",
                        "Evap Cooling DeltaT", ar.eccDeltaT, "C", advBase));
            }
            if (ar.eccCorrectedMAD != null) {
                samples.add(new SensorSample("derived_ecc_mad",
                        "Evap-Corrected MAD", ar.eccCorrectedMAD, "lbs/1000ft3", mapDepStatus));
            }
            if (ar.pdi != null) {
                samples.add(new SensorSample("derived_pdi",
                        "Power Density Index", ar.pdi, "", displStatus));
            }
            if (ar.saeJ607CF != null) {
                samples.add(new SensorSample("derived_sae_j607",
                        "SAE J607 CF", ar.saeJ607CF, "", advBase));
            }
            if (ar.saeCFDelta != null) {
                samples.add(new SensorSample("derived_sae_cf_delta",
                        "SAE CF Delta", ar.saeCFDelta, "", advBase));
            }
        } catch (Exception advEx) {
            Log.w(TAG, "Advanced air density computation failed non-fatally", advEx);
        }
    }

    /** AFR is useful independently of the optional AeroDensity panel. */
    public static void appendAfrSamples(java.util.List<SensorSample> samples,
                                        Double actualLambda, Double commandedLambda,
                                        FuelMode fuelMode) {
        if (samples == null) return;
        Double actualAfr = AdvancedAirDensity.airFuelRatio(actualLambda, fuelMode);
        Double commandedAfr = AdvancedAirDensity.airFuelRatio(commandedLambda, fuelMode);
        samples.add(new SensorSample("derived_lambda_source", "Actual Lambda Source",
                actualAfr != null ? 1.0 : 0.0, "code", actualAfr != null ? "measured" : "unavailable"));
        samples.add(new SensorSample("derived_afr_quality", "Actual AFR Quality",
                actualAfr != null ? 1.0 : 0.0, "code", actualAfr != null ? "measured" : "unavailable"));
        if (actualAfr != null) {
            samples.add(new SensorSample("derived_actual_afr", "Actual AFR",
                    actualAfr, ":1", "measured"));
        }
        if (commandedAfr != null) {
            samples.add(new SensorSample("derived_commanded_afr", "Commanded AFR",
                    commandedAfr, ":1", "commanded"));
        }
    }
}
