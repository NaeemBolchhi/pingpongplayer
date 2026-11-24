package nb.pingpong.ping;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.gson.JsonObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remote control for one TV app. Four tabs in a navigation bar (Controls, Playback, Subtitles, Playlist);
 * every grey section heading collapses its section, and the collapsed state is remembered.
 */
public class ControlActivity extends AppCompatActivity implements TvClient.Listener {
    private static final float[] PRESETS = {
            0.25f, 0.5f, 0.75f, 1f,
            1.25f, 1.5f, 1.75f, 2f,
            2.25f, 2.5f, 2.75f, 3f,
            3.25f, 3.5f, 3.75f, 4f};
    private static final float SPEED_STEP = 0.05f, SPEED_MIN = 0.05f, SPEED_MAX = 16f;
    private static final int AUDIO_STEP_MS = 250, SUB_STEP_MS = 500, VOLUME_STEP = 5;
    private static final String[] COLOR_NAMES = {"White", "Yellow", "Cyan", "Green"};
    private static final int[] COLORS = {0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00};
    private static final int[] OUTLINE_IDS = {R.id.ol_none, R.id.ol_thin, R.id.ol_normal, R.id.ol_thick};
    private static final int[] OUTLINES = {0, 2, 4, 6};
    /** After the user touches a control, ignore TV status for it this long so it doesn't jump back. */
    private static final long HOLD_MS = 2000;
    private static final float DISABLED_ALPHA = 0.38f;

    private static final String[] TAB_NAMES = {"Controls", "Playback", "Subtitles", "Playlist"};
    private static final int[] TAB_ICONS = {R.drawable.ic_tab_controls, R.drawable.ic_tab_playback,
            R.drawable.ic_tab_subtitles, R.drawable.ic_tab_playlist};
    private static final int TAB_PLAYLIST = 3;
    /** Where the Playlist tab's rows come from. */
    private static final int MODE_SINGLE = 0, MODE_LOCAL = 1, MODE_TORRENT = 2;

    private final DecimalFormat shortNum = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private DeviceInfo device;
    private TvClient client;
    private UsageStore usage;
    private SharedPreferences prefs;
    private TvStatus last = new TvStatus();
    private boolean connected = false;
    private boolean seeking = false;
    private boolean styleSynced = false;
    private boolean applying = false; // programmatic changes must not trigger sends
    private String styleSig = "";
    private long holdStyle;
    private boolean styleDirty = false;
    private long holdSpeed, holdVol, holdAudio, holdSub, holdTracks, holdDr;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // appearance in force when this screen was built; a change in Settings rebuilds it
    private boolean generatedColors;
    private String appearanceSig = "";

    // local values behind the steppers
    private float speed = 1f;
    private long audioMs = 0, subMs = 0;
    private int volumeValue = 100;
    private boolean hdr = false;
    private int colorIdx = 0;
    private String tvName = "";

    private View root, banner;
    private MaterialToolbar toolbar;
    private NestedScrollView scroll;
    private NavBar nav;
    private TextView tvTitle, tvState, tvDesc, tvPos, tvLen, tvVolume, tvSpeed, tvAudioSync, tvSubSync, tvSize, tvBg,
            preview;
    private Slider seek, volume, slSize, slBg;
    private MaterialButton btnBack10, btnPlay, btnFwd10, btnPasteVideo, btnSlower, btnFaster, btnSdr, btnHdr;
    private MaterialButton btnVolLess, btnVolMore;
    private MaterialButton btnAudioLess, btnAudioMore, btnAudioReset, btnSubLess, btnSubMore, btnSubReset;
    private MaterialButton btnPasteSub;
    private View btnSearch;
    private MaterialAutoCompleteTextView ddAudio, ddSubtitle;
    private View tilAudio, tilSubtitle;
    private MaterialSwitch swBold;
    private MaterialButtonToggleGroup outlineGroup;
    private EditText etQuery, etLang;
    private LinearLayout results, swatches;
    private View[] panes;
    private View subFind, subStyle, subSync;
    private LinearProgressIndicator torrentProgress, plProgress;
    private MaterialCardView torrentCard;
    private TextView tvPlTitle, tvPlState;
    private LinearLayout playlistRows;
    private TextView tvPlaylistHeader;
    private String playlistSig = "";
    private final List<View> swatchViews = new ArrayList<>();
    private final List<MaterialButton> gridButtons = new ArrayList<>();
    private final List<View> controls = new ArrayList<>();

    private final List<Integer> audioIds = new ArrayList<>();
    private final List<Integer> spuIds = new ArrayList<>();
    private String audioSig = "", spuSig = "";

    // torrent playlist (the TV owns the files, this screen only picks one)
    private String torrentTitleSeen = "";
    private boolean torrentPromptShown = false;

    private static final class Row {
        final String name;
        final int subs;
        final int index;
        final long size; // bytes, 0 = unknown

        Row(String name, int subs, int index, long size) {
            this.name = name;
            this.subs = subs;
            this.index = index;
            this.size = size;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        generatedColors = Appearance.apply(this);
        appearanceSig = appearanceSignature();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        device = getIntent().getParcelableExtra("device");
        if (device == null) {
            finish();
            return;
        }
        usage = new UsageStore(this);
        prefs = Appearance.prefs(this);
        tvName = device.serviceName;

        bindViews();
        setupToolbar();
        setupNav(savedInstanceState == null ? 0 : savedInstanceState.getInt("tab", 0));
        setupSections();
        setupPlayer();
        setupVideo();
        setupAudio();
        setupSubtitles();

        collectControls();
        updateSpeedUi();
        updateAudioUi();
        updateSubUi();
        updateVolumeUi();
        updateStyleUi();
        updateDrUi();
        setConnectedUi(false);

        client = new TvClient(device, this);
        client.start();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (nav != null) out.putInt("tab", nav.selected());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Settings may have changed the colours or the navigation bar.
        if (!appearanceSig.isEmpty() && !appearanceSig.equals(appearanceSignature())) recreate();
    }

    private String appearanceSignature() {
        return Appearance.floating(this) + "|" + Appearance.frosted(this) + "|" + Appearance.deviceColors(this)
                + "|" + Appearance.accent(this) + "|" + Appearance.customColor(this);
    }

    // ------------------------------------------------------------ setup

    private void bindViews() {
        root = findViewById(R.id.root);
        banner = findViewById(R.id.banner);
        toolbar = findViewById(R.id.toolbar);
        scroll = findViewById(R.id.scroll);
        tvTitle = findViewById(R.id.tv_title);
        tvState = findViewById(R.id.tv_state);
        tvDesc = findViewById(R.id.tv_desc);
        tvPos = findViewById(R.id.tv_pos);
        tvLen = findViewById(R.id.tv_len);
        tvVolume = findViewById(R.id.tv_volume);
        tvSpeed = findViewById(R.id.tv_speed);
        tvAudioSync = findViewById(R.id.tv_audio_sync);
        tvSubSync = findViewById(R.id.tv_sub_sync);
        tvSize = findViewById(R.id.tv_size);
        tvBg = findViewById(R.id.tv_bg);
        preview = findViewById(R.id.preview);
        seek = findViewById(R.id.seek);
        volume = findViewById(R.id.volume);
        slSize = findViewById(R.id.sl_size);
        slBg = findViewById(R.id.sl_bg);
        btnBack10 = findViewById(R.id.btn_back10);
        btnPlay = findViewById(R.id.btn_play);
        btnFwd10 = findViewById(R.id.btn_fwd10);
        btnPasteVideo = findViewById(R.id.btn_paste_video);
        btnVolLess = findViewById(R.id.btn_vol_less);
        btnVolMore = findViewById(R.id.btn_vol_more);
        btnSlower = findViewById(R.id.btn_slower);
        btnFaster = findViewById(R.id.btn_faster);
        btnSdr = findViewById(R.id.btn_sdr);
        btnHdr = findViewById(R.id.btn_hdr);
        btnAudioLess = findViewById(R.id.btn_audio_less);
        btnAudioMore = findViewById(R.id.btn_audio_more);
        btnAudioReset = findViewById(R.id.btn_audio_reset);
        btnSubLess = findViewById(R.id.btn_sub_less);
        btnSubMore = findViewById(R.id.btn_sub_more);
        btnSubReset = findViewById(R.id.btn_sub_reset);
        btnPasteSub = findViewById(R.id.btn_paste_sub);
        btnSearch = findViewById(R.id.btn_search);
        ddAudio = findViewById(R.id.dd_audio);
        ddSubtitle = findViewById(R.id.dd_subtitle);
        tilAudio = findViewById(R.id.til_audio);
        tilSubtitle = findViewById(R.id.til_subtitle);
        swBold = findViewById(R.id.sw_bold);
        outlineGroup = findViewById(R.id.outline_group);
        etQuery = findViewById(R.id.et_query);
        etLang = findViewById(R.id.et_lang);
        results = findViewById(R.id.results);
        swatches = findViewById(R.id.swatches);
        panes = new View[]{findViewById(R.id.pane_controls), findViewById(R.id.pane_playback),
                findViewById(R.id.pane_subs), findViewById(R.id.pane_playlist)};
        subFind = findViewById(R.id.sub_find);
        subStyle = findViewById(R.id.sub_style);
        subSync = findViewById(R.id.sub_sync);
        torrentProgress = findViewById(R.id.torrent_progress);
        torrentCard = findViewById(R.id.torrent_card);
        tvPlTitle = findViewById(R.id.tv_pl_title);
        tvPlState = findViewById(R.id.tv_pl_state);
        plProgress = findViewById(R.id.pl_progress);
        playlistRows = findViewById(R.id.playlist_rows);
        tvPlaylistHeader = findViewById(R.id.tv_playlist_header);
    }

    private void collectControls() {
        View[] all = {seek, volume, btnBack10, btnPlay, btnFwd10, btnPasteVideo, btnVolLess, btnVolMore, btnSlower,
                btnFaster, btnSdr, btnHdr, btnAudioLess, btnAudioMore, btnAudioReset, btnSubLess, btnSubMore,
                btnSubReset, btnPasteSub, tilAudio, tilSubtitle, slSize, slBg, swBold,
                findViewById(R.id.ol_none), findViewById(R.id.ol_thin), findViewById(R.id.ol_normal),
                findViewById(R.id.ol_thick)};
        for (View v : all) controls.add(v);
        controls.addAll(gridButtons);
        controls.addAll(swatchViews);
    }

    private void setupToolbar() {
        toolbar.inflateMenu(R.menu.menu_control);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setSubtitleTextColor(color(R.color.error));
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_rename) {
                showRenameDialog();
                return true;
            }
            if (id == R.id.action_updates) {
                // the phone checks itself; the TV is asked to check too when it's connected
                if (connected) send("CHECK_UPDATE");
                UpdateDialog.checkNow(this, text -> snack(connected && text.startsWith("Checking")
                        ? "Checking for updates on this phone and the TV…" : text));
                return true;
            }
            if (id == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
        toolbar.setTitle(tvName);
    }

    private void setupNav(int tab) {
        nav = new NavBar(this, findViewById(R.id.nav_host), TAB_NAMES, TAB_ICONS,
                Appearance.floating(this), Appearance.frosted(this), generatedColors, this::showPane);
        // the content scrolls on under the bar; leave room so its end can scroll clear of it
        scroll.setPadding(0, 0, 0, nav.reservedHeight());
        nav.select(tab);
        showPane(tab);
    }

    private void showPane(int index) {
        for (int i = 0; i < panes.length; i++) panes[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        scroll.scrollTo(0, 0);
        if (index == TAB_PLAYLIST) refreshPlaylist();
    }

    private void selectTab(int index) {
        nav.select(index);
        showPane(index);
    }

    /** Every grey heading collapses the section under it; the state is remembered per heading. */
    private void setupSections() {
        section(R.id.h_volume, R.id.s_volume, "volume");
        section(R.id.h_speed, R.id.s_speed, "speed");
        section(R.id.h_audio_track, R.id.s_audio_track, "audio_track");
        section(R.id.h_audio_sync, R.id.s_audio_sync, "audio_sync");
        section(R.id.h_dynamic_range, R.id.s_dynamic_range, "dynamic_range");
        section(R.id.h_sub_track, R.id.s_sub_track, "sub_track");
        section(R.id.h_sub_search, R.id.s_sub_search, "sub_search");
        section(R.id.h_sub_size, R.id.s_sub_size, "sub_size");
        section(R.id.h_sub_color, R.id.s_sub_color, "sub_color");
        section(R.id.h_sub_outline, R.id.s_sub_outline, "sub_outline");
        section(R.id.h_sub_bg, R.id.s_sub_bg, "sub_bg");
        section(R.id.h_sub_sync, R.id.s_sub_sync, "sub_sync");
    }

    private void section(int headId, int bodyId, final String key) {
        final ViewGroup head = findViewById(headId);
        final View body = findViewById(bodyId);
        final ImageView arrow = (ImageView) head.getChildAt(head.getChildCount() - 1);
        final String label = ((TextView) head.getChildAt(0)).getText().toString();
        final String pref = "collapse_" + key;
        setCollapsed(head, body, arrow, label, prefs.getBoolean(pref, false));
        head.setOnClickListener(v -> {
            boolean collapse = body.getVisibility() == View.VISIBLE;
            setCollapsed(head, body, arrow, label, collapse);
            prefs.edit().putBoolean(pref, collapse).apply();
        });
    }

    private static void setCollapsed(View head, View body, ImageView arrow, String label, boolean collapsed) {
        body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        arrow.setImageResource(collapsed ? R.drawable.ic_expand_more : R.drawable.ic_expand_less);
        head.setContentDescription(label + (collapsed ? ", collapsed" : ", expanded"));
    }

    private void setupPlayer() {
        btnPlay.setOnClickListener(v -> {
            boolean playing = "playing".equals(last.state) || "buffering".equals(last.state)
                    || "opening".equals(last.state);
            send(playing ? "PAUSE" : "RESUME"); // RESUME also replays a video that ended
        });
        btnBack10.setOnClickListener(v -> send("SEEK_REL:-10000"));
        btnFwd10.setOnClickListener(v -> send("SEEK_REL:10000"));
        btnPasteVideo.setOnClickListener(v -> pasteVideo());

        seek.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser && last.length > 0) tvPos.setText(fmt((long) (last.length * value / 1000)));
        });
        seek.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider s) {
                seeking = false;
                if (last.length > 0) send("SEEK:" + (long) (last.length * s.getValue() / 1000));
            }
        });

        volume.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) setVolume((int) value);
        });
        btnVolLess.setOnClickListener(v -> setVolume(volumeValue - VOLUME_STEP));
        btnVolMore.setOnClickListener(v -> setVolume(volumeValue + VOLUME_STEP));
    }

    private void setupVideo() {
        LayoutInflater inf = LayoutInflater.from(this);
        GridLayout grid = findViewById(R.id.speed_grid);
        for (final float preset : PRESETS) {
            MaterialButton b = (MaterialButton) inf.inflate(R.layout.item_choice, grid, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(40);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            b.setLayoutParams(lp);
            b.setText(shortNum.format(preset) + "x");
            b.setOnClickListener(v -> setSpeed(preset));
            grid.addView(b);
            gridButtons.add(b);
        }
        btnSlower.setOnClickListener(v -> setSpeed(speed - SPEED_STEP));
        btnFaster.setOnClickListener(v -> setSpeed(speed + SPEED_STEP));

        btnSdr.setOnClickListener(v -> setHdr(false));
        btnHdr.setOnClickListener(v -> setHdr(true));
    }

    private void setupAudio() {
        ddAudio.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < audioIds.size() && audioIds.get(pos) != last.audioTrack) {
                holdTracks = hold();
                send("AUDIO_TRACK:" + audioIds.get(pos));
            }
        });
        btnAudioLess.setOnClickListener(v -> setAudioSync(audioMs - AUDIO_STEP_MS));
        btnAudioMore.setOnClickListener(v -> setAudioSync(audioMs + AUDIO_STEP_MS));
        btnAudioReset.setOnClickListener(v -> setAudioSync(0));
    }

    private void setupSubtitles() {
        ddSubtitle.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < spuIds.size() && spuIds.get(pos) != last.spuTrack) {
                holdTracks = hold();
                send("SPU_TRACK:" + spuIds.get(pos));
            }
        });

        MaterialButtonToggleGroup sections = findViewById(R.id.sub_sections);
        sections.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            subFind.setVisibility(id == R.id.sec_search ? View.VISIBLE : View.GONE);
            subStyle.setVisibility(id == R.id.sec_style ? View.VISIBLE : View.GONE);
            subSync.setVisibility(id == R.id.sec_sync ? View.VISIBLE : View.GONE);
        });

        // search
        btnPasteSub.setOnClickListener(v -> pasteSubtitle());
        btnSearch.setOnClickListener(v -> searchSubtitles());
        etQuery.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_NULL) {
                searchSubtitles();
                return true;
            }
            return false;
        });

        // style
        for (int i = 0; i < COLORS.length; i++) {
            final int idx = i;
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            sw.setLayoutParams(lp);
            sw.setContentDescription(COLOR_NAMES[i]);
            sw.setOnClickListener(v -> {
                colorIdx = idx;
                updateStyleUi();
                scheduleStyle();
            });
            swatches.addView(sw);
            swatchViews.add(sw);
        }
        slSize.addOnChangeListener((s, value, fromUser) -> {
            updateStyleUi();
            if (fromUser) scheduleStyle();
        });
        slBg.addOnChangeListener((s, value, fromUser) -> {
            updateStyleUi();
            if (fromUser) scheduleStyle();
        });
        swBold.setOnCheckedChangeListener((b, checked) -> {
            updateStyleUi();
            if (!applying) scheduleStyle();
        });
        outlineGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            updateStyleUi();
            if (!applying) scheduleStyle();
        });

        // sync
        btnSubLess.setOnClickListener(v -> setSubSync(subMs - SUB_STEP_MS));
        btnSubMore.setOnClickListener(v -> setSubSync(subMs + SUB_STEP_MS));
        btnSubReset.setOnClickListener(v -> setSubSync(0));
    }

    // ------------------------------------------------------------ actions

    private void setVolume(int v) {
        volumeValue = Math.max(0, Math.min(100, v));
        holdVol = hold();
        updateVolumeUi();
        send("VOLUME:" + volumeValue);
    }

    private void setSpeed(float v) {
        speed = Math.round(Math.max(SPEED_MIN, Math.min(SPEED_MAX, v)) * 100f) / 100f;
        holdSpeed = hold();
        updateSpeedUi();
        send("RATE:" + speed);
    }

    private void setAudioSync(long ms) {
        audioMs = ms;
        holdAudio = hold();
        updateAudioUi();
        send("AUDIO_DELAY:" + audioMs);
    }

    private void setSubSync(long ms) {
        subMs = ms;
        holdSub = hold();
        updateSubUi();
        send("SUBTITLE_DELAY:" + subMs);
    }

    private void setHdr(boolean on) {
        hdr = on;
        holdDr = hold();
        updateDrUi();
        send("HDR:" + (on ? "ON" : "OFF"));
        snack(on ? "Enabled HDR" : "Enabled SDR");
    }

    private final Runnable sendStyle = () -> {
        styleDirty = false;
        JsonObject o = new JsonObject();
        o.addProperty("size", (int) slSize.getValue());
        o.addProperty("color", COLORS[colorIdx]);
        o.addProperty("outline", OUTLINES[outlineIndex()]);
        o.addProperty("bg", (int) slBg.getValue() * 255 / 100);
        o.addProperty("bold", swBold.isChecked());
        send("STYLE:" + o);
        holdStyle = hold() + 2000;
        snack("Applying subtitle style…");
    };

    private void scheduleStyle() {
        // The TV restarts its player to apply style changes, so wait until the user stops fiddling.
        styleDirty = true;
        handler.removeCallbacks(sendStyle);
        handler.postDelayed(sendStyle, 800);
    }

    private int outlineIndex() {
        int id = outlineGroup.getCheckedButtonId();
        for (int i = 0; i < OUTLINE_IDS.length; i++) if (OUTLINE_IDS[i] == id) return i;
        return 2;
    }

    private void pasteVideo() {
        String url = clipboardUrl();
        if (url == null) {
            snack("No link on the clipboard");
            return;
        }
        send("PLAY:" + url);
        snack(url.regionMatches(true, 0, "magnet:", 0, 7) ? "Magnet sent to TV · loading torrent…" : "Link sent to TV");
    }

    private void pasteSubtitle() {
        final String url = clipboardUrl();
        if (url == null) {
            snack("No link on the clipboard");
            return;
        }
        snack("Checking subtitle link…");
        new Thread(() -> {
            final boolean ok = SubtitleApi.looksLikeSubtitle(url);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (ok) {
                    send("SUB_URL:" + url);
                    snack("Subtitle sent to TV");
                } else {
                    snack("That link isn't a subtitle file");
                }
            });
        }, "sub-check").start();
    }

    /** Reads the clipboard (only ever called from a tap) and pulls a magnet or an http(s) link out of it. */
    private String clipboardUrl() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return null;
            CharSequence t = d.getItemAt(0).coerceToText(this);
            if (t == null) return null;
            Matcher mag = Pattern.compile("magnet:\\?[^\\s<>\"']+").matcher(t);
            if (mag.find()) return mag.group();
            Matcher m = Pattern.compile("https?://\\S+").matcher(t);
            return m.find() ? m.group() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ torrent + playlist

    /** The torrent status line. The TV writes it (it runs the engine); older TVs get a local fallback. */
    private String torrentLabel(TvStatus s) {
        String st = s.torrentState;
        if (st == null || st.isEmpty()) return "";
        if (st.equals("choosing")) return "Torrent loaded · choose a video in the playlist";
        if (s.torrentMessage != null && !s.torrentMessage.isEmpty()) return s.torrentMessage;
        StringBuilder b = new StringBuilder("Torrent · ");
        switch (st) {
            case "metadata":
                b.append("loading torrent…");
                break;
            case "checking":
                b.append("checking downloaded videos");
                break;
            case "buffering":
                b.append("buffering ").append(s.torrentProgress).append('%');
                break;
            case "streaming":
                b.append(s.torrentProgress).append('%');
                break;
            case "error":
                b.append(s.torrentError == null || s.torrentError.isEmpty() ? "error" : s.torrentError);
                break;
            default:
                b.append(st);
                break;
        }
        if (s.torrentPeers > 0) b.append(" · ").append(s.torrentPeers).append(" peers");
        if (s.torrentRate > 0) b.append(" · ").append(s.torrentRate / 1024).append(" KB/s");
        return b.toString();
    }

    /** True while the TV is still getting a torrent ready (before the video itself is playing). */
    private static boolean torrentLoading(TvStatus s) {
        String st = s.torrentState == null ? "" : s.torrentState;
        if (st.equals("metadata") || st.equals("checking") || st.equals("buffering")) return true;
        String ps = s.state == null ? "" : s.state;
        return st.equals("streaming") && (ps.equals("opening") || ps.equals("buffering"));
    }

    /** A multi-video torrent waits for a pick: bring up the Playlist tab once per torrent. */
    private void maybeShowTorrentPlaylist(TvStatus s) {
        String title = s.torrentTitle == null ? "" : s.torrentTitle;
        if (!title.equals(torrentTitleSeen)) {
            torrentTitleSeen = title;
            torrentPromptShown = false;
        }
        if (torrentPromptShown || title.isEmpty()) return;
        if (!"choosing".equals(s.torrentState)) return;
        if (torrentRows(s).size() <= 1) return;
        torrentPromptShown = true;
        if (nav.selected() != TAB_PLAYLIST) selectTab(TAB_PLAYLIST);
        snack("Choose a video from the torrent");
    }

    private List<Row> torrentRows(TvStatus s) {
        List<Row> rows = new ArrayList<>();
        if (s.torrentFiles == null) return rows;
        for (TvStatus.TorrentFile f : s.torrentFiles) {
            if (f.name != null && MediaFiles.isVideo(f.name)) {
                rows.add(new Row(f.name, f.subtitles, f.index, f.size));
            }
        }
        return rows;
    }

    /**
     * Fills the Playlist tab: the torrent's videos (sent by the TV), the videos shared from this phone, or
     * otherwise just the one video that is playing. Rebuilt only when something in it changed.
     */
    private void refreshPlaylist() {
        final TvStatus s = last;
        final List<Row> rows = new ArrayList<>();
        final int mode;
        int current;
        String header;
        String message = null;
        String tState = s.torrentState == null ? "" : s.torrentState;
        List<CastService.Entry> local = CastService.playlist();

        boolean torrent = !tState.isEmpty();
        torrentCard.setVisibility(torrent ? View.VISIBLE : View.GONE);
        if (torrent) {
            tvPlTitle.setText(s.torrentTitle == null || s.torrentTitle.isEmpty() ? "Torrent" : s.torrentTitle);
            tvPlState.setText(torrentLabel(s));
            plProgress.setVisibility(connected && torrentLoading(s) ? View.VISIBLE : View.GONE);
        }

        if (torrent) {
            mode = MODE_TORRENT;
            current = s.torrentSelected;
            rows.addAll(torrentRows(s));
            header = "Videos in this torrent";
            if (rows.isEmpty()) {
                message = tState.equals("error") ? "The torrent couldn't be loaded."
                        : "The list appears as soon as the TV has the torrent's file list.";
            }
        } else if (local != null && !local.isEmpty()) {
            mode = MODE_LOCAL;
            current = CastService.currentIndex();
            for (int i = 0; i < local.size(); i++) {
                CastService.Entry e = local.get(i);
                rows.add(new Row(e.name, e.subtitles, i, 0));
            }
            header = "Videos in this share";
        } else {
            // A single link: the playlist is just that one video.
            mode = MODE_SINGLE;
            current = 0;
            header = "";
            String title = s.title == null ? "" : s.title.trim();
            if (connected && !title.isEmpty()) rows.add(new Row(title, 0, 0, 0));
            else message = connected ? "Nothing is playing on the TV." : "Waiting for the TV app…";
        }

        StringBuilder sig = new StringBuilder().append(mode).append('|').append(current).append('|')
                .append(connected).append('|').append(message).append('|').append(tState).append('|');
        for (Row r : rows) sig.append(r.index).append(':').append(r.name).append(':').append(r.subs).append('/');
        if (sig.toString().equals(playlistSig)) return;
        playlistSig = sig.toString();

        tvPlaylistHeader.setText(header);
        tvPlaylistHeader.setVisibility(header.isEmpty() ? View.GONE : View.VISIBLE);
        playlistRows.removeAllViews();
        if (message != null) {
            TextView t = new TextView(this);
            t.setText(message);
            t.setTextSize(14);
            t.setTextColor(attr("colorOnSurfaceVariant", Color.DKGRAY));
            t.setPadding(dp(4), dp(8), dp(4), dp(8));
            playlistRows.addView(t);
            return;
        }
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            final boolean isCurrent = r.index == current;
            View row = inf.inflate(R.layout.item_playlist, playlistRows, false);
            ((TextView) row.findViewById(R.id.number)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.name)).setText(r.name);
            TextView detail = row.findViewById(R.id.detail);
            List<String> bits = new ArrayList<>();
            if (isCurrent && rows.size() > 1) {
                bits.add(mode == MODE_TORRENT && torrentLoading(s) ? "Loading" : "Playing");
            }
            if (r.size > 0) bits.add(humanSize(r.size));
            if (r.subs > 0) bits.add(r.subs + (r.subs == 1 ? " subtitle" : " subtitles"));
            if (!bits.isEmpty()) {
                detail.setText(android.text.TextUtils.join(" · ", bits));
                detail.setVisibility(View.VISIBLE);
            }
            ImageView now = row.findViewById(R.id.now);
            now.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            MaterialCardView card = (MaterialCardView) row;
            card.setCardBackgroundColor(attr(isCurrent ? "colorSecondaryContainer" : "colorSurfaceContainer",
                    Color.LTGRAY));
            boolean pickable = mode != MODE_SINGLE && !isCurrent && connected;
            row.setAlpha(connected ? 1f : DISABLED_ALPHA);
            if (pickable) {
                row.setOnClickListener(v -> {
                    if (mode == MODE_TORRENT) {
                        send("TORRENT_FILE:" + r.index);
                        snack("Loading " + r.name);
                    } else {
                        CastService.select(r.index);
                        snack("Playing " + r.name);
                    }
                });
            } else {
                row.setClickable(false);
            }
            playlistRows.addView(row);
        }
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
        return Math.max(1, bytes / 1024) + " KB";
    }

    private void showRenameDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        final EditText et = v.findViewById(R.id.et_name);
        et.setText(tvName);
        et.setSelection(et.getText().length());
        new MaterialAlertDialogBuilder(this)
                .setTitle("Edit name")
                .setView(v)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String n = et.getText().toString().trim().replace("\n", " ");
                    if (n.isEmpty() || n.equals(tvName)) return;
                    usage.rename(tvName, n);
                    tvName = n;
                    toolbar.setTitle(n);
                    send("RENAME:" + n);
                    snack("Renamed. The name is saved on the TV");
                })
                .show();
    }

    // ------------------------------------------------------------ UI updaters

    private void updateVolumeUi() {
        tvVolume.setText(volumeValue + "%");
        float v = Math.max(0, Math.min(100, volumeValue));
        if (volume.getValue() != v) volume.setValue(v);
    }

    private void updateSpeedUi() {
        tvSpeed.setText(String.format(Locale.US, "%.2fx", speed));
        for (int i = 0; i < gridButtons.size(); i++) {
            styleChoice(gridButtons.get(i), Math.abs(PRESETS[i] - speed) < 0.001f, false);
        }
    }

    private void updateAudioUi() {
        tvAudioSync.setText(secs(audioMs));
    }

    private void updateSubUi() {
        tvSubSync.setText(secs(subMs));
    }

    private void updateDrUi() {
        styleChoice(btnSdr, !hdr, true);
        styleChoice(btnHdr, hdr, true);
    }

    private void updateStyleUi() {
        int size = (int) slSize.getValue();
        int bg = (int) slBg.getValue();
        tvSize.setText(size + "%");
        tvBg.setText(bg + "%");
        preview.setTextSize(16f * size / 100f);
        preview.setTextColor(0xFF000000 | COLORS[colorIdx]);
        preview.setTypeface(null, swBold.isChecked() ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        int ol = OUTLINES[outlineIndex()];
        if (ol > 0) preview.setShadowLayer(ol, 0, 0, Color.BLACK);
        else preview.setShadowLayer(0, 0, 0, 0);
        preview.setBackgroundColor(Color.argb(bg * 255 / 100, 0, 0, 0));
        int primary = attr("colorPrimary", 0xFFA43E00);
        int outlineVariant = attr("colorOutlineVariant", Color.LTGRAY);
        for (int i = 0; i < swatchViews.size(); i++) {
            boolean sel = i == colorIdx;
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF000000 | COLORS[i]);
            g.setStroke(dp(sel ? 3 : 1), sel ? primary : outlineVariant);
            swatchViews.get(i).setBackground(g);
        }
    }

    /** Outlined button; the selected one is filled (accent) or tonal. */
    private void styleChoice(MaterialButton b, boolean selected, boolean filled) {
        int primary = attr("colorPrimary", 0xFFA43E00);
        int bg = selected ? (filled ? primary : attr("colorSecondaryContainer", Color.LTGRAY)) : Color.TRANSPARENT;
        int fg = selected ? attr(filled ? "colorOnPrimary" : "colorOnSecondaryContainer", Color.BLACK)
                : (filled ? primary : attr("colorOnSurface", Color.BLACK));
        b.setBackgroundTintList(ColorStateList.valueOf(bg));
        b.setTextColor(fg);
        b.setStrokeColor(ColorStateList.valueOf(selected ? bg : attr("colorOutlineVariant", Color.LTGRAY)));
        b.setStrokeWidth(dp(1));
    }

    private String secs(long ms) {
        String sign = ms > 0 ? "+" : ms < 0 ? "−" : "";
        return sign + shortNum.format(Math.abs(ms) / 1000.0) + " s";
    }

    private void setControlsEnabled(boolean enabled) {
        for (View v : controls) {
            v.setEnabled(enabled);
            v.setAlpha(enabled ? 1f : DISABLED_ALPHA);
        }
        MenuItem rename = toolbar.getMenu().findItem(R.id.action_rename);
        if (rename != null) rename.setVisible(enabled); // editing the name needs the TV
        for (int i = 0; i < results.getChildCount(); i++) {
            View row = results.getChildAt(i);
            View send = row.findViewById(R.id.send);
            if (send != null) {
                send.setEnabled(enabled);
                send.setAlpha(enabled ? 1f : DISABLED_ALPHA);
            }
        }
    }

    private void setConnectedUi(boolean isConnected) {
        setControlsEnabled(isConnected);
        banner.setVisibility(isConnected ? View.GONE : View.VISIBLE);
        toolbar.setSubtitle(isConnected ? "Connected" : "Not connected — waiting for the TV app…");
        toolbar.setSubtitleTextColor(color(isConnected ? R.color.ok : R.color.error));
    }

    // ------------------------------------------------------------ TV events

    @Override
    public void onConnectionChanged(boolean isConnected) {
        connected = isConnected;
        if (!isConnected) {
            styleSynced = false;
            styleSig = "";
        } // re-read the TV's settings after reconnecting
        setConnectedUi(isConnected);
        refreshPlaylist();
    }

    @Override
    public void onStatus(TvStatus s) {
        last = s;
        String state = s.state == null ? "idle" : s.state;
        boolean playing = state.equals("playing") || state.equals("buffering") || state.equals("opening");

        if (s.name != null && !s.name.isEmpty() && !s.name.equals(tvName) && !held(holdTracks)) {
            tvName = s.name;
            toolbar.setTitle(tvName);
        }

        String title = s.title == null || s.title.isEmpty() ? "" : s.title;
        if (title.isEmpty() && s.torrentTitle != null && !s.torrentTitle.isEmpty()) title = s.torrentTitle;
        tvTitle.setText(title.isEmpty() ? "Nothing playing" : title);

        // line 1: what the player is doing; line 2: what the TV is doing (torrent progress and the like)
        String torrent = torrentLabel(s);
        boolean failed = state.equals("error") || "error".equals(s.torrentState);
        tvState.setTextColor(failed ? color(R.color.error) : attr("colorOnSurfaceVariant", Color.DKGRAY));
        if (state.equals("error")) tvState.setText("TV couldn't load this video");
        else tvState.setText(stateLabel(state, s.rate, !torrent.isEmpty()));
        tvDesc.setText(torrent);
        tvDesc.setVisibility(torrent.isEmpty() ? View.GONE : View.VISIBLE);
        torrentProgress.setVisibility(connected && torrentLoading(s) ? View.VISIBLE : View.GONE);
        maybeShowTorrentPlaylist(s);
        refreshPlaylist();
        btnPlay.setIcon(ContextCompat.getDrawable(this, playing ? R.drawable.ic_pause : R.drawable.ic_play));

        seek.setEnabled(connected && s.length > 0 && s.seekable);
        if (!seeking) {
            float v = s.length > 0 ? Math.max(0f, Math.min(1000f, s.time * 1000f / s.length)) : 0f;
            seek.setValue(v);
            tvPos.setText(fmt(s.time));
        }
        tvLen.setText(s.length > 0 ? fmt(s.length) : "--:--");

        if (!held(holdVol)) {
            int v = Math.max(0, Math.min(100, s.volume));
            if (v != volumeValue) {
                volumeValue = v;
                updateVolumeUi();
            }
        }
        if (!held(holdSpeed)) {
            float r = Math.round(Math.max(SPEED_MIN, Math.min(SPEED_MAX, s.rate)) * 100f) / 100f;
            if (r != speed) {
                speed = r;
                updateSpeedUi();
            }
        }
        if (!held(holdAudio) && audioMs != s.audioDelay) {
            audioMs = s.audioDelay;
            updateAudioUi();
        }
        if (!held(holdSub) && subMs != s.spuDelay) {
            subMs = s.spuDelay;
            updateSubUi();
        }
        if (!held(holdDr) && hdr != s.hdr) {
            hdr = s.hdr;
            updateDrUi();
        }
        if (!held(holdTracks)) {
            syncTracks(ddAudio, audioIds, s.audioTracks, s.audioTrack, true);
            syncTracks(ddSubtitle, spuIds, s.spuTracks, s.spuTrack, false);
        }

        // Subtitle style follows the TV too (another phone may have changed it), unless this phone is mid-edit.
        String sig = s.subSize + "/" + s.subColor + "/" + s.subOutline + "/" + s.subBg + "/" + s.subBold;
        boolean editing = held(holdStyle) || styleDirty;
        if (!editing && (!styleSynced || !sig.equals(styleSig))) {
            styleSynced = true;
            styleSig = sig;
            applying = true;
            slSize.setValue(snap(s.subSize, 50, 200, 5));
            slBg.setValue(snap(s.subBg * 100 / 255, 0, 100, 5));
            swBold.setChecked(s.subBold);
            colorIdx = indexOf(COLORS, s.subColor, 0);
            int oi = indexOf(OUTLINES, s.subOutline, 2);
            outlineGroup.check(OUTLINE_IDS[oi]);
            applying = false;
            updateStyleUi();
        }
    }

    private String stateLabel(String state, float rate, boolean torrent) {
        String r = shortNum.format(rate) + "×";
        switch (state) {
            case "playing":
                return "Playing · " + r;
            case "paused":
                return "Paused · " + r;
            case "opening":
            case "buffering":
                return torrent ? "Preparing" : "Loading…";
            case "stopped":
                return "Stopped";
            case "ended":
                return "Ended · press play to watch again";
            default:
                return "Ready";
        }
    }

    private static String sourceLabel(String source) {
        if (source == null) return "";
        switch (source) {
            case "torrent":
                return "from the torrent";
            case "phone":
                return "sent from Ping!";
            case "video":
                return "in the video";
            default:
                return "";
        }
    }

    private void syncTracks(MaterialAutoCompleteTextView dd, List<Integer> ids, List<TvStatus.Track> tracks,
                            int current, boolean audio) {
        StringBuilder sig = new StringBuilder();
        if (tracks != null) {
            for (TvStatus.Track t : tracks) sig.append(t.id).append(':').append(t.name).append(':').append(t.source).append('|');
        }
        String oldSig = audio ? audioSig : spuSig;
        List<String> names = new ArrayList<>();
        if (tracks != null) {
            for (TvStatus.Track t : tracks) {
                String src = audio ? "" : sourceLabel(t.source);
                names.add(src.isEmpty() ? t.name : t.name + " · " + src);
            }
        }
        if (!sig.toString().equals(oldSig)) {
            if (audio) audioSig = sig.toString();
            else spuSig = sig.toString();
            ids.clear();
            if (tracks != null) for (TvStatus.Track t : tracks) ids.add(t.id);
            dd.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        }
        int idx = ids.indexOf(current);
        if (idx >= 0 && idx < names.size()) dd.setText(names.get(idx), false);
        else dd.setText("", false);
    }

    // ------------------------------------------------------ subtitle search

    private void searchSubtitles() {
        final String query = etQuery.getText().toString().trim();
        String l = etLang.getText().toString().trim();
        final String lang = l.isEmpty() ? "en" : l; // the box shows "en" as its placeholder
        if (query.isEmpty()) {
            snack("Enter a movie or show name");
            return;
        }
        if (!SubtitleApi.hasKeys()) {
            snack("Subtitle search isn't set up in this build");
            return;
        }
        results.removeAllViews();
        addMessageRow("Searching…");
        new Thread(() -> {
            try {
                final List<SubtitleApi.Result> found = SubtitleApi.search(query, lang);
                runOnUiThread(() -> showResults(found));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    results.removeAllViews();
                    addMessageRow("Search failed: " + e.getMessage());
                });
            }
        }, "sub-search").start();
    }

    private void showResults(List<SubtitleApi.Result> found) {
        results.removeAllViews();
        if (found.isEmpty()) {
            addMessageRow("No subtitles found");
            return;
        }
        LayoutInflater inf = LayoutInflater.from(this);
        for (final SubtitleApi.Result r : found) {
            View row = inf.inflate(R.layout.item_subtitle_result, results, false);
            ((TextView) row.findViewById(R.id.lang)).setText(
                    r.language == null ? "?" : r.language.toUpperCase(Locale.ROOT));
            ((TextView) row.findViewById(R.id.title)).setText(
                    r.title == null || r.title.isEmpty() ? "(untitled)" : r.title);
            ((TextView) row.findViewById(R.id.release)).setText(r.release == null ? "" : r.release);
            View send = row.findViewById(R.id.send);
            send.setEnabled(connected);
            send.setAlpha(connected ? 1f : DISABLED_ALPHA);
            send.setOnClickListener(v -> sendSubtitle(r));
            results.addView(row);
        }
    }

    private void addMessageRow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(attr("colorOnSurfaceVariant", Color.DKGRAY));
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        results.addView(t);
    }

    private void sendSubtitle(final SubtitleApi.Result r) {
        if (!connected) {
            snack("TV app not connected");
            return;
        }
        snack("Fetching subtitle…");
        new Thread(() -> {
            try {
                final SubtitleApi.Fetched f = SubtitleApi.download(r.id, r.season, r.episode);
                String label = r.title == null || r.title.isEmpty() ? "subtitle" : r.title;
                if (r.language != null && !r.language.isEmpty()) label = label + "." + r.language;
                final String link = SubtitleServer.serve(f.data, f.ext, device.host, device.port, label);
                runOnUiThread(() -> {
                    send("SUB_URL:" + link);
                    snack("Subtitle sent to TV");
                });
            } catch (Exception e) {
                runOnUiThread(() -> snack(String.valueOf(e.getMessage())));
            }
        }, "sub-download").start();
    }

    // -------------------------------------------------------------- helpers

    private void send(String msg) {
        if (client != null) client.send(msg);
    }

    private long hold() {
        return SystemClock.elapsedRealtime() + HOLD_MS;
    }

    private boolean held(long until) {
        return SystemClock.elapsedRealtime() < until;
    }

    private void snack(String msg) {
        Snackbar sb = Snackbar.make(root, msg, Snackbar.LENGTH_LONG);
        sb.setBackgroundTint(color(R.color.snack_bg));
        sb.setTextColor(color(R.color.snack_text));
        sb.setActionTextColor(color(R.color.snack_action));
        sb.setAction("Dismiss", v -> sb.dismiss());
        // keep it above the navigation bar
        if (nav != null) sb.getView().setTranslationY(-nav.reservedHeight());
        sb.show();
    }

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private int attr(String name, int fallback) {
        return Appearance.color(this, name, fallback);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static float snap(float v, float from, float to, float step) {
        float c = Math.max(from, Math.min(to, v));
        return from + Math.round((c - from) / step) * step;
    }

    private static int indexOf(int[] arr, int v, int def) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return def;
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%02d:%02d", m, sec);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (client != null) client.stop();
    }
}
