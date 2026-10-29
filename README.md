# PingPong: TV Player and Remote

Companion media streaming apps for Android TVs and phones.

- **Ping!** (`nb.pingpong.ping`) runs on your phone. It finds the TV app on your Wi-Fi and sends it links and commands.
- **Pong!** (`nb.pingpong.pong`) runs on an Android TV, or on any Android phone or tablet you've repurposed as a TV box. It plays the video with VLC.

Everything stays on your local network. There is no account and no server.

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
- Subtitle search through OpenSubtitles, or **Paste subtitle link** for your own file
- Subtitle size, colour, outline, background and bold
- Dynamic range (Standard / High) for HDR-capable screens
- Rename the TV; the TV remembers the new name
- Controls are disabled while the TV is unreachable

**On the TV**
- Full remote support: left/right seeks 10 s without opening the menu, with on-screen feedback. On the seek bar, left/right seeks 30 s.
- Player menu with file name, clock, play/pause, ±10 s, seek bar and subtitles
- Back arrow (or the Back key) asks before ending the session
- A clear error screen when a link won't load
- Also works with touch: tap for the menu, double-tap the sides to seek, double-tap the middle to play/pause

## How it works

Pong! advertises itself on the network as a `_pingpong._tcp.` service. Ping! discovers it and talks to it over UDP (port 8888) using short text commands such as `PLAY:<url>`, `SEEK:<ms>` and `STATUS`. The TV answers `STATUS` with its current state, which the phone polls to stay in sync.

## Requirements

- Android 5.0 (API 21) or newer, on both the phone and the TV or TV-box device
- Both devices on the same local network (and multicast allowed on the router)

## Building

Both apps are standard Gradle/Android projects (`phone-app/` and `tv-app/`, JDK 17).

The **Build APKs** GitHub Actions workflow builds both apps. It only runs when you start it manually (Actions tab → Build APKs → Run workflow) and produces two zips, `Ping-Phone-<version>` and `Pong-TV-<version>`. Each contains one APK per ABI plus a universal APK, named like `Pong-TV-1.0.0-arm64-v8a.apk`.

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

- Subtitle search uses the [OpenSubtitles.com](https://www.opensubtitles.com) API. Searching needs a free API key; downloading needs your account login. Enter them under **Account** in the Subtitles tab. They are stored on the phone only.
- Playback is powered by LibVLC (see License below).

## License

The code in this repository is released under the [MIT License](LICENSE).

### Third-party software

The Pong! TV app bundles [LibVLC](https://code.videolan.org/videolan/libvlc-android) (the `libvlc-all` library, version 3.5.1) from the VideoLAN project, which is licensed under the **GNU Lesser General Public License (LGPL) v2.1 or later**. LibVLC is not modified here. Its source code is available from the link above, and its license text is at <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>.

LibVLC itself includes other open-source libraries (such as FFmpeg) under their own licenses; see [videolan.org/legal.html](https://www.videolan.org/legal.html) for details.

Because the app only links to LibVLC as a library, you can rebuild Pong! with a different or modified version of it by changing the dependency in `tv-app/app/build.gradle`.

Other dependencies: AndroidX, Material Components and Gson (Apache License 2.0).
