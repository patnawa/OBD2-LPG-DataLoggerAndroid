package com.alpha.obd2logger.can;

import java.util.Arrays;

/**
 * Decoder for already-received UDS responses.
 *
 * <p>This class has no request builder and does not implement Security Access,
 * memory services, programming, or ECU writes. It is safe to use for passive
 * evidence collection and offline replay only.</p>
 */
public final class UdsResponseDecoder {
    public enum Kind {
        POSITIVE_RESPONSE,
        NEGATIVE_RESPONSE,
        MALFORMED
    }

    public static final class DecodedResponse {
        private final Kind kind;
        private final int requestService;
        private final int responseService;
        private final int negativeResponseCode;
        private final int sessionType;
        private final byte[] data;
        private final String detail;

        private DecodedResponse(Kind kind, int requestService, int responseService,
                                int negativeResponseCode, int sessionType,
                                byte[] data, String detail) {
            this.kind = kind;
            this.requestService = requestService;
            this.responseService = responseService;
            this.negativeResponseCode = negativeResponseCode;
            this.sessionType = sessionType;
            this.data = data != null ? Arrays.copyOf(data, data.length) : new byte[0];
            this.detail = detail;
        }

        public Kind getKind() { return kind; }
        public int getRequestService() { return requestService; }
        public int getResponseService() { return responseService; }
        public int getNegativeResponseCode() { return negativeResponseCode; }
        public int getSessionType() { return sessionType; }
        public byte[] getData() { return Arrays.copyOf(data, data.length); }
        public String getDetail() { return detail; }
    }

    private UdsResponseDecoder() {
    }

    public static DecodedResponse decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return malformed("Empty UDS payload");
        }
        int service = payload[0] & 0xFF;
        if (service == 0x7F) {
            if (payload.length < 3) return malformed("Truncated negative UDS response");
            return new DecodedResponse(Kind.NEGATIVE_RESPONSE, payload[1] & 0xFF, service,
                    payload[2] & 0xFF, -1, copyRange(payload, 3),
                    negativeResponseName(payload[2] & 0xFF));
        }
        if (service < 0x40) {
            return malformed("Observed UDS request/unknown payload, not a response");
        }
        int requestService = service - 0x40;
        int session = service == 0x50 && payload.length >= 2 ? payload[1] & 0xFF : -1;
        return new DecodedResponse(Kind.POSITIVE_RESPONSE, requestService, service, -1,
                session, copyRange(payload, 1), serviceName(requestService));
    }

    private static DecodedResponse malformed(String detail) {
        return new DecodedResponse(Kind.MALFORMED, -1, -1, -1, -1, null, detail);
    }

    private static byte[] copyRange(byte[] source, int start) {
        return start >= source.length ? new byte[0] : Arrays.copyOfRange(source, start, source.length);
    }

    private static String serviceName(int service) {
        switch (service) {
            case 0x10: return "Diagnostic Session Control response";
            case 0x19: return "Read DTC Information response";
            case 0x22: return "Read Data By Identifier response";
            case 0x2E: return "Write Data By Identifier response (observed only)";
            case 0x27: return "Security Access response (observed only)";
            case 0x3E: return "Tester Present response";
            default: return "UDS positive response to service 0x" + Integer.toHexString(service).toUpperCase();
        }
    }

    private static String negativeResponseName(int nrc) {
        switch (nrc) {
            case 0x10: return "General Reject";
            case 0x11: return "Service Not Supported";
            case 0x12: return "Sub-function Not Supported";
            case 0x21: return "Busy Repeat Request";
            case 0x22: return "Conditions Not Correct";
            case 0x33: return "Security Access Denied";
            case 0x78: return "Response Pending";
            default: return "Negative Response Code 0x" + Integer.toHexString(nrc).toUpperCase();
        }
    }
}
