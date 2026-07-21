package com.alpha.obd2logger;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds the EXACT multi-frame responses captured from the field ELM327 v2.3
 * (segmented "00B\r0:..\r1:.." ISO-TP format) into the real batch parser to
 * see whether live values actually come out. Diagnoses the "connected but no
 * data" field report.
 */
public class CapturedMultiFrameParseTest {

    private static List<PIDDefinition> chunk(String... keys) {
        List<PIDDefinition> pids = new ArrayList<>();
        for (String k : keys) {
            PIDDefinition p = PIDDefinition.findByKey(k);
            assertNotNull("PID must exist: " + k, p);
            pids.add(p);
        }
        return pids;
    }

    @Test
    public void capturedRpmBatch_parsesValues() {
        // TX[01 0C 04 03 05] -> RX[00B 0:410C00000400 1:03010005620000]
        List<PIDDefinition> chunk = chunk("01_0C", "01_04", "01_03", "01_05");
        Map<String, Double> out = new LinkedHashMap<>();
        PIDParser.extractMulti(chunk,
                "00B\r0:410C00000400\r1:03010005620000\r>", out);
        System.out.println("[CAPTURE] rpm batch -> " + out);
        assertTrue("expected at least one value from RPM batch, got " + out,
                !out.isEmpty());
    }

    @Test
    public void capturedTrimBatch_parsesValues() {
        // TX[01 06 07 11 0D] -> RX[009 0:410680077B11 1:2F0D0000000000]
        List<PIDDefinition> chunk = chunk("01_06", "01_07", "01_11", "01_0D");
        Map<String, Double> out = new LinkedHashMap<>();
        PIDParser.extractMulti(chunk,
                "009\r0:410680077B11\r1:2F0D0000000000\r>", out);
        System.out.println("[CAPTURE] trim batch -> " + out);
        assertTrue("expected at least one value from trim batch, got " + out,
                !out.isEmpty());
    }
}
