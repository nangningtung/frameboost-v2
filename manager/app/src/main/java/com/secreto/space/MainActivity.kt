package com.secreto.space

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.secreto.space.ui.HomeScreen
import com.secreto.space.ui.GamesScreen
import com.secreto.space.ui.SystemScreen
import com.secreto.space.ui.SecretoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Secreto Space — Manager native (Compose), tema Pixel/Material You.
 *
 * Alur: onboarding pertama kali → navigasi 3 tab (Beranda / Game / Sistem).
 * Side panel in-game dan daemon tidak tersentuh.
 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy {
        getSharedPreferences("secreto_manager", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecretoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarded", false)) }
                    if (onboarded) {
                        AppNav(onOpenLegacy = { openLegacyConsole() })
                    } else {
                        OnboardingFlow(
                            onFinished = {
                                prefs.edit().putBoolean("onboarded", true).apply()
                                onboarded = true
                            }
                        )
                    }
                }
            }
        }
    }

    private fun openLegacyConsole() {
        startActivity(Intent(this, LegacyWebViewActivity::class.java))
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Beranda", Icons.Filled.Home),
    Games("Game", Icons.Filled.Star),
    System("Sistem", Icons.Filled.Settings),
}

@Composable
private fun AppNav(onOpenLegacy: () -> Unit) {
    // Game library is the front door: Secreto Space should feel like a game hub,
    // not a diagnostics dashboard.
    var tab by rememberSaveable { mutableIntStateOf(Tab.Games.ordinal) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            Crossfade(targetState = tab, label = "tab") { current ->
                when (current) {
                    0 -> HomeScreen()
                    1 -> GamesScreen()
                    else -> SystemScreen(onOpenLegacy = onOpenLegacy)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- onboarding

private const val STEP_COUNT = 5 // welcome, overlay, root, daemon, ready

@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var rootOk by remember { mutableStateOf<Boolean?>(null) } // null = belum dicek
    var rootBusy by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<String?>(null) }
    var bootPhase by remember { mutableStateOf("") }

    // Izin overlay bisa berubah setelah user kembali dari Settings — cek ulang
    // setiap kali layar kembali ke resumed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayOk = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun grantRoot() {
        rootBusy = true
        scope.launch {
            rootOk = withContext(Dispatchers.IO) {
                runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                    val out = p.inputStream.bufferedReader().readLine()
                    p.waitFor()
                    out?.startsWith("uid=0") == true
                }.getOrDefault(false)
            }
            rootBusy = false
            if (rootOk == true) {
                context.getSharedPreferences("secreto_manager", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("root_granted", true).apply()
            }
        }
    }

    fun finishBootHandoff() {
        scope.launch {
            val phases = listOf("Menautkan daemon…", "Memindai game…", "Menyetel profil…", "Siap.")
            for (ph in phases) {
                bootPhase = ph
                delay(700)
            }
            onFinished()
        }
    }

    val progress = animateFloatAsState(
        targetValue = (step + 1f) / STEP_COUNT,
        animationSpec = tween(300),
        label = "progress"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text("Selamat datang di", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Secreto Space",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium
        )
        LinearProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedContent(targetState = step, transitionSpec = { fadeIn() togetherWith fadeOut() }) { s ->
            when (s) {
                0 -> OnboardingCard(
                    title = "Game space untuk A50",
                    body = "Preset per game, side panel in-game, tangkap layar & rekam, " +
                        "render scale, jaringan, dan pemulihan ke stock yang terjamin. " +
                        "Semua dijalankan daemon Rust lokal — tanpa cloud, tanpa internet."
                ) {
                    Button(
                        onClick = { step = 1 },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape
                    ) {
                        Text("Mulai penyiapan")
                    }
                }

                1 -> OnboardingCard(
                    title = "1/3 — Izin overlay",
                    body = "Dibutuhkan untuk side panel & FPS overlay di atas game. " +
                        "Jika sudah pernah diizinkan lewat modul, langkah ini otomatis hijau.",
                    status = if (overlayOk) CheckState.OK else CheckState.WAITING
                ) {
                    if (overlayOk) {
                        Button(
                            onClick = { step = 2 },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape
                        ) {
                            Text("Lanjut")
                        }
                    } else {
                        Button(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:com.secreto.space")
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape
                        ) {
                            Text("Izinkan tampil di atas aplikasi lain")
                        }
                    }
                }

                2 -> OnboardingCard(
                    title = "2/3 — Akses root",
                    body = "Manager berbicara ke daemon lewat KernelSU/Superuser. " +
                        "Tekan tombolnya lalu izinkan dialog Superuser untuk com.secreto.space.",
                    status = when {
                        rootOk == true -> CheckState.OK
                        rootOk == false -> CheckState.FAILED
                        else -> CheckState.WAITING
                    }
                ) {
                    when {
                        rootBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp))
                            Spacer(modifier = Modifier.padding(6.dp))
                            Text("Menunggu keputusan Superuser…")
                        }
                        rootOk == true -> Button(
                            onClick = { step = 3 },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape
                        ) {
                            Text("Lanjut")
                        }
                        else -> Button(
                            onClick = { grantRoot() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape
                        ) {
                            Text("Minta akses root")
                        }
                    }
                }

                3 -> OnboardingCard(
                    title = "3/3 — Hubungkan daemon",
                    body = "Memastikan secretod berjalan di perangkat ini.",
                    status = when {
                        probe?.contains("\"device\":\"a50\"") == true -> CheckState.OK
                        else -> CheckState.WAITING
                    }
                ) {
                    LaunchedEffect(Unit) {
                        probe = withContext(Dispatchers.IO) { IpcClient.sendCommand("probe") }
                    }
                    if (probe != null) {
                        Text(
                            probe!!.take(300),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (probe!!.contains("\"device\":\"a50\"")) {
                            Button(
                                onClick = { step = 4 },
                                modifier = Modifier.fillMaxWidth(),
                                shape = CircleShape
                            ) {
                                Text("Selesai penyiapan")
                            }
                        } else {
                            Text(
                                "Daemon tidak menjawab — pastikan modul aktif.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                4 -> OnboardingCard(
                    title = "Siap terbang 🚀",
                    body = bootPhase.ifEmpty { "Tekan tombol untuk menyelesaikan boot hand-off." }
                ) {
                    if (bootPhase.isEmpty()) {
                        Button(
                            onClick = { finishBootHandoff() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape
                        ) {
                            Text("Nyalakan Secreto Space")
                        }
                    } else if (bootPhase == "Siap.") {
                        Button(
                            onClick = onFinished,
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape
                        ) {
                            Text("Masuk")
                        }
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        Text(
            "Langkah ${step + 1} dari $STEP_COUNT",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private enum class CheckState { WAITING, OK, FAILED }

@Composable
private fun OnboardingCard(
    title: String,
    body: String,
    status: CheckState? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium
                )
                when (status) {
                    CheckState.OK -> StatusPill(text = "Siap")
                    CheckState.FAILED -> StatusPill(text = "Gagal")
                    else -> {}
                }
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

// ----------------------------------------------------------------------- home

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
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Secreto Space",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium
        )
        StatusPill(
            text = when (probeOk) {
                true -> "Daemon terhubung"
                false -> "Daemon tidak menjawab"
                null -> "Menyambung…"
            }
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Perangkat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    probe.take(400),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Button(
            onClick = {
                // placeholder Fase 1: diganti layar library Compose
            },
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape
        ) {
            Text("Library game (Fase 1)")
        }

        OutlinedButton(
            onClick = onOpenLegacy,
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape
        ) {
            Text("Buka konsol legacy (WebView)")
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}
