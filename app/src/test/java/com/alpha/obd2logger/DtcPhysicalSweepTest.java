package com.alpha.obd2logger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Covers the decisions behind the physical address sweep.
 *
 * <p>The deep scan previously used functional addressing only, so a module was
 * found solely if it answered the broadcast — gatewayed body/ABS/SRS modules
 * usually don't. The ECU name table knows roughly 60 CAN IDs while only 6 were
 * ever queried.
 */
public class DtcPhysicalSweepTest {

    private static DtcReader.ProtocolBus bus(String atSp) {
        return new DtcReader.ProtocolBus("test", atSp, "test bus", false, null);
    }

    /** 11-bit CAN protocols accept an ATSH7Ex request header. */
    @Test
    public void elevenBitCanProtocolsAreSwept() {
        assertTrue(DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP0")));
        assertTrue(DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP6")));
        assertTrue(DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP8")));
        assertTrue("SW-CAN carries the body modules this sweep targets",
                DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSPA")));
        assertTrue("MS-CAN", DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSPB")));
        assertTrue("CH-CAN", DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSPC")));
        assertTrue("LS-CAN", DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSPD")));
    }

    /**
     * 29-bit and the K-line / VPW protocols address ECUs differently. Sweeping
     * them with 11-bit headers would waste a scan cycle per address and could
     * leave the adapter with a header that cannot apply to the active protocol.
     */
    @Test
    public void nonElevenBitProtocolsAreNotSwept() {
        assertFalse("29-bit uses 18DAxxF1 addressing",
                DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP7")));
        assertFalse("KWP2000", DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP5")));
        assertFalse("ISO 9141-2", DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP3")));
        assertFalse("J1850 VPW", DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP2")));
        assertFalse(DtcReader.supportsElevenBitPhysicalAddressing(null));
    }

    /**
     * Brand tables list request and response IDs together. Addressing a
     * response ID as a request reaches nothing, so it must not be swept.
     */
    @Test
    public void responseAddressesAreNotTreatedAsRequestAddresses() {
        Map<Integer, String> brandMap = new LinkedHashMap<>();
        brandMap.put(0x7E0, "ECM — Engine Control (Toyota)");
        brandMap.put(0x7E1, "TCM — Transmission (Toyota)");
        brandMap.put(0x7E8, "ECM Response");
        brandMap.put(0x7E9, "TCM Response");

        assertFalse("7E0 is a request address",
                DtcReader.isLikelyResponseAddress(0x7E0, brandMap));
        assertTrue("named as a response",
                DtcReader.isLikelyResponseAddress(0x7E8, brandMap));
        assertTrue("sits 8 above a listed request",
                DtcReader.isLikelyResponseAddress(0x7E9, brandMap));
    }

    /** Brand-specific request blocks must still be swept. */
    @Test
    public void brandSpecificRequestAddressesAreSwept() {
        Map<Integer, String> honda = new LinkedHashMap<>();
        honda.put(0x7C0, "PGM-FI — Fuel Injection (Honda)");
        honda.put(0x7C8, "PGM-FI Response");
        assertFalse(DtcReader.isLikelyResponseAddress(0x7C0, honda));
        assertTrue(DtcReader.isLikelyResponseAddress(0x7C8, honda));

        Map<Integer, String> mitsubishi = new LinkedHashMap<>();
        mitsubishi.put(0x762, "AWC/S-AWC — All Wheel Control (Mitsubishi)");
        mitsubishi.put(0x764, "ETACS — Body Control/BCM (Mitsubishi)");
        assertFalse("body modules are exactly what the sweep exists to reach",
                DtcReader.isLikelyResponseAddress(0x764, mitsubishi));
    }

    /**
     * A rich DTC response must not be misread as a broken adapter. The overflow
     * marker is classified as a transport fault, so the cap has to sit above
     * what a real multi-module Mode 03 produces.
     */
    @Test
    public void responseCapAccommodatesAMultiModuleDtcReply() {
        // 8 modules × ~12 DTCs each, spaced hex, plus headers.
        int worstCaseChars = 8 * 12 * 12 + 8 * 8;
        assertTrue("cap " + ElmResponseSanitizer.MAX_RESPONSE_CHARS
                        + " must exceed a realistic full scan (" + worstCaseChars + ")",
                ElmResponseSanitizer.MAX_RESPONSE_CHARS > worstCaseChars);
    }
}
