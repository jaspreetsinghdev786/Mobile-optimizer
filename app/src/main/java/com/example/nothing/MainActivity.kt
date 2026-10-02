package com.example.nothing

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Reached through `adb reverse`, which the Python backend sets up on start.
private const val BACKEND = "http://127.0.0.1:8799"
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OptimizerScreen()
                }
            }
        }
    }
}

private suspend fun http(method: String, path: String): JSONObject? = withContext(Dispatchers.IO) {
    try {
        val conn = URL(BACKEND + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3000
        conn.readTimeout = 10000
        val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
        JSONObject(stream.bufferedReader().readText())
    } catch (e: Exception) {
        null
    }
}

@Composable
fun OptimizerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JSONObject?>(null) }
    // true = PC backend reachable; false = on-phone mode through Shizuku (no PC, no internet).
    var usePc by remember { mutableStateOf(false) }
    var shizukuRunning by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<String>() }
    var nextPc by remember { mutableStateOf(0) }
    var nextLocal by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    // Poll status + terminal log. PC backend wins when reachable, otherwise run on the phone.
    LaunchedEffect(Unit) {
        while (true) {
            val pc = http("GET", "/status")
            if ((pc != null) != usePc) lines.add(if (pc != null) "--- PC backend found, using PC ---" else "--- No PC, using on-phone mode ---")
            usePc = pc != null
            shizukuRunning = LocalOptimizer.shizukuRunning()
            if (pc != null) {
                status = pc
                http("GET", "/log?since=$nextPc")?.let { r ->
                    val arr = r.getJSONArray("lines")
                    for (i in 0 until arr.length()) lines.add(arr.getString(i))
                    nextPc = r.getInt("next")
                }
            } else {
                status = LocalOptimizer.status(context)
                val (newLines, n) = LocalOptimizer.logSince(nextLocal)
                lines.addAll(newLines)
                nextLocal = n
            }
            delay(1000)
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    val running = status?.optBoolean("running") == true
    val connected = status?.optBoolean("connected") == true
    val online = usePc || shizukuRunning
    fun run(mode: String) = scope.launch {
        if (usePc) http("POST", "/optimize?mode=$mode") else LocalOptimizer.optimize(context, mode)
    }
    fun setAuto(on: Boolean) = scope.launch {
        if (usePc) http("POST", "/auto?on=${if (on) 1 else 0}") else AutoFixService.setEnabled(context, on)
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Mobile Optimizer", style = MaterialTheme.typography.headlineMedium)

        val s = status
        Text(
            when {
                usePc && !connected -> "PC backend online, but phone not seen by ADB"
                !usePc && !shizukuRunning -> "No PC and Shizuku is not running. Open Shizuku and tap Start."
                !usePc && !connected -> "Shizuku running. Allow this app to use it."
                else -> "${s!!.optString("model")}  •  RAM free ${s.optInt("ram_avail_mb")} MB " +
                    "(${s.optInt("ram_avail_percent")}%)  •  ${s.optDouble("temp_c")}°C"
            },
            color = if (online && connected) Color(0xFF7CD992) else Color(0xFFFF8A80)
        )
        Text(
            if (usePc) "Mode: PC backend" else "Mode: on-phone (offline, via Shizuku)",
            style = MaterialTheme.typography.bodySmall
        )
        if (!usePc && !connected) {
            OutlinedButton(
                onClick = {
                    if (shizukuRunning) LocalOptimizer.requestShizuku(1)
                    else context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                        ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (shizukuRunning) "Allow Shizuku access" else "Open Shizuku") }
        }

        if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        Button(
            onClick = { run("quick") },
            enabled = online && connected && !running,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Text(if (running) "Running ${status?.optString("mode")}…" else "RUN  OPTIMIZE", fontSize = 20.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val enabled = online && connected && !running
            OutlinedButton(onClick = { run("deep") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Deep") }
            OutlinedButton(onClick = { run("smooth") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Smooth UI") }
            OutlinedButton(onClick = { run("reset") }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Reset UI") }
        }

        OutlinedButton(
            onClick = { run("photos") },
            enabled = usePc && connected && !running,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Enhance Photos  (saved to Gallery › Enhanced)") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-fix lag")
                Text("Re-optimizes when RAM gets low", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = status?.optBoolean("auto") == true,
                enabled = online,
                onCheckedChange = { on -> setAuto(on) }
            )
        }

        Spacer(Modifier.height(4.dp))
        Text("Terminal", style = MaterialTheme.typography.titleSmall)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(lines) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFB0F0B0))
            }
        }
    }
}
