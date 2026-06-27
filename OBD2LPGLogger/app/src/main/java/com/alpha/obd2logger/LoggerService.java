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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private LoggerCallback getCallback() {
        WeakReference<LoggerCallback> ref = callbackRef;
        return ref != null ? ref.get() : null;
    }

    public static void setPendingConfig(LoggerConfig config) {
        pendingConfig = config;
    }

    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private BaseDriver driver;
    private volatile boolean running = false;
    private DataWriter writer;
    private int recordCount = 0;
    
    private ApiServer apiServer;

    public interface LoggerCallback {
        void onRecord(DataRecord record, int count);
        void onStatus(String status, boolean isError);
        void onStopped(int totalRecords);
        void onVinRead(String vin);
        void onPidsDetected(int supportedCount, int totalCount, boolean fromLiveQuery);
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
        pendingConfig = null;

        startForeground(NOTIFICATION_ID, buildNotification("Starting OBD2 logger...", 0));
        acquireWakeLock();
        startLogging(config);
        return START_STICKY;
    }

    private void startLogging(LoggerConfig config) {
        running = true;
        recordCount = 0;
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> runLogger(config));
    }

    private void runLogger(LoggerConfig config) {
        String sessionId = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        driver = DriverFactory.create(config);

        if (!driver.isConnected() && !driver.connect()) {
            running = false;
            notifyStatus("Connection failed. Check adapter and settings.", true);
            releaseWakeLock();
            stopForeground(true);
            stopSelf();
            return;
        }

        if (config.transportMode == TransportMode.AUTO && driver instanceof SimulationDriver) {
            notifyStatus("Auto probe failed — running simulation.", false);
        } else {
            notifyStatus("Connected. Logging in background.", false);
        }

        if (config.vin == null || config.vin.isEmpty()) {
            String vin = VinReader.readVin(driver);
                if (vin != null) {
                    config.vin = vin;
                    LoggerCallback cb = getCallback();
                    if (cb != null) {
                        mainHandler.post(() -> cb.onVinRead(vin));
                    }
                }
        }
        
        if (config.enableApiServer) {
            try {
                apiServer = new ApiServer(8080);
                apiServer.start();
                Log.i(TAG, "ApiServer started on port 8080");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start ApiServer", e);
            }
        }

        // --- Auto-detect supported PIDs ---
        List<PIDDefinition> allPids = config.lpgOnlyMode ? PIDCatalogue.getLpgCritical() : PIDCatalogue.getAll();
        List<PIDDefinition> pids = allPids;
        boolean detectedFromLive = false;

        if (driver instanceof SimulationDriver) {
            // Simulation: use the simulated profile for realism
            List<String> supportedHex = PidAvailabilityChecker.querySupportedPids(driver);
            if (supportedHex != null) {
                pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                detectedFromLive = true;
            }
        } else if (driver instanceof ElmDriver) {
            // Real adapter: query the vehicle for supported PIDs
            notifyStatus("Detecting supported PIDs...", false);
            List<String> supportedHex = PidAvailabilityChecker.querySupportedPids(driver);

            if (supportedHex != null && !supportedHex.isEmpty()) {
                pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                detectedFromLive = true;
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
                // If VIN profile also failed, keep allPids (current behavior)
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

        try {
            writer = new DataWriter(this, sessionId, finalPids);
            updateNotification("Logging: 0 records", 0);

            while (running) {
                Map<String, Double> batch = driver.queryPidBatch(finalPids);
                List<SensorSample> samples = new ArrayList<>();
                for (PIDDefinition pid : finalPids) {
                    Double value = batch.get(pid.getName());
                    samples.add(new SensorSample(pid.key(), pid.getName(), value, pid.getUnit(),
                            value == null ? "err" : "ok"));
                }

                DataRecord record = new DataRecord(
                        iso.format(new Date()),
                        (android.os.SystemClock.elapsedRealtime() - started) / 1000.0,
                        config.fuelMode.getValue(),
                        config.vehicleBrand,
                        config.vin,
                        samples
                );

                writer.writeRecord(record);
                recordCount++;
                publishRecord(record, recordCount);
                
                if (apiServer != null) {
                    apiServer.setLatestData(record, true);
                }

                if (recordCount % 10 == 0) {
                    updateNotification("Logging: " + recordCount + " records", recordCount);
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
            if (!(e instanceof InterruptedException)) {
                notifyStatus("Logger error: " + e.getMessage(), true);
                Log.e(TAG, "Logger error", e);
            }
        } finally {
            try {
                if (writer != null) writer.close();
            } catch (Exception ignored) {
            }
            if (apiServer != null) {
                apiServer.stop();
                apiServer = null;
            }
            if (driver != null) driver.disconnect();
            releaseWakeLock();
            stopForeground(true);
            stopSelf();

            final int total = recordCount;
            LoggerCallback cb = getCallback();
            if (cb != null) {
                mainHandler.post(() -> cb.onStopped(total));
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
    }

    private void publishRecord(DataRecord record, int count) {
        LoggerCallback cb = getCallback();
        if (cb != null) {
            mainHandler.post(() -> cb.onRecord(record, count));
        }
    }

    private void notifyStatus(String status, boolean isError) {
        LoggerCallback cb = getCallback();
        if (cb != null) {
            mainHandler.post(() -> cb.onStatus(status, isError));
        }
        updateNotification(status, recordCount);
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
                .setContentTitle("OBD2 LPG Logger")
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

    @Override
    public void onDestroy() {
        stopLogging();
        releaseWakeLock();
        super.onDestroy();
    }
}
