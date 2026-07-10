# TunerMap Pro — OBD2 Multi-Fuel Data Logger Android

**Version 3.7.1** | Professional-grade OBD2 vehicle diagnostics, multi-fuel air density analysis, and AI Agent integration.

แอปพลิเคชัน Android สำหรับบันทึกข้อมูล OBD2 จากรถยนต์ วิเคราะห์ความหนาแน่นของอากาศ (AAD/MAD/BAD) และการจูนเชื้อเพลิงทุกชนิด พร้อมือนต่อ AI Agent ผ่าน REST API

---

## What's New in 3.7.1 — PID Filter + Bug Fixes

### PID Filter for Live Readings
- New "Filter PIDs" button in Logs tab — select which PIDs to display
- Derived sensors (AAD/MAD/BAD, etc.) hidden by default — toggle to show
- Reduces UI lag on slow/cheap OBD2 adapters (50+ cards → only what you need)
- Filter persists across sessions

### Air Density UI Toggle
- New checkbox in Settings to enable/disable Air Density calculation
- Air density now works in both in-process and background logging modes

### Bug Fixes
- Fuel mode spinner now properly disabled during logging (was changeable but had no effect)
- Default fuel selection fixed (was NGV, now correctly Petrol)

## What's New in 3.7.0 — Air Density System + Multi-Fuel Support

### Banks iDash Style Air Density (AAD/MAD/BAD)
- **Live Weather API** — Open-Meteo (free, no key) fetches humidity, temperature, pressure, wind via GPS. Cached 10 min
- **AAD (Ambient Air Density)** — Air density around vehicle in lbs/1000ft³ (SAE J1349 standard = 72.2)
- **MAD (Manifold Air Density)** — Air density in intake manifold
- **BAD (Boost Air Density)** = MAD - AAD — density gain from forced induction
- **Density Altitude**, **SAE J1349/J607 Correction Factors**, **Grains H2O**

### 10 Advanced Formulas Beyond Banks iDash
1. **OMD** — Oxygen Mass Density (true combustible O2, not just total air)
2. **CE** — Compressor Efficiency (turbo health, 65-78% normal)
3. **IC-EFF** — Intercooler Effectiveness (<60% = upgrade needed)
4. **VE** — Volumetric Efficiency (cross-validates MAF sensor)
5. **DCAFR** — Density-Corrected AFR (humidity-corrected mixture)
6. **TMF** — Theoretical Mass Flow (independent MAF cross-check)
7. **LVD** — LPG/CNG Vapor Displacement (critical for gaseous fuel)
8. **ECC** — Evaporative Cooling Correction (charge cooling from fuel evap)
9. **PDI** — Power Density Index (single number tracking power)
10. **Dynamic SAE CF** — J1349 + J607 with delta comparison

### Multi-Fuel Support — All 8 Thai Fuel Types
- **Gasohol 91 (E10)**, **Gasohol 95 (E10)**, **E20**, **E85**
- **LPG**, **NGV/CNG**
- **Diesel B7**, **Diesel B20**
- Each fuel has correct AFR, density, LHV, ethanol%, thermal efficiency
- 8-color UI theme per fuel type
- Backwards-compatible with existing CSV logs ("petrol", "lpg/cng")

### 22 New Derived Columns Logged to CSV/JSONL
AAD, MAD, BAD, density%, density altitude, SAE J1349 CF, grains, humidity, OMD, compressor eff, intercooler eff, VE, DCAFR, TMF, MAF deviation, LVD, effective density, evap cooling ΔT, evap-corrected MAD, PDI, SAE J607 CF, SAE CF delta

## What's New in 3.6.0 — Pro DTC Scanner Upgrade

Major upgrade bringing the DTC scanner to professional diagnostic scanner level:

### Scan Reliability
- **Protocol Probe** — Probes each protocol bus before scanning, skips dead buses
- **Retry with Backoff** — Mode 03/07/0A retry 3× with exponential backoff
- **ISO-TP Flow Control** — Multi-frame DTC responses no longer truncated
- **Post-Clear Verification** — Rescans after Mode 04 to confirm DTCs are actually cleared

### DTC Intelligence
- **Fixed Severity Logic** — Uses enrichment DB + proper heuristic (airbag=CRITICAL, misfire=CRITICAL)
- **DTC → Monitor Correlation** — Maps each DTC to affected readiness monitor
- **Drive Cycle Guidance** — Step-by-step driving instructions for incomplete monitors

### 20 Brand-Specific DTC Databases (5,054 codes total)
- Toyota/Lexus, Honda, Isuzu, Nissan, Mitsubishi, Ford/Mazda, Suzuki
- Chevrolet, Hyundai/Kia, Volvo, BMW, Mercedes-Benz
- BYD, GWM, NETA, AION, Deepal, MG, Tesla (Chinese EV + Tesla)

### Professional Diagnostics
- **Continuous DTC Monitoring** — Polls PID 01 every 30s, full scan only on change
- **Per-ECU Physical Addressing** — ATSH/ATCRA for individual ECU scans
- **Enhanced Mode Scanning** — Mode 21/22/1A/27 manufacturer-specific codes
- **Mode 08 Bi-Directional Control** — 14 tests (EGR, EVAP, fan, fuel pump, etc.)
- **Mode 09 In-Use Performance** — Ignition cycles, OBD trips, distance/time since clear
- **Freeze Frame PID Query** — Discovers supported PIDs via Mode 02 PID 00

### Enhanced PDF Report
- Now includes: Readiness status, Mode 06 results, ECU module list, protocol scan results, drive cycle guidance

---

## Overview

This app connects to your vehicle's OBD2 port via ELM327-compatible adapters (vLinker, generic clones) and logs real-time sensor data for fuel tuning analysis. It supports Petrol and LPG/CNG dual-fuel workflows with a live fuel map, Tune Assist correction grid, and DTC/VIN/readiness diagnostics. A built-in HTTP API server lets external AI Agents read live sensor data over WiFi.

## Key Features

### Data Logging
- **45+ OBD2 PIDs**: Engine RPM, Vehicle Speed, Engine Load, Coolant Temp, Intake Air Temp, MAF, MAP, Fuel Pressure, Barometric Pressure, Throttle Position, Timing Advance, STFT/LTFT (Bank 1 & 2), O2 Sensors (B1S1-B2S4 voltage + STFT), Lambda (wideband), Control Module Voltage, Absolute Load, Fuel Level, Fuel Type, Ethanol %, EVAP Pressure, Run Time, Distance counters, DPF Soot/Temp/Delta/Regen/Ash, and more
- **Derived sensors**: Fuel Economy (km/L, L/100km), Turbo Boost (kPa, psi), DPF Health (Clean/Moderate/Warning/Critical), DPF Regen Status
- **Dual format logging**: CSV (RFC-4180 quoted) + JSONL (one JSON object per line)
- **Custom log folder**: Save logs anywhere via Android Storage Access Framework (SAF) — Downloads, SD card, USB, Google Drive
- **Fuel-mode prefixed filenames**: e.g. `PETROL_20260630_120000_obd2.csv`, `LPG_20260630_130000_obd2.csv`
- **Background foreground service**: Logging continues when screen is off or app is minimised (PARTIAL_WAKE_LOCK)
- **PID auto-detection**: Queries SAE J1979 PID availability bitmaps (0x00, 0x20, 0x40) to poll only supported PIDs — 30-50% faster cycles
- **VIN-based fallback**: If live detection fails, decodes brand/year from VIN WMI + model year code to estimate supported PIDs
- **Config persistence**: All settings restored on reopen; checkboxes save instantly (no onPause required)

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
- **2x4 telemetry grid**: RPM | Speed | Coolant | Voltage + Fuel (km/L) | Boost (psi) | DPF | DTC count
- **4 customizable gauges**: Professional analog gauges with tapered needle, arc number labels, smooth animation, peak value indicator, 3D hub cap, per-gauge color themes (RPM=red, Speed=cyan, Temp=amber, Load=green), and warning zones
- **4 dashboard value cards**: Long-press to swap which PID each card shows
- **5 real-time graphs**: Rolling line charts with auto-scaling, fill area, and current-value indicators
- **Readings table**: Full PID list with values, units, and ok/err status
- **Tuning status**: Real-time LEAN/RICH/OK analysis with recommendations (Thai/English)

### Diagnostics
- **DTC scanning**: Read stored (Mode 03), pending (Mode 07), and permanent (Mode 0A) Diagnostic Trouble Codes with descriptions
- **Multi-protocol deep scan**: 7 protocol buses — HS-CAN, MS-CAN (Ford/Mazda), CAN 29-bit (Isuzu), CAN 250k, KWP2000 (older Toyota), ISO 9141-2 (older Honda/Nissan), J1850 VPW (older Isuzu)
- **ECU database**: 40+ CAN IDs mapped to human-readable module names — Toyota, Honda, Mazda, Isuzu, Nissan, Mitsubishi, Ford
- **Mode 06 Monitor Test Results**: Read actual test values, min/max thresholds, and pass/fail for all monitors (Catalyst, O2, EGR, EVAP, Misfire)
- **Per-DTC Freeze Frames**: Individual freeze frame snapshot for each stored DTC with 10 PIDs
- **DTC Enrichment**: 157 codes with probable causes, repair suggestions, emissions flags, and drive cycles to clear
- **Scan Comparison**: Shows NEW and CLEARED codes vs previous scan
- **Clear DTCs**: Send Mode 04 to clear codes and reset MIL
- **VIN reader**: Read 17-character VIN via Mode 09 PID 02 (multi-frame ISO-TP) — auto-detects diesel vehicles
- **ECU Calibration**: Read Cal-ID and CVN via Mode 09 for emissions compliance
- **Readiness monitors**: Check emission inspection readiness — 12 monitors including Particulate Filter

### Battery & Charging System Tester
Professional-grade 12V battery diagnostics via OBD2 PID 0x42 (Control Module Voltage) or AT RV fallback. 11 automated tests with pass/fail/warn severity, overall health grade (A+ to F), and Battery Life estimation. Chemistry-aware: Flooded, AGM, EFB, Gel, Calcium, LiFePO4.

### Adapter Support
- **vLinker FS USB** (MIC3322): USB CDC, firmware-specific optimizations (ATAT1, ATST32, ATAL, 6-PID chunks)
- **vLinker MC WiFi** (MIC3313): TCP, pre-buffering optimizations (ATAT2, ATST1A, ATAL, 6-PID chunks)
- **vLinker MC BT** (MIC3313): Bluetooth SPP, conservative timing (ATAT1, ATST23, ATAL, 4-PID chunks)
- **Generic ELM327**: WiFi/BLE/BT SPP/USB — conservative 4-PID chunks
- **Auto-connect**: Tries USB → WiFi → selected BT device → all paired BT devices → simulation fallback
- **Multi-PID batch polling**: Sends up to 6 PIDs per ELM327 command
- **USB serial**: Supports CH340, CP2102, FTDI, Prolific via usb-serial-for-android v3.7.3

### AI Agent Integration
- **HTTP API server** (NanoHTTPD on port 8080): Enable in Settings to expose live data as JSON
  - `GET /api/ping` — heartbeat
  - `GET /api/data` — all sensor values with units
  - `GET /api/map` — binned Fuel Map with min_hits filter
  - `POST /api/map/import` — import map session JSON
  - `GET /api/map/export` — download correction CSV
- **CORS enabled**: Web AI agents and MCP clients can fetch directly
- **Zero polling impact**: Separate thread, no OBD2 slowdown

### UI/UX
- **6 tabs**: Dashboard, Gauges, Fuel Map, DTC, Battery, History (with Settings panel)
- **Multi-language**: Thai (ภาษาไทย) + English + System Default
- **Day/Night theme**: System default, Light, or Dark mode with quick-toggle
- **Keep screen on**: Prevents screen sleep while app is foregrounded
- **Dynamic PID selection**: Long-press any gauge/card/graph to choose which PID it displays
- **History browser**: Browse, open, share, delete, and compare past log files

## Build

Requirements: JDK 17, Android SDK 35 (API 35), Gradle 8.5.2+

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
export ANDROID_HOME="/c/Users/Alpha/Android/Sdk"
./gradlew clean testDebugUnitTest assembleDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk (~6.5 MB)`

## Runtime Notes

- **WiFi**: Default `192.168.0.10:35000`. Change in Settings.
- **Bluetooth**: Select a paired ELM327 from the dropdown. No MAC typing.
- **USB**: Plug in vLinker/USB-serial adapter via OTG. Grant USB permission when prompted.
- **Background logging**: Enable in Settings to use foreground service (survives screen off / app switch).
- **Keep screen on**: Enabled by default. Toggle in Settings.
- **API server**: Enable in Settings to allow AI Agents to read live data on `http://<phone-ip>:8080/api/data`.
- **DPF Monitor**: Auto-enabled for diesel vehicles on first VIN read. Toggle in Settings to disable.
- **Custom PIDs**: Enable in Settings, then add via SharedPreferences or API.
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
├── PIDCatalogue.java       # 45+ PID definitions (SAE J1979 + DPF + custom merge)
├── PIDDefinition.java       # PID data class (name, service, hex, formula, dataBytes)
├── PIDParser.java           # Formula evaluator + multi-PID response parser
├── DerivedSensors.java      # Fuel economy, turbo boost, DPF health computation
├── CustomPidManager.java    # User-defined PID storage (JSON in SharedPreferences)
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
├── GaugeView.java           # Professional analog gauge
├── LPGAnalyzer.java         # STFT+LTFT analysis (LEAN/RICH/OK)
├── FuelTrimResult.java      # Analysis result data class
├── PidAvailabilityChecker.java  # SAE J1979 PID bitmap queries
├── BrandYearProfile.java    # VIN-based brand/year PID profile fallback
├── DtcReader.java           # Mode 03/07/0A DTC reader + multi-protocol deep scan
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
├── BatteryTester.java       # Professional 12V battery + charging system tester
├── ApiServer.java           # NanoHTTPD REST API server
├── LoggerConfig.java        # Configuration data class
├── ObdProtocol.java         # OBD protocol enum (ATSP values)
├── TransportMode.java       # Transport enum (SIM/WIFI/SERIAL/BLE/USB/AUTO)
├── FuelMode.java            # Fuel enum (PETROL/LPG_CNG)
└── LocaleHelper.java        # Thai/English locale switcher
```

## Changelog

See [CHANGELOG.md](./CHANGELOG.md) for the full release history.

## License

MIT