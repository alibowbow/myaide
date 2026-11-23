package dev.jun.gyeot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.app.Dialog;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The design system, in code: "Paper & Ink". Toss-like clarity — bold sans titles, generous whitespace, flat white
 * surfaces on a quiet warm-gray canvas, no outlines — with Apple's structure (grouped lists, segmented controls, sheets,
 * a floating tab bar) and one warm terracotta reserved for what can be pressed. A light and dark palette that follows the
 * phone, a named type scale and radius scale, and the controls built from them. One place for every color, radius and spacing.
 */
final class Ui {
    /** The palette for the current light or dark mode; set when an activity creates its Ui. BUBBLE_FILL is the user's own chat bubble. */
    static int BG, CARD, RAISED, FILL, TEXT, MUTED, ACCENT, ACCENT_SOFT, ON_ACCENT, ERROR, SUCCESS, WARNING, SEPARATOR, GLASS, BUBBLE_FILL;
    static boolean DARK;
    /** Hues for icon tiles and status. Tiles are tonal (a soft wash of the hue under a glyph in the hue), tuned per mode
     * so a glyph keeps 3:1 or more on its wash. Names are kept from the earlier iOS set so callers read the same. */
    static int GREEN, TEAL, BLUE, INDIGO, PURPLE, PINK, RED, ORANGE, GRAY, MINT;
    /** Type scale (sp). Bold, tight titles and calm body text, as in Toss and the iOS text styles. */
    static final float LARGE_TITLE = 30, TITLE1 = 24, TITLE2 = 21, HEADLINE = 17, BODY = 16, CALLOUT = 15, SUBHEAD = 14, FOOTNOTE = 13, CAPTION = 12;
    /** Corner radii (dp): controls, rows, cards, sheets; PILL is a full capsule. */
    static final int R_CONTROL = 14, R_BUTTON = 16, R_GROUP = 20, R_CARD = 22, R_SHEET = 28, PILL = 99;
    /** The widest a conversation's text column and its composer get (dp). A wider window centers them between equal gutters instead of stretching the lines. */
    static final int READING_WIDTH = 680;
    private final Context context;
    private final float density;

    Ui(Context context) {
        this.context = context; density = context.getResources().getDisplayMetrics().density;
        DARK = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // Paper & Ink: pure white surfaces on a warm off-white canvas; ink text; one terracotta. Separation comes from tone and
        // spacing, not outlines. Text keeps 4.5:1 or more where it sits (TEXT ~17:1, MUTED ~5.6:1 on CARD and 4.5:1 on FILL,
        // ACCENT 5:1 on CARD). Dark mode is a deep warm charcoal; ON_ACCENT is ink there because the lighter coral needs it.
        // values/colors.xml and values-night/colors.xml repeat BG and ACCENT so the launch window matches.
        if (DARK) {
            BG = 0xff121110; CARD = 0xff1d1c1a; RAISED = 0xff34332f; FILL = 0xff282725; TEXT = 0xfff3f1ec; MUTED = 0xffa3a097;
            ACCENT = 0xfff08a63; ACCENT_SOFT = 0x29f08a63; ON_ACCENT = 0xff191816; ERROR = 0xffff7a6b; SUCCESS = 0xff4cd38a; WARNING = 0xfff0b054;
            SEPARATOR = 0xff2e2d2a; GLASS = 0xf21d1c1a; BUBBLE_FILL = 0xff35271f;
            GREEN = 0xff6fcf97; TEAL = 0xff5cc8c8; BLUE = 0xff7fa8ff; INDIGO = 0xff9c9cf5; PURPLE = 0xffc79af0; PINK = 0xfff28db2;
            RED = 0xffff7a6b; ORANGE = 0xfff2b65a; GRAY = 0xffa3a097; MINT = 0xff4fd1b0;
        } else {
            BG = 0xfff5f4f1; CARD = 0xffffffff; RAISED = 0xffefede8; FILL = 0xffeae8e2; TEXT = 0xff191816; MUTED = 0xff6b6861;
            ACCENT = 0xffb94e2b; ACCENT_SOFT = 0x1ab94e2b; ON_ACCENT = 0xffffffff; ERROR = 0xffd0362a; SUCCESS = 0xff1d8a4c; WARNING = 0xff9a5b00;
            SEPARATOR = 0xffeae8e3; GLASS = 0xf7ffffff; BUBBLE_FILL = 0xfff3e4db;
            GREEN = 0xff2e8b57; TEAL = 0xff138a8a; BLUE = 0xff2f6fde; INDIGO = 0xff5b5bd6; PURPLE = 0xff8e4ec6; PINK = 0xffd1477a;
            RED = 0xffd93a2e; ORANGE = 0xffb7791f; GRAY = 0xff7d7a73; MINT = 0xff0f8f72;
        }
    }
    int dp(int value) { return Math.round(value * density); }
    int dp(float value) { return Math.round(value * density); }
    /** The side gutter (px) that centers the reading column in a width (px); never under min (px), so a phone keeps its own edge. */
    int gutter(int width, int min) { return Math.max(min, (width - dp(READING_WIDTH)) / 2); }

    // ── Type ──────────────────────────────────────────────────────────────

    static Typeface weight(int weight) { return Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false); }
    /** The display face for titles and greetings. The system serif lacks Hangul on many phones and fell back unevenly, so
     * titles are set in the sans at display weights instead; the name is kept for existing callers. */
    static Typeface serif(int weight) { return weight(Math.max(weight, 650)); }
    TextView text(String value, float size, int weight, int color) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(weight(weight));
        view.setIncludeFontPadding(false); view.setLineSpacing(dp(3), 1); view.setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        // Large Hangul text reads best slightly tightened, small text as designed.
        if (size >= 20) view.setLetterSpacing(-0.02f);
        return view;
    }
    /** Screen title, as in iOS large titles: bold sans, tightly tracked. */
    TextView largeTitle(String value) { TextView view = text(value, LARGE_TITLE, 760, TEXT); view.setLetterSpacing(-0.03f); view.setLineSpacing(0, 1); view.setAccessibilityHeading(true); return view; }
    TextView title(String value, int size) { TextView view = text(value, size, size >= 20 ? 740 : 660, TEXT); view.setLetterSpacing(size >= 20 ? -0.025f : -0.01f); return view; }
    TextView label(String value, int size, int color) { return text(value, size, size >= 15 ? 420 : 450, color); }

    // ── Containers ────────────────────────────────────────────────────────

    LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    LinearLayout row() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    /** A rounded content card on the grouped background. */
    LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(20), dp(18), dp(20), dp(20)); view.setBackground(glass(R_CARD, false));
        // An even rhythm between everything stacked in a card.
        GradientDrawable gap = new GradientDrawable(); gap.setColor(Color.TRANSPARENT); gap.setSize(0, dp(10));
        view.setDividerDrawable(gap); view.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return view;
    }
    /** A rounded list section; add rows with addRow so separators line up with the text, as in iOS. */
    final java.util.Set<View> listGroups = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    record RowParts(FrameLayout tile, ImageView icon, TextView title, TextView subtitle, View trailing, int tint) {}
    final class ListRow extends LinearLayout {
        RowParts parts;
        ListRow() { super(context); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL); }
    }
    LinearLayout group() { LinearLayout view = column(); view.setBackground(glass(R_GROUP, false)); view.setClipToOutline(true); listGroups.add(view); return view; }
    void addRow(LinearLayout group, View row, boolean iconInset) {
        if (group.getChildCount() > 0) {
            View line = new View(context); line.setBackgroundColor(SEPARATOR);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))); p.leftMargin = dp(iconInset ? 64 : 16);
            group.addView(line, p);
        }
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    /** A soft wash of a hue for icon tiles: the glyph in the hue reads on it, and many tiles together stay calm. */
    int tone(int hue) { return (hue & 0x00ffffff) | (DARK ? 0x2e000000 : 0x1f000000); }
    /** One list row: an optional icon tile, title, optional subtitle and trailing view; tappable when action is given. */
    LinearLayout listRow(int icon, int tint, String title, String subtitle, View trailing, Runnable action) {
        ListRow row = new ListRow(); row.setMinimumHeight(dp(58)); row.setPadding(dp(16), dp(11), dp(14), dp(11));
        FrameLayout tile = null; ImageView glyph = null;
        if (icon != 0) {
            tile = new FrameLayout(context); tile.setBackground(shape(tone(tint), 10));
            glyph = new ImageView(context); glyph.setImageResource(icon); glyph.setImageTintList(ColorStateList.valueOf(tint | 0xff000000));
            tile.addView(glyph, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(32), dp(32)); p.rightMargin = dp(16); row.addView(tile, p);
        }
        LinearLayout words = column();
        TextView name = text(title, BODY, 520, TEXT); words.addView(name);
        TextView sub = null;
        if (subtitle != null && !subtitle.isBlank()) {
            sub = text(subtitle, FOOTNOTE, 450, MUTED); sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END); sub.setPadding(0, dp(3), 0, 0); words.addView(sub);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.leftMargin = dp(8); row.addView(trailing, p); }
        if (action != null) { row.setOnClickListener(v -> action.run()); pressable(row, true); row.setContentDescription(title + (subtitle == null ? "" : ", " + subtitle)); }
        row.parts = new RowParts(tile, glyph, name, sub, trailing, tint);
        return row;
    }
    /** The trailing disclosure arrow of a tappable row: the vector chevron, quiet, and skipped by screen readers. */
    TextView chevron() {
        TextView view = new TextView(context); android.graphics.drawable.Drawable arrow = context.getDrawable(R.drawable.ic_chevron);
        if (arrow != null) { arrow = arrow.mutate(); arrow.setTint(MUTED); arrow.setBounds(0, 0, dp(18), dp(18)); view.setCompoundDrawablesRelative(arrow, null, null, null); }
        view.setAlpha(0.55f); view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return view;
    }
    TextView badge(String value, int color) {
        // Ink on the lighter dark-mode hues, white on the deeper light-mode ones.
        TextView view = text(value, 11.5f, 700, DARK ? 0xff191816 : Color.WHITE); view.setBackground(shape(color, PILL)); view.setPadding(dp(8), dp(3), dp(8), dp(3)); return view;
    }
    /** Small gray caption above a group or card. */
    TextView sectionHeader(String value) { TextView view = text(value, FOOTNOTE, 650, MUTED); view.setPadding(dp(16), dp(26), dp(16), dp(8)); view.setAccessibilityHeading(true); return view; }
    /** Explanatory text under a group. */
    TextView footer(String value) { TextView view = text(value, 12.5f, 450, MUTED); view.setPadding(dp(16), dp(10), dp(16), 0); view.setLineSpacing(dp(3), 1); return view; }
    /** A tinted strip for errors and news at the top of a screen. */
    TextView banner(String value, int color) {
        // The words take the strip's color, deepened toward the ink just enough to read at 4.5:1 on its own tint.
        int tint = (color & 0x00ffffff) | (DARK ? 0x2a000000 : 0x1c000000);
        TextView view = text(value, SUBHEAD, 560, readable(color, over(tint, BG))); view.setBackground(shape(tint, R_CONTROL)); view.setPadding(dp(16), dp(13), dp(16), dp(13)); return view;
    }

    // ── Controls ──────────────────────────────────────────────────────────

    /** A full-width action: filled with the accent when primary, a soft accent wash otherwise (Toss-style rounded rectangle). */
    Button button(String value, boolean primary, Runnable action) {
        // Compact: 44dp tall in the callout size, so a sheet's button and a page's actions do not shout.
        Button button = new Button(context); button.setText(value); button.setAllCaps(false); button.setTextSize(CALLOUT); button.setTypeface(weight(650));
        button.setTextColor(primary ? ON_ACCENT : ACCENT); button.setBackground(shape(primary ? ACCENT : ACCENT_SOFT, R_CONTROL));
        button.setStateListAnimator(null); button.setElevation(0); button.setMinHeight(dp(44)); button.setMinimumHeight(dp(44)); button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setOnClickListener(v -> action.run()); pressable(button, false); return button;
    }
    /** A capsule drawn 40dp tall inside a 48dp touch target, so rows of them stay light without shrinking the target. */
    android.graphics.drawable.Drawable capsule(int color) {
        // No padding of its own: a background swapped on a tap (a pill chosen) must not reset the view's padding, which
        // squeezed the words against the capsule's ends.
        return new android.graphics.drawable.InsetDrawable(shape(color, PILL), 0, dp(4), 0, dp(4)) {
            @Override public boolean getPadding(android.graphics.Rect padding) { padding.set(0, 0, 0, 0); return false; }
        };
    }
    /** A compact capsule for secondary actions on cards. */
    /**
     * A capsule at most `tallest` high, centred in its view whatever the view's height (a 48dp touch target or a 36dp row),
     * and never taller than the view; it adds no padding of its own, so the text keeps the padding the chip sets.
     */
    android.graphics.drawable.Drawable centeredCapsule(int color, int tallest) {
        GradientDrawable pill = shape(color, PILL);
        return new android.graphics.drawable.Drawable() {
            @Override protected void onBoundsChange(android.graphics.Rect bounds) {
                int height = Math.min(tallest, bounds.height()), top = bounds.top + (bounds.height() - height) / 2;
                pill.setBounds(bounds.left, top, bounds.right, top + height);
            }
            @Override public void draw(android.graphics.Canvas canvas) { pill.draw(canvas); }
            @Override public void setAlpha(int alpha) { pill.setAlpha(alpha); }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { pill.setColorFilter(filter); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }
    /** An action chip: a slim 34dp capsule in 13.5sp, inside a 48dp touch target (the rest is transparent). */
    Button chip(String value, Runnable action) {
        Button chip = new Button(context); chip.setText(value); chip.setAllCaps(false); chip.setTextSize(13.5f); chip.setTypeface(weight(600)); chip.setTextColor(TEXT);
        chip.setBackground(centeredCapsule(FILL, dp(34))); chip.setStateListAnimator(null); chip.setElevation(0);
        chip.setPadding(dp(14), 0, dp(14), 0); chip.setMinHeight(dp(48)); chip.setMinimumHeight(dp(48)); chip.setMinWidth(0); chip.setMinimumWidth(0);
        chip.setOnClickListener(v -> action.run()); pressable(chip, false); return chip;
    }
    /** A chip for a slim toolbar row: the same capsule, lower (36dp) and in smaller type. */
    Button smallChip(String value, Runnable action) {
        // The background first: setting one takes the drawable's padding, which would wipe the side padding set before it.
        Button chip = chip(value, action); chip.setBackground(centeredCapsule(FILL, dp(28))); chip.setTextSize(FOOTNOTE); chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setMinHeight(dp(36)); chip.setMinimumHeight(dp(36)); chip.setHeight(dp(36)); return chip;
    }
    /**
     * A compact action pill (34dp, 13sp) with an optional leading icon: the soft fill, or the accent's soft wash for the one
     * action a notice leads to. For the actions under a notice, grouped on one line with pills().
     */
    TextView pill(String value, int icon, boolean primary, Runnable action) {
        int ink = primary ? ACCENT : TEXT;
        TextView view = text(value, 13, 600, ink); view.setGravity(Gravity.CENTER); view.setSingleLine(true);
        view.setBackground(shape(primary ? ACCENT_SOFT : FILL, PILL)); view.setPadding(dp(icon == 0 ? 14 : 11), 0, dp(14), 0);
        view.setMinHeight(dp(34)); view.setMinimumHeight(dp(34)); view.setHeight(dp(34)); view.setIncludeFontPadding(false);
        if (icon != 0) {
            android.graphics.drawable.Drawable glyph = context.getDrawable(icon).mutate(); glyph.setTint(ink); glyph.setBounds(0, 0, dp(15), dp(15));
            view.setCompoundDrawablesRelative(glyph, null, null, null); view.setCompoundDrawablePadding(dp(5));
        }
        view.setContentDescription(value); view.setOnClickListener(v -> action.run()); pressable(view, true); return view;
    }
    /** Pills side by side, 6dp apart, wrapping on a narrow screen; nulls are left out. */
    ViewGroup pills(View... views) {
        Flow flow = new Flow(context, dp(6), dp(6)); flow.setPadding(0, dp(8), 0, 0);
        for (View view : views) if (view != null) flow.addView(view, new ViewGroup.LayoutParams(-2, -2));
        return flow;
    }
    /** A quiet text button for the actions under an answer: muted, no fill, dims when pressed. */
    TextView ghost(String value, Runnable action) {
        TextView view = text(value, 13.5f, 600, MUTED); view.setGravity(Gravity.CENTER); view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setOnClickListener(v -> action.run()); pressable(view, true); view.setContentDescription(value); return view;
    }
    /** A quiet icon button beside ghost buttons: the icon in the muted color, no fill, dims when pressed. */
    ImageView quietIcon(int icon, String description, Runnable action) {
        ImageView view = new ImageView(context); view.setImageResource(icon); view.setImageTintList(ColorStateList.valueOf(MUTED));
        // Keep a 24dp vector at its intrinsic size in larger touch targets; compact callers may shrink it.
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE); view.setPadding(dp(10), dp(9), dp(10), dp(9));
        iconAccessibility(view, description); view.setOnClickListener(v -> action.run()); pressable(view, true); return view;
    }
    /** An icon alone in a 48dp target: no circle behind it, the glyph at its own 24dp (chat headers, the composer's +). */
    ImageView bareIcon(int icon, int tint, String description, Runnable action) {
        ImageView view = quietIcon(icon, description, action); view.setImageTintList(ColorStateList.valueOf(tint)); view.setPadding(dp(12), dp(12), dp(12), dp(12)); return view;
    }
    /** One icon of the row under an answer (icons only, each with its description): a 20dp muted glyph in a 48dp target. */
    ImageView footIcon(int icon, String description, Runnable action) {
        ImageView view = quietIcon(icon, description, action); view.setPadding(dp(14), dp(14), dp(14), dp(14)); return view;
    }
    /**
     * The composer's one round button: a 38dp disc in a 48dp target. It sends in the accent (the only accent in a chat); while
     * a turn runs and nothing is typed it stops instead, an ink disc with a square, so the same thumb position ends the turn.
     */
    ImageView sendButton(String description, Runnable action) {
        ImageView view = iconButton(R.drawable.ic_send, ACCENT, ON_ACCENT, description, action); sendLooks(view, false); return view;
    }
    void sendLooks(ImageView button, boolean stop) {
        button.setImageResource(stop ? R.drawable.ic_stop : R.drawable.ic_send); button.setImageTintList(ColorStateList.valueOf(stop ? BG : ON_ACCENT));
        button.setBackground(new android.graphics.drawable.InsetDrawable(shape(stop ? TEXT : ACCENT, PILL), dp(5)));
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
    }
    /** A fold's arrow at the end of its one-line label: › closed, turned down while open. */
    void foldArrow(TextView label, boolean open) {
        android.graphics.drawable.Drawable arrow = context.getDrawable(R.drawable.ic_chevron); if (arrow == null) return;
        android.graphics.drawable.RotateDrawable turned = new android.graphics.drawable.RotateDrawable(); turned.setDrawable(arrow.mutate());
        turned.setFromDegrees(open ? 90 : 0); turned.setToDegrees(open ? 90 : 0); turned.setLevel(1); turned.setTint(label.getCurrentTextColor()); turned.setBounds(0, 0, dp(14), dp(14));
        label.setCompoundDrawablesRelative(null, null, turned, null); label.setCompoundDrawablePadding(dp(4));
    }
    /** A quiet line of text that can be tapped, left-aligned in the muted color with a 48dp target (rows inside a fold). */
    TextView quietRow(String value, Runnable action) {
        TextView view = text(value, FOOTNOTE, 500, MUTED); view.setGravity(Gravity.CENTER_VERTICAL); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        view.setOnClickListener(v -> action.run()); pressable(view, true); view.setContentDescription(value); view.setFocusable(true); return view;
    }
    /** The user's own message: the warm wash, evenly rounded, the same in every chat. */
    TextView bubble(String value) {
        TextView view = text(value, BODY, 430, TEXT); view.setLineSpacing(dp(3), 1);
        view.setBackground(shape(BUBBLE_FILL, R_GROUP)); view.setPadding(dp(16), dp(10), dp(16), dp(11)); return view;
    }
    /** Capsules that wrap onto new lines as space runs out; null entries (actions not offered) are skipped. */
    ViewGroup actions(Button... chips) {
        Flow flow = new Flow(context, dp(8), dp(8)); flow.setPadding(0, dp(6), 0, 0);
        for (Button chip : chips) if (chip != null) flow.addView(chip, new ViewGroup.LayoutParams(-2, -2));
        return flow;
    }
    /** iOS segmented control: equal segments on a gray track, the chosen one raised. */
    /**
     * The full-width, 48dp switch. The user asked again and again for compact controls: a screen's tabs and a setting's few
     * choices use switcher() (as wide as its words, 34dp), at the end of a line that is already there. This one only where a
     * sheet's whole body is that one choice.
     */
    LinearLayout segmented(String[] labels, int selected, IntConsumer action) { return segmented(labels, selected, action, false); }
    /**
     * A small switch between two or three views of the same thing (a page's 미리보기 and 소스): as wide as its words, to sit at
     * the end of a line that is already there. It does not take a line of its own: the full-width control is for a choice the
     * screen is about, and this is a detail of one.
     */
    LinearLayout switcher(String[] labels, int selected, IntConsumer action) { return segmented(labels, selected, action, true); }
    private LinearLayout segmented(String[] labels, int selected, IntConsumer action, boolean compact) {
        LinearLayout track = row(); track.setBackground(shape(FILL, R_CONTROL)); track.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < labels.length; i++) {
            int index = i; boolean on = i == selected;
            TextView segment = text(labels[i], compact ? FOOTNOTE : SUBHEAD, on ? 650 : 500, on ? TEXT : MUTED); segment.setGravity(Gravity.CENTER);
            if (compact) { segment.setMinimumHeight(dp(34)); segment.setMinimumWidth(dp(56)); segment.setPadding(dp(12), dp(6), dp(12), dp(6)); segment.setSingleLine(true); }
            else { segment.setMinimumHeight(dp(48)); segment.setPadding(dp(8), dp(10), dp(8), dp(10)); }
            segment.setFocusable(true);
            segment.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.RadioButton"); info.setCheckable(true); info.setChecked(host.isSelected()); } });
            segment.setOnClickListener(v -> {
                if (!v.isSelected()) tick(v);
                selectSegment(track, labels, index);
                action.accept(index);
            });
            track.addView(segment, compact ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        }
        selectSegment(track, labels, selected);
        return track;
    }
    private void selectSegment(LinearLayout track, String[] labels, int selected) {
        for (int i = 0; i < track.getChildCount(); i++) {
            TextView segment = (TextView) track.getChildAt(i); boolean on = i == selected;
            segment.setSelected(on); segment.setTextColor(on ? TEXT : MUTED); segment.setTypeface(weight(on ? 650 : 500));
            segment.setBackground(on ? shape(DARK ? RAISED : CARD, 11) : null); segment.setElevation(on ? dp(1.5f) : 0);
            if (on) { segment.setOutlineSpotShadowColor(0x33000000); segment.setOutlineAmbientShadowColor(0x1a000000); }
            segment.setContentDescription(labels[i] + (on ? ", 선택됨" : ""));
        }
    }
    /** A scrolling row of filter capsules; the chosen one is inverted (ink fill, paper text), so the accent stays for actions. */
    View pills(String[] labels, int selected, IntConsumer action) { return pills(labels, selected, action, false); }
    /** The same filter capsules, lower (34dp) and in smaller type, for a list that wants its room. */
    View smallPills(String[] labels, int selected, IntConsumer action) { return pills(labels, selected, action, true); }
    private View pills(String[] labels, int selected, IntConsumer action, boolean small) {
        HorizontalScrollView scroller = new HorizontalScrollView(context); scroller.setHorizontalScrollBarEnabled(false); scroller.setClipToPadding(false);
        LinearLayout row = row();
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            TextView pill = text(labels[i], small ? FOOTNOTE : SUBHEAD, i == selected ? 680 : 560, i == selected ? BG : TEXT); pill.setGravity(Gravity.CENTER);
            pill.setBackground(capsule(i == selected ? TEXT : FILL));
            if (small) { pill.setPadding(dp(14), dp(6), dp(14), dp(6)); pill.setMinimumHeight(dp(34)); }
            else { pill.setPadding(dp(14), dp(7), dp(14), dp(7)); pill.setMinimumHeight(dp(42)); }
            pill.setSelected(i == selected); pill.setFocusable(true); pill.setAccessibilityDelegate(new View.AccessibilityDelegate() { @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName("android.widget.RadioButton"); info.setCheckable(true); info.setChecked(host.isSelected()); } });
            pill.setContentDescription(labels[i] + (i == selected ? ", 선택됨" : ""));
            pill.setOnClickListener(v -> {
                if (!v.isSelected()) tick(v);
                for (int j = 0; j < row.getChildCount(); j++) {
                    TextView option = (TextView) row.getChildAt(j); boolean on = j == index;
                    int l = option.getPaddingLeft(), t = option.getPaddingTop(), r = option.getPaddingRight(), b = option.getPaddingBottom();
                    option.setSelected(on); option.setTextColor(on ? BG : TEXT); option.setBackground(capsule(on ? TEXT : FILL)); option.setPadding(l, t, r, b); option.setTypeface(weight(on ? 680 : 560));
                    option.setContentDescription(labels[j] + (on ? ", 선택됨" : ""));
                }
                action.accept(index);
            }); pressable(pill, false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); if (i > 0) p.leftMargin = dp(6); row.addView(pill, p);
        }
        scroller.addView(row); return scroller;
    }
    /** A visible, accessible single selection; selection feedback does not depend on the caller rebuilding its sheet. */
    View reasoningChoices(dev.jun.gyeot.core.Reasoning.Profile profile, String selected, Consumer<String> action) {
        return reasoningChoices(profile, selected, false, action);
    }
    View reasoningChoices(dev.jun.gyeot.core.Reasoning.Profile profile, String selected, boolean gpt, Consumer<String> action) {
        LinearLayout list = column(); String[] values = profile.values();
        for (int i = 0; i < values.length; i++) {
            String value = values[i]; int index = i;
            String label = value.isEmpty() ? "기본" + (profile.defaultEffort.isEmpty() ? "" : " · " + (gpt ? dev.jun.gyeot.core.Reasoning.gptLabel(profile.defaultEffort) : dev.jun.gyeot.core.Reasoning.label(profile.defaultEffort))) : (gpt ? dev.jun.gyeot.core.Reasoning.gptLabel(value) : dev.jun.gyeot.core.Reasoning.label(value));
            android.widget.RadioButton option = new android.widget.RadioButton(context); option.setText(label); option.setTextSize(16); option.setTextColor(TEXT); option.setTypeface(weight(550));
            option.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACCENT, MUTED}));
            option.setPadding(dp(12), dp(10), dp(12), dp(10)); option.setMinHeight(dp(52)); option.setChecked(value.equals(selected)); option.setBackground(shape(value.equals(selected) ? ACCENT_SOFT : Color.TRANSPARENT, 16));
            option.setOnClickListener(v -> { for (int j = 0; j < list.getChildCount(); j++) {
                if (list.getChildAt(j) instanceof android.widget.RadioButton other) { other.setChecked(j == index); other.setBackground(shape(j == index ? ACCENT_SOFT : Color.TRANSPARENT, 16)); }
            } action.accept(value); }); list.addView(option, new LinearLayout.LayoutParams(-1, -2));
        }
        if (profile.efforts.isEmpty()) list.addView(footer("이 모델 연결은 생각 깊이 조절을 제공하지 않아 모델 기본값으로 실행해요."));
        return list;
    }
    /** A filled, borderless text field. */
    EditText input(String hint, int minLines, int maxLength) {
        EditText input = new EditText(context); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(BODY); input.setHint(hint);
        input.setBackground(field()); input.setPadding(dp(16), dp(13), dp(16), dp(13)); input.setTypeface(weight(420));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        if (minLines > 1) { input.setMinLines(minLines); input.setGravity(Gravity.TOP); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); }
        else input.setSingleLine(true);
        return input;
    }
    /** Marks a field as search: a leading magnifier in the muted color, as in iOS and Toss search bars. */
    EditText searchField(EditText field) {
        android.graphics.drawable.Drawable lens = context.getDrawable(R.drawable.ic_search);
        if (lens != null) { lens = lens.mutate(); lens.setTint(MUTED); lens.setBounds(0, 0, dp(20), dp(20)); field.setCompoundDrawablesRelative(lens, null, null, null); }
        field.setCompoundDrawablePadding(dp(10)); field.setPadding(dp(14), field.getPaddingTop(), dp(16), field.getPaddingBottom()); return field;
    }
    /** A filled, borderless field that lifts to the card color with an accent ring while focused. */
    android.graphics.drawable.StateListDrawable field() {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        GradientDrawable focused = shape(CARD, R_CONTROL); focused.setStroke(dp(1.5f), ACCENT);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, shape(FILL, R_CONTROL));
        return states;
    }
    /** A labelled switch in the accent when on, so every "on" in the app speaks one color. */
    Switch toggle(String title, boolean checked, Consumer<Boolean> action) {
        Switch view = new Switch(context); view.setText(title); view.setTextColor(TEXT); view.setTextSize(BODY); view.setTypeface(weight(480));
        view.setPadding(0, dp(12), 0, dp(12)); view.setChecked(checked); view.setSwitchPadding(dp(12));
        int[][] states = {{android.R.attr.state_checked}, {}};
        view.setThumbTintList(new ColorStateList(states, new int[]{Color.WHITE, DARK ? 0xffd9d6cf : Color.WHITE}));
        // Off is a clearly darker track than the surface it sits on, so on and off never look alike.
        view.setTrackTintList(new ColorStateList(states, new int[]{ACCENT, DARK ? 0xff55534e : 0xffc4c0b7}));
        view.setOnCheckedChangeListener((button, value) -> { if (button.isPressed()) tick(button); action.accept(value); }); return view;
    }
    /**
     * A switch as one row of a group (settings): the title on the switch itself, so it reads and is
     * found as a labelled switch, and under it one to three quiet lines saying what it does. A tap anywhere on the row flips
     * it. Anything longer goes into details() below the group, never under the switch.
     */
    LinearLayout switchRow(String title, String subtitle, boolean checked, Consumer<Boolean> action) {
        boolean note = subtitle != null && !subtitle.isBlank();
        LinearLayout row = column(); row.setMinimumHeight(dp(56)); row.setPadding(dp(16), dp(4), dp(14), dp(note ? 12 : 4));
        Switch toggle = toggle(title, checked, action); toggle.setTypeface(weight(520)); toggle.setPadding(0, dp(8), 0, dp(note ? 1 : 8));
        row.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        if (note) {
            TextView words = text(subtitle, FOOTNOTE, 450, MUTED); words.setLineSpacing(dp(2), 1); words.setMaxLines(3); words.setEllipsize(TextUtils.TruncateAt.END);
            // Clear of the switch's track, so the lines belong to the title.
            words.setPadding(0, 0, dp(60), 0); words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(words, new LinearLayout.LayoutParams(-1, -2));
            toggle.setContentDescription(title + ", " + subtitle);
        }
        row.setOnClickListener(v -> toggle.toggle()); pressable(row, true);
        return row;
    }
    /**
     * The top of a connection or live-state page: one quiet row whose title is the state itself
     * (「연결됨」, 「연결 안 됨」, 「꺼 둠」, 「확인이 필요해요」), the account, key or mode under it in up to two lines, and a red
     * badge when something needs the user. It is not tappable; the page's one primary button follows it.
     */
    LinearLayout statusCard(int icon, int tint, String state, String detail, String badge) {
        LinearLayout group = group();
        LinearLayout row = listRow(icon, tint, state, detail, badge == null ? null : badge(badge, RED), null);
        RowParts parts = ((ListRow) row).parts;
        parts.title().setTextSize(HEADLINE); parts.title().setTypeface(weight(650));
        if (parts.subtitle() != null) { parts.subtitle().setSingleLine(false); parts.subtitle().setMaxLines(2); }
        row.setContentDescription(state + (detail == null || detail.isBlank() ? "" : ", " + detail) + (badge == null ? "" : ", " + badge));
        row.setScreenReaderFocusable(true);
        addRow(group, row, icon != 0);
        return group;
    }
    /**
     * The sheet a choice opens: one row per label ("name · note" shows the note in gray under the name), the chosen one in
     * the accent with a check. A tap closes it; pick runs only for another index. Callers show() it.
     */
    Sheet choices(String title, String subtitle, String[] labels, int selected, IntConsumer pick) {
        Sheet dialog = selectionSheet(title, subtitle);
        LinearLayout list = group();
        for (int i = 0; i < labels.length; i++) {
            int index = i; boolean on = i == selected; String[] parts = labels[i].split(" · ", 2);
            LinearLayout option = row(); option.setMinimumHeight(dp(46)); option.setPadding(dp(16), dp(8), dp(14), dp(8));
            LinearLayout words = column();
            words.addView(text(parts[0], CALLOUT, on ? 650 : 480, on ? ACCENT : TEXT));
            if (parts.length > 1) { TextView note = text(parts[1], FOOTNOTE, 450, MUTED); note.setPadding(0, dp(2), 0, 0); words.addView(note); }
            option.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView check = new ImageView(context); check.setImageResource(R.drawable.ic_check); check.setImageTintList(ColorStateList.valueOf(ACCENT));
            check.setVisibility(on ? View.VISIBLE : View.INVISIBLE); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(18), dp(18)); checkParams.leftMargin = dp(10); option.addView(check, checkParams);
            option.setContentDescription(labels[i] + (on ? ", 선택됨" : ""));
            option.setOnClickListener(o -> {
                dialog.dismiss();
                if (index != selected) pick.accept(index);
            });
            pressable(option, true); addRow(list, option, false);
        }
        dialog.body.addView(list);
        return dialog;
    }
    /**
     * A choice as one row of a group (settings): its name, the current value under it in up to two lines (long model and
     * voice names stay whole at 360dp) and a chevron. A tap opens choices(); picking another option updates the row in place,
     * then runs action.
     */
    LinearLayout choiceRow(String title, String sheetTitle, String sheetSubtitle, String[] labels, int selected, IntConsumer action) {
        int[] chosen = {labels.length == 0 ? 0 : Math.max(0, Math.min(labels.length - 1, selected))};
        LinearLayout[] row = {null};
        row[0] = listRow(0, 0, title, labels.length == 0 ? "" : labels[chosen[0]], chevron(), () -> {
            if (labels.length == 0) return;
            IntConsumer picked = index -> {
                chosen[0] = index;
                RowParts parts = ((ListRow) row[0]).parts; if (parts.subtitle() != null) parts.subtitle().setText(labels[index]);
                row[0].setContentDescription(title + ", " + labels[index] + ", 눌러서 바꾸기");
                action.accept(index);
            };
            // A few short options (no notes): a small menu at the row; longer lists or ones with notes keep the sheet.
            boolean plain = labels.length <= 8; for (String label : labels) if (label.contains(" · ") || label.length() > 24) plain = false;
            if (plain) miniMenu(row[0], labels, chosen[0], index -> { if (index != chosen[0]) picked.accept(index); });
            else choices(sheetTitle, sheetSubtitle, labels, chosen[0], picked).show();
        });
        RowParts parts = ((ListRow) row[0]).parts;
        if (parts.subtitle() != null) { parts.subtitle().setSingleLine(false); parts.subtitle().setMaxLines(2); }
        row[0].setContentDescription(title + ", " + (labels.length == 0 ? "" : labels[chosen[0]]) + ", 눌러서 바꾸기");
        return row[0];
    }
    /**
     * The only place a key, token or address is typed (settings): a sheet with one masked field kept out of autofill and
     * never prefilled. The primary button calls save with the sheet and the field and does not close the sheet by itself:
     * the caller dismisses on success or sets the field's error. Closing the sheet any other way saves nothing. Callers add
     * notes and link chips to body after the field, then show() it.
     */
    Sheet secretSheet(String title, String subtitle, String hint, int maxLength, String action, java.util.function.BiConsumer<Sheet, EditText> save) {
        Sheet sheet = selectionSheet(title, subtitle);
        EditText field = input(hint, 1, maxLength);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setContentDescription(hint);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(10); sheet.body.addView(field, p);
        sheet.primary.setText(action);
        sheet.primary.setOnClickListener(v -> save.accept(sheet, field));
        return sheet;
    }
    /** A choice as one row of a group: its name, the current value quiet at the end and a chevron; a tap opens the choices. */
    LinearLayout valueRow(String title, String value, Runnable action) {
        LinearLayout trailing = row();
        TextView shown = text(value == null ? "" : value, SUBHEAD, 450, MUTED); shown.setSingleLine(true); shown.setEllipsize(TextUtils.TruncateAt.END);
        shown.setMaxWidth(dp(176)); shown.setGravity(Gravity.END);
        trailing.addView(shown); LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(-2, -2); arrow.leftMargin = dp(4); trailing.addView(chevron(), arrow);
        LinearLayout row = listRow(0, 0, title, null, trailing, action);
        row.setMinimumHeight(dp(52)); row.setContentDescription(title + (value == null || value.isBlank() ? "" : ", " + value));
        return row;
    }
    /** A row that removes or disconnects: its title in the error color, last in its group; the action asks first (confirm). */
    LinearLayout dangerRow(String title, String subtitle, Runnable action) {
        LinearLayout row = listRow(0, 0, title, subtitle, null, action); ((ListRow) row).parts.title().setTextColor(ERROR); return row;
    }
    /**
     * The longer explanation of a group, closed: "자세히" quiet with its arrow, and the words under it once tapped. A page
     * shows its controls first; what they cost, where data goes and how they work stays one tap away.
     */
    LinearLayout details(String label, String body) {
        LinearLayout box = column(); box.setPadding(dp(16), 0, dp(16), 0);
        TextView text = text(body, FOOTNOTE, 450, MUTED); text.setLineSpacing(dp(3), 1); text.setPadding(0, 0, 0, dp(8)); text.setVisibility(View.GONE);
        TextView head = text(label, FOOTNOTE, 600, MUTED); head.setGravity(Gravity.CENTER_VERTICAL); head.setMinHeight(dp(48)); head.setMinimumHeight(dp(48));
        foldArrow(head, false); head.setFocusable(true); head.setContentDescription(label + ", 접힘");
        head.setOnClickListener(v -> {
            boolean open = text.getVisibility() != View.VISIBLE;
            text.setVisibility(open ? View.VISIBLE : View.GONE); foldArrow(head, open); head.setContentDescription(label + (open ? ", 펼침" : ", 접힘"));
        });
        pressable(head, true);
        box.addView(head, new LinearLayout.LayoutParams(-2, -2)); box.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }
    /**
     * A choice among a few: the chosen one in a filled field with a down chevron; a tap opens a sheet from the bottom listing
     * every option, the chosen one checked, as in iOS. A label written "name · note" shows the note in gray under the name.
     * action runs only when the user picks another option.
     */
    View spinner(String[] labels, int selected, IntConsumer action) { return spinner(null, labels, selected, action); }
    /** The same, its sheet titled with what is being chosen ("음성 모델", "목소리") instead of a generic "선택". */
    View spinner(String title, String[] labels, int selected, IntConsumer action) {
        int[] chosen = {labels.length == 0 ? 0 : Math.max(0, Math.min(labels.length - 1, selected))};
        LinearLayout field = row(); field.setBackground(shape(FILL, R_CONTROL)); field.setPadding(dp(16), dp(12), dp(12), dp(12)); field.setMinimumHeight(dp(50));
        TextView value = text(labels.length == 0 ? "" : labels[chosen[0]], BODY, 520, TEXT); value.setSingleLine(true); value.setEllipsize(TextUtils.TruncateAt.END);
        field.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(context); arrow.setImageResource(R.drawable.ic_chevron); arrow.setRotation(90); arrow.setImageTintList(ColorStateList.valueOf(MUTED));
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(20), dp(20)); arrowParams.leftMargin = dp(8); field.addView(arrow, arrowParams);
        field.setContentDescription((labels.length == 0 ? "" : labels[chosen[0]]) + ", 눌러서 바꾸기");
        field.setOnClickListener(v -> choices(title == null ? "선택" : title, title == null ? "원하는 항목을 골라 주세요" : null, labels, chosen[0], index -> {
            chosen[0] = index; value.setText(labels[index]); field.setContentDescription(labels[index] + ", 눌러서 바꾸기");
            action.accept(index);
        }).show());
        pressable(field, true);
        return field;
    }
    Sheet selectionSheet(String title, String subtitle) { return new Sheet(title, subtitle); }
    /**
     * A confirmation in the app's own sheet instead of a system alert: the question, a full-width action (red when it
     * removes or disconnects something) and 취소. The action runs only from its button.
     */
    Sheet confirm(String message, String action, Runnable run) {
        boolean destructive = action.matches(".*(지우|삭제|해제|끊).*");
        Sheet sheet = new Sheet(destructive ? "확인이 필요해요" : "계속할까요?", null);
        TextView question = text(message, 16, 450, TEXT); question.setLineSpacing(dp(4), 1); question.setPadding(dp(4), dp(2), dp(4), dp(6)); sheet.body.addView(question);
        sheet.primary.setText(action); if (destructive) sheet.primary.setBackground(shape(ERROR, R_BUTTON));
        sheet.primary.setOnClickListener(v -> { sheet.dismiss(); run.run(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); sheet.footer.addView(button("취소", false, sheet::dismiss), p);
        return sheet;
    }

    /** Image controls edit the next chat message's mode; the chat composer is the only prompt editor. */
    Sheet imageOptions(String aspect, boolean edit, java.util.function.BiConsumer<String, Boolean> apply) {
        Sheet sheet = selectionSheet("이미지 옵션", "그릴 내용은 대화 입력창에 적어 주세요");
        TextView heading = text("화면 비율", 14, 650, TEXT); heading.setPadding(dp(2), dp(4), 0, dp(12)); sheet.body.addView(heading);
        RatioPicker ratios = new RatioPicker(context, this, aspect); sheet.body.addView(ratios);
        boolean[] editing = {edit};
        View editingSwitch = toggle("기존 이미지로 편집하기", edit, value -> editing[0] = value); editingSwitch.setPadding(dp(2), dp(18), dp(2), dp(8)); sheet.body.addView(editingSwitch);
        TextView note = label("옵션을 적용한 뒤 대화창에서 내용을 적고 보내세요.", 12, MUTED); note.setPadding(dp(2), dp(8), dp(2), 0); sheet.body.addView(note);
        sheet.primary.setText("대화창에 적용");
        sheet.primary.setOnClickListener(v -> { String selected = ratios.aspect(); sheet.dismiss(); apply.accept(selected, editing[0]); });
        return sheet;
    }

    /**
     * A conversation's bar, one thin row that MainNavigation shows in its title's place: back to the list, the title (a tap
     * opens the menu), then the menu and a new conversation as bare icons. Ink and gray only; no shadow, no discs.
     */
    LinearLayout conversationBar(String title, Runnable list, Runnable menu, Runnable fresh) {
        LinearLayout bar = row(); bar.setMinimumHeight(dp(48));
        // The way back keeps its name where there is room for it; a phone shows the chevron alone.
        LinearLayout back = row(); back.setMinimumHeight(dp(48)); back.setMinimumWidth(dp(48)); back.setGravity(Gravity.CENTER);
        ImageView chevron = new ImageView(context); chevron.setImageResource(R.drawable.ic_back); chevron.setImageTintList(ColorStateList.valueOf(TEXT));
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); back.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
        if (context.getResources().getConfiguration().screenWidthDp >= 600) {
            TextView label = text("대화 목록", SUBHEAD, 520, MUTED); label.setSingleLine(true); label.setPadding(dp(2), 0, dp(12), 0); back.addView(label); back.setPadding(dp(6), 0, 0, 0);
        }
        back.setContentDescription("대화 목록(으)로 돌아가기"); back.setTooltipText("대화 목록"); back.setFocusable(true); back.setOnClickListener(v -> list.run()); pressable(back, true); bar.addView(back);
        TextView name = text(title, CALLOUT, 650, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); name.setGravity(Gravity.CENTER_VERTICAL); name.setPadding(dp(4), 0, dp(4), 0);
        name.setContentDescription(title + ", 대화 메뉴"); name.setOnClickListener(v -> menu.run()); pressable(name, true); bar.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(bareIcon(R.drawable.ic_more, TEXT, "대화 메뉴", menu), new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(bareIcon(R.drawable.ic_add, TEXT, "새 대화", fresh), new LinearLayout.LayoutParams(dp(48), dp(48)));
        return bar;
    }

    /** Bounded native sheet: only the body scrolls; the close button and primary action always remain reachable. */
    final class Sheet extends Dialog {
        final LinearLayout controls = column(), body = column(), footer = column();
        final ScrollView scroll = new ScrollView(context);
        final Button primary;
        final LinearLayout panel, header;
        final TextView heading, subheading;
        final ImageView closeButton;
        final View dragHandle;
        int viewportHeightLimit;
        /** Opt-in only: browser model controls can scroll with their catalog under the fixed apply button. */
        void scrollControlsWithBody() {
            if (controls.getParent() != panel || body.getParent() != scroll) return;
            panel.removeView(controls); scroll.removeView(body);
            LinearLayout content = column(); content.addView(controls); content.addView(body); scroll.addView(content);
        }
        Sheet(String title, String subtitle) {
            super(context); requestWindowFeature(Window.FEATURE_NO_TITLE);
            setCancelable(true); setCanceledOnTouchOutside(true);
            LinearLayout frame = new LinearLayout(context) {
                @Override protected void onMeasure(int width, int height) {
                    int available = View.MeasureSpec.getSize(height);
                    int cap = viewportHeightLimit > 0 ? viewportHeightLimit : Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.86f);
                    if (available > 0) cap = Math.min(cap, Math.max(0, available - (viewportHeightLimit > 0 ? 0 : dp(16))));
                    super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
                }
            };
            // The sheet is the grouped canvas, so white groups and cards inside it read as surfaces without outlines.
            frame.setOrientation(LinearLayout.VERTICAL); frame.setBackground(shape(BG, R_SHEET)); frame.setClipToOutline(true); panel = frame;
            frame.setFocusableInTouchMode(true);
            dragHandle = new View(context); dragHandle.setBackground(shape(MUTED, PILL)); dragHandle.setAlpha(0.28f);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(36), dp(5)); hp.gravity = Gravity.CENTER_HORIZONTAL; hp.topMargin = dp(8); frame.addView(dragHandle, hp);
            header = row(); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(20), dp(4), dp(8), dp(8));
            LinearLayout words = column(); heading = text(title, HEADLINE + 1, 700, TEXT); heading.setLetterSpacing(-0.025f); heading.setAccessibilityHeading(true); words.addView(heading);
            subheading = subtitle != null && !subtitle.isBlank() ? text(subtitle, FOOTNOTE, 450, MUTED) : null;
            if (subheading != null) { subheading.setPadding(0, dp(2), 0, 0); words.addView(subheading); }
            header.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            closeButton = iconButton(R.drawable.ic_close, CARD, MUTED, "닫기", this::dismiss);
            closeButton.setPadding(dp(9), dp(9), dp(9), dp(9));
            header.addView(closeButton, new LinearLayout.LayoutParams(dp(38), dp(38))); frame.addView(header);
            float[] down = {0, 0};
            header.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { down[0] = e.getRawX(); down[1] = e.getRawY(); return true; }
                if (e.getActionMasked() == MotionEvent.ACTION_UP) { if (e.getRawY() - down[1] > dp(56) && Math.abs(e.getRawX() - down[0]) < e.getRawY() - down[1]) dismiss(); v.performClick(); return true; }
                return true;
            });
            controls.setPadding(dp(16), 0, dp(16), dp(10)); frame.addView(controls);
            body.setPadding(dp(16), dp(2), dp(16), dp(16));
            scroll.setFillViewport(false); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false); scroll.addView(body);
            frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            footer.setPadding(dp(16), dp(6), dp(16), dp(12)); footer.setBackgroundColor(Color.TRANSPARENT);
            primary = button("완료", true, this::dismiss); footer.addView(primary, new LinearLayout.LayoutParams(-1, -2)); frame.addView(footer);
            setContentView(frame);
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(DARK ? 0.5f : 0.3f);
                if (context.getSystemService(android.view.WindowManager.class).isCrossWindowBlurEnabled()) window.setBackgroundBlurRadius(dp(28));
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
        }
        @Override public void show() {
            super.show(); Window window = getWindow(); if (window == null) return;
            android.view.WindowMetrics metrics = window.getWindowManager().getCurrentWindowMetrics();
            android.graphics.Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int width = metrics.getBounds().width() - bars.left - bars.right;
            // At most 480dp wide: on an unfolded Fold or a tablet it stays a sheet, not a wall.
            window.setLayout(Math.min(dp(480), Math.max(1, width - dp(24))), -2);
            android.view.WindowManager.LayoutParams params = window.getAttributes(); params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; params.y = bars.bottom + dp(8); window.setAttributes(params);
            // The sheet rises into place and settles on an ease-out-expo curve, instead of popping in.
            if (panel != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                panel.setTranslationY(dp(44)); panel.setAlpha(0f);
                panel.animate().translationY(0).alpha(1f).setDuration(380).setInterpolator(EASE_OUT).start();
            }
        }
    }
    /** Apple-style deceleration: fast out of the gate, a long gentle settle. */
    static final android.view.animation.Interpolator EASE_OUT = new android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f);
    /** The way back at the top of a sub-page: the accent back chevron and where it goes ("설정"), as in iOS. */
    LinearLayout backButton(String to, Runnable action) {
        LinearLayout button = row(); button.setPadding(dp(2), 0, dp(12), 0); button.setMinimumHeight(dp(44));
        ImageView chevron = new ImageView(context); chevron.setImageResource(R.drawable.ic_back); chevron.setImageTintList(ColorStateList.valueOf(ACCENT));
        button.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = text(to, CALLOUT + 1, 600, ACCENT); label.setPadding(dp(2), 0, 0, 0); button.addView(label);
        button.setContentDescription(to + "(으)로 돌아가기"); button.setOnClickListener(v -> action.run()); pressable(button, false);
        return button;
    }
    /**
     * One of a call's controls: a round disc with an icon and no caption (mute, camera, end); its name is its description.
     * on() repaints it for its state: an active control (muted, camera on) fills with the accent, the ending one with the
     * error color. The disc is 44dp in a 48dp target.
     */
    static final class Dial extends FrameLayout {
        private final Ui ui; private final ImageView disc; private final boolean ending; private int side = 48;
        Dial(Ui ui, int icon, String name, boolean ending, Runnable action) {
            super(ui.context); this.ui = ui; this.ending = ending; setMinimumWidth(ui.dp(48)); setMinimumHeight(ui.dp(48));
            disc = new ImageView(ui.context); disc.setScaleType(ImageView.ScaleType.CENTER_INSIDE); disc.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            addView(disc, new LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER));
            setFocusable(true); setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); action.run(); }); pressable(this, false);
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); }
            });
            on(icon, name, false);
        }
        @Override protected void onMeasure(int width, int height) { int size = MeasureSpec.makeMeasureSpec(ui.dp(side), MeasureSpec.EXACTLY); super.onMeasure(size, size); }
        /** The slim size (40dp, its disc 34dp) for a compact row of controls. */
        Dial compact() {
            side = 40; setMinimumWidth(ui.dp(40)); setMinimumHeight(ui.dp(40));
            LayoutParams p = (LayoutParams) disc.getLayoutParams(); p.width = p.height = ui.dp(34); disc.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); disc.setLayoutParams(p);
            requestLayout(); return this;
        }
        /** Shows this icon and name; active fills the disc. */
        void on(int icon, String name, boolean active) {
            int fill = ending ? ERROR : active ? ACCENT : FILL, glyph = ending ? Color.WHITE : active ? ON_ACCENT : TEXT;
            disc.setImageResource(icon); disc.setImageTintList(ColorStateList.valueOf(glyph)); disc.setBackground(ui.shape(fill, PILL));
            setContentDescription(name); setTooltipText(name);
        }
    }
    Dial dial(int icon, String name, boolean ending, Runnable action) { return new Dial(this, icon, name, ending, action); }
    /** A slim shortcut: a tinted glyph and a name in one white capsule, for a single row of places to go (the home's shortcuts). */
    View shortcut(int icon, String name, int tint, Runnable action) {
        LinearLayout chip = row(); chip.setBackground(new android.graphics.drawable.InsetDrawable(shape(CARD, PILL), 0, dp(2), 0, dp(2))); chip.setPadding(dp(12), 0, dp(16), 0); chip.setMinimumHeight(dp(48));
        ImageView glyph = new ImageView(context); glyph.setImageResource(icon); glyph.setImageTintList(ColorStateList.valueOf(tint | 0xff000000)); glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chip.addView(glyph, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView label = text(name, SUBHEAD, 600, TEXT); label.setPadding(dp(8), 0, 0, 0); label.setMaxLines(1); chip.addView(label);
        chip.setContentDescription(name); chip.setFocusable(true); chip.setOnClickListener(v -> action.run()); pressable(chip, false);
        chip.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); }
        });
        return chip;
    }
    /** Children side by side in one line that scrolls sideways when it does not fit, with this gap (dp) between them. */
    android.widget.HorizontalScrollView strip(int gap, View... children) {
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(context); scroller.setHorizontalScrollBarEnabled(false); scroller.setOverScrollMode(View.OVER_SCROLL_NEVER); scroller.setClipToPadding(false);
        LinearLayout line = row();
        for (int i = 0; i < children.length; i++) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2); if (i > 0) params.leftMargin = dp(gap); line.addView(children[i], params); }
        scroller.addView(line, new ViewGroup.LayoutParams(-2, -2)); return scroller;
    }
    /**
     * Whether views may move on their own (a screen fading in, a message rising): animations are on, and this is a phone, not
     * a Robolectric run, whose paused clock would leave a view at its first frame in the renders.
     */
    static boolean motion() { return android.animation.ValueAnimator.areAnimatorsEnabled() && !"robolectric".equals(android.os.Build.FINGERPRINT); }
    /** A screen's content arriving: it fades in and settles up a few dp. Nothing when motion() is off. */
    void arrive(View view) {
        if (!motion()) return;
        view.animate().cancel(); view.setAlpha(0f); view.setTranslationY(dp(10));
        view.animate().alpha(1f).translationY(0f).setDuration(190).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }
    /** A short tick under the finger for a choice made (a switch, a pill, a row picked); a firmer one for something done (copied, sent). */
    static void tick(View view) { if (view != null) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); }
    static void done(View view) { if (view != null) view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); }
    /**
     * A small menu right at a button (마이튜브's 화질 and 배속): just the choices, a check by the one in use, no title or notes;
     * below the button, or above it when there is no room below. A tap picks and closes; a tap outside closes.
     */
    android.widget.PopupWindow miniMenu(View anchor, String[] labels, int selected, java.util.function.IntConsumer pick) {
        LinearLayout card = column(); card.setBackground(shape(CARD, 14)); card.setPadding(0, dp(4), 0, dp(4)); card.setClipToOutline(true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context); scroll.setVerticalScrollBarEnabled(false); scroll.addView(card);
        android.widget.PopupWindow[] window = new android.widget.PopupWindow[1];
        for (int i = 0; i < labels.length; i++) {
            int index = i; boolean on = i == selected;
            TextView row = text(labels[i], 14, on ? 650 : 450, on ? ACCENT : TEXT); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(20), 0); row.setMinHeight(dp(40)); row.setMinimumHeight(dp(40));
            if (on) { android.graphics.drawable.Drawable check = context.getDrawable(R.drawable.ic_check); if (check != null) { check = check.mutate(); check.setTint(ACCENT); check.setBounds(0, 0, dp(16), dp(16)); row.setCompoundDrawablesRelative(null, null, check, null); row.setCompoundDrawablePadding(dp(12)); } }
            row.setContentDescription(labels[i] + (on ? ", 선택됨" : ""));
            row.setOnClickListener(v -> { tick(v); if (window[0] != null) window[0].dismiss(); pick.accept(index); }); pressable(row, true);
            card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        card.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        android.util.DisplayMetrics screen = context.getResources().getDisplayMetrics();
        // As wide as its words (a small button's width at least); a wide anchor (a settings row) gets it at its right end.
        boolean wide = anchor.getWidth() > dp(240);
        int width = Math.max(dp(120), wide ? card.getMeasuredWidth() : Math.max(anchor.getWidth(), card.getMeasuredWidth())), height = Math.min(card.getMeasuredHeight(), screen.heightPixels - dp(96));
        window[0] = new android.widget.PopupWindow(scroll, width, height, true);
        window[0].setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0)); window[0].setOutsideTouchable(true); window[0].setElevation(dp(12));
        window[0].setAnimationStyle(android.R.style.Animation_Dialog);
        int[] at = new int[2]; anchor.getLocationInWindow(at);
        boolean below = at[1] + anchor.getHeight() + dp(6) + height < screen.heightPixels - dp(24);
        int x = Math.max(dp(8), Math.min(wide ? at[0] + anchor.getWidth() - width - dp(12) : at[0], screen.widthPixels - width - dp(8)));
        int y = below ? at[1] + anchor.getHeight() + dp(6) : Math.max(dp(12), at[1] - height - dp(6));
        window[0].showAtLocation(anchor.getRootView(), Gravity.NO_GRAVITY, x, y);
        return window[0];
    }
    /** A round icon button, e.g. send. */
    ImageView iconButton(int icon, int background, int tint, String description, Runnable action) {
        ImageView view = new ImageView(context); view.setImageResource(icon); view.setImageTintList(ColorStateList.valueOf(tint));
        view.setBackground(shape(background, 99)); view.setPadding(dp(8), dp(8), dp(8), dp(8)); view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconAccessibility(view, description); view.setOnClickListener(v -> action.run()); pressable(view, false); return view;
    }

    /** Icon actions expose the same name and button role to touch, keyboard and accessibility users. */
    private static void iconAccessibility(ImageView view, String description) {
        view.setFocusable(true); view.setContentDescription(description); view.setTooltipText(description);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
            }
        });
    }

    /**
     * A surface: flat CARD on the canvas, separated by tone rather than an outline (dark mode adds a whisper of an edge, where
     * tone alone is too close). The accent variant carries a soft terracotta glow from one corner, for the one hero on a screen.
     */
    GradientDrawable glass(int radius, boolean accent) {
        GradientDrawable surface = new GradientDrawable();
        if (accent) {
            surface.setOrientation(GradientDrawable.Orientation.TL_BR);
            surface.setColors(DARK ? new int[]{0xff3a2720, 0xff241c18, CARD} : new int[]{0xfffde9df, 0xfffff5f0, CARD}, new float[]{0f, 0.45f, 1f});
        } else surface.setColor(CARD);
        surface.setCornerRadius(dp(radius));
        if (DARK) surface.setStroke(Math.max(1, dp(.6f)), accent ? 0x22f08a63 : 0x0fffffff);
        return surface;
    }
    /** The highlight under the open tab or rail item: a tonal accent capsule. */
    GradientDrawable lens(int radius) { return shape(ACCENT_SOFT, radius); }
    /** The page background: the flat warm canvas, so floating chrome and white surfaces carry all the depth. */
    GradientDrawable canvas() { GradientDrawable canvas = new GradientDrawable(); canvas.setColor(BG); return canvas; }
    /** Lifts a floating control above content with a soft, wide, warm shadow (tab bar, composer). */
    void lift(View view, int elevationDp) {
        view.setElevation(dp(elevationDp)); view.setOutlineSpotShadowColor(DARK ? 0x80000000 : 0x2e3a2a1c); view.setOutlineAmbientShadowColor(DARK ? 0x66000000 : 0x1f3a2a1c);
    }
    View feature(int icon, String title, String subtitle, int tint, Runnable action) {
        LinearLayout tile = column(); tile.setBackground(glass(R_CARD, false)); tile.setPadding(dp(18), dp(16), dp(16), dp(18)); tile.setMinimumHeight(dp(118));
        ImageView glyph = new ImageView(context); glyph.setImageResource(icon); glyph.setImageTintList(ColorStateList.valueOf(tint | 0xff000000)); glyph.setBackground(shape(tone(tint), 13)); glyph.setPadding(dp(9), dp(9), dp(9), dp(9)); tile.addView(glyph, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView name = text(title, BODY, 700, TEXT); name.setLetterSpacing(-0.01f); name.setPadding(0, dp(14), 0, dp(4)); tile.addView(name); tile.addView(text(subtitle, FOOTNOTE, 450, MUTED));
        tile.setContentDescription(title + ", " + subtitle); tile.setFocusable(true); tile.setOnClickListener(v -> action.run()); pressable(tile, false); return tile;
    }
    GradientDrawable shape(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }

    /** color (with its alpha) drawn over an opaque background, as the eye sees it. */
    static int over(int color, int background) {
        float a = Color.alpha(color) / 255f;
        return Color.rgb(Math.round(Color.red(color) * a + Color.red(background) * (1 - a)), Math.round(Color.green(color) * a + Color.green(background) * (1 - a)),
            Math.round(Color.blue(color) * a + Color.blue(background) * (1 - a)));
    }
    /** The color, moved toward the ink a tenth at a time until it reads at 4.5:1 (WCAG AA) on the background. */
    private static int readable(int color, int background) {
        int ink = color | 0xff000000;
        for (int step = 1; step <= 10 && contrast(ink, background) < 4.5; step++) ink = over((color & 0x00ffffff) | (Math.round(255 * (1 - step / 10f)) << 24), TEXT);
        return ink;
    }
    private static double contrast(int a, int b) { double x = luminance(a), y = luminance(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }
    private static double luminance(int color) { return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color)); }
    private static double channel(int value) { double c = value / 255.0; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
    void add(LinearLayout parent, View view, int bottom) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(bottom); parent.addView(view, params); }

    /** Apple-style press feedback: a quick dim and shrink instead of a ripple; list rows only dim. */
    @SuppressLint("ClickableViewAccessibility")
    static void pressable(View view, boolean dimOnly) { view.setOnTouchListener(press(dimOnly)); }
    /** The press feedback alone, for a control that also handles its own touches (the main-chat mic's hold). */
    static View.OnTouchListener press(boolean dimOnly) {
        return (v, event) -> {
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> v.animate().alpha(dimOnly ? 0.6f : 0.85f).scaleX(dimOnly ? 1f : 0.965f).scaleY(dimOnly ? 1f : 0.965f).setDuration(90)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                // Released controls settle back with a small spring overshoot; list rows simply brighten.
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(dimOnly ? 180 : 300)
                    .setInterpolator(dimOnly ? new android.view.animation.DecelerateInterpolator() : new android.view.animation.OvershootInterpolator(1.6f)).start();
                default -> {}
            }
            return false;
        };
    }

    /** Lays children out left to right and wraps them onto new lines. */
    static final class Flow extends ViewGroup {
        private final int gapX, gapY;
        Flow(Context context, int gapX, int gapY) { super(context); this.gapX = gapX; this.gapY = gapY; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight(), x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
                LayoutParams p = child.getLayoutParams();
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), p.height > 0 ? MeasureSpec.makeMeasureSpec(p.height, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += line + gapY; line = 0; }
                x += child.getMeasuredWidth() + gapX; line = Math.max(line, child.getMeasuredHeight());
            }
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + line + getPaddingTop() + getPaddingBottom());
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l - getPaddingLeft() - getPaddingRight(), x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i); if (child.getVisibility() == GONE) continue;
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += line + gapY; line = 0; }
                child.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + child.getMeasuredWidth(), getPaddingTop() + y + child.getMeasuredHeight());
                x += child.getMeasuredWidth() + gapX; line = Math.max(line, child.getMeasuredHeight());
            }
        }
    }
}
