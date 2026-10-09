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

/**
 * 默认正文排版（中文小说阅读的舒适区）。
 * 实际渲染时会由 [com.inkflow.app.data.ReaderPrefs] 覆写字号 / 行高 / 字体。
 */
val BodySerif = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = 17.sp,
    lineHeight = 32.sp,
    letterSpacing = 0.4.sp,
)

/**
 * 依据用户偏好构造正文 TextStyle。
 *
 * 行高按「字号 × 倍数」计算而非固定值：用户放大字号时，
 * 行距若保持固定会显得越来越挤，中文尤其明显。
 */
fun bodyTextStyle(prefs: com.inkflow.app.data.ReaderPrefs): TextStyle = TextStyle(
    fontFamily = when (prefs.fontChoice) {
        com.inkflow.app.data.ReaderFont.Serif -> FontFamily.Serif
        com.inkflow.app.data.ReaderFont.Sans -> FontFamily.SansSerif
        com.inkflow.app.data.ReaderFont.Monospace -> FontFamily.Monospace
    },
    fontSize = prefs.fontSizeSp.sp,
    lineHeight = prefs.lineHeightSp.sp,
    letterSpacing = 0.4.sp,
    textIndent = if (prefs.indentFirstLine) androidx.compose.ui.text.style.TextIndent(
        firstLine = (prefs.fontSizeSp * 2).sp,
    ) else androidx.compose.ui.text.style.TextIndent.None,
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

/**
 * 阅读区配色。返回 (背景色, 正文色, 次要文字色)。
 *
 * 不直接改全局 MaterialTheme，是因为阅读区经常需要与 App 主题不同 ——
 * 例如暗色 App 里仍想用暖黄底读稿。
 */
fun readerColors(
    scheme: com.inkflow.app.data.ReaderScheme,
    isDark: Boolean,
): Triple<Color, Color, Color> = when (scheme) {
    com.inkflow.app.data.ReaderScheme.FollowSystem ->
        if (isDark) Triple(Color(0xFF0D1117), Color(0xFFD8DCE3), Color(0xFF8B93A1))
        else Triple(Color(0xFFFBF8F3), Color(0xFF23262B), Color(0xFF6B7280))

    com.inkflow.app.data.ReaderScheme.Paper ->
        Triple(Color(0xFFFFFFFF), Color(0xFF1F2226), Color(0xFF707784))

    com.inkflow.app.data.ReaderScheme.Warm ->
        Triple(Color(0xFFF7EFDD), Color(0xFF3A3226), Color(0xFF7A6E59))

    com.inkflow.app.data.ReaderScheme.Sepia ->
        Triple(Color(0xFFEDE0C8), Color(0xFF453B2C), Color(0xFF83765E))

    com.inkflow.app.data.ReaderScheme.Night ->
        Triple(Color(0xFF121417), Color(0xFFB9BFC9), Color(0xFF6E7681))
}

/** 正文块的行高样式：避免中文字形上下被裁切。 */
val BodyLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val EditorPadding = 20.dp
