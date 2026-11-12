package com.iameberhard.icharge

import androidx.compose.ui.graphics.Color

/**
 * The app's colours. Dark by default; Settings › Appearance can follow the system or force light.
 * They are plain vars read at composition: the whole UI is re-created (key) when the mode changes.
 */
object Palette {
    var dark = true
        private set

    fun apply(isDark: Boolean) {
        dark = isDark
        if (isDark) {
            Bg = Color(0xFF0B0F14); CardColor = Color(0xFF161C24); Card2 = Color(0xFF1F2733)
            Fg = Color.White; Fg2 = Color(0xFFD5DBE2); Muted = Color(0xFF9AA5B1)
        } else {
            Bg = Color(0xFFF3F5F7); CardColor = Color(0xFFFFFFFF); Card2 = Color(0xFFE9EDF1)
            Fg = Color(0xFF111418); Fg2 = Color(0xFF2B333B); Muted = Color(0xFF5B6673)
        }
    }
}

var Bg = Color(0xFF0B0F14)
var CardColor = Color(0xFF161C24)
var Card2 = Color(0xFF1F2733)
var Fg: Color = Color.White
var Fg2 = Color(0xFFD5DBE2)
var Muted = Color(0xFF9AA5B1)
val Accent = Color(0xFF3DDC84)
val FastColor = Color(0xFFFFC43D)
val AcColor = Color(0xFF4DA3FF)
