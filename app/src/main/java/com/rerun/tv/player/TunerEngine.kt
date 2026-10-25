package com.rerun.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.OkHttpClient
import com.rerun.core.catalog.Lineup
import com.rerun.core.model.MediaItem
import com.rerun.core.model.MediaKind
import com.rerun.core.plex.PlaybackPlan
import com.rerun.core.plex.Playback
import com.rerun.core.plex.TranscodeFormat
import com.rerun.core.plex.ServerUri
import com.rerun.core.plex.selectSubtitle
import com.rerun.core.plex.subtitleLanguageCode
import com.rerun.core.plex.TokenRedaction
import com.rerun.core.plex.planPlayback
import com.rerun.core.plex.AudioStream
import com.rerun.core.plex.HdrMode
import com.rerun.core.plex.VideoDetails
import com.rerun.core.plex.DynamicRange
import com.rerun.core.plex.selectAudio
import com.rerun.core.plex.requiresSdr
import com.rerun.core.plex.SubtitleStream
import com.rerun.core.schedule.EntryKind
import com.rerun.core.schedule.Located
import com.rerun.core.schedule.locate
import com.rerun.core.schedule.adFiller
import com.rerun.tv.data.ChannelRow
import com.rerun.tv.data.PlexApi
import com.rerun.tv.data.ScheduleRepo
import com.rerun.tv.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.UUID

enum class Phase { TUNING, PLAYING, UP_NEXT, OFF_AIR, DIFFICULTIES }
const val CLIP_FADE_MS = 180

data class TunerState(
    val channel: ChannelRow? = null,
    val located: Located? = null,
    val item: MediaItem? = null,
    val nextItem: MediaItem? = null,
    /** When nothing is on yet, the wall-clock time the next entry starts. */
    val holdUntilMs: Long? = null,
    val phase: Phase = Phase.TUNING,
    /** Bumps on every channel change so overlays can replay their entrance. */
    val tuneId: Long = 0,
    val audioTracks: List<AudioStream> = emptyList(),
    val selectedAudioId: Int? = null,
    val convertingHdr: Boolean = false,
    val message: String? = null,
    val sameChannelTransition: Boolean = false,
    val fadingOut: Boolean = false,
)

/**
 * "Turn to channel X": find what's on now, start it at the right spot, and roll over to the
 * next entry when this one ends. One ExoPlayer for the app's lifetime.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TunerEngine(
    context: Context,
    private val repo: ScheduleRepo,
    private val settings: Settings,
    httpClient: OkHttpClient,
    private val api: PlexApi = PlexApi({ settings.clientId }, httpClient),
    private val clock: () -> Long = System::currentTimeMillis,
    private val hdrSupported: (VideoDetails) -> Boolean = { supportsHdr(context, it) },
    playerFactory: (Context) -> ExoPlayer = { ExoPlayer.Builder(it).build() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val http = ResolvingDataSource.Factory(
        OkHttpDataSource.Factory(httpClient),
        object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec = withoutQueryToken(dataSpec)

            override fun resolveReportedUri(uri: Uri): Uri = withoutQueryToken(uri)
        },
    )
    private val session = UUID.randomUUID().toString()
    private var tuneJob: Job? = null
    private var rollover: Runnable? = null
    private var retry: Runnable? = null
    private var tuneSeq = 0L
    private var subtitlesOn = false
    private var subtitleLanguage = "auto"
    private var subtitleAvailable = true
    private var selectedSubtitle: SubtitleStream? = null
    private var embeddedSubtitleIndex = -1
    private var transcoded = false
    private var restartReturn: String? = null
    private var restartFresh = false
    private data class PlaybackIdentity(val channel: String, val start: Long, val item: String?)
    private var identity: PlaybackIdentity? = null
    private var audioOverride: Int? = null
    private var hdrFallback = false
    private var audioFallback = false
    private var currentHdr = false
    private var convertingHdr = false
    private var normalizingAudio = false
    private val metadataCache = PlaybackMetadataCache()

    /** Called when the hidden start-over channel finishes and the TV should go back. */
    var onReturned: ((String) -> Unit)? = null

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state.asStateFlow()

    private val player: ExoPlayer = playerFactory(context).apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (_state.value.phase == Phase.TUNING) _state.value = _state.value.copy(phase = Phase.PLAYING)
                }

                override fun onPlayerError(error: PlaybackException) {
                    val httpStatus = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                        .firstOrNull()?.responseCode
                    val diagnostic = error.errorCodeName + (httpStatus?.let { "; HTTP $it" } ?: "")
                    android.util.Log.e("TunerEngine", "Playback failed ($diagnostic)")
                    val id = _state.value.channel?.id
                    if (_state.value.item?.kind == MediaKind.TRACK && !transcoded && !audioFallback && id != null) {
                        audioFallback = true
                        tune(id)
                    } else if (currentHdr && !convertingHdr && !hdrFallback && id != null) {
                        hdrFallback = true
                        tune(id)
                    } else difficulties(
                        if (convertingHdr) HDR_HELP else if (normalizingAudio) NORMALIZATION_HELP
                        else if (_state.value.item?.kind == MediaKind.TRACK)
                            "Music playback failed ($diagnostic). Check the Plex server connection and audio transcoder."
                        else "Playback failed. Retrying shortly.",
                    )
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (convertingHdr && tracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_VIDEO && (0 until group.length).any { index ->
                            group.isTrackSelected(index) && group.getTrackFormat(index).colorInfo?.colorTransfer in
                                setOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG)
                        }
                    }) {
                        difficulties("Plex returned HDR instead of SDR. $HDR_HELP")
                        return
                    }
                    applySubtitles(tracks)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && _state.value.phase == Phase.TUNING &&
                        _state.value.item?.kind == MediaKind.TRACK) {
                        _state.value = _state.value.copy(phase = Phase.PLAYING)
                    }
                    if (playbackState != Player.STATE_ENDED || _state.value.phase != Phase.PLAYING) return
                    // The start-over channel is a one-shot. When the episode ends, go back.
                    if (_state.value.channel?.id == Lineup.RESTART_ID) {
                        leaveRestart()
                        return
                    }
                    // File ended early (duration in Plex was longer than the real file): wait on the card.
                    _state.value = _state.value.copy(phase = Phase.UP_NEXT)
                }
            })
        }

    fun player(): ExoPlayer = player

    /** Muting survives media replacement and wall-clock rollovers while menus are open. */
    fun setVisible(visible: Boolean) {
        player.volume = if (visible) 1f else 0f
    }

    fun refreshPlaybackPreferences() {
        audioOverride = null
        hdrFallback = false
        audioFallback = false
        _state.value.channel?.id?.let(::tune)
    }

    fun selectAudioTrack(id: Int?, expectedTuneId: Long) {
        check(_state.value.tuneId == expectedTuneId) { "The program changed. Reopen audio tracks." }
        require(id == null || _state.value.audioTracks.any { it.id == id }) { "Audio track is unavailable" }
        audioOverride = id
        _state.value.channel?.id?.let(::tune)
    }

    /** Remember which channel to restore when the start-over episode ends. */
    fun armReturn(channelId: String) {
        restartReturn = channelId
        restartFresh = true
    }

    /** Subtitle switch. Direct play selects a text track; a transcode has to be requested again. */
    fun setSubtitles(on: Boolean) {
        subtitlesOn = on
        applySubtitles(player.currentTracks)
        val id = _state.value.channel?.id ?: return
        if (_state.value.item?.kind != MediaKind.TRACK && _state.value.phase != Phase.DIFFICULTIES) tune(id)
    }

    fun tune(channelId: String) {
        if (channelId != Lineup.RESTART_ID) restartReturn = null
        tuneJob?.cancel()
        clearTimers()
        val previous = _state.value
        val sameChannel = previous.channel?.id == channelId
        if (!sameChannel) player.stop()
        transcoded = false
        currentHdr = false
        convertingHdr = false
        normalizingAudio = false
        val seq = ++tuneSeq
        tuneJob = scope.launch {
            if (sameChannel && previous.phase == Phase.PLAYING) {
                _state.value = previous.copy(sameChannelTransition = true, fadingOut = true)
                delay(CLIP_FADE_MS.toLong())
            }
            if (sameChannel) player.stop()
            val t0 = clock()
            val channel = repo.channel(channelId)
            _state.value = TunerState(channel = channel, phase = Phase.TUNING, tuneId = seq, sameChannelTransition = sameChannel)
            if (channel == null) return@launch difficulties()
            if (channelId != Lineup.RESTART_ID) settings.setLastChannel(channelId)
            try {
                val subs = withContext(Dispatchers.IO) {
                    repo.ensure(channelId, t0)
                    settings.isOn(Settings.SUBTITLES)
                }
                subtitlesOn = subs
                subtitleLanguage = settings.subtitleLanguageValue()
                val now = clock()
                val entries = withContext(Dispatchers.IO) { repo.entries(channelId, now - 1, now + 6 * 3_600_000L) }
                val loc = locate(entries, now, skipTail = channelId != Lineup.ADS_ID)
                if (loc == null) {
                    if (channelId == Lineup.RESTART_ID) return@launch leaveRestart()
                    val upcoming = entries.firstOrNull { it.startMs > now }
                    if (upcoming == null) return@launch difficulties()
                    // Nothing stored for right now. Off Air waits for the next entry.
                    val program = if (upcoming.kind == EntryKind.PROGRAM) upcoming
                        else entries.firstOrNull { it.startMs >= upcoming.endMs && it.kind == EntryKind.PROGRAM }
                    player.stop()
                    _state.value = _state.value.copy(
                        phase = Phase.OFF_AIR,
                        nextItem = program?.let { channelItem(channelId, it.itemKey) },
                        holdUntilMs = upcoming.startMs,
                    )
                    scheduleRollover(channelId, upcoming.startMs)
                    return@launch
                }
                val ad = if (loc.entry.kind == EntryKind.FILLER) {
                    withContext(Dispatchers.IO) {
                        adFiller(loc.entry, repo.adCatalog().map { it.toItem() }, now)
                    }
                } else null
                val item = if (loc.entry.kind == EntryKind.FILLER) ad?.item else channelItem(channelId, loc.entry.itemKey)
                val nextIdentity = PlaybackIdentity(channelId, loc.entry.startMs, item?.key)
                if (identity != nextIdentity) {
                    identity = nextIdentity
                    audioOverride = null
                    hdrFallback = false
                    audioFallback = false
                }
                val nextItem = loc.next?.let { n ->
                    if (n.kind == EntryKind.PROGRAM) channelItem(channelId, n.itemKey)
                    else entries.firstOrNull { it.startMs >= n.endMs && it.kind == EntryKind.PROGRAM }?.let { channelItem(channelId, it.itemKey) }
                }
                _state.value = _state.value.copy(located = loc, item = item, nextItem = nextItem)
                scheduleRollover(channelId, if (loc.entry.kind == EntryKind.FILLER && loc.entry.startMs > now)
                    loc.entry.startMs else ad?.endMs ?: loc.entry.endMs)

                if (loc.entry.kind == EntryKind.FILLER && ad == null) {
                    player.stop()
                    _state.value = _state.value.copy(phase = Phase.UP_NEXT)
                    return@launch
                }
                val base = settings.serverBase()
                val token = settings.serverToken ?: settings.accountToken
                if (base == null || token == null) return@launch difficulties()
                val metadata = if (item != null && item.kind != MediaKind.TRACK) {
                    val path = "/library/metadata/${item.key}"
                    require(ServerUri.isLibraryPath(path)) { "Invalid Plex metadata path" }
                    metadataCache.get(base, token, repo.guideRevision.value, item.key) { api.get(base, path, token) }
                } else null
                val details = metadata?.video ?: VideoDetails()
                val preferredAudio = selectAudio(details.audio, settings.audioLanguageValue())
                val audio = details.audio.firstOrNull { it.id == audioOverride } ?: preferredAudio
                val mode = HdrMode.from(settings.hdrModeValue())
                currentHdr = details.range != DynamicRange.SDR
                val supported = !currentHdr || mode != HdrMode.AUTO || hdrSupported(details)
                convertingHdr = requiresSdr(details, mode, supported) || (currentHdr && hdrFallback)
                val normalizationRequested = item?.kind != MediaKind.TRACK && settings.isOn(Settings.NORMALIZE_LOUDNESS)
                val subtitleStreams = if (subtitlesOn) metadata?.subtitles.orEmpty() else emptyList()
                val subtitle = selectSubtitle(subtitleStreams, subtitleLanguage)
                val normalizationCompatible = !convertingHdr && subtitle == null && details.range != DynamicRange.DOLBY_VISION &&
                    item?.let { Playback.canDirectPlay(if (audio?.codec != null) it.copy(audioCodec = audio.codec) else it) } == true
                val normalize = normalizationRequested && audio?.canNormalizeLoudness == true && normalizationCompatible
                normalizingAudio = normalize
                _state.value = _state.value.copy(
                    audioTracks = details.audio, selectedAudioId = audio?.id, convertingHdr = convertingHdr,
                    message = listOfNotNull(
                        if (convertingHdr) "Converting HDR to SDR using Plex tone mapping." else null,
                        if (normalizationRequested && !normalize)
                            if (audio?.canNormalizeLoudness != true)
                                "Plex loudness normalization unavailable for this audio track. Enable audio loudness analysis in Plex (Plex Pass required)."
                            else "Plex loudness normalization unavailable with this video format, conversion, or subtitle burn-in."
                        else null,
                    ).joinToString(" ").ifEmpty { null },
                )
                selectedSubtitle = subtitle
                embeddedSubtitleIndex = subtitleStreams.filter { it.key == null }.indexOfFirst { it.id == subtitle?.id }
                subtitleAvailable = subtitle != null
                // Whatever time loading took, add it back so we land exactly on schedule.
                // Only the first tune starts at zero; retries and subtitle changes keep position.
                val fromStart = channelId == Lineup.RESTART_ID && restartFresh
                restartFresh = false
                val lag = clock() - now
                val offset = if (fromStart) 0L else (ad?.let { now - it.startMs } ?: loc.offsetMs) + lag
                when (val plan = planPlayback(
                    item?.let { if (audio?.codec != null) it.copy(audioCodec = audio.codec) else it },
                    base, settings.clientId, "$session-$seq", offset, subtitlesOn && subtitleAvailable,
                    subtitleStreamId = subtitle?.id,
                    forceTranscode = (item?.kind == MediaKind.TRACK && audioFallback) ||
                        (subtitle != null && subtitle.mimeType == null),
                    audioStreamId = audio?.id?.takeIf { details.audio.size > 1 },
                    forceSdr = convertingHdr,
                    normalizeLoudness = normalize,
                )) {
                    is PlaybackPlan.Direct -> {
                        transcoded = false
                        applySubtitles(player.currentTracks)
                        val media = ExoItem.Builder().setUri(plan.url)
                        if (subtitle?.key != null) {
                            require(ServerUri.isLibraryPath(subtitle.key)) { "Invalid Plex subtitle path" }
                            media.setSubtitleConfigurations(listOf(
                                ExoItem.SubtitleConfiguration.Builder(Uri.parse(base.trimEnd('/') + subtitle.key))
                                    .setId("plex-subtitle-${subtitle.id}")
                                    .setMimeType(subtitle.mimeType)
                                    .setLanguage(subtitle.language)
                                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                    .build(),
                            ))
                        }
                        player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(media.build()))
                        player.prepare()
                        player.seekTo(if (fromStart) 0L else plan.startMs + (clock() - now - lag))
                    }
                    is PlaybackPlan.Transcoded -> {
                        transcoded = true
                        applySubtitles(player.currentTracks)
                        val source = when (plan.format) {
                            TranscodeFormat.HLS ->
                                HlsMediaSource.Factory(http).createMediaSource(ExoItem.fromUri(plan.url))
                            TranscodeFormat.MP3 ->
                                ProgressiveMediaSource.Factory(http).createMediaSource(
                                    ExoItem.Builder().setUri(plan.url).setMimeType(MimeTypes.AUDIO_MPEG).build(),
                                )
                        }
                        player.setMediaSource(source)
                        player.prepare()
                    }
                    is PlaybackPlan.UpNext -> {
                        transcoded = false
                        player.stop()
                        if (item?.kind == MediaKind.TRACK) {
                            difficulties("Unable to prepare this music track. Re-sync the music library in Settings.")
                        } else _state.value = _state.value.copy(phase = Phase.UP_NEXT)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TunerEngine", "Unable to prepare playback (${e.javaClass.simpleName})")
                difficulties(
                    if (convertingHdr) HDR_HELP else if (normalizingAudio) NORMALIZATION_HELP
                    else "Couldn't load playback. Check the Plex server connection.",
                )
            }
        }
    }

    private suspend fun channelItem(channelId: String, key: String?): MediaItem? =
        if (channelId == Lineup.ADS_ID) repo.adItem(key) else repo.item(key)

    /** Linear channels follow wall time; start-over returns on playback end, not a metadata timer. */
    private fun scheduleRollover(channelId: String, endMs: Long) {
        if (channelId == Lineup.RESTART_ID) return
        val r = Runnable { tune(channelId) }
        rollover = r
        handler.postDelayed(r, (endMs - clock()).coerceAtLeast(0))
    }

    private fun leaveRestart() {
        val back = restartReturn ?: return
        restartReturn = null
        clearTimers()
        onReturned?.invoke(back)
        tune(back)
    }

    private fun applySubtitles(tracks: Tracks) {
        val builder = player.trackSelectionParameters.buildUpon()
        builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val language = selectedSubtitle?.language ?: subtitleLanguage.takeUnless { it == "auto" }
        builder.setPreferredTextLanguage(language)
        if (subtitlesOn && subtitleAvailable && !transcoded) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val candidates = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
                (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
            }
            val subtitle = selectedSubtitle
            val selection = if (subtitle?.key != null) {
                candidates.firstOrNull { (group, index) ->
                    group.getTrackFormat(index).id == "plex-subtitle-${subtitle.id}"
                }
            } else {
                val matches = candidates.filter { (group, index) ->
                    language == null || group.getTrackFormat(index).language?.let(::subtitleLanguageCode) == subtitleLanguageCode(language)
                }
                candidates.getOrNull(embeddedSubtitleIndex)?.takeIf { it in matches } ?: matches.firstOrNull()
            }
            if (selection != null) {
                builder.setOverrideForType(TrackSelectionOverride(selection.first.mediaTrackGroup, selection.second))
            } else {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            }
        } else {
            // Off, or already burned into a transcode.
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun difficulties(message: String? = null) {
        player.stop()
        _state.value = _state.value.copy(phase = Phase.DIFFICULTIES, message = message)
        val id = _state.value.channel?.id ?: return
        retry?.let(handler::removeCallbacks)
        val r = Runnable { tune(id) }
        retry = r
        handler.postDelayed(r, RETRY_MS)
    }

    private fun clearTimers() {
        rollover?.let(handler::removeCallbacks)
        retry?.let(handler::removeCallbacks)
        rollover = null
        retry = null
    }

    fun stop() {
        tuneJob?.cancel()
        clearTimers()
        player.stop()
    }

    fun clear() {
        stop()
        metadataCache.clear()
        restartReturn = null
        identity = null
        audioOverride = null
        hdrFallback = false
        audioFallback = false
        currentHdr = false
        convertingHdr = false
        normalizingAudio = false
        _state.value = TunerState(phase = Phase.OFF_AIR)
    }

    fun release() {
        stop()
        metadataCache.clear()
        scope.cancel()
        player.release()
    }

    private companion object {
        const val RETRY_MS = 15_000L
        const val NORMALIZATION_HELP = "Plex normalized audio playback failed. Check Plex Pass and audio loudness analysis, " +
            "or turn off Normalize loudness in Settings."
        const val HDR_HELP = "HDR conversion failed. Enable HDR tone mapping in Plex Server > Transcoder " +
            "(Plex Pass required), or use an SDR version. Dolby Vision compatibility depends on the server and profile."

        fun withoutQueryToken(dataSpec: DataSpec): DataSpec {
            val clean = withoutQueryToken(dataSpec.uri)
            return if (clean == dataSpec.uri) dataSpec else dataSpec.withUri(clean)
        }

        fun withoutQueryToken(uri: Uri): Uri {
            val raw = uri.toString()
            val clean = TokenRedaction.stripQuery(raw)
            return if (clean == raw) uri else Uri.parse(clean)
        }
    }
}
