package com.streamvault.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExternalSubtitlePlaybackTest {

    @Test
    fun `external subtitle transition resumes from current playback position`() {
        val transition = buildExternalSubtitlePlaybackTransition(
            currentPositionMs = 123_456L,
            playWhenReady = true
        )

        assertThat(transition.resumePositionMs).isEqualTo(123_456L)
        assertThat(transition.playWhenReady).isTrue()
    }

    @Test
    fun `external subtitle transition enables text renderer`() {
        val textDisabledParameters = TrackSelectionParameters.DEFAULT
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()

        val subtitleEnabledParameters = textDisabledParameters.withExternalSubtitleEnabled("hr")

        assertThat(subtitleEnabledParameters.disabledTrackTypes).doesNotContain(C.TRACK_TYPE_TEXT)
        assertThat(subtitleEnabledParameters.preferredTextLanguages).contains("hr")
        assertThat(subtitleEnabledParameters.selectTextByDefault).isTrue()
    }

    @Test
    fun `external subtitle configuration preserves language and uses subrip`() {
        val configuration = buildExternalSubtitleConfiguration(
            subtitleUri = Uri.parse("https://example.test/subtitles/episode.srt"),
            language = "he"
        )

        assertThat(configuration.uri.toString()).isEqualTo("https://example.test/subtitles/episode.srt")
        assertThat(configuration.mimeType).isEqualTo(MimeTypes.APPLICATION_SUBRIP)
        assertThat(configuration.language).isEqualTo("he")
        assertThat(configuration.selectionFlags).isEqualTo(C.SELECTION_FLAG_DEFAULT)
        assertThat(configuration.id).isEqualTo(externalSubtitleTrackId(configuration.uri, "he"))
        assertThat(configuration.label).contains("Titlovi.com")
    }

    @Test
    fun `selected external subtitle is reattached when the media source is rebuilt and clears after a track change`() {
        val subtitleDataSourceFactory = DataSource.Factory { ByteArrayDataSource(byteArrayOf()) }
        val mainDataSourceFactory = DataSource.Factory { ByteArrayDataSource(byteArrayOf()) }
        val state = ExternalSubtitlePlaybackState()
        state.select(Uri.parse("file:///tmp/croatian.srt"), "hr")

        fun mainSource(name: String) = ProgressiveMediaSource.Factory(mainDataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse("https://example.test/$name.mp4")))

        val initial = state.attach(mainSource("movie"), subtitleDataSourceFactory)
        val recovered = state.attach(mainSource("retried-movie"), subtitleDataSourceFactory)

        assertThat(initial).isInstanceOf(MergingMediaSource::class.java)
        assertThat(recovered).isInstanceOf(MergingMediaSource::class.java)
        assertThat(state.selection?.language).isEqualTo("hr")
        assertThat(state.selection?.trackId).isEqualTo(externalSubtitleTrackId(Uri.parse("file:///tmp/croatian.srt"), "hr"))

        state.clearUnlessSelected("in-stream-subtitle")
        val afterUserSelection = mainSource("movie-after-track-change")
        assertThat(state.selection).isNull()
        assertThat(state.attach(afterUserSelection, subtitleDataSourceFactory)).isSameInstanceAs(afterUserSelection)
    }

    @Test
    fun `external subtitles use extraction based progressive media source`() {
        val configuration = buildExternalSubtitleConfiguration(
            subtitleUri = Uri.parse("https://example.test/subtitles/episode.srt"),
            language = "en"
        )
        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(byteArrayOf()) }

        val source = buildExternalSubtitleMediaSource(dataSourceFactory, configuration)

        assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
    }

    @Test
    fun `external subtitle data source treats open failure as empty optional content`() {
        var closed = false
        val failingFactory = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = throw IOException("missing subtitle")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    error("read must not reach failed delegate")
                override fun getUri(): Uri? = null
                override fun close() {
                    closed = true
                }
            }
        }
        val source = optionalExternalSubtitleDataSourceFactory(failingFactory).createDataSource()
        val dataSpec = DataSpec(Uri.parse("https://example.test/missing.srt"))

        assertThat(source.open(dataSpec)).isEqualTo(0L)
        assertThat(source.read(ByteArray(8), 0, 8)).isEqualTo(C.RESULT_END_OF_INPUT)
        source.close()

        assertThat(closed).isTrue()
    }

    @Test
    fun `external subtitle data source treats read failure as optional end of input`() {
        val failingFactory = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw IOException("subtitle connection interrupted")
                override fun getUri(): Uri? = null
                override fun close() = Unit
            }
        }
        val source = optionalExternalSubtitleDataSourceFactory(failingFactory).createDataSource()
        source.open(DataSpec(Uri.parse("https://example.test/interrupted.srt")))

        assertThat(source.read(ByteArray(8), 0, 8)).isEqualTo(C.RESULT_END_OF_INPUT)
    }
}
