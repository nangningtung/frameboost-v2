package com.secreto.space.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun GamesScreen() {
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<GameRow>>(emptyList()) }
    var sessionPkg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var detailPkg by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            val arr = runCatching { org.json.JSONArray(ipc("list_games")) }
                .getOrDefault(org.json.JSONArray())
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                GameRow(
                    pkg = o.optString("pkg"),
                    name = o.optString("name"),
                    source = o.optString("source"),
                    preset = o.optString("preset"),
                    autoBbr = o.optBoolean("auto_bbr")
                )
            }
            val snap = runCatching { JSONObject(ipc("snapshot")) }.getOrNull()
            sessionPkg = snap?.optJSONObject("session")?.optString("pkg", "") ?: ""
            games = list
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    // Keep the hero badge truthful while the user is playing or returns from a game.
    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { JSONObject(ipc("snapshot")) }
                .getOrNull()
                ?.let { snapshot ->
                    sessionPkg = snapshot.optJSONObject("session")?.optString("pkg", "") ?: ""
                }
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
            Text("Selamat datang di", style = MaterialTheme.typography.labelLarge)
            Text(
                "Game Space",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Pilih game. Nyalakan performa. Main tanpa distraksi.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (loading) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else if (games.isEmpty()) {
            EmptyGameCarousel()
        } else {
            GameCarousel(
                games = games,
                sessionPkg = sessionPkg,
                onOpen = { detailPkg = it }
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Library kamu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${games.size} game", style = MaterialTheme.typography.labelLarge)
        }

        if (!loading && games.isNotEmpty()) {
            games.forEach { game ->
                GameRowCard(game, active = sessionPkg == game.pkg) { detailPkg = game.pkg }
            }
        } else if (!loading) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Belum ada game yang terdeteksi. Tambahkan game lewat konsol legacy, lalu tekan scan ulang.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = { reload() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50)
                ) { Text("Scan ulang library") }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    val detail = games.find { it.pkg == detailPkg }
    if (detail != null) {
        GameDetailSheet(
            game = detail,
            sessionPkg = sessionPkg,
            onClose = { detailPkg = null; reload() }
        )
    }
}

@Composable
private fun GameCarousel(
    games: List<GameRow>,
    sessionPkg: String,
    onOpen: (String) -> Unit
) {
    val pager = rememberPagerState(pageCount = { games.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(games.size) {
        while (games.size > 1) {
            delay(5500)
            scope.launch { pager.animateScrollToPage((pager.currentPage + 1) % games.size) }
        }
    }

    HorizontalPager(
        state = pager,
        pageSize = PageSize.Fill,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        pageSpacing = 14.dp
    ) { page ->
        GameHeroCard(games[page], active = games[page].pkg == sessionPkg) {
            onOpen(games[page].pkg)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(games.size) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (index == pager.currentPage) 22.dp else 7.dp, 7.dp)
                    .background(
                        if (index == pager.currentPage) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape
                    )
            )
        }
    }
}

@Composable
private fun EmptyGameCarousel() {
    val slides = listOf(
        "Your games, tuned" to "Preset otomatis per game",
        "Play your way" to "FPS stabil, panas terkendali",
        "Built for long sessions" to "Satu tap untuk mulai"
    )
    val pager = rememberPagerState(pageCount = { slides.size })
    HorizontalPager(
        state = pager,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        pageSpacing = 14.dp
    ) { page ->
        Card(
            modifier = Modifier.fillMaxWidth().height(224.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("SECRETO SPACE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(slides[page].first, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(slides[page].second, style = MaterialTheme.typography.bodyLarge)
                }
                Text("Tambahkan game untuk mulai", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun GameHeroCard(game: GameRow, active: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(game.pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(game.pkg).toBitmap(160, 160).asImageBitmap()
        }.getOrNull()
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(254.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                    )
                )
                .padding(22.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("READY TO PLAY", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    if (active) StatusPill("AKTIF")
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (icon != null) Image(icon, contentDescription = game.name, modifier = Modifier.size(68.dp))
                    Column {
                        Text(game.name.ifEmpty { game.pkg }, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${game.preset}  •  Tap untuk atur", color = Color.White.copy(alpha = .82f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRowCard(g: GameRow, active: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val icon = remember(g.pkg) {
        runCatching {
            ctx.packageManager.getApplicationIcon(g.pkg).toBitmap(64, 64).asImageBitmap()
        }.getOrNull()
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = g.name,
                    modifier = Modifier.size(44.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    g.name.ifEmpty { g.pkg },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    g.pkg,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (active) {
                StatusPill("AKTIF")
            } else {
                Text(
                    g.preset,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

private data class GameRow(
    val pkg: String,
    val name: String,
    val source: String,
    val preset: String,
    val autoBbr: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameDetailSheet(
    game: GameRow,
    sessionPkg: String,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var preset by remember { mutableStateOf(game.preset) }
    var bbr by remember { mutableStateOf(game.autoBbr) }
    var scale by remember { mutableStateOf("native") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val rs = runCatching { JSONObject(ipc("get_render_scale")) }.getOrNull()
        scale = rs?.optJSONObject("render")
            ?.optJSONObject("scales")
            ?.optString(game.pkg, "native") ?: "native"
        if (scale.isNullOrBlank()) scale = "native"
    }

    fun applyProfile() {
        scope.launch {
            busy = true
            ipc("set_game_profile ${game.pkg} $preset ${if (bbr) "true" else "false"}")
            busy = false
        }
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val ctx = LocalContext.current
                val icon = remember(game.pkg) {
                    runCatching {
                        ctx.packageManager.getApplicationIcon(game.pkg).toBitmap(96, 96).asImageBitmap()
                    }.getOrNull()
                }
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = game.name,
                        modifier = Modifier.size(52.dp)
                    )
                }
                Column {
                    Text(
                        game.name.ifEmpty { game.pkg },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(game.pkg, style = MaterialTheme.typography.bodySmall)
                }
            }

            Text("Preset", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ECO", "BALANCED", "PERFORMANCE", "ESPORTS").forEach { p ->
                    FilterChip(
                        selected = preset == p,
                        onClick = {
                            preset = p
                            applyProfile()
                        },
                        label = { Text(p) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Auto-BBR", style = MaterialTheme.typography.titleMedium)
                    Text("Congestion control BBR saat game online", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = bbr, onCheckedChange = {
                    bbr = it
                    applyProfile()
                })
            }

            Text("Render scale", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("native", "900p", "720p").forEach { m ->
                    FilterChip(
                        selected = scale == m,
                        onClick = {
                            scale = m
                            scope.launch { ipc("set_render_scale ${game.pkg} $m") }
                        },
                        label = { Text(if (m == "native") "Native" else m) }
                    )
                }
            }
            Text(
                "Menurunkan resolusi saat GPU-bound; reset langsung & berjurnal.",
                style = MaterialTheme.typography.bodySmall
            )

            if (sessionPkg == game.pkg) {
                Button(
                    onClick = {
                        busy = true
                        scope.launch { ipc("session_end"); onClose() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Akhiri sesi") }
            } else {
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            ipc("session_start ${game.pkg}")
                            // A game hub should take the player into the game, not stop at a daemon command.
                            context.packageManager.getLaunchIntentForPackage(game.pkg)?.let { intent ->
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                            onClose()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50)
                ) { Text("Mulai sesi") }
            }

            if (busy) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
