package io.github.thupapuha.macrotracker.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "settings")

data class UserSettings(
    val targets: MacroTargets? = null,
    val displayName: String = "",
    val displayNameSynced: Boolean = false,
    val hasGroups: Boolean = false,
    val shareMeals: Boolean = true,
    val ownGeminiKey: String = "",
    val geminiModel: String = "",
    val bodyProfile: BodyProfile? = null,
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

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
