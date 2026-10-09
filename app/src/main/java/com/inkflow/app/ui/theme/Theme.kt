package com.inkflow.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 沉浸式写作主题。
 *
 * 配色取自「墨与灯」：深靛蓝底 + 暖纸色文字 + 青蓝强调色。
 * 长时间写作最重要的是**低对比度疲劳**与**正文行高**，
 * 因此排版刻意把正文行高设到 1.9 倍、字距略放开。
 */
private val InkDark = darkColorScheme(
    primary = Color(0xFF7FD1E8),
    onPrimary = Color(0xFF00202B),
    primaryContainer = Color(0xFF00495C),
    onPrimaryContainer = Color(0xFFB6EBFF),
    secondary = Color(0xFFD8C3A5),
    onSecondary = Color(0xFF3A2E1E),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE4E6EB),
    surface = Color(0xFF141A22),
    onSurface = Color(0xFFE4E6EB),
    surfaceVariant = Color(0xFF1D2530),
    onSurfaceVariant = Color(0xFFB0B8C4),
    outline = Color(0xFF3A4450),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3A0906),
    tertiary = Color(0xFFC6A9F0),
)

private val InkLight = lightColorScheme(
    primary = Color(0xFF00677F),
    onPrimary = Color.White,
    secondary = Color(0xFF6B5A3E),
    background = Color(0xFFFBF8F3),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFFFBF6),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFECE4D9),
    onSurfaceVariant = Color(0xFF4A4741),
)

/** 正文排版：中文小说阅读的舒适区。 */
val BodySerif = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = 17.sp,
    lineHeight = 32.sp,
    letterSpacing = 0.4.sp,
)

private val InkTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)

@Composable
fun InkFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) InkDark else InkLight

    val view = LocalContext.current
    SideEffect {
        (view as? Activity)?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = InkTypography,
        content = content,
    )
}

/** 正文块的行高样式：避免中文字形上下被裁切。 */
val BodyLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val EditorPadding = 20.dp
