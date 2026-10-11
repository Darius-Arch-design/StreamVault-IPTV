package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ExternalSubtitle
import com.streamvault.domain.repository.ExternalSubtitleRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PlayerExternalSubtitleRequest(
    val contentType: ContentType,
    val title: String,
    val year: Int? = null,
    val tmdbId: Long? = null,
    val parentTmdbId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
)

internal data class LoadedExternalSubtitle(
    val subtitle: ExternalSubtitle,
    val file: File
)

class PlayerExternalSubtitleCoordinator @Inject constructor(
    private val repository: ExternalSubtitleRepository
) {
    private val loadMutex = Mutex()
    private val cache = LinkedHashMap<PlayerExternalSubtitleRequest, LoadedExternalSubtitle>()

    internal suspend fun loadBest(
        request: PlayerExternalSubtitleRequest
    ): LoadedExternalSubtitle? = loadMutex.withLock {
        cache[request]?.takeIf { it.file.isFile && it.file.length() > 0L }?.let { return@withLock it }

        for (language in PREFERRED_LANGUAGES) {
            val search = repository.search(
                contentType = request.contentType,
                title = request.title,
                year = request.year,
                tmdbId = request.tmdbId,
                parentTmdbId = request.parentTmdbId,
                seasonNumber = request.seasonNumber,
                episodeNumber = request.episodeNumber,
                language = language
            )
            if (search.isFailure) return@withLock null
            for (subtitle in search.getOrDefault(emptyList()).take(MAX_DOWNLOAD_ATTEMPTS_PER_LANGUAGE)) {
                val file = repository.downloadToCache(subtitle).getOrNull() ?: continue
                return@withLock LoadedExternalSubtitle(subtitle, file).also { loaded ->
                    cache[request] = loaded
                    while (cache.size > MAX_MEMORY_CACHE_ENTRIES) {
                        cache.remove(cache.keys.first())
                    }
                }
            }
        }
        null
    }

    private companion object {
        val PREFERRED_LANGUAGES = listOf("hr", "bs", "sr")
        const val MAX_DOWNLOAD_ATTEMPTS_PER_LANGUAGE = 3
        const val MAX_MEMORY_CACHE_ENTRIES = 20
    }
}
