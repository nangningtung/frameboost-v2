package com.secreto.space.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Tema "cozy modern — Pixel OS style":
 * - Android 12+: Material You — warna diambil dari wallpaper (dynamic color),
 *   terang/gelap mengikuti sistem. Di A50 GSI Android 16 ini jalan penuh.
 * - Versi lama / fallback: palet cozy netral-hangat.
 * - Bentuk: sudut besar, nuansa lembut khas Pixel.
 */
private val CozyLight = lightColorScheme(
    primary = Color(0xFF5D5CDE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3E1FA),
    onPrimaryContainer = Color(0xFF17164F),
    secondary = Color(0xFF8A6F4D),
    background = Color(0xFFFAF9FE),
    surface = Color(0xFFFAF9FE),
    surfaceContainerLow = Color(0xFFF2F0FA),
    surfaceVariant = Color(0xFFE7E4F1),
    onBackground = Color(0xFF1B1B21),
)

private val CozyDark = darkColorScheme(
    primary = Color(0xFFC3C3FF),
    onPrimary = Color(0xFF1B1B6D),
    primaryContainer = Color(0xFF343486),
    onPrimaryContainer = Color(0xFFE1E0FF),
    background = Color(0xFF131318),
    surface = Color(0xFF131318),
    surfaceContainerLow = Color(0xFF1D1D24),
    surfaceVariant = Color(0xFF47464F),
    onBackground = Color(0xFFE4E1E9),
)

val PixelShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun SecretoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        darkTheme -> CozyDark
        else -> CozyLight
    }
    MaterialTheme(colorScheme = colorScheme, shapes = PixelShapes, content = content)
}
