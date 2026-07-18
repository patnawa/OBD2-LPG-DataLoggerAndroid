package com.alpha.obd2logger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for {@link VLinkerOptimizer}.
 *
 * Root cause guarded here: the optimizer used to send "AT NL" (ATNL = Normal
 * Length), which re-imposes the ELM327 7-data-byte-per-message limit and
 * TRUNCATES multi-PID batch responses. On real vLinker FS USB hardware this
 * silently dropped trailing PIDs — including MAP (0x0B) — so
 * MainActivity.updateFuelMap() never received a MAP value and the fuel map
 * stayed empty. Simulation never exercises ATNL or real CAN framing, so the bug
 * was invisible in sim. The fix sends ATAL (Allow Long) instead.
 *
 * These tests assert the recorded AT command stream contains ATAL and never
 * ATNL, for every vLinker device type.
 */
public class VLinkerOptimizerTest {

    /** A fake ElmDriver that records every raw command it is asked to send. */
    private static class RecordingElmDriver extends ElmDriver {
        final List<String> sent = new ArrayList<>();

        RecordingElmDriver(LoggerConfig config) {
            super(config);
            this.connected = true; // pretend we're connected so optimizations run
        }

        @Override
        public boolean connect() {
            return true;
        }

        @Override
        public void disconnect() {
            connected = false;
        }

        @Override
        public Double queryPid(PIDDefinition pidDef) {
            return null;
        }

        @Override
        protected String sendCommand(String command) {
            sent.add(command);
            return "OK";
        }

        // Helpers — normalize spaces so "AT NL" and "ATNL" compare equal.
        boolean sentContains(String atCmd) {
            String target = atCmd.replace(" ", "").toUpperCase();
            for (String c : sent) {
                if (c.replace(" ", "").toUpperCase().equals(target)) return true;
            }
            return false;
        }
    }

    private RecordingElmDriver run(VLinkerOptimizer.DeviceType type) {
        LoggerConfig config = new LoggerConfig();
        RecordingElmDriver drv = new RecordingElmDriver(config);
        VLinkerOptimizer.applyOptimizations(drv, type, config);
        return drv;
    }

    @Test
    public void fsUsbSendsAtalNotAtnl() {
        RecordingElmDriver drv = run(VLinkerOptimizer.DeviceType.VLINKER_FS_USB);
        assertTrue("FS USB must send ATAL (Allow Long) for full multi-PID responses",
                drv.sentContains("ATAL"));
        assertFalse("FS USB must NOT send ATNL — it truncates batch responses and empties the fuel map",
                drv.sentContains("ATNL"));
    }

    @Test
    public void mcWifiSendsAtalNotAtnl() {
        RecordingElmDriver drv = run(VLinkerOptimizer.DeviceType.VLINKER_MC_WIFI);
        assertTrue("MC WiFi must send ATAL", drv.sentContains("ATAL"));
        assertFalse("MC WiFi must NOT send ATNL", drv.sentContains("ATNL"));
    }

    @Test
    public void mcBtSendsAtalNotAtnl() {
        RecordingElmDriver drv = run(VLinkerOptimizer.DeviceType.VLINKER_MC_BT);
        assertTrue("MC BT must send ATAL", drv.sentContains("ATAL"));
        assertFalse("MC BT must NOT send ATNL", drv.sentContains("ATNL"));
        assertTrue("Bluetooth vLinker must retain the safe 200 ms ELM timeout",
                drv.sentContains("ATST32"));
        assertFalse("Bluetooth vLinker must not use the former 140 ms timeout",
                drv.sentContains("ATST23"));
    }

    @Test
    public void unversionedBluetoothVlinkerUsesBluetoothProfile() {
        assertEquals(VLinkerOptimizer.DeviceType.VLINKER_MC_BT,
                VLinkerOptimizer.classifyVLinkerVersion("vLinker MS", true));
        assertEquals(VLinkerOptimizer.DeviceType.VLINKER_MC_BT,
                VLinkerOptimizer.classifyVLinkerVersion(null, true));
        assertEquals(VLinkerOptimizer.DeviceType.VLINKER_MC_WIFI,
                VLinkerOptimizer.classifyVLinkerVersion("vLinker", false));
        assertEquals(6, VLinkerOptimizer.getRecommendedChunkSize(
                VLinkerOptimizer.classifyVLinkerVersion("vLinker MS", true)));
    }

    @Test
    public void genericElmGetsNoVlinkerSpecificCommands() {
        // Generic clones should not receive vLinker-only tuning; applyOptimizations
        // returns early for GENERIC_ELM327, so nothing extra is sent here.
        RecordingElmDriver drv = run(VLinkerOptimizer.DeviceType.GENERIC_ELM327);
        assertTrue("Generic ELM327 should not get vLinker optimizations applied",
                drv.sent.isEmpty());
    }

    @Test
    public void atCommandsHaveNoInternalSpaces() {
        // ELM327 AT commands are sent without internal spaces (matching
        // initializeElm327's conservative ATAT1/ATST32 style). Verify the optimizer's command
        // stream contains no space-separated AT mnemonics that older code used
        // (e.g. "AT AT 1", "AT ST 32", "AT NL").
        RecordingElmDriver drv = run(VLinkerOptimizer.DeviceType.VLINKER_FS_USB);
        for (String c : drv.sent) {
            assertFalse("AT command should not contain internal spaces: '" + c + "'",
                    c.matches("(?i)AT\\s+.*"));
        }
    }
}
