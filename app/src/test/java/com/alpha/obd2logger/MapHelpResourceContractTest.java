package com.alpha.obd2logger;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Locks the safety-critical meaning and localization of the Live Map help. */
public class MapHelpResourceContractTest {

    @Test
    public void dialogUsesLocalizedHelpAndFormulaResources() throws Exception {
        String layout = readSource("app/src/main/res/layout/dialog_map_info.xml",
                "src/main/res/layout/dialog_map_info.xml");
        String main = readSource("app/src/main/res/layout/activity_main.xml",
                "src/main/res/layout/activity_main.xml");
        String review = readSource("app/src/main/res/layout/activity_review_session.xml",
                "src/main/res/layout/activity_review_session.xml");

        assertTrue(layout.contains("@string/how_to_read_map_desc"));
        assertTrue(layout.contains("@string/map_info_trim_formula"));
        assertTrue(layout.contains("@string/map_info_delta_formula"));
        assertTrue(layout.contains("@string/map_info_tune_formula"));
        assertFalse("Formula labels must not stay hardcoded in English",
                layout.contains("android:text=\"Formula:"));
        assertTrue(main.contains("@string/map_formula_delta_title"));
        assertTrue(review.contains("@string/map_formula_delta_title"));
        assertFalse(main.contains("android:text=\"Deviation = LPG Trim"));
        assertFalse(review.contains("android:text=\"Deviation = LPG Trim"));
    }

    @Test
    public void englishAndThaiExplainDeltaUnitsExamplesAndDieselLimit() throws Exception {
        String english = readSource("app/src/main/res/values/strings.xml",
                "src/main/res/values/strings.xml");
        String thai = readSource("app/src/main/res/values-th/strings.xml",
                "src/main/res/values-th/strings.xml");

        assertTrue(english.contains("percentage points (pp)"));
        assertTrue(english.contains("Δ +6 pp"));
        assertTrue(english.contains("approximate gas-map adjustment percentage"));
        assertTrue(english.contains("some Ford diesels"));
        assertTrue(thai.contains("จุดเปอร์เซ็นต์ (pp)"));
        assertTrue(thai.contains("Δ +6 pp"));
        assertTrue(thai.contains("เปอร์เซ็นต์ปรับตารางแก๊สโดยประมาณ"));
        assertTrue(thai.contains("Ford ดีเซลบางรุ่น"));
    }

    private static String readSource(String fromRoot, String fromModule) throws Exception {
        File file = new File(fromRoot);
        if (!file.exists()) file = new File(fromModule);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
