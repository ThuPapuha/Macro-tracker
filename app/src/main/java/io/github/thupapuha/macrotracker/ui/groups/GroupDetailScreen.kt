@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.thupapuha.macrotracker.ui.groups

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.thupapuha.macrotracker.core.gym.WeightGoal
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.model.MealType
import io.github.thupapuha.macrotracker.core.supabase.BackendException
import io.github.thupapuha.macrotracker.core.supabase.DailySummary
import io.github.thupapuha.macrotracker.core.supabase.FeedPost
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import io.github.thupapuha.macrotracker.core.supabase.GroupInfo
import io.github.thupapuha.macrotracker.core.supabase.GroupMember
import io.github.thupapuha.macrotracker.core.supabase.GymStats
import io.github.thupapuha.macrotracker.core.supabase.MealComment
import io.github.thupapuha.macrotracker.core.supabase.MemberProgress
import io.github.thupapuha.macrotracker.core.supabase.PostType
import io.github.thupapuha.macrotracker.data.ProgressSync
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.SummarySync
import io.github.thupapuha.macrotracker.ui.common.Avatar
import io.github.thupapuha.macrotracker.ui.common.ConfirmDialog
import io.github.thupapuha.macrotracker.ui.common.ErrorText
import io.github.thupapuha.macrotracker.ui.common.MacroBars
import io.github.thupapuha.macrotracker.ui.common.StoryAvatar
import io.github.thupapuha.macrotracker.ui.common.appViewModel
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.toggledReactions
import io.github.thupapuha.macrotracker.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val FEED_PAGE = 30

class GroupDetailViewModel(
    private val groupId: String,
    private val api: GroupApi,
    private val sync: SummarySync,
    private val progressSync: ProgressSync,
    private val settings: SettingsStore,
) : ViewModel() {
    var group by mutableStateOf<GroupInfo?>(null)
        private set
    var members by mutableStateOf<List<GroupMember>>(emptyList())
        private set
    var summaries by mutableStateOf<List<DailySummary>>(emptyList())
        private set

    /** Everybody's food today, for the rings around their avatars. */
    var todaySummaries by mutableStateOf<List<DailySummary>>(emptyList())
        private set
    var comments by mutableStateOf<List<MealComment>>(emptyList())
        private set
    var feed by mutableStateOf<List<FeedPost>>(emptyList())
        private set
    var feedLoaded by mutableStateOf(false)
        private set
    var feedEnd by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var gymStats by mutableStateOf<List<GymStats>>(emptyList())
        private set
    var myId by mutableStateOf<String?>(null)
        private set
    var date by mutableStateOf(LocalDate.now())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var openPost by mutableStateOf<PostRef?>(null)
        private set
    var postComments by mutableStateOf<List<MealComment>>(emptyList())
        private set
    var commentsLoading by mutableStateOf(false)
        private set
    var commentsError by mutableStateOf<String?>(null)
        private set
    var sending by mutableStateOf(false)
        private set

    /** Member whose profile sheet is open. */
    var shownMember by mutableStateOf<GroupMember?>(null)
        private set
    var memberProgress by mutableStateOf<MemberProgress?>(null)
        private set
    var memberLoading by mutableStateOf(false)
        private set
    var memberError by mutableStateOf<String?>(null)
        private set

    private var dayJob: Job? = null

    fun refresh() {
        load {
            // Make sure our own numbers are fresh before showing everybody's.
            sync.syncNow(listOf(LocalDate.now(), date).distinct())
            progressSync.syncNow()
            myId = api.currentUserId()
            group = api.myGroups().firstOrNull { it.id == groupId }
                ?: throw BackendException("Ya no perteneces a este grupo")
            coroutineScope {
                val membersCall = async { api.members(groupId) }
                val feedCall = async { api.feed(groupId, limit = FEED_PAGE) }
                val statsCall = async { api.gymStats(groupId, LocalDate.now().minusDays(6)) }
                val todayCall = async { if (date == LocalDate.now()) null else api.summaries(groupId, LocalDate.now(), LocalDate.now()) }
                loadWindow(date)
                members = membersCall.await()
                feed = feedCall.await()
                feedLoaded = true
                feedEnd = feed.size < FEED_PAGE
                gymStats = statsCall.await()
                todayCall.await()?.let { todaySummaries = it }
            }
        }
    }

    fun loadMore() {
        if (loadingMore || feedEnd) return
        val before = feed.lastOrNull()?.at ?: return
        viewModelScope.launch {
            loadingMore = true
            try {
                val page = api.feed(groupId, before = before, limit = FEED_PAGE)
                val known = feed.mapTo(HashSet()) { it.key }
                feed = feed + page.filter { it.key !in known }
                feedEnd = page.size < FEED_PAGE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "No se pudieron cargar más publicaciones"
            } finally {
                loadingMore = false
            }
        }
    }

    fun shiftDay(days: Long) {
        val next = date.plusDays(days)
        if (next.isAfter(LocalDate.now())) return
        date = next
        dayJob?.cancel()
        dayJob = load { loadWindow(next) }
    }

    /** Toggles my reaction right away and undoes it if the server refuses. */
    fun react(post: FeedPost, emoji: String) {
        val (counts, mine) = toggledReactions(post.reactions, post.myReaction, emoji)
        replacePost(post.key) { it.copy(reactions = counts, myReaction = mine) }
        viewModelScope.launch {
            try {
                api.react(groupId, post.userId, post.type, post.postId, emoji)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                replacePost(post.key) { it.copy(reactions = post.reactions, myReaction = post.myReaction) }
                error = e.message ?: "No se pudo reaccionar"
            }
        }
    }

    fun openComments(post: PostRef) {
        openPost = post
        postComments = emptyList()
        commentsError = null
        viewModelScope.launch { reloadComments(post) }
    }

    fun closeComments() {
        openPost = null
    }

    fun sendComment(food: String?, body: String, onSent: () -> Unit) {
        val post = openPost ?: return
        viewModelScope.launch {
            sending = true
            commentsError = null
            try {
                api.addComment(groupId, post.userId, LocalDate.parse(post.day), food, body, post.type, post.postId)
                onSent()
                reloadComments(post)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                commentsError = e.message ?: "No se pudo enviar el comentario"
            } finally {
                sending = false
            }
        }
    }

    fun deleteComment(comment: MealComment) {
        val post = openPost ?: return
        viewModelScope.launch {
            try {
                api.deleteComment(comment.id)
                reloadComments(post)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                commentsError = e.message ?: "No se pudo eliminar"
            }
        }
    }

    private suspend fun reloadComments(post: PostRef) {
        commentsLoading = true
        try {
            val list = api.postComments(groupId, post.userId, post.type, post.postId)
            if (openPost == post) postComments = list
            // Keep the counters of the feed and of the day cards in step.
            replacePost("${post.type}|${post.userId}|${post.postId}") { it.copy(commentCount = list.size) }
            if (post.type == PostType.FOOD) comments = api.comments(groupId, date.minusDays(6), date)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            commentsError = e.message ?: "No se pudieron cargar los comentarios"
        } finally {
            commentsLoading = false
        }
    }

    fun openMember(member: GroupMember) {
        shownMember = member
        memberProgress = null
        memberError = null
        viewModelScope.launch {
            memberLoading = true
            try {
                val progress = api.memberProgress(groupId, member.userId)
                if (shownMember == member) memberProgress = progress
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                memberError = e.message ?: "No se pudo cargar su progreso"
            } finally {
                memberLoading = false
            }
        }
    }

    fun closeMember() {
        shownMember = null
    }

    fun leave(onLeft: () -> Unit) {
        load {
            api.leaveGroup(groupId)
            settings.setLastGroup("")
            settings.setHasGroups(api.myGroups().isNotEmpty())
            onLeft()
        }
    }

    private fun replacePost(key: String, change: (FeedPost) -> FeedPost) {
        feed = feed.map { if (it.key == key) change(it) else it }
    }

    private suspend fun loadWindow(day: LocalDate) {
        summaries = api.summaries(groupId, day.minusDays(6), day)
        comments = api.comments(groupId, day.minusDays(6), day)
        if (day == LocalDate.now()) todaySummaries = summaries.filter { it.day == day.toString() }
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

private val TABS = listOf("Feed", "Hoy", "Ranking")

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
        GroupDetailViewModel(groupId, requireNotNull(it.groupApi), it.summarySync, it.progressSync, it.settings)
    }
    // Reload every time the tab is opened so friends' latest posts show up.
    LaunchedEffect(groupId) { vm.refresh() }

    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    var switcherOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val group = vm.group ?: groups.firstOrNull { it.id == groupId }

    fun shareCode(info: GroupInfo) {
        val text = "¡Únete a mi grupo \"${info.name}\" en Macro Tracker! Código: ${info.inviteCode}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Compartir código"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { switcherOpen = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(group?.name ?: "Grupo", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Cambiar de grupo")
                        }
                        DropdownMenu(expanded = switcherOpen, onDismissRequest = { switcherOpen = false }) {
                            groups.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.name) },
                                    leadingIcon = {
                                        if (item.id == groupId) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        switcherOpen = false
                                        onSelectGroup(item.id)
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Crear grupo") }, onClick = { switcherOpen = false; onCreate() })
                            DropdownMenuItem(text = { Text("Unirme con código") }, onClick = { switcherOpen = false; onJoin() })
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Actualizar") }
                    if (group != null) {
                        IconButton(onClick = { shareCode(group) }) { Icon(Icons.Filled.Share, contentDescription = "Compartir código") }
                    }
                    Box {
                        IconButton(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Más opciones") }
                        DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                            DropdownMenuItem(text = { Text("Cambiar mi nombre") }, onClick = { moreOpen = false; onRename() })
                            if (group != null) {
                                DropdownMenuItem(text = { Text("Salir del grupo") }, onClick = { moreOpen = false; confirmLeave = true })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "stories") {
                StoriesRow(vm.members, vm.todaySummaries, vm.summaries, vm.myId, onOpen = vm::openMember)
            }
            item(key = "tabs") {
                TabRow(selectedTabIndex = tab) {
                    TABS.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
            }
            if (vm.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
            vm.error?.let { message -> item(key = "error") { ErrorText(message, Modifier.padding(horizontal = 16.dp)) } }

            when (tab) {
                0 -> {
                    if (group != null && group.memberCount <= 1) {
                        item(key = "invite") { InviteCard(group, onShare = { shareCode(group) }, modifier = Modifier.padding(horizontal = 16.dp)) }
                    }
                    if (vm.feedLoaded && vm.feed.isEmpty()) {
                        item(key = "feed-empty") {
                            Text(
                                "Aún no hay publicaciones. Registra tus comidas, tu peso o un entrenamiento y aparecerán aquí para tus amigos.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    items(vm.feed, key = { "post-${it.key}" }) { post ->
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            FeedPostCard(
                                post = post,
                                isMine = post.userId == vm.myId,
                                onReact = { emoji -> vm.react(post, emoji) },
                                onComments = { food -> vm.openComments(post.ref(food)) },
                                onOpenMember = {
                                    vm.members.firstOrNull { it.userId == post.userId }?.let(vm::openMember)
                                },
                            )
                        }
                    }
                    if (vm.feed.isNotEmpty() && !vm.feedEnd) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                OutlinedButton(onClick = vm::loadMore, enabled = !vm.loadingMore) {
                                    Text(if (vm.loadingMore) "Cargando…" else "Ver publicaciones anteriores")
                                }
                            }
                        }
                    }
                }
                1 -> {
                    item(key = "day-nav") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    items(ordered, key = { "member-${it.userId}" }) { member ->
                        val memberSummaries = vm.summaries.filter { it.userId == member.userId }
                        val summary = memberSummaries.firstOrNull { it.day == day }
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            MemberDayCard(
                                member = member,
                                isMe = member.userId == vm.myId,
                                summary = summary,
                                // A member without a row for this day still shows their latest goals.
                                fallbackTargets = memberSummaries.sortedByDescending { it.day }.firstNotNullOfOrNull { it.targets },
                                commentCount = vm.comments.count {
                                    it.targetUser == member.userId && it.day == day && it.postType == PostType.FOOD
                                },
                                onOpenMember = { vm.openMember(member) },
                                onComment = { food ->
                                    vm.openComments(
                                        PostRef(
                                            userId = member.userId,
                                            displayName = member.displayName,
                                            type = PostType.FOOD,
                                            postId = day,
                                            day = day,
                                            foods = summary?.meals?.map { it.name }?.distinct().orEmpty(),
                                            initialFood = food,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                else -> {
                    if (vm.members.isNotEmpty()) {
                        item(key = "ranking") {
                            Box(Modifier.padding(horizontal = 16.dp)) { RankingCard(vm.members, vm.summaries, vm.myId) }
                        }
                        item(key = "gym-ranking") {
                            Box(Modifier.padding(horizontal = 16.dp)) { GymRankingCard(vm.members, vm.gymStats, vm.myId) }
                        }
                        item(key = "weight-ranking") {
                            Box(Modifier.padding(horizontal = 16.dp)) { WeightGoalsCard(vm.members, vm.myId, onOpen = vm::openMember) }
                        }
                    }
                    if (group != null) {
                        item(key = "invite-bottom") { InviteCard(group, onShare = { shareCode(group) }, modifier = Modifier.padding(horizontal = 16.dp)) }
                    }
                }
            }
        }
    }

    vm.openPost?.let { post ->
        CommentsSheet(
            post = post,
            comments = vm.postComments,
            loading = vm.commentsLoading,
            sending = vm.sending,
            error = vm.commentsError,
            myId = vm.myId,
            onSend = vm::sendComment,
            onDelete = vm::deleteComment,
            onDismiss = vm::closeComments,
        )
    }

    vm.shownMember?.let { member ->
        MemberSheet(
            member = member,
            isMe = member.userId == vm.myId,
            progress = vm.memberProgress,
            loading = vm.memberLoading,
            error = vm.memberError,
            onDismiss = vm::closeMember,
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

/** Members as stories: the ring fills with today's calories against their goal. */
@Composable
private fun StoriesRow(
    members: List<GroupMember>,
    today: List<DailySummary>,
    window: List<DailySummary>,
    myId: String?,
    onOpen: (GroupMember) -> Unit,
) {
    val ordered = members.sortedWith(compareByDescending<GroupMember> { it.userId == myId }.thenBy { it.displayName.lowercase() })
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(ordered, key = { it.userId }) { member ->
            val summary = today.firstOrNull { it.userId == member.userId }
            val targets = summary?.targets
                ?: window.filter { it.userId == member.userId }.sortedByDescending { it.day }.firstNotNullOfOrNull { it.targets }
            val calories = summary?.calories ?: 0.0
            val progress = targets?.let { (calories / it.calories).toFloat() }
            Column(
                Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(member) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StoryAvatar(
                    name = member.displayName,
                    seed = member.userId,
                    progress = progress,
                    highlight = targets != null && summary != null && targets.isOnTarget(summary.totals),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (member.userId == myId) "Tú" else member.displayName.substringBefore(' '),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (calories > 0) "${formatAmount(calories)} kcal" else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun InviteCard(group: GroupInfo, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Invita a tus amigos", style = MaterialTheme.typography.labelLarge)
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
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onShare) { Text("Compartir") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(group.inviteCode)) }) { Text("Copiar") }
            }
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
    onOpenMember: () -> Unit,
    onComment: (food: String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val totals = summary?.totals ?: Macros.ZERO
    val targets = summary?.targets ?: fallbackTargets
    val logged = totals.calories > 0 || summary?.meals?.isNotEmpty() == true

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(member.displayName, member.userId, Modifier.clickable(onClick = onOpenMember), diameter = 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    member.displayName + (if (isMe) " (tú)" else "") + (if (member.isOwner) " 👑" else ""),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

private fun medal(index: Int) = when (index) {
    0 -> "🥇"
    1 -> "🥈"
    2 -> "🥉"
    else -> "${index + 1}."
}

private data class RankingRow(val member: GroupMember, val onTarget: Int, val logged: Int)

@Composable
private fun RankingCard(members: List<GroupMember>, summaries: List<DailySummary>, myId: String?) {
    val rows = members.map { member ->
        val days = summaries.filter { it.userId == member.userId && it.calories > 0 }
        RankingRow(member, days.count { it.targets?.isOnTarget(it.totals) == true }, days.size)
    }.sortedWith(compareByDescending<RankingRow> { it.onTarget }.thenByDescending { it.logged })

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🎯 Alimentación · 7 días", style = MaterialTheme.typography.titleMedium)
            rows.forEachIndexed { index, row ->
                RankingLine(index, row.member, myId, "${row.onTarget} 🎯 · ${row.logged} días")
            }
            Text(
                "🎯 = días dentro de ±10% de su objetivo de calorías.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GymRankingCard(members: List<GroupMember>, stats: List<GymStats>, myId: String?) {
    val byUser = stats.associateBy { it.userId }
    val rows = members.map { it to (byUser[it.userId] ?: GymStats(it.userId)) }
        .sortedWith(compareByDescending<Pair<GroupMember, GymStats>> { it.second.workouts }.thenByDescending { it.second.prs })
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🏋️ Gym · 7 días", style = MaterialTheme.typography.titleMedium)
            rows.forEachIndexed { index, (member, stat) ->
                RankingLine(
                    index,
                    member,
                    myId,
                    "${stat.workouts} entrenos · ${stat.prs} 🏆" + if (stat.volume > 0) " · ${formatAmount(stat.volume)} kg" else "",
                )
            }
        }
    }
}

/** Members who share a weight goal, by how far along they are. */
@Composable
private fun WeightGoalsCard(members: List<GroupMember>, myId: String?, onOpen: (GroupMember) -> Unit) {
    val rows = members.mapNotNull { member ->
        val goal = member.goalWeight ?: return@mapNotNull null
        val current = member.currentWeight ?: return@mapNotNull null
        val start = member.startWeight ?: current
        member to WeightGoal(start, goal).progress(current)
    }.sortedByDescending { it.second }
    if (rows.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("⚖️ Metas de peso", style = MaterialTheme.typography.titleMedium)
            rows.forEach { (member, progress) ->
                Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpen(member) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(member.displayName, member.userId, diameter = 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            member.displayName + if (member.userId == myId) " (tú)" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Brand.Amber,
                        strokeCap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun RankingLine(index: Int, member: GroupMember, myId: String?, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(medal(index), modifier = Modifier.width(30.dp))
        Avatar(member.displayName, member.userId, diameter = 28.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            member.displayName + if (member.userId == myId) " (tú)" else "",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(detail, style = MaterialTheme.typography.labelLarge)
    }
}
