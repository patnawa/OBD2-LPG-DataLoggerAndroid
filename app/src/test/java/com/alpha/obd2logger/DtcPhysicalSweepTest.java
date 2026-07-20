package com.alpha.obd2logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    private static final DtcReader.DtcScanProgressListener NO_OP_LISTENER =
            new DtcReader.DtcScanProgressListener() {
                @Override public void onProtocolProbeStart(String l, String d, int i, int n) {}
                @Override public void onProtocolProbeResult(String l, boolean r, int n) {}
                @Override public void onModeScanStart(String l, String m) {}
                @Override public void onModuleDetected(String l, String id, String n,
                                                       int s, int p, int per) {}
                @Override public void onModeScanComplete(String l, String m, int n) {}
                @Override public void onScanComplete(int p, int r, int n) {}
            };

    private static DtcReader.ProtocolBus bus(String atSp) {
        return new DtcReader.ProtocolBus("test", atSp, "test bus", false, null);
    }

    /** 11-bit CAN protocols accept an ATSH7Ex request header. */
    @Test
    public void elevenBitCanProtocolsAreSwept() {
        assertTrue(DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP6")));
        assertTrue(DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP8")));
    }

    /**
     * 29-bit and the K-line / VPW protocols address ECUs differently. Sweeping
     * them with 11-bit headers would waste a scan cycle per address and could
     * leave the adapter with a header that cannot apply to the active protocol.
     */
    @Test
    public void nonElevenBitProtocolsAreNotSwept() {
        assertFalse("AUTO bit width is unknown until the live protocol is resolved",
                DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP0")));
        assertFalse("29-bit uses 18DAxxF1 addressing",
                DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP7")));
        assertFalse("KWP2000", DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP5")));
        assertFalse("ISO 9141-2", DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP3")));
        assertFalse("J1850 PWM is not a CAN transport",
                DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP1")));
        assertFalse("J1850 VPW", DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSP2")));
        assertFalse("J1939 is 29-bit and is not an alternate physical bus",
                DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSPA")));
        assertFalse("programmable USER1 is not proof of Ford pin routing",
                DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSPB")));
        assertFalse("programmable USER2 width is not a routed secondary bus",
                DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSPC")));
        assertFalse("standard ELM327 has no protocol D",
                DtcReader.supportsElevenBitPhysicalAddressing(bus("ATSPD")));
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

    @Test
    public void functionalDiscoveryDoesNotPersistAnUnprovenPhysicalPair() {
        // isValidResponse("STOPPED") is true because its letters contain two
        // hex characters. Adapter status text must still not prove a CAN pair.
        PhysicalSweepDriver driver = new PhysicalSweepDriver("STOPPED");
        Map<Integer, DtcReader.ModuleInfo.Builder> modules = new HashMap<>();
        DtcReader.ModuleInfo.Builder functional =
                new DtcReader.ModuleInfo.Builder(0x7E8, "test", false);
        functional.storedOk = true;
        modules.put(0x7E8, functional);

        runSweep(driver, modules);

        assertTrue("the functionally discovered ECU must still be probed directly",
                driver.commands.contains("ATSH7E0"));
        assertNull("adapter status text cannot prove the inferred 7E0/7E8 pair",
                modules.get(0x7E8).build().requestCanId);
        assertTrue("failed physical proof must preserve functional scan data",
                modules.get(0x7E8).build().storedOk);
    }

    @Test
    public void directResponsePersistsThePhysicallyProvenPair() {
        PhysicalSweepDriver driver = new PhysicalSweepDriver("7E8 02 43 00");
        Map<Integer, DtcReader.ModuleInfo.Builder> modules = new HashMap<>();
        modules.put(0x7E8, new DtcReader.ModuleInfo.Builder(0x7E8, "test", false));

        runSweep(driver, modules);

        assertEquals("7E0", modules.get(0x7E8).build().requestCanId);
    }

    @Test
    public void physicalProofRequiresPositiveMode03FromExpectedReceiver() {
        assertTrue(DtcReader.isVerifiedPhysicalMode03Response("7E8 02 43 00", 0x7E8));
        assertTrue("spaces-off clone output", DtcReader.isVerifiedPhysicalMode03Response(
                "7E8024300", 0x7E8));
        assertTrue("ATH1-ignoring clone under the active RX filter",
                DtcReader.isVerifiedPhysicalMode03Response("43 00", 0x7E8));
        assertTrue("all-numeric colon-formatted CAN header",
                DtcReader.isVerifiedPhysicalMode03Response("619: 02 43 00", 0x619));
        assertTrue("indexed response with a one-nibble DLC",
                DtcReader.isVerifiedPhysicalMode03Response(
                        "7E8 0: 8 02 43 00", 0x7E8));

        assertFalse("a different receiver cannot prove this pair",
                DtcReader.isVerifiedPhysicalMode03Response("7E9 02 43 00", 0x7E8));
        assertFalse("an all-numeric CAN header must not be stripped as a frame index",
                DtcReader.isVerifiedPhysicalMode03Response("620: 02 43 00", 0x619));
        assertFalse("a negative diagnostic response is not a positive Mode 03 reply",
                DtcReader.isVerifiedPhysicalMode03Response("7E8 03 7F 03 11", 0x7E8));
        assertFalse(DtcReader.isVerifiedPhysicalMode03Response("STOPPED", 0x7E8));
        assertFalse(DtcReader.isVerifiedPhysicalMode03Response("CAN ERROR", 0x7E8));
        assertFalse(DtcReader.isVerifiedPhysicalMode03Response("SEARCHING...", 0x7E8));
        assertFalse("command echo", DtcReader.isVerifiedPhysicalMode03Response("03", 0x7E8));
    }

    private static void runSweep(PhysicalSweepDriver driver,
                                 Map<Integer, DtcReader.ModuleInfo.Builder> modules) {
        DtcReader.setBrand(null);
        DtcReader.sweepPhysicalAddresses(driver, bus("ATSP6"), false, modules,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), NO_OP_LISTENER);
    }

    private static final class PhysicalSweepDriver extends BaseDriver {
        final List<String> commands = new ArrayList<>();
        final String targetResponse;
        String activeHeader;

        PhysicalSweepDriver(String targetResponse) {
            super(new LoggerConfig());
            this.targetResponse = targetResponse;
            connected = true;
        }

        @Override public boolean connect() { connected = true; return true; }
        @Override public void disconnect() { connected = false; }
        @Override public Double queryPid(PIDDefinition pidDef) { return null; }

        @Override public String sendCommandRaw(String command) {
            commands.add(command);
            if (command.startsWith("ATSH")) {
                activeHeader = command.substring(4);
                return "OK";
            }
            if (command.startsWith("AT")) return "OK";
            if ("03".equals(command) && targetResponse != null && "7E0".equals(activeHeader)) {
                return targetResponse;
            }
            return "NO DATA";
        }
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
