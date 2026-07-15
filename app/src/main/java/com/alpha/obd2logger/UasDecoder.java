package com.alpha.obd2logger;

/**
 * SAE J1979 Unit And Scaling (UAS) decoder for Mode 06 data.
 *
 * Mode 06 responses include a UASID byte that tells us how to scale the raw
 * 2-byte value into a human-readable number with a unit.
 *
 * Based on SAE J1979-DA / ISO 15031-5 UASID table.
 */
public final class UasDecoder {

    private UasDecoder() {}

    /**
     * Decode a raw 2-byte value using the given UASID.
     * Scaling factors per SAE J1979 Appendix (Unit and Scaling table).
     *
     * UASIDs 0x81 and above are the signed (two's-complement) counterparts
     * of the unsigned 0x01+ entries.
     */
    public static double scale(int uasId, int raw) {
        boolean signed = uasId >= 0x81;
        int baseId = signed ? uasId - 0x80 : uasId;
        double v = signed ? (short) raw : raw;
        switch (baseId) {
            case 0x01: return v;                      // raw count, 1 per bit
            case 0x02: return v * 0.1;                // raw, 0.1 per bit
            case 0x03: return v * 0.01;               // raw, 0.01 per bit
            case 0x04: return v * 0.001;              // raw, 0.001 per bit
            case 0x05: return v * 0.0000305;          // raw, 0.0000305 per bit
            case 0x06: return v * 0.000305;           // raw, 0.000305 per bit
            case 0x07: return v * 0.25;               // rpm, 0.25 per bit
            case 0x08: return v * 0.01;               // km/h, 0.01 per bit
            case 0x09: return v;                      // km/h, 1 per bit
            case 0x0A: return v * 0.122;              // mV, 0.122 per bit
            case 0x0B: return v * 0.001;              // V, 0.001 per bit
            case 0x0C: return v * 0.01;               // V, 0.01 per bit
            case 0x0D: return v * 0.00390625;         // mA, 1/256 per bit
            case 0x0E: return v * 0.001;              // A, 0.001 per bit
            case 0x0F: return v * 0.01;               // A, 0.01 per bit
            case 0x10: return v;                      // ms, 1 per bit
            case 0x11: return v * 100;                // ms, 100 per bit
            case 0x12: return v;                      // s, 1 per bit
            case 0x13: return v;                      // mΩ, 1 per bit
            case 0x14: return v;                      // Ω, 1 per bit
            case 0x15: return v;                      // kΩ, 1 per bit
            case 0x16: return signed ? v * 0.1        // °C, 0.1 per bit
                                     : v * 0.1 - 40;  //   (unsigned has -40 offset)
            case 0x17: return v * 0.01;               // kPa, 0.01 per bit
            case 0x18: return v * 0.0117;             // kPa, 0.0117 per bit
            case 0x19: return v * 0.079;              // kPa, 0.079 per bit
            case 0x1A: return v;                      // kPa, 1 per bit
            case 0x1B: return v * 10;                 // kPa, 10 per bit
            case 0x1C: return v * 0.01;               // degrees, 0.01 per bit
            case 0x1D: return v * 0.5;                // degrees, 0.5 per bit
            case 0x1E: return v * 0.0000305;          // ratio (lambda), 0.0000305 per bit
            case 0x1F: return v * 0.05;               // ratio (A/F), 0.05 per bit
            case 0x20: return v * 0.00390625;         // ratio, 1/256 per bit
            case 0x21: return v;                      // mHz, 1 per bit
            case 0x22: return v * 0.1;                // Hz, 0.1 per bit
            case 0x23: return v;                      // Hz, 1 per bit
            case 0x24: return v;                      // counts, 1 per bit
            case 0x25: return v;                      // km, 1 per bit
            case 0x26: return v * 0.1;                // mV/ms, 0.1 per bit
            case 0x27: return v * 0.01;               // g/s, 0.01 per bit
            case 0x28: return v;                      // g/s, 1 per bit
            case 0x29: return v * 0.25;               // Pa/s, 0.25 per bit
            case 0x2A: return v * 0.001;              // kg/h, 0.001 per bit
            case 0x2B: return v;                      // switches, 1 per bit
            case 0x2C: return v * 0.01;               // g/cyl, 0.01 per bit
            case 0x2D: return v * 0.01;               // mg/stroke, 0.01 per bit
            case 0x2E: return v;                      // true/false
            case 0x2F: return v * 0.01;               // %, 0.01 per bit
            case 0x30: return v * 0.001526;           // %, 0.001526 per bit
            case 0x31: return v * 0.001;              // L, 0.001 per bit
            case 0x32: return v * 0.0000305;          // inch, 0.0000305 per bit
            case 0x33: return v * 0.00024414;         // ratio, 0.00024414 per bit
            default:
                return v; // unknown UASID — return raw (signed if 0x81+)
        }
    }

    /**
     * Return a human-readable unit string for a given UASID.
     * UASIDs 0x81+ share the units of their unsigned counterparts.
     */
    public static String unitFor(int uasId) {
        int baseId = uasId >= 0x81 ? uasId - 0x80 : uasId;
        switch (baseId) {
            case 0x01: return "counts";
            case 0x02: return "counts";
            case 0x03: return "counts";
            case 0x04: return "counts";
            case 0x05: return "counts";
            case 0x06: return "counts";
            case 0x07: return "rpm";
            case 0x08: return "km/h";
            case 0x09: return "km/h";
            case 0x0A: return "mV";
            case 0x0B: return "V";
            case 0x0C: return "V";
            case 0x0D: return "mA";
            case 0x0E: return "A";
            case 0x0F: return "A";
            case 0x10: return "ms";
            case 0x11: return "ms";
            case 0x12: return "s";
            case 0x13: return "mΩ";
            case 0x14: return "Ω";
            case 0x15: return "kΩ";
            case 0x16: return "°C";
            case 0x17: return "kPa";
            case 0x18: return "kPa";
            case 0x19: return "kPa";
            case 0x1A: return "kPa";
            case 0x1B: return "kPa";
            case 0x1C: return "°";
            case 0x1D: return "°";
            case 0x1E: return "ratio";
            case 0x1F: return "ratio";
            case 0x20: return "ratio";
            case 0x21: return "mHz";
            case 0x22: return "Hz";
            case 0x23: return "Hz";
            case 0x24: return "counts";
            case 0x25: return "km";
            case 0x26: return "mV/ms";
            case 0x27: return "g/s";
            case 0x28: return "g/s";
            case 0x29: return "Pa/s";
            case 0x2A: return "kg/h";
            case 0x2B: return "switches";
            case 0x2C: return "g/cyl";
            case 0x2D: return "mg/stroke";
            case 0x2E: return "";
            case 0x2F: return "%";
            case 0x30: return "%";
            case 0x31: return "L";
            case 0x32: return "in";
            case 0x33: return "ratio";
            default:   return "";
        }
    }
}
