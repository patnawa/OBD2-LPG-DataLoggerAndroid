package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects standard, read-only OBD vehicle identity evidence. It uses only
 * Mode 09 information requests and does not open diagnostic sessions, write
 * ECU data, or perform actuator tests.
 */
public final class VehicleInformationReader {

    private VehicleInformationReader() {
    }

    public static Snapshot read(BaseDriver driver) {
        if (driver == null || !driver.isConnected()) return Snapshot.empty();

        List<Integer> infoTypes = Mode09Reader.readSupportedInfoTypes(driver);
        // The 0900 bitmap is only advisory for the VIN: some ECUs answer 0900
        // but omit info type 0x02 even though 0902 works, and others publish the
        // VIN over UDS regardless of what Mode 09 advertises. VinReader is cheap
        // when the vehicle genuinely has nothing to say, so always ask.
        String vin = VinReader.readVin(driver);
        List<Mode09Reader.CalIdEntry> calIds = supports(infoTypes, 0x04)
                ? Mode09Reader.readCalIds(driver) : Collections.emptyList();
        List<Mode09Reader.CvnEntry> cvns = supports(infoTypes, 0x06)
                ? Mode09Reader.readCvns(driver) : Collections.emptyList();
        return new Snapshot(vin, infoTypes, calIds, cvns);
    }

    private static boolean supports(List<Integer> infoTypes, int infoType) {
        return infoTypes != null && infoTypes.contains(infoType);
    }

    public static String infoTypeLabel(int infoType) {
        switch (infoType) {
            case 0x01: return "VIN count";
            case 0x02: return "VIN";
            case 0x03: return "Cal-ID count";
            case 0x04: return "Cal-ID";
            case 0x05: return "CVN count";
            case 0x06: return "CVN";
            case 0x09: return "ECU name count";
            case 0x0A: return "ECU name";
            case 0x0D: return "Engine serial number";
            default: return String.format(java.util.Locale.US, "Info Type %02X", infoType);
        }
    }

    public static final class Snapshot {
        private final String vin;
        private final List<Integer> supportedInfoTypes;
        private final List<Mode09Reader.CalIdEntry> calIds;
        private final List<Mode09Reader.CvnEntry> cvns;
        private final long capturedAtEpochMs;

        Snapshot(String vin, List<Integer> supportedInfoTypes,
                 List<Mode09Reader.CalIdEntry> calIds,
                 List<Mode09Reader.CvnEntry> cvns) {
            this(vin, supportedInfoTypes, calIds, cvns, System.currentTimeMillis());
        }

        Snapshot(String vin, List<Integer> supportedInfoTypes,
                 List<Mode09Reader.CalIdEntry> calIds,
                 List<Mode09Reader.CvnEntry> cvns, long capturedAtEpochMs) {
            this.vin = vin;
            this.supportedInfoTypes = copy(supportedInfoTypes);
            this.calIds = copy(calIds);
            this.cvns = copy(cvns);
            this.capturedAtEpochMs = Math.max(0L, capturedAtEpochMs);
        }

        static Snapshot empty() {
            return new Snapshot(null, Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), 0L);
        }

        public String getVin() { return vin; }
        public List<Integer> getSupportedInfoTypes() { return supportedInfoTypes; }
        public List<Mode09Reader.CalIdEntry> getCalIds() { return calIds; }
        public List<Mode09Reader.CvnEntry> getCvns() { return cvns; }
        public long getCapturedAtEpochMs() { return capturedAtEpochMs; }

        public boolean hasCapabilityBitmap() { return !supportedInfoTypes.isEmpty(); }

        public String getBrandLabel() {
            if (vin == null) return "Unknown";
            VinBrandDetector.Brand brand = VinBrandDetector.detect(vin);
            return brand == null ? "Unknown" : brand.name();
        }

        public int getModelYear() {
            return vin == null ? 0 : BrandYearProfile.yearFromVin(vin);
        }

        private static <T> List<T> copy(List<T> source) {
            return source == null || source.isEmpty() ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(source));
        }
    }
}
