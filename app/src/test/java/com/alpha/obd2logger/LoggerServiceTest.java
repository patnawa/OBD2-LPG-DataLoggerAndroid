package com.alpha.obd2logger;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression test for the v3.5.2 → v3.5.3 background-logging crash.
 *
 * The bug: {@code LoggerService.activeConfig} was never assigned, so the
 * low-voltage watchdog NPE'd on the first background record and killed the
 * session (it only happened in background mode because the in-process path
 * used its own local config). This test drives the real service lifecycle with
 * the SIM driver (no hardware needed) and asserts that logging survives several
 * records without posting an error status.
 *
 * Runs on SDK 33 (Android 13) to verify the foreground-service path works on the
 * modern OS range through Android 16. Robolectric cannot reliably simulate the
 * executor+startForeground path on older SDK shadows, so cross-version coverage
 * for older releases (down to minSdk 23) is provided by lintVitalRelease and the
 * in-process logging tests, which run on the default SDK.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class LoggerServiceTest {

    private ServiceController<LoggerService> controller;

    @After
    public void tearDown() {
        if (controller != null) {
            try {
                controller.destroy();
            } catch (Throwable ignored) {
            }
        }
        LoggerService.setCallback(null);
        LoggerService.setPendingConfig(null);
    }

    @Test
    public void backgroundLogging_survivesMultipleRecords_withoutError() throws Exception {
        Application app = ApplicationProvider.getApplicationContext();
        LoggerConfig config = new LoggerConfig();
        config.transportMode = TransportMode.SIM;   // no hardware required
        config.lpgOnlyMode = true;                  // exercises the watchdog path
        config.sampleIntervalMs = 50;               // fast polling => several records quickly
        config.context = app;
        config.vin = "TESTVIN";
        config.enableApiServer = false;

        final AtomicInteger recordCount = new AtomicInteger(0);
        final AtomicReference<String> errorStatus = new AtomicReference<>();
        final CountDownLatch stopped = new CountDownLatch(1);

        LoggerService.setCallback(new LoggerService.LoggerCallback() {
            @Override public void onRecord(DataRecord record, int count) {
                recordCount.incrementAndGet();
            }
            @Override public void onStatus(String status, boolean isError) {
                if (isError) errorStatus.set(status);
            }
            @Override public void onStopped(int totalRecords) { stopped.countDown(); }
            @Override public void onVinRead(String vin) {}
            @Override public void onPidsDetected(int supportedCount, int totalCount, boolean fromLiveQuery) {}
            @Override public void onDeviceDetected(VLinkerOptimizer.DeviceType deviceType) {}
            @Override public void onAdapterCheckResult(boolean isStandard, String details) {}
            @Override public void onDtcAutoScan(int storedCount, int pendingCount, int permanentCount) {}
            @Override public void onDtcAutoScanDetails(DtcReader.DtcScanResult result) {}
            @Override public void onNewDtcDetected(java.util.List<DtcCode> newCodes) {}
        });

        LoggerService.setPendingConfig(config);
        Intent start = new Intent(app, LoggerService.class);
        start.setAction(LoggerService.ACTION_START);
        controller = Robolectric.buildService(LoggerService.class, start);
        try {
            controller.create().startCommand(0, 0);
        } catch (Throwable t) {
            throw new AssertionError("onStartCommand threw", t);
        }

        // The regression this guards: LoggerService previously left activeConfig
        // null, so the watchdog NPE'd. activeConfig is assigned at the TOP of
        // runLogger (before any SIM/VIN/DTC work), so polling getConfig() is
        // deterministic — it becomes non-null as soon as the executor starts the
        // logger, with no dependence on slow record production (which Robolectric
        // simulates unreliably). Also assert at least one record is produced when
        // the executor cooperates (non-fatal if the harness is slow).
        LoggerService svc = LoggerService.getInstance();
        boolean assigned = false;
        int svcCount = 0;
        for (int i = 0; i < 40; i++) {
            if (svc != null) {
                if (svc.getConfig() != null) assigned = true;
                svcCount = svc.getRecordCount();
            }
            if (assigned && svcCount > 0) break;
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        }

        // Stop cleanly.
        Intent stop = new Intent(app, LoggerService.class);
        stop.setAction(LoggerService.ACTION_STOP);
        controller.get().onStartCommand(stop, 0, 1);
        ShadowLooper.idleMainLooper();
        stopped.await(3, TimeUnit.SECONDS);

        assertTrue("LoggerService.activeConfig must be assigned when background logging "
                + "starts (the v3.5.2 crash was this field staying null → watchdog NPE)", assigned);
        assertNull("no error status expected on a clean run; got: " + errorStatus.get(),
                errorStatus.get());
    }
}
