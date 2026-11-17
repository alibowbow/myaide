package dev.jun.gyeot;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Html;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import dev.jun.gyeot.core.Markdown;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown drawn natively in a TextView, the way chat apps show answers: an even, roomy rhythm between lines, paragraphs
 * and list items, headings in three sizes with more room above than below, lists set in from the margin with drawn
 * bullets (nested ones with their own shapes, numbers as written), quotes beside a quiet bar, inline code on a low rounded
 * box that stays inside its line, rules as a thin line, and table rows one per line with a bold header. Inline formatting
 * (bold, italic, code, links) comes from Markdown.inlineHtml; blocks from Markdown.blocks. Every measure is in ems of the
 * body text (16sp), so the look follows the phone's font size.
 */
final class RichText {
    private RichText() {}
    /** One em of body text (16sp), in pixels. */
    static float em() { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, Resources.getSystem().getDisplayMetrics()); }
    /** The room a view of prose adds between its lines: with it a line of body text takes about 1.6 em, as chat apps set it. */
    static int leading() { return Math.round(em() * 0.42f); }
    /** Heading sizes by level, against body text. */
    private static float size(int level) { return level <= 1 ? 1.42f : level == 2 ? 1.25f : level == 3 ? 1.1f : 1f; }

    static CharSequence render(String markdown) {
        float em = em();
        List<Markdown.Block> blocks = Markdown.blocks(markdown);
        SpannableStringBuilder out = new SpannableStringBuilder();
        // Spans are set once all the text is in: a paragraph span that ends at the end of the text grows over anything
        // appended after it, so setting them block by block made every later line inherit the earlier margins and gaps.
        List<Object[]> marks = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Markdown.Block block = blocks.get(i), next = i + 1 < blocks.size() ? blocks.get(i + 1) : null;
            int start = out.length();
            switch (block.kind) {
                case Markdown.Block.RULE -> out.append(' ');
                case Markdown.Block.CODE -> out.append(block.text.isEmpty() ? " " : block.text);
                default -> out.append(styled(block.text));
            }
            int textEnd = out.length();
            if (next != null) out.append('\n');
            int end = out.length();
            // An empty block (a bare "## ") takes no spans: Android refuses zero-length ones.
            if (textEnd == start) continue;
            switch (block.kind) {
                case Markdown.Block.HEADING -> {
                    if (size(block.level) != 1f) mark(marks, new RelativeSizeSpan(size(block.level)), start, textEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    mark(marks, new StyleSpan(Typeface.BOLD), start, textEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                case Markdown.Block.ITEM -> mark(marks, new Item(block.level, block.marker, em), start, end, Spanned.SPAN_PARAGRAPH);
                case Markdown.Block.QUOTE -> {
                    mark(marks, new Quote(em), start, end, Spanned.SPAN_PARAGRAPH);
                    mark(marks, new ForegroundColorSpan(Ui.MUTED), start, textEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                case Markdown.Block.CODE -> {
                    mark(marks, new TypefaceSpan("monospace"), start, textEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    mark(marks, new RelativeSizeSpan(0.88f), start, textEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    mark(marks, new LineBackgroundSpan.Standard(Ui.FILL), start, end, Spanned.SPAN_PARAGRAPH);
                    mark(marks, new LeadingMarginSpan.Standard(Math.round(em * 0.6f)), start, end, Spanned.SPAN_PARAGRAPH);
                }
                case Markdown.Block.RULE -> {
                    // The rule's own line is only as tall as it needs to be; the room around it comes from the gaps.
                    mark(marks, new RelativeSizeSpan(0.2f), start, textEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    mark(marks, new Rule(), start, end, Spanned.SPAN_PARAGRAPH);
                }
                case Markdown.Block.ROW -> { if (block.header) mark(marks, new StyleSpan(Typeface.BOLD), start, textEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
                default -> {}
            }
            if (!block.kind.equals(Markdown.Block.CODE) && out.getSpans(start, textEnd, Code.class).length > 0) mark(marks, new Boxes(em), start, end, Spanned.SPAN_PARAGRAPH);
            int before = i > 0 ? Math.round(em * above(block)) : 0;
            int after = next == null ? 0 : Math.round(em * gap(block, next));
            if (before > 0 || after > 0) mark(marks, new Gap(before, after), start, end, Spanned.SPAN_PARAGRAPH);
        }
        for (Object[] mark : marks) out.setSpan(mark[0], (int) mark[1], (int) mark[2], (int) mark[3]);
        return out;
    }
    private static void mark(List<Object[]> marks, Object span, int start, int end, int flags) { marks.add(new Object[]{span, start, end, flags}); }
    /** Extra room above a block that follows another, in ems: a heading opens a new part, the larger the further away. */
    private static float above(Markdown.Block block) {
        return !block.kind.equals(Markdown.Block.HEADING) ? 0 : block.level <= 2 ? 0.7f : 0.5f;
    }
    /**
     * Room after a block, in ems, on top of the leading: a heading stays with what it heads, list items and table rows
     * stay with their neighbors, a list sits a little closer to the sentence that leads into it, a rule has less room
     * around it (its own line adds some), and other blocks get a paragraph's space.
     */
    private static float gap(Markdown.Block block, Markdown.Block next) {
        String kind = block.kind, after = next.kind;
        if (kind.equals(Markdown.Block.HEADING)) return block.level <= 2 ? 0.4f : 0.3f;
        if (kind.equals(Markdown.Block.ITEM) && after.equals(Markdown.Block.ITEM)) return next.level > block.level ? 0.2f : 0.3f;
        if (kind.equals(Markdown.Block.ROW) && after.equals(Markdown.Block.ROW)) return 0.2f;
        if (kind.equals(Markdown.Block.PARAGRAPH) && after.equals(Markdown.Block.ITEM)) return 0.5f;
        if (kind.equals(Markdown.Block.RULE) || after.equals(Markdown.Block.RULE)) return 0.55f;
        return 0.8f;
    }
    /**
     * The room between two pieces of one prose drawn as two views (RichAnswer.Live draws finished paragraphs apart): what
     * render() leaves between their last and first blocks in one view, with the leading the view adds between lines.
     */
    static int between(String above, String below) {
        List<Markdown.Block> first = Markdown.blocks(above), second = Markdown.blocks(below);
        if (first.isEmpty() || second.isEmpty()) return leading();
        Markdown.Block last = first.get(first.size() - 1), next = second.get(0);
        return Math.round(em() * (gap(last, next) + above(next))) + leading();
    }
    /** A block's inline formatting; code spans sit on low rounded boxes. Also used for table cells. */
    static CharSequence inline(String raw) {
        SpannableStringBuilder text = styled(raw);
        if (text.length() > 0 && text.getSpans(0, text.length(), Code.class).length > 0) text.setSpan(new Boxes(em()), 0, text.length(), Spanned.SPAN_PARAGRAPH);
        return text;
    }
    /** Inline formatting as spans; each code span is marked (Code) and set a little smaller, for Boxes to draw behind. */
    private static SpannableStringBuilder styled(String raw) {
        SpannableStringBuilder text = new SpannableStringBuilder(Html.fromHtml(Markdown.inlineHtml(raw), Html.FROM_HTML_MODE_COMPACT));
        int end = text.length(); while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        text.delete(end, text.length());
        for (TypefaceSpan code : text.getSpans(0, text.length(), TypefaceSpan.class)) {
            int from = text.getSpanStart(code), to = text.getSpanEnd(code);
            if (!"monospace".equals(code.getFamily()) || to <= from) continue;
            text.setSpan(new Code(), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new RelativeSizeSpan(Code.SIZE), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    /** Marks a run of inline code (a file name, a command); Boxes draws its background. */
    static final class Code { static final float SIZE = 0.9f; }
    /**
     * The backgrounds of a block's inline code: a low rounded box behind each run, as tall as its letters and no taller, so
     * it never fills the room between lines the way a background color span does. Drawn per line from the layout, so a
     * run that wraps gets a box on each line. Takes no margin; it is a margin span only because those are handed the layout.
     */
    private static final class Boxes implements LeadingMarginSpan {
        private final float em; private final RectF box = new RectF();
        Boxes(float em) { this.em = em; }
        @Override public int getLeadingMargin(boolean first) { return 0; }
        @Override public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!(text instanceof Spanned spanned) || layout == null || dir < 0) return;
            Code[] codes = spanned.getSpans(start, end, Code.class);
            if (codes.length == 0) return;
            int line = layout.getLineForOffset(start), color = paint.getColor(); Paint.Style style = paint.getStyle();
            for (Code code : codes) {
                int from = Math.max(start, spanned.getSpanStart(code)), to = Math.min(end, spanned.getSpanEnd(code));
                while (to > from && Character.isWhitespace(text.charAt(to - 1))) to--;
                if (to <= from) continue;
                // The letters' own size: the code span's, times a heading's when the code sits in one.
                float scale = 1; for (RelativeSizeSpan sized : spanned.getSpans(from, from + 1, RelativeSizeSpan.class)) scale *= sized.getSizeChange();
                float left = layout.getPrimaryHorizontal(from), right = to < layout.getLineEnd(line) ? layout.getPrimaryHorizontal(to) : layout.getLineRight(line);
                if (right <= left) continue;
                // Kept inside the text's own width (a view clips there), so a run that starts or ends a line keeps round corners.
                float side = em * 0.16f, radius = em * 0.3f;
                box.set(Math.max(0, left - side), baseline + paint.ascent() * scale * 0.94f, Math.min(layout.getWidth(), right + side), baseline + paint.descent() * scale * 1.1f);
                // Set for each box, after the layout was asked: its line measures reset this very paint (it is the layout's own).
                paint.setStyle(Paint.Style.FILL); paint.setColor(Ui.FILL);
                canvas.drawRoundRect(box, radius, radius, paint);
            }
            paint.setColor(color); paint.setStyle(style);
        }
    }
    /** Extra room above the first line and below the last line of a block. */
    private static final class Gap implements LineHeightSpan {
        private final int before, after;
        Gap(int before, int after) { this.before = before; this.after = after; }
        @Override public void chooseHeight(CharSequence text, int start, int end, int spanStart, int lineHeight, Paint.FontMetricsInt metrics) {
            if (!(text instanceof Spanned spanned)) return;
            if (before > 0 && start == spanned.getSpanStart(this)) { metrics.ascent -= before; metrics.top -= before; }
            if (after > 0 && end == spanned.getSpanEnd(this)) { metrics.descent += after; metrics.bottom += after; }
        }
    }
    /**
     * A list item: its marker drawn in the margin on the first line, every line of the item set in past it. Bullets are drawn
     * shapes (a dot, a ring, a small square by depth) at the middle of the line; numbers are written as they came, right
     * aligned so their periods line up. Each level of nesting moves in by about the width of a marker, so a nested item
     * lines up under its parent's text.
     */
    private static final class Item implements LeadingMarginSpan {
        private final int depth; private final String marker; private final float em; private final boolean number;
        Item(int depth, String marker, float em) { this.depth = depth; this.marker = marker; this.em = em; this.number = marker.endsWith("."); }
        private float indent() { return em * 1.45f * depth; }
        @Override public int getLeadingMargin(boolean first) { return Math.round(indent() + em * (number ? 1.8f : 1.6f)); }
        @Override public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!(text instanceof Spanned spanned) || spanned.getSpanStart(this) != start) return;
            int alpha = paint.getAlpha(); Paint.Align align = paint.getTextAlign(); Paint.Style style = paint.getStyle(); float stroke = paint.getStrokeWidth();
            float left = x + indent();
            if (number) { paint.setTextAlign(Paint.Align.RIGHT); canvas.drawText(marker, left + em * 1.4f, baseline, paint); }
            else {
                // The middle of the line's letters, where a bullet reads as belonging to the first line.
                float cx = left + em * 0.8f, cy = baseline + (paint.ascent() + paint.descent()) / 2f, r = em * 0.155f;
                paint.setAlpha(Math.round(alpha * 0.72f));
                if (depth % 3 == 0) { paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy, r, paint); }
                else if (depth % 3 == 1) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1f, em * 0.07f)); canvas.drawCircle(cx, cy, r * 0.92f, paint); }
                else { paint.setStyle(Paint.Style.FILL); canvas.drawRect(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f, paint); }
            }
            paint.setAlpha(alpha); paint.setTextAlign(align); paint.setStyle(style); paint.setStrokeWidth(stroke);
        }
    }
    /** A quote: a quiet bar beside its lines, as tall as the words (not the room under the block). */
    private static final class Quote implements LeadingMarginSpan {
        private final float em;
        Quote(float em) { this.em = em; }
        @Override public int getLeadingMargin(boolean first) { return Math.round(em * 1.05f); }
        @Override public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int dir, int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!(text instanceof Spanned spanned)) return;
            int color = paint.getColor(); Paint.Style style = paint.getStyle();
            // end is where the line's words end; the line itself runs on to its break, where the block's span ends.
            boolean head = start == spanned.getSpanStart(this), tail = layout == null || layout.getLineEnd(layout.getLineForOffset(start)) >= spanned.getSpanEnd(this);
            float width = Math.max(2f, em * 0.19f), from = dir < 0 ? x - width : x;
            // Through the leading between its lines, but only as far as the letters at both ends.
            paint.setStyle(Paint.Style.FILL); paint.setColor((Ui.MUTED & 0x00ffffff) | 0x70000000);
            canvas.drawRect(from, head ? baseline + paint.ascent() * 0.95f : top, from + width, tail ? baseline + paint.descent() * 1.05f : bottom, paint);
            paint.setColor(color); paint.setStyle(style);
        }
    }
    /** A thin line across its (short, empty) line, near the top: the room under it is the line's own gap and leading. */
    private static final class Rule implements LineBackgroundSpan {
        @Override public void drawBackground(Canvas canvas, Paint paint, int left, int right, int top, int baseline, int bottom, CharSequence text, int start, int end, int line) {
            int color = paint.getColor();
            paint.setColor(Ui.MUTED == 0 ? 0x33888888 : (Ui.MUTED & 0x00ffffff) | 0x47000000);
            float middle = (top + baseline) / 2f, thickness = Math.max(1f, Resources.getSystem().getDisplayMetrics().density);
            canvas.drawRect(left, middle, right, middle + thickness, paint);
            paint.setColor(color);
        }
    }
}
