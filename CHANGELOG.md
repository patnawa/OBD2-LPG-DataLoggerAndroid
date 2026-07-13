# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

## [3.22.1] - 2026-07-13 — SW-CAN/CH-CAN/LS-CAN Protocol Expansion

- Versioned the program as `3.22.1` (`versionCode 120`).
- Added 3 new CAN protocol buses to the deep DTC scan, unlocking modules on GM/Lexus/Chrysler vehicles that were previously invisible:
  - **SW-CAN** (ATSPA) — Single-Wire CAN 33.3kbps, GM/Lexus body/door/lighting/seat modules.
  - **CH-CAN** (ATSPC) — Chrysler/FCA/Jeep/Ram High-Speed CAN 500kbps.
  - **LS-CAN** (ATSPD) — GM/Chrysler Low-Speed CAN 125kbps, comfort/BCM/lighting modules.
- Deep scan now probes 10 protocol buses (was 7): HS-CAN auto, MS-CAN, CAN 29-bit, CAN 11-bit 250k, KWP2000, ISO 9141-2, J1850 VPW, SW-CAN, CH-CAN, LS-CAN.
- Requires vLinker MS or equivalent 5 CAN-channel adapter for full coverage; non-vLinker adapters will simply get no response on the new buses (graceful skip).
- Added ECU name maps:
  - `GM_ECU` — 20 GM/Chevrolet modules across HS-CAN (0x7E0-7EB), SW-CAN (0x640-645), LS-CAN (0x680-683).
  - `CHRYSLER_ECU` — 13 Chrysler/Jeep/FCA modules across CH-CAN (0x7E0-7E9) and LS-CAN (0x710-714).
  - `LEXUS_ECU` — 8 Lexus modules including SW-CAN body modules (0x740-743).
- Updated `getBrandEcuMap()` to route Chevrolet -> GM_ECU, Chrysler/Jeep/Dodge -> CHRYSLER_ECU.
- The real-time ScanTrackerView (v3.22.0) automatically displays these new protocol buses as they are probed.

## [3.22.0] - 2026-07-13 — Real-Time DTC Scan Tracker

- Versioned the program as `3.22.0` (`versionCode 119`).
- Added `DtcScanProgressListener` callback interface to `DtcReader.java` — fires real-time events as each protocol bus is probed, each DTC mode (03/07/0A) is scanned, and each ECU module is detected.
- Added overloaded `readAllDtcs(driver, msCan, listener)` and `readAllDtcsDeep(driver, fordMode, listener)` that accept a progress listener; the original no-listener overloads remain backward-compatible.
- Created `ScanTrackerView.java` — a visual scan tracker panel that replaces the single indeterminate ProgressBar with a live per-protocol and per-module status display:
  - Each protocol bus shows as a row with an animated status icon: ○ pending → ◐ scanning (amber pulse) → ✓ responded (green) / ● has DTCs (red) / ✗ no response (grey).
  - Per-protocol Mode 03/07/0A chips appear inline as each mode is scanned, showing clean/DTC count.
  - Detected ECU modules appear as nested rows with CAN ID, module name, and per-mode DTC counts.
  - Summary line shows live progress ("Scanning 2/7: MS-CAN") and final result ("Scan complete: 3 protocols responded, 5 DTCs found").
  - Thread-safe: all UI updates marshalled to the main thread via Handler.
- Wired `ScanTrackerView` into `MainActivity.java` — inserted above the DTC list container, reset on scan start, stays visible after scan completes for review, hidden on error.
- No layout XML changes needed — ScanTrackerView is created programmatically and inserted into the existing DTC panel parent.

## [3.21.0] - 2026-07-13 — Drive Insight Merge & Feature Completion

- Versioned the program as `3.21.0` (`versionCode 118`).
- Merged `agent/v3-20-production-hardening` branch into `main`, bringing the full Drive Insight feature set that was previously missing from main:
  - Added `DriveInsightEngine.java` — a pure, testable priority engine that evaluates RPM, coolant, voltage, fuel trim, and DTC count to produce a typed insight result with navigation destination.
  - Added `DriveInsightEngineTest.java` with 3 unit tests covering DTC priority, per-condition routing, and stable/collecting states.
  - `updateCockpitInsight()` now delegates to `DriveInsightEngine.evaluate()` instead of inline if/else logic, and stores `currentDriveInsight` for reuse.
  - Added tap-to-detail dialog (`showDriveInsightDetails()`): tapping the Drive Insight card opens a MaterialAlertDialog showing the current data snapshot (RPM, coolant, voltage, fuel trim), an advisory notice, and a positive button that deep-links to the relevant tool (Diagnostics, Battery, Fuel Map, or Dashboard).
  - Added `cockpitInsightCard.setOnClickListener` so the card is actually tappable.
  - Added missing strings: `drive_insight_tap_details`, `drive_insight_advisory`, `drive_insight_snapshot`, `drive_insight_open_dashboard`, `drive_insight_open_diagnostics`, `drive_insight_open_battery`, `drive_insight_open_map`.
  - Added `NONE` destination enum for healthy/collecting states that don't need navigation.
- Resolved CI workflow conflict by adopting the agent branch's `build-apk.yml` with AAB support and secret validation.

## [3.20.3] - 2026-07-13 — Play Store Release Preparation

- Versioned the program as `3.20.3` (`versionCode 117`).
- Updated the release workflow to build and publish a signed Android App Bundle (`.aab`) alongside the QA APK, with APK/AAB signature verification and SHA-256 checksums.
- Added a Play Store release checklist covering privacy, Data Safety, foreground-service declaration, closed testing, 16 KB page-size verification, store assets, and hardware smoke tests.

## [3.20.2] - 2026-07-13 — Session History & UX

### Live Map accuracy and diagnostics
- Added a dedicated multi-select mode in Session History: select any number of CSV/JSONL sessions, share them together with Android's multi-file chooser, or delete them after one confirmation.
- Kept Compare 2 Logs as a separate workflow so batch file management cannot accidentally launch map comparison.
- Versioned the program as `3.20.2` (`versionCode 116`) and refreshed the README/release notes.
- Drive Insight now stays in place for healthy/collecting states, shows a current-data snapshot, and only deep-links to Dashboard, Diagnostics, Battery, or Live Map when an actionable condition is detected.
- Fixed gauge color rendering across Light/Dark themes: adaptive track, bezel, tick, text, and alpha-safe hub colors now stay readable for every gauge slot.
- Made output routing explicit: valid VINs use their own folder, while missing/placeholder VINs use `Downloads/TunerMapPro/General` and summary metadata records the routing decision.
- Reframed Live Map values as ECU fuel corrections instead of labeling positive trim as a currently lean mixture; measured Lambda is now shown separately when available.
- Added transient/load-step, unstable-trim, and measured-vs-commanded Lambda quality gates before a sample can affect the learned map.
- Reset debounce and stability history at petrol/gaseous fuel changeover so samples from one fuel cannot prime the other map.
- Prevented comparisons and correction exports when petrol and gaseous maps were learned from incompatible MAP/load-axis sources.
- Added per-cell STFT, LTFT, Lambda, spread, hit count, confidence, and lock diagnostics to the realtime API and AI export.
- Treated `0xFF` in unused secondary O2-trim bytes as unavailable without changing the valid SAE boundary behavior of the primary STFT/LTFT PIDs.
- Updated Live Map status feedback and all supported translations to describe correction direction without incorrectly claiming rich/lean combustion.
- Added regression coverage for correction components, Lambda stability, transient rejection, axis compatibility, and unused O2-trim sentinel handling.

## [3.20.1] - 2026-07-13 — Live Map & Installability Hotfix

- Fixed foreground Map Live Data reading a stale background-service store, including state restoration after Activity recreation.
- Restored Fuel System Status (`01 03`) to Simulation auto-detection and kept admitted MAP/loop/trim PIDs continuously polled so live learning does not appear frozen.
- Added a non-persistent `LIVE` cell preview while warm-up, loop-state, trim, and debounce safety gates settle; preview samples never contaminate correction export or API map data.
- Made Drive Insight interactive with a localized detail dialog and context-aware actions to Dashboard, Diagnostics, Battery Tester, or Fuel Map.
- Reworked Background Logging permission UX: notification access is requested only when starting a background session, denial no longer incorrectly disables a valid foreground service, and users can explicitly continue with a hidden notification.
- Added live background state to the compact header (`BG START`, `BG LIVE`, `BG RETRY`, `BG ERROR`) and Settings, including record count, notification visibility, persisted preference, channel-level blocking detection, and notification actions to reopen the app or stop logging.
- Fixed GitHub Actions publishing an unsigned release APK. Tag releases now require a persistent keystore from GitHub Secrets, verify the signed APK before publishing, and include a SHA-256 checksum; CI debug APKs are explicitly clean-install-only.
- Versioned the hotfix as `3.20.1` (`versionCode 115`) and added regression tests for simulator map prerequisites, map polling continuity, and Drive Insight routing.

## [3.20.0] - 2026-07-13 — Production Reliability & Secure Realtime API

### Transport and Session Reliability
- AUTO discovery is now covered by a hard 30-second deadline, prioritizes likely OBD adapters, and reports the transport that actually connected.
- AUTO no longer silently falls back to simulated telemetry; demo data is available only when Simulation is selected explicitly.
- Reconnect attempts use bounded daemon executors with deterministic shutdown, and transport I/O failures now activate reconnect instead of leaving an empty session running.
- Replaced permanent PID blacklisting with adaptive cooldown and automatic retry, while preserving null/quality columns in every record.
- Foreground-service logging now survives closing the Activity as intended.

### Fuel-map and Output Integrity
- Synthesized MAP is explicitly identified as `SYNTH_MAP` instead of being presented as an ECU MAP sensor value.
- Fuel-map learning now fails safe when coolant temperature or fuel-system loop state is unavailable; the live cursor remains active without storing an unsafe correction.
- Missing STFT/LTFT is logged as null/unavailable instead of a fabricated `0%` trim.
- Map imports are transactional, bounded to 512 KiB/1000 cells, range checked, and protected from malformed requests clearing existing data.

### API and Platform Security
- Added a per-install 128-bit API token. VIN, telemetry, map, stream, and DTC endpoints require Bearer or `X-API-Key` authentication; only `/api/ping` is public.
- DTC clear requests now open the same physical confirmation flow as the UI and return HTTP 202 Accepted.
- SSE delivery is non-blocking and client bounded so a slow network consumer cannot stall OBD polling.
- Disabled Android backup and global cleartext client traffic, removed the direct battery-optimization exemption request, and added no-cache/nosniff response headers.

### UI and Packaging
- The connection header/status strip now shows resolved adapter transport consistently after initial connect and reconnect.
- Replaced invalid downloaded font files with the Android system sans-serif family for deterministic rendering and smaller packaging.
- Unified the API preference key and added a tap-to-copy endpoint/token card in Settings.
- Versioned the app as `3.20.0` (`versionCode 114`) and verified installable QA packaging with APK Signature Schemes v1/v2/v3; production distribution still requires the owner's private release key.
- Added regression coverage for connection deadlines, PID recovery, API authentication, MAP safety/provenance, and reconnect behavior.

## [3.19.0] - 2026-07-13 — Reliable Session Intelligence & Output Schema v2

### Session Summary v2
- Session summaries now use the exact session ID (`<session>_summary.json`) even when logs are written through Android SAF; generic `session_summary (N).json` collisions are eliminated.
- Added `schema_version`, `app_version`, session identity, completion/checkpoint state, timestamps, duration, raw-file references, vehicle/fuel metadata, and connection/adapter/protocol metadata.
- Added per-column sample count, null count, coverage percentage, min/average/max, standard deviation, unit, and status/source counts.
- Added declared/observed/empty-column inventory and overall numeric coverage so a three-sample MAP cannot appear representative of an entire session.

### Accurate Trip Integration
- Replaced the fixed one-second assumption with real `elapsed_s` deltas and trapezoidal speed/fuel integration.
- Prefer standardized Engine Fuel Rate PID `0x5E` for fuel used, including idle consumption; fall back to speed/kmL only when the PID is unavailable.
- Added distance/fuel integrated-duration, integration-gap count, fuel source duration, method, and distance-weighted average km/L.
- Gaps longer than 30 seconds are explicitly counted and excluded instead of integrating stale values.

### Crash Recovery & Log Provenance
- Summary checkpoints are rewritten every 10 records with `complete=false`; graceful close finalizes the same file with `complete=true`.
- Summary write failures are non-fatal and can no longer stop CSV/JSONL logging.
- JSONL now includes compact `_quality` metadata for non-OK samples.
- CSV/JSONL include `map_value_source` (`0=missing`, `1=measured`, `2=synthesized`) so estimated MAP is never silently presented as sensor data.
- Added regression tests for elapsed-time integration, PID 0x5E fuel use, coverage/null accounting, MAP provenance, checkpoint recovery, and session-linked filenames.

## [3.18.0] - 2026-07-13 — Next-generation Scanner Intelligence & Wideband Integrity

### Connection, VIN & PID Discovery
- Improved AUTO transport handling across Bluetooth Classic, BLE, Wi-Fi, USB serial, and simulation paths, including clearer failure state and reporting of the transport that actually connected.
- Initialize the detected vehicle brand from VIN before DTC scans and API snapshots so brand-specific decoding is available immediately.
- Added centralized per-VIN PID support caching and targeted PID probing when ECU support bitmaps are missing or malformed.
- Expanded VIN/WMI and model-year profiles and the standardized Mode 01 catalogue while keeping live ECU detection authoritative.
- Fixed configured Custom PIDs disappearing from LPG-only sessions.

### Scanner & Settings UX
- Added an in-app Custom PID manager with add, edit, delete, formula validation, raw-byte testing, unit/range configuration, and dashboard assignment.
- Refined the connection/settings flow, adapter state, VIN/header layout, history rows, responsive cockpit layouts, and light/dark surface consistency.
- Improved DTC workflow presentation and decoding safeguards, with clearer scan state and results appropriate for a diagnostic scanner.
- Strengthened Battery Tester and Crank Test validation, status classification, and test coverage.
- Completed missing localized resources and repaired language-switch display consistency across supported languages.

### Wideband, AFR & Fuel Map Correctness
- Corrected SAE J1979 PID `0x34`: bytes A/B now decode measured lambda and bytes C/D decode wideband O2 sensor current in mA.
- Corrected PID `0x44` to a two-byte **Commanded Equivalence Ratio** and stopped presenting it as measured wideband lambda.
- Added separate Actual AFR and Commanded AFR calculations using the configured fuel's stoichiometric AFR.
- Commanded lambda can no longer drive Actual AFR, Density-Corrected AFR, vapor displacement, evaporative cooling, or corrected MAD calculations.
- Added Actual AFR, Commanded AFR, lambda source, and AFR quality columns to CSV/JSONL output and exposed both AFR values to dashboard gauge selection.
- Log replay now uses measured PID `0x34` only for lambda fallback; STFT/LTFT remain authoritative and PID `0x44` can no longer manufacture synthetic trim.
- Kept live fuel-map binning and correction logic independent from O2 sensor current and commanded lambda.

### Reliability & Validation
- Generalized batch parsing for multi-byte pseudo-PIDs such as the PID `0x34` current channel.
- Added regression coverage for transport resolution, logger configuration, VIN/PID discovery, DTC decoding, battery/crank classification, wideband parsing, AFR source integrity, and Live Map replay.
- Enabled Java core-library desugaring for consistent runtime behavior on supported Android versions.

## [3.17.0] - 2026-07-12 — UX Polish, Localization & Code Cleanup

### Home & Core UX
- Corrected bottom telemetry status strip visibility logic to display on all non-home tabs.
- Added warning confirmation modal prompt before manually clearing Diagnostic Trouble Codes (DTCs).
- Added button spamming protection that disables read/clear/VIN/readiness scanner buttons during active operations.
- Fixed theme settings selection loop recreations.
- Synced system theme preference changes dynamically to the Battery Tester graphic monitor.
- Added Slate-900 rounded contrast bubble tooltips behind the live telemetry graph cursor to ensure legibility in both light and dark modes.

### Code Quality & Lifecycle Safeguards
- Added thread interruption checks in replay parsing loops to completely prevent background thread leaks on screen rotations.
- Removed obsolete legacy layouts (`panelHomeLegacy` removing ~970 lines of unused XML).
- Cleaned up unused gradients, menu structures, and dead helper methods in ApiServer and MainActivity.

## [3.16.9] - 2026-07-12 — Interactive Live Graph

### Home scanner
- Added pause/resume control for the Home live graph without stopping logging.
- Added touch/drag inspection cursor with real RPM, Speed, and Boost values.
- Added tap-to-toggle legend controls for each graph series.
- Added explicit `LIVE`, `PAUSED`, and `NO LIVE DATA` feedback.

## [3.16.8] - 2026-07-12 — Detailed Multi-color Live Graph

### Home scanner
- Added a dedicated normalized multi-series Home graph for RPM, Speed, and Boost.
- Added clear cyan/blue, green, and purple line colors with a visible legend.
- Added rolling 60-second label, grid, time axis, and `LIVE` / `CONNECTING` / `NO LIVE DATA` state.
- The graph clears when the vehicle disconnects.
- Reduced the Scan FAB from 68dp to 58dp so it no longer dominates the bottom navigation.
- Kept Settings available from the front-page top-right gear while hiding the duplicate connection chip on Home.

## [3.16.7] - 2026-07-12 — Scanner-first Navigation + Responsive Gauges

### Scanner UX
- Replaced bottom Settings and Dashboard destinations with Live Data and Diagnostics.
- Bottom navigation now follows primary scanner workflows: Home, Live Data, Scan, Logs, Diagnostics.
- Disconnected state now explains how to begin scanning instead of showing a generic empty adapter value.
- Connecting state now reports `Negotiating protocol…`.
- GaugeView now calculates its height from measured width, keeping the circular gauge proportional across phone and tablet widths.

## [3.16.6] - 2026-07-12 — Modern Icon-only Scan Action

### Primary action
- Removed the old MaterialButton Start/Stop styling completely.
- Added a true icon-only Material FloatingActionButton in the persistent bottom navigation.
- Idle state uses a play icon with the primary color.
- Logging state uses a pause icon with the danger color.
- Added Material elevation and press/focus translation for a modern tactile interaction.

## [3.16.5] - 2026-07-12 — Professional Scanner Home

### First-page UX
- Added a dedicated Diagnostic Health card directly below Live Telemetry.
- Shows `No active fault codes` in the healthy state and the exact active fault count when DTCs exist.
- Shows contextual guidance: `Ready for a full scan` or `Tap to inspect and clear`.
- Made the entire Diagnostic Health card open the DTC Scanner.
- Kept live telemetry as the primary hierarchy: RPM, speed, coolant, voltage, boost, throttle, and total fuel trim.

## [3.16.4] - 2026-07-12 — Modern Primary Action + Balanced Status/Gauges

### UI polish
- Changed the persistent Start/Stop control to icon-only: play when idle, pause when logging, with primary/danger color states.
- Enlarged the top connection status chip, status dot, and status typography for a balanced automotive header.
- Enlarged the home status card and OBD protocol badge for clearer glanceability.
- Increased Dashboard GaugeView height to 340dp so the circular gauge uses the available card area instead of appearing undersized.

## [3.16.3] - 2026-07-12 — Refined Primary Action + Gauge Sizing

### UI polish
- Reworked the bottom Start control into a larger, elevated, solid-primary circular action with a play icon and high-contrast label; Stop uses the pause icon and danger color.
- Permanently removed Home and Start actions from the top toolbar because both are available in persistent bottom navigation.
- Increased the home OBD protocol badge typography, padding, and touch/readability height.
- Reduced all four Dashboard circular gauge containers from 320dp to 200dp so cards match their two-column width and no longer have excessive vertical space.

## [3.16.2] - 2026-07-12 — Persistent Navigation + Dense Live Telemetry

### Navigation
- Bottom navigation now stays visible on every panel, so Home can always return to the cockpit.
- Renamed Connect to Settings because it opens connection settings.
- Replaced the ambiguous center `+` with explicit START / STOP state text.
- Renamed More to Dashboard because it opens the live parameter dashboard.
- Retired the old live status strip to avoid competing bottom navigation surfaces.

### Live Telemetry
- Added compact Boost, Throttle, and combined STFT+LTFT readouts below the RPM trend.
- Kept RPM and speed dominant while filling unused space with actionable tuning data.
- Live values reset cleanly when the adapter disconnects.

## [3.16.1] - 2026-07-12 — Reference-Matched Navigation + Quick Access

### Fixed from visual review
- Added a fixed bottom navigation matching the supplied reference: Home, Connect, center Start/Stop action, Logs, and More.
- Removed the generic top toolbar on Home to eliminate duplicate connection/settings controls.
- Removed the second Connect/Disconnect button from the cockpit header; connection status is now display-only.
- Rebuilt Quick Access into two rows of three cards with icons, titles, descriptions, chevrons, and feature-specific accent colors.
- Added descriptions for Gauges Cluster, Live Graph, Dashboard, Trim Mapping, DTC Scanner, and Power Monitor so cards no longer look empty.
- Added extra bottom content clearance so the fixed navigation never covers the last Quick Access row.

## [3.16.0] - 2026-07-12 — Material 3 Automotive Cockpit UI

### Changed — Complete home layout redesign
- Rebuilt the home screen as a focused automotive cockpit inspired by the supplied `ui.png` reference.
- Added a compact ECU connection card and a 48dp one-tap connect/disconnect action.
- Added a prominent live telemetry card with RPM, speed, protocol, and an animated RPM trend graph.
- Added large coolant and voltage cards for at-a-glance driving visibility.
- Reorganized Dashboard, Gauges, Trim Map, DTC Scanner, Logs, and Battery into large two-column quick-access tiles.
- Added LIVE / GAUGES / TABLE / GRAPH / ALERTS shortcuts mapped to the existing feature panels.

### Material 3 / in-car usability
- Refined the dark palette to near-black navy surfaces with cyan primary, green success, amber warning, and red danger roles.
- Increased important tap targets and numeric typography, reduced decorative clutter, and strengthened contrast for night driving.
- Made the persistent live status strip horizontally scrollable so RPM, speed, voltage, boost, fuel, DTC, and Start/Stop remain usable on narrow phones.
- Preserved the existing single-Activity panel architecture and all OBD2 logging/diagnostic behavior.

## [3.15.0] - 2026-07-12 — AeroDensity Intelligence Physics + Scanner Fix

### Fixed — Air density math & honesty
- **MAD humidity physics** — stopped applying ambient RH at hot IAT. Absolute humidity (mixing ratio from ambient T/P/RH) is conserved into the manifold; Pv is also capped ≤ 98% of total pressure. Fix improves turbo / post-IC density accuracy in tropical climate.
- **OMD double-count of humidity** — now `ρ_dry × O2_mass_fraction (0.2314)` instead of total-density × dry-frac × vol-frac.
- **TMF / MAF Deviation tautology** — independent theoretical mass flow uses assumed VE (85% NA / 95% FI). Deviation vs measured MAF is finally meaningful for leaks/drift.
- **Density altitude** — NOAA-style pressure altitude + virtual-temp ISA offset (near 0 ft at ISA sea level).
- **Weather fetch failure wiped humidity** — last-good Open-Meteo cache is retained; failed refresh no longer forces silent 50% RH and fake AAD.
- **Weather never re-fetched while logging** — soft async refresh by 10 min TTL from the OBD loop (never blocks ELM).
- **CE / IC as “estimate”** — street cars only expose post-IC IAT; status stamped `estimate`.
- **Displacement default 1998 / 6000 RPM** — VE/TMF/PDI stamped `assumed` until user sets prefs (`pref_engine_displacement_cc` + `pref_engine_displacement_user_set`).

### Fixed — OBD2 scanner wiring for AeroDensity
- **Ambient Air Temp (0x46) missing from LPG poll set** — AAD fell back to 25°C / weather in default `lpgOnlyMode`. Now `getLpgPollSet(showAirDensity)` keeps Ambient, IAT, MAP, Baro, MAF, Lambda.
- **Blacklist protect** — IAT / Baro / Ambient no longer auto-removed after 3 fails (alongside MAP/RPM).
- **Quality columns in logs** — `derived_aad_quality`, `derived_baro_src`, `derived_rh_src`; sample status `ok|est|default|assumed|estimate`.
- **Phone sensors** — TYPE_PRESSURE / TYPE_RELATIVE_HUMIDITY registered when present.

### Tests
- New `AeroDensityTest` — SAE J1349 density+CF, DA≈0 at ISA SL, MAD abs-humidity, independent TMF, OMD, poll-set Ambient, sample quality emission.

## [3.14.0] - 2026-07-12 — Professional Fuel-Trim Analyzer (OBD2 shop-grade)

### Fixed
- **Thai OK → UNKNOWN** — Localization rebuilt FuelTrimResult from the string status only. Thai `สมบูรณ์ (PERFECT)` never contained the word "OK", so `getVerdict()` became UNKNOWN after display. Status now keeps the enum through localization.
- **UNKNOWN painted as RICH/red** — UI colour fell through to danger when status was not OK/LEAN. Now uses discrete roles: OK green, LEAN blue, RICH red, UNSTABLE amber, UNKNOWN muted grey.
- **Analyzer ignored open-loop / cold engine** — Live strip could shout LEAN/RICH while loop was open or ECT < 80 °C. Gate forces UNKNOWN with explicit reason.
- **Panel only when STFT present** — If STFT dropped momentarily UI froze on last sample. Analyses with STFT-only / LTFT-only / Bank2 now update and show "—" for missing side.
- **±10% STFT+LTFT "OK"** — Too soft for petrol prep (false PERFECT at LTFT +8). Replaced with LTFT-primary thresholds: petrol ±5% OK / ±8% lean-rich; LPG ±8% OK / ±12% lean-rich.

### Added
- Bank1+Bank2 fusion (01_06/07/08/09) with imbalance flag when |B1−B2| ≥ 5%.
- STFT short-window std-dev → UNSTABLE verdict (hold load, don't log map cells).
- Confidence % and gate reason (OPEN_LOOP / COLD / PARTIAL / NO_DATA / DIESEL).
- EN + TH strings for all new states; advice appends LTFT magnitude for map adjustments.
- `LPGAnalyzerTest` coverage for CL gate, cold, thresholds, bank merge, localization enum preserve.

### Dashboard
- `tuning_status_format` now shows STFT/LTFT text, confidence, professional advice.
- Reset STFT history window on Start logging (no bleed across fuel sessions).

## [3.13.0] - 2026-07-12 — Live Map Right-Cell + AI Log Columns + Import/Compare

### Fixed — Live Map always tracks the correct cell
- **Active cell lost on `syncFromStore`** — UI only copied cell maps, so the live neon highlight froze while driving. Snapshot now carries `activeRpmCell` / `activeMapBin`; highlight follows the real FLOOR bin every tick.
- **Highlight required accepted samples** — Debounced / open-loop samples still update the cursor so position is never lagging behind RPM/MAP.
- **Debounce incorrectly accepted first transients** — While the window was still filling, one-off cells were allowed. Require ≥2 same-cell sightings before storing.
- **Double-push inflated hit counts** — `ApiServer.setLatestData` + `MainActivity.updateFuelMap` both called `pushSample`. API path no longer writes the store; sole writers are `LoggerService` (background) and the in-process loop (foreground). UI only snapshots.
- **Hard-lock was cosmetic** — `MAX_HITS=20` only changed alpha; values kept diluting forever. Store now rejects after lock.
- **Snapshot shared mutable TrimData** — Deep-copies on snapshot so later hits can't mutate API/UI reads mid-flight.

### Added — Logs optimized for AI analysis
- **`MapSampleMeta`** — Single enrichment helper: bin, closed-loop, warm, trim total, accept/reject reason.
- **CSV/JSONL map AI columns** (every record):
  `map_rpm_cell`, `map_axis_value`, `map_axis_source` (1=MAP / 2=LOAD),
  `map_trim_total`, `map_closed_loop`, `map_warm`, `map_gated`, `map_accepted`,
  `map_reject_code` (0=ok, 3=open-loop, 4=cold, 6=debounce, 7=locked, …).
- Agents can rebuild LiveMapStore-quality maps from the log alone without re-deriving bin/gate rules.

### Fixed / Improved — Import + Compare
- **POST `/api/map/import`** — Normalizes cell keys through `MapBinning` (legacy `2000_40` → `2000_40.00`); supports `replace` flag and compact `cells:[{rpm,map,avg,hits,fuel}]` array format for AI import; returns counts + overlapping cell count.
- **GET `/api/map` + `/api/map/summary`** — Export `activeCellKey`, `activeRpmCell`, `activeMapBin`, `axisSource`, `totalAcceptedSamples` for real-time AI replay.
- **Compare / Review replay** — Uses `map_accepted` + binned map columns when present so replotted maps match live store exactly; ignores Bank-2 trim columns that previously overwrote Bank-1 headers; in-process `LiveMapStore` so compare baseline + live LPG share one source.
- **History Compare 2 logs (Petrol vs LPG)** — still plots both fuel sides on the same FuelMapView for Deviation / Correction (multi-URI path unchanged; data quality now matches live).

### Tests
- New `LiveMapStoreTest` (binning, debounce cursor, lock, import compare, meta).
- Full unit pass: LiveMapStore + LogReplayParser + ApiServer.

## [3.12.0] - 2026-07-11 — Gauge/Dashboard Localization + Long-press to Clear PID slots

### Fixed — Localization (Thai/English now switches properly)
- **"แตะเพื่อเพิ่ม (Tap to Add)" bilingual text** — The placeholder for empty gauge/dashboard/graph slots was a fixed bilingual literal that never switched with the app language. Now uses `R.string.tap_to_add` (EN: "Tap to Add", TH: "แตะเพื่อเพิ่ม") in `setupDashboard()`, `setupGauges()`, and `setupGraphs()`.
- **PID Selection Dialog All Hardcoded Thai** — The dialog title ("เลือก OBD2 Parameter"), subtitle, search hint ("ค้นหา Parameter... (Search)"), and "Hide/Disable" option were all hardcoded Thai. Now uses string resources that switch with locale.
- **"Live Graphs" header** — Hardcoded in `activity_main.xml`. Now uses `@string/live_graphs`.
- **"Data 1-4" dashboard placeholders** — Hardcoded in `activity_main.xml`. Now uses `@string/tap_to_add`.
- **"Records: N" counter** — Hardcoded `"Records: " + count` in 4 Java locations. Now uses `getString(R.string.records_count, N)`.

### Added — Long-press to Clear/Remove PID
- **Long-press clears slot** — Gauges, Dashboard stat cards, and Graph cards: long-press now clears the PID slot to "none" (hidden). Previously click and long-press both opened the same PID picker dialog with no direct way to remove a PID.
- **`clearPidSlot()` method** — Saves "none" to SharedPreferences, refreshes the UI, and shows a Toast confirmation ("Slot N cleared").
- **"💡 Long-press to clear" hint** — Added a footer hint in the PID selection dialog so users know they can long-press to remove a PID.

## [3.11.0] - 2026-07-11 — Pro Scanner Bugs + Icon Visibility + Compact Start Button

### Fixed — Pro Scanner (4 bugs)
- **In-Use Performance data hidden** — Display condition only checked PIDs 0D/0E (ignition/trip count). If a vehicle supported only PID 0F (distance/time), the entire performance block was silently skipped. Extended condition to also check `distanceSinceClearKm` and `timeSinceClearMin`.
- **Enhanced Scan only worked for 4 of 23 brands** — `scanEnhancedForBrand()` matched string names ("toyota", "honda", etc.) but `getBrandName()` returns display names like "Mercedes-Benz" — 19 brands always got "No codes found". Rewrote to accept `VinBrandDetector.Brand` enum directly. Mapped all 23 brands: Toyota/Lexus/Honda/Mitsubishi/Mazda/Suzuki/Hyundai/Kia/Volvo/Isuzu→Mode 21, Nissan→Mode 1A, Ford→Mode 27, Chevrolet→Mode 2C, BMW→21+22, Mercedes/BYD/GWM/NETA/AION/DEEPAL/MG/Tesla→Mode 22 (UDS).
- **Mode 21/61 sent twice for unknown VINs** — Toyota and Honda both mapped to Mode 21/61; when brand=null (unknown VIN), the same command was sent twice. Restructured with `LinkedHashSet` so each unique mode/header pair is sent only once.
- **Mode 08 dialog showed all 14 tests unconditionally** — `querySupportedTests()` existed but was never called; bitmap parser only read 8 bits (TIDs 01-08). Dialog now queries supported TIDs first and filters the list. Bitmap parser fixed to read all data bytes. Falls back to showing all with warning if query fails.

### Fixed — Icon Visibility
- **App logo on blue hero header** — Gauge arc and glow were `#38BDF8` (light blue) on a `#2563EB→#1E3A8A` blue gradient — nearly invisible. Changed to `#FFFFFF` (white). OBD plug accent `#22D3EE` (cyan) changed to `#FBBF24` (amber, matches needle).
- **Hero subtitle** — `#BFDBFE` (light blue) on blue gradient had marginal contrast. Changed to `#E0F2FE` (pale blue).

### Fixed — Start Button
- **Start button too wide, shrinking fuel badge** — Reduced padding 14dp→8dp, icon size 14dp→12dp, icon padding 4dp→2dp. Added `maxWidth="90dp"` to cap button width.

## [3.10.0] - 2026-07-11 — DTC Scan, VIN Detection & Deep PID Scan (8 bugs) + DTC UI Overhaul

### Fixed — Fuel & AFR
- **Fuel badge showing raw enum name** — `applyFuelTheme()` set the correct label, then `headerFuelMode.setText(mode.name())` overwrote it with "PETROL_95". Removed the redundant setText.
- **AFR gauge stuck at zero** — `setupGauges()` skipped derived keys (no PIDDefinition) leaving the gauge at 0-100 range. Added `DerivedGaugeConfig` with proper ranges for 27 derived keys (DCAFR: 8-20, fuel economy: 0-30, boost: -100 to 200, etc.).
- **LPG fuel map shows 0% trim** — `LogReplayParser` only read STFT+LTFT; LPG vehicles often have only lambda. Added lambda-based trim fallback: `trim = (lambda - 1.0) * 100`.

### Fixed — PID Detection
- **Missing bitmap banks** — Only 4 of 8 SAE J1979 bitmap banks were queried (`0100`–`0160`). PIDs 0x81–0xFF never detected via live query. Extended to all 8 banks (`0100`–`01E0`).
- **Force-include set too narrow** — Only 7 core PIDs force-included. Expanded to 17: added speed (0D), MAF (10), throttle (11), baro (33), voltage (42), and DPF PIDs (7A, 7B, 85, 8B, 8C).

### Fixed — DTC Scanning
- **ECU names show wrong brand** — Single `ECU_NAMES` LinkedHashMap — Nissan overwrote Toyota at shared CAN ID 0x7E0. Split into per-brand maps with `setBrand()` method.
- **In-process path didn't load brand DTC DB** — Added `DtcDatabase.initForVin()` + `DtcReader.setBrand()` calls in the in-process VIN reading path.
- **Protocol not re-locked after DTC scan** — Added `0100` re-probe + 200ms settle after `ATSP0` restore.
- **NO DATA with space not filtered** — Changed `clean.equals("NODATA")` to `clean.matches("(?i)NODATA|NO DATA")`.

### Added — DTC UI Overhaul
- **Vehicle info card** — Shows detected brand + VIN after scanning.
- **Loading state** — ProgressBar spinner during DTC scan.
- **Empty state** — Green checkmark "All Clear!" card when no DTCs found.
- **Deep scan badge** — Orange pill "🔬 DEEP SCAN — All Protocols".
- **Button grouping** — "DTC Actions" and "Vehicle Info" section labels.
- **Visual consistency** — All buttons converted to MaterialButton with 12dp corner radius. Card radius 2dp→8dp.

### Fixed — Pre-existing Test Failures
- **FreezeFrameReaderTest** — Count-byte heuristic was too strict, rejecting valid count bytes with trailing padding. Reverted to always skipping count byte (SAE J1979 standard).
- **AuditImprovementsTest** — `isClosedLoop()` default changed from false to true for CSV files without loop-state columns.

## [3.9.0] - 2026-07-11 — Realtime AI Agent Pipeline & Map Accuracy Overhaul

### Fixed — Fuel Map Bin Accuracy (3 Data Copies → 1 Source of Truth)
- **RPM Binning Mismatch (UI vs API)** — `FuelMapView` used FLOOR-based binning (749→500) while `ApiServer` used `Math.round` (750→1000), causing the AI agent to see data in a different cell than the user saw on screen. Created `MapBinning.java` as the single source of truth — all three paths (UI, API, SSE) now share the same FLOOR-based binning logic.
- **API Server Had No Debounce** — The UI filtered transient noise with a 4-sample sliding-window debounce, but the API path (`ApiServer.updateLiveMap`) had none — AI agents saw noise spikes that the user never saw. Moved debounce into `LiveMapStore` so all consumers get the same filtered data.
- **Race Condition in sessionPetrolData Copy** — Every record, `MainActivity` did `sessionPetrolData.clear(); sessionPetrolData.putAll(...)` which could leave the map empty if an API read happened between clear and putAll. Eliminated by replacing with immutable `LiveMapStore.snapshot()` reads.
- **CSV Export Header Wrong** — `ApiServer.handleMapExport` still used `"T.inj \\ RPM"` despite the Y-axis being changed to MAP kPa in v3.8.1. Fixed to `"MAP kPa \\ RPM"`.
- **Filter PIDs Dialog Not Working** — `BottomSheetDialog` was created without an explicit theme, inheriting `Theme_DeviceDefault` which doesn't support Material3 components (`MaterialButton`, `BottomSheetDialog`). This caused a silent crash when opening the dialog — identical root cause to the v3.7.5 AirDensity dialog crash. Fixed by specifying `R.style.AppTheme` and adding a try-catch fallback to `AlertDialog`.

### Added — Realtime AI Agent Pipeline (SSE Push + Zone Analysis)
- **`LiveMapStore.java` (New)** — Single source of truth for fuel-map trim data. Thread-safe write path (`pushSample`), immutable read path (`snapshot()`), delta path (`deltaSince()` for SSE), with built-in debounce and closed-loop/temp gating. Replaces the three disconnected copies (FuelMapView + sessionPetrolData + ApiServer).
- **`MapBinning.java` (New)** — Unified binning logic. `binRpm()` (FLOOR-based), `binMap()` (closest-bin), `cellKey()` (canonical format). All map consumers now delegate here.
- **SSE `map_update` Event** — AI agents connected via `/api/stream` now receive `event: map_update` per record with the current cell, trim averages, hit counts, and deviation — no polling needed.
- **SSE `map_summary` Event** — Every 5 records, `event: map_summary` pushes aggregated stats (cell counts, average deviation, max deviation cell) so AI agents get a rolling overview without calling `/api/map`.
- **`/api/agent` Zone Analysis** — The agent endpoint now includes a `zones` object breaking the map into 4 zones (idle/cruise/acceleration/fullLoad) with per-zone avg deviation, avg hits, and confidence level (HIGH/MEDIUM/LOW/NONE).
- **`/api/agent` Hotspots** — The agent endpoint now includes a `hotspots` array — the top 20 cells with |deviation| > 5%, sorted by severity, each with RPM, load, deviation, hits, verdict (LEAN/RICH), suggested correction percentage, and confidence.
- **`/api/agent` Snapshot Cache** — The agent endpoint now caches the `LiveMapStore` snapshot for 500ms instead of recomputing the full map analysis on every request — significantly reduces latency for frequent AI polling.
- **AeroDensity API Status Indicator** — The AeroDensity Intelligence dialog now shows the Open-Meteo weather API connection status (Connected ✓ / Disconnected ✗), data source (Open-Meteo API / Android Sensor / Default), and the age of the last successful fetch ("just now" / "X min ago" / "X hr ago").

### Changed — Architecture
- **3 Map Data Copies → 1 `LiveMapStore`** — Previously: (A) `FuelMapView.petrolData/lpgData` with FLOOR + debounce, (B) `MainActivity.sessionPetrolData/LpgData` clear+putAll copy, (C) `ApiServer.petrolMap/lpgMap` with ROUND + no debounce. Now: all reads come from `LiveMapStore.snapshot()`.
- **`FuelMapView.TrimData` removed** — Replaced by `LiveMapStore.TrimData`. `FuelMapView` data maps now use `LiveMapStore.TrimData` directly so snapshots can be copied without conversion.
- **`ApiServer.MapTrimData` removed** — Replaced by `LiveMapStore.TrimData`. All API endpoints (`/api/map`, `/api/map/summary`, `/api/map/export`, `/api/map/import`, `/api/agent`) now read from the store.
- **`AirDensityMonitor` Status Getters** — Added `isWeatherValid()`, `getLastWeatherFetchMs()`, `getWeatherSource()`, `getWeatherWindKmh()` for UI display of API connection status.

## [3.8.1] - 2026-07-10
### Fixed
- **Fuel Map Y-Axis Wrong (T.inj → MAP kPa)** — The fuel map grid Y-axis was using a non-linear "injection time" (T.inj) conversion of MAP, not the actual MAP (kPa) value. Data was placed in wrong cells because the `mapLoadToTinj()` function distorted the sensor readings. Changed to use MAP (kPa) directly with bins: 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 120, 150, 200, 250. Now data matches what the tuner sees on the gauge.
- **Compare Map From Log File Not Working** — `LogReplayParser.isClosedLoop()` defaulted to `false` for CSV files without a fuel system status or `loop_status` column, skipping ALL rows and showing "No valid tuning points found." Changed default to `true` (matching the live logging path) so all rows plot when loop state is unknown.
- **Logs Not Saved in VIN Subfolder (Background Mode)** — `LoggerService` guarded VIN reading with `config.vin == null || isEmpty()` but the config default is `"UNKNOWN"` (not null/empty), so the real VIN was never read in background logging mode. Logs always went to the root folder instead of per-VIN subfolders. Fixed by treating `"UNKNOWN"` as unset.
- **Fuel Mode Resets to Petrol on Disconnect/Stop** — `restoreConfigPrefs()` forced `fuelSpinner.setSelection(PETROL)` when `!running`, ignoring the saved `pref_fuel_position`. Now always restores from SharedPreferences. Also removed the hard-coded `setSelection(2)` in `setupListeners()` that fired the listener and overwrote the saved value before restore ran.
- **Bluetooth Device Selection Resets on Disconnect/Stop** — `refreshBluetoothDevices()` always set `setSelection(0)`, discarding the user's pick. Now saves the selected device MAC to SharedPreferences and restores it on refresh. Added `OnItemSelectedListener` to persist on every change.
- **API Sensor Key Collision (Data Loss)** — `GET /api/data` and SSE used sensor display name as JSON key. Multiple sensors share names ("Fuel Economy", "Turbo Boost") → `JSONObject.put` silently overwrote earlier values, losing air density and derived sensor data. Changed to use `pidKey` (e.g. `"01_0C"`, `"derived_aad"`) as key, and emit an array of `{pidKey, name, value, unit, status}` objects.
- **API Missing Session Metadata & Connection Status** — `/api/data` now includes `vehicleBrand`, `vin`, `recordCount`, `adapterConnected`, `transportMode`, `sessionDurationMs`. `/api/status` includes adapter connection state. New `setAdapterConnected()`, `setTransportMode()`, `resetSession()` methods wired from LoggerService.

### Added
- **`GET /api/agent` Endpoint** — Aggregate endpoint returning everything an AI Agent needs in one HTTP call: session status, detailed sensor data (pidKey/name/value/unit/status), fuel map summary, and DTC codes with severity. Eliminates the need for multiple round-trips.
- **DTC Severity in API** — `GET /api/dtc` and `/api/agent` now include `severity` (CRITICAL/WARNING/INFO) for each DTC code, computed via `DtcCode.getSeverity()`.

### Changed
- **"LIVE SESSION METRICS" → "RECORDING STATUS"** — Renamed the confusing session metrics card label to "RECORDING STATUS (Duration • Records • Rate)" so its purpose is clear at a glance.

## [3.8.0] - 2026-07-10
### Fixed — Logger Random Stop (Comprehensive Fix)
- **'NO DATA' Treated as Fatal — False PID Blacklisting** — The ELM327 response `"NO DATA"` (a normal response meaning "ECU has no value for this PID right now") was in the `FATAL_RESPONSE_MARKERS` list. This caused PIDs returning `NO DATA` at idle/startup (RPM=0, speed=0, fuel trims in open loop) to be permanently blacklisted after only 3 consecutive failures, depleting the PID list until the batch was empty. Removed `"NO DATA"` and `"STOPPED"` from fatal markers — they are now treated as normal null results.
- **In-Process Connection Watchdog Falsely Killing Logger** — The 20-second watchdog (in-process mode) was only canceled by `LoggerService` callbacks, which never fire in in-process mode. So if connect + VIN read + DTC scan + PID detection took >20s (common on slow clones), the watchdog killed the logger mid-connect. Now cancels the watchdog immediately when `driver.connect()` succeeds. Timeout also raised from 20s → 45s.
- **Stale `isConnected()` Flag — Dead Socket Never Detected** — `BaseDriver.isConnected()` returned a stale `true` when the Bluetooth/WiFi socket silently died (BT radio interference, adapter sleep, half-open TCP). The logger's reconnect logic never triggered. Added `trackResponseLiveness()` in `BaseDriver`: after 5 consecutive empty/null responses, `connected` is set to `false`, forcing the reconnect path. Implemented in `SerialDriver` (Bluetooth) and `WiFiDriver`.
- **`isPaused` Stuck True — Diagnostic Features Freeze Logger** — The static `isPaused` flag could get stuck `true` if a diagnostic thread (DTC scan, battery test) was interrupted mid-operation, silently freezing the logger loop. Now explicitly reset to `false` in `stopLogging()` and `onDestroy()`.
- **Core PIDs Permanently Blacklisted** — Runtime PID blacklisting could remove essential PIDs (RPM, Speed, Coolant, MAP, Load) and even deplete the list to zero. Now protects core PIDs from blacklisting and enforces a minimum floor of 3 PIDs.
- **Reconnect `connect()` Could Hang Forever** — The reconnect path inside the logging loop called `driver.connect()` directly with no timeout, stalling the loop indefinitely on a dead adapter. Now wrapped in a `Future.get(30s)` timeout. Initial connect timeout also raised from 15s → 30s.

## [3.7.9] - 2026-07-10
### Fixed
- **Air Density Dialog Top Cut Off** — The full-screen dialog's content started at the screen edge, hidden behind the status bar. Added window insets padding so content starts below the status bar properly.
- **Air Density Toolbar Icon Re-arranged** — Moved the Air Density icon to sit directly next to the Settings icon (right side). New right-to-left order: Settings → AeroDensity → START/STOP → Status → Home.

### Changed
- **Removed "Banks iDash" Branding** — All user-visible references to "Banks iDash" have been removed from the UI and Java comments across the entire codebase.
- **Renamed Air Density Center → "AeroDensity Intelligence"** — The Air Density Center dialog, dashboard panel label, settings description, and toolbar content description now use the new name "AeroDensity Intelligence".
- **Air Density Dialog Icon Fixed** — Replaced the mismatched `ic_speed` icon in the dialog header with the correct `ic_air_density` icon.

## [3.7.8] - 2026-07-10
### Fixed
- **Logger Randomly Stops — retryCount Accumulation Bug** — The `retryCount` counter in both `LoggerService.runLogger` and `MainActivity.runLogger` (in-process mode) was incremented on *any* exception during the logging loop but only reset to 0 when a reconnection occurred (when `isConnected()` returned false). During steady-state operation, the driver's `isConnected()` flag stays true even when transient errors occur (read timeouts, momentary I/O glitches, NPEs from derived-sensor computations), so the counter was never reset. Over a long drive, 11 scattered transient errors would accumulate and permanently kill the logger (`"Logger disconnected permanently"`), requiring a manual restart. Fixed by resetting `retryCount = 0` after every successful `writeRecord()`, so transient blips don't accumulate across a long session.
- **Derived-Sensor NPEs Feeding Retry Counter** — `AirDensityMonitor.compute()` and `computeAdvanced()` were called unprotected in the logging loop. Any NPE from a null batch value threw straight into the catch block, incrementing the retry counter. Both calls are now wrapped in individual try/catch blocks in both LoggerService and MainActivity, logging the error as non-fatal without feeding the retry counter.
- **Non-IO Exceptions Counting Toward Retry Cap** — The catch block in both logging paths now distinguishes connection/IO errors (`IOException`, `SocketTimeoutException`, `SocketException`) from data-parsing/derived-sensor errors. Only connection errors increment `retryCount`; non-IO exceptions are logged as warnings and the loop continues without counting toward the permanent stop threshold.

## [3.7.7] - 2026-07-10
### Fixed
- **Toolbar Button Overlap (START/STOP + Air Density)** — `btnHeaderAirDensity` in `activity_main.xml` was anchored `layout_toStartOf="@id/btnSettings"`, the same anchor as the `fabLog` START/STOP button, causing both views to stack at the same position in the `RelativeLayout`. The Air Density icon overlapped the START/STOP button making both unreliable to tap. Fixed by chaining `btnHeaderAirDensity` to `layout_toStartOf="@id/fabLog"` so the right-to-left order reads: Settings → START/STOP → Air Density → Status → Home.
- **Wrong Icon for Air Density Button** — The `btnHeaderAirDensity` toolbar button (opens Air Density Center) was using `@drawable/ic_speed` (a speedometer/gauge icon). Replaced with a new dedicated `ic_air_density.xml` wind/air-flow icon (three flowing curves) that matches the feature's purpose.
- **Weird Hero Logo on Home Screen** — The Home "TUNER CONTROL PANEL" hero header `ImageView` used `@drawable/ic_launcher_foreground`, a 108dp-viewport vector designed for the adaptive icon system (~18% safe-zone padding). Rendered inside a plain 56dp `ImageView`, the gauge/car/OBD artwork occupied only ~55% of the view, appearing tiny and off-center. Created a dedicated `ic_app_logo.xml` (72dp viewport, no safe-zone padding) so the artwork fills the `ImageView` correctly. The launcher icon is untouched.
- **Startup NullPointerException on null DataRecord** (from 3.7.6) — `valueByKey()` dereferenced `latestDataRecord` which could be null at startup. Added null guards to `onRecord`, `updateDashboard`, and `updateFuelMap`.

## [3.7.5] - 2026-07-10
### Fixed
- **App Crash Fixed when opening Air Density Center UI** — Fixed a crash caused by inflating Material 3 components (`MaterialCardView` and `MaterialButton`) inside the system `Theme_DeviceDefault_Light_NoActionBar` dialog theme. Updated `showAirDensityCenterDialog()` to use `R.style.AppTheme` (descendant of Material3 DayNight NoActionBar), ordered `show()` before UI updates so dialog views populate reliably, and added safe exception handling.

## [3.7.4] - 2026-07-10
### Added
- **Separate Dedicated Air Density UI Center Accessible on Front Page** — Created a standalone full-screen Air Density UI dialog (`dialog_air_density_center.xml`) showcasing all 12 Air Density metrics (AAD, MAD, BAD, Density %, SAE J1349 CF, Density Altitude, Volumetric Efficiency, Compressor Efficiency, Intercooler Effectiveness, Power Density Index, Water Vapor Grains) with live weather summary and manual refresh. Easily accessible directly from the front page via the new top-bar Air Density button (`btnHeaderAirDensity`) or the "OPEN SEPARATE UI" button on the dashboard Air Density card.

### Fixed
- **Live Data Logger Random Stop Fixed** — Periodic Mode 01 PID 01 continuous DTC check previously ran asynchronously on a separate executor while the main logging loop queried PID batches concurrently on the same ELM327 socket, causing socket I/O collisions and premature connection drops. The DTC check now runs synchronously on the main logging thread between batches, `maxRetries` is increased to 10, and transient query errors retry without closing the connection prematurely.
- **PID ID Filter in Live Data Logger Fixed** — SharedPreferences mutation of the same `Set<String>` instance prevented filter updates from saving/re-rendering properly, custom PIDs were missing from the filter list, and dismissing the filter dialog cleared readings without repopulating. Fixed by using fresh HashSet copies, including custom PIDs via `PIDCatalogue.getAllWithCustom`, and instantly re-rendering the latest recorded sample when toggling or applying PID filters.
- **Air Density UI Panel Visibility & Setting Toggle Fixed** — Toggling the Air Density setting in Settings previously required an app restart or logging session start to take effect. Added instant toggle listener to update configurations immediately, populate the Air Density panel with live or fallback `AirDensityMonitor` calculations right away, and fixed loading of `pref_air_density`.
- **Engine Gauges Cluster UI Lag / Duplicate Peak Telemetry Removed** — Removed the redundant "Session Peak Telemetry" (`gaugeReadingsContainer`) list from the Gauges tab (`panelGauges`). Previously, rendering 20–50+ PID cards concurrently with 4 animated dial gauges and 5 line charts on every OBD2 sample caused significant UI thread lag and frame drops. Removing this duplicate grid allows the Gauges tab to dedicate 100% of UI thread resources to smooth dial and graph animations (full telemetry remains accessible on the Live Stream / Logs tab).

## [3.7.3] - 2026-07-10
### Fixed
- **Logger stops randomly / connection timeout** — `airDensityMonitor.refreshWeatherSync()` was called every 0.5s inside the logging loop. On slow networks or no GPS, this blocked the logger thread, causing connection timeout and auto-stop. Now weather refresh only happens at init (cached 10 min internally via TTL). Affects both in-process and background LoggerService
- **PID Filter not working** — `visiblePidsFilter` initialized as empty Set caused ALL PIDs to be filtered out (nothing displayed). Fixed: null/empty = show all, filter only active when user explicitly selects PIDs. Added `pidFilterActive` flag + saved to SharedPreferences
- **Redundant gauge readings** — `gaugeReadingsContainer` in Gauges tab duplicated the same PID data as Live Readings in Logs tab, causing double UI load. Now only updates if the container is visible

## [3.7.2] - 2026-07-10
### Added
- **Air Density Dashboard Panel** — Dedicated UI panel in Dashboard tab showing all 12 air density values in a symmetric 4x3 grid: AAD/MAD/BAD, Density%/Density Alt/SAE CF, OMD/Comp Eff/IC Eff, VE/PDI/Grains. Panel auto-shows when Air Density is enabled in Settings, hides when disabled. Weather info (RH, temp, baro) shown in panel header

## [3.7.1] - 2026-07-10
### Added
- **PID Filter for Live Readings** — New "Filter PIDs" button in Logs tab with bottom sheet dialog. User can select which PIDs to display via checkbox list with search. Reduces UI load on slow/cheap OBD2 adapters where 50+ PID cards (raw + 22 derived air density) caused lag and freezes
- **Derived Sensors Hidden by Default** — AAD/MAD/BAD, compressor efficiency, intercooler effectiveness, etc. are hidden by default in Live Readings. Toggle button to show/hide derived sensors. Filter persists in SharedPreferences
- **Air Density Checkbox in Settings** — New toggle in Settings UI to enable/disable Air Density (AAD/MAD/BAD) calculation. Was computed automatically before but had no UI toggle
- **Air Density in In-Process Mode** — Air density computation (AirDensityMonitor) now works in both in-process and background service logging modes. Previously only ran in background LoggerService

### Fixed
- **Fuel Mode Switch Bug** — `fuelSpinner` was NOT disabled during logging, so user could change fuel mid-session. The running logger thread used a local config copy — the change had no effect, and the UI showed a different fuel than what was actually being logged. Now `fuelSpinner` is properly disabled by `setConfigUiEnabled(false)` during logging
- **Default Fuel Selection** — Was `setSelection(1)` which maps to NGV (not Petrol!). Fixed to `setSelection(2)` = Petrol. Header fuel mode badge and fuel map mode now update on fuel selection change

## [3.7.0] - 2026-07-10
### Air Density System + Multi-Fuel Support — Banks iDash style + beyond

### Added — Banks iDash Style Air Density (AAD/MAD/BAD)
- **WeatherProvider** — Fetches live weather data from Open-Meteo API (free, no API key required). Provides relative humidity, ambient temperature, sea-level pressure, and wind speed via GPS location. Cached for 10 minutes. Critical for air density calculations since OBD2/SAE J1979 does not define a humidity PID
- **AirDensityMonitor** — Central coordinator merging data from 3 sources in priority order: OBD2 PIDs → Android phone sensors → Weather API → defaults. Feeds latest OBD2 batch values and computes all density formulas
- **DerivedSensors air density methods**:
  - `airDensityKgM3()` — Ideal gas law with Magnus vapor pressure formula: ρ = (Pd/(Rd×T)) + (Pv/(Rv×T))
  - `ambientAirDensity()` (AAD) — lbs/1000ft³ using baro PID 0x33 + ambient temp PID 0x46
  - `manifoldAirDensity()` (MAD) — lbs/1000ft³ using MAP PID 0x0B + IAT PID 0x0F
  - `boostAirDensity()` (BAD) = MAD - AAD — additional density from forced induction
  - `airDensityPercent()` — compared to SAE J1349 standard (72.2 lbs/1000ft³)
  - `densityAltitudeFt()` — density altitude using pressure/temperature/humidity
  - `saeJ1349CorrectionFactor()` — normalizes horsepower to standard conditions
  - `grainsH2O()` — absolute humidity in grains per lb dry air

### Added — 10 Advanced Formulas Beyond Banks iDash
- **AdvancedAirDensity.java** — All formulas support petrol, E10, E20, E85, LPG, NGV, diesel B7, diesel B20
  1. **OMD (Oxygen Mass Density)** — Isolates combustible O2 mass (not just total air). Banks shows total density, but N2/Ar/CO2 don't combust
  2. **CE (Compressor Efficiency)** — Isentropic vs actual temperature rise. 65-78% normal, <55% = overspeed/choke
  3. **IC-EFF (Intercooler Effectiveness)** — 70-85% good, <60% upgrade needed
  4. **VE (Volumetric Efficiency)** — MAF vs theoretical flow (RPM/120 for 4-stroke). Cross-validates MAF sensor
  5. **DCAFR (Density-Corrected AFR)** — Humidity-corrected true combustion mixture
  6. **TMF (Theoretical Mass Flow)** — Independent MAF cross-check. Detects sensor drift
  7. **LVD (LPG/CNG Vapor Displacement)** — Critical for gaseous fuel tuning. Diesel returns 0 (DI)
  8. **ECC (Evaporative Cooling Correction)** — Charge cooling from fuel evaporation. Diesel/NGV = 0
  9. **PDI (Power Density Index)** — Single dimensionless number tracking engine power output
  10. **Dynamic SAE CF** — Both J1349 and J607 correction factors with delta comparison

### Added — Multi-Fuel Support (All 8 Thai Fuel Types)
- **FuelMode enum expanded**: LPG, NGV, PETROL (95 E10), PETROL_91 (E10), E20, E85, DIESEL (B7), B20
- **FuelProperties.java** — Central database with correct AFR, density, LHV, ethanol%, thermal efficiency per fuel:
  - Gasohol 91/95 (E10): AFR=14.23, 10% ethanol, density=741 g/L
  - Gasohol E20: AFR=13.75, 20% ethanol, density=745 g/L
  - Gasohol E85: AFR=9.77, 85% ethanol, density=783 g/L
  - LPG: AFR=15.5, gaseous, density=510 g/L, LHV_vap=370 kJ/kg
  - NGV/CNG: AFR=17.2, gaseous, density=0.72 g/L, no evap cooling
  - Diesel B7: AFR=14.45, DI (no fuel trim), density=833 g/L
  - Diesel B20: AFR=14.30, DI, density=835 g/L
- **Helper methods**: `isGaseous()`, `isDiesel()`, `hasEthanol()`, `hasFuelTrim()`, `fromString()` for backwards-compatible log replay
- **8-color UI theme** — Each fuel type has its own color (LPG=orange, NGV=green, E20=purple, E85=pink, diesel=gray, B20=teal, G91=indigo, G95=blue)
- **FuelMapView** — Uses `isGaseous()` for petrol/lpg map grouping instead of binary enum comparison. Diesel and E20/E85 route to petrol map side

### Changed
- **DerivedSensors** — Fuel consumption now uses FuelProperties (accurate AFR/density per fuel type). Previous code used hardcoded petrol AFR=14.7 for all fuels
- **LPGAnalyzer** — Recommendation text uses `isGaseous()` instead of binary PETROL check
- **LogReplayParser** — Uses `FuelMode.fromString()` for all legacy + new fuel codes
- **ApiServer** — Uses `FuelMode.fromString().isGaseous()` for map routing
- **LoggerConfig** — Added `showAirDensity` (default true), `engineDisplacementCC` (default 1998), `ratedRPM` (default 6000)
- **DataWriter** — Registers 22 new derived columns in CSV/JSONL: AAD, MAD, BAD, density%, density altitude, SAE J1349 CF, grains, humidity, OMD, compressor eff, intercooler eff, VE, DCAFR, TMF, MAF deviation, LVD, effective density, evap cooling ΔT, evap-corrected MAD, PDI, SAE J607 CF, SAE CF delta
- **LoggerService** — Computes + logs all 22 air density derived values per record. Weather API refreshes every 10 min (cached)

### Fixed
- **DtcReaderTest** — Mock driver now responds to 0100 protocol probe (scanSingleBus requires this before scanning DTCs)
- **LoggerServiceTest** — Disables `showAirDensity` in test config to prevent WeatherProvider network calls in Robolectric
- **AuditImprovementsTest** — Updated fuel consumption expected value for E10 petrol (AFR=14.23 vs legacy 14.7)
- **VE formula** — Corrected to use RPM/120 (4-stroke: one intake per 2 revs) instead of MAF×2/disp×RPM/60

### No Patent Conflict
- Banks patents (US 7,254,477 + 7,593,808) cover the *display method* of air density on a gauge
- The underlying physics (ideal gas law, Magnus formula) is public domain
- The 10 advanced formulas are original work not found in Banks iDash

## [3.6.0] - 2026-07-09
### Pro DTC Scanner Upgrade — 15 improvements for professional-grade diagnostics

### Added — Scan Reliability
- **Protocol Probe** — DTC scan now sends Mode 01 PID 00 probe before scanning each protocol bus, skipping dead buses instead of wasting time + getting false "NO DATA" results
- **Retry with Exponential Backoff** — Mode 03/07/0A commands retry up to 3 times with backoff (200ms × attempt), matching professional scanner behavior where ECUs need multiple queries
- **ISO-TP Flow Control** — Enables ATCFC1 before multi-frame DTC scans so ECUs returning many codes (>3 DTCs in one response) are not truncated at the first CAN frame
- **Post-Clear Verification** — After Mode 04 (Clear DTCs), automatically rescans Mode 03 to verify all stored codes are actually gone, instead of just trusting the 44 acknowledgment

### Added — DTC Code Intelligence
- **Fixed DTC Severity Logic** — getSeverity() now uses enrichment database (per-code expert severity) + proper heuristic fallback (airbag B0xxx=CRITICAL, misfire P03xx=CRITICAL, fuel trim P01xx=CRITICAL, ABS/network=WARNING). Previous logic incorrectly marked all P0xxx as critical and all body codes as info
- **DTC → Monitor Correlation** — New DtcMonitorCorrelation class maps DTC codes to affected readiness monitors (e.g. P0420 → Catalyst monitor, P0300 → Misfire monitor). Shown in readiness display
- **Drive Cycle Guidance** — New DriveCycleGuide class provides step-by-step driving instructions for each incomplete readiness monitor (e.g. "Drive at 64-80 km/h for 5-8 min" for Catalyst). Shown in readiness display with estimated time per monitor

### Added — Manufacturer-Specific DTC Databases (20 brands)
- **VinBrandDetector** — Detects vehicle brand from VIN WMI (first 3 chars), covering all major Thai-market brands + Chinese EVs
- **Dynamic Brand Database Loading** — DtcDatabase now loads brand-specific JSON database on top of generic database when VIN is read
- **Toyota/Lexus** (182 codes incl. VVT-i, ETCS, hybrid P3190-P3305, immobilizer NATS)
- **Honda** (197 codes incl. VTEC, A/F sensor, IMA, CVT)
- **Isuzu** (138 codes incl. diesel DPF/DEF/SCR/NOx)
- **Nissan** (157 codes incl. CVT, NATS, ETCS, A/F sensor)
- **Mitsubishi** (45 codes incl. MIVEC, MUT)
- **Ford/Mazda** (53 codes incl. MS-CAN body modules)
- **Suzuki, Chevrolet, Hyundai/Kia, Volvo, BMW, Mercedes-Benz**
- **BYD** (66 codes incl. HV battery, motor inverter, OBC, DC-DC, HVIL)
- **GWM/Haval/Ora, NETA, AION, Deepal, MG** (Chinese EV manufacturer codes)
- **Tesla** (66 codes incl. drive unit, pyrofuse, supercharger, autopilot)
- Total: 5,054 DTC codes across 21 databases (generic + brand-specific)

### Added — Enhanced Diagnostics
- **Continuous DTC Monitoring** — LoggerService now polls Mode 01 PID 01 every 30 seconds (was full scan every 60s) for real-time MIL/DTC count detection, triggering full Mode 03/07/0A scan only when count changes
- **Per-ECU Physical Addressing Scan** — New scanEcuDirectly() method targets individual ECUs via ATSH/ATCRA (e.g. 7E0→7E8 for ECM, 7E1→7E9 for TCM) instead of broadcast, avoiding multi-ECU response collisions
- **Enhanced Mode Scanning** — New scanEnhancedMode() reads manufacturer-specific DTC modes beyond standard OBD2: Mode 21 (Toyota/Honda), Mode 1A (Nissan), Mode 27 (Ford). Auto-detects brand from VIN
- **Mode 08 Bi-Directional Control** — New Mode08Controller class with 14 standard tests (EVAP purge, EGR, radiator fan, fuel pump, MIL, A/C clutch, glow plugs, etc.) with confirmation dialog (WARNING: activates physical components)
- **Mode 09 In-Use Performance Tracking** — Reads PID 0D (ignition cycles), PID 0E (OBD trips), PID 0F (distance + engine time since clear) — the only definitive way to confirm drive cycles actually completed required monitors
- **Freeze Frame Supported PID Query** — FreezeFrameReader now queries Mode 02 PID 00 to discover which PIDs the ECU has freeze frame data for, instead of blindly querying hardcoded PIDs

### Added — UI Integration
- **Pro Scanner Features Section** — After every DTC scan, displays 3 new action buttons: Bi-Directional Control, Enhanced Scan, Per-ECU Scan
- **In-Use Performance Display** — Shows ignition cycles, OBD trips, distance, and engine time since last DTC clear
- **Drive Cycle Guidance in Readiness** — Readiness tab now shows step-by-step driving instructions for each incomplete monitor
- **DTC → Monitor Correlation in Readiness** — Shows which monitors are affected by current stored DTCs

### Added — PDF Report
- **Enhanced PDF Report** — DtcReportExporter now includes: Readiness Monitor Status, Mode 06 Test Results, ECU Module List, Protocol Bus Scan Results, Drive Cycle Guidance

### Changed
- **Version**: 3.5.15 → 3.6.0 (code 80 → 81)

## [3.5.15] - 2026-07-09
### Fixed
- **Battery Tester Active Logging & Background Support** — Fixed the battery tester by keeping its control buttons enabled during active logging. Resolved active driver mapping by introducing `getActiveDriver()` in `MainActivity` which accesses the active driver from either `MainActivity` (for in-process logging) or `LoggerService` (for background logging). The battery tester now successfully reads live/direct adapter voltages in both configurations.
- **Android 14+ Background FGS Bluetooth Permission Compliance** — Updated `ensureTransportPermissions()` to always request Bluetooth permissions when `backgroundLoggingCheckbox` is checked. This guarantees the app holds the required permissions for the service type `connectedDevice` before calling `startForegroundService()`, preventing Android 14+ startup security exceptions even when using Simulation or WiFi transport modes.

## [3.5.14] - 2026-07-09
### Fixed
- **Connection Timeout Watchdog** — Implemented a 20-second connection timeout watchdog in `MainActivity` that auto-stops logging, resets states, and releases the UI if starting background logging is silently blocked by the OS (autostart/battery limits) or if the adapter connection hangs.
- **Manual Stop UI Lock Release** — Fixed a bug where stopping logging manually via the FAB or Connect button left all UI settings inputs (spinners, text inputs, checkboxes) disabled because the callback was cleared before the UI release routine was invoked. Now, `stopLogging()` immediately releases the UI input locks.

## [3.5.13] - 2026-07-09
### Fixed
- **Asynchronous Service Startup Race Condition** — Fixed a key race condition where an old logger thread's `finally` block would asynchronously post `onStopped()` back to the activity *after* a new session had already started, causing the UI to false-reset to "Stopped" and locking the user out of the connection.
- **Thread-Safety & Local Variable Isolation** — Refactored the core background logging service loop to use local, thread-bound variables (`localDriver`, `localWriter`, `localApiServer`) and session tokens. This prevents overlapping background runs from clashing on shared instance resources.
- **Driver Context Lifecycle Initialization** — Ensured that `DriverFactory.setAppContext()` is initialized in `LoggerService.onCreate()` so that the driver context remains valid if the process is restarted by the OS.
- **Robust FGS Start Error Handling & Post-Delay** — Added a safety try-catch around `startForegroundService()` and introduced a 300ms delay after permission dialog dismissal to prevent foreground service start background restrictions.

## [3.5.12] - 2026-07-09
### Fixed
- **Background Logging Start Crash / Connection Failure Race Condition** — Moved the battery optimization exemption request dialog launcher out of the start logging sequence and into the `backgroundLoggingCheckbox` click toggle listener. This prevents the system settings dialog from pausing the activity during service startup, which would trigger background start restrictions and cause the service promotion (`startForeground()`) to fail or crash on Android 14+.

## [3.5.11] - 2026-07-09
### Fixed
- **Startup Fuel Mode Default & Persistence** — Changed the default startup fuel mode to **Petrol** (instead of LPG/CNG) for new installs or clean preference runs. Added instant selection saving in the `fuelSpinner` listener so that any user fuel selection is persisted immediately to `SharedPreferences` and reliably remembered even if the app closes or restarts.

## [3.5.10] - 2026-07-09
### Fixed
- **UI Settings and Diagnostic Button Interference During Logging** — Added dynamic UI disablement (via `setConfigUiEnabled()`) which disables settings input spinners/checkboxes, alternative OBD2 commands (DTC diagnostics: read/clear DTCs, read VIN, check readiness), and battery tester buttons during active logging. This prevents users from altering parameters mid-session and avoids adapter command collisions.
- **Notification Permission Denial Graceful Recovery** — Hardened the notification permission result callback to reset `running` to false, update the FAB state, and re-enable UI controls upon permission denial.

## [3.5.9] - 2026-07-09
### Fixed
- **Foreground Service Startup Crash on Android 14+ (SDK 34+)** — Added the required service type parameter `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` in the `startForeground()` call.
- **Background Logging Status / FAB State Desynchronization** — Added a reset in `syncLoggerState()` to clear the static `running` flag when neither background service nor in-process logging is active. Updated `stopLogging()` to ensure that the stop intent is always dispatched to the service even if the user toggled the background setting off while logging was running.
- **Log Directory Permission & Auto-Resume** — Validated directory read/write permissions via `DocumentFile.canWrite()`. Added support for an auto-resume flag that automatically starts the log session once the user selects the folder, without requiring a second tap.

## [3.5.8] - 2026-07-09
### Fixed
- **Startup language wrong / app doesn't remember language setting** — `LocaleHelper` wrote the language choice to TWO stores: a custom `OBD2Prefs` key AND `AppCompatDelegate.setApplicationLocales()`. On Android 13+ (API 33, incl. Android 16) `AppCompatActivity` applies AppCompat's own store in `attachBaseContext`, overriding the manual `createConfigurationContext` wrap, so the effective language was whatever AppCompat had — which silently diverged from `OBD2Prefs`. That caused the wrong language at startup and the choice not being remembered. Now AppCompat's application-locale store is the single source of truth (it persists correctly across restarts on every API level AppCompat supports); the conflicting `OBD2Prefs` dual-write is removed and the old value is migrated once so existing users keep their setting. Works on Android 6 (API 23) through 16 (API 36).

## [3.5.7] - 2026-07-09
### Fixed
- **Background logging stuck on "Connecting…" / can't start** — two defects:
  1. `MainActivity.onStopped` never reset `running`, so whenever the service stopped (including a failed foreground-service start on Android 16) `running` stayed `true` and the next Start tap did nothing — the app had to be reopened to recover. `onStopped` now sets `running = false`.
  2. `LoggerService.onStartCommand`'s `startForeground` failure (e.g. Android 16 foreground-service restriction) now reports the real error via `onStatus` AND fires `onStopped`, so the UI returns to a startable state with a visible message instead of freezing.
- **Connect timeout** — `driver.connect()` is synchronous and could hang forever on some adapters/Android Bluetooth stacks, leaving status frozen on "Connecting…". It is now bounded to 15s in a separate task; on timeout it reports "Connection failed" and stops cleanly (with `onStopped` so `running` resets).

## [3.5.6] - 2026-07-09
### Fixed
- **Background logging wouldn't start until app reopen** — `startLogging()` set `running = true` *before* the `POST_NOTIFICATIONS` permission gate. On Android 13+, if the permission wasn't granted yet, `startBackgroundLogging` deferred the start (requested permission + returned) but `running` was already stuck `true` with the checkbox checked and no session active. The first Start did nothing; only a Stop/restart made it work — and reopening the app (which resets `running` via `isLoggingActive()`) "fixed" it. Now `running = true` is set only when logging actually launches (`actuallyStartBackgroundLogging` / `startInProcessLogging`), and `stopLogging()` clears any deferred (permission-gated) start.

## [3.5.5] - 2026-07-09
### Fixed
- **Background logging crash on Android 13+ (Android 16)** — `startForeground()` requires the runtime `POST_NOTIFICATIONS` permission. If it wasn't granted, `startForeground()` threw `SecurityException` on the system binder thread and killed the whole app the moment background logging started (the service worker thread's own try/catch does not cover `onStartCommand`). Fix: `startBackgroundLogging` now gates on `POST_NOTIFICATIONS` (API ≥ 33) and defers the `startForegroundService` call until the user grants it (resumed in `onRequestPermissionsResult`). Also hardened `LoggerService.onStartCommand` so a foreground-service startup failure degrades to a clean stop with an error instead of crashing.

## [3.5.4] - 2026-07-09
### Fixed
- **DataWriter MediaStore fallback** — on API ≥ Q, if `MediaStore.Downloads.insert` returns null (some emulators / restricted-storage profiles), `createDownloadTarget` previously threw and logging died on the first record. Now falls back to a direct file under `Download/<TunerMapPro>` instead of throwing. (Found while standing up the regression test below.)
### Added
- **Regression test: LoggerServiceTest** — drives the real `LoggerService` lifecycle with the SIM driver (no hardware) and asserts background logging survives multiple records without an error status. This is the exact class of bug that caused the 3.5.2 background-logging crash (unassigned `activeConfig` → watchdog NPE), so it can't silently regress again.

## [3.5.3] - 2026-07-09
### Fixed
- **Background logging crash (regression in 3.5.2)** — `LoggerService.activeConfig` was never assigned (always null), so the new low-voltage watchdog NPE'd on the first background record and killed the logging session. Now set at the top of `runLogger`, with a null-guard in `checkVoltageWatchdog()` and the periodic DTC-scan lambda so a missed assignment can never crash the worker thread again. In-process logging was unaffected (MainActivity used its own local config), which is why only background logging crashed.

## [3.5.2] - 2026-07-09
### Fixed
- **LPG-only mode silently dropped Vehicle Speed / Throttle (HIGH)** — `lpgOnlyMode` polled only `getLpgCritical()`, which excluded Vehicle Speed (needed for Fuel Economy) and Throttle Position. Fuel Economy now stays blank in LPG-only mode. Added `PIDCatalogue.getLpgPollSet()` = `lpgCritical ∪ dashboard ∪ derived-sensor dependencies`, so the lean mode never starves a feature the UI/log relies on. Wired into LoggerService + MainActivity.
- **Derived sensors not saved to the log (HIGH)** — Fuel Economy (km/L, L/100km), Turbo Boost (kPa, psi) and DPF stats were shown live but never written to CSV/JSONL because the writer only iterated the poll-PID columns. DataWriter now builds its columns from a keyed set (`pidKey`) that includes every derived sensor, so each gets its own column (previously the two "Fuel Economy" / two "Turbo Boost" samples silently overwrote each other by display name).
- **LPGAnalyzer fabricated a verdict on missing trim (MEDIUM)** — a null STFT/LTFT was treated as 0.0, producing a confident OK/Lean/Rich on partial data. Now reports UNKNOWN. Core logic extracted to a Context-free `analyzeFuelTrim(stft, ltft, mode)` returning a `TrimVerdict` enum (testable on plain JVM).
- **LogReplayParser assumed closed loop when loop state unknown (LOW)** — `isClosedLoop()` defaulted to CLOSED when no loop column existed, plotting open-loop rows into the tuning map. Now defaults to OPEN (skipped).
- **LocaleHelper mutated process-wide default locale (LOW)** — `Locale.setDefault()` was called on API ≥ 24 where the scoped `createConfigurationContext` is sufficient; removed from the N+ path so one activity's locale can't clobber another's.
- **GraphView data race (LOW)** — `pushValue()`/`clear()` now `synchronized` so a future background feeder can't corrupt the deque / min-max during `onDraw`.
### Added
- **Session summary JSON** — DataWriter now also emits `<session>_summary.json` on close with per-column min/avg/max plus integrated distance (km) and fuel used (L) from the L/100km stream — no need to re-parse the whole CSV.
- **LPG low-voltage watchdog** — LoggerService warns once per session (status bar + notification) when Control Module Voltage sags below 13.0 V in LPG mode, since a weak alternator/battery causes lean misfires that masquerade as fuel-trim problems.
- **Unit tests** — `AuditImprovementsTest` (poll set, analyzer verdicts, replay fallback, DerivedSensors math) + `DataWriterTest` (Robolectric; verifies derived columns are persisted with distinct keys). Added `androidx.test:core` + `org.robolectric:robolectric` test deps.
- **Version**: 3.5.1 → 3.5.2 (code 66 → 67)

## [3.5.1] - 2026-07-09
### Fixed
- **DTC Non-Header Parsing Bug** — Enforced 3-character minimum check on CAN ID tokens to prevent 2-character mode response headers from corrupting trouble codes when headers are disabled.
- **In-Process Telemetry Derived Sensors** — Added real-time derived sensor computations (Fuel Economy, Turbo Boost, DPF Health) to the foreground/in-process polling loop so the home page updates correctly.
- **Multi-Frame VIN Parsing Bug** — Integrated robust line-by-line normalization to strip CAN ID headers, PCI frame descriptors, and frame indexes so that multi-frame VIN queries do not decode as corrupted characters.
- **Mode 09 Cal-ID & CVN Parsing Bug** — Implemented the same line-by-line cleaning mechanism in `Mode09Reader` to resolve character corruption on multi-frame Cal-ID and CVN responses.
- **Simulation Driver Mode 07 Pending DTC Response Typo** — Fixed typo in Simulated Mode 07 response string from `41 01 00 07` to `47 01 00 07 00 00`.

## [3.5.0] - 2026-07-09
### Added
- **Fuel Consumption (km/L)** — real-time from MAF + Speed, auto AFR/density per fuel mode (14.7/737 petrol, 15.5/510 LPG). Also outputs L/100km. Null-safe at idle.
- **Turbo Boost Pressure** — MAP minus Barometric, displayed in kPa + PSI. Barometric PID (0x33) polled automatically. Sea-level fallback if unavailable.
- **DPF Status Monitor** — 5 PIDs for diesel: Soot Load (0x7A), Temperature (0x7B), Delta Pressure (0x85), Regen Status (0x8C), Ash Load (0x8B). Health: Clean/Moderate/Warning/Critical.
- **Custom PID Support** — JSON persistence in SharedPreferences. Full CRUD + formula tester. Merge into catalogue at logging start.
- **Thai UI Localization** — Complete `values-th/strings.xml` for all panels.
- **Auto-detect diesel** — VIN WMI heuristic (MPA/MNB/MMB/MR0) enables DPF + Deep Scan on first connect. One-time only.
- **Instant-save checkboxes** — All 5 feature toggles save on toggle, not just onPause. Survives force-kill.
- **2x4 telemetry grid** — Clean balanced layout: RPM/Speed/Coolant/Voltage + Fuel/Boost/DPF/DTC count.
- **DTC badge on dashboard** — Shows stored+pending count with red/green color.

### Changed
- **`DerivedSensors`** — Centralized computation (fuel, boost, DPF health, regen status).
- **`CustomPidManager`** — User-defined PID storage.
- **`PIDCatalogue`** — `getAllWithCustom()`, Baro `lpgCritical=true`, +5 DPF PIDs.
- **`LoggerService`** — Derived sensors appended after each batch. Custom PID merge.
- **`LoggerConfig`** — 4 feature flags (turbo/fuel/dpf/custom).
- **`SimulationDriver`** — DPF PID simulations (15% soot, 250°C, 0.5kPa, idle regen, 5g ash).
- **Version**: 3.4.30 → 3.5.0 (code 64 → 65)

## [3.4.30] - 2026-07-08
### Added
- **Ford HS-CAN ECU names** — brand-aware ECU naming: when Deep Scan is on, shared HS-CAN IDs (0x7E0-0x7EF) show Ford-specific names (PCM, TCM, ABS Module, RCM, IPC, PSCM, HVAC, APIM) instead of Toyota names
- **Mitsubishi ECU database** — 13 unique CAN IDs: AWC/S-AWC (0x762), ETACS (0x764), KOS/OSS (0x76A), MMCS (0x744), Diesel Engine-ECU (0x611/0x619), 4WD Transfer (0x72E), AFS (0x72F), TPMS (0x76B), ASC (0x763), EPS (0x765)

### Changed
- **moduleNameForCanId** now accepts `fordMode` parameter — Ford names override Toyota for shared HS-CAN IDs when checkbox is enabled
- **readAllDtcsDeep** now accepts `fordMode` flag — threaded through entire scan pipeline (scanBuses → scanSingleBus → ModuleInfo.Builder)
- **Settings description** updated: "Scan all protocols (Ford: HS-CAN + MS-CAN, Toyota, Honda, Isuzu, etc)"

## [3.4.29] - 2026-07-08
### Added
- **Multi-Protocol Deep Scan** — DTC scan now covers all major protocol buses for Thai-market vehicles: HS-CAN auto-detect, MS-CAN (Ford/Mazda body), CAN 29-bit (Isuzu D-Max/trucks), CAN 11-bit 250k, KWP2000 Fast (older Toyota Diesel), ISO 9141-2 (older Honda/Nissan), J1850 VPW (older Isuzu MU-7). Long-press "Read DTCs" button triggers exhaustive deep scan.
- **Brand-aware ECU name database** — 40+ ECU CAN IDs mapped to human-readable names: Toyota (ECM, TCM, ABS/VSC, SRS, HV ECU), Honda (PGM-FI, AT, VSA), Mazda (PCM, ABS/DSC), Isuzu D-Max 29-bit (ECM, TCM), Nissan (ECM, CVT, ABS/VDC), Ford MS-CAN (GEM, SJB, BCM, IC, etc)
- **Protocol scan status display** — DTC tab now shows a protocol scan summary table (🟢 responded / 🔴 has DTCs / ⚫ no response) with module count and DTC count per protocol bus
- **Deep Scan button** — long-press "Read DTCs" for exhaustive multi-protocol scan; long-press "Read VIN" for scan history

### Changed
- **DtcReader v3.0** — `ProtocolBus` class, `ProtocolScanStatus`, `readAllDtcsDeep()`, multi-bus scan engine with ATSP switching
- **Settings checkbox renamed** — "Ford MS-CAN Scan" → "Deep Multi-Protocol Scan"
- **Decreased auto-scan disruption** — fast scan still uses auto-detect only; deep scan is manual trigger

## [3.4.28] - 2026-07-08
### Added
- **Ford MS-CAN support** — DTC scan now supports Ford's secondary CAN bus (MS-CAN, 125 kbps) for body/GEM modules. Enable via Settings checkbox "Ford MS-CAN Scan". Scans both HS-CAN (powertrain) and MS-CAN (body modules) buses sequentially.
- **Module detection with scan status** — DTC scan now identifes which ECUs responded and displays per-module scan status in the DTC tab. Shows CAN ID, module name, and colored chips per mode (✓ clean / ● X DTCs / ⚠ no response). Supports 30+ known ECU IDs across HS-CAN and MS-CAN buses.

### Changed
- **DtcReader refactored** — new `readAllDtcs()` API with `DtcScanResult` containing codes + `ModuleInfo` list. Backward-compatible simple methods preserved.
- **LoggerService** auto-scan and periodic check now use the module-aware `readAllDtcs()` API.

## [3.4.27] - 2026-07-08
### Fixed
- **Fuel map debounce too strict — missed stable data during RPM jitter** — v3.4.26 added a 2-consecutive-ticks debounce but the 500-RPM cell boundary caused problems: idle at 800 RPM (cell 500) with a brief 1050 RPM blip (cell 1000) reset the counter. Returning to idle started from tick 1 again — idle data never accumulated. Replaced with a **sliding window** debounce (ring buffer of last 4 cell positions). A cell is accepted if it appeared at least once in the window — tolerates boundary jitter while still filtering one-off transient cells.

## [3.4.26] - 2026-07-08
### Fixed
- **Fuel map debounce was dead code** — `FuelMapView` had `consecutiveTicks`/`lastRpmCell`/`lastTinjCell` tracking variables that were set but never read. Every sample pushed to the map immediately, including transient pass-through points during acceleration/deceleration — contaminating cell averages with unstable readings. Now wired up: data only enters the map after ≥2 consecutive ticks at the same cell, filtering out momentary RPM/load transitions.
- **Fuel mode change mid-session ignored** — Changing the fuel spinner (Petrol ↔ LPG) while logging only changed the UI theme color. The running `LoggerService` and `activeInProcessConfig` still used the fuel mode set at session start, so all new records routed to the OLD map layer. Switching from Petrol to LPG mid-drive silently continued filling the Petrol map. Now the spinner propagates the change to both the service config and the in-process config.

## [3.4.25] - 2026-07-08
### Fixed
- **Battery status badge now uses chemistry-specific thresholds** — `getBatteryStatusDescription()` and `getBatteryStatusColor()` had hardcoded Flooded-battery thresholds (13.2–14.8 V charging band). An AGM battery whose alternator outputs 13.5 V is *undercharging* (AGM needs ≥14.0 V) but the badge showed green "charging normal." Now both methods accept a Chemistry parameter and use `chem.altMinV/altMaxV/restLowV/restDeepV` — the live monitor badge reflects the actual chemistry selected in settings.

## [3.4.24] - 2026-07-08
### Fixed
- **Config not persisted across app restarts** — Transport mode, WiFi IP/port, baud rate, sample interval, fuel mode, OBD protocol, LPG-only, and API server settings all reset to defaults every time the app was closed/reopened. No `onPause()` save existed. Now `saveConfigPrefs()` persists all 9 settings to SharedPreferences on pause, and `restoreConfigPrefs()` restores them on resume — the app picks up exactly where you left it.

## [3.4.23] - 2026-07-08
### Fixed
- **Background logging crash** — `MainActivity.onRecord()` called `countText.setText()` without null-/destroyed-guard. When the activity was destroyed (swipe-away, rotation) while `LoggerService` kept firing callbacks through `mainHandler`, the UI thread crashed with `NullPointerException` because all views were already cleared. Now `onRecord()` checks `isFinishing() || isDestroyed()` at entry and inside the `runOnUiThread` block, plus null-checks `countText`.
- **Fuel map RPM cell mismatch** — `FuelMapView.pushDataInternal()` used `Math.round(rpm/500)*500` (HALF_UP rounding) to bin RPM into 500-step grid cells. At idle (~750 RPM), `Math.round(1.5)=2` placed the highlight at the 1000 RPM cell while the dashboard showed 750 — the cell looked 250 RPM ahead of actual. Changed to floor-based binning `(int)(rpm/500)*500` so each cell covers `[cell, cell+500)` and the highlight always sits at or below the actual RPM.

## [3.4.22] - 2026-07-08
### Fixed
- **ElmDriver.initializeElm327() always returns true even when AT commands fail** — The method had a try/catch that only caught exceptions, but `sendCommand()` returns empty string on socket failure instead of throwing. This meant `WiFiDriver.connect()` would report success with `connected = true` even when the ELM327 never responded (dead adapter, wrong WiFi network, etc.). Now:
  - Checks `ATZ` response — if empty/null, logs error + disconnects + returns `false` immediately.
  - Checks `ATI` response — if empty/null, logs error + disconnects + returns `false` immediately.
  - This prevents the app from silently staying in a broken "connected but not really" state.
### Fixed
- **CRITICAL: WiFi connection fails when gateway disabled (mobile data + WiFi simultaneous)** — When the user disables the WiFi gateway to use mobile data alongside the vLinker WiFi adapter, Android's routing table doesn't route the adapter's subnet (192.168.0.x) through wlan0, so `Socket.connect()` times out. CarScanner Pro works because it uses `ConnectivityManager` to find the WiFi transport and calls `Network.bindSocket()` to force the socket onto the WiFi link. Now the app does the same: `WiFiDriver.findWifiNetwork()` locates the active WiFi `Network` (TRANSPORT_WIFI) and binds the socket before `connect()`. This bypasses the missing route entirely.
  - Added `Context context` field to `LoggerConfig` (transient) so WiFiDriver can access `ConnectivityManager`.
  - `MainActivity.readConfigFromUi()` and `LoggerService` both set `config.context = getApplicationContext()`.
  - `DriverFactory.copyConfig()` propagates `context` to cloned configs.
- **Python client: `SO_BINDTODEVICE` for same routing issue** — `WiFiDriver.connect()` now binds the socket to `wlan0` via `SO_BINDTODEVICE` (sockopt 25) before `connect()`, with a config flag `wifi_bind_interface` (default True) to disable if needed.
- **Python client: PID parser missing formulas for 3E/46/5C** — `Control Module Voltage` (0x3E) returned 4628V instead of 4.628V (missing `/1000.0`), `Ambient Air Temp` (0x46) returned 115°C instead of 75°C (missing `-40`). Added explicit cases for 3E, 46, and 5C (Engine Oil Temp) in `pid_parser.py`.

## [3.4.20] - 2026-07-07
### Fixed
- **Python client — vLinker detection dead-code bug (CRITICAL)**: `vlinker_optimizer.py` `detect_device()` and `apply_optimizations()` both checked `elm.connected`, which is always `False` during `initialize_elm327()` (before `self.connected` is set). This meant vLinker device detection always returned `UNKNOWN` and firmware-specific optimizations (ATAT2, ATST1A, ATAL, 6-PID chunks) were never applied on the Python client — the exact bug the Java version explicitly documents and avoids. Removed the `elm.connected` guard from both functions.
- **Python client — ATZ boot-buffer contamination**: `obd2_client.py` `initialize_elm327()` had only a 500ms sleep after ATZ with no buffer drain, so the ELM327 boot banner (`ELM327 v2.x\r\n>`) could interleave with the ATI/AT@1 probe — the stray `>` prematurely closed the recv loop and the adapter was misclassified as a non-standard clone. Now waits 1.5s after ATZ and calls new `drain_stale_bytes(0.5s)` to flush leftover boot bytes before probing. Mirrors the Java fix from v3.4.19.
- **Python client — init-phase timeout race in `WiFiDriver.send_command()`**: Per-recv timeout was hardcoded at 500ms for all phases, too tight for ATZ/ATI/AT@1 during init (ELM327 needs 500ms–1.5s to produce its first `>` after reset). Added `_initializing` flag (mirrors Java's `volatile boolean initializing`) — during init, per-recv timeout is `max(connectionTimeoutMs, 2000ms)` and the recv loop waits through timeouts instead of breaking on first partial response.

## [3.4.19] - 2026-07-07
### Fixed
- **WiFi connection — additional fix for ELM327 boot-buffer contamination**: v3.4.18 fixed the per-read timeout but missed a class of failures where the ATZ boot banner (`ELM327 v2.x\r\n>`) arrives in pieces interleaved with the subsequent ATI/AT@1 probe — the stray boot-prompt `>` prematurely closed the recv loop, the response parser saw an empty/truncated string, and the adapter was misclassified as a non-standard clone (so vLinker optimizations never applied). New behaviour:
  - `ElmDriver.initializeElm327()` now waits `Thread.sleep(1500L)` after ATZ (was 500ms) and then calls `drainStaleBytes(500ms)` to flush any leftover bytes from the boot banner before the ATI/AT@1 probe.
  - New `BaseDriver.drainStaleBytes(long maxMillis)` no-op default + `WiFiDriver.drainStaleBytes()` override that reads & discards buffered bytes with a tight 50ms per-read SO_TIMEOUT (then restores the previous SO_TIMEOUT). Holds `commandLock` so it cannot race with concurrent `sendCommand`.
  - `WiFiDriver.sendCommand()` simplified: during init-phase, the recv loop no longer breaks on first `response.length() > 0` — it keeps reading until the `>` prompt arrives, because a `>` from the ATZ boot banner can sit at the head of the response and we must not treat that as end-of-message. The deadline itself is the only bound.
- Same root cause: `vLinkerOptimizer.detectDevice()` was previously returning `GENERIC_ELM327` for these adapters, so even when the connection succeeded the firmware-specific optimizations (ATAT1/2, ATST32/1A/23, ATAL) were never applied. With the buffer-drain in place, vLinker devices are now correctly identified and tuned.

## [3.4.18] - 2026-07-07
### Fixed
- **WiFi connection reliability — ELM327 boot-timeout race**: Rewrote `WiFiDriver.connect()` to fix a class of failures where the app reported "could not connect" against perfectly reachable vLinker MC WiFi adapters (other apps like Car Scanner Pro worked fine on the same hardware/network). Root causes addressed:
  - Hardcoded `Socket.setSoTimeout(250)` on the WiFi socket caused every recv during the ATZ/ATI/AT@1 init probe to expire at 250ms — too tight for a slow-booting ELM327 (often 500ms–1.5s after TCP accept). Now uses `Math.max(connectionTimeoutMs, 2000)` during init and tightens to `Math.max(connectionTimeoutMs / 4, 500)` for steady-state queries.
  - Connect handshake timeout was 2s; bumped floor to 5s to absorb slow Android hotspot join latency.
  - Added `Thread.sleep(500ms)` immediately after `socket.connect()` to let the ELM327 finish booting before we send the first ATZ — eliminates the race where the boot prompt `>` arrives after our read timeout.
  - New `volatile boolean initializing` flag lets `sendCommand()` distinguish init-phase (wait through timeouts for boot prompt) from steady-state (treat timeout with partial response as end-of-message).
  - Replaced `catch (Exception ignored)` swallowing with `android.util.Log.e/w("WiFiDriver", ...)` so connection failures are visible in `adb logcat` for debugging.

## [3.4.17] - 2026-07-07
### Added
- **vLinker FS USB on Termux — Setup Guide**: New `docs/termux-usb-setup.md` companion document covering the verified working path for using a vLinker FS USB adapter directly from Termux/Python on Android 11+ (Android 16 verified, Xiaomi 2311DRK48G). Explains the SELinux sandbox limitation, the `termux:API` v0.53.0 FD-passing mechanism, and the `libusb_wrap_sys_device()` pattern required to bridge a Termux USB handle into Python. Documents pitfalls encountered (v0.51 "No such device" bug, FD ordering, CDC notification-header stripping, FTDI FT230X quirks) so future users do not repeat the same debug cycle.
### Fixed
- **Locale cleanup**: Removed 13 unused translation resources (Arabic, German, Spanish, French, Hindi, Indonesian, Italian, Japanese, Korean, Portuguese, Russian, Vietnamese, Chinese), keeping only `System Default`, `English`, and `Thai`. Trims APK size and removes maintenance debt for translations that were never actually populated beyond the base English copy.

## [3.4.16] - 2026-07-06
### Added
- **Material 3 UI Overhaul**: Fully migrated the application styling to Google's modern Material Design 3 (M3) specifications.
- **Custom Google Font**: Integrated the premium, geometric **Outfit** font family (Regular, Medium, Bold weights) globally.
- **M3 Colors & Theme System**: Added semantic Material 3 color roles (surface variant, outline, containers, etc.) to both light (`colors.xml`) and dark (`values-night/colors.xml`) configurations.
- **Uniform M3 Layout Cards**: Standardized all cards to use modern `16dp` rounded corners and flat `1dp` outline margins (`?attr/colorOutlineVariant`) instead of legacy sharp borders and shadow elevations.

## [3.4.15] - 2026-07-05
### Fixed
- **Map & Review screen localization**: Replaced hardcoded bilingual strings under the fuel trim tuner map with dynamic localized string resources in both English and Thai.

## [3.4.14] - 2026-07-05
### Added
- **Professional Tuner Home Screen Redesign**: Replaced the flat list on the landing page (`panelHome`) with a high-end tuning dashboard layout, including a vehicle status card, action controls, and an organized 2x2 grid representing functional modules (Gauges, Dashboard, Fuel Trim Map, DTC Scanner, Power Monitor).
- **Real-Time Home Telemetry Widget**: Integrated a horizontal status bar displaying real-time values for RPM, vehicle speed, coolant temperature, and battery voltage.
### Fixed
- **Battery Wizard Localization**: Resolved a localization issue where step instructions, dialogue warnings, and progress countdowns for all battery monitor tests (Voltage Drop, Cranking, Alternator, Ripple) were hardcoded in English. Configured all steps to fetch localized string resources dynamically (English & Thai support).

## [3.4.13] - 2026-07-05
### Fixed
- **Top Menu VIN Visibility**: Modified the top menu status container (`statusLayout`) width to `wrap_content` and increased the `headerVin` maximum width to `200dp` to prevent VIN truncation.
- **Simulation Coolant Temp**: Configured `SimulationDriver` to default to `RUNNING` state upon connection, ensuring the coolant temperature starts at 80°C and warms up to 90°C (passing the tuning log threshold) instead of declining.
- **Dashboard & Sensor Simulation**: Added realistic, smooth mock simulations for Speed, Engine Load, Throttle, MAF, and other core OBD2 sensors in simulation mode to replace erratic, random readings.
### Added
- **Landing Page Hero Icon**: Incorporated the premium vector app icon (`ic_launcher_foreground`) into the home screen's hero welcome header.

## [3.4.12] - 2026-07-05
### Fixed
- **Battery Type Selection & Calculations**:
  - **Dynamic Dropdown Selection Response**: Added an `OnItemClickListener` listener to `batteryTypeSpinner` to immediately refresh the dashboard's quick SoC estimates, battery metrics, and any displayed full diagnostic reports when the user selects a different battery type.
  - **Smooth LiFePO4 SoC Curve Interpolation**: Removed redundant step-like conditional checks inside `voltageToSoC()` for LiFePO4 chemistry. This enables smooth, linear interpolation between calibration points (e.g. 13.25V now correctly evaluates to 87.5% SoC instead of jumping discretely from 30% to 80%).
  - **SoC-Based Health Classification**: Refactored `testBatteryHealth()` to grade resting voltage status using chemistry-aware SoC percentage boundaries instead of raw voltage offsets from `fullRestV`. This fixes a bug where a LiFePO4 battery at 13.15V resting voltage (representing only ~55% charge) was incorrectly graded as "Excellent".
  - **Enhanced Unit Tests**: Added unit tests in `BatteryTesterTest.java` verifying smooth interpolation metrics and chemistry-aware health classifications.

## [3.4.11] - 2026-07-05
### Fixed
- **Battery Tester Simulator Mode Support**:
  - **Stateful Simulator Engine**: Redesigned `SimulationDriver` to support stateful transitions corresponding to resting, cranking, normal running, high-RPM running, loaded, and recovery states.
  - **Dynamic Voltage & RPM Simulation**: Mapped PID `01_42` (Control Module Voltage) and PID `01_0C` (RPM) to output realistic waveforms and curves based on the active test state (e.g. simulated cranking voltage dip to 9.7V, high-RPM charging stability, and load-drop voltage dips).
  - **Full Diagnostic Auto-Population**: Added automatic parameter population when clicking "Full Diagnostic" in simulation mode so that a complete, realistic, passing diagnostic report is compiled instantly.
  - **Ripple Noise Simulation**: Adjusted simulated AC noise under normal running to match standard vehicle bounds (approx. 0.03V peak-to-peak), allowing the ripple test to pass successfully in simulation mode.

## [3.4.10] - 2026-07-05
### Fixed
- **Battery Analyzer Logic & Validation**:
  - **Voltage Recovery Bug Fix**: Resolved a critical argument mismatch in `BatteryTester.buildFullReport()` where `postLoadV` was passed twice to `testVoltageRecovery()`, causing the recovery delta to calculate incorrectly and always return a passing score of 100%. Now passes `noLoadV` as the baseline.
  - **Chemistry-Aware State of Health (SOH)**: Overloaded `testStateOfHealth()` to accept the `Chemistry` parameter and mapped it to the chemistry-specific SoC lookup curve instead of defaulting to Flooded. This fixes highly inaccurate SOH values for AGM, Gel, and LiFePO4 chemistry profiles.
  - **Ripple Test Diagnostic Report integration**: Fixed a bug in `testBatteryRipple()` where sample data was never stored in the class-level `rippleSamples` list, causing the full diagnostic report to always skip the diode/ripple test.
  - **Interactive Multi-Step Load Drop & Recovery wizard**: Extended `testBatteryLoadDrop()` to guide users step-by-step through measuring no-load, full-load, and 5-second post-load recovery voltages (with a real-time countdown) so that recovery parameters are populated in real-world use.
  - **Interactive Alternator & High-RPM Regulation wizard**: Extended `testBatteryAlternator()` to prompt users to rev the engine to 2500–3000 RPM, measuring alternator regulation stability at idle vs. high RPM (Charging System Efficiency).
  - **Verification Unit Tests**: Added a complete suite of unit tests in `BatteryTesterTest.java` validating chemistry-specific SoC curves, charging efficiency bounds, recovery indices, and full report scoring.

## [3.4.9] - 2026-07-05
### Fixed
- **Battery Tester UX & Layout Redesign**:
  - **Localized Chemistry Matching**: Fixed a critical bug where localized battery chemistry names (e.g. Thai names) selected in the spinner could not be parsed by English substring matching, causing the tester to fallback incorrectly to Flooded thresholds. The selector now matches using the localized string resource array to map directly to the correct `Chemistry` enum index.
  - **Localized Display Names**: Replaced references to English-only `chem.displayName` with `chem.getDisplayName(this)` across the battery monitor and test routines to properly display selected types in all 15 target languages.
  - **High-Density Graph Scaling**: Updated the custom `BatteryTestView` to scale all grid lines, outline border widths, text paints, dot metrics, statistics coordinates, and paddings by the target device screen density.
  - **Responsive Graph Height**: Scaled the custom view height on measure from a tiny hardcoded 320 raw pixels to a proportional 220dp height, making the timeline graph highly readable on modern high-resolution displays.
  - **Clean Action Button Layout**: Reorganized the 6 battery actions into a unified and responsive grid. Individual tests are now clean outlined MaterialButtons, with the "Run Full Diagnostic" option styled as a premium solid accent button spanning the full width at the bottom.
  - **Alternator Chemistry Awareness**: Added missing chemistry parameter delegation in `testBatteryAlternator()` to ensure regulated alternator charging system tests are matched against chemistry-correct limits (e.g., specific profiles for AGM, Gel, and LiFePO4).

## [3.4.8] - 2026-07-05
### Fixed
- **Home Screen Internationalization**: Replaced hardcoded English text strings in the newly redesigned Home layout (`activity_main.xml`) with resource references (`@string/...`). Reused pre-existing translation keys where possible to enable immediate, automatic translation into 15 supported languages (e.g. Thai, Spanish, German, Arabic) without adding translation debt.
- **New Translation Keys Added**: Defined new keys for interface status titles and labels in `strings.xml` (default English) and `strings.xml` (Thai translation).

## [3.4.7] - 2026-07-05
### Changed
- **Redesigned Home Screen Layout**: Replaced the outdated, cramped side-by-side card grid with a premium, responsive single-column Control Center. Features horizontal MaterialCardViews with theme icons, clean descriptions, and navigation indicators to prevent text wrapping on narrow devices.
- **Active Vehicle Status Dashboard**: Added a new flat status card featuring real-time fields for chassis VIN, battery voltage, detected OBD2 adapter, and active protocol (replaces old static greetings).
- **Cleaned Resource Duplications**: Resolved Gradle build merge failure by removing duplicate translation resource definitions (e.g., `battery_test_resting`) across all strings files (15 translation files).

## [3.4.6] - 2026-07-05

### Added
- **Multi-chemistry battery support** — Battery Tester now supports 6 battery chemistries with chemistry-specific voltage tables and thresholds:
  - **Flooded (Standard)** — 12.65V = 100% SoC
  - **AGM (Absorbent Glass Mat)** — 12.80V = 100% SoC, 54-month base life
  - **EFB (Enhanced Flooded)** — 12.70V = 100% SoC, 48-month base life
  - **Gel Cell** — 12.75V = 100% SoC, 48-month base life
  - **Calcium (Ca/Ca)** — 12.75V = 100% SoC
  - **LiFePO4 (Lithium)** — 13.30V = 100% SoC, 120-month base life
- **Chemistry enum** in `BatteryTester.Java` with properties for `fullRestV`, `altMinV`, `altMaxV`, `baseLifeMonths`
- **Chemistry-specific alternator thresholds** — AGM batteries accept 14.0-14.8V, LiFePO4 accepts 14.0-14.6V, Flooded accepts 13.8-14.7V
- **Chemistry-aware methods** — `voltageToSoC()`, `testStateOfCharge()`, `testBatteryHealth()`, `testAlternatorVoltage()`, `buildFullReport()` all accept `Chemistry` parameter
- **Spinner integration** — Battery Tester panel now has a dropdown to select battery type; selection flows through to all calculations and reports

### Changed
- **Battery type dropdown** — Expanded from 3 options (Flooded/AGM/Calcium) to 6 full chemistries
- **SoC calculations** — Each chemistry uses accurate voltage-to-SoC lookup tables (e.g., LiFePO4 has a flat discharge curve with 90% range between 13.1-13.3V)
- **Health classification thresholds** — Adjusted proportionally to each chemistry's `fullRestV`
- **Life expectancy** — `testBatteryLife()` now uses chemistry-specific `baseLifeMonths` and applies tropical climate derating
- **Live display** — `updateBatteryMonitor()` shows chemistry name in status text (e.g., "12.65 V | SoC: 95% [AGM]")
- **Full diagnostic report** — Displays "Battery type: AGM" or other selected chemistry in summary

### Technical Details
- Added `BatteryTester.Chemistry` enum with `fromSpinner(String)` parser
- Added overloaded methods for backward compatibility — old 2-arg signatures delegate to new Chemistry-aware versions
- `buildFullReport()` signature changed from `boolean isAgm` to `Chemistry chem` — all callers updated

## [3.4.5] - 2026-07-05
### Changed
- **Professional status chip in top toolbar** — Connection status + VIN now wrapped in a rounded pill (bg_status_chip.xml) with a colored connection dot (green/amber/red), status text, divider, and VIN. Syncs with the bottom status strip.
- **Start/Stop button redesigned** — Proper compact pill button with icon + "START"/"STOP" text (32dp height, 16dp corner radius). No longer a bare circular icon.

## [3.4.4] - 2026-07-05
### Changed
- **Redesigned home screen** — Compact hero header (was 28dp padding + 30sp title + description + status pill, now 16dp padding + 20sp title + small greeting, single row). Feature cards switched from vertical-centered (56dp icon circle + negative margin overlay hack) to horizontal layout (36dp icon circle on left, title+desc on right). Card corners 20dp→12dp, padding 20dp→12dp. Much more compact and professional.
- **Removed redundant Settings card from home screen** — Settings is already accessible via the gear icon in the top toolbar. The home card was redundant. Home screen is now 3 rows x 2 columns (Dashboard, Gauges, Map, DTC, Battery, Logs) + version text.

## [3.4.3] - 2026-07-05
### Fixed
- **Start button too wide, covering VIN** — Shrunk to compact 40dp icon-only circular button (play/pause icon, no text). Now takes minimal space in the top toolbar, leaving the status + VIN text fully visible.

## [3.4.2] - 2026-07-05
### Changed
- **Start/Stop button moved back to top toolbar** — User feedback: easier to reach at the top. Now a proper pill-shaped MaterialButton ("START"/"STOP" with play/pause icon) in the top header bar, positioned left of the theme toggle. Bottom status strip remains purely informational (status dot, device, RPM, Speed, Voltage, DTC badge).

## [3.4.1] - 2026-07-05
### Changed
- **Moved Start/Stop button from top toolbar to bottom status strip** — The floating FAB in the top header looked unprofessional. Replaced with a proper pill-shaped MaterialButton ("START"/"STOP") at the end of the bottom status strip. The top toolbar is now clean: Home button, connection status, VIN, theme toggle, settings — no more floating button.
- Added `setFabState(boolean)` helper to centralize all Start/Stop button appearance changes (text, icon, color) in one place. Eliminates duplicated `setImageResource`/`setBackgroundTintList` calls across 8 call sites.

## [3.4.0] - 2026-07-05
### Changed
- **Bottom navigation bar replaced with live status strip** — The old `BottomNavigationView` (5 tab icons) has been replaced with a persistent bottom status bar that shows real-time information on every screen:
  - **Connection indicator**: colored dot (green=connected, amber=connecting, red=disconnected) + device/transport name
  - **Live mini-readouts**: RPM | Speed (km/h) | Battery Voltage (V) — always visible even when on DTC, Battery, or Settings tabs
  - **Voltage color-coding**: green (12.65-14.7V healthy), amber (12.2-12.65V low/resting or 14.7-14.8V high), red (<12.2V critical or >14.8V overcharge)
  - **DTC badge**: red/amber count badge showing total stored+pending DTCs (tap to jump to DTC tab)
  - **Tap to navigate**: tap the status strip to jump to Dashboard, tap the DTC badge to jump to DTC scanner
  - Hides on the home screen for a cleaner menu appearance
- **Navigation is now home-card-only** — All tab navigation goes through the home screen's feature cards. The bottom bar earns its space by showing live data instead of redundant navigation icons. The Start/Stop logging FAB remains in the top toolbar.

### Removed
- `bottom_nav_menu.xml` no longer used by the app (kept in repo for reference)
- All `BottomNavigationView` Java code (setupTabs listener, showTab menu sync, BadgeDrawable)

## [3.3.1] - 2026-07-05
### Fixed
- **CRITICAL: App crash on startup (InflateException)** — BottomNavigationView had 6 menu items, but Material Design's BottomNavigationView supports a maximum of 5. The 6th item (nav_battery, added in v3.3.0) caused `IllegalArgumentException: Maximum number of items supported by BottomNavigationView is 5` during layout inflation on startup, crashing the app immediately. Fix: removed `nav_logs` from the bottom nav menu (Logs tab remains accessible via the home screen card). Battery tab keeps its bottom nav slot.
- **paddingHorizontal/paddingVertical on API 23-25** — These XML attributes require API 26+; using them on minSdk 23 can cause crashes on older devices. Replaced with paddingStart/paddingEnd/paddingTop/paddingBottom.

## [3.3.0] - 2026-07-05
### Added
- **Battery & Charging System Tester**: Professional-grade 12V battery diagnostics via OBD2 PID 0x42 (Control Module Voltage). A new dedicated "Battery" tab in the bottom navigation with 11 automated tests:
  - **State of Charge (SoC)**: Open-circuit voltage → SoC% lookup table (flooded lead-acid, 25°C)
  - **State of Health (SOH)**: Multi-factor degradation estimate combining resting voltage, cranking voltage, recovery delta, and charge acceptance (sulfation detection)
  - **Battery Life Estimate**: Remaining months extrapolated from SOH, battery type (Flooded/AGM), age, and tropical climate factor
  - **Alternator Voltage**: Regulated output check at idle (13.8-14.7V spec, optimal 14.2V)
  - **Voltage Drop Test**: No-load vs full-load (headlights + blower + AC + defroster) comparison with step-by-step dialog
  - **Voltage Recovery Test**: Measures how fast voltage returns after load dump (internal resistance indicator)
  - **Cranking Voltage Test**: Minimum battery voltage during engine crank — fast 80ms sampling for 5 seconds
  - **Ripple / Diode Health**: AC ripple detection via 20-sample burst at 100ms intervals (bad diode detection)
  - **Parasitic Drain Estimate**: Voltage decay rate when engine off (SoC loss per hour)
  - **Charging System Efficiency**: Voltage stability across RPM range (idle vs high RPM)
  - **Live Voltage Monitor**: Real-time scrolling BatteryTestView custom View with color-coded threshold bands (crank 9.6V, resting 12.2/12.65V, alternator 13.8/14.2/14.7V), min/max/avg stats, and gradient fill
  - **Full Diagnostic Report**: One-tap comprehensive report with weighted overall score (A+ to F grade), summary, and individual test result rows with pass/fail/warn severity colors
  - **Battery type selector**: Flooded (Standard), AGM/Gel, Calcium dropdown
  - **SoC/SOH summary cards**: Quick-glance large-number display at top of Battery tab
  - Bilingual strings (English + Thai) for all battery test labels
  - Bottom navigation now has 6 items (Dashboard, Gauges, Map, DTC, Battery, Logs)

- **Professional Home Screen Redesign**: Complete overhaul of the Home Hub panel:
  - **Gradient Hero Header**: Full-width blue gradient banner with app name, welcome text, and status pill with green dot indicator
  - **Color-coded gradient icon circles**: Each feature card has a unique colored gradient circle (Blue=Dashboard, Cyan=Gauges, Amber=Tuning Map, Red=DTC, Green=Battery, Purple=Logs, Slate=Settings)
  - **Icon overlay technique**: White Material icons overlaid on gradient circles for a polished 3D look
  - **Card elevation & rounding**: 20dp corner radius, 2dp elevation, 0.5dp subtle border
  - **Settings as full-width list item**: Horizontal layout with icon circle, title+description, and chevron arrow
  - **Ripple feedback**: selectableItemBackground on all cards for touch feedback

### New Files
- `BatteryTester.java` — Core analysis engine (11 tests, SOH, Battery Life, full report builder, SAE J1979 thresholds)
- `BatteryTestView.java` — Custom View for real-time voltage timeline graph with threshold bands
- `ic_battery.xml` — Material battery vector icon
- `bg_icon_*.xml` (7 files) — Gradient circle drawables for home screen feature icons
- `bg_hero_gradient.xml` — Blue gradient banner background for home hero header

### Changed
- Version bumped to 3.3.0 (code 32)
- Bottom navigation menu: added Battery tab (6 items total)
- `showTab()`: added panelBattery visibility at index 7
- `onRecord()`: now calls `updateBatteryMonitor()` to feed live voltage graph
- `setupTabs()`: added nav_battery item handler
- `setupHomeMenu()`: added cardHomeBattery click handler
- Battery voltage PID 0x42 simulation already present in SimulationDriver

## [3.2.3] - 2026-07-05
### Added
- **Gauge AVG (Average) Stat Tracking**: The Gauges tab now displays a running Average alongside MIN and MAX for every sensor PID. The stat line format is now `MIN: value • AVG: value • MAX: value` with three distinct theme colors (primary blue for MIN, accent green for AVG, danger red for MAX). Average is computed as cumulative mean across all valid readings in the session.

## [3.2.2] - 2026-07-05
### Changed
- **Replaced Active Session Folder Card with Live Session Metrics**: Removed the unstable "Active log session folder" card from the Live Stream tab that was prone to freezing device OS due to document intent queries. Replaced it with a professional, real-time metadata dashboard displaying logging status (pulsing badge), session duration (running timer), total recorded samples, and current logging frequency in Hz.

## [3.2.1] - 2026-07-05
### Added
- **Gauges Screen Colorized Limits**: Formatted the session MIN and MAX labels under the Gauges list with dynamic, high-contrast HTML colors. The values are now bolded and highlighted using theme-matching primary blue/cyan for MIN, and danger red for MAX.
- **Home Screen Program Version**: Added a centered version indicator TextView at the bottom of the new Home Hub panel, displaying the current running version dynamically alongside the settings panel.

## [3.2.0] - 2026-07-05
### Added
- **Home Hub Screen**: A card-based Home menu as the primary entry point of the app on startup. Displays 6 grid cards with modern primary color icons, headers, and descriptions for Dashboard, Gauges, Tuning Map, DTC Scan, Logs & History, and Settings.
- **Top Bar Home Shortcut**: Dynamic home navigation button in the toolbar on all secondary pages to instantly return to the Home Hub.
- **OnBackPressed Interception**: Callback that intercepts the system back button on any sub-page to take the user back to the Home Menu.
- **Logs Page UI Split**: A Material Toggle Button Group at the top of the logs tab to switch between "Live Stream" (active OBD2 data) and "Session History" (saved log files list).

### Fixed
- **App Theme Toggle Loop**: Solved a race condition where binding the theme spinner listener during layout inflation repeatedly reset the night mode preference. Using layout `.post()` queues the listener setup until after initial layout completes.
- **Always-Mutable PID Catalogue**: Resolved a potential `UnsupportedOperationException` when the live OBD2 driver tries to blacklist unsupported PIDs, ensuring `filterCatalogue` always returns a mutable `ArrayList` copy.
- **Foreground Logging parity**: Synced `runLogger()` in `MainActivity.java` with the background logging service's caching and adaptive blacklisting.

## [3.1.0] - 2026-07-04
### Added
- **Mode 06 — On-Board Monitor Test Results**: Professional-grade diagnostic feature that reads actual test values, min/max thresholds, and pass/fail status for all OBD2 monitors (Catalyst, O2 Sensors, O2 Heaters, EGR, EVAP, Misfire, Fuel System). Displays monitor name, test name, measured value, limits, and engineering unit. Includes full SAE J1979 UAS (Unit And Scaling) decoder covering 53 scaling IDs. Shown as a dedicated "Mode 06 — Monitor Test Results" section in the DTC scan results with pass/fail icons.
- **Per-DTC Freeze Frame Snapshots**: Reads freeze frame data for each stored DTC individually (via Mode 02 + DTC-to-frame mapping using PID 02), instead of only the generic frame 00. Each DTC now shows its own engine snapshot with 10 PIDs (RPM, Speed, Coolant, Load, STFT, LTFT, MAP, IAT, Timing Advance, Throttle Position). Falls back gracefully to generic frame if per-DTC mapping is unsupported.
- **Mode 09 — ECU Calibration ID & CVN**: Reads vehicle ECU calibration IDs (Cal-ID) and Calibration Verification Numbers (CVN) via Mode 09 PIDs 02/04. Critical for emissions inspection verification and detecting ECU reflashing/tuning. Displayed as "ECU Calibration Info" section.
- **DTC Enrichment Database**: 157 common DTCs enriched with probable causes (3-6 per code), suggested repair steps (2-5 per code), emissions-related flag, drive cycles to clear after repair, severity level, and system category. Each DTC card now shows "Possible Causes", "Suggested Repair", and drive cycle count. Covers P0100-P0799 plus P2xxx/P3xxx codes.
- **Scan Comparison (New vs Cleared)**: After the second DTC scan, displays a diff summary banner ("2 NEW, 1 CLEARED, 3 persisting"). Shows dedicated sections for newly appeared codes and codes that cleared since last scan. Persists across scans using existing SQLite history database.
- **GaugeView Professional Overhaul**: Complete rewrite of the analog gauge with tapered needle (wide base, sharp tip), drop shadow, white tip highlight, arc number labels around the gauge (values printed at major tick marks), smooth 300ms needle animation with DecelerateInterpolator easing, peak value indicator (yellow triangle on outer bezel showing session maximum), 3D hub cap (dark ring, colored inner, specular highlight), outer bezel ring, per-gauge color themes (RPM=red, Speed=cyan, Temp=amber, Load=green), configurable warning zone start per gauge, and clean non-overlapping text layout (label/value/unit).

### Changed
- **DTC Card UI Redesign**: Each DTC code is now displayed as an expandable card with system category tag, [Emissions] badge for emissions-related codes, severity color coding, and enrichment data (causes, fixes, drive cycles). Tap still opens Google search.
- **Freeze Frame Extended PIDs**: Freeze frame reader now attempts 9 PIDs (added Timing Advance PID 0E, Throttle Position PID 11) in addition to the original 8 core PIDs.

### New Files
- `Mode06Reader.java` — Mode 06 ELM327 communication, multi-frame parser, diagnostic MID probing
- `Mode06Result.java` — Mode 06 result model with monitor/test name lookup tables
- `UasDecoder.java` — SAE J1979 Unit And Scaling decoder (53 UASIDs with unit strings)
- `DtcEnrichment.java` — Enrichment database loader from assets JSON
- `Mode09Reader.java` — Cal-ID and CVN parser (Mode 09 PIDs 02/04)
- `DtcComparison.java` — Scan diff engine comparing current vs previous scan
- `dtc_enrichment.json` — 157 enriched DTC entries in assets
- `Mode06ReaderTest.java` — 7 unit tests for Mode 06 parsing
- `DtcComparisonTest.java` — 5 unit tests for scan comparison logic

## [3.0.8] - 2026-07-04
### Added & Improved
- **Comprehensive DTC Diagnostic Scanner Suite**:
  - **Freeze Frame Snapshot**: Added reading of Mode 02 freeze frame data (RPM, Speed, Coolant, Load, STFT/LTFT trims, MAP, IAT) inside `FreezeFrameReader` and displayed it as a clean engine snapshot card on trigger.
  - **Readiness Monitor Dashboard Grid**: Redesigned readiness monitors tab into a visually appealing two-column layout using colored indicators (🟢 complete / 🔴 incomplete) and automated spark-ignition vs compression-ignition (gasoline vs diesel) test name detection.
  - **DTC History Database (SQLite)**: Created a local SQLite history storage (`DtcHistoryDb`) recording each DTC scan's stored, pending, and permanent codes, with comparison highlight diffs. Added long-press action on "Read DTCs" to view vehicle scan history.
  - **PDF Diagnostic Report Export**: Implemented native Android `PdfDocument` generation allowing export of complete vehicle reports (DTCs, readiness status, freeze frames) to shareable PDF files directly without adding external library size.
  - **Background DTC Monitoring Alert**: Added an automatic check (every 60 seconds) during active logging to scan for new fault codes and trigger notifications if errors arise.
  - **WMI Brand Detection Fixes**: Refactored `BrandYearProfile` WMI prefix mappings to properly support Kia (KN), Audi, Dodge, Jeep, Chevrolet, Renault, Citroen, Tata, Mahindra, Isuzu, Hino, and Chinese vehicle manufacturers.
  - **PID Availability Caching**: Integrated persistent caching of supported OBD2 PIDs to eliminate redundant live bitmap queries on reconnection.
  - **PID Blacklisting**: Automatically drops unsupported sensors returning 3 consecutive failed queries to reduce CAN bus latency.
  - **4-Byte PID Support**: Expanded `FormulaEvaluator` with `D` variable support to parse and evaluate standard 4-byte PID responses.

## [3.0.7] - 2026-07-04
### Added & Improved
- **Simulated Diagnostic Commands**: Added simulation support for Modes 03 (stored codes), 07 (pending codes), 04 (clear codes), 0101 (readiness monitors), and 0902 (VIN queries) inside `SimulationDriver`. Decoupled diagnostic execution by declaring a unified raw command interface `sendCommandRaw` in `BaseDriver`.
- **DTC Web Lookup**: Added an interactive click listener on each fault code listed in the DTC scanner tab. Clicking any code opens Google Search (e.g. `P0171`) in a browser window to help users lookup repair suggestions.
- **REST API Diagnostics Integration**: Added `GET /api/dtc` and `DELETE /api/dtc` endpoints to the built-in HTTP server on port 8080. AI Agents can now inspect vehicle fault status and clear active fault codes.

## [3.0.6] - 2026-07-03
### Added
- **Delete Vehicle Folder capability**: Added a trash can button to each vehicle (VIN) folder row in the history list. Clicking it prompts for confirmation, then deletes all log files contained inside that folder, automatically cleans up the directory, and refreshes the folder view.

## [3.0.5] - 2026-07-03
### Added & Improved
- **Automatic empty parent folder cleanup**: Implemented automatic folder deletion when the last log file inside a VIN subdirectory is deleted (both for SAF DocumentFiles and local Files).
- **Auto folder-view redirect**: Resets selection and automatically redirects back to the main VIN vehicles list if the folder becomes empty.
- **Robust Path Validation**: Ensured sanitized VIN subfolder paths to prevent illegal naming or directory traversal errors.

## [3.0.4] - 2026-07-03
### Added
- **VIN Folders Grouping in History/Logs**: Grouped saved session log files (.csv) by their parsed/associated VIN ID into folders on the logs tab. Clicking a VIN folder enters that folder, allowing comparison of log files captured for that specific vehicle. Clicking "Back to Vehicles" returns to the folder list. Added directory scanner support and MediaStore relative path queries to extract VIN info automatically.

## [3.0.3] - 2026-07-02
### Added & Improved
- **AI Agent REST API Enhancement**: Rewrote and expanded the local HTTP REST API Server (`ApiServer.java` on port 8080) to support robust, full-featured AI Agent integrations:
  - Added `GET /api/ping` endpoint providing server uptime and availability.
  - Fully implemented `GET /api/map` returning binned 2D Fuel Map arrays and cells (Petrol, LPG, Deviation, Tune Assist) supporting `?min_hits=N` filtering.
  - Added `DELETE /api/map` to clear/reset live session maps.
  - Added `GET /api/map/summary` returning calibration statistics, absolute average deviations, and smart tuning recommendations.
  - Added `GET /api/map/export` to download fuel correction maps directly as a CSV file.
  - Added `POST /api/map/import` to upload and pre-populate historical baseline map grids.
  - Enabled full CORS support across all methods (`GET`, `POST`, `DELETE`, `OPTIONS`).
- **Comprehensive Unit Tests**: Added `ApiServerTest.java` verifying all REST API endpoints.
- **JSON Test Configuration**: Integrated plain JVM JSON testing dependency `org.json:json` in `build.gradle`.

## [3.0.2] - 2026-06-30
### Added
- **Detailed Formula Explanations in Help Dialog**: Rewrote the "How to read the Map" help dialog (`how_to_read_map_desc` in English and Thai) to explain the formulas and mathematical logic for each tab (Petrol, LPG, Deviation, and Tune Assist) separately.

## [3.0.1] - 2026-06-30
### Added
- **UI Formula Explanation**: Added a dynamic, bilingual formula label (`txtMapFormulaHint`) directly underneath the Fuel Map on the main screen to make it clear how the Deviation/Tune Assist percentages are calculated (`Deviation = LPG Trim - Petrol Trim`).
- **Detailed Formula Documentation**: Added a "Tuning Formula & Examples" section in the README.md explaining how to apply positive/negative correction values to the LPG ECU multiplier map.

## [3.0.0] - 2026-06-30
### Added & Changed
- **Fuel Map Redesign (Y = T.inj ms, X = RPM)**: Redesigned the Live Fuel Map layout to swap axes to match standard LPG tuning software: Y-axis is now gasoline injection time (`T.inj` in ms) bins, and X-axis is `RPM` (500 to 6500 in 500 steps).
- **Linear MAP/Load to T.inj Scaling**: Implemented automated linear scaling from standard OBD2 MAP/Load values into estimated `T.inj` ms to ensure compatibility with all vehicle models.
- **Tuning Correction CSV Export Update**: Realigned the Tune Assist correction CSV export to output in the new `T.inj` row-by-RPM-column layout format.
- **Multilingual Axis Labeling**: Updated the `map_axes` helper text resource in all 15 translation files (Thai, English, Arabic, German, Spanish, French, Hindi, Indonesian, Italian, Japanese, Korean, Portuguese, Russian, Vietnamese, Chinese).

### Fixed
- **History Log List Height Calculation**: Resolved a classic Android layout bug where list views inside a ScrollView measured with 0 width during initial layout (since the tab starts as `gone`), forcing them to wrap text vertically and request an artificially massive height (leaving a huge blank space at the bottom). Implemented screen width fallback and scheduled precise post-layout height adjustments.
- **Changelog and Readme Clarification**: Documented transition colors (Orange/Yellow for slightly rich, Light Blue for slightly lean) in the "How to read the Map" helper dialog to clarify grid color blending on dark backgrounds.

## [2.9.5] - 2026-06-30
### Added
- **ELM327 Clone & Standard Adapter Detection**: Implemented an automated adapter validator query check during OBD2 chip initialization. It queries `ATI` and `AT@1` command availability to identify standard vs. non-standard/buggy clone adapters (such as low-quality ELM327 v2.1 Chinese clones).
- **Bilingual Adapter Warning Dialog**: Created a pop-up alert dialog in `MainActivity.java` that alerts the user in both English and Thai if their connected OBD2 adapter is detected as non-standard or a buggy clone, detailing the performance and tuning failure risks.

## [2.9.4] - 2026-06-30
### Fixed & Optimized
- **Classic Bluetooth (SPP) Connection Fallback & Discovery Cancellation**: Configured `SerialDriver.connect()` to cancel Bluetooth active discovery searches before connecting as recommended by the Android SDK to prevent timeouts. Implemented a reflection-based socket connection fallback (using channel 1 `createRfcommSocket`) to recover from standard UUID connection lookup failures.
- **BLE Write Characteristics & Dynamic Write Type Optimization**: Modified fallback write characteristic scanning in `BleDriver.java` to support either `PROPERTY_WRITE` or `PROPERTY_WRITE_NO_RESPONSE` independently (previously required both). Added dynamic write type selection to use `WRITE_TYPE_NO_RESPONSE` when the characteristic only supports write-without-response.
- **Runtime Security Exception Safety Guards**: Wrapped Bluetooth bonded device queries and name queries in `MainActivity.java` and `DriverFactory.java` inside try-catch blocks for `SecurityException` to prevent background service crashes on Android 12+ due to runtime permission changes.
- **Improved Bluetooth Exception Logging**: Replaced silent try-catch blocks with explicit system log outputs (`Log.e`) to provide detailed diagnostics for bluetooth connection failures.

## [2.9.3] - 2026-06-30
### Fixed
- **Arabic RTL Layout Direction Alignment**: Dynamically set layout direction (`setLayoutDirection`) on configuration and activity decor views based on the resolved locale. This ensures that when the user switches to Arabic (or any RTL language), the entire app layout correctly mirrors to Right-to-Left (RTL) mode instead of alignment bugs.

## [2.9.2] - 2026-06-30
### Added & Changed
- **Splitting Log History Lists (Petrol vs. LPG/CNG)**: Split the history files into two distinct visual lists under localized Petrol Logs and LPG/CNG Logs headers to make scanning and comparison much easier.
- **Uri-based Compare Logs Selection**: Reworked the checkbox selection mechanism to track items directly by their document `Uri` (using `LinkedHashSet<Uri>`) instead of unstable index list view positions. Fixes checking/unchecking bugs.
- **Persistent Compare Mode & Selection State**: Ensured that the selected comparison files and compare mode are retained and fully interactive when returning (pressing Back) from the review screen to `MainActivity`.
- **Global Multi-Language Support (16 Languages)**: Expanded translation files to support 16 major languages, including Spanish, Portuguese, German, French, Italian, Russian, Hindi, Arabic, Indonesian, Vietnamese, Japanese, Korean, and Chinese, alongside a "System Default" fallback locale setting.
- **Top App Bar Theme Switcher**: Added a beautiful sun/moon toggle button in the top app bar header, providing a single-tap way to switch between light and dark themes synced with Settings.

## [2.9.1] - 2026-06-30
### Added & Changed
- **Organized log files by VIN subdirectory**: Created separate subdirectories inside `Downloads/OBD2LPGLogger` based on the vehicle's sanitized VIN (e.g. `Downloads/OBD2LPGLogger/1HGCR2F8.../`). If no VIN is detected, logs are written to the root log folder. Also updated history file loading to recursively scan folders so that all subfolder logs are fully loaded into the session list.
- **Replaced Dashboard Fuel Toggle with Tuning Readiness Widget**: Removed the redundant Petrol/LPG toggle button group from the Dashboard tab (since it is already available on the Settings tab). In its place, added a creative, live "Tuning Readiness Monitor" that displays real-time Engine Warm-up progress (using a horizontal progress bar based on Coolant Temperature) and Closed-Loop status verification.

### Fixed
- **Removed Map Tab external log picker**: Removed the "Open Log File" (`btnOpenLogFile`) button and its corresponding dead code from the Map page. Tapping log files inside the "Session History" list remains as the primary way to review logs, which resolves a bug where the user got stuck after choosing a file from the external picker.
- **Localized Bottom Navigation Bar Titles**: Changed the hardcoded menu titles for the "Map" and "Logs & History" tabs in the bottom navigation bar to use the `@string/tab_map` and `@string/tab_logs` string resources. This displays shorter, localized labels ("แผนที่" and "บันทึก" in Thai, and "Map" and "Logs" in English) that completely resolve text clipping/wrapping bugs on smaller screens.

## [2.9.0] - 2026-06-30
### Fixed — Critical
- **DataWriter closed-loop inversion (CRITICAL)**: The `loop_status` column written to every CSV/JSONL log file was inverted. SAE J1979 PID 03 values `0x01` (open loop/temp) and `0x08` (open loop/failure) were labelled `"Closed"`, while the real closed-loop value `0x02` was labelled `"Open"`. This corrupted every saved log's loop_status, causing replay parsers to drop closed-loop tuning rows. Fixed to `(val & 0x02) != 0 ? "Closed" : "Open"`. This was the same bug family previously fixed in `MainActivity.updateFuelMap()` and `LogReplayParser.isClosedLoop()` but survived in `DataWriter`.
- **Lambda PIDs 0x34/0x44 wrong formula (CRITICAL)**: PIDs `0x34` (Lambda B1S1) and `0x44` (Wideband Lambda) used formula `(A*256+B)/32768`, but per SAE J1979 byte A is the sensor index, not part of the lambda value. The correct formula is `(B*256+C)/32768`. The old formula mixed the sensor index byte into the value, producing incorrect lambda readings on any vehicle with wideband sensors (BMW, Mercedes, VW, etc.).

### Fixed — High
- **ReadinessMonitor byte D overlapping bit pairs**: The old code used overlapping bit pairs for Group 2 monitors — each monitor's "complete" bit was the next monitor's "available" bit, which is logically impossible. Per SAE J1979, byte D contains ONLY status bits (bit 3=O2 Heater, bit 4=O2 Sensor, bit 5=EGR, bit 6=Particulate Filter, bit 7=NOx/SOR). Fixed to use bits 3-7 for status with `available=true` (availability is in a separate PID 41 query).
- **ReadinessMonitor rejects valid 3-byte responses**: The minimum data length was 8 hex chars (4 bytes), but pre-2008 non-CAN vehicles (ISO 9141-2, KWP) may return only 3 bytes (A B C). Lowered to 6 hex chars (3 bytes) so MIL status and Group 1 monitors work on older vehicles.
- **BaseDriver.connected not volatile**: The `connected` field is written by the logger thread and read from the main thread (DTC/VIN/readiness operations) and BLE GATT callbacks. Without `volatile`, the main thread could see stale cached values. Made `connected` field `volatile`.
- **LoggerService double-start orphaned executor**: If `ACTION_START` was received twice (rapid restart or START_STICKY redelivery), a new executor was created without shutting down the old one, orphaning the previous logger thread and causing races on `driver`/`writer` fields. Added a guard that calls `stopLogging()` first if `executor != null`.
- **MainActivity.stopLogging disconnect() race**: The main thread called `currentDriver.disconnect()` while the logger thread was potentially still using the driver, and the logger thread's `finally` block also calls `disconnect()`. This could double-free native resources (GATT handles, BluetoothSocket, UsbSerialPort). Removed the `disconnect()` call from `stopLogging()` — the logger thread handles cleanup in its `finally` block.
- **BleDriver notifyEnabled set prematurely**: `notifyEnabled = true` was set synchronously after calling `g.writeDescriptor(cccd)`, but `writeDescriptor` is asynchronous and may fail. If it failed, `connect()` would proceed thinking notifications were enabled, leading to silent command loss (all `sendCommand` calls would time out). Moved `notifyEnabled = true` to `onDescriptorWrite` callback that only sets it when `status == GATT_SUCCESS`.
- **DtcReader PCI byte not stripped on continuation frames**: Consecutive ISO-TP frames start with a PCI byte (0x21, 0x22, etc.) that was being parsed as the first byte of a spurious DTC code. Added PCI byte stripping for continuation frames that don't start with the mode header.

### Fixed — Medium
- **VLinkerOptimizer MC BT detected as WiFi**: A vLinker MC connected via Bluetooth was always detected as `VLINKER_MC_WIFI`, applying WiFi optimizations (aggressive timing, 6-PID chunks) instead of BT optimizations (conservative timing, 4-PID chunks). This could cause dropped responses on Bluetooth. Fixed by checking `elm instanceof BleDriver || elm instanceof SerialDriver` to return `VLINKER_MC_BT`.
- **ReviewSessionActivity per-line runOnUiThread ANR**: Opening a large log file (10000+ lines) called `runOnUiThread()` for every line, flooding the UI thread message queue and causing ANR. Added `FuelMapView.pushDataNoInvalidate()` and batch all data pushes, then trigger a single `postInvalidate()` at the end. Reduces 10000 UI messages to 1 redraw.
- **MainActivity.runLogger unsafe executor shutdown**: The logger thread called `executor.shutdown()` without checking if `stopLogging()` on the main thread had already shut it down. Could clobber a newly-started executor. Fixed to capture executor reference locally and check `isShutdown()`.
- **DriverFactory.copyConfig missing enableApiServer**: The `copyConfig` method used for AUTO-mode candidate configs didn't copy the `enableApiServer` field, so when AUTO mode tried Bluetooth devices, the API server setting was silently lost.
- **GaugeView warning-zone background overpainted value arc**: The dim (alpha-60) warning-zone background was drawn AFTER the bright value arc. When the current value entered the warning zone, the full-alpha warning portion was overpainted by the 60-alpha background, making the gauge appear to stop at the warning threshold. Fixed draw order: warning background first, then value arc on top.
- **Engine Fuel Rate maxVal off by 0.75**: Formula `(A*256+B)/20` at max raw value produces 3276.75, but `maxVal` was 3276, causing the parser to reject the true maximum as out-of-range. Fixed to `3276.75`.

### Fixed — Low
- **FuelMapView misleading dwell comment**: The comment claimed "first sample stored immediately" but the code actually requires 2 consecutive same-cell ticks. Corrected the comment.

## [2.8.0] - 2026-06-29
### Fixed
- **Keep Screen On Not Working (CRITICAL)**: The "Keep Screen On" setting (checked by default) never actually kept the screen awake. The `FLAG_KEEP_SCREEN_ON` window flag was only applied inside the checkbox's `OnCheckedChangeListener`, which fires only when the user *changes* the checkbox — never on startup. So the box always showed as checked, but the screen still dimmed and locked during logging. Now the flag is applied immediately on app start based on the saved preference, so the screen stays on as soon as the app opens.

### Added
- **Keep Screen On Preference Persistence**: The "Keep Screen On" choice is now saved to `SharedPreferences` (`keepScreenOn`, default `true`) and restored on next launch.
- **Flag Re-assertion on Resume**: `MainActivity.onResume()` now re-applies the keep-screen-on flag in case the window/activity was recreated (e.g. after rotation or returning from the background), so the screen reliably stays on the whole time the app is in the foreground.
- **`applyKeepScreenOn(boolean)` Helper**: Centralized the add/clear of `FLAG_KEEP_SCREEN_ON` into a single method used by startup, the listener, and `onResume()`.

## [2.7.0] - 2026-06-29
### Added
- **Fuel Mode Prefix in Log Filenames**: Log files now include the fuel mode and transport mode in the filename (e.g., `Sim_LPG_20260628_120000_obd2.csv`, `PETROL_20260628_120000_obd2.csv`) to easily identify which mode was used during testing.
- **Empty Data Warning on Export**: The app now shows a clear Toast warning ("No correction data yet. Need both Petrol & LPG data in the same RPM/MAP cells.") when the user tries to export a Tune Assist CSV with no overlapping data, instead of silently exporting an empty file.
- **`hasAnyCorrection()` Method**: New public method on `FuelMapView` to check whether any valid correction data exists before attempting export.
- **Error Detail in Export Toast**: Export failure messages now include the actual error reason (e.g., `Failed to export CSV: permission denied`) instead of a generic message.

### Fixed
- **Multi-ECU PID Detection — STFT/LTFT/O2 Not Detected on Toyota Sienta 2014 (CRITICAL)**: On multi-ECU vehicles, the PID availability checker concatenated all ECU response lines into one string and used `indexOf` to find the first match. If the transmission ECU (7E9, supporting almost no PIDs) responded before the engine ECU (7E8, supporting STFT/LTFT/O2/MAP), only the transmission's sparse bitmask was used, causing all fuel trim and O2 sensor PIDs to be silently excluded. Fixed by parsing each ECU response line separately and OR-merging all bitmasks to get the full supported PID set from every ECU.
- **Corrupt CSV Header (CRITICAL)**: Fixed a bug where `exportCorrectionMapCsv()` wrote the header label twice without a newline separator (`MAP\RPMMAP \ RPM,500,...`), producing a malformed CSV that couldn't be opened in Excel or tuning software.
- **Wrong Fuel Mode in Log Filename (CRITICAL)**: Fixed the session ID prefix logic that used a binary PETROL-vs-LPG check. Any future fuel mode (CNG, Aviation, etc.) would have been incorrectly labeled as "LPG". Now uses the enum name directly (`fuelMode.name()`).
- **In-Process Logger Missing Fuel Prefix**: The in-process logger (`MainActivity.runLogger()`) was never updated with the fuel mode prefix, so log files recorded without background logging still used the old date-only naming format.
- **CSV Export Crash — FileProvider Authority Mismatch**: Fixed the FileProvider authority string from `.provider` to `.fileprovider` to match the AndroidManifest declaration, which caused `IllegalArgumentException` crashes on every export attempt.
- **CSV Export Crash — Missing FileProvider Path**: Added `<cache-path>` and `<external-cache-path>` entries to `file_paths.xml` to cover the cache directory used for temporary CSV files.
- **CSV Export Storage Access**: Moved CSV export from `Downloads/OBD2LPGLogger` (which requires special permissions on Android 10+) to the app's internal cache directory (`getCacheDir()`), which requires no permissions and is automatically cleaned by the OS.
- **FileWriter Resource Leak**: Wrapped `FileWriter` in try-with-resources in both `MainActivity` and `ReviewSessionActivity` export methods to prevent file handle leaks if `write()` throws an exception.
- **Data Loss on App Crash**: Reduced the flush interval from every 10 records to every 5 records, and added an immediate flush after writing the CSV header, reducing potential data loss from 9 records to 4.
- **CorrectionMap Files in History List**: Filtered out files starting with `CorrectionMap_` from the session history list to prevent users from accidentally opening tuning CSVs as log files (which would show an empty graph).
- **ReviewSessionActivity Crash on Destroy**: Added null check on `executor` in `onDestroy()` to prevent NPE if the activity is destroyed before the executor is initialized.

### Changed
- **Fuel Mode Prefix Logic**: Both `LoggerService.runLogger()` and `MainActivity.runLogger()` now use `config.fuelMode.name() + "_"` instead of a ternary `PETROL ? "Petrol_" : "LPG_"`, making the prefix future-proof for any new fuel modes.

### Removed
- **Dead Code Cleanup**: Removed 13 lines of commented-out debug code and an unused loop in `FuelMapView.exportCorrectionMapCsv()` that was left over from axis orientation experimentation.

## [2.6.0] - 2026-06-28
### Added
- **Smart Auto-Correction Grid (Tune Assist)**: Added a "Tune Assist" button on the Fuel Map which calculates the exact correction multiplier (% to add or subtract) for the gas ECU to reach the ideal fuel mixture. 
- **Export Tune Assist to CSV**: Added a button to export the Tune Assist map as a CSV file and share it via any installed app (Line, Email, Bluetooth) to be viewed on a tuning laptop. 

## [2.5.0] - 2026-06-28
### Added
- **In-App Session History & Viewer**: Added a new "History" tab that allows users to review previously recorded log files (.csv) directly within the app without exporting.
- Standalone "Review Session" Activity which parses historical log data in the background and plots it on the Fuel Map, fully supporting Petrol/LPG/Deviation overlays.

### Fixed
- Fixed an issue where historical logs were skipped during parsing if the vehicle lacked the `Fuel System Status` PID, resulting in no map data being plotted.
- Improved log file backward compatibility to gracefully handle logs recorded in older app versions.

## [2.4.0] - 2026-06-28
### Added
- Added Cell Lock & Hit Counter features to the Fuel Map with Dwell Time filtering (Debouncing) to ensure only steady-state data is plotted.
- Added visual opacity (Alpha Blending) indicators based on hit counts.
- Added a thick gold border for cells that are fully collected (20+ hits).

### Fixed
- Fixed an issue where cells lacking the `01 03` PID (Fuel System Status) or `01 05` PID (ECT) would completely block the Fuel Map from plotting.
- Re-labeled all remaining "LPG" UI strings to "LPG/CNG".

## [2.3.0] - 2026-06-28
### Added
- Added OBD2 PID 01 03 (Fuel System Status) to monitor open/closed loop state.
- Enhanced Fuel Map tab with live display of Engine Coolant Temperature (ECT) and Loop Status.
- Improved auto-tune calculation logic to plot fuel map data only when the engine is in a stable, closed-loop state and ECT is at least 80°C.

## [2.2.0] - 2026-06-27
### Added
- Local API Server (NanoHTTPD) to allow external AI Agents and MCP Clients to fetch live sensor data via local WiFi on Port 8080.
- Added API Server toggle in Settings tab with automatic local IP detection.

## [2.1.0] - 2026-06-27
### Added
- Manual Theme Selector (Day/Night Mode toggle) in the Settings tab, overriding system default.

## [2.0.0] - 2026-06-27
### Added
- Separated Fuel Maps for PETROL and LPG modes with visual 3D scatter plot.
- Multi-language support (English and Thai).
- Smart Tuning Analysis panel on the Map tab for real-time advice.
- Custom Log Destination Folder selection via Android Storage Access Framework (SAF).
- Display of the App Version in the Settings tab.
- **Auto-Detect Supported PIDs (Major Feature)**: The logger now automatically detects which OBD2 PIDs your vehicle supports when you click Connect, using the SAE J1979 standard PID availability bitmaps (PIDs 0x00, 0x20, 0x40). Only supported PIDs are polled, eliminating wasted bandwidth and timeout delays on unsupported PIDs — making each polling cycle 30-50% faster.
- **VIN-Based Brand/Year Profile (Fallback)**: If live PID detection fails (e.g. cheap ELM327 clones), the app falls back to a brand/year profile derived from the VIN's World Manufacturer Identifier (WMI) and model year code. Supports 30+ brands including Toyota, Honda, Ford, BMW, Mercedes, VW, Hyundai, and more.
- **PID Detection UI Feedback**: After connecting, the status bar shows "X/Y PIDs detected (live query)" or "X/Y PIDs (VIN profile fallback)" so you know exactly what's being logged.
- **DataWriter PID-Aware Constructor**: New `DataWriter(Context, String, List<PIDDefinition>)` constructor ensures CSV/JSONL headers match the actual PIDs being logged, even in LPG-only mode.
### Fixed
- **ReadinessMonitor bit masks (CRITICAL)**: Corrected SAE J1979 emission readiness monitor bit masks. The old code used duplicate bit positions for different monitors (e.g. Heated Catalyst used the same bit as Components, EVAP used wrong bit). Now correctly maps bits 1-7 in bytes B/C and bits in byte D per the J1979 specification. Added Particulate Filter and NOx/SOR monitors.
- **PIDParser.extractMulti unknown PID handling (CRITICAL)**: When an unknown PID hex was encountered in a multi-PID response, the parser left the index pointing mid-data, causing the outer loop to potentially find false frame headers (e.g. "41") inside data bytes. Now advances past unknown PID hex before breaking.
- **PIDParser.extractMulti outer loop boundary**: Changed outer while-loop condition from `idx < length - 2` to `idx <= length - 4` to ensure at least a 2-char mode header + 2-char PID can be read.
- **O2 Sensor STFT pseudo-PID parsing (CRITICAL)**: The "_B" pseudo-PIDs (e.g. "14_B" for O2 Sensor B1S1 STFT) were never matched in `extractMulti` because `getPidHex()` returns "14_B" which doesn't equal "14". Now `extractMulti` parses "_B" PIDs from the same raw data bytes as their parent PID, and skips "_B" PIDs when matching PID hex in the response.
- **SimulationDriver _B PID handling**: `queryPid()` now strips the "_B" suffix before matching PID hex, so simulated STFT values are returned correctly for pseudo-PIDs.
- **PIDParser.extractAndParse dead code**: Removed unused `bestValue` variable that was assigned but immediately returned in the same iteration.
- **LoggerService.stopLogging() hang (CRITICAL)**: Changed `executor.shutdown()` to `executor.shutdownNow()` so the logger thread's `Thread.sleep()` is interrupted immediately instead of waiting up to the full sample interval. Added `InterruptedException` handling in the `runLogger()` while-loop to exit gracefully on interrupt.
- **MainActivity.runLogger() non-interruptible sleep (CRITICAL)**: Replaced `SystemClock.sleep()` (which silently catches `InterruptedException`) with `Thread.sleep()` + explicit `InterruptedException` handling, so `shutdownNow()` can actually interrupt the logging loop.
- **MainActivity executor sharing (CRITICAL)**: Stopping logging via `shutdownNow()` previously killed any pending DTC/VIN/readiness reads because they shared the same `executor`. Added a separate `dtcExecutor` for all diagnostic operations (readDtcs, clearDtcs, readVin, checkReadiness). The `dtcExecutor` is shut down only in `onDestroy()`.
- **SerialDriver infinite busy-loop (CRITICAL)**: `sendCommand()` caught all `IOException` and continued, causing a 100% CPU spin when the Bluetooth stream was broken (non-timeout error). Now catches `InterruptedIOException` separately (continue on timeout) and breaks on other `IOException` (stream broken). Also documented that `BluetoothSocket` does not support `setSoTimeout()`.
- **WiFiDriver CPU busy-wait (PERFORMANCE)**: `sendCommand()` busy-waited on `SocketTimeoutException` with no sleep, spinning at 100% CPU for the entire connection timeout window. Added `Thread.sleep(10)` in the timeout catch block.
- **BleDriver silent write failure (CRITICAL)**: `gatt.writeCharacteristic()` return value was not checked — if it returned `false`, the BLE command was silently lost. Now checks the return value and returns an empty string immediately on failure. Added deprecation note for `setValue()`.
- **DataWriter per-record flush (PERFORMANCE)**: Both CSV and JSONL writers called `flush()` after every record, causing excessive fsync operations on flash storage (up to 20/sec at fast sampling). Now flushes every 10 records, with a final flush in `close()`.
- **DataWriter O(n²) PID lookup (PERFORMANCE)**: `writeRecord()` did a linear scan of `record.getSamples()` for each PID in the catalogue (O(P×S) per record). Now builds a `HashMap` for O(1) lookup.
- **DataWriter CSV column misalignment**: Added a new constructor `DataWriter(Context, String, List<PIDDefinition>)` that accepts the list of PIDs to log, so the CSV header matches the actual data columns. The old constructor delegates to the new one with `PIDCatalogue.getAll()`.
- **PIDParserTest broken tests**: Fixed test file that referenced non-existent `PIDParser.extractHex()` method and called `LPGAnalyzer.analyzeFuelTrim()` with wrong number of arguments. Rewrote all tests to match current API. Added new tests for `extractAndParse` edge cases, `extractMulti` multi-PID parsing, and `_B` pseudo-PID handling. Updated LPG-critical PID count assertion from 14 to 19.
### Changed
- **PIDParser.extractMulti _B PID matching**: The inner loop now skips PIDs whose `getPidHex()` contains "_" when matching response data, and instead parses them from their parent PID's data bytes after the parent is parsed.
### Added
- **USB Serial Support**: Added `usb-serial-for-android` dependency to support physical USB adapters like the vLinker FS USB.
- **Dynamic Permissions**: Added Android USB Host runtime permissions flow in `MainActivity` and `DriverFactory` for seamless USB connection.
- **Auto-VIN on Connect**: `LoggerService` now automatically reads the vehicle's VIN upon successful connection.
- **Live VIN Callback**: Added `onVinRead` to `LoggerCallback` so the dashboard updates the VIN instantly without waiting for a manual read.
- **Multi-PID Polling (Extreme Performance)**: Rewrote `ElmDriver.queryPidBatch()` and `PIDParser.extractMulti()` to group and send up to 6 PIDs in a single CAN request. This drastically reduces ELM327 overhead and serial latency, increasing the app's refresh rate by up to 5x.
- **Live Fuel Map (3D Scatter Plot)**: Created a new custom `FuelMapView` component and a dedicated "Fuel Map" tab. It draws a live 2D grid (RPM vs MAP) and plots STFT+LTFT averages, dynamically coloring cells from red (rich) to blue (lean) while driving.
- **Global Logging Button**: Replaced the dashboard start/stop buttons with a global `FloatingActionButton` that persists across all tabs for quick emergency stops.
- **Quick Fuel Mode Toggle**: Moved the Petrol/LPG selector to a prominent toggle switch on the Dashboard for quick switching while on the road.
### Changed
- **UI Scaling**: `GaugeView` and `GraphView` text sizes and paddings now scale dynamically based on device pixel density (`dp`/`sp`), preventing tiny text on high-resolution screens.
- **Gauge Bounds Math**: Fixed the circular arc radius calculations in `GaugeView` to ensure gauges never clip outside their bounding boxes on different screen ratios.
- **PID Parsing**: Overhauled `PIDParser.extractAndParse()` to process ELM327 responses line-by-line, intelligently handling multi-ECU responses and picking valid non-zero data (fixes sensors failing when transmission ECU answers first).
- **DTC Parsing**: Rewrote `DtcReader.parseDtcResponse()` to independently parse ECU lines and strip CAN frame index bytes, preventing false-positive DTC codes.
- **ELM327 Performance Optimization**: Injected `AT S0` (Spaces off), `AT AT 2` (Aggressive adaptive timing), and `AT ST 19` (100ms timeout) into `ElmDriver.initializeElm327()` to massively increase polling refresh rate.
- **LPG Critical Sensors**: Updated `PIDCatalogue.java` to flag Intake Manifold Pressure (MAP), Engine Load, and Timing Advance as `isLpgCritical`, ensuring they are logged during fast-polling LPG runs for accurate AI tuning analysis.
- **Layout Adjustments**: Applied `fitsSystemWindows="true"` to `activity_main.xml` to prevent the UI from hiding behind the status bar or navigation bar.
