package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ExternalSubtitle
import com.streamvault.domain.model.ExternalSubtitleSource
import com.streamvault.domain.repository.ExternalSubtitleRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlayerExternalSubtitleCoordinatorTest {

    @Test
    fun `prefers Croatian then falls back to Bosnian and caches the download`() = runTest {
        val repository = FakeExternalSubtitleRepository()
        val coordinator = PlayerExternalSubtitleCoordinator(repository)
        val request = PlayerExternalSubtitleRequest(
            contentType = ContentType.SERIES_EPISODE,
            title = "Example",
            seasonNumber = 1,
            episodeNumber = 2
        )

        val first = coordinator.loadBest(request)
        val second = coordinator.loadBest(request)

        assertThat(first?.subtitle?.language).isEqualTo("bs")
        assertThat(second).isEqualTo(first)
        assertThat(repository.searchedLanguages).containsExactly("hr", "bs").inOrder()
        assertThat(repository.downloadCount).isEqualTo(1)
    }

    private class FakeExternalSubtitleRepository : ExternalSubtitleRepository {
        val searchedLanguages = mutableListOf<String>()
        var downloadCount = 0
        private val file = File.createTempFile("streamvault-subtitle", ".srt").apply {
            writeText("1\n00:00:00,000 --> 00:00:01,000\nTest\n")
            deleteOnExit()
        }

        override suspend fun search(
            contentType: ContentType,
            title: String,
            year: Int?,
            tmdbId: Long?,
            parentTmdbId: Long?,
            seasonNumber: Int?,
            episodeNumber: Int?,
            language: String
        ): Result<List<ExternalSubtitle>> {
            searchedLanguages += language
            return Result.success(
                if (language == "bs") {
                    listOf(
                        ExternalSubtitle(
                            id = "1",
                            language = "bs",
                            displayLanguage = "Bosanski",
                            releaseName = "WEB-DL",
                            source = ExternalSubtitleSource.TITLOVI,
                            downloadId = "1:2:1:2"
                        )
                    )
                } else {
                    emptyList()
                }
            )
        }

        override suspend fun downloadToCache(subtitle: ExternalSubtitle): Result<File> {
            downloadCount += 1
            return Result.success(file)
        }
    }
}
