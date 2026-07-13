# DTC Database JSON Format Guide

This guide explains how to create and update DTC (Diagnostic Trouble Code) JSON files for the OBD2LPGLogger app, including how to deliver them via the free OTA update system.

---

## 1. File Types

The app uses **three types** of JSON files. Each has a different format:

| Type | Filename Pattern | Purpose |
|------|-----------------|---------|
| **DTC Description** | `dtc_*.json` | Maps a DTC code to a human-readable description |
| **DTC Enrichment** | `dtc_enrichment.json` | Adds causes, fixes, severity, and system info per code |
| **OTA Manifest** | `dtc_updates/manifest.json` | Tracks version numbers for OTA updates |

---

## 2. DTC Description Format (`dtc_*.json`)

This is the primary format. A flat JSON object mapping DTC codes to descriptions.

### Structure

```json
{
  "PCODE": "Description of the fault",
  ...
}
```

- **Key**: The DTC code (1 letter + 4 hex digits), always uppercase
- **Value**: Human-readable description string

### DTC Code Format

```
[Letter][Digit1][Digit2][Digit3][Digit4]
   │       │      │      │      │
   │       │      │      │      └─ 4th hex digit (0-F)
   │       │      │      └──────── 3rd hex digit (0-F)
   │       │      └─────────────── 2nd hex digit (0-F)
   │       └────────────────────── 1st digit: 0=generic, 1=brand-specific, 2-3=pending
   └─────────────────────────────── System: P=Powertrain, C=Chassis, B=Body, U=Network
```

### Example: Generic Database (`dtc_database.json`)

```json
{
  "P0000": "No trouble code",
  "P0001": "Fuel Volume Regulator Control Circuit / Open",
  "P0002": "Fuel Volume Regulator Control Circuit Range/Performance",
  "P0100": "MAF Sensor Circuit Malfunction",
  "P0171": "System Too Lean (Bank 1)",
  "P0300": "Random/Multiple Cylinder Misfire Detected",
  "P0420": "Catalyst System Efficiency Below Threshold (Bank 1)",
  "C0050": "Steering Position Sensor Malfunction",
  "B1000": "Driver Airbag Circuit Open",
  "U0100": "Lost Communication With ECM/PCM A"
}
```

### Example: Brand-Specific Database (`dtc_byd.json`)

```json
{
  "P1A00": "High Voltage Battery System Malfunction (BYD)",
  "P1A01": "High Voltage Battery Isolation Fault (BYD)",
  "P1A30": "Motor Inverter Malfunction (BYD)",
  "P1A50": "Onboard Charger (OBC) Malfunction (BYD)",
  "P1A60": "DC-DC Converter Malfunction (BYD)",
  "P1AC0": "Charging System Malfunction (BYD)",
  "P1AD0": "Gear Shift System Malfunction (BYD)"
}
```

### Rules

1. **Keys must be uppercase**: `"P0171"` is correct, `"p0171"` is wrong
2. **One code per key**: No duplicate keys
3. **Brand-specific files use P1xxx and P3xxx**: Generic codes (P0xxx) go in `dtc_database.json`
4. **Append brand name in description**: e.g. `"...(BYD)"` to distinguish from generic descriptions
5. **No trailing commas**: Standard JSON — last entry must not have a comma

---

## 3. DTC Enrichment Format (`dtc_enrichment.json`)

This optional file adds causes, fixes, severity, and system categorization per DTC code.

### Structure

```json
{
  "PCODE": {
    "causes": ["cause 1", "cause 2", ...],
    "fixes": ["fix 1", "fix 2", ...],
    "emissions_related": true|false,
    "drive_cycles_to_clear": 3,
    "severity": "critical"|"warning"|"info",
    "system": "System Name"
  }
}
```

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `causes` | String array | List of possible causes for this DTC |
| `fixes` | String array | List of recommended fixes/diagnostic steps |
| `emissions_related` | Boolean | Whether this code affects emissions/MIL |
| `drive_cycles_to_clear` | Integer | Number of successful drive cycles to auto-clear |
| `severity` | String | `"critical"`, `"warning"`, or `"info"` |
| `system` | String | System category (e.g. `"Intake/Air"`, `"Fuel"`, `"Ignition"`) |

### Example

```json
{
  "P0171": {
    "causes": [
      "Vacuum leak in intake manifold",
      "Dirty or failing MAF sensor",
      "Low fuel pressure",
      "Faulty oxygen sensor"
    ],
    "fixes": [
      "Perform smoke test for vacuum leaks",
      "Clean or replace MAF sensor",
      "Check fuel pressure and fuel pump",
      "Inspect oxygen sensor wiring and output"
    ],
    "emissions_related": true,
    "drive_cycles_to_clear": 3,
    "severity": "warning",
    "system": "Fuel/Air"
  },
  "P0420": {
    "causes": [
      "Catalytic converter degradation",
      "Oxygen sensor malfunction",
      "Exhaust leak upstream of catalytic converter",
      "Engine misfire causing catalyst damage"
    ],
    "fixes": [
      "Check catalytic converter efficiency",
      "Replace upstream and downstream O2 sensors",
      "Repair exhaust leaks",
      "Address any misfire codes first"
    ],
    "emissions_related": true,
    "drive_cycles_to_clear": 3,
    "severity": "critical",
    "system": "Emissions/Exhaust"
  }
}
```

---

## 4. OTA Manifest Format (`dtc_updates/manifest.json`)

This file tells the app which DTC files have updates available. It lives in the `dtc_updates/` folder at the root of your GitHub repo.

### Structure

```json
{
  "version": 1,
  "updated": "YYYY-MM-DD",
  "files": [
    { "name": "dtc_database.json", "version": 5 },
    { "name": "dtc_toyota.json",   "version": 2 },
    { "name": "dtc_byd.json",      "version": 1 }
  ]
}
```

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `version` | Integer | Manifest schema version (always `1` for now) |
| `updated` | String | Date of last update (YYYY-MM-DD format) |
| `files` | Array | List of DTC files available for OTA |
| `files[].name` | String | The DTC JSON filename (must match the asset filename) |
| `files[].version` | Integer | Version number of this file — **bump this when you update the file** |

### How OTA Versioning Works

```
App startup
   │
   ├─ Fetch manifest.json from GitHub (throttled to once per 6 hours)
   │
   ├─ For each file in manifest:
   │    ├─ Read local .ver file from internal storage
   │    ├─ If remote version > local version → download the file
   │    └─ If remote version <= local version → skip
   │
   ├─ Save downloaded file to internal storage (atomic write)
   └─ Save .ver file with the new version number
```

**Rule**: When you update a DTC file in `dtc_updates/`, you MUST also bump its version number in `manifest.json`. The app will not re-download a file whose version hasn't changed.

---

## 5. Step-by-Step: How to Add New DTC Codes via OTA

### Scenario: You discovered 5 new BYD EV codes from GitHub Issues

#### Step 1: Create the updated DTC file

Create `dtc_updates/dtc_byd.json` (or edit if it already exists):

```json
{
  "P1A00": "High Voltage Battery System Malfunction (BYD)",
  "P1A01": "High Voltage Battery Isolation Fault (BYD)",
  "P1A30": "Motor Inverter Malfunction (BYD)",
  "P1A50": "Onboard Charger (OBC) Malfunction (BYD)",
  "P1A60": "DC-DC Converter Malfunction (BYD)",
  "P1AC0": "Charging System Malfunction (BYD)",
  "P1AD0": "Gear Shift System Malfunction (BYD)",

  "P1B00": "Battery Cell Voltage Difference Exceeded (BYD)",
  "P1B01": "Battery Pack Self-Discharge Excessive (BYD)",
  "P1B02": "Battery Thermal Runaway Warning (BYD)",
  "P1B03": "Battery Pack Self-Heating Failure (BYD)",
  "P1B04": "Battery Cold Start Performance Degradation (BYD)"
}
```

Note: You must include ALL codes in the file, not just the new ones. The OTA file completely replaces the bundled asset file.

#### Step 2: Update the manifest

Edit `dtc_updates/manifest.json` and bump the version for `dtc_byd.json`:

```json
{
  "version": 1,
  "updated": "2026-07-13",
  "files": [
    { "name": "dtc_database.json", "version": 1 },
    { "name": "dtc_toyota.json",   "version": 1 },
    { "name": "dtc_byd.json",      "version": 2 },
    { "name": "dtc_aion.json",     "version": 1 }
  ]
}
```

Changed `dtc_byd.json` from `version: 1` to `version: 2`.

#### Step 3: Commit and push

```bash
git add dtc_updates/dtc_byd.json dtc_updates/manifest.json
git commit -m "Add 5 new BYD EV DTC codes (P1B00-P1B04)"
git push
```

#### Step 4: Done

All apps will download the update within 6 hours. No APK update needed. No server needed.

---

## 6. File Naming Convention

DTC files must match the mapping in `VinBrandDetector.getDtcDatabaseAsset()`:

| Brand | DTC File | Notes |
|-------|----------|-------|
| Generic (all brands) | `dtc_database.json` | Standard SAE codes (P0xxx, C0xxx, etc.) |
| Toyota / Lexus | `dtc_toyota.json` | Lexus shares Toyota codes |
| Honda | `dtc_honda.json` | |
| Isuzu | `dtc_isuzu.json` | |
| Nissan | `dtc_nissan.json` | |
| Mitsubishi | `dtc_mitsubishi.json` | |
| Mazda | `dtc_ford.json` | Shares Ford architecture |
| Suzuki | `dtc_suzuki.json` | |
| Ford | `dtc_ford.json` | |
| Chevrolet | `dtc_chevrolet.json` | |
| Hyundai / Kia | `dtc_hyundai.json` | Kia shares Hyundai codes |
| Volvo | `dtc_volvo.json` | |
| BMW | `dtc_bmw.json` | |
| Mercedes-Benz | `dtc_mercedes.json` | |
| BYD | `dtc_byd.json` | |
| GWM (Haval/Ora) | `dtc_gwm.json` | |
| NETA (Hozon) | `dtc_neta.json` | |
| AION (GAC) | `dtc_aion.json` | |
| Deepal (Changan) | `dtc_deepal.json` | |
| MG (SAIC) | `dtc_mg.json` | |
| Tesla | `dtc_tesla.json` | |

---

## 7. Validation Checklist

Before pushing a DTC file update, verify:

- [ ] All keys are uppercase (e.g. `"P0171"`, not `"p0171"`)
- [ ] All keys follow the format: 1 letter + 4 hex digits (P/C/B/U + 0-9/A-F)
- [ ] No duplicate keys in the file
- [ ] No trailing commas after the last entry
- [ ] JSON is valid (test with `jsonlint.com` or `python -m json.tool file.json`)
- [ ] Brand-specific descriptions include the brand name suffix (e.g. `"(BYD)"`)
- [ ] For OTA: the full file is included (not just new codes — OTA replaces the entire file)
- [ ] For OTA: the version number in `manifest.json` was bumped
- [ ] For OTA: the `updated` date in `manifest.json` was changed

---

## 8. Quick Reference: JSON Validation

You can validate a DTC JSON file from the terminal:

```bash
python -m json.tool dtc_updates/dtc_byd.json > /dev/null && echo "Valid JSON" || echo "Invalid JSON"
```

Or from Python:

```python
import json

with open('dtc_byd.json') as f:
    data = json.load(f)

for key in data:
    assert len(key) == 5, f"Invalid key length: {key}"
    assert key[0] in 'PCBU', f"Invalid system letter: {key}"
    assert key[1:] == key[1:].upper(), f"Key not uppercase: {key}"

print(f"Valid: {len(data)} codes")
```
