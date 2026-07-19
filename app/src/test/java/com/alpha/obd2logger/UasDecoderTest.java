package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * SAE J1979 UAS decoding — the Mode 06 scaling table.
 *
 * A wrong factor here silently corrupts every Mode 06 test result shown to
 * the user, so each family of scalings gets a representative check, plus the
 * two subtle behaviors: signed (0x81+) two's-complement decoding, and the
 * unsigned-only −40 °C offset on UASID 0x16.
 */
public class UasDecoderTest {

    private static final double EPS = 1e-9;

    // ── Unsigned scalings ─────────────────────────────────────────────

    @Test
    public void rawCount_onePerBit() {
        assertEquals(1234.0, UasDecoder.scale(0x01, 1234), EPS);
    }

    @Test
    public void rpm_quarterPerBit() {
        // 0x1F40 = 8000 raw → 2000 rpm
        assertEquals(2000.0, UasDecoder.scale(0x07, 0x1F40), EPS);
    }

    @Test
    public void milliVolts_0p122PerBit() {
        // Typical O2 sensor Mode 06 value: 3600 raw → 439.2 mV
        assertEquals(439.2, UasDecoder.scale(0x0A, 3600), EPS);
    }

    @Test
    public void unsignedTempC_hasMinus40Offset() {
        // 0x16 unsigned: v * 0.1 - 40 → raw 1300 = 90 °C
        assertEquals(90.0, UasDecoder.scale(0x16, 1300), EPS);
        // raw 0 = -40 °C (sensor floor)
        assertEquals(-40.0, UasDecoder.scale(0x16, 0), EPS);
    }

    @Test
    public void lambda_0p0000305PerBit() {
        // raw 32787 ≈ lambda 1.0000535
        assertEquals(32787 * 0.0000305, UasDecoder.scale(0x1E, 32787), EPS);
    }

    @Test
    public void percent_0p001526PerBit_fullScaleIsRoughly100() {
        assertEquals(100.0, UasDecoder.scale(0x30, 65535), 0.05);
    }

    @Test
    public void ratio_oneOver256PerBit() {
        assertEquals(1.0, UasDecoder.scale(0x20, 256), EPS);
    }

    // ── Signed (0x81+) scalings ───────────────────────────────────────

    @Test
    public void signed_rawCount_decodesTwosComplement() {
        // 0x81 = signed 0x01: 0xFFFF → -1
        assertEquals(-1.0, UasDecoder.scale(0x81, 0xFFFF), EPS);
        assertEquals(-32768.0, UasDecoder.scale(0x81, 0x8000), EPS);
    }

    @Test
    public void signedTempC_isTenthPerBit_withoutOffset() {
        // 0x96 = signed 0x16: v * 0.1, no -40 offset
        assertEquals(-10.0, UasDecoder.scale(0x96, (0xFFFF & -100)), EPS);
        assertEquals(25.0, UasDecoder.scale(0x96, 250), EPS);
    }

    @Test
    public void signed_positiveValues_matchUnsignedScaling() {
        // Positive raw values below 0x8000 must scale identically to unsigned
        assertEquals(UasDecoder.scale(0x07, 1000), UasDecoder.scale(0x87, 1000), EPS);
    }

    // ── Unknown UASID fallback ────────────────────────────────────────

    @Test
    public void unknownUasId_returnsRaw() {
        assertEquals(4242.0, UasDecoder.scale(0x7F, 4242), EPS);
    }

    @Test
    public void unknownSignedUasId_returnsSignedRaw() {
        assertEquals(-1.0, UasDecoder.scale(0xFF, 0xFFFF), EPS);
    }

    // ── Units ─────────────────────────────────────────────────────────

    @Test
    public void unitFor_coversRepresentativeIds() {
        assertEquals("rpm", UasDecoder.unitFor(0x07));
        assertEquals("°C", UasDecoder.unitFor(0x16));
        assertEquals("mV", UasDecoder.unitFor(0x0A));
        assertEquals("ratio", UasDecoder.unitFor(0x1E));
        assertEquals("%", UasDecoder.unitFor(0x30));
    }

    @Test
    public void unitFor_signedSharesUnsignedUnit() {
        assertEquals(UasDecoder.unitFor(0x16), UasDecoder.unitFor(0x96));
        assertEquals(UasDecoder.unitFor(0x07), UasDecoder.unitFor(0x87));
    }

    @Test
    public void unitFor_unknownAndBoolean_areEmpty() {
        assertEquals("", UasDecoder.unitFor(0x7F));
        assertEquals("", UasDecoder.unitFor(0x2E)); // true/false has no unit
    }
}
