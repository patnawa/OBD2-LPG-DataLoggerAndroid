package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class FreezeFrameReaderTest {

    @Test
    public void testParseFreezeFrameDtcMapping() {
        // Mock a driver that responds to Mode 03 and Mode 02 PID 02
        BaseDriver mockDriver = new BaseDriver(new LoggerConfig()) {
            @Override
            public boolean connect() {
                connected = true;
                return true;
            }

            @Override
            public void disconnect() {
                connected = false;
            }

            @Override
            public Double queryPid(PIDDefinition pidDef) {
                return null;
            }

            @Override
            public String sendCommandRaw(String command) {
                if ("03".equals(command)) {
                    // Stored DTC: P0171 (01 71)
                    return "43 01 01 71 00 00";
                }
                // SAE J1979 Mode 02 responses echo the frame number after
                // the PID: 42 [PID] [frame] [data...]
                if ("020200".equals(command)) {
                    // Mode 02 PID 02 for frame 00: P0171
                    return "42 02 00 01 71";
                }
                if (command.startsWith("020C")) { // Engine RPM in frame 00
                    return "42 0C 00 1A F8"; // 1726 RPM
                }
                if (command.startsWith("020D")) { // Speed in frame 00
                    return "42 0D 00 32"; // 50 km/h
                }
                return "";
            }
        };
        mockDriver.connect();

        List<FreezeFrameReader.FreezeFrameEntry> entries = FreezeFrameReader.readAllFreezeFrames(mockDriver);
        assertEquals(1, entries.size());
        
        FreezeFrameReader.FreezeFrameEntry entry = entries.get(0);
        assertEquals("P0171", entry.getDtcCode());
        assertEquals(0, entry.getFrameNumber());
        
        FreezeFrameData data = entry.getData();
        assertNotNull(data);
        assertEquals(Double.valueOf(1726.0), data.getValues().get("0C"));
        assertEquals(Double.valueOf(50.0), data.getValues().get("0D"));
    }
}
