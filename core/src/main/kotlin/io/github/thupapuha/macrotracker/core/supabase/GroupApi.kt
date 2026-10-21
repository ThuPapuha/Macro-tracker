package io.github.thupapuha.macrotracker.core.supabase

import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.net.AppJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

@Serializable
data class GroupInfo(
    val id: String,
    val name: String,
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("member_count") val memberCount: Int = 1,
)

@Serializable
data class GroupMember(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String = "Anónimo",
    @SerialName("is_owner") val isOwner: Boolean = false,
)

@Serializable
data class SharedMeal(
    val name: String,
    val calories: Double = 0.0,
    val meal: String = "",
)

@Serializable
data class DailySummary(
    @SerialName("user_id") val userId: String,
    val day: String,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    @SerialName("target_calories") val targetCalories: Double? = null,
    @SerialName("target_protein") val targetProtein: Double? = null,
    @SerialName("target_carbs") val targetCarbs: Double? = null,
    @SerialName("target_fat") val targetFat: Double? = null,
    val meals: List<SharedMeal> = emptyList(),
) {
    val totals: Macros get() = Macros(calories, protein, carbs, fat)

    val targets: MacroTargets?
        get() = targetCalories?.takeIf { it > 0 }?.let {
            MacroTargets(it, targetProtein ?: 0.0, targetCarbs ?: 0.0, targetFat ?: 0.0)
        }
}

@Serializable
data class MealComment(
    val id: String,
    @SerialName("target_user") val targetUser: String,
    val day: String,
    @SerialName("meal_name") val mealName: String? = null,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_name") val authorName: String = "Anónimo",
    val body: String,
    @SerialName("created_at") val createdAt: String,
)

/** What changed in my groups since the last poll (see `group_activity` in schema.sql). */
@Serializable
data class GroupActivity(
    val now: String,
    val items: List<ActivityItem> = emptyList(),
    val comments: List<ActivityComment> = emptyList(),
)

/** A friend's day whose food changed. */
@Serializable
data class ActivityItem(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String = "Anónimo",
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String = "",
    val day: String,
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val meals: List<SharedMeal> = emptyList(),
    @SerialName("updated_at") val updatedAt: String,
) {
    val totals: Macros get() = Macros(calories, protein, carbs, fat)
}

/** Someone commented on my food. */
@Serializable
data class ActivityComment(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String = "",
    val day: String,
    @SerialName("meal_name") val mealName: String? = null,
    val body: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("author_name") val authorName: String = "Anónimo",
)

/** Group features, implemented by the SQL functions in `supabase/schema.sql`. */
class GroupApi(private val client: SupabaseClient) {

    suspend fun currentUserId(): String = client.session().userId

    suspend fun setDisplayName(name: String) {
        client.rpc("set_profile", buildJsonObject { put("p_display_name", name) })
    }

    suspend fun myGroups(): List<GroupInfo> =
        decode(ListSerializer(GroupInfo.serializer()), client.rpc("my_groups"))

    suspend fun createGroup(name: String): GroupInfo =
        decode(GroupInfo.serializer(), client.rpc("create_group", buildJsonObject { put("p_name", name) }))

    suspend fun joinGroup(code: String): GroupInfo =
        decode(GroupInfo.serializer(), client.rpc("join_group", buildJsonObject { put("p_code", code) }))

    suspend fun leaveGroup(groupId: String) {
        client.rpc("leave_group", buildJsonObject { put("p_group", groupId) })
    }

    suspend fun members(groupId: String): List<GroupMember> = decode(
        ListSerializer(GroupMember.serializer()),
        client.rpc("group_members_list", buildJsonObject { put("p_group", groupId) }),
    )

    suspend fun summaries(groupId: String, from: LocalDate, to: LocalDate): List<DailySummary> = decode(
        ListSerializer(DailySummary.serializer()),
        client.rpc(
            "group_summaries",
            buildJsonObject {
                put("p_group", groupId)
                put("p_from", from.toString())
                put("p_to", to.toString())
            },
        ),
    )

    suspend fun upsertSummary(day: LocalDate, totals: Macros, targets: MacroTargets?, meals: List<SharedMeal>) {
        client.rpc("upsert_daily_summary", summaryArgs(day, totals, targets, meals))
    }

    suspend fun comments(groupId: String, from: LocalDate, to: LocalDate): List<MealComment> = decode(
        ListSerializer(MealComment.serializer()),
        client.rpc(
            "group_comments",
            buildJsonObject {
                put("p_group", groupId)
                put("p_from", from.toString())
                put("p_to", to.toString())
            },
        ),
    )

    suspend fun addComment(groupId: String, targetUser: String, day: LocalDate, mealName: String?, body: String) {
        client.rpc(
            "add_meal_comment",
            buildJsonObject {
                put("p_group", groupId)
                put("p_target", targetUser)
                put("p_day", day.toString())
                put("p_meal", mealName)
                put("p_body", body)
            },
        )
    }

    suspend fun deleteComment(commentId: String) {
        client.rpc("delete_meal_comment", buildJsonObject { put("p_id", commentId) })
    }

    /** Pass null the first time to get only the server time to use as the next [since]. */
    suspend fun activity(since: String?): GroupActivity = decode(
        GroupActivity.serializer(),
        client.rpc("group_activity", buildJsonObject { put("p_since", since) }),
    )

    private fun <T> decode(serializer: KSerializer<T>, body: String): T = try {
        AppJson.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw BackendException("Respuesta inesperada del servidor", cause = e)
    } catch (e: IllegalArgumentException) {
        throw BackendException("Respuesta inesperada del servidor", cause = e)
    }

    companion object {
        fun summaryArgs(day: LocalDate, totals: Macros, targets: MacroTargets?, meals: List<SharedMeal>): JsonObject =
            buildJsonObject {
                put("p_day", day.toString())
                put("p_calories", totals.calories)
                put("p_protein", totals.protein)
                put("p_carbs", totals.carbs)
                put("p_fat", totals.fat)
                put("p_target_calories", targets?.calories)
                put("p_target_protein", targets?.protein)
                put("p_target_carbs", targets?.carbs)
                put("p_target_fat", targets?.fat)
                put("p_meals", AppJson.encodeToJsonElement(ListSerializer(SharedMeal.serializer()), meals.take(40)))
            }
    }
}
