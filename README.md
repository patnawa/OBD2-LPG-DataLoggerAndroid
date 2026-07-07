# TunerMap Pro >> OBD2 Petrol/LPG/CNG Data Logger Android

**Version 3.4.21** | Native Android app for OBD2 vehicle data logging, LPG/CNG/Petrol tuning analysis, and AI Agent integration.

แอปพลิเคชัน Android สำหรับบันทึกข้อมูล OBD2 จากรถยนต์ วิเคราะห์การจูนแก๊ส LPG/CNG และเชื่อมต่อกับ AI Agent ผ่าน REST API

---

## What's New in 3.4.21

- **CRITICAL: WiFi connect fails when gateway disabled** — When using mobile data + WiFi adapter simultaneously (gateway disabled), Android doesn't route the adapter's subnet through wlan0. Now uses `Network.bindSocket()` (same trick CarScanner Pro uses) to force the socket onto the WiFi link.
- Python client: `SO_BINDTODEVICE` on wlan0 for the same routing fix.
- Python client: PID parser — Control Module Voltage (0x3E) and Ambient Air Temp (0x46) had wrong formulas, returned 4628V / 115°C instead of 4.628V / 75°C. Fixed.

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
- **Live Fuel Map**: 2D grid (T.inj ms × RPM) with color-coded STFT+LTFT averages — red (rich) to blue (lean), green (perfect)
- **Dual-fuel comparison**: Separate Petrol and LPG data layers on the same map; Deviation view shows `LPG - Petrol` per cell
- **Correction grid**: Auto-calculates the % correction multiplier needed for the LPG ECU to match petrol trims
- **CSV export**: Export correction map as CSV for use on a tuning laptop; share via any app (Line, Email, Bluetooth)
- **Closed-loop gating**: Only plots data when PID 03 bit 0x02 is set (closed loop) and ECT ≥ 80°C
- **LTFT fallback**: Uses LTFT alone when STFT is unavailable (common on some Toyota/Honda models)
- **MAP → T.inj scaling**: Linearly scales standard OBD2 MAP/Load values into estimated `T.inj` ms to ensure compatibility with all car models
- **Cell lock & hit counter**: Dwell-time debounce filters transients; cells lock after 20+ hits (gold border)
- **Open Log File**: Load external CSV files from anywhere (SAF picker) for offline analysis; multi-select for cross-file Petrol+LPG comparison
- **In-list Compare 2 Logs**: Pick a Petrol log + an LPG log from History and plot both on one map

### Tuning Formula & Examples
The **Deviation** and **Tune Assist** percentage calculations are based on the following formula:
$$\text{Deviation} = \text{LPG Trim} - \text{Petrol Trim}$$
This calculates the net fuel trim difference between running on gas and the petrol baseline:
- **Positive Deviation (e.g., +8%)**: LPG is running leaner than petrol (the ECU must add 8% extra fuel when on gas). To correct this, you need to **increase (add +8%)** the LPG multiplier map at that cell in your gas tuning software.
- **Negative Deviation (e.g., -8%)**: LPG is running richer than petrol (the ECU is pulling 8% more fuel when on gas). To correct this, you need to **decrease (subtract -8%)** the LPG multiplier map at that cell in your gas tuning software.
- **Goal**: Adjust the LPG map until the Deviation is close to **0%** across all cells.

### Live Dashboard
- **4 customizable gauges**: Professional analog gauges with tapered needle, arc number labels, smooth animation, peak value indicator, 3D hub cap, per-gauge color themes (RPM=red, Speed=cyan, Temp=amber, Load=green), and warning zones
- **4 dashboard value cards**: Long-press to swap which PID each card shows
- **5 real-time graphs**: Rolling line charts with auto-scaling, fill area, and current-value indicators
- **Readings table**: Full PID list with values, units, and ok/err status
- **Tuning status**: Real-time LEAN/RICH/OK analysis with recommendations (Thai/English)

### Diagnostics
- **DTC scanning**: Read stored (Mode 03), pending (Mode 07), and permanent (Mode 0A) Diagnostic Trouble Codes with descriptions
- **Mode 06 Monitor Test Results**: Read actual test values, min/max thresholds, and pass/fail for all monitors (Catalyst, O2, EGR, EVAP, Misfire)
- **Per-DTC Freeze Frames**: Individual freeze frame snapshot for each stored DTC with 10 PIDs
- **DTC Enrichment**: 157 codes with probable causes, repair suggestions, emissions flags, and drive cycles to clear
- **Scan Comparison**: Shows NEW and CLEARED codes vs previous scan
- **Clear DTCs**: Send Mode 04 to clear codes and reset MIL
- **VIN reader**: Read 17-character VIN via Mode 09 PID 02 (multi-frame ISO-TP)
- **ECU Calibration**: Read Cal-ID and CVN via Mode 09 for emissions compliance
- **Readiness monitors**: Check emission inspection readiness — Misfire, Fuel System, Components, Catalyst, Heated Catalyst, EVAP, Secondary Air, EGR, Particulate Filter, NOx/SOR, O2 Sensor, O2 Heater

### Battery & Charging System Tester (NEW in v3.3.0)
Professional-grade 12V battery diagnostics via OBD2 PID 0x42 (Control Module Voltage) or AT RV fallback. 11 automated tests with pass/fail/warn severity, overall health grade (A+ to F), and Battery Life estimation:
- **ระบบเลือกช่องสัญญาณแรงดันไฟอัจฉริยะ (Smart Voltage Acquisition with AT RV Fallback)**:
  - อ่านแรงดันไฟฟ้าหลักผ่าน OBD2 PID `01 42` (Control Module Voltage) จากกล่อง ECU โดยตรง
  - **ระบบสำรองอัตโนมัติ (Fallback)**: ในกรณีที่เป็นรถยนต์รุ่นเก่า (เช่น รถก่อนปี 2008) ที่กล่อง ECU ไม่รองรับรหัส PID `01 42` แอปพลิเคชันจะสลับไปส่งคำสั่งตรงเข้าฮาร์ดแวร์อะแดปเตอร์ ELM327 ด้วยคำสั่ง **`AT RV`** ทันที เพื่ออ่านค่าแรงดันไฟฟ้าทางกายภาพ (Analog Voltage) จาก Pin 16 ของพอร์ต OBD2 (ซึ่งเชื่อมกับแบตเตอรี่รถยนต์โดยตรง) ทำให้มั่นใจได้ว่าระบบสามารถแสดงผลค่าแรงดันไฟได้สำเร็จกับรถยนต์ทุกรุ่น 100%
- **State of Charge (SoC)**: Open-circuit voltage → SoC% lookup (flooded lead-acid, 25°C)
- **State of Health (SOH)**: Multi-factor degradation estimate (resting voltage + cranking voltage + recovery + charge acceptance)
- **Battery Life Estimate**: Remaining months based on SOH, battery type (Flooded/AGM), age, and tropical climate factor
- **Alternator Voltage**: Regulated output check at idle (13.8-14.7V spec)
- **Voltage Drop Test**: No-load vs full-load (headlights + blower + AC + defroster) comparison
- **Voltage Recovery**: How fast voltage returns after load dump (internal resistance indicator)
- **Cranking Voltage**: Minimum battery voltage during engine crank (fast 80ms sampling for 5 seconds)
- **Ripple / Diode Health**: AC ripple detection via 20-sample burst (bad diode detection)
- **Parasitic Drain Estimate**: Voltage decay rate when engine off
- **Charging Efficiency**: Voltage stability across RPM range (idle vs high RPM)
- **Live Voltage Monitor**: Real-time scrolling graph with threshold bands (crank/rest/alternator/overcharge zones)
- **Battery type selector**: Flooded (Standard), AGM/Gel, Calcium
- **Full Diagnostic**: One-tap comprehensive report with weighted overall score

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
  - `GET /api/ping` — ping server, returns uptime and timestamp
  - `GET /api/status` — connection status, fuel mode, VIN
  - `GET /api/data` — latest sensor data (all PIDs with values and units)
  - `GET /api/map?min_hits=N` — binned 2D Fuel Map (Petrol, LPG, Deviation, Tune Assist); filters cells by minimum hits `N` (default `1`)
  - `DELETE /api/map` — clear/reset in-memory map data
  - `GET /api/map/summary` — aggregate calibration metrics, max deviation coordinates, and tuning recommendation text
  - `GET /api/map/export` — download the current map correction grid directly as a CSV file
  - `POST /api/map/import` — import and override the map session data via a JSON payload
- **CORS enabled**: Web-based AI agents, MCP clients, and laptops can fetch and modify data directly
- **Zero polling impact**: API server runs on separate thread; doesn't slow OBD2 polling

### UI/UX
- **5 tabs**: Dashboard, Gauges, Fuel Map, DTC, History (with Settings panel)
- **Global Multi-Language**: Support for 16 options including English, Thai (ภาษาไทย), Spanish (Español), Portuguese (Português), German (Deutsch), French (Français), Italian (Italiano), Russian (Русский), Hindi (हिन्दी), Arabic (العربية), Indonesian (Bahasa Indonesia), Vietnamese (Tiếng Việt), Japanese (日本語), Korean (한국어), Chinese (中文), and System Default auto-detection
- **Day/Night theme**: System default, Light, or Dark mode with a quick-access theme toggle button (sun/moon) on the top app bar
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
- **Testing**: JUnit 4, 75 unit tests (PID parsing, log replay, PID availability, vLinker optimizer)

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
├── GaugeView.java           # Professional analog gauge (tapered needle, labels, animation)
├── LPGAnalyzer.java         # STFT+LTFT analysis (LEAN/RICH/OK)
├── FuelTrimResult.java      # Analysis result data class
├── PidAvailabilityChecker.java  # SAE J1979 PID bitmap queries
├── BrandYearProfile.java    # VIN-based brand/year PID profile fallback
├── DtcReader.java           # Mode 03/07/0A DTC reader
├── DtcCode.java             # DTC code parser (SAE J2010)
├── DtcEnrichment.java       # DTC enrichment DB (causes, fixes, emissions)
├── DtcComparison.java       # Scan comparison (new/cleared/persisting)
├── DtcReportExporter.java   # PDF diagnostic report export
├── DtcHistoryDb.java        # SQLite DTC scan history per VIN
├── Mode06Reader.java        # Mode 06 monitor test results reader
├── Mode06Result.java        # Mode 06 result model + monitor names
├── UasDecoder.java          # SAE J1979 Unit And Scaling decoder
├── Mode09Reader.java        # Cal-ID and CVN reader
├── ReadinessMonitor.java    # Mode 01 PID 01 readiness parser
├── VinReader.java           # Mode 09 PID 02 VIN reader
├── ApiServer.java           # NanoHTTPD REST API server
├── LoggerConfig.java        # Configuration data class
├── ObdProtocol.java         # OBD protocol enum (ATSP values)
├── TransportMode.java       # Transport enum (SIM/WIFI/SERIAL/BLE/USB/AUTO)
├── FuelMode.java            # Fuel enum (PETROL/LPG_CNG)
└── LocaleHelper.java        # Thai/English locale switcher
```

## Changelog

### v3.4.18 (2026-07-07) — WiFi Connection Reliability Fix
- **ELM327 boot-timeout race in `WiFiDriver.connect()`**:
  - Replaced hardcoded `Socket.setSoTimeout(250)` with adaptive timeout: `Math.max(connectionTimeoutMs, 2000)` during ATZ/ATI/AT@1 init probe, tightened to `Math.max(connectionTimeoutMs / 4, 500)` for steady-state.
  - Connect handshake timeout floor raised from 2s to 5s.
  - Added 500ms `Thread.sleep` after TCP accept to let ELM327 finish booting before sending ATZ.
  - New `volatile boolean initializing` flag distinguishes init-phase (wait through timeouts for boot prompt) from steady-state (treat timeout-with-data as end-of-message).
  - Replaced silent `catch (Exception ignored)` with `Log.e/w("WiFiDriver", ...)` for debugging via `adb logcat`.
- Verified on Xiaomi 2311DRK48G / Android 16 / vLinker MC WiFi (same setup where Car Scanner Pro works fine).

### v3.4.17 (2026-07-07) — Termux USB Guide + Locale Cleanup
- **New `docs/termux-usb-setup.md`**: Companion guide for using vLinker FS USB from Termux/Python on Android 11+ (Android 16 verified). Documents SELinux sandbox workaround via `termux:API` v0.53.0 FD-passing + `libusb_wrap_sys_device()` bridge.
- **Removed 13 unused translation resources** (ar, de, es, fr, hi, id, it, ja, ko, pt, ru, vi, zh); kept `System Default`, `English`, `Thai`.

### v3.1.1 (2026-07-04) — Bug Hunt: Threading & vLinker Fixes
- **[HIGH] vLinker optimizations were dead code** — `detectDevice()` checked `isConnected()` before `connected` was set, so all vLinker-specific AT commands (ATST32/ATST1A/ATST23, ATAT1/ATAT2, 6-PID chunks) never applied. Every adapter got generic ELM327 settings. Fixed by removing the premature guard.
- **[HIGH] Concurrent OBD2 command corruption** — Logger thread and DTC executor thread both called `sendCommand()` on the same driver simultaneously, interleaving writes/reads and corrupting responses. Added `ReentrantLock` to all 4 drivers (USB, WiFi, BT SPP, BLE).
- **[HIGH] removeAll() crash on unmodifiable PID list** — When PID detection failed, `LoggerService` tried to `removeAll()` on `PIDCatalogue.getAll()` which returns an unmodifiable list → `UnsupportedOperationException`. Now always uses a mutable copy.
- **[HIGH] FuelMapView thread-unsafe HashMap** — Background log replay thread wrote to `HashMap` while UI thread read/iterated → `ConcurrentModificationException`. Changed to `ConcurrentHashMap`.
- **[MEDIUM] Bluetooth SPP socket leak** — When standard RFCOMM `connect()` failed, the old socket wasn't closed before creating the fallback socket via reflection, leaking native Bluetooth resources.

## License

MIT
