package com.alpha.obd2logger;

import com.alpha.obd2logger.can.UdsDataIdentifier;
import com.alpha.obd2logger.can.UdsResponseDecoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the standardized ISO 14229 ECU-identification block from one physical
 * CAN module.
 *
 * <p>The reader never guesses manufacturer DIDs and never exposes a general
 * command API. {@link ElmUdsTransport#requestIdentificationBatch} constructs
 * the complete request set from {@link UdsDataIdentifier#sweepSet()}, using
 * only ReadDataByIdentifier (service {@code 0x22}) in the ECU's default
 * diagnostic session.</p>
 */
public final class EcuIdentificationReader {

    public interface ProgressListener {
        void onProgress(int completed, int total);
    }

    /** How one DID read ended. */
    public enum Status {
        POSITIVE,
        NEGATIVE,
        RESPONSE_PENDING,
        MALFORMED
    }

    /** One physical ECU address pair. */
    public static final class Target {
        private final String name;
        private final String requestId;
        private final String responseId;
        private final String protocol;

        /** Package-visible for versioned store reconstruction. */
        Target(String name, String requestId, String responseId, String protocol) {
            String normalizedResponse = normalizeCanId(responseId);
            String derivedRequest = deriveRequestId(normalizedResponse);
            String normalizedRequest = normalizeCanId(requestId);
            if (derivedRequest == null || !derivedRequest.equals(normalizedRequest)) {
                throw new IllegalArgumentException("Invalid physical ECU address pair");
            }
            this.name = cleanLabel(name, "ECU " + normalizedResponse);
            this.requestId = normalizedRequest;
            this.responseId = normalizedResponse;
            this.protocol = cleanLabel(protocol, "");
        }

        /**
         * Build from a response whose physical request is standardized by its
         * address form: 7E8-7EF or fixed-normal ISO-TP 18DAF1xx.
         */
        public static Target fromResponseId(String responseId, String name, String protocol) {
            String normalized = normalizeCanId(responseId);
            if (normalized == null || !(normalized.matches("7E[8-F]")
                    || normalized.matches("18DAF1[0-9A-F]{2}"))) {
                return null;
            }
            String request = deriveRequestId(normalized);
            return request == null ? null : new Target(name, request, normalized, protocol);
        }

        public String getRequestId() { return requestId; }
        public String getResponseId() { return responseId; }
        public String getName() { return name; }
        public String getProtocol() { return protocol; }

        /** Stable across display-name changes. */
        public String getStableId() {
            return protocol.isEmpty() ? responseId : protocol + " / " + responseId;
        }

        public String getDisplayLabel() {
            String addresses = requestId + " -> " + responseId;
            return protocol.isEmpty()
                    ? name + " (" + addresses + ")"
                    : name + " (" + addresses + ", " + protocol + ")";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Target
                    && getStableId().equals(((Target) other).getStableId());
        }

        @Override
        public int hashCode() {
            return getStableId().hashCode();
        }
    }

    /** Parsed result for one standardized identifier. */
    public static final class Item {
        private final int identifier;
        private final String didHex;
        private final String label;
        private final String displayValue;
        private final String rawHex;
        private final Status status;
        private final int negativeResponseCode;
        private final String detail;

        /** Package-visible for versioned store reconstruction. */
        Item(int identifier, String label, String displayValue, String rawHex,
             Status status, int negativeResponseCode, String detail) {
            if (!UdsDataIdentifier.isStandardIdentificationDid(identifier)) {
                throw new IllegalArgumentException("Identifier is outside the safe sweep set");
            }
            this.identifier = identifier;
            this.didHex = UdsDataIdentifier.toHex(identifier);
            this.label = cleanLabel(label, UdsDataIdentifier.labelFor(identifier));
            this.displayValue = cleanLabel(displayValue, "No valid response");
            this.rawHex = normalizeRawHex(rawHex);
            this.status = status == null ? Status.MALFORMED : status;
            this.negativeResponseCode = negativeResponseCode;
            this.detail = cleanLabel(detail, "");
        }

        public int getIdentifier() { return identifier; }
        public String getDidHex() { return didHex; }
        public String getLabel() { return label; }
        public String getDisplayValue() { return displayValue; }
        public String getRawHex() { return rawHex; }
        public Status getStatus() { return status; }
        public int getNegativeResponseCode() { return negativeResponseCode; }
        public String getDetail() { return detail; }
        public boolean isPositive() { return status == Status.POSITIVE; }
        public boolean isNegative() {
            return status == Status.NEGATIVE || status == Status.RESPONSE_PENDING;
        }
        public boolean isMalformed() { return status == Status.MALFORMED; }
        public boolean isPending() { return status == Status.RESPONSE_PENDING; }
    }

    /** Immutable result of reading one ECU target. */
    public static final class Snapshot {
        private final Target target;
        private final List<Item> items;
        private final int positiveCount;
        private final int negativeCount;
        private final int malformedCount;
        private final long capturedAtEpochMs;

        /** Package-visible for versioned store reconstruction. */
        Snapshot(Target target, List<Item> items, long capturedAtEpochMs) {
            this.target = target;
            List<Item> safeItems = items == null
                    ? Collections.emptyList() : new ArrayList<>(items);
            this.items = Collections.unmodifiableList(safeItems);
            int positives = 0;
            int negatives = 0;
            int malformed = 0;
            for (Item item : safeItems) {
                if (item == null || item.isMalformed()) malformed++;
                else if (item.isPositive()) positives++;
                else if (item.isNegative()) negatives++;
                else malformed++;
            }
            this.positiveCount = positives;
            this.negativeCount = negatives;
            this.malformedCount = malformed;
            this.capturedAtEpochMs = Math.max(0L, capturedAtEpochMs);
        }

        public Target getTarget() { return target; }
        public List<Item> getItems() { return items; }
        public boolean isResponded() { return positiveCount + negativeCount > 0; }
        public int getPositiveCount() { return positiveCount; }
        public int getNegativeCount() { return negativeCount; }
        public int getMalformedCount() { return malformedCount; }
        public long getCapturedAtEpochMs() { return capturedAtEpochMs; }

        public Item itemFor(int identifier) {
            for (Item item : items) {
                if (item != null && item.getIdentifier() == identifier) return item;
            }
            return null;
        }
    }

    private EcuIdentificationReader() {
    }

    /** Convenience alias for callers that do not need the nested factory. */
    public static Target targetFromResponseId(
            String responseId, String name, String protocol) {
        return Target.fromResponseId(responseId, name, protocol);
    }

    /** Conservative engine/transmission defaults for an auto-selected bus. */
    public static List<Target> defaultTargets() {
        return defaultTargets(ObdProtocol.AUTO);
    }

    /**
     * Conservative defaults for the active ISO-TP CAN address width.
     * Observed deep-scan modules should be preferred because they can safely
     * supply additional vehicle-specific addresses.
     */
    public static List<Target> defaultTargets(ObdProtocol protocol) {
        ObdProtocol selected = protocol == null ? ObdProtocol.AUTO : protocol;
        String label = selected.getLabel();
        List<Target> targets = new ArrayList<>();
        if (selected.isTwentyNineBitCan()) {
            targets.add(new Target("Engine ECU", "18DA10F1", "18DAF110", label));
            targets.add(new Target("Transmission ECU", "18DA18F1", "18DAF118", label));
        } else if (selected == ObdProtocol.AUTO || selected.isElevenBitCan()) {
            targets.add(new Target("Engine ECU", "7E0", "7E8", label));
            targets.add(new Target("Transmission ECU", "7E1", "7E9", label));
        }
        return Collections.unmodifiableList(targets);
    }

    /** Convert addressable, observed deep-scan modules into read targets. */
    public static List<Target> targetsFromModules(List<DtcReader.ModuleInfo> modules) {
        if (modules == null || modules.isEmpty()) return Collections.emptyList();
        List<Target> targets = new ArrayList<>();
        for (DtcReader.ModuleInfo module : modules) {
            if (module == null) continue;
            String responseId = module.canId;
            if ((responseId == null || responseId.trim().isEmpty()) && module.ecuId > 0) {
                responseId = module.ecuId > 0x7FF
                        ? String.format(Locale.US, "%08X", module.ecuId)
                        : String.format(Locale.US, "%03X", module.ecuId);
            }
            Target target = targetFromKnownPair(
                    module.requestCanId, responseId,
                    module.moduleName, module.protocolLabel);
            if (target != null) targets.add(target);
        }
        return deduplicate(targets);
    }

    /** Convert a saved read-only module inventory into read targets. */
    public static List<Target> targetsFromProfile(VehicleModuleProfileStore.Snapshot profile) {
        if (profile == null || profile.getModules() == null) {
            return Collections.emptyList();
        }
        List<Target> targets = new ArrayList<>();
        for (VehicleModuleProfileStore.Module module : profile.getModules()) {
            if (module == null) continue;
            Target target = targetFromKnownPair(
                    module.getRequestCanId(), module.getCanId(),
                    module.getName(), module.getProtocol());
            if (target != null) targets.add(target);
        }
        return deduplicate(targets);
    }

    /** Read every standardized identification DID from one physical ECU. */
    public static Snapshot read(ElmDriver driver, Target target) {
        return read(driver, target, null);
    }

    /** Read every supported standard DID while reporting completed requests. */
    public static Snapshot read(
            ElmDriver driver, Target target, ProgressListener progressListener) {
        long capturedAt = System.currentTimeMillis();
        if (target == null) {
            return new Snapshot(null, Collections.emptyList(), capturedAt);
        }
        Map<Integer, ElmUdsTransport.Exchange> exchanges =
                ElmUdsTransport.requestIdentificationBatch(
                        driver, target.getRequestId(), target.getResponseId(),
                        ElmUdsTransport.DEFAULT_MAX_ATTEMPTS,
                        ElmUdsTransport.DEFAULT_PENDING_DELAY_MS,
                        progressListener == null ? null
                                : progressListener::onProgress);
        List<Item> items = new ArrayList<>();
        for (int identifier : UdsDataIdentifier.sweepSet()) {
            items.add(parseItem(identifier, exchanges.get(identifier)));
        }
        return new Snapshot(target, items, capturedAt);
    }

    static Item parseItem(int identifier, ElmUdsTransport.Exchange exchange) {
        String label = UdsDataIdentifier.labelFor(identifier);
        if (exchange == null || exchange.getResponse() == null) {
            return malformedItem(identifier, label, "Missing transport result");
        }

        UdsResponseDecoder.DecodedResponse response = exchange.getResponse();
        if (response.getKind() == UdsResponseDecoder.Kind.NEGATIVE_RESPONSE) {
            int nrc = response.getNegativeResponseCode();
            String raw = String.format(Locale.US, "7F%02X%02X",
                    Math.max(0, response.getRequestService()), Math.max(0, nrc));
            if (nrc == 0x78 || exchange.isPendingExhausted()) {
                return new Item(identifier, label, "Response pending timed out", raw,
                        Status.RESPONSE_PENDING, nrc, response.getDetail());
            }
            String nrcText = String.format(Locale.US, "Negative response NRC 0x%02X", nrc);
            if (response.getDetail() != null && !response.getDetail().trim().isEmpty()) {
                nrcText += " (" + response.getDetail().trim() + ")";
            }
            return new Item(identifier, label, nrcText, raw,
                    Status.NEGATIVE, nrc, response.getDetail());
        }

        if (response.getKind() != UdsResponseDecoder.Kind.POSITIVE_RESPONSE
                || response.getRequestService()
                != com.alpha.obd2logger.can.UdsRequest.SERVICE_READ_DATA_BY_IDENTIFIER) {
            return malformedItem(identifier, label, response.getDetail());
        }

        byte[] data = response.getData();
        if (data.length < 3) {
            return malformedItem(identifier, label, "Truncated DID response");
        }
        int echoedDid = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (echoedDid != identifier) {
            return malformedItem(identifier, label, "Response echoed a different DID");
        }

        byte[] value = java.util.Arrays.copyOfRange(data, 2, data.length);
        String rawHex = bytesToHex(value, false);
        return new Item(identifier, label, safeAsciiOrHex(value), rawHex,
                Status.POSITIVE, -1, response.getDetail());
    }

    static String safeAsciiOrHex(byte[] value) {
        if (value == null || value.length == 0) return "Empty value";

        // Identification strings are commonly space/NUL/FF padded. Trim only
        // for the ASCII candidate; if any byte is non-printable, show every
        // original byte as hex so binary data is never silently discarded.
        int start = 0;
        int end = value.length;
        while (start < end && isTextPadding(value[start] & 0xFF)) start++;
        while (end > start && isTextPadding(value[end - 1] & 0xFF)) end--;
        boolean printable = end > start;
        for (int i = start; i < end && printable; i++) {
            int b = value[i] & 0xFF;
            printable = b >= 0x20 && b <= 0x7E;
        }
        if (printable) {
            return new String(value, start, end - start, StandardCharsets.US_ASCII);
        }
        return "Hex: " + bytesToHex(value, true);
    }

    private static Item malformedItem(int identifier, String label, String detail) {
        return new Item(identifier, label, "No valid response", "",
                Status.MALFORMED, -1, detail);
    }

    /**
     * Prefer the TX/RX pair proven by a physical scan. Only the legislated
     * 7E8-7EF and ISO-TP 18DAF1xx response forms may be derived without one.
     */
    private static Target targetFromKnownPair(
            String requestId, String responseId, String name, String protocol) {
        String normalizedResponse = normalizeCanId(responseId);
        if (requestId != null && !requestId.trim().isEmpty()) {
            try {
                return new Target(name, requestId, normalizedResponse, protocol);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (normalizedResponse == null
                || !(normalizedResponse.matches("7E[8-F]")
                || normalizedResponse.matches("18DAF1[0-9A-F]{2}"))) {
            return null;
        }
        return Target.fromResponseId(normalizedResponse, name, protocol);
    }

    private static String deriveRequestId(String normalizedResponse) {
        if (normalizedResponse == null) return null;
        if (normalizedResponse.matches("[0-7][0-9A-F]{2}")) {
            int response = Integer.parseInt(normalizedResponse, 16);
            if (response < PhysicalAddressing.RESPONSE_OFFSET) return null;
            return String.format(Locale.US, "%03X",
                    response - PhysicalAddressing.RESPONSE_OFFSET);
        }
        if (normalizedResponse.matches("18DAF1[0-9A-F]{2}")) {
            return "18DA" + normalizedResponse.substring(6) + "F1";
        }
        return null;
    }

    private static String normalizeCanId(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.US);
        if (normalized.startsWith("0X")) normalized = normalized.substring(2);
        return normalized.matches("[0-9A-F]+") ? normalized : null;
    }

    private static List<Target> deduplicate(List<Target> targets) {
        LinkedHashMap<String, Target> unique = new LinkedHashMap<>();
        for (Target target : targets) {
            if (target != null && !unique.containsKey(target.getStableId())) {
                unique.put(target.getStableId(), target);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static boolean isTextPadding(int value) {
        return value == 0x00 || value == 0xFF || value == 0x20;
    }

    private static String bytesToHex(byte[] bytes, boolean spaced) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder out = new StringBuilder(bytes.length * (spaced ? 3 : 2));
        for (byte value : bytes) {
            if (spaced && out.length() > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", value & 0xFF));
        }
        return out.toString();
    }

    private static String normalizeRawHex(String rawHex) {
        if (rawHex == null) return "";
        String normalized = rawHex.toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        return normalized.length() % 2 == 0 ? normalized : "";
    }

    private static String cleanLabel(String value, String fallback) {
        String cleaned = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (cleaned.isEmpty()) cleaned = fallback == null ? "" : fallback.trim();
        return cleaned;
    }
}
