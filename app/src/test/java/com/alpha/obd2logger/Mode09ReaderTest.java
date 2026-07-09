package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class Mode09ReaderTest {

    @Test
    public void testParseCalIdsWithoutHeaders() {
        // Mode 09 PID 02 response with headers off, single line
        // 49 02 [count=01] [Cal-ID in ASCII: "39036-S1B-AE2" padded with 0]
        // "39036-S1B-AE2" = 33 39 30 33 36 2D 53 31 42 2D 41 45 32
        // Padding: 00 00 00 (making it 16 bytes = 32 hex chars)
        String response = "49 02 01 33 39 30 33 36 2D 53 31 42 2D 41 45 32 00 00 00";
        List<Mode09Reader.CalIdEntry> entries = Mode09Reader.parseCalIds(response);
        assertEquals(1, entries.size());
        assertEquals("39036-S1B-AE2", entries.get(0).getCalId());
    }

    @Test
    public void testParseCalIdsMultiFrameWithFrameIndexes() {
        // Multi-frame response without headers (Format C/D): prefixed with "0:", "1:", "2:"
        String response = "0: 49 02 01 33 39 30\r\n1: 33 36 2D 53 31 42 2D 41\r\n2: 45 32 00 00 00\r\n";
        List<Mode09Reader.CalIdEntry> entries = Mode09Reader.parseCalIds(response);
        assertEquals(1, entries.size());
        assertEquals("39036-S1B-AE2", entries.get(0).getCalId());
    }

    @Test
    public void testParseCalIdsWithCANHeaders() {
        // Multi-frame response with CAN headers and PCI descriptors (Format A)
        String response = "7E8 10 14 49 02 01 33 39 30\r\n7E8 21 33 36 2D 53 31 42 2D 41\r\n7E8 22 45 32 00 00 00\r\n";
        List<Mode09Reader.CalIdEntry> entries = Mode09Reader.parseCalIds(response);
        assertEquals(1, entries.size());
        assertEquals("39036-S1B-AE2", entries.get(0).getCalId());
    }

    @Test
    public void testParseCvnsWithoutHeaders() {
        // Mode 09 PID 04 response: 49 04 [count=01] [CVN: 4 bytes = 8 hex chars, e.g. AABBCCDD]
        String response = "49 04 01 AA BB CC DD";
        List<Mode09Reader.CvnEntry> entries = Mode09Reader.parseCvns(response);
        assertEquals(1, entries.size());
        assertEquals("AABBCCDD", entries.get(0).getCvn());
    }

    @Test
    public void testParseCvnsMultiFrameWithFrameIndexes() {
        String response = "0: 49 04 01 AA BB\r\n1: CC DD\r\n";
        List<Mode09Reader.CvnEntry> entries = Mode09Reader.parseCvns(response);
        assertEquals(1, entries.size());
        assertEquals("AABBCCDD", entries.get(0).getCvn());
    }

    @Test
    public void testParseCvnsWithCANHeaders() {
        String response = "7E8 10 08 49 04 01 AA BB CC\r\n7E8 21 DD 00 00 00 00 00 00\r\n";
        List<Mode09Reader.CvnEntry> entries = Mode09Reader.parseCvns(response);
        assertEquals(1, entries.size());
        assertEquals("AABBCCDD", entries.get(0).getCvn());
    }
}
