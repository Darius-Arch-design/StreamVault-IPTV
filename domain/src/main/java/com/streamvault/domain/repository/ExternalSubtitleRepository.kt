package com.streamvault.domain.repository

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ExternalSubtitle
import java.io.File

interface ExternalSubtitleRepository {

    /** Checks that the account can obtain an API token before it is stored. */
    suspend fun validateAccount(username: String, password: String): Result<Unit>

    suspend fun search(
        contentType: ContentType,
        title: String,
        year: Int?,
        tmdbId: Long?,
        parentTmdbId: Long?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        language: String
    ): Result<List<ExternalSubtitle>>

    suspend fun downloadToCache(subtitle: ExternalSubtitle): Result<File>
}
