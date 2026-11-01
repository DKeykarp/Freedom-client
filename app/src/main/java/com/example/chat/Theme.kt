package com.example.chat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Цвета синхронизированы с веб-версией (server/web/style.css: --bg, --panel, --panel2, --accent, --mine, --danger).
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B8CFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9EAFF),   // фон «своего» сообщения
    onPrimaryContainer = Color(0xFF0B3B78),
    secondaryContainer = Color(0xFFD9EAFF),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF101820),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101820),
    surfaceVariant = Color(0xFFEEF2F6),     // фон «чужого» сообщения
    onSurfaceVariant = Color(0xFF5B6B7C),
    error = Color(0xFFC62828),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2B8CFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2B5278),   // фон «своего» сообщения
    onPrimaryContainer = Color(0xFFE8EEF4),
    secondaryContainer = Color(0xFF2B5278),
    background = Color(0xFF0E1621),
    onBackground = Color(0xFFE8EEF4),
    surface = Color(0xFF17212B),
    onSurface = Color(0xFFE8EEF4),
    surfaceVariant = Color(0xFF1E2C3A),     // фон «чужого» сообщения
    onSurfaceVariant = Color(0xFF7F91A4),
    error = Color(0xFFE5484D),
)

/** Оформление приложения: само переключается между светлой и тёмной темой вслед за системой. */
@Composable
fun FreedomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
