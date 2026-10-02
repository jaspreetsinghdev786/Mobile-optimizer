package com.example.nothing

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-phone optimizer: same steps as backend/optimizer.py, but the shell commands
 * run through Shizuku, so no PC or internet is needed.
 * Status and log use the same JSON shape as the PC backend so the UI treats both alike.
 */
object LocalOptimizer {
    const val AUTO_LOW_RAM_PERCENT = 20
    private const val AUTO_COOLDOWN_MS = 180_000L
    private val ANIMATION_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    private val log = mutableListOf<String>()
    private val busy = AtomicBoolean(false)
    @Volatile private var mode: String? = null
    @Volatile private var lastRun: String? = null
    @Volatile private var lastAutoRun = 0L
    @Volatile var auto = false

    fun log(msg: String) {
        val line = "[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $msg"
        synchronized(log) {
            log.add(line)
            if (log.size > 2000) log.subList(0, 1000).clear()
        }
    }

    fun logSince(since: Int): Pair<List<String>, Int> = synchronized(log) {
        val from = since.coerceIn(0, log.size)
        log.subList(from, log.size).toList() to log.size
    }

    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun shizukuGranted(): Boolean =
        shizukuRunning() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestShizuku(requestCode: Int) {
        if (shizukuRunning() && !shizukuGranted()) Shizuku.requestPermission(requestCode)
    }

    /** Runs a shell command with ADB (shell user) rights. Returns exit code and output. */
    private fun exec(cmd: String, timeoutSec: Long): Pair<Int, String> = try {
        // newProcess is private in the Shizuku API jar but is the documented way to run a shell command.
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        val out = StringBuilder()
        val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
        // Shizuku's remote process lacks a timed waitFor, so wait on a helper thread instead.
        var code = -1
        val waiter = Thread { code = p.waitFor() }.apply { start() }
        waiter.join(TimeUnit.SECONDS.toMillis(timeoutSec))
        if (waiter.isAlive) {
            p.destroy()
            -1 to "timed out"
        } else {
            reader.join(2000)
            code to out.toString().trim()
        }
    } catch (e: Throwable) {
        -2 to (e.cause?.message ?: e.message ?: e.toString())
    }

    private fun step(title: String, cmd: String, timeoutSec: Long = 60): Boolean {
        log("$ $cmd")
        val (rc, out) = exec(cmd, timeoutSec)
        val lines = out.lines().filter { it.isNotBlank() }
        val shown = if (rc == 0) lines.take(15)
        else lines.filter { "Exception" in it || "Error" in it }.take(2).ifEmpty { lines.take(2) }
        shown.forEach { log("  $it") }
        when {
            rc == -1 -> log("  $title: still working on the phone, continuing")
            rc == 0 -> log("  $title: ok")
            else -> log("  $title: failed ($rc)")
        }
        return rc == 0
    }

    private fun memory(context: Context): Pair<Long, Long> {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.availMem / (1024 * 1024) to info.totalMem / (1024 * 1024)
    }

    fun ramPercent(context: Context): Int {
        val (avail, total) = memory(context)
        return if (total > 0) (avail * 100 / total).toInt() else 0
    }

    fun status(context: Context): JSONObject {
        val (avail, total) = memory(context)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val scale = Settings.Global.getString(context.contentResolver, "window_animation_scale") ?: "1"
        return JSONObject()
            .put("connected", shizukuGranted())
            .put("model", Build.MODEL)
            .put("ram_total_mb", total)
            .put("ram_avail_mb", avail)
            .put("ram_avail_percent", if (total > 0) avail * 100 / total else 0)
            .put("battery", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
            .put("temp_c", (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
            .put("animation_scale", scale)
            .put("running", busy.get())
            .put("mode", mode)
            .put("auto", auto)
            .put("last_run", lastRun)
    }

    /** mode: quick | deep | smooth | reset */
    suspend fun optimize(context: Context, mode: String): Boolean = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) {
            log("Already running, ignoring request.")
            return@withContext false
        }
        this@LocalOptimizer.mode = mode
        try {
            if (!shizukuGranted()) {
                log("Shizuku not running or not allowed. Open Shizuku and start it.")
                return@withContext false
            }
            if (mode == "photos") {
                log("Photo enhance needs the PC backend (OpenCV). Not available offline.")
                return@withContext false
            }
            val (before, _) = memory(context)
            log("=== ${mode.uppercase()} optimize on ${Build.MODEL} (on-phone) ===")
            log("RAM free before: $before MB (${ramPercent(context)}%)")

            if (mode == "quick" || mode == "deep") {
                step("Kill background apps", "am kill-all")
                step("Clear app caches", "pm trim-caches 999G", 120)
            }
            if (mode == "deep") {
                log("Deep mode: compiling apps + storage trim. Can take several minutes...")
                step("Optimize app code (dexopt)", "cmd package bg-dexopt-job", 900)
                step("Storage trim (fstrim)", "sm idle-maint run", 300)
            }
            if (mode == "smooth") ANIMATION_KEYS.forEach { step("Set $it=0.5", "settings put global $it 0.5") }
            if (mode == "reset") ANIMATION_KEYS.forEach { step("Reset $it=1", "settings put global $it 1") }

            val (after, _) = memory(context)
            log("RAM free after: $after MB (${ramPercent(context)}%)  [${"%+d".format(after - before)} MB]")
            log("=== Done ===")
            lastRun = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            true
        } finally {
            this@LocalOptimizer.mode = null
            busy.set(false)
        }
    }

    /** Called by AutoFixService: re-optimizes when RAM gets low, at most once per cooldown. */
    suspend fun autoCheck(context: Context) {
        if (busy.get() || !shizukuGranted()) return
        val pct = ramPercent(context)
        if (pct < AUTO_LOW_RAM_PERCENT && System.currentTimeMillis() - lastAutoRun > AUTO_COOLDOWN_MS) {
            log("Auto: RAM low ($pct%), optimizing...")
            lastAutoRun = System.currentTimeMillis()
            optimize(context, "quick")
        }
    }
}
