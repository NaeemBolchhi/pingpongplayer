package nb.pingpong.ping;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.gson.JsonObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Remote control for one TV app. Shows no video/audio, only controls. */
public class ControlActivity extends AppCompatActivity implements TvClient.Listener {
    private static final float[] PRESETS = {
            0.25f, 0.5f, 0.75f, 1f,
            1.25f, 1.5f, 1.75f, 2f,
            2.25f, 2.5f, 2.75f, 3f,
            3.25f, 3.5f, 3.75f, 4f};
    private static final float SPEED_STEP = 0.05f, SPEED_MIN = 0.05f, SPEED_MAX = 16f;
    private static final int AUDIO_STEP_MS = 250, SUB_STEP_MS = 500;
    private static final String[] COLOR_NAMES = {"White", "Yellow", "Cyan", "Green"};
    private static final int[] COLORS = {0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00};
    private static final int[] OUTLINE_IDS = {R.id.ol_none, R.id.ol_thin, R.id.ol_normal, R.id.ol_thick};
    private static final int[] OUTLINES = {0, 2, 4, 6};
    /** After the user touches a control, ignore TV status for it this long so it doesn't jump back. */
    private static final long HOLD_MS = 2000;
    private static final float DISABLED_ALPHA = 0.38f;

    private final DecimalFormat shortNum = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private DeviceInfo device;
    private TvClient client;
    private UsageStore usage;
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

    // local values behind the steppers
    private float speed = 1f;
    private long audioMs = 0, subMs = 0;
    private boolean hdr = false;
    private int colorIdx = 0;
    private String tvName = "";

    private View root, banner;
    private MaterialToolbar toolbar;
    private TextView tvTitle, tvState, tvPos, tvLen, tvVolume, tvSpeed, tvAudioSync, tvSubSync, tvSize, tvBg, preview;
    private Slider seek, volume, slSize, slBg;
    private MaterialButton btnBack10, btnPlay, btnFwd10, btnPasteVideo, btnSlower, btnFaster, btnSdr, btnHdr;
    private MaterialButton btnAudioLess, btnAudioMore, btnAudioReset, btnSubLess, btnSubMore, btnSubReset;
    private MaterialButton btnPasteSub, btnSearch, btnAccount;
    private MaterialAutoCompleteTextView ddAudio, ddSubtitle;
    private View tilAudio, tilSubtitle;
    private MaterialSwitch swBold;
    private MaterialButtonToggleGroup outlineGroup;
    private EditText etQuery, etLang;
    private LinearLayout results, swatches;
    private View paneVideo, paneAudio, paneSubs, subFind, subStyle, subSync;
    private final List<View> swatchViews = new ArrayList<>();
    private final List<MaterialButton> gridButtons = new ArrayList<>();
    private final List<View> controls = new ArrayList<>();

    private final List<Integer> audioIds = new ArrayList<>();
    private final List<Integer> spuIds = new ArrayList<>();
    private String audioSig = "", spuSig = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        device = getIntent().getParcelableExtra("device");
        if (device == null) {
            finish();
            return;
        }
        usage = new UsageStore(this);
        tvName = device.serviceName;

        bindViews();
        setupToolbar();
        setupPlayer();
        setupTabs();
        setupVideo();
        setupAudio();
        setupSubtitles();

        collectControls();
        updateSpeedUi();
        updateAudioUi();
        updateSubUi();
        updateStyleUi();
        updateDrUi();
        setConnectedUi(false);

        client = new TvClient(device, this);
        client.start();
    }

    // ------------------------------------------------------------ setup

    private void bindViews() {
        root = findViewById(R.id.root);
        banner = findViewById(R.id.banner);
        toolbar = findViewById(R.id.toolbar);
        tvTitle = findViewById(R.id.tv_title);
        tvState = findViewById(R.id.tv_state);
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
        btnAccount = findViewById(R.id.btn_account);
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
        paneVideo = findViewById(R.id.pane_video);
        paneAudio = findViewById(R.id.pane_audio);
        paneSubs = findViewById(R.id.pane_subs);
        subFind = findViewById(R.id.sub_find);
        subStyle = findViewById(R.id.sub_style);
        subSync = findViewById(R.id.sub_sync);
    }

    private void collectControls() {
        View[] all = {seek, volume, btnBack10, btnPlay, btnFwd10, btnPasteVideo, btnSlower, btnFaster, btnSdr, btnHdr,
                btnAudioLess, btnAudioMore, btnAudioReset, btnSubLess, btnSubMore, btnSubReset, btnPasteSub,
                tilAudio, tilSubtitle, slSize, slBg, swBold,
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
            if (item.getItemId() == R.id.action_rename) {
                showRenameDialog();
                return true;
            }
            return false;
        });
        toolbar.setTitle(tvName);
    }

    private void setupPlayer() {
        btnPlay.setOnClickListener(v -> {
            boolean playing = "playing".equals(last.state) || "buffering".equals(last.state)
                    || "opening".equals(last.state);
            send(playing ? "PAUSE" : "RESUME");
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
            tvVolume.setText(((int) value) + "%");
            if (fromUser) {
                holdVol = hold();
                send("VOLUME:" + (int) value);
            }
        });
    }

    private void setupTabs() {
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int p = tab.getPosition();
                paneVideo.setVisibility(p == 0 ? View.VISIBLE : View.GONE);
                paneAudio.setVisibility(p == 1 ? View.VISIBLE : View.GONE);
                paneSubs.setVisibility(p == 2 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
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
            searchSubtitles();
            return true;
        });
        btnAccount.setOnClickListener(v -> showAccountDialog());

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
        snack("Link sent to TV");
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

    /** Reads the clipboard (only ever called from a tap) and pulls an http(s) link out of it. */
    private String clipboardUrl() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return null;
            CharSequence t = d.getItemAt(0).coerceToText(this);
            if (t == null) return null;
            Matcher m = Pattern.compile("https?://\\S+").matcher(t);
            return m.find() ? m.group() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void showRenameDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null);
        final EditText et = v.findViewById(R.id.et_name);
        et.setText(tvName);
        et.setSelection(et.getText().length());
        new MaterialAlertDialogBuilder(this)
                .setTitle("Rename TV")
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
        for (int i = 0; i < swatchViews.size(); i++) {
            boolean sel = i == colorIdx;
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF000000 | COLORS[i]);
            g.setStroke(dp(sel ? 3 : 1), color(sel ? R.color.primary : R.color.outline_variant));
            swatchViews.get(i).setBackground(g);
        }
    }

    /** Outlined button; the selected one is filled (accent) or tonal. */
    private void styleChoice(MaterialButton b, boolean selected, boolean filled) {
        int bg = selected ? color(filled ? R.color.primary : R.color.secondary_container) : Color.TRANSPARENT;
        int fg = selected ? color(filled ? R.color.on_primary : R.color.on_secondary_container)
                : color(filled ? R.color.primary : R.color.on_surface);
        b.setBackgroundTintList(ColorStateList.valueOf(bg));
        b.setTextColor(fg);
        b.setStrokeColor(ColorStateList.valueOf(selected ? bg : color(R.color.outline_variant)));
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
        if (toolbar.getMenu().findItem(R.id.action_rename) != null) {
            toolbar.getMenu().findItem(R.id.action_rename).setEnabled(enabled);
        }
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

        tvTitle.setText(s.title == null || s.title.isEmpty() ? "Nothing playing" : s.title);
        if (state.equals("error")) {
            tvState.setText("TV couldn't load this video");
            tvState.setTextColor(color(R.color.error));
        } else {
            tvState.setTextColor(color(R.color.on_surface_variant));
            tvState.setText(stateLabel(state, s.rate));
        }
        btnPlay.setIcon(ContextCompat.getDrawable(this, playing ? R.drawable.ic_pause : R.drawable.ic_play));

        seek.setEnabled(connected && s.length > 0 && s.seekable);
        if (!seeking) {
            float v = s.length > 0 ? Math.max(0f, Math.min(1000f, s.time * 1000f / s.length)) : 0f;
            seek.setValue(v);
            tvPos.setText(fmt(s.time));
        }
        tvLen.setText(s.length > 0 ? fmt(s.length) : "--:--");

        if (!held(holdVol)) volume.setValue(Math.max(0, Math.min(100, s.volume)));
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

    private String stateLabel(String state, float rate) {
        String r = shortNum.format(rate) + "×";
        switch (state) {
            case "playing":
                return "Playing · " + r;
            case "paused":
                return "Paused · " + r;
            case "opening":
            case "buffering":
                return "Loading…";
            case "stopped":
                return "Stopped";
            case "ended":
                return "Ended";
            default:
                return "Ready";
        }
    }

    private void syncTracks(MaterialAutoCompleteTextView dd, List<Integer> ids, List<TvStatus.Track> tracks,
                            int current, boolean audio) {
        StringBuilder sig = new StringBuilder();
        if (tracks != null) for (TvStatus.Track t : tracks) sig.append(t.id).append(':').append(t.name).append('|');
        String oldSig = audio ? audioSig : spuSig;
        if (!sig.toString().equals(oldSig)) {
            if (audio) audioSig = sig.toString();
            else spuSig = sig.toString();
            ids.clear();
            List<String> names = new ArrayList<>();
            if (tracks != null) {
                for (TvStatus.Track t : tracks) {
                    ids.add(t.id);
                    names.add(t.name);
                }
            }
            dd.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        }
        int idx = ids.indexOf(current);
        if (idx >= 0 && tracks != null && idx < tracks.size()) dd.setText(tracks.get(idx).name, false);
        else dd.setText("", false);
    }

    // ------------------------------------------------------ subtitle search

    private void searchSubtitles() {
        final String query = etQuery.getText().toString().trim();
        final String lang = etLang.getText().toString().trim();
        if (query.isEmpty()) {
            snack("Enter a movie or show name");
            return;
        }
        if (!SubtitleApi.hasApiKey(this)) {
            showAccountDialog();
            return;
        }
        results.removeAllViews();
        addMessageRow("Searching…");
        new Thread(() -> {
            try {
                final List<SubtitleApi.Result> found = SubtitleApi.search(this, query, lang);
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
        t.setTextColor(color(R.color.on_surface_variant));
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
                final String link = SubtitleApi.downloadLink(this, r.fileId);
                runOnUiThread(() -> {
                    send("SUB_URL:" + link);
                    snack("Subtitle sent to TV");
                });
            } catch (Exception e) {
                runOnUiThread(() -> snack(String.valueOf(e.getMessage())));
            }
        }, "sub-download").start();
    }

    private void showAccountDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        final EditText key = new EditText(this);
        key.setHint("OpenSubtitles API key");
        key.setSingleLine(true);
        final EditText user = new EditText(this);
        user.setHint("Username");
        user.setSingleLine(true);
        final EditText pass = new EditText(this);
        pass.setHint("Password");
        pass.setSingleLine(true);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(SubtitleApi.prefs(this).getString("api_key", ""));
        user.setText(SubtitleApi.prefs(this).getString("username", ""));
        pass.setText(SubtitleApi.prefs(this).getString("password", ""));
        box.addView(key);
        box.addView(user);
        box.addView(pass);
        new MaterialAlertDialogBuilder(this)
                .setTitle("OpenSubtitles.com")
                .setMessage("Searching needs a free API key; downloading needs your account login.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> SubtitleApi.saveCredentials(this,
                        key.getText().toString(), user.getText().toString(), pass.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
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
        sb.show();
    }

    private int color(int res) {
        return ContextCompat.getColor(this, res);
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
