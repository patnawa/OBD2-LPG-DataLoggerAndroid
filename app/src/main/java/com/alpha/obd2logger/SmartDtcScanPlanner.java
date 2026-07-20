package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure policy for choosing the protocol sequence of a DTC scan.
 *
 * <p>The first entry is always {@link #CURRENT_PROTOCOL_ID}; callers scan the
 * adapter's already-selected bus for that entry. A smart plan may add only the
 * nine legislated ELM/OBD-II protocols. Manufacturer buses and user protocols
 * are deliberately outside this planner.</p>
 */
public final class SmartDtcScanPlanner {

    public static final String CURRENT_PROTOCOL_ID = "CURRENT";

    public enum ScanMode {
        QUICK,
        SMART,
        FULL
    }

    private static final List<ObdProtocol> STANDARD_PROTOCOLS;

    static {
        List<ObdProtocol> standards = new ArrayList<>();
        standards.add(ObdProtocol.SAE_J1850_PWM);
        standards.add(ObdProtocol.SAE_J1850_VPW);
        standards.add(ObdProtocol.ISO_9141_2);
        standards.add(ObdProtocol.ISO_14230_4_KWP_5_BAUD);
        standards.add(ObdProtocol.ISO_14230_4_KWP_FAST);
        standards.add(ObdProtocol.ISO_15765_4_CAN_11BIT_500);
        standards.add(ObdProtocol.ISO_15765_4_CAN_29BIT_500);
        standards.add(ObdProtocol.ISO_15765_4_CAN_11BIT_250);
        standards.add(ObdProtocol.ISO_15765_4_CAN_29BIT_250);
        STANDARD_PROTOCOLS = Collections.unmodifiableList(standards);
    }

    private SmartDtcScanPlanner() {
    }

    /**
     * Runtime Smart-scan entry point backed only by schema-v2 evidence from a
     * strict Full scan. Legacy module-comparison snapshots intentionally do not
     * flow through this method.
     */
    public static Plan createPlanFromEvidence(
            ScanMode requestedMode,
            ObdProtocol activeProtocol,
            VehicleModuleProfileStore.SmartProtocolEvidence evidence,
            String verifiedVin,
            boolean smartScanEnabled,
            boolean adapterSupportsStandardProtocols) {
        ScanMode mode = requestedMode != null ? requestedMode : ScanMode.QUICK;
        ObdProtocol current = activeProtocol != null ? activeProtocol : ObdProtocol.AUTO;
        String normalizedVin = normalizeVerifiedVin(verifiedVin);
        boolean useFordLabels = normalizedVin != null
                && VinBrandDetector.detect(normalizedVin) == VinBrandDetector.Brand.FORD;

        List<String> ids = new ArrayList<>();
        List<ObdProtocol> protocols = new ArrayList<>();
        ids.add(CURRENT_PROTOCOL_ID);
        protocols.add(current);

        if (mode == ScanMode.FULL) {
            appendInStandardOrder(ids, protocols, STANDARD_PROTOCOLS, current);
        } else if (mode == ScanMode.SMART
                && smartScanEnabled
                && adapterSupportsStandardProtocols
                && evidenceMatchesVerifiedVin(evidence, normalizedVin)) {
            appendInStandardOrder(ids, protocols, evidence.getProtocols(), current);
        }

        return new Plan(mode, ids, protocols, useFordLabels);
    }

    private static void appendInStandardOrder(
            List<String> ids,
            List<ObdProtocol> protocols,
            Iterable<ObdProtocol> candidates,
            ObdProtocol activeProtocol) {
        Set<ObdProtocol> candidateSet = new LinkedHashSet<>();
        for (ObdProtocol candidate : candidates) {
            if (isStandardProtocol(candidate)) candidateSet.add(candidate);
        }
        for (ObdProtocol protocol : STANDARD_PROTOCOLS) {
            if (protocol != activeProtocol && candidateSet.contains(protocol)) {
                ids.add(protocol.getElmValue());
                protocols.add(protocol);
            }
        }
    }

    private static boolean evidenceMatchesVerifiedVin(
            VehicleModuleProfileStore.SmartProtocolEvidence evidence,
            String normalizedVerifiedVin) {
        if (evidence == null || normalizedVerifiedVin == null) return false;
        return normalizedVerifiedVin.equals(normalizeVerifiedVin(evidence.getVin()));
    }

    private static String normalizeVerifiedVin(String vin) {
        if (vin == null) return null;
        String normalized = vin.trim().toUpperCase(Locale.US);
        return VinBrandDetector.isStructurallyValid(normalized) ? normalized : null;
    }

    private static boolean isStandardProtocol(ObdProtocol protocol) {
        return protocol != null
                && protocol.getElmValue().length() == 1
                && protocol.getElmValue().charAt(0) >= '1'
                && protocol.getElmValue().charAt(0) <= '9';
    }

    /** Immutable, ordered result consumed by the scan executor. */
    public static final class Plan {
        private final ScanMode scanMode;
        private final List<String> protocolIds;
        private final List<ObdProtocol> protocols;
        private final boolean useFordLabels;

        private Plan(ScanMode scanMode, List<String> protocolIds,
                     List<ObdProtocol> protocols, boolean useFordLabels) {
            this.scanMode = scanMode;
            this.protocolIds = Collections.unmodifiableList(
                    new ArrayList<>(protocolIds));
            this.protocols = Collections.unmodifiableList(
                    new ArrayList<>(protocols));
            this.useFordLabels = useFordLabels;
        }

        public ScanMode getScanMode() {
            return scanMode;
        }

        /** CURRENT first, followed by zero or more canonical ELM IDs 1-9. */
        public List<String> getProtocolIds() {
            return protocolIds;
        }

        /**
         * Protocols in the same order as {@link #getProtocolIds()}.
         * The first entry is the detected active protocol, or AUTO when unknown.
         */
        public List<ObdProtocol> getProtocols() {
            return protocols;
        }

        public boolean usesFordLabels() {
            return useFordLabels;
        }
    }
}
