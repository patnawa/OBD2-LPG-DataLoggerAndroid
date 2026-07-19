package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;

public class Mode09ReaderTest {

    @Test
    public void testParseCalIdsWithoutHeaders() {
        // Mode 09 Info Type 04 response with headers off, single line
        // 49 04 [count=01] [Cal-ID in ASCII: "39036-S1B-AE2" padded with 0]
        // "39036-S1B-AE2" = 33 39 30 33 36 2D 53 31 42 2D 41 45 32
        // Padding: 00 00 00 (making it 16 bytes = 32 hex chars)
        String response = "49 04 01 33 39 30 33 36 2D 53 31 42 2D 41 45 32 00 00 00";
        List<Mode09Reader.CalIdEntry> entries = Mode09Reader.parseCalIds(response);
        assertEquals(1, entries.size());
        assertEquals("39036-S1B-AE2", entries.get(0).getCalId());
    }

    @Test
    public void testParseCalIdsMultiFrameWithFrameIndexes() {
        // Multi-frame response without headers (Format C/D): prefixed with "0:", "1:", "2:"
        String response = "0: 49 04 01 33 39 30\r\n1: 33 36 2D 53 31 42 2D 41\r\n2: 45 32 00 00 00\r\n";
        List<Mode09Reader.CalIdEntry> entries = Mode09Reader.parseCalIds(response);
        assertEquals(1, entries.size());
        assertEquals("39036-S1B-AE2", entries.get(0).getCalId());
    }

    @Test
    public void testParseCalIdsWithCANHeaders() {
        // Multi-frame response with CAN headers and PCI descriptors (Format A)
        String response = "7E8 10 14 49 04 01 33 39 30\r\n7E8 21 33 36 2D 53 31 42 2D 41\r\n7E8 22 45 32 00 00 00\r\n";
        List<Mode09Reader.CalIdEntry> entries = Mode09Reader.parseCalIds(response);
        assertEquals(1, entries.size());
        assertEquals("39036-S1B-AE2", entries.get(0).getCalId());
    }

    @Test
    public void testParseCvnsWithoutHeaders() {
        // Mode 09 Info Type 06 response: 49 06 [count=01] [CVN: 4 bytes = 8 hex chars]
        String response = "49 06 01 AA BB CC DD";
        List<Mode09Reader.CvnEntry> entries = Mode09Reader.parseCvns(response);
        assertEquals(1, entries.size());
        assertEquals("AABBCCDD", entries.get(0).getCvn());
    }

    @Test
    public void testParseCvnsMultiFrameWithFrameIndexes() {
        String response = "0: 49 06 01 AA BB\r\n1: CC DD\r\n";
        List<Mode09Reader.CvnEntry> entries = Mode09Reader.parseCvns(response);
        assertEquals(1, entries.size());
        assertEquals("AABBCCDD", entries.get(0).getCvn());
    }

    @Test
    public void testParseCvnsWithCANHeaders() {
        String response = "7E8 10 08 49 06 01 AA BB CC\r\n7E8 21 DD 00 00 00 00 00 00\r\n";
        List<Mode09Reader.CvnEntry> entries = Mode09Reader.parseCvns(response);
        assertEquals(1, entries.size());
        assertEquals("AABBCCDD", entries.get(0).getCvn());
    }

    @Test
    public void parsesInterleavedCalibrationIdsFromMultipleEcus() {
        String response =
                "7E8 10 13 49 04 01 45 43 4D\r\n" +
                "7E9 10 13 49 04 01 54 43 4D\r\n" +
                "7E8 21 2D 41 00 00 00 00 00\r\n" +
                "7E9 21 2D 42 00 00 00 00 00\r\n" +
                "7E8 22 00 00 00 00 00 00 00\r\n" +
                "7E9 22 00 00 00 00 00 00 00\r\n";

        List<Mode09Reader.CalIdEntry> entries = Mode09Reader.parseCalIds(response);

        assertEquals(2, entries.size());
        assertEquals("ECM-A", entries.get(0).getCalId());
        assertEquals(0, entries.get(0).getEcuIndex());
        assertEquals("TCM-B", entries.get(1).getCalId());
        assertEquals(1, entries.get(1).getEcuIndex());
    }

    @Test
    public void parsesCvnsFromMultipleEcusIndependently() {
        String response = "7E8 07 49 06 01 AA BB CC DD\r\n"
                + "7E9 07 49 06 01 11 22 33 44\r\n";

        List<Mode09Reader.CvnEntry> entries = Mode09Reader.parseCvns(response);

        assertEquals(2, entries.size());
        assertEquals("AABBCCDD", entries.get(0).getCvn());
        assertEquals(0, entries.get(0).getEcuIndex());
        assertEquals("11223344", entries.get(1).getCvn());
        assertEquals(1, entries.get(1).getEcuIndex());
    }

    @Test
    public void parsesSupportedMode09InfoTypes() {
        // D0 = types 01, 02 and 04; the following bytes are all clear.
        assertEquals(Arrays.asList(1, 2, 4),
                Mode09Reader.parseSupportedInfoTypes("49 00 D0 00 00 00"));
    }

    @Test
    public void unionsSupportedMode09InfoTypesAcrossEcus() {
        String response = "7E8 06 49 00 D0 00 00 00\r\n"
                + "7E9 06 49 00 08 00 00 00\r\n";

        assertEquals(Arrays.asList(1, 2, 4, 5),
                Mode09Reader.parseSupportedInfoTypes(response));
    }

    @Test
    public void parsesMode01EvidenceSinceClear() {
        assertEquals(5, Mode09Reader.parseMode01UnsignedValue("7E8 03 41 30 05", "4130", 1));
        assertEquals(300, Mode09Reader.parseMode01UnsignedValue("41 31 01 2C", "4131", 2));
        assertEquals(90, Mode09Reader.parseMode01UnsignedValue("41 4E 00 5A", "414E", 2));
    }
}
