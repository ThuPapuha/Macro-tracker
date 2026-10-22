@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.thupapuha.macrotracker.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thupapuha.macrotracker.core.gym.BodyMetric
import io.github.thupapuha.macrotracker.core.gym.Gym
import io.github.thupapuha.macrotracker.core.gym.WeightGoal
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.core.supabase.FeedPost
import io.github.thupapuha.macrotracker.core.supabase.FoodPostData
import io.github.thupapuha.macrotracker.core.supabase.GroupMember
import io.github.thupapuha.macrotracker.core.supabase.MealComment
import io.github.thupapuha.macrotracker.core.supabase.MemberProgress
import io.github.thupapuha.macrotracker.core.supabase.PostType
import io.github.thupapuha.macrotracker.core.supabase.WeightPostData
import io.github.thupapuha.macrotracker.core.supabase.WorkoutPostData
import io.github.thupapuha.macrotracker.ui.common.Avatar
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.Pill
import io.github.thupapuha.macrotracker.ui.common.ReactionBar
import io.github.thupapuha.macrotracker.ui.common.StatTile
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.timeAgo
import io.github.thupapuha.macrotracker.ui.progress.BodyChart
import io.github.thupapuha.macrotracker.ui.progress.CompositionRow
import io.github.thupapuha.macrotracker.ui.progress.ExerciseLines
import io.github.thupapuha.macrotracker.ui.progress.TrendPill
import io.github.thupapuha.macrotracker.ui.progress.WorkoutStatsRow
import io.github.thupapuha.macrotracker.ui.progress.kg
import io.github.thupapuha.macrotracker.ui.progress.liftLabel
import io.github.thupapuha.macrotracker.ui.progress.metricColor
import io.github.thupapuha.macrotracker.ui.progress.metricText
import io.github.thupapuha.macrotracker.ui.theme.Brand
import io.github.thupapuha.macrotracker.ui.theme.MacroColors
import java.time.LocalDate

/** The post a comments sheet is about. [postId] is the day for food and weight, the id for workouts. */
data class PostRef(
    val userId: String,
    val displayName: String,
    val type: String,
    val postId: String,
    val day: String,
    val foods: List<String> = emptyList(),
    val initialFood: String? = null,
)

fun FeedPost.ref(initialFood: String? = null) = PostRef(
    userId = userId,
    displayName = displayName,
    type = type,
    postId = postId,
    day = day,
    foods = food?.meals?.map { it.name }?.distinct().orEmpty(),
    initialFood = initialFood,
)

private fun dayText(day: String): String =
    runCatching { dayLabel(LocalDate.parse(day)).lowercase() }.getOrDefault(day)

@Composable
fun FeedPostCard(
    post: FeedPost,
    isMine: Boolean,
    onReact: (String) -> Unit,
    onComments: (food: String?) -> Unit,
    onOpenMember: () -> Unit,
) {
    val action = when (post.type) {
        PostType.FOOD -> "registró sus comidas de ${dayText(post.day)}"
        PostType.WORKOUT -> "entrenó ${post.workout?.title.orEmpty()}"
        else -> if (post.weight?.let { it.fat ?: it.muscle ?: it.bone } != null) "registró su peso y composición" else "registró su peso"
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.displayName, post.userId, Modifier.clickable(onClick = onOpenMember), diameter = 42.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clickable(onClick = onOpenMember)) {
                    Text(
                        post.displayName + if (isMine) " (tú)" else "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$action · ${timeAgo(post.at)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    when (post.type) {
                        PostType.FOOD -> "🍽️"
                        PostType.WORKOUT -> "🏋️"
                        else -> "⚖️"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Column(Modifier.padding(end = 8.dp)) {
                post.food?.let { FoodContent(it, onFood = { name -> onComments(name) }) }
                post.workout?.let { WorkoutContent(it) }
                post.weight?.let { WeightContent(it) }
            }
            ReactionBar(
                reactions = post.reactions,
                mine = post.myReaction,
                commentCount = post.commentCount,
                onReact = onReact,
                onComments = { onComments(null) },
            )
        }
    }
}

@Composable
private fun FoodContent(food: FoodPostData, onFood: (String) -> Unit) {
    val targets = food.targets
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatAmount(food.calories), style = MaterialTheme.typography.headlineSmall)
            Text(
                if (targets != null) " / ${formatAmount(targets.calories)} kcal" else " kcal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            if (targets?.isOnTarget(food.totals) == true) Pill("🎯 En objetivo", selected = true)
        }
        if (targets != null) {
            val over = food.calories > targets.calories * 1.1
            LinearProgressIndicator(
                progress = { (food.calories / targets.calories).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                strokeCap = StrokeCap.Round,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MacroPill("P", food.protein, targets?.protein, MacroColors.Protein)
            MacroPill("C", food.carbs, targets?.carbs, MacroColors.Carbs)
            MacroPill("G", food.fat, targets?.fat, MacroColors.Fat)
        }
        if (food.meals.isNotEmpty()) {
            food.meals.groupBy { MealType.fromName(it.meal) }.toSortedMap().forEach { (meal, items) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(meal.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items.forEach { item ->
                            Pill("${item.name} · ${formatAmount(item.calories)}", onClick = { onFood(item.name) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MacroPill(label: String, value: Double, target: Double?, color: Color) {
    Pill(
        if (target != null && target > 0) "$label ${formatAmount(value)}/${formatAmount(target)} g" else "$label ${formatAmount(value)} g",
        container = color.copy(alpha = 0.2f),
    )
}

@Composable
private fun WorkoutContent(workout: WorkoutPostData) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkoutStatsRow(workout.exercises, workout.durationMin, workout.prCount)
        ExerciseLines(workout.exercises, limit = 5)
    }
}

@Composable
private fun WeightContent(weight: WeightPostData) {
    val goal = weight.goal?.let { WeightGoal(weight.start ?: weight.previous ?: weight.kg, it) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kg(weight.kg), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            // Without a goal, going down counts as the good direction.
            TrendPill(BodyMetric.WEIGHT, weight.previous?.let { weight.kg - it }, goal?.losing ?: true)
        }
        val composition = listOf(
            Triple(BodyMetric.FAT, weight.fat, weight.previousFat),
            Triple(BodyMetric.MUSCLE, weight.muscle, weight.previousMuscle),
            Triple(BodyMetric.BONE, weight.bone, null),
        ).filter { it.second != null }
        if (composition.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                composition.forEach { (metric, value, previous) ->
                    Pill("${metric.label} ${metricText(metric, value!!)}", container = metricColor(metric).copy(alpha = 0.2f))
                    TrendPill(metric, previous?.let { value - it })
                }
            }
        }
        if (goal != null) {
            val progress = goal.progress(weight.kg)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = Brand.Amber,
                trackColor = Brand.Amber.copy(alpha = 0.18f),
                strokeCap = StrokeCap.Round,
            )
            Text(
                "Meta ${kg(goal.goal)} · ${(progress * 100).toInt()}% del camino",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Comments of one post, with a box to write a new one. */
@Composable
fun CommentsSheet(
    post: PostRef,
    comments: List<MealComment>,
    loading: Boolean,
    sending: Boolean,
    error: String?,
    myId: String?,
    onSend: (food: String?, body: String, onSent: () -> Unit) -> Unit,
    onDelete: (MealComment) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var food by remember { mutableStateOf(post.initialFood) }
    val what = when (post.type) {
        PostType.WORKOUT -> "Entrenamiento de ${post.displayName}"
        PostType.WEIGHT -> "Peso de ${post.displayName}"
        else -> "Comidas de ${post.displayName} · ${dayText(post.day)}"
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Comentarios", style = MaterialTheme.typography.titleLarge)
            Text(what, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            if (loading && comments.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (comments.isEmpty()) {
                Text(
                    "Todavía no hay comentarios. ¡Deja el primero!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(comments, key = { it.id }) { comment ->
                    Row {
                        Avatar(comment.authorName, comment.authorId, diameter = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    comment.authorName + if (comment.authorId == myId) " (tú)" else "",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    timeAgo(comment.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            comment.mealName?.let {
                                Text("sobre $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(comment.body, style = MaterialTheme.typography.bodyMedium)
                            if (comment.authorId == myId) {
                                Text(
                                    "Eliminar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.clickable { onDelete(comment) }.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            error?.let { ErrorText(it) }
            if (post.foods.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = food == null, onClick = { food = null }, label = { Text("Todo el día") })
                    post.foods.forEach { name ->
                        FilterChip(selected = food == name, onClick = { food = name }, label = { Text(name) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 500) text = it },
                    placeholder = { Text(if (food != null) "Comenta sobre $food" else "Escribe un comentario") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onSend(food, text.trim()) { text = "" } }, enabled = text.isNotBlank() && !sending) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}

/** A member's profile: weight progress and recent workouts with their best lifts. */
@Composable
fun MemberSheet(
    member: GroupMember,
    isMe: Boolean,
    progress: MemberProgress?,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(member.displayName, member.userId, diameter = 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(member.displayName + if (isMe) " (tú)" else "", style = MaterialTheme.typography.titleLarge)
                        if (member.isOwner) {
                            Text("👑 Creó el grupo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (loading && progress == null) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            }
            error?.let { message -> item { ErrorText(message) } }
            if (progress != null) {
                item { MemberWeight(progress) }
                val workouts = progress.workouts
                if (workouts.isNotEmpty()) {
                    val bests = Gym.bests(workouts.map { it.exercises })
                    val names = workouts.flatMap { w -> w.exercises.map { it.name } }.associateBy { Gym.key(it) }
                    item {
                        Row(Modifier.fillMaxWidth()) {
                            StatTile("${workouts.count { it.day >= LocalDate.now().minusDays(6).toString() }}", "entrenos 7 días", Modifier.weight(1f))
                            StatTile("${workouts.sumOf { it.prCount }}", "récords", Modifier.weight(1f))
                            StatTile("${bests.size}", "ejercicios", Modifier.weight(1f))
                        }
                    }
                    item { Text("Mejores marcas", style = MaterialTheme.typography.titleMedium) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            bests.entries.sortedByDescending { it.value.estimatedMax }.take(6).forEach { (key, best) ->
                                Row {
                                    Text(names[key] ?: key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("🏆 ${liftLabel(best)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    item { Text("Entrenamientos recientes", style = MaterialTheme.typography.titleMedium) }
                    items(workouts.take(10), key = { it.id }) { workout ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row {
                                    Text(workout.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Text(dayText(workout.day), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                WorkoutStatsRow(workout.exercises, workout.durationMin, workout.prCount)
                                ExerciseLines(workout.exercises, limit = 4)
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            "Todavía no comparte entrenamientos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MemberWeight(progress: MemberProgress) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⚖️ Peso y composición", style = MaterialTheme.typography.titleMedium)
            when {
                !progress.shareWeight -> Text(
                    "Prefiere no compartir su peso.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress.weights.isEmpty() && progress.goalWeight == null -> Text(
                    "Todavía no registra su peso.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val current = progress.weights.lastOrNull()?.kg
                    val goal = progress.goalWeight?.let { WeightGoal(progress.startWeight ?: progress.weights.firstOrNull()?.kg ?: it, it) }
                    Row(Modifier.fillMaxWidth()) {
                        StatTile(current?.let(::kg) ?: "—", "Actual", Modifier.weight(1f))
                        StatTile(goal?.let { kg(it.goal) } ?: "—", "Meta", Modifier.weight(1f))
                        StatTile(goal?.let { kg(it.start) } ?: "—", "Inicio", Modifier.weight(1f))
                    }
                    if (goal != null && current != null) {
                        val fraction = goal.progress(current)
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = Brand.Amber,
                            trackColor = Brand.Amber.copy(alpha = 0.18f),
                            strokeCap = StrokeCap.Round,
                        )
                        Text(
                            "${(fraction * 100).toInt()}% del camino a su meta",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val logs = progress.weights.map { it.toLog() }
                    CompositionRow(logs)
                    BodyChart(logs, goal?.goal)
                }
            }
        }
    }
}
