package com.rerun.tv.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.rerun.core.catalog.Lineup
import com.rerun.core.model.ChannelDef
import com.rerun.core.model.ChannelKind
import com.rerun.core.model.MediaItem
import com.rerun.core.model.Rules
import com.rerun.core.plex.PlexParse
import com.rerun.core.schedule.EntryKind
import com.rerun.core.schedule.ScheduleEntry
import com.rerun.tv.data.ChannelRow
import com.rerun.tv.data.AdRow
import com.rerun.tv.data.PlexApi
import com.rerun.tv.data.ScheduleRepo
import com.rerun.tv.data.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.mockito.ArgumentCaptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TunerEngineTest {
    private lateinit var engine: TunerEngine
    private lateinit var player: ExoPlayer
    private lateinit var repo: ScheduleRepo
    private lateinit var settings: Settings
    private lateinit var api: PlexApi
    private lateinit var listener: Player.Listener
    private val sources = mutableListOf<MediaSource>()
    private var wallTime: Long? = null
    private var hdrSupported = false
    private val revision = MutableStateFlow(0L)

    @Before
    fun setUp() = runBlocking {
        player = mock(ExoPlayer::class.java)
        repo = mock(ScheduleRepo::class.java)
        `when`(repo.guideRevision).thenReturn(revision)
        settings = mock(Settings::class.java)
        api = mock(PlexApi::class.java)
        `when`(player.trackSelectionParameters).thenReturn(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT)
        `when`(player.currentTracks).thenReturn(Tracks.EMPTY)
        `when`(settings.serverBase()).thenReturn("http://127.0.0.1:32400")
        `when`(settings.isOn(Settings.SUBTITLES)).thenReturn(false)
        `when`(settings.isOn(Settings.NORMALIZE_LOUDNESS)).thenReturn(false)
        `when`(settings.subtitleLanguageValue()).thenReturn("auto")
        `when`(settings.audioLanguageValue()).thenReturn("auto")
        `when`(settings.hdrModeValue()).thenReturn("AUTO")
        `when`(api.get(anyString(), anyString(), anyString(), isNull(), isNull()))
            .thenReturn("""{"MediaContainer":{"Metadata":[]}}""")
        `when`(settings.serverToken).thenReturn("test-only-placeholder")
        `when`(settings.clientId).thenReturn("test-client")
        doAnswer { listener = it.getArgument(0); null }.`when`(player).addListener(any(Player.Listener::class.java))
        doAnswer { sources += it.getArgument<MediaSource>(0); null }.`when`(player).setMediaSource(any(MediaSource::class.java))
        engine = TunerEngine(
            RuntimeEnvironment.getApplication(), repo, settings, OkHttpClient(), api,
            clock = { wallTime ?: System.currentTimeMillis() },
            hdrSupported = { hdrSupported },
        ) { player }
    }

    @After
    fun tearDown() = engine.release()

    @Test
    fun audioBecomesPlayingWithoutAVideoFrame() = runBlocking {
        val item = PlexParse.tracks(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Song","duration":240000,"Genre":[{"tag":"Jazz"}],"Media":[{"container":"mp3","Part":[{"key":"/library/parts/1/file.mp3"}]}]}]}}""",
        ).single()
        prepare("music:jazz", item)
        engine.tune("music:jazz")
        await { sources.size == 1 }
        assertEquals(Phase.TUNING, engine.state.value.phase)
        listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(Phase.PLAYING, engine.state.value.phase)
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(Phase.UP_NEXT, engine.state.value.phase)
    }

    @Test
    fun unsupportedMusicTranscodesInsteadOfCountingDownThroughUnplayedSongs() = runBlocking<Unit> {
        val item = PlexParse.tracks(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Song","duration":240000,
            "Media":[{"container":"wma","audioCodec":"wmav2","Part":[{"key":"/library/parts/1/file.wma"}]}]}]}}""",
        ).single()
        prepare("music:jazz", item)
        wallTime = System.currentTimeMillis()
        engine.tune("music:jazz")
        await { sources.size == 1 }
        val uri = sources.single().mediaItem.localConfiguration!!.uri
        assertEquals("/music/:/transcode/universal/start.m3u8", uri.path)
        assertEquals("http", uri.getQueryParameter("protocol"))
        assertEquals("mp3", uri.getQueryParameter("audioCodec"))
        assertTrue(sources.single() is ProgressiveMediaSource)
        assertEquals(MimeTypes.AUDIO_MPEG, sources.single().mediaItem.localConfiguration!!.mimeType)
        assertEquals("60", uri.getQueryParameter("offset"))
        assertEquals(Phase.TUNING, engine.state.value.phase)
        listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(Phase.PLAYING, engine.state.value.phase)
        verify(player, never()).seekTo(anyLong())
        verify(api, never()).get(anyString(), anyString(), anyString(), isNull(), isNull())
    }

    @Test
    fun musicDecodeFailureRetriesWithAudioTranscodingAndResetsOnTheNextSong() = runBlocking<Unit> {
        val item = PlexParse.tracks(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Song","duration":240000,
            "Media":[{"container":"mp4","audioCodec":"alac","Part":[{"key":"/library/parts/1/file.m4a"}]}]}]}}""",
        ).single()
        prepare("music:jazz", item)
        engine.tune("music:jazz")
        await { sources.size == 1 }
        assertTrue(sources.last().mediaItem.localConfiguration!!.uri.path!!.startsWith("/library/parts"))
        engine.setVisible(false)
        val error = PlaybackException("Test", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        listener.onPlayerError(error)
        await { sources.size == 2 }
        assertEquals("/music/:/transcode/universal/start.m3u8", sources.last().mediaItem.localConfiguration!!.uri.path)
        listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(Phase.PLAYING, engine.state.value.phase)
        verify(player, never()).setVolume(1f)
        val httpError = HttpDataSource.InvalidResponseCodeException(
            400, "Bad Request", null, emptyMap(),
            DataSpec(android.net.Uri.parse("http://127.0.0.1/private?X-Plex-Token=do-not-display")), byteArrayOf(),
        )
        listener.onPlayerError(PlaybackException(
            "Do not display raw request", httpError, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        ))
        assertEquals(Phase.DIFFICULTIES, engine.state.value.phase)
        assertTrue(engine.state.value.message!!.contains("audio transcoder"))
        assertTrue(engine.state.value.message!!.contains("ERROR_CODE_IO_BAD_HTTP_STATUS; HTTP 400"))
        assertFalse(engine.state.value.message!!.contains("do-not-display"))
        assertFalse(engine.state.value.message!!.contains("127.0.0.1"))
        assertEquals(2, sources.size)
        val now = System.currentTimeMillis()
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry("music:jazz", now - 10_000, now + 200_000, EntryKind.PROGRAM, item.key),
        ))
        engine.tune("music:jazz")
        await { sources.size == 3 }
        assertTrue(sources.last().mediaItem.localConfiguration!!.uri.path!!.startsWith("/library/parts"))
    }

    @Test
    fun restartedTranscodeDoesNotReturnToZeroOnRetune() = runBlocking {
        val item = PlexParse.episodes(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Episode","duration":1800000}]}}""",
            emptyMap(),
        ).single()
        prepare(Lineup.RESTART_ID, item)
        engine.armReturn("east")
        engine.tune(Lineup.RESTART_ID)
        await { sources.size == 1 }
        assertTrue(sources[0].mediaItem.localConfiguration!!.uri.toString().contains("&offset=0&"))
        engine.tune(Lineup.RESTART_ID)
        await { sources.size == 2 }
        val offset = sources[1].mediaItem.localConfiguration!!.uri.getQueryParameter("offset")!!.toLong()
        assertTrue(offset >= 60)
    }

    @Test
    fun restartReturnsOnPlaybackEndRatherThanCuttingOffAtMetadataDeadline() = runBlocking {
        val item = PlexParse.episodes(
            """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Episode","duration":1800000}]}}""",
            emptyMap(),
        ).single()
        prepare(Lineup.RESTART_ID, item)
        var returned: String? = null
        engine.onReturned = { returned = it }
        engine.armReturn("east")
        engine.tune(Lineup.RESTART_ID)
        await { sources.size == 1 }
        listener.onRenderedFirstFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(11))
        assertNull(returned)
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals("east", returned)
    }

    @Test
    fun togglingSubtitlesLoadsAndRemovesExternalTextDuringDirectPlay() = runBlocking<Unit> {
        val metadata = videoWithSubtitle("srt")
        prepare("east", PlexParse.movies(metadata).single())
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        assertTrue(sources[0].mediaItem.localConfiguration!!.subtitleConfigurations.isEmpty())

        `when`(settings.isOn(Settings.SUBTITLES)).thenReturn(true)
        engine.setSubtitles(true)
        await { sources.size == 2 }
        val subtitle = sources[1].mediaItem.localConfiguration!!.subtitleConfigurations.single()
        assertEquals("http://127.0.0.1:32400/library/streams/7", subtitle.uri.toString())
        assertEquals("application/x-subrip", subtitle.mimeType)

        `when`(settings.isOn(Settings.SUBTITLES)).thenReturn(false)
        engine.setSubtitles(false)
        await { sources.size == 3 }
        assertTrue(sources[2].mediaItem.localConfiguration!!.subtitleConfigurations.isEmpty())
        verify(api, times(1)).get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")
    }

    @Test
    fun metadataIsReusedOnRetuneButReloadedAfterSyncAndClear() = runBlocking<Unit> {
        val metadata = videoWithSubtitle("srt")
        prepare("east", PlexParse.movies(metadata).single())
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        engine.tune("east")
        await { sources.size == 2 }
        verify(api, times(1)).get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")
        revision.value++
        engine.tune("east")
        await { sources.size == 3 }
        verify(api, times(2)).get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")
        engine.clear()
        engine.tune("east")
        await { sources.size == 4 }
        verify(api, times(3)).get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")
    }

    @Test
    fun imageSubtitlesForceBurnInWithTheSelectedStream() = runBlocking {
        val metadata = videoWithSubtitle("pgs")
        prepare("east", PlexParse.movies(metadata).single())
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        `when`(settings.isOn(Settings.SUBTITLES)).thenReturn(true)
        engine.tune("east")
        await { sources.size == 1 }
        val uri = sources.single().mediaItem.localConfiguration!!.uri
        assertEquals("burn", uri.getQueryParameter("subtitles"))
        assertEquals("7", uri.getQueryParameter("subtitleStreamID"))
    }

    @Test
    fun preferredLanguageSelectsExternalTrackInsteadOfPlexDefault() = runBlocking {
        val metadata = videoWithSubtitle("srt").replace(
            """"selected":true}""",
            """"languageCode":"eng","selected":true},{"id":8,"streamType":3,"codec":"srt","key":"/library/streams/8","languageCode":"spa"}""",
        )
        prepare("east", PlexParse.movies(metadata).single())
        `when`(settings.isOn(Settings.SUBTITLES)).thenReturn(true)
        `when`(settings.subtitleLanguageValue()).thenReturn("es")
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        val subtitle = sources.single().mediaItem.localConfiguration!!.subtitleConfigurations.single()
        assertEquals("http://127.0.0.1:32400/library/streams/8", subtitle.uri.toString())
        assertEquals("spa", subtitle.language)
        val englishTracks = Tracks(listOf(Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType("application/x-subrip").setLanguage("en").build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false),
        )))
        listener.onTracksChanged(englishTracks)
        val parameters = ArgumentCaptor.forClass(TrackSelectionParameters::class.java)
        verify(player, atLeastOnce()).trackSelectionParameters = parameters.capture()
        assertTrue(C.TRACK_TYPE_TEXT in parameters.value.disabledTrackTypes)

        val spanishTracks = Tracks(listOf(Tracks.Group(
            TrackGroup(Format.Builder().setId("plex-subtitle-8").setSampleMimeType("application/x-subrip").setLanguage("spa").build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false),
        )))
        listener.onTracksChanged(spanishTracks)
        verify(player, atLeastOnce()).trackSelectionParameters = parameters.capture()
        assertFalse(C.TRACK_TYPE_TEXT in parameters.value.disabledTrackTypes)
        assertTrue(parameters.value.overrides.isNotEmpty())
    }

    @Test
    fun automaticEmbeddedSubtitlesUsePlexSelectedTrackRatherThanTheFirstLanguage() = runBlocking {
        val metadata = videoWithSubtitle("srt").replace(
            """"key":"/library/streams/7","selected":true}""",
            """"languageCode":"eng"},{"id":8,"streamType":3,"codec":"srt","languageCode":"spa","selected":true}""",
        )
        prepare("east", PlexParse.movies(metadata).single())
        `when`(settings.isOn(Settings.SUBTITLES)).thenReturn(true)
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        val english = TrackGroup(Format.Builder().setSampleMimeType("application/x-subrip").setLanguage("eng").build())
        val spanish = TrackGroup(Format.Builder().setSampleMimeType("application/x-subrip").setLanguage("spa").build())
        listener.onTracksChanged(Tracks(listOf(english, spanish).map {
            Tracks.Group(it, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        }))
        val parameters = ArgumentCaptor.forClass(TrackSelectionParameters::class.java)
        verify(player, atLeastOnce()).trackSelectionParameters = parameters.capture()
        assertTrue(spanish in parameters.value.overrides)
        assertFalse(english in parameters.value.overrides)
    }

    @Test
    fun unavailablePreferredLanguageDoesNotBurnAnotherLanguage() = runBlocking {
        val metadata = videoWithSubtitle("pgs")
        prepare("east", PlexParse.movies(metadata).single().copy(audioCodec = "dca"))
        `when`(settings.isOn(Settings.SUBTITLES)).thenReturn(true)
        `when`(settings.subtitleLanguageValue()).thenReturn("ja")
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        assertEquals("none", sources.single().mediaItem.localConfiguration!!.uri.getQueryParameter("subtitles"))
    }

    @Test
    fun clearingPlaybackRemovesAnExcludedChannelFromState() = runBlocking {
        prepare("east", PlexParse.movies(videoWithSubtitle("srt")).single())
        engine.tune("east")
        await { sources.size == 1 }
        engine.clear()
        assertNull(engine.state.value.channel)
        assertNull(engine.state.value.item)
        assertEquals(Phase.OFF_AIR, engine.state.value.phase)
    }

    @Test
    fun adsSeekIntoTheBreakAndRollOverWithoutUnmutingMenus() = runBlocking {
        val ad = PlexParse.movies(videoWithSubtitle("srt")).single().copy(key = "ad", durationMs = 30_000)
        val now = System.currentTimeMillis()
        wallTime = now
        prepare("east", ad)
        `when`(repo.adCatalog()).thenReturn(listOf(AdRow.of(ad)))
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry("east", now - 10_000, now + 55_000, EntryKind.FILLER, null),
            ScheduleEntry("east", now + 55_000, now + 600_000, EntryKind.PROGRAM, ad.key),
        ))
        engine.setVisible(false)
        engine.tune("east")
        await { sources.size == 1 }
        assertEquals(EntryKind.FILLER, engine.state.value.located!!.entry.kind)
        assertEquals("ad", engine.state.value.item!!.key)
        val seeks = ArgumentCaptor.forClass(Long::class.javaObjectType)
        verify(player).seekTo(seeks.capture())
        assertTrue(seeks.value in 10_000L..15_000L)
        wallTime = now + 21_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        await { sources.size == 2 }
        wallTime = now + 51_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        await { engine.state.value.phase == Phase.UP_NEXT }
        wallTime = now + 56_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        await { sources.size == 3 }
        assertEquals(EntryKind.PROGRAM, engine.state.value.located!!.entry.kind)
        verify(player, never()).volume = 1f
        engine.setVisible(true)
        verify(player).volume = 1f
    }

    @Test
    fun emptyAdCatalogKeepsUpNextAndUnsupportedCodecsUseTranscoding() = runBlocking {
        val ad = PlexParse.movies(videoWithSubtitle("srt")).single().copy(key = "ad", durationMs = 30_000, audioCodec = "dca")
        prepare("east", ad)
        val now = System.currentTimeMillis()
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry("east", now - 10_000, now + 50_000, EntryKind.FILLER, null),
        ))
        `when`(repo.adCatalog()).thenReturn(emptyList())
        engine.tune("east")
        await { engine.state.value.phase == Phase.UP_NEXT }
        assertTrue(sources.isEmpty())
        `when`(repo.adCatalog()).thenReturn(listOf(AdRow.of(ad)))
        engine.tune("east")
        await { sources.size == 1 }
        assertTrue(sources.single().mediaItem.localConfiguration!!.uri.path!!.endsWith("start.m3u8"))
    }

    @Test
    fun failedAdDoesNotCancelTheNextProgramsDeadline() = runBlocking {
        val ad = PlexParse.movies(videoWithSubtitle("srt")).single().copy(key = "ad", durationMs = 10_000)
        val now = System.currentTimeMillis()
        wallTime = now
        prepare("east", ad)
        `when`(repo.adCatalog()).thenReturn(listOf(AdRow.of(ad)))
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry("east", now, now + 10_000, EntryKind.FILLER, null),
            ScheduleEntry("east", now + 10_000, now + 600_000, EntryKind.PROGRAM, ad.key),
        ))
        engine.tune("east")
        await { sources.size == 1 }
        listener.onPlayerError(PlaybackException("Test failure", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertEquals(Phase.DIFFICULTIES, engine.state.value.phase)
        wallTime = now + 11_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        await { sources.size == 2 }
        assertEquals(EntryKind.PROGRAM, engine.state.value.located!!.entry.kind)
    }

    @Test
    fun hdrAutoConvertsForUnsupportedDisplayAndTheOverrideConvertsOnSupportedDisplays() = runBlocking<Unit> {
        val metadata = videoWithSubtitle("srt").replace(
            """"id":7,"streamType":3""", """"id":8,"streamType":1,"colorTrc":"smpte2084"},{"id":7,"streamType":3""",
        )
        prepare("east", PlexParse.movies(metadata).single())
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        assertTrue(engine.state.value.convertingHdr)
        assertEquals("0", sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("directStream"))
        assertEquals("h264", sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("videoCodec"))
        hdrSupported = true
        engine.refreshPlaybackPreferences()
        await { sources.size == 2 }
        assertFalse(engine.state.value.convertingHdr)
        assertTrue(sources.last().mediaItem.localConfiguration!!.uri.path!!.contains("/library/parts"))
        `when`(settings.hdrModeValue()).thenReturn("CONVERT")
        engine.refreshPlaybackPreferences()
        await { sources.size == 3 }
        assertTrue(engine.state.value.convertingHdr)
        verify(api, times(1)).get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")
    }

    @Test
    fun preferredAndManualAudioTracksSurviveRetunesButNotTheNextAiring() = runBlocking {
        val metadata = videoWithSubtitle("srt").replace(
            """"id":7,"streamType":3""",
            """"id":21,"streamType":2,"languageCode":"eng","codec":"aac","selected":true},
            {"id":22,"streamType":2,"languageCode":"spa","codec":"aac"},{"id":7,"streamType":3""",
        )
        val item = PlexParse.movies(metadata).single()
        prepare("east", item)
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        `when`(settings.audioLanguageValue()).thenReturn("es")
        engine.tune("east")
        await { sources.size == 1 }
        assertEquals(22, engine.state.value.selectedAudioId)
        assertEquals("22", sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("audioStreamID"))
        val previousTune = engine.state.value.tuneId
        engine.selectAudioTrack(21, previousTune)
        await { sources.size == 2 }
        assertEquals("21", sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("audioStreamID"))
        engine.tune("east")
        await { sources.size == 3 }
        assertEquals(21, engine.state.value.selectedAudioId)
        try {
            engine.selectAudioTrack(22, previousTune)
            fail("Stale picker must not affect a new tune")
        } catch (_: IllegalStateException) { }
        val nextNow = System.currentTimeMillis()
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry("east", nextNow - 30_000, nextNow + 600_000, EntryKind.PROGRAM, item.key),
        ))
        engine.tune("east")
        await { sources.size == 4 }
        assertEquals(22, engine.state.value.selectedAudioId)
    }

    @Test
    fun nativeHdrErrorsRetryOnceAsSdrAndThenExplainServerRequirements() = runBlocking {
        hdrSupported = true
        val metadata = videoWithSubtitle("srt").replace(
            """"id":7,"streamType":3""", """"id":8,"streamType":1,"colorTrc":"smpte2084"},{"id":7,"streamType":3""",
        )
        prepare("east", PlexParse.movies(metadata).single())
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        val error = PlaybackException("Test failure", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        listener.onPlayerError(error)
        await { sources.size == 2 }
        assertTrue(engine.state.value.convertingHdr)
        listener.onPlayerError(error)
        assertEquals(Phase.DIFFICULTIES, engine.state.value.phase)
        assertTrue(engine.state.value.message!!.contains("Enable HDR tone mapping"))
        assertEquals(2, sources.size)
    }

    @Test
    fun hdrAdsUseTheSameConversionPolicyAndRejectHdrOutputFromTheServer() = runBlocking {
        val metadata = videoWithSubtitle("srt").replace(
            """"id":7,"streamType":3""", """"id":8,"streamType":1,"colorTrc":"smpte2084"},{"id":7,"streamType":3""",
        )
        val ad = PlexParse.movies(metadata).single().copy(durationMs = 30_000)
        prepare("east", ad)
        val now = System.currentTimeMillis()
        `when`(repo.adCatalog()).thenReturn(listOf(AdRow.of(ad)))
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry("east", now - 5_000, now + 55_000, EntryKind.FILLER, null),
        ))
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        assertTrue(engine.state.value.convertingHdr)
        val hdrTracks = Tracks(listOf(Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType("video/hevc")
                .setColorInfo(androidx.media3.common.ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build())
                .build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true),
        )))
        listener.onTracksChanged(hdrTracks)
        assertEquals(Phase.DIFFICULTIES, engine.state.value.phase)
        assertTrue(engine.state.value.message!!.contains("Plex returned HDR instead of SDR"))
    }

    private fun videoWithSubtitle(codec: String): String =
        """{"MediaContainer":{"Metadata":[{"ratingKey":"1","title":"Video","duration":1800000,
          "Media":[{"container":"mp4","videoCodec":"h264","audioCodec":"aac","Part":[
            {"key":"/library/parts/1/file.mp4","Stream":[
              {"id":7,"streamType":3,"codec":"$codec","key":"/library/streams/7","selected":true}
            ]}
          ]}]}]}}"""

    @Test
    fun adsStationPlaysShortClipsFromTheSeparateCatalogIncludingTheirLastSeconds() = runBlocking<Unit> {
        val metadata = videoWithSubtitle("srt")
        val clip = PlexParse.movies(metadata).single().copy(durationMs = 15_000)
        prepare(Lineup.ADS_ID, clip)
        val now = System.currentTimeMillis()
        wallTime = now
        `when`(repo.item(clip.key)).thenReturn(null)
        `when`(repo.adItem(clip.key)).thenReturn(clip)
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry(Lineup.ADS_ID, now - 11_000, now + 4_000, EntryKind.PROGRAM, clip.key),
            ScheduleEntry(Lineup.ADS_ID, now + 4_000, now + 19_000, EntryKind.PROGRAM, clip.key),
        ))
        engine.tune(Lineup.ADS_ID)
        await { sources.size == 1 }
        assertEquals(now - 11_000, engine.state.value.located!!.entry.startMs)
        assertEquals(clip, engine.state.value.item)
        assertEquals(clip, engine.state.value.nextItem)
        verify(player).seekTo(11_000)
        listener.onRenderedFirstFrame()
        engine.setVisible(false)
        wallTime = now + 4_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        await { sources.size == 2 }
        assertTrue(engine.state.value.sameChannelTransition)
        assertEquals(now + 4_000, engine.state.value.located!!.entry.startMs)
        verify(player, never()).setVolume(1f)
        verify(repo, never()).item(clip.key)
    }

    @Test
    fun normalizationAppliesToProgramsAndAdsButWarnsForUnanalyzedTracks() = runBlocking {
        val metadata = videoWithSubtitle("srt").replace(
            """"id":7,"streamType":3""",
            """"id":8,"streamType":2,"codec":"aac","selected":true,"canNormalizeLoudness":1},{"id":7,"streamType":3""",
        )
        val item = PlexParse.movies(metadata).single()
        prepare("east", item)
        `when`(settings.isOn(Settings.NORMALIZE_LOUDNESS)).thenReturn(true)
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        assertEquals("1", sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("normalizeLoudness"))
        assertNull(engine.state.value.message)
        engine.setVisible(false)
        val now = System.currentTimeMillis()
        `when`(repo.adCatalog()).thenReturn(listOf(AdRow.of(item.copy(durationMs = 30_000))))
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry("east", now - 5_000, now + 55_000, EntryKind.FILLER, null),
        ))
        engine.tune("east")
        await { sources.size == 2 }
        assertEquals("1", sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("normalizeLoudness"))
        verify(player).setVolume(0f)
        listener.onPlayerError(PlaybackException("Test", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertTrue(engine.state.value.message!!.contains("normalized audio playback failed"))
        engine.clear()
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder"))
            .thenReturn(metadata.replace(""""canNormalizeLoudness":1""", """"canNormalizeLoudness":0"""))
        engine.tune("east")
        await { sources.size == 3 }
        assertNull(sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("normalizeLoudness"))
        assertTrue(engine.state.value.message!!.contains("unavailable"))
    }

    @Test
    fun normalizationDoesNotOverrideHdrConversionOrSubtitleBurnIn() = runBlocking {
        val metadata = videoWithSubtitle("pgs").replace(
            """"id":7,"streamType":3""",
            """"id":8,"streamType":2,"codec":"aac","selected":true,"canNormalizeLoudness":1},
            {"id":9,"streamType":1,"colorTrc":"smpte2084"},{"id":7,"streamType":3""",
        )
        prepare("east", PlexParse.movies(metadata).single())
        `when`(settings.isOn(Settings.NORMALIZE_LOUDNESS)).thenReturn(true)
        `when`(api.get("http://127.0.0.1:32400", "/library/metadata/1", "test-only-placeholder")).thenReturn(metadata)
        engine.tune("east")
        await { sources.size == 1 }
        assertTrue(engine.state.value.convertingHdr)
        assertNull(sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("normalizeLoudness"))
        assertTrue(engine.state.value.message!!.contains("unavailable"))
        hdrSupported = true
        `when`(settings.isOn(Settings.SUBTITLES)).thenReturn(true)
        engine.tune("east")
        await { sources.size == 2 }
        assertEquals("burn", sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("subtitles"))
        assertNull(sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("normalizeLoudness"))
    }

    @Test
    fun sameStationFadesBeforeStoppingButStationChangesStillTuneImmediately() = runBlocking<Unit> {
        val item = PlexParse.movies(videoWithSubtitle("srt")).single()
        prepare("east", item)
        prepare("other", item)
        engine.tune("east")
        await { sources.size == 1 }
        assertFalse(engine.state.value.sameChannelTransition)
        listener.onRenderedFirstFrame()
        engine.setVisible(false)
        clearInvocations(player)
        engine.tune("east")
        assertTrue(engine.state.value.fadingOut)
        assertTrue(engine.state.value.sameChannelTransition)
        verify(player, never()).stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        verify(player, never()).stop()
        await { sources.size == 2 }
        assertEquals(Phase.TUNING, engine.state.value.phase)
        assertFalse(engine.state.value.fadingOut)
        listener.onRenderedFirstFrame()
        assertEquals(Phase.PLAYING, engine.state.value.phase)
        verify(player, never()).setVolume(1f)
        engine.tune("other")
        await { sources.size == 3 }
        assertFalse(engine.state.value.sameChannelTransition)
        assertFalse(engine.state.value.fadingOut)
    }

    @Test
    fun tuningJustBeforeFillerStartsRetriesAtTheBreakStart() = runBlocking<Unit> {
        val ad = PlexParse.movies(videoWithSubtitle("srt")).single().copy(durationMs = 30_000)
        prepare("east", ad)
        val now = System.currentTimeMillis()
        wallTime = now
        `when`(repo.adCatalog()).thenReturn(listOf(AdRow.of(ad)))
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry("east", now - 60_000, now + 4_000, EntryKind.PROGRAM, ad.key),
            ScheduleEntry("east", now + 4_000, now + 34_000, EntryKind.FILLER, null),
        ))
        engine.tune("east")
        await { engine.state.value.phase == Phase.UP_NEXT }
        assertTrue(sources.isEmpty())
        wallTime = now + 4_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        await { sources.size == 1 }
        assertEquals(EntryKind.FILLER, engine.state.value.located!!.entry.kind)
        assertTrue(engine.state.value.sameChannelTransition)
    }

    @Test
    fun scheduledProgramToAdRolloverFadesAndKeepsTheWallClockOffset() = runBlocking<Unit> {
        val item = PlexParse.movies(videoWithSubtitle("srt")).single().copy(audioCodec = "dca", durationMs = 30_000)
        prepare("east", item)
        val now = System.currentTimeMillis()
        wallTime = now
        `when`(repo.adCatalog()).thenReturn(listOf(AdRow.of(item)))
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(listOf(
            ScheduleEntry("east", now - 60_000, now + 10_000, EntryKind.PROGRAM, item.key),
            ScheduleEntry("east", now + 10_000, now + 40_000, EntryKind.FILLER, null),
        ))
        engine.tune("east")
        await { sources.size == 1 }
        listener.onRenderedFirstFrame()
        clearInvocations(player)
        wallTime = now + 12_000
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertTrue(engine.state.value.fadingOut)
        verify(player, never()).stop()
        await { sources.size == 2 }
        assertTrue(engine.state.value.sameChannelTransition)
        assertEquals(EntryKind.FILLER, engine.state.value.located!!.entry.kind)
        assertEquals("2", sources.last().mediaItem.localConfiguration!!.uri.getQueryParameter("offset"))
    }

    @Test
    fun changingStationDuringFadeCancelsTheOldMediaReplacement() = runBlocking<Unit> {
        val item = PlexParse.movies(videoWithSubtitle("srt")).single()
        prepare("east", item)
        prepare("other", item)
        engine.tune("east")
        await { sources.size == 1 }
        listener.onRenderedFirstFrame()
        engine.tune("east")
        assertTrue(engine.state.value.fadingOut)
        engine.tune("other")
        await { sources.size == 2 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(2, sources.size)
        assertEquals("other", engine.state.value.channel?.id)
        assertFalse(engine.state.value.sameChannelTransition)
    }

    private suspend fun prepare(id: String, item: MediaItem) {
        val row = ChannelRow.of(ChannelDef(id, 1, "Test", ChannelKind.CUSTOM, Rules()), false, false)
        val now = System.currentTimeMillis()
        `when`(repo.channel(id)).thenReturn(row)
        `when`(repo.item(item.key)).thenReturn(item)
        `when`(repo.entries(anyString(), anyLong(), anyLong())).thenReturn(
            listOf(ScheduleEntry(id, now - 60_000, now + 600_000, EntryKind.PROGRAM, item.key)),
        )
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(10)
        }
        assertTrue("Tuner did not finish preparing", condition())
    }
}
