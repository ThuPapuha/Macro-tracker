package io.github.thupapuha.macrotracker.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.thupapuha.macrotracker.MacroTrackerApp
import io.github.thupapuha.macrotracker.MainActivity
import io.github.thupapuha.macrotracker.R
import io.github.thupapuha.macrotracker.core.supabase.ActivityComment
import io.github.thupapuha.macrotracker.core.supabase.ActivityItem
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import io.github.thupapuha.macrotracker.ui.common.dayLabel
import io.github.thupapuha.macrotracker.ui.common.formatAmount
import io.github.thupapuha.macrotracker.ui.common.macroLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * Tells you when friends log food or comment on yours. There is no push server: a periodic
 * background job (every ~15 minutes, Android's minimum) polls `group_activity`.
 */
class GroupActivityNotifier(
    context: Context,
    private val api: GroupApi?,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext

    fun start() {
        if (api == null) return
        scope.launch {
            settings.settings
                .map { it.hasGroups && it.notifyGroupActivity }
                .distinctUntilChanged()
                .collect { active ->
                    val work = WorkManager.getInstance(context)
                    if (active) {
                        val request = PeriodicWorkRequestBuilder<GroupActivityWorker>(15, TimeUnit.MINUTES)
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .build()
                        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
                    } else {
                        work.cancelUniqueWork(WORK_NAME)
                    }
                }
        }
    }

    /** Fetches what changed since the last poll and posts one notification per friend or comment. */
    suspend fun poll() {
        val api = api ?: return
        val prefs = settings.current()
        if (!prefs.hasGroups || !prefs.notifyGroupActivity) return

        val seen = settings.activitySeen()
        // Overlap a little with the previous poll; already-seen changes are skipped below.
        val activity = api.activity(seen.cursor?.let { minusSeconds(it, 30) })
        val days = seen.days.toMutableMap()
        val commentIds = seen.commentIds.toMutableList()

        for (item in activity.items) {
            val key = "${item.userId}|${item.day}"
            val previous = days[key]
            if (previous != null && !isAfter(item.updatedAt, previous.updatedAt)) continue
            val foods = item.meals.map { it.name }
            notifyFood(item, newFoods(previous?.foods.orEmpty(), foods))
            days[key] = SeenDay(item.updatedAt, foods)
        }
        for (comment in activity.comments) {
            if (comment.id in commentIds) continue
            notifyComment(comment)
            commentIds += comment.id
        }

        val oldest = LocalDate.now().minusDays(4).toString()
        settings.setActivitySeen(
            ActivitySeen(
                cursor = activity.now,
                days = days.filterKeys { it.substringAfter('|') >= oldest },
                commentIds = commentIds.takeLast(200),
            ),
        )
    }

    private fun notifyFood(item: ActivityItem, newFoods: List<String>) {
        val day = runCatching { dayLabel(LocalDate.parse(item.day)) }.getOrDefault(item.day)
        val total = "${formatAmount(item.calories)} kcal"
        val (title, text) = if (newFoods.isNotEmpty()) {
            val foods = newFoods.take(4).joinToString(", ") + if (newFoods.size > 4) "…" else ""
            "${item.displayName} registró comida" to "$foods · $day lleva $total"
        } else {
            "${item.displayName} actualizó sus comidas" to "$day lleva $total (${macroLine(item.totals)})"
        }
        post("food-${item.userId}".hashCode(), item.groupId, title, text, item.groupName)
    }

    private fun notifyComment(comment: ActivityComment) {
        val about = comment.mealName?.let { "Sobre $it: " }.orEmpty()
        post(
            "comment-${comment.id}".hashCode(),
            comment.groupId,
            "${comment.authorName} comentó tu comida",
            about + comment.body,
            comment.groupName,
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(id: Int, groupId: String, title: String, text: String, subText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Actividad de tus grupos")
                .setDescription("Cuando tus amigos registran comida o comentan la tuya")
                .build(),
        )
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_GROUP, groupId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(subText)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    companion object {
        const val EXTRA_OPEN_GROUP = "open_group"
        private const val CHANNEL_ID = "group_activity"
        private const val WORK_NAME = "group-activity"

        /** Foods in [current] that were not in [previous], counting repeats. */
        fun newFoods(previous: List<String>, current: List<String>): List<String> {
            val remaining = previous.groupingBy { it }.eachCount().toMutableMap()
            return current.filter { name ->
                val count = remaining[name] ?: 0
                if (count > 0) {
                    remaining[name] = count - 1
                    false
                } else {
                    true
                }
            }
        }

        private fun isAfter(a: String, b: String): Boolean = try {
            OffsetDateTime.parse(a).isAfter(OffsetDateTime.parse(b))
        } catch (e: DateTimeParseException) {
            a > b
        }

        private fun minusSeconds(timestamp: String, seconds: Long): String = try {
            OffsetDateTime.parse(timestamp).minusSeconds(seconds).toString()
        } catch (e: DateTimeParseException) {
            timestamp
        }
    }
}

class GroupActivityWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MacroTrackerApp).container
        try {
            container.groupNotifier.poll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Try again on the next period instead of retrying in a tight loop.
            Log.w("GroupActivityWorker", "Poll failed", e)
        }
        return Result.success()
    }
}
