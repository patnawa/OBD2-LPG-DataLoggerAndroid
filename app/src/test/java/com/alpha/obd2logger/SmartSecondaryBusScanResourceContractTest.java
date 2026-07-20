package com.alpha.obd2logger;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Locks the distinction between automatic Smart routing and manual Full Scan UI. */
public class SmartSecondaryBusScanResourceContractTest {

    @Test
    public void settingsRowUsesLocalizedSmartScanCopyWithoutFullScanClaim() throws Exception {
        String layout = readSource("app/src/main/res/layout/activity_main.xml",
                "src/main/res/layout/activity_main.xml");

        assertTrue(layout.contains("@string/smart_secondary_bus_scan_title"));
        assertTrue(layout.contains("@string/smart_secondary_bus_scan_description"));
        assertTrue(layout.contains("@+id/smartDtcScanCheckbox"));
        assertFalse(layout.contains("@+id/fordMsCanCheckbox"));
        assertFalse(layout.contains("android:text=\"Deep Multi-Protocol Scan\""));
        assertFalse(layout.contains("android:text=\"Scan all protocols"));
        assertTrue("The separate manual action must remain available",
                layout.contains("android:text=\"@string/dtc_full_scan\""));
    }

    @Test
    public void englishAndThaiBothExplainSmartVersusFullScan() throws Exception {
        String english = readSource("app/src/main/res/values/strings.xml",
                "src/main/res/values/strings.xml");
        String thai = readSource("app/src/main/res/values-th/strings.xml",
                "src/main/res/values-th/strings.xml");

        assertTrue(english.contains("name=\"smart_secondary_bus_scan_title\""));
        assertTrue(english.contains("Recommended: leave on"));
        assertTrue(english.contains("standard OBD-II protocols"));
        assertTrue(english.contains("Full Scan remains manual"));
        assertTrue(english.contains("name=\"dtc_full_scan_starting\""));
        assertTrue(english.contains("OBD-II protocols 1–9"));
        assertTrue(thai.contains("name=\"smart_secondary_bus_scan_title\""));
        assertTrue(thai.contains("สแกนหลายโปรโตคอลอัตโนมัติ"));
        assertTrue(thai.contains("แนะนำให้เปิดไว้"));
        assertTrue(thai.contains("Full Scan ต้องสั่งเองเสมอ"));
        assertTrue(thai.contains("name=\"dtc_full_scan_starting\""));
    }

    private static String readSource(String fromRoot, String fromModule) throws Exception {
        File file = new File(fromRoot);
        if (!file.exists()) file = new File(fromModule);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
