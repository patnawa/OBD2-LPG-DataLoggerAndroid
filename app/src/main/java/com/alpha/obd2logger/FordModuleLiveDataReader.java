package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Read-only, physically addressed OEM module snapshot.
 *
 * <p>This reader intentionally uses only SAE J1979 Mode 01 values from the
 * PCM/ECM and Mode 03 to check whether powertrain, transmission, and brake
 * modules answer directly. Manufacturer transmission and brake live-data identifiers vary by platform and model
 * year, so this class never guesses a UDS DID, starts a diagnostic session,
 * requests security access, or writes to an ECU.</p>
 */
public final class FordModuleLiveDataReader {
    private static final ModuleProfile FORD = new ModuleProfile(
            new ModuleTarget("PCM — Powertrain", "7E0", "7E8"),
            new ModuleTarget("TCM — Transmission", "7E1", "7E9"),
            new ModuleTarget("ABS — Brakes", "7E2", "7EA"));
    private static final ModuleProfile TOYOTA = new ModuleProfile(
            new ModuleTarget("ECM — Engine", "7E0", "7E8"),
            new ModuleTarget("TCM — Transmission", "7E1", "7E9"),
            new ModuleTarget("ABS/VSC — Brakes", "7E2", "7EA"));
    private static final ModuleProfile MAZDA = new ModuleProfile(
            new ModuleTarget("PCM — Powertrain", "7E0", "7E8"),
            new ModuleTarget("TCM — Transmission", "7E1", "7E9"), null);
    private static final ModuleProfile NISSAN = new ModuleProfile(
            new ModuleTarget("ECM — Engine", "7E0", "7E8"),
            new ModuleTarget("TCM — CVT/Auto", "7E1", "7E9"), null);
    // The existing project profile confirms this diesel engine request/response pair.
    // Do not infer TCM or ABS physical addresses for other Mitsubishi platforms.
    private static final ModuleProfile HONDA = new ModuleProfile(
            new ModuleTarget("PGM-FI — Engine", "7C0", "7C8"), null, null);
    private static final ModuleProfile CHEVROLET = new ModuleProfile(
            new ModuleTarget("ECM — Engine", "7E0", "7E8"),
            new ModuleTarget("TCM — Transmission", "7E1", "7E9"),
            new ModuleTarget("EBCM — Brakes", "7E2", "7EA"));

    private static final PIDDefinition GENERIC_COOLANT = new PIDDefinition(
            "Engine coolant temperature", "01", "05", "°C", "A-40", -40, 215, false, 1);
    private static final PIDDefinition GENERIC_IAT = new PIDDefinition(
            "Intake air temperature", "01", "0F", "°C", "A-40", -40, 215, false, 1);
    private static final PIDDefinition GENERIC_OIL_TEMP = new PIDDefinition(
            "Engine oil temperature", "01", "5C", "°C", "A-40", -40, 215, false, 1);
    private static final PIDDefinition GENERIC_RPM = new PIDDefinition(
            "Engine speed", "01", "0C", "rpm", "(A*256+B)/4", 0, 16383.75, false, 2);
    private static final PIDDefinition GENERIC_SPEED = new PIDDefinition(
            "Vehicle speed", "01", "0D", "km/h", "A", 0, 255, false, 1);

    private FordModuleLiveDataReader() {
    }

    private static final class ModuleTarget {
        final String name;
        final String requestId;
        final String responseId;

        ModuleTarget(String name, String requestId, String responseId) {
            this.name = name;
            this.requestId = requestId;
            this.responseId = responseId;
        }
    }

    private static final class ModuleProfile {
        final ModuleTarget powertrain;
        final ModuleTarget transmission;
        final ModuleTarget brakes;

        ModuleProfile(ModuleTarget powertrain, ModuleTarget transmission, ModuleTarget brakes) {
            this.powertrain = powertrain;
            this.transmission = transmission;
            this.brakes = brakes;
        }
    }

    public static final class Metric {
        private final String label;
        private final String unit;
        private final Double value;

        Metric(String label, String unit, Double value) {
            this.label = label;
            this.unit = unit;
            this.value = value;
        }

        public String getLabel() { return label; }
        public String getUnit() { return unit; }
        public Double getValue() { return value; }
        public boolean isAvailable() { return value != null; }

        public String getDisplayValue() {
            if (value == null) return "Not reported";
            if ("rpm".equals(unit) || "km/h".equals(unit)) {
                return String.format(Locale.US, "%.0f %s", value, unit);
            }
            return String.format(Locale.US, "%.1f %s", value, unit);
        }
    }

    public static final class ModuleStatus {
        private final String name;
        private final String requestId;
        private final String responseId;
        private final boolean responded;
        private final int storedDtcCount;
        private final List<Metric> metrics;
        private final String statusText;

        ModuleStatus(String name, String requestId, String responseId, boolean responded,
                     int storedDtcCount, List<Metric> metrics) {
            this(name, requestId, responseId, responded, storedDtcCount, metrics, null);
        }

        ModuleStatus(String name, String requestId, String responseId, boolean responded,
                     int storedDtcCount, List<Metric> metrics, String statusText) {
            this.name = name;
            this.requestId = requestId;
            this.responseId = responseId;
            this.responded = responded;
            this.storedDtcCount = storedDtcCount;
            this.metrics = Collections.unmodifiableList(new ArrayList<>(metrics));
            this.statusText = statusText;
        }

        public String getName() { return name; }
        public String getRequestId() { return requestId; }
        public String getResponseId() { return responseId; }
        public boolean responded() { return responded; }
        public int getStoredDtcCount() { return storedDtcCount; }
        public List<Metric> getMetrics() { return metrics; }

        public String getStatusText() {
            if (statusText != null) return statusText;
            if (!responded) return "No direct response to read-only Mode 03";
            return storedDtcCount == 0
                    ? "Responded; no stored DTC reported"
                    : "Responded; " + storedDtcCount + " stored DTC(s) reported";
        }
    }

    public static final class Snapshot {
        private final List<ModuleStatus> modules;

        Snapshot(List<ModuleStatus> modules) {
            this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
        }

        public List<ModuleStatus> getModules() { return modules; }

        public ModuleStatus moduleNamed(String name) {
            for (ModuleStatus module : modules) {
                if (module.getName().equals(name)) return module;
            }
            return null;
        }
    }

    /** Reads Ford PCM standard values and direct, read-only DTC status from PCM/TCM/ABS. */
    public static Snapshot read(BaseDriver driver) {
        return read(driver, VinBrandDetector.Brand.FORD);
    }

    /** Every vehicle can use the standard OBD fallback; direct layouts are a smaller verified subset. */
    public static boolean supports(VinBrandDetector.Brand brand) {
        return true;
    }

    /** Returns whether the brand has a verified direct physical-address module layout. */
    public static boolean hasDirectModuleProfile(VinBrandDetector.Brand brand) {
        return profileFor(brand) != null;
    }

    /** Reads standard engine values and direct, read-only DTC status for a supported brand. */
    public static Snapshot read(BaseDriver driver, VinBrandDetector.Brand brand) {
        if (driver == null || !driver.isConnected()) {
            return new Snapshot(Collections.emptyList());
        }
        ModuleProfile profile = profileFor(brand);
        if (profile == null) return readGenericObd(driver);

        configureForPhysicalRead(driver);
        try {
            List<ModuleStatus> modules = new ArrayList<>();
            modules.add(readPowertrain(driver, profile.powertrain));
            if (profile.transmission != null) {
                modules.add(readDtcStatus(driver, profile.transmission));
            }
            if (profile.brakes != null) {
                modules.add(readDtcStatus(driver, profile.brakes));
            }
            return new Snapshot(modules);
        } finally {
            restorePollingState(driver);
        }
    }

    private static ModuleProfile profileFor(VinBrandDetector.Brand brand) {
        if (brand == null) return null;
        switch (brand) {
            case FORD: return FORD;
            case TOYOTA: return TOYOTA;
            case MAZDA: return MAZDA;
            case NISSAN: return NISSAN;
            // 611/619 is confirmed only for specific Mitsubishi diesel platforms.
            // Brand detection alone cannot distinguish those vehicles from petrol
            // models, so use the standard functional fallback instead of guessing.
            case MITSUBISHI: return null;
            case HONDA: return HONDA;
            case CHEVROLET: return CHEVROLET;
            default: return null;
        }
    }

    /**
     * Fallback for brands/models without a confirmed physical module layout.
     * BaseDriver already knows the active transport, so this works for standard
     * CAN, K-line, and J1850 OBD without forcing a CAN request header.
     */
    private static Snapshot readGenericObd(BaseDriver driver) {
        List<Metric> metrics = Arrays.asList(
                new Metric("Engine coolant temperature", "°C", queryStandardPid(driver, GENERIC_COOLANT)),
                new Metric("Intake air temperature", "°C", queryStandardPid(driver, GENERIC_IAT)),
                new Metric("Engine oil temperature", "°C", queryStandardPid(driver, GENERIC_OIL_TEMP)),
                new Metric("Engine speed", "rpm", queryStandardPid(driver, GENERIC_RPM)),
                new Metric("Vehicle speed", "km/h", queryStandardPid(driver, GENERIC_SPEED))
        );
        boolean anyMetric = false;
        for (Metric metric : metrics) {
            if (metric.isAvailable()) {
                anyMetric = true;
                break;
            }
        }
        String status = anyMetric
                ? "Standard OBD-II powertrain data reported"
                : "No standard OBD-II powertrain data reported";
        ModuleStatus module = new ModuleStatus("OBD-II Powertrain", "functional", "",
                anyMetric, 0, metrics, status);
        return new Snapshot(Collections.singletonList(module));
    }

    private static Double queryStandardPid(BaseDriver driver, PIDDefinition pid) {
        try {
            return driver.queryPid(pid);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ModuleStatus readPowertrain(BaseDriver driver, ModuleTarget target) {
        String dtcRaw = queryPhysical(driver, target, "03");
        boolean dtcResponded = hasServiceResponse(dtcRaw, target.responseId, "43");
        int storedDtcs = dtcResponded ? dtcCountForExpectedModule(dtcRaw, target) : 0;

        List<Metric> metrics = Arrays.asList(
                new Metric("Engine coolant temperature", "°C",
                        decodeTemperature(queryPhysical(driver, target, "0105"), "05")),
                new Metric("Intake air temperature", "°C",
                        decodeTemperature(queryPhysical(driver, target, "010F"), "0F")),
                new Metric("Engine oil temperature", "°C",
                        decodeTemperature(queryPhysical(driver, target, "015C"), "5C")),
                new Metric("Engine speed", "rpm",
                        decodeRpm(queryPhysical(driver, target, "010C"))),
                new Metric("Vehicle speed", "km/h",
                        decodeSingleByte(queryPhysical(driver, target, "010D"), "0D"))
        );
        boolean anyMetric = false;
        for (Metric metric : metrics) {
            if (metric.isAvailable()) {
                anyMetric = true;
                break;
            }
        }
        return new ModuleStatus(target.name, target.requestId, target.responseId,
                dtcResponded || anyMetric, storedDtcs, metrics);
    }

    private static ModuleStatus readDtcStatus(BaseDriver driver, ModuleTarget target) {
        String raw = queryPhysical(driver, target, "03");
        boolean responded = hasServiceResponse(raw, target.responseId, "43");
        int storedDtcs = responded ? dtcCountForExpectedModule(raw, target) : 0;
        return new ModuleStatus(target.name, target.requestId, target.responseId, responded, storedDtcs,
                Collections.emptyList());
    }

    private static void configureForPhysicalRead(BaseDriver driver) {
        driver.sendCommandRaw("ATSP0");
        driver.sendCommandRaw("ATCFC1");
        driver.sendCommandRaw("ATH1");
        driver.sendCommandRaw("ATS1");
        // Up to 400 ms is enough for a small, direct read without slowing polling indefinitely.
        driver.sendCommandRaw("ATST64");
        // Establish the auto-selected protocol with functional 11-bit addressing
        // before applying a physical request/filter pair.
        driver.sendCommandRaw("ATCRA");
        driver.sendCommandRaw("ATSH7DF");
        driver.sendCommandRaw("0100");
    }

    private static String queryPhysical(BaseDriver driver, ModuleTarget target, String command) {
        if (!PhysicalAddressing.applyTarget(driver, target.requestId, target.responseId)) {
            return "";
        }
        String raw = driver.sendCommandRaw(command);
        return framesFromExpectedModule(raw, target.responseId);
    }

    private static void restorePollingState(BaseDriver driver) {
        if (driver instanceof ElmDriver) {
            ((ElmDriver) driver).restorePollingState();
            return;
        }
        driver.sendCommandRaw("ATCRA");
        driver.sendCommandRaw("ATAR");
        driver.sendCommandRaw("ATCFC1");
        driver.sendCommandRaw("ATH0");
        driver.sendCommandRaw("ATS0");
        driver.sendCommandRaw("ATSP0");
        driver.sendCommandRaw("0100");
    }

    /**
     * Keep only frames whose CAN ID matches the configured response half of the
     * request/response pair. ATH1 is mandatory for this direct reader; if an
     * adapter ignores it, returning no data is safer than attributing another
     * module's reply to the selected ECU.
     */
    private static String framesFromExpectedModule(String raw, String responseId) {
        if (raw == null || raw.trim().isEmpty() || responseId == null) return "";
        String expected = responseId.toUpperCase(Locale.US);
        StringBuilder matched = new StringBuilder();
        String normalized = raw.replace('\r', '\n');
        for (String sourceLine : normalized.split("\\n")) {
            String line = sourceLine.trim().toUpperCase(Locale.US);
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon >= 0) line = line.substring(colon + 1).trim();
            String compact = line.replaceAll("[^0-9A-F]", "");
            if (!compact.startsWith(expected)) continue;
            if (matched.length() > 0) matched.append('\n');
            matched.append(line);
        }
        return matched.toString();
    }

    private static int dtcCountForExpectedModule(String raw, ModuleTarget target) {
        DtcReader.ScanLineResult parsed = DtcReader.parseWithModuleHeaders(
                raw, "43", "Module live data");
        try {
            int expectedId = Integer.parseInt(target.responseId, 16);
            List<DtcCode> codes = parsed.perModule.get(expectedId);
            return codes == null ? 0 : codes.size();
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Double decodeTemperature(String raw, String pid) {
        Double value = decodeSingleByte(raw, pid);
        return value == null ? null : value - 40.0;
    }

    private static Double decodeRpm(String raw) {
        int[] bytes = responseBytes(raw, "0C", 2);
        return bytes == null ? null : ((bytes[0] * 256.0) + bytes[1]) / 4.0;
    }

    private static Double decodeSingleByte(String raw, String pid) {
        int[] bytes = responseBytes(raw, pid, 1);
        return bytes == null ? null : (double) bytes[0];
    }

    /** Extracts Mode 01 bytes from either a headers-on or headers-off ELM response. */
    private static int[] responseBytes(String raw, String pid, int requiredBytes) {
        if (raw == null || raw.isEmpty()) return null;
        String upper = raw.toUpperCase(Locale.US);
        String[] tokens = upper.replaceAll("[^0-9A-F]", " ").trim().split("\\s+");
        for (int i = 0; i + 2 + requiredBytes <= tokens.length; i++) {
            if (!"41".equals(tokens[i]) || !pid.equals(tokens[i + 1])) continue;
            int[] bytes = new int[requiredBytes];
            for (int b = 0; b < requiredBytes; b++) {
                if (!tokens[i + 2 + b].matches("[0-9A-F]{2}")) return null;
                bytes[b] = Integer.parseInt(tokens[i + 2 + b], 16);
            }
            return bytes;
        }

        // Some ELM clones ignore ATS1. Fall back to the compact frame form.
        String compact = upper.replaceAll("[^0-9A-F]", "");
        String marker = "41" + pid;
        int start = compact.indexOf(marker);
        if (start < 0) return null;
        int dataStart = start + marker.length();
        if (compact.length() < dataStart + (requiredBytes * 2)) return null;
        int[] bytes = new int[requiredBytes];
        try {
            for (int b = 0; b < requiredBytes; b++) {
                bytes[b] = Integer.parseInt(compact.substring(dataStart + b * 2,
                        dataStart + b * 2 + 2), 16);
            }
            return bytes;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean hasServiceResponse(String raw, String responseId, String service) {
        if (raw == null || raw.isEmpty() || responseId == null || service == null) return false;
        String expected = responseId.toUpperCase(Locale.US);
        for (String line : raw.toUpperCase(Locale.US).replace('\r', '\n').split("\\n")) {
            String frame = line.replaceAll("[^0-9A-F]", "");
            if (!frame.startsWith(expected)) continue;
            String payload = frame.substring(expected.length());
            if (payload.startsWith(service)) return true;
            if (payload.length() >= 2 && payload.charAt(0) == '0'
                    && payload.substring(2).startsWith(service)) return true;
            if (payload.length() >= 4 && payload.charAt(0) == '1'
                    && payload.substring(4).startsWith(service)) return true;
        }
        return false;
    }
}
