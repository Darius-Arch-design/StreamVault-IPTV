package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ExternalSubtitle
import com.streamvault.domain.model.ExternalSubtitleSource
import org.junit.Test

class PlayerExternalSubtitlePickerLabelTest {

    @Test
    fun `normalizes separators and removes subtitle file extension`() {
        val label = subtitle("1080p.BluRay.x264.YIFY.srt").toPlayerSubtitlePickerLabel()

        assertThat(label).isEqualTo("Hrvatski · 1080p BluRay x264 YIFY")
    }

    @Test
    fun `limits long release names to a readable menu length`() {
        val label = subtitle("release.".repeat(50)).toPlayerSubtitlePickerLabel()
        val releaseLabel = label.substringAfter(" · ")

        assertThat(releaseLabel.length).isAtMost(65)
        assertThat(releaseLabel).endsWith("…")
    }

    @Test
    fun `uses language name when release name is empty`() {
        assertThat(subtitle("   ").toPlayerSubtitlePickerLabel()).isEqualTo("Hrvatski")
    }

    private fun subtitle(releaseName: String) = ExternalSubtitle(
        id = "subtitle",
        language = "hr",
        displayLanguage = "Hrvatski",
        releaseName = releaseName,
        source = ExternalSubtitleSource.TITLOVI,
        downloadId = "1:2:3:4"
    )
}
