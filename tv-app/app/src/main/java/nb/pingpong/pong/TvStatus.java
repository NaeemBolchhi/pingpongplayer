package nb.pingpong.pong;

import java.util.ArrayList;
import java.util.List;

/** Status snapshot sent to the phone app (JSON via Gson). Keep in sync with the phone's copy. */
public class TvStatus {
    public static class Track {
        public int id;
        public String name;
        public Track(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** A video file found in the torrent currently loaded. */
    public static class TorrentFile {
        public int index = -1;      // libtorrent file index, used by TORRENT_FILE:<index>
        public String name = "";
        public long size;
        public int subtitles;       // matching subtitle files inside the torrent
    }

    public String name = "";        // the name this TV is advertised under
    public String state = "idle";   // idle, opening, buffering, playing, paused, stopped, ended, error
    public String title = "";
    public long time;               // ms
    public long length;             // ms
    public boolean seekable;
    public int volume = 100;        // 0..100
    public float rate = 1f;
    public int audioTrack = -1;
    public int spuTrack = -1;
    public long audioDelay;         // ms
    public long spuDelay;           // ms
    public List<Track> audioTracks = new ArrayList<>();
    public List<Track> spuTracks = new ArrayList<>();

    // subtitle style + display
    public int subSize = 100;       // percent
    public int subColor = 0xFFFFFF; // RGB
    public int subOutline = 4;      // 0 none, 2 thin, 4 normal, 6 thick
    public boolean subBold = false;
    public int subBg = 0;           // 0..255 background opacity
    public boolean hdr = false;

    // torrent playback (empty when no magnet is loaded)
    public String torrentState = ""; // metadata, choosing, checking, buffering, streaming, error
    public String torrentTitle = "";
    public int torrentSelected = -1;
    public int torrentPeers;
    public int torrentRate;          // bytes per second
    public int torrentProgress;      // 0..100 of the selected file
    public String torrentError = "";
    public String torrentMessage = ""; // ready-made status line, the same text the TV shows
    public List<TorrentFile> torrentFiles = new ArrayList<>();
}
