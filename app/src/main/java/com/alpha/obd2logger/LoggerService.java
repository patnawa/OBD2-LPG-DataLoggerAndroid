package com.alpha.obd2logger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that keeps OBD2 logging alive when the app is minimised.
 * Holds a partial WakeLock so the CPU stays awake between PID polls.
 * Communicates state back to MainActivity via a static callback.
 */
public final class LoggerService extends Service {
    private static final String TAG = "LoggerService";
    private static final String CHANNEL_ID = "obd2_logger_channel";
    private static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_START = "com.alpha.obd2logger.START";
    public static final String ACTION_STOP = "com.alpha.obd2logger.STOP";

    /** Static holder — config is set before startService, read by the service thread. */
    private static volatile LoggerConfig pendingConfig;
    private static volatile WeakReference<LoggerCallback> callbackRef;
    private static volatile long currentSessionToken = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LoggerCallback getCallback() {
        WeakReference<LoggerCallback> ref = callbackRef;
        return ref != null ? ref.get() : null;
    }

    public static void setPendingConfig(LoggerConfig config) {
        pendingConfig = config;
    }

    private static volatile LoggerService instance;

    public static LoggerService getInstance() {
        return instance;
    }

    public static boolean isLoggingActive() {
        LoggerService s = instance;
        return s != null && s.running;
    }

    public LoggerConfig getConfig() {
        return activeConfig;
    }

    public BaseDriver getDriver() {
        return driver;
    }

    public int getRecordCount() {
        return recordCount;
    }

    private ExecutorService executor;
    private ExecutorService dtcExecutor;
    private PowerManager.WakeLock wakeLock;
    private BaseDriver driver;
    private volatile boolean running = false;
    private LoggerConfig activeConfig;
    private DataWriter writer;
    private int recordCount = 0;
    
    private ApiServer apiServer;

    public static final java.util.List<DtcCode> lastStoredDtcs = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static final java.util.List<DtcCode> lastPendingDtcs = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static final java.util.List<DtcCode> lastPermanentDtcs = new java.util.concurrent.CopyOnWriteArrayList<>();

    public interface DtcClearTrigger {
        boolean clear();
    }
    public static volatile DtcClearTrigger dtcClearTrigger;

    public interface LoggerCallback {
        void onRecord(DataRecord record, int count);
        void onStatus(String status, boolean isError);
        void onStopped(int totalRecords);
        void onVinRead(String vin);
        void onPidsDetected(int supportedCount, int totalCount, boolean fromLiveQuery);
        void onDeviceDetected(VLinkerOptimizer.DeviceType deviceType);
        void onAdapterCheckResult(boolean isStandard, String details);
        void onDtcAutoScan(int storedCount, int pendingCount, int permanentCount);
        void onDtcAutoScanDetails(DtcReader.DtcScanResult result);
        void onNewDtcDetected(java.util.List<DtcCode> newCodes);
    }

    public static void setCallback(LoggerCallback cb) {
        callbackRef = cb != null ? new WeakReference<>(cb) : null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DriverFactory.setAppContext(this.getApplicationContext());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopLogging();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        LoggerConfig config = pendingConfig;
        if (config == null) {
            config = new LoggerConfig();
        }
        if (config.context == null) {
            config.context = getApplicationContext();
        }
        pendingConfig = null;

        long sessionToken = System.currentTimeMillis();
        currentSessionToken = sessionToken;

        // A throw here runs on the system's binder thread and takes down the WHOLE
        // app (the service worker thread's own try/catch does not cover onStartCommand).
        // Guard every step so a foreground-service startup failure degrades to a
        // clean stop with a user-visible error instead of a crash.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int fgsType = (config != null && (config.transportMode == TransportMode.SIM || config.transportMode == TransportMode.WIFI))
                        ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        : android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                try {
                    startForeground(NOTIFICATION_ID, buildNotification("Starting OBD2 logger...", 0), fgsType);
                } catch (Exception e1) {
                    try {
                        startForeground(NOTIFICATION_ID, buildNotification("Starting OBD2 logger...", 0),
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } catch (Exception e2) {
                        startForeground(NOTIFICATION_ID, buildNotification("Starting OBD2 logger...", 0));
                    }
                }
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Starting OBD2 logger...", 0));
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed — cannot run as foreground service", e);
            notifyStatus("Background logging failed to start: " + e.getMessage(), true);
            // Fire onStopped so the UI (running flag, FAB) returns to a startable
            // state instead of freezing on "Connecting…".
            notifyStopped();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            acquireWakeLock();
        } catch (Exception e) {
            Log.w(TAG, "acquireWakeLock failed", e);
        }
        startLogging(config, sessionToken);
        return START_STICKY;
    }

    private void startLogging(LoggerConfig config, long sessionToken) {
        // Guard against double-start: if a previous logging session is still
        // running (e.g. START intent received twice), shut it down first so we
        // don't orphan the old executor thread or race on driver/writer fields.
        if (executor != null) {
            stopLogging();
        }
        running = true;
        recordCount = 0;
        executor = Executors.newSingleThreadExecutor();
        dtcExecutor = Executors.newSingleThreadExecutor();
        executor.submit(() -> runLogger(config, sessionToken));
    }

    private void runLogger(LoggerConfig config, long sessionToken) {
        if (currentSessionToken == sessionToken) {
            activeConfig = config;
        }
        String fuelPrefix = config.fuelMode != null ? config.fuelMode.name() + "_" : "";
        String simPrefix = (config.transportMode == TransportMode.SIM) ? "Sim_" : "";
        String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String sessionId = simPrefix + fuelPrefix + timeStr;
        
        BaseDriver localDriver = DriverFactory.create(config);
        if (currentSessionToken == sessionToken) {
            driver = localDriver;
        }

        // connect() can hang on some adapters/Android versions (e.g. a Bluetooth
        // socket that never completes). Bound it with a timeout so a dead adapter
        // reports "Connection failed" instead of freezing on "Connecting…" forever.
        java.util.concurrent.Future<Boolean> connectTask =
                Executors.newSingleThreadExecutor().submit(() -> localDriver.isConnected() || localDriver.connect());
        boolean connectedResult;
        try {
            connectedResult = connectTask.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            connectTask.cancel(true);
            try { localDriver.disconnect(); } catch (Throwable ignored) {}
            connectedResult = false;
            Log.w(TAG, "driver.connect() timed out after 15s");
        } catch (Exception e) {
            connectTask.cancel(true);
            connectedResult = false;
        }

        if (!connectedResult) {
            if (currentSessionToken == sessionToken) {
                running = false;
                driver = null;
                notifyStatus("Connection failed. Check adapter and settings.", true);
                notifyStopped();
                releaseWakeLock();
                stopForeground(true);
                stopSelf();
            } else {
                try { localDriver.disconnect(); } catch (Throwable ignored) {}
            }
            return;
        }

        if (config.transportMode == TransportMode.AUTO && localDriver instanceof SimulationDriver) {
            notifyStatus("Auto probe failed — running simulation.", false);
        } else {
            notifyStatus("Connected. Logging in background.", false);
        }

        // Notify UI of detected vLinker device type
        if (localDriver instanceof ElmDriver) {
            final VLinkerOptimizer.DeviceType dt = ((ElmDriver) localDriver).getVlinkerType();
            LoggerCallback cb0 = getCallback();
            if (cb0 != null && dt != VLinkerOptimizer.DeviceType.UNKNOWN) {
                mainHandler.post(() -> cb0.onDeviceDetected(dt));
            }
        }

        // Notify UI of adapter standard/clone validation result
        final boolean isStd = localDriver.isStandardAdapter();
        final String details = localDriver.getAdapterDetails();
        LoggerCallback cbCheck = getCallback();
        if (cbCheck != null) {
            mainHandler.post(() -> cbCheck.onAdapterCheckResult(isStd, details));
        }

        if (config.vin == null || config.vin.isEmpty()) {
            String vin = VinReader.readVin(localDriver);
                if (vin != null) {
                    config.vin = vin;
                    LoggerCallback cb = getCallback();
                    if (cb != null) {
                        mainHandler.post(() -> cb.onVinRead(vin));
                    }
                }
        }
        
        ApiServer localApiServer = null;
        if (config.enableApiServer) {
            try {
                localApiServer = new ApiServer(8080);
                localApiServer.setDtcProvider(new ApiServer.DtcProvider() {
                    @Override
                    public java.util.List<DtcCode> getStoredDtcs() {
                        return lastStoredDtcs;
                    }

                    @Override
                    public java.util.List<DtcCode> getPendingDtcs() {
                        return lastPendingDtcs;
                    }

                    @Override
                    public boolean triggerClearDtcs() {
                        DtcClearTrigger trigger = dtcClearTrigger;
                        if (trigger != null) {
                            return trigger.clear();
                        }
                        return false;
                    }
                });
                localApiServer.start();
                if (currentSessionToken == sessionToken) {
                    apiServer = localApiServer;
                }
                Log.i(TAG, "ApiServer started on port 8080");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start ApiServer", e);
            }
        }

        // --- Auto-scan DTCs (module-aware, supports Ford MS-CAN) ---
        try {
            final BaseDriver finalDriverForDtc = localDriver;
            DtcReader.DtcScanResult scanResult = DtcReader.readAllDtcs(finalDriverForDtc, config.fordMsCanEnabled);
            
            lastStoredDtcs.clear();
            lastStoredDtcs.addAll(scanResult.storedDtcs);
            lastPendingDtcs.clear();
            lastPendingDtcs.addAll(scanResult.pendingDtcs);
            lastPermanentDtcs.clear();
            lastPermanentDtcs.addAll(scanResult.permanentDtcs);
            
            LoggerCallback cbDtc = getCallback();
            if (cbDtc != null) {
                mainHandler.post(() -> cbDtc.onDtcAutoScan(
                    scanResult.storedDtcs.size(),
                    scanResult.pendingDtcs.size(),
                    scanResult.permanentDtcs.size()));
                mainHandler.post(() -> cbDtc.onDtcAutoScanDetails(scanResult));
            }
        } catch (Exception e) {
            Log.e(TAG, "DTC Auto-scan failed", e);
        }

        // --- Auto-detect supported PIDs ---
        List<PIDDefinition> allPids;
        if (config.customPidsEnabled) {
            allPids = config.lpgOnlyMode ? PIDCatalogue.getLpgPollSet() : PIDCatalogue.getAllWithCustom(this);
        } else {
            allPids = config.lpgOnlyMode ? PIDCatalogue.getLpgPollSet() : PIDCatalogue.getAll();
        }
        // Always make a mutable copy — PIDCatalogue returns unmodifiable lists,
        // and removeAll() in the logging loop would throw UnsupportedOperationException
        // if PID detection fails and we end up using the original list.
        List<PIDDefinition> pids = new ArrayList<>(allPids);
        boolean detectedFromLive = false;

        if (localDriver instanceof SimulationDriver) {
            // Simulation: use the simulated profile for realism
            List<String> supportedHex = PidAvailabilityChecker.querySupportedPids(localDriver);
            if (supportedHex != null) {
                pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                detectedFromLive = true;
            }
        } else if (localDriver instanceof ElmDriver) {
            // Real adapter: query the vehicle for supported PIDs
            List<String> supportedHex = getCachedPids(config.vin);
            if (supportedHex != null && !supportedHex.isEmpty()) {
                pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                detectedFromLive = true;
                Log.i(TAG, "Cached PIDs loaded for VIN " + config.vin + ": " + pids.size() + " PIDs");
            } else {
                notifyStatus("Detecting supported PIDs...", false);
                supportedHex = PidAvailabilityChecker.querySupportedPids(localDriver);

                if (supportedHex != null && !supportedHex.isEmpty()) {
                    pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                    detectedFromLive = true;
                    cachePids(config.vin, supportedHex);
                    Log.i(TAG, "Live detection: " + pids.size() + "/" + allPids.size() + " PIDs supported");
                } else {
                    // Fallback: use VIN-based brand/year profile
                    Log.w(TAG, "Live PID detection failed — trying VIN-based profile");
                    java.util.Set<String> brandPids = BrandYearProfile.getProfileFromVin(config.vin);
                    if (brandPids != null) {
                        pids = PidAvailabilityChecker.filterCatalogue(
                                new ArrayList<>(brandPids), allPids);
                        Log.i(TAG, "VIN profile: " + pids.size() + "/" + allPids.size() + " PIDs");
                    }
                }
            }

            // Notify UI of detection results
            final int detectedCount = pids.size();
            final int totalCount = allPids.size();
            final boolean fromLive = detectedFromLive;
            LoggerCallback cb = getCallback();
            if (cb != null) {
                mainHandler.post(() -> cb.onPidsDetected(detectedCount, totalCount, fromLive));
            }
        }

        final List<PIDDefinition> finalPids = pids;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        long started = android.os.SystemClock.elapsedRealtime();
        final java.util.Map<String, Integer> consecutiveFailures = new java.util.HashMap<>();

        DataWriter localWriter = null;
        int localRecordCount = 0;

        try {
            localWriter = new DataWriter(this, sessionId, finalPids, config.vin);
            if (currentSessionToken == sessionToken) {
                writer = localWriter;
            }
            updateNotification("Logging: 0 records", 0);
            long lastDtcCheckMs = android.os.SystemClock.elapsedRealtime();
            int retryCount = 0;
            int maxRetries = 3;

            while (running) {
                try {
                    if (!localDriver.isConnected()) {
                        if (retryCount > 0) {
                            final int finalRetry = retryCount;
                            notifyStatus("Connection lost. Reconnecting (" + finalRetry + "/" + maxRetries + ")...", false);
                        }
                        if (!localDriver.connect()) {
                            throw new java.io.IOException("Reconnection failed");
                        }
                        retryCount = 0;
                        notifyStatus("Connected. Logging resumed.", false);
                    }

                    while (running) {
                        long nowMs = android.os.SystemClock.elapsedRealtime();
                        // ── Continuous DTC Monitor: poll Mode 01 PID 01 every 30s ──
                        // Instead of a full Mode 03/07/0A scan every 60s (which pauses
                        // logging and is slow), we poll just PID 01 which returns:
                        //   - MIL status (on/off)
                        //   - DTC count (stored)
                        // If the count changes, we trigger a full scan immediately.
                        // This is how professional scanners do real-time DTC detection.
                        if (nowMs - lastDtcCheckMs >= 30000) {
                            lastDtcCheckMs = nowMs;
                            ExecutorService de = dtcExecutor;
                            if (de != null && !de.isShutdown()) {
                                final BaseDriver finalDriverForDtcLoop = localDriver;
                                de.submit(() -> {
                                    MainActivity.isPaused = true;
                                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                                    try {
                                        // Quick poll: Mode 01 PID 01 (readiness + DTC count)
                                        ReadinessMonitor readiness = ReadinessMonitor.read(finalDriverForDtcLoop);
                                        int currentDtcCount = readiness.getDtcCount();
                                        boolean currentMil = readiness.isMilOn();

                                        // Count previous stored DTCs
                                        int prevCount = lastStoredDtcs.size();

                                        if (currentDtcCount != prevCount || (currentMil && prevCount == 0)) {
                                            // DTC count changed OR MIL is on but we have no stored codes
                                            // → trigger a full scan to get the actual codes
                                            DtcReader.DtcScanResult sr = DtcReader.readAllDtcs(
                                                finalDriverForDtcLoop, activeConfig != null && activeConfig.fordMsCanEnabled);
                                            List<DtcCode> stored = sr.storedDtcs;
                                            List<DtcCode> pending = sr.pendingDtcs;
                                            List<DtcCode> permanent = sr.permanentDtcs;

                                            // Detect new codes
                                            List<DtcCode> newCodes = new ArrayList<>();
                                            for (DtcCode c : stored) {
                                                if (!lastStoredDtcs.contains(c)) {
                                                    newCodes.add(c);
                                                }
                                            }
                                            for (DtcCode c : pending) {
                                                if (!lastPendingDtcs.contains(c)) {
                                                    newCodes.add(c);
                                                }
                                            }

                                            lastStoredDtcs.clear();
                                            lastStoredDtcs.addAll(stored);
                                            lastPendingDtcs.clear();
                                            lastPendingDtcs.addAll(pending);
                                            lastPermanentDtcs.clear();
                                            lastPermanentDtcs.addAll(permanent);

                                            if (!newCodes.isEmpty()) {
                                                LoggerCallback cbDtc = getCallback();
                                                if (cbDtc != null) {
                                                    mainHandler.post(() -> cbDtc.onNewDtcDetected(newCodes));
                                                }
                                            }
                                        }
                                        // If count is same, no need to do a full scan —
                                        // the quick PID 01 poll is enough to confirm no new DTCs
                                    } catch (Exception e) {
                                        Log.e(TAG, "Background periodic DTC monitor failed", e);
                                    } finally {
                                        MainActivity.isPaused = false;
                                    }
                                });
                            }
                        }
                        if (MainActivity.isPaused) {
                            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                            continue;
                        }
                        Map<String, Double> batch = localDriver.queryPidBatch(finalPids);
                        List<SensorSample> samples = new ArrayList<>();
                        List<PIDDefinition> toRemove = new ArrayList<>();

                        // ── Raw PID samples ──────────────────────
                        for (PIDDefinition pid : finalPids) {
                            Double value = batch.get(pid.getName());
                            samples.add(new SensorSample(pid.key(), pid.getName(), value, pid.getUnit(),
                                    value == null ? "err" : "ok"));

                            if (value == null) {
                                int fails = consecutiveFailures.getOrDefault(pid.key(), 0) + 1;
                                consecutiveFailures.put(pid.key(), fails);
                                if (fails >= 3) {
                                    toRemove.add(pid);
                                }
                            } else {
                                consecutiveFailures.put(pid.key(), 0);
                            }
                        }

                        // ── Derived sensors ──────────────────────
                        // Look up raw values from batch by PID name
                        Double mafValue = batch.get("MAF Air Flow");
                        Double speedValue = batch.get("Vehicle Speed");
                        Double mapValue = batch.get("Intake Manifold Pressure");
                        Double baroValue = batch.get("Barometric Pressure");
                        Double dpfSoot = batch.get("DPF Soot Load");
                        Double dpfTemp = batch.get("DPF Temperature");
                        Double dpfDelta = batch.get("DPF Delta Pressure");
                        Double dpfRegen = batch.get("DPF Regen Status");
                        Double dpfAsh = batch.get("DPF Ash Load");

                        // Fuel Consumption
                        if (config.showFuelConsumption && mafValue != null && speedValue != null) {
                            Double kml = DerivedSensors.fuelConsumptionKmL(mafValue, speedValue, config.fuelMode);
                            if (kml != null) {
                                samples.add(new SensorSample("derived_fuel_kmL", "Fuel Economy", kml, "km/L", "ok"));
                                Double l100 = DerivedSensors.fuelConsumptionL100km(mafValue, speedValue, config.fuelMode);
                                if (l100 != null) {
                                    samples.add(new SensorSample("derived_fuel_l100", "Fuel Economy", l100, "L/100km", "ok"));
                                }
                            }
                        }

                        // Turbo Boost
                        if (config.showTurboBoost && mapValue != null) {
                            Double boostKpa = DerivedSensors.boostPressureKpa(mapValue, baroValue);
                            if (boostKpa != null) {
                                samples.add(new SensorSample("derived_boost_kpa", "Turbo Boost", boostKpa, "kPa", "ok"));
                                Double boostPsi = DerivedSensors.boostPressurePsi(mapValue, baroValue);
                                if (boostPsi != null) {
                                    samples.add(new SensorSample("derived_boost_psi", "Turbo Boost", boostPsi, "psi", "ok"));
                                }
                            }
                        }

                        // DPF Status (derived interpretations)
                        if (config.dpfMonitorEnabled) {
                            if (dpfSoot != null) {
                                String dpfHealth = DerivedSensors.dpfHealthStatus(dpfSoot, dpfAsh);
                                samples.add(new SensorSample("derived_dpf_health", "DPF Health", 
                                    "Clean".equals(dpfHealth) ? 1.0 : "Moderate".equals(dpfHealth) ? 2.0 
                                    : "Warning".equals(dpfHealth) ? 3.0 : 4.0, "status", "ok"));
                            }
                            if (dpfRegen != null) {
                                String regen = DerivedSensors.dpfRegenStatus(dpfRegen);
                                double regenCode = "Regen Active".equals(regen) ? 1.0 : 0.0;
                                samples.add(new SensorSample("derived_dpf_regen", "DPF Regen", regenCode, "active=" + regen, "ok"));
                            }
                        }
                        
                        if (!toRemove.isEmpty()) {
                            finalPids.removeAll(toRemove);
                            for (PIDDefinition p : toRemove) {
                                Log.w(TAG, "Blacklisted unsupported PID: " + p.key() + " (" + p.getName() + ")");
                            }
                        }

                        DataRecord record = new DataRecord(
                                iso.format(new Date()),
                                (android.os.SystemClock.elapsedRealtime() - started) / 1000.0,
                                config.fuelMode.getValue(),
                                config.vehicleBrand,
                                config.vin,
                                samples
                        );

                        localWriter.writeRecord(record);
                        localRecordCount++;
                        if (currentSessionToken == sessionToken) {
                            recordCount = localRecordCount;
                        }
                        publishRecord(record, localRecordCount);

                        // Low-voltage watchdog: a weak alternator / battery causes lean
                        // misfires that masquerade as fuel-trim problems — especially on
                        // LPG. Warn once per session (transient dips ignored) when module
                        // voltage sags below the charging floor.
                        checkVoltageWatchdog(record);

                        if (localApiServer != null) {
                            localApiServer.setLatestData(record, true);
                        }

                        if (localRecordCount % 10 == 0) {
                            updateNotification("Logging: " + localRecordCount + " records", localRecordCount);
                        }

                        try {
                            Thread.sleep(config.sampleIntervalMs);
                        } catch (InterruptedException ie) {
                            // shutdownNow() interrupted our sleep — exit the loop gracefully
                            running = false;
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    if (!running || e instanceof InterruptedException) {
                        break;
                    }
                    if (localDriver != null) localDriver.disconnect();
                    retryCount++;
                    if (retryCount > maxRetries) {
                        running = false;
                        notifyStatus("Logger disconnected permanently: " + e.getMessage(), true);
                        break;
                    }
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (!(e instanceof InterruptedException)) {
                notifyStatus("Logger error: " + e.getMessage(), true);
                Log.e(TAG, "Logger error", e);
            }
        } finally {
            try {
                if (localWriter != null) localWriter.close();
            } catch (Exception ignored) {
            }
            if (localApiServer != null) {
                try { localApiServer.stop(); } catch (Exception ignored) {}
            }
            if (localDriver != null) {
                try { localDriver.disconnect(); } catch (Exception ignored) {}
            }
            releaseWakeLock();

            if (currentSessionToken == sessionToken) {
                writer = null;
                apiServer = null;
                driver = null;

                stopForeground(true);
                stopSelf();

                final int total = localRecordCount;
                LoggerCallback cb = getCallback();
                if (cb != null) {
                    mainHandler.post(() -> cb.onStopped(total));
                }
            }
        }
    }

    private void stopLogging() {
        running = false;
        if (executor != null) {
            // shutdownNow() interrupts the running logger thread so Thread.sleep()
            // throws InterruptedException and the loop exits immediately instead
            // of waiting for the full sample interval to elapse.
            executor.shutdownNow();
            // Don't awaitTermination here because stopLogging() is called on the main thread
            // and blocking here can cause ANR (Application Not Responding).
            executor = null;
        }
        if (dtcExecutor != null) {
            dtcExecutor.shutdownNow();
            dtcExecutor = null;
        }
    }

    private void publishRecord(DataRecord record, int count) {
        LoggerCallback cb = getCallback();
        if (cb != null) {
            mainHandler.post(() -> cb.onRecord(record, count));
        }
    }

    // Once-per-session latch so the low-voltage warning fires only once (not every tick).
    private boolean voltageWarned = false;

    /**
     * Warn when Control Module Voltage sags below the LPG charging floor. A weak
     * alternator / battery causes lean misfires that look like a fuel-trim problem,
     * so surfacing it early saves the user from chasing the wrong issue. Voltage is
     * read from the record's samples (key "01_42", which is lpgCritical=true and is
     * always polled), so no extra query is needed.
     */
    private void checkVoltageWatchdog(DataRecord record) {
        LoggerConfig cfg = activeConfig;
        if (cfg == null || !cfg.lpgOnlyMode || voltageWarned) return;
        Double volts = null;
        for (SensorSample s : record.getSamples()) {
            if ("01_42".equals(s.getPidKey())) { volts = s.getValue(); break; }
        }
        // 13.0 V is a conservative "not charging well" floor for an LPG-running vehicle.
        if (volts != null && volts < 13.0) {
            voltageWarned = true;
            notifyStatus("⚠ Low voltage " + Math.round(volts * 10.0) / 10.0
                    + "V — check alternator/battery (causes lean misfire on LPG)", true);
        }
    }

    private void notifyStatus(String status, boolean isError) {
        LoggerCallback cb = getCallback();
        if (cb != null) {
            mainHandler.post(() -> cb.onStatus(status, isError));
        }
        updateNotification(status, recordCount);
    }

    private void notifyStopped() {
        LoggerCallback cb = getCallback();
        if (cb != null) {
            final int total = recordCount;
            mainHandler.post(() -> cb.onStopped(total));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "OBD2 Logger", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("OBD2 data logging in progress");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text, int recordCount) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TunerMap Pro")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text, int recordCount) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text, recordCount));
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBD2Logger::LoggingWakeLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(8 * 60 * 60 * 1000L); // 8 hours max
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    private List<String> getCachedPids(String vin) {
        if (vin == null || vin.isEmpty()) return null;
        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        String cached = prefs.getString("pids_cache_" + vin, null);
        if (cached == null || cached.isEmpty()) return null;
        
        List<String> list = new ArrayList<>();
        for (String s : cached.split(",")) {
            list.add(s.trim());
        }
        return list;
    }

    private void cachePids(String vin, List<String> pids) {
        if (vin == null || vin.isEmpty() || pids == null || pids.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String s : pids) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        android.content.SharedPreferences prefs = getSharedPreferences("OBD2Prefs", MODE_PRIVATE);
        prefs.edit().putString("pids_cache_" + vin, sb.toString()).apply();
    }

    @Override
    public void onDestroy() {
        stopLogging();
        releaseWakeLock();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
