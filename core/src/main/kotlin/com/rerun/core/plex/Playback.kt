package com.rerun.core.plex

import com.rerun.core.model.MediaItem
import com.rerun.core.model.MediaKind
import java.net.URLEncoder

enum class TranscodeFormat { HLS, MP3 }

sealed interface PlaybackPlan {
    /** Play the file directly and seek the player to [startMs]. */
    data class Direct(val url: String, val startMs: Long) : PlaybackPlan
    /** Plex transcodes; the start offset is already in the URL, so the player starts at 0. */
    data class Transcoded(val url: String, val format: TranscodeFormat = TranscodeFormat.HLS) : PlaybackPlan
    /** Nothing playable for this slot: show the Up Next card. */
    data class UpNext(val reason: String) : PlaybackPlan
}

object Playback {
    private val containers = setOf("mkv", "mp4", "m4v", "mov", "ts")
    private val video = setOf("h264", "hevc", "mpeg2video", "vp9", "av1")
    private val audio = setOf("aac", "ac3", "eac3", "mp3", "opus", "flac", "vorbis")
    private val trackContainers = setOf("mp3", "mpeg", "m4a", "mp4", "aac", "flac", "ogg", "opus", "wav")

    fun canDirectPlay(item: MediaItem): Boolean =
        canCopyVideo(item) && item.audioCodec?.lowercase() in audio

    private fun canCopyVideo(item: MediaItem): Boolean =
        item.kind != MediaKind.TRACK &&
            ServerUri.isLibraryPath(item.partKey) &&
            item.container?.lowercase() in containers &&
            item.videoCodec?.lowercase() in video

    /** Audio files the player can stream without the video transcoder. */
    fun canPlayTrack(item: MediaItem): Boolean =
        item.kind == MediaKind.TRACK &&
            ServerUri.isLibraryPath(item.partKey) &&
            item.container?.lowercase() in trackContainers

    /** Token stays off the URL. The HTTP client adds X-Plex-Token for trusted hosts. */
    fun directUrl(base: String, item: MediaItem): String? {
        val path = item.partKey
        if (!ServerUri.isAcceptable(base) || !ServerUri.isLibraryPath(path)) return null
        return base.trimEnd('/') + path
    }

    fun audioTranscodeUrl(base: String, item: MediaItem, clientId: String, session: String, offsetMs: Long): String? {
        val meta = "/library/metadata/${item.key}"
        if (!ServerUri.isAcceptable(base) || item.key.isBlank() || !ServerUri.isLibraryPath(meta)) return null
        // Plex's music HTTP endpoint returns MP3 bytes despite the .m3u8 suffix.
        return base.trimEnd('/') + "/music/:/transcode/universal/start.m3u8" +
            "?path=${enc(meta)}&mediaIndex=0&partIndex=0&protocol=http" +
            "&offset=${offsetMs / 1000}&fastSeek=1&directPlay=0&directStream=0&audioCodec=mp3" +
            "&session=${enc(session)}&X-Plex-Session-Identifier=${enc(session)}" +
            "&X-Plex-Client-Identifier=${enc(clientId)}&X-Plex-Product=Rerun&X-Plex-Platform=Android"
    }

    fun transcodeUrl(
        base: String,
        item: MediaItem,
        clientId: String,
        session: String,
        offsetMs: Long,
        subtitles: Boolean = false,
        subtitleStreamId: Int? = null,
        audioStreamId: Int? = null,
        forceSdr: Boolean = false,
        forceVideoTranscode: Boolean = false,
        normalizeLoudness: Boolean = false,
    ): String? {
        val meta = "/library/metadata/${item.key}"
        if (!ServerUri.isAcceptable(base) || !ServerUri.isLibraryPath(meta)) return null
        val subs = if (subtitles) "burn" else "none"
        return base.trimEnd('/') + "/video/:/transcode/universal/start.m3u8" +
            "?path=${enc(meta)}" +
            "&mediaIndex=0&partIndex=0&protocol=hls" +
            "&offset=${offsetMs / 1000}" +
            "&fastSeek=1&directPlay=0&directStream=${if (forceSdr || forceVideoTranscode) 0 else 1}&directStreamAudio=0" +
            (if (forceSdr) "&videoCodec=h264&videoProfile=high&videoBitDepth=8" else "") +
            (if (normalizeLoudness) "&normalizeLoudness=1&audioCodec=aac" else "") +
            (if (!forceSdr && !forceVideoTranscode && !subtitles && canCopyVideo(item)) ""
                else "&videoResolution=1920x1080&maxVideoBitrate=20000") +
            "&subtitles=$subs" +
            (if (subtitles && subtitleStreamId != null) "&subtitleStreamID=$subtitleStreamId" else "") +
            (audioStreamId?.let { "&audioStreamID=$it" } ?: "") +
            "&session=${enc(session)}&X-Plex-Session-Identifier=${enc(session)}" +
            "&X-Plex-Client-Identifier=${enc(clientId)}&X-Plex-Product=Rerun&X-Plex-Platform=Android"
    }

    /** Reachable connections only: local direct, then remote direct, then relays. */
    fun connectionOrder(server: PlexServer): List<PlexConnection> =
        server.connections
            .filter { ServerUri.isAcceptable(it.uri) }
            .sortedBy { c -> if (c.relay) 2 else if (c.local) 0 else 1 }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

fun planPlayback(
    item: MediaItem?,
    base: String,
    clientId: String,
    session: String,
    offsetMs: Long,
    subtitles: Boolean = false,
    subtitleStreamId: Int? = null,
    forceTranscode: Boolean = false,
    audioStreamId: Int? = null,
    forceSdr: Boolean = false,
    normalizeLoudness: Boolean = false,
): PlaybackPlan {
    if (item == null) return PlaybackPlan.UpNext("missing")
    if (!ServerUri.isAcceptable(base)) return PlaybackPlan.UpNext("server")
    if (item.kind == MediaKind.TRACK) {
        if (!forceTranscode && Playback.canPlayTrack(item)) {
            val url = Playback.directUrl(base, item) ?: return PlaybackPlan.UpNext("server")
            return PlaybackPlan.Direct(url, offsetMs)
        }
        val url = Playback.audioTranscodeUrl(base, item, clientId, session, offsetMs)
            ?: return PlaybackPlan.UpNext("audio")
        return PlaybackPlan.Transcoded(url, TranscodeFormat.MP3)
    }
    if (!forceTranscode && !forceSdr && !normalizeLoudness && audioStreamId == null && Playback.canDirectPlay(item)) {
        val url = Playback.directUrl(base, item) ?: return PlaybackPlan.UpNext("server")
        return PlaybackPlan.Direct(url, offsetMs)
    }
    val url = Playback.transcodeUrl(
        base, item, clientId, session, offsetMs, subtitles, subtitleStreamId,
        audioStreamId, forceSdr, forceVideoTranscode = forceTranscode, normalizeLoudness = normalizeLoudness,
    )
        ?: return PlaybackPlan.UpNext("server")
    return PlaybackPlan.Transcoded(url)
}
