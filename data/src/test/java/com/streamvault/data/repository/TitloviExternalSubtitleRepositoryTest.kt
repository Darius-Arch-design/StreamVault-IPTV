package com.streamvault.data.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.settings.ExternalSubtitleCredentialsStore
import com.streamvault.domain.settings.TitloviCredentials
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TitloviExternalSubtitleRepositoryTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private val credentialsStore = FakeCredentialsStore()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.cacheDir.resolve("external-subtitles").deleteRecursively()
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        context.cacheDir.resolve("external-subtitles").deleteRecursively()
    }

    @Test
    fun `episode search authenticates and sends exact EX-YU filters`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"Token":"token-1","UserId":42,"ExpirationDate":"2099-01-01T00:00:00Z"}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{
                    "SubtitleResults":[
                      {"Id":77,"Title":"Example","Year":2024,"Type":2,"Season":1,"Episode":2,"Lang":"Hrvatski","DownloadCount":100,"Rating":4.5,"Release":"WEB-DL"}
                    ]
                }""".trimIndent()
            )
        )
        val repository = repository()

        val result = repository.search(
            contentType = ContentType.SERIES_EPISODE,
            title = "Example",
            year = 2024,
            tmdbId = null,
            parentTmdbId = null,
            seasonNumber = 1,
            episodeNumber = 2,
            language = "hr"
        )

        assertThat(result.getOrThrow()).containsExactly(
            com.streamvault.domain.model.ExternalSubtitle(
                id = "77",
                language = "hr",
                displayLanguage = "Hrvatski",
                releaseName = "WEB-DL",
                source = com.streamvault.domain.model.ExternalSubtitleSource.TITLOVI,
                downloadId = "77:2:1:2"
            )
        )
        val login = server.takeRequest()
        assertThat(login.method).isEqualTo("POST")
        assertThat(login.requestUrl?.queryParameter("username")).isEqualTo("user")
        assertThat(login.requestUrl?.queryParameter("password")).isEqualTo("pass")
        val search = server.takeRequest()
        assertThat(search.requestUrl?.queryParameter("lang")).isEqualTo("Hrvatski")
        assertThat(search.requestUrl?.queryParameterValues("season")).containsExactly("0", "1").inOrder()
        assertThat(search.requestUrl?.queryParameterValues("episode")).containsExactly("0", "2").inOrder()
    }

    @Test
    fun `download selects matching episode and converts windows 1250 to utf8`() = runBlocking {
        val archive = zip(
            "Show.S01E01.srt" to "1\n00:00:01,000 --> 00:00:02,000\nKrivi\n",
            "Show.S01E02.srt" to "1\n00:00:01,000 --> 00:00:02,000\nČćžšđ\n"
        )
        server.enqueue(MockResponse().setBody(archive.toByteString()))
        val repository = repository()
        val subtitle = com.streamvault.domain.model.ExternalSubtitle(
            id = "77",
            language = "hr",
            displayLanguage = "Hrvatski",
            releaseName = "WEB-DL",
            source = com.streamvault.domain.model.ExternalSubtitleSource.TITLOVI,
            downloadId = "77:2:1:2"
        )

        val file = repository.downloadToCache(subtitle).getOrThrow()

        assertThat(file.readText()).contains("Čćžšđ")
        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameter("mediaId")).isEqualTo("77")
        assertThat(request.requestUrl?.queryParameter("type")).isEqualTo("2")
    }

    @Test
    fun `missing credentials are a silent no-op`() = runBlocking {
        credentialsStore.credentials = null

        val result = repository().search(
            contentType = ContentType.MOVIE,
            title = "Example",
            year = null,
            tmdbId = null,
            parentTmdbId = null,
            seasonNumber = null,
            episodeNumber = null,
            language = "hr"
        )

        assertThat(result.getOrThrow()).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `search title removes only matching trailing year`() {
        assertThat(normalizeTitloviSearchTitle("Film (2024)", 2024)).isEqualTo("Film")
        assertThat(normalizeTitloviSearchTitle("2001 A Space Odyssey", 1968))
            .isEqualTo("2001 A Space Odyssey")
    }

    private fun repository(): TitloviExternalSubtitleRepository = TitloviExternalSubtitleRepository(
        context = context,
        okHttpClient = OkHttpClient(),
        gson = Gson(),
        credentialsStore = credentialsStore,
        apiBaseUrl = server.url("/api/subtitles/").toString(),
        siteBaseUrl = server.url("/").toString()
    )

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            entries.forEach { (name, text) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(text.toByteArray(Charset.forName("windows-1250")))
                archive.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private class FakeCredentialsStore : ExternalSubtitleCredentialsStore {
        var credentials: TitloviCredentials? = TitloviCredentials("user", "pass")

        override suspend fun getTitloviCredentials(): TitloviCredentials? = credentials

        override suspend fun setTitloviCredentials(username: String, password: String) {
            credentials = TitloviCredentials(username, password)
        }

        override suspend fun clearTitloviCredentials() {
            credentials = null
        }
    }
}
