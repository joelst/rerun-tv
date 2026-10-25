package com.rerun.core.plex

import com.rerun.core.movie
import com.rerun.core.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackTest {
    private val base = "https://192-168-1-20.abc.plex.direct:32400"

    @Test
    fun audioSelectionPreservesSourceVideoWhileVideoConversionKeepsLimits() {
        for (codec in listOf("hevc", "h264")) {
            for (audio in listOf("aac", "dca")) {
                val item = movie("1").copy(videoCodec = codec, audioCodec = audio)
                val plan = planPlayback(item, base, "c", "s", 0, audioStreamId = 9)
                assertTrue(plan is PlaybackPlan.Transcoded)
                val url = (plan as PlaybackPlan.Transcoded).url
                assertTrue("&directStream=1&" in url)
                assertTrue("&audioStreamID=9&" in url)
                assertFalse("videoResolution=" in url)
                assertFalse("maxVideoBitrate=" in url)
                val sdr = planPlayback(item, base, "c", "s", 0, audioStreamId = 9, forceSdr = true)
                assertTrue(sdr is PlaybackPlan.Transcoded && "videoResolution=1920x1080" in sdr.url)
            }
        }
        val incompatible = planPlayback(movie("1").copy(videoCodec = "mpeg4"), base, "c", "s", 0, audioStreamId = 9)
        assertTrue(incompatible is PlaybackPlan.Transcoded && "maxVideoBitrate=20000" in incompatible.url)
    }

    @Test
    fun normalizationRequestsAudioTranscodeWithoutVideoLimits() {
        val plan = planPlayback(movie("1"), base, "c", "s", 30_000, normalizeLoudness = true, audioStreamId = 8)
        assertTrue(plan is PlaybackPlan.Transcoded)
        val url = (plan as PlaybackPlan.Transcoded).url
        assertTrue("&normalizeLoudness=1&" in url)
        assertTrue("&audioCodec=aac&" in url)
        assertTrue("&directStream=1&directStreamAudio=0" in url)
        assertTrue("&audioStreamID=8&" in url)
        assertFalse("videoResolution=" in url)
        assertFalse("maxVideoBitrate=" in url)
        assertTrue(planPlayback(movie("1"), base, "c", "s", 0) is PlaybackPlan.Direct)
        assertTrue(planPlayback(track("1"), base, "c", "s", 0, normalizeLoudness = true) is PlaybackPlan.Direct)
    }

    @Test
    fun audioNormalizationCapabilityIsPerStreamAndDefaultsOff() {
        for (value in listOf("true", "1", "\"1\"")) {
            val details = PlexParse.videoDetails(
                """{"MediaContainer":{"Metadata":[{"Media":[{"Part":[{"Stream":[
                {"id":8,"streamType":2,"canNormalizeLoudness":$value},
                {"id":9,"streamType":2}]}]}]}]}}""",
            )
            assertTrue(details.audio[0].canNormalizeLoudness)
            assertFalse(details.audio[1].canNormalizeLoudness)
        }
    }

    @Test
    fun h264AacDirectPlays() {
        val m = movie("1002").copy(container = "mp4", videoCodec = "h264", audioCodec = "aac", partKey = "/library/parts/78/1/file.mp4")
        assertTrue(Playback.canDirectPlay(m))
        assertEquals("$base/library/parts/78/1/file.mp4", Playback.directUrl(base, m))
    }

    @Test
    fun dtsForcesTranscode() {
        assertFalse(Playback.canDirectPlay(movie("1").copy(audioCodec = "dca")))
        assertFalse(Playback.canDirectPlay(movie("1").copy(audioCodec = "truehd")))
    }

    @Test
    fun oddContainerOrCodecForcesTranscode() {
        assertFalse(Playback.canDirectPlay(movie("1").copy(container = "avi")))
        assertFalse(Playback.canDirectPlay(movie("1").copy(videoCodec = "mpeg4")))
        assertFalse(Playback.canDirectPlay(movie("1").copy(partKey = null)))
    }

    @Test
    fun hostilePartKeyIsNotDirect() {
        assertFalse(Playback.canDirectPlay(movie("1").copy(partKey = "https://evil.example/x")))
        assertFalse(Playback.canDirectPlay(movie("1").copy(partKey = "//evil.example/x")))
        assertFalse(Playback.canDirectPlay(movie("1").copy(partKey = "/library/parts/../secret")))
        assertNull(Playback.directUrl(base, movie("1").copy(partKey = "https://evil.example/x")))
    }

    @Test
    fun transcodeUrlCarriesOffsetSecondsAndNoToken() {
        val url = Playback.transcodeUrl(base, movie("1001"), "client-1", "sess-9", 2_820_500)
        assertTrue(url!!.startsWith("$base/video/:/transcode/universal/start.m3u8?path=%2Flibrary%2Fmetadata%2F1001&"), url)
        assertTrue("&offset=2820&" in url, url)
        assertTrue("&session=sess-9&" in url, url)
        assertTrue("X-Plex-Client-Identifier=client-1" in url, url)
        assertTrue("subtitles=none" in url, url)
        assertFalse("X-Plex-Token" in url, url)
    }

    @Test
    fun subtitlesBurnWhenAsked() {
        val off = Playback.transcodeUrl(base, movie("1001"), "c", "s", 0, subtitles = false)
        val on = Playback.transcodeUrl(base, movie("1001"), "c", "s", 0, subtitles = true)
        assertTrue("subtitles=none" in off!!, off)
        assertTrue("subtitles=burn" in on!!, on)
        val plan = planPlayback(movie("1001").copy(audioCodec = "dca"), base, "c", "s", 0, subtitles = true)
        assertTrue(plan is PlaybackPlan.Transcoded && "subtitles=burn" in plan.url)
        val image = planPlayback(
            movie("1001"), base, "c", "s", 60_000, subtitles = true, subtitleStreamId = 7, forceTranscode = true,
        )
        assertTrue(image is PlaybackPlan.Transcoded && "subtitles=burn&subtitleStreamID=7" in image.url)
    }

    @Test
    fun trackDirectPlaysAudioAndSkipsVideoTranscode() {
        val song = track("9")
        val plan = planPlayback(song, base, "c", "s", 15_000)
        assertEquals(PlaybackPlan.Direct("$base/library/parts/9/file.mp3", 15_000), plan)
        assertTrue(planPlayback(song.copy(container = "MPEG"), base, "c", "s", 0) is PlaybackPlan.Direct)
    }

    @Test
    fun unsupportedOrUnknownMusicFormatsUseAudioOnlyTranscoding() {
        for (container in listOf("wma", "asf", "aiff", "ape", null)) {
            val plan = planPlayback(track("9").copy(container = container), base, "c", "s", 15_500)
            assertTrue(plan is PlaybackPlan.Transcoded)
            val url = (plan as PlaybackPlan.Transcoded).url
            assertTrue("/music/:/transcode/universal/start.m3u8?" in url)
            assertEquals(TranscodeFormat.MP3, plan.format)
            assertTrue("&protocol=http&" in url)
            assertTrue("path=%2Flibrary%2Fmetadata%2F9&" in url)
            assertTrue("&offset=15&" in url)
            assertTrue("&audioCodec=mp3&" in url)
            assertFalse("videoResolution" in url)
            assertFalse("normalizeLoudness" in url)
            assertFalse("X-Plex-Token" in url)
        }
        val fallback = planPlayback(track("9"), base, "c", "s", 0, forceTranscode = true)
        assertTrue(fallback is PlaybackPlan.Transcoded && "/music/" in fallback.url && fallback.format == TranscodeFormat.MP3)
        assertEquals(PlaybackPlan.UpNext("audio"), planPlayback(track("../9"), base, "c", "s", 0, forceTranscode = true))
    }

    @Test
    fun planDirectKeepsOffsetForPlayer() {
        val plan = planPlayback(movie("1002"), base, "c", "s", 47 * 60_000L)
        assertEquals(PlaybackPlan.Direct("$base/library/parts/1002/file.mkv", 47 * 60_000L), plan)
    }

    @Test
    fun planTranscodeForDts() {
        val plan = planPlayback(movie("1001").copy(audioCodec = "dca"), base, "c", "s", 60_000)
        assertTrue(plan is PlaybackPlan.Transcoded && "offset=60&" in plan.url && "X-Plex-Token" !in plan.url)
    }

    @Test
    fun missingItemPlansUpNext() =
        assertEquals(PlaybackPlan.UpNext("missing"), planPlayback(null, base, "c", "s", 0))

    @Test
    fun publicHttpServerIsNotPlayable() {
        assertEquals(
            PlaybackPlan.UpNext("server"),
            planPlayback(movie("1002"), "http://203.0.113.9:32400", "c", "s", 0),
        )
    }

    @Test
    fun connectionOrderDropsPublicHttp() {
        val server = PlexServer(
            "x", "id", "t",
            listOf(
                PlexConnection("http://203.0.113.5:32400", local = false, relay = false),
                PlexConnection("http://192.168.1.20:32400", local = true, relay = false),
                PlexConnection("https://example.plex.direct:32400", local = false, relay = false),
                PlexConnection("file:///tmp/library", local = true, relay = false),
            ),
        )
        assertEquals(
            listOf("http://192.168.1.20:32400", "https://example.plex.direct:32400"),
            Playback.connectionOrder(server).map { it.uri },
        )
    }
}
