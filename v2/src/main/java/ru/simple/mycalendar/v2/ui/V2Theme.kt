package ru.simple.mycalendar.v2.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4255D4),
    secondary = Color(0xFF59608A),
    tertiary = Color(0xFFE56B20),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF1EFF7)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    secondary = Color(0xFFC2C5E9),
    tertiary = Color(0xFFFFB68A),
    surface = Color(0xFF131318),
    surfaceVariant = Color(0xFF292832)
)

@Composable
fun MyCalendarV2Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
