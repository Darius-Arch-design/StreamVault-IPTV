package com.streamvault.data.repository

import android.database.sqlite.SQLiteException
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.SearchDao
import com.streamvault.data.local.dao.SearchHitEntity
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.VodSearchResult
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.repository.VodRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SearchRepositoryImplTest {
    private val searchDao: SearchDao = mock()
    private val channelRepository: ChannelRepository = mock()
    private val movieRepository: MovieRepository = mock()
    private val seriesRepository: SeriesRepository = mock()
    private val vodRepository: VodRepository = mock()
    private val providerDao: ProviderDao = mock()

    private val repository = SearchRepositoryImpl(
        searchDao = searchDao,
        channelRepository = channelRepository,
        movieRepository = movieRepository,
        seriesRepository = seriesRepository,
        vodRepository = vodRepository,
        providerDao = providerDao,
        stalkerIndexJobDao = mock()
    )

    @Test
    fun `empty unified FTS result does not trigger legacy LIKE fallback`() = runTest {
        whenever(searchDao.searchAll(eq(PROVIDER_ID), any(), any(), any(), eq(LIMIT)))
            .thenReturn(flowOf(emptyList()))

        val result = repository.searchContent(
            providerId = PROVIDER_ID,
            query = "missing",
            includeLive = true,
            includeMovies = true,
            includeSeries = true,
            maxResultsPerSection = LIMIT
        ).first()

        assertThat(result.channels).isEmpty()
        assertThat(result.movies).isEmpty()
        assertThat(result.series).isEmpty()
        verify(channelRepository, never()).searchChannels(any(), any())
        verify(movieRepository, never()).searchMovies(any(), any())
        verify(seriesRepository, never()).searchSeries(any(), any())
    }

    @Test
    fun `empty Stalker index searches portal and keeps requested content type`() = runTest {
        whenever(providerDao.getById(PROVIDER_ID)).thenReturn(
            ProviderEntity(id = PROVIDER_ID, name = "Portal", type = ProviderType.STALKER_PORTAL)
        )
        whenever(searchDao.searchMovies(eq(PROVIDER_ID), any(), any(), any(), eq(LIMIT)))
            .thenReturn(flowOf(emptyList()))
        whenever(vodRepository.searchVod(PROVIDER_ID, "star", 1)).thenReturn(
            Result.success(VodSearchResult(
                items = listOf(
                    VodCatalogItem.MovieItem(Movie(id = 2L, name = "Star Movie")),
                    VodCatalogItem.SeriesItem(Series(id = 3L, name = "Star Series"))
                ),
                totalCount = 2, page = 1, pageSize = 14, hasMore = false
            ))
        )

        val result = repository.searchContent(
            providerId = PROVIDER_ID, query = "star", includeLive = false,
            includeMovies = true, includeSeries = false, maxResultsPerSection = LIMIT
        ).first()

        assertThat(result.movies.map { it.name }).containsExactly("Star Movie")
        assertThat(result.series).isEmpty()
        assertThat(repository.responseTimeoutMillis(PROVIDER_ID)).isEqualTo(15_000L)
    }

    @Test
    fun `Stalker global search includes portal movies when local index finds only channels`() = runTest {
        whenever(providerDao.getById(PROVIDER_ID)).thenReturn(
            ProviderEntity(id = PROVIDER_ID, name = "Portal", type = ProviderType.STALKER_PORTAL)
        )
        whenever(searchDao.searchAll(eq(PROVIDER_ID), any(), any(), any(), eq(LIMIT)))
            .thenReturn(flowOf(listOf(hit(contentType = "LIVE", contentId = 4L, title = "Star News", matchRank = 0))))
        whenever(channelRepository.getChannelsByIds(listOf(4L)))
            .thenReturn(flowOf(listOf(Channel(id = 4L, name = "Star News"))))
        whenever(vodRepository.searchVod(PROVIDER_ID, "star", 1)).thenReturn(
            Result.success(VodSearchResult(
                items = listOf(VodCatalogItem.MovieItem(Movie(id = 2L, name = "Star Movie"))),
                totalCount = 1, page = 1, pageSize = 14, hasMore = false
            ))
        )

        val result = repository.searchContent(
            providerId = PROVIDER_ID, query = "star", includeLive = true,
            includeMovies = true, includeSeries = true, maxResultsPerSection = LIMIT
        ).first()

        assertThat(result.channels.map { it.name }).containsExactly("Star News")
        assertThat(result.movies.map { it.name }).containsExactly("Star Movie")
    }

    @Test
    fun `Stalker series search advances past movie-only portal page`() = runTest {
        whenever(providerDao.getById(PROVIDER_ID)).thenReturn(
            ProviderEntity(id = PROVIDER_ID, name = "Portal", type = ProviderType.STALKER_PORTAL)
        )
        whenever(searchDao.searchSeries(eq(PROVIDER_ID), any(), any(), any(), eq(LIMIT)))
            .thenReturn(flowOf(emptyList()))
        whenever(vodRepository.searchVod(PROVIDER_ID, "star", 1)).thenReturn(
            Result.success(VodSearchResult(
                items = listOf(VodCatalogItem.MovieItem(Movie(id = 2L, name = "Star Movie"))),
                totalCount = 15, page = 1, pageSize = 14, hasMore = true
            ))
        )
        whenever(vodRepository.searchVod(PROVIDER_ID, "star", 2)).thenReturn(
            Result.success(VodSearchResult(
                items = listOf(VodCatalogItem.SeriesItem(Series(id = 3L, name = "Star Series"))),
                totalCount = 15, page = 2, pageSize = 14, hasMore = false
            ))
        )

        val result = repository.searchContent(
            providerId = PROVIDER_ID, query = "star", includeLive = false,
            includeMovies = false, includeSeries = true, maxResultsPerSection = LIMIT
        ).first()

        assertThat(result.series.map { it.name }).containsExactly("Star Series")
    }

    @Test
    fun `unified FTS failure falls back to legacy section search`() = runTest {
        whenever(searchDao.searchAll(eq(PROVIDER_ID), any(), any(), any(), eq(LIMIT)))
            .thenReturn(flow { throw SQLiteException("fts unavailable") })
        whenever(channelRepository.searchChannels(PROVIDER_ID, "star"))
            .thenReturn(flowOf(listOf(Channel(id = 1L, name = "Star News"))))
        whenever(movieRepository.searchMovies(PROVIDER_ID, "star"))
            .thenReturn(flowOf(listOf(Movie(id = 2L, name = "Star Movie"))))
        whenever(seriesRepository.searchSeries(PROVIDER_ID, "star"))
            .thenReturn(flowOf(listOf(Series(id = 3L, name = "Star Series"))))

        val result = repository.searchContent(
            providerId = PROVIDER_ID,
            query = "star",
            includeLive = true,
            includeMovies = true,
            includeSeries = true,
            maxResultsPerSection = LIMIT
        ).first()

        assertThat(result.channels.map { it.id }).containsExactly(1L)
        assertThat(result.movies.map { it.id }).containsExactly(2L)
        assertThat(result.series.map { it.id }).containsExactly(3L)
    }

    @Test
    fun `unified FTS hits hydrate only matching ids and preserve ranking order`() = runTest {
        whenever(searchDao.searchAll(eq(PROVIDER_ID), any(), any(), any(), eq(LIMIT)))
            .thenReturn(
                flowOf(
                    listOf(
                        hit(contentType = "MOVIE", contentId = 20L, title = "Star B", matchRank = 1),
                        hit(contentType = "MOVIE", contentId = 10L, title = "Star A", matchRank = 0)
                    )
                )
            )
        whenever(movieRepository.getMoviesByIds(listOf(10L, 20L)))
            .thenReturn(
                flowOf(
                    listOf(
                        Movie(id = 20L, name = "Star B"),
                        Movie(id = 10L, name = "Star A")
                    )
                )
            )

        val result = repository.searchContent(
            providerId = PROVIDER_ID,
            query = "star",
            includeLive = true,
            includeMovies = true,
            includeSeries = true,
            maxResultsPerSection = LIMIT
        ).first()

        assertThat(result.channels).isEmpty()
        assertThat(result.movies.map { it.id }).containsExactly(10L, 20L).inOrder()
        assertThat(result.series).isEmpty()
        verify(channelRepository, never()).getChannelsByIds(any())
        verify(seriesRepository, never()).getSeriesByIds(any())
    }

    @Test
    fun `unified search uses the case-insensitive DAO path for mixed-case navbar queries`() = runTest {
        whenever(searchDao.searchAll(eq(PROVIDER_ID), any(), any(), any(), eq(LIMIT)))
            .thenReturn(flowOf(emptyList()))

        repository.searchContent(
            providerId = PROVIDER_ID,
            query = " NeWs ",
            includeLive = true,
            includeMovies = true,
            includeSeries = true,
            maxResultsPerSection = LIMIT
        ).first()

        verify(searchDao).searchAll(
            providerId = eq(PROVIDER_ID),
            ftsQuery = eq("NeWs*"),
            rawQuery = eq("NeWs"),
            prefixLike = eq("NeWs%"),
            limitPerSection = eq(LIMIT)
        )
        verify(channelRepository, never()).searchChannels(any(), any())
        verify(movieRepository, never()).searchMovies(any(), any())
        verify(seriesRepository, never()).searchSeries(any(), any())
    }

    private fun hit(
        contentType: String,
        contentId: Long,
        title: String,
        matchRank: Int
    ): SearchHitEntity =
        SearchHitEntity(
            contentType = contentType,
            contentId = contentId,
            title = title,
            sectionRank = 0,
            matchRank = matchRank
        )

    private companion object {
        const val PROVIDER_ID = 99L
        const val LIMIT = 120
    }
}
