package dev.jun.gyeot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import dev.jun.gyeot.core.Board;
import dev.jun.gyeot.core.BoardWidget;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The board itself: a page Board.WIDTH units wide that fills the view's width and scrolls down. One finger uses the
 * chosen tool (the hand that moves the page, which a board opens with, or a pen, highlighter, eraser, shape or text);
 * two fingers always move and zoom the page. An item can be shown partly (reveal), which is how the AI's writing appears stroke by stroke.
 */
final class BoardView extends View {
    static final String PEN = "pen", MARKER = "marker", ERASER = "eraser", LINE = "line", ARROW = "arrow", RECT = "rect", OVAL = "oval", TEXT = "text", HAND = "hand";
    /** The pen's three widths in board units, and the marker's. */
    static final float[] WIDTHS = {4, 7, 12};
    static final float MARKER_WIDTH = 34, MAX_ZOOM = 4;
    interface Listener {
        /** The items changed by the user's own hand (not by reveal). */
        void changed();
        /** The text tool tapped the page here (board units). */
        void textAt(float x, float y);
        /** The hand tapped (without moving the page) on the item at this index, a picture included; -1 for bare page. */
        default void tapped(int index, float x, float y) {}
        default void full() {}
    }
    /** Gives a picture's file as a bitmap (null when it is gone); asked on the main thread, kept for a few pictures. */
    interface Pictures { Bitmap load(String name); }
    private Pictures pictures; private final java.util.LinkedHashMap<String, Bitmap> loaded = new java.util.LinkedHashMap<>(8, .75f, true);
    private static final int KEPT_PICTURES = 6;
    /** The bars that float over the page, in pixels: writing starts under the top one and follows above the bottom one. */
    private int insetTop, insetBottom;
    private Typeface penFace; private boolean penMissing, hand; static final float PEN_SCALE = 1.12f;
    private final Paint picturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private JSONArray items = new JSONArray();
    private final Deque<Object[]> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Map<JSONObject, Float> revealing = new IdentityHashMap<>();
    private final Map<JSONObject, StaticLayout> layouts = new IdentityHashMap<>();
    private final Map<JSONObject, JSONObject> playback = new IdentityHashMap<>();
    private final Map<JSONObject, List<JSONObject>> widgets = new IdentityHashMap<>();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint letters = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Listener listener;
    /** The hand first: a finger on a board is usually moving it, and drawing begins when a pen is picked. */
    private String tool = HAND, color = Board.INK; private float width = WIDTHS[1];
    /** The page's top-left corner in board units, and how much it is magnified beyond fitting the width. */
    private float left, top, zoom = 1;
    // The gesture in progress.
    private final List<Float> points = new ArrayList<>();
    private float downX, downY, downSx, downSy, lastX, lastY, lastSpan, lastMidX, lastMidY; private boolean drawing, moving, erased; private long touchedAt;
    private JSONObject preview;

    BoardView(Context context) {
        super(context);
        ink.setStrokeCap(Paint.Cap.ROUND); ink.setStrokeJoin(Paint.Join.ROUND);
        setFocusable(true); setContentDescription("판서 판");
    }
    void listen(Listener value) { listener = value; }
    final Board.Measure measure = new Board.Measure() {
        @Override public float[] size(String text, float size, boolean bold, float maxWidth) { return size(text, size, bold, maxWidth, false); }
        @Override public float[] size(String text, float size, boolean bold, float maxWidth, boolean hand) {
            StaticLayout layout = layout(text, size, bold, maxWidth, hand);
            float widest = 0; for (int i = 0; i < layout.getLineCount(); i++) widest = Math.max(widest, layout.getLineWidth(i));
            return new float[]{widest, layout.getHeight()};
        }
        @Override public List<float[]> spans(String text, float size, boolean bold, float maxWidth, boolean hand, int start, int end) {
            StaticLayout placed = layout(text, size, bold, maxWidth, hand);
            List<float[]> rows = new ArrayList<>();
            Path selection = new Path();
            RectF box = new RectF();
            for (int i = 0; i < placed.getLineCount(); i++) {
                int from = Math.max(start, placed.getLineStart(i)), to = Math.min(end, placed.getLineVisibleEnd(i));
                if (from >= to) continue;
                selection.reset();
                placed.getSelectionPath(from, to, selection);
                selection.computeBounds(box, true);
                if (box.width() > 0) rows.add(new float[]{box.left, placed.getLineTop(i), box.right, placed.getLineBottom(i)});
            }
            return rows;
        }
    };
    /** The pen font, read once; null when it cannot be (then handwriting is drawn in print letters). */
    private Typeface pen() {
        if (penFace == null && !penMissing) { try { penFace = Typeface.createFromAsset(getContext().getAssets(), "fonts/GamjaFlower-Regular.ttf"); } catch (RuntimeException missing) { penMissing = true; } }
        return penFace;
    }
    private StaticLayout layout(String text, float size, boolean bold, float maxWidth, boolean hand) {
        Typeface pen = hand ? pen() : null;
        // The pen font's letters are small for their size: they are drawn a quarter larger to read like the print ones.
        TextPaint paint = new TextPaint(letters); paint.setTextSize(pen != null ? size * PEN_SCALE : size); paint.setTypeface(Typeface.create(pen != null ? pen : Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        if (pen != null && bold) paint.setFakeBoldText(true);
        CharSequence written = pen != null ? penned(text) : text;
        return StaticLayout.Builder.obtain(written, 0, written.length(), paint, Math.max(1, Math.round(maxWidth))).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.18f).setIncludePad(false).build();
    }
    private static final String RAISED = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿ", RAISED_AS = "0123456789+-=()n", LOWERED = "₀₁₂₃₄₅₆₇₈₉₊₋", LOWERED_AS = "0123456789+-";
    /**
     * A line as the pen font writes it. The font draws a raised 2 and the signs of arithmetic as wide as a whole
     * letter of Hangul, which pulls x² apart into "x 2": a raised or lowered figure is written as a small one of the
     * pen's own, close to what it belongs to, and a sign in the system's letters, which are as wide as a sign is.
     */
    private static CharSequence penned(String text) {
        android.text.SpannableStringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i); int up = RAISED.indexOf(c), down = up < 0 ? LOWERED.indexOf(c) : -1;
            boolean sign = c == '×' || c == '÷' || c == '±' || (c >= 0x2200 && c <= 0x22ff);
            if (up < 0 && down < 0 && !sign) continue;
            if (out == null) out = new android.text.SpannableStringBuilder(text);
            if (!sign) out.replace(i, i + 1, String.valueOf(up >= 0 ? RAISED_AS.charAt(up) : LOWERED_AS.charAt(down)));
            out.setSpan(sign ? new Sign() : new Small(up >= 0), i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out == null ? text : out;
    }
    private static final class Small extends android.text.style.MetricAffectingSpan {
        private final boolean raised; Small(boolean raised) { this.raised = raised; }
        @Override public void updateDrawState(TextPaint paint) { apply(paint); }
        @Override public void updateMeasureState(TextPaint paint) { apply(paint); }
        private void apply(TextPaint paint) { float size = paint.getTextSize(); paint.setTextSize(size * .62f); paint.baselineShift += raised ? -Math.round(size * .36f) : Math.round(size * .12f); }
    }
    private static final class Sign extends android.text.style.MetricAffectingSpan {
        @Override public void updateDrawState(TextPaint paint) { apply(paint); }
        @Override public void updateMeasureState(TextPaint paint) { apply(paint); }
        private void apply(TextPaint paint) { paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(paint.getTextSize() / PEN_SCALE); }
    }

    void pictures(Pictures value) { pictures = value; loaded.clear(); invalidate(); }
    /** What the text tool writes in from now on: the bundled pen font's handwriting, or the system's print letters. */
    void handwriting(boolean on) { hand = on && pen() != null; }
    boolean handwriting() { return hand; }
    void insets(int topPx, int bottomPx) { if (insetTop == topPx && insetBottom == bottomPx) return; insetTop = Math.max(0, topPx); insetBottom = Math.max(0, bottomPx); clampPage(); invalidate(); }

    // ── What is on the board ────────────────────────────────────────────────────────────────────────────

    JSONArray items() { return items; }
    void show(JSONArray stored) { playback.clear(); items = stored == null ? new JSONArray() : stored; undo.clear(); redo.clear(); revealing.clear(); layouts.clear(); widgets.clear(); loaded.clear(); left = 0; top = 0; zoom = 1; invalidate(); }
    /** Puts a picture on the page under what is there; undo takes it off again. */
    void addPicture(JSONObject picture) { if (add(picture, false)) changed(); }
    /** Shows another arrangement of the board as a single undoable edit. */
    void replace(JSONArray next) {
        if (next == null || next == items) return;
        if (Board.bottom(next, measure) > Board.DEEPEST) { if (listener != null) listener.full(); return; }
        undo.push(new Object[]{"clear", items}); redo.clear(); items = next; revealing.clear(); layouts.clear(); widgets.clear(); changed();
    }
    /** Takes an item off the board (the hand's 지우기 for a picture or a line); undo puts it back in its place. */
    void remove(int index) {
        JSONObject gone = items.optJSONObject(index); if (gone == null) return;
        if (Board.IMAGE.equals(gone.optString("t"))) {
            JSONArray next = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (i != index && item != null && !gone.optString("id").equals(item.optString("on"))) next.put(item);
            }
            replace(next); return;
        }
        removeAt(index); revealing.remove(gone); layouts.remove(gone); widgets.remove(gone); undo.push(new Object[]{"remove", index, gone}); redo.clear(); changed();
    }
    String tool() { return tool; }
    void tool(String value) { tool = value; cancelGesture(); }
    String color() { return color; }
    void color(String value) { color = Board.color(value); }
    float penWidth() { return width; }
    void penWidth(float value) { width = value; }
    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }

    /** Puts an item on the board. hidden: nothing of it shows until reveal() says how much. */
    boolean fits(List<JSONObject> additions) {
        return items.length() + additions.size() <= Board.ITEMS && additions.stream().allMatch(i -> Board.bounds(i, measure)[3] <= Board.DEEPEST);
    }
    boolean add(JSONObject item, boolean hidden) {
        if (!fits(List.of(item))) { if (listener != null) listener.full(); return false; }
        items.put(item); undo.push(new Object[]{"add", item}); redo.clear();
        if (hidden) revealing.put(item, 0f);
        invalidate();
        return true;
    }
    /** How much of an item shows, 0 to 1; at 1 it is whole and no longer tracked. */
    void reveal(JSONObject item, float fraction) {
        if (fraction >= 1) revealing.remove(item); else if (revealing.containsKey(item) || fraction < 1) revealing.put(item, Math.max(0, fraction));
        invalidate();
    }
    void revealAll() { revealing.clear();for(JSONObject item:playback.values())widgets.remove(item);playback.clear();invalidate(); }
    void showFrame(JSONObject item,JSONObject spec) {
        if(item==null || spec==null)return;
        try {
            JSONObject frame=BoardWidget.make(item.optString("kind"),spec,0,(float)item.optDouble("scale",.85),item.optString("by"),measure);
            if(frame!=null){widgets.remove(playback.put(item,frame));invalidate();}
        } catch(IllegalArgumentException ignored) { /* Older saved input can still use the current drawing. */ }
    }
    private int indexOf(JSONObject item) { for (int i = items.length() - 1; i >= 0; i--) if (items.opt(i) == item) return i; return -1; }
    private void removeAt(int index) { items.remove(index); }
    private void insert(int index, JSONObject item) {
        JSONArray next = new JSONArray();
        for (int i = 0; i < items.length(); i++) { if (i == index) next.put(item); next.put(items.opt(i)); }
        if (index >= items.length()) next.put(item);
        items = next;
    }
    void undo() {
        Object[] step = undo.poll(); if (step == null) return;
        if ("add".equals(step[0])) { int index = indexOf((JSONObject) step[1]); if (index >= 0) removeAt(index); revealing.remove((JSONObject) step[1]); }
        else if ("many".equals(step[0])) { for (Object one : (List<?>) step[1]) { int index = indexOf((JSONObject) one); if (index >= 0) removeAt(index); } }
        else if ("remove".equals(step[0])) insert((Integer) step[1], (JSONObject) step[2]);
        else { JSONArray now = items; items = (JSONArray) step[1]; step = new Object[]{"clear", now}; }
        redo.push(step); widgets.clear(); layouts.clear(); changed();
    }
    void redo() {
        Object[] step = redo.poll(); if (step == null) return;
        if ("add".equals(step[0])) items.put((JSONObject) step[1]);
        else if ("many".equals(step[0])) { for (Object one : (List<?>) step[1]) items.put((JSONObject) one); }
        else if ("remove".equals(step[0])) { int index = indexOf((JSONObject) step[2]); if (index >= 0) removeAt(index); }
        else { JSONArray now = items; items = (JSONArray) step[1]; step = new Object[]{"clear", now}; }
        undo.push(step); widgets.clear(); layouts.clear(); changed();
    }
    /** Empties the board; undo brings everything back. */
    void clear() { if (items.length() == 0) return; undo.push(new Object[]{"clear", items}); redo.clear(); items = new JSONArray(); revealing.clear(); widgets.clear(); layouts.clear(); top = 0; changed(); }
    /** What the text tool writes at (the board's 글자 크기). */
    float textScale = 1;
    void addText(float x, float y, String text) {
        if (text == null || text.isBlank()) return;
        float left = Math.min(Board.WIDTH - Board.MARGIN - 96, x);
        if (add(Board.handwritten(Board.text(left, y, text, Board.NORMAL * textScale, color, false, Board.WIDTH - Board.MARGIN - left, Board.ME), hand), false)) changed();
    }
    /** Puts several items on the board as one thing: one undo takes all of them off. */
    boolean addAll(List<JSONObject> made) {
        if (made == null || made.isEmpty()) return false;
        if (!fits(made)) { if (listener != null) listener.full(); return false; }
        for (JSONObject item : made) items.put(item);
        undo.push(new Object[]{"many", new ArrayList<>(made)}); redo.clear(); changed();
        return true;
    }
    private void changed() { invalidate(); if (listener != null) listener.changed(); }

    // ── The page and the screen ─────────────────────────────────────────────────────────────────────────

    private float scale() { return getWidth() <= 0 ? 1 : getWidth() * zoom / Board.WIDTH; }
    private float boardX(float screenX) { return left + screenX / scale(); }
    private float boardY(float screenY) { return top + (screenY - insetTop) / scale(); }
    float top() { return top; }
    /** The page's height shown clear of the bars at once, in board units. */
    float shown() { return Math.max(1, getHeight() - insetTop - insetBottom) / scale(); }
    private void clampPage() {
        float visible = Board.WIDTH / zoom;
        left = Math.max(0, Math.min(Board.WIDTH - visible, left));
        // The page ends where its last ink sits a third of the way down: there is always room below to write on.
        float deepest = Math.max(0, Board.bottom(items, measure) - shown() * .3f);
        top = Math.max(0, Math.min(deepest, top));
    }
    /** Moves the page so y sits about two thirds down the view, when it is not already comfortably in view. */
    void follow(float y) {
        if (getHeight() <= 0) return;
        if (System.currentTimeMillis() - touchedAt < 2500) return;
        float visible = shown();
        if (y > top + visible * .12f && y < top + visible * .62f) return;
        top = Math.max(0, y - visible * .55f); invalidate();
    }
    void scrollToEnd() { touchedAt = 0; follow(Board.bottom(items, measure)); }

    /** How the board looks: a page of neat notes in pen, or a blackboard in chalk. The same items, drawn differently. */
    static final String NOTE = "note", CHALK = "chalk";
    private String style = NOTE;
    /** Chalk's grain: an alpha-only speckle that a paint in chalk draws through, so lines and letters come out dusty. */
    private android.graphics.BitmapShader grain, softGrain;
    private final Paint scrim = new Paint(); private android.graphics.Shader topFade, bottomFade; private long fadeKey;
    void style(String value) { String wanted = CHALK.equals(value) ? CHALK : NOTE; if (wanted.equals(style)) return; style = wanted; layouts.clear(); invalidate(); }
    String style() { return style; }
    boolean chalk() { return CHALK.equals(style); }
    /** The page's own color: warm paper, or the green of a blackboard. */
    int paper() { return chalk() ? 0xff28463b : Ui.DARK ? Ui.BG : 0xfffbf8f1; }
    /** A named color as this board's pen or chalk has it. */
    int ink(String color) {
        if (chalk()) return switch (Board.color(color)) { case "red" -> 0xfff59a90; case "pink" -> 0xfff9add0; case "orange" -> 0xfff9b872; case "yellow" -> 0xfffde27a; case "green" -> 0xffa3e3ad; case "blue" -> 0xffa3ccff; case "purple" -> 0xffd4b9ff; default -> 0xfff4f1e7; };
        return switch (Board.color(color)) { case "red" -> 0xffe5566a; case "pink" -> 0xffee6fa8; case "orange" -> 0xfff0953a; case "yellow" -> 0xffeebb1c; case "green" -> 0xff2aa873; case "blue" -> 0xff4484e6; case "purple" -> 0xff8b6be0; default -> Ui.DARK ? Ui.TEXT : 0xff37322f; };
    }
    private int textInk(JSONObject item) {
        if (chalk() || Ui.DARK) return argb(item);
        return switch (Board.color(item.optString("c"))) {
            case "red" -> 0xffa83248; case "pink" -> 0xffa03767; case "orange" -> 0xff944611;
            case "yellow" -> 0xff795d00; case "green" -> 0xff176641; case "blue" -> 0xff275a9d;
            case "purple" -> 0xff624a9b; default -> argb(item);
        };
    }
    private int argb(JSONObject item) {
        int base = ink(item.optString("c"));
        // A highlighter is see-through (and yellow marker is brighter than yellow pen); a guide line (a graph's grid) is paler still.
        if (item.optBoolean("hl")) return ((!chalk() && "yellow".equals(item.optString("c")) ? 0xffffd84a : base) & 0x00ffffff) | (chalk() ? 0x42000000 : 0x5c000000);
        return item.optBoolean("faint") ? (base & 0x00ffffff) | (chalk() ? 0x38000000 : 0x26000000) : base;
    }
    /**
     * The dust of chalk, for a line or for letters. A line is dustier; letters keep nearly all of their chalk, so a
     * thin stroke of one (a minus sign, a small figure) is never broken in two and stays easy to read.
     */
    private android.graphics.Shader grain(boolean letters) {
        android.graphics.BitmapShader made = letters ? softGrain : grain; if (made != null) return made;
        int size = 96; byte[] alpha = new byte[size * size]; java.util.Random random = new java.util.Random(letters ? 11 : 7);
        for (int i = 0; i < alpha.length; i++) {
            int roll = random.nextInt(100);
            alpha[i] = (byte) (letters ? (roll < 6 ? 178 + random.nextInt(30) : roll < 24 ? 212 + random.nextInt(24) : 240 + random.nextInt(16))
                : (roll < 8 ? 112 + random.nextInt(50) : roll < 30 ? 172 + random.nextInt(44) : 224 + random.nextInt(32)));
        }
        Bitmap speckle = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8); speckle.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(alpha));
        made = new android.graphics.BitmapShader(speckle, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        android.graphics.Matrix coarse = new android.graphics.Matrix(); coarse.setScale(1.6f, 1.6f); made.setLocalMatrix(coarse);
        if (letters) softGrain = made; else grain = made;
        return made;
    }
    /** Whether a marker's band lies on a picture: there it stays a band, on a blackboard too. */
    private boolean onPicture(float x, float y) {
        for (int i = 0; i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item == null || !Board.IMAGE.equals(item.optString("t"))) continue; float[] b = Board.bounds(item, measure); if (x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]) return true; }
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(paper());
        float scale = scale();
        // The page's own top sits under the top bar; what scrolls above it passes behind the bar.
        canvas.save(); canvas.translate(0, insetTop); canvas.scale(scale, scale); canvas.translate(-left, -top);
        paint(canvas, top - insetTop / scale, top + (getHeight() - insetTop) / scale, true);
        canvas.restore();
        // What scrolls toward the bars fades into the paper before it reaches them: nothing is half-read under a bar.
        int paper = paper(); float fade = getResources().getDisplayMetrics().density * 22, edge = getHeight() - insetBottom;
        long key = ((long) paper << 32) ^ ((long) insetTop << 20) ^ ((long) insetBottom << 8) ^ getHeight();
        if (key != fadeKey || topFade == null) {
            fadeKey = key;
            topFade = new android.graphics.LinearGradient(0, insetTop - fade, 0, insetTop, paper, paper & 0x00ffffff, android.graphics.Shader.TileMode.CLAMP);
            bottomFade = new android.graphics.LinearGradient(0, edge, 0, edge + fade, paper & 0x00ffffff, paper, android.graphics.Shader.TileMode.CLAMP);
        }
        if (insetTop > 0) { scrim.setShader(topFade); canvas.drawRect(0, 0, getWidth(), insetTop, scrim); }
        if (insetBottom > 0) { scrim.setShader(bottomFade); canvas.drawRect(0, edge, getWidth(), getHeight(), scrim); }
    }
    /** Every item between two heights of the page, in board units. */
    private void paint(Canvas canvas, float from, float to, boolean live) {
        ink.setShader(chalk() ? grain(false) : null);
        // Pictures lie under everything written, and a marker's band under the ink it colors, wherever they are in the
        // order of things: words stay as dark as they were written.
        for (int pass = 0; pass < 3; pass++) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null || (Board.IMAGE.equals(item.optString("t")) ? 0 : item.optBoolean("hl") ? 1 : 2) != pass) continue;
            Float shown = live ? revealing.get(item) : null;
            if (shown != null && shown <= 0) continue;
            float[] b = Board.bounds(item, measure);
            if (b[3] < from - 40 || b[1] > to + 40) continue;
            if (pass == 0) picture(canvas, item, b, shown == null ? 1 : shown);
            else if(live && playback.containsKey(item)){
                JSONObject frame=playback.get(item);
                float fit=(float)Math.min(1,item.optDouble("h")/Math.max(1,frame.optDouble("h")));
                canvas.save();canvas.translate(0,(float)item.optDouble("y"));canvas.scale(fit,fit);
                paint(canvas,frame,shown==null?1:shown);canvas.restore();
            } else paint(canvas, item, shown == null ? 1 : shown);
        }
        if (live && preview != null) paint(canvas, preview, 1);
        if (live && drawing && points.size() >= 2 && (tool.equals(PEN) || tool.equals(MARKER))) {
            float[] flat = new float[points.size()]; for (int i = 0; i < flat.length; i++) flat[i] = points.get(i);
            paint(canvas, Board.stroke(color, tool.equals(MARKER) ? MARKER_WIDTH : width, tool.equals(MARKER), flat, Board.ME), 1);
        }
    }
    private Bitmap bitmap(String name) {
        Bitmap kept = loaded.get(name); if (kept != null && !kept.isRecycled()) return kept;
        Bitmap fresh = null; try { fresh = pictures == null ? null : pictures.load(name); } catch (RuntimeException unreadable) { /* drawn as missing */ }
        if (fresh == null) return null;
        loaded.put(name, fresh);
        if (loaded.size() > KEPT_PICTURES) { java.util.Iterator<String> oldest = loaded.keySet().iterator(); oldest.next(); oldest.remove(); }
        return fresh;
    }
    private void picture(Canvas canvas, JSONObject item, float[] b, float shown) {
        RectF box = new RectF(b[0], b[1], b[2], b[3]); Bitmap bitmap = bitmap(item.optString("id")); float corner = 12;
        // A sheet of paper lying on the page: rounded, with a hairline around it.
        if (bitmap != null) {
            picturePaint.setAlpha(Math.round(255 * Math.min(1, shown)));
            Path round = new Path(); round.addRoundRect(box, corner, corner, Path.Direction.CW);
            canvas.save(); canvas.clipPath(round); canvas.drawBitmap(bitmap, null, box, picturePaint); canvas.restore();
        }
        ink.setPathEffect(null); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(1.2f); ink.setColor((Ui.MUTED & 0x00ffffff) | 0x55000000);
        canvas.drawRoundRect(box, corner, corner, ink);
        if (bitmap == null) { TextPaint paint = new TextPaint(letters); paint.setTextSize(30); paint.setColor(Ui.MUTED); canvas.drawText("자료를 불러오지 못했어요", box.left + 20, box.top + 50, paint); }
    }
    private void paint(Canvas canvas, JSONObject item, float shown) {
        String type = item.optString("t");
        if (Board.WIDGET.equals(type)) {
            canvas.save();
            canvas.translate(0, (float) item.optDouble("y", 0));
            if (shown < 1) canvas.clipRect(0, 0, Board.WIDTH, (float) item.optDouble("h", 48) * shown);
            for (JSONObject child : widgets.computeIfAbsent(item, key -> BoardWidget.render(key, measure))) paint(canvas, child, 1);
            canvas.restore();
            return;
        }
        if (Board.TEXT.equals(type)) {
            String text = item.optString("text"); float size = (float) item.optDouble("s", Board.NORMAL), room = (float) item.optDouble("mw", Board.WIDTH);
            StaticLayout layout;
            if (shown >= 1) { layout = layouts.get(item); if (layout == null) { layout = layout(text, size, item.optBoolean("b"), room, item.optBoolean("hand")); layouts.put(item, layout); } }
            else { int count = Math.max(1, Math.min(text.length(), Math.round(text.length() * shown))); if (count < text.length() && Character.isHighSurrogate(text.charAt(count - 1))) count++; layout = layout(text.substring(0, Math.min(text.length(), count)), size, item.optBoolean("b"), room, item.optBoolean("hand")); }
            TextPaint written = layout.getPaint(); written.setColor(textInk(item)); written.setShader(chalk() ? grain(true) : null);
            // Chalk is thick: its letters are drawn a little heavier than a pen's, so small writing reads from afar.
            if (chalk()) { written.setStyle(Paint.Style.FILL_AND_STROKE); written.setStrokeJoin(Paint.Join.ROUND); written.setStrokeWidth(written.getTextSize() * .026f); }
            else { written.setStyle(Paint.Style.FILL); written.setStrokeWidth(0); }
            canvas.save(); canvas.translate((float) item.optDouble("x"), (float) item.optDouble("y")); layout.draw(canvas); canvas.restore();
            return;
        }
        float pen = (float) item.optDouble("w", 6); boolean band = item.optBoolean("hl");
        // A thin line of chalk is still a line of chalk: never a hair that its dust breaks into dots.
        ink.setStyle(Paint.Style.STROKE); ink.setColor(argb(item)); ink.setStrokeWidth(chalk() && !band && !item.optBoolean("faint") ? Math.max(pen, 2.2f) : pen);
        ink.setPathEffect(item.optBoolean("d") ? new android.graphics.DashPathEffect(new float[]{16, 13}, 0) : null);
        path.reset();
        if (Board.STROKE.equals(type)) {
            JSONArray p = item.optJSONArray("p"); int count = p == null ? 0 : p.length() / 2; if (count == 0) return;
            float px = (float) p.optDouble(0), py = (float) p.optDouble(1);
            if (chalk() && band && count == 2 && Math.abs(p.optDouble(3) - py) < 1 && !onPicture((px + (float) p.optDouble(2)) / 2, py)) {
                // On a blackboard nobody highlights: what a marker's band would color is underlined in colored chalk,
                // and the words above it stay light on the dark board.
                float end = px + ((float) p.optDouble(2) - px) * Math.min(1, shown), under = py + pen * .5f + 1;
                ink.setColor(ink(item.optString("c")) & 0x00ffffff | 0xd0000000); ink.setStrokeWidth(Math.max(3, Math.min(7, pen * .13f))); canvas.drawLine(px, under, end, under, ink); return;
            }
            if (count == 1) { ink.setStyle(Paint.Style.FILL); canvas.drawCircle(px, py, ink.getStrokeWidth() / 2, ink); return; }
            path.moveTo(px, py); boolean sharp = item.optBoolean("sharp");
            for (int i = 1; i < count; i++) {
                float x = (float) p.optDouble(2 * i), y = (float) p.optDouble(2 * i + 1);
                if (sharp) { path.lineTo(x, y); continue; }
                // Each point is a corner softened toward the middle of the next segment: a hand's line, not a polygon.
                if (i < count - 1) path.quadTo(x, y, (x + (float) p.optDouble(2 * i + 2)) / 2, (y + (float) p.optDouble(2 * i + 3)) / 2); else path.lineTo(x, y);
            }
        } else {
            float x1 = (float) item.optDouble("x1"), y1 = (float) item.optDouble("y1"), x2 = (float) item.optDouble("x2"), y2 = (float) item.optDouble("y2");
            if (Board.RECT.equals(type)) { float corner = (float) Math.min(item.optDouble("r", 6), Math.min(Math.abs(x2 - x1), Math.abs(y2 - y1)) / 2); path.addRoundRect(new RectF(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)), corner, corner, Path.Direction.CW); }
            else if (Board.OVAL.equals(type)) path.addOval(new RectF(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)), Path.Direction.CW);
            else {
                if (item.optBoolean("wavy") && Math.hypot(x2 - x1, y2 - y1) > 20) {
                    // A wave along the line: the underline a student draws under what matters.
                    float length = (float) Math.hypot(x2 - x1, y2 - y1), ux = (x2 - x1) / length, uy = (y2 - y1) / length, wave = Math.max(10, ink.getStrokeWidth() * 3.2f), height = Math.max(3, ink.getStrokeWidth() * 1.1f);
                    int waves = Math.max(2, Math.round(length / wave)); path.moveTo(x1, y1);
                    for (int i = 0; i < waves; i++) { float a = length * (i + .5f) / waves, b = length * (i + 1f) / waves, side = i % 2 == 0 ? -height : height; path.quadTo(x1 + ux * a - uy * side, y1 + uy * a + ux * side, x1 + ux * b, y1 + uy * b); }
                } else { path.moveTo(x1, y1); path.lineTo(x2, y2); }
                if (item.optBoolean("arrow") && shown >= 1) {
                    double angle = Math.atan2(y2 - y1, x2 - x1); float head = Math.max(18, ink.getStrokeWidth() * 4);
                    path.moveTo(x2, y2); path.lineTo((float) (x2 - head * Math.cos(angle - .45)), (float) (y2 - head * Math.sin(angle - .45)));
                    path.moveTo(x2, y2); path.lineTo((float) (x2 - head * Math.cos(angle + .45)), (float) (y2 - head * Math.sin(angle + .45)));
                }
            }
        }
        if (shown < 1) {
            PathMeasure whole = new PathMeasure(path, false); Path part = new Path();
            whole.getSegment(0, whole.getLength() * shown, part, true); canvas.drawPath(part, ink);
        } else {
            // A tinted inside (a bar, a slice, a shaded region, a diagram's box) under its own outline.
            // On a blackboard the tint is faint and even: writing on it must read as well as writing beside it.
            if (item.optBoolean("fill")) { int line = ink.getColor(); android.graphics.Shader dust = ink.getShader(); ink.setShader(null); ink.setPathEffect(null); ink.setStyle(Paint.Style.FILL); ink.setColor((line & 0x00ffffff) | (chalk() ? 0x24000000 : 0x2e000000)); canvas.drawPath(path, ink); ink.setStyle(Paint.Style.STROKE); ink.setColor(line); ink.setShader(dust); }
            if (!(item.optBoolean("fill") && item.optBoolean("bare"))) canvas.drawPath(path, ink);
        }
    }

    /** The page between two heights as a picture widthPx wide, on the board's own paper. */
    Bitmap render(int widthPx, float from, float to) {
        float scale = widthPx / (float) Board.WIDTH; int height = Math.max(1, (int) Math.ceil((to - from) * scale));
        Bitmap bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888);
        renderPage(new Canvas(bitmap), widthPx, from, to);
        return bitmap;
    }

    void renderPage(Canvas canvas, float width, float from, float to) {
        float scale = width / Board.WIDTH;
        canvas.save();
        canvas.clipRect(0, 0, width, (to - from) * scale);
        canvas.drawColor(paper()); canvas.scale(scale, scale); canvas.translate(0, -from);
        paint(canvas, from, to, false);
        ink.setPathEffect(null);
        canvas.restore();
    }

    // ── Touch ───────────────────────────────────────────────────────────────────────────────────────────

    private void cancelGesture() { drawing = false; points.clear(); preview = null; invalidate(); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        touchedAt = System.currentTimeMillis();
        int action = event.getActionMasked();
        if (event.getPointerCount() >= 2) {
            // Two fingers: the page moves and zooms, whatever the tool; a line begun with the first finger is dropped.
            float midX = (event.getX(0) + event.getX(1)) / 2, midY = (event.getY(0) + event.getY(1)) / 2;
            float span = (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
            if (action == MotionEvent.ACTION_POINTER_DOWN || !moving) { cancelGesture(); moving = true; }
            else if (action == MotionEvent.ACTION_MOVE) {
                float before = scale(), anchorX = left + midX / before, anchorY = top + (midY - insetTop) / before;
                if (lastSpan > 10 && span > 10) zoom = Math.max(1, Math.min(MAX_ZOOM, zoom * span / lastSpan));
                float after = scale();
                left = anchorX - midX / after - (midX - lastMidX) / after; top = anchorY - (midY - insetTop) / after - (midY - lastMidY) / after;
                clampPage(); invalidate();
            }
            lastSpan = span; lastMidX = midX; lastMidY = midY;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        float sx = event.getX(), sy = event.getY(), x = boardX(sx), y = boardY(sy);
        switch (action) {
            case MotionEvent.ACTION_DOWN -> {
                getParent().requestDisallowInterceptTouchEvent(true);
                moving = false; drawing = true; erased = false; points.clear(); downX = x; downY = y; lastX = sx; lastY = sy; downSx = sx; downSy = sy;
                if (tool.equals(PEN) || tool.equals(MARKER)) { points.add(x); points.add(y); }
                else if (tool.equals(ERASER)) erase(x, y);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (moving || !drawing) { lastX = sx; lastY = sy; return true; }
                if (tool.equals(HAND)) { top -= (sy - lastY) / scale(); left -= (sx - lastX) / scale(); clampPage(); }
                else if (tool.equals(PEN) || tool.equals(MARKER)) {
                    for (int i = 0; i < event.getHistorySize(); i++) addPoint(boardX(event.getHistoricalX(i)), boardY(event.getHistoricalY(i)));
                    addPoint(x, y);
                }
                else if (tool.equals(ERASER)) erase(x, y);
                else if (!tool.equals(TEXT)) preview = Board.shape(tool.equals(RECT) ? Board.RECT : tool.equals(OVAL) ? Board.OVAL : Board.LINE, downX, downY, x, y, color, width, tool.equals(ARROW), Board.ME);
                lastX = sx; lastY = sy; invalidate();
            }
            case MotionEvent.ACTION_UP -> {
                if (moving) { moving = false; drawing = false; return true; }
                if (!drawing) return true;
                drawing = false;
                if (tool.equals(PEN) || tool.equals(MARKER)) {
                    if (points.size() >= 2) {
                        float[] flat = new float[points.size()]; for (int i = 0; i < flat.length; i++) flat[i] = points.get(i);
                        boolean added = add(Board.stroke(color, tool.equals(MARKER) ? MARKER_WIDTH : width, tool.equals(MARKER), flat, Board.ME), false);
                        points.clear(); if (added) changed(); else invalidate();
                    }
                } else if (tool.equals(TEXT)) { if (listener != null && Math.hypot(x - downX, y - downY) < 30) listener.textAt(Math.max(Board.MARGIN / 2f, downX), downY); }
                else if (tool.equals(HAND)) { if (listener != null && Math.hypot(sx - downSx, sy - downSy) < 14) listener.tapped(Board.hit(items, x, y, 12 / zoom + 8, measure, true), x, y); }
                else if (tool.equals(ERASER)) { if (erased) changed(); }
                else if (preview != null) {
                    JSONObject made = preview; preview = null;
                    if (Math.hypot(x - downX, y - downY) >= 8 && add(made, false)) changed(); else invalidate();
                }
                performClick();
            }
            case MotionEvent.ACTION_CANCEL -> { moving = false; cancelGesture(); }
            default -> { }
        }
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }
    private void addPoint(float x, float y) {
        int size = points.size();
        if (size >= 2 && Math.hypot(x - points.get(size - 2), y - points.get(size - 1)) < 1.5f) return;
        if (size / 2 < Board.POINTS) { points.add(x); points.add(y); }
    }
    private void erase(float x, float y) {
        int index = Board.hit(items, x, y, 14 / zoom + 6, measure); if (index < 0) return;
        JSONObject gone = items.optJSONObject(index); removeAt(index); revealing.remove(gone); layouts.remove(gone); widgets.remove(gone);
        undo.push(new Object[]{"remove", index, gone}); redo.clear(); erased = true; invalidate();
    }
}
