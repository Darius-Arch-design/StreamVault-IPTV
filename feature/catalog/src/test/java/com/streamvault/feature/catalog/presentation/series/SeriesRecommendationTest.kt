package com.streamvault.feature.catalog.presentation.series

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Series
import org.junit.Test

class SeriesRecommendationTest {
    @Test
    fun ranksMetadataSimilarToRecentlyWatchedSeriesFirst() {
        val watched = Series(
            id = 1,
            name = "Mystery House",
            categoryId = 10,
            genre = "Drama, Mystery",
            cast = "Alex Doe",
            director = "Jamie Roe"
        )
        val similar = Series(
            id = 2,
            name = "Another Mystery",
            categoryId = 10,
            genre = "Mystery",
            rating = 5f
        )
        val unrelatedButPopular = Series(
            id = 3,
            name = "Comedy Special",
            categoryId = 99,
            genre = "Comedy",
            rating = 10f
        )

        val result = buildSeriesRecommendations(
            candidates = listOf(unrelatedButPopular, similar),
            seedSeries = listOf(watched),
            excludedIds = setOf(watched.id),
            limit = 1
        )

        assertThat(result).containsExactly(similar)
    }

    @Test
    fun excludesWatchedAndFavoriteSeriesFromFallbackRecommendations() {
        val watched = Series(id = 1, name = "Watched", rating = 10f)
        val favorite = Series(id = 2, name = "Favorite", rating = 9f)
        val available = Series(id = 3, name = "Available", rating = 8f)

        val result = buildSeriesRecommendations(
            candidates = listOf(watched, favorite, available),
            seedSeries = emptyList(),
            excludedIds = setOf(watched.id, favorite.id),
            limit = 1
        )

        assertThat(result).containsExactly(available)
    }
}
