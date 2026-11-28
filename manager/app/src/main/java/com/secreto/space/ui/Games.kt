package com.secreto.space.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

private val PRESETS = listOf("ECO", "BALANCED", "PERFORMANCE", "ESPORTS")
private val SCALES = listOf("native", "900p", "720p")
private val CARD_HEIGHT = 262.dp
private val GRID_TILE_HEIGHT = 196.dp
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
    var detailPkg by remember { mutableStateOf<String?>(null) }
    var launching by remember { mutableStateOf<GameRow?>(null) }
    var gridMode by remember { mutableStateOf(prefs.getString("games_view", "carousel") == "grid") }

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

    // Badge "AKTIF" dan header ikut daemon selama layar terbuka.
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
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            GamesHeader(
                count = games.size,
                liveName = games.find { it.pkg == sessionPkg }?.label(),
                onScan = { reload(scan = true) }
            )

            if (!loading && games.isNotEmpty()) {
                ModeSwitch(
                    gridMode = gridMode,
                    onChange = {
                        gridMode = it
                        prefs.edit().putString("games_view", if (it) "grid" else "carousel").apply()
                    }
                )
            }

            AnimatedContent(
                targetState = bodyState,
                transitionSpec = {
                    (fadeIn(tween(260)) + scaleIn(initialScale = 0.97f, animationSpec = tween(320)))
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "games-body"
            ) { state ->
                when (state) {
                    0 -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CARD_HEIGHT),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    1 -> EmptyLibrary(onScan = { reload(scan = true) })

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
                        onTune = { detailPkg = it.pkg }
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

/** Warna glow ambient untuk game ini. */
private fun GameRow.glow(): Color = Color.hsv(hue(), 0.7f, 0.95f)

private fun sourceLabel(source: String): String = when (source) {
    "manual" -> "MANUAL"
    "config" -> "CONFIG"
    "auto" -> "AUTO"
    else -> "BUILTIN"
}

@Composable
private fun rememberAppIcon(pkg: String, px: Int): ImageBitmap? {
    val context = LocalContext.current
    return remember(pkg, px) {
        runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
        }.getOrNull()
    }
}

// ----------------------------------------------------------------- header

@Composable
private fun GamesHeader(count: Int, liveName: String?, onScan: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                "Game Space",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                if (liveName != null) "Sesi aktif · $liveName" else "$count game terpasang",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onScan, shape = CircleShape) { Text("Scan") }
    }
}

@Composable
private fun ModeSwitch(gridMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeOption("Carousel", selected = !gridMode) { onChange(false) }
        ModeOption("Grid", selected = gridMode) { onChange(true) }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(260, easing = EASE_EMPHASIZED),
        label = "modeBg"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(260, easing = EASE_EMPHASIZED),
        label = "modeFg"
    )
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// --------------------------------------------------------------- carousel

@Composable
private fun CoverSection(
    games: List<GameRow>,
    sessionPkg: String,
    onPlay: (GameRow) -> Unit,
    onTune: (GameRow) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { games.size })
    val current = pager.currentPage.coerceIn(0, games.lastIndex)
    val game = games.getOrNull(current)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CoverFlow(games = games, sessionPkg = sessionPkg, pager = pager, onTune = onTune)

        PagerDots(count = games.size, index = current)

        AnimatedContent(
            targetState = game,
            modifier = Modifier.padding(horizontal = 16.dp),
            transitionSpec = {
                (fadeIn(tween(240)) + slideInVertically(tween(300, easing = EASE_EMPHASIZED)) { it / 5 })
                    .togetherWith(fadeOut(tween(120)))
            },
            label = "hero"
        ) { target ->
            if (target != null) {
                HeroBlock(
                    game = target,
                    live = sessionPkg == target.pkg,
                    onPlay = { onPlay(target) },
                    onTune = { onTune(target) }
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
    onTune: (GameRow) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current = pager.currentPage.coerceIn(0, games.lastIndex.coerceAtLeast(0))
    val glow by animateColorAsState(
        targetValue = games.getOrNull(current)?.glow() ?: Color.Transparent,
        animationSpec = tween(700, easing = EASE_EMPHASIZED),
        label = "ambient"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT + 16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Ambient glow: lembut dan berganti warna mengikuti game aktif.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(listOf(glow.copy(alpha = 0.40f), Color.Transparent))
                )
                .blur(48.dp)
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = minOf(maxWidth * 0.49f, 200.dp)
            val stepPx = with(LocalDensity.current) { (cardWidth * 0.8f).toPx() }
            HorizontalPager(
                state = pager,
                pageSize = PageSize.Fixed(cardWidth),
                contentPadding = PaddingValues(horizontal = (maxWidth - cardWidth) / 2),
                pageSpacing = 0.dp,
                beyondViewportPageCount = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CARD_HEIGHT)
            ) { page ->
                CoverCard(
                    game = games[page],
                    live = games[page].pkg == sessionPkg,
                    active = page == current,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(20f - abs(page - current))
                        .graphicsLayer {
                            // Posisi kontinu (ikut drag) — tidak perlu recompose tiap frame.
                            val offset = page - (pager.currentPage + pager.currentPageOffsetFraction)
                            val dist = offset.absoluteValue
                            val t = dist.coerceIn(0f, 1f)
                            translationX = offset * stepPx
                            val s = if (dist <= 1f) lerp(1f, 0.86f, t)
                            else max(0.76f, 0.86f - (dist - 1f) * 0.08f)
                            scaleX = s
                            scaleY = s
                            rotationY = -10f * offset.coerceIn(-1f, 1f)
                            cameraDistance = 12f * density
                            alpha = if (dist > 2f) 0f else lerp(1f, 0.68f, t)
                        },
                    onClick = {
                        if (page == current) onTune(games[page])
                        else scope.launch { pager.animateScrollToPage(page) }
                    }
                )
            }
        }
    }
}

@Composable
private fun PagerDots(count: Int, index: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i -> PageDot(active = i == index) }
    }
}

@Composable
private fun PageDot(active: Boolean) {
    val width by animateDpAsState(
        targetValue = if (active) 18.dp else 6.dp,
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
            .background(color, CircleShape)
    )
}

@Composable
private fun HeroBlock(game: GameRow, live: Boolean, onPlay: () -> Unit, onTune: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (live) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.outline,
                        CircleShape
                    )
            )
            Text(
                if (live) "Sesi aktif · ${game.preset}" else "Siap dimainkan · ${game.preset}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            game.pkg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPlay,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 30.dp, vertical = 12.dp)
            ) { Text("Main", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onTune,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) { Text("Atur") }
        }
    }
}

/**
 * Kartu cover: gradasi per game, ikon aplikasi di tengah, dan scrim gelap di bawah
 * untuk judul. Tekan sedikit mengecil agar terasa responsif.
 */
@Composable
private fun CoverCard(
    game: GameRow,
    live: Boolean,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val hue = game.hue()
    val icon = rememberAppIcon(game.pkg, 224)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "press"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX *= pressScale
                scaleY *= pressScale
            }
            .shadow(if (active) 22.dp else 6.dp, CARD_SHAPE, clip = false)
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
            .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .padding(14.dp)
    ) {
        // Halo lembut di belakang ikon.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(170.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color.hsv(hue, 0.7f, 1f).copy(alpha = 0.32f), Color.Transparent)
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
                ChipLabel(sourceLabel(game.source), Color.White.copy(alpha = 0.16f))
                if (live) StatusPill("AKTIF") else ChipLabel(game.preset, Color.White.copy(alpha = 0.16f))
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                        )
                    )
                    .padding(top = 18.dp)
            ) {
                Text(
                    game.label(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
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

// ------------------------------------------------------------------- grid

/** Mode grid: tile cover 2 kolom di ponsel, 3 kolom di layar >= 600 dp. */
@Composable
private fun GameGrid(
    games: List<GameRow>,
    sessionPkg: String,
    onPlay: (GameRow) -> Unit,
    onTune: (GameRow) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val columns = if (maxWidth >= 600.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            games.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { game ->
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CoverCard(
                                game = game,
                                live = game.pkg == sessionPkg,
                                active = false,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(GRID_TILE_HEIGHT),
                                onClick = { onPlay(game) }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onTune(game) },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Atur", style = MaterialTheme.typography.labelLarge) }
                                Button(
                                    onClick = { onPlay(game) },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Main", style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------ peluncuran

/**
 * Splash saat Main ditekan: kartu membesar lalu memudar (1,45 dtk), progress bar
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
                "${game.preset} · ${sourceLabel(game.source)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { bar.value },
                modifier = Modifier
                    .width(200.dp)
                    .height(6.dp)
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
