package com.secreto.space.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

private val PRESETS = listOf("ECO", "BALANCED", "PERFORMANCE", "ESPORTS")
private val QUICK_PRESETS = listOf(
    "ECO" to "Save",
    "BALANCED" to "Balanced",
    "PERFORMANCE" to "Perf",
    "ESPORTS" to "Esports",
)
private val SCALES = listOf("native", "900p", "720p")
/** Rasio tinggi/lebar kartu cover (mendekati proporsi poster 176:244 pada referensi). */
private val COVER_ASPECT = 1.36f
private val GRID_TILE_HEIGHT = 220.dp
private val CARD_SHAPE = RoundedCornerShape(26.dp)
private val EASE_EMPHASIZED = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun GamesScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var games by remember { mutableStateOf<List<GameRow>>(emptyList()) }
    var sessionPkg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var scanning by remember { mutableStateOf(false) }
    var detailPkg by remember { mutableStateOf<String?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf<GameRow?>(null) }
    var gridMode by remember { mutableStateOf(prefs.getString("games_view", "carousel") == "grid") }

    fun parseGames(arr: JSONArray): List<GameRow> = (0 until arr.length()).mapNotNull { i ->
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

    fun reload(scan: Boolean = false) {
        scope.launch {
            if (scan) scanning = true else loading = true
            if (scan) ipc("scan_games")
            val arr = runCatching { JSONArray(ipc("list_games")) }.getOrDefault(JSONArray())
            games = parseGames(arr)
            loading = false
            scanning = false
        }
    }

    fun updateProfileQuick(game: GameRow, newPreset: String, newBbr: Boolean) {
        // Optimistic update so UI feels instant, synced with daemon response.
        games = games.map {
            if (it.pkg == game.pkg) it.copy(preset = newPreset, autoBbr = newBbr) else it
        }
        scope.launch {
            val res = ipc("set_game_profile ${game.pkg} $newPreset ${if (newBbr) "true" else "false"}")
            runCatching {
                val obj = JSONObject(res)
                if (obj.optString("status") == "ok" && obj.has("games")) {
                    games = parseGames(obj.getJSONArray("games"))
                }
            }
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

    // Keep "Sesi aktif" synced with daemon while screen is open.
    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { JSONObject(ipc("snapshot")) }.getOrNull()?.let { snap ->
                sessionPkg = snap.optJSONObject("session")?.optString("pkg", "") ?: ""
            }
            delay(2000)
        }
    }

    val bodyState = when {
        loading -> 0
        games.isEmpty() -> 1
        gridMode -> 2
        else -> 3
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GamesHeader(
                count = games.size,
                liveName = games.find { it.pkg == sessionPkg }?.label(),
                gridMode = gridMode,
                scanning = scanning,
                showViewToggle = !loading && games.isNotEmpty(),
                onToggleView = {
                    val next = !gridMode
                    gridMode = next
                    prefs.edit().putString("games_view", if (next) "grid" else "carousel").apply()
                },
                onScan = { reload(scan = true) },
                onAdd = { showAddSheet = true }
            )

            AnimatedContent(
                targetState = bodyState,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(tween(260, easing = EASE_EMPHASIZED)) +
                        scaleIn(initialScale = 0.96f, animationSpec = tween(300, easing = EASE_EMPHASIZED)))
                        .togetherWith(fadeOut(tween(150)))
                },
                label = "games-body"
            ) { state ->
                when (state) {
                    0 -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    1 -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { EmptyLibrary(onScan = { reload(scan = true) }, onAdd = { showAddSheet = true }) }

                    2 -> GameGrid(
                        games = games,
                        sessionPkg = sessionPkg,
                        onPlay = { launching = it },
                        onTune = { detailPkg = it.pkg }
                    )

                    else -> CoverSection(
                        games = games,
                        sessionPkg = sessionPkg,
                        onPlay = { launching = it },
                        onTune = { detailPkg = it.pkg },
                        onQuickPreset = { g, p -> updateProfileQuick(g, p, g.autoBbr) },
                        onToggleBbr = { g -> updateProfileQuick(g, g.preset, !g.autoBbr) }
                    )
                }
            }
        }

        launching?.let { game ->
            LaunchSplash(
                game = game,
                onDone = {
                    launching = null
                    startSession(game.pkg)
                }
            )
        }
    }

    if (showAddSheet) {
        AddGameSheet(
            existingPkgs = games.map { it.pkg }.toSet(),
            onAdded = {
                showAddSheet = false
                reload()
            },
            onClose = { showAddSheet = false }
        )
    }

    val detail = games.find { it.pkg == detailPkg }
    if (detail != null) {
        GameDetailSheet(
            game = detail,
            sessionPkg = sessionPkg,
            onStart = {
                val target = detail
                detailPkg = null
                launching = target
            },
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

private fun GameRow.shortPreset(): String = when (preset) {
    "ECO", "BATTERY" -> "Eco"
    "PERFORMANCE" -> "Perf"
    "ESPORTS" -> "Esports"
    else -> "Balanced"
}

private fun sourceLabel(source: String): String = when (source) {
    "manual" -> "MANUAL"
    "config" -> "CONFIG"
    "auto" -> "AUTO"
    else -> "BUILTIN"
}

@Composable
private fun rememberAppIcon(pkg: String, px: Int): ImageBitmap? {
    val context = LocalContext.current
    // Muat ikon di IO dispatcher: getApplicationIcon + toBitmap lumayan berat dan
    // kalau jalan di main thread bikin frame patah-patah saat pertama render.
    return produceState<ImageBitmap?>(initialValue = null, pkg, px) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
            }.getOrNull()
        }
    }.value
}

// ----------------------------------------------------------------- header

@Composable
private fun GamesHeader(
    count: Int,
    liveName: String?,
    gridMode: Boolean,
    scanning: Boolean,
    showViewToggle: Boolean,
    onToggleView: () -> Unit,
    onScan: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                GamepadGlyph(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column {
                Text(
                    "Game Space",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (liveName != null) "Aktif · $liveName" else "$count games installed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showViewToggle) {
                HeaderCircleButton(
                    onClick = onToggleView,
                    content = {
                        ViewModeGlyph(
                            showCarouselIcon = gridMode,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
            HeaderCircleButton(
                onClick = { if (!scanning) onScan() },
                content = {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Scan",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            )
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Add",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun HeaderCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun GamepadGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.24f),
            size = Size(w * 0.84f, h * 0.52f),
            cornerRadius = CornerRadius(h * 0.18f, h * 0.18f),
            style = Stroke(width = w * 0.11f)
        )
        // D-pad
        drawLine(color, Offset(w * 0.24f, h * 0.50f), Offset(w * 0.40f, h * 0.50f), strokeWidth = w * 0.10f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.32f, h * 0.42f), Offset(w * 0.32f, h * 0.58f), strokeWidth = w * 0.10f, cap = StrokeCap.Round)
        // Face buttons
        drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.68f, h * 0.43f))
        drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.77f, h * 0.56f))
    }
}

@Composable
private fun ViewModeGlyph(showCarouselIcon: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round)
        if (showCarouselIcon) {
            drawRoundRect(
                color = color,
                topLeft = Offset(w * 0.26f, h * 0.14f),
                size = Size(w * 0.48f, h * 0.72f),
                cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
                style = stroke
            )
            drawLine(color, Offset(w * 0.10f, h * 0.28f), Offset(w * 0.10f, h * 0.72f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
            drawLine(color, Offset(w * 0.90f, h * 0.28f), Offset(w * 0.90f, h * 0.72f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        } else {
            val box = w * 0.34f
            val r = CornerRadius(w * 0.08f, w * 0.08f)
            drawRoundRect(color, Offset(w * 0.10f, h * 0.10f), Size(box, box), r, style = stroke)
            drawRoundRect(color, Offset(w * 0.56f, h * 0.10f), Size(box, box), r, style = stroke)
            drawRoundRect(color, Offset(w * 0.10f, h * 0.56f), Size(box, box), r, style = stroke)
            drawRoundRect(color, Offset(w * 0.56f, h * 0.56f), Size(box, box), r, style = stroke)
        }
    }
}

// --------------------------------------------------------------- carousel

@Composable
private fun CoverSection(
    games: List<GameRow>,
    sessionPkg: String,
    onPlay: (GameRow) -> Unit,
    onTune: (GameRow) -> Unit,
    onQuickPreset: (GameRow, String) -> Unit,
    onToggleBbr: (GameRow) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { games.size })
    val scope = rememberCoroutineScope()
    val current = pager.currentPage.coerceIn(0, games.lastIndex)
    val game = games.getOrNull(current)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        CoverFlow(
            games = games,
            sessionPkg = sessionPkg,
            pager = pager,
            onPlay = onPlay
        )

        PagerDots(
            count = games.size,
            index = current,
            onSelect = { idx -> scope.launch { pager.animateScrollToPage(idx) } }
        )

        AnimatedContent(
            targetState = game,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            transitionSpec = {
                (fadeIn(tween(240)) + slideInVertically(tween(280, easing = EASE_EMPHASIZED)) { it / 6 })
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "hero"
        ) { target ->
            if (target != null) {
                HeroBlock(
                    game = target,
                    live = sessionPkg == target.pkg,
                    onPlay = { onPlay(target) },
                    onTune = { onTune(target) },
                    onQuickPreset = { preset -> onQuickPreset(target, preset) },
                    onToggleBbr = { onToggleBbr(target) }
                )
            }
        }
    }
}

@Composable
private fun CoverFlow(
    games: List<GameRow>,
    sessionPkg: String,
    pager: PagerState,
    onPlay: (GameRow) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current = pager.currentPage.coerceIn(0, games.lastIndex.coerceAtLeast(0))

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Cover lebih besar & responsif: lebar mengikuti layar, tinggi mengikuti rasio poster.
        val shellWidth = maxWidth
        val cardWidth = minOf(shellWidth * 0.56f, 244.dp)
        val cardHeight = minOf(cardWidth * COVER_ASPECT, 336.dp)

        // Jarak pusat antar kartu rapat (overlap ala cover flow), transform 2D ringan.
        val cardWidthPx = with(LocalDensity.current) { cardWidth.toPx() }
        val shiftPx = cardWidthPx * 0.72f - cardWidthPx
        HorizontalPager(
            state = pager,
            pageSize = PageSize.Fixed(cardWidth),
            contentPadding = PaddingValues(horizontal = (shellWidth - cardWidth) / 2),
            pageSpacing = 0.dp,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
        ) { page ->
            CoverCard(
                game = games[page],
                live = games[page].pkg == sessionPkg,
                active = page == current,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(20f - abs(page - current))
                    .graphicsLayer {
                        // Transform 2D ringan saja: tanpa rotationY + cameraDistance.
                        val offset = page - (pager.currentPage + pager.currentPageOffsetFraction)
                        val dist = offset.absoluteValue
                        val t = dist.coerceIn(0f, 1f)
                        translationX = offset * shiftPx
                        val s = if (dist <= 1f) lerp(1f, 0.92f, t)
                        else max(0.88f, 0.92f - (dist - 1f) * 0.05f)
                        scaleX = s
                        scaleY = s
                        alpha = if (dist > 2f) 0f else lerp(1f, 0.70f, t)
                    },
                onClick = {
                    if (page == current) onPlay(games[page])
                    else scope.launch { pager.animateScrollToPage(page) }
                }
            )
        }
    }
}

@Composable
private fun PagerDots(count: Int, index: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            PageDot(active = i == index, onClick = { onSelect(i) })
        }
    }
}

@Composable
private fun PageDot(active: Boolean, onClick: () -> Unit) {
    val width by animateDpAsState(
        targetValue = if (active) 20.dp else 6.dp,
        animationSpec = tween(300, easing = EASE_EMPHASIZED),
        label = "dotWidth"
    )
    val color by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(300, easing = EASE_EMPHASIZED),
        label = "dotColor"
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .size(width, 6.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun HeroBlock(
    game: GameRow,
    live: Boolean,
    onPlay: () -> Unit,
    onTune: () -> Unit,
    onQuickPreset: (String) -> Unit,
    onToggleBbr: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Ready / Active Pill
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (live) Color(0xFF34D399) else MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
            )
            Text(
                "${sourceLabel(game.source)} • ${if (live) "Session Active" else "Ready"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }

        Text(
            game.label(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            "${game.shortPreset()} • BBR ${if (game.autoBbr) "On" else "Off"} • ${game.pkg}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Play Pill + Tune Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onPlay,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .width(210.dp)
                    .height(44.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onTune,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Text("Tune", fontWeight = FontWeight.SemiBold)
            }
        }

        // Compact bottom quick-bar: Save / Balanced / Perf / Esports + BBR pill
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val activePreset = if (game.preset == "BATTERY") "ECO" else game.preset
                QUICK_PRESETS.forEach { (code, label) ->
                    val selected = activePreset == code
                    val bg by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else Color.Transparent,
                        animationSpec = tween(220),
                        label = "qmodeBg"
                    )
                    val fg by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(220),
                        label = "qmodeFg"
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(bg)
                            .clickable { onQuickPreset(code) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            label,
                            color = fg,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            // Quick BBR Toggle Pill
            val bbrBg by animateColorAsState(
                targetValue = if (game.autoBbr) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                animationSpec = tween(220),
                label = "bbrBg"
            )
            val bbrFg by animateColorAsState(
                targetValue = if (game.autoBbr) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(220),
                label = "bbrFg"
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bbrBg)
                    .border(
                        1.dp,
                        if (game.autoBbr) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        CircleShape
                    )
                    .clickable(onClick = onToggleBbr)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (game.autoBbr) "BBR On" else "BBR Off",
                    color = bbrFg,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Kartu cover: gradasi per game, ikon aplikasi di tengah, dan scrim gelap di bawah
 * untuk judul.
 */
@Composable
private fun CoverCard(
    game: GameRow,
    live: Boolean,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    bottomAction: (@Composable () -> Unit)? = null,
) {
    val hue = game.hue()
    val icon = rememberAppIcon(game.pkg, 128)
    Box(
        modifier = modifier
            .clip(CARD_SHAPE)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.hsv(hue, 0.62f, 0.36f),
                        Color.hsv((hue + 40f) % 360f, 0.55f, 0.19f),
                        Color.hsv((hue + 70f) % 360f, 0.50f, 0.10f),
                    )
                )
            )
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = Color.White.copy(alpha = if (active) 0.32f else 0.10f),
                shape = CARD_SHAPE
            )
            .clickable(onClick = onClick)
            .padding(13.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(180.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color.hsv(hue, 0.7f, 1f).copy(alpha = 0.34f), Color.Transparent)
                    ),
                    CircleShape
                )
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChipLabel(sourceLabel(game.source), Color.Black.copy(alpha = 0.35f))
                if (live) StatusPill("AKTIF") else ChipLabel(game.shortPreset().uppercase(), Color.Black.copy(alpha = 0.40f))
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
                        modifier = Modifier.size(if (bottomAction != null) 74.dp else 116.dp)
                    )
                } else {
                    Text(
                        game.label().take(1).uppercase(),
                        color = Color.White,
                        fontSize = if (bottomAction != null) 46.sp else 72.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                        )
                    )
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    game.label(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (bottomAction != null) {
                    bottomAction()
                } else {
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
}

@Composable
private fun ChipLabel(text: String, background: Color) {
    Box(
        modifier = Modifier
            .background(background, CircleShape)
            .border(0.8.dp, Color.White.copy(alpha = 0.14f), CircleShape)
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

// ------------------------------------------------------------------- grid

/** Mode grid ala prototipe HTML: 2 kolom di ponsel, 3 di layar lebar, 4 di tablet besar. */
@Composable
private fun GameGrid(
    games: List<GameRow>,
    sessionPkg: String,
    onPlay: (GameRow) -> Unit,
    onTune: (GameRow) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        val columns = when {
            maxWidth >= 900.dp -> 4
            maxWidth >= 600.dp -> 3
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            games.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { game ->
                        CoverCard(
                            game = game,
                            live = game.pkg == sessionPkg,
                            active = false,
                            modifier = Modifier
                                .weight(1f)
                                .height(GRID_TILE_HEIGHT),
                            onClick = { onTune(game) },
                            bottomAction = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        if (game.autoBbr) "BBR On" else game.shortPreset(),
                                        color = Color.White.copy(alpha = 0.78f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Row(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                            .clickable { onPlay(game) }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = "Play",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            "Play",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        )
                    }
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------ peluncuran

/**
 * Splash saat Play ditekan: kartu membesar lalu memudar (1,45 dtk), progress bar
 * berdenyut. Sesi dimulai dan game dibuka setelah animasi selesai.
 */
@Composable
private fun LaunchSplash(game: GameRow, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val bar = remember { Animatable(0.10f) }

    LaunchedEffect(game.pkg) {
        launch {
            bar.animateTo(
                1f,
                keyframes {
                    durationMillis = 1200
                    0.10f at 0
                    0.82f at 720
                    1f at 1200
                }
            )
        }
        progress.animateTo(1f, tween(durationMillis = 1450, easing = LinearEasing))
        onDone()
    }

    val icon = rememberAppIcon(game.pkg, 256)
    val hue = game.hue()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .graphicsLayer {
                    val t = progress.value
                    alpha = splashAlpha(t)
                    val s = splashScale(t)
                    scaleX = s
                    scaleY = s
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.hsv(hue, 0.62f, 0.36f),
                                Color.hsv((hue + 70f) % 360f, 0.50f, 0.10f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = game.label(), modifier = Modifier.size(96.dp))
                }
            }
            Text(
                game.label(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${game.shortPreset()} • BBR ${if (game.autoBbr) "On" else "Off"} • ${sourceLabel(game.source)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { bar.value },
                modifier = Modifier
                    .width(200.dp)
                    .height(6.dp)
                    .clip(CircleShape)
            )
        }
    }
}

private fun splashAlpha(t: Float): Float = when {
    t < 0.2f -> t / 0.2f
    t < 0.85f -> 1f
    else -> ((1f - t) / 0.15f).coerceAtLeast(0f)
}

private fun splashScale(t: Float): Float = when {
    t < 0.2f -> 0.92f + 0.08f * (t / 0.2f)
    t < 0.85f -> 1f + 0.03f * ((t - 0.2f) / 0.65f)
    else -> 1.03f + 0.07f * ((t - 0.85f) / 0.15f)
}

// ---------------------------------------------------------------- empty

@Composable
private fun EmptyLibrary(onScan: () -> Unit, onAdd: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Belum ada game terdeteksi",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Game terpasang dideteksi otomatis, atau tambahkan aplikasi secara manual.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape
                ) { Text("Scan ulang") }
                Button(
                    onClick = onAdd,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape
                ) { Text("Add game") }
            }
        }
    }
}

// ------------------------------------------------------------- add game

private data class InstalledAppItem(val pkg: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGameSheet(
    existingPkgs: Set<String>,
    onAdded: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var manualPkg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val raw = runCatching { JSONArray(ipc("list_installed_apps")) }.getOrDefault(JSONArray())
        val pm = context.packageManager
        apps = (0 until raw.length()).mapNotNull { i ->
            val o = raw.optJSONObject(i) ?: return@mapNotNull null
            val pkg = o.optString("pkg")
            if (pkg.isBlank() || pkg in existingPkgs) return@mapNotNull null
            val pmLabel = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
            InstalledAppItem(pkg = pkg, label = pmLabel?.ifBlank { null } ?: o.optString("label", pkg))
        }.sortedBy { it.label.lowercase() }
        loadingApps = false
    }

    fun submit(pkg: String) {
        val clean = pkg.trim()
        if (clean.isEmpty()) return
        scope.launch {
            busy = true
            errorMsg = null
            val res = ipc("add_game $clean")
            val ok = runCatching { JSONObject(res).optString("status") == "ok" }.getOrDefault(false)
            busy = false
            if (ok) onAdded() else errorMsg = "Gagal menambahkan $clean"
        }
    }

    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter {
            it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
        }
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add game", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = manualPkg,
                onValueChange = { manualPkg = it },
                label = { Text("Package name atau cari di bawah") },
                placeholder = { Text("com.mobile.legends") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            if (manualPkg.isNotBlank()) {
                Button(
                    onClick = { submit(manualPkg) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape
                ) { Text("Add ${manualPkg.trim()}") }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter aplikasi terpasang") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )

            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            when {
                loadingApps -> Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                filtered.isEmpty() -> Text(
                    "Tidak ada aplikasi yang cocok.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.pkg }) { item ->
                        val icon = rememberAppIcon(item.pkg, 72)
                        Card(
                            onClick = { if (!busy) submit(item.pkg) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (icon != null) {
                                    Image(bitmap = icon, contentDescription = item.label, modifier = Modifier.size(36.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        item.pkg,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    "+ Add",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
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
    var preset by remember { mutableStateOf(if (game.preset == "BATTERY") "ECO" else game.preset) }
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
                val icon = rememberAppIcon(game.pkg, 96)
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
                ) { Text("Play") }
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
    return "${arr.length()} sesi · ${minutes} menit · $fpsText · $jankText"
}

private fun sessionSuggestion(reports: JSONObject?): String? {
    val s = reports ?: return null
    if (s.isNull("suggestion")) return null
    return s.optString("suggestion").ifBlank { null }
}
