package it.dalbrocante.gestorefile.ui

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Impostazioni di aspetto persistenti. */
object AppPrefs {
    var dynamic by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        dynamic = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("dynamic", false)
    }

    fun setDynamic(ctx: Context, v: Boolean) {
        dynamic = v
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("dynamic", v).apply()
    }
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B4BDB), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E0FF), onPrimaryContainer = Color(0xFF1B1259),
    secondary = Color(0xFF0E9F9A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F4F0), onSecondaryContainer = Color(0xFF00201E),
    tertiary = Color(0xFFFF7A59), onTertiary = Color.White,
    background = Color(0xFFF6F5FB), onBackground = Color(0xFF1A1823),
    surface = Color(0xFFF6F5FB), onSurface = Color(0xFF1A1823),
    surfaceVariant = Color(0xFFE6E3F1), onSurfaceVariant = Color(0xFF5B5870),
    surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFF0EEF8),
    surfaceContainerHigh = Color(0xFFEAE7F4), surfaceContainerHighest = Color(0xFFE4E1F0),
    outlineVariant = Color(0xFFD9D5E8),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB8AEFF), onPrimary = Color(0xFF2A1F8F),
    primaryContainer = Color(0xFF3D31B0), onPrimaryContainer = Color(0xFFE4E0FF),
    secondary = Color(0xFF58D9D1), onSecondary = Color(0xFF003734),
    secondaryContainer = Color(0xFF0B4F4B), onSecondaryContainer = Color(0xFFD3F4F0),
    tertiary = Color(0xFFFFB4A1), onTertiary = Color(0xFF5E1500),
    background = Color.Black, onBackground = Color(0xFFE8E5F3),
    surface = Color.Black, onSurface = Color(0xFFE8E5F3),
    surfaceVariant = Color(0xFF26242F), onSurfaceVariant = Color(0xFFC6C2D6),
    surfaceContainerLow = Color(0xFF14131A), surfaceContainer = Color(0xFF1A1921),
    surfaceContainerHigh = Color(0xFF211F29), surfaceContainerHighest = Color(0xFF292731),
    outlineVariant = Color(0xFF3A3846),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun GestoreTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        AppPrefs.dynamic && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx).copy(background = Color.Black, surface = Color.Black) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}
