package io.github.thupapuha.macrotracker.core.supabase

import io.github.thupapuha.macrotracker.core.gym.ExerciseLog
import io.github.thupapuha.macrotracker.core.model.MacroTargets
import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.net.AppJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
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
    /** Weights are null when the member does not share them. */
    @SerialName("goal_weight") val goalWeight: Double? = null,
    @SerialName("start_weight") val startWeight: Double? = null,
    @SerialName("current_weight") val currentWeight: Double? = null,
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
    @SerialName("post_type") val postType: String = PostType.FOOD,
    /** The day for food and weight posts, the workout id for workouts. */
    @SerialName("post_id") val postId: String? = null,
)

/** Kinds of posts in a group's feed. */
object PostType {
    const val FOOD = "food"
    const val WORKOUT = "workout"
    const val WEIGHT = "weight"
}

@Serializable
data class FoodPostData(
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
data class WorkoutPostData(
    val title: String = "Entrenamiento",
    val exercises: List<ExerciseLog> = emptyList(),
    @SerialName("pr_count") val prCount: Int = 0,
    @SerialName("duration_min") val durationMin: Int? = null,
)

@Serializable
data class WeightPostData(
    val kg: Double,
    val goal: Double? = null,
    val start: Double? = null,
    val previous: Double? = null,
)

@Serializable
private data class RawFeedPost(
    @SerialName("post_type") val type: String,
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    val day: String,
    val at: String,
    val data: JsonElement? = null,
    @SerialName("display_name") val displayName: String = "Anónimo",
    val reactions: Map<String, Int> = emptyMap(),
    @SerialName("my_reaction") val myReaction: String? = null,
    @SerialName("comment_count") val commentCount: Int = 0,
)

/** One post of a group's feed (see `group_feed` in schema.sql). Exactly one of the data fields is set. */
data class FeedPost(
    val type: String,
    val postId: String,
    val userId: String,
    val day: String,
    val at: String,
    val displayName: String,
    val reactions: Map<String, Int>,
    val myReaction: String?,
    val commentCount: Int,
    val food: FoodPostData? = null,
    val workout: WorkoutPostData? = null,
    val weight: WeightPostData? = null,
) {
    val key: String get() = "$type|$userId|$postId"
}

@Serializable
data class WeightPoint(val day: String, val kg: Double)

@Serializable
data class MemberWorkout(
    val id: String,
    val day: String,
    val title: String = "Entrenamiento",
    val exercises: List<ExerciseLog> = emptyList(),
    @SerialName("pr_count") val prCount: Int = 0,
    @SerialName("duration_min") val durationMin: Int? = null,
    val at: String = "",
)

/** A member's gym activity in a period (see `group_gym_stats`). */
@Serializable
data class GymStats(
    @SerialName("user_id") val userId: String,
    val workouts: Int = 0,
    val prs: Int = 0,
    val volume: Double = 0.0,
)

/** A member's shared weight history and recent workouts. */
@Serializable
data class MemberProgress(
    @SerialName("share_weight") val shareWeight: Boolean = true,
    @SerialName("goal_weight") val goalWeight: Double? = null,
    @SerialName("start_weight") val startWeight: Double? = null,
    val weights: List<WeightPoint> = emptyList(),
    val workouts: List<MemberWorkout> = emptyList(),
)

/** What changed in my groups since the last poll (see `group_activity` in schema.sql). */
@Serializable
data class GroupActivity(
    val now: String,
    val items: List<ActivityItem> = emptyList(),
    val comments: List<ActivityComment> = emptyList(),
    val workouts: List<ActivityWorkout> = emptyList(),
    val reactions: List<ActivityReaction> = emptyList(),
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
    @SerialName("post_type") val postType: String = PostType.FOOD,
    @SerialName("post_id") val postId: String? = null,
)

/** A friend finished a workout. */
@Serializable
data class ActivityWorkout(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String = "Anónimo",
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String = "",
    val title: String = "Entrenamiento",
    @SerialName("pr_count") val prCount: Int = 0,
    val exercises: List<ExerciseLog> = emptyList(),
    @SerialName("created_at") val createdAt: String,
)

/** Someone reacted to one of my posts. */
@Serializable
data class ActivityReaction(
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String = "",
    @SerialName("post_type") val postType: String = PostType.FOOD,
    @SerialName("post_id") val postId: String,
    val emoji: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_name") val authorName: String = "Anónimo",
)

@Serializable
private data class ReactionResult(val emoji: String? = null)

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

    /** [postId] is the workout id for workouts; for food and weight posts the day is used. */
    suspend fun addComment(
        groupId: String,
        targetUser: String,
        day: LocalDate,
        mealName: String?,
        body: String,
        postType: String = PostType.FOOD,
        postId: String? = null,
    ) {
        client.rpc(
            "add_meal_comment",
            buildJsonObject {
                put("p_group", groupId)
                put("p_target", targetUser)
                put("p_day", day.toString())
                put("p_meal", mealName)
                put("p_body", body)
                put("p_post_type", postType)
                put("p_post_id", postId ?: day.toString())
            },
        )
    }

    suspend fun postComments(groupId: String, targetUser: String, postType: String, postId: String): List<MealComment> =
        decode(
            ListSerializer(MealComment.serializer()),
            client.rpc(
                "post_comments",
                buildJsonObject {
                    put("p_group", groupId)
                    put("p_target", targetUser)
                    put("p_post_type", postType)
                    put("p_post_id", postId)
                },
            ),
        )

    /** Newest posts first; pass the [FeedPost.at] of the last post as [before] to load older ones. */
    suspend fun feed(groupId: String, before: String? = null, limit: Int = 30): List<FeedPost> {
        val raw = decode(
            ListSerializer(RawFeedPost.serializer()),
            client.rpc(
                "group_feed",
                buildJsonObject {
                    put("p_group", groupId)
                    put("p_before", before)
                    put("p_limit", limit)
                },
            ),
        )
        return raw.mapNotNull(::toFeedPost)
    }

    /** Toggles my reaction; returns the emoji now set, or null if it was removed. */
    suspend fun react(groupId: String, targetUser: String, postType: String, postId: String, emoji: String): String? =
        decode(
            ReactionResult.serializer(),
            client.rpc(
                "react",
                buildJsonObject {
                    put("p_group", groupId)
                    put("p_target", targetUser)
                    put("p_post_type", postType)
                    put("p_post_id", postId)
                    put("p_emoji", emoji)
                },
            ),
        ).emoji

    suspend fun memberProgress(groupId: String, userId: String): MemberProgress = decode(
        MemberProgress.serializer(),
        client.rpc(
            "member_progress",
            buildJsonObject {
                put("p_group", groupId)
                put("p_user", userId)
            },
        ),
    )

    suspend fun gymStats(groupId: String, from: LocalDate): List<GymStats> = decode(
        ListSerializer(GymStats.serializer()),
        client.rpc(
            "group_gym_stats",
            buildJsonObject {
                put("p_group", groupId)
                put("p_from", from.toString())
            },
        ),
    )

    suspend fun setWeightGoal(goal: Double?, start: Double?, share: Boolean) {
        client.rpc(
            "set_weight_goal",
            buildJsonObject {
                put("p_goal", goal)
                put("p_start", start)
                put("p_share", share)
            },
        )
    }

    suspend fun upsertWeight(day: LocalDate, kg: Double) {
        client.rpc(
            "upsert_weight",
            buildJsonObject {
                put("p_day", day.toString())
                put("p_kg", kg)
            },
        )
    }

    suspend fun deleteWeight(day: LocalDate) {
        client.rpc("delete_weight", buildJsonObject { put("p_day", day.toString()) })
    }

    suspend fun upsertWorkout(
        id: String,
        day: LocalDate,
        title: String,
        exercises: List<ExerciseLog>,
        prCount: Int,
        durationMin: Int?,
        performedAt: String?,
    ) {
        client.rpc(
            "upsert_workout",
            buildJsonObject {
                put("p_id", id)
                put("p_day", day.toString())
                put("p_title", title)
                put("p_exercises", AppJson.encodeToJsonElement(ListSerializer(ExerciseLog.serializer()), exercises))
                put("p_pr_count", prCount)
                put("p_duration_min", durationMin)
                put("p_performed_at", performedAt)
            },
        )
    }

    suspend fun deleteWorkout(id: String) {
        client.rpc("delete_workout", buildJsonObject { put("p_id", id) })
    }

    suspend fun deleteComment(commentId: String) {
        client.rpc("delete_meal_comment", buildJsonObject { put("p_id", commentId) })
    }

    /** Pass null the first time to get only the server time to use as the next [since]. */
    suspend fun activity(since: String?): GroupActivity = decode(
        GroupActivity.serializer(),
        client.rpc("group_activity", buildJsonObject { put("p_since", since) }),
    )

    /** Posts whose data cannot be read (e.g. a newer post type) are skipped. */
    private fun toFeedPost(raw: RawFeedPost): FeedPost? {
        val data = raw.data ?: return null
        val post = FeedPost(
            type = raw.type,
            postId = raw.postId,
            userId = raw.userId,
            day = raw.day,
            at = raw.at,
            displayName = raw.displayName,
            reactions = raw.reactions.filterValues { it > 0 },
            myReaction = raw.myReaction,
            commentCount = raw.commentCount,
        )
        return try {
            when (raw.type) {
                PostType.FOOD -> post.copy(food = AppJson.decodeFromJsonElement(FoodPostData.serializer(), data))
                PostType.WORKOUT -> post.copy(workout = AppJson.decodeFromJsonElement(WorkoutPostData.serializer(), data))
                PostType.WEIGHT -> post.copy(weight = AppJson.decodeFromJsonElement(WeightPostData.serializer(), data))
                else -> null
            }
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

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
