# OBD2 Petrol/LPG/CNG Data Logger Android

**Version 2.9.1** | Native Android app for OBD2 vehicle data logging, LPG/CNG/Petrol tuning analysis, and AI Agent integration.

แอปพลิเคชัน Android สำหรับบันทึกข้อมูล OBD2 จากรถยนต์ วิเคราะห์การจูนแก๊ส LPG/CNG และเชื่อมต่อกับ AI Agent ผ่าน REST API

---

## Overview

This app connects to your vehicle's OBD2 port via ELM327-compatible adapters (vLinker, generic clones) and logs real-time sensor data for fuel tuning analysis. It supports Petrol and LPG/CNG dual-fuel workflows with a live fuel map, Tune Assist correction grid, and DTC/VIN/readiness diagnostics. A built-in HTTP API server lets external AI Agents read live sensor data over WiFi.

## Key Features

### Data Logging
- **40+ OBD2 PIDs**: Engine RPM, Vehicle Speed, Engine Load, Coolant Temp, Intake Air Temp, MAF, MAP, Fuel Pressure, Barometric Pressure, Throttle Position, Timing Advance, STFT/LTFT (Bank 1 & 2), O2 Sensors (B1S1-B2S4 voltage + STFT), Lambda (wideband), Control Module Voltage, Absolute Load, Fuel Level, Fuel Type, Ethanol %, EVAP Pressure, Run Time, Distance counters, and more
- **Dual format logging**: CSV (RFC-4180 quoted) + JSONL (one JSON object per line)
- **Custom log folder**: Save logs anywhere via Android Storage Access Framework (SAF) — Downloads, SD card, USB, Google Drive
- **Fuel-mode prefixed filenames**: e.g. `PETROL_20260630_120000_obd2.csv`, `LPG_20260630_130000_obd2.csv`
- **Background foreground service**: Logging continues when screen is off or app is minimised (PARTIAL_WAKE_LOCK)
- **PID auto-detection**: Queries SAE J1979 PID availability bitmaps (0x00, 0x20, 0x40) to poll only supported PIDs — 30-50% faster cycles
- **VIN-based fallback**: If live detection fails, decodes brand/year from VIN WMI + model year code to estimate supported PIDs

### LPG/CNG Tune Assist
- **Live Fuel Map**: 2D grid (RPM × MAP) with color-coded STFT+LTFT averages — red (rich) to blue (lean), green (perfect)
- **Dual-fuel comparison**: Separate Petrol and LPG data layers on the same map; Deviation view shows `LPG - Petrol` per cell
- **Correction grid**: Auto-calculates the % correction multiplier needed for the LPG ECU to match petrol trims
- **CSV export**: Export correction map as CSV for use on a tuning laptop; share via any app (Line, Email, Bluetooth)
- **Closed-loop gating**: Only plots data when PID 03 bit 0x02 is set (closed loop) and ECT ≥ 80°C
- **LTFT fallback**: Uses LTFT alone when STFT is unavailable (common on some Toyota/Honda models)
- **MAP → Engine Load fallback**: Uses Engine Load (PID 0x04) as X-axis when MAP is unavailable (MAF-only vehicles)
- **Cell lock & hit counter**: Dwell-time debounce filters transients; cells lock after 20+ hits (gold border)
- **Open Log File**: Load external CSV files from anywhere (SAF picker) for offline analysis; multi-select for cross-file Petrol+LPG comparison
- **In-list Compare 2 Logs**: Pick a Petrol log + an LPG log from History and plot both on one map

### Live Dashboard
- **4 customizable gauges**: Analog gauge views with neon glow, warning zones, and tick marks
- **4 dashboard value cards**: Long-press to swap which PID each card shows
- **5 real-time graphs**: Rolling line charts with auto-scaling, fill area, and current-value indicators
- **Readings table**: Full PID list with values, units, and ok/err status
- **Tuning status**: Real-time LEAN/RICH/OK analysis with recommendations (Thai/English)

### Diagnostics
- **DTC scanning**: Read stored (Mode 03) and pending (Mode 07) Diagnostic Trouble Codes with descriptions
- **Clear DTCs**: Send Mode 04 to clear codes and reset MIL
- **VIN reader**: Read 17-character VIN via Mode 09 PID 02 (multi-frame ISO-TP)
- **Readiness monitors**: Check emission inspection readiness — Misfire, Fuel System, Components, Catalyst, Heated Catalyst, EVAP, Secondary Air, EGR, Particulate Filter, NOx/SOR, O2 Sensor, O2 Heater

### Adapter Support
- **vLinker FS USB** (MIC3322): USB CDC, firmware-specific optimizations (ATAT1, ATST32, ATAL, 6-PID chunks, 250ms interval)
- **vLinker MC WiFi** (MIC3313): TCP, pre-buffering optimizations (ATAT2, ATST1A, ATAL, 6-PID chunks, 300ms interval)
- **vLinker MC BT** (MIC3313): Bluetooth SPP, conservative timing (ATAT1, ATST23, ATAL, 4-PID chunks, 500ms interval)
- **Generic ELM327**: WiFi/BLE/BT SPP/USB — conservative 4-PID chunks, 100ms timeout, 500ms interval
- **Auto-connect**: Tries USB → WiFi → selected BT device → all paired BT devices → simulation fallback
- **Multi-PID batch polling**: Sends up to 6 PIDs per ELM327 command; individual retry for failed PIDs
- **USB serial**: Supports CH340, CP2102, FTDI, Prolific via usb-serial-for-android v3.7.3

### AI Agent Integration
- **HTTP API server** (NanoHTTPD on port 8080): Enable in Settings to expose live data as JSON
  - `GET /api/status` — connection status, fuel mode, VIN
  - `GET /api/data` — latest sensor data array (all PIDs with values and units)
  - `GET /api/map` — fuel map data (endpoint reserved)
- **CORS enabled**: Web-based AI agents can fetch data directly from browser
- **Zero polling impact**: API server runs on separate thread; doesn't slow OBD2 polling

### UI/UX
- **5 tabs**: Dashboard, Gauges, Fuel Map, DTC, History (with Settings panel)
- **Bilingual**: English and Thai (ภาษาไทย), auto-detects system locale
- **Day/Night theme**: System default, Light, or Dark mode
- **Keep screen on**: Prevents screen sleep while app is foregrounded (default on, toggleable)
- **Dynamic PID selection**: Long-press any gauge/card/graph to choose which PID it displays
- **History browser**: Browse, open, share, delete, and compare past log files

## Build

Requirements: JDK 17, Android SDK 35 (API 35), Gradle 8.5.2+

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
export ANDROID_HOME="/c/Users/Alpha/Android/Sdk"
./gradlew clean testDebugUnitTest assembleDebug
```

Debug APK output:
```
app/build/outputs/apk/debug/app-debug.apk (~5.8 MB)
```

## Runtime Notes

- **WiFi**: Default `192.168.0.10:35000`. Change in Settings.
- **Bluetooth**: Select a paired ELM327 from the dropdown. No MAC typing.
- **USB**: Plug in vLinker/USB-serial adapter via OTG. Grant USB permission when prompted.
- **Background logging**: Enable in Settings to use foreground service (survives screen off / app switch).
- **Keep screen on**: Enabled by default. Toggle in Settings.
- **API server**: Enable in Settings to allow AI Agents to read live data on `http://<phone-ip>:8080/api/data`.
- **Custom log folder**: Use Settings → "Select Log Folder" to choose a SAF-accessible directory.

## Tech Stack

- **Language**: Java 17 (Android minSdk 23, targetSdk 35)
- **UI**: AndroidX, Material Design 3, custom Canvas views (GaugeView, GraphView, FuelMapView)
- **USB**: usb-serial-for-android v3.7.3 (mik3y)
- **API server**: NanoHTTPD 2.3.1
- **Architecture**: Catalogue pattern — PIDs defined once in `PIDCatalogue.java`, auto-flow through querying, logging, and display
- **Testing**: JUnit 4, 59 unit tests (PID parsing, log replay, PID availability, vLinker optimizer)

## Project Structure

```
app/src/main/java/com/alpha/obd2logger/
├── PIDCatalogue.java       # 40+ PID definitions (SAE J1979)
├── PIDDefinition.java       # PID data class (name, service, hex, formula, dataBytes)
├── PIDParser.java           # Formula evaluator + multi-PID response parser
├── ElmDriver.java           # ELM327 AT command init, multi-PID batch query
├── BaseDriver.java          # Abstract driver base
├── WiFiDriver.java          # WiFi TCP socket transport
├── UsbDriver.java           # USB CDC serial transport
├── BleDriver.java           # Bluetooth LE GATT transport
├── SerialDriver.java        # Bluetooth SPP transport
├── SimulationDriver.java    # Simulated vehicle data (no adapter needed)
├── DriverFactory.java       # Transport selection + AUTO probe
├── VLinkerOptimizer.java    # vLinker firmware-specific AT command tuning
├── LoggerService.java       # Foreground service for background logging
├── MainActivity.java        # UI, in-process logging, dashboard/gauges/graphs
├── ReviewSessionActivity.java  # Offline log replay onto FuelMapView
├── LogReplayParser.java     # RFC-4180 CSV parser for log replay
├── DataWriter.java          # CSV + JSONL writer (MediaStore/SAF/legacy)
├── DataRecord.java          # Log record data class
├── SensorSample.java        # Single PID sample data class
├── FuelMapView.java         # 2D fuel map grid view (RPM × MAP)
├── GraphView.java           # Real-time line graph
├── GaugeView.java           # Analog gauge with needle
├── LPGAnalyzer.java         # STFT+LTFT analysis (LEAN/RICH/OK)
├── FuelTrimResult.java      # Analysis result data class
├── PidAvailabilityChecker.java  # SAE J1979 PID bitmap queries
├── BrandYearProfile.java    # VIN-based brand/year PID profile fallback
├── DtcReader.java           # Mode 03/07 DTC reader
├── DtcCode.java             # DTC code parser (SAE J2010)
├── ReadinessMonitor.java    # Mode 01 PID 01 readiness parser
├── VinReader.java           # Mode 09 PID 02 VIN reader
├── ApiServer.java           # NanoHTTPD REST API server
├── LoggerConfig.java        # Configuration data class
├── ObdProtocol.java         # OBD protocol enum (ATSP values)
├── TransportMode.java       # Transport enum (SIM/WIFI/SERIAL/BLE/USB/AUTO)
├── FuelMode.java            # Fuel enum (PETROL/LPG_CNG)
└── LocaleHelper.java        # Thai/English locale switcher
```

## License

MIT
