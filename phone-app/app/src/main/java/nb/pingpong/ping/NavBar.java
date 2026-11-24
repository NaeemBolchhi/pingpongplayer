package nb.pingpong.ping;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
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
 * <p>
 * The highlight is drawn by the row itself, so it can move: every tab has an "on" amount between 0 and 1 (they
 * always add up to 1), and switching tabs animates those amounts. The highlight sits at the on-weighted position
 * of the tabs, so it slides from one tab to the next (and stays sensible if a switch interrupts another).
 */
final class NavBar {
    interface Listener {
        void onSelected(int index);
    }

    private static final long ANIM_MS = 340;

    private final Activity activity;
    private final FrameLayout host;
    private final String[] labels;
    private final int[] icons;
    private final boolean floating, frosted;
    private final Listener listener;
    private final int indicator, onIndicator, onVariant, onSurface;
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private LinearLayout items;
    private int selected = 0;

    // per tab
    private View[] tabs;
    private ImageView[] tabIcons;
    private TextView[] tabLabels;
    private float[] on;
    private ValueAnimator anim;
    private int floatPad;

    NavBar(Activity a, FrameLayout host, String[] labels, int[] icons, boolean floating, boolean frosted,
           boolean generatedColors, Listener listener) {
        this.activity = a;
        this.host = host;
        this.labels = labels;
        this.icons = icons;
        this.floating = floating;
        this.frosted = frosted;
        this.listener = listener;
        int ind = Appearance.navIndicator(a, generatedColors);
        this.indicator = frosted ? ColorUtils.setAlphaComponent(ind, 217) : ind;
        this.onIndicator = Appearance.color(a, "colorOnPrimaryContainer", Color.BLACK);
        this.onVariant = Appearance.color(a, "colorOnSurfaceVariant", Color.DKGRAY);
        this.onSurface = Appearance.color(a, "colorOnSurface", Color.BLACK);
        paint.setColor(indicator);
        on = new float[labels.length];
        on[0] = 1f;
        build();
    }

    /** Space the content should leave free at the bottom so its end can scroll clear of the bar. */
    int reservedHeight() {
        return dp(floating ? 56 + 24 + 16 : 80);
    }

    int selected() {
        return selected;
    }

    /** Selects a tab; animated once the bar is on screen, instantly before that. */
    void select(int index) {
        if (index < 0 || index >= labels.length) return;
        selected = index;
        for (int i = 0; i < tabs.length; i++) tabs[i].setSelected(i == index);
        if (anim != null) anim.cancel();
        if (items.getWidth() == 0) {
            for (int i = 0; i < on.length; i++) on[i] = i == index ? 1f : 0f;
            apply();
            return;
        }
        final float[] from = on.clone();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ANIM_MS);
        anim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)); // Material "emphasized decelerate"
        anim.addUpdateListener(va -> {
            float f = (float) va.getAnimatedValue();
            for (int i = 0; i < on.length; i++) on[i] = from[i] + ((i == index ? 1f : 0f) - from[i]) * f;
            apply();
        });
        anim.start();
    }

    private void build() {
        host.removeAllViews();
        int surface = Appearance.color(activity, floating ? "colorSurfaceContainerHigh" : "colorSurfaceContainer",
                Color.WHITE);
        int outlineVariant = Appearance.color(activity, "colorOutlineVariant", Color.LTGRAY);
        items = new LinearLayout(activity) {
            @Override
            protected void dispatchDraw(Canvas c) {
                drawIndicator(c); // under the icons and labels
                super.dispatchDraw(c);
            }
        };
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
            FrameLayout p = new FrameLayout(activity);
            p.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                }
            });
            p.setClipToOutline(true);
            p.setElevation(dp(8));
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(dp(28));
            border.setColor(Color.TRANSPARENT);
            // the same hairline as the solid pill, softer over the blur
            border.setStroke(dp(1), frosted ? ColorUtils.setAlphaComponent(outlineVariant, 110) : outlineVariant);
            p.setForeground(border);
            if (frosted) {
                BlurView blur = blurView(ColorUtils.setAlphaComponent(
                        Appearance.color(activity, "colorSurface", Color.WHITE), 128));
                blur.addView(items, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
                p.addView(blur, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
            } else {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(28));
                bg.setColor(surface);
                p.setBackground(bg);
                p.addView(items, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(24);
            host.addView(p, lp);
            // The pill's width doesn't change when the highlight moves (one icon shrinks as the other grows).
            // Pin it after the first layout, so rounding can't nudge it a pixel per frame (and make the blur
            // rebuild its buffers while animating).
            p.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    if (r - l <= 0) return;
                    v.removeOnLayoutChangeListener(this);
                    ViewGroup.LayoutParams plp = v.getLayoutParams();
                    plp.width = r - l;
                    v.setLayoutParams(plp);
                    // inside, fill the pinned width and keep the tabs centred
                    ViewGroup inner = (ViewGroup) ((ViewGroup) v).getChildAt(0);
                    inner.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                    if (inner != items) items.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                    items.setGravity(Gravity.CENTER);
                    inner.requestLayout();
                }
            });
        }
        createTabs();
        apply();
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

    /** Creates the tab views once; {@link #apply()} then styles them for the current "on" amounts. */
    private void createTabs() {
        int n = labels.length;
        tabs = new View[n];
        tabIcons = new ImageView[n];
        tabLabels = new TextView[n];
        // narrow phones: keep the floating pill inside the screen
        float widthDp = activity.getResources().getDisplayMetrics().widthPixels
                / activity.getResources().getDisplayMetrics().density;
        float floatText = widthDp < 340 ? 12f : widthDp < 392 ? 13f : 14f;
        floatPad = widthDp < 392 ? 4 : 6;
        for (int i = 0; i < n; i++) {
            final int index = i;
            View item;
            ImageView icon = icon(i);
            if (!floating) {
                LinearLayout col = new LinearLayout(activity);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER);
                FrameLayout pillBox = new FrameLayout(activity); // the highlight is drawn behind this box
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(24), dp(24));
                ilp.gravity = Gravity.CENTER;
                pillBox.addView(icon, ilp);
                col.addView(pillBox, new LinearLayout.LayoutParams(dp(64), dp(32)));
                TextView label = label(i, 12f);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.topMargin = dp(4);
                col.addView(label, llp);
                item = col;
                tabLabels[i] = label;
                items.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            } else {
                // [icon][label]; the icon's width animates between 0 and 24dp
                LinearLayout cap = new LinearLayout(activity);
                cap.setOrientation(LinearLayout.HORIZONTAL);
                cap.setGravity(Gravity.CENTER);
                cap.setMinimumWidth(dp(44));
                cap.addView(icon, new LinearLayout.LayoutParams(0, dp(24)));
                TextView label = label(i, floatText);
                cap.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                item = cap;
                tabLabels[i] = label;
                items.addView(cap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            }
            tabs[i] = item;
            tabIcons[i] = icon;
            item.setContentDescription(labels[i]);
            item.setSelected(i == selected);
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

    /** Styles every tab for its current "on" amount (0 = plain, 1 = selected). */
    private void apply() {
        boolean relayout = false;
        for (int i = 0; i < tabs.length; i++) {
            float s = on[i];
            ImageView icon = tabIcons[i];
            TextView label = tabLabels[i];
            if (!floating) {
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(blend(onVariant, onIndicator, s)));
                label.setTextColor(blend(onVariant, onSurface, s));
                continue;
            }
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(blend(onVariant, onIndicator, s)));
            label.setTextColor(blend(onVariant, onIndicator, s));
            icon.setAlpha(s);
            float scale = 0.5f + 0.5f * s;
            icon.setScaleX(scale);
            icon.setScaleY(scale);
            LinearLayout.LayoutParams ilp = (LinearLayout.LayoutParams) icon.getLayoutParams();
            int w = Math.round(dp(24) * s);
            int gap = Math.round(dp(6) * s);
            if (ilp.width != w || ilp.getMarginEnd() != gap) {
                ilp.width = w;
                ilp.setMarginEnd(gap);
                icon.setLayoutParams(ilp);
                relayout = true;
            }
            View t = tabs[i];
            int ps = Math.round(dp(floatPad) + (dp(12) - dp(floatPad)) * s);
            int pe = Math.round(dp(floatPad) + (dp(16) - dp(floatPad)) * s);
            if (t.getPaddingStart() != ps || t.getPaddingEnd() != pe) {
                t.setPaddingRelative(ps, 0, pe, 0);
                relayout = true;
            }
        }
        if (relayout) items.requestLayout();
        items.invalidate();
    }

    /** The highlight: the floating capsule, or the bottom bar's pill behind the icon. */
    private void drawIndicator(Canvas c) {
        if (tabs == null || tabs.length == 0) return;
        if (floating) {
            // the capsule spans the on-weighted edges of the tabs, so it slides and reshapes as they change
            float l = 0, r = 0;
            for (int i = 0; i < tabs.length; i++) {
                l += on[i] * tabs[i].getLeft();
                r += on[i] * tabs[i].getRight();
            }
            float top = tabs[0].getTop(), bottom = tabs[0].getBottom();
            if (r - l < 1) return;
            rect.set(l, top, r, bottom);
            float rad = (bottom - top) / 2f;
            c.drawRoundRect(rect, rad, rad, paint);
            return;
        }
        // bottom bar: the pill flies between icons and stretches a little mid-flight
        float cx = 0, extra = 0;
        float[] centers = new float[tabs.length];
        View box0 = ((ViewGroup) tabs[0]).getChildAt(0);
        for (int i = 0; i < tabs.length; i++) {
            View box = ((ViewGroup) tabs[i]).getChildAt(0);
            centers[i] = tabs[i].getLeft() + box.getLeft() + box.getWidth() / 2f;
            cx += on[i] * centers[i];
        }
        for (int i = 0; i < tabs.length; i++) {
            for (int j = i + 1; j < tabs.length; j++) {
                extra += 4f * on[i] * on[j] * Math.abs(centers[i] - centers[j]) * 0.3f;
            }
        }
        extra = Math.min(extra, dp(48));
        float w = box0.getWidth() + extra, h = box0.getHeight();
        float top = tabs[0].getTop() + box0.getTop();
        if (w < 1) return;
        rect.set(cx - w / 2f, top, cx + w / 2f, top + h);
        c.drawRoundRect(rect, h / 2f, h / 2f, paint);
    }

    private int blend(int from, int to, float s) {
        return (Integer) argb.evaluate(Math.max(0f, Math.min(1f, s)), from, to);
    }

    private ImageView icon(int i) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(icons[i]);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private TextView label(int i, float sp) {
        TextView t = new TextView(activity);
        t.setText(labels[i]);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        // one weight for all states, so a label never changes width (or jumps) as it becomes selected
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
