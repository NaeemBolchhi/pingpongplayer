package nb.pingpong.ping;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.TypedValue;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

import java.lang.reflect.Method;

/**
 * The phone app's look, as chosen in Settings: navigation bar style and appearance, and the accent colour
 * (a preset, a custom colour, or the device's Material You colours). Every screen calls {@link #apply} before
 * {@code setContentView}; layouts and code then read colours from the theme.
 */
final class Appearance {
    static final String PREFS = "ping_settings";

    static final String[] PRESETS = {"ember", "ocean", "forest", "grape", "rose", "teal"};
    static final String[] PRESET_NAMES = {"Ember", "Ocean", "Forest", "Grape", "Rose", "Teal"};
    static final int[] PRESET_COLORS = {0xFFA43E00, 0xFF00629E, 0xFF2E6B2F, 0xFF6B4FA0, 0xFFA0335B, 0xFF006A66};
    private static final int[] OVERLAYS = {0, R.style.ThemeOverlay_Ping_Ocean, R.style.ThemeOverlay_Ping_Forest,
            R.style.ThemeOverlay_Ping_Grape, R.style.ThemeOverlay_Ping_Rose, R.style.ThemeOverlay_Ping_Teal};
    static final String CUSTOM = "custom";

    private Appearance() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean floating(Context c) {
        return "floating".equals(prefs(c).getString("nav_style", "bottom"));
    }

    static boolean frosted(Context c) {
        return "frosted".equals(prefs(c).getString("nav_look", "solid"));
    }

    static boolean deviceColors(Context c) {
        return prefs(c).getBoolean("device_colors", false);
    }

    static String accent(Context c) {
        return prefs(c).getString("accent", PRESETS[0]);
    }

    static int customColor(Context c) {
        return prefs(c).getInt("accent_custom", PRESET_COLORS[0]);
    }

    /** Material You is offered from Android 12 where the device supports it. */
    static boolean deviceColorsAvailable() {
        return DynamicColors.isDynamicColorAvailable();
    }

    /** A custom accent uses Material's colour engine, which needs dynamic colour support and a recent library. */
    static boolean customAvailable() {
        return deviceColorsAvailable() && contentSourceMethod() != null;
    }

    /**
     * Applies the chosen colours to {@code a}; call before setContentView. Returns true when the colours were
     * generated at runtime (device or custom colours), false for a preset.
     */
    static boolean apply(Activity a) {
        if (deviceColors(a) && deviceColorsAvailable()) {
            DynamicColors.applyToActivityIfAvailable(a);
            return true;
        }
        String acc = accent(a);
        if (CUSTOM.equals(acc) && customAvailable()) {
            try {
                DynamicColorsOptions.Builder b = new DynamicColorsOptions.Builder();
                contentSourceMethod().invoke(b, customColor(a));
                DynamicColors.applyToActivityIfAvailable(a, b.build());
                return true;
            } catch (Throwable ignored) {
                // fall back to the default preset below
            }
        }
        for (int i = 1; i < PRESETS.length; i++) {
            if (PRESETS[i].equals(acc)) {
                a.getTheme().applyStyle(OVERLAYS[i], true);
                break;
            }
        }
        return false;
    }

    private static Method contentSourceMethod() {
        try {
            return DynamicColorsOptions.Builder.class.getMethod("setContentBasedSource", int.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A theme colour by attribute name (names keep this independent of which library declares the attribute). */
    static int color(Context c, String attr, int fallback) {
        Resources r = c.getResources();
        int id = r.getIdentifier(attr, "attr", c.getPackageName());
        if (id == 0) return fallback;
        TypedValue tv = new TypedValue();
        if (!c.getTheme().resolveAttribute(id, tv, true)) return fallback;
        if (tv.resourceId != 0) {
            try {
                return androidx.core.content.ContextCompat.getColor(c, tv.resourceId);
            } catch (Resources.NotFoundException ignored) {
                return tv.data;
            }
        }
        return tv.data;
    }

    /** The selected-tab colour. Presets define it; runtime-generated colours derive it from the accent. */
    static int navIndicator(Context c, boolean generated) {
        int container = color(c, "colorPrimaryContainer", 0xFFFFDBCC);
        if (generated) return ColorUtils.blendARGB(container, color(c, "colorPrimary", 0xFFA43E00), 0.14f);
        return color(c, "pingNavIndicator", 0xFFFFC9AF);
    }
}
