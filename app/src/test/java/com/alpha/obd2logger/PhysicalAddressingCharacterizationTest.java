package com.alpha.obd2logger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the ELM327 command sequences used to address one ECU physically.
 *
 * <p>Physical addressing has a history of subtle, hard-to-spot faults: a bare
 * {@code ATCRA} clears the receive filter but {@code ATCRA000} sets it to CAN
 * ID 0x000, and {@code ATSH000} sets a literal header rather than restoring the
 * default. Both leave the adapter mute for every later poll. These tests record
 * the exact command order so a refactor of the addressing helpers cannot change
 * it unnoticed.
 */
public class PhysicalAddressingCharacterizationTest {

    /** Records every command and answers just enough for a scan to proceed. */
    private static class RecordingDriver extends BaseDriver {
        final List<String> commands = new ArrayList<>();
        /** CAN IDs (as ATSH argument) that will answer a Mode 03 request. */
        final List<String> respondingHeaders = new ArrayList<>();
        private String header = "7DF";

        RecordingDriver() {
            super(new LoggerConfig());
            connected = true;
        }

        @Override public boolean connect() { connected = true; return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override
        public String sendCommandRaw(String command) {
            commands.add(command);
            if (command.startsWith("ATSH")) {
                header = command.substring(4);
                return "OK";
            }
            if (command.startsWith("AT")) return "OK";
            if ("0100".equals(command)) return "41 00 00 00 00 00";
            if ("03".equals(command)) {
                return respondingHeaders.contains(header) ? "43 01 01 71" : "NO DATA";
            }
            if ("07".equals(command) || "0A".equals(command)) {
                return respondingHeaders.contains(header) ? "47 00" : "NO DATA";
            }
            return "";
        }

        /** Commands issued after the last occurrence of {@code marker}. */
        List<String> after(String marker) {
            int index = commands.lastIndexOf(marker);
            return index < 0 ? new ArrayList<>()
                    : new ArrayList<>(commands.subList(index + 1, commands.size()));
        }
    }

    // ── scanEcuDirectly ────────────────────────────────────────────────

    @Test
    public void scanEcuDirectlyAddressesThenRestoresElevenBitFunctionalHeader() {
        RecordingDriver driver = new RecordingDriver();
        driver.respondingHeaders.add("7E0");

        DtcReader.scanEcuDirectly(driver, "7E0", "7E8");

        assertEquals("ATSH7E0", driver.commands.get(0));
        assertEquals("ATCRA7E8", driver.commands.get(1));

        // Teardown order matters: clear the filter, restore automatic receive
        // addressing, then put the functional broadcast header back.
        List<String> teardown = driver.after("0A");
        assertEquals(java.util.Arrays.asList("ATCRA", "ATAR", "ATSH7DF"), teardown);
    }

    @Test
    public void scanEcuDirectlyRestoresTwentyNineBitFunctionalHeader() {
        RecordingDriver driver = new RecordingDriver();

        DtcReader.scanEcuDirectly(driver, "18DA10F1", "18DAF110");

        assertEquals("ATSH18DA10F1", driver.commands.get(0));
        assertEquals("ATCRA18DAF110", driver.commands.get(1));
        assertTrue("29-bit buses need the 29-bit functional address restored",
                driver.commands.contains("ATSH18DB33F1"));
        assertFalse("restoring the 11-bit header would strand a 29-bit bus",
                driver.commands.contains("ATSH7DF"));
    }

    @Test
    public void scanEcuDirectlyNeverClearsFilterByAddressingCanIdZero() {
        RecordingDriver driver = new RecordingDriver();

        DtcReader.scanEcuDirectly(driver, "7E0", "7E8");

        assertFalse("ATCRA000 sets a filter for CAN 0x000 instead of clearing it",
                driver.commands.contains("ATCRA000"));
        assertFalse("ATSH000 sets a literal header instead of restoring the default",
                driver.commands.contains("ATSH000"));
    }

    // ── deep-scan physical sweep ───────────────────────────────────────

    @Test
    public void deepSweepPairsEachRequestHeaderWithItsPlusEightResponseFilter() {
        RecordingDriver driver = new RecordingDriver();
        driver.respondingHeaders.add("7E2");

        DtcReader.readAllDtcsDeep(driver, false);

        // Every ATSH7Ex in the sweep is immediately followed by ATCRA(x+8).
        for (int i = 0; i < driver.commands.size() - 1; i++) {
            String command = driver.commands.get(i);
            if (!command.matches("ATSH7E[0-7]")) continue;
            int tx = Integer.parseInt(command.substring(4), 16);
            assertEquals("request header must be paired with its response filter",
                    String.format(java.util.Locale.US, "ATCRA%03X", tx + 8),
                    driver.commands.get(i + 1));
        }
    }

    @Test
    public void deepSweepProbesWithModeThreeAndOnlyEscalatesOnAResponse() {
        RecordingDriver driver = new RecordingDriver();
        driver.respondingHeaders.add("7E2");

        DtcReader.readAllDtcsDeep(driver, false);

        // A silent address costs one Mode 03 and nothing more; the responder
        // additionally gets Mode 07 and Mode 0A.
        int probes = java.util.Collections.frequency(driver.commands, "03");
        int pending = java.util.Collections.frequency(driver.commands, "07");
        assertTrue("every candidate address is probed with Mode 03", probes > pending);
        assertTrue("the responding module is escalated to Mode 07", pending >= 1);
    }

    @Test
    public void deepSweepRestoresFunctionalAddressingWhenItFinishes() {
        RecordingDriver driver = new RecordingDriver();

        DtcReader.readAllDtcsDeep(driver, false);

        int lastClear = driver.commands.lastIndexOf("ATCRA");
        int lastFunctional = driver.commands.lastIndexOf("ATSH7DF");
        assertTrue("the receive filter is cleared before the scan ends", lastClear >= 0);
        assertTrue("functional addressing is restored after the filter is cleared",
                lastFunctional > lastClear);
    }

    @Test
    public void deepSweepStopsWhenTheAdapterRejectsARequestHeader() {
        // An adapter that resolved to 29-bit rejects ATSH7Ex with "?". Sweeping
        // the remaining addresses would fail identically, so it must stop.
        RecordingDriver driver = new RecordingDriver() {
            @Override
            public String sendCommandRaw(String command) {
                if (command.startsWith("ATSH7E")) {
                    commands.add(command);
                    return "?";
                }
                return super.sendCommandRaw(command);
            }
        };

        DtcReader.readAllDtcsDeep(driver, false);

        // Each bus is entitled to one attempt — a bus that resolved to 29-bit
        // says nothing about the next one — but within a bus the remaining
        // seven addresses must be abandoned rather than retried identically.
        int attemptsOnThisBus = 0;
        for (String command : driver.commands) {
            if (command.startsWith("ATSP")) {
                attemptsOnThisBus = 0;
            } else if (command.matches("ATSH7E[0-7]")) {
                attemptsOnThisBus++;
                assertTrue("sweep must stop after the first rejected header on a bus",
                        attemptsOnThisBus <= 1);
            }
        }
    }
}
