# Custom Rules for OBD2LPGLogger

## 1. Track Revisions
Every time you make a notable change, fix a bug, or add a feature, you MUST append a record of the change to the `CHANGELOG.md` file located at the project root. Ensure you categorize it correctly under `[Unreleased]` (or the current date) as `Added`, `Changed`, `Fixed`, etc.

## 2. Java Build & Test Environment
- **JDK Requirement:** Always build using JDK 17 (Eclipse Temurin JDK 17). Do NOT use Java 21 (Android Studio JBR).
- **Environment Variables:**
  - `JAVA_HOME`: `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`
  - `ANDROID_HOME`: `C:\Users\Alpha\Android\Sdk`
- **Gradle Commands:**
  - Compile: `./gradlew.bat compileDebugJavaWithJavac`
  - Unit Tests: `./gradlew.bat testDebugUnitTest`
  - Build Debug APK: `./gradlew.bat assembleDebug`

## 3. Adding PIDs & Syncing Displays
- **Catalogue Addition:**
  1. Add a `PIDDefinition` in `PIDCatalogue.buildAll()`.
  2. Implement simulation response in `SimulationDriver.queryPid()`.
  3. Write test cases in `PIDParserTest.java`. Always pass at least `dataBytes * 2` hex chars in test strings.
  4. Add the PID hex to `BrandYearProfile.getProfile()` if post-2008 or brand-specific, and to `PidAvailabilityChecker.getSimulationProfile()`.
- **Syncing Displays/Reports:**
  - Adding a PID to the catalogue polls and logs the data, but **does not** display it. You must manually sync:
    1. Live display functions (e.g. `_print_cycle_summary` in the Python logger, or `MainActivity` layout updater).
    2. Analysis reports (e.g. `REPORT_KEYS` in `LPGAnalysisReport` / `ReviewSessionActivity`).
    3. Flag/threshold logic (such as checking warning conditions for the new PID).

## 4. OBD2 Protocol & Parser Rules
- **dataBytes Spec:** Ensure `dataBytes` matches SAE J1979 exactly (e.g., PID 0x03 is 2 bytes, Lambda 0x34 & 0x44 are 4 bytes). Any mismatch will cause silent cascading failures in multi-PID queries, causing subsequent PIDs in the chunk to fail.
- **Pseudo-PIDs:** Suffixes like `_B` (e.g., `14_B`) represent secondary bytes. Filter out these pseudo-PIDs from the raw ELM327 command strings to avoid invalid commands, but keep them in the processing queue for parser extraction.
- **STFT/LTFT Range:** Maximum value for fuel trims must be `99.22` (not `99.2`) to handle `0xFF` (99.21875%) boundary value safely.

## 5. Hardware Optimizations & Driver Rules
- **vLinker Optimizations:** Always use `ATAL` (Allow Long) instead of `ATNL` (Normal Length) during init to avoid truncating multi-PID response bytes.
- **USB Buffer Management:** Always clear both TX and RX buffers via `usbSerialPort.purgeHwBuffers(true, true)` before sending any write command to prevent corrupted responses.
- **Wakelocks & Screen On:** Use `FLAG_KEEP_SCREEN_ON` on the Window for keeping screen active (no special permissions needed). Use `PARTIAL_WAKE_LOCK` only in background `LoggerService` to keep CPU awake.
- **Thread Interruption:** In loops that must be interruptible (like logger polling loops), use `Thread.sleep()` and handle `InterruptedException` instead of `SystemClock.sleep()` (which silently consumes interrupts).

## 6. UI, Localization, & Battery Voltage Rules
- **No Hardcoded Strings**: All labels, description cards, and section titles on the Home screen and other screens must be bound to `@string` resources to support localization (Thai/English). Always update both `values/strings.xml` and `values-th/strings.xml`.
- **Telemetry Configuration Single-Tap**: Do not clear card click listeners inside layout setups (like `setupDashboard()`, `setupGauges()`, or `setupGraphs()`). Global click handling is managed in `setupDynamicPids()` (single-tap to configure).
- **Battery Voltage Reading**: When reading battery voltage, always use the fallback-ready mechanism. Try querying OBD2 PID `01_42` first. If it returns null or fails, fall back to executing the ELM327 command `AT RV` raw to support older cars lacking PID `01_42`.
- **Smart Tuning Panel Layout**: Update the separate TextView widgets (`txtTuningStft`, `txtTuningLtft`, `txtTuningStatus`, `txtTuningAdvice`) when updating fuel trim analyzer states, rather than writing a single flat formatted block of text to `tuningStatusText`.

