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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val SESSION_HISTORY = 60 // 60 sampel x 2 dtk ≈ 2 menit

/** Tab Sesi: telemetri live, guard sesi, dan probe jaringan. */
@Composable
fun SessionScreen() {
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf<JSONObject?>(null) }
    val fpsHistory = remember { mutableStateListOf<Float>() }
    var ping by remember { mutableStateOf<String?>(null) }
    var pingBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (isActive) {
            val next = runCatching { JSONObject(ipc("snapshot")) }.getOrNull()
            snap = next
            next?.let {
                fpsHistory.add(it.optDouble("fps", 0.0).toFloat())
                if (fpsHistory.size > SESSION_HISTORY) fpsHistory.removeAt(0)
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
            "Sesi",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium
        )

        if (s == null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val session = s.optJSONObject("session")
        val active = session?.optBoolean("active") == true || s.optBoolean("is_game", false)
        val pkg = session?.optString("pkg")?.ifBlank { null } ?: s.optString("game_pkg")

        // --- Sesi aktif ---
        SectionCard(
            title = if (active) "Sesi aktif" else "Belum ada sesi",
            subtitle = pkg.ifBlank { "Buka game dari tab Game untuk memulai sesi." }
        ) {
            StatusPill(text = if (active) "● AKTIF" else "STANDBY", error = false)
            if (active) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCard(
                        "Durasi",
                        formatElapsed(session?.optLong("elapsed_s", 0) ?: 0L),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        "FPS rata-rata",
                        session?.optDouble("avg_fps", 0.0)?.takeIf { it > 0 }
                            ?.let { "%.1f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            ipc("session_end")
                            snap = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Akhiri sesi") }
            }
        }

        // --- Telemetri live ---
        StatGrid(
            cells = listOf(
                {
                    StatCard(
                        "FPS",
                        s.optDouble("fps", 0.0).takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "—",
                        sub = "1% low " + (s.optDouble("p1_low_fps", 0.0).takeIf { it > 0 }
                            ?.let { "%.0f".format(it) } ?: "—")
                    )
                },
                {
                    StatCard(
                        "Suhu SoC",
                        "%.1f °C".format(s.optInt("temp_big_mc", 0) / 1000.0),
                        sub = "GPU %.1f °C".format(s.optInt("temp_gpu_mc", 0) / 1000.0)
                    )
                },
                {
                    val gpu = s.optJSONObject("gpu")
                    StatCard(
                        "GPU",
                        "${gpu?.optInt("busy_pct", 0) ?: 0}%",
                        sub = "${(gpu?.optInt("current_clock", 0) ?: 0) / 1000} MHz · " +
                            (gpu?.optString("governor", "") ?: "")
                    )
                },
                {
                    val batt = s.optJSONObject("battery")
                    StatCard(
                        "Baterai",
                        "${batt?.optInt("capacity_pct", 0) ?: 0}%",
                        sub = "${batt?.optInt("current_now_ma", 0) ?: 0} mA · " +
                            "%.1f °C".format(batt?.optDouble("temp_c", 0.0) ?: 0.0)
                    )
                },
            )
        )

        // --- Riwayat FPS ---
        SectionCard(
            title = "FPS",
            subtitle = "Riwayat ±2 menit · ${"%.1f".format(fpsHistory.lastOrNull() ?: 0f)} FPS"
        ) {
            Sparkline(
                values = fpsHistory.toList(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            )
        }

        // --- Guard sesi ---
        val dnd = s.optBoolean("heads_up_blocked", false)
        val batt = s.optJSONObject("battery")
        val bypass = batt?.optBoolean("bypass_charging", false) ?: false
        SectionCard(title = "Guard sesi") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Game DND", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Blokir notifikasi heads-up saat game",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = dnd,
                    onCheckedChange = {
                        scope.launch { ipc("set_heads_up ${if (it) "0" else "1"}") }
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bypass charging", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Stop isi daya saat sesi (jaga suhu)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = bypass,
                    onCheckedChange = {
                        scope.launch { ipc("set_bypass_charge ${if (it) "1" else "0"}") }
                    }
                )
            }
        }

        // --- Jaringan ---
        val net = s.optJSONObject("net")
        SectionCard(
            title = "Jaringan",
            subtitle = "TCP ${net?.optString("congestion_control", "—")} · " +
                (net?.optString("interface", "—") ?: "—")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        pingBusy = true
                        ping = null
                        scope.launch {
                            val res = runCatching { JSONObject(ipc("net_ping 1.1.1.1 3")) }.getOrNull()
                            ping = res?.let {
                                "1.1.1.1 → ${"%.1f".format(it.optDouble("avg_ms", 0.0))} ms · " +
                                    "loss ${"%.0f".format(it.optDouble("loss_pct", 0.0))}% · " +
                                    "jitter ${"%.1f".format(it.optDouble("jitter_ms", 0.0))} ms"
                            } ?: "Ping gagal"
                            pingBusy = false
                        }
                    },
                    enabled = !pingBusy
                ) { Text(if (pingBusy) "Probing…" else "Ping 1.1.1.1") }
                ping?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}
