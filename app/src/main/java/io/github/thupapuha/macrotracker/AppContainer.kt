package io.github.thupapuha.macrotracker

import android.content.Context
import io.github.thupapuha.macrotracker.core.ai.AiException
import io.github.thupapuha.macrotracker.core.ai.AiService
import io.github.thupapuha.macrotracker.core.ai.DirectGeminiTransport
import io.github.thupapuha.macrotracker.core.ai.GeminiClient
import io.github.thupapuha.macrotracker.core.ai.GeminiTransport
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import io.github.thupapuha.macrotracker.core.supabase.SupabaseClient
import io.github.thupapuha.macrotracker.core.supabase.SupabaseConfig
import io.github.thupapuha.macrotracker.data.FoodDatabase
import io.github.thupapuha.macrotracker.data.FoodRepository
import io.github.thupapuha.macrotracker.data.MediaReader
import io.github.thupapuha.macrotracker.data.SettingsStore
import io.github.thupapuha.macrotracker.data.SummarySync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container, created once in [MacroTrackerApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)
    val foodRepository = FoodRepository(FoodDatabase.build(context).foodDao())
    val media = MediaReader(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val supabase: SupabaseClient? =
        SupabaseConfig.of(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            ?.let { SupabaseClient(it, http, settings.sessionStore) }

    /** Null when the APK was built without Supabase settings (groups disabled). */
    val groupApi: GroupApi? = supabase?.let(::GroupApi)

    val serverConfigured: Boolean get() = supabase != null

    val ai = AiService(GeminiClient { geminiTransport() })

    val summarySync = SummarySync(appScope, foodRepository, settings, groupApi)

    /** A key typed in Perfil wins; otherwise requests go through the Supabase proxy. */
    private suspend fun geminiTransport(): GeminiTransport {
        val prefs = settings.current()
        if (prefs.ownGeminiKey.isNotBlank()) {
            return DirectGeminiTransport(http, prefs.ownGeminiKey, prefs.geminiModel.ifBlank { BuildConfig.GEMINI_MODEL })
        }
        return supabase?.geminiTransport() ?: throw AiException(
            "La IA no está configurada. Configura el servidor (ver README) o agrega tu propia clave de Gemini en Perfil.",
        )
    }
}
