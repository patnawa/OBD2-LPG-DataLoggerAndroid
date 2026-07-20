package com.alpha.obd2logger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Guards the UI/publication contract for partial Mode 03/07/0A scans. */
public class DtcSnapshotValidityContractTest {

    @Test
    public void publicationUsesPerModeValidityAndConnectionGeneration() throws Exception {
        String service = readSource(
                "app/src/main/java/com/alpha/obd2logger/LoggerService.java",
                "src/main/java/com/alpha/obd2logger/LoggerService.java");

        assertTrue(service.contains("class DtcSnapshotValidity"));
        assertTrue(service.contains("boolean stored;"));
        assertTrue(service.contains("boolean pending;"));
        assertTrue(service.contains("boolean permanent;"));
        assertTrue(service.contains("dtcSnapshotGeneration != expectedGeneration"));
        assertTrue(service.contains("hasCompletePendingSnapshot"));
        assertTrue(service.contains("hasCompletePermanentSnapshot"));
        assertFalse("one success bit cannot represent three DTC services",
                service.contains("boolean lastDtcSnapshotValid;"));
    }

    @Test
    public void englishAndThaiExplainUnknownIsNotZero() throws Exception {
        String english = readSource(
                "app/src/main/res/values/strings.xml",
                "src/main/res/values/strings.xml");
        String thai = readSource(
                "app/src/main/res/values-th/strings.xml",
                "src/main/res/values-th/strings.xml");

        assertTrue(english.contains("name=\"dtc_scan_partial\""));
        assertTrue(english.contains("not as zero"));
        assertTrue(english.contains("name=\"dtc_pending_unavailable\""));
        assertTrue(english.contains("name=\"dtc_permanent_unavailable\""));
        assertTrue(thai.contains("name=\"dtc_scan_partial\""));
        assertTrue(thai.contains("ไม่ใช่ศูนย์"));
        assertTrue(thai.contains("name=\"dtc_pending_unavailable\""));
        assertTrue(thai.contains("name=\"dtc_permanent_unavailable\""));
    }

    private static String readSource(String fromRoot, String fromModule) throws Exception {
        File file = new File(fromRoot);
        if (!file.exists()) file = new File(fromModule);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
