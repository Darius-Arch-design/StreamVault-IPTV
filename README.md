# StreamVault

<p align="center">
	<a href="https://discord.gg/FrkumnnTz"><img src="https://img.shields.io/badge/Discord-Join%20Server-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Join the StreamVault Discord" /></a>
	<a href="docs/CHANGELOG.md"><img src="https://img.shields.io/badge/Changelog-View-2563eb?style=for-the-badge" alt="View changelog" /></a>
	<a href="https://github.com/Darius-Arch-design/StreamVault-IPTV/actions/workflows/master-test-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/Darius-Arch-design/StreamVault-IPTV/master-test-apk.yml?branch=master&style=for-the-badge&label=Master%20tests" alt="GitHub Actions status" /></a>
	<a href="https://ko-fi.com/davidona"><img src="https://img.shields.io/badge/Support-Ko--fi-ff5f5f?style=for-the-badge&logo=kofi" alt="Support on Ko-fi" /></a>
	<a href="LICENSE"><img src="https://img.shields.io/badge/License-StreamVault_OSL-0284c7?style=for-the-badge" alt="License" /></a>
</p>

> This repository is a non-commercial derivative based on [StreamVault](https://github.com/Davidona/StreamVault-IPTV), originally developed by [David Nashash (Davidona)](https://github.com/Davidona). The original developer's [Ko-fi page](https://ko-fi.com/davidona) remains available for support.

StreamVault is a TV-first IPTV player for Android TV built with Kotlin, Jetpack Compose, Room, Hilt, and Media3.

It is designed for large playlists, remote-friendly browsing, fast provider switching, and a polished living-room playback experience. StreamVault supports `M3U` playlists, `Xtream Codes`, `Stalker Portal`, and `Jellyfin` providers, with dedicated flows for `Live TV`, `Movies`, and `Series`.

Built for Android TV first, StreamVault focuses on the things generic IPTV apps usually get wrong: D-pad navigation, quick channel movement, large-library organization, and a player that still feels good to use from the couch. Phone and tablet installs are also supported, but the primary UX target is TV.

## Current Beta: 1.0.19-beta.000002.01

- Faster Xtream live-catalog synchronization uses bounded parallel requests and adapts to slow or rate-limited providers.
- Stalker catalog metadata loads in parallel with adaptive throttling so portal browsing and background synchronization stay more responsive.
- Movies and series episodes can automatically load subtitles from Titlovi.com, trying Croatian, then Bosnian, then Serbian. Add your Titlovi.com account under **Settings → Playback → Subtitles → Titlovi.com account**; availability depends on that service and your account access. Live TV is not covered by this integration.
- This beta is built from `develop` and uses the separate `com.streamvault.app.beta` package for testing on Android TV.

Installable packages, when available, are published through the repository's GitHub Releases section, not linked directly from this README.

## Preview
<p align="center">
	<a href="https://github.com/Davidona/StreamVault-IPTV/raw/master/docs/images/LiveTV.png"><img src="docs/images/LiveTV.png" alt="Live TV" width="88%" /></a>
</p>

<p align="center">
	<a href="https://github.com/Davidona/StreamVault-IPTV/raw/master/docs/images/Movies.png"><img src="docs/images/Movies.png" alt="Movies" width="44%" /></a>
	<a href="https://github.com/Davidona/StreamVault-IPTV/raw/master/docs/images/MovieInfo.png"><img src="docs/images/MovieInfo.png" alt="Movie Details" width="44%" /></a>
</p>

<p align="center">
	<a href="https://github.com/Davidona/StreamVault-IPTV/raw/master/docs/images/Home.png"><img src="docs/images/Home.png" alt="Home" width="19%" /></a>
	<a href="https://github.com/Davidona/StreamVault-IPTV/raw/master/docs/images/LiveTV.png"><img src="docs/images/LiveTV.png" alt="Live TV" width="19%" /></a>
	<a href="https://github.com/Davidona/StreamVault-IPTV/raw/master/docs/images/ChannelView.png"><img src="docs/images/ChannelView.png" alt="Channel Preview" width="19%" /></a>
	<a href="https://github.com/Davidona/StreamVault-IPTV/raw/master/docs/images/Guide.png"><img src="docs/images/Guide.png" alt="Guide" width="19%" /></a>
	<a href="https://github.com/Davidona/StreamVault-IPTV/raw/master/docs/images/Settings.png"><img src="docs/images/Settings.png" alt="Settings" width="19%" /></a>
</p>

<p align="center">
	<a href="https://github.com/Davidona/StreamVault-IPTV/raw/master/docs/images/SeriesEpisodes.png"><img src="docs/images/SeriesEpisodes.png" alt="Series Episodes" width="32%" /></a>
</p>

## Highlights

- Android TV-first interface with D-pad-friendly focus, navigation, and playback flows
- Provider support for `Xtream Codes`, `Stalker Portal`, `Jellyfin`, and `M3U` sources, including local playlist files
- Combined M3U live-source support with optional in-browser source switching for merged Live TV setups
- Jellyfin onboarding with password sign-in or Quick Connect QR/code flow for TV-friendly setup
- Fast live-TV browsing with preview mode, favorites, recent channels, custom groups, and pinned categories
- Transparent full-guide overlay that can open over live playback without leaving the player
- Movie and series libraries with detailed info pages, resume support, episode switching, and auto-play for next episodes
- Offline VOD downloads with grouped episode queues, pause or resume controls, and local playback for completed files
- Full EPG support with guide search, XMLTV support, and provider archive or catch-up when available
- Built-in DVR with scheduled recording, background capture, recording playback, and app-managed default storage
- Multi-view split-screen playback for watching multiple channels at once
- Plugin API for creating companion Android APKs that extend providers, playback, Cast URL handling, or configuration flows
- Strong parental controls with PIN-protected categories and automatic adult-category detection
- TV integrations including Watch Next, launcher recommendations, TV input sync, Cast support, external-player handoff, and in-app update delivery

## Features

### Provider Support

- `Xtream Codes`
- `Stalker Portal`
- `Jellyfin` media servers with direct library sync and TV-friendly Quick Connect support
- `M3U` playlists from URLs plus local files
- Separate onboarding and sync flows for live channels, movies, series, and guide data
- Faster Xtream live synchronization and Stalker catalog loading with bounded parallel work and adaptive throttling
- Fast switching between providers with provider-scoped settings
- Combined M3U profiles for merging multiple M3U providers into a single Live TV source
- QR-based provider pairing from a phone on the same LAN for faster TV setup

### Navigation And TV UX

- Designed for Android TV and D-pad navigation first
- Fast channel browsing with large-playlist friendly layouts
- Numeric remote input for direct channel entry
- Configurable startup landing screen so the app can open Home, Live TV, Movies, Series, Guide, Downloads, Plugins, or Settings first
- Colored remote button remapping with global defaults plus playback and live-browse overrides
- Preview mode while browsing channels
- TV-friendly search and text-entry flows

### Live TV And Channel Management

- Favorites and recently watched channels
- Custom groups for personal channel collections
- Pinned categories surfaced near the top of the live guide rail
- Optional Live TV provider or source browser for M3U-based setups
- Long-press live categories for actions like pin, hide, lock or unlock, and custom-group management
- Channel reordering for favorites and custom groups
- Channel numbering modes by group or across the full provider lineup
- Predefined filter words to make category search cleaner on noisy provider data

### Guide, Search, And Playback

- Full EPG grid view
- Transparent full-guide overlay over live playback
- Program search inside the guide
- XMLTV guide support with built-in EPG source management
- Manual EPG match overrides and source-priority controls from inside Settings and Guide flows
- Provider archive or catch-up support when the source exposes replay streams
- Live rewind or timeshift playback with up to 30 minutes of buffer, even when provider catch-up is unavailable
- Global search across live TV, movies, and series
- Multi-view for watching multiple live streams at once
- Player controls for subtitles, audio tracks, aspect ratio, playback speed, video quality, Cast, and external-player handoff
- Automatic Titlovi.com subtitles for movies and series episodes after configuring an eligible account in Playback → Subtitles

### Recording And Playback

- Scheduled and background DVR recording for live channels
- Offline VOD downloads with grouped episode handling and completed-file local playback
- Program reminders from guide entries when you want a notification without scheduling a recording
- Conflict detection, persistence, and repair support for recording jobs
- App-managed default recording folder with optional custom storage selection
- In-app playback for completed recordings with a visible on-player recording indicator during active capture
- Playback troubleshooting controls for decoder mode, media session behavior, and timeout tuning
- Bundled Media3 FFmpeg audio fallback for unsupported audio codecs such as AC-3, E-AC-3, DTS, MP2, and TrueHD, with diagnostics and expert compatibility controls

### Movies And Series

- Two VOD layouts:
	- Modern shelf-based browsing
	- Classic left-sidebar category browsing
- Detailed info pages for movies and series
- Continue watching, playback history, and detail-screen resume actions with saved position context
- Long-press VOD categories and custom groups for actions like hide, rename, delete, or reorder when applicable
- In-player episode switching for series
- Automatic next-episode playback when another episode is available

### Parental Controls

- Hide categories completely
- Lock categories behind a PIN
- Option to hide locked content from browsing views
- Adult-category detection using provider flags and category naming heuristics

### Languages And Device Support

- English plus 25 translated locale packs currently ship with the app
- Locale coverage is broader and rendering is more reliable across supported languages
- Built for TV first; phones and tablets are supported, but not the primary design target

### Platform Integrations

- Android TV Watch Next integration
- Launcher recommendations and TV entry points
- Android TV Input Framework channel sync
- Google Cast sender support

### Plugins

- StreamVault can be extended with companion Android APK plugins.
- Plugin developers can expose provider, playback, Cast URL rewrite, and host-rendered or native configuration capabilities.
- See the [StreamVault Plugin API](docs/PLUGIN_API.md) docs to create compatible plugins.

## Quick TV Tips

- Long-press a channel, movie, or series to add it to Favorites or a custom group.
- Long-press a live category to open category actions such as pin, hide, lock or unlock, and custom-group actions like reorder.
- In Movies and Series, long-press categories or custom groups for hide or group-management actions where available.
- Long-press a live channel to queue it for Split Screen.
- Use the number keys on a remote while in the player to jump directly to a channel.
- While watching a series, open Episodes in the player to switch episodes without backing out to the details page.

## Releases

Installable binary packages, when available, are distributed solely through the repository's GitHub Releases section. This README does not include direct APK download links or URL-shortener redirects.

Beta pre-releases are built and published from `develop`. The master branch has a separate test APK workflow. Stable releases use the manual `workflow_dispatch` workflow after the required checks pass.

## Support

If StreamVault is useful to you, you can support development here:

- [Support on Ko-fi](https://ko-fi.com/davidona)

## Project Structure

- `app/` Android app UI, navigation, dependency injection, and Android TV integrations
- `data/` Room database, sync, parsing, provider implementations, and repositories
- `domain/` models, repository contracts, managers, and use cases
- `player/` playback abstraction and Media3 player implementation
- `docs/` architecture notes, plugin API docs, and image assets

## Build

Requirements:

- Android Studio
- Android SDK
- JDK 17 or another Gradle-supported JDK 17 runtime
- Android NDK only if you want to rebuild the bundled Media3 FFmpeg extension locally

Useful commands:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew testDebugUnitTest
```

## Notes

- StreamVault is an IPTV client, not a content provider.
- Use only playlists, streams, and guide sources you are authorized to access.
- Local configuration and signing files are intentionally excluded from git.

## License

This project was originally released without an explicit license.  
As of April 2026, all usage, modification, and distribution are governed by the StreamVault Source-Available License (Non-Commercial).

Any use of this project must comply with the terms defined in the LICENSE file.
