package nb.pingpong.ping;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Remote control for one TV app. Shows no video/audio, only controls. */
public class ControlActivity extends AppCompatActivity implements TvClient.Listener {
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final String[] SPEED_NAMES = {"0.5x", "0.75x", "1x (normal)", "1.25x", "1.5x", "2x"};
    private static final String[] COLOR_NAMES = {"White", "Yellow", "Cyan", "Green"};
    private static final int[] COLORS = {0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00};
    private static final String[] OUTLINE_NAMES = {"None", "Thin", "Normal", "Thick"};
    private static final int[] OUTLINES = {0, 2, 4, 6};
    /** After the user touches a control, ignore TV status for it this long so it doesn't jump back. */
    private static final long HOLD_MS = 2000;

    private DeviceInfo device;
    private TvClient client;
    private TvStatus last = new TvStatus();
    private boolean connected = false;
    private boolean seeking = false;
    private boolean styleSynced = false;
    private long holdUntil = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvConnection, tvTitle, tvTime, tvAudioDelay, tvSubDelay, tvSubSize;
    private SeekBar seekProgress, seekVolume, seekAudioDelay, seekSubDelay, seekSubSize, seekSubBg;
    private Button btnBack10, btnPlayPause, btnFwd10, btnSearch, btnAccount;
    private Spinner spSpeed, spAudio, spSubtitle, spColor, spOutline;
    private Switch swBold, swHdr;
    private EditText etQuery, etLang;
    private LinearLayout results;
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

        ((TextView) findViewById(R.id.tv_device_name)).setText(device.serviceName);
        tvConnection = findViewById(R.id.tv_connection);
        tvTitle = findViewById(R.id.tv_title);
        tvTime = findViewById(R.id.tv_time);
        tvAudioDelay = findViewById(R.id.tv_audio_delay);
        tvSubDelay = findViewById(R.id.tv_sub_delay);
        tvSubSize = findViewById(R.id.tv_sub_size);
        seekProgress = findViewById(R.id.seek_progress);
        seekVolume = findViewById(R.id.seek_volume);
        seekAudioDelay = findViewById(R.id.seek_audio_delay);
        seekSubDelay = findViewById(R.id.seek_sub_delay);
        seekSubSize = findViewById(R.id.seek_sub_size);
        seekSubBg = findViewById(R.id.seek_sub_bg);
        btnBack10 = findViewById(R.id.btn_back10);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnFwd10 = findViewById(R.id.btn_fwd10);
        btnSearch = findViewById(R.id.btn_search_subtitle);
        btnAccount = findViewById(R.id.btn_subtitle_account);
        spSpeed = findViewById(R.id.spinner_speed);
        spAudio = findViewById(R.id.spinner_audio);
        spSubtitle = findViewById(R.id.spinner_subtitle);
        spColor = findViewById(R.id.spinner_sub_color);
        spOutline = findViewById(R.id.spinner_sub_outline);
        swBold = findViewById(R.id.switch_sub_bold);
        swHdr = findViewById(R.id.switch_hdr);
        etQuery = findViewById(R.id.et_subtitle_query);
        etLang = findViewById(R.id.et_subtitle_lang);
        results = findViewById(R.id.subtitle_results);

        for (View v : new View[]{seekProgress, seekVolume, seekAudioDelay, seekSubDelay, seekSubSize, seekSubBg,
                btnBack10, btnPlayPause, btnFwd10, btnSearch, btnAccount, spSpeed, spAudio, spSubtitle, spColor,
                spOutline, swBold, swHdr, etQuery, etLang}) {
            controls.add(v);
        }

        spSpeed.setAdapter(adapterOf(SPEED_NAMES));
        spSpeed.setSelection(2);
        spColor.setAdapter(adapterOf(COLOR_NAMES));
        spOutline.setAdapter(adapterOf(OUTLINE_NAMES));
        spOutline.setSelection(2);

        setupListeners();
        setControlsEnabled(false);
        updateConnectionText();

        client = new TvClient(device, this);
        client.start();
    }

    private ArrayAdapter<String> adapterOf(String[] items) {
        return adapterOf(java.util.Arrays.asList(items));
    }

    private ArrayAdapter<String> adapterOf(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    // ------------------------------------------------------------ listeners

    private void setupListeners() {
        btnPlayPause.setOnClickListener(v -> {
            boolean playing = "playing".equals(last.state) || "buffering".equals(last.state)
                    || "opening".equals(last.state);
            send(playing ? "PAUSE" : "RESUME");
        });
        btnBack10.setOnClickListener(v -> send("SEEK_REL:-10000"));
        btnFwd10.setOnClickListener(v -> send("SEEK_REL:10000"));

        seekProgress.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && last.length > 0) tvTime.setText(fmt(last.length * p / 1000) + " / " + fmt(last.length));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (last.length > 0) send("SEEK:" + (last.length * s.getProgress() / 1000));
            }
        });

        seekVolume.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    hold();
                    send("VOLUME:" + p);
                }
            }
        });

        spSpeed.setOnItemSelectedListener(new SimpleSel() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (Math.abs(SPEEDS[pos] - last.rate) > 0.01f) {
                    hold();
                    send("RATE:" + SPEEDS[pos]);
                }
            }
        });
        spAudio.setOnItemSelectedListener(new SimpleSel() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < audioIds.size() && audioIds.get(pos) != last.audioTrack) {
                    hold();
                    send("AUDIO_TRACK:" + audioIds.get(pos));
                }
            }
        });
        spSubtitle.setOnItemSelectedListener(new SimpleSel() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos < spuIds.size() && spuIds.get(pos) != last.spuTrack) {
                    hold();
                    send("SPU_TRACK:" + spuIds.get(pos));
                }
            }
        });

        seekAudioDelay.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvAudioDelay.setText("Audio delay: " + delayOf(p) + " ms");
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                hold();
                send("AUDIO_DELAY:" + delayOf(s.getProgress()));
            }
        });
        seekSubDelay.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvSubDelay.setText("Subtitle delay: " + delayOf(p) + " ms");
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                hold();
                send("SUBTITLE_DELAY:" + delayOf(s.getProgress()));
            }
        });

        // subtitle style: user-driven changes are batched and sent together
        seekSubSize.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvSubSize.setText("Size: " + (p + 50) + "%");
                if (fromUser) scheduleStyle();
            }
        });
        seekSubBg.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) scheduleStyle();
            }
        });
        spColor.setOnItemSelectedListener(new SimpleSel() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (styleSynced && COLORS[pos] != last.subColor) scheduleStyle();
            }
        });
        spOutline.setOnItemSelectedListener(new SimpleSel() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (styleSynced && OUTLINES[pos] != last.subOutline) scheduleStyle();
            }
        });
        swBold.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            if (b.isPressed()) scheduleStyle();
        });
        swHdr.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
            if (b.isPressed()) send("HDR:" + (checked ? "ON" : "OFF"));
        });

        btnSearch.setOnClickListener(v -> searchSubtitles());
        etQuery.setOnEditorActionListener((v, actionId, event) -> {
            searchSubtitles();
            return true;
        });
        btnAccount.setOnClickListener(v -> showAccountDialog());
    }

    private static int delayOf(int progress) {
        return (progress - 100) * 50; // -5000 .. +5000 ms
    }

    private void hold() {
        holdUntil = SystemClock.elapsedRealtime() + HOLD_MS;
    }

    private boolean held() {
        return SystemClock.elapsedRealtime() < holdUntil;
    }

    private final Runnable sendStyle = () -> {
        JsonObject o = new JsonObject();
        o.addProperty("size", seekSubSize.getProgress() + 50);
        o.addProperty("color", COLORS[spColor.getSelectedItemPosition()]);
        o.addProperty("outline", OUTLINES[spOutline.getSelectedItemPosition()]);
        o.addProperty("bg", seekSubBg.getProgress() * 255 / 100);
        o.addProperty("bold", swBold.isChecked());
        send("STYLE:" + o);
        Toast.makeText(this, "Applying subtitle style…", Toast.LENGTH_SHORT).show();
    };

    private void scheduleStyle() {
        // The TV restarts its player to apply style changes, so wait until the user stops fiddling.
        handler.removeCallbacks(sendStyle);
        handler.postDelayed(sendStyle, 800);
    }

    private void send(String msg) {
        if (client != null) client.send(msg);
    }

    // ------------------------------------------------------------ TV events

    @Override
    public void onConnectionChanged(boolean isConnected) {
        connected = isConnected;
        if (!isConnected) styleSynced = false; // re-read the TV's settings after reconnecting
        setControlsEnabled(isConnected);
        updateConnectionText();
    }

    @Override
    public void onStatus(TvStatus s) {
        last = s;
        String state = s.state == null ? "idle" : s.state;
        boolean playing = state.equals("playing") || state.equals("buffering") || state.equals("opening");
        tvTitle.setText((s.title == null || s.title.isEmpty() ? "Nothing playing" : s.title)
                + (state.equals("idle") ? "" : "  (" + state + ")"));
        btnPlayPause.setText(playing ? "Pause" : "Play");

        if (!seeking) seekProgress.setProgress(s.length > 0 ? (int) (s.time * 1000 / s.length) : 0);
        if (!seeking) tvTime.setText(fmt(s.time) + " / " + (s.length > 0 ? fmt(s.length) : "--:--"));
        seekProgress.setEnabled(connected && s.length > 0 && s.seekable);

        if (!held()) {
            seekVolume.setProgress(Math.max(0, Math.min(100, s.volume)));
            seekAudioDelay.setProgress(clamp((int) (s.audioDelay / 50) + 100));
            seekSubDelay.setProgress(clamp((int) (s.spuDelay / 50) + 100));
            int best = 2;
            for (int i = 0; i < SPEEDS.length; i++) {
                if (Math.abs(SPEEDS[i] - s.rate) < Math.abs(SPEEDS[best] - s.rate)) best = i;
            }
            if (spSpeed.getSelectedItemPosition() != best) spSpeed.setSelection(best);
            syncTracks(spAudio, audioIds, s.audioTracks, s.audioTrack, true);
            syncTracks(spSubtitle, spuIds, s.spuTracks, s.spuTrack, false);
        }

        if (!styleSynced) {
            styleSynced = true;
            seekSubSize.setProgress(Math.max(0, Math.min(150, s.subSize - 50)));
            seekSubBg.setProgress(s.subBg * 100 / 255);
            swBold.setChecked(s.subBold);
            swHdr.setChecked(s.hdr);
            spColor.setSelection(indexOf(COLORS, s.subColor, 0));
            spOutline.setSelection(indexOf(OUTLINES, s.subOutline, 2));
        }
    }

    private void syncTracks(Spinner sp, List<Integer> ids, List<TvStatus.Track> tracks, int current, boolean audio) {
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
            sp.setAdapter(adapterOf(names));
        }
        int idx = ids.indexOf(current);
        if (idx >= 0 && sp.getSelectedItemPosition() != idx) sp.setSelection(idx);
    }

    private static int indexOf(int[] arr, int v, int def) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return def;
    }

    private static int clamp(int progress) {
        return Math.max(0, Math.min(200, progress));
    }

    private void setControlsEnabled(boolean enabled) {
        for (View v : controls) v.setEnabled(enabled);
        for (int i = 0; i < results.getChildCount(); i++) results.getChildAt(i).setEnabled(enabled);
    }

    private void updateConnectionText() {
        if (connected) {
            tvConnection.setText("Connected");
            tvConnection.setTextColor(0xFF2E7D32);
        } else {
            tvConnection.setText("Not connected — waiting for the TV app…");
            tvConnection.setTextColor(0xFFC62828);
        }
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    // ------------------------------------------------------ subtitle search

    private void searchSubtitles() {
        final String query = etQuery.getText().toString().trim();
        final String lang = etLang.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Enter a movie or show name", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SubtitleApi.hasApiKey(this)) {
            showAccountDialog();
            return;
        }
        results.removeAllViews();
        addResultRow("Searching…", null);
        new Thread(() -> {
            try {
                final List<SubtitleApi.Result> found = SubtitleApi.search(this, query, lang);
                runOnUiThread(() -> showResults(found));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    results.removeAllViews();
                    addResultRow("Search failed: " + e.getMessage(), null);
                });
            }
        }, "sub-search").start();
    }

    private void showResults(List<SubtitleApi.Result> found) {
        results.removeAllViews();
        if (found.isEmpty()) {
            addResultRow("No subtitles found", null);
            return;
        }
        for (SubtitleApi.Result r : found) addResultRow(r.toString(), r);
    }

    private void addResultRow(String text, final SubtitleApi.Result r) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setPadding(8, 20, 8, 20);
        row.setEnabled(r == null || connected);
        if (r != null) {
            row.setOnClickListener(v -> sendSubtitle(r));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
        }
        results.addView(row);
    }

    private void sendSubtitle(final SubtitleApi.Result r) {
        if (!connected) {
            Toast.makeText(this, "TV app not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Fetching subtitle…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final String link = SubtitleApi.downloadLink(this, r.fileId);
                runOnUiThread(() -> {
                    send("SUB_URL:" + link);
                    Toast.makeText(this, "Subtitle sent to TV", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "sub-download").start();
    }

    private void showAccountDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 16, 48, 0);
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
        new AlertDialog.Builder(this)
                .setTitle("OpenSubtitles.com")
                .setMessage("Searching needs a free API key; downloading needs your account login.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> SubtitleApi.saveCredentials(this,
                        key.getText().toString(), user.getText().toString(), pass.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (client != null) client.stop();
    }

    // -------------------------------------------------------------- helpers

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
        }

        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }

    private abstract static class SimpleSel implements AdapterView.OnItemSelectedListener {
        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }
}
