@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.groups

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.core.supabase.BackendException
import io.github.thupapuha.macrotracker.core.supabase.DailySummary
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import io.github.thupapuha.macrotracker.core.supabase.GroupInfo
import io.github.thupapuha.macrotracker.core.supabase.GroupMember
import io.github.thupapuha.macrotracker.core.supabase.MealComment
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.SummarySync
import io.github.thupapuha.macrotracker.ui.common.ConfirmDialog
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.MacroBars
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class GroupDetailViewModel(
    private val groupId: String,
    private val api: GroupApi,
    private val sync: SummarySync,
    private val settings: SettingsStore,
) : ViewModel() {
    var group by mutableStateOf<GroupInfo?>(null)
        private set
    var members by mutableStateOf<List<GroupMember>>(emptyList())
        private set
    var summaries by mutableStateOf<List<DailySummary>>(emptyList())
        private set
    var comments by mutableStateOf<List<MealComment>>(emptyList())
        private set
    var myId by mutableStateOf<String?>(null)
        private set
    var date by mutableStateOf(LocalDate.now())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var sending by mutableStateOf(false)
        private set

    private var dayJob: Job? = null

    fun refresh() {
        load {
            // Make sure our own numbers are fresh before showing everybody's.
            sync.syncNow(listOf(LocalDate.now(), date).distinct())
            myId = api.currentUserId()
            group = api.myGroups().firstOrNull { it.id == groupId }
                ?: throw BackendException("Ya no perteneces a este grupo")
            members = api.members(groupId)
            loadWindow(date)
        }
    }

    fun shiftDay(days: Long) {
        val next = date.plusDays(days)
        if (next.isAfter(LocalDate.now())) return
        date = next
        dayJob?.cancel()
        dayJob = load { loadWindow(next) }
    }

    fun addComment(target: GroupMember, mealName: String?, body: String, onSent: () -> Unit) {
        viewModelScope.launch {
            sending = true
            error = null
            try {
                api.addComment(groupId, target.userId, date, mealName, body)
                comments = api.comments(groupId, date.minusDays(6), date)
                onSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "No se pudo enviar el comentario"
            } finally {
                sending = false
            }
        }
    }

    fun deleteComment(comment: MealComment) {
        load {
            api.deleteComment(comment.id)
            comments = api.comments(groupId, date.minusDays(6), date)
        }
    }

    fun leave(onLeft: () -> Unit) {
        load {
            api.leaveGroup(groupId)
            settings.setLastGroup("")
            settings.setHasGroups(api.myGroups().isNotEmpty())
            onLeft()
        }
    }

    private suspend fun loadWindow(day: LocalDate) {
        summaries = api.summaries(groupId, day.minusDays(6), day)
        comments = api.comments(groupId, day.minusDays(6), day)
    }

    private fun load(block: suspend () -> Unit): Job =
        viewModelScope.launch {
            loading = true
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "No se pudo cargar el grupo"
            } finally {
                loading = false
            }
        }
}

/** Which member's day (and optionally which food) the comments dialog is about. */
private data class CommentTarget(val member: GroupMember, val mealName: String?)

@Composable
fun GroupDetailScreen(
    groupId: String,
    groups: List<GroupInfo>,
    onSelectGroup: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onRename: () -> Unit,
    onLeft: () -> Unit,
) {
    val vm = appViewModel(key = "group-$groupId") {
        GroupDetailViewModel(groupId, requireNotNull(it.groupApi), it.summarySync, it.settings)
    }
    // Reload every time the tab is opened so friends' latest food shows up.
    LaunchedEffect(groupId) { vm.refresh() }

    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var commenting by remember { mutableStateOf<CommentTarget?>(null) }
    val group = vm.group ?: groups.firstOrNull { it.id == groupId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { menuOpen = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group?.name ?: "Grupo", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Cambiar de grupo")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            groups.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.name) },
                                    leadingIcon = {
                                        if (item.id == groupId) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onSelectGroup(item.id)
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Crear grupo") }, onClick = { menuOpen = false; onCreate() })
                            DropdownMenuItem(text = { Text("Unirme con código") }, onClick = { menuOpen = false; onJoin() })
                            DropdownMenuItem(text = { Text("Cambiar mi nombre") }, onClick = { menuOpen = false; onRename() })
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualizar") }
                    if (group != null) {
                        IconButton(onClick = {
                            val text = "¡Únete a mi grupo \"${group.name}\" en Macro Tracker! Código: ${group.inviteCode}"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Compartir código"))
                        }) { Icon(Icons.Filled.Share, contentDescription = "Compartir código") }
                        IconButton(onClick = { confirmLeave = true }) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Salir del grupo")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            vm.error?.let { message -> item { ErrorText(message) } }
            if (group != null) {
                item { InviteCard(group) }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shiftDay(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Día anterior")
                    }
                    Text(
                        dayLabel(vm.date),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { vm.shiftDay(1) }, enabled = vm.date.isBefore(LocalDate.now())) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Día siguiente")
                    }
                }
            }
            val day = vm.date.toString()
            val ordered = vm.members.sortedWith(
                compareByDescending<GroupMember> { it.userId == vm.myId }.thenBy { it.displayName.lowercase() },
            )
            items(ordered, key = { it.userId }) { member ->
                val memberSummaries = vm.summaries.filter { it.userId == member.userId }
                MemberDayCard(
                    member = member,
                    isMe = member.userId == vm.myId,
                    summary = memberSummaries.firstOrNull { it.day == day },
                    // A member without a row for this day still shows their latest goals.
                    fallbackTargets = memberSummaries.sortedByDescending { it.day }.firstNotNullOfOrNull { it.targets },
                    commentCount = vm.comments.count { it.targetUser == member.userId && it.day == day },
                    onComment = { mealName -> commenting = CommentTarget(member, mealName) },
                )
            }
            if (vm.members.isNotEmpty()) {
                item { RankingCard(vm.members, vm.summaries, vm.myId) }
            }
        }
    }

    commenting?.let { target ->
        val summary = vm.summaries.firstOrNull { it.userId == target.member.userId && it.day == vm.date.toString() }
        CommentsDialog(
            member = target.member,
            isMe = target.member.userId == vm.myId,
            day = vm.date,
            foods = summary?.meals?.map { it.name }?.distinct().orEmpty(),
            initialMeal = target.mealName,
            comments = vm.comments.filter { it.targetUser == target.member.userId && it.day == vm.date.toString() },
            myId = vm.myId,
            sending = vm.sending,
            onSend = { mealName, body, onSent -> vm.addComment(target.member, mealName, body, onSent) },
            onDelete = vm::deleteComment,
            onDismiss = { commenting = null },
        )
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Salir del grupo",
            message = "Dejarás de ver el progreso de este grupo. Puedes volver a unirte con el código.",
            confirmLabel = "Salir",
            onDismiss = { confirmLeave = false },
            onConfirm = {
                confirmLeave = false
                vm.leave(onLeft)
            },
        )
    }
}

@Composable
private fun InviteCard(group: GroupInfo) {
    val clipboard = LocalClipboardManager.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Código de invitación", style = MaterialTheme.typography.labelMedium)
                Text(
                    group.inviteCode,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (group.memberCount == 1) "1 miembro" else "${group.memberCount} miembros",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(group.inviteCode)) }) { Text("Copiar") }
        }
    }
}

@Composable
private fun MemberDayCard(
    member: GroupMember,
    isMe: Boolean,
    summary: DailySummary?,
    fallbackTargets: MacroTargets?,
    commentCount: Int,
    onComment: (mealName: String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val totals = summary?.totals ?: Macros.ZERO
    val targets = summary?.targets ?: fallbackTargets
    val logged = totals.calories > 0 || summary?.meals?.isNotEmpty() == true

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    member.displayName + (if (isMe) " (tú)" else "") + (if (member.isOwner) " 👑" else ""),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        targets != null -> "${formatAmount(totals.calories)} / ${formatAmount(targets.calories)} kcal"
                        logged -> "${formatAmount(totals.calories)} kcal"
                        else -> "Sin registros"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (targets != null) {
                val over = totals.calories > targets.calories * 1.1
                LinearProgressIndicator(
                    progress = { (totals.calories / targets.calories).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    strokeCap = StrokeCap.Round,
                )
                MacroBars(totals, targets)
                if (!logged) {
                    Text(
                        "Sin registros este día",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (logged) {
                MacroBars(totals, null)
            }

            val meals = summary?.meals.orEmpty()
            if (expanded && meals.isNotEmpty()) {
                meals.groupBy { MealType.fromName(it.meal) }.toSortedMap().forEach { (meal, foods) ->
                    Text(meal.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    foods.forEach { food ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onComment(food.name) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("• ${food.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("${formatAmount(food.calories)} kcal", style = MaterialTheme.typography.bodySmall)
                            Text("  💬", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meals.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Ocultar comidas ▴" else "Ver ${meals.size} alimentos ▾")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onComment(null) }) {
                    Text(
                        when (commentCount) {
                            0 -> "💬 Comentar"
                            1 -> "💬 1 comentario"
                            else -> "💬 $commentCount comentarios"
                        },
                    )
                }
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("es"))

private fun commentTime(createdAt: String): String = runCatching {
    OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()).format(timeFormat)
}.getOrDefault("")

@Composable
private fun CommentsDialog(
    member: GroupMember,
    isMe: Boolean,
    day: LocalDate,
    foods: List<String>,
    initialMeal: String?,
    comments: List<MealComment>,
    myId: String?,
    sending: Boolean,
    onSend: (mealName: String?, body: String, onSent: () -> Unit) -> Unit,
    onDelete: (MealComment) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var meal by remember { mutableStateOf(initialMeal) }
    val who = if (isMe) "tus comidas" else "comidas de ${member.displayName}"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Comentarios · ${dayLabel(day)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(who.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge)
                Column(
                    Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (comments.isEmpty()) {
                        Text(
                            "Todavía no hay comentarios. ¡Deja el primero!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    comments.forEach { comment ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    comment.authorName + (if (comment.authorId == myId) " (tú)" else ""),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    commentTime(comment.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            comment.mealName?.let {
                                Text(
                                    "sobre $it",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
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
                HorizontalDivider()
                if (foods.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(selected = meal == null, onClick = { meal = null }, label = { Text("Todo el día") })
                        foods.forEach { food ->
                            FilterChip(selected = meal == food, onClick = { meal = food }, label = { Text(food) })
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 500) text = it },
                    placeholder = { Text(if (meal != null) "Comenta sobre $meal" else "Escribe un comentario") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(meal, text.trim()) { text = "" } },
                enabled = text.isNotBlank() && !sending,
            ) { Text(if (sending) "Enviando…" else "Enviar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

private data class RankingRow(val member: GroupMember, val onTarget: Int, val logged: Int)

@Composable
private fun RankingCard(members: List<GroupMember>, summaries: List<DailySummary>, myId: String?) {
    val rows = members.map { member ->
        val days = summaries.filter { it.userId == member.userId && it.calories > 0 }
        RankingRow(member, days.count { it.targets?.isOnTarget(it.totals) == true }, days.size)
    }.sortedWith(compareByDescending<RankingRow> { it.onTarget }.thenByDescending { it.logged })

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🏆 Ranking de 7 días", style = MaterialTheme.typography.titleMedium)
            rows.forEachIndexed { index, row ->
                val medal = when (index) {
                    0 -> "🥇"
                    1 -> "🥈"
                    2 -> "🥉"
                    else -> "${index + 1}."
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(medal, modifier = Modifier.width(32.dp))
                    Text(
                        row.member.displayName + if (row.member.userId == myId) " (tú)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${row.onTarget} 🎯 · ${row.logged} días", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                "🎯 = días dentro de ±10% de su objetivo de calorías.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
