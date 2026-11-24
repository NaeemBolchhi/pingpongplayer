package nb.pingpong.ping;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams the files shared to this app from the phone to the TV.
 * <p>
 * A share can contain several videos, each with its own matching subtitles. The first video is played; the rest
 * stay in a playlist and can be selected from the remote without sharing again. Files are served by
 * {@link CastServer}; switching an item only changes the URL sent to the TV.
 * <p>
 * Runs as a foreground service of type connectedDevice (no system time limit), so the stream continues with the
 * screen off or the app closed. It stops when the user presses Stop in the notification, when another share
 * replaces it, or when the TV stops answering.
 */
public class CastService extends Service {
    static final String ACTION_STOP = "nb.pingpong.ping.CAST_STOP";

    private static final String CHANNEL = "cast";
    private static final int NOTE_ID = 4201;
    private static final long FIRST_CONNECT_MS = 20_000;
    private static final long DISCONNECT_GRACE_MS = 15_000;
    private static final long SUB_FALLBACK_MS = 15_000;

    /** One shared file. Its access was opened by ShareActivity while the share's temporary permission was valid. */
    static final class Item {
        final String name;
        final String mime;
        final ParcelFileDescriptor pfd;
        final InputStream in; // only when the provider can't give a file descriptor

        Item(String name, String mime, ParcelFileDescriptor pfd, InputStream in) {
            this.name = name;
            this.mime = mime;
            this.pfd = pfd;
            this.in = in;
        }

        void close() {
            try {
                if (pfd != null) pfd.close();
            } catch (IOException ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** One playlist entry: a video and the subtitles whose names match it. */
    static final class Play {
        final Item video;
        final List<Item> subs;

        Play(Item video, List<Item> subs) {
            this.video = video;
            this.subs = subs;
        }

        String name() {
            return video.name;
        }
    }

    static final class Pending {
        final DeviceInfo device;
        final List<Play> playlist;
        final int selected;

        Pending(DeviceInfo device, List<Play> playlist, int selected) {
            this.device = device;
            this.playlist = playlist;
            this.selected = selected;
        }
    }

    /** A file ready to be served. */
    private static final class Prepared {
        final String name;
        final String mime;
        final ParcelFileDescriptor pfd;
        final FileInputStream fis;
        final FileChannel channel;
        final long size;
        CastServer.Entry entry;

        Prepared(String name, String mime, ParcelFileDescriptor pfd, FileInputStream fis, FileChannel channel, long size) {
            this.name = name;
            this.mime = mime;
            this.pfd = pfd;
            this.fis = fis;
            this.channel = channel;
            this.size = size;
        }

        void close() {
            try {
                pfd.close(); // the stream/channel only borrow this descriptor
            } catch (IOException ignored) {
            }
        }
    }

    /** Everything materialised for one playlist entry. */
    private static final class Serving {
        final Prepared video;
        final List<Prepared> subs = new ArrayList<>();

        Serving(Prepared video) {
            this.video = video;
        }

        String name() {
            return video.name;
        }

        void close() {
            video.close();
            for (Prepared s : subs) s.close();
        }
    }

    /** Read-only playlist snapshot for the remote. */
    public static final class Entry {
        public final String name;
        public final int subtitles;

        Entry(String name, int subtitles) {
            this.name = name;
            this.subtitles = subtitles;
        }
    }

    private static volatile Pending pending;
    private static volatile CastService instance;
    private static volatile List<Entry> snapshot = Collections.emptyList();
    private static volatile int currentIndex = -1;

    /** Called by ShareActivity: hands the files over and starts the service. */
    static void start(Context c, Pending p) {
        pending = p;
        ContextCompat.startForegroundService(c, new Intent(c, CastService.class));
    }

    public static List<Entry> playlist() {
        return snapshot;
    }

    public static int currentIndex() {
        return currentIndex;
    }

    /** Switches to another video of the current share; safe to call from the UI thread. */
    public static void select(int index) {
        CastService s = instance;
        if (s != null) s.selectIndex(index);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // session state (main thread)
    private int generation = 0;
    private DeviceInfo device;
    private List<Play> playlist = new ArrayList<>();
    private final List<Item> allItems = new ArrayList<>();
    private final Map<Integer, Serving> served = new HashMap<>();
    private int selectedIndex = -1;
    private CastServer server;
    private TvClient client;
    private List<String> subUrls = new ArrayList<>();
    private String videoName = "";
    private boolean subSent;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    private final Runnable giveUp = () -> {
        toast("TV disconnected, sharing stopped");
        stopAll();
    };
    private final Runnable subFallback = this::sendSubtitles;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            return START_NOT_STICKY;
        }
        Pending p = pending;
        pending = null;
        // Android requires startForeground() soon after startForegroundService(), whatever happens next.
        boolean valid = p != null && !p.playlist.isEmpty() && p.selected >= 0 && p.selected < p.playlist.size();
        String first = valid ? "Preparing " + p.playlist.get(p.selected).name() + "…" : "a video";
        if (!goForeground(p == null ? null : p.device, first) || !valid) {
            if (p != null) closeAllItems(p);
            stopAll();
            return START_NOT_STICKY;
        }
        begin(p);
        return START_NOT_STICKY; // the file descriptors can't be restored after the process dies
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        endSession();
        worker.shutdownNow();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ session

    private void begin(final Pending p) {
        endSession();
        final int g = generation;
        device = p.device;
        playlist = new ArrayList<>(p.playlist);
        for (Play play : playlist) {
            allItems.add(play.video);
            allItems.addAll(play.subs);
        }
        publish(); // let the remote show the playlist immediately
        acquireLocks();
        worker.execute(() -> {
            try {
                if (server == null) {
                    InetAddress local = SubtitleServer.localAddressToward(device.host, device.port);
                    CastServer srv = new CastServer(device.host);
                    final int port = srv.start();
                    final String host = localAddress(local);
                    handler.post(() -> {
                        if (g == generation) {
                            server = srv;
                            baseUrl = "http://" + host + ":" + port;
                            // the first item was already queued before the server came up
                            worker.execute(() -> prepareAndPlay(g, p.selected));
                        } else {
                            srv.stop();
                        }
                    });
                } else {
                    prepareAndPlay(g, p.selected);
                }
            } catch (Exception e) {
                handler.post(() -> {
                    if (g != generation) return;
                    toast("Couldn't share the video: " + e.getMessage());
                    stopAll();
                });
            }
        });
    }

    private volatile String baseUrl;

    private static String localAddress(InetAddress local) {
        String host = local.getHostAddress();
        int pct = host.indexOf('%');
        if (pct > 0) host = host.substring(0, pct);
        if (host.indexOf(':') >= 0) host = "[" + host + "]";
        return host;
    }

    /** Worker thread: makes the selected files servable, then tells the TV to play them. */
    private void prepareAndPlay(int g, int index) {
        if (g != generation || index < 0 || index >= playlist.size() || server == null) return;
        Serving s = served.get(index);
        if (s == null) {
            Play play = playlist.get(index);
            Prepared v;
            try {
                v = materialize(play.video);
            } catch (IOException e) {
                handler.post(() -> toast("Couldn't share " + play.video.name + ": " + e.getMessage()));
                return;
            }
            s = new Serving(v);
            served.put(index, s);
            if (server != null) {
                v.entry = server.add(v.name, v.mime, v.channel, v.size);
                for (Item sub : play.subs) {
                    try {
                        Prepared ps = materialize(sub);
                        ps.entry = server.add(ps.name, ps.mime, ps.channel, ps.size);
                        s.subs.add(ps);
                    } catch (IOException e) {
                        sub.close();
                        postToast("Subtitle skipped: " + e.getMessage());
                    }
                }
            }
        }
        String videoUrl = baseUrl + "/" + s.video.entry.token + "/" + Uri.encode(s.video.name);
        List<String> urls = new ArrayList<>();
        for (Prepared ps : s.subs) {
            urls.add(baseUrl + "/" + ps.entry.token + "/" + Uri.encode(ps.name));
        }
        handler.post(() -> live(g, index, s.name(), videoUrl, urls));
    }

    /** Main thread: the server is up, tell the TV. */
    private void live(int g, int index, String name, String videoUrl, List<String> urls) {
        if (g != generation) return;
        selectedIndex = index;
        videoName = name;
        subUrls = urls;
        subSent = false;
        publish();
        goForeground(device, "Sharing " + (index + 1) + "/" + playlist.size() + ": " + name
                + (urls.isEmpty() ? "" : " + " + urls.size() + " subtitle" + (urls.size() == 1 ? "" : "s")));

        if (client == null) {
            client = new TvClient(device, new TvClient.Listener() {
                @Override
                public void onConnectionChanged(boolean connected) {
                    handler.removeCallbacks(giveUp);
                    if (!connected) handler.postDelayed(giveUp, DISCONNECT_GRACE_MS);
                }

                @Override
                public void onStatus(TvStatus st) {
                    // The TV drops its subtitles when a new video starts, so wait until it plays this one.
                    if (!subUrls.isEmpty() && !subSent && videoName.equals(st.title) && "playing".equals(st.state)) {
                        sendSubtitles();
                    }
                }
            });
            client.start();
            handler.postDelayed(giveUp, FIRST_CONNECT_MS);
        }
        sendUdp("PLAY:" + videoUrl);
        handler.removeCallbacks(subFallback);
        if (!subUrls.isEmpty()) handler.postDelayed(subFallback, SUB_FALLBACK_MS);
    }

    private void publish() {
        List<Entry> list = new ArrayList<>();
        for (Play p : playlist) list.add(new Entry(p.name(), p.subs.size()));
        snapshot = Collections.unmodifiableList(list);
        currentIndex = selectedIndex;
    }

    /** Switches to another item of the same share without restarting the service. */
    private void selectIndex(int index) {
        if (index < 0 || index >= playlist.size() || index == selectedIndex) return;
        final int g = generation;
        handler.post(() -> {
            if (g != generation) return;
            handler.removeCallbacks(subFallback);
            subSent = false;
            subUrls = new ArrayList<>();
            worker.execute(() -> prepareAndPlay(g, index));
        });
    }

    private void sendSubtitles() {
        if (subUrls.isEmpty() || subSent) return;
        subSent = true;
        handler.removeCallbacks(subFallback);
        for (String url : subUrls) sendUdp("SUB_URL:" + url);
    }

    private void sendUdp(final String message) {
        final DeviceInfo d = device;
        if (d == null) return;
        worker.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(d.host), d.port));
            } catch (Exception ignored) {
            }
        });
    }

    /** Releases everything of the current session (and cancels one that is still preparing). */
    private void endSession() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (client != null) {
            client.stop();
            client = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        for (Serving s : served.values()) s.close();
        served.clear();
        closeAllItems(null);
        allItems.clear();
        playlist = new ArrayList<>();
        selectedIndex = -1;
        subUrls = new ArrayList<>();
        videoName = "";
        baseUrl = null;
        snapshot = Collections.emptyList();
        currentIndex = -1;
        releaseLocks();
    }

    private void closeAllItems(Pending extra) {
        if (extra != null) {
            for (Play p : extra.playlist) {
                p.video.close();
                for (Item sub : p.subs) sub.close();
            }
        }
        for (Item it : allItems) it.close();
    }

    private void stopAll() {
        endSession();
        stopForeground(true);
        stopSelf();
    }

    // ---------------------------------------------------------------- the files

    /** Gives back a seekable file with a known size, copying to cache only if the source can't be read in place. */
    private Prepared materialize(Item it) throws IOException {
        if (it.pfd != null && isSeekable(it.pfd)) {
            long size = 0;
            try {
                size = Os.fstat(it.pfd.getFileDescriptor()).st_size;
            } catch (ErrnoException ignored) {
            }
            if (size > 0) {
                FileInputStream fis = new FileInputStream(it.pfd.getFileDescriptor());
                return new Prepared(it.name, it.mime, it.pfd, fis, fis.getChannel(), size);
            }
        }
        // Pipe-like source (some cloud providers) or unknown size: a range-capable server needs a real file.
        File f = File.createTempFile("cast", ".tmp", getCacheDir());
        InputStream in = it.in != null ? it.in : new FileInputStream(it.pfd.getFileDescriptor());
        try (OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw e;
        }
        it.close();
        ParcelFileDescriptor copy = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        //noinspection ResultOfMethodCallIgnored
        f.delete(); // stays readable through the open descriptor and disappears when it is closed
        FileInputStream fis = new FileInputStream(copy.getFileDescriptor());
        return new Prepared(it.name, it.mime, copy, fis, fis.getChannel(), fis.getChannel().size());
    }

    private static boolean isSeekable(ParcelFileDescriptor pfd) {
        try {
            Os.lseek(pfd.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ notification

    private boolean goForeground(DeviceInfo d, String text) {
        try {
            Notification n = buildNotification(d, text);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTE_ID, n);
            }
            return true;
        } catch (Exception e) {
            toast("Couldn't keep sharing in the background: " + e.getMessage());
            return false;
        }
    }

    private Notification buildNotification(DeviceInfo d, String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Sharing to TV", NotificationManager.IMPORTANCE_LOW));
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent open = d != null
                ? new Intent(this, ControlActivity.class).putExtra("device", d)
                : new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, piFlags);
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, CastService.class).setAction(ACTION_STOP), piFlags);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_cast)
                .setContentTitle(d != null ? "Sharing to " + d.serviceName : "Sharing to TV")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(contentPi)
                .addAction(0, "Stop", stopPi)
                .build();
    }

    // ------------------------------------------------------------------ locks

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ping:cast");
            wake.setReferenceCounted(false);
            wake.acquire();
        } catch (Exception ignored) {
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ping:cast");
            wifi.setReferenceCounted(false);
            wifi.acquire();
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
        try {
            if (wifi != null && wifi.isHeld()) wifi.release();
        } catch (Exception ignored) {
        }
        wake = null;
        wifi = null;
    }

    // ------------------------------------------------------------------ misc

    private void toast(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }

    private void postToast(final String text) {
        handler.post(() -> toast(text));
    }
}
