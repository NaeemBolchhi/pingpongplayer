package nb.pingpong.pong;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class MainActivity extends FragmentActivity {
    private static final String SERVICE_TYPE = "_streamcast._tcp.";
    private static final int CONTROL_PORT = 8888;
    private static final int SEEK_STEP_MS = 10000;
    private static final int CONTROLS_TIMEOUT_MS = 5000;

    private VLCVideoLayout videoLayout;
    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private LinearLayout controlsOverlay;
    private SeekBar seekBar;
    private TextView tvTime, tvIdle, tvTitle;
    private Button btnPlayPause, btnSubtitle;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean controlsVisible = false;
    private boolean viewsAttached = false;

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private String serviceName = "Pong (" + Build.MODEL + ")";

    private volatile DatagramSocket udpSocket;
    private volatile boolean serverRunning = false;

    private final Gson gson = new Gson();
    private SharedPreferences prefs;

    // playback bookkeeping
    private volatile String currentUrl;
    private volatile String currentTitle = "";
    private final List<File> subtitleFiles = new ArrayList<>();

    // subtitle style / display settings (persisted)
    private int subSize, subColor, subOutline, subBg;
    private boolean subBold, hdr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("PongPrefs", Context.MODE_PRIVATE);
        subSize = prefs.getInt("sub_size", 100);
        subColor = prefs.getInt("sub_color", 0xFFFFFF);
        subOutline = prefs.getInt("sub_outline", 4);
        subBg = prefs.getInt("sub_bg", 0);
        subBold = prefs.getBoolean("sub_bold", false);
        hdr = prefs.getBoolean("hdr", false);

        videoLayout = findViewById(R.id.video_layout);
        controlsOverlay = findViewById(R.id.controls_overlay);
        seekBar = findViewById(R.id.seek_bar);
        tvTime = findViewById(R.id.tv_time);
        tvIdle = findViewById(R.id.tv_idle);
        tvTitle = findViewById(R.id.tv_title);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSubtitle = findViewById(R.id.btn_subtitle);

        tvIdle.setText("Pong! is ready as \"" + serviceName + "\"\n\nShare a link to Ping! on your phone to start playing.");

        createPlayer();
        applyHdr();

        btnPlayPause.setOnClickListener(v -> {
            togglePlayPause();
            showControls();
        });
        btnSubtitle.setOnClickListener(v -> {
            toggleSubtitles();
            showControls();
        });

        handler.postDelayed(updateUiRunnable, 500);
        registerService();
        startUdpServer();
    }

    @Override
    protected void onStart() {
        super.onStart();
        attachViews();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mediaPlayer != null) {
            try {
                mediaPlayer.pause();
            } catch (Exception ignored) {
            }
        }
        detachViews();
    }

    // ---------------------------------------------------------------- player

    private void createPlayer() {
        ArrayList<String> args = new ArrayList<>();
        args.add("--http-reconnect");
        int rel = Math.max(4, Math.min(40, Math.round(1600f / Math.max(10, subSize))));
        args.add("--freetype-rel-fontsize=" + rel);
        args.add("--freetype-color=" + subColor);
        args.add("--freetype-outline-thickness=" + subOutline);
        args.add("--freetype-background-opacity=" + subBg);
        if (subBold) args.add("--freetype-bold");
        libVLC = new LibVLC(this, args);
        mediaPlayer = new MediaPlayer(libVLC);
        if (viewsAttached) {
            viewsAttached = false;
            attachViews();
        }
    }

    private void attachViews() {
        if (mediaPlayer != null && !viewsAttached) {
            mediaPlayer.attachViews(videoLayout, null, true, false);
            viewsAttached = true;
        }
    }

    private void detachViews() {
        if (mediaPlayer != null && viewsAttached) {
            mediaPlayer.detachViews();
            viewsAttached = false;
        }
    }

    /** Subtitle style options are LibVLC init options, so the player has to be recreated. */
    private void rebuildPlayer() {
        String url = currentUrl;
        long time = 0;
        boolean wasPlaying = false;
        try {
            if (mediaPlayer != null) {
                time = Math.max(0, mediaPlayer.getTime());
                wasPlaying = mediaPlayer.isPlaying();
            }
        } catch (Exception ignored) {
        }
        boolean attached = viewsAttached;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            detachViews();
            mediaPlayer.release();
        }
        if (libVLC != null) libVLC.release();
        createPlayer();
        if (attached) attachViews();
        if (url != null) {
            final List<File> subs = new ArrayList<>(subtitleFiles);
            startMedia(url, time, wasPlaying);
            handler.postDelayed(() -> {
                for (File f : subs) addSubtitleFile(f, false);
            }, 1000);
        }
    }

    private void playMedia(String url) {
        subtitleFiles.clear();
        currentUrl = url;
        currentTitle = titleFromUrl(url);
        startMedia(url, 0, true);
    }

    private void startMedia(String url, long startMs, boolean play) {
        if (mediaPlayer == null) return;
        try {
            Media media = new Media(libVLC, Uri.parse(url));
            if (startMs > 1000) media.addOption(":start-time=" + (startMs / 1000.0));
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
            if (!play) {
                handler.postDelayed(() -> {
                    try {
                        mediaPlayer.pause();
                    } catch (Exception ignored) {
                    }
                }, 800);
            }
            tvIdle.setVisibility(View.GONE);
            hideControls();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot play: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String titleFromUrl(String url) {
        try {
            String seg = Uri.parse(url).getLastPathSegment();
            if (seg != null && !seg.isEmpty()) return seg;
        } catch (Exception ignored) {
        }
        return url;
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        else mediaPlayer.play();
    }

    private void seekBy(long deltaMs) {
        if (mediaPlayer == null) return;
        long len = mediaPlayer.getLength();
        seekTo(mediaPlayer.getTime() + deltaMs, len);
    }

    private void seekTo(long ms, long len) {
        if (mediaPlayer == null) return;
        long t = Math.max(0, ms);
        if (len > 0) t = Math.min(t, len - 500 > 0 ? len - 500 : len);
        mediaPlayer.setTime(t);
    }

    private void toggleSubtitles() {
        if (mediaPlayer == null) return;
        MediaPlayer.TrackDescription[] tracks = mediaPlayer.getSpuTracks();
        if (tracks == null || tracks.length < 2) {
            Toast.makeText(this, "No subtitles available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mediaPlayer.getSpuTrack() == -1) {
            // tracks[0] is normally the "Disable" entry
            int id = tracks[1].id;
            mediaPlayer.setSpuTrack(id);
            Toast.makeText(this, "Subtitles on", Toast.LENGTH_SHORT).show();
        } else {
            mediaPlayer.setSpuTrack(-1);
            Toast.makeText(this, "Subtitles off", Toast.LENGTH_SHORT).show();
        }
    }

    private void addSubtitleFile(File f, boolean remember) {
        if (mediaPlayer == null) return;
        try {
            if (remember) subtitleFiles.add(f);
            mediaPlayer.addSlave(Media.Slave.Type.Subtitle, Uri.fromFile(f), true);
        } catch (Exception e) {
            Toast.makeText(this, "Subtitle error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void downloadSubtitle(final String urlStr) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "PingPong Pong/1.0");
                InputStream in = new BufferedInputStream(c.getInputStream());
                in.mark(2);
                int b1 = in.read();
                int b2 = in.read();
                in.reset();
                if (b1 == 0x1f && b2 == 0x8b) in = new GZIPInputStream(in);

                String path = Uri.parse(urlStr).getPath();
                String ext = ".srt";
                if (path != null) {
                    String p = path.toLowerCase();
                    if (p.endsWith(".gz")) p = p.substring(0, p.length() - 3);
                    for (String e : new String[]{".srt", ".ass", ".ssa", ".vtt", ".sub"}) {
                        if (p.endsWith(e)) ext = e;
                    }
                }
                final File f = new File(getCacheDir(), "sub_" + System.currentTimeMillis() + ext);
                try (OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                in.close();
                runOnUiThread(() -> {
                    addSubtitleFile(f, true);
                    Toast.makeText(this, "Subtitles loaded", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Subtitle download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "sub-download").start();
    }

    private void applyHdr() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                getWindow().setColorMode(hdr ? ActivityInfo.COLOR_MODE_HDR : ActivityInfo.COLOR_MODE_DEFAULT);
            } catch (Exception ignored) {
            }
        }
    }

    private void saveStyle() {
        prefs.edit()
                .putInt("sub_size", subSize)
                .putInt("sub_color", subColor)
                .putInt("sub_outline", subOutline)
                .putInt("sub_bg", subBg)
                .putBoolean("sub_bold", subBold)
                .putBoolean("hdr", hdr)
                .apply();
    }

    // ------------------------------------------------------------------- UI

    private final Runnable updateUiRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (mediaPlayer != null && currentUrl != null && controlsVisible) {
                    long len = mediaPlayer.getLength();
                    long t = mediaPlayer.getTime();
                    if (len > 0) seekBar.setProgress((int) (t * 1000 / len));
                    tvTime.setText(formatTime(t) + " / " + (len > 0 ? formatTime(len) : "--:--"));
                    tvTitle.setText(currentTitle);
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    private static String formatTime(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    private void showControls() {
        if (currentUrl == null) return;
        controlsOverlay.setVisibility(View.VISIBLE);
        controlsVisible = true;
        handler.removeCallbacks(hideControlsRunnable);
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS);
    }

    private void hideControls() {
        controlsOverlay.setVisibility(View.GONE);
        controlsVisible = false;
        handler.removeCallbacks(hideControlsRunnable);
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
    }

    private final Runnable hideControlsRunnable = this::hideControls;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        View focus = getCurrentFocus();
        boolean onButton = controlsVisible && (focus == btnPlayPause || focus == btnSubtitle);

        // keys that work the same everywhere
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                showControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.play();
                showControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
                showControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD:
                seekBy(-SEEK_STEP_MS);
                showControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD:
                seekBy(SEEK_STEP_MS);
                showControls();
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
            case KeyEvent.KEYCODE_MENU:
                toggleSubtitles();
                showControls();
                return true;
            default:
                break;
        }

        if (onButton) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideControls();
                return true;
            }
            // let the system move focus between buttons; keep the overlay alive
            handler.removeCallbacks(hideControlsRunnable);
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS);
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                togglePlayPause();
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-SEEK_STEP_MS);
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(SEEK_STEP_MS);
                showControls();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showControls();
                if (controlsVisible) btnPlayPause.requestFocus();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (controlsVisible) {
                    hideControls();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------- networking

    private void registerService() {
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(serviceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(CONTROL_PORT);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                serviceName = info.getServiceName();
                runOnUiThread(() -> tvIdle.setText("Pong! is ready as \"" + serviceName
                        + "\"\n\nShare a link to Ping! on your phone to start playing."));
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Network registration failed (" + errorCode + ")", Toast.LENGTH_LONG).show());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
            }
        };
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    private void startUdpServer() {
        serverRunning = true;
        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new java.net.InetSocketAddress(CONTROL_PORT));
                udpSocket = socket;
                byte[] buffer = new byte[8192];
                while (serverRunning) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    try {
                        handleControlMessage(message, packet, socket);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                if (serverRunning) e.printStackTrace();
            }
        }, "udp-server").start();
    }

    private void handleControlMessage(String message, DatagramPacket from, DatagramSocket socket) throws Exception {
        if (message.equals("STATUS")) {
            byte[] data = ("STATUS:" + gson.toJson(buildStatus())).getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, from.getSocketAddress()));
            return;
        }
        int idx = message.indexOf(':');
        final String cmd = idx < 0 ? message : message.substring(0, idx);
        final String arg = idx < 0 ? "" : message.substring(idx + 1);

        runOnUiThread(() -> {
            try {
                executeCommand(cmd, arg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void executeCommand(String cmd, String arg) {
        MediaPlayer mp = mediaPlayer;
        switch (cmd) {
            case "PLAY":
                playMedia(arg.trim());
                break;
            case "PAUSE":
                if (mp != null && mp.isPlaying()) mp.pause();
                break;
            case "RESUME":
                if (mp != null && !mp.isPlaying()) mp.play();
                break;
            case "SEEK":
                if (mp != null) seekTo(Long.parseLong(arg), mp.getLength());
                break;
            case "SEEK_REL":
                seekBy(Long.parseLong(arg));
                break;
            case "VOLUME":
                if (mp != null) mp.setVolume(Math.max(0, Math.min(100, Integer.parseInt(arg))));
                break;
            case "RATE":
                if (mp != null) mp.setRate(Float.parseFloat(arg));
                break;
            case "AUDIO_TRACK":
                if (mp != null) mp.setAudioTrack(Integer.parseInt(arg));
                break;
            case "SPU_TRACK":
                if (mp != null) mp.setSpuTrack(Integer.parseInt(arg));
                break;
            case "AUDIO_DELAY": // ms -> us
                if (mp != null) mp.setAudioDelay(Long.parseLong(arg) * 1000L);
                break;
            case "SUBTITLE_DELAY": // ms -> us
                if (mp != null) mp.setSpuDelay(Long.parseLong(arg) * 1000L);
                break;
            case "SUB_URL":
                downloadSubtitle(arg.trim());
                break;
            case "HDR":
                hdr = arg.equals("ON");
                saveStyle();
                applyHdr();
                break;
            case "STYLE": {
                JsonObject o = JsonParser.parseString(arg).getAsJsonObject();
                int newSize = o.get("size").getAsInt();
                int newColor = o.get("color").getAsInt();
                int newOutline = o.get("outline").getAsInt();
                int newBg = o.get("bg").getAsInt();
                boolean newBold = o.get("bold").getAsBoolean();
                boolean changed = newSize != subSize || newColor != subColor || newOutline != subOutline
                        || newBg != subBg || newBold != subBold;
                subSize = newSize;
                subColor = newColor;
                subOutline = newOutline;
                subBg = newBg;
                subBold = newBold;
                saveStyle();
                if (changed) rebuildPlayer();
                break;
            }
            default:
                break;
        }
    }

    private TvStatus buildStatus() {
        TvStatus s = new TvStatus();
        s.title = currentTitle;
        s.subSize = subSize;
        s.subColor = subColor;
        s.subOutline = subOutline;
        s.subBg = subBg;
        s.subBold = subBold;
        s.hdr = hdr;
        MediaPlayer mp = mediaPlayer;
        if (mp == null || currentUrl == null) return s;
        try {
            int st = mp.getPlayerState();
            if (st == Media.State.Opening) s.state = "opening";
            else if (st == Media.State.Playing) s.state = "playing";
            else if (st == Media.State.Paused) s.state = "paused";
            else if (st == Media.State.Stopped) s.state = "stopped";
            else if (st == Media.State.Ended) s.state = "ended";
            else if (st == Media.State.Error) s.state = "error";
            else s.state = "idle";
            s.time = Math.max(0, mp.getTime());
            s.length = Math.max(0, mp.getLength());
            s.seekable = mp.isSeekable();
            s.volume = mp.getVolume();
            s.rate = mp.getRate();
            s.audioTrack = mp.getAudioTrack();
            s.spuTrack = mp.getSpuTrack();
            s.audioDelay = mp.getAudioDelay() / 1000L;
            s.spuDelay = mp.getSpuDelay() / 1000L;
            fillTracks(s.audioTracks, mp.getAudioTracks());
            fillTracks(s.spuTracks, mp.getSpuTracks());
        } catch (Exception ignored) {
        }
        return s;
    }

    private static void fillTracks(List<TvStatus.Track> out, MediaPlayer.TrackDescription[] tracks) {
        if (tracks == null) return;
        for (int i = 0; i < tracks.length && i < 24; i++) {
            String n = tracks[i].name == null ? ("Track " + tracks[i].id) : tracks[i].name;
            if (n.length() > 60) n = n.substring(0, 60);
            out.add(new TvStatus.Track(tracks[i].id, n));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        serverRunning = false;
        handler.removeCallbacksAndMessages(null);
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {
            }
        }
        DatagramSocket s = udpSocket;
        if (s != null) s.close();
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            detachViews();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
    }
}
