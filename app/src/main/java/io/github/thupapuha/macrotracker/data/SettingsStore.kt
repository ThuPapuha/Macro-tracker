package io.github.thupapuha.macrotracker.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.thupapuha.macrotracker.core.gym.ExerciseLog
import io.github.thupapuha.macrotracker.core.gym.WeightGoal
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.net.AppJson
import io.github.thupapuha.macrotracker.core.nutrition.ActivityLevel
import io.github.thupapuha.macrotracker.core.nutrition.BodyProfile
import io.github.thupapuha.macrotracker.core.nutrition.Goal
import io.github.thupapuha.macrotracker.core.nutrition.Sex
import io.github.thupapuha.macrotracker.core.supabase.AuthSession
import io.github.thupapuha.macrotracker.core.supabase.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore(name = "settings")

// Saved on every set while training, so it lives apart from the settings everyone observes.
private val Context.workoutDraftStore by preferencesDataStore(name = "active_workout")

data class UserSettings(
    val targets: MacroTargets? = null,
    val displayName: String = "",
    val displayNameSynced: Boolean = false,
    val hasGroups: Boolean = false,
    val shareMeals: Boolean = true,
    val ownGeminiKey: String = "",
    val geminiModel: String = "",
    val bodyProfile: BodyProfile? = null,
    /** Group shown when the Grupos tab opens. */
    val lastGroupId: String = "",
    val notifyGroupActivity: Boolean = true,
    val notificationPermissionAsked: Boolean = false,
    /** Epoch millis of the last automatic backup to Downloads, 0 if never. */
    val lastBackupAt: Long = 0,
    val weightGoal: WeightGoal? = null,
    /** Show my weight and its goal to my groups. */
    val shareWeight: Boolean = true,
    val weightGoalSynced: Boolean = false,
    val creatineDays: Set<LocalDate> = emptySet(),
    val creatineDosesByDay: Map<LocalDate, Int> = emptyMap(),
    val creatineDoseGrams: Int = 5,
    val remindCreatine: Boolean = false,
    val creatineReminderMinute: Int = 20 * 60,
)

/** A workout in progress, kept so closing the app at the gym does not lose it. */
@Serializable
data class ActiveWorkout(
    val title: String,
    val routineId: String? = null,
    val date: String,
    val startedAt: Long,
    val exercises: List<ExerciseLog>,
    /** Section of the routine, saved with the workout. */
    val section: String? = null,
)

/** Last friends' days and comments already notified, so each change is announced once. */
@Serializable
data class ActivitySeen(
    val cursor: String? = null,
    /** "userId|day" -> last updated_at and food names seen. */
    val days: Map<String, SeenDay> = emptyMap(),
    val commentIds: List<String> = emptyList(),
    val workoutIds: List<String> = emptyList(),
    /** "groupId|postType|postId|authorId|emoji" of reactions to my posts. */
    val reactionKeys: List<String> = emptyList(),
)

@Serializable
data class SeenDay(val updatedAt: String, val foods: List<String>)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore
    private val draftStore = context.applicationContext.workoutDraftStore

    private object Keys {
        val targetCalories = doublePreferencesKey("target_calories")
        val targetProtein = doublePreferencesKey("target_protein")
        val targetCarbs = doublePreferencesKey("target_carbs")
        val targetFat = doublePreferencesKey("target_fat")
        val targetNote = stringPreferencesKey("target_note")
        val displayName = stringPreferencesKey("display_name")
        val displayNameSynced = booleanPreferencesKey("display_name_synced")
        val hasGroups = booleanPreferencesKey("has_groups")
        val shareMeals = booleanPreferencesKey("share_meals")
        val geminiKey = stringPreferencesKey("gemini_key")
        val geminiModel = stringPreferencesKey("gemini_model")
        val session = stringPreferencesKey("supabase_session")
        val bodySex = stringPreferencesKey("body_sex")
        val bodyAge = intPreferencesKey("body_age")
        val bodyWeight = doublePreferencesKey("body_weight")
        val bodyHeight = doublePreferencesKey("body_height")
        val bodyActivity = stringPreferencesKey("body_activity")
        val bodyGoal = stringPreferencesKey("body_goal")
        val lastGroupId = stringPreferencesKey("last_group_id")
        val notifyGroupActivity = booleanPreferencesKey("notify_group_activity")
        val notificationPermissionAsked = booleanPreferencesKey("notification_permission_asked")
        val activitySeen = stringPreferencesKey("activity_seen")
        val lastBackupAt = longPreferencesKey("last_backup_at")
        val weightStart = doublePreferencesKey("weight_start")
        val weightGoal = doublePreferencesKey("weight_goal")
        val shareWeight = booleanPreferencesKey("share_weight")
        val weightGoalSynced = booleanPreferencesKey("weight_goal_synced")
        val activeWorkout = stringPreferencesKey("active_workout")
        val creatineDays = stringSetPreferencesKey("creatine_days")
        val creatineDoses = stringSetPreferencesKey("creatine_doses")
        val creatineDoseGrams = intPreferencesKey("creatine_dose_grams")
        val remindCreatine = booleanPreferencesKey("remind_creatine")
        val creatineReminderMinute = intPreferencesKey("creatine_reminder_minute")
        val creatineReminderSentDay = stringPreferencesKey("creatine_reminder_sent_day")
    }

    private val preferences: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<UserSettings> = preferences.map { p ->
        UserSettings(
            targets = p[Keys.targetCalories]?.let { calories ->
                MacroTargets(
                    calories = calories,
                    protein = p[Keys.targetProtein] ?: 0.0,
                    carbs = p[Keys.targetCarbs] ?: 0.0,
                    fat = p[Keys.targetFat] ?: 0.0,
                    note = p[Keys.targetNote].orEmpty(),
                )
            },
            displayName = p[Keys.displayName].orEmpty(),
            displayNameSynced = p[Keys.displayNameSynced] ?: false,
            hasGroups = p[Keys.hasGroups] ?: false,
            shareMeals = p[Keys.shareMeals] ?: true,
            ownGeminiKey = p[Keys.geminiKey].orEmpty(),
            geminiModel = p[Keys.geminiModel].orEmpty(),
            bodyProfile = readBodyProfile(p),
            lastGroupId = p[Keys.lastGroupId].orEmpty(),
            notifyGroupActivity = p[Keys.notifyGroupActivity] ?: true,
            notificationPermissionAsked = p[Keys.notificationPermissionAsked] ?: false,
            lastBackupAt = p[Keys.lastBackupAt] ?: 0,
            weightGoal = p[Keys.weightGoal]?.let { goal -> WeightGoal(start = p[Keys.weightStart] ?: goal, goal = goal) },
            shareWeight = p[Keys.shareWeight] ?: true,
            weightGoalSynced = p[Keys.weightGoalSynced] ?: false,
            creatineDays = p[Keys.creatineDays].orEmpty().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet(),
            creatineDosesByDay = parseCreatineDoses(p[Keys.creatineDoses].orEmpty()),
            creatineDoseGrams = p[Keys.creatineDoseGrams]?.takeIf { it in listOf(3, 5, 10, 20) } ?: 5,
            remindCreatine = p[Keys.remindCreatine] ?: false,
            creatineReminderMinute = p[Keys.creatineReminderMinute]?.takeIf { it in 0 until 24 * 60 } ?: 20 * 60,
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun setTargets(targets: MacroTargets) {
        store.edit {
            it[Keys.targetCalories] = targets.calories
            it[Keys.targetProtein] = targets.protein
            it[Keys.targetCarbs] = targets.carbs
            it[Keys.targetFat] = targets.fat
            it[Keys.targetNote] = targets.note
        }
    }

    suspend fun setDisplayName(name: String, synced: Boolean) {
        store.edit {
            it[Keys.displayName] = name
            it[Keys.displayNameSynced] = synced
        }
    }

    suspend fun setHasGroups(value: Boolean) {
        store.edit { it[Keys.hasGroups] = value }
    }

    suspend fun setShareMeals(value: Boolean) {
        store.edit { it[Keys.shareMeals] = value }
    }

    suspend fun setOwnGemini(apiKey: String, model: String) {
        store.edit {
            it[Keys.geminiKey] = apiKey.trim()
            it[Keys.geminiModel] = model.trim()
        }
    }

    suspend fun setLastGroup(groupId: String) {
        store.edit { it[Keys.lastGroupId] = groupId }
    }

    /** After deleting the groups account: back to a phone-only install. */
    suspend fun forgetGroups() {
        store.edit {
            it[Keys.hasGroups] = false
            it[Keys.lastGroupId] = ""
            it[Keys.displayNameSynced] = false
            it[Keys.weightGoalSynced] = false
            it.remove(Keys.activitySeen)
        }
    }

    suspend fun setNotifyGroupActivity(value: Boolean) {
        store.edit { it[Keys.notifyGroupActivity] = value }
    }

    suspend fun setLastBackup(epochMillis: Long) {
        store.edit { it[Keys.lastBackupAt] = epochMillis }
    }

    suspend fun setWeightGoal(goal: WeightGoal?, share: Boolean) {
        store.edit {
            if (goal == null) {
                it.remove(Keys.weightGoal)
                it.remove(Keys.weightStart)
            } else {
                it[Keys.weightGoal] = goal.goal
                it[Keys.weightStart] = goal.start
            }
            it[Keys.shareWeight] = share
            it[Keys.weightGoalSynced] = false
        }
    }

    suspend fun setCreatineTaken(day: LocalDate, taken: Boolean, doseGrams: Int = 5) {
        require(!day.isAfter(LocalDate.now()))
        store.edit { preferences ->
            val days = preferences[Keys.creatineDays].orEmpty().toMutableSet()
            if (taken) days.add(day.toString()) else days.remove(day.toString())
            preferences[Keys.creatineDays] = days
            val doses = parseCreatineDoses(preferences[Keys.creatineDoses].orEmpty()).toMutableMap()
            if (taken) doses[day] = doseGrams.takeIf { it in listOf(3, 5, 10, 20) } ?: 5 else doses.remove(day)
            preferences[Keys.creatineDoses] = doses.mapTo(mutableSetOf()) { (date, grams) -> "$date|$grams" }
        }
    }

    suspend fun setCreatineDose(grams: Int) {
        require(grams in listOf(3, 5, 10, 20))
        store.edit { it[Keys.creatineDoseGrams] = grams }
    }

    suspend fun setRemindCreatine(enabled: Boolean) {
        store.edit { it[Keys.remindCreatine] = enabled }
    }

    suspend fun setCreatineReminderMinute(minute: Int) {
        require(minute in 0 until 24 * 60)
        store.edit { it[Keys.creatineReminderMinute] = minute }
    }

    suspend fun creatineReminderSentDay(): LocalDate? = preferences.first()[Keys.creatineReminderSentDay]
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    suspend fun markCreatineReminderSent(day: LocalDate) {
        store.edit { it[Keys.creatineReminderSentDay] = day.toString() }
    }

    suspend fun importCreatineDays(days: List<String>, importedDoses: Map<String, Int> = emptyMap()) {
        val valid = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .filterNot { it.isAfter(LocalDate.now()) }.toSet()
        if (valid.isEmpty()) return
        store.edit { preferences ->
            preferences[Keys.creatineDays] = preferences[Keys.creatineDays].orEmpty() + valid.map { it.toString() }
            val doses = parseCreatineDoses(preferences[Keys.creatineDoses].orEmpty()).toMutableMap()
            for (day in valid) {
                val grams = importedDoses[day.toString()]
                if (day !in doses && grams != null && grams in listOf(3, 5, 10, 20)) doses[day] = grams
            }
            preferences[Keys.creatineDoses] = doses.mapTo(mutableSetOf()) { (date, grams) -> "$date|$grams" }
        }
    }

    private fun parseCreatineDoses(values: Set<String>): Map<LocalDate, Int> = values.mapNotNull { entry ->
        val parts = entry.split('|')
        val day = parts.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val grams = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size == 2 && day != null && grams != null && grams in listOf(3, 5, 10, 20)) day to grams else null
    }.toMap()

    /** Marks the weight goal as uploaded, unless it changed while uploading. */
    suspend fun markWeightGoalSynced(uploaded: WeightGoal?, share: Boolean) {
        store.edit {
            val current = it[Keys.weightGoal]?.let { goal -> WeightGoal(it[Keys.weightStart] ?: goal, goal) }
            if (current == uploaded && (it[Keys.shareWeight] ?: true) == share) it[Keys.weightGoalSynced] = true
        }
    }

    suspend fun activeWorkout(): ActiveWorkout? = draftStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }.first()[Keys.activeWorkout]?.let {
        runCatching { AppJson.decodeFromString(ActiveWorkout.serializer(), it) }.getOrNull()
    }

    val hasActiveWorkout: Flow<Boolean> = draftStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[Keys.activeWorkout] != null }

    suspend fun setActiveWorkout(workout: ActiveWorkout?) {
        draftStore.edit {
            if (workout == null) {
                it.remove(Keys.activeWorkout)
            } else {
                it[Keys.activeWorkout] = AppJson.encodeToString(ActiveWorkout.serializer(), workout)
            }
        }
    }

    suspend fun setNotificationPermissionAsked() {
        store.edit { it[Keys.notificationPermissionAsked] = true }
    }

    suspend fun activitySeen(): ActivitySeen = preferences.first()[Keys.activitySeen]?.let {
        runCatching { AppJson.decodeFromString(ActivitySeen.serializer(), it) }.getOrNull()
    } ?: ActivitySeen()

    suspend fun setActivitySeen(seen: ActivitySeen) {
        store.edit { it[Keys.activitySeen] = AppJson.encodeToString(ActivitySeen.serializer(), seen) }
    }

    suspend fun setBodyProfile(profile: BodyProfile) {
        store.edit {
            it[Keys.bodySex] = profile.sex.name
            it[Keys.bodyAge] = profile.age
            it[Keys.bodyWeight] = profile.weightKg
            it[Keys.bodyHeight] = profile.heightCm
            it[Keys.bodyActivity] = profile.activity.name
            it[Keys.bodyGoal] = profile.goal.name
        }
    }

    private fun readBodyProfile(p: Preferences): BodyProfile? {
        val sex = p[Keys.bodySex]?.let { name -> Sex.entries.firstOrNull { it.name == name } } ?: return null
        return BodyProfile(
            sex = sex,
            age = p[Keys.bodyAge] ?: return null,
            weightKg = p[Keys.bodyWeight] ?: return null,
            heightCm = p[Keys.bodyHeight] ?: return null,
            activity = ActivityLevel.entries.firstOrNull { it.name == p[Keys.bodyActivity] } ?: ActivityLevel.MODERATE,
            goal = Goal.entries.firstOrNull { it.name == p[Keys.bodyGoal] } ?: Goal.MAINTAIN,
        )
    }

    /** Persists the anonymous Supabase session so the same user is kept across app restarts. */
    val sessionStore: SessionStore = object : SessionStore {
        override suspend fun load(): AuthSession? = preferences.first()[Keys.session]?.let {
            runCatching { AppJson.decodeFromString(AuthSession.serializer(), it) }.getOrNull()
        }

        override suspend fun save(session: AuthSession?) {
            store.edit {
                if (session == null) {
                    it.remove(Keys.session)
                } else {
                    it[Keys.session] = AppJson.encodeToString(AuthSession.serializer(), session)
                }
            }
        }
    }
}
