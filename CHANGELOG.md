# Changelog

All notable changes to this project will be documented in this file.

## [2.5.0] - 2026-06-28
### Added
- **In-App Session History & Viewer**: Added a new "History" tab that allows users to review previously recorded log files (.csv) directly within the app without exporting.
- Standalone "Review Session" Activity which parses historical log data in the background and plots it on the Fuel Map, fully supporting Petrol/LPG/Deviation overlays.


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
