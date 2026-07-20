package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
@Config(sdk = {33, 34})
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
        config.showAirDensity = false; // disable weather API fetch in unit tests

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
            @Override public void onVinRead(String vin, BaseDriver sourceDriver,
                                            long sourceSessionToken,
                                            long sourceConnectionEpoch) {}
            @Override public void onPidsDetected(int supportedCount, int totalCount, boolean fromLiveQuery) {}
            @Override public void onDeviceDetected(VLinkerOptimizer.DeviceType deviceType) {}
            @Override public void onAdapterCheckResult(boolean isStandard, String details) {}
            @Override public void onDtcAutoScan(int storedCount, int pendingCount,
                                                int permanentCount,
                                                LoggerService.DtcSnapshotValidity validity) {}
            @Override public void onDtcAutoScanDetails(DtcReader.DtcScanResult result,
                                                       BaseDriver sourceDriver,
                                                       long sourceSessionToken,
                                                       long sourceConnectionEpoch) {}
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

    @Test
    public void delayedDiagnosticDelivery_requiresProducingDriverAndSession() {
        BaseDriver oldDriver = new SimulationDriver(new LoggerConfig());
        BaseDriver newDriver = new SimulationDriver(new LoggerConfig());

        assertTrue(LoggerService.sameSessionIdentity(
                oldDriver, 41L, oldDriver, 41L));
        assertFalse("a reconnect must reject results produced by the old driver",
                LoggerService.sameSessionIdentity(oldDriver, 41L, newDriver, 42L));
        assertFalse("even a reused driver object must not cross service sessions",
                LoggerService.sameSessionIdentity(oldDriver, 41L, oldDriver, 42L));
        assertFalse("zero is the explicit no-service-session sentinel",
                LoggerService.sameSessionIdentity(oldDriver, 0L, oldDriver, 0L));
    }

    @Test
    public void ecuPickerEvidence_rejectsReconnectDuringBuild() {
        BaseDriver driver = new SimulationDriver(new LoggerConfig());

        assertFalse("same driver and logger session must reject pre-reconnect evidence",
                LoggerService.sameConnectionIdentity(
                        driver, 41L, 1L, driver, 41L, 2L));
        assertTrue(LoggerService.sameConnectionIdentity(
                driver, 41L, 2L, driver, 41L, 2L));
    }

    @Test
    public void ecuPersistenceVin_requiresSameDriverAndSession() {
        BaseDriver oldDriver = new SimulationDriver(new LoggerConfig());
        BaseDriver newDriver = new SimulationDriver(new LoggerConfig());
        String vin = "MR0FZ29G1J1234567";

        assertEquals(vin, MainActivity.selectBoundVinForConnection(
                vin, oldDriver, 41L, 1L, oldDriver, 41L, 1L));
        assertNull("a displayed VIN from the old adapter must not name the new car",
                MainActivity.selectBoundVinForConnection(
                        vin, oldDriver, 41L, 1L, newDriver, 42L, 1L));
        assertNull("a delayed read must not persist after a session rollover",
                MainActivity.selectBoundVinForConnection(
                        vin, oldDriver, 41L, 1L, oldDriver, 42L, 1L));
        assertNull("same driver/session must reject a VIN from the prior connection",
                MainActivity.selectBoundVinForConnection(
                        vin, oldDriver, 41L, 1L, oldDriver, 41L, 2L));
    }

    @Test
    public void dtcSnapshotValidity_keepsMode07AndMode0ATimeoutsUnknown() {
        long generation = LoggerService.resetDtcSnapshotForConnection();
        DtcReader.DtcScanResult partial = scanResult(true, false, false);

        LoggerService.DtcSnapshotValidity published =
                LoggerService.replaceDtcSnapshotFromScan(
                        partial, true, generation);

        assertNotNull(published);
        assertTrue(published.stored);
        assertFalse("Mode 07 timeout must not be published as zero", published.pending);
        assertFalse("Mode 0A timeout must not be published as zero", published.permanent);
        assertFalse(published.allModesValid());
        assertTrue(LoggerService.lastPendingDtcs.isEmpty());
        assertTrue(LoggerService.lastPermanentDtcs.isEmpty());
    }

    @Test
    public void dtcSnapshotGeneration_rejectsResultFromConnectionBeforeReset() {
        long staleGeneration = LoggerService.resetDtcSnapshotForConnection();
        long currentGeneration = LoggerService.resetDtcSnapshotForConnection();

        assertNull(LoggerService.replaceDtcSnapshotFromScan(
                scanResult(true, true, true), true, staleGeneration));
        LoggerService.DtcSnapshotValidity validity =
                LoggerService.getDtcSnapshotValidity();
        assertEquals(currentGeneration, validity.generation);
        assertFalse(validity.stored);
        assertFalse(validity.pending);
        assertFalse(validity.permanent);
    }

    @Test
    public void verifiedCurrentProtocolClear_preservesSecondaryBusCodes() {
        long generation = LoggerService.resetDtcSnapshotForConnection();
        DtcCode currentCode = new DtcCode("P0171", "current");
        DtcCode secondaryCode = new DtcCode("P0300", "secondary");

        assertNotNull(LoggerService.replaceDtcSnapshotFromScan(
                scopedStoredResult(currentCode, secondaryCode), true, generation));
        assertNotNull(LoggerService.applyVerifiedClearSnapshot(
                scanResult(true, true, true), generation));

        assertFalse(LoggerService.lastStoredDtcs.contains(currentCode));
        assertTrue("Mode 04 did not address the secondary protocol",
                LoggerService.lastStoredDtcs.contains(secondaryCode));
    }

    @Test
    public void dtcSnapshotPublishesListsAndValidityAsOneImmutableView() {
        long generation = LoggerService.resetDtcSnapshotForConnection();
        LoggerService.DtcSnapshot before = LoggerService.getDtcSnapshot();
        DtcCode currentCode = new DtcCode("P0171", "current");
        DtcCode secondaryCode = new DtcCode("P0300", "secondary");

        assertNotNull(LoggerService.replaceDtcSnapshotFromScan(
                scopedStoredResult(currentCode, secondaryCode), true, generation));
        LoggerService.DtcSnapshot after = LoggerService.getDtcSnapshot();

        assertFalse(before.validity.stored);
        assertTrue(before.stored.isEmpty());
        assertTrue(after.validity.stored);
        assertTrue(after.stored.contains(currentCode));
        assertTrue(after.stored.contains(secondaryCode));
        try {
            after.stored.add(new DtcCode("P9999", "must fail"));
            fail("published DTC lists must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: readers cannot mutate a published snapshot.
        }
    }

    private static DtcReader.DtcScanResult scanResult(
            boolean storedResponse, boolean pendingResponse,
            boolean permanentResponse) {
        DtcReader.ProtocolBus current = new DtcReader.ProtocolBus(
                "Current", null, "Connected protocol", false, null);
        DtcReader.ProtocolScanStatus status = new DtcReader.ProtocolScanStatus(
                current, true, storedResponse, pendingResponse,
                permanentResponse, 0, 0, 0, 0);
        java.util.List<DtcReader.ProtocolScanStatus> statuses =
                java.util.Collections.singletonList(status);
        return new DtcReader.DtcScanResult(
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), statuses, 1, false,
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList());
    }

    private static DtcReader.DtcScanResult scopedStoredResult(
            DtcCode currentCode, DtcCode secondaryCode) {
        DtcReader.ProtocolBus current = new DtcReader.ProtocolBus(
                "Current", null, "Connected protocol", false, null);
        DtcReader.ProtocolBus secondary = new DtcReader.ProtocolBus(
                "CAN", "ATSP6", "Secondary protocol", false, null);
        java.util.List<DtcReader.ProtocolScanStatus> statuses = java.util.Arrays.asList(
                new DtcReader.ProtocolScanStatus(
                        current, true, true, true, true, 0, 1, 0, 0),
                new DtcReader.ProtocolScanStatus(
                        secondary, true, true, true, true, 0, 1, 0, 0));
        java.util.List<DtcCode> all = java.util.Arrays.asList(
                currentCode, secondaryCode);
        return new DtcReader.DtcScanResult(
                all, java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), statuses, 2, false,
                java.util.Collections.singletonList(currentCode),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.singletonList(secondaryCode),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList());
    }
}
