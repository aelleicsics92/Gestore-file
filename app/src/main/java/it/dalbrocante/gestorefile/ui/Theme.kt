package it.dalbrocante.gestorefile.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun GestoreTheme(amoled: Boolean = true, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark ->
            dynamicDarkColorScheme(ctx).let { if (amoled) it.copy(background = Color.Black, surface = Color.Black) else it }
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(background = if (amoled) Color.Black else Color(0xFF121212), surface = if (amoled) Color.Black else Color(0xFF121212))
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
