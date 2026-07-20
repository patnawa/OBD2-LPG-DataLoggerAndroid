# ANCEL X6 clean-room parity plan

The `ancel-x6-backup-2026-07-18` directory is an installed commercial runtime,
not an Android source project. Its diagnostic behavior is split across the APK,
ARMv7 native libraries, ANCEL VCI transport code, and roughly 19 GB of opaque
vehicle/function modules. Those binaries assume the vendor package, hardware,
activation state, and backend, so they are not copied into TunerMap Pro.

This project treats the backup as a behavioral inventory and implements
portable equivalents against documented vehicle protocols.

## Coverage

Already present in TunerMap Pro:

- standard live OBD data and logging;
- stored, pending, and permanent DTCs;
- multi-bus scan and observed module inventory;
- freeze frames, readiness, Mode 06, VIN, Cal-ID, and CVN;
- reports, history, and log playback;
- Bluetooth, BLE, Wi-Fi, and USB ELM327/vLinker transports.

First parity feature implemented from the inventory:

- per-module ECU Information using a curated set of 19 standardized ISO 14229
  identification DIDs (`F180`–`F19D`) on the current live ISO-TP CAN bus.
  Targets come from a known scan-derived physical TX/RX pair or conservative legislated
  powertrain defaults. Positive results persist per valid VIN and can be viewed
  offline. The request surface is limited to read-only service `0x22`.

## Confirmed ANCEL hardware target

The owner reports having the ANCEL interface associated with this backup. The
backup confirms that its private device serial is used with the MCU ID in
module validation/licensing records; that serial and the associated MCU token
are intentionally not copied into source code or documentation.

Runtime logs show the VCI connecting over both Bluetooth and USB and exchanging
proprietary framed messages with the X6 native layer. It is not an ELM327 text
command endpoint. The APK also bundles X6-specific link firmware and a native
vehicle-interface library, confirming that this is a firmware/native transport
contract rather than a generic serial profile. Supporting the owned VCI
therefore requires a separate `AncelVciDriver` backed by a documented vendor
SDK or a clean-room capture of the Bluetooth services / USB descriptors and
framing. The opaque native X6 libraries and credentials are not transplanted
into this app.

## Hardware-dependent work

The X6 advertises OEM active tests and service workflows such as ABS bleeding,
battery registration, DPF service, EPB maintenance, immobilizer/key functions,
injector coding, oil reset, steering-angle calibration, throttle adaptation,
and TPMS. These are not universal OBD commands. They vary by make, model, year,
ECU, session, security algorithm, prerequisites, and adapter routing.

Implementing them requires an explicit supported hardware target and legally
obtained OEM command definitions. A production design also needs an exclusive
diagnostic-session state machine, tester-present timing, prerequisite checks,
user-visible abort/recovery, and hardware-in-loop tests. Generic labels over
unverified Mode 08 or UDS routine commands are not treated as feature parity.

Until that contract exists, the app intentionally exposes no UDS session
control, security access, routine control, ECU reset, coding, programming, or
write-data API.

## Recommended sequence

1. Characterize the owned ANCEL VCI transport: Bluetooth service UUIDs, USB
   vendor/product/interface descriptors, framing, firmware handshake, and a
   read-only loopback/capability probe. Keep credentials out of captures.
2. Implement an opt-in `AncelVciDriver` with transcript fixtures and automatic
   fallback to the existing ELM327/vLinker transports.
3. Add read-only OEM system discovery and definition packs for the first named
   make/model/year range.
4. Extend per-module DTC, freeze-frame, and live-data coverage from documented
   definitions.
5. Add guarded service routines one at a time, with vehicle-specific
   prerequisites and hardware tests.
6. Defer key programming, VIN/mileage writes, ECU replacement/coding, and
   firmware programming until authorization and recovery requirements are
   explicit.
