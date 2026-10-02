# Mobile Optimizer

An Android app that frees RAM, clears app caches and speeds up the UI on a laggy phone.
It needs ADB-level rights, which it gets in one of two ways:

| Mode | Needs | What works |
|------|-------|-----------|
| **PC mode** | A Windows/Mac/Linux PC running `backend/optimizer.py`, phone on USB or same Wi-Fi | Everything, including **Enhance Photos** |
| **Offline mode** | The free [Shizuku](https://shizuku.rikka.app/) app on the phone. No PC, no internet | Everything except Enhance Photos |

The app picks the mode by itself: if the PC backend answers it uses the PC, otherwise it runs on the phone through Shizuku.

## Buttons

- **RUN OPTIMIZE** (quick): kills background apps (`am kill-all`) and trims app caches (`pm trim-caches`).
- **Deep**: quick, plus recompiles apps (`bg-dexopt-job`) and runs storage trim. Takes several minutes.
- **Smooth UI**: sets the three animation scales to 0.5x so the phone feels faster.
- **Reset UI**: puts the animation scales back to 1x.
- **Enhance Photos** (PC mode only): denoises, brightens and sharpens your newest 10 camera photos. Copies go to `DCIM/Enhanced`; the originals are not touched.
- **Auto-fix lag**: checks RAM every 30 seconds and runs a quick optimize when free RAM drops below 20% (at most once every 3 minutes).

Nothing here needs root. Nothing deletes your photos, messages or app data.

---

## 1. One-time phone setup (both modes)

1. **Settings > About phone > Version**, tap **Build number** 7 times to unlock Developer options.
2. **Settings > Additional settings > Developer options**, turn on **USB debugging**.
3. On OPPO / Realme / OnePlus (ColorOS): in Developer options also turn on **Disable permission monitoring**. Without it, Shizuku access and some commands are refused.
4. Install the app (see [Build and install](#4-build-and-install)).

## 2. Offline mode (no PC)

Shizuku runs a small helper with ADB rights on the phone itself, so the app works anywhere.

1. Install **Shizuku** from the Play Store.
2. Connect the phone to any Wi-Fi network. Internet is not needed; Wireless debugging just requires Wi-Fi to be on.
3. Developer options > turn on **Wireless debugging**.
4. Open Shizuku > **Pairing** > follow the notification: open Wireless debugging > **Pair device with pairing code**, then type the code into the Shizuku notification.
5. In Shizuku tap **Start**. It should say "Shizuku is running".
6. Open Mobile Optimizer. It shows "Mode: on-phone (offline, via Shizuku)". Tap **Allow Shizuku access** and allow.
7. The status line turns green. Press **RUN OPTIMIZE**.

After that you can turn Wi-Fi off; the app keeps working until the phone reboots.

**After every reboot**, Shizuku stops. Open Shizuku and tap **Start** again (Wi-Fi on for that moment). If you have a PC handy, you can instead start it over USB once:

```
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

**Auto-fix lag in offline mode** runs as a background service with a permanent "Auto-fix lag is on" notification. On ColorOS, stop the system from killing it: long-press the app icon > App info > Battery > **Allow background activity**. Turn the switch back on after a reboot.

## 3. PC mode

Needs Python 3.9+ and Android platform-tools (`adb`) on the PATH.

```
pip install -r backend/requirements.txt
python backend/optimizer.py
```

On Windows you can double-click `start-optimizer.bat` instead. Keep the window open while using the app.

1. Plug the phone in with USB and allow the "USB debugging" prompt.
2. The backend switches the phone to Wi-Fi ADB and saves its IP in `backend/phone.json`. You can unplug USB now; it keeps reconnecting over Wi-Fi (same network as the PC).
3. It also runs `adb reverse`, so the app reaches the backend at `127.0.0.1:8799`. Open the app and it shows "Mode: PC backend".

After a phone reboot, Wi-Fi ADB is off again. Plug in USB once.

Run a job straight from the terminal without the app:

```
python backend/optimizer.py --run quick    # quick | deep | smooth | reset | photos
```

## 4. Build and install

Needs the Android SDK and **JDK 17** (a JRE is not enough).

```
# Windows PowerShell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
.\gradlew.bat installDebug
```

```
# macOS / Linux
./gradlew installDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. You can also copy it to the phone and install it by tapping it.

If Gradle can't find the SDK, create `local.properties` with `sdk.dir=<path to Android SDK>`.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "No PC and Shizuku is not running" | Open Shizuku and tap Start (needed after every reboot). |
| "Shizuku running. Allow this app to use it." | Tap **Allow Shizuku access**. On ColorOS turn on **Disable permission monitoring** first. |
| "PC backend online, but phone not seen by ADB" | Plug in USB once, or check that phone and PC are on the same Wi-Fi. |
| Backend says `adb not found on PATH` | Install Android platform-tools and add the folder to PATH. |
| Port 8799 already in use | Another backend is running. Close the other window. |
| Build fails with `does not provide the required capabilities: [JAVA_COMPILER]` | `JAVA_HOME` points to a JRE. Point it to a JDK 17. |
| Enhance Photos says missing libraries | `pip install -r backend/requirements.txt` |

## Project layout

```
app/                      Android app (Kotlin, Jetpack Compose)
  MainActivity.kt         UI; talks to the PC backend or LocalOptimizer
  LocalOptimizer.kt       offline engine, runs shell commands through Shizuku
  AutoFixService.kt       offline auto-fix foreground service
backend/optimizer.py      PC engine, HTTP API on port 8799
start-optimizer.bat       Windows launcher for the backend
```

Both engines return the same status/log JSON, so the UI treats them the same.
