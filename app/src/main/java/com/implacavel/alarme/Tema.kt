package com.implacavel.alarme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Claro = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1512),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857371),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF6E4E2),
    surfaceContainerHighest = Color(0xFFF1DEDC),
)

private val Escuro = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE7BDB7),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DEDC),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DEDC),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BF),
    outline = Color(0xFFA08C8A),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322827),
    surfaceContainerHighest = Color(0xFF3D3231),
)

/** Tema do app: tons de vermelho de alarme, claro ou escuro conforme o sistema. */
@Composable
fun TemaImplacavel(escuro: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (escuro) Escuro else Claro, content = content)
}
