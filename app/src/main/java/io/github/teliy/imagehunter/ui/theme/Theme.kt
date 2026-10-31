package io.github.teliy.imagehunter.ui.theme

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

/** Фирменный оранжевый чекбоксов Image Hunter (тот же, что в JS-инъекции). */
val HunterOrange = Color(0xFFFF7A1A)

@Composable
fun ImageHunterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> darkColorScheme(primary = HunterOrange, secondary = HunterOrange.copy(alpha = 0.7f))
        else -> lightColorScheme(primary = HunterOrange, secondary = HunterOrange.copy(alpha = 0.7f))
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
