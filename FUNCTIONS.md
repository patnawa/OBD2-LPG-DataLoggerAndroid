# TunerMap Pro v3.23.0 — Full Function List

### LIVE DATA / REAL-TIME MONITORING
| Dashboard     | 2x4 telemetry grid: RPM \| Speed \| Coolant \| Voltage \| km/L \| Boost \| DPF \| DTC |
|---------------|----------|
| Gauges        | 4 analog gauges (tapered needle, peak hold, color themes) |
| Graphs        | 5 rolling line charts (auto-scale, fill area) |
| Readings      | Full PID table with values + ok/err status |
| Status Strip  | Bottom bar: RPM, Speed, Voltage, Boost psi, km/L, DTC badge |
| PID Swap      | Long-press any gauge/card/graph to choose PID |

### DERIVED SENSORS (v3.9.0)
| Fuel Economy  | km/L + L/100km from MAF+Speed. Auto AFR/density per fuel mode. Null-safe at idle. |
|---------------|----------|
| Turbo Boost   | kPa + psi from MAP-Baro. Baro 0x33 auto-polled, sea-level fallback 101.3 kPa. |
| DPF Status    | 5 PIDs: Soot (0x7A), Temp (0x7B), Delta (0x85), Regen (0x8C), Ash (0x8B). Health: Clean/Moderate/Warning/Critical. Auto-detect diesel from VIN. |
| AeroDensity   | AAD/MAD/BAD/Density%/DensityAlt/SAE J1349 CF/Grains — Open-Meteo API status + last fetch time shown in dialog |

### LPG/CNG TUNING
| Fuel Map      | 2D grid (RPM x MAP kPa) — STFT+LTFT color-coded |
|---------------|----------|
| Dual Fuel     | Petrol + LPG layers, side-by-side comparison |
| Deviation     | LPG Trim - Petrol Trim per cell (+ lean / - rich) |
| Tune Assist   | Auto-calculated % correction for LPG ECU multiplier |
| CSV Export    | Correction grid as CSV (MAP kPa header) for tuning laptop |
| Closed-Loop   | PID 03 closed loop + ECT >= 80 degC gate |
| LTFT Fallback | LTFT-only when STFT unavailable (Toyota/Honda) |
| Cell Lock     | Sliding-window debounce (in LiveMapStore); golden border at 20+ hits |
| Log Compare   | Petrol + LPG logs on one map |
| Fuel Switch   | Live mid-session Petrol/LPG toggle |
| Unified Binning| MapBinning.java — FLOOR-based RPM + closest-bin MAP, shared by UI/API/SSE |
| LiveMapStore  | Single source of truth — snapshot() for reads, pushSample() for writes |
| Zone Analysis | Idle/Cruise/Acceleration/FullLoad zones with avg deviation + confidence |

### DIAGNOSTICS
| DTC Scan      | Mode 03 (stored) + 07 (pending) + 0A (permanent) |
|---------------|----------|
| Deep Scan     | 7 protocol buses: HS-CAN, MS-CAN, CAN 29-bit, CAN 250k, KWP2000, ISO 9141, J1850 VPW |
| ECU Database  | 40+ CAN IDs: Toyota, Honda, Mazda, Isuzu, Nissan, Mitsubishi, Ford |
| DTC Enrich    | 157 codes with causes, fixes, emissions flags, drive cycles |
| DTC OTA Update| Free GitHub-based OTA — downloads updated DTC JSON files from raw.githubusercontent.com with versioned manifest. Throttled to 1 check per 6h. Atomic write with JSON validation. No backend required. |
| DTC Telemetry | Crowdsourced unknown-DTC reporting — auto-creates GitHub Issues for codes not in local database. Rate-limited to 1 report per unique code per 7 days. No VIN or personal data sent. |
| Scan Compare  | NEW + CLEARED vs previous session |
| Clear DTC     | Mode 04 with confirmation |
| Freeze Frame  | Per-DTC snapshot, 10 PIDs |
| Mode 06       | Catalyst, O2, EGR, EVAP, Misfire monitor tests |
| Readiness     | 12 monitors incl. Particulate Filter |
| VIN Reader    | Mode 09 PID 02 via multi-frame ISO-TP. Auto-detect diesel. |
| Cal-ID/CVN    | Mode 09 calibration verification |
| DTC History   | SQLite per-VIN scan history |
| Report Export | PDF diagnostic report |
| New DTC Alert | Push notification on new fault during logging |

### BATTERY TESTER
| Full Diag       | 11 automated tests, grade A+ to F |
|-----------------|----------|
| Resting V        | SoC% from open-circuit voltage |
| Cranking V       | Fast 80ms sampling, 5s capture |
| Alternator       | Idle + high-RPM regulated output |
| Voltage Drop     | No-load vs full-load |
| Recovery         | Voltage bounce after load dump |
| Ripple/Diode     | 20-sample AC ripple burst |
| Parasitic Drain  | Voltage decay rate |
| Chemistry        | Flooded, AGM, EFB, Gel, Calcium, LiFePO4 |
| Live Graph       | Real-time voltage with threshold bands |
| Battery Life     | Remaining months estimate |

### DATA LOGGING
| Dual Format   | CSV (RFC-4180) + JSONL |
|---------------|----------|
| 45+ PIDs      | SAE J1979 standard + DPF + derived sensors |
| PID Detection | Bitmap query 0x00/0x20/0x40 — only supported PIDs |
| VIN Fallback  | Brand/year profile when detection fails |
| Background    | Foreground service, PARTIAL_WAKE_LOCK |
| Config Save   | SharedPreferences + instant-save checkboxes |
| Custom Folder | SAF picker: Downloads, SD card, USB, Drive |
| Filename      | Fuel-mode prefix: PETROL_... or LPG_... |
| Session Meta  | session_info.json per session |
| Replay        | Open past CSV for offline analysis |

### API SERVER (NanoHTTPD :8080)
| GET  /api/ping           | Heartbeat |
|--------------------------|----------|
| GET  /api/status         | Connection + fuel mode + VIN |
| GET  /api/data           | All live sensors (JSON) |
| GET  /api/config         | Logger config |
| GET  /api/pids           | PID catalogue |
| GET  /api/map            | Binned fuel map (from LiveMapStore), min_hits filter |
| GET  /api/map/summary    | Map stats + tuning recommendation |
| POST /api/map/import     | Import map session JSON |
| GET  /api/map/export     | Download correction CSV (MAP kPa header) |
| DELETE /api/map          | Reset map data |
| GET  /api/agent          | AI Agent aggregate: status + sensors + map summary + zones + hotspots + DTCs (500ms cache) |
| GET  /api/stream         | SSE: sensor data + map_update + map_summary push events |
| CORS                     | Enabled — web AI agents + MCP |

### SSE (Server-Sent Events) — Realtime Push
| event: sensor       | Per-record sensor data push |
|---------------------|----------------------------|
| event: map_update   | Per-record: current cell, trim avg, hits, deviation |
| event: map_summary  | Every 5 records: cell counts, avg/max deviation, health trend |

### AI AGENT TUNING INTELLIGENCE
| Zone Analysis  | Idle (500-1000) / Cruise (1500-3000) / Acceleration (2500-4500) / FullLoad (4000-6500) |
|----------------|----------------------------------------------------------------------------------------|
| Hotspots       | Top 20 cells with |deviation| > 5%, sorted by severity, with suggested correction % |
| Confidence     | HIGH (20+ hits) / MEDIUM (10-19) / LOW (<10) / NONE (0) per cell and per zone |
| Snapshot Cache | 500ms TTL — frequent polling doesn't recompute full map analysis |

### UI / UX
| 6 Tabs        | Dashboard, Gauges, Map, DTC, Battery, Logs |
|---------------|----------|
| Thai+English  | Full localization values-th/strings.xml |
| Dark/Light    | System, Light, or Dark mode |
| Keep Screen   | Prevent sleep while foreground |
| Home Menu     | Card navigation + live vehicle status |
| History       | Browse, open, compare, select multiple sessions, batch share, batch delete |
| Play release  | Signed APK + AAB build, signature verification, SHA-256 checksums, Play Store readiness checklist |
| Filter PIDs   | Select which PIDs to show in Live Readings (theme-fixed) |
| Settings      | Transport, WiFi, BT, USB, fuel, protocol, interval, toggles |

### ADAPTER SUPPORT
| WiFi        | vLinker MC WiFi (MIC3313) + generic ELM327 TCP |
|-------------|----------|
| USB         | vLinker FS USB (MIC3322) + CH340/CP2102/FTDI/Prolific |
| Bluetooth   | SPP (SerialDriver) + BLE GATT (BleDriver) |
| Simulation  | No hardware — full feature test |
| Auto-Probe  | USB > WiFi > BT paired > BT scan > Simulation |
| Multi-PID   | Up to 6 per batch (vLinker) / 4 (generic) |
| vLinker     | Firmware-specific AT optimizations |

### CUSTOMIZATION
| Custom PID     | JSON in SharedPreferences, CRUD, formula tester |
|----------------|----------|
| PID Swap       | Long-press any widget to change PID |
| Custom Folder  | SAF log destination picker |
| Feature Flags  | 4 toggles: Turbo, Fuel, DPF, Custom PID — instant-save |
| Deep Scan      | Auto-enabled for diesel; 7 buses with scan status table |
| Auto-Detect    | Diesel from VIN WMI (MPA/MNB/MMB/MR0) — DPF+DeepScan auto-on |

### DTC OTA UPDATE SYSTEM (v3.23.0)
| Component      | Description |
|----------------|-------------|
| DtcUpdater     | Background OTA — fetches manifest.json from GitHub, compares versions, downloads only changed files. 6h throttle. Atomic temp-file + rename. JSON validated before commit. |
| Manifest       | `dtc_updates/manifest.json` — versioned file list. Bump version number to trigger update. |
| Storage        | OTA files saved to app internal storage (getFilesDir). Loaded before bundled assets. |
| TelemetryClient| Auto-creates GitHub Issue when unknown DTC encountered. 7-day per-code dedup. No VIN/personal data. Optional GITHUB_TOKEN BuildConfig for higher rate limits. |
| DtcDatabase    | Modified to check internal storage (OTA) first, then fall back to bundled assets. Added getAppContext() + getCurrentBrand() for telemetry. |

### VEHICLE BRAND SUPPORT (37 brands via VIN WMI)
| Region         | Brands |
|----------------|--------|
| Japanese       | Toyota, Lexus, Honda, Isuzu, Nissan, Mitsubishi, Mazda, Suzuki, Subaru, Hino |
| Korean         | Hyundai, Kia |
| European       | Volvo, BMW, Mercedes-Benz, Volkswagen, Audi, Porsche, Renault, Peugeot, Citroën, Fiat, Land Rover |
| American       | Ford, Chevrolet, Jeep, Dodge, Chrysler, Tesla |
| Chinese EV     | BYD, GWM (Haval/Ora), NETA (Hozon), AION (GAC), Deepal (Changan), MG (SAIC) |
| Indian         | Tata, Mahindra |

### OBD2 PROTOCOL SUPPORT (13 protocols)
| Protocol                  | ELM ATSP |
|---------------------------|----------|
| Auto                      | ATSP0 |
| SAE J1850 PWM             | ATSP1 |
| SAE J1850 VPW             | ATSP2 |
| ISO 9141-2                | ATSP3 |
| ISO 14230-4 KWP 5-baud    | ATSP4 |
| ISO 14230-4 KWP fast      | ATSP5 |
| ISO 15765-4 CAN 11-bit 500| ATSP6 |
| ISO 15765-4 CAN 29-bit 500| ATSP7 |
| ISO 15765-4 CAN 11-bit 250| ATSP8 |
| ISO 15765-4 CAN 29-bit 250| ATSP9 |
| SAE J1939 CAN             | ATSPA |
| User1 CAN                 | ATSPB |
| User2 CAN                 | ATSPC |
