# OBD2 LPG Logger Android App

Native Android port of the OBD2 data logging system, now at version **2.2.0**.

This Android app support OBD2 adapter : Support vlinker FS MC bluetooth Wifi Serial OTG OBD adapters and AI Agent.

## What was ported and added

- **Core Logging**: PID catalogue and safe formula parser for `A`/`B` OBD2 responses.
- **Drivers**: Support for Simulation, WiFi TCP, Bluetooth SPP, and USB OTG Serial adapters (CH340, CP2102, FTDI, Prolific).
- **Auto-connect**: Tries WiFi TCP first, then selected Bluetooth SPP device, then paired devices, then USB, and finally simulation fallback.
- **Flexible Logging**: CSV + JSONL logging to any user-selected folder (via Storage Access Framework). Logging runs continuously as a foreground service.
- **LPG Tuning Analysis**: Real-time analysis of `STFT + LTFT` with LEAN/RICH/OK recommendations.
- **DTC & VIN Scanning**: Reads current diagnostic trouble codes (DTC) and retrieves the Vehicle Identification Number (VIN).
- **Readiness Monitors**: Checks vehicle inspection readiness (Misfire, Fuel System, O2 Sensors, etc.).
- **O2 Sensor Logging**: Reads all 8 standard OBD2 O2 sensor PIDs (0x14-0x1B) as voltage (V).
- **Day/Night Theme**: Choose between System, Light, and Dark mode in settings.
- **AI Agent API Server**: Built-in HTTP server (`NanoHTTPD`) running on port `8080`. AI Agents and MCP Clients can read live JSON data on the same WiFi network with zero impact on vehicle polling speed.

## Endpoints (AI Agent)

Enable the API server in Settings to expose:

- `GET /api/status`: Returns connection status, fuel mode, and VIN.
- `GET /api/data`: Returns the latest polled sensor data array in JSON format.

## Build

From the project root:

```bash
export JAVA_HOME="$HOME/jdk-17.0.19+10"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew clean :app:testDebugUnitTest :app:assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Runtime notes

- **WiFi**: Default is `192.168.0.10:35000`. You can change this in the Settings tab.
- **Bluetooth**: Select a paired ELM327 device from the dropdown. No MAC address typing required.
- **USB**: Requires a compatible USB-to-Serial adapter connected via OTG. Grant USB permissions when prompted.
- **Background Logging**: The app runs a persistent foreground service. You can lock the screen or switch apps without dropping the OBD2 connection.
- **User Guide**: An in-app HTML user guide (Thai/English) is provided for easy troubleshooting and reference.
