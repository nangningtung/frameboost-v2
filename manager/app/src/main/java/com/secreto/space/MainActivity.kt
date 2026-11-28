package com.secreto.space

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager native (Compose) — Fase 0 skeleton.
 * Layar tunggal: status daemon via IPC + pintu ke konsol legacy (WebView)
 * sampai paritas layar tercapai. Side panel overlay tidak tersentuh.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ManagerHome(onOpenLegacy = { openLegacyConsole() })
                }
            }
        }
    }

    private fun openLegacyConsole() {
        startActivity(Intent(this, LegacyWebViewActivity::class.java))
    }
}

@Composable
private fun ManagerHome(onOpenLegacy: () -> Unit) {
    var probe by remember { mutableStateOf("menghubungi daemon…") }
    var probeOk by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            probe = IpcClient.sendCommand("probe")
        }
        probeOk = probe.contains("\"device\":\"a50\"")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Secreto Space", style = MaterialTheme.typography.headlineMedium)
        Text("Manager native — Fase 0", style = MaterialTheme.typography.bodyMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (probeOk) {
                    true -> MaterialTheme.colorScheme.primaryContainer
                    false -> MaterialTheme.colorScheme.errorContainer
                    null -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row {
                    Text("Daemon  ", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (probeOk) {
                            true -> "TERHUBUNG"
                            false -> "GAGAL"
                            null -> "…"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    probe.take(400),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenLegacy
        ) {
            Text("Buka konsol legacy (WebView)")
        }
    }
}
