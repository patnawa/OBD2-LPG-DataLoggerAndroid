package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Guards automatic/manual scan-mode routing in both logger implementations. */
public class SmartDtcScanRoutingContractTest {

    @Test
    public void backgroundStartupIsSmartAndPeriodicMonitorIsQuickOnly() throws Exception {
        String source = readSource(
                "app/src/main/java/com/alpha/obd2logger/LoggerService.java",
                "src/main/java/com/alpha/obd2logger/LoggerService.java");

        assertEquals(1, occurrences(source, "SmartDtcScanPlanner.ScanMode.SMART"));
        assertEquals("periodic monitor and reconnect baseline are Quick", 2,
                occurrences(source, "SmartDtcScanPlanner.ScanMode.QUICK"));
        assertEquals(0, occurrences(source, "SmartDtcScanPlanner.ScanMode.FULL"));
        assertTrue(source.contains("vinReadThisSession\n                ? ManualVinStore.normalize"));
        assertTrue(source.contains("getSmartProtocolEvidence("));
        assertTrue(source.contains("replaceDtcSnapshotFromScan("));
        assertTrue(source.contains("initialDtcSnapshotGeneration"));
        assertTrue(source.contains("replaceCurrentDtcSnapshotFromQuick("));
        assertTrue(source.contains("localDriver.commandLock.lockInterruptibly()"));
        assertFalse("runtime Smart routing must never consume legacy v1 profiles",
                source.contains("VehicleModuleProfileStore.get(this, verifiedVinForDtcScan"));
        assertFalse(source.contains("DtcReader.readAllDtcs("));
        assertFalse(source.contains("DtcReader.readAllDtcsDeep("));
    }

    @Test
    public void foregroundAndManualPathsKeepFullScanExplicit() throws Exception {
        String source = readSource(
                "app/src/main/java/com/alpha/obd2logger/MainActivity.java",
                "src/main/java/com/alpha/obd2logger/MainActivity.java");

        assertEquals("startup and Read DTCs are Smart", 2,
                occurrences(source, "SmartDtcScanPlanner.ScanMode.SMART"));
        assertEquals("foreground monitor and post-clear verification are Quick", 2,
                occurrences(source, "SmartDtcScanPlanner.ScanMode.QUICK"));
        assertEquals("only the explicit Full Scan action may build FULL", 1,
                occurrences(source, "SmartDtcScanPlanner.ScanMode.FULL"));
        assertTrue(source.contains("final String scanVin = verifiedVehicleVinFor(activeDriver)"));
        assertTrue(source.contains("final String verifiedDtcVin = vinReadFromThisDriver"));
        assertTrue(source.contains("getSmartProtocolEvidence("));
        assertTrue(source.contains("saveSmartProtocolEvidenceFromScan("));
        assertTrue(source.contains("isCurrentDtcScanIdentity("));
        assertTrue(source.contains("activeDriver.commandLock.lockInterruptibly()"));
        assertFalse("runtime paths must use schema-v2 evidence entry point",
                source.contains("SmartDtcScanPlanner.createPlan("));
        assertFalse(source.contains("DtcReader.readAllDtcs("));
        assertFalse(source.contains("DtcReader.readAllDtcsDeep("));
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String readSource(String fromRoot, String fromModule) throws Exception {
        File file = new File(fromRoot);
        if (!file.exists()) file = new File(fromModule);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replace("\r", "");
    }
}
