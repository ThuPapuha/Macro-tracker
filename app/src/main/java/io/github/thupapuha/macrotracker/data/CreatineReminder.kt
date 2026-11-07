package io.github.thupapuha.macrotracker.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.thupapuha.macrotracker.MainActivity
import io.github.thupapuha.macrotracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** A local daily check, scheduled near the user's chosen time by WorkManager. */
class CreatineReminder(context: Context, private val settings: SettingsStore, private val scope: CoroutineScope) {
    private val context = context.applicationContext

    fun start() {
        scope.launch {
            settings.settings.map { it.remindCreatine to it.creatineReminderMinute }
                .distinctUntilChanged()
                .collect { (enabled, minute) ->
                    val work = WorkManager.getInstance(context)
                    if (!enabled) {
                        work.cancelUniqueWork(WORK_NAME)
                    } else {
                        val now = LocalDateTime.now()
                        var next = LocalDate.now().atTime(LocalTime.of(minute / 60, minute % 60))
                        if (!next.isAfter(now)) next = next.plusDays(1)
                        val request = PeriodicWorkRequestBuilder<CreatineReminderWorker>(1, TimeUnit.DAYS)
                            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                            .build()
                        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request)
                    }
                }
        }
    }

    private companion object { const val WORK_NAME = "creatine-reminder" }
}

class CreatineReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = SettingsStore(context)
        val prefs = settings.current()
        val today = LocalDate.now()
        if (!prefs.remindCreatine || today in prefs.creatineDays || settings.creatineReminderSentDay() == today) {
            return Result.success()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return Result.success()
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Recordatorio de creatina")
                .build(),
        )
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("¿Ya tomaste tu creatina?")
            .setContentText("Registra la toma de hoy en Progreso.")
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            settings.markCreatineReminderSent(today)
        } catch (_: SecurityException) {
            // Notifications can be disabled between the permission check and delivery.
        }
        return Result.success()
    }

    private companion object {
        const val CHANNEL_ID = "creatine-reminder"
        const val NOTIFICATION_ID = 2031
    }
}
