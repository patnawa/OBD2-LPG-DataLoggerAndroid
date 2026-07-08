# Changelog

All notable changes to this project will be documented in this file.

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
