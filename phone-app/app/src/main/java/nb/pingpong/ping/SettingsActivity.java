package nb.pingpong.ping;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

/** Settings, reached only from the three-dot menu: navigation bar style and look, colours, about. */
public class SettingsActivity extends AppCompatActivity {
    private boolean applying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Appearance.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        applying = true;
        MaterialButtonToggleGroup style = findViewById(R.id.nav_style);
        style.check(Appearance.floating(this) ? R.id.nav_style_floating : R.id.nav_style_bottom);
        style.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked || applying) return;
            Appearance.prefs(this).edit().putString("nav_style", id == R.id.nav_style_floating ? "floating" : "bottom").apply();
        });

        MaterialButtonToggleGroup look = findViewById(R.id.nav_look);
        look.check(Appearance.frosted(this) ? R.id.nav_look_frosted : R.id.nav_look_solid);
        look.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked || applying) return;
            Appearance.prefs(this).edit().putString("nav_look", id == R.id.nav_look_frosted ? "frosted" : "solid").apply();
        });

        View deviceRow = findViewById(R.id.row_device_colors);
        MaterialSwitch device = findViewById(R.id.sw_device_colors);
        if (!Appearance.deviceColorsAvailable()) {
            deviceRow.setVisibility(View.GONE);
        } else {
            device.setChecked(Appearance.deviceColors(this));
            deviceRow.setOnClickListener(v -> device.toggle());
            device.setOnCheckedChangeListener((b, on) -> {
                if (applying) return;
                Appearance.prefs(this).edit().putBoolean("device_colors", on).apply();
                recreate();
            });
        }
        buildSwatches();
        applying = false;

        View about = findViewById(R.id.row_about);
        String repo = BuildConfig.UPDATE_REPO;
        if (repo == null || repo.isEmpty()) {
            // local builds don't know which repository they came from
            about.setVisibility(View.GONE);
            findViewById(R.id.header_about).setVisibility(View.GONE);
        } else {
            about.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + repo)));
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void buildSwatches() {
        LinearLayout row = findViewById(R.id.accent_swatches);
        row.removeAllViews();
        boolean locked = Appearance.deviceColors(this) && Appearance.deviceColorsAvailable();
        String current = Appearance.accent(this);
        int size = dp(40), gap = dp(12);
        for (int i = 0; i < Appearance.PRESETS.length; i++) {
            final String key = Appearance.PRESETS[i];
            View sw = swatch(Appearance.PRESET_COLORS[i], key.equals(current), Appearance.PRESET_NAMES[i]);
            sw.setOnClickListener(v -> pick(key));
            row.addView(sw, params(size, i == 0 ? 0 : gap));
        }
        if (Appearance.customAvailable()) {
            boolean on = Appearance.CUSTOM.equals(current);
            View custom = on ? swatch(Appearance.customColor(this), true, "Custom color")
                    : plusSwatch();
            custom.setOnClickListener(v -> showCustomPicker());
            row.addView(custom, params(size, gap));
        }
        // With device colors on, the accent comes from the wallpaper.
        row.setAlpha(locked ? 0.38f : 1f);
        for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).setEnabled(!locked);
    }

    private LinearLayout.LayoutParams params(int size, int start) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart(start);
        return lp;
    }

    private View swatch(int color, boolean selected, String name) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (selected) g.setStroke(dp(3), Appearance.color(this, "colorOnSurface", Color.BLACK));
        f.setBackground(g);
        f.setContentDescription(name + (selected ? ", selected" : ""));
        if (selected) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(ColorUtils.calculateLuminance(color) > 0.5 ? Color.BLACK : Color.WHITE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(20), dp(20));
            lp.gravity = android.view.Gravity.CENTER;
            f.addView(check, lp);
        }
        return f;
    }

    private View plusSwatch() {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(1), Appearance.color(this, "colorOutline", Color.GRAY), dp(4), dp(3));
        f.setBackground(g);
        f.setContentDescription("Custom color");
        ImageView plus = new ImageView(this);
        plus.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_plus));
        plus.setColorFilter(Appearance.color(this, "colorOnSurfaceVariant", Color.GRAY));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(20), dp(20));
        lp.gravity = android.view.Gravity.CENTER;
        f.addView(plus, lp);
        return f;
    }

    private void pick(String key) {
        if (key.equals(Appearance.accent(this))) return;
        Appearance.prefs(this).edit().putString("accent", key).apply();
        recreate();
    }

    /** A hue slider with a live preview; saturation and lightness stay in a range that reads well as an accent. */
    private void showCustomPicker() {
        float[] hsv = new float[3];
        Color.colorToHSV(Appearance.customColor(this), hsv);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        final View preview = new View(this);
        final GradientDrawable pg = new GradientDrawable();
        pg.setCornerRadius(dp(16));
        preview.setBackground(pg);
        box.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        final Slider hue = new Slider(this);
        hue.setValueFrom(0f);
        hue.setValueTo(359f);
        hue.setStepSize(1f);
        hue.setValue(Math.round(Math.max(0f, Math.min(359f, hsv[0]))));
        hue.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
        hue.setContentDescription("Hue");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.addView(hue, lp);
        final int[] chosen = {hueColor(hue.getValue())};
        pg.setColor(chosen[0]);
        hue.addOnChangeListener((s, v, fromUser) -> {
            chosen[0] = hueColor(v);
            pg.setColor(chosen[0]);
        });
        new MaterialAlertDialogBuilder(this)
                .setTitle("Custom accent")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use", (d, w) -> {
                    Appearance.prefs(this).edit().putString("accent", Appearance.CUSTOM)
                            .putInt("accent_custom", chosen[0]).apply();
                    recreate();
                })
                .show();
    }

    private static int hueColor(float hue) {
        return Color.HSVToColor(new float[]{hue, 0.85f, 0.62f});
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
