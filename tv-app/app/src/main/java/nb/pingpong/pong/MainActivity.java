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
import android.widget.Button;
import android.widget.ImageButton;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private TextView tvTimeCur, tvTimeTotal, tvIdleChip, tvTitle, tvLoadingTitle;
    private View idleContainer, loadingOverlay, confirmOverlay, topBar, bottomBar, seekGlowLeft, seekGlowRight;
    private ImageButton btnPlayPause, btnBackNav, btnBack10, btnFwd10;
    private Button btnErrorOk, btnConfirmKeep, btnConfirmStop;
    private volatile boolean confirmShown = false;
    private View errorOverlay;
    private TextView tvErrorUrl, tvErrorDetail;
    private volatile boolean errorShown = false;
    private boolean loaded = false;
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
        tvTimeCur = findViewById(R.id.tv_time_cur);
        tvTimeTotal = findViewById(R.id.tv_time_total);
        idleContainer = findViewById(R.id.idle_container);
        tvIdleChip = findViewById(R.id.tv_idle_chip);
        loadingOverlay = findViewById(R.id.loading_overlay);
        tvLoadingTitle = findViewById(R.id.tv_loading_title);
        confirmOverlay = findViewById(R.id.confirm_overlay);
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

        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
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
        btnConfirmKeep.setOnClickListener(v -> dismissConfirm());
        btnConfirmStop.setOnClickListener(v -> {
            dismissConfirm();
            endSession();
        });

        handler.postDelayed(updateUiRunnable, 500);
        registerService();
        startUdpServer();
    }

    // ----------------------------------------------- touch (phones / tablets used as TV boxes)

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** True when a touch belongs to a dialog or to the menu's top/bottom bars rather than the video. */
    private boolean insideOverlay(MotionEvent e) {
        if (confirmShown || errorShown) return true;
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
                for (File f : subs) addSubtitleFile(f, false);
            }, 1000);
        }
    }

    // ---------------------------------------------------- load errors

    private void onPlayerEvent(final MediaPlayer.Event event) {
        runOnUiThread(() -> {
            int t = event.type;
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
            if (loaded || currentUrl == null) return;
            if (loadAttempt == 0 && currentUrl.startsWith("http")) {
                loadAttempt = 1;
                startMedia(currentUrl, 0, true);
            } else {
                showError();
            }
        }
    };

    private void showError() {
        if (errorShown) return;
        errorShown = true;
        handler.removeCallbacks(loadTimeout);
        try {
            if (mediaPlayer != null) mediaPlayer.stop();
        } catch (Exception ignored) {
        }
        hideControls();
        hideLoading();
        idleContainer.setVisibility(View.GONE);
        tvErrorUrl.setText(currentUrl == null ? "" : currentUrl);
        tvErrorDetail.setVisibility(View.GONE);
        if (currentUrl != null && currentUrl.startsWith("http")) {
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
        handler.removeCallbacks(loadTimeout);
        try {
            if (mediaPlayer != null) mediaPlayer.stop();
        } catch (Exception ignored) {
        }
        currentUrl = null;
        currentTitle = "";
        subtitleFiles.clear();
        hideLoading();
        hideControls();
        idleContainer.setVisibility(View.VISIBLE);
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
        subtitleFiles.clear();
        currentUrl = url;
        currentTitle = titleFromUrl(url);
        loadAttempt = 0;
        startMedia(url, 0, true);
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

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
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
        tvTimeTotal.setText(len > 0 ? formatTime(len) : "--:--");
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
                final File f = new File(getCacheDir(), "sub_" + System.currentTimeMillis() + ext);
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
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
                    setTimes(t, len);
                    tvTitle.setText(currentTitle);
                    updatePlayIcon(mediaPlayer.isPlaying());
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
        showControls(false);
    }

    /** @param focusPlay put focus on the play/pause button (used right after play/pause with the menu hidden) */
    private void showControls(boolean focusPlay) {
        if (currentUrl == null) return;
        if (confirmShown || errorShown) return;
        controlsOverlay.setVisibility(View.VISIBLE);
        controlsVisible = true;
        tvTitle.setText(currentTitle);
        if (mediaPlayer != null) updatePlayIcon(mediaPlayer.isPlaying());
        View f = getCurrentFocus();
        if (focusPlay) btnPlayPause.requestFocus();
        else if (f == null || f.getParent() == null) seekBar.requestFocus();
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

    /**
     * Menu hidden: left/right seek 10s with on-screen feedback and never open the menu.
     * Seek bar focused: left/right seek 30s.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && !errorShown && !confirmShown && currentUrl != null
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
                || focus == btnBackNav || focus == btnBack10 || focus == btnFwd10 || focus == seekBar);

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
                if (mediaPlayer != null && !mediaPlayer.isPlaying()) mediaPlayer.play();
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
            case "PAUSE":
                if (mp != null && mp.isPlaying()) mp.pause();
                break;
            case "RESUME":
                if (errorShown && currentUrl != null) {
                    loadAttempt = 0;
                    startMedia(currentUrl, 0, true); // retry the link that failed
                } else if (mp != null && !mp.isPlaying()) {
                    mp.play();
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
        s.name = serviceName;
        s.title = currentTitle;
        s.subSize = subSize;
        s.subColor = subColor;
        s.subOutline = subOutline;
        s.subBg = subBg;
        s.subBold = subBold;
        s.hdr = hdr;
        if (errorShown) {
            s.state = "error";
            return s;
        }
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
