package nb.pingpong.ping;

import java.util.ArrayList;
import java.util.List;

/** Status snapshot received from the TV app (JSON via Gson). Keep in sync with the TV's copy. */
public class TvStatus {
    public static class Track {
        public int id;
        public String name;
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
}
