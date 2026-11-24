# PingPong: TV Player and Remote

Companion media streaming apps for Android TVs and phones.

- **Ping!** (`nb.pingpong.ping`) runs on your phone. It finds the TV app on your Wi-Fi and sends it links, files, torrents and commands.
- **Pong!** (`nb.pingpong.pong`) runs on an Android TV, or on any Android phone or tablet you've repurposed as a TV box. It plays the video with VLC.

Playback control stays on your local network. There is no account to create and no server of ours. The only outside services are SubSource, if you use subtitle search, and the BitTorrent peers of any torrent you play.

## Features

**Casting**
- Share a link from any app to Ping! and it plays on the TV.
- Or open a TV in Ping! and tap **Paste & play a link** to send the link on your clipboard.
- Share video files from the phone itself, alone or several at once, together with their subtitle files. Ping! serves them to the TV over your local network, matches each subtitle to its video by name, and plays the first video. The others are in the playlist.
- Magnet links: share one to Ping!, paste it, or tap it in a browser (Ping! opens magnet links). The TV streams the torrent while it downloads (see **Torrents** below).
- Links with extra HTTP headers, IPTV style, are supported: `https://host/stream.m3u8|Referer=https://site/&User-Agent=MyPlayer`
- TVs are listed in order of how often you use them. If only one is found, Ping! opens it directly.
- Several phones can control the same TV. Each one follows the TV's latest state, so a rewind on one phone shows up on the others.

**Torrents** (played by the TV, which runs the torrent engine)
- While the torrent loads, both the TV and the phone say what is happening: finding peers, getting the file list, buffering, and how many peers there are and how fast it's downloading.
- A torrent with several videos waits for you to choose one. The phone opens its Playlist tab, and you can also choose from the TV's Playlist panel. Subtitle files in the torrent that match the chosen video are added automatically.
- Only the chosen video is downloaded. The TV checks there's enough free space for that one episode.
- Switching to another video in the same torrent keeps the session going. Fully downloaded videos are kept, so you can go back and replay them, unless their space is needed for the new one. Partly downloaded ones are erased.
- Sending a new link or torrent wipes the previous downloads.
- When a video ends, the torrent stays loaded: press play to watch it again, or pick another video.

**Remote control from the phone** (the phone shows controls only, no video or audio)

The remote has four tabs in a navigation bar at the bottom. Each grey section heading can be tapped to collapse its section, and Ping! remembers which sections you collapsed.
- **Controls**: title, playback state and what the TV is doing (for example torrent progress), seek bar, play/pause (which replays a video that ended), ±10 s, **Paste & play a link**, and volume in 5% steps or with a slider
- **Playback**: speed from 0.05x to 16x with fine steps and 16 presets, audio track, audio sync, and dynamic range (Standard / High) for HDR-capable screens
- **Subtitles**: subtitle track, then three sections:
  - **Search**: **Paste subtitle link** for your own file, or search [SubSource](https://subsource.net) with a language code and a search term
    - For TV shows, add the season or episode to the search, for example `Breaking Bad S02` or `Breaking Bad S02E05`
    - The language box takes a code such as `en` (the default) or `bn`, or a SubSource language name such as `farsi_persian`
    - Results show the release name, hearing-impaired and download count, most downloaded first
  - **Style**: size, colour, outline, background opacity and bold, with a live preview
  - **Sync**: subtitle timing, adjustable without limit
- **Playlist**: the videos in the current torrent or phone share, with sizes and subtitle counts. Tap one to play it. A single link shows just that one video.

The three-dot menu has:
- **Edit name** (while a TV is connected): rename the TV. The TV remembers the new name.
- **Check for updates**: checks right away, on the phone and on the connected TV
- **Settings**

Controls are disabled while the TV is unreachable.

**Settings** (phone)
- **Navigation bar**: Bottom (Material style) or Floating (a pill like Google Photos), each in a Solid or Frosted look
- **Colors**: use the device colours (Material You, Android 12 and newer), or pick an accent colour from six presets or a custom colour
- **About Ping!**: opens the app's GitHub repository
- Light and dark themes follow the system.

**On the TV**
- Ready, loading and error screens in black with a warm glow, which say what went wrong when a link won't load
- Player menu:
  - top: back arrow, title, a status line (such as torrent progress and peers) with the time the video ends, and the clock
  - bottom: a seek bar that also shows how much of a torrent video has downloaded, elapsed and remaining time, then play/pause, −10 s, +10 s, speed, **Playlist**, **Audio** and **Subtitles**
- The speed button cycles 1x → 1.25x → 1.5x → 1.75x → 2x → 1x. Any other speed (set from the phone) goes back to 1x.
- The **Playlist** panel lists the videos of the current torrent or phone share. The button is hidden when there's only one video.
- The **Audio** panel lists the video's audio tracks, with the playing one checked.
- The **Subtitles** panel lists Off and every subtitle track, labelled with where it came from (the torrent, sent from Ping!, or the video file). The Menu key on the remote opens it too.
- Full remote support: left/right seeks 10 s without opening the menu, with on-screen feedback. On the seek bar, left/right seeks 30 s. Back closes a panel or the menu. On the player, Back asks before ending the session.
- Subtitles in ASS/SSA, WebVTT and TTML format are converted to SRT before they are shown (SRT and other formats are used as they are)
- If the app closed unexpectedly, the next start shows why
- Runs full screen on phones and tablets too, with the picture extending into the camera cutout and the menu kept clear of it
- Also works with touch: tap for the menu, double-tap the sides to seek, double-tap the middle to play/pause

## How it works

Pong! advertises itself on the network as a `_pingpong._tcp.` service. Ping! discovers it and talks to it over UDP (port 8888) using short text commands such as `PLAY:<url>`, `SEEK:<ms>`, `TORRENT_FILE:<index>` and `STATUS`. The TV answers `STATUS` with its current state, which the phone polls to stay in sync. That state includes the torrent's progress and file list, and the same status line the TV shows.

Files shared from the phone are served by Ping! over HTTP on your local network, and the TV streams them from there. For a share of several videos, the phone also sends the TV the list of names (`SHARE_LIST`), so the TV can show its own Playlist panel. A video picked on the TV is reported back in its status, and the phone switches to it.

Torrents run inside Pong! with [libtorrent4j](https://github.com/aldenml/libtorrent4j). The TV downloads the chosen video in order, starting with the beginning and end of the file, and plays it through a small local HTTP server while the rest arrives.

Subtitle search runs on the phone. When you pick a result, the phone downloads and unpacks the subtitle, then briefly serves the finished file over your local network and sends the TV a `SUB_URL:<url>` command. The TV fetches it from the phone, so the TV needs no internet access for subtitles found through search.

## Requirements

- Android 5.0 (API 21) or newer, on both the phone and the TV or TV-box device
- Both devices on the same local network (and multicast allowed on the router)
- Internet access on the phone for subtitle search, and on the TV for links and torrents
- Free storage on the TV for torrent playback (enough for the video being played)

## Building

Both apps are standard Gradle/Android projects (`phone-app/` and `tv-app/`) built with JDK 17, Gradle 8.7 and Android Gradle Plugin 8.5.2. The phone app downloads one library from JitPack (`https://jitpack.io`), which is set up in `phone-app/build.gradle`.

The **Build APKs** GitHub Actions workflow builds both apps. It only runs when you start it manually (Actions tab → Build APKs → Run workflow) and produces two zips, `Ping-Phone-v<version>` and `Pong-TV-v<version>`. Each contains one APK per ABI plus a universal APK, named like `Pong-TV-v1.2.1-arm64-v8a.apk`.

### Subtitle search keys

Subtitle search needs SubSource API keys, which are supplied when the phone app is built. Add a repository secret named `SUBSOURCE_API_KEYS` containing your API key in a JSON array:

```
["your-api-key"]
```

You can create keys in the API tab of your SubSource account dashboard. If the secret is missing, the apps still build, but subtitle search is disabled in the phone app.

### Signing

If these repository secrets all exist, the release APKs are signed with your key:

| Secret | Contents |
| --- | --- |
| `KEYSTORE_FILE` | your keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Otherwise the workflow generates a throwaway test key. Test-signed builds work fine for personal use, but they can't be updated in place by builds signed with a different key.

## Notes

- Subtitle search uses the [SubSource](https://subsource.net) API. It is subject to SubSource's [terms](https://subsource.net/terms) and rate limits, so if search is busy, try again in a while.
- Torrent playback shares the downloaded pieces with other peers while the torrent is loaded, as BitTorrent does. Only play torrents you have the right to download and share.
- Both apps check for updates in the background (at most every 12 hours) against the latest GitHub release of the repository that built them, so forks look at their own releases. **Check for updates** in Ping!'s menu checks right away on the phone and the connected TV, even if you chose Never show again. The TV shows the update prompt on the ready screen or after a video ends, never over a video that is playing.
- To publish an update, tag a release like `v1.2.1` and attach the APKs that the workflow produces (`Pong-TV-v1.2.1-arm64-v8a.apk`, `Ping-Phone-v1.2.1-universal.apk` and so on, or a zip of them). The app picks the file that matches the device's CPU, downloads it and opens Android's installer. The prompt offers Update, Not now and Never show again. Local builds (no `GITHUB_REPOSITORY`) don't check.
- Playback is powered by LibVLC (see License below).

## License

The code in this repository is released under the [MIT License](LICENSE).

### Third-party software

The Pong! TV app bundles [LibVLC](https://www.videolan.org/vlc/libvlc.html) (version 3.5.1) from the VideoLAN project, which is licensed under the **GNU Lesser General Public License (LGPL) v2.1**. LibVLC is not modified here. Its source code is available at VideoLAN's code repo, and its license text is at <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>.

Because the app only links to LibVLC as a library, you can rebuild Pong! with a different or modified version of it by changing the dependency in `tv-app/app/build.gradle`.

Other libraries, used unmodified:
- [libtorrent4j](https://github.com/aldenml/libtorrent4j) (Pong!, torrent playback), which wraps [libtorrent](https://libtorrent.org). See each project for its license.
- [BlurView](https://github.com/Dimezis/BlurView) (Ping!, frosted navigation bar), Apache License 2.0
