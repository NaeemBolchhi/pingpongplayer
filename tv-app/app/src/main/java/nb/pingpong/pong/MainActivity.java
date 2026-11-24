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
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.View;
import android.text.format.DateFormat;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class MainActivity extends FragmentActivity {
    private static final String SERVICE_TYPE = "_pingpong._tcp.";
    private static final int CONTROL_PORT = 8888;
    private static final int SEEK_STEP_MS = 10000;
    private static final int SEEK_BAR_STEP_MS = 30000;
    private static final int CONTROLS_TIMEOUT_MS = 5000;
    private static final int LOAD_TIMEOUT_MS = 30000;

    private VLCVideoLayout videoLayout;
    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private View controlsOverlay;
    private SeekBar seekBar;
    private TextView tvTimeCur, tvTimeTotal, tvIdleChip, tvTitle, tvLoadingTitle, tvLoadingStatus;
    private View idleContainer, loadingOverlay, confirmOverlay, topBar, bottomBar, seekGlowLeft, seekGlowRight;
    private ImageButton btnPlayPause, btnBackNav, btnBack10, btnFwd10, btnPlaylist, btnAudio, btnSubtitles;
    private Button btnSpeed;
    private TextView tvStatusLine, tvEndsAt;
    private View statusDot;

    // side panel (playlist / subtitles)
    private static final int PANEL_NONE = 0, PANEL_PLAYLIST = 1, PANEL_SUBS = 2, PANEL_AUDIO = 3;
    private int panelKind = PANEL_NONE;
    private View panelOverlay, panelOpener;
    private TextView panelTitle, panelSubtitle;
    private LinearLayout panelList;
    private ScrollView panelScroll;
    private ViewGroup controlsGroup;

    // the videos of a share sent from a phone (SHARE_LIST); they belong to the URL that was playing then
    private volatile String shareUrl = "";
    private volatile int shareIndex = -1;
    private final List<String> shareNames = new ArrayList<>();
    private final List<Integer> shareSubs = new ArrayList<>();
    private volatile int shareSelect = -1, shareSelectSeq = 0;

    /** Where each subtitle file came from: "torrent" or "phone" (tracks inside the video are "video"). */
    private final Map<String, String> subtitleSource = new ConcurrentHashMap<>();

    private static final float[] SPEED_STEPS = {1f, 1.25f, 1.5f, 1.75f, 2f};
    private final DecimalFormat speedFormat = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));
    private Button btnErrorOk, btnConfirmKeep, btnConfirmStop;
    private volatile boolean confirmShown = false;
    // update prompt
    private View updateOverlay;
    private TextView tvUpdateMsg, tvUpdateStatus;
    private ProgressBar updateBar;
    private Button btnUpdateNow, btnUpdateLater, btnUpdateNever;
    private volatile boolean updateShown = false;
    private volatile boolean checkingUpdate = false;
    private boolean updating = false;
    private UpdateChecker.Update pendingUpdate;
    private View errorOverlay;
    private TextView tvErrorUrl, tvErrorDetail;
    private volatile boolean errorShown = false;
    private volatile boolean loaded = false; // read by the status thread too
    /** 0 = normal settings, 1 = HLS fallback settings (tried automatically once before showing the error). */
    private int loadAttempt = 0;
    private HlsProxy hlsProxy;
    // Many streaming hosts refuse LibVLC's default "VLC/3.x" user agent, so identify as a regular Android player.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Pong) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private TextView seekLeft, seekRight;
    private GestureDetector gestures;
    private long multiTapUntil;
    private boolean swallowTouch;
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

    // torrent playback
    private TorrentStreamer torrents;
    private volatile TorrentStreamer.Snapshot lastTorrent;
    private volatile boolean currentIsTorrent;
    private volatile String torrentMagnet;   // the magnet of the torrent that is loaded
    /** The video played to the end; play/OK then starts it again from the beginning. */
    private volatile boolean ended;
    private String torrentUnavailable = "Torrent playback isn't available on this TV";

    // subtitle style / display settings (persisted)
    private int subSize, subColor, subOutline, subBg;
    private boolean subBold, hdr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("PongPrefs", Context.MODE_PRIVATE);
        installCrashRecorder();
        subSize = prefs.getInt("sub_size", 100);
        subColor = prefs.getInt("sub_color", 0xFFFFFF);
        subOutline = prefs.getInt("sub_outline", 4);
        subBg = prefs.getInt("sub_bg", 0);
        subBold = prefs.getBoolean("sub_bold", false);
        hdr = prefs.getBoolean("hdr", false);

        videoLayout = findViewById(R.id.video_layout);
        controlsOverlay = findViewById(R.id.controls_overlay);
        seekBar = findViewById(R.id.seek_bar);
        tvTimeCur = findViewById(R.id.tv_time_cur);
        tvTimeTotal = findViewById(R.id.tv_time_total);
        idleContainer = findViewById(R.id.idle_container);
        tvIdleChip = findViewById(R.id.tv_idle_chip);
        loadingOverlay = findViewById(R.id.loading_overlay);
        tvLoadingTitle = findViewById(R.id.tv_loading_title);
        tvLoadingStatus = findViewById(R.id.tv_loading_status);
        confirmOverlay = findViewById(R.id.confirm_overlay);
        updateOverlay = findViewById(R.id.update_overlay);
        tvUpdateMsg = findViewById(R.id.tv_update_msg);
        tvUpdateStatus = findViewById(R.id.tv_update_status);
        updateBar = findViewById(R.id.update_bar);
        btnUpdateNow = findViewById(R.id.btn_update_now);
        btnUpdateLater = findViewById(R.id.btn_update_later);
        btnUpdateNever = findViewById(R.id.btn_update_never);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        seekGlowLeft = findViewById(R.id.seek_glow_left);
        seekGlowRight = findViewById(R.id.seek_glow_right);
        btnBackNav = findViewById(R.id.btn_back_nav);
        btnBack10 = findViewById(R.id.btn_back10);
        btnFwd10 = findViewById(R.id.btn_fwd10);
        btnConfirmKeep = findViewById(R.id.btn_confirm_keep);
        btnConfirmStop = findViewById(R.id.btn_confirm_stop);
        tvTitle = findViewById(R.id.tv_title);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSpeed = findViewById(R.id.btn_speed);
        btnPlaylist = findViewById(R.id.btn_playlist);
        btnAudio = findViewById(R.id.btn_audio);
        btnSubtitles = findViewById(R.id.btn_subtitles);
        tvStatusLine = findViewById(R.id.tv_status_line);
        tvEndsAt = findViewById(R.id.tv_ends_at);
        statusDot = findViewById(R.id.status_dot);
        panelOverlay = findViewById(R.id.panel_overlay);
        panelTitle = findViewById(R.id.panel_title);
        panelSubtitle = findViewById(R.id.panel_subtitle);
        panelList = findViewById(R.id.panel_list);
        panelScroll = findViewById(R.id.panel_scroll);
        controlsGroup = (ViewGroup) controlsOverlay;
        panelOverlay.setOnClickListener(v -> closePanel()); // a tap beside the panel closes it
        // the seek bar grows and brightens while it has focus
        seekBar.setOnFocusChangeListener((v, has) -> {
            int p = seekBar.getProgress(), sp = seekBar.getSecondaryProgress();
            seekBar.setProgressDrawable(androidx.core.content.ContextCompat.getDrawable(this,
                    has ? R.drawable.seek_progress_focused : R.drawable.seek_progress));
            seekBar.setProgress(0);
            seekBar.setProgress(p);
            seekBar.setSecondaryProgress(sp);
        });

        goFullscreen();
        keepControlsClearOfCutout();
        setupTouch();
        seekLeft = findViewById(R.id.seek_left);
        seekRight = findViewById(R.id.seek_right);
        errorOverlay = findViewById(R.id.error_overlay);
        tvErrorUrl = findViewById(R.id.tv_error_url);
        tvErrorDetail = findViewById(R.id.tv_error_detail);
        btnErrorOk = findViewById(R.id.btn_error_ok);
        btnErrorOk.setOnClickListener(v -> dismissError());

        serviceName = prefs.getString("device_name", serviceName);
        updateIdleText();

        createPlayer();
        applyHdr();
        setupTorrents();

        btnPlayPause.setOnClickListener(v -> {
            togglePlayPause();
            showControls();
        });
        btnBack10.setOnClickListener(v -> {
            seekBy(-SEEK_STEP_MS);
            showControls();
        });
        btnFwd10.setOnClickListener(v -> {
            seekBy(SEEK_STEP_MS);
            showControls();
        });
        btnBackNav.setOnClickListener(v -> showConfirm());
        btnSpeed.setOnClickListener(v -> {
            cycleSpeed();
            showControls();
        });
        btnPlaylist.setOnClickListener(v -> openPanel(PANEL_PLAYLIST));
        btnAudio.setOnClickListener(v -> openPanel(PANEL_AUDIO));
        btnSubtitles.setOnClickListener(v -> openPanel(PANEL_SUBS));
        btnConfirmKeep.setOnClickListener(v -> dismissConfirm());
        btnConfirmStop.setOnClickListener(v -> {
            dismissConfirm();
            endSession();
        });

        btnUpdateNow.setOnClickListener(v -> startUpdate());
        btnUpdateLater.setOnClickListener(v -> hideUpdate());
        btnUpdateNever.setOnClickListener(v -> {
            UpdateChecker.neverShowAgain(this);
            hideUpdate();
        });
        checkForUpdate();

        handler.postDelayed(updateUiRunnable, 500);
        registerService();
        startUdpServer();
        showLastCrash();
    }

    /** Remembers why the app crashed (Java exceptions only), so the next start can show it. */
    private void installCrashRecorder() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        final SharedPreferences p = prefs;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(sw));
                String trace = sw.toString();
                if (trace.length() > 1500) trace = trace.substring(0, 1500);
                p.edit().putString("last_crash", "Thread " + thread.getName() + ": " + trace).commit();
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private void showLastCrash() {
        String crash = prefs.getString("last_crash", null);
        String exit = ExitReport.take(this, prefs); // native crashes, ANRs, low-memory kills (Android 11+)
        if (crash == null && exit == null) return;
        prefs.edit().remove("last_crash").apply();
        showError(crash != null ? crash : exit);
        tvErrorDetail.setTextSize(11);
        tvErrorDetail.setGravity(android.view.Gravity.START);
        tvErrorUrl.setText("Pong! closed unexpectedly last time. The details below help find the cause.");
    }

    // --------------------------------------------------------------- torrent playback

    private void setupTorrents() {
        // Old downloads are never kept across an app start.
        TorrentStreamer.clear(this);
        try {
            torrents = TorrentStreamer.get(this, new TorrentStreamer.Listener() {
                @Override
                public void onReady(String url, String title) {
                    runOnUiThread(() -> {
                        if (!currentIsTorrent) return;
                        currentUrl = url;
                        currentTitle = title == null || title.isEmpty() ? currentTitle : title;
                        startMedia(url, 0, true);
                    });
                }

                @Override
                public void onState(TorrentStreamer.Snapshot s) {
                    runOnUiThread(() -> {
                        lastTorrent = s;
                        if (currentIsTorrent) updateTorrentUi(s);
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        if (currentIsTorrent) showError(message);
                    });
                }

                @Override
                public void onSubtitles(List<File> files) {
                    runOnUiThread(() -> {
                        if (currentIsTorrent) addTorrentSubtitles(files);
                    });
                }

                @Override
                public void onLoading(String title) {
                    runOnUiThread(() -> {
                        if (currentIsTorrent) showTorrentLoading(title);
                    });
                }
            });
        } catch (TorrentStreamer.Unavailable e) {
            torrents = null;
            torrentUnavailable = e.getMessage() == null ? "Torrent playback isn't available on this TV" : e.getMessage();
        }
    }

    /** Another video of the loaded torrent is being prepared: drop the old one and show the loading screen. */
    private void showTorrentLoading(String title) {
        handler.removeCallbacks(loadTimeout);
        try {
            if (mediaPlayer != null) mediaPlayer.stop();
        } catch (Exception ignored) {
        }
        loaded = false;
        ended = false;
        loadAttempt = 0;
        subtitleFiles.clear();
        hideError();
        if (torrentMagnet != null) currentUrl = torrentMagnet; // "opening" for the phone until it's ready
        if (title != null && !title.isEmpty()) currentTitle = title;
        closePanel();
        tvLoadingStatus.setText(torrentMessage(lastTorrent, false));
        idleContainer.setVisibility(View.GONE);
        hideControls();
        showLoading();
    }

    /** True when a player event belongs to a torrent stream that has since been replaced. */
    private boolean staleTorrentEvent() {
        if (!currentIsTorrent || torrents == null || currentUrl == null) return false;
        if (currentUrl.startsWith("magnet:")) return true; // nothing of ours is in the player yet
        return !currentUrl.equals(torrents.streamUrl());
    }

    private void updateTorrentUi(TorrentStreamer.Snapshot s) {
        if (s == null) return;
        // While the video is already in VLC's hands, currentUrl is the local stream; keep the file name.
        if (s.title != null && !s.title.isEmpty()) currentTitle = s.title;
        tvLoadingTitle.setText(currentTitle);
        tvLoadingStatus.setText(torrentMessage(s, false));
    }

    /**
     * One line describing the torrent's progress. The TV's loading screen shows it and the phone receives the
     * same text in its status, so both always say the same thing.
     */
    static String torrentMessage(TorrentStreamer.Snapshot s, boolean playing) {
        if (s == null) return "Loading torrent…";
        String peers = s.peers == 1 ? "1 peer" : s.peers + " peers";
        String state = s.state == null ? "" : s.state;
        switch (state) {
            case "metadata":
                return s.peers > 0 ? "Loading torrent… getting file list from " + peers
                        : "Loading torrent… looking for peers";
            case "choosing":
                return "Torrent loaded · choose a video on the phone";
            case "checking":
                return "Freeing space… checking the downloaded videos";
            case "buffering":
                return "Buffering " + s.progress + "% · " + peers + " · " + humanRate(s.rate);
            case "streaming":
                return playing ? s.progress + "% downloaded · " + peers + " · " + humanRate(s.rate)
                        : "Starting video · " + peers + " · " + humanRate(s.rate);
            case "error":
                return s.error == null || s.error.isEmpty() ? "Torrent playback failed" : s.error;
            default:
                return "Loading torrent…";
        }
    }

    private static String humanRate(int bytes) {
        if (bytes <= 0) return "0 KB/s";
        if (bytes >= 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB/s", bytes / 1048576.0);
        return (bytes / 1024) + " KB/s";
    }

    /** Adds the sidecar subtitles found in the torrent, converted to SRT where needed. */
    private void addTorrentSubtitles(List<File> files) {
        if (files == null || files.isEmpty()) return;
        boolean first = subtitleFiles.isEmpty();
        for (File f : files) {
            try {
                byte[] data = readFile(f);
                String name = f.getName();
                byte[] srt = SubtitleConverter.toSrt(data);
                String outName = name;
                if (srt != null) {
                    data = srt;
                    int dot = outName.lastIndexOf('.');
                    outName = (dot > 0 ? outName.substring(0, dot) : outName) + ".srt";
                }
                File cached = saveSubtitle(data, outName);
                subtitleSource.put(cached.getAbsolutePath(), "torrent");
                addSubtitleFile(cached, true, first);
                first = false;
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] readFile(File f) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 16 * 1024 * 1024) throw new IOException("Subtitle file too large");
            }
        }
        return out.toByteArray();
    }

    private File saveSubtitle(byte[] data, String name) throws IOException {
        File d = new File(getCacheDir(), "subs_" + System.currentTimeMillis() + "_" + subtitleFiles.size());
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        File out = new File(d, safe.isEmpty() ? "subtitle.srt" : safe);
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(data);
        }
        return out;
    }

    // ----------------------------------------------- touch (phones / tablets used as TV boxes)

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen(); // the bars come back after dialogs, the recents screen and the like
    }

    /**
     * True full screen: the window covers the whole display, including the camera cutout on phones (the theme
     * asks for that too), and the status and navigation bars stay hidden until swiped in.
     */
    private void goFullscreen() {
        android.view.Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            int mode = Build.VERSION.SDK_INT >= 30 ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            if (lp.layoutInDisplayCutoutMode != mode) {
                lp.layoutInDisplayCutoutMode = mode;
                w.setAttributes(lp);
            }
        }
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false);
        androidx.core.view.WindowInsetsControllerCompat c =
                androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** The picture fills the cutout area, but the menu and the side panel keep their buttons and text out of it. */
    private void keepControlsClearOfCutout() {
        final View panel = findViewById(R.id.panel);
        final int pl = panel.getPaddingLeft(), pt = panel.getPaddingTop(), pr = panel.getPaddingRight(),
                pb = panel.getPaddingBottom();
        final View clock = findViewById(R.id.clock_box);
        // padding on the bars themselves, so the shading behind them still reaches the screen edges
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(controlsOverlay, (v, insets) -> {
            androidx.core.graphics.Insets cut = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(cut.left, cut.top, 0, 0);
            clock.setPadding(0, cut.top, cut.right, 0);
            bottomBar.setPadding(cut.left, 0, cut.right, cut.bottom);
            return insets;
        });
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(panelOverlay, (v, insets) -> {
            androidx.core.graphics.Insets cut = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            panel.setPadding(pl, pt + cut.top, pr + cut.right, pb + cut.bottom);
            return insets;
        });
    }

    /** True when a touch belongs to a dialog or to the menu's top/bottom bars rather than the video. */
    private boolean insideOverlay(MotionEvent e) {
        if (confirmShown || errorShown || updateShown || panelKind != PANEL_NONE) return true;
        if (!controlsVisible) return false;
        Rect r = new Rect();
        topBar.getHitRect(r);
        if (r.contains((int) e.getX(), (int) e.getY())) return true;
        bottomBar.getHitRect(r);
        return r.contains((int) e.getX(), (int) e.getY());
    }

    private void setupTouch() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (currentUrl == null || insideOverlay(e)) return false;
                if (controlsVisible) hideControls();
                else showControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (currentUrl == null || insideOverlay(e)) return false;
                float w = getWindow().getDecorView().getWidth();
                if (e.getX() < w / 3f) {
                    seekWithFeedback(-SEEK_STEP_MS);
                    multiTapUntil = SystemClock.uptimeMillis() + 450;
                } else if (e.getX() > w * 2f / 3f) {
                    seekWithFeedback(SEEK_STEP_MS);
                    multiTapUntil = SystemClock.uptimeMillis() + 450;
                } else {
                    togglePlayPause();
                }
                return true;
            }
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || mediaPlayer == null) return;
                long len = mediaPlayer.getLength();
                if (len > 0) tvTimeCur.setText(formatTime(len * progress / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                handler.removeCallbacks(hideControlsRunnable);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (mediaPlayer != null) {
                    long len = mediaPlayer.getLength();
                    if (len > 0) seekTo(len * bar.getProgress() / 1000, len);
                }
                showControls();
            }
        });
    }

    /** Taps: show/hide the menu. Double tap left/right: seek 10s (keep tapping to keep seeking). */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            swallowTouch = false;
            if (!errorShown && currentUrl != null && !insideOverlay(e)
                    && SystemClock.uptimeMillis() < multiTapUntil) {
                float w = getWindow().getDecorView().getWidth();
                if (e.getX() < w / 3f || e.getX() > w * 2f / 3f) {
                    seekWithFeedback(e.getX() < w / 3f ? -SEEK_STEP_MS : SEEK_STEP_MS);
                    multiTapUntil = SystemClock.uptimeMillis() + 450;
                    swallowTouch = true;
                    return true;
                }
            }
        } else if (swallowTouch) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) swallowTouch = false;
            return true;
        }
        if (gestures != null) gestures.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    @Override
    protected void onStart() {
        super.onStart();
        attachViews();
        checkForUpdate(); // a TV app is usually resumed, not relaunched, so check on every return to the screen
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
        args.add("--http-user-agent=" + USER_AGENT);
        int rel = Math.max(4, Math.min(40, Math.round(1600f / Math.max(10, subSize))));
        args.add("--freetype-rel-fontsize=" + rel);
        args.add("--freetype-color=" + subColor);
        args.add("--freetype-outline-thickness=" + subOutline);
        args.add("--freetype-background-opacity=" + subBg);
        if (subBold) args.add("--freetype-bold");
        libVLC = new LibVLC(this, args);
        mediaPlayer = new MediaPlayer(libVLC);
        mediaPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                onPlayerEvent(event);
            }
        });
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
            mediaPlayer.setEventListener(null);
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
                for (File f : subs) addSubtitleFile(f, false, true);
            }, 1000);
        }
    }

    // ---------------------------------------------------- load errors

    private void onPlayerEvent(final MediaPlayer.Event event) {
        runOnUiThread(() -> {
            int t = event.type;
            if ((t == MediaPlayer.Event.EndReached || t == MediaPlayer.Event.EncounteredError)
                    && staleTorrentEvent()) {
                return; // the old stream of a torrent switch closing down
            }
            if (t == MediaPlayer.Event.EndReached) {
                // Keep everything (a torrent stays loaded) so play replays it or the phone picks another.
                ended = true;
                handler.removeCallbacks(loadTimeout);
                hideLoading();
                updatePlayIcon(false);
                closePanel();
                maybeShowUpdate(); // a forced check found an update while this was playing
                showControls(true);
                return;
            }
            if (t == MediaPlayer.Event.EncounteredError) {
                if (currentUrl != null && loadAttempt == 0 && currentUrl.startsWith("http")) {
                    loadAttempt = 1; // one automatic retry with the fallback settings
                    startMedia(currentUrl, 0, true);
                } else if (currentUrl != null) {
                    showError();
                }
            } else if (t == MediaPlayer.Event.Playing) {
                hideLoading();
            } else if (t == MediaPlayer.Event.TimeChanged || t == MediaPlayer.Event.LengthChanged
                    || t == MediaPlayer.Event.Vout) {
                try {
                    if (mediaPlayer != null && (mediaPlayer.getTime() > 0 || mediaPlayer.getLength() > 0
                            || t == MediaPlayer.Event.Vout)) {
                        loaded = true;
                        handler.removeCallbacks(loadTimeout);
                        hideLoading();
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private final Runnable loadTimeout = new Runnable() {
        @Override
        public void run() {
            if (loaded || currentUrl == null || staleTorrentEvent()) return;
            if (loadAttempt == 0 && currentUrl.startsWith("http")) {
                loadAttempt = 1;
                startMedia(currentUrl, 0, true);
            } else {
                showError();
            }
        }
    };

    private void showError() {
        showError(null);
    }

    private void showError(String detail) {
        if (errorShown) return;
        errorShown = true;
        closePanel();
        handler.removeCallbacks(loadTimeout);
        try {
            if (mediaPlayer != null) mediaPlayer.stop();
        } catch (Exception ignored) {
        }
        hideControls();
        hideLoading();
        idleContainer.setVisibility(View.GONE);
        tvErrorUrl.setText(currentUrl == null ? "" : currentUrl);
        tvErrorDetail.setTextSize(14);
        tvErrorDetail.setGravity(android.view.Gravity.CENTER);
        if (detail != null && !detail.isEmpty()) {
            tvErrorDetail.setText(detail);
            tvErrorDetail.setVisibility(View.VISIBLE);
        } else {
            tvErrorDetail.setVisibility(View.GONE);
        }
        if (detail == null && !currentIsTorrent && currentUrl != null && currentUrl.startsWith("http")) {
            // find out why, in the background, and show it under the link
            final String failed = currentUrl;
            final String clean = failed.contains("|") ? failed.substring(0, failed.indexOf('|')) : failed;
            new Thread(() -> {
                String why = StreamProbe.run(clean, USER_AGENT, probeReferer(clean));
                runOnUiThread(() -> {
                    if (errorShown && failed.equals(currentUrl) && !why.isEmpty()) {
                        tvErrorDetail.setText(why);
                        tvErrorDetail.setVisibility(View.VISIBLE);
                    }
                });
            }, "probe").start();
        }
        errorOverlay.setVisibility(View.VISIBLE);
        btnErrorOk.requestFocus();
    }

    private static String probeReferer(String url) {
        try {
            Uri u = Uri.parse(url);
            return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + "/";
        } catch (Exception e) {
            return null;
        }
    }

    private void hideError() {
        errorShown = false;
        errorOverlay.setVisibility(View.GONE);
    }

    /** OK on the error screen: forget the failed link and go back to the idle screen. */
    private void dismissError() {
        endSession();
    }

    /** Stops playback, forgets the current link and returns to the ready screen. */
    private void endSession() {
        hideError();
        if (currentIsTorrent && torrents != null) torrents.stop(); // stop seeding, keep the files
        currentIsTorrent = false;
        torrentMagnet = null;
        ended = false;
        lastTorrent = null;
        handler.removeCallbacks(loadTimeout);
        try {
            if (mediaPlayer != null) mediaPlayer.stop();
        } catch (Exception ignored) {
        }
        currentUrl = null;
        currentTitle = "";
        subtitleFiles.clear();
        closePanel();
        synchronized (shareNames) {
            shareUrl = "";
            shareIndex = -1;
            shareNames.clear();
            shareSubs.clear();
        }
        hideLoading();
        hideControls();
        idleContainer.setVisibility(View.VISIBLE);
        maybeShowUpdate();
    }

    // ------------------------------------------------------------ updates

    /** Background check against the GitHub releases of the repo that built this app. */
    private void checkForUpdate() {
        if (checkingUpdate || pendingUpdate != null || !UpdateChecker.enabled(this) || !UpdateChecker.due(this)) return;
        checkingUpdate = true;
        new Thread(() -> {
            try {
                final UpdateChecker.Update u = UpdateChecker.check(this);
                if (u == null) {
                    UpdateChecker.markChecked(this);
                    return;
                }
                runOnUiThread(() -> {
                    pendingUpdate = u;
                    maybeShowUpdate();
                });
            } catch (Exception ignored) {
                // offline or GitHub unreachable (the TV may still be joining Wi-Fi): retry in a minute
                handler.postDelayed(this::checkForUpdate, 60_000);
            } finally {
                checkingUpdate = false;
            }
        }, "update-check").start();
    }

    /** A check asked for from a phone ("Check for updates"): ignores the daily limit and "never show again". */
    private void checkForUpdateNow() {
        if (BuildConfig.UPDATE_REPO.isEmpty()) {
            Toast.makeText(this, "Update checks aren't available in this build", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkingUpdate) return;
        checkingUpdate = true;
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final UpdateChecker.Update u = UpdateChecker.check(this);
                UpdateChecker.markChecked(this);
                runOnUiThread(() -> {
                    if (u == null) {
                        Toast.makeText(this, "Pong! is up to date (" + BuildConfig.VERSION_NAME + ")",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    pendingUpdate = u;
                    if (currentUrl != null && !isEnded()) {
                        Toast.makeText(this, "Pong! " + u.version + " is available. It will be offered when the video ends.",
                                Toast.LENGTH_LONG).show();
                    }
                    maybeShowUpdate();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't reach GitHub to check for updates",
                        Toast.LENGTH_LONG).show());
            } finally {
                checkingUpdate = false;
            }
        }, "update-check-now").start();
    }

    /** The prompt appears on the idle screen or after a video ended, never over a video that is playing. */
    private void maybeShowUpdate() {
        if (pendingUpdate == null || updateShown || confirmShown || errorShown) return;
        if (currentUrl != null && !isEnded()) return; // never over a video that is playing
        closePanel();
        hideControls();
        String size = UpdateLogic.humanSize(pendingUpdate.size);
        tvUpdateMsg.setText("Pong! " + pendingUpdate.version + " is available (you have " + BuildConfig.VERSION_NAME
                + ")." + (size.isEmpty() ? "" : "\nDownload size: " + size));
        updateBar.setVisibility(View.GONE);
        tvUpdateStatus.setVisibility(View.GONE);
        btnUpdateNow.setEnabled(true);
        btnUpdateLater.setEnabled(true);
        btnUpdateNever.setEnabled(true);
        updateShown = true;
        updateOverlay.setVisibility(View.VISIBLE);
        btnUpdateNow.requestFocus();
    }

    private void hideUpdate() {
        updateShown = false;
        updating = false;
        pendingUpdate = null;
        updateOverlay.setVisibility(View.GONE);
    }

    private void startUpdate() {
        final UpdateChecker.Update u = pendingUpdate;
        if (u == null || updating) return;
        updating = true;
        btnUpdateNow.setEnabled(false);
        btnUpdateLater.setEnabled(false);
        btnUpdateNever.setEnabled(false);
        updateBar.setVisibility(View.VISIBLE);
        updateBar.setIndeterminate(true);
        tvUpdateStatus.setTextColor(0xFFCDB8AE);
        tvUpdateStatus.setVisibility(View.VISIBLE);
        tvUpdateStatus.setText("Downloading…");
        new Thread(() -> {
            try {
                final java.io.File apk = UpdateChecker.download(this, u, (done, total) -> runOnUiThread(() -> {
                    if (total > 0) {
                        updateBar.setIndeterminate(false);
                        updateBar.setMax(100);
                        updateBar.setProgress((int) (done * 100 / total));
                        tvUpdateStatus.setText("Downloading… " + (done * 100 / total) + "%");
                    }
                }));
                runOnUiThread(() -> {
                    UpdateChecker.InstallResult r = UpdateChecker.install(this, apk);
                    if (r == UpdateChecker.InstallResult.STARTED) {
                        hideUpdate();
                    } else {
                        updateFailed(r == UpdateChecker.InstallResult.NEEDS_PERMISSION
                                ? "Allow Pong! to install apps (Settings > Apps > Special access > Install unknown apps), then press Update again."
                                : "Couldn't start the installer.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> updateFailed("Download failed: "
                        + (e.getMessage() == null ? "unknown error" : e.getMessage())));
            }
        }, "update-download").start();
    }

    private void updateFailed(String text) {
        updating = false;
        updateBar.setVisibility(View.GONE);
        tvUpdateStatus.setTextColor(0xFFFFB4AB);
        tvUpdateStatus.setText(text);
        tvUpdateStatus.setVisibility(View.VISIBLE);
        btnUpdateNow.setEnabled(true);
        btnUpdateLater.setEnabled(true);
        btnUpdateNever.setEnabled(true);
        btnUpdateNow.requestFocus();
    }

    private void showLoading() {
        tvLoadingTitle.setText(currentTitle);
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    private void showConfirm() {
        if (currentUrl == null || errorShown) return;
        confirmShown = true;
        closePanel();
        hideControls();
        confirmOverlay.setVisibility(View.VISIBLE);
        btnConfirmKeep.requestFocus();
    }

    private void dismissConfirm() {
        confirmShown = false;
        confirmOverlay.setVisibility(View.GONE);
    }

    private void updatePlayIcon(boolean playing) {
        btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        btnPlayPause.setContentDescription(playing ? "Pause" : "Play");
    }

    private void updateIdleText() {
        tvIdleChip.setText("Ready as " + serviceName);
    }

    private void renameDevice(String raw) {
        String n = raw == null ? "" : raw.trim().replace('\n', ' ');
        if (n.length() > 40) n = n.substring(0, 40);
        if (n.isEmpty() || n.equals(serviceName)) return;
        serviceName = n;
        prefs.edit().putString("device_name", n).apply();
        updateIdleText();
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {
            }
        }
        registerService();
    }

    private void playMedia(String url) {
        if (updateShown && !updating) hideUpdate();
        subtitleFiles.clear();
        loadAttempt = 0;
        hideError();
        String u = url == null ? "" : url.trim();
        if (u.toLowerCase(java.util.Locale.ROOT).startsWith("magnet:")) {
            if (torrents == null) {
                currentUrl = u;
                currentTitle = "Torrent";
                showError(torrentUnavailable);
                return;
            }
            // A new video load clears the old downloads, torrent or not.
            if (currentIsTorrent) torrents.stop();
            TorrentStreamer.clear(this);
            // Stop whatever was playing: otherwise it keeps playing behind the loading screen, and its
            // end/error events or its pending load timeout would be taken for the torrent's.
            handler.removeCallbacks(loadTimeout);
            try {
                if (mediaPlayer != null) mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            loaded = false;
            ended = false;
            currentIsTorrent = true;
            torrentMagnet = u;
            lastTorrent = null;
            currentUrl = u;
            String dn = TorrentFiles.magnetParam(u, "dn"); // Uri can't read magnet parameters
            currentTitle = dn == null || dn.trim().isEmpty() ? "Torrent" : dn.trim();
            tvLoadingStatus.setText("Loading torrent…");
            idleContainer.setVisibility(View.GONE);
            showLoading();
            hideControls();
            torrents.play(u);
            return;
        }
        if (currentIsTorrent) {
            torrents.stop();
            TorrentStreamer.clear(this);
            currentIsTorrent = false;
            torrentMagnet = null;
            lastTorrent = null;
        }
        currentUrl = u;
        currentTitle = titleFromUrl(u);
        startMedia(u, 0, true);
    }

    private void startMedia(String url, long startMs, boolean play) {
        if (mediaPlayer == null) return;
        try {
            // IPTV-style links may carry headers after a pipe: https://host/a.m3u8|Referer=https://x/&User-Agent=Y
            String playUrl = url;
            String referer = null, agent = null;
            int bar = url.indexOf('|');
            if (bar > 0) {
                playUrl = url.substring(0, bar).trim();
                for (String kv : url.substring(bar + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String k = kv.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
                    String v = Uri.decode(kv.substring(eq + 1).trim());
                    if (k.equals("referer") || k.equals("referrer")) referer = v;
                    else if (k.equals("user-agent")) agent = v;
                }
            }
            Uri uri = Uri.parse(playUrl);
            boolean web = playUrl.startsWith("http");
            if (web && referer == null && uri.getScheme() != null && uri.getHost() != null) {
                referer = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "/";
            }
            // HLS playlists go through a small local proxy first: it fixes disguised segment names/types
            // (e.g. "page-0.html") that LibVLC rejects but other players accept. The second try is direct.
            boolean proxied = false;
            if (web && loadAttempt == 0 && uri.getPath() != null
                    && uri.getPath().toLowerCase(java.util.Locale.ROOT).contains("m3u8")) {
                try {
                    if (hlsProxy == null) hlsProxy = new HlsProxy(agent != null ? agent : USER_AGENT);
                    uri = Uri.parse(hlsProxy.playlistUrl(playUrl, referer));
                    proxied = true;
                } catch (Exception ignored) {
                    // fall back to a direct connection
                }
            }
            Media media = new Media(libVLC, uri);
            if (web && !proxied) {
                if (agent != null) media.addOption(":http-user-agent=" + agent);
                if (referer != null) media.addOption(":http-referrer=" + referer);
                // fallback: read playlists through the plain HTTP access module instead of the adaptive one
                if (loadAttempt > 0) media.addOption(":adaptive-use-access");
            }
            if (startMs > 1000) media.addOption(":start-time=" + (startMs / 1000.0));
            hideError();
            ended = false;
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
            loaded = false;
            handler.removeCallbacks(loadTimeout);
            if (play) handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS);
            if (!play) {
                handler.postDelayed(() -> {
                    try {
                        mediaPlayer.pause();
                    } catch (Exception ignored) {
                    }
                }, 800);
            }
            idleContainer.setVisibility(View.GONE);
            if (play) showLoading();
            hideControls();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot play: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String titleFromUrl(String url) {
        try {
            String seg = Uri.parse(url.split("\\|")[0]).getLastPathSegment();
            if (seg != null && !seg.isEmpty()) return seg;
        } catch (Exception ignored) {
        }
        return url;
    }

    /** Starts the current video again from the beginning (after it played to the end). */
    private void replay() {
        if (mediaPlayer == null || currentUrl == null) return;
        if (currentIsTorrent && currentUrl.startsWith("magnet:")) return; // still being prepared
        final List<File> subs = new ArrayList<>(subtitleFiles);
        loadAttempt = 0;
        startMedia(currentUrl, 0, true);
        if (!subs.isEmpty()) {
            // a new Media loses its subtitle slaves
            handler.postDelayed(() -> {
                for (File f : subs) addSubtitleFile(f, false, true);
            }, 1000);
        }
        updatePlayIcon(true);
    }

    private boolean isEnded() {
        if (ended) return true;
        try {
            return mediaPlayer != null && mediaPlayer.getPlayerState() == Media.State.Ended;
        } catch (Exception e) {
            return false;
        }
    }

    /** Play (replaying a video that ended) or pause. */
    private void play() {
        if (mediaPlayer == null) return;
        if (isEnded()) replay();
        else if (!mediaPlayer.isPlaying()) mediaPlayer.play();
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (isEnded()) {
            replay();
            return;
        }
        boolean wasPlaying = mediaPlayer.isPlaying();
        if (wasPlaying) mediaPlayer.pause();
        else mediaPlayer.play();
        updatePlayIcon(!wasPlaying);
    }

    private long seekTarget, lastSeekAt;
    private long feedbackTotal, lastFeedbackAt, lastKeySeekAt;

    /** Seeks and flashes "+10s" / "-10s" on the matching side; repeated seeks add up. */
    private void seekWithFeedback(long deltaMs) {
        seekBy(deltaMs);
        long now = SystemClock.uptimeMillis();
        boolean sameDir = feedbackTotal != 0 && (feedbackTotal > 0) == (deltaMs > 0);
        if (now - lastFeedbackAt > 900 || !sameDir) feedbackTotal = 0;
        feedbackTotal += deltaMs;
        lastFeedbackAt = now;
        long secs = Math.abs(feedbackTotal) / 1000;
        String label = (feedbackTotal < 0 ? "\u2212" : "+") + secs + "s";
        TextView show = feedbackTotal < 0 ? seekLeft : seekRight;
        TextView other = feedbackTotal < 0 ? seekRight : seekLeft;
        other.setVisibility(View.GONE);
        (feedbackTotal < 0 ? seekGlowRight : seekGlowLeft).setVisibility(View.GONE);
        (feedbackTotal < 0 ? seekGlowLeft : seekGlowRight).setVisibility(View.VISIBLE);
        show.setText(label);
        show.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideFeedback);
        handler.postDelayed(hideFeedback, 800);
    }

    private final Runnable hideFeedback = new Runnable() {
        @Override
        public void run() {
            seekLeft.setVisibility(View.GONE);
            seekRight.setVisibility(View.GONE);
            seekGlowLeft.setVisibility(View.GONE);
            seekGlowRight.setVisibility(View.GONE);
            feedbackTotal = 0;
        }
    };


    private void seekBy(long deltaMs) {
        if (mediaPlayer == null) return;
        long len = mediaPlayer.getLength();
        long now = SystemClock.uptimeMillis();
        // The player's clock lags behind a seek, so chained seeks build on the last target.
        long base = now - lastSeekAt < 1000 ? seekTarget : mediaPlayer.getTime();
        long t = Math.max(0, base + deltaMs);
        if (len > 0) t = Math.min(t, len - 500 > 0 ? len - 500 : len);
        seekTarget = t;
        lastSeekAt = now;
        seekTo(t, len);
        if (len > 0) seekBar.setProgress((int) (t * 1000 / len));
        setTimes(t, len);
    }

    private void setTimes(long t, long len) {
        tvTimeCur.setText(formatTime(t));
        tvTimeTotal.setText(len > 0 ? "\u2212" + formatTime(Math.max(0, len - t)) : "--:--");
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

    private void addSubtitleFile(File f, boolean remember, boolean select) {
        if (mediaPlayer == null) return;
        try {
            if (remember) subtitleFiles.add(f);
            mediaPlayer.addSlave(Media.Slave.Type.Subtitle, Uri.fromFile(f), select);
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
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 16 * 1024 * 1024) throw new IOException("Subtitle file too large");
                }
                in.close();
                byte[] data = bos.toByteArray();

                // ASS / SSA / WebVTT / TTML -> SRT. Null means "use the original file unchanged".
                byte[] srt = SubtitleConverter.toSrt(data);
                if (srt != null) {
                    data = srt;
                    ext = ".srt";
                }
                String hint = Uri.parse(urlStr).getQueryParameter("name");
                if (hint == null || hint.isEmpty()) {
                    String p = Uri.parse(urlStr).getPath();
                    hint = p == null ? "subtitle" + ext : p.substring(p.lastIndexOf('/') + 1);
                }
                if (!hint.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) {
                    int dot = hint.lastIndexOf('.');
                    hint = (dot > 0 ? hint.substring(0, dot) : hint) + ext;
                }
                final File f = saveSubtitle(data, hint);
                subtitleSource.put(f.getAbsolutePath(), "phone");
                runOnUiThread(() -> {
                    addSubtitleFile(f, true, true);
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
                    setTimes(t, len);
                    tvTitle.setText(currentTitle);
                    updatePlayIcon(mediaPlayer.isPlaying());
                    updateInfo(t, len);
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    /** Status line (torrent progress), "Ends at", the speed button, the downloaded part of the seek bar. */
    private void updateInfo(long t, long len) {
        float rate = 1f;
        boolean playing = false;
        try {
            rate = mediaPlayer.getRate();
            playing = mediaPlayer.isPlaying();
        } catch (Exception ignored) {
        }
        String status = "";
        TorrentStreamer.Snapshot ts = lastTorrent;
        if (currentIsTorrent && ts != null) {
            String peers = ts.peers == 1 ? "1 peer" : ts.peers + " peers";
            if ("streaming".equals(ts.state)) {
                status = "Torrent · " + (ts.progress >= 100 ? "downloaded" : ts.progress + "% downloaded")
                        + " · " + peers;
            } else {
                status = "Torrent · " + torrentMessage(ts, loaded);
            }
            seekBar.setSecondaryProgress(ts.fileProgress);
        } else {
            seekBar.setSecondaryProgress(0);
            if (!playing && !isEnded() && loaded) status = "Paused";
        }
        String ends = "";
        if (len > 0 && rate > 0 && !isEnded()) {
            long end = System.currentTimeMillis() + (long) (Math.max(0, len - t) / rate);
            ends = "Ends at " + DateFormat.getTimeFormat(this).format(new Date(end));
        }
        tvStatusLine.setText(status);
        tvStatusLine.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        tvEndsAt.setText(ends);
        tvEndsAt.setVisibility(ends.isEmpty() ? View.GONE : View.VISIBLE);
        statusDot.setVisibility(status.isEmpty() || ends.isEmpty() ? View.GONE : View.VISIBLE);

        String label = speedFormat.format(rate) + "x";
        if (!label.contentEquals(btnSpeed.getText())) {
            btnSpeed.setText(label);
            btnSpeed.setContentDescription("Playback speed " + label + ", press to change");
        }
        boolean many = playlistRows().size() > 1;
        int vis = many ? View.VISIBLE : View.GONE;
        if (btnPlaylist.getVisibility() != vis) {
            if (!many && btnPlaylist.hasFocus()) btnAudio.requestFocus();
            btnPlaylist.setVisibility(vis);
            // the gap before Audio belongs to Playlist only when it's shown
            ((LinearLayout.LayoutParams) btnAudio.getLayoutParams()).setMarginStart(many ? dp(4) : 0);
            btnAudio.requestLayout();
        }
    }

    /** 1x → 1.25x → 1.5x → 1.75x → 2x → 1x. Any other speed (set from the phone) goes back to 1x. */
    private void cycleSpeed() {
        if (mediaPlayer == null) return;
        float r = mediaPlayer.getRate();
        float next = 1f;
        for (int i = 0; i < SPEED_STEPS.length - 1; i++) {
            if (Math.abs(r - SPEED_STEPS[i]) < 0.01f) next = SPEED_STEPS[i + 1];
        }
        mediaPlayer.setRate(next);
        String label = speedFormat.format(next) + "x";
        btnSpeed.setText(label);
        btnSpeed.setContentDescription("Playback speed " + label + ", press to change");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------ side panels

    /** One row of a side panel. */
    private static final class PanelRow {
        final String name, detail;
        final boolean current;
        final int number; // shown when not current (playlist); 0 = none
        final Runnable action;

        PanelRow(String name, String detail, boolean current, int number, Runnable action) {
            this.name = name;
            this.detail = detail;
            this.current = current;
            this.number = number;
            this.action = action;
        }
    }

    /** The torrent's videos, or the videos shared from a phone, or nothing (a single video). */
    private List<PanelRow> playlistRows() {
        List<PanelRow> rows = new ArrayList<>();
        TorrentStreamer.Snapshot ts = lastTorrent;
        if (currentIsTorrent && ts != null && ts.files != null) {
            int n = 0;
            for (final TvStatus.TorrentFile f : ts.files) {
                n++;
                boolean cur = f.index == ts.selected;
                String size = f.size > 0 ? UpdateLogic.humanSize(f.size) : "";
                String detail = cur ? (size.isEmpty() ? "Playing" : "Playing · " + size) : size;
                rows.add(new PanelRow(f.name, detail, cur, n, () -> {
                    if (torrents != null) torrents.selectFile(f.index);
                    closePanel();
                    hideControls();
                }));
            }
            return rows;
        }
        String url = currentUrl;
        synchronized (shareNames) {
            if (url == null || !url.equals(shareUrl) || shareNames.size() < 2) return rows;
            for (int i = 0; i < shareNames.size(); i++) {
                final int idx = i;
                final String name = shareNames.get(i);
                boolean cur = i == shareIndex;
                int subs = shareSubs.get(i);
                String subText = subs > 0 ? subs + (subs == 1 ? " subtitle" : " subtitles") : "";
                String detail = cur ? (subText.isEmpty() ? "Playing" : "Playing · " + subText) : subText;
                rows.add(new PanelRow(name, detail, cur, i + 1, () -> {
                    // the phone serves these files: ask it to switch (it reads this from the status)
                    shareSelect = idx;
                    shareSelectSeq++;
                    Toast.makeText(this, "Switching to " + name + "…", Toast.LENGTH_SHORT).show();
                    closePanel();
                    hideControls();
                }));
            }
        }
        return rows;
    }

    /** "Off" plus every subtitle track, with where it came from. */
    private List<PanelRow> subtitleRows() {
        List<PanelRow> rows = new ArrayList<>();
        final MediaPlayer mp = mediaPlayer;
        if (mp == null) return rows;
        MediaPlayer.TrackDescription[] tracks;
        int current;
        try {
            tracks = mp.getSpuTracks();
            current = mp.getSpuTrack();
        } catch (Exception e) {
            return rows;
        }
        rows.add(new PanelRow("Off", "", current == -1, 0, () -> selectSubtitle(-1)));
        if (tracks == null) return rows;
        String[] sources = subtitleSources(tracks, new ArrayList<>(subtitleFiles));
        File[] files = slaveFiles(tracks, new ArrayList<>(subtitleFiles));
        for (int i = 0; i < tracks.length; i++) {
            final int id = tracks[i].id;
            if (id == -1) continue;
            String name = tracks[i].name == null ? "" : tracks[i].name.trim();
            File f = files[i];
            if (f != null && genericTrackName(name)) name = baseName(f.getName());
            if (name.isEmpty()) name = "Track " + id;
            String label = sourceLabel(sources[i]);
            String detail = f != null ? f.getName() + (label.isEmpty() ? "" : " · " + label) : label;
            rows.add(new PanelRow(name, detail, id == current, 0, () -> selectSubtitle(id)));
        }
        return rows;
    }

    /** Every audio track (VLC's "Disable" entry left out), the playing one checked. */
    private List<PanelRow> audioRows() {
        List<PanelRow> rows = new ArrayList<>();
        final MediaPlayer mp = mediaPlayer;
        if (mp == null) return rows;
        MediaPlayer.TrackDescription[] tracks;
        int current;
        try {
            tracks = mp.getAudioTracks();
            current = mp.getAudioTrack();
        } catch (Exception e) {
            return rows;
        }
        if (tracks == null) return rows;
        for (MediaPlayer.TrackDescription t : tracks) {
            final int id = t.id;
            if (id == -1) continue;
            String name = t.name == null || t.name.trim().isEmpty() ? "Track " + id : t.name.trim();
            rows.add(new PanelRow(name, "", id == current, 0, () -> selectTrack(true, id)));
        }
        return rows;
    }

    private void selectSubtitle(int id) {
        selectTrack(false, id);
    }

    private void selectTrack(boolean audio, int id) {
        try {
            if (mediaPlayer != null) {
                if (audio) mediaPlayer.setAudioTrack(id);
                else mediaPlayer.setSpuTrack(id);
            }
        } catch (Exception ignored) {
        }
        // move the check, keep the focus on the row that was picked
        int focused = panelList.indexOfChild(panelList.getFocusedChild());
        fillPanel();
        View v = panelList.getChildAt(Math.max(0, focused));
        if (v != null) v.requestFocus();
    }

    private static boolean genericTrackName(String n) {
        return n.isEmpty() || n.matches("(?i)(track|subtitle)s?\\s*\\d*|undetermined|unknown|und");
    }

    private static String baseName(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static String sourceLabel(String source) {
        if ("torrent".equals(source)) return "from the torrent";
        if ("phone".equals(source)) return "Sent from Ping!";
        if ("video".equals(source)) return "In the video file";
        return "";
    }

    /**
     * The files added as subtitle slaves show up as the last tracks, in the order they were added; everything
     * before them is inside the video. Returns, per track, the slave file (or null).
     */
    private static File[] slaveFiles(MediaPlayer.TrackDescription[] tracks, List<File> slaves) {
        File[] out = new File[tracks.length];
        List<Integer> real = new ArrayList<>();
        for (int i = 0; i < tracks.length; i++) if (tracks[i].id != -1) real.add(i);
        int s = Math.min(slaves.size(), real.size());
        for (int j = 0; j < s; j++) {
            out[real.get(real.size() - s + j)] = slaves.get(slaves.size() - s + j);
        }
        return out;
    }

    private String[] subtitleSources(MediaPlayer.TrackDescription[] tracks, List<File> slaves) {
        File[] files = slaveFiles(tracks, slaves);
        String[] out = new String[tracks.length];
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i].id == -1) out[i] = "";
            else if (files[i] == null) out[i] = "video";
            else {
                String src = subtitleSource.get(files[i].getAbsolutePath());
                out[i] = src == null ? "phone" : src;
            }
        }
        return out;
    }

    private void openPanel(int kind) {
        if (currentUrl == null) return;
        panelOpener = getCurrentFocus();
        panelKind = kind;
        handler.removeCallbacks(hideControlsRunnable); // the menu stays while the panel is open
        // only the panel takes focus while it's open
        controlsGroup.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        fillPanel();
        panelOverlay.setVisibility(View.VISIBLE);
        panelScroll.scrollTo(0, 0);
        View first = null;
        for (int i = 0; i < panelList.getChildCount(); i++) {
            View v = panelList.getChildAt(i);
            if (first == null) first = v;
            if (Boolean.TRUE.equals(v.getTag())) {
                first = v;
                break;
            }
        }
        if (first != null) first.requestFocus();
    }

    private void closePanel() {
        if (panelKind == PANEL_NONE) return;
        panelKind = PANEL_NONE;
        panelOverlay.setVisibility(View.GONE);
        panelList.removeAllViews();
        controlsGroup.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (controlsVisible) {
            View back = panelOpener != null && panelOpener.getVisibility() == View.VISIBLE ? panelOpener : btnPlayPause;
            back.requestFocus();
            handler.removeCallbacks(hideControlsRunnable);
            handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS);
        }
        panelOpener = null;
    }

    private void fillPanel() {
        panelList.removeAllViews();
        List<PanelRow> rows;
        if (panelKind == PANEL_PLAYLIST) {
            panelTitle.setText("Playlist");
            panelSubtitle.setText(currentIsTorrent ? "Videos in this torrent" : "Videos in this share");
            rows = playlistRows();
        } else if (panelKind == PANEL_AUDIO) {
            panelTitle.setText("Audio");
            panelSubtitle.setText("Choose an audio track");
            rows = audioRows();
            if (rows.isEmpty()) panelSubtitle.setText("This video has no audio tracks to choose from");
        } else {
            panelTitle.setText("Subtitles");
            panelSubtitle.setText("Choose a subtitle track");
            rows = subtitleRows();
        }
        for (PanelRow r : rows) panelList.addView(panelRow(r));
    }

    private View panelRow(final PanelRow r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(14), dp(6), dp(18), dp(6));
        row.setBackgroundResource(R.drawable.row_bg);
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(r.current);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);

        android.content.res.ColorStateList text = androidx.core.content.ContextCompat.getColorStateList(this, R.color.row_text);
        android.content.res.ColorStateList detail = androidx.core.content.ContextCompat.getColorStateList(this, R.color.row_detail);
        // the leading slot: play/check icon on the current row, else the number (or blank)
        View lead;
        if (r.current) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(panelKind == PANEL_PLAYLIST ? R.drawable.ic_play : R.drawable.ic_check);
            iv.setImageTintList(text);
            iv.setContentDescription(panelKind == PANEL_PLAYLIST ? "Now playing" : "Selected");
            lead = iv;
        } else {
            TextView num = new TextView(this);
            num.setText(r.number > 0 ? String.valueOf(r.number) : "");
            num.setGravity(android.view.Gravity.CENTER);
            num.setTextSize(15);
            num.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            num.setTextColor(detail);
            lead = num;
        }
        lead.setDuplicateParentStateEnabled(true);
        row.addView(lead, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(12));
        row.addView(texts, tlp);

        TextView name = new TextView(this);
        name.setText(r.name);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextSize(17);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setTextColor(text);
        name.setDuplicateParentStateEnabled(true);
        texts.addView(name);
        if (!r.detail.isEmpty()) {
            TextView d = new TextView(this);
            d.setText(r.detail);
            d.setSingleLine(true);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            d.setTextSize(13);
            d.setTextColor(detail);
            d.setDuplicateParentStateEnabled(true);
            texts.addView(d);
        }
        row.setContentDescription(r.name + (r.detail.isEmpty() ? "" : ", " + r.detail));
        row.setOnClickListener(v -> {
            if (r.current && panelKind == PANEL_PLAYLIST) {
                closePanel(); // already playing
                return;
            }
            r.action.run();
        });
        return row;
    }

    private static String formatTime(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    private void showControls() {
        showControls(false);
    }

    /** @param focusPlay put focus on the play/pause button (used right after play/pause with the menu hidden) */
    private void showControls(boolean focusPlay) {
        if (currentUrl == null) return;
        if (confirmShown || errorShown || updateShown) return;
        controlsOverlay.setVisibility(View.VISIBLE);
        controlsVisible = true;
        tvTitle.setText(currentTitle);
        try {
            if (mediaPlayer != null) {
                long len = mediaPlayer.getLength(), t = mediaPlayer.getTime();
                if (len > 0) seekBar.setProgress((int) (t * 1000 / len));
                setTimes(t, len);
                updateInfo(t, len);
            }
        } catch (Exception ignored) {
        }
        if (panelKind != PANEL_NONE) return;
        if (mediaPlayer != null) updatePlayIcon(mediaPlayer.isPlaying());
        View f = getCurrentFocus();
        if (focusPlay) btnPlayPause.requestFocus();
        else if (f == null || f.getParent() == null) seekBar.requestFocus();
        handler.removeCallbacks(hideControlsRunnable);
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS);
    }

    private void hideControls() {
        if (panelKind != PANEL_NONE) closePanel();
        controlsOverlay.setVisibility(View.GONE);
        controlsVisible = false;
        handler.removeCallbacks(hideControlsRunnable);
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
    }

    private final Runnable hideControlsRunnable = this::hideControls;

    /**
     * Menu hidden: left/right seek 10s with on-screen feedback and never open the menu.
     * Seek bar focused: left/right seek 30s.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        if (panelKind != PANEL_NONE && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            return true; // the panel is a single column: left/right do nothing (and never seek)
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && !errorShown && !confirmShown && !updateShown && currentUrl != null
                && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int dir = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            long now = SystemClock.uptimeMillis();
            if (!controlsVisible) {
                // holding the key repeats, but not faster than every 300ms
                if (event.getRepeatCount() == 0 || now - lastKeySeekAt >= 300) {
                    lastKeySeekAt = now;
                    seekWithFeedback(dir * SEEK_STEP_MS);
                }
                return true;
            }
            if (getCurrentFocus() == seekBar) {
                if (event.getRepeatCount() == 0 || now - lastKeySeekAt >= 200) {
                    lastKeySeekAt = now;
                    seekBy(dir * SEEK_BAR_STEP_MS);
                    showControls();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        View focus = getCurrentFocus();
        boolean onButton = controlsVisible && focus != null && (focus == btnPlayPause
                || focus == btnBackNav || focus == btnBack10 || focus == btnFwd10 || focus == seekBar
                || focus == btnSpeed || focus == btnPlaylist || focus == btnAudio || focus == btnSubtitles);

        if (panelKind != PANEL_NONE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closePanel();
                return true;
            }
            return super.onKeyDown(keyCode, event); // up/down move through the list, OK picks
        }

        if (updateShown) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (!updating) hideUpdate();
                return true;
            }
            return super.onKeyDown(keyCode, event); // focus moves between the three buttons
        }

        if (confirmShown) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismissConfirm();
                return true;
            }
            return super.onKeyDown(keyCode, event); // focus moves between the two buttons
        }

        if (errorShown) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismissError();
                return true;
            }
            return super.onKeyDown(keyCode, event); // lets the OK button take focus / clicks
        }

        // keys that work the same everywhere
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                showControls(!controlsVisible);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                play();
                updatePlayIcon(true);
                showControls(!controlsVisible);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.pause();
                updatePlayIcon(false);
                showControls(!controlsVisible);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD:
                seekWithFeedback(-SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD:
                seekWithFeedback(SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
                toggleSubtitles();
                showControls();
                return true;
            case KeyEvent.KEYCODE_MENU:
                showControls();
                openPanel(PANEL_SUBS);
                return true;
            default:
                break;
        }

        if (onButton) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideControls();
                return true;
            }
            if (focus == seekBar && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                togglePlayPause();
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
                showControls(!controlsVisible);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekWithFeedback(-SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekWithFeedback(SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showControls();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (controlsVisible) {
                    hideControls();
                    return true;
                }
                if (currentUrl != null) {
                    showConfirm();
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
                runOnUiThread(() -> updateIdleText());
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
            case "TORRENT_FILE":
                if (currentIsTorrent && torrents != null) {
                    try {
                        torrents.selectFile(Integer.parseInt(arg.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                break;
            case "PAUSE":
                if (mp != null && mp.isPlaying()) mp.pause();
                break;
            case "RESUME":
                if (errorShown && currentUrl != null) {
                    loadAttempt = 0;
                    if (currentIsTorrent && currentUrl.startsWith("magnet:")) playMedia(currentUrl);
                    else startMedia(currentUrl, 0, true); // retry the link that failed
                } else {
                    play(); // replays a video that played to the end
                }
                break;
            case "RENAME":
                renameDevice(arg);
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
            case "SHARE_LIST":
                setShareList(arg);
                break;
            case "CHECK_UPDATE":
                checkForUpdateNow();
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

    /** {url, index, items[{name, subs}]}: the videos of a phone's share, for the Playlist panel. */
    private void setShareList(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String url = o.get("url").getAsString().trim();
            int index = o.has("index") ? o.get("index").getAsInt() : -1;
            List<String> names = new ArrayList<>();
            List<Integer> subs = new ArrayList<>();
            for (com.google.gson.JsonElement e : o.getAsJsonArray("items")) {
                JsonObject it = e.getAsJsonObject();
                names.add(it.has("name") ? it.get("name").getAsString() : "Video");
                subs.add(it.has("subs") ? it.get("subs").getAsInt() : 0);
            }
            synchronized (shareNames) {
                shareUrl = url;
                shareIndex = index;
                shareNames.clear();
                shareNames.addAll(names);
                shareSubs.clear();
                shareSubs.addAll(subs);
            }
            if (panelKind == PANEL_PLAYLIST) fillPanel();
        } catch (Exception ignored) {
        }
    }

    private TvStatus buildStatus() {
        TvStatus s = new TvStatus();
        s.name = serviceName;
        s.title = currentTitle;
        s.subSize = subSize;
        s.subColor = subColor;
        s.subOutline = subOutline;
        s.subBg = subBg;
        s.subBold = subBold;
        s.hdr = hdr;
        s.shareUrl = shareUrl;
        s.shareSelect = shareSelect;
        s.shareSelectSeq = shareSelectSeq;
        fillTorrentStatus(s);
        if (errorShown) {
            s.state = "error";
            return s;
        }
        MediaPlayer mp = mediaPlayer;
        if (mp == null || currentUrl == null) return s;
        if (currentIsTorrent && currentUrl.startsWith("magnet:")) {
            // The torrent engine is still preparing; the player holds nothing (or the previous video).
            s.state = "opening";
            return s;
        }
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
            MediaPlayer.TrackDescription[] spu = mp.getSpuTracks();
            fillTracks(s.spuTracks, spu);
            if (spu != null) {
                String[] src = subtitleSources(spu, new ArrayList<>(subtitleFiles));
                for (int i = 0; i < s.spuTracks.size() && i < src.length; i++) s.spuTracks.get(i).source = src[i];
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private void fillTorrentStatus(TvStatus s) {
        if (!currentIsTorrent) return;
        TorrentStreamer.Snapshot ts = lastTorrent;
        if (ts == null) {
            // The engine hasn't reported yet (it is still shutting down the previous torrent): say it's loading.
            s.torrentState = "metadata";
            s.torrentTitle = currentTitle;
            s.torrentMessage = "Loading torrent…";
            return;
        }
        s.torrentMessage = torrentMessage(ts, loaded);
        s.torrentState = ts.state == null ? "" : ts.state;
        s.torrentTitle = ts.title == null ? "" : ts.title;
        s.torrentSelected = ts.selected;
        s.torrentPeers = ts.peers;
        s.torrentRate = ts.rate;
        s.torrentProgress = ts.progress;
        s.torrentFileProgress = ts.fileProgress;
        s.torrentError = ts.error == null ? "" : ts.error;
        if (ts.files != null) s.torrentFiles.addAll(ts.files);
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
        if (torrents != null) {
            torrents.setListener(null);
            torrents.stop();
        }
        if (hlsProxy != null) hlsProxy.stop();
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
