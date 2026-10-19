@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.groups

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.core.supabase.BackendException
import io.github.thupapuha.macrotracker.core.supabase.DailySummary
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import io.github.thupapuha.macrotracker.core.supabase.GroupInfo
import io.github.thupapuha.macrotracker.core.supabase.GroupMember
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.SummarySync
import io.github.thupapuha.macrotracker.ui.common.ConfirmDialog
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.macroLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

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
    var myId by mutableStateOf<String?>(null)
        private set
    var date by mutableStateOf(LocalDate.now())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var dayJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        load {
            // Make sure our own numbers are fresh before showing everybody's.
            sync.syncNow(listOf(LocalDate.now(), date).distinct())
            myId = api.currentUserId()
            group = api.myGroups().firstOrNull { it.id == groupId }
                ?: throw BackendException("Ya no perteneces a este grupo")
            members = api.members(groupId)
            summaries = api.summaries(groupId, date.minusDays(6), date)
        }
    }

    fun shiftDay(days: Long) {
        val next = date.plusDays(days)
        if (next.isAfter(LocalDate.now())) return
        date = next
        dayJob?.cancel()
        dayJob = load { summaries = api.summaries(groupId, next.minusDays(6), next) }
    }

    fun leave(onLeft: () -> Unit) {
        load {
            api.leaveGroup(groupId)
            settings.setHasGroups(api.myGroups().isNotEmpty())
            onLeft()
        }
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

@Composable
fun GroupDetailScreen(groupId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "group-$groupId") {
        GroupDetailViewModel(groupId, requireNotNull(it.groupApi), it.summarySync, it.settings)
    }
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    val group = vm.group

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "Grupo") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
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
            val ordered = vm.members.sortedWith(compareByDescending<GroupMember> { it.userId == vm.myId }.thenBy { it.displayName.lowercase() })
            items(ordered, key = { it.userId }) { member ->
                MemberDayCard(
                    member = member,
                    isMe = member.userId == vm.myId,
                    summary = vm.summaries.firstOrNull { it.userId == member.userId && it.day == day },
                )
            }
            if (vm.members.isNotEmpty()) {
                item { RankingCard(vm.members, vm.summaries, vm.myId) }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Salir del grupo",
            message = "Dejarás de ver el progreso de este grupo. Puedes volver a unirte con el código.",
            confirmLabel = "Salir",
            onDismiss = { confirmLeave = false },
            onConfirm = {
                confirmLeave = false
                vm.leave(onBack)
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
private fun MemberDayCard(member: GroupMember, isMe: Boolean, summary: DailySummary?) {
    var expanded by remember { mutableStateOf(false) }
    val targets = summary?.targets
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .clickable(enabled = summary?.meals?.isNotEmpty() == true) { expanded = !expanded }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    member.displayName + (if (isMe) " (tú)" else "") + (if (member.isOwner) " 👑" else ""),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        summary == null || summary.calories <= 0 -> "Sin registros"
                        targets != null -> "${formatAmount(summary.calories)} / ${formatAmount(targets.calories)} kcal"
                        else -> "${formatAmount(summary.calories)} kcal"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (summary != null && summary.calories > 0) {
                Spacer(Modifier.height(8.dp))
                if (targets != null) {
                    val over = summary.calories > targets.calories * 1.1
                    LinearProgressIndicator(
                        progress = { (summary.calories / targets.calories).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        strokeCap = StrokeCap.Round,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    macroLine(summary.totals) + (
                        if (targets != null && targets.protein > 0) {
                            " · proteína ${(summary.protein / targets.protein * 100).toInt()}%"
                        } else {
                            ""
                        }
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (summary.meals.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    if (expanded) {
                        summary.meals
                            .groupBy { MealType.fromName(it.meal) }
                            .toSortedMap()
                            .forEach { (meal, foods) ->
                                Text(meal.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                                foods.forEach { food ->
                                    Row {
                                        Text("• ${food.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        Text("${formatAmount(food.calories)} kcal", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                    } else {
                        Text(
                            "Ver ${summary.meals.size} alimentos ▾",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
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
