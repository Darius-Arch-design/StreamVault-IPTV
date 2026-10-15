package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import org.junit.Test

class PlayerAutomaticSubtitlePolicyTest {

    @Test
    fun `recognizes embedded ex yu subtitle language tags`() {
        listOf("hr", "hr-HR", "hrv", "bs", "bos", "sr", "sr-Latn", "srp", "sh", "cnr", "sl", "mk")
            .forEach { language ->
                assertThat(hasEmbeddedExYuSubtitleTrack(listOf(track(language = language)))).isTrue()
            }
    }

    @Test
    fun `recognizes ex yu labels when language metadata is missing`() {
        val tracks = listOf(
            track(language = null, name = "Hrvatski"),
            track(language = "und", name = "Serbian Latin"),
            track(language = null, name = "Bosanski")
        )

        assertThat(hasEmbeddedExYuSubtitleTrack(tracks)).isTrue()
    }

    @Test
    fun `does not treat english subtitles or ex yu audio as an embedded ex yu subtitle`() {
        val tracks = listOf(
            track(language = "en", name = "English"),
            track(language = "hr", name = "Croatian audio", type = TrackType.AUDIO)
        )

        assertThat(hasEmbeddedExYuSubtitleTrack(tracks)).isFalse()
    }

    private fun track(
        language: String?,
        name: String = "Subtitle",
        type: TrackType = TrackType.TEXT
    ) = PlayerTrack(
        id = "$name-$language-$type",
        name = name,
        language = language,
        type = type,
        isSelected = false
    )
}
