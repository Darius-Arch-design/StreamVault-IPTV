package com.streamvault.feature.catalog.presentation.series

import com.streamvault.domain.model.Series
import java.util.Locale

internal fun buildSeriesRecommendations(
    candidates: List<Series>,
    seedSeries: List<Series>,
    excludedIds: Set<Long>,
    limit: Int
): List<Series> {
    if (limit <= 0 || candidates.isEmpty()) return emptyList()

    val available = candidates
        .distinctBy { it.id }
        .filterNot { it.id in excludedIds }
    if (available.isEmpty()) return emptyList()

    if (seedSeries.isEmpty()) {
        return available
            .sortedWith(
                compareByDescending<Series> { it.rating }
                    .thenByDescending(::seriesReleaseScore)
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .take(limit)
    }

    return available
        .map { candidate -> candidate to recommendationScore(candidate, seedSeries) }
        .sortedWith(
            compareByDescending<Pair<Series, Float>> { it.second }
                .thenByDescending { seriesReleaseScore(it.first) }
                .thenBy { it.first.name.lowercase(Locale.ROOT) }
        )
        .take(limit)
        .map { it.first }
}

private fun recommendationScore(candidate: Series, seeds: List<Series>): Float {
    val candidateGenres = metadataTokens(candidate.genre)
    val candidateCast = metadataTokens(candidate.cast)
    val candidateDirector = metadataTokens(candidate.director)
    var score = 0f

    seeds.forEach { seed ->
        score += tokenOverlap(candidateGenres, metadataTokens(seed.genre)) * 4f
        score += tokenOverlap(candidateCast, metadataTokens(seed.cast)) * 2.5f
        score += tokenOverlap(candidateDirector, metadataTokens(seed.director)) * 2f
        if (candidate.categoryId != null && candidate.categoryId == seed.categoryId) {
            score += 1.5f
        }
    }

    return score + candidate.rating * 0.35f
}

private fun metadataTokens(value: String?): Set<String> =
    value.orEmpty()
        .lowercase(Locale.ROOT)
        .split(',', '/', '|')
        .flatMap { chunk -> chunk.split(' ') }
        .map(String::trim)
        .filter { it.length >= 3 }
        .toSet()

private fun tokenOverlap(left: Set<String>, right: Set<String>): Float {
    if (left.isEmpty() || right.isEmpty()) return 0f
    return left.intersect(right).size.toFloat()
}

private fun seriesReleaseScore(series: Series): Long =
    series.releaseDate
        ?.filter(Char::isDigit)
        ?.take(8)
        ?.toLongOrNull()
        ?: series.lastModified
