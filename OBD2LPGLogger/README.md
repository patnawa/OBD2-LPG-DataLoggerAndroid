# OBD2 LPG Logger Android App

Native Android port of `obd2_loggerv4.2.py`.

## What was ported

- PID catalogue and safe formula parser for `A`/`B` OBD2 responses.
- Simulation, WiFi TCP, and Bluetooth SPP drivers.
- Auto mode: tries WiFi TCP first, then Bluetooth SPP, then simulation fallback.
- CSV + JSONL logging to `Downloads/OBD2LPGLogger`.
- LPG fuel-trim analysis (`STFT + LTFT`) with LEAN/RICH/OK recommendation.
- Oxygen sensor detection and logging: all 8 standard OBD2 O2 sensor PIDs (0x14-0x1B, Bank 1/2 Sensor 1-4) are queried and logged as voltage (V).
- Continuous logging mode: record time is unlocked; logging runs until you press Stop.
- Dark dashboard-style live UI for critical LPG PIDs.

## Build

From this directory:

```bash
export JAVA_HOME="$HOME/jdk-17.0.19+10"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew clean :app:testDebugUnitTest :app:assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Logs are written to:

```text
/storage/emulated/0/Download/OBD2LPGLogger/
```

On Android 10+ the app uses `MediaStore.Downloads`, so it does not rely on legacy broad file permissions. The UI shows the concrete `content://` locations for the current session CSV and JSONL files. Tap the log path box to open the log folder; if the installed file manager cannot open folders directly, the app falls back to opening the latest CSV/JSONL file.

## Runtime notes

- WiFi mode expects an ELM327/WiFi OBD2 adapter at `192.168.0.10:35000` by default.
- Bluetooth SPP mode now lists paired Bluetooth devices; no MAC address needs to be typed manually.
- Auto mode tries WiFi TCP first, then the selected Bluetooth SPP device, then other paired Bluetooth devices, then simulation fallback.
- USB serial is not implemented in this first Android build because Android USB serial needs a device permission flow plus a USB-serial driver library.
- Keep the phone screen awake during long logging sessions.
