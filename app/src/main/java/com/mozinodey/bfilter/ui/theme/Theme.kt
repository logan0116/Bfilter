package com.mozinodey.bfilter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** B 站粉，用作主色 */
val BiliPink = Color(0xFFFB7299)

/** B 站蓝，用作次级强调 */
val BiliBlue = Color(0xFF00AEEC)

/**
 * 只提供深色主题 —— 这是产品决策不是省事：
 * 这个 app 的目的是让人**少看一点**，亮白界面和大面积高饱和封面都更容易把人吸住。
 */
private val BfilterColors = darkColorScheme(
    primary = BiliPink,
    onPrimary = Color.White,
    secondary = BiliBlue,
    onSecondary = Color.White,
    background = Color(0xFF0F0F12),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF17171C),
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF22222A),
    onSurfaceVariant = Color(0xFFA8A8B3),
    outline = Color(0xFF3A3A44),
    error = Color(0xFFFF6B6B)
)

@Composable
fun BfilterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BfilterColors, content = content)
}
