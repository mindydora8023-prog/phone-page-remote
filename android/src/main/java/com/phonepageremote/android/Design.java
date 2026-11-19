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
 *
 * The page is a Swiss grid: a heavy rule under the masthead, numbered sections
 * split by hairlines, square controls, and one blue primary per section.
 */
final class Design {
    private static final String SANS = "sans-serif";
    private static final String SANS_LIGHT = "sans-serif-light";
    private static final int WHITE = Color.WHITE;

    private final Activity activity;
    private LinearLayout card;
    private LinearLayout cardParent;
    private boolean firstSection = true;

    Design(Activity activity) {
        this.activity = activity;
    }

    int dp(int id) {
        return activity.getResources().getDimensionPixelSize(id);
    }

    int color(int id) {
        return activity.getColor(id);
    }

    /** Full-bleed rule that opens the screen, the Android counterpart of the window's top border. */
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
        view.setLetterSpacing(-0.035f);
        view.setIncludeFontPadding(false);
        view.setMaxLines(2);
        view.setAutoSizeTextTypeUniformWithConfiguration(24, 40, 2, TypedValue.COMPLEX_UNIT_SP);
        if (Build.VERSION.SDK_INT >= 28) {
            view.setAccessibilityHeading(true);
        }
        return view;
    }

    /** The heavy ink rule that closes the masthead and the page. */
    void heavyRule(LinearLayout parent, int topDimen) {
        View view = new View(activity);
        view.setBackgroundColor(color(R.color.rule));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(R.dimen.heavy_rule));
        if (topDimen != 0) {
            params.topMargin = dp(topDimen);
        }
        parent.addView(view, params);
        card = null;
        cardParent = null;
    }

    private View hairline() {
        View view = new View(activity);
        view.setBackgroundColor(color(R.color.line));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(R.dimen.hairline)));
        return view;
    }

    /**
     * Opens a numbered section: hairline above (except the first), a light blue
     * folio, the title and a live marker, then routes the rows that follow into it.
     */
    void section(LinearLayout parent, String folio, int title) {
        if (!firstSection) {
            parent.addView(hairline());
        }
        firstSection = false;
        LinearLayout surface = stack();
        surface.setPadding(0, dp(R.dimen.section_pad), 0, dp(R.dimen.section_pad));
        parent.addView(surface, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = row();
        head.setGravity(Gravity.BOTTOM);
        TextView number = new TextView(activity);
        number.setText(folio);
        number.setTextColor(color(R.color.brand_text));
        number.setTypeface(Typeface.create(SANS_LIGHT, Typeface.NORMAL));
        number.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_folio));
        number.setLetterSpacing(-0.04f);
        number.setIncludeFontPadding(false);
        number.setFontFeatureSettings("tnum");
        number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        numberParams.rightMargin = dp(R.dimen.gap_m);
        head.addView(number, numberParams);

        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(color(R.color.ink));
        heading.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_section));
        heading.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        head.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View dot = new View(activity);
        dot.setBackgroundColor(color(R.color.ok));
        dot.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(R.dimen.dot), dp(R.dimen.dot));
        dotParams.leftMargin = dp(R.dimen.gap_s);
        dotParams.bottomMargin = dp(R.dimen.hairline) * 3;
        head.addView(dot, dotParams);
        surface.setTag(dot);

        LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headParams.bottomMargin = dp(R.dimen.gap_m);
        surface.addView(head, headParams);

        card = surface;
        cardParent = parent;
    }

    /** Unnumbered block that needs an answer now: brand bar on the left, shaded ground. */
    void callout(LinearLayout parent, int title) {
        LinearLayout frame = row();
        frame.setBackgroundColor(color(R.color.shade));
        View bar = new View(activity);
        bar.setBackgroundColor(color(R.color.brand_blue));
        frame.addView(bar, new LinearLayout.LayoutParams(dp(R.dimen.callout_bar), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout surface = stack();
        int pad = dp(R.dimen.gap_l);
        surface.setPadding(pad, pad, pad, pad);
        frame.addView(surface, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(color(R.color.ink));
        heading.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_section));
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        surface.addView(heading);

        card = surface;
        cardParent = parent;
    }

    /** The section the last call opened, so the screen can light its marker later. */
    LinearLayout currentCard() {
        return card;
    }

    /** Green square after the title once that part is actually live. */
    void markCard(LinearLayout surface, boolean live) {
        if (surface == null || !(surface.getTag() instanceof View)) {
            return;
        }
        ((View) surface.getTag()).setVisibility(live ? View.VISIBLE : View.INVISIBLE);
    }

    TextView bodyText(int text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(color(R.color.ink));
        view.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_body));
        view.setLineSpacing(0, 1.4f);
        return view;
    }

    TextView caption(int text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(color(R.color.muted));
        view.setTypeface(Typeface.create(SANS, Typeface.NORMAL));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_caption));
        view.setLineSpacing(0, 1.4f);
        return view;
    }

    /** Six-digit pairing code: light, wide, tabular, in brand blue. */
    TextView pairCode() {
        TextView view = new TextView(activity);
        view.setTextColor(color(R.color.brand_text));
        view.setTypeface(Typeface.create(SANS_LIGHT, Typeface.NORMAL));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_pair_code));
        view.setLetterSpacing(0.08f);
        view.setFontFeatureSettings("tnum");
        view.setIncludeFontPadding(false);
        return view;
    }

    /** State marker: a square plus a word. Hollow and muted when idle, filled and blue otherwise. */
    TextView statusChip() {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_chip));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setSingleLine(true);
        view.setCompoundDrawablePadding(dp(R.dimen.gap_s));
        markStatus(view, false);
        return view;
    }

    void markStatus(TextView chip, boolean emphasized) {
        int tone = color(emphasized ? R.color.brand_text : R.color.muted);
        chip.setTextColor(tone);
        chip.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        GradientDrawable square = shape(emphasized ? tone : Color.TRANSPARENT, tone);
        square.setStroke(dp(R.dimen.heavy_rule), tone);
        int size = dp(R.dimen.marker);
        square.setBounds(0, 0, size, size);
        chip.setCompoundDrawablesRelative(square, null, null, null);
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
        GradientDrawable fill = shape(color(R.color.brand_blue), color(R.color.brand_blue));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.ripple_primary)), fill, null));
        view.setOnClickListener(click);
        // minHeight, not a fixed height: at 200% font scale the label must grow the control.
        view.setMinHeight(dp(R.dimen.control));
        int vertical = dp(R.dimen.gap_s);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    /** Secondary action: square, hairline border, ink label; rows of them read left to right. */
    Button addChip(LinearLayout parent, int label, View.OnClickListener click) {
        Button view = new Button(activity);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(R.dimen.type_caption));
        view.setTextColor(color(R.color.ink));
        view.setTypeface(Typeface.create(SANS, Typeface.BOLD));
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setStateListAnimator(null);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        int horizontal = dp(R.dimen.gap_l);
        GradientDrawable edge = shape(color(R.color.page), color(R.color.line));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.ripple_secondary)), edge, null));
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

    /** Turns a secondary action into the block's one blue answer. */
    void emphasize(Button view) {
        view.setTextColor(WHITE);
        GradientDrawable fill = shape(color(R.color.brand_blue), color(R.color.brand_blue));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.ripple_primary)), fill, null));
    }

    /** The pairing key reads like the Windows keycap: monospaced, brand, on a shaded slab. */
    TextView keyCard() {
        TextView view = new TextView(activity);
        view.setTextColor(color(R.color.brand_text));
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
        LinearLayout target = (card != null && parent == cardParent) ? card : parent;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topDimen != 0) {
            params.topMargin = dp(topDimen);
        }
        target.addView(view, params);
        return view;
    }

    GradientDrawable shape(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(R.dimen.hairline), stroke);
        return drawable;
    }
}
