package io.github.thupapuha.macrotracker.core

import io.github.thupapuha.macrotracker.core.update.UpdateChecker
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun onlyReportsNewerVersions() = runTest {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody(release("build-16", apkAsset))) }
        server.start()
        val checker = UpdateChecker(OkHttpClient(), "ThuPapuha/Macro-tracker", server.url("/").toString().trimEnd('/'))

        assertEquals(16, checker.check(currentVersionCode = 15)?.versionCode)
        assertNull(checker.check(currentVersionCode = 16))
        assertEquals("/repos/ThuPapuha/Macro-tracker/releases/latest", server.takeRequest().path)
        server.shutdown()
    }
}
