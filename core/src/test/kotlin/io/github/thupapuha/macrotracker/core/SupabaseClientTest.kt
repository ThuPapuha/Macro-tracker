package io.github.thupapuha.macrotracker.core

import io.github.thupapuha.macrotracker.core.model.Macros
import io.github.thupapuha.macrotracker.core.supabase.AuthSession
import io.github.thupapuha.macrotracker.core.supabase.GroupApi
import io.github.thupapuha.macrotracker.core.supabase.SessionStore
import io.github.thupapuha.macrotracker.core.supabase.SharedMeal
import io.github.thupapuha.macrotracker.core.supabase.SupabaseClient
import io.github.thupapuha.macrotracker.core.supabase.SupabaseConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class SupabaseClientTest {

    private lateinit var server: MockWebServer
    private val store = object : SessionStore {
        var saved: AuthSession? = null
        override suspend fun load() = saved
        override suspend fun save(session: AuthSession?) {
            saved = session
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = SupabaseClient(
        config = SupabaseConfig.of(server.url("/").toString(), "anon-key")!!,
        http = OkHttpClient(),
        store = store,
        nowSeconds = { 1_000 },
    )

    private fun token(access: String, refresh: String) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"expires_at":5000,"user":{"id":"user-1"}}"""

    @Test
    fun signsUpAnonymouslyThenCallsRpc() = runTest {
        server.enqueue(MockResponse().setBody(token("a1", "r1")))
        server.enqueue(MockResponse().setBody("""[{"id":"g1","name":"Gym","invite_code":"ABC123","owner_id":"user-1","member_count":3}]"""))

        val groups = GroupApi(client()).myGroups()

        assertEquals("ABC123", groups.single().inviteCode)
        val signup = server.takeRequest()
        assertEquals("/auth/v1/signup", signup.path)
        assertEquals("anon-key", signup.getHeader("apikey"))
        val rpc = server.takeRequest()
        assertEquals("/rest/v1/rpc/my_groups", rpc.path)
        assertEquals("Bearer a1", rpc.getHeader("Authorization"))
        assertEquals("user-1", store.saved?.userId)
    }

    @Test
    fun refreshesTokenAfter401AndRetries() = runTest {
        store.saved = AuthSession("old", "r0", expiresAt = 5_000, userId = "user-1")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"JWT expired"}"""))
        server.enqueue(MockResponse().setBody(token("new", "r1")))
        server.enqueue(MockResponse().setResponseCode(204))

        GroupApi(client()).leaveGroup("g1")

        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        assertEquals("r1", store.saved?.refreshToken)
    }

    @Test
    fun parsesActivityFeed() = runTest {
        store.saved = AuthSession("a1", "r1", expiresAt = 5_000, userId = "user-1")
        server.enqueue(
            MockResponse().setBody(
                """{"now":"2026-10-21T05:00:00.123+00:00",
                  "items":[{"user_id":"u2","display_name":"Ana","group_id":"g1","group_name":"Gym","day":"2026-10-21",
                            "calories":500,"protein":30,"carbs":50,"fat":20,
                            "meals":[{"name":"Avena","calories":300,"meal":"BREAKFAST"}],
                            "updated_at":"2026-10-21T04:59:00+00:00"}],
                  "comments":[{"id":"c1","group_id":"g1","group_name":"Gym","day":"2026-10-21","meal_name":null,
                               "body":"¡Bien!","created_at":"2026-10-21T04:58:00+00:00","author_name":"Ana"}]}""",
            ),
        )

        val activity = GroupApi(client()).activity(since = null)

        assertEquals("2026-10-21T05:00:00.123+00:00", activity.now)
        assertEquals("Avena", activity.items.single().meals.single().name)
        assertEquals(500.0, activity.items.single().totals.calories, 0.0)
        assertNull(activity.comments.single().mealName)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/group_activity", request.path)
        assertEquals("""{"p_since":null}""", request.body.readUtf8())
    }

    @Test
    fun summaryArgsIncludeMealsAndNullTargets() {
        val args = GroupApi.summaryArgs(
            LocalDate.of(2026, 10, 19),
            Macros(1500.0, 100.0, 150.0, 50.0),
            targets = null,
            meals = listOf(SharedMeal("Avena", 300.0, "BREAKFAST")),
        )
        assertEquals("2026-10-19", args["p_day"]!!.jsonPrimitive.content)
        assertNull(args["p_target_calories"]!!.jsonPrimitive.content.toDoubleOrNull())
        assertEquals(1, args["p_meals"]!!.jsonArray.size)
    }
}
