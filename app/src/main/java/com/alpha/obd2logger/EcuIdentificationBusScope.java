package com.alpha.obd2logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Restricts saved or observed ECU targets to the ISO-TP CAN bus that is
 * currently selected by the adapter.
 *
 * <p>A deep scan can discover modules after temporarily switching protocols
 * or physical CAN channels. ECU identification does not repeat those bus
 * switches, so a module is safe to offer only when its scan-bus label and
 * address width agree with the live protocol. Explicit scan-bus labels must
 * also agree with the live bit rate; the auto bus inherits the live rate.</p>
 */
public final class EcuIdentificationBusScope {

    private static final String PRIMARY_AUTO_BUS = "HS-CAN (auto)";
    private static final String CAN_29_BIT_500_BUS = "CAN 29-bit";
    private static final String CAN_11_BIT_250_BUS = "CAN 11-bit 250k";

    private EcuIdentificationBusScope() {
    }

    /**
     * Return an immutable snapshot of candidates addressable on {@code activeProtocol}.
     *
     * <p>The primary auto bus inherits the concrete protocol detected by
     * {@code ATDPN}. Explicit deep-scan buses must match their encoded width
     * and bit rate exactly. The primary auto bus must match the address width
     * and inherits the concrete live bit rate. All other labels are deliberately
     * excluded because the identification transport does not switch physical buses.</p>
     */
    public static List<EcuIdentificationReader.Target> filterForActiveProtocol(
            List<EcuIdentificationReader.Target> candidates,
            ObdProtocol activeProtocol) {
        if (candidates == null || candidates.isEmpty()
                || !isConcreteIsoTpCan(activeProtocol)) {
            return Collections.emptyList();
        }

        List<EcuIdentificationReader.Target> compatible = new ArrayList<>();
        for (EcuIdentificationReader.Target target : candidates) {
            if (target != null && isCompatible(target, activeProtocol)) {
                compatible.add(target);
            }
        }
        if (compatible.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(compatible);
    }

    private static boolean isCompatible(
            EcuIdentificationReader.Target target, ObdProtocol activeProtocol) {
        String busLabel = target.getProtocol();
        if (PRIMARY_AUTO_BUS.equals(busLabel)) {
            return hasMatchingAddressWidth(target, activeProtocol);
        }
        if (CAN_29_BIT_500_BUS.equals(busLabel)) {
            return activeProtocol == ObdProtocol.ISO_15765_4_CAN_29BIT_500
                    && isTwentyNineBit(target);
        }
        if (CAN_11_BIT_250_BUS.equals(busLabel)) {
            return activeProtocol == ObdProtocol.ISO_15765_4_CAN_11BIT_250
                    && !isTwentyNineBit(target);
        }
        return false;
    }

    private static boolean hasMatchingAddressWidth(
            EcuIdentificationReader.Target target, ObdProtocol activeProtocol) {
        return isTwentyNineBit(target) == isTwentyNineBit(activeProtocol);
    }

    private static boolean isTwentyNineBit(EcuIdentificationReader.Target target) {
        return target.getRequestId().length() == 8;
    }

    private static boolean isTwentyNineBit(ObdProtocol protocol) {
        return protocol == ObdProtocol.ISO_15765_4_CAN_29BIT_500
                || protocol == ObdProtocol.ISO_15765_4_CAN_29BIT_250;
    }

    private static boolean isConcreteIsoTpCan(ObdProtocol protocol) {
        return protocol == ObdProtocol.ISO_15765_4_CAN_11BIT_500
                || protocol == ObdProtocol.ISO_15765_4_CAN_29BIT_500
                || protocol == ObdProtocol.ISO_15765_4_CAN_11BIT_250
                || protocol == ObdProtocol.ISO_15765_4_CAN_29BIT_250;
    }
}
