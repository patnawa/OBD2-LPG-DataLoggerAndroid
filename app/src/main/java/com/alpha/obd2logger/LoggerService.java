package com.alpha.obd2logger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground service that keeps OBD2 logging alive when the app is minimised.
 * Holds a partial WakeLock so the CPU stays awake between PID polls.
 * Communicates state back to MainActivity via a static callback.
 */
public final class LoggerService extends Service {
    private static final String TAG = "LoggerService";
    static final String CHANNEL_ID = "obd2_logger_channel";
    private static final int NOTIFICATION_ID = 2001;

    public static final String ACTION_START = "com.alpha.obd2logger.START";
    public static final String ACTION_STOP = "com.alpha.obd2logger.STOP";

    /** Static holder — config is set before startService, read by the service thread. */
    private static volatile LoggerConfig pendingConfig;
    private static volatile WeakReference<LoggerCallback> callbackRef;
    private static volatile long currentSessionToken = 0;
    private static final AtomicLong SESSION_TOKEN_SEQUENCE =
            new AtomicLong(System.currentTimeMillis());
    private static final Object SESSION_STATE_LOCK = new Object();
    /** elapsedRealtime() when the current session started — lets a recreated Activity restore its duration clock. */
    public static volatile long sessionStartElapsedMs = 0;
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

    /** Publish fresh read-only common vehicle data to the local API, if active. */
    public void publishVehicleInformation(VehicleInformationReader.Snapshot snapshot) {
        if (snapshot == null) return;
        ApiServer currentApiServer = apiServer;
        if (currentApiServer != null) currentApiServer.setVehicleInformation(snapshot);
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
    private long wakeLockSessionToken;
    private volatile BaseDriver driver;
    private volatile boolean running = false;
    private volatile LoggerConfig activeConfig;
    private DataWriter writer;
    private int recordCount = 0;

    private ApiServer apiServer;

    /**
     * A worker owns mutable service state only while both the running flag and
     * its opaque, process-unique token still match. Checking {@code running}
     * alone creates an ABA race: stop sets it false, a fast restart sets it
     * true again, and the old worker can resume as if it owned the new run.
     */
    static boolean sameActiveSession(
            boolean isRunning, long currentToken, long expectedToken) {
        return isRunning && expectedToken != 0L && currentToken == expectedToken;
    }

    private boolean ownsActiveSession(long sessionToken) {
        return sameActiveSession(running, currentSessionToken, sessionToken);
    }

    private boolean isCurrentSession(long sessionToken) {
        return sessionToken != 0L && currentSessionToken == sessionToken;
    }

    /** Atomically invalidate DTC state only for the service worker that owns it. */
    private long resetDtcSnapshotForSession(long sessionToken) {
        synchronized (SESSION_STATE_LOCK) {
            if (!sameActiveSession(running, currentSessionToken, sessionToken)) {
                return -1L;
            }
            return resetDtcSnapshotForConnection();
        }
    }

    private static long nextSessionToken() {
        long token = SESSION_TOKEN_SEQUENCE.incrementAndGet();
        // Zero is reserved as the explicit "no foreground-service session"
        // sentinel. Overflow is fantastically unlikely, but keep the contract
        // correct even under synthetic tests.
        return token != 0L ? token : SESSION_TOKEN_SEQUENCE.incrementAndGet();
    }

    /**
     * Canonical fuel-map store — single source of truth for both the UI
     * (FuelMapView) and the API server (ApiServer). Set when the logging
     * session starts, cleared when it stops.
     */
    // Written by the logging executor and read by MainActivity / API threads.
    // Without volatile publication, Bluetooth background logging could update
    // a store that the UI still observed as null or an earlier empty store.
    private volatile LiveMapStore liveMapStore;

    public LiveMapStore getLiveMapStore() {
        return liveMapStore;
    }

    /** Learned VE surface — sibling of {@link #liveMapStore}, same lifecycle. */
    private volatile VeMapStore veMapStore;

    public VeMapStore getVeMapStore() {
        return veMapStore;
    }

    /** Air density monitor — merges OBD2 + weather API for AAD/MAD/BAD */
    private AirDensityMonitor airDensityMonitor;

    static final java.util.List<DtcCode> lastStoredDtcs = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.List<DtcCode> lastPendingDtcs = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.List<DtcCode> lastPermanentDtcs = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static volatile int lastCurrentProtocolStoredDtcCount;
    private static final Object DTC_SNAPSHOT_LOCK = new Object();
    private static long dtcSnapshotGeneration;

    /**
     * Per-service validity for the three legislated DTC modes.  A positive
     * Mode 03 reply does not prove that Mode 07 or Mode 0A replied, so callers
     * must never collapse these fields into one ambiguous "scan succeeded"
     * flag.  The immutable value is published only after the matching lists
     * have been updated under {@link #DTC_SNAPSHOT_LOCK}.
     */
    public static final class DtcSnapshotValidity {
        public final long generation;
        public final boolean stored;
        public final boolean pending;
        public final boolean permanent;

        private DtcSnapshotValidity(long generation, boolean stored,
                                    boolean pending, boolean permanent) {
            this.generation = generation;
            this.stored = stored;
            this.pending = pending;
            this.permanent = permanent;
        }

        public boolean allModesValid() {
            return stored && pending && permanent;
        }

        public boolean activeModesValid() {
            return stored && pending;
        }
    }

    /** Immutable, atomically published view consumed by UI/API readers. */
    public static final class DtcSnapshot {
        public final java.util.List<DtcCode> stored;
        public final java.util.List<DtcCode> pending;
        public final java.util.List<DtcCode> permanent;
        public final DtcSnapshotValidity validity;

        private DtcSnapshot(java.util.List<DtcCode> stored,
                            java.util.List<DtcCode> pending,
                            java.util.List<DtcCode> permanent,
                            DtcSnapshotValidity validity) {
            this.stored = java.util.Collections.unmodifiableList(
                    new ArrayList<>(stored));
            this.pending = java.util.Collections.unmodifiableList(
                    new ArrayList<>(pending));
            this.permanent = java.util.Collections.unmodifiableList(
                    new ArrayList<>(permanent));
            this.validity = validity;
        }
    }

    private static volatile DtcSnapshotValidity lastDtcSnapshotValidity =
            new DtcSnapshotValidity(0L, false, false, false);
    private static volatile DtcSnapshot lastDtcSnapshot = new DtcSnapshot(
            java.util.Collections.emptyList(), java.util.Collections.emptyList(),
            java.util.Collections.emptyList(), lastDtcSnapshotValidity);
    private static final java.util.List<DtcCode> currentStoredDtcs = new ArrayList<>();
    private static final java.util.List<DtcCode> currentPendingDtcs = new ArrayList<>();
    private static final java.util.List<DtcCode> currentPermanentDtcs = new ArrayList<>();
    private static final java.util.List<DtcCode> secondaryStoredDtcs = new ArrayList<>();
    private static final java.util.List<DtcCode> secondaryPendingDtcs = new ArrayList<>();
    private static final java.util.List<DtcCode> secondaryPermanentDtcs = new ArrayList<>();

    /** Freeze-frame results from the last Full Scan, for the HTTP API. */
    public static final class FreezeFrameSnapshot {
        public final java.util.List<FreezeFrameReader.FreezeFrameEntry> perDtcFrames;
        public final FreezeFrameData genericFrame;
        public final long capturedAtMs;

        FreezeFrameSnapshot(java.util.List<FreezeFrameReader.FreezeFrameEntry> perDtcFrames,
                            FreezeFrameData genericFrame, long capturedAtMs) {
            this.perDtcFrames = java.util.Collections.unmodifiableList(
                    new ArrayList<>(perDtcFrames != null
                            ? perDtcFrames : java.util.Collections.emptyList()));
            this.genericFrame = genericFrame;
            this.capturedAtMs = capturedAtMs;
        }
    }

    private static volatile FreezeFrameSnapshot lastFreezeFrameSnapshot =
            new FreezeFrameSnapshot(null, null, 0L);

    /** Published by the Full Scan; cleared on a verified DTC clear. */
    static void setFreezeFrameSnapshot(
            java.util.List<FreezeFrameReader.FreezeFrameEntry> perDtcFrames,
            FreezeFrameData genericFrame) {
        lastFreezeFrameSnapshot = new FreezeFrameSnapshot(perDtcFrames, genericFrame,
                (perDtcFrames == null && genericFrame == null)
                        ? 0L : System.currentTimeMillis());
    }

    public static FreezeFrameSnapshot getFreezeFrameSnapshot() {
        return lastFreezeFrameSnapshot;
    }

    static long resetDtcSnapshotForConnection() {
        final long generation;
        synchronized (DTC_SNAPSHOT_LOCK) {
            generation = ++dtcSnapshotGeneration;
            lastStoredDtcs.clear();
            lastPendingDtcs.clear();
            lastPermanentDtcs.clear();
            currentStoredDtcs.clear();
            currentPendingDtcs.clear();
            currentPermanentDtcs.clear();
            secondaryStoredDtcs.clear();
            secondaryPendingDtcs.clear();
            secondaryPermanentDtcs.clear();
            lastCurrentProtocolStoredDtcCount = 0;
            publishDtcSnapshotLocked(new DtcSnapshotValidity(
                    generation, false, false, false));
        }
        MainActivity.invalidateDtcUiForNewConnection();
        return generation;
    }

    static long currentDtcSnapshotGeneration() {
        return lastDtcSnapshot.validity.generation;
    }

    static DtcSnapshotValidity getDtcSnapshotValidity() {
        return lastDtcSnapshot.validity;
    }

    static DtcSnapshot getDtcSnapshot() {
        return lastDtcSnapshot;
    }

    static DtcSnapshotValidity replaceDtcSnapshotFromScan(
            DtcReader.DtcScanResult result, boolean requireEveryPlannedBus,
            long expectedGeneration) {
        if (result == null
                || !result.hasCompleteStoredSnapshot(requireEveryPlannedBus)) return null;
        synchronized (DTC_SNAPSHOT_LOCK) {
            if (dtcSnapshotGeneration != expectedGeneration) return null;
            boolean pendingValid =
                    result.hasCompletePendingSnapshot(requireEveryPlannedBus);
            boolean permanentValid =
                    result.hasCompletePermanentSnapshot(requireEveryPlannedBus);

            replace(currentStoredDtcs, result.currentStoredDtcs);
            replace(secondaryStoredDtcs, result.secondaryStoredDtcs);
            rebuild(lastStoredDtcs, secondaryStoredDtcs, currentStoredDtcs);
            lastCurrentProtocolStoredDtcCount = currentStoredDtcs.size();

            if (pendingValid) {
                replace(currentPendingDtcs, result.currentPendingDtcs);
                replace(secondaryPendingDtcs, result.secondaryPendingDtcs);
                rebuild(lastPendingDtcs, secondaryPendingDtcs, currentPendingDtcs);
            } else {
                currentPendingDtcs.clear();
                secondaryPendingDtcs.clear();
                lastPendingDtcs.clear();
            }
            if (permanentValid) {
                replace(currentPermanentDtcs, result.currentPermanentDtcs);
                replace(secondaryPermanentDtcs, result.secondaryPermanentDtcs);
                rebuild(lastPermanentDtcs, secondaryPermanentDtcs, currentPermanentDtcs);
            } else {
                currentPermanentDtcs.clear();
                secondaryPermanentDtcs.clear();
                lastPermanentDtcs.clear();
            }
            DtcSnapshotValidity validity = new DtcSnapshotValidity(
                    expectedGeneration, true, pendingValid, permanentValid);
            publishDtcSnapshotLocked(validity);
            return validity;
        }
    }

    static DtcSnapshotValidity replaceCurrentDtcSnapshotFromQuick(
            DtcReader.DtcScanResult result, long expectedGeneration) {
        if (result == null || !result.hasCompleteStoredSnapshot(true)) return null;
        synchronized (DTC_SNAPSHOT_LOCK) {
            if (dtcSnapshotGeneration != expectedGeneration) return null;
            boolean pendingValid = result.hasCompletePendingSnapshot(true);
            boolean permanentValid = result.hasCompletePermanentSnapshot(true);

            replace(currentStoredDtcs, result.currentStoredDtcs);
            rebuild(lastStoredDtcs, secondaryStoredDtcs, currentStoredDtcs);
            lastCurrentProtocolStoredDtcCount = currentStoredDtcs.size();
            if (pendingValid) {
                replace(currentPendingDtcs, result.currentPendingDtcs);
                rebuild(lastPendingDtcs, secondaryPendingDtcs, currentPendingDtcs);
            } else {
                currentPendingDtcs.clear();
                rebuild(lastPendingDtcs, secondaryPendingDtcs, currentPendingDtcs);
            }
            if (permanentValid) {
                replace(currentPermanentDtcs, result.currentPermanentDtcs);
                rebuild(lastPermanentDtcs, secondaryPermanentDtcs, currentPermanentDtcs);
            } else {
                currentPermanentDtcs.clear();
                rebuild(lastPermanentDtcs, secondaryPermanentDtcs, currentPermanentDtcs);
            }
            DtcSnapshotValidity validity = new DtcSnapshotValidity(
                    expectedGeneration, true, pendingValid, permanentValid);
            publishDtcSnapshotLocked(validity);
            return validity;
        }
    }

    static DtcSnapshotValidity applyVerifiedClearSnapshot(
            DtcReader.DtcScanResult verification, long expectedGeneration) {
        if (verification == null
                || !verification.hasCompleteStoredSnapshot(true)
                || !verification.currentStoredDtcs.isEmpty()) return null;
        synchronized (DTC_SNAPSHOT_LOCK) {
            if (dtcSnapshotGeneration != expectedGeneration) return null;
            boolean pendingValid = verification.hasCompletePendingSnapshot(true);
            boolean permanentValid = verification.hasCompletePermanentSnapshot(true);
            // Mode 04 and its verification run only on the connected/current
            // protocol. Preserve codes learned from secondary buses until
            // those buses are explicitly rescanned or cleared themselves.
            currentStoredDtcs.clear();
            replace(currentPendingDtcs, pendingValid
                    ? verification.currentPendingDtcs : java.util.Collections.emptyList());
            replace(currentPermanentDtcs, permanentValid
                    ? verification.currentPermanentDtcs : java.util.Collections.emptyList());
            rebuild(lastStoredDtcs, secondaryStoredDtcs, currentStoredDtcs);
            rebuild(lastPendingDtcs, secondaryPendingDtcs, currentPendingDtcs);
            rebuild(lastPermanentDtcs, secondaryPermanentDtcs, currentPermanentDtcs);
            lastCurrentProtocolStoredDtcCount = 0;
            DtcSnapshotValidity validity = new DtcSnapshotValidity(
                    expectedGeneration, true, pendingValid, permanentValid);
            publishDtcSnapshotLocked(validity);
            return validity;
        }
    }

    private static void replace(
            java.util.List<DtcCode> target, java.util.List<DtcCode> source) {
        target.clear();
        if (source != null) target.addAll(source);
    }

    /** Caller holds {@link #DTC_SNAPSHOT_LOCK}; volatile assignment is the commit. */
    private static void publishDtcSnapshotLocked(DtcSnapshotValidity validity) {
        lastDtcSnapshotValidity = validity;
        lastDtcSnapshot = new DtcSnapshot(
                lastStoredDtcs, lastPendingDtcs, lastPermanentDtcs, validity);
    }

    private static void rebuild(java.util.List<DtcCode> target,
                                java.util.List<DtcCode> secondary,
                                java.util.List<DtcCode> current) {
        target.clear();
        for (DtcCode code : secondary) if (!target.contains(code)) target.add(code);
        for (DtcCode code : current) if (!target.contains(code)) target.add(code);
    }

    public interface DtcClearTrigger {
        boolean clear();
    }
    public static volatile DtcClearTrigger dtcClearTrigger;

    public interface LoggerCallback {
        void onRecord(DataRecord record, int count);
        void onStatus(String status, boolean isError);
        void onStopped(int totalRecords);
        void onVinRead(String vin, BaseDriver sourceDriver, long sourceSessionToken,
                       long sourceConnectionEpoch);
        void onPidsDetected(int supportedCount, int totalCount, boolean fromLiveQuery);
        void onDeviceDetected(VLinkerOptimizer.DeviceType deviceType);
        void onAdapterCheckResult(boolean isStandard, String details);
        void onDtcAutoScan(int storedCount, int pendingCount, int permanentCount,
                           DtcSnapshotValidity validity);
        void onDtcAutoScanDetails(DtcReader.DtcScanResult result,
                                  BaseDriver sourceDriver, long sourceSessionToken,
                                  long sourceConnectionEpoch);
        void onNewDtcDetected(java.util.List<DtcCode> newCodes);
    }

    public static void setCallback(LoggerCallback cb) {
        callbackRef = cb != null ? new WeakReference<>(cb) : null;
    }

    /**
     * Return the live service-session token only when {@code expectedDriver}
     * is still the driver published by that session. A zero token means that
     * the driver is not owned by the current foreground-service session.
     */
    static long sessionTokenForDriver(BaseDriver expectedDriver) {
        LoggerService service = instance;
        long token = currentSessionToken;
        return service != null && service.running && expectedDriver != null
                && service.driver == expectedDriver ? token : 0L;
    }

    /** Validate a delayed callback against its logger and physical connection. */
    static boolean isCurrentConnection(BaseDriver sourceDriver, long sourceSessionToken,
                                       long sourceConnectionEpoch) {
        LoggerService service = instance;
        return service != null && service.running && sourceDriver != null
                && sourceDriver.isConnected()
                && sameConnectionIdentity(sourceDriver, sourceSessionToken,
                sourceConnectionEpoch, service.driver, currentSessionToken,
                service.driver != null ? service.driver.getConnectionEpoch() : 0L);
    }

    static boolean sameSessionIdentity(BaseDriver sourceDriver, long sourceSessionToken,
                                       BaseDriver currentDriver, long currentToken) {
        return sourceDriver != null && sourceDriver == currentDriver
                && sourceSessionToken != 0L && sourceSessionToken == currentToken;
    }

    static boolean sameConnectionIdentity(
            BaseDriver sourceDriver, long sourceSessionToken, long sourceConnectionEpoch,
            BaseDriver currentDriver, long currentSessionToken, long currentConnectionEpoch) {
        return sameSessionIdentity(sourceDriver, sourceSessionToken,
                currentDriver, currentSessionToken)
                && sourceConnectionEpoch > 0L
                && sourceConnectionEpoch == currentConnectionEpoch;
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
            // Sticky/system restart after process death: the session state
            // (pendingConfig, callbacks, executor) is gone and cannot be
            // rebuilt. On Android 8-11 the system still expects a pending
            // startForeground() call after such a restart — skipping it
            // crashes with RemoteServiceException. Satisfy the contract with
            // a minimal notification, then stop cleanly.
            try {
                startForeground(NOTIFICATION_ID,
                        buildNotification(localizedString(
                                R.string.background_notification_starting,
                                "Stopping OBD2 logger…"), 0));
            } catch (Exception e) {
                Log.w(TAG, "startForeground on restart failed", e);
            }
            stopForeground(true);
            stopSelf();
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

        // An opaque monotonic token cannot collide during same-millisecond
        // stop/start cycles (unlike using currentTimeMillis() directly).
        long sessionToken = nextSessionToken();

        // A throw here runs on the system's binder thread and takes down the WHOLE
        // app (the service worker thread's own try/catch does not cover onStartCommand).
        // Guard every step so a foreground-service startup failure degrades to a
        // clean stop with a user-visible error instead of a crash.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int fgsType = (config != null && (config.transportMode == TransportMode.SIM || config.transportMode == TransportMode.WIFI))
                        ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        : android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                // GPS route capture keeps working while backgrounded only when
                // the service runs with the location type; requires while-in-use
                // permission, so the bit is added conditionally. The catch chain
                // below already falls back if the system rejects the combination.
                if (RouteRecorder.hasPermission(this)) {
                    fgsType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                }
                try {
                    startForeground(NOTIFICATION_ID,
                            buildNotification(localizedString(
                                    R.string.background_notification_starting,
                                    "Starting OBD2 logger…"), 0),
                            fgsType);
                } catch (Exception e1) {
                    try {
                        startForeground(NOTIFICATION_ID,
                                buildNotification(localizedString(
                                        R.string.background_notification_starting,
                                        "Starting OBD2 logger…"), 0),
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } catch (Exception e2) {
                        startForeground(NOTIFICATION_ID,
                                buildNotification(localizedString(
                                        R.string.background_notification_starting,
                                        "Starting OBD2 logger…"), 0));
                    }
                }
            } else {
                startForeground(NOTIFICATION_ID,
                        buildNotification(localizedString(
                                R.string.background_notification_starting,
                                "Starting OBD2 logger…"), 0));
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
        startLogging(config, sessionToken, startId);
        try {
            acquireWakeLock(sessionToken);
        } catch (Exception e) {
            Log.w(TAG, "acquireWakeLock failed", e);
        }
        // NOT_STICKY: after a kill the session (pendingConfig, callbacks,
        // driver) cannot be rebuilt, so a sticky restart would only flash a
        // notification and stop again (see the intent == null path above).
        return START_NOT_STICKY;
    }

    private void startLogging(LoggerConfig config, long sessionToken, int startId) {
        // Guard against double-start: if a previous logging session is still
        // running (e.g. START intent received twice), shut it down first so we
        // don't orphan the old executor thread or race on driver/writer fields.
        if (executor != null) {
            stopLogging();
        }
        ExecutorService ownedExecutor = Executors.newSingleThreadExecutor();
        ExecutorService ownedDtcExecutor = Executors.newSingleThreadExecutor();
        synchronized (SESSION_STATE_LOCK) {
            currentSessionToken = sessionToken;
            running = true;
            recordCount = 0;
            voltageWarningSessionToken = sessionToken;
            voltageWarned = false;
            sessionStartElapsedMs = android.os.SystemClock.elapsedRealtime();
            executor = ownedExecutor;
            dtcExecutor = ownedDtcExecutor;
        }
        ownedExecutor.submit(() -> runLogger(config, sessionToken, startId));
    }

    /**
     * First connection deserves the same resilience as a mid-drive reconnect.
     * The previous path stopped the foreground service after one failed RFCOMM
     * attempt, even when the adapter was still waking up after ignition-on.
     */
    private DriverConnector.Result connectWithBackoff(LoggerConfig config, long sessionToken) {
        DriverConnector.Result last = null;
        for (int attempt = 1; ownsActiveSession(sessionToken); attempt++) {
            last = DriverConnector.connect(config, 30_000L);
            if (last.isConnected()) return last;
            if (!ownsActiveSession(sessionToken)) break;

            long delayMs = ReconnectBackoff.delayForAttempt(attempt);
            notifySessionStatus(sessionToken, "Connection attempt " + attempt
                    + " failed. Retrying in " + (delayMs / 1000) + "s...", false);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    /**
     * A reconnect is a new physical connection epoch, so the previous DTC
     * snapshot must not participate in readiness-count comparisons. Clear it
     * first, then establish a fresh current-protocol baseline. If the scan is
     * incomplete the snapshot deliberately remains invalid and the periodic
     * monitor retries instead of presenting a false clean result.
     */
    private void refreshDtcSnapshotAfterReconnect(
            BaseDriver localDriver, long sessionToken) {
        final long connectionEpoch = localDriver.getConnectionEpoch();
        if (!isCurrentConnection(localDriver, sessionToken, connectionEpoch)) return;

        final long snapshotGeneration = resetDtcSnapshotForSession(sessionToken);
        if (snapshotGeneration < 0L) return;
        try {
            SmartDtcScanPlanner.Plan quickPlan =
                    SmartDtcScanPlanner.createPlanFromEvidence(
                            SmartDtcScanPlanner.ScanMode.QUICK,
                            DtcReader.activeProtocolFor(localDriver),
                            null, null, false, false);
            DtcReader.DtcScanResult result = DtcReader.readDtcs(localDriver, quickPlan);
            DtcSnapshotValidity published = isCurrentConnection(
                    localDriver, sessionToken, connectionEpoch)
                    ? replaceCurrentDtcSnapshotFromQuick(
                            result, snapshotGeneration) : null;
            if (published == null) return;
            DtcSnapshot snapshot = getDtcSnapshot();

            LoggerCallback callback = getCallback();
            if (callback != null) {
                mainHandler.post(() -> {
                    if (!isCurrentConnection(
                            localDriver, sessionToken, connectionEpoch)) return;
                    callback.onDtcAutoScan(
                            snapshot.stored.size(), snapshot.pending.size(),
                            snapshot.permanent.size(), snapshot.validity);
                    callback.onDtcAutoScanDetails(
                            result, localDriver, sessionToken, connectionEpoch);
                });
            }
        } catch (Exception e) {
            if (ownsActiveSession(sessionToken)) {
                Log.w(TAG, "Reconnect DTC baseline scan failed non-fatally", e);
            }
        }
    }

    /** Release only resources created by a worker that lost session ownership. */
    private boolean abortRetiredInitialization(
            long sessionToken, BaseDriver localDriver,
            ApiServer localApiServer, AirDensityMonitor localAirDensity) {
        if (ownsActiveSession(sessionToken)) return false;
        if (localAirDensity != null) {
            try { localAirDensity.stopPhoneSensors(); } catch (Exception ignored) {}
        }
        if (localApiServer != null) {
            try { localApiServer.stop(); } catch (Exception ignored) {}
        }
        if (localDriver != null) {
            try { localDriver.disconnect(); } catch (Exception ignored) {}
        }
        return true;
    }

    /**
     * Smart startup DTC scan, deferred to run once immediately AFTER the first
     * live record so the gauges populate before this multi-bus scan (which can
     * take a few seconds) rather than after it. Runs on the logging worker
     * thread, so it is naturally serialized with polling; commandLock is still
     * taken to exclude any concurrent manual UI diagnostic. Failures are
     * non-fatal — a DTC scan must never take the live logger down.
     */
    private void runStartupDtcScan(BaseDriver localDriver, long sessionToken,
            String verifiedVinForDtcScan, long initialDtcSnapshotGeneration,
            LoggerConfig config) {
        boolean lockHeld = false;
        try {
            final BaseDriver finalDriverForDtc = localDriver;
            final long dtcConnectionEpoch = finalDriverForDtc.getConnectionEpoch();
            localDriver.commandLock.lockInterruptibly();
            lockHeld = true;
            // A stop/start or reconnect may have happened while polling; do not
            // scan or publish against a connection that is no longer current.
            if (!isCurrentConnection(finalDriverForDtc, sessionToken, dtcConnectionEpoch)) {
                return;
            }
            VehicleModuleProfileStore.SmartProtocolEvidence evidence =
                    verifiedVinForDtcScan != null
                    ? VehicleModuleProfileStore.getSmartProtocolEvidence(
                            this, verifiedVinForDtcScan) : null;
            SmartDtcScanPlanner.Plan startupPlan =
                    SmartDtcScanPlanner.createPlanFromEvidence(
                    SmartDtcScanPlanner.ScanMode.SMART,
                    DtcReader.activeProtocolFor(finalDriverForDtc), evidence,
                    verifiedVinForDtcScan, config.fordMsCanEnabled,
                    DtcReader.supportsStandardProtocolSelection(finalDriverForDtc));
            DtcReader.DtcScanResult scanResult = DtcReader.readDtcs(
                    finalDriverForDtc, startupPlan);

            DtcSnapshotValidity published = isCurrentConnection(
                    finalDriverForDtc, sessionToken, dtcConnectionEpoch)
                    ? replaceDtcSnapshotFromScan(
                            scanResult, true, initialDtcSnapshotGeneration)
                    : null;
            if (published != null) {
                DtcSnapshot snapshot = getDtcSnapshot();

                LoggerCallback cbDtc = getCallback();
                if (cbDtc != null) {
                    mainHandler.post(() -> {
                        if (isCurrentConnection(
                                finalDriverForDtc, sessionToken, dtcConnectionEpoch)) {
                            cbDtc.onDtcAutoScan(
                                    snapshot.stored.size(),
                                    snapshot.pending.size(),
                                    snapshot.permanent.size(), snapshot.validity);
                        }
                    });
                    mainHandler.post(() -> {
                        if (isCurrentConnection(
                                finalDriverForDtc, sessionToken, dtcConnectionEpoch)) {
                            cbDtc.onDtcAutoScanDetails(
                                    scanResult, finalDriverForDtc, sessionToken,
                                    dtcConnectionEpoch);
                        }
                    });
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (ownsActiveSession(sessionToken)) Log.e(TAG, "DTC Auto-scan failed", e);
        } finally {
            if (lockHeld) localDriver.commandLock.unlock();
        }
    }

    private void runLogger(LoggerConfig config, long sessionToken, int startId) {
        synchronized (SESSION_STATE_LOCK) {
            if (ownsActiveSession(sessionToken)) activeConfig = config;
        }
        String fuelPrefix = config.fuelMode != null ? config.fuelMode.name() + "_" : "";
        String simPrefix = (config.transportMode == TransportMode.SIM) ? "Sim_" : "";
        String timeStr = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String sessionId = simPrefix + fuelPrefix + timeStr + "_" + sessionToken;
        
        DriverConnector.Result connection = connectWithBackoff(config, sessionToken);
        BaseDriver localDriver = connection != null ? connection.getDriver() : null;
        synchronized (SESSION_STATE_LOCK) {
            if (ownsActiveSession(sessionToken)) driver = localDriver;
        }

        if (connection == null || !connection.isConnected()) {
            String connectionError = connection != null ? connection.getError() : "Connection cancelled";
            if (ownsActiveSession(sessionToken)) {
                DriverFactory.markConnectionFailure(connectionError);
                notifySessionStatus(sessionToken,
                        connection != null && connection.isTimedOut()
                        ? "Connection timed out. Check adapter power and transport settings."
                        : "Connection failed: " + connectionError, true);
                if (clearPublishedSessionStateIfCurrent(sessionToken)) {
                    releaseWakeLock(sessionToken);
                    requestServiceStopForSession(sessionToken, startId, 0);
                }
            } else if (localDriver != null) {
                try { localDriver.disconnect(); } catch (Throwable ignored) {}
            }
            return;
        }

        // The connect call can outlive stop/start. Never let a connection
        // obtained by the old executor enter initialization for the new run.
        if (!ownsActiveSession(sessionToken)) {
            try { localDriver.disconnect(); } catch (Throwable ignored) {}
            return;
        }

        // Invalidate the previous vehicle's diagnostics as soon as this
        // physical connection is accepted.  VIN/weather/API setup below must
        // never expose codes retained from the earlier connection.
        final long initialDtcSnapshotGeneration =
                resetDtcSnapshotForSession(sessionToken);
        if (initialDtcSnapshotGeneration < 0L) {
            try { localDriver.disconnect(); } catch (Throwable ignored) {}
            return;
        }

        // Breadcrumbs for the crash report — a stack trace from the logging
        // worker is much less useful without knowing which transport it was on.
        synchronized (SESSION_STATE_LOCK) {
            if (ownsActiveSession(sessionToken)) {
                CrashReporter.noteSession(sessionId, DriverFactory.getLastResolvedTransport());
            }
        }
        notifySessionStatus(sessionToken,
                "Connected via " + DriverFactory.getLastResolvedTransport()
                + ". Logging in background.", false);

        // Notify UI of detected vLinker device type
        if (localDriver instanceof ElmDriver) {
            final VLinkerOptimizer.DeviceType dt = ((ElmDriver) localDriver).getVlinkerType();
            LoggerCallback cb0 = getCallback();
            if (cb0 != null && dt != VLinkerOptimizer.DeviceType.UNKNOWN) {
                mainHandler.post(() -> {
                    if (ownsActiveSession(sessionToken)) cb0.onDeviceDetected(dt);
                });
            }
        }

        // Notify UI of adapter standard/clone validation result
        final boolean isStd = localDriver.isStandardAdapter();
        final String details = localDriver.getAdapterDetails();
        LoggerCallback cbCheck = getCallback();
        if (cbCheck != null) {
            mainHandler.post(() -> {
                if (ownsActiveSession(sessionToken)) {
                    cbCheck.onAdapterCheckResult(isStd, details);
                }
            });
        }

        // Read VIN if not already known. The config default is "UNKNOWN"
        // (not null/empty), so we must treat that as unset too — otherwise
        // the VIN is never read and logs are saved without a VIN subfolder.
        boolean vinReadThisSession = false;
        final long vinConnectionEpoch = localDriver.getConnectionEpoch();
        if (config.vin == null || config.vin.isEmpty()
                || "UNKNOWN".equalsIgnoreCase(config.vin)) {
            String vin = VinReader.readVin(localDriver);
            if (vin != null) {
                config.vin = vin;
                vinReadThisSession = true;
            }
        }
        // Brand must be initialized before the automatic DTC scan and before
        // the API/DataRecord snapshot is created. Previously this happened in
        // the asynchronous UI callback, leaving the service path on the
        // generic DTC database and reporting vehicleBrand="auto".
        if (config.vin != null && !config.vin.isEmpty()
                && !"UNKNOWN".equalsIgnoreCase(config.vin)) {
            String detectedBrand = DtcDatabase.initForVin(this, config.vin);
            config.applyDetectedVehicleBrand(detectedBrand);
            // Do not wait for the asynchronous UI callback: the first DTC
            // scan and PID-list snapshot below consume these flags on this
            // worker. The UI remains responsible for persisting/visualizing
            // the same idempotent per-VIN decisions.
            VinSessionAutomation.applyFromPreferences(
                    config, config.vin,
                    getSharedPreferences("OBD2Prefs", MODE_PRIVATE),
                    vinReadThisSession);
        }
        if (vinReadThisSession) {
            LoggerCallback cb = getCallback();
            if (cb != null) {
                final String detectedVin = config.vin;
                final BaseDriver detectedVinDriver = localDriver;
                mainHandler.post(() -> {
                    if (isCurrentConnection(detectedVinDriver, sessionToken,
                            vinConnectionEpoch)) {
                        cb.onVinRead(detectedVin, detectedVinDriver, sessionToken,
                                vinConnectionEpoch);
                    }
                });
            }
        }
        // Only a VIN read over this exact physical connection may authorize
        // profile-assisted protocol expansion. A persisted/manual VIN can set
        // display metadata, but it cannot prove which vehicle is now attached.
        final String verifiedVinForDtcScan = vinReadThisSession
                ? ManualVinStore.normalize(config.vin) : null;

        if (abortRetiredInitialization(
                sessionToken, localDriver, null, null)) return;
        
        // ── Initialize LiveMapStore (single source of truth for fuel map) ──
        // Per-session local reference; only the CURRENT session may publish it
        // to the instance field, otherwise a stale worker from a quick
        // stop/restart would overwrite the new session's store.
        // Reuse the retained store across sessions. Allocating a fresh one per
        // ACTION_START silently destroyed both fuels' learned cells, which
        // defeats the Tune Assist workflow outright: that flow logs Petrol, then
        // switches to LPG and logs again to compare the two. MainActivity
        // deliberately clears only the fuel about to be re-logged, and this
        // allocation threw away the comparison fuel it had just preserved —
        // leaving Deviation/Correction permanently empty.
        LiveMapStore localMapStore = liveMapStore != null ? liveMapStore : new LiveMapStore();
        // VE map shares the fuel map's cross-session retention: the Tune Assist
        // workflow logs Petrol then LPG to compare, and the ΔVE surface is only
        // meaningful if both fuels' learned cells survive the fuel switch.
        // A brand-new store additionally restores the persisted surface, so
        // learning survives process death and reboots (saved on stop/destroy).
        VeMapStore localVeMapStore = veMapStore;
        if (localVeMapStore == null) {
            localVeMapStore = new VeMapStore();
            if (VeMapPersistence.load(this, localVeMapStore)) {
                Log.i(TAG, "Restored learned VE surface: "
                        + localVeMapStore.getPetrolData().size() + " petrol / "
                        + localVeMapStore.getLpgData().size() + " lpg cells");
            }
        }
        synchronized (SESSION_STATE_LOCK) {
            if (ownsActiveSession(sessionToken)) {
                // Expose via instance getter for MainActivity to read snapshots
                liveMapStore = localMapStore;
                veMapStore = localVeMapStore;
            }
        }

        ApiServer localApiServer = null;
        if (config.enableApiServer) {
            try {
                localApiServer = new ApiServer(8080, config.apiAccessToken);
                localApiServer.setDtcProvider(new ApiServer.DtcProvider() {
                    @Override
                    public java.util.List<DtcCode> getStoredDtcs() {
                        return LoggerService.getDtcSnapshot().stored;
                    }

                    @Override
                    public java.util.List<DtcCode> getPendingDtcs() {
                        return LoggerService.getDtcSnapshot().pending;
                    }

                    @Override
                    public ApiServer.DtcSnapshot getDtcSnapshot() {
                        LoggerService.DtcSnapshot snapshot =
                                LoggerService.getDtcSnapshot();
                        return new ApiServer.DtcSnapshot(
                                snapshot.stored, snapshot.pending);
                    }

                    @Override
                    public boolean triggerClearDtcs() {
                        DtcClearTrigger trigger = dtcClearTrigger;
                        if (trigger != null) {
                            return trigger.clear();
                        }
                        return false;
                    }

                    @Override
                    public java.util.List<FreezeFrameReader.FreezeFrameEntry> getPerDtcFreezeFrames() {
                        return LoggerService.getFreezeFrameSnapshot().perDtcFrames;
                    }

                    @Override
                    public FreezeFrameData getGenericFreezeFrame() {
                        return LoggerService.getFreezeFrameSnapshot().genericFrame;
                    }
                });
                localApiServer.start();
                // Report the adapter actually connected by AUTO, not merely
                // the preference value "auto". This is what an external AI
                // agent needs to diagnose transport-specific behaviour.
                localApiServer.setTransportMode(DriverFactory.getLastResolvedTransport());
                localApiServer.setAdapterConnected(true);
                localApiServer.setVehicleBrand(config.vehicleBrand);
                localApiServer.setLiveMapStore(localMapStore);
                localApiServer.setVeMapStore(localVeMapStore);
                localApiServer.setVeMapPersistenceClearHook(
                        () -> VeMapPersistence.clear(this));
                localApiServer.setVeTrendProvider(() -> VeTrendTracker.evaluate(this));
                localApiServer.setVehicleInformation(CommonVehicleDataStore.get(this, config.vin));
                localApiServer.resetSession();
                synchronized (SESSION_STATE_LOCK) {
                    if (ownsActiveSession(sessionToken)) apiServer = localApiServer;
                }
                Log.i(TAG, "ApiServer started on port 8080");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start ApiServer", e);
            }
        }
        if (abortRetiredInitialization(
                sessionToken, localDriver, localApiServer, null)) return;

        // --- Initialize Air Density Monitor (AAD/MAD/BAD) ---
        // Per-session local reference (mirrors localDriver/localWriter): the
        // worker only publishes to the instance field while it is the current
        // session, and only ever stops ITS OWN monitor in the finally block —
        // never a newer session's.
        AirDensityMonitor localAirDensity = null;
        if (config.showAirDensity) {
            try {
                localAirDensity = new AirDensityMonitor(this);
                localAirDensity.startPhoneSensors();
                synchronized (SESSION_STATE_LOCK) {
                    if (ownsActiveSession(sessionToken)) {
                        airDensityMonitor = localAirDensity;
                    }
                }
                // Sync fetch — LoggerService runs on background thread so this is safe.
                // The initial fetch populates humidity (not available via OBD2) before
                // the first logging loop iteration, so the first record has AAD/MAD/BAD.
                // Wrapped in try/catch: network may be unavailable (e.g. unit tests,
                // offline environments) — air density falls back to last-good / default RH.
                localAirDensity.refreshWeatherSync();
                Log.i(TAG, "AirDensityMonitor initialized");
            } catch (Exception e) {
                Log.w(TAG, "AirDensityMonitor init failed (weather unavailable) — using defaults", e);
            }
        }
        if (abortRetiredInitialization(
                sessionToken, localDriver, localApiServer, localAirDensity)) return;

        // The smart startup DTC scan is deferred: it runs once immediately
        // after the first live poll (see runStartupDtcScan below), so gauges
        // populate in a few seconds instead of waiting out this multi-bus scan.
        // A vehicle that answers no VIN service used to leave the driver
        // staring at empty gauges while VIN + DTC acquisition blocked the first
        // poll — hence "connected but no data".

        // --- Auto-detect supported PIDs ---
        List<PIDDefinition> allPids = PIDCatalogue.getConfiguredPollSet(this,
                config.lpgOnlyMode, config.showAirDensity, config.customPidsEnabled,
                config.dpfMonitorEnabled);
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
                notifySessionStatus(sessionToken, "Detecting supported PIDs...", false);
                supportedHex = PidAvailabilityChecker.querySupportedPids(localDriver);

                if (supportedHex != null && !supportedHex.isEmpty()) {
                    pids = PidAvailabilityChecker.filterCatalogue(supportedHex, allPids);
                    detectedFromLive = true;
                    cachePids(config.vin, supportedHex);
                    Log.i(TAG, "Live detection: " + pids.size() + "/" + allPids.size() + " PIDs supported");
                } else {
                    // Some ECUs/clones provide broken support bitmaps. Probe
                    // each configured standard PID before resorting to hints.
                    List<String> probed = PidAvailabilityChecker.probeCatalogue(localDriver, allPids);
                    if (probed != null && !probed.isEmpty()) {
                        pids = PidAvailabilityChecker.filterCatalogue(probed, allPids);
                        detectedFromLive = true;
                        cachePids(config.vin, probed);
                        Log.i(TAG, "Targeted PID probe: " + pids.size() + "/" + allPids.size());
                    } else {
                        Log.w(TAG, "Live PID detection failed — trying VIN-based profile");
                        java.util.Set<String> brandPids = BrandYearProfile.getProfileFromVin(config.vin);
                        if (brandPids != null) {
                            pids = PidAvailabilityChecker.filterCatalogue(
                                    new ArrayList<>(brandPids), allPids);
                            Log.i(TAG, "VIN profile: " + pids.size() + "/" + allPids.size() + " PIDs");
                        }
                    }
                }
            }

            // Notify UI of detection results
            final int detectedCount = pids.size();
            final int totalCount = allPids.size();
            final boolean fromLive = detectedFromLive;
            LoggerCallback cb = getCallback();
            if (cb != null) {
                mainHandler.post(() -> {
                    if (ownsActiveSession(sessionToken)) {
                        cb.onPidsDetected(detectedCount, totalCount, fromLive);
                    }
                });
            }
        }

        if (abortRetiredInitialization(
                sessionToken, localDriver, localApiServer, localAirDensity)) return;
        final List<PIDDefinition> finalPids = pids;
        long started = android.os.SystemClock.elapsedRealtime();
        // Shared with the in-process path in MainActivity — see PollingEngine.
        final PollingEngine engine = new PollingEngine(config, finalPids, started);
        // Link-level liveness supervisor; owned by this worker like the engine.
        final TelemetryWatchdog watchdog = new TelemetryWatchdog();
        // GPS route capture is best-effort: without permission or a provider
        // the session simply logs no gps_* columns.
        final RouteRecorder routeRecorder = new RouteRecorder(this);
        if (routeRecorder.start()) {
            engine.setLocationSupplier(routeRecorder);
        }

        DataWriter localWriter = null;
        int localRecordCount = 0;

        try {
            localWriter = new DataWriter(this, sessionId, finalPids, config.vin,
                    config, DataWriter.describeAdapter(localDriver));
            synchronized (SESSION_STATE_LOCK) {
                if (ownsActiveSession(sessionToken)) writer = localWriter;
            }
            updateSessionNotification(sessionToken, "Logging: 0 records", 0);
            long lastDtcCheckMs = android.os.SystemClock.elapsedRealtime();
            int retryCount = 0;
            // The smart startup DTC scan runs once, right after the first live
            // record, instead of blocking the first poll. See runStartupDtcScan.
            boolean startupDtcDone = false;

            while (ownsActiveSession(sessionToken)) {
                try {
                    if (!localDriver.isConnected()) {
                        if (retryCount > 0) {
                            final int finalRetry = retryCount;
                            notifySessionStatus(sessionToken,
                                    "Connection lost. Reconnecting (attempt "
                                            + finalRetry + ")...", false);
                        }
                        DriverConnector.Result reconnect =
                                DriverConnector.reconnect(localDriver, 30_000L);
                        if (!reconnect.isConnected()) {
                            throw new java.io.IOException(reconnect.getError());
                        }
                        retryCount = 0;
                        // Fresh link — stale watchdog history must not re-kill it.
                        watchdog.reset();
                        refreshDtcSnapshotAfterReconnect(localDriver, sessionToken);
                        lastDtcCheckMs = android.os.SystemClock.elapsedRealtime();
                        notifySessionStatus(sessionToken, "Connected. Logging resumed.", false);
                        if (localApiServer != null) localApiServer.setAdapterConnected(true);
                    }

                    while (ownsActiveSession(sessionToken)) {
                        if (MainActivity.isPaused) {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            continue;
                        }
                        long nowMs = android.os.SystemClock.elapsedRealtime();
                        // Continuous DTC monitor: validated current-protocol Quick scan every 30s.
                        if (nowMs - lastDtcCheckMs >= 30000) {
                            lastDtcCheckMs = nowMs;
                            boolean diagnosticLockHeld = false;
                            try {
                                final long periodicEpoch = localDriver.getConnectionEpoch();
                                localDriver.commandLock.lockInterruptibly();
                                diagnosticLockHeld = true;
                                // A manual diagnostic may have started while we
                                // waited. It owns the adapter until pause clears.
                                if (MainActivity.isPaused || !isCurrentConnection(
                                        localDriver, sessionToken, periodicEpoch)) {
                                    continue;
                                }
                                final long periodicSnapshotGeneration =
                                        currentDtcSnapshotGeneration();
                                // Refresh unconditionally. PID 01 only exposes a
                                // count, so code A changing to code B with the same
                                // count would otherwise leave a stale snapshot.
                                SmartDtcScanPlanner.Plan quickPlan =
                                        SmartDtcScanPlanner.createPlanFromEvidence(
                                                SmartDtcScanPlanner.ScanMode.QUICK,
                                                DtcReader.activeProtocolFor(localDriver),
                                                null, null,
                                                false, false);
                                DtcReader.DtcScanResult sr = DtcReader.readDtcs(
                                        localDriver, quickPlan);
                                if (!sr.hasCompleteStoredSnapshot(true)
                                        || !isCurrentConnection(
                                                localDriver, sessionToken, periodicEpoch)) {
                                    continue;
                                }
                                DtcSnapshot beforeSnapshot = getDtcSnapshot();
                                List<DtcCode> newCodes = new ArrayList<>();
                                for (DtcCode c : sr.currentStoredDtcs) {
                                    if (!beforeSnapshot.stored.contains(c)) newCodes.add(c);
                                }
                                if (sr.hasCompletePendingSnapshot(true)) {
                                    for (DtcCode c : sr.currentPendingDtcs) {
                                        if (!beforeSnapshot.pending.contains(c)) newCodes.add(c);
                                    }
                                }

                                DtcSnapshotValidity published =
                                        replaceCurrentDtcSnapshotFromQuick(
                                                sr, periodicSnapshotGeneration);
                                if (published == null) continue;
                                DtcSnapshot snapshot = getDtcSnapshot();

                                LoggerCallback cbDtc = getCallback();
                                if (cbDtc != null) {
                                    mainHandler.post(() -> {
                                        if (isCurrentConnection(localDriver,
                                                sessionToken, periodicEpoch)) {
                                            cbDtc.onDtcAutoScan(
                                                    snapshot.stored.size(),
                                                    snapshot.pending.size(),
                                                    snapshot.permanent.size(),
                                                    snapshot.validity);
                                            if (!newCodes.isEmpty()) {
                                                cbDtc.onNewDtcDetected(newCodes);
                                            }
                                        }
                                    });
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (Exception e) {
                                Log.e(TAG, "Periodic synchronous DTC monitor failed non-fatally", e);
                            } finally {
                                if (diagnosticLockHeld) localDriver.commandLock.unlock();
                            }
                        }
                        if (MainActivity.isPaused) continue;
                        PollingEngine.PollOutcome outcome =
                                engine.poll(localDriver, localAirDensity, localMapStore, localVeMapStore);
                        // A stop/start can complete while poll() is blocked. Do
                        // not let that last result escape the worker that made it.
                        if (!ownsActiveSession(sessionToken)) break;

                        // FDIR: "connected" with no live engine data is a fault,
                        // not a state to sit in. On DEAD, escalate into the
                        // existing IOException reconnect path (backoff →
                        // disconnect → reconnect → protocol re-detect).
                        TelemetryWatchdog.State linkState =
                                watchdog.observeCycle(outcome.batch);
                        if (watchdog.consumeRecoveryDemand()) {
                            notifySessionStatus(sessionToken,
                                    "No live data — recovering connection...", true);
                            // The whole point of this fault class: the driver
                            // still CLAIMS connected, so the reconnect block
                            // above would be skipped. Force the disconnect so
                            // DriverConnector.reconnect + protocol re-detect
                            // actually run.
                            try {
                                localDriver.disconnect();
                            } catch (Exception ignored) {
                            }
                            throw new java.io.IOException(
                                    "Telemetry watchdog: adapter connected but no "
                                    + "engine data for " + watchdog.getCorelessCycles()
                                    + " cycles — forcing transport recovery");
                        } else if (linkState == TelemetryWatchdog.State.STALE) {
                            Log.w(TAG, "Telemetry STALE: "
                                    + watchdog.getCorelessCycles()
                                    + " cycles without core data");
                        }
                        DataRecord record = outcome.record;

                        localWriter.writeRecord(record);
                        localRecordCount++;
                        // Reset retry counter on every successful record write so
                        // transient errors don't accumulate across a long session.
                        // Without this, 11 scattered blips over hours permanently
                        // kill the logger even though the connection is fine.
                        retryCount = 0;
                        synchronized (SESSION_STATE_LOCK) {
                            if (ownsActiveSession(sessionToken)) {
                                recordCount = localRecordCount;
                                CrashReporter.noteRecordCount(localRecordCount);
                            }
                        }
                        publishSessionRecord(sessionToken, record, localRecordCount);

                        // Deferred smart startup DTC scan: gauges are now live, so
                        // run the (slower, multi-bus) scan once. Same worker thread
                        // as polling, so it is serialized with it naturally.
                        if (!startupDtcDone) {
                            startupDtcDone = true;
                            runStartupDtcScan(localDriver, sessionToken,
                                    verifiedVinForDtcScan, initialDtcSnapshotGeneration,
                                    config);
                            lastDtcCheckMs = android.os.SystemClock.elapsedRealtime();
                        }

                        // Low-voltage watchdog: a weak alternator / battery cause lean
                        // misfires that masquerade as fuel-trim problems — especially on
                        // LPG. Warn once per session (transient dips ignored) when module
                        // voltage sags below the charging floor.
                        checkVoltageWatchdog(config, sessionToken, record);

                        if (localApiServer != null) {
                            localApiServer.setLatestData(record, true);
                        }

                        if (localRecordCount % 10 == 0) {
                            updateSessionNotification(sessionToken,
                                    "Logging: " + localRecordCount + " records",
                                    localRecordCount);
                        }

                        try {
                            // Fixed-rate: sleeping for the full interval here would
                            // make the achieved period pollDuration + interval.
                            engine.awaitNextCycle();
                        } catch (InterruptedException ie) {
                            // shutdownNow() interrupted our sleep — exit the loop gracefully
                            synchronized (SESSION_STATE_LOCK) {
                                if (currentSessionToken == sessionToken) {
                                    running = false;
                                }
                            }
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    if (!ownsActiveSession(sessionToken)
                            || e instanceof InterruptedException) {
                        break;
                    }
                    // Only connection/IO errors should count toward the retry cap.
                    // Data-parsing or derived-sensor errors are transient and
                    // should not accumulate toward a permanent stop.
                    boolean isConnectionError = e instanceof java.io.IOException
                            || e instanceof java.net.SocketTimeoutException
                            || e instanceof java.net.SocketException;
                    if (isConnectionError) {
                        retryCount++;
                        Log.w(TAG, "Connection error (attempt " + retryCount + "): " + e.getMessage());
                    } else {
                        // Non-IO exception (data parsing, NPE, etc.) — log and
                        // continue without incrementing the connection retry counter.
                        Log.w(TAG, "Non-fatal logger exception (not counted toward retry cap)", e);
                    }
                    if (retryCount > 3 && localDriver != null) {
                        localDriver.disconnect();
                        if (localApiServer != null) localApiServer.setAdapterConnected(false);
                    }
                    try {
                        // Back off only after an actual transport failure.
                        // Parsing/derived-sensor faults receive a short yield
                        // so they cannot turn into a tight CPU loop either.
                        long delayMs = retryCount > 0
                                ? ReconnectBackoff.delayForAttempt(retryCount) : 250L;
                        if (retryCount > 0) {
                            notifySessionStatus(sessionToken, "Connection lost. Retrying in "
                                    + (delayMs / 1000) + "s (attempt " + retryCount + ")...", false);
                        }
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (!(e instanceof InterruptedException) && ownsActiveSession(sessionToken)) {
                notifySessionStatus(sessionToken, "Logger error: " + e.getMessage(), true);
                Log.e(TAG, "Logger error", e);
            }
        } finally {
            routeRecorder.stop();
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
            // Stop THIS session's sensor monitor (per-session local), never
            // the instance field: on a quick stop/restart the field already
            // belongs to the NEW session and stopping it would silently
            // stall its phone sensors.
            try {
                if (localAirDensity != null) localAirDensity.stopPhoneSensors();
            } catch (Exception ignored) {}

            if (clearPublishedSessionStateIfCurrent(sessionToken)) {
                // Keep liveMapStore alive briefly: MainActivity may still read
                // the last snapshot after stop. It is replaced on the next run.
                releaseWakeLock(sessionToken);
                requestServiceStopForSession(sessionToken, startId, localRecordCount);
            }
        }
    }

    private void stopLogging() {
        // Persist the learned VE surface before the session is torn down.
        // .apply() is async, so this does not block the main thread.
        VeMapPersistence.save(this, veMapStore);
        // One ΔVE trend point per session — the cross-session widening of the
        // petrol−LPG gap is the gaseous-fuel-system degradation signal.
        VeMapStore trendStore = veMapStore;
        if (trendStore != null) {
            try {
                VeTrendTracker.recordSession(this, trendStore.snapshot(),
                        System.currentTimeMillis());
            } catch (Exception e) {
                Log.w(TAG, "VE trend record failed non-fatally", e);
            }
        }
        ExecutorService loggerToStop;
        ExecutorService dtcToStop;
        synchronized (SESSION_STATE_LOCK) {
            running = false;
            loggerToStop = executor;
            dtcToStop = dtcExecutor;
            executor = null;
            dtcExecutor = null;
        }
        if (loggerToStop != null) {
            // shutdownNow() interrupts the running logger thread so Thread.sleep()
            // throws InterruptedException and the loop exits immediately instead
            // of waiting for the full sample interval to elapse.
            loggerToStop.shutdownNow();
            // Don't awaitTermination here because stopLogging() is called on the main thread
            // and blocking here can cause ANR (Application Not Responding).
        }
        if (dtcToStop != null) {
            dtcToStop.shutdownNow();
        }
    }

    /** Atomically retire published fields only if this worker still owns them. */
    private boolean clearPublishedSessionStateIfCurrent(long sessionToken) {
        synchronized (SESSION_STATE_LOCK) {
            if (!isCurrentSession(sessionToken)) return false;
            running = false;
            writer = null;
            apiServer = null;
            driver = null;
            airDensityMonitor = null;
            CrashReporter.noteSessionEnded();
            return true;
        }
    }

    /**
     * Service shutdown is serialized on the main looper with ACTION_START.
     * The token check and stopSelfResult(startId) prevent an old worker from
     * removing the foreground notification or stopping a newer start.
     */
    private void requestServiceStopForSession(
            long sessionToken, int startId, int totalRecords) {
        mainHandler.post(() -> {
            if (!isCurrentSession(sessionToken)) return;
            stopForeground(true);
            stopSelfResult(startId);
            LoggerCallback callback = getCallback();
            if (callback != null && isCurrentSession(sessionToken)) {
                callback.onStopped(totalRecords);
            }
        });
    }

    private void publishSessionRecord(long sessionToken, DataRecord record, int count) {
        if (!ownsActiveSession(sessionToken)) return;
        LoggerCallback cb = getCallback();
        if (cb != null) {
            mainHandler.post(() -> {
                if (ownsActiveSession(sessionToken)) cb.onRecord(record, count);
            });
        }
    }

    // Once-per-session latch so the low-voltage warning fires only once (not every tick).
    private boolean voltageWarned = false;
    private long voltageWarningSessionToken;

    /**
     * Warn when Control Module Voltage sags below the LPG charging floor. A weak
     * alternator / battery causes lean misfires that look like a fuel-trim problem,
     * so surfacing it early saves the user from chasing the wrong issue. Voltage is
     * read from the record's samples (key "01_42", which is lpgCritical=true and is
     * always polled), so no extra query is needed.
     */
    private void checkVoltageWatchdog(
            LoggerConfig config, long sessionToken, DataRecord record) {
        if (config == null || !config.lpgOnlyMode
                || !ownsActiveSession(sessionToken)) return;
        Double volts = null;
        for (SensorSample s : record.getSamples()) {
            if ("01_42".equals(s.getPidKey())) { volts = s.getValue(); break; }
        }
        // 13.0 V is a conservative "not charging well" floor for an LPG-running vehicle.
        if (volts != null && volts < 13.0) {
            synchronized (SESSION_STATE_LOCK) {
                if (!ownsActiveSession(sessionToken)) return;
                if (voltageWarningSessionToken != sessionToken) {
                    voltageWarningSessionToken = sessionToken;
                    voltageWarned = false;
                }
                if (voltageWarned) return;
                voltageWarned = true;
            }
            notifySessionStatus(sessionToken,
                    "⚠ Low voltage " + Math.round(volts * 10.0) / 10.0
                    + "V — check alternator/battery (causes lean misfire on LPG)", true);
        }
    }

    private void notifySessionStatus(
            long sessionToken, String status, boolean isError) {
        if (!ownsActiveSession(sessionToken)) return;
        LoggerCallback cb = getCallback();
        final int count = recordCount;
        mainHandler.post(() -> {
            if (!isCurrentSession(sessionToken)) return;
            if (cb != null) cb.onStatus(status, isError);
            updateNotification(status, count);
        });
    }

    private void updateSessionNotification(
            long sessionToken, String text, int count) {
        if (!ownsActiveSession(sessionToken)) return;
        mainHandler.post(() -> {
            if (ownsActiveSession(sessionToken)) {
                updateNotification(text, count);
            }
        });
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
                    CHANNEL_ID, localizedString(R.string.background_notification_channel_name,
                            "OBD2 background logger"),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(localizedString(
                    R.string.background_notification_channel_description,
                    "Live OBD2 collection and connection status"));
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text, int recordCount) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, LoggerService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(localizedString(R.string.app_name, "TunerMap Pro"))
                .setContentText(text)
                .setSubText(localizedString(R.string.records_count,
                        "Records: " + Math.max(0, recordCount), Math.max(0, recordCount)))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        localizedString(R.string.background_notification_stop, "Stop logging"),
                        stopPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setNumber(Math.max(0, recordCount))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private String localizedString(int resourceId, String fallback, Object... formatArgs) {
        try {
            return formatArgs != null && formatArgs.length > 0
                    ? getString(resourceId, formatArgs) : getString(resourceId);
        } catch (android.content.res.Resources.NotFoundException e) {
            // Defensive fallback for resource-table edge cases during process
            // restoration and for service-only test harnesses.
            return fallback;
        }
    }

    private void updateNotification(String text, int recordCount) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, buildNotification(text, recordCount));
            } catch (SecurityException e) {
                // Android 13+ may hide notifications after the user denies the
                // runtime permission; the foreground service remains valid.
                Log.w(TAG, "Notification hidden by system permission", e);
            }
        }
    }

    private synchronized void acquireWakeLock(long sessionToken) {
        if (!ownsActiveSession(sessionToken)) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            // Release any lock left over from a previous session BEFORE
            // overwriting the field — otherwise the old lock leaks (held
            // until its 8h timeout) with no reference left to release it.
            releaseWakeLock();
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBD2Logger::LoggingWakeLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(8 * 60 * 60 * 1000L); // 8 hours max
            wakeLockSessionToken = sessionToken;
            // The worker may have failed while PowerManager was acquiring the
            // lock. Do not leave a lock behind for a retired session.
            if (!ownsActiveSession(sessionToken)) releaseWakeLock(sessionToken);
        }
    }

    private synchronized void releaseWakeLock(long sessionToken) {
        if (wakeLockSessionToken != sessionToken) return;
        releaseWakeLock();
    }

    private synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        wakeLockSessionToken = 0L;
    }

    private List<String> getCachedPids(String vin) {
        return PidSupportCache.get(this, vin);
    }

    private void cachePids(String vin, List<String> pids) {
        PidSupportCache.put(this, vin, pids);
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
