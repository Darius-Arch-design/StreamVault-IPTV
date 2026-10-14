package com.streamvault.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.data.util.runSuspendCatching
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ExternalSubtitle
import com.streamvault.domain.model.ExternalSubtitleSource
import com.streamvault.domain.repository.ExternalSubtitleRepository
import com.streamvault.domain.settings.ExternalSubtitleCredentialsStore
import com.streamvault.domain.settings.TitloviCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor

@Singleton
class TitloviExternalSubtitleRepository private constructor(
    private val context: Context,
    okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val credentialsStore: ExternalSubtitleCredentialsStore,
    private val endpoints: TitloviEndpoints
) : ExternalSubtitleRepository {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        gson: Gson,
        credentialsStore: ExternalSubtitleCredentialsStore
    ) : this(context, okHttpClient, gson, credentialsStore, TitloviEndpoints.production())

    internal constructor(
        context: Context,
        okHttpClient: OkHttpClient,
        gson: Gson,
        credentialsStore: ExternalSubtitleCredentialsStore,
        apiBaseUrl: String,
        siteBaseUrl: String
    ) : this(
        context = context,
        okHttpClient = okHttpClient,
        gson = gson,
        credentialsStore = credentialsStore,
        endpoints = TitloviEndpoints(apiBaseUrl, siteBaseUrl)
    )

    private val client = okHttpClient.newBuilder()
        .apply {
            interceptors().removeAll { interceptor -> interceptor is HttpLoggingInterceptor }
        }
        .cache(null)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val tokenMutex = Mutex()

    @Volatile
    private var cachedToken: CachedToken? = null

    override suspend fun validateAccount(username: String, password: String): Result<Unit> = runSuspendCatching {
        require(username.isNotBlank() && password.isNotBlank()) { "Enter the Titlovi.com username and password." }
        authenticatedToken(TitloviCredentials(username.trim(), password), forceRefresh = true)
        Unit
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
    ): Result<List<ExternalSubtitle>> = runSuspendCatching {
        val credentials = credentialsStore.getTitloviCredentials() ?: return@runSuspendCatching emptyList()
        val providerLanguage = language.toTitloviLanguage() ?: return@runSuspendCatching emptyList()
        val mediaType = when (contentType) {
            ContentType.MOVIE, ContentType.VOD -> TITLOVI_MOVIE_TYPE
            ContentType.SERIES, ContentType.SERIES_EPISODE -> TITLOVI_EPISODE_TYPE
            ContentType.LIVE -> return@runSuspendCatching emptyList()
        }
        if (mediaType == TITLOVI_EPISODE_TYPE && (seasonNumber == null || episodeNumber == null)) {
            return@runSuspendCatching emptyList()
        }
        val normalizedTitle = normalizeTitloviSearchTitle(title, year)
        if (normalizedTitle.isBlank()) return@runSuspendCatching emptyList()

        fun searchRequest(token: CachedToken): Request {
            val url = "${endpoints.apiBaseUrl.trimEnd('/')}/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", normalizedTitle)
                .addQueryParameter("type", mediaType.toString())
                .addQueryParameter("pg", "1")
                .addQueryParameter("ignoreLangAndEpisode", "false")
                .addQueryParameter("lang", providerLanguage)
                .addQueryParameter("token", token.id)
                .addQueryParameter("userid", token.userId.toString())
                .apply {
                    if (mediaType == TITLOVI_EPISODE_TYPE) {
                        addQueryParameter("season", "0")
                        addQueryParameter("season", seasonNumber.toString())
                        addQueryParameter("episode", "0")
                        addQueryParameter("episode", episodeNumber.toString())
                    }
                }
                .build()
            return Request.Builder().url(url).titloviHeaders().get().build()
        }
        val payload = try {
            executeJson(searchRequest(authenticatedToken(credentials)), "search")
        } catch (error: TitloviApiException) {
            if (error.httpStatus != 401) throw error
            // A token can be revoked before its advertised expiry. Refresh it once,
            // without repeatedly submitting bad credentials or looping on a denied account.
            executeJson(searchRequest(authenticatedToken(credentials, forceRefresh = true)), "search")
        }
        val results = payload.getAsJsonArray("SubtitleResults") ?: return@runSuspendCatching emptyList()
        results
            .mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
            .mapNotNull { result ->
                val id = result.int("Id") ?: return@mapNotNull null
                val resultType = result.int("Type") ?: mediaType
                val resultSeason = result.int("Season") ?: 0
                val resultEpisode = result.int("Episode") ?: 0
                val resultLanguage = result.string("Lang").orEmpty()
                if (!resultLanguage.equals(providerLanguage, ignoreCase = true)) return@mapNotNull null
                if (
                    mediaType == TITLOVI_EPISODE_TYPE &&
                    ((resultSeason > 0 && resultSeason != seasonNumber) ||
                        (resultEpisode > 0 && resultEpisode != episodeNumber))
                ) {
                    return@mapNotNull null
                }
                TitloviSearchResult(
                    subtitle = ExternalSubtitle(
                        id = id.toString(),
                        language = language.toPlayerLanguage(),
                        displayLanguage = resultLanguage,
                        releaseName = result.string("Release")
                            ?.takeIf(String::isNotBlank)
                            ?: result.string("Title").orEmpty(),
                        source = ExternalSubtitleSource.TITLOVI,
                        downloadId = listOf(id, resultType, resultSeason, resultEpisode).joinToString(":")
                    ),
                    exactYear = year != null && result.int("Year") == year,
                    rating = result.float("Rating") ?: 0f,
                    downloadCount = result.int("DownloadCount") ?: 0
                )
            }
            .sortedWith(
                compareByDescending<TitloviSearchResult> { it.exactYear }
                    .thenByDescending { it.rating }
                    .thenByDescending { it.downloadCount }
            )
            .take(MAX_SEARCH_RESULTS)
            .map(TitloviSearchResult::subtitle)
    }

    override suspend fun downloadToCache(subtitle: ExternalSubtitle): Result<File> = runSuspendCatching {
        withContext(Dispatchers.IO) {
            require(subtitle.source == ExternalSubtitleSource.TITLOVI) {
                "Unsupported external subtitle source: ${subtitle.source}"
            }
            val descriptor = TitloviDownloadDescriptor.parse(subtitle.downloadId)
            val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
            val target = File(
                cacheDirectory,
                "titlovi-${descriptor.id}-${descriptor.type}-${descriptor.season}-${descriptor.episode}-${subtitle.language}.srt"
            )
            if (target.isFile && target.length() in 1L..MAX_EXTRACTED_BYTES) {
                return@withContext target
            }

            val url = "${endpoints.siteBaseUrl.trimEnd('/')}/download".toHttpUrl().newBuilder()
                .addQueryParameter("mediaId", descriptor.id.toString())
                .addQueryParameter("type", descriptor.type.toString())
                .build()
            val downloaded = executeBytes(Request.Builder().url(url).titloviHeaders().get().build())
            val selected = selectSubtitleBytes(downloaded, descriptor)
            val utf8 = subtitleBytesAsUtf8(selected)
            require(utf8.size <= MAX_EXTRACTED_BYTES) { "Subtitle is larger than the allowed limit" }
            require(String(utf8, StandardCharsets.UTF_8).contains("-->")) {
                "Downloaded file is not a valid SRT subtitle"
            }

            val temporary = File(cacheDirectory, ".${target.name}.${UUID.randomUUID()}.tmp")
            try {
                temporary.writeBytes(utf8)
                if (!temporary.renameTo(target)) {
                    target.writeBytes(utf8)
                }
            } finally {
                temporary.delete()
            }
            pruneSubtitleCache(cacheDirectory, target)
            target
        }
    }

    private suspend fun authenticatedToken(
        credentials: TitloviCredentials,
        forceRefresh: Boolean = false
    ): CachedToken = tokenMutex.withLock {
        val fingerprint = credentials.username + ':' + credentials.password.hashCode()
        val now = System.currentTimeMillis()
        cachedToken?.takeIf { token ->
            !forceRefresh &&
            token.credentialFingerprint == fingerprint && token.expiresAtMs - TOKEN_EXPIRY_MARGIN_MS > now
        }?.let { return@withLock it }

        val url = "${endpoints.apiBaseUrl.trimEnd('/')}/gettoken".toHttpUrl().newBuilder()
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
            .build()
        val request = Request.Builder()
            .url(url)
            .titloviHeaders()
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val payload = executeJson(request, "login")
        val id = payload.string("Token")?.takeIf(String::isNotBlank)
            ?: throw TitloviApiException("Titlovi.com authentication did not return a token")
        val userId = payload.int("UserId")
            ?: throw TitloviApiException("Titlovi.com authentication did not return a user id")
        CachedToken(
            id = id,
            userId = userId,
            credentialFingerprint = fingerprint,
            expiresAtMs = parseExpiration(payload.string("ExpirationDate"), now)
        ).also { cachedToken = it }
    }

    private suspend fun executeJson(request: Request, operation: String): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) cachedToken = null
                val message = if (response.code == 401 || response.code == 403) {
                    if (operation == "login") {
                        "Titlovi.com rejected login (HTTP ${response.code}). Check your username, password, confirmed email and API access."
                    } else {
                        "Titlovi.com rejected subtitle search (HTTP ${response.code}). Check that API access is enabled for this account."
                    }
                } else {
                    "Titlovi.com $operation failed with HTTP ${response.code}"
                }
                throw TitloviApiException(message, httpStatus = response.code)
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw TitloviApiException("Titlovi.com returned an empty response")
            runCatching { gson.fromJson(body, JsonObject::class.java) }
                .getOrElse { error -> throw TitloviApiException("Titlovi.com returned invalid JSON", error) }
        }
    }

    private suspend fun executeBytes(request: Request): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(request).useCancellableResponse { response ->
            if (!response.isSuccessful) {
                throw TitloviApiException("Titlovi.com download failed with HTTP ${response.code}")
            }
            val body = response.body ?: throw TitloviApiException("Titlovi.com returned an empty download")
            val length = body.contentLength()
            require(length < 0 || length <= MAX_DOWNLOAD_BYTES) { "Subtitle download is larger than the allowed limit" }
            body.byteStream().readBounded(MAX_DOWNLOAD_BYTES)
        }
    }

    private fun Request.Builder.titloviHeaders(): Request.Builder =
        header("User-Agent", TITLOVI_USER_AGENT)
            .header("X-App", TITLOVI_APP_HEADER)

    private fun selectSubtitleBytes(
        downloaded: ByteArray,
        descriptor: TitloviDownloadDescriptor
    ): ByteArray {
        if (!downloaded.isZipArchive()) return downloaded
        val candidates = mutableListOf<SubtitleArchiveEntry>()
        ZipInputStream(ByteArrayInputStream(downloaded)).use { archive ->
            var entryCount = 0
            while (entryCount < MAX_ARCHIVE_ENTRIES) {
                val entry = archive.nextEntry ?: break
                entryCount += 1
                if (!entry.isDirectory && entry.name.endsWith(".srt", ignoreCase = true)) {
                    candidates += SubtitleArchiveEntry(
                        name = entry.name.substringAfterLast('/').substringAfterLast('\\'),
                        bytes = archive.readBounded(MAX_EXTRACTED_BYTES)
                    )
                }
                archive.closeEntry()
            }
        }
        require(candidates.isNotEmpty()) { "Downloaded archive contains no SRT subtitle" }
        if (descriptor.type != TITLOVI_EPISODE_TYPE) return candidates.first().bytes

        val exact = candidates.firstOrNull { candidate ->
            candidate.name.matchesEpisode(descriptor.season, descriptor.episode)
        }
        return exact?.bytes
            ?: candidates.singleOrNull()?.bytes
            ?: throw TitloviApiException(
                "Downloaded archive contains no subtitle for S${descriptor.season}E${descriptor.episode}"
            )
    }

    private fun pruneSubtitleCache(directory: File, keep: File) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it != keep }
            .sortedByDescending(File::lastModified)
            .drop(MAX_CACHED_SUBTITLES - 1)
            .forEach(File::delete)
    }

    private companion object {
        const val TITLOVI_MOVIE_TYPE = 1
        const val TITLOVI_EPISODE_TYPE = 2
        const val TITLOVI_USER_AGENT = "StreamVault-IPTV/1.0 (Android)"
        const val TITLOVI_APP_HEADER = "streamvault-android"
        const val CACHE_DIRECTORY = "external-subtitles"
        const val MAX_SEARCH_RESULTS = 20
        const val MAX_ARCHIVE_ENTRIES = 64
        const val MAX_CACHED_SUBTITLES = 40
        const val MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L
        const val MAX_EXTRACTED_BYTES = 4L * 1024L * 1024L
        const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
    }
}

internal data class TitloviEndpoints(
    val apiBaseUrl: String,
    val siteBaseUrl: String
) {
    companion object {
        fun production(): TitloviEndpoints = TitloviEndpoints(
            apiBaseUrl = "https://kodi.titlovi.com/api/subtitles/",
            siteBaseUrl = "https://titlovi.com/"
        )
    }
}

private data class CachedToken(
    val id: String,
    val userId: Int,
    val credentialFingerprint: String,
    val expiresAtMs: Long
)

private data class TitloviSearchResult(
    val subtitle: ExternalSubtitle,
    val exactYear: Boolean,
    val rating: Float,
    val downloadCount: Int
)

private data class SubtitleArchiveEntry(val name: String, val bytes: ByteArray)

private data class TitloviDownloadDescriptor(
    val id: Int,
    val type: Int,
    val season: Int,
    val episode: Int
) {
    companion object {
        fun parse(value: String): TitloviDownloadDescriptor {
            val parts = value.split(':')
            require(parts.size == 4) { "Invalid Titlovi.com download id" }
            val descriptor = TitloviDownloadDescriptor(
                id = parts[0].toIntOrNull() ?: error("Invalid Titlovi.com subtitle id"),
                type = parts[1].toIntOrNull() ?: error("Invalid Titlovi.com subtitle type"),
                season = parts[2].toIntOrNull() ?: 0,
                episode = parts[3].toIntOrNull() ?: 0
            )
            require(descriptor.id > 0) { "Invalid Titlovi.com subtitle id" }
            require(descriptor.type == 1 || descriptor.type == 2) { "Invalid Titlovi.com subtitle type" }
            return descriptor
        }
    }
}

private class TitloviApiException(
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null
) : Exception(message, cause)

internal fun normalizeTitloviSearchTitle(title: String, year: Int?): String {
    var normalized = title.trim()
    if (year != null) {
        normalized = normalized.replace(
            Regex("""\s*[\[(]?${Regex.escape(year.toString())}[\])]?\s*$"""),
            ""
        )
    }
    return normalized.trim()
}

private fun String.toTitloviLanguage(): String? = when (trim().lowercase(Locale.ROOT)) {
    "hr", "hrv", "hrvatski" -> "Hrvatski"
    "bs", "bos", "bosanski" -> "Bosanski"
    "sr", "srp", "srpski" -> "Srpski"
    "mk", "mkd", "makedonski" -> "Makedonski"
    "sl", "slv", "slovenski" -> "Slovenski"
    "cyr", "cir", "cirilica" -> "Cirilica"
    else -> null
}

private fun String.toPlayerLanguage(): String = when (trim().lowercase(Locale.ROOT)) {
    "hrv", "hrvatski" -> "hr"
    "bos", "bosanski" -> "bs"
    "srp", "srpski", "cir", "cirilica" -> "sr"
    "mkd", "makedonski" -> "mk"
    "slv", "slovenski" -> "sl"
    else -> trim().lowercase(Locale.ROOT)
}

private fun JsonObject.string(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.let { value -> runCatching { value.asString }.getOrNull() }

private fun JsonObject.int(name: String): Int? =
    get(name)?.takeUnless { it.isJsonNull }?.let { value -> runCatching { value.asInt }.getOrNull() }

private fun JsonObject.float(name: String): Float? =
    get(name)?.takeUnless { it.isJsonNull }?.let { value -> runCatching { value.asFloat }.getOrNull() }

private fun parseExpiration(raw: String?, nowMs: Long): Long {
    if (raw.isNullOrBlank()) return nowMs + 20L * 60L * 1000L
    return runCatching { Instant.parse(raw).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .recoverCatching {
            LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        .getOrDefault(nowMs + 20L * 60L * 1000L)
}

private fun ByteArray.isZipArchive(): Boolean =
    size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte() &&
        (this[2] == 0x03.toByte() || this[2] == 0x05.toByte() || this[2] == 0x07.toByte())

private fun InputStream.readBounded(maxBytes: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "Subtitle payload is larger than the allowed limit" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun subtitleBytesAsUtf8(bytes: ByteArray): ByteArray {
    val withoutBom = if (
        bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) {
        bytes.copyOfRange(3, bytes.size)
    } else {
        bytes
    }
    val utf8 = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(withoutBom))
            .toString()
    }.getOrNull()
    val text = utf8 ?: String(withoutBom, charset("windows-1250"))
    return text.toByteArray(StandardCharsets.UTF_8)
}

private fun String.matchesEpisode(season: Int, episode: Int): Boolean {
    val escapedSeason = season.toString().padStart(2, '0')
    val escapedEpisode = episode.toString().padStart(2, '0')
    val sxe = Regex("(?i)s0*$season[._ -]*e0*$episode(?!\\d)")
    val numeric = Regex("(?i)(?<!\\d)0*$season[._ -]*x[._ -]*0*$episode(?!\\d)")
    val padded = Regex("(?i)s$escapedSeason[._ -]*e$escapedEpisode(?!\\d)")
    return sxe.containsMatchIn(this) || numeric.containsMatchIn(this) || padded.containsMatchIn(this)
}
