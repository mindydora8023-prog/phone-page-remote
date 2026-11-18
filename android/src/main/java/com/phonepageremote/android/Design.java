package com.phonepageremote.android;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Every view the setup screen shows, so the screen itself stays behaviour only.
 *
 * Colours and distances come from res/values/{colors,dimens}.xml, which mirror
 * windows/ui/style.css; values-night/colors.xml carries the same palette in dark.
 * Nothing here hard-codes a colour, a size or a spacing.
 */
final class Design {
    private static final String SANS = "sans-serif";
    private static final int WHITE = Color.WHITE;

    private final Activity activity;

    Design(Activity activity) {
        this.activity = activity;
    }

    int dp(int id) {
        return activity.getResources().getDimensionPixelSize(id);
    }

    int color(int id) {
        return activity.getColor(id);
    }

    /** Full-bleed rule that opens the screen, the Android counterpart of the masthead border. */
    View brandRule() {
        View view = new View(activity);
        view.setBackgroundColor(color(R.color.brand_blue));
        return view;
    }

    /** Full-bleed vertical container without padding. */
    LinearLayout stack() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    /** Padded, centred column: on wide screens it keeps a readable measure. */
    LinearLayout column(int screenWidth) {
        LinearLayout view = stack();
        int gutter = dp(R.dimen.gutter);
        view.setPadding(gutter, dp(R.dimen.masthead_top), gutter, dp(R.dimen.bottom_space));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                Math.min(screenWidth, dp(R.dimen.measure)), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        view.setLayoutParams(params);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    /** Display heading; shrinks with the available width so large font scales still fit. */
    TextView display(int text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(color(R.color.ink));
        view.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_display));
        view.setLetterSpacing(-0.03f);
        view.setLineSpacing(0, 1.06f);
        view.setAutoSizeTextTypeUniformWithConfiguration(20, 34, 2, TypedValue.COMPLEX_UNIT_SP);
        if (Build.VERSION.SDK_INT >= 28) {
            view.setAccessibilityHeading(true);
        }
        return view;
    }

    View rule(int topDimen) {
        View view = new View(activity);
        view.setBackgroundColor(color(R.color.line));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(R.dimen.hairline));
        if (topDimen != 0) {
            params.topMargin = dp(topDimen);
        }
        view.setLayoutParams(params);
        return view;
    }

    /** Hairline + heading: the page's rule/h2 pair. */
    void section(LinearLayout parent, int title) {
        parent.addView(rule(R.dimen.section_gap));
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(color(R.color.ink));
        heading.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_section));
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        add(parent, heading, R.dimen.title_gap);
    }

    TextView bodyText(int text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(color(R.color.muted));
        view.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_body));
        view.setLineSpacing(0, 1.35f);
        return view;
    }

    TextView caption(int text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(color(R.color.muted));
        view.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_caption));
        view.setLineSpacing(0, 1.35f);
        return view;
    }

    /** State chip: idle is quiet, anything that needs attention turns brand and bold. */
    TextView statusChip() {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_chip));
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        int pad = dp(R.dimen.gap_s);
        view.setPadding(pad, pad, pad, pad);
        markStatus(view, false);
        return view;
    }

    void markStatus(TextView chip, boolean emphasized) {
        chip.setTextColor(color(emphasized ? R.color.brand_blue : R.color.muted));
        chip.setTypeface(Typeface.create(SANS, emphasized ? Typeface.BOLD : Typeface.NORMAL));
        chip.setBackground(shape(color(R.color.paper),
                color(emphasized ? R.color.brand_blue : R.color.line)));
    }

    Button primary(int label, View.OnClickListener click) {
        Button view = new Button(activity);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_body));
        view.setTextColor(WHITE);
        view.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setStateListAnimator(null);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        int horizontal = dp(R.dimen.gap_l);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.ripple_primary)),
                shape(color(R.color.brand_blue), color(R.color.brand_blue)), null));
        view.setOnClickListener(click);
        // minHeight, not a fixed height: at 200% font scale the label must grow the control.
        view.setMinHeight(dp(R.dimen.control));
        int vertical = dp(R.dimen.gap_s);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    /** Compact secondary action, aligned right the way the page puts its footer buttons. */
    Button addChip(LinearLayout parent, int label, View.OnClickListener click) {
        Button view = new Button(activity);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_caption));
        view.setTextColor(color(R.color.brand_blue));
        view.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setStateListAnimator(null);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        int horizontal = dp(R.dimen.gap_m);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.ripple_secondary)),
                shape(color(R.color.paper), color(R.color.line)), null));
        view.setOnClickListener(click);
        view.setMinHeight(dp(R.dimen.chip_height));
        int vertical = dp(R.dimen.gap_s);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (parent.getChildCount() > 0) {
            params.leftMargin = dp(R.dimen.gap_s);
        }
        params.topMargin = dp(R.dimen.gap_s);
        parent.addView(view, params);
        return view;
    }

    /** The pairing key reads like the Windows keycap: monospaced, brand, boxed. */
    TextView keyCard() {
        TextView view = new TextView(activity);
        view.setTextColor(color(R.color.brand_blue));
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_key));
        view.setLetterSpacing(0.04f);
        view.setFontFeatureSettings("tnum");
        view.setTextIsSelectable(true);
        int horizontal = dp(R.dimen.gap_l);
        int vertical = dp(R.dimen.gap_m);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        view.setBackground(shape(color(R.color.shade), color(R.color.line)));
        return view;
    }

    <T extends View> T add(LinearLayout parent, T view, int topDimen) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topDimen != 0) {
            params.topMargin = dp(topDimen);
        }
        parent.addView(view, params);
        return view;
    }

    GradientDrawable shape(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(R.dimen.hairline), stroke);
        return drawable;
    }
}
