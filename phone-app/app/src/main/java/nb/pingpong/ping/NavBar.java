package nb.pingpong.ping;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;

import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderScriptBlur;

/**
 * The remote's tab bar, in the style chosen in Settings:
 * <ul>
 * <li>Bottom: a Material navigation bar along the bottom edge, every tab with icon and label;</li>
 * <li>Floating: a pill above the bottom edge (Google Photos style) where only the selected tab shows its icon
 * on a highlight, the others are plain labels;</li>
 * </ul>
 * each either solid or frosted (blurring the content scrolling behind it).
 */
final class NavBar {
    interface Listener {
        void onSelected(int index);
    }

    private final Activity activity;
    private final FrameLayout host;
    private final String[] labels;
    private final int[] icons;
    private final boolean floating, frosted;
    private final Listener listener;
    private final int indicator, onIndicator, onVariant, onSurface;
    private LinearLayout items;
    private int selected = 0;

    NavBar(Activity a, FrameLayout host, String[] labels, int[] icons, boolean floating, boolean frosted,
           boolean generatedColors, Listener listener) {
        this.activity = a;
        this.host = host;
        this.labels = labels;
        this.icons = icons;
        this.floating = floating;
        this.frosted = frosted;
        this.listener = listener;
        this.indicator = Appearance.navIndicator(a, generatedColors);
        this.onIndicator = Appearance.color(a, "colorOnPrimaryContainer", Color.BLACK);
        this.onVariant = Appearance.color(a, "colorOnSurfaceVariant", Color.DKGRAY);
        this.onSurface = Appearance.color(a, "colorOnSurface", Color.BLACK);
        build();
    }

    /** Space the content should leave free at the bottom so its end can scroll clear of the bar. */
    int reservedHeight() {
        return dp(floating ? 56 + 24 + 16 : 80);
    }

    int selected() {
        return selected;
    }

    void select(int index) {
        if (index < 0 || index >= labels.length) return;
        selected = index;
        fill();
    }

    private void build() {
        host.removeAllViews();
        int surface = Appearance.color(activity, floating ? "colorSurfaceContainerHigh" : "colorSurfaceContainer",
                Color.WHITE);
        int outlineVariant = Appearance.color(activity, "colorOutlineVariant", Color.LTGRAY);
        items = new LinearLayout(activity);
        items.setOrientation(LinearLayout.HORIZONTAL);
        items.setGravity(Gravity.CENTER_VERTICAL);

        if (!floating) {
            items.setPadding(dp(8), 0, dp(8), 0);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80));
            lp.gravity = Gravity.BOTTOM;
            if (frosted) {
                BlurView blur = blurView(ColorUtils.setAlphaComponent(surface, 158));
                blur.addView(items, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                FrameLayout wrap = new FrameLayout(activity);
                wrap.addView(blur, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                View line = new View(activity);
                line.setBackgroundColor(ColorUtils.setAlphaComponent(outlineVariant, 150));
                wrap.addView(line, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                host.addView(wrap, lp);
            } else {
                items.setBackgroundColor(surface);
                host.addView(items, lp);
            }
        } else {
            items.setPadding(dp(6), 0, dp(6), 0);
            FrameLayout pill = new FrameLayout(activity);
            pill.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                }
            });
            pill.setClipToOutline(true);
            pill.setElevation(dp(8));
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(dp(28));
            border.setColor(Color.TRANSPARENT);
            border.setStroke(dp(1), frosted ? ColorUtils.setAlphaComponent(Color.WHITE, 190) : outlineVariant);
            pill.setForeground(border);
            if (frosted) {
                BlurView blur = blurView(ColorUtils.setAlphaComponent(
                        Appearance.color(activity, "colorSurface", Color.WHITE), 128));
                blur.addView(items, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
                pill.addView(blur, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
            } else {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(28));
                bg.setColor(surface);
                pill.setBackground(bg);
                pill.addView(items, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(24);
            host.addView(pill, lp);
        }
        fill();
    }

    private BlurView blurView(int overlay) {
        BlurView blur = new BlurView(activity);
        ViewGroup root = activity.findViewById(android.R.id.content);
        blur.setupWith(root, new RenderScriptBlur(activity))
                .setFrameClearDrawable(activity.getWindow().getDecorView().getBackground())
                .setBlurRadius(20f)
                .setOverlayColor(overlay);
        return blur;
    }

    /** (Re)creates the tab views for the current selection. */
    private void fill() {
        items.removeAllViews();
        int ind = frosted ? ColorUtils.setAlphaComponent(indicator, 217) : indicator;
        // narrow phones: keep the floating pill inside the screen
        float widthDp = activity.getResources().getDisplayMetrics().widthPixels
                / activity.getResources().getDisplayMetrics().density;
        float floatText = widthDp < 340 ? 12f : widthDp < 392 ? 13f : 14f;
        int floatPad = widthDp < 392 ? 4 : 6;
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean on = i == selected;
            View item;
            if (!floating) {
                LinearLayout col = new LinearLayout(activity);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER);
                FrameLayout pillBox = new FrameLayout(activity);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(16));
                g.setColor(on ? ind : Color.TRANSPARENT);
                pillBox.setBackground(g);
                ImageView icon = icon(i, on ? onIndicator : onVariant);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(24), dp(24));
                ilp.gravity = Gravity.CENTER;
                pillBox.addView(icon, ilp);
                col.addView(pillBox, new LinearLayout.LayoutParams(dp(64), dp(32)));
                TextView label = label(i, 12f, on ? onSurface : onVariant, on);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.topMargin = dp(4);
                col.addView(label, llp);
                item = col;
                items.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            } else if (on) {
                LinearLayout cap = new LinearLayout(activity);
                cap.setOrientation(LinearLayout.HORIZONTAL);
                cap.setGravity(Gravity.CENTER_VERTICAL);
                cap.setPadding(dp(12), 0, dp(16), 0);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(22));
                g.setColor(ind);
                cap.setBackground(g);
                cap.addView(icon(i, onIndicator), new LinearLayout.LayoutParams(dp(24), dp(24)));
                TextView label = label(i, floatText, onIndicator, true);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.setMarginStart(dp(6));
                cap.addView(label, llp);
                item = cap;
                items.addView(cap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            } else {
                TextView label = label(i, floatText, onVariant, false);
                label.setGravity(Gravity.CENTER);
                label.setPadding(dp(floatPad), 0, dp(floatPad), 0);
                label.setMinWidth(dp(44));
                item = label;
                items.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            }
            item.setContentDescription(labels[i]);
            item.setSelected(on);
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> {
                if (index != selected) {
                    select(index);
                    listener.onSelected(index);
                }
            });
        }
    }

    private ImageView icon(int i, int color) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(icons[i]);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color));
        return icon;
    }

    private TextView label(int i, float sp, int color, boolean strong) {
        TextView t = new TextView(activity);
        t.setText(labels[i]);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setTypeface(Typeface.create("sans-serif-medium", strong ? Typeface.BOLD : Typeface.NORMAL));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
