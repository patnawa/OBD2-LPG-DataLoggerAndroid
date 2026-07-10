package com.alpha.obd2logger;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class DtcReaderTest {

    @Test
    public void testDtcParsingWithoutHeaders() {
        // Mock a driver that responds to DTC requests
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
                // Protocol probe — must return a valid response so scanSingleBus
                // proceeds to the DTC scan. "41" = Mode 01 response prefix.
                if ("0100".equals(command)) {
                    return "41 04 00 00 00 00"; // PIDs 01-20 supported
                }
                // Mode 03 — Stored DTCs
                if ("03".equals(command)) {
                    // Standard response: mode 43, 2 DTCs: P0171 (01 71) and P0300 (03 00)
                    return "43 02 01 71 03 00";
                }
                return "";
            }
        };
        mockDriver.connect();

        // Perform fast scan
        DtcReader.DtcScanResult result = DtcReader.readAllDtcs(mockDriver, false);

        // Verify that the parser extracted P0171 and P0300
        List<DtcCode> stored = result.storedDtcs;
        assertEquals("Should extract exactly 2 stored DTCs", 2, stored.size());
        assertEquals("First DTC should be P0171", "P0171", stored.get(0).getCode());
        assertEquals("Second DTC should be P0300", "P0300", stored.get(1).getCode());
    }
}
