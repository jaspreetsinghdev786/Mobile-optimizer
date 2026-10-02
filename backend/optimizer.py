"""Phone optimizer backend.

Runs on the PC, talks to the phone over ADB, and exposes a small HTTP API
that the Android app calls when you press Run. The server also sets up
`adb reverse`, so the app reaches it at http://127.0.0.1:8799.

Wireless: plug in USB once and the backend switches the phone to Wi-Fi ADB
(port 5555) and remembers its IP. After that you can unplug; the backend keeps
reconnecting over Wi-Fi. A phone reboot turns Wi-Fi ADB off, so after a reboot
plug in USB once more.

Usage:
    python backend/optimizer.py              # start server
    python backend/optimizer.py --run quick  # one-shot optimize from terminal
    python backend/optimizer.py --run photos # enhance latest camera photos

Photo enhancement needs OpenCV and Pillow: pip install opencv-python-headless pillow
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = 8799
AUTO_CHECK_SECONDS = 30
AUTO_LOW_RAM_PERCENT = 20  # auto mode optimizes when available RAM drops below this
AUTO_COOLDOWN_SECONDS = 180
WIFI_ADB_PORT = 5555
RECONNECT_SECONDS = 10
PHONE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "phone.json")

MODES = ("quick", "deep", "smooth", "reset", "photos")
CAMERA_DIR = "/sdcard/DCIM/Camera"
ENHANCED_DIR = "/sdcard/DCIM/Enhanced"
PHOTOS_PER_RUN = 10  # newest camera photos not yet enhanced

ADB = shutil.which("adb") or "adb"

_log = []
_log_lock = threading.Lock()
_job_lock = threading.Lock()
_state = {"running": False, "mode": None, "auto": False, "last_run": None, "link": None}
_serial = None  # adb serial in use: USB id or "ip:5555"


def log(msg):
    line = f"[{datetime.now():%H:%M:%S}] {msg}"
    print(line, flush=True)
    with _log_lock:
        _log.append(line)
        if len(_log) > 2000:
            del _log[:1000]


def adb(*args, timeout=60):
    # With USB and Wi-Fi both attached adb sees two devices, so pin one serial.
    target = ["-s", _serial] if _serial and args[0] not in ("devices", "connect", "disconnect") else []
    try:
        out = subprocess.run(
            [ADB, *target, *args], capture_output=True, text=True, timeout=timeout
        )
        return out.returncode, (out.stdout + out.stderr).strip()
    except subprocess.TimeoutExpired:
        return -1, "timed out"
    except FileNotFoundError:
        return -1, "adb not found on PATH"


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def list_devices():
    rc, out = adb("devices")
    if rc != 0:
        return []
    return [l.split("\t")[0] for l in out.splitlines()[1:] if l.endswith("\tdevice")]


def device_connected():
    return _serial is not None and _serial in list_devices()


def load_phone_ip():
    try:
        with open(PHONE_FILE) as f:
            return json.load(f).get("ip")
    except (OSError, ValueError):
        return None


def enable_wifi_adb(usb_serial):
    """Over USB: switch the phone's adb to TCP and remember its Wi-Fi IP."""
    global _serial
    _serial = usb_serial
    _, out = shell("ip -f inet addr show wlan0")
    m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out)
    if not m:
        log("Phone is not on Wi-Fi; wireless mode unavailable (USB still works).")
        return
    ip = m.group(1)
    rc, out = adb("tcpip", str(WIFI_ADB_PORT))
    if rc != 0:
        log(f"Could not enable Wi-Fi ADB: {out}")
        return
    with open(PHONE_FILE, "w") as f:
        json.dump({"ip": ip}, f)
    time.sleep(3)  # adbd restarts in TCP mode
    rc, out = adb("connect", f"{ip}:{WIFI_ADB_PORT}", timeout=10)
    log(f"Wi-Fi ADB enabled at {ip}:{WIFI_ADB_PORT} ({out}). You can unplug USB now.")


def pick_device():
    """Chooses Wi-Fi if reachable, else USB. Returns True when the device changed."""
    global _serial
    devices = list_devices()
    ip = load_phone_ip()
    wifi = f"{ip}:{WIFI_ADB_PORT}" if ip else None
    if wifi and wifi not in devices:
        adb("connect", wifi, timeout=8)
        devices = list_devices()
    usb = [d for d in devices if ":" not in d and not d.startswith("emulator")]
    if wifi in devices:
        chosen = wifi
    elif usb:
        chosen = usb[0]
    else:
        chosen = None
    changed = chosen != _serial
    _serial = chosen
    _state["link"] = None if not chosen else ("wifi" if chosen == wifi else "usb")
    return changed


def connection_loop():
    """Keeps the phone attached: reconnects Wi-Fi after drops and re-arms adb reverse."""
    while True:
        time.sleep(RECONNECT_SECONDS)
        try:
            was = _serial
            if pick_device() and _serial:
                log(f"Phone connected via {_state['link'].upper()} ({_serial})")
                setup_reverse()
            elif was and not _serial:
                log("Phone lost. Retrying Wi-Fi... (after a phone reboot, plug in USB once)")
        except Exception as e:  # keep the watcher alive no matter what
            log(f"Connection check error: {e}")


def setup_reverse():
    rc, out = adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    if rc == 0:
        log(f"adb reverse ready: phone 127.0.0.1:{PORT} -> PC:{PORT}")
    else:
        log(f"adb reverse failed: {out}")


def status():
    if not device_connected():
        return {"connected": False}
    _, mem = shell("cat /proc/meminfo")
    kb = {
        k: int(v)
        for k, v in re.findall(r"^(\w+):\s+(\d+) kB", mem, re.M)
    }
    total = kb.get("MemTotal", 0)
    avail = kb.get("MemAvailable", 0)
    _, df = shell("df /data | tail -1")
    parts = df.split()
    storage_used = parts[4] if len(parts) >= 5 else "?"
    _, batt = shell("dumpsys battery")
    level = re.search(r"level: (\d+)", batt)
    temp = re.search(r"temperature: (\d+)", batt)
    _, anim = shell("settings get global window_animation_scale")
    _, model = shell("getprop ro.product.model")
    return {
        "connected": True,
        "model": model,
        "ram_total_mb": total // 1024,
        "ram_avail_mb": avail // 1024,
        "ram_avail_percent": round(avail * 100 / total) if total else 0,
        "storage_used": storage_used,
        "battery": int(level.group(1)) if level else None,
        "temp_c": int(temp.group(1)) / 10 if temp else None,
        "animation_scale": anim,
        **_state,
    }


def step(title, cmd, timeout=60):
    log(f"$ {cmd}")
    rc, out = shell(cmd, timeout=timeout)
    lines = out.splitlines()
    # On failure show only the error line, not the full Java stack trace.
    for line in (lines[:15] if rc == 0 else [l for l in lines if "Exception" in l or "Error" in l][:2] or lines[:2]):
        log(f"  {line}")
    if rc == -1 and out == "timed out":
        log(f"  {title}: still working on the phone, continuing")
    else:
        log(f"  {title}: {'ok' if rc == 0 else f'failed ({rc})'}")
    return rc == 0


def enhance_image(src, dst):
    """Cleans up one phone photo: chroma denoise, local contrast, sharpening, a touch of color.

    Pixels are processed as stored (no EXIF rotation) and the original EXIF is
    written back, so orientation, date and camera info stay correct.
    """
    import cv2
    import numpy as np
    from PIL import Image

    with Image.open(src) as im:
        exif = im.info.get("exif", b"")
        icc = im.info.get("icc_profile")
        rgb = np.asarray(im.convert("RGB"))

    lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB)
    l, a, b = cv2.split(lab)

    # Colour noise (blotchy specks in low light) lives in a/b; smoothing them there costs no detail.
    a = cv2.bilateralFilter(a, 9, 12, 9)
    b = cv2.bilateralFilter(b, 9, 12, 9)
    # Light luma denoise before sharpening so we don't sharpen grain.
    l = cv2.bilateralFilter(l, 5, 8, 5)

    # Dim photos: brighten with a gamma curve that pulls mid-tones towards ~45% grey.
    mean = max(float(l.mean()), 1.0) / 255
    if mean < 0.45:
        gamma = min(max(np.log(0.45) / np.log(mean), 0.65), 1.0)
        lut = (255 * (np.arange(256) / 255) ** gamma).astype(np.uint8)
        l = cv2.LUT(l, lut)

    # Local contrast for flat areas; blended at 40% to stay natural.
    clahe = cv2.createCLAHE(clipLimit=1.5, tileGridSize=(8, 8))
    l = cv2.addWeighted(clahe.apply(l), 0.4, l, 0.6, 0)

    # Unsharp mask on luma only (no colour halos).
    blur = cv2.GaussianBlur(l, (0, 0), 1.4)
    l = cv2.addWeighted(l, 1.45, blur, -0.45, 0)

    out = cv2.cvtColor(cv2.merge((l, a, b)), cv2.COLOR_LAB2RGB)

    # Mild saturation boost.
    hsv = cv2.cvtColor(out, cv2.COLOR_RGB2HSV).astype(np.float32)
    hsv[..., 1] = np.clip(hsv[..., 1] * 1.06, 0, 255)
    out = cv2.cvtColor(hsv.astype(np.uint8), cv2.COLOR_HSV2RGB)

    kwargs = {"quality": 95, "subsampling": 0, "exif": exif}
    if icc:
        kwargs["icc_profile"] = icc
    Image.fromarray(out).save(dst, "JPEG", **kwargs)


def enhance_photos(limit=PHOTOS_PER_RUN):
    try:
        import cv2  # noqa: F401
        import PIL  # noqa: F401
    except ImportError:
        log("Missing libraries. On the PC run: pip install opencv-python-headless pillow")
        return False

    _, listing = shell(f"ls -t {CAMERA_DIR}")
    photos = [f for f in listing.splitlines() if f.lower().endswith((".jpg", ".jpeg"))]
    _, done_listing = shell(f"ls {ENHANCED_DIR} 2>/dev/null")
    done = set(done_listing.split())
    todo = [f for f in photos if enhanced_name(f) not in done][:limit]
    log(f"{len(photos)} camera photos, {len(todo)} to enhance")
    if not todo:
        log("Nothing new. Take some photos, then run again.")
        return True

    shell(f"mkdir -p {ENHANCED_DIR}")
    ok = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name in todo:
            src = os.path.join(tmp, name)
            dst = os.path.join(tmp, enhanced_name(name))
            rc, out = adb("pull", f"{CAMERA_DIR}/{name}", src, timeout=120)
            if rc != 0:
                log(f"  {name}: pull failed ({out})")
                continue
            start = time.time()
            try:
                enhance_image(src, dst)
            except Exception as e:
                log(f"  {name}: enhance failed ({e})")
                continue
            rc, out = adb("push", dst, f"{ENHANCED_DIR}/{enhanced_name(name)}", timeout=120)
            if rc != 0:
                log(f"  {name}: push failed ({out})")
                continue
            # Make it show up in Gallery / Photos right away.
            shell(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE "
                  f"-d file://{ENHANCED_DIR}/{enhanced_name(name)}")
            ok += 1
            log(f"  {name} -> Enhanced/{enhanced_name(name)}  ({time.time() - start:.1f}s)")

    log(f"Enhanced {ok}/{len(todo)} photos. Open Gallery > album 'Enhanced'.")
    return ok == len(todo)


def enhanced_name(name):
    base, _ = os.path.splitext(name)
    return f"{base}_enh.jpg"


def optimize(mode):
    """mode: quick | deep | smooth | reset | photos"""
    if not _job_lock.acquire(blocking=False):
        log("Already running, ignoring request.")
        return False
    _state.update(running=True, mode=mode)
    try:
        if not device_connected():
            log("No phone connected. Plug in USB and allow USB debugging.")
            return False
        if mode == "photos":
            log("=== PHOTO ENHANCE ===")
            result = enhance_photos()
            log("=== Done ===")
            _state["last_run"] = datetime.now().strftime("%H:%M:%S")
            return result
        before = status()
        log(f"=== {mode.upper()} optimize on {before['model']} ===")
        log(f"RAM free before: {before['ram_avail_mb']} MB ({before['ram_avail_percent']}%)")

        if mode in ("quick", "deep"):
            step("Kill background apps", "am kill-all")
            step("Clear app caches", "pm trim-caches 999G", timeout=120)
        if mode == "deep":
            log("Deep mode: compiling apps + storage trim. Can take several minutes...")
            step("Optimize app code (dexopt)", "cmd package bg-dexopt-job", timeout=900)
            step("Storage trim (fstrim)", "sm idle-maint run", timeout=300)
        if mode == "smooth":
            for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                step(f"Set {key}=0.5", f"settings put global {key} 0.5")
        if mode == "reset":
            for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                step(f"Reset {key}=1", f"settings put global {key} 1")

        after = status()
        freed = after["ram_avail_mb"] - before["ram_avail_mb"]
        log(f"RAM free after: {after['ram_avail_mb']} MB ({after['ram_avail_percent']}%)  [{freed:+d} MB]")
        log("=== Done ===")
        _state["last_run"] = datetime.now().strftime("%H:%M:%S")
        return True
    finally:
        _state.update(running=False, mode=None)
        _job_lock.release()


def auto_loop():
    """Watches RAM and re-runs quick optimize when the phone starts to lag again."""
    last = 0.0
    while True:
        time.sleep(AUTO_CHECK_SECONDS)
        if not _state["auto"] or _state["running"]:
            continue
        try:
            s = status()
        except Exception as e:  # keep the watcher alive no matter what
            log(f"Auto check error: {e}")
            continue
        if not s.get("connected"):
            continue
        low = s["ram_avail_percent"] < AUTO_LOW_RAM_PERCENT
        if low and time.time() - last > AUTO_COOLDOWN_SECONDS:
            log(f"Auto: RAM low ({s['ram_avail_percent']}%), optimizing...")
            optimize("quick")
            last = time.time()


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        if url.path == "/status":
            self._send(200, status())
        elif url.path == "/log":
            since = int(q.get("since", ["0"])[0])
            with _log_lock:
                self._send(200, {"lines": _log[since:], "next": len(_log)})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        if url.path == "/optimize":
            mode = q.get("mode", ["quick"])[0]
            if mode not in MODES:
                return self._send(400, {"error": "bad mode"})
            if _state["running"]:
                return self._send(409, {"error": "already running"})
            threading.Thread(target=optimize, args=(mode,), daemon=True).start()
            self._send(200, {"started": mode})
        elif url.path == "/auto":
            _state["auto"] = q.get("on", ["1"])[0] == "1"
            log(f"Auto mode {'ON' if _state['auto'] else 'OFF'} (optimize when RAM < {AUTO_LOW_RAM_PERCENT}%)")
            self._send(200, {"auto": _state["auto"]})
        else:
            self._send(404, {"error": "not found"})

    def log_message(self, *args):
        pass  # keep terminal clean; our own log() shows what matters


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--run", choices=MODES, help="run once and exit")
    args = p.parse_args()

    if args.run:
        pick_device()
        optimize(args.run)
        return

    pick_device()
    if _state["link"] == "usb":
        enable_wifi_adb(_serial)
        pick_device()
    if _serial:
        log(f"Phone connected via {_state['link'].upper()} ({_serial})")
        setup_reverse()
    else:
        log("No phone yet. Plug in USB once (Wi-Fi works after that). Waiting...")
    threading.Thread(target=connection_loop, daemon=True).start()
    threading.Thread(target=auto_loop, daemon=True).start()
    log(f"Optimizer backend on http://0.0.0.0:{PORT}  (Ctrl+C to stop)")
    # No address reuse: on Windows it would let two servers share the port silently.
    ThreadingHTTPServer.allow_reuse_address = False
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
