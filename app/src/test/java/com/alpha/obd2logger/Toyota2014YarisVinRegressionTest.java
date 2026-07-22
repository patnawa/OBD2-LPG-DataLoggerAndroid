package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feedback loop for the field regression: 2014 Toyota Yaris (petrol,
 * Thai market) stopped returning a VIN after the physical-fallback budget
 * was cut 30 s → 3 s (commit 6333681, tuned against a 2004 Yaris that has
 * NO readable VIN).
 *
 * <p>Pre-2016 Asian-market Toyotas do not implement Mode 09 Info Type 02 —
 * their VIN is only readable via UDS 22 F1 90 through the physical ECU
 * sweep (established in "Fix Toyota VIN detection"). Every existing
 * VinReaderTest fake returns instantly, so the time budget never expires
 * in tests and the suite stayed green while the real car broke.
 *
 * <p>These drivers charge realistic wall time to an injected fake clock:
 * <ul>
 *   <li>AT / setup command: 80 ms (Bluetooth ELM round trip)</li>
 *   <li>dead extended-timeout data request (NO DATA under ATSTFF): 1200 ms
 *       — deliberately CHARITABLE; the 6333681 commit message's own field
 *       data (30 s spent on the 7E0–7E4 sweep ≈ 15 commands) implies ~2 s</li>
 *   <li>answered data request: 400 ms</li>
 * </ul>
 */
public class Toyota2014YarisVinRegressionTest {

    private static final long MS = 1_000_000L;
    private static final String YARIS_VIN = "MR2KT9F30E1234567"; // MR2 = Toyota Thailand

    /**
     * UDS-only Toyota with realistic command latency. The engine ECU (7E0)
     * answers DID F190 — the MOST charitable layout for the current sweep:
     * the very first physical target owns the VIN.
     */
    private static class TimedYarisDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();
        final AtomicLong clock = new AtomicLong();
        String header = "7DF";
        int udsAttemptsAt7E0;

        TimedYarisDriver(LoggerConfig config) {
            super(config);
            connected = true;
        }

        @Override public boolean connect() { return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        private String vinFrames() {
            return "7E8 10 14 62 F1 90 4D 52 32\r"
                    + "7E8 21 4B 54 39 46 33 30 45\r"
                    + "7E8 22 31 32 33 34 35 36 37\r>";
        }

        @Override
        protected String sendCommand(String command) {
            sent.add(command);
            if (command.startsWith("ATSH")) {
                header = command.substring(4);
                clock.addAndGet(80 * MS);
                return "OK\r>";
            }
            if (command.startsWith("AT")) {
                clock.addAndGet(80 * MS);
                if ("ATDPN".equals(command)) return "A6\r>";
                return "OK\r>";
            }
            if ("0902".equals(command)) {
                // No Mode 09 on this vehicle — adapter waits out ATSTFF.
                clock.addAndGet(1200 * MS);
                return "NO DATA\r>";
            }
            if ("22F190".equals(command)) {
                if ("7E0".equals(header)) {
                    udsAttemptsAt7E0++;
                    clock.addAndGet(400 * MS);
                    return vinFrames();
                }
                clock.addAndGet(1200 * MS);
                return "NO DATA\r>";
            }
            clock.addAndGet(80 * MS);
            return "OK\r>";
        }
    }

    /**
     * THE RED LOOP — the user's exact symptom. Same driver, same parsing,
     * production time budget: the VIN must come back, because this vehicle
     * HAS a readable VIN and the app read it before the budget cut.
     */
    @Test
    public void yaris2014VinIsReadUnderTheProductionTimeBudget() {
        TimedYarisDriver driver = new TimedYarisDriver(new LoggerConfig());
        String vin = VinReader.readVin(driver, driver.clock::get,
                VinReader.PHYSICAL_FALLBACK_BUDGET_NANOS);
        assertEquals("2014 Yaris (UDS-only VIN at 7E0) must be readable "
                        + "under the production physical-fallback budget",
                YARIS_VIN, vin);
    }

    /**
     * CONTROL — identical driver and latency under the pre-regression 30 s
     * budget. If this passes while the production-budget test fails, the
     * budget is the broken variable, not parsing or the sweep itself.
     */
    @Test
    public void controlSameVehicleSucceedsUnderTheOldThirtySecondBudget() {
        TimedYarisDriver driver = new TimedYarisDriver(new LoggerConfig());
        String vin = VinReader.readVin(driver, driver.clock::get, 30_000_000_000L);
        assertNotNull("with the pre-6333681 budget this vehicle's VIN was readable", vin);
        assertEquals(YARIS_VIN, vin);
    }

    /**
     * Harder layout: VIN owned by a gateway at 7E2 (toyotaVinFallbackReaches-
     * GatewayAt7e2 models this vehicle, latency-free). Two dead F190s must fit
     * before the answering one — this is what the F190-first order buys.
     */
    @Test
    public void gatewayVinAt7e2IsReachedUnderTheProductionTimeBudget() {
        TimedYarisDriver driver = new TimedYarisDriver(new LoggerConfig()) {
            @Override
            protected String sendCommand(String command) {
                // Move the VIN from the engine ECU to the 7E2 gateway. The
                // engine and TCM are alive — a live ECU NAKs an unsupported
                // DID quickly (7F 22 31) instead of timing out silently.
                if ("22F190".equals(command)) {
                    sent.add(command);
                    if ("7E2".equals(header)) {
                        clock.addAndGet(400 * MS);
                        return "7EA 10 14 62 F1 90 4D 52 32\r"
                                + "7EA 21 4B 54 39 46 33 30 45\r"
                                + "7EA 22 31 32 33 34 35 36 37\r>";
                    }
                    clock.addAndGet(150 * MS);
                    return "7E8 03 7F 22 31\r>";
                }
                return super.sendCommand(command);
            }
        };
        String vin = VinReader.readVin(driver, driver.clock::get,
                VinReader.PHYSICAL_FALLBACK_BUDGET_NANOS);
        assertEquals(YARIS_VIN, vin);
    }

    /**
     * The OTHER car in this tug-of-war: a vehicle with no readable VIN at all
     * (2004 Yaris) must still bail out near the budget — the sweep may finish
     * its in-flight command but must never wander toward the original 30 s.
     */
    @Test
    public void noVinVehicleStillBailsOutNearTheBudget() {
        TimedYarisDriver driver = new TimedYarisDriver(new LoggerConfig()) {
            @Override
            protected String sendCommand(String command) {
                if ("22F190".equals(command)) {
                    sent.add(command);
                    clock.addAndGet(1200 * MS);
                    return "NO DATA\r>"; // no ECU owns F190 either
                }
                return super.sendCommand(command);
            }
        };
        assertNull(VinReader.readVin(driver, driver.clock::get,
                VinReader.PHYSICAL_FALLBACK_BUDGET_NANOS));

        // The sharp property: the silent-address early exit bounds the sweep
        // to exactly 3 dead F190 attempts plus the single engine-ECU 0902
        // safety retry — never a walk across all 8 addresses.
        long f190Sends = driver.sent.stream().filter("22F190"::equals).count();
        long mode09Sends = driver.sent.stream().filter("0902"::equals).count();
        assertEquals(VinReader.MAX_CONSECUTIVE_SILENT, f190Sends);
        assertEquals("2 functional attempts + 1 engine-ECU physical retry",
                3, mode09Sends);

        // Sanity ceiling on wall time: pre-sweep functional attempts (~3.5 s,
        // present on every code path since long before the regression) + the
        // budget-bounded sweep. Anything drifting back toward the original
        // 30 s sweep behavior fails this loudly.
        long ceilingNanos = VinReader.PHYSICAL_FALLBACK_BUDGET_NANOS + 6_000 * MS;
        assertTrue("no-VIN bail-out took " + driver.clock.get() / 1_000_000 + " ms",
                driver.clock.get() <= ceilingNanos);
    }
}
