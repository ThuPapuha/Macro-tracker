package io.github.thupapuha.macrotracker.core

import io.github.thupapuha.macrotracker.core.update.UpdateChecker
import io.github.thupapuha.macrotracker.core.update.UpdateException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateCheckerTest {

    private fun release(tag: String, assets: String, draft: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":false,"name":"Macro Tracker","assets":[$assets]}"""

    private val apkAsset =
        """{"name":"MacroTracker-16.apk","size":8005162,"browser_download_url":"https://github.com/o/r/releases/download/build-16/MacroTracker-16.apk"}"""

    @Test
    fun parsesVersionAndApk() {
        val update = UpdateChecker.parseRelease(release("build-16", """{"name":"notes.txt","browser_download_url":"x"},$apkAsset"""))!!
        assertEquals(16, update.versionCode)
        assertEquals("1.0.16", update.versionName)
        assertEquals(8005162L, update.sizeBytes)
        assertEquals("https://github.com/o/r/releases/download/build-16/MacroTracker-16.apk", update.apkUrl)
    }

    @Test
    fun ignoresDraftsReleasesWithoutApkAndOddTags() {
        assertNull(UpdateChecker.parseRelease(release("build-16", apkAsset, draft = true)))
        assertNull(UpdateChecker.parseRelease(release("build-16", "")))
        assertNull(UpdateChecker.parseRelease(release("latest", apkAsset)))
    }

    private fun checker(server: MockWebServer): UpdateChecker {
        val base = server.url("/").toString().trimEnd('/')
        return UpdateChecker(OkHttpClient(), "ThuPapuha/Macro-tracker", apiUrl = "$base/api", webUrl = base)
    }

    @Test
    fun usesVersionManifestFirst() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody("""{"versionCode":19,"versionName":"1.0.19","tag":"build-19","apk":"MacroTracker-19.apk","size":8287015}"""),
        )
        server.start()

        val update = checker(server).check(currentVersionCode = 17)!!

        assertEquals(19, update.versionCode)
        assertEquals(8287015L, update.sizeBytes)
        assertTrue(update.apkUrl.endsWith("/ThuPapuha/Macro-tracker/releases/download/build-19/MacroTracker-19.apk"))
        assertEquals("/ThuPapuha/Macro-tracker/releases/latest/download/version.json", server.takeRequest().path)
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun fallsBackToApiForReleasesWithoutManifest() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(release("build-16", apkAsset)))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(release("build-16", apkAsset)))
        server.start()
        val checker = checker(server)

        assertEquals(16, checker.check(currentVersionCode = 15)?.versionCode)
        assertNull(checker.check(currentVersionCode = 16))
        server.takeRequest()
        assertEquals("/api/repos/ThuPapuha/Macro-tracker/releases/latest", server.takeRequest().path)
        server.shutdown()
    }

    @Test
    fun explainsApiRateLimit() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
        server.start()
        try {
            checker(server).check(currentVersionCode = 1)
            fail("expected UpdateException")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("limitó"))
        }
        server.shutdown()
    }
}
