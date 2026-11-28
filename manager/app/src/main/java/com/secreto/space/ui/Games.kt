package com.secreto.space.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private val PRESETS = listOf("ECO", "BALANCED", "PERFORMANCE", "ESPORTS")
private val SCALES = listOf("native", "900p", "720p")
private val COVER_HEIGHT = 250.dp

@Composable
fun GamesScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var games by remember { mutableStateOf<List<GameRow>>(emptyList()) }
    var sessionPkg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var detailPkg by remember { mutableStateOf<String?>(null) }

    fun reload(scan: Boolean = false) {
        scope.launch {
            loading = true
            if (scan) ipc("scan_games")
            val arr = runCatching { JSONArray(ipc("list_games")) }.getOrDefault(JSONArray())
            games = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                GameRow(
                    pkg = o.optString("pkg"),
                    name = o.optString("name"),
                    source = o.optString("source"),
                    preset = o.optString("preset", "BALANCED").uppercase(),
                    autoBbr = o.optBoolean("auto_bbr"),
                    profile = o.optString("profile"),
                )
            }
            loading = false
        }
    }

    fun startSession(pkg: String) {
        scope.launch {
            ipc("session_start $pkg")
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    // Keep the "AKTIF" badge in sync with the daemon while the screen is open.
    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { JSONObject(ipc("snapshot")) }.getOrNull()?.let { snap ->
                sessionPkg = snap.optJSONObject("session")?.optString("pkg", "") ?: ""
            }
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Game library",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Terdeteksi otomatis · ${games.size} game",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = { reload(scan = true) }, shape = RoundedCornerShape(50)) {
                Text("Scan")
            }
        }

        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(COVER_HEIGHT),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            games.isEmpty() -> EmptyLibrary(onScan = { reload(scan = true) })

            else -> {
                GameCarousel(
                    games = games,
                    sessionPkg = sessionPkg,
                    onPlay = { startSession(it.pkg) },
                    onTune = { detailPkg = it.pkg }
                )

                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Semua game",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    games.forEach { game ->
                        GameRowCard(game, active = sessionPkg == game.pkg) { detailPkg = game.pkg }
                    }
                }
            }
        }
    }

    val detail = games.find { it.pkg == detailPkg }
    if (detail != null) {
        GameDetailSheet(
            game = detail,
            sessionPkg = sessionPkg,
            onStart = { startSession(detail.pkg); detailPkg = null },
            onClose = { detailPkg = null; reload() }
        )
    }
}

// ----------------------------------------------------------------- data

private data class GameRow(
    val pkg: String,
    val name: String,
    val source: String,
    val preset: String,
    val autoBbr: Boolean,
    val profile: String,
)

private fun GameRow.label(): String = name.ifEmpty { pkg }

/** Hue deterministik per paket, sama dengan WebUI lama (charSumHue). */
private fun GameRow.hue(): Float =
    ((pkg.sumOf { it.code } * 137.508) % 360).roundToInt().toFloat()

private fun sourceLabel(source: String): String = when (source) {
    "manual" -> "MANUAL"
    "config" -> "CONFIG"
    "auto" -> "AUTO"
    else -> "BUILTIN"
}

// ------------------------------------------------------------- carousel

@Composable
private fun GameCarousel(
    games: List<GameRow>,
    sessionPkg: String,
    onPlay: (GameRow) -> Unit,
    onTune: (GameRow) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { games.size })
    val scope = rememberCoroutineScope()
    val current = games.getOrNull(pager.currentPage.coerceIn(0, games.lastIndex))

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Lebar cover mengikuti layar, tapi dibatasi agar tidak terlalu besar di tablet.
        val coverWidth = minOf(maxWidth * 0.58f, 260.dp)
        HorizontalPager(
            state = pager,
            pageSize = PageSize.Fixed(coverWidth),
            contentPadding = PaddingValues(horizontal = (maxWidth - coverWidth) / 2),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(COVER_HEIGHT)
        ) { page ->
            val distance = pager.getOffsetDistanceInPages(page).absoluteValue.coerceIn(0f, 1f)
            CoverCard(
                game = games[page],
                modifier = Modifier.graphicsLayer {
                    val s = lerp(1f, 0.88f, distance)
                    scaleX = s
                    scaleY = s
                    alpha = lerp(1f, 0.6f, distance)
                },
                onClick = {
                    if (pager.currentPage == page) onTune(games[page])
                    else scope.launch { pager.animateScrollToPage(page) }
                }
            )
        }
    }

    AnimatedContent(
        targetState = current,
        modifier = Modifier.padding(horizontal = 16.dp),
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "hero"
    ) { target ->
        if (target != null) {
            HeroBlock(
                game = target,
                active = sessionPkg == target.pkg,
                onPlay = { onPlay(target) },
                onTune = { onTune(target) }
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        games.indices.forEach { index ->
            PageDot(active = index == pager.currentPage)
        }
    }
}

@Composable
private fun HeroBlock(game: GameRow, active: Boolean, onPlay: () -> Unit, onTune: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusPill(game.preset)
            if (active) StatusPill("AKTIF")
        }
        Text(
            game.label(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            game.pkg,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPlay,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50)
            ) { Text("Main") }
            OutlinedButton(
                onClick = onTune,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50)
            ) { Text("Atur") }
        }
    }
}

@Composable
private fun PageDot(active: Boolean) {
    val width by animateDpAsState(
        targetValue = if (active) 18.dp else 6.dp,
        animationSpec = tween(250),
        label = "dotWidth"
    )
    val color by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(250),
        label = "dotColor"
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(width, 6.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun CoverCard(game: GameRow, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(game.pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(game.pkg).toBitmap(192, 192).asImageBitmap()
        }.getOrNull()
    }
    val hue = game.hue()
    val chipColor = Color.White.copy(alpha = 0.16f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.hsv(hue, 0.62f, 0.34f),
                        Color.hsv((hue + 40f) % 360f, 0.55f, 0.18f),
                        Color.hsv((hue + 70f) % 360f, 0.50f, 0.10f),
                    )
                )
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ChipLabel(sourceLabel(game.source), chipColor)
                ChipLabel(game.preset, chipColor)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = game.label(),
                        modifier = Modifier.size(104.dp)
                    )
                } else {
                    Text(
                        game.label().take(1).uppercase(),
                        color = Color.White,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column {
                Text(
                    game.label(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    game.pkg,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ChipLabel(text: String, background: Color) {
    Box(
        modifier = Modifier
            .background(background, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EmptyLibrary(onScan: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Belum ada game terdeteksi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Game yang terpasang akan muncul otomatis. Pasang game lalu scan ulang.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50)
            ) { Text("Scan ulang") }
        }
    }
}

// ----------------------------------------------------------------- list

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
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = g.label(),
                    modifier = Modifier.size(40.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    g.label(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${sourceLabel(g.source)} · ${g.pkg}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (active) {
                StatusPill("AKTIF")
            } else {
                Text(g.preset, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// --------------------------------------------------------------- detail

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GameDetailSheet(
    game: GameRow,
    sessionPkg: String,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var preset by remember { mutableStateOf(game.preset) }
    var bbr by remember { mutableStateOf(game.autoBbr) }
    var scale by remember { mutableStateOf("native") }
    var status by remember { mutableStateOf<String?>(null) }
    var reports by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(game.pkg) {
        val rs = runCatching { JSONObject(ipc("get_render_scale")) }.getOrNull()
        scale = rs?.optJSONObject("render")
            ?.optJSONObject("scales")
            ?.optString(game.pkg, "native")
            ?.ifBlank { "native" } ?: "native"
        reports = runCatching { JSONObject(ipc("session_history ${game.pkg}")) }.getOrNull()
    }

    fun saveProfile(newPreset: String, newBbr: Boolean) {
        scope.launch {
            busy = true
            val res = ipc("set_game_profile ${game.pkg} $newPreset ${if (newBbr) "true" else "false"}")
            val ok = runCatching { JSONObject(res).optString("status") == "ok" }.getOrDefault(false)
            status = if (ok) "Profil tersimpan" else "Gagal menyimpan profil"
            busy = false
        }
    }

    fun applyNow() {
        scope.launch {
            busy = true
            val res = ipc("apply_preset $preset")
            val state = runCatching { JSONObject(res).optString("status") }.getOrDefault("fail")
            status = when (state) {
                "ok" -> "Profil diterapkan"
                "idle" -> "Tersimpan untuk sesi berikutnya"
                else -> "Gagal menerapkan profil"
            }
            busy = false
        }
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val icon = remember(game.pkg) {
                    runCatching {
                        context.packageManager.getApplicationIcon(game.pkg).toBitmap(96, 96).asImageBitmap()
                    }.getOrNull()
                }
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = game.label(),
                        modifier = Modifier.size(48.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        game.label(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        game.pkg,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text("Preset per game", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PRESETS.forEach { p ->
                    FilterChip(
                        selected = preset == p,
                        onClick = {
                            preset = p
                            saveProfile(p, bbr)
                        },
                        label = { Text(p.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("TCP BBR", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Congestion control BBR saat game online",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = bbr, onCheckedChange = {
                    bbr = it
                    saveProfile(preset, it)
                })
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Sesi terakhir", style = MaterialTheme.typography.titleSmall)
                    Text(sessionSummary(reports), style = MaterialTheme.typography.bodyMedium)
                    sessionSuggestion(reports)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text("Render scale", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SCALES.forEach { m ->
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
                "Menurunkan resolusi saat GPU-bound. Direset otomatis saat keluar game.",
                style = MaterialTheme.typography.bodySmall
            )

            if (sessionPkg == game.pkg) {
                Button(
                    onClick = {
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
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(50)
                ) { Text("Main") }
            }

            OutlinedButton(
                onClick = { applyNow() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                enabled = !busy
            ) { Text("Terapkan profil sekarang") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        ipc("remove_game ${game.pkg}")
                        onClose()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Hapus dari library") }

            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (busy) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

private fun sessionSummary(reports: JSONObject?): String {
    val arr = reports?.optJSONArray("reports") ?: return "Belum ada sesi tercatat."
    if (arr.length() == 0) return "Belum ada sesi tercatat."
    val last = arr.optJSONObject(arr.length() - 1) ?: return "Belum ada sesi tercatat."
    val fps = last.optDouble("avg_fps", Double.NaN)
    val jank = last.optDouble("jank_pct", Double.NaN)
    val minutes = last.optLong("duration_s", 0) / 60
    val fpsText = if (fps.isNaN()) "—" else "%.1f FPS".format(fps)
    val jankText = if (jank.isNaN()) "—" else "%.1f%% jank".format(jank)
    return "${arr.length()} sesi · $minutes menit · $fpsText · $jankText"
}

private fun sessionSuggestion(reports: JSONObject?): String? {
    val s = reports ?: return null
    if (s.isNull("suggestion")) return null
    return s.optString("suggestion").ifBlank { null }
}
