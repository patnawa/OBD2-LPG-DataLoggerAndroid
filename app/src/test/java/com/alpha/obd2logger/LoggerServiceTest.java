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
 */
@RunWith(RobolectricTestRunner.class)
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

        // Let the logger thread run. Poll the service's authoritative record count
        // (set on the executor thread) for up to ~5s. We pump the main looper each
        // iteration so any posted callbacks flush too. Robolectric starts the
        // executor thread lazily, so give it real wall-clock time.
        int svcCount = 0;
        for (int i = 0; i < 25; i++) {
            ShadowLooper.idleMainLooper();
            LoggerService svc = LoggerService.getInstance();
            svcCount = (svc != null) ? svc.getRecordCount() : 0;
            if (svcCount > 0) break;
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        // Stop cleanly.
        Intent stop = new Intent(app, LoggerService.class);
        stop.setAction(LoggerService.ACTION_STOP);
        controller.get().onStartCommand(stop, 0, 1);
        ShadowLooper.idleMainLooper();
        stopped.await(3, TimeUnit.SECONDS);

        assertTrue("background logging should produce records (svcCount=" + svcCount
                + ", callbackCount=" + recordCount.get() + "); regression: activeConfig==null "
                + "made the watchdog NPE and killed the session",
                svcCount > 0 || recordCount.get() > 0);
        assertNull("no error status expected on a clean run; got: " + errorStatus.get(),
                errorStatus.get());
    }
}
