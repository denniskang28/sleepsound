package com.miansheng.sleep.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.miansheng.sleep.R

object SleepColors {
    val Background = Color(0xFF101722)
    val Card = Color(0xFF1B2533)
    val Elevated = Color(0xFF243044)
    val Sheet = Color(0xFF1C2738)
    val Nav = Color(0xFF151E2B)
    val Primary = Color(0xFF78C7BB)
    val PrimaryText = Color(0xFFA6DED5)
    val PrimaryInk = Color(0xFF0E2B27)
    val Snore = Color(0xFFD9A85B)
    val SnoreText = Color(0xFFE2B56A)
    val Talk = Color(0xFFA99BE6)
    val TalkText = Color(0xFFB7AAEC)
    val Danger = Color(0xFFE8907F)
    val Text = Color(0xFFF1ECE3)
    val Secondary = Color(0xFFAAB4C2)
    val Muted = Color(0xFF7F8A9B)
    val Border = Color(0xFF2B3749)
    val Night = Color(0xFF05080D)
}

@OptIn(ExperimentalTextApi::class)
private fun family(resource: Int) = FontFamily(listOf(300, 400, 500, 600, 700).map { weight ->
    Font(resource, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})
val Chinese = family(R.font.noto_sans_sc)
val Numbers = family(R.font.archivo)

@Composable
fun SleepTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = SleepColors.Primary, onPrimary = SleepColors.PrimaryInk,
        background = SleepColors.Background, onBackground = SleepColors.Text, surface = SleepColors.Card,
        onSurface = SleepColors.Text, surfaceContainerHigh = SleepColors.Sheet,
        outline = SleepColors.Border, error = SleepColors.Danger), content = content)
}

@Composable
fun Txt(text: String, size: Int = 14, color: Color = SleepColors.Text, weight: FontWeight = FontWeight.Normal,
        modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, numeric: Boolean = false) {
    Text(text, modifier, color = color, fontSize = size.sp, fontWeight = weight,
        fontFamily = if (numeric || text.none { it.code in 0x3400..0x9fff }) Numbers else Chinese,
        lineHeight = (size * if (numeric || text.none { it.code in 0x3400..0x9fff }) 1.2 else if (size >= 20) 1.35 else 1.55).sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Glyph(name: String, color: Color = SleepColors.Secondary, size: Int = 22, description: String? = null) {
    val id = LucideIcons[name] ?: R.drawable.ic_moon
    Icon(painterResource(id), description, Modifier.size(size.dp), tint = color)
}

@Composable
fun IconTap(name: String, description: String, color: Color = SleepColors.Text, bg: Color = Color.Transparent,
            size: Int = 48, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(bg).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center) { Glyph(name, color, description = description) }
}

@Composable
fun ActionButton(label: String, icon: String = "arrow-right", modifier: Modifier = Modifier,
                 primary: Boolean = true, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val bg = if (primary) if (danger) SleepColors.Danger else SleepColors.Primary else Color.Transparent
    val fg = if (primary) if (danger) Color(0xFF2B0F0A) else SleepColors.PrimaryInk else SleepColors.Text
    Row(modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp))
        .background(if (enabled) bg else bg.copy(alpha = .35f))
        .then(if (!primary) Modifier.border(1.dp, SleepColors.Border, RoundedCornerShape(28.dp)) else Modifier)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Txt(label, 16, fg.copy(alpha = if (enabled) 1f else .5f), FontWeight.SemiBold)
        Glyph(icon, fg.copy(alpha = if (enabled) 1f else .5f), 20)
    }
}

@Composable
fun SleepCard(modifier: Modifier = Modifier, color: Color = SleepColors.Card, padding: Int = 20,
              onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(color)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(padding.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun Tag(label: String, color: Color = SleepColors.PrimaryText, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Txt(label, 12, color, maxLines = 1)
    }
}

@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) SleepColors.Primary.copy(alpha = .14f) else Color.Transparent)
        .border(1.dp, if (selected) SleepColors.Primary else SleepColors.Border, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selected) Glyph("check", SleepColors.PrimaryText, 16)
        Txt(label, 14, if (selected) SleepColors.PrimaryText else SleepColors.Text)
    }
}

@Composable
fun PageTitle(title: String, back: (() -> Unit)? = null, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (back == null) 20.dp else 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconTap("arrow-left", "返回", onClick = back)
        Column(Modifier.weight(1f)) {
            Txt(title, if (back == null) 24 else 20, weight = if (back == null) FontWeight.SemiBold else FontWeight.Medium)
            subtitle?.let { Txt(it, 12, SleepColors.Secondary) }
        }
        trailing?.invoke()
    }
}

@Composable
fun Hint(text: String, icon: String = "info", color: Color = SleepColors.Secondary) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Glyph(icon, color, 18); Txt(text, 12, color, modifier = Modifier.weight(1f))
    }
}

@Composable
fun EmptyState(title: String, body: String, icon: String = "moon", modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 38.dp, horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(SleepColors.Card), contentAlignment = Alignment.Center) {
            Glyph(icon, SleepColors.Muted, 28)
        }
        Txt(title, 18, weight = FontWeight.Medium)
        Txt(body, 14, SleepColors.Secondary)
    }
}
