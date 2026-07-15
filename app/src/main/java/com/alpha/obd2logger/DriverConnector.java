package com.alpha.obd2logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded, leak-free driver creation and connection. */
public final class DriverConnector {
    private DriverConnector() {}

    public static final class Result {
        private final BaseDriver driver;
        private final boolean timedOut;
        private final String error;

        private Result(BaseDriver driver, boolean timedOut, String error) {
            this.driver = driver;
            this.timedOut = timedOut;
            this.error = error == null ? "" : error;
        }

        public boolean isConnected() {
            return driver != null && driver.isConnected();
        }

        public BaseDriver getDriver() {
            return driver;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public String getError() {
            return error;
        }
    }

    /**
     * Includes AUTO transport probing inside the timeout. The old flow called
     * DriverFactory.create(AUTO) before starting its timeout, so USB/Wi-Fi/BT
     * probes could leave the UI on Connecting indefinitely.
     */
    public static Result connect(LoggerConfig config, long timeoutMs) {
        long boundedTimeoutMs = Math.max(1_000L, timeoutMs);
        AtomicReference<BaseDriver> candidate = new AtomicReference<>();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "obd-driver-connect");
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService connectionExecutor = Executors.newSingleThreadExecutor(factory);
        Future<BaseDriver> future = connectionExecutor.submit(() -> {
            BaseDriver driver = DriverFactory.create(config);
            candidate.set(driver);
            if (Thread.currentThread().isInterrupted()) {
                safeDisconnect(driver);
                return null;
            }
            if (driver.isConnected()) {
                return driver;
            }
            // Serialize connect() per driver: a timed-out attempt may still be
            // running connect() on this instance when the caller retries.
            if (!driver.tryAcquireConnectGate(boundedTimeoutMs)) {
                safeDisconnect(driver);
                return driver;
            }
            try {
                if (driver.connect()) {
                    return driver;
                }
            } finally {
                driver.releaseConnectGate();
            }
            safeDisconnect(driver);
            return driver;
        });

        try {
            BaseDriver driver = future.get(boundedTimeoutMs, TimeUnit.MILLISECONDS);
            if (driver != null && driver.isConnected()) {
                return new Result(driver, false, "");
            }
            String details = driver != null ? driver.getAdapterDetails() : "Connection cancelled";
            return new Result(driver, false, details);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            DriverFactory.cancelActiveProbe();
            safeDisconnect(candidate.get());
            return new Result(candidate.get(), true,
                    "Connection timed out after " + boundedTimeoutMs + " ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            DriverFactory.cancelActiveProbe();
            safeDisconnect(candidate.get());
            return new Result(candidate.get(), false, "Connection cancelled");
        } catch (Exception failure) {
            future.cancel(true);
            DriverFactory.cancelActiveProbe();
            safeDisconnect(candidate.get());
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            return new Result(candidate.get(), false,
                    cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        } finally {
            connectionExecutor.shutdownNow();
        }
    }

    /** Reconnect an existing driver with the same bounded, leak-free policy. */
    public static Result reconnect(BaseDriver driver, long timeoutMs) {
        if (driver == null) return new Result(null, false, "Driver unavailable");
        long boundedTimeoutMs = Math.max(1_000L, timeoutMs);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "obd-driver-reconnect");
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(factory);
        Future<Boolean> future = executor.submit(() -> {
            if (driver.isConnected()) {
                return true;
            }
            // A previous, abandoned connect attempt may still be inside
            // connect() on this driver — wait for it to exit (bounded)
            // instead of re-entering connect() concurrently.
            if (!driver.tryAcquireConnectGate(boundedTimeoutMs)) {
                return false;
            }
            try {
                return driver.connect();
            } finally {
                driver.releaseConnectGate();
            }
        });
        try {
            boolean connected = Boolean.TRUE.equals(
                    future.get(boundedTimeoutMs, TimeUnit.MILLISECONDS));
            return new Result(driver, false, connected ? "" : driver.getAdapterDetails());
        } catch (TimeoutException timeout) {
            future.cancel(true);
            safeDisconnect(driver);
            return new Result(driver, true,
                    "Reconnection timed out after " + boundedTimeoutMs + " ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            safeDisconnect(driver);
            return new Result(driver, false, "Reconnection cancelled");
        } catch (Exception failure) {
            future.cancel(true);
            safeDisconnect(driver);
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            return new Result(driver, false,
                    cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void safeDisconnect(BaseDriver driver) {
        if (driver == null) return;
        try {
            driver.disconnect();
        } catch (Throwable ignored) {
            // Connection cleanup must never replace the original failure.
        }
    }
}
