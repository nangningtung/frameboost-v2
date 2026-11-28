package com.secreto.space.ui

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

    LaunchedEffect(Unit) {
        while (isActive) {
            snap = runCatching { JSONObject(ipc("snapshot")) }.getOrNull()
            delay(2000)
        }
    }

    val s = snap
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Beranda",
            style = MaterialTheme.typography.displaySmall,
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

@Composable
fun SystemScreen(onOpenLegacy: () -> Unit) {
    val scope = rememberCoroutineScope()
    var gos by remember { mutableStateOf<JSONObject?>(null) }
    var globalPreset by remember { mutableStateOf<String?>(null) }
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var rootBusy by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        gos = runCatching { JSONObject(ipc("gos_status")) }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Sistem",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium
        )

        SectionCard(
            title = "Preset global",
            subtitle = "Mode ala powercfg (Scene) — dipakai game tanpa config custom."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

// ------------------------------------------------------------------ util

fun formatElapsed(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
