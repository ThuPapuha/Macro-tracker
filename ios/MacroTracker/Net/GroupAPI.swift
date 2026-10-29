import Foundation

struct GroupInfo: Decodable, Hashable, Identifiable {
    let id: String
    let name: String
    let inviteCode: String
    let ownerId: String
    let memberCount: Int

    enum CodingKeys: String, CodingKey {
        case id, name
        case inviteCode = "invite_code"
        case ownerId = "owner_id"
        case memberCount = "member_count"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = c.value(.name, "Grupo")
        inviteCode = c.value(.inviteCode, "")
        ownerId = c.value(.ownerId, "")
        memberCount = c.value(.memberCount, 1)
    }
}

struct GroupMember: Decodable, Hashable, Identifiable {
    let userId: String
    let displayName: String
    let isOwner: Bool
    /// Weights are nil when the member does not share them.
    let goalWeight: Double?
    let startWeight: Double?
    let currentWeight: Double?

    var id: String { userId }

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case displayName = "display_name"
        case isOwner = "is_owner"
        case goalWeight = "goal_weight"
        case startWeight = "start_weight"
        case currentWeight = "current_weight"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        userId = try c.decode(String.self, forKey: .userId)
        displayName = c.value(.displayName, "Anónimo")
        isOwner = c.value(.isOwner, false)
        goalWeight = c.optional(.goalWeight)
        startWeight = c.optional(.startWeight)
        currentWeight = c.optional(.currentWeight)
    }
}

struct SharedMeal: Codable, Hashable {
    var name: String
    var calories: Double = 0
    var meal: String = ""

    init(name: String, calories: Double, meal: String) {
        self.name = name
        self.calories = calories
        self.meal = meal
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = c.value(.name, "")
        calories = c.value(.calories, 0)
        meal = c.value(.meal, "")
    }
}

/// Totals and goals of a day, as shared with the groups.
struct NutritionData: Decodable, Hashable {
    let calories: Double
    let protein: Double
    let carbs: Double
    let fat: Double
    let targetCalories: Double?
    let targetProtein: Double?
    let targetCarbs: Double?
    let targetFat: Double?
    let meals: [SharedMeal]

    var totals: Macros { Macros(calories: calories, protein: protein, carbs: carbs, fat: fat) }

    var targets: MacroTargets? {
        guard let calories = targetCalories, calories > 0 else { return nil }
        return MacroTargets(calories: calories, protein: targetProtein ?? 0, carbs: targetCarbs ?? 0, fat: targetFat ?? 0)
    }

    enum CodingKeys: String, CodingKey {
        case calories, protein, carbs, fat, meals
        case targetCalories = "target_calories"
        case targetProtein = "target_protein"
        case targetCarbs = "target_carbs"
        case targetFat = "target_fat"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        calories = c.value(.calories, 0)
        protein = c.value(.protein, 0)
        carbs = c.value(.carbs, 0)
        fat = c.value(.fat, 0)
        targetCalories = c.optional(.targetCalories)
        targetProtein = c.optional(.targetProtein)
        targetCarbs = c.optional(.targetCarbs)
        targetFat = c.optional(.targetFat)
        meals = c.value(.meals, [])
    }
}

struct DailySummary: Decodable, Hashable {
    let userId: String
    let day: String
    let nutrition: NutritionData

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case day
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        userId = try c.decode(String.self, forKey: .userId)
        day = try c.decode(String.self, forKey: .day)
        nutrition = try NutritionData(from: decoder)
    }
}

/// Kinds of posts in a group's feed.
enum PostType {
    static let food = "food"
    static let workout = "workout"
    static let weight = "weight"
}

struct MealComment: Decodable, Hashable, Identifiable {
    let id: String
    let targetUser: String
    let day: String
    let mealName: String?
    let authorId: String
    let authorName: String
    let body: String
    let createdAt: String
    let postType: String
    /// The day for food and weight posts, the workout id for workouts.
    let postId: String?

    enum CodingKeys: String, CodingKey {
        case id, day, body
        case targetUser = "target_user"
        case mealName = "meal_name"
        case authorId = "author_id"
        case authorName = "author_name"
        case createdAt = "created_at"
        case postType = "post_type"
        case postId = "post_id"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        targetUser = c.value(.targetUser, "")
        day = c.value(.day, "")
        mealName = c.optional(.mealName)
        authorId = c.value(.authorId, "")
        authorName = c.value(.authorName, "Anónimo")
        body = c.value(.body, "")
        createdAt = c.value(.createdAt, "")
        postType = c.value(.postType, PostType.food)
        postId = c.optional(.postId)
    }
}

struct WorkoutPostData: Decodable, Hashable {
    let title: String
    let exercises: [ExerciseLog]
    let prCount: Int
    let durationMin: Int?
    /// Section of the routine (Hipertrofia, Fuerza…), if any.
    let category: String?

    enum CodingKeys: String, CodingKey {
        case title, exercises, category
        case prCount = "pr_count"
        case durationMin = "duration_min"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = c.value(.title, "Entrenamiento")
        exercises = try c.decodeIfPresent([ExerciseLog].self, forKey: .exercises) ?? []
        prCount = c.value(.prCount, 0)
        durationMin = c.optional(.durationMin)
        category = c.optional(.category)
    }
}

struct WeightPostData: Decodable, Hashable {
    let kg: Double
    let goal: Double?
    let start: Double?
    let previous: Double?
    let fat: Double?
    let muscle: Double?
    let bone: Double?
    let previousFat: Double?
    let previousMuscle: Double?

    enum CodingKeys: String, CodingKey {
        case kg, goal, start, previous, fat, muscle, bone
        case previousFat = "previous_fat"
        case previousMuscle = "previous_muscle"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kg = try c.decode(Double.self, forKey: .kg)
        goal = c.optional(.goal)
        start = c.optional(.start)
        previous = c.optional(.previous)
        fat = c.optional(.fat)
        muscle = c.optional(.muscle)
        bone = c.optional(.bone)
        previousFat = c.optional(.previousFat)
        previousMuscle = c.optional(.previousMuscle)
    }
}

/// One post of a group's feed (see `group_feed` in schema.sql). Exactly one of the data fields is set.
struct FeedPost: Decodable, Hashable, Identifiable {
    let type: String
    let postId: String
    let userId: String
    let day: String
    let at: String
    let displayName: String
    var reactions: [String: Int]
    var myReaction: String?
    var commentCount: Int
    let food: NutritionData?
    let workout: WorkoutPostData?
    let weight: WeightPostData?

    var id: String { "\(type)|\(userId)|\(postId)" }

    enum CodingKeys: String, CodingKey {
        case type = "post_type"
        case postId = "post_id"
        case userId = "user_id"
        case day, at, data, reactions
        case displayName = "display_name"
        case myReaction = "my_reaction"
        case commentCount = "comment_count"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = try c.decode(String.self, forKey: .type)
        postId = try c.decode(String.self, forKey: .postId)
        userId = try c.decode(String.self, forKey: .userId)
        day = c.value(.day, "")
        at = c.value(.at, "")
        displayName = c.value(.displayName, "Anónimo")
        reactions = c.value(.reactions, [String: Int]()).filter { $0.value > 0 }
        myReaction = c.optional(.myReaction)
        commentCount = c.value(.commentCount, 0)
        switch type {
        case PostType.food:
            food = try c.decode(NutritionData.self, forKey: .data)
            workout = nil
            weight = nil
        case PostType.workout:
            food = nil
            workout = try c.decode(WorkoutPostData.self, forKey: .data)
            weight = nil
        case PostType.weight:
            food = nil
            workout = nil
            weight = try c.decode(WeightPostData.self, forKey: .data)
        default:
            throw DecodingError.dataCorruptedError(forKey: .type, in: c, debugDescription: "Tipo de publicación desconocido")
        }
    }
}

struct WeightPoint: Decodable, Hashable {
    let day: String
    let kg: Double
    let fat: Double?
    let muscle: Double?
    let bone: Double?

    var log: BodyLog { BodyLog(day: day, kg: kg, fatPercent: fat, musclePercent: muscle, boneKg: bone) }
}

struct MemberWorkout: Decodable, Hashable, Identifiable {
    let id: String
    let day: String
    let title: String
    let exercises: [ExerciseLog]
    let prCount: Int
    let durationMin: Int?
    let category: String?

    enum CodingKeys: String, CodingKey {
        case id, day, title, exercises, category
        case prCount = "pr_count"
        case durationMin = "duration_min"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        day = c.value(.day, "")
        title = c.value(.title, "Entrenamiento")
        exercises = try c.decodeIfPresent([ExerciseLog].self, forKey: .exercises) ?? []
        prCount = c.value(.prCount, 0)
        durationMin = c.optional(.durationMin)
        category = c.optional(.category)
    }
}

/// A member's gym activity in a period (see `group_gym_stats`).
struct GymStats: Decodable, Hashable {
    let userId: String
    let workouts: Int
    let prs: Int
    let volume: Double

    enum CodingKeys: String, CodingKey {
        case userId = "user_id"
        case workouts, prs, volume
    }

    init(userId: String) {
        self.userId = userId
        workouts = 0
        prs = 0
        volume = 0
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        userId = try c.decode(String.self, forKey: .userId)
        workouts = c.value(.workouts, 0)
        prs = c.value(.prs, 0)
        volume = c.value(.volume, 0)
    }
}

/// A member's shared weight history and recent workouts.
struct MemberProgress: Decodable {
    let shareWeight: Bool
    let goalWeight: Double?
    let startWeight: Double?
    let weights: [WeightPoint]
    let workouts: [MemberWorkout]

    enum CodingKeys: String, CodingKey {
        case shareWeight = "share_weight"
        case goalWeight = "goal_weight"
        case startWeight = "start_weight"
        case weights, workouts
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        shareWeight = c.value(.shareWeight, true)
        goalWeight = c.optional(.goalWeight)
        startWeight = c.optional(.startWeight)
        weights = c.value(.weights, [Lossy<WeightPoint>]()).compactMap(\.value)
        workouts = c.value(.workouts, [Lossy<MemberWorkout>]()).compactMap(\.value)
    }
}

/// What changed in my groups since the last poll (see `group_activity`).
struct GroupActivity: Decodable {
    struct Item: Decodable {
        let userId: String
        let displayName: String
        let groupId: String
        let groupName: String
        let day: String
        let calories: Double
        let meals: [SharedMeal]
        let updatedAt: String

        enum CodingKeys: String, CodingKey {
            case userId = "user_id"
            case displayName = "display_name"
            case groupId = "group_id"
            case groupName = "group_name"
            case day, calories, meals
            case updatedAt = "updated_at"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            userId = try c.decode(String.self, forKey: .userId)
            displayName = c.value(.displayName, "Anónimo")
            groupId = try c.decode(String.self, forKey: .groupId)
            groupName = c.value(.groupName, "")
            day = c.value(.day, "")
            calories = c.value(.calories, 0)
            meals = c.value(.meals, [])
            updatedAt = c.value(.updatedAt, "")
        }
    }

    struct Comment: Decodable {
        let id: String
        let groupId: String
        let groupName: String
        let mealName: String?
        let body: String
        let authorName: String
        let postType: String

        enum CodingKeys: String, CodingKey {
            case id, body
            case groupId = "group_id"
            case groupName = "group_name"
            case mealName = "meal_name"
            case authorName = "author_name"
            case postType = "post_type"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            groupId = try c.decode(String.self, forKey: .groupId)
            groupName = c.value(.groupName, "")
            mealName = c.optional(.mealName)
            body = c.value(.body, "")
            authorName = c.value(.authorName, "Anónimo")
            postType = c.value(.postType, PostType.food)
        }
    }

    struct Workout: Decodable {
        let id: String
        let displayName: String
        let groupId: String
        let groupName: String
        let title: String
        let exercises: [ExerciseLog]
        let category: String?

        enum CodingKeys: String, CodingKey {
            case id, title, exercises, category
            case displayName = "display_name"
            case groupId = "group_id"
            case groupName = "group_name"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decode(String.self, forKey: .id)
            displayName = c.value(.displayName, "Anónimo")
            groupId = try c.decode(String.self, forKey: .groupId)
            groupName = c.value(.groupName, "")
            title = c.value(.title, "Entrenamiento")
            exercises = c.value(.exercises, [])
            category = c.optional(.category)
        }
    }

    struct Reaction: Decodable {
        let groupId: String
        let groupName: String
        let postType: String
        let postId: String
        let emoji: String
        let authorId: String
        let authorName: String

        enum CodingKeys: String, CodingKey {
            case emoji
            case groupId = "group_id"
            case groupName = "group_name"
            case postType = "post_type"
            case postId = "post_id"
            case authorId = "author_id"
            case authorName = "author_name"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            groupId = try c.decode(String.self, forKey: .groupId)
            groupName = c.value(.groupName, "")
            postType = c.value(.postType, PostType.food)
            postId = c.value(.postId, "")
            emoji = c.value(.emoji, "👍")
            authorId = c.value(.authorId, "")
            authorName = c.value(.authorName, "Anónimo")
        }
    }

    let now: String
    let items: [Item]
    let comments: [Comment]
    let workouts: [Workout]
    let reactions: [Reaction]

    init(from decoder: Decoder) throws {
        enum Keys: String, CodingKey { case now, items, comments, workouts, reactions }
        let c = try decoder.container(keyedBy: Keys.self)
        now = try c.decode(String.self, forKey: .now)
        items = c.value(.items, [Lossy<Item>]()).compactMap(\.value)
        comments = c.value(.comments, [Lossy<Comment>]()).compactMap(\.value)
        workouts = c.value(.workouts, [Lossy<Workout>]()).compactMap(\.value)
        reactions = c.value(.reactions, [Lossy<Reaction>]()).compactMap(\.value)
    }
}

/// Group features, implemented by the SQL functions in `supabase/schema.sql`.
struct GroupAPI {
    let client: SupabaseClient

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw BackendError(message: "Respuesta inesperada del servidor")
        }
    }

    private func list<T: Decodable>(_ type: T.Type, _ data: Data) throws -> [T] {
        try decode([Lossy<T>].self, data).compactMap(\.value)
    }

    func currentUserId() async throws -> String { try await client.userId() }

    func setDisplayName(_ name: String) async throws {
        _ = try await client.rpc("set_profile", ["p_display_name": .string(name)])
    }

    func myGroups() async throws -> [GroupInfo] { try list(GroupInfo.self, try await client.rpc("my_groups")) }

    func createGroup(_ name: String) async throws -> GroupInfo {
        try decode(GroupInfo.self, try await client.rpc("create_group", ["p_name": .string(name)]))
    }

    func joinGroup(_ code: String) async throws -> GroupInfo {
        try decode(GroupInfo.self, try await client.rpc("join_group", ["p_code": .string(code)]))
    }

    func leaveGroup(_ groupId: String) async throws {
        _ = try await client.rpc("leave_group", ["p_group": .string(groupId)])
    }

    func members(_ groupId: String) async throws -> [GroupMember] {
        try list(GroupMember.self, try await client.rpc("group_members_list", ["p_group": .string(groupId)]))
    }

    func summaries(_ groupId: String, from: Day, to: Day) async throws -> [DailySummary] {
        try list(DailySummary.self, try await client.rpc("group_summaries", [
            "p_group": .string(groupId), "p_from": .string(from.iso), "p_to": .string(to.iso),
        ]))
    }

    func upsertSummary(day: Day, totals: Macros, targets: MacroTargets?, meals: [SharedMeal]) async throws {
        _ = try await client.rpc("upsert_daily_summary", [
            "p_day": .string(day.iso),
            "p_calories": .double(totals.calories),
            "p_protein": .double(totals.protein),
            "p_carbs": .double(totals.carbs),
            "p_fat": .double(totals.fat),
            "p_target_calories": .of(targets?.calories),
            "p_target_protein": .of(targets?.protein),
            "p_target_carbs": .of(targets?.carbs),
            "p_target_fat": .of(targets?.fat),
            "p_meals": .encoded(Array(meals.prefix(40))),
        ])
    }

    func comments(_ groupId: String, from: Day, to: Day) async throws -> [MealComment] {
        try list(MealComment.self, try await client.rpc("group_comments", [
            "p_group": .string(groupId), "p_from": .string(from.iso), "p_to": .string(to.iso),
        ]))
    }

    /// [postId] is the workout id for workouts; for food and weight posts the day is used.
    func addComment(groupId: String, targetUser: String, day: String, mealName: String?, body: String,
                    postType: String = PostType.food, postId: String? = nil) async throws {
        _ = try await client.rpc("add_meal_comment", [
            "p_group": .string(groupId),
            "p_target": .string(targetUser),
            "p_day": .string(day),
            "p_meal": .of(mealName),
            "p_body": .string(body),
            "p_post_type": .string(postType),
            "p_post_id": .string(postId ?? day),
        ])
    }

    func postComments(groupId: String, targetUser: String, postType: String, postId: String) async throws -> [MealComment] {
        try list(MealComment.self, try await client.rpc("post_comments", [
            "p_group": .string(groupId), "p_target": .string(targetUser),
            "p_post_type": .string(postType), "p_post_id": .string(postId),
        ]))
    }

    func deleteComment(_ id: String) async throws {
        _ = try await client.rpc("delete_meal_comment", ["p_id": .string(id)])
    }

    /// Newest posts first; pass the [FeedPost.at] of the last post as [before] to load older ones.
    func feed(_ groupId: String, before: String? = nil, limit: Int = 30) async throws -> [FeedPost] {
        try list(FeedPost.self, try await client.rpc("group_feed", [
            "p_group": .string(groupId), "p_before": .of(before), "p_limit": .int(limit),
        ]))
    }

    /// Toggles my reaction; returns the emoji now set, or nil if it was removed.
    func react(groupId: String, targetUser: String, postType: String, postId: String, emoji: String) async throws -> String? {
        struct Result: Decodable { let emoji: String? }
        return try decode(Result.self, try await client.rpc("react", [
            "p_group": .string(groupId), "p_target": .string(targetUser),
            "p_post_type": .string(postType), "p_post_id": .string(postId), "p_emoji": .string(emoji),
        ])).emoji
    }

    func memberProgress(groupId: String, userId: String) async throws -> MemberProgress {
        try decode(MemberProgress.self, try await client.rpc("member_progress", [
            "p_group": .string(groupId), "p_user": .string(userId),
        ]))
    }

    func gymStats(_ groupId: String, from: Day) async throws -> [GymStats] {
        try list(GymStats.self, try await client.rpc("group_gym_stats", [
            "p_group": .string(groupId), "p_from": .string(from.iso),
        ]))
    }

    func setWeightGoal(goal: Double?, start: Double?, share: Bool) async throws {
        _ = try await client.rpc("set_weight_goal", ["p_goal": .of(goal), "p_start": .of(start), "p_share": .bool(share)])
    }

    /// Sends the whole day: a nil body composition value clears it on the server.
    func upsertWeight(day: String, kg: Double, fat: Double?, muscle: Double?, bone: Double?) async throws {
        _ = try await client.rpc("upsert_weight", [
            "p_day": .string(day), "p_kg": .double(kg), "p_fat": .of(fat), "p_muscle": .of(muscle), "p_bone": .of(bone),
        ])
    }

    func deleteWeight(day: String) async throws {
        _ = try await client.rpc("delete_weight", ["p_day": .string(day)])
    }

    func upsertWorkout(id: String, day: String, title: String, exercises: [ExerciseLog], prCount: Int,
                       durationMin: Int?, performedAt: String?, category: String?) async throws {
        _ = try await client.rpc("upsert_workout", [
            "p_id": .string(id),
            "p_day": .string(day),
            "p_title": .string(title),
            "p_exercises": .encoded(exercises),
            "p_pr_count": .int(prCount),
            "p_duration_min": .of(durationMin),
            "p_performed_at": .of(performedAt),
            "p_category": .of(category),
        ])
    }

    func deleteWorkout(_ id: String) async throws {
        _ = try await client.rpc("delete_workout", ["p_id": .string(id)])
    }

    /// Pass nil the first time to get only the server time to use as the next [since].
    func activity(since: String?) async throws -> GroupActivity {
        try decode(GroupActivity.self, try await client.rpc("group_activity", ["p_since": .of(since)]))
    }

    /// Deletes my anonymous account and everything I shared (see `delete_my_account`).
    func deleteMyAccount() async throws {
        _ = try await client.rpc("delete_my_account")
        await client.clearSession()
    }
}
