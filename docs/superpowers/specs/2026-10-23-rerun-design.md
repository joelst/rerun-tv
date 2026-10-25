# Rerun architecture and behavior

Current implementation reference, updated 2026-10-24. This replaces the original speculative design.
Installation, features, remote controls, and Plex prerequisites live in [README](../../../README.md);
contribution rules and validation commands live in [AGENTS.md](../../../AGENTS.md).

## Scope

Rerun is a native Android/Fire TV player for a personal Plex library, not a virtual tuner server.
The minimum Android API is 26; compile/target SDKs and dependency versions are defined by the build files.
Basic playback does not itself require Plex Pass; optional Plex HDR tone mapping and loudness normalization do.
There is no separate Rerun server and no watch-history reporting.

Implemented sources are Plex movies, episodes, music tracks, and a separately selected ad library.
HDHomeRun/live TV, TMDb enrichment, commercial content filtering, archive streaming, and idle timeout
are not implemented. The local commercial helper downloads files and queues upscaling; it does not
automatically split compilations or classify ads. Users must review their own ad content.

## Ownership and data flow

| Component | Responsibility |
|---|---|
| `core` | Models, catalog/routing/resolution, deterministic scheduler, guide/tune math, ad planning, Plex parsing and playback plans |
| `RerunApp` | Process-level Room, settings, HTTP, repository and sync wiring; awaited startup lineup refresh |
| `LibrarySync` | Serialized paged imports, persistent library selections, ad catalog updates and lineup rebuilds |
| `ScheduleRepo` | Transactional channel/library changes, cached library snapshot, schedule top-ups, West mirroring and guide revision |
| `TvController` | Activity-owned navigation, foreground eligibility, startup state, scans, progress/errors and playback reconciliation |
| `TunerEngine` | Single Media3 player, metadata cache, wall-clock seeks/rollovers, tracks, compatibility policy and playback errors |
| Compose screens | Render state and remote focus; local overlay/music timers, not whole-player clock polling |

Shared typography pairs VT323 for display headings/OSD/channel numbers with Barlow Semi Condensed
for body, caption and guide-cell text. Both are bundled OFL fonts; readable text has no pixel-offset shadow.

Room stores media, channels, schedules, scheduler state, ad clips and settings.
Explicit migrations preserve prior data. Credentials are kept out of play URLs and redacted from
diagnostics; HTTP token attachment follows the trusted Plex host policy.

## Scheduling and ads

- Active pools require at least four hours of content. Channels can be built-in, generated, custom, or music.
- Schedules extend approximately 48 hours and top up below 24 hours. Epoch times are UTC; time blocks use the device zone.
- Movies use a shuffled deck; shows preserve episode pointers. Music plays back to back.
- Video padding ends at the next half-hour boundary and becomes a filler entry.
- Routine top-ups do not reshuffle existing entries. Changing included libraries rebuilds schedules;
  missing items and removed channels invalidate affected playback. Channel edits rebuild the future after the current entry.
- West mirrors East three hours later, not an independent shuffle. New West feeds can initially be Off Air.
- Ads are chosen deterministically at playback time from the persisted catalog, seek to the current
  break position, and never extend the program deadline. Short/unfillable remainders show Up Next.
- A nonempty enabled ad catalog also creates **Buy Me Maybe**, a generated commercials-only station.
  Its preferred number is 998; collisions advance to a free number, skipping reserved numbers.
  It shuffles clips back-to-back without the ordinary minimum-duration/four-hour threshold, tail
  skipping, padding, or a West feed. Items stay in the ad table, not the main library. Catalog
  changes invalidate only its schedule; disabling ads or emptying the catalog removes it.
- Tuning in the last five seconds of a program skips its tail. A future filler entry waits until its
  start before retrying ad selection, rather than sleeping through the break.
- The guide merges program and following filler into one cell. Music and Buy Me Maybe use hourly station blocks rather than individual songs or ads; Off Air gaps remain visible.
- Each guide opening selects the current station and wall-clock program after loading; subsequent browsing is not reset by schedule reloads.

## Startup, updates and lifecycle

Startup starts in loading state for an existing sign-in, awaits the automatic saved-lineup refresh,
then queries active channels. Only a successful settled empty result displays no-channel guidance.
Failures have their own message. Missing connection/initial-sync configuration returns to onboarding.

Music, East/West, re-sync, ad catalog and included-library updates are controller-owned and survive screen navigation.
Guide rebuilding hides rows behind progress/elapsed time and permits only Channels and Settings controls.
Failed flag updates restore the previous value; failed imports preserve selections until commit.
Playback reconciliation preserves open menus. Completing a scan after activity stop does not tune;
resume starts the appropriate current/replacement station. Sign-out cancels and joins controller work.

## Playback compatibility and transitions

Direct playback is preferred for the supported container/video/audio policy in `Playback`.
Music formats outside the direct-play policy (or missing format metadata) use the Plex audio
music universal transcoder with HTTP/MP3 output, explicitly loaded as progressive audio despite
the endpoint's `.m3u8` suffix. A failed direct music stream retries through audio
conversion once per airing; conversion failures show a player error code and available HTTP status
instead of a silent Up Next loop. Diagnostics omit raw exception messages, URLs and credentials.
Multi-audio selection uses an explicit Plex stream ID. Audio-only conversion allows video copy without
1080p/bitrate caps; required video conversion retains its compatibility limits.
Subtitles can use external or embedded text, otherwise Plex burn-in.

HDR Auto checks connected display and decoder support. Convert requests SDR tone mapping;
Prefer native bypasses the capability-driven conversion choice. Native HDR errors retry SDR once.
Normalization is off by default and requests Plex audio transcoding only for eligible selected tracks;
HDR conversion, Dolby Vision and selected subtitles currently prevent normalization. Skipped normalization
and playback failures are visible, not silent volume guarantees.

Station changes retain configurable static/roll effects. Same-station clips fade to black before media
replacement, hold black while loading, and fade in on the first frame (or music readiness).
Load/fade time is included in wall-clock seeking; menu muting survives replacement.
Start-over alone begins at zero and returns to the prior station when the one-shot ends.

## Performance contracts and validation limits

Scheduler candidate indexes and reused lineup calculations preserve deterministic output.
Library list/map readers share a coalesced off-Main snapshot; invalidation prevents stale publication.
Parsed playback metadata is cached in memory for at most 16 items and 60 seconds, scoped to server,
credentials and guide revision. Display capability is checked live; remote track defaults can take
up to one minute to appear on a subsequent tune.

The test suite covers policy, persistence, sync/navigation, lifecycle, remote focus and selected rendering.
See the [readiness checklist](../plans/2026-10-23-rerun-phase1-core-tv.md) for remaining physical-TV checks.
