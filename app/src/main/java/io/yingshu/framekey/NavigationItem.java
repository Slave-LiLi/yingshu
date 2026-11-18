package io.yingshu.framekey;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A single focus target, including its icon, optional status and current-page mark. */
final class NavigationItem extends LinearLayout {
    private final ThemePalette palette;
    private final String title;
    private final TextView name, detail;
    private final ImageView mark;
    private final NavigationGlyph symbol;
    private final GradientDrawable surface, plate, railFill;
    private final View rail;
    private final RippleDrawable feedback;
    private final int focusWidth;
    private float progress;
    private ValueAnimator animator;

    NavigationItem(Context context, ThemePalette palette, int destination, String title) {
        super(context);
        this.palette = palette;
        this.title = title;
        focusWidth = dp(2);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(58));
        setPaddingRelative(dp(6), dp(9), dp(12), dp(9));
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);

        surface = new LiveGradientDrawable();
        surface.setCornerRadius(dp(12));
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(12));
        mask.setColor(Color.WHITE);
        feedback = new RippleDrawable(ColorStateList.valueOf(Color.TRANSPARENT), surface, mask);
        setBackground(feedback);
        MotionFeedback.attach(this);

        rail = new View(context);
        rail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        railFill = new LiveGradientDrawable();
        railFill.setCornerRadius(dp(2));
        rail.setBackground(railFill);
        LayoutParams railParams = new LayoutParams(dp(3), dp(24));
        railParams.setMarginEnd(dp(3));
        addView(rail, railParams);

        ImageView icon = new ImageView(context);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        symbol = new NavigationGlyph(palette, destination, dp(23));
        icon.setImageDrawable(symbol);
        plate = new LiveGradientDrawable();
        plate.setCornerRadius(dp(11));
        icon.setBackground(plate);
        addView(icon, new LayoutParams(dp(38), dp(38)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(VERTICAL);
        labels.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        name = new TextView(context);
        name.setText(title);
        name.setTextSize(16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        detail = new TextView(context);
        detail.setTextSize(12);
        detail.setVisibility(GONE);
        labels.addView(name);
        labels.addView(detail);
        LayoutParams labelParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        labelParams.setMarginStart(dp(12));
        labelParams.setMarginEnd(dp(6));
        addView(labels, labelParams);

        mark = new ImageView(context);
        mark.setScaleType(ImageView.ScaleType.CENTER);
        mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        NavigationGlyph check = new NavigationGlyph(palette, NavigationGlyph.CHECK, dp(19));
        check.setSelection(1);
        mark.setImageDrawable(check);
        mark.setAlpha(0f);
        addView(mark, new LayoutParams(dp(20), dp(24)));
        setContentDescription(title);
        setOnFocusChangeListener((v, focused) -> paintSelection());
        palette.bind(this, (item, p) -> item.paintTheme());
    }

    @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }

    void setDetail(String value) {
        if (value.contentEquals(detail.getText())) return;
        detail.setText(value);
        detail.setVisibility(value.isEmpty() ? GONE : VISIBLE);
        setContentDescription(value.isEmpty() ? title : title + "，" + value);
    }

    void setCurrent(boolean current, boolean animate, Interpolator ease) {
        if (animator != null) animator.cancel();
        setSelected(current);
        float target = current ? 1f : 0f;
        if (!animate || progress == target) {
            progress = target;
            paintSelection();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(160);
        animator.setInterpolator(ease);
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            paintSelection();
        });
        animator.start();
    }

    private void paintTheme() {
        name.setTextColor(palette.color(ThemePalette.FG));
        detail.setTextColor(palette.color(ThemePalette.MUTED));
        feedback.setColor(ColorStateList.valueOf(palette.color(ThemePalette.RIPPLE)));
        railFill.setColor(palette.color(ThemePalette.ACCENT_TEXT));
        paintSelection();
    }

    private void paintSelection() {
        int drawer = palette.color(ThemePalette.DRAWER);
        int selected = palette.color(ThemePalette.SELECTED);
        surface.setColor(ThemePalette.blend(drawer, selected, progress));
        surface.setStroke(focusWidth, hasFocus() ? palette.color(ThemePalette.FOCUS) : Color.TRANSPARENT);
        plate.setColor(ThemePalette.blend(drawer, palette.color(ThemePalette.CARD), progress * .85f));
        symbol.setSelection(progress);
        rail.setScaleY(.5f + .5f * progress);
        rail.setAlpha(progress);
        mark.setAlpha(progress);
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
