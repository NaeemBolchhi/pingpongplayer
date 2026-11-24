package nb.pingpong.pong;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.libtorrent4j.AlertListener;
import org.libtorrent4j.LibTorrent;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.AddTorrentAlert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.FileErrorAlert;
import org.libtorrent4j.alerts.MetadataReceivedAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads a magnet with libtorrent4j and exposes the chosen video through a loopback HTTP range server
 * that LibVLC can play. Only one torrent is active at a time.
 * <p>
 * The download folder lives on the volume with the most free space (app-specific storage, no permission needed).
 * It is cleared on app start and whenever a new link or share is loaded. The session stays up when a video
 * ends, so it can be replayed or another video of the same torrent picked; it stops when the viewer stops
 * playback on the TV or loads something else. Switching videos erases partly downloaded ones and keeps
 * fully downloaded ones unless their space is needed. Seeding happens alongside, with a low upload cap.
 */
public final class TorrentStreamer {
    private static final long MARGIN = 256L * 1024 * 1024;
    private static final long METADATA_TIMEOUT_MS = 90_000;
    private static final long PEER_TIMEOUT_MS = 90_000;
    private static final long BUFFER_TIMEOUT_MS = 90_000;
    private static final long CHOICE_TIMEOUT_MS = 60_000;
    private static final long PIECE_READ_TIMEOUT_MS = 60_000;

    private static final long START_BYTES = 8L * 1024 * 1024;
    private static final long TAIL_BYTES = 2L * 1024 * 1024;
    private static final int WINDOW_PIECES = 24;
    private static final int AHEAD_PIECES = 96;
    private static final int UPLOAD_LIMIT = 100 * 1024; // bytes per second

    /** Raised when this device cannot run the native torrent engine. */
    public static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message);
        }
    }

    public static final class Snapshot {
        public final String state; // metadata, choosing, checking, buffering, streaming, error
        public final String title;
        public final int selected;
        public final int peers;
        public final int rate;
        public final int progress;
        public final String error;
        public final List<TvStatus.TorrentFile> files;

        Snapshot(String state, String title, int selected, int peers, int rate, int progress,
                 String error, List<TvStatus.TorrentFile> files) {
            this.state = state;
            this.title = title;
            this.selected = selected;
            this.peers = peers;
            this.rate = rate;
            this.progress = progress;
            this.error = error;
            this.files = files;
        }
    }

    public interface Listener {
        void onReady(String url, String title);

        void onState(Snapshot state);

        void onError(String message);

        /** Matching subtitle files extracted from the torrent, once their pieces have arrived. */
        void onSubtitles(List<File> files);

        /** A video of the torrent is being prepared (first pick, or a switch from the playlist). */
        void onLoading(String title);
    }

    private static final ExecutorService CTRL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "torrent-ctrl");
        t.setDaemon(true);
        return t;
    });
    private static volatile TorrentStreamer instance;

    /** Creates (or reuses) the single streamer. Call on the main thread. */
    public static synchronized TorrentStreamer get(Context context, Listener listener) throws Unavailable {
        if (Build.VERSION.SDK_INT < 24) {
            throw new Unavailable("Torrent playback needs Android 7.0 or newer");
        }
        try {
            Class.forName("org.libtorrent4j.SessionManager");
            LibTorrent.version(); // forces the native library to load here, where we can catch the failure
        } catch (Throwable t) {
            throw new Unavailable("Torrent playback isn't available on this TV");
        }
        if (instance == null) instance = new TorrentStreamer(context.getApplicationContext());
        instance.listener = listener;
        return instance;
    }

    /** The local URL of the video being served now; null while another one is being prepared. */
    public String streamUrl() {
        return bufferPending ? null : currentUrl;
    }

    public static boolean isActive() {
        TorrentStreamer s = instance;
        return s != null && s.running;
    }

    /** Stops the session and deletes every downloaded file; serialised with {@link #play(String)}. */
    public static void clear(final Context context) {
        CTRL.execute(() -> {
            try {
                TorrentStreamer s = instance;
                if (s != null) s.stopNow();
                deleteRecursive(downloadDir(context.getApplicationContext()));
            } catch (Throwable ignored) {
                // never take the app down over a cleanup
            }
        });
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;

    private volatile boolean running;
    private volatile boolean metadataReady;
    private volatile boolean choicePending;
    private volatile boolean bufferPending;
    private volatile String lastError = "";
    private volatile SessionManager session;
    /** Our own handle, from SessionManager.find() (never one taken from an alert). */
    private volatile TorrentHandle handle;
    private volatile Sha1Hash infoHash;
    private volatile TorrentInfo info;
    private volatile File dir;
    private volatile String magnetUri = "";
    private volatile FileChannel channel;
    private volatile RangeServer server;
    private volatile int selected = -1;
    private volatile String currentUrl;
    private volatile String currentTitle = "";
    private volatile int peers;
    private volatile int rate;
    private volatile int progress;

    private long metadataDeadline;
    private long lastPeerAt;
    private long bufferDeadline;
    private long choiceDeadline;
    private long subsWaitAt;
    private int subsDeliveredFor = -1;
    private long lastMetaCheck;
    private int haveCount = -1;
    private long lastHaveCount;
    private volatile boolean checkingFiles;
    /** Video file indices in the order they were played, most recent last. */
    private final List<Integer> recent = new ArrayList<>();
    private Thread pump;

    // selected file geometry
    private long fileOffset;
    private long fileSize;
    private long pieceLength;
    private int firstPiece;
    private int lastPiece;
    private int windowPiece = -1;
    private final Set<Integer> deadlinePieces = new HashSet<>();

    private final List<TvStatus.TorrentFile> videoFiles = new ArrayList<>();
    private final Set<Integer> videoIndices = new HashSet<>();
    private final Map<Integer, List<Integer>> subsByVideo = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<Integer, Long> sizes = new HashMap<>();

    private static final int[] ALERT_TYPES = alertTypes(AlertType.ADD_TORRENT, AlertType.METADATA_RECEIVED,
            AlertType.TORRENT_ERROR, AlertType.FILE_ERROR);

    /** AlertType -> libtorrent's numeric type, via swig() (by reflection: its name varies). null = all alerts. */
    private static int[] alertTypes(AlertType... types) {
        try {
            java.lang.reflect.Method m = AlertType.class.getMethod("swig");
            int[] out = new int[types.length];
            for (int i = 0; i < types.length; i++) out[i] = ((Number) m.invoke(types[i])).intValue();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private final AlertListener alerts = new AlertListener() {
        @Override
        public int[] types() {
            // Only what we handle. Listening to everything made libtorrent4j wrap every block/piece/peer alert
            // in Java objects (thousands per second while downloading).
            return ALERT_TYPES;
        }

        /*
         * This runs on libtorrent4j's alert thread. Nothing here may stop the session: SessionManager.stop()
         * joins this very thread, so stopping from here deadlocks and every later torrent hangs on
         * "Looking for peers". Real work goes to CTRL.
         */
        @Override
        public void alert(Alert<?> alert) {
            if (!running) return;
            try {
                switch (alert.type()) {
                    case ADD_TORRENT: {
                        // Used only right here: an alert's handle points into the alert itself, which libtorrent
                        // frees on its next poll. Keeping it crashed the app. The pump gets its own handle.
                        TorrentHandle h = ((AddTorrentAlert) alert).handle();
                        if (h != null && h.isValid()) {
                            if (infoHash == null) infoHash = HashCompat.of(h); // a copy, safe to keep
                            h.resume();
                        } else {
                            failLater("Couldn't add the torrent");
                        }
                        break;
                    }
                    case METADATA_RECEIVED:
                        onCtrl(TorrentStreamer.this::onMetadata);
                        break;
                    case TORRENT_ERROR: {
                        TorrentErrorAlert e = (TorrentErrorAlert) alert;
                        String m = e.error() == null ? null : e.error().getMessage();
                        failLater("Torrent error" + (m == null || m.isEmpty() ? "" : ": " + m));
                        break;
                    }
                    case FILE_ERROR:
                        FileErrorAlert fe = (FileErrorAlert) alert;
                        String msg = fe.error() == null ? null : fe.error().getMessage();
                        failLater("Can't write torrent files" + (msg == null || msg.isEmpty() ? "" : ": " + msg));
                        break;
                    default:
                        break;
                }
            } catch (Throwable t) {
                failLater("Torrent error: " + t.getMessage());
            }
        }
    };

    private void failLater(final String message) {
        onCtrl(() -> fail(message));
    }

    /** Runs on CTRL; an exception there would kill the app, so it becomes a torrent error instead. */
    private void onCtrl(final Runnable task) {
        CTRL.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                fail("Torrent error: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
        });
    }

    private TorrentStreamer(Context app) {
        this.app = app;
    }

    /** Called by MainActivity when the user changes listener (activity recreated). */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts a magnet download. Returns immediately; progress arrives through the listener. */
    public void play(final String magnet) {
        CTRL.execute(() -> { // setup has its own catch-all
            try {
                setup(magnet);
            } catch (Throwable t) {
                fail(t.getMessage() == null ? "Couldn't start torrent playback" : t.getMessage());
            }
        });
    }

    private void setup(String magnet) throws Exception {
        stopNow();
        magnetUri = magnet == null ? "" : magnet;
        running = true;
        lastCrumb = "";
        peers = 0;
        rate = 0;
        progress = 0;
        metadataReady = false;
        choicePending = false;
        bufferPending = false;
        lastError = "";
        selected = -1;
        currentUrl = null;
        String dn = TorrentFiles.magnetParam(magnet, "dn");
        currentTitle = dn == null || dn.trim().isEmpty() ? "Torrent" : dn.trim();
        String hex = TorrentFiles.infoHashHex(magnet);
        if (hex == null) throw new IllegalArgumentException("This magnet link has no BitTorrent info hash");
        infoHash = HashCompat.fromHex(hex); // may be null; then the ADD_TORRENT alert supplies it
        videoFiles.clear();
        videoIndices.clear();
        subsByVideo.clear();
        names.clear();
        sizes.clear();
        metadataDeadline = System.currentTimeMillis() + METADATA_TIMEOUT_MS;
        lastPeerAt = System.currentTimeMillis();
        subsDeliveredFor = -1;
        subsWaitAt = 0;
        lastMetaCheck = 0;
        haveCount = -1;
        checkingFiles = false;
        recent.clear();
        // Tell the TV screen and the phone right away; resolving the DHT routers below can take a few seconds.
        publish();

        dir = downloadDir(app);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        SettingsPack sp = new SettingsPack();
        sp.uploadRateLimit(UPLOAD_LIMIT);
        String nodes = bootstrapNodes();
        if (nodes != null) sp.setDhtBootstrapNodes(nodes);
        SessionParams params = new SessionParams();
        params.setSettings(sp);
        diskIo = usePosixDiskIo(params);

        SessionManager sm = new SessionManager();
        sm.start(params);
        sm.addListener(alerts);
        session = sm;
        sm.download(magnet, dir, TorrentFlags.DEFAULT_DONT_DOWNLOAD);

        pump = new Thread(this::pump, "torrent-pump");
        pump.setDaemon(true);
        pump.start();
    }

    /** Runs on CTRL. */
    private void onMetadata() {
        TorrentHandle h = ensureHandle();
        if (metadataReady || !running || h == null || !h.isValid()) return;
        TorrentInfo ti = h.torrentFile();
        if (ti == null || !ti.isValid()) return;
        metadataReady = true;
        info = ti;
        lastPeerAt = System.currentTimeMillis();
        // No space check here: only the video that gets picked has to fit (see applyFile), so one
        // episode of a large season pack can play on a small drive.

        names.clear();
        sizes.clear();
        videoFiles.clear();
        subsByVideo.clear();
        List<Integer> subs = new ArrayList<>();
        for (int i = 0; i < ti.numFiles(); i++) {
            String path = ti.files().filePath(i);
            String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
            names.put(i, name);
            sizes.put(i, ti.files().fileSize(i));
            if (TorrentFiles.isVideo(name)) {
                TvStatus.TorrentFile f = new TvStatus.TorrentFile();
                f.index = i;
                f.name = name;
                f.size = ti.files().fileSize(i);
                videoFiles.add(f);
            } else if (TorrentFiles.isSubtitle(name)) {
                subs.add(i);
            }
        }
        videoIndices.clear();
        for (TvStatus.TorrentFile vf : videoFiles) videoIndices.add(vf.index);
        if (videoFiles.isEmpty()) {
            fail("No video file in the torrent");
            return;
        }
        for (TvStatus.TorrentFile v : videoFiles) {
            List<Integer> matched = new ArrayList<>();
            for (int sub : subs) {
                if (TorrentFiles.matches(v.name, names.get(sub))) matched.add(sub);
            }
            v.subtitles = matched.size();
            subsByVideo.put(v.index, matched);
        }
        // keep the status payload small (single UDP packet)
        if (videoFiles.size() > 60) {
            List<TvStatus.TorrentFile> capped = new ArrayList<>(videoFiles.subList(0, 60));
            videoFiles.clear();
            videoFiles.addAll(capped);
        }

        int pick = -1;
        try {
            String so = TorrentFiles.magnetParam(magnetUri, "so");
            if (so != null && !so.isEmpty()) pick = Integer.parseInt(so.split("[,-]")[0].trim());
        } catch (Exception ignored) {
        }
        if (pick >= 0 && !names.containsKey(pick)) pick = -1;
        if (pick < 0 && videoFiles.size() == 1) pick = videoFiles.get(0).index;
        if (pick >= 0) {
            applyFile(pick);
        } else {
            choicePending = true;
            choiceDeadline = System.currentTimeMillis() + CHOICE_TIMEOUT_MS;
            notifyState("choosing");
        }
    }

    /** Looks up a durable handle for our torrent once the session has added it. */
    private TorrentHandle ensureHandle() {
        TorrentHandle h = handle;
        if (h != null) return h;
        SessionManager sm = session;
        Sha1Hash ih = infoHash;
        if (sm == null || ih == null || !running) return null;
        TorrentHandle found = sm.find(ih);
        if (found != null && found.isValid()) {
            handle = found;
            return found;
        }
        return null;
    }

    /** Selects a video from the playlist (libtorrent file index). */
    public void selectFile(final int index) {
        onCtrl(() -> {
            if (running && metadataReady) applyFile(index);
        });
    }

    private void applyFile(int index) {
        TorrentHandle h = handle;
        TorrentInfo ti = info;
        if (h == null || ti == null || !running) return;
        if (index < 0 || index >= ti.numFiles() || !videoIndices.contains(index)) return;
        if (index == selected && server != null && !bufferPending) return;

        // ---- space: only the picked video and its subtitles have to fit
        List<Integer> subs = subsByVideo.get(index);
        long need = MARGIN + Math.max(0, ti.files().fileSize(index) - doneBytes(h, ti, index));
        if (subs != null) for (int sub : subs) need += Math.max(0, ti.files().fileSize(sub) - doneBytes(h, ti, sub));
        // Other videos of this torrent that have data on disk. Partly downloaded ones are erased; fully
        // downloaded ones stay so they can be replayed, unless their space is needed for this one.
        List<Integer> erase = new ArrayList<>();
        List<Integer> kept = new ArrayList<>();
        long reclaim = 0;
        for (int v : videoIndices) {
            if (v == index) continue;
            long done = doneBytes(h, ti, v);
            if (done <= 0) continue;
            if (done >= ti.files().fileSize(v)) {
                kept.add(v);
            } else {
                erase.add(v);
                reclaim += done;
            }
        }
        long free = dir.getUsableSpace();
        // least recently played first
        Collections.sort(kept, (a, b) -> Integer.compare(recent.indexOf(a), recent.indexOf(b)));
        for (int v : kept) {
            if (free + reclaim >= need) break;
            erase.add(v);
            reclaim += ti.files().fileSize(v);
        }
        if (free + reclaim < need) {
            fail("Not enough space: need " + human(need) + ", free " + human(free + reclaim));
            return;
        }

        // Let the screen drop the old stream before its server goes away (VLC would report an error).
        final Listener ll = listener;
        final String nextTitle = names.get(index);
        if (ll != null) main.post(() -> ll.onLoading(nextTitle));
        currentUrl = null;    // set again once the new video's server is up (after its geometry below)
        bufferPending = true; // streamUrl() is null from here until the new video is ready
        if (server != null) server.stop();
        closeChannel();
        recent.remove(Integer.valueOf(index));
        recent.add(index);
        selected = index;
        choicePending = false;
        subsDeliveredFor = -1;
        subsWaitAt = 0;
        currentTitle = names.get(index);
        currentUrl = null;

        Priority[] priorities = new Priority[ti.numFiles()];
        Arrays.fill(priorities, Priority.IGNORE);
        priorities[index] = Priority.DEFAULT;
        if (subs != null) for (int s : subs) if (s < priorities.length) priorities[s] = Priority.DEFAULT;
        h.prioritizeFiles(priorities);
        h.clearPieceDeadlines();
        if (!erase.isEmpty()) eraseFiles(h, ti, erase);

        fileOffset = ti.files().fileOffset(index);
        fileSize = ti.files().fileSize(index);
        pieceLength = Math.max(1, ti.pieceLength());
        firstPiece = ti.files().pieceIndexAtFile(index);
        lastPiece = ti.files().lastPieceIndexAtFile(index);
        windowPiece = -1;
        deadlinePieces.clear();
        for (int p = firstPiece; p <= lastPiece; p++) h.piecePriority(p, Priority.DEFAULT);
        int startCount = Math.max(1, (int) Math.min(lastPiece - firstPiece + 1,
                (START_BYTES + pieceLength - 1) / pieceLength));
        int tailCount = Math.max(1, (int) Math.min(lastPiece - firstPiece + 1,
                (TAIL_BYTES + pieceLength - 1) / pieceLength));
        for (int p = firstPiece; p < firstPiece + startCount; p++) h.piecePriority(p, Priority.TOP_PRIORITY);
        for (int p = Math.max(firstPiece, lastPiece - tailCount + 1); p <= lastPiece; p++) {
            h.piecePriority(p, Priority.TOP_PRIORITY);
        }

        File file = new File(ti.files().filePath(index, dir.getAbsolutePath()));
        File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try {
            // "rw" creates an empty file if libtorrent hasn't made it yet; only positional reads are used.
            FileOutputStream out = new FileOutputStream(file, true);
            out.close();
            channel = new java.io.RandomAccessFile(file, "r").getChannel();
        } catch (IOException e) {
            fail("Can't open the video file: " + e.getMessage());
            return;
        }

        RangeServer srv = new RangeServer(nameOf(file), fileSize, guessMime(nameOf(file)));
        try {
            int port = srv.start();
            currentUrl = srv.url(port);
            server = srv;
        } catch (IOException e) {
            fail("Can't start the local stream: " + e.getMessage());
            return;
        }
        bufferPending = true;
        haveCount = -1;
        lastHaveCount = 0;
        bufferDeadline = System.currentTimeMillis() + BUFFER_TIMEOUT_MS;
        notifyState("buffering");
    }

    /** Bytes of a file already downloaded and verified (counted in whole pieces). */
    private static long doneBytes(TorrentHandle h, TorrentInfo ti, int idx) {
        long size = ti.files().fileSize(idx);
        if (size <= 0) return 0;
        long off = ti.files().fileOffset(idx);
        long pl = Math.max(1, ti.pieceLength());
        int first = ti.files().pieceIndexAtFile(idx);
        int last = ti.files().lastPieceIndexAtFile(idx);
        long done = 0;
        for (int p = first; p <= last; p++) {
            if (!h.havePiece(p)) continue;
            long ps = (long) p * pl, pe = ps + pl;
            done += Math.max(0, Math.min(pe, off + size) - Math.max(ps, off));
        }
        return done;
    }

    /**
     * Deletes videos of the current torrent from disk. Pausing makes libtorrent close its handles so the
     * space is really freed; the recheck makes it forget pieces that are gone (otherwise it would try to
     * upload them and stop with a file error). Fully downloaded videos that are kept get verified again.
     */
    private void eraseFiles(TorrentHandle h, TorrentInfo ti, List<Integer> files) {
        h.pause();
        for (int v : files) {
            String path = ti.files().filePath(v, dir.getAbsolutePath());
            if (path != null) {
                //noinspection ResultOfMethodCallIgnored
                new File(path).delete();
            }
        }
        h.forceRecheck();
        h.resume();
        checkingFiles = true;
    }

    private static String nameOf(File file) {
        String n = file.getName();
        return n.isEmpty() ? "video.mp4" : n;
    }

    // ------------------------------------------------------------------ background pump

    private void pump() {
        long lastPublish = 0;
        final long started = System.currentTimeMillis();
        while (running) {
            try {
                long now = System.currentTimeMillis();
                if (handle == null && infoHash == null && now - started > 15_000) {
                    // the torrent was added but none of the known libtorrent4j calls could identify it
                    failLater("Torrent engine error: couldn't identify the torrent (libtorrent4j API mismatch)");
                    break;
                }
                if (!metadataReady && now > metadataDeadline) {
                    failLater("No peers or metadata after 90 seconds");
                    break;
                }
                TorrentHandle h = ensureHandle();
                if (h != null && h.isValid()) {
                    TorrentStatus st = h.status();
                    peers = st.numPeers();
                    rate = st.downloadRate();
                    if (peers > 0) lastPeerAt = now;
                    TorrentStatus.State ts = st.state();
                    boolean checking = ts == TorrentStatus.State.CHECKING_FILES
                            || ts == TorrentStatus.State.CHECKING_RESUME_DATA;
                    checkingFiles = checking;
                    if (checking) haveCount = -1; // a recheck resets what libtorrent has; re-measure afterwards
                    if (checking && bufferPending) bufferDeadline = now + BUFFER_TIMEOUT_MS; // not stalled
                    if (!metadataReady && now - lastMetaCheck >= 1000) {
                        // Fallback in case the metadata alert was missed (e.g. a full alert queue).
                        lastMetaCheck = now;
                        TorrentInfo ti = h.torrentFile();
                        if (ti != null && ti.isValid()) onCtrl(this::onMetadata);
                    }
                    // Only while waiting for a choice: once a video streams, a quiet swarm must not kill
                    // playback (the file may already be complete, or the viewer may simply be paused).
                    if (metadataReady && choicePending && now - lastPeerAt > PEER_TIMEOUT_MS) {
                        failLater("No peers are sharing this torrent");
                        break;
                    }
                    if (choicePending && now > choiceDeadline) {
                        // don't leave the TV stuck if the phone went away
                        TvStatus.TorrentFile biggest = null;
                        for (TvStatus.TorrentFile f : videoFiles) {
                            if (biggest == null || f.size > biggest.size) biggest = f;
                        }
                        choiceDeadline = Long.MAX_VALUE; // applyFile clears choicePending on CTRL
                        if (biggest == null) choicePending = false;
                        else {
                            final int pick = biggest.index;
                            onCtrl(() -> {
                                if (running && selected < 0) applyFile(pick);
                            });
                        }
                    }
                    if (bufferPending) {
                        // Each havePiece() is a round trip to libtorrent's network thread, so the whole-file
                        // count runs once a second; the start/end windows below are small.
                        int have = haveCount;
                        if (now - lastHaveCount >= 1000) {
                            lastHaveCount = now;
                            have = haveCount(h);
                        }
                        if (have > haveCount) {
                            // still making progress: keep waiting
                            if (haveCount >= 0) bufferDeadline = now + BUFFER_TIMEOUT_MS;
                            haveCount = have;
                        }
                        if (now > bufferDeadline) {
                            failLater("Couldn't buffer the video (" + peers + " peers, " + human(rate) + "/s)");
                            break;
                        }
                        progress = bufferPercent();
                        if (currentUrl != null && server != null && bufferReady(h)) {
                            bufferPending = false;
                            final String url = currentUrl, title = currentTitle;
                            final Listener l = listener;
                            notifyState("streaming");
                            main.post(() -> {
                                if (l != null) l.onReady(url, title);
                            });
                        }
                    } else if (metadataReady) {
                        progress = (int) (st.progress() * 100);
                    }
                    if (metadataReady && !bufferPending && currentUrl != null) maybeDeliverSubtitles(now);
                }
                if (now - lastPublish >= 500) {
                    lastPublish = now;
                    notifyState(null);
                }
            } catch (Throwable t) {
                failLater("Torrent error: " + t.getMessage());
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Adds matching subtitle files from the torrent once their pieces are complete. */
    private void maybeDeliverSubtitles(long now) {
        if (subsDeliveredFor == selected) return;
        List<Integer> subs = subsByVideo.get(selected);
        if (subs == null || subs.isEmpty()) {
            subsDeliveredFor = selected;
            return;
        }
        TorrentHandle h = handle;
        TorrentInfo ti = info;
        if (h == null || ti == null) return;
        List<File> ready = new ArrayList<>();
        boolean all = true;
        for (int idx : subs) {
            int f = ti.files().pieceIndexAtFile(idx);
            int l = ti.files().lastPieceIndexAtFile(idx);
            boolean done = true;
            for (int p = f; p <= l; p++) {
                if (!h.havePiece(p)) {
                    done = false;
                    break;
                }
            }
            String path = ti.files().filePath(idx, dir.getAbsolutePath());
            if (done && path != null) ready.add(new File(path));
            else all = false;
        }
        if (!all) {
            if (subsWaitAt == 0) subsWaitAt = now;
            if (now - subsWaitAt < 30_000) return;
        }
        subsDeliveredFor = selected;
        final List<File> files = ready;
        final Listener l = listener;
        if (!files.isEmpty() && l != null) main.post(() -> l.onSubtitles(files));
    }

    /** Pieces of the selected file that have arrived. */
    private int haveCount(TorrentHandle h) {
        int have = 0;
        for (int p = firstPiece; p <= lastPiece; p++) if (h.havePiece(p)) have++;
        return have;
    }

    /** Buffering progress: how much of the start and end windows (what playback needs first) has arrived. */
    private int bufferPercent() {
        int count = lastPiece - firstPiece + 1;
        if (count <= 0) return 0;
        int start = Math.max(1, (int) Math.min(count, (START_BYTES + pieceLength - 1) / pieceLength));
        int tail = Math.max(1, (int) Math.min(count, (TAIL_BYTES + pieceLength - 1) / pieceLength));
        int needed = Math.min(count, start + tail);
        TorrentHandle h = handle;
        if (h == null) return 0;
        int got = 0;
        for (int p = firstPiece; p < firstPiece + start; p++) if (h.havePiece(p)) got++;
        for (int p = Math.max(firstPiece + start, lastPiece - tail + 1); p <= lastPiece; p++) {
            if (h.havePiece(p)) got++;
        }
        return Math.min(100, (int) ((long) got * 100 / Math.max(1, needed)));
    }

    private boolean bufferReady(TorrentHandle h) {
        int count = lastPiece - firstPiece + 1;
        if (count <= 0) return false;
        int start = Math.max(1, (int) Math.min(count, (START_BYTES + pieceLength - 1) / pieceLength));
        int tail = Math.max(1, (int) Math.min(count, (TAIL_BYTES + pieceLength - 1) / pieceLength));
        for (int p = firstPiece; p < firstPiece + start; p++) if (!h.havePiece(p)) return false;
        for (int p = Math.max(firstPiece, lastPiece - tail + 1); p <= lastPiece; p++) {
            if (!h.havePiece(p)) return false;
        }
        return true;
    }

    private String state() {
        if (!lastError.isEmpty()) return "error";
        if (choicePending) return "choosing";
        if (bufferPending && checkingFiles) return "checking";
        if (bufferPending) return "buffering";
        if (currentUrl != null) return "streaming";
        return "metadata";
    }

    private void notifyState(String override) {
        final Snapshot s = new Snapshot(override == null ? state() : override, currentTitle, selected,
                peers, rate, progress, lastError, new ArrayList<>(videoFiles));
        breadcrumb(s.state);
        final Listener l = listener;
        if (l != null) main.post(() -> l.onState(s));
    }

    private void publish() {
        notifyState(null);
    }

    /**
     * Reports an error and ends the current torrent. Safe from any thread except libtorrent's alert thread
     * (use {@link #failLater}); the session itself is always stopped on CTRL.
     */
    private void fail(String message) {
        if (!running) return;
        running = false;
        choicePending = false;
        bufferPending = false;
        lastError = message == null || message.isEmpty() ? "Torrent playback failed" : message;
        RangeServer srv = server;
        server = null;
        if (srv != null) srv.stop();
        closeChannel();
        notifyState("error");
        final Listener l = listener;
        final String m = lastError;
        if (l != null) main.post(() -> l.onError(m));
        final SessionManager sm = session;
        CTRL.execute(() -> {
            if (session != sm) return; // a newer torrent already replaced it (stopNow handled this one)
            joinPump();
            stopSession();
            session = null;
            handle = null;
            info = null;
        });
    }

    /** Stops everything but keeps the downloaded files. Serialised through CTRL when called publicly. */
    public void stop() {
        CTRL.execute(() -> {
            try {
                stopNow();
            } catch (Throwable ignored) {
            }
        });
    }

    private void stopNow() {
        running = false;
        try {
            app.getSharedPreferences("PongPrefs", Context.MODE_PRIVATE).edit().remove("torrent_trace").apply();
        } catch (Throwable ignored) {
        }
        choicePending = false;
        bufferPending = false;
        joinPump();
        if (server != null) server.stop();
        server = null;
        closeChannel();
        stopSession();
        session = null;
        handle = null;
        infoHash = null;
        info = null;
        currentUrl = null;
        currentTitle = "";
        selected = -1;
        metadataReady = false;
        progress = 0;
        peers = 0;
        rate = 0;
    }

    /**
     * libtorrent 2 maps files into memory on 64-bit devices. On Android's external storage (FUSE, USB drives,
     * SD cards) a write through such a mapping can kill the process with SIGBUS, so plain file I/O is used.
     * The method's name differs between libtorrent4j versions, hence reflection. Returns the mode in use.
     */
    private static String usePosixDiskIo(SessionParams params) {
        for (String name : new String[]{"setPosixDiskIO", "setPosixDiskIo", "posixDiskIO"}) {
            try {
                params.getClass().getMethod(name).invoke(params);
                return "posix";
            } catch (Throwable ignored) {
            }
        }
        try {
            Object raw = params.getClass().getMethod("swig").invoke(params);
            for (String name : new String[]{"set_posix_disk_io_constructor", "set_posix_disk_io"}) {
                try {
                    raw.getClass().getMethod(name).invoke(raw);
                    return "posix";
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return "default (" + (android.os.Process.is64Bit() ? "mmap" : "posix") + ")";
    }

    private volatile String diskIo = "?";
    private volatile String lastCrumb = "";

    /**
     * Notes what the torrent engine is doing, so a report after a hard kill can say where it happened.
     * Written only when the stage changes.
     */
    private void breadcrumb(String stage) {
        String key = stage + "|" + selected;
        if (key.equals(lastCrumb)) return;
        lastCrumb = key;
        try {
            File d = dir;
            String size = selected >= 0 && sizes.get(selected) != null ? human(sizes.get(selected)) : "-";
            String text = "Torrent stage: " + stage + " · file " + size + " · peers " + peers
                    + "\nDisk I/O: " + diskIo + " · folder: " + (d == null ? "-" : d.getAbsolutePath())
                    + "\nFree: " + (d == null ? "-" : human(d.getUsableSpace())) + " · ABI: "
                    + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                    + (android.os.Process.is64Bit() ? " (64-bit app)" : " (32-bit app)");
            app.getSharedPreferences("PongPrefs", Context.MODE_PRIVATE).edit()
                    .putString("torrent_trace", text).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Waits for the pump to leave any native call, so the session is never deleted under it. */
    private void joinPump() {
        Thread p = pump;
        pump = null;
        if (p == null || p == Thread.currentThread()) return;
        p.interrupt();
        try {
            p.join(3000);
        } catch (InterruptedException ignored) {
        }
    }

    private void stopSession() {
        SessionManager sm = session;
        if (sm == null) return;
        try {
            sm.removeListener(alerts);
        } catch (Throwable ignored) {
        }
        try {
            sm.stop(); // blocks; never call on the main thread
        } catch (Throwable ignored) {
        }
    }

    private void closeChannel() {
        FileChannel ch = channel;
        channel = null;
        if (ch == null) return;
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ reads and priorities

    /** Called by the range server before serving a chunk. */
    private void onRead(long position) throws IOException {
        TorrentHandle h = handle;
        if (h == null || !running) throw new IOException("Torrent stopped");
        int piece = (int) Math.min(lastPiece, (fileOffset + position) / pieceLength);
        if (piece == windowPiece) return;
        windowPiece = piece;

        for (int old : deadlinePieces) {
            try {
                h.resetPieceDeadline(old);
            } catch (Throwable ignored) {
            }
        }
        deadlinePieces.clear();
        int lo = Math.max(firstPiece, piece - 8);
        int hi = Math.min(lastPiece, piece + WINDOW_PIECES + AHEAD_PIECES);
        for (int p = lo; p <= hi; p++) h.piecePriority(p, Priority.DEFAULT);
        int windowEnd = Math.min(lastPiece + 1, piece + WINDOW_PIECES);
        for (int p = piece; p < windowEnd; p++) {
            h.piecePriority(p, Priority.TOP_PRIORITY);
            h.setPieceDeadline(p, 15_000);
            deadlinePieces.add(p);
        }
    }

    /** Blocks until every piece covering the requested range has been downloaded, then reads it. */
    private byte[] readSelected(long position, int length) throws IOException {
        TorrentHandle h = handle;
        FileChannel ch = channel;
        if (h == null || ch == null || !running) throw new IOException("Torrent stopped");
        onRead(position);
        int first = (int) ((fileOffset + position) / pieceLength);
        int last = (int) ((fileOffset + position + length - 1) / pieceLength);
        for (int p = first; p <= last; p++) {
            long deadline = System.currentTimeMillis() + PIECE_READ_TIMEOUT_MS;
            while (!h.havePiece(p)) {
                if (!running) throw new IOException("Torrent stopped");
                if (System.currentTimeMillis() > deadline) throw new IOException("Torrent is stalled");
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted");
                }
            }
        }
        byte[] data = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        int done = 0;
        int retries = 0;
        while (done < length) {
            bb.clear();
            bb.limit(length - done);
            int n = ch.read(bb, position + done);
            if (n > 0) {
                done += n;
                retries = 0;
            } else if (++retries > 100) {
                // n == 0, or -1 while libtorrent hasn't grown the file to its full size on disk yet
                throw new IOException(n < 0 ? "Unexpected end of file" : "Downloaded data isn't readable yet");
            } else {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new IOException("Interrupted");
                }
            }
        }
        return data;
    }

    // ------------------------------------------------------------------ local HTTP server

    /** Minimal loopback-only HTTP server with byte ranges, because VLC seeks with new ranged connections. */
    private final class RangeServer {
        private final String token;
        private final String name;
        private final long size;
        private final String mime;
        private final Set<Socket> open = Collections.synchronizedSet(new HashSet<>());
        private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "torrent-conn");
            t.setDaemon(true);
            return t;
        });
        private ServerSocket server;
        private volatile boolean active;

        RangeServer(String name, long size, String mime) {
            byte[] rnd = new byte[12];
            new SecureRandom().nextBytes(rnd);
            StringBuilder sb = new StringBuilder();
            for (byte b : rnd) sb.append(String.format(Locale.ROOT, "%02x", b));
            token = sb.toString();
            this.name = name;
            this.size = size;
            this.mime = mime;
        }

        int start() throws IOException {
            // Bind exactly the address the URL names. On Android getLoopbackAddress() is ::1 (IPv6), so a
            // server bound there refuses VLC's connection to 127.0.0.1 and every torrent fails to open.
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
            active = true;
            Thread t = new Thread(() -> {
                while (active) {
                    try {
                        final Socket s = server.accept();
                        open.add(s);
                        pool.execute(() -> serve(s));
                    } catch (IOException e) {
                        if (!active) break;
                    } catch (RuntimeException e) {
                        break;
                    }
                }
            }, "torrent-accept");
            t.setDaemon(true);
            t.start();
            return server.getLocalPort();
        }

        String url(int port) {
            return "http://127.0.0.1:" + port + "/" + token + "/" + Uri.encode(name);
        }

        void stop() {
            active = false;
            try {
                if (server != null) server.close();
            } catch (IOException ignored) {
            }
            synchronized (open) {
                for (Socket s : open) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
                open.clear();
            }
            pool.shutdownNow();
            try {
                // let stream threads leave libtorrent calls before the caller tears the session down
                pool.awaitTermination(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        private void serve(Socket s) {
            try {
                s.setSoTimeout(10_000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                String request = readLine(in);
                if (request == null) return;
                String range = null, line;
                for (int i = 0; i < 100 && (line = readLine(in)) != null && !line.isEmpty(); i++) {
                    if (line.regionMatches(true, 0, "range:", 0, 6)) range = line.substring(6).trim();
                }
                s.setSoTimeout(0);
                String[] parts = request.split(" ");
                if (parts.length < 2 || !(parts[0].equals("GET") || parts[0].equals("HEAD"))
                        || !parts[1].startsWith("/" + token + "/")) {
                    status(out, "404 Not Found", null);
                    return;
                }
                long start = 0, end = size - 1;
                boolean partial = false;
                if (range != null && size > 0 && range.regionMatches(true, 0, "bytes=", 0, 6)) {
                    String spec = range.substring(6).trim();
                    int comma = spec.indexOf(',');
                    if (comma >= 0) spec = spec.substring(0, comma).trim();
                    int dash = spec.indexOf('-');
                    try {
                        if (dash >= 0) {
                            String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
                            if (a.isEmpty()) {
                                long suffix = Long.parseLong(b);
                                start = Math.max(0, size - suffix);
                                if (suffix <= 0) start = size;
                            } else {
                                start = Long.parseLong(a);
                                if (!b.isEmpty()) end = Math.min(Long.parseLong(b), size - 1);
                            }
                            if (start >= size || start > end) {
                                status(out, "416 Range Not Satisfiable", "Content-Range: bytes */" + size + "\r\n");
                                return;
                            }
                            partial = true;
                        }
                    } catch (NumberFormatException bad) {
                        start = 0;
                        end = size - 1;
                        partial = false;
                    }
                }
                long length = size == 0 ? 0 : end - start + 1;
                StringBuilder h = new StringBuilder();
                h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                h.append("Content-Type: ").append(mime).append("\r\n");
                h.append("Accept-Ranges: bytes\r\n");
                h.append("Content-Length: ").append(length).append("\r\n");
                if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(size).append("\r\n");
                h.append("Connection: close\r\n\r\n");
                out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
                if (!parts[0].equals("HEAD")) {
                    long pos = start;
                    long left = length;
                    while (left > 0 && active && running) {
                        int n = (int) Math.min(64 * 1024, left);
                        byte[] data = readSelected(pos, n);
                        out.write(data, 0, n);
                        pos += n;
                        left -= n;
                    }
                }
                out.flush();
            } catch (Exception ignored) {
                // the player closed the connection (seek/stop): normal
            } finally {
                open.remove(s);
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void status(OutputStream out, String code, String extra) throws IOException {
            out.write(("HTTP/1.1 " + code + "\r\n" + (extra == null ? "" : extra)
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            if (sb.length() >= 8192) throw new IOException("Header line too long");
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String guessMime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".ts") || n.endsWith(".m2ts") || n.endsWith(".mts")) return "video/mp2t";
        if (n.endsWith(".mpg") || n.endsWith(".mpeg")) return "video/mpeg";
        return "application/octet-stream";
    }

    private static File downloadDir(Context ctx) {
        File[] dirs = ctx.getExternalFilesDirs(null);
        File best = null;
        long bestFree = -1;
        if (dirs != null) {
            for (File d : dirs) {
                if (d == null) continue;
                if (!d.exists() && !d.mkdirs()) continue;
                if (!d.canWrite()) continue;
                long free = d.getUsableSpace();
                if (free > bestFree) {
                    bestFree = free;
                    best = d;
                }
            }
        }
        if (best == null) best = ctx.getFilesDir();
        return new File(best, "torrents");
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private String bootstrapNodes() {
        String[] hosts = {"router.bittorrent.com:6881", "router.utorrent.com:6881",
                "dht.transmissionbt.com:6881", "dht.libtorrent.org:25401"};
        List<String> nodes = new ArrayList<>();
        for (String spec : hosts) {
            int colon = spec.lastIndexOf(':');
            String host = spec.substring(0, colon);
            String port = spec.substring(colon + 1);
            try {
                // prefer IPv4: many home networks have no working IPv6 route
                java.net.InetAddress pick = null;
                for (java.net.InetAddress a : Net.resolve(host)) {
                    if (a.isAnyLocalAddress() || a.isLoopbackAddress()) continue;
                    if (a instanceof java.net.Inet4Address) {
                        pick = a;
                        break;
                    }
                    if (pick == null) pick = a;
                }
                if (pick != null) {
                    String ip = pick.getHostAddress();
                    if (ip.indexOf('%') > 0) ip = ip.substring(0, ip.indexOf('%'));
                    if (ip.indexOf(':') >= 0) ip = "[" + ip + "]"; // IPv6 literal
                    nodes.add(ip + ":" + port);
                }
            } catch (Exception ignored) {
                // leave this IP out; the host name below still lets libtorrent try system DNS
            }
        }
        // Keep the names too, so system DNS is still tried when DoH is unreachable.
        for (String spec : hosts) nodes.add(spec);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(nodes.get(i));
        }
        return sb.toString();
    }

    private static String human(long bytes) {
        if (bytes < 0) bytes = 0;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, i == 0 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }
}
