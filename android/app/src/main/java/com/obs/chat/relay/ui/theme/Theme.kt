package com.obs.chat.relay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0F0F11)
val CardDark = Color(0xFF18181B)
val BorderDark = Color(0xFF27272A)
val AccentRed = Color(0xFFEF4444)
val KickGreen = Color(0xFF53FC18)
val AccentBlue = Color(0xFF38BDF8)
val AccentPurple = Color(0xFF9146FF)
val AccentOrange = Color(0xFFFB923C)
val TextMain = Color(0xFFE4E4E7)
val TextMuted = Color(0xFFA1A1AA)

private val DarkColorScheme = darkColorScheme(
    primary = AccentRed,
    secondary = KickGreen,
    tertiary = AccentBlue,
    background = BgDark,
    surface = CardDark,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = TextMain,
    onSurface = TextMain
)

@Composable
fun OBSChatRelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
