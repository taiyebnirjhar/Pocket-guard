package com.taiyeb.pocketguard

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Space left under scrolling content so the floating navigation bar never covers it. */
private val NavClearance = 104.dp

enum class Tab(@DrawableRes val icon: Int, @StringRes val label: Int) {
    HOME(R.drawable.ic_home, R.string.nav_home),
    TRIGGERS(R.drawable.ic_bolt, R.string.triggers_title),
    HISTORY(R.drawable.ic_history, R.string.main_history),
    SETTINGS(R.drawable.ic_tune, R.string.settings_title),
}

/** Near-black page with a soft glow of [glow] behind the top half. */
@Composable
fun GlowBackground(glow: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val color by animateColorAsState(glow, tween(700), label = "glow")
    Box(
        modifier
            .fillMaxSize()
            .background(Background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        0f to color.copy(alpha = 0.32f),
                        0.45f to color.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                        center = Offset(size.width / 2f, size.height * 0.2f),
                        radius = size.width * 1.15f,
                    ),
                )
            },
        content = content,
    )
}

/** A tab's page: big title, optional subtitle and action, then scrolling content. */
@Composable
fun TabPage(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = NavClearance),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                if (subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp)
            }
            action?.invoke()
        }
        content()
    }
}

/** Home has no title row, but the same insets and clearance as the other tabs. */
@Composable
fun HomePage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = NavClearance),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardColor)
            .border(1.dp, CardBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** Round tinted badge holding an icon, as in front of every row. */
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Color, size: Dp = 42.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun CircleIconButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(CardColor)
            .border(1.dp, CardBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription, tint = TextPrimary, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun TagPill(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Small tinted pill button, for "Fix", "Allow" and similar row actions. */
@Composable
fun PillAction(text: String, onClick: () -> Unit, color: Color = Lavender) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(58.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PillSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Purple,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Muted,
            uncheckedTrackColor = Track,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Icon, title and subtitle, with [trailing] (a switch, tag or button) on the right. */
@Composable
fun SettingRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    tint: Color = Lavender,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        trailing()
    }
}

/** A whole row that flips a setting, so the label is as easy to hit as the switch. */
@Composable
fun ToggleRow(@DrawableRes icon: Int, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(
        icon,
        title,
        subtitle,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        tint = if (checked) Lavender else Muted,
    ) {
        PillSwitch(checked, onChange = null)
    }
}

@Composable
fun PermissionWarning(text: String, onGrant: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Amber.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = Amber, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Amber, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        PillAction(stringResource(R.string.action_allow), onGrant, color = Amber)
    }
}

/** "Guard status" chip with a pulsing dot in the phase colour. */
@Composable
fun StatusChip(phase: Phase) {
    val color by animateColorAsState(phase.color, tween(500), label = "chip")
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.10f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(if (phase.isAlarm) 350 else 1200), RepeatMode.Reverse),
        label = "pulse",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(CardColor)
            .border(1.dp, CardBorder, CircleShape)
            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = if (phase == Phase.OFF) 0.12f else pulse)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.home_status), color = Muted, fontSize = 11.sp)
            Text(stringResource(phase.shortRes()), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Floating pill of round tab buttons, the selected one filled purple. */
@Composable
fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color(0xF21C1C1F))
            .border(1.dp, CardBorder, CircleShape)
            .padding(8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            val background by animateColorAsState(if (isSelected) Purple else Color.Transparent, label = "tab")
            val tint by animateColorAsState(if (isSelected) Color.White else Muted, label = "tabIcon")
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(background)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(tab.icon),
                    contentDescription = stringResource(tab.label),
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

private val Phase.ringSpeed
    get() = when (this) {
        Phase.OFF -> 0.2f
        Phase.WAITING_FOR_POCKET -> 0.55f
        Phase.IN_POCKET -> 0.4f
        Phase.WARNING -> 1.8f
        Phase.SIREN -> 3f
    }

private val Phase.ringWobble
    get() = when (this) {
        Phase.OFF -> 0.35f
        Phase.WAITING_FOR_POCKET -> 0.8f
        Phase.IN_POCKET -> 1f
        Phase.WARNING -> 1.6f
        Phase.SIREN -> 2.2f
    }

private const val RINGS = 6
private const val DOTS_PER_RING = 80

/**
 * Rings of dots on a slowly morphing, wavy outline, like particles orbiting the power button.
 * Calm and grey when off, purple when protected, fast and hot during an alarm.
 */
@Composable
fun ParticleRing(phase: Phase, modifier: Modifier = Modifier) {
    val color by animateColorAsState(phase.color, tween(700), label = "ring")
    val accent by animateColorAsState(if (phase.isAlarm) phase.color else Lavender, tween(700), label = "ringAccent")
    val speed by animateFloatAsState(phase.ringSpeed, tween(900), label = "ringSpeed")
    val wobble by animateFloatAsState(phase.ringWobble, tween(900), label = "ringWobble")
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f * speed
                last = now
            }
        }
    }
    val even = remember { ArrayList<Offset>(DOTS_PER_RING / 2) }
    val odd = remember { ArrayList<Offset>(DOTS_PER_RING / 2) }
    Canvas(modifier) {
        if (size.minDimension <= 0f) return@Canvas
        val maxRadius = size.minDimension / 2f
        drawCircle(
            Brush.radialGradient(
                listOf(color.copy(alpha = 0.30f), Color.Transparent),
                center = center,
                radius = maxRadius * 0.62f,
            ),
            radius = maxRadius * 0.62f,
        )
        val dot = 2.4.dp.toPx()
        for (ring in 0 until RINGS) {
            val base = maxRadius * (0.5f + ring * 0.082f)
            val direction = if (ring % 2 == 0) 1f else -1f
            val spin = time * (0.22f + ring * 0.04f) * direction + ring * 0.4f
            even.clear()
            odd.clear()
            for (i in 0 until DOTS_PER_RING) {
                val angle = i * (2f * PI.toFloat() / DOTS_PER_RING) + spin
                val wave = sin(angle * 3f + time * 1.2f + ring * 0.9f) * 0.6f +
                    sin(angle * 5f - time * 0.8f + ring * 1.7f) * 0.4f
                val radius = base + wave * wobble * maxRadius * 0.045f * (1f + ring * 0.35f)
                val point = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
                if (i % 2 == 0) even += point else odd += point
            }
            val ringColor = lerp(color, accent, ring / (RINGS - 1f))
            val fade = 1f - ring * 0.13f
            val twinkle = 0.5f + 0.5f * sin(time * 2.2f + ring)
            val width = dot * (1f - ring * 0.09f)
            drawPoints(even, PointMode.Points, ringColor.copy(alpha = fade * (0.45f + 0.55f * twinkle)), width, StrokeCap.Round)
            drawPoints(odd, PointMode.Points, ringColor.copy(alpha = fade * (1f - 0.55f * twinkle)), width, StrokeCap.Round)
        }
    }
}

/** Two-line figure card, as on the home screen. */
@Composable
fun StatCard(@DrawableRes icon: Int, label: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        IconBadge(icon, Lavender, size = 38.dp)
        Column {
            Text(label, color = Muted, fontSize = 12.sp)
            Text(
                value,
                color = TextPrimary,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Centred empty-state card with a large badge. */
@Composable
fun EmptyCard(@DrawableRes icon: Int, text: String) {
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(icon, Lavender, size = 72.dp)
            Spacer(Modifier.height(14.dp))
            Text(text, color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}
