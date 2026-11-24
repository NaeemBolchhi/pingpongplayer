package nb.pingpong.ping;

import java.util.ArrayList;
import java.util.List;

/** Status snapshot received from the TV app (JSON via Gson). Keep in sync with the TV's copy. */
public class TvStatus {
    public static class Track {
        public int id;
        public String name;
        public String source = ""; // subtitles: "torrent", "phone", "video" (where the track came from)
    }

    /** A video file found in the torrent currently loaded on the TV. */
    public static class TorrentFile {
        public int index = -1;      // libtorrent file index, used by TORRENT_FILE:<index>
        public String name = "";
        public long size;
        public int subtitles;       // matching subtitle files inside the torrent
    }

    public String name = "";
    public String state = "idle";
    public String title = "";
    public long time;
    public long length;
    public boolean seekable;
    public int volume = 100;
    public float rate = 1f;
    public int audioTrack = -1;
    public int spuTrack = -1;
    public long audioDelay;
    public long spuDelay;
    public List<Track> audioTracks = new ArrayList<>();
    public List<Track> spuTracks = new ArrayList<>();

    public int subSize = 100;
    public int subColor = 0xFFFFFF;
    public int subOutline = 4;
    public boolean subBold = false;
    public int subBg = 0;
    public boolean hdr = false;

    // torrent playback (empty when the TV isn't playing a magnet)
    public String torrentState = ""; // metadata, choosing, checking, buffering, streaming, error
    public String torrentTitle = "";
    public int torrentSelected = -1;
    public int torrentPeers;
    public int torrentRate;          // bytes per second
    public int torrentProgress;      // 0..100 of the selected file
    public String torrentError = "";
    public String torrentMessage = ""; // ready-made status line, the same text the TV shows
    public int torrentFileProgress;  // 0..1000: how much of the playing file has downloaded

    // videos shared from a phone, as the TV last received them (see CastService)
    public String shareUrl = "";      // the video URL the TV's share list belongs to
    public int shareSelect = -1;      // a video the viewer picked on the TV...
    public int shareSelectSeq;        // ...counted up on every pick, so the phone can tell a new one
    public List<TorrentFile> torrentFiles = new ArrayList<>();
}
