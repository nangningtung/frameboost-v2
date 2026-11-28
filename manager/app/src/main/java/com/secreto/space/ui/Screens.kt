package com.secreto.space.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject

// --------------------------------------------------------------- Beranda

@Composable
fun HomeScreen() {
    var snap by remember { mutableStateOf<JSONObject?>(null) }
    val history = remember { mutableStateListOf<Float>() }

    LaunchedEffect(Unit) {
        while (isActive) {
            val next = runCatching { JSONObject(ipc("snapshot")) }.getOrNull()
            snap = next
            next?.let {
                history.add(it.optInt("temp_big_mc", 0) / 1000f)
                if (history.size > HISTORY_SIZE) history.removeAt(0)
            }
            delay(2000)
        }
    }

    val s = snap
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Beranda",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium
        )

        if (s == null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            StatusPill(
                text = s.optString("daemon_state", "—"),
                error = s.optString("daemon_state") == "CRIT"
            )
            if (s.optBoolean("recording")) {
                Spacer(modifier = Modifier.padding(6.dp))
                StatusPill(text = "● MEREKAM", error = true)
            }
        }

        val session = s.optJSONObject("session")
        if (session != null && session.optBoolean("active")) {
            SectionCard(
                title = "Sesi aktif — ${session.optString("pkg")}",
                subtitle = "Preset ${session.optString("preset")}"
            ) {
                StatGrid(cells = listOf(
                    { StatCard("Durasi", formatElapsed(session.optLong("elapsed_s", 0))) },
                    { StatCard("FPS rata-rata", session.optDouble("avg_fps", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "—") },
                    { StatCard("Jank", session.optDouble("jank_pct", 0.0).takeIf { it > 0 }?.let { "%.1f%%".format(it) } ?: "—") },
                    { StatCard("App tidur", session.optInt("slept_apps", 0).toString()) },
                ))
            }
        }

        StatGrid(cells = listOf(
            { StatCard(
                "FPS",
                s.optDouble("fps", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "—",
                sub = "SurfaceFlinger"
            ) },
            { StatCard(
                "Suhu BIG",
                "%.1f °C".format(s.optInt("temp_big_mc", 0) / 1000.0),
                sub = "thermal_zone0"
            ) },
            { StatCard(
                "GPU",
                "${(s.optJSONObject("gpu")?.optInt("busy_pct", 0) ?: 0)}%",
                sub = "${(s.optJSONObject("gpu")?.optInt("current_clock", 0) ?: 0) / 1000} MHz · " +
                    s.optJSONObject("gpu")?.optString("governor", "")
            ) },
            { StatCard(
                "Baterai",
                "${s.optJSONObject("battery")?.optInt("capacity_pct", 0)}%",
                sub = "${(s.optJSONObject("battery")?.optInt("current_now_ma", 0) ?: 0)} mA · " +
                    "%.1f °C".format(s.optJSONObject("battery")?.optDouble("temp_c", 0.0) ?: 0.0)
            ) },
            { StatCard(
                "CPU Little",
                "${(s.optJSONObject("cpu")?.optJSONObject("little")?.optInt("cur_freq", 0) ?: 0) / 1000} MHz",
                sub = s.optJSONObject("cpu")?.optJSONObject("little")?.optString("gov", "")
            ) },
            { StatCard(
                "CPU Big",
                "${(s.optJSONObject("cpu")?.optJSONObject("big")?.optInt("cur_freq", 0) ?: 0) / 1000} MHz",
                sub = s.optJSONObject("cpu")?.optJSONObject("big")?.optString("gov", "")
            ) },
        ))

        SectionCard(
            title = "Suhu BIG",
            subtitle = "Riwayat ±2 menit · ${"%.1f".format(history.lastOrNull() ?: 0f)} °C"
        ) {
            Sparkline(
                values = history.toList(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            )
        }

        SectionCard(title = "Lainnya") {
            Text(
                "TCP: ${s.optJSONObject("net")?.optString("congestion_control", "—")} · " +
                    "swappiness: ${s.optInt("vm_swappiness", 0)} · " +
                    "uptime daemon: ${formatElapsed(s.optLong("daemon_uptime_s", 0))}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

// ----------------------------------------------------------------- Sistem

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemScreen(onOpenLegacy: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // State disimpan di prefs: tab ini dibuang setiap kali pindah tab, jadi tanpa ini
    // tombol root dan pilihan preset selalu kembali ke kondisi awal.
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var gos by remember { mutableStateOf<JSONObject?>(null) }
    var globalPreset by remember { mutableStateOf(prefs.getString("global_preset", null)) }
    var rootOk by remember {
        mutableStateOf<Boolean?>(if (prefs.getBoolean("root_granted", false)) true else null)
    }
    var rootBusy by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        gos = runCatching { JSONObject(ipc("gos_status")) }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Sistem",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium
        )

        SectionCard(
            title = "Preset global",
            subtitle = "Mode ala powercfg (Scene) — dipakai game tanpa config custom."
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "auto" to "Auto",
                    "eco" to "ECO",
                    "balance" to "Balanced",
                    "performance" to "Perf",
                    "fast" to "Esports",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = globalPreset == mode,
                        onClick = {
                            globalPreset = mode
                            prefs.edit().putString("global_preset", mode).apply()
                            scope.launch { ipc("set_global_preset $mode") }
                        },
                        label = { Text(label) }
                    )
                }
            }
        }

        SectionCard(
            title = "Samsung GOS",
            subtitle = "Game Optimizing Service yang membatasi performa saat gaming."
        ) {
            val hasGos = gos?.optBoolean("has_gos") ?: false
            if (gos == null) {
                CircularProgressIndicator()
            } else if (!hasGos) {
                Text("Tidak ada GOS di ROM ini — tidak perlu bypass.")
            } else {
                Text(if (gos!!.optBoolean("disabled")) "Status: disabled (bypass)" else "Status: enabled (stock)")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = {
                        scope.launch { ipc("gos_disable"); gos = JSONObject(ipc("gos_status")) }
                    }) { Text("Disable") }
                    OutlinedButton(onClick = {
                        scope.launch { ipc("gos_restore"); gos = JSONObject(ipc("gos_status")) }
                    }) { Text("Restore") }
                }
            }
        }

        SectionCard(
            title = "KernelSU access",
            subtitle = "Daftarkan app ke Superuser: tekan lalu izinkan dialognya."
        ) {
            when {
                rootBusy -> Row {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Spacer(modifier = Modifier.padding(6.dp))
                    Text("Menunggu Superuser…")
                }
                rootOk == true -> StatusPill("Root granted")
                else -> Button(onClick = {
                    rootBusy = true
                    scope.launch {
                        rootOk = runCatching {
                            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                            val out = p.inputStream.bufferedReader().readLine()
                            p.waitFor()
                            out?.startsWith("uid=0") == true
                        }.getOrDefault(false)
                        if (rootOk == true) prefs.edit().putBoolean("root_granted", true).apply()
                        rootBusy = false
                    }
                }) { Text("Request root access") }
            }
        }

        SectionCard(title = "Lainnya") {
            OutlinedButton(onClick = onOpenLegacy, modifier = Modifier.fillMaxWidth()) {
                Text("Buka konsol legacy (WebView)")
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = { confirmRestore = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Pulihkan semua ke stock")
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Pulihkan ke stock?") },
            text = { Text("GPU, CPU, jaringan dan semua knob dikembalikan ke nilai pabrik. Sesi aktif akan diakhiri.") },
            confirmButton = {
                Button(onClick = {
                    confirmRestore = false
                    scope.launch { ipc("restore_all") }
                }) { Text("Ya, pulihkan") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmRestore = false }) { Text("Batal") }
            }
        )
    }
}

// ------------------------------------------------------------------ chart

private const val HISTORY_SIZE = 60 // 60 sampel x 2 detik ≈ 2 menit

/** Grafik garis ringan untuk riwayat nilai; sumbu Y menyesuaikan min–max. */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.16f)
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val range = (hi - lo).takeIf { it > 0.01f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - lo) / range) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, fill)
        drawPath(
            path,
            line,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// ------------------------------------------------------------------ util

fun formatElapsed(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
