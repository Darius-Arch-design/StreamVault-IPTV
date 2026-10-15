package com.streamvault.feature.playback.player

import android.net.Uri
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ExternalSubtitle
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class TitloviSearchUiState(
    val loading: Boolean = false,
    val downloading: Boolean = false,
    val results: List<ExternalSubtitle> = emptyList(),
    val message: String? = null
)

internal fun PlayerViewModel.resetTitloviSearch() {
    titloviSearchUiState.value = TitloviSearchUiState()
}

internal fun PlayerViewModel.searchTitloviSubtitles() {
    val requestVersion = prepareRequestVersion
    val scope = playbackSessionScope(requestVersion) ?: return
    titloviSearchUiState.value = TitloviSearchUiState(loading = true)
    scope.launch {
        val request = resolveExternalSubtitleRequest()
        if (request == null) {
            titloviSearchUiState.value = TitloviSearchUiState(message = "Movie or episode details are unavailable.")
            return@launch
        }
        val result = playerExternalSubtitleCoordinator.search(request)
        if (!isActivePlaybackSession(requestVersion)) return@launch
        titloviSearchUiState.value = if (result.isSuccess) {
            val results = result.getOrDefault(emptyList())
            TitloviSearchUiState(
                results = results,
                message = if (results.isEmpty()) "No subtitles found. Check your Titlovi.com account and try another title." else null
            )
        } else {
            TitloviSearchUiState(message = "Titlovi.com search failed: ${result.exceptionOrNull()?.message.orEmpty().take(100)}")
        }
    }
}

internal fun PlayerViewModel.downloadTitloviSubtitle(subtitle: ExternalSubtitle) {
    val requestVersion = prepareRequestVersion
    val expectedPlaybackUrl = currentResolvedPlaybackUrl
    val scope = playbackSessionScope(requestVersion) ?: return
    externalSubtitleJob?.cancel()
    titloviSearchUiState.update { it.copy(downloading = true, message = null) }
    scope.launch {
        val result = playerExternalSubtitleCoordinator.download(subtitle)
        if (!isActivePlaybackSession(requestVersion) || currentResolvedPlaybackUrl != expectedPlaybackUrl) return@launch
        val file = result.getOrNull()?.file
        if (file == null) {
            titloviSearchUiState.update { it.copy(downloading = false, message = "Subtitle download failed. Try another result.") }
            return@launch
        }
        try {
            playerEngine.addExternalSubtitle(Uri.fromFile(file), subtitle.language)
            titloviSearchUiState.update { it.copy(downloading = false, message = "Subtitle downloaded and selected.") }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            titloviSearchUiState.update { it.copy(downloading = false, message = "Subtitle could not be played.") }
        }
    }
}

internal fun PlayerViewModel.scheduleAutomaticExternalSubtitle(
    requestVersion: Long,
    expectedPlaybackUrl: String
) {
    externalSubtitleJob?.cancel()
    if (currentContentType == ContentType.LIVE) return
    val sessionScope = playbackSessionScope(requestVersion) ?: return
    externalSubtitleJob = sessionScope.launch {
        val engine = playerEngine
        val playbackState = engine.playbackState.first { state ->
            state == PlaybackState.READY ||
                state == PlaybackState.ERROR ||
                state == PlaybackState.ENDED
        }
        if (playbackState != PlaybackState.READY || !isActivePlaybackSession(requestVersion)) return@launch

        // Wait for the media tracks before deciding whether an external subtitle is needed.
        if (hasEmbeddedExYuSubtitleTrack(engine.availableSubtitleTracks.value)) return@launch

        val request = resolveExternalSubtitleRequest() ?: return@launch
        val loaded = playerExternalSubtitleCoordinator.loadBest(request) ?: return@launch
        if (!isActivePlaybackSession(requestVersion)) return@launch
        if (currentResolvedPlaybackUrl != expectedPlaybackUrl) return@launch
        // A late manifest update may reveal embedded text tracks while Titlovi is searched.
        if (hasEmbeddedExYuSubtitleTrack(engine.availableSubtitleTracks.value)) return@launch
        runCatching {
            engine.addExternalSubtitle(
                subtitleUri = Uri.fromFile(loaded.file),
                language = loaded.subtitle.language
            )
        }
    }
}

internal suspend fun PlayerViewModel.resolveExternalSubtitleRequest(): PlayerExternalSubtitleRequest? =
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


internal fun hasEmbeddedExYuSubtitleTrack(tracks: List<PlayerTrack>): Boolean =
    tracks.any { track ->
        track.type == TrackType.TEXT &&
            listOfNotNull(track.language, track.name).any(::isExYuSubtitleDescriptor)
    }

private fun isExYuSubtitleDescriptor(value: String): Boolean {
    val normalized = value.trim().lowercase(Locale.ROOT).replace('_', '-')
    if (normalized.isBlank()) return false
    val code = normalized.substringBefore('-')
    if (code in EX_YU_SUBTITLE_LANGUAGE_CODES) return true

    val label = normalized.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val tokens = label.split(Regex("\\s+")).toSet()
    return tokens.any { it in EX_YU_SUBTITLE_LANGUAGE_CODES } ||
        EX_YU_SUBTITLE_LANGUAGE_LABELS.any { marker -> label.contains(marker) } ||
        "bcs" in tokens
}

private val EX_YU_SUBTITLE_LANGUAGE_CODES = setOf(
    "hr", "hrv", "bs", "bos", "sr", "srp", "sh", "hbs",
    "cnr", "sl", "slv", "mk", "mkd"
)

private val EX_YU_SUBTITLE_LANGUAGE_LABELS = listOf(
    "croatian", "hrvatski", "хрватски",
    "bosnian", "bosanski", "босански",
    "serbian", "srpski", "српски",
    "serbo croatian", "croato serbian",
    "montenegrin", "crnogorski",
    "slovenian", "slovene", "slovenski",
    "macedonian", "makedonski", "македонски",
    "ex yu"
)


internal fun ExternalSubtitle.toPlayerSubtitlePickerLabel(): String {
    val languageLabel = displayLanguage.trim()
        .ifBlank { language.uppercase(Locale.ROOT) }
    val normalizedRelease = releaseName
        .replace(Regex("""(?i)(?:\.(?:srt|ass|ssa|sub|vtt|zip))+$"""), "")
        .replace(Regex("[._]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalizedRelease.isBlank()) return languageLabel

    val compactRelease = normalizedRelease.take(MAX_SUBTITLE_RELEASE_LABEL_LENGTH).trimEnd()
        .let { if (normalizedRelease.length > MAX_SUBTITLE_RELEASE_LABEL_LENGTH) "$it…" else it }
    return "$languageLabel · $compactRelease"
}

private const val MAX_SUBTITLE_RELEASE_LABEL_LENGTH = 64
