# TunerMap Pro — OBD2 Multi-Fuel Data Logger Android

**Version 3.22.0** | Professional-grade OBD2 vehicle diagnostics, multi-fuel air density analysis, and secured AI Agent integration.

แอปพลิเคชัน Android สำหรับบันทึกข้อมูล OBD2 จากรถยนต์ วิเคราะห์ความหนาแน่นของอากาศ (AAD/MAD/BAD) และการจูนเชื้อเพลิงทุกชนิด พร้อมเชื่อมต่อ AI Agent ผ่าน REST API

---

## What's New in 3.22.0 — Real-Time DTC Scan Tracker

- **Live scan progress visualization** — the DTC scan tab now shows a real-time tracker panel instead of a blind spinning progress bar:
  - Each protocol bus (HS-CAN, MS-CAN, CAN 29-bit, KWP2000, ISO 9141, J1850 VPW) appears as a row with animated status: ○ pending → ◐ scanning → ✓ responded / ● has DTCs / ✗ no response.
  - Mode 03/07/0A status chips appear inline per protocol as each mode is scanned, showing clean/DTC count.
  - Detected ECU modules appear as nested rows with CAN ID, module name, and per-mode DTC counts.
  - Summary line shows live progress ("Scanning 2/7: MS-CAN") and final result.
- Added `DtcScanProgressListener` callback interface to `DtcReader.java` for real-time scan events.
- Created `ScanTrackerView.java` — a custom view with thread-safe UI updates.
- Backward-compatible: existing `readAllDtcs()` and `readAllDtcsDeep()` overloads still work without a listener.

## What's New in 3.21.0 — Drive Insight Merge & Feature Completion

- Merged the full Drive Insight feature set from the production-hardening branch into main:
  - **DriveInsightEngine** — a pure, testable priority engine that evaluates RPM, coolant, voltage, fuel trim, and DTC count to produce a typed insight with navigation destination.
  - **Tap-to-detail dialog** — tapping the Drive Insight card now opens a snapshot of current readings with an advisory and a deep-link button to the relevant tool (Diagnostics, Battery, Fuel Map, or Dashboard).
  - **Missing strings added** — `drive_insight_tap_details`, `drive_insight_advisory`, `drive_insight_snapshot`, and all `drive_insight_open_*` navigation labels.
  - **Unit tests** — 3 tests covering DTC priority, per-condition routing, and stable/collecting states.
- Resolved CI workflow conflict by adopting the agent branch's `build-apk.yml` with AAB support and secret validation.

---

## What's New in 3.20.3 — Play Store Release Preparation

- Release CI now builds a signed Android App Bundle (`.aab`) for Play Console, verifies both APK/AAB signatures, and publishes SHA-256 checksums.
- Added the [Play Store release checklist](docs/PLAY_STORE_RELEASE_CHECKLIST.md) for policy declarations, privacy, testing, security, listing assets, and hardware validation.

---

## What's New in 3.20.2 — Session History & UX

- **Batch Session History** — Select any number of CSV/JSONL session files inside a VIN folder, share them together through Android's multi-file chooser, or delete them after a single confirmation.
- **Safe workflow separation** — Compare 2 Logs remains limited to map comparison; Select Logs is dedicated to file management and never opens the replay screen.
- **Drive Insight clarity** — Healthy/collecting states now show a live snapshot in place, while actionable findings deep-link only to the relevant tool.
- **Adaptive gauge colors** — Light and Dark themes now keep gauge tracks, ticks, bezels, text, and needle highlights readable.

---

## What's New in 3.20.1 — Live Map & Installability Hotfix

- Map Live Data now follows the active foreground/background logger store and shows an immediate, safety-isolated live-cell preview while learning gates settle.
- Drive Insight opens an actionable detail dialog and routes each finding to the relevant diagnostic tool.
- Background Logging now requests notification access in context, can continue safely when notifications are denied, and reports `BG START/LIVE/RETRY/ERROR`, record count, and notification visibility in the header and Settings.
- Release automation refuses unsigned APK publication and verifies persistent release signing before creating a GitHub Release.

## 3.20.0 — Production Reliability & Secure Realtime API
- AUTO transport discovery and reconnects now have hard deadlines, deterministic cleanup, actual-transport reporting, and no silent simulation fallback.
- Intermittent PIDs use adaptive cooldown/retry instead of permanent session blacklisting; dead transports trigger reconnect rather than empty logging.
- Fuel-map learning distinguishes measured/synthesized MAP and rejects learning when ECT, loop state, or fuel trim evidence is missing.
- The realtime API now requires a per-install token for all vehicle data and mutations, enforces bounded transactional map imports, and cannot let slow SSE clients block OBD polling.
- Foreground logging survives closing the UI, Android backup/global cleartext are disabled, and invalid bundled font files were removed.

## Previous: 3.19.0 — Reliable Session Intelligence
- Rebuilt session summaries as schema v2 with session-linked filenames, app/schema version, vehicle/connection metadata, completeness state, and raw-file references.
- Added checkpoint summaries every 10 records so force-stop or connection loss preserves a recoverable partial summary.
- Corrected trip distance and fuel integration using real `elapsed_s` intervals, trapezoidal integration, PID 0x5E fuel-rate priority, and explicit fallback/gap metrics.
- Added per-column count, null count, coverage, standard deviation, status/source counts, and overall data-quality reporting.
- Added measured/synthesized MAP source to CSV/JSONL and compact non-OK quality metadata to JSONL.

## Previous: 3.18.0 — Next-generation Scanner Intelligence
- Rebuilt connection setup around AUTO transport resolution with clearer adapter state and the actual connected transport exposed to the realtime API.
- Improved automatic VIN, vehicle-brand, supported-PID, bitmap, targeted-probe, and per-VIN cache logic for broader vehicle compatibility.
- Added a practical Custom PID manager with add/edit/delete, formula validation, raw-data testing, and support in LPG-only polling.
- Upgraded DTC scanning presentation and decoding safeguards, and strengthened Battery/Crank test validation and guidance.
- Corrected wideband PID `0x34` measured lambda/current decoding and separated PID `0x44` commanded equivalence ratio from measured AFR calculations.
- Added Actual AFR, Commanded AFR, AFR source/quality log fields, gauge selection, and safe Live Map replay behavior.
- Refined the responsive cockpit, header/VIN layout, language coverage, settings flow, history UI, and consistent light/dark surfaces.

## Previous: 3.17.0 — UX Polish, Localization & Code Cleanup
- Improved diagnostics safety, theme behavior, graph readability, lifecycle handling, and removed obsolete UI/code paths. See CHANGELOG.

## Previous: 3.16.9 — Interactive Live Graph
- Added pause/resume, touch inspection, and tap-to-toggle graph series on the Home live graph.

## Previous: 3.16.8 — Detailed Multi-color Live Graph
- Added a Home multi-series graph with RPM, Speed, and Boost lines, legend, rolling time window, grid, axis labels, and live connection state.

## Previous: 3.16.7 — Scanner-first Navigation + Responsive Gauges
- Bottom navigation now exposes Home, Live Data, Logs, and Diagnostics; connection states provide clear scanning guidance and dashboard gauges scale to their available width.

## Previous: 3.16.6 — Modern Icon-only Scan Action
- Replaced the legacy MaterialButton Start control with a true Material FloatingActionButton using play/pause icons and state-based color.

## Previous: 3.16.5 — Professional Scanner Home
- Added a dedicated diagnostic health summary and improved the first-page scanner hierarchy around live telemetry, fault visibility, and actionable workflows.

## Previous: 3.16.4 — Modern Primary Action + Balanced Status/Gauges
- Start/Stop is now an icon-only modern circular action with status-based color, the top status chip is larger, and dashboard gauges fill their cards proportionally.

## Previous: 3.16.3 — Refined Primary Action + Gauge Sizing
- Prominent high-contrast Start/Stop control, simplified in-page toolbar, larger protocol badge, and properly proportioned dashboard gauge cards.

## Previous: 3.16.2 — Persistent Navigation + Dense Live Telemetry
- Bottom navigation now remains usable on every screen with labels matching their actual actions; Live Telemetry adds Boost, Throttle, and Total Fuel Trim readouts.

## Previous: 3.16.1 — Reference-Matched Navigation + Quick Access
- Added the fixed five-position bottom navigation from the UI reference, removed duplicated home toolbar actions, and rebuilt Quick Access as detailed 3-column cards.

## Previous: 3.16.0 — Material 3 Automotive Cockpit
- Complete home-screen redesign based on a modern dark automotive dashboard: connection header, live RPM/speed telemetry, compact sensor cards, RPM trend, and large one-tap feature tiles.
- Material Design 3 day/night color roles, safer 48dp touch targets, and a horizontally scrollable live status strip for narrow phones.

## Previous: 3.15.0 — AeroDensity Intelligence Physics + Scanner Fix
- Absolute-humidity MAD, independent TMF, last-good weather cache, Ambient PID in LPG poll set, quality-stamped samples. See CHANGELOG.

## Previous: 3.14.0 — Professional Fuel-Trim Analyzer

Shop-grade STFT/LTFT analysis: closed-loop + warm gates, LTFT-primary thresholds (petrol ±5% / LPG ±8%), Bank1+2 fusion, UNSTABLE detection, confidence %, EN/TH advice. Fixes Thai PERFECT→UNKNOWN, UNKNOWN painted as red, and STFT-only freeze.

## Previous: 3.12.0 — Gauge/Dashboard Localization + Long-press to Clear

### Fixed — Localization
- **"Tap to Add" placeholder** — Was a fixed bilingual literal `"แตะเพื่อเพิ่ม (Tap to Add)""` that never switched with app language. Now uses proper string resources (EN/TH).
- **PID selection dialog** — Was entirely hardcoded Thai. Now uses localized string resources for title, subtitle, search hint, and hide option.
- **"Live Graphs", "Data 1-4", "Records: N"** — All hardcoded strings in the gauge/dashboard layouts now use string resources.

### Added — Long-press to Clear PID
- **Long-press on any gauge/dashboard/graph card** now clears the PID slot (sets to hidden/none). Previously there was no way to remove a PID — click and long-press both opened the same picker dialog.
- **Toast confirmation** — "Slot N cleared" confirms the action.
- **"💡 Long-press to clear" hint** — Added at the bottom of the PID picker dialog.

## What's New in 3.11.0 — Pro Scanner Bugs + Icon Visibility + Compact Start Button

### Fixed — Pro Scanner (4 bugs)
- **In-Use Performance data hidden** — Condition only checked PIDs 0D/0E, skipping valid distance/time data from PID 0F.
- **Enhanced Scan only worked for 4 of 23 brands** — Rewrote to use Brand enum directly. All 23 brands now mapped to their enhanced modes (Mode 21/1A/27/2C/22).
- **Mode 21/61 sent twice for unknown VINs** — Deduplicated with LinkedHashSet.
- **Mode 08 dialog showed all 14 tests** — Now queries supported tests first and filters the list. Bitmap parser fixed to read all data bytes.

### Fixed — UI
- **App logo on blue hero header** — Blue/cyan elements were invisible on the blue gradient. Changed to white + amber.
- **Start button too wide** — Reduced padding/icon size, added maxWidth=90dp to prevent fuel badge from shrinking.
- **Pro Scanner buttons** — Green text on blue background was unreadable. Changed to surface (white/dark) background.

## What's New in 3.10.0 — DTC Scan, VIN Detection & Deep PID Scan (8 bugs) + DTC UI Overhaul

### Fixed — Fuel & AFR
- **Fuel badge showing raw enum name** — Redundant `setText` overwrote the localized label.
- **AFR gauge stuck at zero** — Derived keys had no PIDDefinition; added `DerivedGaugeConfig` with proper ranges for 27 derived sensors.
- **LPG fuel map shows 0% trim** — Added lambda-based trim fallback: `trim = (lambda - 1.0) * 100`.

### Fixed — PID Detection
- **Missing bitmap banks** — Extended from 4 to all 8 SAE J1979 bitmap banks (`0100`–`01E0`).
- **Force-include set too narrow** — Expanded from 7 to 17 PIDs (added speed, MAF, throttle, baro, voltage, DPF PIDs).

### Fixed — DTC Scanning
- **ECU names show wrong brand** — Split single `ECU_NAMES` map into per-brand maps with `setBrand()`.
- **In-process path didn't load brand DTC DB** — Added `DtcDatabase.initForVin()` call.
- **Protocol not re-locked after DTC scan** — Added `0100` re-probe after `ATSP0` restore.
- **NO DATA with space not filtered** — Added regex match for `"NO DATA"` variant.

### Added — DTC UI Overhaul
- Vehicle info card (brand + VIN), loading spinner, empty state ("All Clear!"), deep scan badge, button grouping, MaterialButton consistency.

## What's New in 3.9.0 — Realtime AI Agent Pipeline & Map Accuracy Overhaul

### Fixed — Fuel Map Bin Accuracy
- **RPM Binning Mismatch (UI vs API)** — UI used FLOOR (749→500), API used ROUND (750→1000), causing AI agents to see data in wrong cells. New `MapBinning.java` unifies all binning to FLOOR-based — UI, API, and SSE now agree.
- **API Server Had No Debounce** — UI filtered noise with 4-sample sliding window, but API didn't — AI saw noise spikes users never saw. Debounce moved to `LiveMapStore` so all consumers get clean data.
- **Race Condition in sessionPetrolData** — `clear()+putAll()` every record could leave the map empty mid-read. Replaced with immutable `LiveMapStore.snapshot()`.
- **CSV Export Header** — Still said `T.inj \ RPM` despite Y-axis being MAP kPa. Fixed to `MAP kPa \ RPM`.
- **Filter PIDs Dialog Crash** — `BottomSheetDialog` used default theme which doesn't support Material3, causing a silent crash. Fixed with explicit `R.style.AppTheme` + fallback to `AlertDialog`.

### Added — Realtime AI Agent Pipeline
- **SSE `map_update` Event** — AI agents get per-record map deltas via `/api/stream` — no polling needed.
- **SSE `map_summary` Event** — Every 5 records, aggregated map stats pushed to AI agents.
- **`/api/agent` Zone Analysis** — Map broken into idle/cruise/acceleration/fullLoad zones with per-zone avg deviation + confidence level.
- **`/api/agent` Hotspots** — Top 20 cells with |deviation| > 5%, sorted by severity, with suggested corrections.
- **`/api/agent` Snapshot Cache** — 500ms cache reduces latency for frequent AI polling.
- **AeroDensity API Status** — Dialog now shows Open-Meteo API connection status (✓/✗), data source, and last fetch age.

### Changed — Architecture
- **3 Map Data Copies → 1 `LiveMapStore`** — UI, API, and SSE all read from one thread-safe store via immutable snapshots.
- **`FuelMapView.TrimData` + `ApiServer.MapTrimData` removed** — Replaced by unified `LiveMapStore.TrimData`.

## What's New in 3.8.1 — Fuel Map, Log Replay, VIN Folders, Persistence & API Enhancements

### Fixed
- **Fuel Map Y-Axis (T.inj → MAP kPa)** — Was using a non-linear injection-time conversion instead of actual MAP sensor values, causing data to land in wrong cells. Now uses MAP kPa directly.
- **Compare Map From Log File** — CSV files without loop-state columns were silently dropping all rows. Fixed default to match live path behavior.
- **Logs Not in VIN Folder (Background Mode)** — VIN was never read because `"UNKNOWN"` default wasn't treated as unset. Now creates per-VIN subfolders correctly.
- **Fuel Mode & BT Device Reset on Stop** — Selections now persist across stop/start cycles via SharedPreferences.
- **API Sensor Data Loss** — Duplicate sensor names were overwriting each other in JSON. Now keyed by `pidKey` with full `{pidKey, name, value, unit, status}` objects.

### Added
- **`GET /api/agent`** — Aggregate endpoint for AI Agents: status + sensors + map summary + DTCs in one call.
- **DTC Severity** — API now reports CRITICAL/WARNING/INFO per DTC code.
- **Session Metadata in API** — vehicleBrand, VIN, record count, adapter connection, transport mode, session duration.

### Changed
- **"LIVE SESSION METRICS" → "RECORDING STATUS"** — Clearer label so you know it shows live recording duration, record count, and update rate.

## What's New in 3.8.0 — Logger Random Stop (Comprehensive Fix)

### Fixed (6 root causes addressed)
1. **'NO DATA' false fatal** — Removed `NO DATA`/`STOPPED` from ELM327 fatal response markers. These are normal "no value right now" responses, not connection failures. Previously caused permanent PID blacklisting.
2. **Watchdog false kill** — In-process connection watchdog now cancels on successful `connect()` instead of waiting for the first record. Timeout raised 20s → 45s.
3. **Stale connection detection** — Added liveness tracking: 5 consecutive empty responses now mark the connection as dead, triggering automatic reconnect.
4. **Stuck `isPaused`** — Diagnostic pause flag now resets in `stopLogging()` and `onDestroy()`.
5. **Core PID protection** — RPM/Speed/Coolant/MAP/Load PIDs can no longer be blacklisted. Minimum 3-PID floor enforced.
6. **Reconnect timeout** — Reconnect `connect()` now bounded to 30s. Initial connect raised 15s → 30s.

## What's New in 3.7.9 — AeroDensity Intelligence Rebrand & UI Fixes

### Fixed
- **Air Density Dialog Top Cut Off** — Content was hidden behind the status bar. Fixed with window insets padding.
- **Toolbar Icon Re-arranged** — Air Density icon now sits directly next to the Settings icon on the right side.

### Changed
- **Removed "Banks iDash" Branding** — All user-visible references removed from the entire codebase.
- **Renamed to "AeroDensity Intelligence"** — Dialog title, dashboard panel, settings description, and toolbar all use the new name.
- **Dialog Icon Fixed** — Replaced mismatched speedometer icon with the correct air density icon.

## What's New in 3.7.8 — Logger Random Stop Fix

### Fixed
- **Logger Randomly Stops (retryCount Accumulation Bug)** — The retry counter was never reset during normal operation, so 11 scattered transient errors over a long drive would permanently kill the logger. Now resets to 0 after every successful record write.
- **Derived-Sensor NPEs Feeding Retry Counter** — `AirDensityMonitor.compute()` and `computeAdvanced()` are now wrapped in try/catch so NPEs from null batch values don't increment the retry counter.
- **Non-IO Exceptions No Longer Count Toward Retry Cap** — Only connection/IO errors (`IOException`, `SocketTimeoutException`, `SocketException`) increment the retry counter; data-parsing errors are logged and skipped.

## What's New in 3.7.7 — Toolbar Overlap, Icon & Logo Fixes

### Fixed
- **Toolbar Button Overlap (START/STOP + Air Density)** — `btnHeaderAirDensity` was anchored to `btnSettings` (same anchor as `fabLog`), so the Air Density icon overlapped the START/STOP button. Fixed by chaining it to `layout_toStartOf="@id/fabLog"` so the right-to-left order is: Settings → START/STOP → Air Density → Status → Home.
- **Wrong Air Density Button Icon** — Replaced the mismatched `ic_speed` (speedometer) icon with a new dedicated `ic_air_density.xml` wind/air-flow icon matching the Air Density Center feature.
- **Weird Hero Logo on Home Screen** — The Home hero `ImageView` used `ic_launcher_foreground` (108dp adaptive-icon viewport with safe-zone padding), making the artwork tiny and off-center. Created a dedicated `ic_app_logo.xml` (72dp viewport) so the gauge/car/OBD logo fills the view properly.
- **Startup NullPointerException** — Added null guards to `valueByKey`, `onRecord`, `updateDashboard`, and `updateFuelMap` for when `latestDataRecord` is null at startup.

## What's New in 3.7.5 — Air Density Center UI Crash Fix

### Fixed
- **App Crash Fixed when opening Air Density Center UI** — Fixed a crash caused by inflating Material 3 components (`MaterialCardView` and `MaterialButton`) inside the system `Theme_DeviceDefault_Light_NoActionBar` dialog theme. Updated `showAirDensityCenterDialog()` to use `R.style.AppTheme`, ordered `show()` before UI updates so dialog views populate reliably, and added safe exception handling.

## What's New in 3.7.4 — Separate Air Density UI Center & Engine Gauges Performance Fix

### Added
- **Separate Dedicated Air Density UI Center Accessible on Front Page** — Full-screen standalone Air Density Center dialog showcasing all 12 Air Density & Engine Efficiency metrics (AAD, MAD, BAD, Density %, SAE J1349 CF, Density Altitude, VE, Compressor Efficiency, Intercooler Effectiveness, PDI, Water Vapor Grains) with live weather summary and manual refresh. Accessible instantly from the front page header bar button (`btnHeaderAirDensity`) or the dashboard Air Density card button.

### Fixed & Optimized
- **Engine Gauges Cluster Performance Optimization** — Removed redundant Session Peak Telemetry card grid from the Gauges tab (`panelGauges`), eliminating UI thread lag and frame drops during live logging sessions so animated dials and graphs render at maximum speed.
- **Live Data Logger Random Stop Fixed** — Periodic continuous DTC checks now run synchronously on the main logging loop with increased retry resilience (`maxRetries = 10`), preventing socket I/O collisions and premature connection drops.
- **PID ID Filter Fixed** — SharedPreferences mutation bug resolved; custom PIDs properly included via `PIDCatalogue.getAllWithCustom`, and readings instantly re-render when toggling filters.
- **Air Density UI Toggle Fixed** — Toggling Air Density in Settings immediately updates the live dashboard panel without requiring app restart.

## What's New in 3.7.3 — Bug Fixes (Logger Stops, PID Filter)

### Fixed
- Logger no longer stops randomly — weather API was blocking the logging thread every 0.5s
- PID Filter now works correctly — was filtering out everything due to empty set bug
- Redundant gauge readings removed — reduces UI load on slow devices

## What's New in 3.7.2 — Air Density Dashboard Panel

### Dedicated Air Density UI
- New panel in Dashboard tab with symmetric 4x3 grid showing all 12 air density values
- AAD/MAD/BAD, Density %, Density Altitude, SAE J1349 CF, OMD, Compressor Efficiency, Intercooler Effectiveness, VE, PDI, Grains H2O
- Auto-shows/hides based on Air Density setting toggle
- Live weather data (RH, temperature, barometric pressure) in panel header

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
- **Authenticated HTTP API server** (NanoHTTPD on port 8080): Enable it in Settings, then tap the API card to copy the endpoint and per-install access token
  - `GET /api/ping` — public heartbeat
  - `GET /api/agent` — complete AI snapshot: status, sensors, map analysis, hotspots, and DTCs
  - `GET /api/data` — all sensor values with units
  - `GET /api/map` — binned Fuel Map with min_hits filter
  - `POST /api/map/import` — import map session JSON
  - `GET /api/map/export` — download correction CSV
- **Authentication**: Send `Authorization: Bearer <token>` or `X-API-Key: <token>`; browser EventSource may use `?token=<token>`
- **CORS enabled**: Authenticated web AI agents and MCP clients can fetch directly
- **Zero polling impact**: Separate thread, no OBD2 slowdown

### UI/UX
- **6 tabs**: Dashboard, Gauges, Fuel Map, DTC, Battery, History (with Settings panel)
- **Multi-language**: Thai (ภาษาไทย) + English + System Default
- **Day/Night theme**: System default, Light, or Dark mode with quick-toggle
- **Keep screen on**: Prevents screen sleep while app is foregrounded
- **Dynamic PID selection**: Long-press any gauge/card/graph to choose which PID it displays
- **History browser**: Browse/open supported CSV logs, select multiple CSV/JSONL sessions, batch share or delete them, and compare up to two CSV logs

## Build

Requirements: JDK 17, Android SDK 35 (API 35), Gradle wrapper 9.4.1

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot"
export ANDROID_HOME="/c/Users/Alpha/Android/Sdk"
./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease bundleRelease
```

Build outputs:

- Installable debug APK: `app/build/outputs/apk/debug/app-debug.apk` (about 7.9 MB)
- Optimized release APK: `app/build/outputs/apk/release/app-release-unsigned.apk` (must be signed with a private release keystore before distribution)
- Play Store App Bundle: `app/build/outputs/bundle/release/app-release.aab` (must be signed with the persistent upload key)
- APK and `.idsig` artifacts are intentionally excluded from Git history; publish signed production packages through GitHub Releases or the app store.

### Release signing

GitHub Releases are created only for `v*` tags and only when these repository secrets contain the owner's persistent signing identity:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded JKS/keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow refuses to publish when any signing secret is missing. PR debug artifacts use an ephemeral CI key and are labeled **clean-install-only**; they must not be used as upgrade packages. Never commit a keystore or its passwords.

See [docs/PLAY_STORE_RELEASE_CHECKLIST.md](docs/PLAY_STORE_RELEASE_CHECKLIST.md) before creating a `v*` tag.

## Runtime Notes

- **WiFi**: Default `192.168.0.10:35000`. Change in Settings.
- **Bluetooth**: Select a paired ELM327 from the dropdown. No MAC typing.
- **USB**: Plug in vLinker/USB-serial adapter via OTG. Grant USB permission when prompted.
- **Background logging**: Enable in Settings to use foreground service (survives screen off / app switch).
- **Keep screen on**: Enabled by default. Toggle in Settings.
- **API server**: Enable in Settings, tap the connection card to copy the `/api/agent` URL and token, then authenticate each request.
- **DPF Monitor**: Auto-enabled for diesel vehicles on first VIN read. Toggle in Settings to disable.
- **Custom PIDs**: Enable in Settings, then add via SharedPreferences or API.
- **Custom log folder**: Use Settings → "Select Log Folder" to choose a SAF-accessible directory.

## Tech Stack

- **Language**: Java 17 (Android minSdk 23, targetSdk 35)
- **UI**: AndroidX, Material Design 3, custom Canvas views (GaugeView, GraphView, FuelMapView)
- **USB**: usb-serial-for-android v3.7.3 (mik3y)
- **API server**: NanoHTTPD 2.3.1
- **Architecture**: Catalogue pattern — PIDs defined once in `PIDCatalogue.java`, auto-flow through querying, logging, and display
- **Testing**: JUnit 4, 195 unit tests covering transport, PID parsing/discovery, logging, replay, maps, diagnostics, battery/crank logic, and API security

## Project Structure

Version 3.20 reliability modules include `DriverConnector` (bounded connect/reconnect), `PidHealthTracker` (adaptive PID recovery), `UnavailableDriver` (explicit AUTO failure), `ApiSecurity` (per-install API credentials), and `MapSampleMeta` (fuel-map safety/provenance).

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
