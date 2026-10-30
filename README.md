# PingPong: TV Player and Remote

Companion media streaming apps for Android TVs and phones.

- **Ping!** (`nb.pingpong.ping`) runs on your phone. It finds the TV app on your Wi-Fi and sends it links and commands.
- **Pong!** (`nb.pingpong.pong`) runs on an Android TV, or on any Android phone or tablet you've repurposed as a TV box. It plays the video with VLC.

Playback control stays on your local network. There is no account to create and no server of ours; the only outside service is SubSource, if you use subtitle search.

## Features

**Casting**
- Share a link from any app to Ping! and it plays on the TV.
- Or open a TV in Ping! and tap **Paste & play** to send the link on your clipboard.
- TVs are listed in order of how often you use them. If only one is found, Ping! opens it directly.
- Several phones can control the same TV. Each one follows the TV's latest state, so a rewind on one phone shows up on the others.

**Remote control from the phone** (the phone shows controls only, no video or audio)
- Play/pause, seek bar, ±10 s, volume
- Playback speed from 0.05x to 16x, with presets and fine steps
- Audio and subtitle track selection
- Audio sync and subtitle sync, adjustable without limit
- Subtitle search through [SubSource](https://subsource.net), or **Paste subtitle link** for your own file
  - For TV shows, add the season or episode to the search, for example `Breaking Bad S02` or `Breaking Bad S02E05`
  - The language field takes a code such as `en` or `bn`, or a SubSource language name such as `farsi_persian`
  - Results show the release name, hearing-impaired and download count, most downloaded first
- Subtitle size, colour, outline, background and bold
- Dynamic range (Standard / High) for HDR-capable screens
- Rename the TV; the TV remembers the new name
- Controls are disabled while the TV is unreachable

**On the TV**
- Full remote support: left/right seeks 10 s without opening the menu, with on-screen feedback. On the seek bar, left/right seeks 30 s.
- Player menu with file name, clock, play/pause, ±10 s, seek bar and subtitles
- Subtitles in ASS/SSA, WebVTT and TTML format are converted to SRT before they are shown (SRT and other formats are used as they are)
- Back arrow (or the Back key) asks before ending the session
- A clear error screen when a link won't load
- Also works with touch: tap for the menu, double-tap the sides to seek, double-tap the middle to play/pause

## How it works

Pong! advertises itself on the network as a `_pingpong._tcp.` service. Ping! discovers it and talks to it over UDP (port 8888) using short text commands such as `PLAY:<url>`, `SEEK:<ms>` and `STATUS`. The TV answers `STATUS` with its current state, which the phone polls to stay in sync.

Subtitle search runs on the phone. When you pick a result, the phone downloads and unpacks the subtitle, then briefly serves the finished file over your local network and sends the TV a `SUB_URL:<url>` command. The TV fetches it from the phone, so the TV needs no internet access for subtitles found through search.

## Requirements

- Android 5.0 (API 21) or newer, on both the phone and the TV or TV-box device
- Both devices on the same local network (and multicast allowed on the router)
- Internet access on the phone for subtitle search

## Building

Both apps are standard Gradle/Android projects (`phone-app/` and `tv-app/`) built with JDK 17, Gradle 8.7 and Android Gradle Plugin 8.5.2.

The **Build APKs** GitHub Actions workflow builds both apps. It only runs when you start it manually (Actions tab → Build APKs → Run workflow) and produces two zips, `Ping-Phone-<version>` and `Pong-TV-<version>`. Each contains one APK per ABI plus a universal APK, named like `Pong-TV-1.0.0-arm64-v8a.apk`.

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
- Both apps check for updates in the background (at most every 12 hours) against the latest GitHub release of the repository that built them, so forks look at their own releases. Tag releases like `v1.0.1` and attach the APKs that the workflow produces (`Pong-TV-1.0.1-arm64-v8a.apk`, `Ping-Phone-1.0.1-universal.apk` and so on, or a zip of them). The app picks the file that matches the device's CPU, downloads it and opens Android's installer. The prompt offers Update, Not now and Never show again. Local builds (no `GITHUB_REPOSITORY`) don't check.
- Playback is powered by LibVLC (see License below).

## License

The code in this repository is released under the [MIT License](LICENSE).

### Third-party software

The Pong! TV app bundles [LibVLC](https://www.videolan.org/vlc/libvlc.html) (version 3.5.1) from the VideoLAN project, which is licensed under the **GNU Lesser General Public License (LGPL) v2.1**. LibVLC is not modified here. Its source code is available at VideoLAN's code repo, and its license text is at <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>.

Because the app only links to LibVLC as a library, you can rebuild Pong! with a different or modified version of it by changing the dependency in `tv-app/app/build.gradle`.