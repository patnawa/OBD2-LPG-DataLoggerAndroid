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
     * Returns a double[2] = {scaledValue, scaledMin, scaledMax} or null on error.
     */
    public static double scale(int uasId, int raw) {
        switch (uasId) {
            case 0x01: return raw * 0.001;            // 0.001 per bit
            case 0x02: return raw * 0.01;             // 0.01 per bit
            case 0x03: return raw * 0.1;              // 0.1 per bit
            case 0x04: return raw * 1.0;              // 1 per bit (no scaling)
            case 0x05: return raw;                     // count (integer)
            case 0x06: return raw;                     // unsigned 2-byte count
            case 0x07: return raw * 0.01;             // % — 0 to 100%
            case 0x08: return raw * 0.001;            // Volts — 0 to 65.535V
            case 0x09: return raw * 0.001;            // millivolts per bit
            case 0x0A: return raw * 0.01;             // mA per bit
            case 0x0B: return raw * 0.1;              // kPa per bit
            case 0x0C: return raw * 0.01;             // °C (offset 0)
            case 0x0D: return (raw * 0.01) - 40;      // °C (-40 offset)
            case 0x0E: return raw * 0.1;              // °C — 0.1 deg/bit
            case 0x0F: return (raw * 0.1) - 40;       // °C (-40 offset, 0.1 deg/bit)
            case 0x10: return raw * 0.00390625;       // mPa — EVAP pressure
            case 0x11: return raw * 0.001;            // Pa per bit
            case 0x12: return raw * 0.01;             // Pa per bit — 0.01
            case 0x13: return raw * 0.1;              // Pa per bit — 0.1
            case 0x14: return raw * 1.0;              // Pa per bit — 1.0
            case 0x15: return (raw * 0.001) - 32.768; // Signed mPa
            case 0x16: return raw * 0.01;             // mA per bit — alternative
            case 0x17: return (short)raw;             // signed 16-bit count
            case 0x18: return raw * 0.001;            // Ohms per bit
            case 0x19: return raw * 0.001;            // Hz per bit
            case 0x1A: return raw * 0.001;            // µs per bit
            case 0x1B: return raw;                     // seconds
            case 0x1C: return raw * 0.01;             // km per bit
            case 0x1D: return raw * 0.1;              // kPa (MAP/pressure)
            case 0x1E: return (raw * 0.01) - 327.68;  // signed kPa
            case 0x1F: return (raw * 0.001) - 32.768; // signed V
            case 0x20: return (raw * 0.01) - 327.68;  // signed mA
            case 0x21: return raw * 0.01;             // kPa (gauge)
            case 0x22: return raw * 0.01;             // kPa (absolute)
            case 0x23: return raw * 0.001;            // kg/h
            case 0x24: return raw * 0.001;            // L/s
            case 0x25: return raw * 0.1;              // mg/stroke
            case 0x26: return raw * 0.01;             // mg/stroke — 0.01
            case 0x27: return (raw * 0.001) - 210;    // mg/stroke — offset -210
            case 0x28: return raw * 0.01;             // deg
            case 0x29: return raw * 0.001;            // deg — 0.001
            case 0x2A: return raw * 0.01;             // ratio (e.g., lambda)
            case 0x2B: return raw * 0.0000305;        // ratio — fine
            case 0x2C: return raw * 0.001;            // m3/s (air flow)
            case 0x2D: return raw * 0.01;             // g/s (mass flow)
            case 0x2E: return raw * 0.1;              // mg/stroke (fuel rate)
            case 0x2F: return (raw * 0.001) - 0.001;  // signed mg/stroke
            case 0x30: return raw * 0.001;            // g/km (emission rate)
            case 0x31: return raw * 0.01;             // ppm (concentration)
            case 0x32: return raw * 0.001;            // % (oxygen)
            case 0x33: return raw * 0.01;             // %CO2
            default:
                return raw; // unknown UASID — return raw
        }
    }

    /**
     * Return a human-readable unit string for a given UASID.
     */
    public static String unitFor(int uasId) {
        switch (uasId) {
            case 0x01: return "";
            case 0x02: return "";
            case 0x03: return "";
            case 0x04: return "";
            case 0x05: return "counts";
            case 0x06: return "counts";
            case 0x07: return "%";
            case 0x08: return "V";
            case 0x09: return "mV";
            case 0x0A: return "mA";
            case 0x0B: return "kPa";
            case 0x0C: return "°C";
            case 0x0D: return "°C";
            case 0x0E: return "°C";
            case 0x0F: return "°C";
            case 0x10: return "Pa";
            case 0x11: return "Pa";
            case 0x12: return "Pa";
            case 0x13: return "Pa";
            case 0x14: return "Pa";
            case 0x15: return "Pa";
            case 0x16: return "mA";
            case 0x17: return "";
            case 0x18: return "Ω";
            case 0x19: return "Hz";
            case 0x1A: return "µs";
            case 0x1B: return "s";
            case 0x1C: return "km";
            case 0x1D: return "kPa";
            case 0x1E: return "kPa";
            case 0x1F: return "V";
            case 0x20: return "mA";
            case 0x21: return "kPa";
            case 0x22: return "kPa";
            case 0x23: return "kg/h";
            case 0x24: return "L/s";
            case 0x25: return "mg/stroke";
            case 0x26: return "mg/stroke";
            case 0x27: return "mg/stroke";
            case 0x28: return "°";
            case 0x29: return "°";
            case 0x2A: return "ratio";
            case 0x2B: return "ratio";
            case 0x2C: return "m3/s";
            case 0x2D: return "g/s";
            case 0x2E: return "mg/stroke";
            case 0x2F: return "mg/stroke";
            case 0x30: return "g/km";
            case 0x31: return "ppm";
            case 0x32: return "%O2";
            case 0x33: return "%CO2";
            default:   return "";
        }
    }
}
