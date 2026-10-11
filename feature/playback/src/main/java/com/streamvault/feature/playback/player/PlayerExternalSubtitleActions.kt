package com.streamvault.feature.playback.player

import android.net.Uri
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.launch

internal fun PlayerViewModel.scheduleAutomaticExternalSubtitle(
    requestVersion: Long,
    expectedPlaybackUrl: String
) {
    externalSubtitleJob?.cancel()
    if (currentContentType == ContentType.LIVE) return
    val sessionScope = playbackSessionScope(requestVersion) ?: return
    externalSubtitleJob = sessionScope.launch {
        val request = resolveExternalSubtitleRequest() ?: return@launch
        val loaded = playerExternalSubtitleCoordinator.loadBest(request) ?: return@launch
        if (!isActivePlaybackSession(requestVersion)) return@launch
        if (currentResolvedPlaybackUrl != expectedPlaybackUrl) return@launch
        runCatching {
            playerEngine.addExternalSubtitle(
                subtitleUri = Uri.fromFile(loaded.file),
                language = loaded.subtitle.language
            )
        }
    }
}

private suspend fun PlayerViewModel.resolveExternalSubtitleRequest(): PlayerExternalSubtitleRequest? =
    when (currentContentType) {
        ContentType.MOVIE,
        ContentType.VOD -> {
            val movie = currentContentId.takeIf { it > 0L }?.let { playerContentResolver.getMovie(it) }
            val title = movie?.name?.takeIf(String::isNotBlank) ?: currentTitle
            title.takeIf(String::isNotBlank)?.let {
                PlayerExternalSubtitleRequest(
                    contentType = currentContentType,
                    title = it,
                    year = movie?.year.toExternalSubtitleYear()
                        ?: movie?.releaseDate.toExternalSubtitleYear(),
                    tmdbId = movie?.tmdbId
                )
            }
        }

        ContentType.SERIES,
        ContentType.SERIES_EPISODE -> {
            val episode = currentEpisode.value
                ?: currentStableEpisodeId?.takeIf { it > 0L }?.let { playerContentResolver.getEpisodeById(it) }
                ?: currentContentId.takeIf { it > 0L }?.let { playerContentResolver.getEpisodeById(it) }
            val series = currentSeries.value
                ?: currentSeriesId?.takeIf { it > 0L }?.let { playerContentResolver.getSeriesById(it) }
                ?: episode?.seriesId?.takeIf { it > 0L }?.let { playerContentResolver.getSeriesById(it) }
            val season = currentSeasonNumber ?: episode?.seasonNumber
            val episodeNumber = currentEpisodeNumber ?: episode?.episodeNumber
            val title = series?.name?.takeIf(String::isNotBlank) ?: currentTitle
            if (title.isBlank() || season == null || episodeNumber == null) {
                null
            } else {
                PlayerExternalSubtitleRequest(
                    contentType = ContentType.SERIES_EPISODE,
                    title = title,
                    year = series?.releaseDate.toExternalSubtitleYear(),
                    parentTmdbId = series?.tmdbId,
                    seasonNumber = season,
                    episodeNumber = episodeNumber
                )
            }
        }

        ContentType.LIVE -> null
    }

private fun String?.toExternalSubtitleYear(): Int? = this
    ?.let { value -> Regex("""(?:19|20)\d{2}""").find(value)?.value }
    ?.toIntOrNull()
