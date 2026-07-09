package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;

public class VinReaderTest {

    @Test
    public void testParseStandardVin() {
        // Mode 09 PID 02 response with headers off, single line or merged
        String response = "49 02 01 31 46 4D 43 55 30 47 31 31 46 4B 30 30 30 30 30 31";
        String vin = VinReader.parseVinResponse(response);
        assertNotNull(vin);
        assertEquals("1FMCU0G11FK000001", vin);
    }

    @Test
    public void testParseMultiFrameVinWithHeaders() {
        // CAN multi-frame response with headers: e.g. 7E8 10 14 49 02 01 followed by consecutive frames
        String response = "7E8 10 14 49 02 01 31 46 4D\r7E8 21 43 55 30 47 31 31 46\r7E8 22 4B 30 30 30 30 30 31\r";
        String vin = VinReader.parseVinResponse(response);
        assertNotNull(vin);
        assertEquals("1FMCU0G11FK000001", vin);
    }

    @Test
    public void testParseMultiFrameVinWithoutHeaders() {
        // Multi-frame response without headers (Format C/D): prefixed with "0:", "1:", "2:"
        String response = "0: 49 02 01 31 46 4D 43 55 30\r\n1: 47 31 31 46 4B 30 30 30\r\n2: 30 30 31\r\n";
        String vin = VinReader.parseVinResponse(response);
        assertNotNull(vin);
        assertEquals("1FMCU0G11FK000001", vin);
    }
}
