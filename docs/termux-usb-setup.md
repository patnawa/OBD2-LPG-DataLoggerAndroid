# vLinker FS USB on Termux (Android 11+)

This is the **complementary Termux/Python setup** for working with the OBD2-LPG-DataLoggerAndroid app ecosystem. The Android app (`com.alpha.obd2logger`) already has full Android USB Host API support — this document covers using a **vLinker FS USB adapter directly from Termux/Python** on the same device.

Verified working: **2026-07-07**, Xiaomi 2311DRK48G, Android 16, vLinker FS (FTDI FT230X), Termux app 0.118+, `termux:API` v0.53.0, `termux-api` apt package v0.59.1.

---

## Why this is needed

Android 11+ tightened SELinux policy. Even with USB permission granted, Termux (an `untrusted_app`) cannot:

- `os.open('/dev/bus/usb/001/XXX')` → `EACCES`
- `libusb_init()` → returns -1 (sandbox)
- `usb_host_bridge.py` raw ioctls → block

The only working path is through **Android's `UsbManager` (Java layer)**, which the official `termux:API` app exposes via a broadcast → FD-passing mechanism.

---

## One-time setup

### 1. Install termux:API app v0.53.0+

```bash
# Download
curl -L -o /sdcard/Download/termux-api-v53.apk \
  "https://f-droid.org/repo/com.termux.api_1002.apk"

# Install — must be done via Mi File Manager (Termux can't pm install)
# Settings → Apps → Termux:API → Uninstall (if v0.51)
# File Manager → Internal storage → Download → termux-api-v53.apk → Install
```

**Important version notes:**

| Package | Latest | Source | Notes |
|---------|--------|--------|-------|
| termux:API **app** (com.termux.api) | v0.53.0 | F-Droid / GitHub | Required for USB FD passing |
| termux-api **apt** (CLI wrapper) | v0.59.1 | Termux repo | Independent, runs `termux-usb` etc. |

v0.51.0 of the app is **broken on Android 11+** — `termux-usb -E` returns `"No such device"` due to SELinux. v0.53.0 (Sep 2025) fixes this.

v0.59.x refers to the **apt package**, not the app. Don't confuse them.

### 2. Grant USB permission

Plug in vLinker FS → Android shows permission dialog → tap **Allow**.

Or from Termux:
```bash
termux-usb -r /dev/bus/usb/001/002
# → "Permission granted."
```

### 3. Verify setup

```bash
termux-usb -l
# → ["/dev/bus/usb/001/002"]

termux-battery-status
# → {"present": true, ...}
```

`pm list packages` from Termux won't show `com.termux.api` (uid-isolated). Use the two commands above as proof.

---

## Usage

### Correct invocation

```bash
termux-usb -e /path/to/your_script.py -E /dev/bus/usb/001/002
#      ^-- MUST come first        ^-- device path
#             ^-- AND here (-e sets ACTION=open, -E sets FD-via-env)
```

**`-E` alone only sets an env var.** It does NOT open the device. Must pair with `-e <command>` to actually trigger `ACTION=open` in the app.

### Python script template

```python
#!/usr/bin/env python3
import os, ctypes, time

# === libusb setup ===
libusb = ctypes.CDLL('libusb-1.0.so')
libusb.libusb_init.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
libusb.libusb_init.restype = ctypes.c_int
libusb.libusb_wrap_sys_device.argtypes = [
    ctypes.c_void_p, ctypes.c_int, ctypes.POINTER(ctypes.c_void_p)
]
libusb.libusb_wrap_sys_device.restype = ctypes.c_int
libusb.libusb_claim_interface.argtypes = [ctypes.c_void_p, ctypes.c_int]
libusb.libusb_claim_interface.restype = ctypes.c_int
libusb.libusb_bulk_transfer.argtypes = [
    ctypes.c_void_p, ctypes.c_uint8, ctypes.c_char_p, ctypes.c_int,
    ctypes.POINTER(ctypes.c_int), ctypes.c_uint
]
libusb.libusb_bulk_transfer.restype = ctypes.c_int
libusb.libusb_close.argtypes = [ctypes.c_void_p]
libusb.libusb_close.restype = ctypes.c_int
libusb.libusb_exit.argtypes = [ctypes.c_void_p]
libusb.libusb_exit.restype = None

# === Get FD from termux:API ===
fd = int(os.environ.get('TERMUX_USB_FD', '0'))
print(f"vLinker FD: {fd}")

# === Open via libusb wrap ===
ctx = ctypes.c_void_p()
libusb.libusb_init(ctypes.byref(ctx))
devh = ctypes.c_void_p()
libusb.libusb_wrap_sys_device(ctx, fd, ctypes.byref(devh))  # <-- key call
libusb.libusb_detach_kernel_driver(devh, 0)
libusb.libusb_claim_interface(devh, 0)

# vLinker FS FT230X CDC ACM: OUT=0x02, IN=0x81
EP_OUT, EP_IN = 0x02, 0x81

def send_at(cmd, wait_s=1.5):
    """Send AT command to ELM327, return response."""
    msg = (cmd + '\r').encode('ascii')
    print(f">>> {cmd}")

    # Write
    buf = ctypes.create_string_buffer(msg, len(msg))
    tx = ctypes.c_int(0)
    r = libusb.libusb_bulk_transfer(
        devh, EP_OUT, buf, len(msg), ctypes.byref(tx), 1000
    )
    if r != 0:
        print(f"  write err: {r}")
        return None

    # Read
    time.sleep(wait_s)
    all_data = b''
    for _ in range(3):
        rbuf = ctypes.create_string_buffer(256)
        rx = ctypes.c_int(0)
        r = libusb.libusb_bulk_transfer(
            devh, EP_IN, rbuf, 256, ctypes.byref(rx), 300
        )
        if r == 0 and rx.value > 0:
            chunk = rbuf.raw[:rx.value]
            # Skip CDC notification: 0x11 + 2-bit length
            if chunk[0] == 0x11 and rx.value >= 2:
                wlen = chunk[1] & 0x03
                chunk = chunk[2 + wlen * 2:]
            all_data += chunk
        elif r == -7:  # LIBUSB_ERROR_TIMEOUT
            break

    resp = all_data.decode('utf-8', errors='replace').strip()
    print(f"<<< {resp!r}")
    return resp

# === ELM327 init ===
send_at('ATZ', wait_s=2.5)   # reset, takes 2-3s
send_at('ATE0')              # echo off
send_at('ATL0')              # linefeed off
send_at('ATH0')              # headers off
send_at('ATI')               # identify
send_at('ATRV')              # battery voltage
send_at('0902', wait_s=2.0)  # read VIN

# Cleanup
libusb.libusb_release_interface(devh, 0)
libusb.libusb_close(devh)
libusb.libusb_exit(ctx)
```

### vLinker FS specifics

- **VID/PID:** `0x0403 / 0x6015` (FTDI FT230X)
- **Interface:** 0 (CDC ACM)
- **Endpoints:** OUT `0x02`, IN `0x81`
- **Reset time:** ~2.5 seconds (use `wait_s=2.5` for ATZ)

---

## Troubleshooting

### "No such device"

Either:
1. **termux:API app version too old** (v0.51.0). Upgrade to v0.53.0+.
2. **Permission not granted.** Run `termux-usb -r /dev/bus/usb/001/XXX`.
3. **Wrong flag order.** Use `termux-usb -e <script> -E <device>`, not `-E <script> <device>`.

### `os.write(fd, ...)` returns EINVAL

You're using the wrong FD API. The FD from `TERMUX_USB_FD` is the **control endpoint (ep0)** — only libusb `bulk_transfer` works. Use `os.read`/`os.write` only on ep0 if at all (not for ELM327 data).

### No response from AT commands

If vLinker is plugged in but **not connected to a car** (no ignition), you'll get only 1-byte `\x00` response. This is correct — ELM327 has no ECU to talk to. Connect to car with ignition ON, then retry.

### Permission dialog doesn't appear

Some Android ROMs auto-grant on first plug. If you need to revoke: `Settings → Apps → Termux:API → Permissions → USB`.

---

## Why not use the Android app directly?

The OBD2-LPG-DataLoggerAndroid app already handles vLinker FS perfectly. This Termux path is for:

- **Custom logging** beyond the app's feature set
- **Direct ELM327 scripting** (DTC scans, custom PIDs)
- **Trip analyzers** in Python (`obd2-python-client`)
- **Integration** with other Python tools

The app can be used simultaneously — the `termux:API` USB permission is per-adapter, per-app, so both can have access (subject to USB device exclusivity after one opens it).

---

## Files

- **Working PoC:** `obd2_vlinker_test.py` (in `~/`)
- **Driver to add:** `obd2-python-client/obd2_client.py` → `TermuxUsbDriver` (TODO)
- **Obsolete (delete):** `obd2-python-client/usb_host_bridge.py`, `usb_tcp_bridge.py`

---

## References

- [termux:API GitHub](https://github.com/termux/termux-api) (v0.53.0 source)
- [termux:API F-Droid page](https://f-droid.org/en/packages/com.termux.api/)
- [termux-api-package](https://github.com/termux/termux-api-package) (v0.59.x apt)
- [libusb_wrap_sys_device API](https://libusb.sourceforge.io/api-1.0/group__libusb__dev.html)
