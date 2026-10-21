@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thupapuha.macrotracker.ui.theme.Brand
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Emojis offered to react to a post. */
val REACTIONS = listOf("👍", "🔥", "💪", "👏", "😂")

private val avatarPalette = listOf(
    Color(0xFF10B981) to Color(0xFF3B82F6),
    Color(0xFFF472B6) to Color(0xFF8B5CF6),
    Color(0xFFF59E0B) to Color(0xFFEF4444),
    Color(0xFF06B6D4) to Color(0xFF6366F1),
    Color(0xFF84CC16) to Color(0xFF10B981),
    Color(0xFFEC4899) to Color(0xFFF59E0B),
    Color(0xFF8B5CF6) to Color(0xFF06B6D4),
    Color(0xFFEF4444) to Color(0xFFF472B6),
)

/** Up to two initials; works with names that start with an emoji. */
fun initials(name: String): String =
    name.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { String(Character.toChars(it.codePointAt(0))).uppercase() }
        .ifEmpty { "?" }

/** A colorful circle with the person's initials; the colors depend on [seed] (their user id). */
@Composable
fun Avatar(name: String, seed: String, modifier: Modifier = Modifier, diameter: Dp = 40.dp) {
    val (from, to) = avatarPalette[(seed.hashCode() and Int.MAX_VALUE) % avatarPalette.size]
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(from, to))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (diameter.value * 0.36f).sp,
            maxLines = 1,
        )
    }
}

/**
 * Avatar with a ring like a story: it fills with today's [progress] (0..1) and turns into a
 * rainbow when [highlight] (goal reached).
 */
@Composable
fun StoryAvatar(
    name: String,
    seed: String,
    progress: Float?,
    highlight: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 64.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            if (highlight) {
                drawArc(Brand.storyRing, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            } else {
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                if (progress != null && progress > 0f) {
                    drawArc(
                        fill, -90f, 360f * progress.coerceAtMost(1f), false, topLeft, arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Avatar(name, seed, diameter = diameter - 12.dp)
    }
}

private val spanish: Locale = Locale.forLanguageTag("es")
private val shortDate = DateTimeFormatter.ofPattern("d MMM", spanish)

/** "ahora", "hace 5 min", "hace 3 h", "ayer", "hace 4 d" or a date. */
fun timeAgo(iso: String, now: Instant = Instant.now()): String {
    val then = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return ""
    val minutes = Duration.between(then, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "ahora"
        minutes < 60 -> "hace $minutes min"
        minutes < 24 * 60 -> "hace ${minutes / 60} h"
        minutes < 48 * 60 -> "ayer"
        minutes < 7 * 24 * 60 -> "hace ${minutes / (24 * 60)} d"
        else -> then.atZone(ZoneId.systemDefault()).format(shortDate)
    }
}

/** A small rounded label; clickable when [onClick] is set. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    container: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val background = container ?: if (selected) colors.primaryContainer else colors.surfaceContainerHigh
    val content = if (selected) colors.onPrimaryContainer else colors.onSurface
    val shape = RoundedCornerShape(50)
    val label: @Composable () -> Unit = {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = background, modifier = modifier, content = label)
    } else {
        Surface(shape = shape, color = background, modifier = modifier, content = label)
    }
}

/** Reaction counts, a button to react and the comments button, under each post. */
@Composable
fun ReactionBar(
    reactions: Map<String, Int>,
    mine: String?,
    commentCount: Int,
    onReact: (String) -> Unit,
    onComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        reactions.entries.sortedByDescending { it.value }.take(4).forEach { (emoji, count) ->
            Pill("$emoji $count", selected = emoji == mine, onClick = { onReact(emoji) })
        }
        Box {
            Pill(if (reactions.isEmpty()) "🙂 Reaccionar" else "🙂+", onClick = { picking = true })
            DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                Row(Modifier.padding(horizontal = 6.dp)) {
                    REACTIONS.forEach { emoji ->
                        Text(
                            emoji,
                            fontSize = 26.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (emoji == mine) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable {
                                    picking = false
                                    onReact(emoji)
                                }
                                .padding(8.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onComments) {
            Text(
                when (commentCount) {
                    0 -> "💬 Comentar"
                    1 -> "💬 1"
                    else -> "💬 $commentCount"
                },
            )
        }
    }
}

/** Optimistically applies my reaction toggle to the counts shown. */
fun toggledReactions(reactions: Map<String, Int>, mine: String?, emoji: String): Pair<Map<String, Int>, String?> {
    val counts = reactions.toMutableMap()
    if (mine != null) {
        val left = (counts[mine] ?: 1) - 1
        if (left <= 0) counts.remove(mine) else counts[mine] = left
    }
    if (mine == emoji) return counts to null
    counts[emoji] = (counts[emoji] ?: 0) + 1
    return counts to emoji
}

/** Header of a section, with an optional action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** A rounded card filled with a brand gradient and white content. */
@Composable
fun GradientCard(brush: Brush, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(brush)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Big number with a caption, for stats rows. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color.copy(alpha = 0.85f),
            maxLines = 1,
        )
    }
}
