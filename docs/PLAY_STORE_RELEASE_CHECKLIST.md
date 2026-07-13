# Play Store Release Checklist

Use this checklist before uploading a production release. A green local build is not
enough: Play Console policy declarations, signing identity, hardware behavior, and
store content must agree with the shipped artifact.

## Build and signing

- [ ] Set a new monotonically increasing `versionCode` and user-facing `versionName`.
- [ ] Configure the persistent upload keystore through CI secrets; never commit the keystore or passwords.
- [ ] Run `testDebugUnitTest`, `lintRelease`, `assembleRelease`, and `bundleRelease`.
- [ ] Verify the release APK with `apksigner` and the AAB with `jarsigner`.
- [ ] Upload the AAB to Play Console and confirm Play App Signing is enabled.
- [ ] Verify the Play-generated APKs and 16 KB page-size status in the bundle explorer.

## Play Console policy and privacy

- [ ] Publish a public, non-geofenced privacy policy URL and link it inside the app.
- [ ] Complete Data Safety for VIN, OBD telemetry/logs, Bluetooth, location/weather, local API, and any third-party SDK behavior.
- [ ] Complete the content rating, target audience, ads declaration, and app access instructions.
- [ ] Declare `connectedDevice` and `dataSync` foreground-service use cases with a short demonstration video.
- [ ] Explain Bluetooth/location/notification prompts at the point of use and request only what the selected feature needs.
- [ ] Document that the local AI/API server is opt-in, token-protected, and intended for a trusted local network.

## Product and hardware validation

- [ ] Test Honda/Toyota/Ford and at least one vehicle with unsupported/partial PIDs.
- [ ] Test E20/petrol and LPG/CNG map learning with real logs; confirm no false lean/rich conclusions.
- [ ] Test Bluetooth SPP, BLE, USB-OTG, Wi-Fi adapter, reconnect, background logging, and notification denial.
- [ ] Test DTC read/clear, freeze frame, Mode 08, battery/crank tester, AAD/MAD, VIN, and PID auto-detection.
- [ ] Test Android 12-16, light/dark theme, Thai/English, rotation, process recreation, and offline behavior.
- [ ] Keep a parked-vehicle safety warning visible for live logging and DTC clear actions.

## Store listing and support

- [ ] Prepare icon, phone screenshots, feature graphic, short/full descriptions, and a support email.
- [ ] Do not claim universal manufacturer coverage; describe generic OBD-II coverage and adapter limitations.
- [ ] Provide app-access steps for reviewers using Simulation mode when hardware is unavailable.
- [ ] Define pricing, refunds, terms/EULA, and Play Billing behavior if subscriptions or in-app purchases are added.
