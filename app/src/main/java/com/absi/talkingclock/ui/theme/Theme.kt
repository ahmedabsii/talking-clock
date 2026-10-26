package com.absi.talkingclock.ui.theme

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

// The launcher icon's colours, used where the system has no wallpaper palette (Android 10–11).
private val Navy = Color(0xFF14213D)
private val Amber = Color(0xFFFCA311)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2F5),
    onPrimaryContainer = Navy,
    tertiary = Color(0xFF8A5A00),
)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Navy,
    primaryContainer = Color(0xFF2B3A5C),
    onPrimaryContainer = Color(0xFFFFDDB0),
    tertiary = Amber,
)

@Composable
fun TalkingClockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
