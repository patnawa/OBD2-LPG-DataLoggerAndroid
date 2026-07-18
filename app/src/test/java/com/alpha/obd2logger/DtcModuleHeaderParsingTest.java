package com.alpha.obd2logger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression coverage for CAN module attribution in the DTC scan.
 *
 * <p>The scan enabled headers ({@code ATH1}) but the driver configures
 * {@code ATS0} (spaces off) for polling and nothing ever re-enabled spaces —
 * {@code ATS1} did not appear anywhere in the codebase. The parser splits lines
 * on whitespace and skipped any line producing fewer than two tokens, so every
 * unspaced CAN frame was discarded before it could be parsed. The result was
 * zero detected modules <em>and</em> zero DTCs on every CAN bus.
 *
 * <p>The scan now sets {@code ATS1}, and the parser additionally handles the
 * unspaced form because ELM327 clones are known to ignore it.
 */
public class DtcModuleHeaderParsingTest {

    // ── splitUnspacedHeader ────────────────────────────────────────────────

    @Test
    public void splitsElevenBitHeaderFromUnspacedFrame() {
        assertArrayEquals(new String[] { "7E8", "0643040133000000" },
                DtcReader.splitUnspacedHeader("7E80643040133000000", "43"));
    }

    @Test
    public void splitsTwentyNineBitHeaderFromUnspacedFrame() {
        assertArrayEquals(new String[] { "18DAF110", "0643040133000000" },
                DtcReader.splitUnspacedHeader("18DAF1100643040133000000", "43"));
    }

    /**
     * The critical guard: a headers-off payload leads with the mode byte and
     * must not be mistaken for a frame addressed by CAN ID 0x430.
     */
    @Test
    public void headersOffPayloadIsNotMistakenForACanId() {
        assertNull(DtcReader.splitUnspacedHeader("4304013300", "43"));
        assertNull(DtcReader.splitUnspacedHeader("43 04 01 33 00", "43"));
    }

    @Test
    public void rejectsElmLengthLinesAndOutOfRangeIds() {
        assertNull("multi-frame length line", DtcReader.splitUnspacedHeader("010", "43"));
        assertNull("odd payload is not whole bytes",
                DtcReader.splitUnspacedHeader("7E8064304013300000", "43"));
        assertNull("11-bit CAN IDs are bounded by 0x7FF",
                DtcReader.splitUnspacedHeader("FFF0643040133000000", "43"));
        assertNull("empty", DtcReader.splitUnspacedHeader("", "43"));
        assertNull("null", DtcReader.splitUnspacedHeader(null, "43"));
    }

    // ── end-to-end parsing ─────────────────────────────────────────────────

    /** The format the parser's javadoc documents — must keep working. */
    @Test
    public void spacedFramesYieldModulesAndCodes() {
        DtcReader.ScanLineResult result = DtcReader.parseWithModuleHeaders(
                "7E8 06 43 04 01 33 00 00 00 00\r", "43", "HS-CAN");

        assertFalse("spaced frame must yield DTCs", result.codes.isEmpty());
        assertTrue("engine ECU must be attributed",
                result.perModule.containsKey(0x7E8));
    }

    /**
     * The bug: identical content without spaces used to produce nothing at all.
     */
    @Test
    public void unspacedFramesYieldTheSameModulesAndCodes() {
        DtcReader.ScanLineResult spaced = DtcReader.parseWithModuleHeaders(
                "7E8 06 43 04 01 33 00 00 00 00\r", "43", "HS-CAN");
        DtcReader.ScanLineResult unspaced = DtcReader.parseWithModuleHeaders(
                "7E80643040133000000\r", "43", "HS-CAN");

        assertFalse("unspaced frame must not be silently discarded",
                unspaced.codes.isEmpty());
        assertEquals("unspaced must decode the same DTC count as spaced",
                spaced.codes.size(), unspaced.codes.size());
        assertEquals("unspaced must decode the same DTCs",
                spaced.codes.toString(), unspaced.codes.toString());
        assertTrue("unspaced must still attribute the module",
                unspaced.perModule.containsKey(0x7E8));
    }

    /** Multiple ECUs answering the broadcast must each be listed separately. */
    @Test
    public void multipleUnspacedModulesAreAttributedIndependently() {
        DtcReader.ScanLineResult result = DtcReader.parseWithModuleHeaders(
                "7E80643040133000000\r7EA0643071500000000\r", "43", "HS-CAN");

        assertEquals("both responding modules must be detected",
                2, result.perModule.size());
        assertTrue(result.perModule.containsKey(0x7E8));
        assertTrue(result.perModule.containsKey(0x7EA));
    }

    /** Headers-off responses must still parse, unchanged. */
    @Test
    public void headersOffResponseStillParses() {
        DtcReader.ScanLineResult result = DtcReader.parseWithModuleHeaders(
                "43 04 01 33 00 00 00\r", "43", "HS-CAN");

        assertNotNull(result);
        assertFalse("headers-off path must be unaffected by the header fix",
                result.codes.isEmpty());
    }
}
