package com.inkflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkflow.app.data.ReaderFont
import com.inkflow.app.data.ReaderPrefs
import com.inkflow.app.data.ReaderScheme
import com.inkflow.app.ui.theme.bodyTextStyle
import com.inkflow.app.ui.theme.readerColors
import kotlinx.coroutines.launch

/**
 * 阅读 / 写作排版设置。
 *
 * 设计取舍：写作和阅读对排版的要求是矛盾的 ——
 * 码字时要一屏信息量（小字号、紧行距），通读检查时要眼睛舒服（大字号、松行距）。
 * 因此这里把字号、行距、字体、配色、留白、段距全部开放，
 * 并且**实时预览**：改一下就能在上方正文里看到效果，不用来回切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    prefs: ReaderPrefs,
    onChange: (ReaderPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // ---------------- 标题 ----------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "阅读设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    onChange(ReaderPrefs())
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("恢复默认")
                }
                IconButton(onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            Spacer(Modifier.height(6.dp))

            // ---------------- 实时预览 ----------------
            PreviewCard(prefs)

            Spacer(Modifier.height(20.dp))

            // ---------------- 字号 ----------------
            SettingSlider(
                label = "字号",
                valueText = "${prefs.fontSizeSp.toInt()} sp",
                value = prefs.fontSizeSp,
                range = 12f..30f,
                steps = 17,
                onValueChange = { onChange(prefs.copy(fontSizeSp = it)) },
            )

            // ---------------- 行距 ----------------
            SettingSlider(
                label = "行距",
                valueText = String.format("%.2f 倍（%.1f sp）", prefs.lineHeightMultiplier, prefs.lineHeightSp),
                value = prefs.lineHeightMultiplier,
                range = 1.2f..2.6f,
                steps = 13,
                onValueChange = { onChange(prefs.copy(lineHeightMultiplier = it)) },
            )

            // ---------------- 段间距 ----------------
            SettingSlider(
                label = "段间距",
                valueText = "${prefs.paragraphSpacingDp.toInt()} dp",
                value = prefs.paragraphSpacingDp,
                range = 0f..30f,
                steps = 14,
                onValueChange = { onChange(prefs.copy(paragraphSpacingDp = it)) },
            )

            // ---------------- 左右留白 ----------------
            SettingSlider(
                label = "左右留白",
                valueText = "${prefs.horizontalPaddingDp.toInt()} dp",
                value = prefs.horizontalPaddingDp,
                range = 8f..48f,
                steps = 19,
                onValueChange = { onChange(prefs.copy(horizontalPaddingDp = it)) },
            )

            Spacer(Modifier.height(12.dp))

            // ---------------- 字体 ----------------
            SectionTitle("字体")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { f ->
                    FilterChip(
                        selected = prefs.fontChoice == f,
                        onClick = { onChange(prefs.copy(fontChoice = f)) },
                        label = { Text(f.label, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---------------- 配色 ----------------
            SectionTitle("页面配色")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReaderScheme.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { scheme ->
                            SchemeChip(
                                scheme = scheme,
                                selected = prefs.colorScheme == scheme,
                                onClick = { onChange(prefs.copy(colorScheme = scheme)) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---------------- 开关项 ----------------
            ToggleRow(
                label = "段首缩进两字符",
                hint = "中文排版习惯，网文平台通常不缩进",
                checked = prefs.indentFirstLine,
                onChange = { onChange(prefs.copy(indentFirstLine = it)) },
            )
            ToggleRow(
                label = "写作时屏幕常亮",
                hint = "长时间码字时避免自动锁屏",
                checked = prefs.keepScreenOn,
                onChange = { onChange(prefs.copy(keepScreenOn = it)) },
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "设置会立即生效并自动保存，下次打开依然保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 实时预览：用真实排版参数渲染一段示例文字。 */
@Composable
private fun PreviewCard(prefs: ReaderPrefs) {
    val isDark = isSystemInDarkTheme()
    val (bg, fg, dim) = readerColors(prefs.colorScheme, isDark)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            Modifier.padding(
                horizontal = prefs.horizontalPaddingDp.dp.coerceAtMost(28.dp),
                vertical = 14.dp,
            ),
        ) {
            Text(
                "他推开门的时候，就知道事情不对。",
                style = bodyTextStyle(prefs).copy(color = fg),
                modifier = Modifier.padding(vertical = (prefs.paragraphSpacingDp / 2).dp),
            )
            Text(
                "屋里的灯还亮着，茶却已经凉透。桌上的信纸被人翻动过，边角折了一道浅浅的痕——那是他今早亲手压平的。",
                style = bodyTextStyle(prefs).copy(color = fg),
                modifier = Modifier.padding(vertical = (prefs.paragraphSpacingDp / 2).dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "预览效果 · 实际正文将使用同一套排版",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = dim,
            )
        }
    }
}

/** 配色选项：用色块直观展示每种方案的实际观感。 */
@Composable
private fun SchemeChip(
    scheme: ReaderScheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val (bg, fg, _) = readerColors(scheme, isDark)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else Color.Transparent
            )
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .size(width = 54.dp, height = 40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            // 用「文」字示意该配色下的正文观感
            Text("文", color = fg, fontSize = 16.sp)
            if (selected) {
                Box(
                    Modifier
                        .size(width = 54.dp, height = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            scheme.label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
