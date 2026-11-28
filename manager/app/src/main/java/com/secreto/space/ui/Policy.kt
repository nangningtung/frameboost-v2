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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

// Ambang histeresis termal (selaras daemon: warn 87°C, crit 92°C, restore 82°C).
private const val TEMP_MIN = 40f
private const val TEMP_MAX = 95f
private const val TEMP_WARN = 87f
private const val TEMP_CRIT = 92f

/** Tab Kebijakan: histeresis termal, GOS, kernel & jalur pemulihan. */
@Composable
fun PolicyScreen() {
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf<JSONObject?>(null) }
    var gos by remember { mutableStateOf<JSONObject?>(null) }
    var restoreMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        gos = runCatching { JSONObject(ipc("gos_status")) }.getOrNull()
        while (isActive) {
            snap = runCatching { JSONObject(ipc("snapshot")) }.getOrNull()
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
            "Kebijakan",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium
        )

        if (s == null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // --- Histeresis termal ---
        val tempC = (s.optInt("temp_big_mc", 0) / 1000f)
        val state = s.optString("daemon_state", "IDLE")
        val hot = state == "WARN" || state == "CRIT"
        SectionCard(
            title = "Histeresis termal",
            subtitle = "SoC ${"%.1f".format(tempC)} °C · little ${"%.1f".format(s.optInt("temp_little_mc", 0) / 1000f)} °C · GPU ${"%.1f".format(s.optInt("temp_gpu_mc", 0) / 1000f)} °C"
        ) {
            StatusPill(text = state, error = hot)
            LinearProgressIndicator(
                progress = { ((tempC - TEMP_MIN) / (TEMP_MAX - TEMP_MIN)).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${TEMP_MIN.toInt()}°", style = MaterialTheme.typography.labelSmall)
                Text("Warn ${TEMP_WARN.toInt()}°", style = MaterialTheme.typography.labelSmall)
                Text("Crit ${TEMP_CRIT.toInt()}°", style = MaterialTheme.typography.labelSmall)
                Text("${TEMP_MAX.toInt()}°", style = MaterialTheme.typography.labelSmall)
            }
        }

        // --- Samsung GOS ---
        SectionCard(
            title = "Samsung GOS",
            subtitle = "Game Optimizing Service yang membatasi performa saat gaming."
        ) {
            val hasGos = gos?.optBoolean("has_gos") ?: false
            val disabled = gos?.optBoolean("disabled") ?: true
            when {
                gos == null -> CircularProgressIndicator()
                !hasGos -> Text("Tidak ada GOS di ROM ini — tidak perlu bypass.")
                else -> {
                    StatusPill(text = if (disabled) "BYPASS" else "STOCK", error = false)
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
        }

        // --- Kernel & SurfaceFlinger ---
        val cpu = s.optJSONObject("cpu")
        val gpu = s.optJSONObject("gpu")
        val net = s.optJSONObject("net")
        SectionCard(title = "Kernel & SurfaceFlinger") {
            InfoRow(
                "CPU Big",
                "${(cpu?.optJSONObject("big")?.optInt("cur_freq", 0) ?: 0) / 1000} MHz · " +
                    (cpu?.optJSONObject("big")?.optString("gov", "") ?: "")
            )
            InfoRow(
                "CPU Little",
                "${(cpu?.optJSONObject("little")?.optInt("cur_freq", 0) ?: 0) / 1000} MHz · " +
                    (cpu?.optJSONObject("little")?.optString("gov", "") ?: "")
            )
            InfoRow("GPU governor", gpu?.optString("governor", "") ?: "—")
            InfoRow("ZRAM swappiness", s.optInt("vm_swappiness", 0).toString())
            InfoRow("TCP congestion", net?.optString("congestion_control", "") ?: "—")
        }

        // --- Jalur pemulihan ---
        SectionCard(
            title = "Jalur pemulihan keluar",
            subtitle = if (s.optBoolean("stock_saved", false))
                "Snapshot stock tersimpan — aman untuk dipulihkan."
            else "Snapshot stock belum dibuat."
        ) {
            Text(
                "1. Restore refresh rate  2. Reset CPU governor\n" +
                    "3. Nonaktifkan DND  4. Restore kecerahan",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val res = ipc("restore_all")
                        restoreMsg = runCatching {
                            if (JSONObject(res).optString("status") == "ok") "Dipulihkan ke stock"
                            else "Gagal memulihkan"
                        }.getOrDefault("Gagal memulihkan")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Pulihkan semua ke stock") }
            restoreMsg?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}
