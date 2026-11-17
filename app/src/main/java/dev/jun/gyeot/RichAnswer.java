package dev.jun.gyeot;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import dev.jun.gyeot.core.Markdown;
import dev.jun.gyeot.core.RichBlocks;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import static dev.jun.gyeot.Ui.*;

/**
 * An answer (main chat and 작업창) drawn the way chat apps draw one: Markdown prose natively (headings, lists, quotes,
 * emphasis, links), code in a card with its language and a copy button, tables as a grid that fills the column or scrolls
 * sideways, HTML, SVG or Mermaid visuals in a sandboxed WebView card that can open full screen, and the next requests the
 * answer suggests as quiet buttons that only fill the message box. The sandbox runs the page's own scripts but has no app
 * bridge, no file or content access, and refuses every request except the scripts, styles and fonts RichBlocks allows.
 */
final class RichAnswer {
    /** What a drawn answer asks of its screen. */
    interface Host {
        void copy(String text);
        /** A link the user tapped, inside prose or a visual. */
        void open(String url);
        /** An inline citation the user tapped ([1], Grok's): the page it cites; an ordinary link unless the screen opens cited pages its own way. */
        default void cite(String url) { open(url); }
        /** The visual block, full screen. */
        void expand(RichBlocks.Block block);
        /** A long press anywhere on the answer: the message menu. */
        void menu();
        /** Loads a drawn picture's private copy into the view, off the main thread; a missing one shows a placeholder. */
        default void picture(String id, ImageView into) {}
        /** The drawn picture in the gallery viewer (share, edit, star, open the original, delete). */
        default void openPicture(String id) {}
        /** 중지 on the answer's index-th picture card while it is being drawn. */
        default void stopPicture(int index) {}
        /** 다시 그리기 on the answer's index-th picture card: the user asks for a new drawing. */
        default void redraw(int index) {}
        /** 캘린더에 추가 on the answer's index-th calendar card: the user's tap, the only way it is added. */
        default void addEvent(int index) {}
        /** 되돌리기 on the answer's index-th calendar card, shortly after it was added. */
        default void undoEvent(int index) {}
        /** A suggested next request the user tapped: its words go into the message box, to be read and sent by the user. Never sent from here. */
        default void suggest(String text) {}
        /** Whether prose can be selected (작업창); a long press then starts a selection instead of opening the message menu. */
        default boolean selectable() { return false; }
    }
    /** Inline visuals never grow taller than this; the rest is a tap away in full screen. */
    static final int MAX_VISUAL_DP = 560, MIN_VISUAL_DP = 96, START_VISUAL_DP = 200;
    private RichAnswer() {}

    /** Room between two different pieces of an answer (prose and a card, two cards), and above its suggested requests. */
    private static final int BLOCK_GAP_DP = 14, OFFER_GAP_DP = 18;
    /** The answer's views. live=false shows visuals as cards that open full screen instead of running them inline. */
    static LinearLayout build(Context context, Ui ui, String text, boolean live, Host host) { return build(context, ui, text, live ? Integer.MAX_VALUE : 0, host); }
    /**
     * As build; only the first live visuals run inline, the rest are cards that open full screen. The requests the answer
     * suggests are built hidden: its screen shows them (offer) only while it is the conversation's newest answer.
     */
    static LinearLayout build(Context context, Ui ui, String text, int live, Host host) {
        LinearLayout column = ui.column();
        List<RichBlocks.Block> blocks = RichBlocks.split(text);
        if (blocks.isEmpty()) { column.addView(prose(ui, RichBlocks.withoutSuggestions(text), host)); return column; }
        int pictures = 0, visuals = 0, events = 0;
        for (int i = 0; i < blocks.size(); i++) {
            RichBlocks.Block block = blocks.get(i);
            View view = switch (block.kind()) {
                case CODE -> code(context, ui, block, host);
                case TABLE -> table(context, ui, block, host);
                case HTML, SVG, MERMAID -> visual(context, ui, block, visuals++ < live, host);
                case PICTURE -> picture(context, ui, block, host, pictures++);
                case IMAGE -> request(context, ui, block, host);
                case EVENT -> dev.jun.gyeot.core.CalendarCards.CARD.equals(block.lang()) ? event(context, ui, block, host, events++) : asCode(context, ui, block, host);
                case SUGGESTIONS -> suggestions(context, ui, block, host, false);
                default -> prose(ui, block.text(), host);
            };
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) p.topMargin = gap(ui, blocks.get(i - 1), block);
            column.addView(view, p);
        }
        return column;
    }
    /** The room above a piece that follows another: prose after prose as one text would set it, cards a step apart. */
    private static int gap(Ui ui, RichBlocks.Block above, RichBlocks.Block block) {
        if (block.kind() == RichBlocks.Kind.SUGGESTIONS) return ui.dp(OFFER_GAP_DP);
        return block.kind() == RichBlocks.Kind.MARKDOWN && above.kind() == RichBlocks.Kind.MARKDOWN ? RichText.between(above.text(), block.text()) : ui.dp(BLOCK_GAP_DP);
    }
    /**
     * Shows or hides the requests an answer suggests. Only the conversation's newest answer offers them: an older answer's
     * would ask for something the conversation has already moved past.
     */
    static void offer(View answer, boolean on) {
        View last = answer;
        while (last instanceof ViewGroup group && !(last instanceof Offers) && group.getChildCount() > 0) last = group.getChildAt(group.getChildCount() - 1);
        if (last instanceof Offers) last.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /**
     * An answer still being written, or one the app closed on: drawn like build(), but each piece is drawn only once it
     * changed. The text before RichBlocks.settled is final, so its pieces are drawn once and kept (prose cut at its finished
     * paragraphs, each its own view); only the rest is read and drawn again as more arrives, normally just the paragraph
     * being written. HTML, SVG and Mermaid run only once their fence has closed (a card says the visual is still being
     * written until then), at most visuals of them (the rest open on tap, as older answers' do), and never at all for a
     * cut-off answer (run off); picture blocks show as code, since only a saved answer's own records are pictures, and
     * image requests as a card that waits for the answer to end. The requests the answer suggests show once their fence
     * has closed at its end (never for a cut-off answer), and only while the screen offers them (offer).
     */
    static final class Live {
        /**
         * A drawn view's piece and how it was drawn: still open (a fence or page whose end has not arrived), prose as plain
         * text, a visual running inline; key holds all of it with the piece's text.
         */
        private record Piece(RichBlocks.Block block, boolean open, boolean plain, boolean live, String key) {
            Piece(RichBlocks.Block block, boolean open, boolean plain, boolean live) { this(block, open, plain, live, block.kind() + "|" + block.lang() + "|" + open + "|" + plain + "|" + live + "|" + block.text().length() + "|" + block.text().hashCode()); }
        }
        private final Context context; private final Ui ui; private final Host host; private final boolean run; private final int visuals;
        private final LinearLayout column; private final List<Piece> drawn = new java.util.ArrayList<>(); private String text = "";
        /** The text before settled never changes how it is drawn: its pieces are the first kept views, never drawn again. */
        private int settled, kept;
        /** Whether the screen shows this answer's suggested requests (it is the conversation's newest). */
        private boolean offer = true;
        Live(Context context, Ui ui, Host host, boolean run) { this(context, ui, host, run, Integer.MAX_VALUE); }
        Live(Context context, Ui ui, Host host, boolean run, int visuals) { this.context = context; this.ui = ui; this.host = host; this.run = run; this.visuals = visuals; column = ui.column(); }
        LinearLayout view() { return column; }
        String text() { return text; }
        /** Shows or hides the answer's suggested requests, now and when they arrive. */
        void offer(boolean on) { offer = on; RichAnswer.offer(column, run && on); }
        /** Draws the text so far, keeping the final pieces and redrawing the rest only from the first piece that changed. */
        void update(String value) {
            String next = value == null ? "" : value;
            // Another text (a retry starts the answer over): nothing drawn is kept.
            if (settled > next.length() || !next.regionMatches(0, text, 0, settled)) { clear(column.getChildCount()); drawn.clear(); settled = kept = 0; }
            text = next;
            int point = RichBlocks.settled(text, settled);
            List<RichBlocks.Block> blocks = new java.util.ArrayList<>(RichBlocks.split(text.substring(settled, point)));
            int fixed = blocks.size();
            RichBlocks.Partial tail = RichBlocks.partial(text.substring(point));
            blocks.addAll(tail.blocks());
            if (kept == 0 && blocks.isEmpty() && !text.isBlank() && !tail.suggesting() && !RichBlocks.withoutSuggestions(text).isBlank()) blocks.add(new RichBlocks.Block(RichBlocks.Kind.MARKDOWN, "", text, List.of()));
            // A kept paragraph drawn plain is drawn formatted once another follows it, as the whole answer's prose is.
            if (kept > 0 && !blocks.isEmpty() && blocks.get(0).kind() == RichBlocks.Kind.MARKDOWN && drawn.get(kept - 1).plain()) { kept--; blocks.add(0, drawn.get(kept).block()); fixed++; }
            int shown = 0; for (int i = 0; i < kept; i++) if (drawn.get(i).block().visual()) shown++;
            List<Piece> want = new java.util.ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                RichBlocks.Block b = blocks.get(i); boolean open = i >= fixed && tail.open() && i == blocks.size() - 1;
                RichBlocks.Kind before = i > 0 ? blocks.get(i - 1).kind() : kept > 0 ? drawn.get(kept - 1).block().kind() : null;
                boolean prose = b.kind() == RichBlocks.Kind.MARKDOWN, plain = prose && !Markdown.looksLike(b.text()) && !b.text().contains("\n\n") && before != RichBlocks.Kind.MARKDOWN && !(i + 1 < blocks.size() && blocks.get(i + 1).kind() == RichBlocks.Kind.MARKDOWN);
                want.add(new Piece(b, open, plain, b.visual() && run && shown++ < visuals));
            }
            int same = 0; while (same < want.size() && kept + same < drawn.size() && drawn.get(kept + same).key().equals(want.get(same).key())) same++;
            clear(column.getChildCount() - kept - same); while (drawn.size() > kept + same) drawn.remove(drawn.size() - 1);
            for (int i = same; i < want.size(); i++) {
                Piece piece = want.get(i); RichBlocks.Block block = piece.block();
                View view = switch (block.kind()) {
                    // A fence whose tag may yet read "suggestions" is not flashed as an empty code card.
                    case CODE -> piece.open() && RichBlocks.suggesting(block) ? unseen(context) : code(context, ui, block, host);
                    case TABLE -> table(context, ui, block, host);
                    case SUGGESTIONS -> suggestions(context, ui, block, host, run && offer);
                    case HTML, SVG, MERMAID -> !piece.open() ? visual(context, ui, block, piece.live(), host) : run ? writing(context, ui, block) : code(context, ui, new RichBlocks.Block(RichBlocks.Kind.CODE, block.lang(), block.text(), List.of()), host);
                    case PICTURE -> code(context, ui, new RichBlocks.Block(RichBlocks.Kind.CODE, "json", block.text(), List.of()), host);
                    case IMAGE -> run ? waiting(context, ui) : request(context, ui, block, host);
                    // A card exists only in a saved answer; while it is written, a request waits for the answer to end.
                    case EVENT -> run && dev.jun.gyeot.core.CalendarCards.ASK.equals(block.lang()) ? eventWaiting(context, ui) : asCode(context, ui, block, host);
                    default -> prose(ui, block.text(), host, !piece.plain());
                };
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                if (!drawn.isEmpty()) p.topMargin = gap(ui, drawn.get(drawn.size() - 1).block(), block);
                column.addView(view, p); drawn.add(piece);
            }
            kept += fixed; settled = point;
        }
        /** Removes and frees the last count views. */
        private void clear(int count) { for (int i = column.getChildCount() - 1; count-- > 0 && i >= 0; i--) { destroy(column.getChildAt(i)); column.removeViewAt(i); } }
    }
    /** A piece that takes no room yet. */
    private static View unseen(Context context) { View none = new View(context); none.setVisibility(View.GONE); return none; }
    /** A visual whose fence has not closed yet: a quiet card instead of a page that is half written. */
    private static View writing(Context context, Ui ui, RichBlocks.Block block) {
        LinearLayout card = ui.column(); card.setBackground(surface(ui, R_GROUP - 4));
        card.addView(header(context, ui, "시각화 · " + block.label(), R.drawable.ic_sparkle));
        TextView note = ui.text(WRITING, FOOTNOTE, 450, MUTED); note.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(12)); card.addView(note);
        return card;
    }
    /** What a visual still being written says: it shows as soon as its block is complete, not when the whole answer is. */
    static final String WRITING = "시각화를 쓰고 있어요 · 다 쓰면 보여요";
    /** An image request in an answer still being written: it is drawn once the answer has arrived. */
    private static View waiting(Context context, Ui ui) {
        LinearLayout card = ui.column(); card.setBackground(surface(ui, R_CONTROL));
        card.addView(header(context, ui, "그림 요청 · 답이 끝나면 그려요", R.drawable.ic_gallery));
        return card;
    }

    /** Prose: Markdown drawn as formatting, links tappable; a long press opens the message menu. */
    static TextView prose(Ui ui, String text, Host host) { return prose(ui, text, host, Markdown.looksLike(text) || text.contains("\n\n")); }
    /** As prose; formatted says whether the text is drawn as Markdown or as it is (Live: a paragraph of longer prose is). */
    static TextView prose(Ui ui, String text, Host host, boolean formatted) {
        TextView body = ui.text("", BODY, 400, TEXT);
        body.setText(formatted ? cited(RichText.render(text), host) : text);
        body.setLineSpacing(RichText.leading(), 1); body.setLinkTextColor(ACCENT);
        if (host.selectable()) {
            // Selectable text has no link movement method; with a link mask set after the text is, Android itself opens
            // a tapped link span (and nothing is linkified again, since the text is never set a second time).
            body.setTextIsSelectable(true); body.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        } else {
            body.setMovementMethod(LinkMovementMethod.getInstance());
            body.setOnLongClickListener(v -> { host.menu(); return true; });
        }
        return body;
    }

    /** Inline citations (a link that reads [1], as Markdown draws Grok's) go to the host's cite, the rest stay ordinary links. */
    private static CharSequence cited(CharSequence rendered, Host host) {
        if (!(rendered instanceof Spannable spans)) return rendered;
        for (URLSpan link : spans.getSpans(0, spans.length(), URLSpan.class)) {
            int start = spans.getSpanStart(link), end = spans.getSpanEnd(link), flags = spans.getSpanFlags(link); String url = link.getURL();
            if (!spans.subSequence(start, end).toString().matches("\\[\\d{1,3}]")) continue;
            spans.removeSpan(link);
            spans.setSpan(new ClickableSpan() { @Override public void onClick(View widget) { host.cite(url); } }, start, end, flags);
        }
        return rendered;
    }

    private static LinearLayout header(Context context, Ui ui, String label, int icon) {
        LinearLayout header = ui.row(); header.setPadding(ui.dp(14), 0, ui.dp(2), 0); header.setMinimumHeight(ui.dp(44));
        if (icon != 0) {
            ImageView glyph = new ImageView(context); glyph.setImageResource(icon); glyph.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)); p.rightMargin = ui.dp(7); header.addView(glyph, p);
        }
        TextView name = ui.text(label, FOOTNOTE, 620, MUTED); name.setSingleLine(true);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        return header;
    }
    private static GradientDrawable surface(Ui ui, int radius) {
        GradientDrawable shape = ui.shape(CARD, radius); shape.setStroke(Math.max(1, ui.dp(1)), SEPARATOR); return shape;
    }

    /** The tint behind a table's header row and a code card's title: FILL thinned over the card, so it stays a hint. */
    private static GradientDrawable headTint(Ui ui) {
        // Rounded with its card's top corners itself: clipping to the card's outline is not done on every canvas.
        GradientDrawable tint = new GradientDrawable(); tint.setColor((FILL & 0x00ffffff) | 0x8c000000);
        float r = ui.dp(R_CONTROL); tint.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return tint;
    }
    /** Code: its language and a copy button on a tinted title row, the code in monospace, scrolling sideways instead of wrapping. */
    static View code(Context context, Ui ui, RichBlocks.Block block, Host host) {
        LinearLayout card = ui.column(); card.setBackground(surface(ui, R_CONTROL)); card.setClipToOutline(true);
        LinearLayout header = header(context, ui, block.label(), 0); header.setMinimumHeight(ui.dp(40)); header.setBackground(headTint(ui));
        header.addView(ui.quietIcon(R.drawable.ic_copy, "코드 복사", () -> host.copy(block.text())), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)));
        card.addView(header);
        HorizontalScrollView scroller = new HorizontalScrollView(context); scroller.setHorizontalScrollBarEnabled(false); scroller.setClipToPadding(false);
        TextView code = ui.text(block.text().isEmpty() ? " " : block.text(), 13.5f, 400, TEXT);
        code.setTypeface(Typeface.MONOSPACE); code.setLineSpacing(ui.dp(4), 1); code.setLetterSpacing(0);
        code.setHorizontallyScrolling(true); code.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); code.setTextIsSelectable(true);
        code.setContentDescription(block.label() + " 코드");
        scroller.addView(code); card.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
        card.setOnLongClickListener(v -> { host.menu(); return true; });
        return card;
    }

    /** No table column is squeezed narrower than this to fit; a table that would need it scrolls sideways instead. */
    static final int MIN_COLUMN_DP = 96;
    /**
     * A table's scroller. It tells each column how wide its cells may grow before they wrap, the way a page lays out a
     * table: when every column fits the answer's width at its natural width, nothing wraps (and the table stretches to
     * fill it); otherwise the narrow columns keep their width and the wide ones share what is left, never under
     * MIN_COLUMN_DP each. So a table of a few columns fits a phone without scrolling and one of many scrolls sideways.
     */
    static final class Grid extends HorizontalScrollView {
        private final int floor; private int room = -1; private int[] natural;
        Grid(Context context, int floor) { super(context); this.floor = floor; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && width > 0 && width != room && getChildCount() > 0 && getChildAt(0) instanceof TableLayout grid) { room = width; share(grid, width); }
            super.onMeasure(widthSpec, heightSpec);
        }
        private void share(TableLayout grid, int width) {
            if (natural == null) {
                // Each column's widest cell on one line; the text never changes, so this is measured once.
                int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED); List<Integer> widest = new java.util.ArrayList<>();
                for (int r = 0; r < grid.getChildCount(); r++) if (grid.getChildAt(r) instanceof TableRow row)
                    for (int c = 0; c < row.getChildCount(); c++) if (row.getChildAt(c) instanceof TextView cell) {
                        cell.setMaxWidth(Integer.MAX_VALUE); cell.measure(free, free);
                        while (widest.size() <= c) widest.add(0);
                        widest.set(c, Math.max(widest.get(c), cell.getMeasuredWidth()));
                    }
                natural = new int[widest.size()]; for (int c = 0; c < natural.length; c++) natural[c] = widest.get(c);
            }
            int[] most = natural.clone(); long sum = 0; for (int w : natural) sum += w;
            if (sum > width) {
                // Columns narrower than an even share keep their width; the rest share what is left, again and again.
                boolean[] settled = new boolean[most.length]; int left = width, open = most.length;
                for (boolean changed = true; changed && open > 0;) {
                    changed = false; int even = left / open;
                    for (int c = 0; c < most.length; c++) if (!settled[c] && natural[c] <= even) { settled[c] = true; left -= natural[c]; open--; changed = true; }
                }
                for (int c = 0; c < most.length; c++) if (!settled[c]) most[c] = Math.max(floor, left / open);
            }
            for (int r = 0; r < grid.getChildCount(); r++) if (grid.getChildAt(r) instanceof TableRow row)
                for (int c = 0; c < row.getChildCount() && c < most.length; c++) if (row.getChildAt(c) instanceof TextView cell) cell.setMaxWidth(most[c]);
        }
    }
    /**
     * A table: a bold header row on a light tint, a hairline between rows, roomy cells with inline formatting. It fills the
     * answer's width; its cells wrap to fit when the columns are few, and it scrolls sideways when they are many (Grid).
     */
    static View table(Context context, Ui ui, RichBlocks.Block block, Host host) {
        int columns = 0; for (List<String> row : block.rows()) columns = Math.max(columns, row.size());
        Grid scroller = new Grid(context, ui.dp(MIN_COLUMN_DP)); scroller.setFillViewport(true); scroller.setHorizontalScrollBarEnabled(false);
        TableLayout grid = new TableLayout(context); grid.setBackground(surface(ui, R_CONTROL)); grid.setClipToOutline(true);
        grid.setStretchAllColumns(true); grid.setShrinkAllColumns(false);
        GradientDrawable divider = new GradientDrawable(); divider.setColor(SEPARATOR); divider.setSize(1, Math.max(1, ui.dp(0.75f)));
        grid.setDividerDrawable(divider); grid.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        for (int r = 0; r < block.rows().size(); r++) {
            List<String> cells = block.rows().get(r); boolean head = r == 0;
            TableRow row = new TableRow(context); if (head) row.setBackground(headTint(ui));
            for (int c = 0; c < columns; c++) {
                TextView cell = ui.text("", CALLOUT - 0.5f, head ? 650 : 420, TEXT);
                cell.setText(RichText.inline(c < cells.size() ? cells.get(c) : "")); cell.setLineSpacing(ui.dp(4), 1);
                cell.setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11)); cell.setMinWidth(ui.dp(56));
                cell.setLinkTextColor(ACCENT);
                if (host.selectable()) cell.setTextIsSelectable(true);
                row.addView(cell);
            }
            grid.addView(row);
        }
        grid.setOnLongClickListener(v -> { host.menu(); return true; });
        scroller.addView(grid, new ViewGroup.LayoutParams(-2, -2));
        scroller.setContentDescription("표, " + block.rows().size() + "행");
        return scroller;
    }

    /** The requests an answer suggests; offer() finds it as the answer's last view. No wider than a comfortable button. */
    static final class Offers extends LinearLayout {
        private final int cap;
        Offers(Context context, int cap) { super(context); this.cap = cap; setOrientation(VERTICAL); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            super.onMeasure(MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && width > cap ? MeasureSpec.makeMeasureSpec(cap, MeasureSpec.getMode(widthSpec)) : widthSpec, heightSpec);
        }
    }
    /** What a suggested request's button says to a screen reader after its words. */
    static final String OFFER_HINT = "눌러서 입력창에 넣기";
    /**
     * The next requests the answer suggests (RichBlocks.SUGGESTIONS), as a short column of quiet buttons: the first on a
     * soft fill, the rest outlined, none in the accent. A tap hands the words to the host, which puts them in the message
     * box; nothing is sent from here, so no request (and no cost) starts by a slip of the finger, and words that came from
     * a web page or a file never go out in the user's name unread.
     */
    static View suggestions(Context context, Ui ui, RichBlocks.Block block, Host host, boolean shown) {
        Offers column = new Offers(context, ui.dp(520)); column.setVisibility(shown ? View.VISIBLE : View.GONE);
        List<String> asks = block.suggestions();
        for (int i = 0; i < asks.size(); i++) {
            String ask = asks.get(i);
            TextView button = ui.text(ask, CALLOUT, i == 0 ? 600 : 500, TEXT);
            button.setGravity(Gravity.CENTER_VERTICAL); button.setMinHeight(ui.dp(48)); button.setPadding(ui.dp(16), ui.dp(11), ui.dp(12), ui.dp(11));
            GradientDrawable shape = ui.shape(i == 0 ? FILL : android.graphics.Color.TRANSPARENT, R_CONTROL);
            if (i > 0) shape.setStroke(Math.max(1, ui.dp(1)), (MUTED & 0x00ffffff) | 0x52000000);
            button.setBackground(shape);
            android.graphics.drawable.Drawable arrow = context.getDrawable(R.drawable.ic_chevron);
            if (arrow != null) { arrow = arrow.mutate(); arrow.setTint(MUTED); arrow.setBounds(0, 0, ui.dp(16), ui.dp(16)); button.setCompoundDrawablesRelative(null, null, arrow, null); button.setCompoundDrawablePadding(ui.dp(8)); }
            button.setContentDescription(ask + ", " + OFFER_HINT); button.setFocusable(true);
            button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(view, info); info.setClassName(android.widget.Button.class.getName()); }
            });
            button.setOnClickListener(v -> host.suggest(ask)); Ui.pressable(button, false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); if (i > 0) p.topMargin = ui.dp(8);
            column.addView(button, p);
        }
        return column;
    }

    /** An image view as tall as its picture's shape for the width it gets, up to a cap, so the chat does not jump when it loads. */
    static final class Shaped extends ImageView {
        private final float ratio; private final int cap;
        Shaped(Context context, int width, int height, int cap) { super(context); ratio = width > 0 && height > 0 ? height / (float) width : 1f; this.cap = cap; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec); int height = Math.min(cap, Math.round(width * ratio));
            setMeasuredDimension(width, Math.max(1, height));
        }
    }
    /**
     * A picture the app drew for this answer: the image (tap for the gallery viewer), what was asked for, and copy/open
     * buttons; while it is being drawn, a "그리는 중" card with 중지 and 다시 그리기; or, when it could not be drawn, the
     * reason, the prompt to copy and 다시 그리기. Only the app's own records get here; index is the card's place among the
     * answer's picture blocks.
     */
    static View picture(Context context, Ui ui, RichBlocks.Block block, Host host, int index) {
        org.json.JSONObject record = dev.jun.gyeot.core.ChatImages.record(block.text());
        if (record == null) return code(context, ui, new RichBlocks.Block(RichBlocks.Kind.CODE, "json", block.text(), List.of()), host);
        String prompt = record.optString("prompt");
        LinearLayout card = ui.column(); card.setBackground(surface(ui, R_GROUP - 4)); card.setClipToOutline(true);
        card.setOnLongClickListener(v -> { host.menu(); return true; });
        if (dev.jun.gyeot.core.ChatImages.pending(record)) {
            card.addView(header(context, ui, MainChatPictures.STAGE + " · 보통 30초~1분", R.drawable.ic_gallery));
            if (!prompt.isBlank()) { TextView asked = ui.text(prompt, FOOTNOTE, 450, MUTED); asked.setMaxLines(2); asked.setEllipsize(android.text.TextUtils.TruncateAt.END); asked.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(2)); card.addView(asked); }
            LinearLayout actions = ui.row(); actions.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4));
            actions.addView(ui.ghost("중지", () -> host.stopPicture(index))); actions.addView(ui.ghost("다시 그리기", () -> host.redraw(index)));
            card.addView(actions); card.setContentDescription("그리는 중 · " + prompt);
            return card;
        }
        if (!record.optString("error").isBlank()) {
            card.addView(header(context, ui, "그림을 만들지 못했어요", R.drawable.ic_gallery));
            TextView reason = ui.text(record.optString("error"), CALLOUT, 450, TEXT); reason.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(6)); card.addView(reason);
            if (!prompt.isBlank()) {
                LinearLayout row = ui.row(); row.setPadding(ui.dp(14), 0, ui.dp(2), ui.dp(6));
                TextView asked = ui.text(prompt, FOOTNOTE, 450, MUTED); asked.setMaxLines(3); asked.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(asked, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(ui.quietIcon(R.drawable.ic_copy, "그림 설명 복사", () -> host.copy(prompt)), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)));
                card.addView(row);
                LinearLayout again = ui.row(); again.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4));
                again.addView(ui.ghost("다시 그리기", () -> host.redraw(index))); card.addView(again);
            }
            return card;
        }
        String id = record.optString("id");
        Shaped image = new Shaped(context, record.optInt("w"), record.optInt("h"), ui.dp(520));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setBackgroundColor(FILL);
        image.setContentDescription("만든 그림 · " + prompt + " · 눌러서 크게 보기");
        image.setOnClickListener(v -> host.openPicture(id)); image.setOnLongClickListener(v -> { host.menu(); return true; }); Ui.pressable(image, true);
        card.addView(image, new LinearLayout.LayoutParams(-1, -2));
        host.picture(id, image);
        LinearLayout row = ui.row(); row.setPadding(ui.dp(14), ui.dp(4), ui.dp(2), ui.dp(4));
        TextView asked = ui.text(prompt, FOOTNOTE, 450, MUTED); asked.setMaxLines(2); asked.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(asked, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(ui.quietIcon(R.drawable.ic_copy, "그림 설명 복사", () -> host.copy(prompt)), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        row.addView(ui.quietIcon(R.drawable.ic_expand, "갤러리에서 크게 보기", () -> host.openPicture(id)), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        card.addView(row);
        if (!record.optString("note").isBlank()) {
            TextView note = ui.text(record.optString("note"), FOOTNOTE, 500, ERROR); note.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(10)); card.addView(note);
        }
        return card;
    }
    /** A block shown as its JSON: a calendar request in an older or cut-off answer, or a card the model wrote. */
    private static View asCode(Context context, Ui ui, RichBlocks.Block block, Host host) { return code(context, ui, new RichBlocks.Block(RichBlocks.Kind.CODE, block.lang(), block.text(), List.of()), host); }
    private static View eventWaiting(Context context, Ui ui) {
        LinearLayout card = ui.column(); card.setBackground(surface(ui, R_CONTROL));
        card.addView(header(context, ui, "일정 추가 · 답이 끝나면 카드가 나와요", R.drawable.ic_calendar));
        return card;
    }
    /**
     * An 일정 추가 card the app made for this answer (CalendarCards): the event's title, date and time, place, calendar and
     * reminders, and one button. 캘린더에 추가 is the only way it is added; once added it says 추가했어요 with 되돌리기 for
     * CalendarQuery.UNDO_MS. index is the card's place among the answer's calendar cards.
     */
    static View event(Context context, Ui ui, RichBlocks.Block block, Host host, int index) {
        org.json.JSONObject record = dev.jun.gyeot.core.CalendarCards.record(block.text());
        if (record == null) return asCode(context, ui, block, host);
        String state = record.optString("state"), title = record.optString("title");
        LinearLayout card = ui.column(); card.setBackground(surface(ui, R_GROUP - 4)); card.setClipToOutline(true);
        card.setOnLongClickListener(v -> { host.menu(); return true; });
        boolean failed = dev.jun.gyeot.core.CalendarCards.FAILED.equals(state);
        card.addView(header(context, ui, failed ? "일정을 추가할 수 없어요" : "일정 추가", R.drawable.ic_calendar));
        LinearLayout words = ui.column(); words.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(4));
        if (!title.isBlank()) words.addView(ui.text(title, BODY, 650, TEXT));
        StringBuilder said = new StringBuilder(title);
        String reminders = "";
        org.json.JSONArray alarms = record.optJSONArray("reminder_minutes");
        if (alarms != null && alarms.length() > 0) { java.util.List<Integer> minutes = new java.util.ArrayList<>(); for (int i = 0; i < alarms.length(); i++) minutes.add(alarms.optInt(i)); reminders = dev.jun.gyeot.core.CalendarQuery.reminderText(minutes); }
        String[][] lines = failed ? new String[][]{{"", record.optString("error")}}
            : new String[][]{{"", record.optString("when")}, {"장소 · ", record.optString("location")}, {"캘린더 · ", record.optString("calendar")}, {"알림 · ", reminders},
                // A card made in another time zone than the phone's now says which zone its times are in.
                {"시간대 · ", record.optString("zone").isEmpty() || record.optString("zone").equals(java.time.ZoneId.systemDefault().getId()) || record.optBoolean("all_day") ? "" : record.optString("zone") + " 기준"}};
        for (String[] line : lines) {
            if (line[1].isBlank()) continue;
            TextView text = ui.text(line[0] + line[1], FOOTNOTE, 450, failed ? TEXT : MUTED); text.setPadding(0, ui.dp(2), 0, 0); words.addView(text);
            said.append(", ").append(line[0]).append(line[1]);
        }
        card.addView(words);
        if (!record.optString("note").isBlank()) { TextView note = ui.text(record.optString("note"), FOOTNOTE, 500, ERROR); note.setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(4)); card.addView(note); }
        LinearLayout actions = ui.row(); actions.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        switch (state) {
            case dev.jun.gyeot.core.CalendarCards.READY -> { actions.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(8)); actions.addView(ui.chip("캘린더에 추가", () -> host.addEvent(index))); }
            case dev.jun.gyeot.core.CalendarCards.ADDING -> actions.addView(status(ui, System.currentTimeMillis() - record.optLong("at") < 60_000 ? "추가하는 중…" : "추가했는지 확인하지 못했어요 · 캘린더 앱에서 확인해 주세요"));
            case dev.jun.gyeot.core.CalendarCards.ADDED -> {
                actions.addView(status(ui, "추가했어요"));
                if (dev.jun.gyeot.core.CalendarCards.undoable(record, System.currentTimeMillis())) actions.addView(ui.ghost("되돌리기", () -> host.undoEvent(index)));
            }
            case dev.jun.gyeot.core.CalendarCards.UNDOING -> actions.addView(status(ui, "되돌리는 중…"));
            case dev.jun.gyeot.core.CalendarCards.UNDONE -> actions.addView(status(ui, "되돌렸어요 · 캘린더에서 지웠어요"));
            case dev.jun.gyeot.core.CalendarCards.GONE -> actions.addView(status(ui, "캘린더에 이미 없어요"));
            default -> { }
        }
        if (actions.getChildCount() > 0) card.addView(actions);
        card.setContentDescription((failed ? "일정을 추가할 수 없어요, " : "일정 추가, ") + said);
        return card;
    }
    private static TextView status(Ui ui, String text) { TextView view = ui.text(text, FOOTNOTE, 600, MUTED); view.setPadding(ui.dp(10), ui.dp(8), ui.dp(6), ui.dp(8)); return view; }
    /** A request that was never drawn (an older answer, or a cut-off reply): its prompt, quietly. */
    static View request(Context context, Ui ui, RichBlocks.Block block, Host host) {
        LinearLayout card = ui.column(); card.setBackground(surface(ui, R_CONTROL));
        card.addView(header(context, ui, "그림 요청 · 그리지 않았어요", R.drawable.ic_gallery));
        String prompt = dev.jun.gyeot.core.ChatImages.requests("```image\n" + block.text() + "\n```").stream().findFirst().map(a -> a.optString("prompt")).orElse(block.text().strip());
        TextView text = ui.text(prompt, FOOTNOTE, 450, MUTED); text.setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(12)); text.setTextIsSelectable(true); card.addView(text);
        return card;
    }

    /** A visual: a labelled card running the page inline (sized to its content) or, when not live, a card that opens it. */
    static View visual(Context context, Ui ui, RichBlocks.Block block, boolean live, Host host) {
        LinearLayout card = ui.column(); card.setBackground(surface(ui, R_GROUP - 4)); card.setClipToOutline(true);
        LinearLayout header = header(context, ui, "시각화 · " + block.label(), R.drawable.ic_sparkle);
        header.addView(ui.quietIcon(R.drawable.ic_copy, "시각화 코드 복사", () -> host.copy(block.text())), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)));
        header.addView(ui.quietIcon(R.drawable.ic_expand, "시각화 크게 보기", () -> host.expand(block)), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)));
        card.addView(header);
        WebView web = null;
        // A phone without a working WebView (being updated, disabled) still shows the answer: the visual's source instead.
        if (live) try { web = sandbox(context, host, false); } catch (RuntimeException unavailable) { return code(context, ui, new RichBlocks.Block(RichBlocks.Kind.CODE, block.lang(), block.text(), List.of()), host); }
        if (web != null) {
            card.addView(web, new LinearLayout.LayoutParams(-1, ui.dp(START_VISUAL_DP)));
            fit(web, ui);
            web.loadDataWithBaseURL(null, RichBlocks.page(block, theme(CARD)), "text/html", "utf-8", null);
        } else {
            TextView open = ui.text("눌러서 시각화 열기", SUBHEAD, 600, ACCENT); open.setGravity(Gravity.CENTER);
            open.setBackgroundColor(FILL); open.setMinHeight(ui.dp(112)); open.setOnClickListener(v -> host.expand(block)); Ui.pressable(open, true);
            open.setContentDescription("시각화 열기 · " + block.label());
            card.addView(open, new LinearLayout.LayoutParams(-1, -2));
        }
        card.setOnLongClickListener(v -> { host.menu(); return true; });
        return card;
    }
    static RichBlocks.Theme theme(int background) { return RichBlocks.Theme.of(DARK, background, TEXT, MUTED, ACCENT, FILL, SEPARATOR); }

    /**
     * A WebView that runs a model-written page and nothing more: scripts on, but no JavaScript bridge, no file, content,
     * storage, location or popups, and every request outside RichBlocks.allowed answered with an empty refusal. Links leave
     * only when the user taps them, and open outside the app.
     */
    @SuppressLint("SetJavaScriptEnabled")
    static WebView sandbox(Context context, Host host, boolean zoom) {
        Sandbox web = new Sandbox(context);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setDomStorageEnabled(false);
        s.setGeolocationEnabled(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false); s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(zoom); s.setBuiltInZoomControls(zoom); s.setDisplayZoomControls(false);
        web.setBackgroundColor(CARD); web.setVerticalScrollBarEnabled(zoom); web.setHorizontalScrollBarEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (RichBlocks.allowed(request.getUrl().toString())) return null;
                return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", Map.of(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (request.hasGesture() && (url.startsWith("https://") || url.startsWith("http://"))) host.open(url);
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) { if (view instanceof Sandbox page && page.fit != null) page.fit.run(); }
        });
        return web;
    }
    /** Grows or shrinks an inline visual to its content a few times while charts and fonts settle. */
    private static void fit(WebView web, Ui ui) {
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable measure = () -> {
            if (!web.isAttachedToWindow()) return;
            web.evaluateJavascript(RichBlocks.MEASURE, value -> {
                int css; try { css = (int) Math.ceil(Double.parseDouble(value == null ? "0" : value.replace("\"", ""))); } catch (NumberFormatException e) { return; }
                if (css <= 0) return;
                int height = Math.max(ui.dp(MIN_VISUAL_DP), Math.min(ui.dp(MAX_VISUAL_DP), ui.dp(css)));
                ViewGroup.LayoutParams p = web.getLayoutParams();
                if (p != null && Math.abs(p.height - height) > ui.dp(2)) { p.height = height; web.setLayoutParams(p); }
            });
        };
        if (web instanceof Sandbox page) page.fit = () -> { for (int delay : new int[]{0, 350, 1200, 2600}) handler.postDelayed(measure, delay); };
    }
    /** The sandboxed page view; fit runs when a page finishes loading. */
    static final class Sandbox extends WebView {
        Runnable fit;
        Sandbox(Context context) { super(context); }
    }

    /** Full screen: the visual with zoom, or its source; 완료 closes and the WebView is destroyed with the dialog. */
    static Dialog viewer(Activity activity, Ui ui, RichBlocks.Block block, Host host) {
        Dialog dialog = new Dialog(activity, DARK ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout page = ui.column(); page.setBackgroundColor(BG);
        LinearLayout bar = ui.row(); bar.setPadding(ui.dp(16), ui.dp(6), ui.dp(8), ui.dp(6)); bar.setMinimumHeight(ui.dp(56));
        TextView done = ui.text("완료", HEADLINE, 650, ACCENT); done.setMinHeight(ui.dp(48)); done.setGravity(Gravity.CENTER_VERTICAL);
        done.setOnClickListener(v -> dialog.dismiss()); Ui.pressable(done, true);
        bar.addView(done, new LinearLayout.LayoutParams(-2, -2));
        TextView title = ui.text("시각화 · " + block.label(), CALLOUT, 650, TEXT); title.setGravity(Gravity.CENTER); title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(ui.quietIcon(R.drawable.ic_copy, "시각화 코드 복사", () -> host.copy(block.text())), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        page.addView(bar);
        FrameLayout stage = new FrameLayout(activity);
        WebView web = sandbox(activity, host, true); web.setBackgroundColor(BG);
        web.loadDataWithBaseURL(null, RichBlocks.page(block, theme(BG)), "text/html", "utf-8", null);
        stage.addView(web, new FrameLayout.LayoutParams(-1, -1));
        ScrollView source = new ScrollView(activity); source.setVisibility(View.GONE);
        HorizontalScrollView wide = new HorizontalScrollView(activity);
        TextView code = ui.text(block.text(), 13.5f, 400, TEXT); code.setTypeface(Typeface.MONOSPACE); code.setTextIsSelectable(true);
        code.setHorizontallyScrolling(true); code.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        wide.addView(code); source.addView(wide); stage.addView(source, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout tabs = ui.row(); tabs.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8));
        tabs.addView(ui.segmented(new String[]{"미리보기", "코드"}, 0, index -> { web.setVisibility(index == 0 ? View.VISIBLE : View.GONE); source.setVisibility(index == 1 ? View.VISIBLE : View.GONE); }), new LinearLayout.LayoutParams(-1, -2));
        page.addView(tabs);
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        dialog.setContentView(page);
        dialog.setOnDismissListener(d -> { web.stopLoading(); web.destroy(); });
        return dialog;
    }

    /** Stops and frees every WebView under view; used when an answer leaves the screen. */
    static void destroy(View view) {
        if (view instanceof WebView web) { web.stopLoading(); web.destroy(); }
        else if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) destroy(group.getChildAt(i));
    }
    /** Pauses or resumes the pages under view with their screen, so hidden charts do not keep animating. */
    static void active(View view, boolean on) {
        if (view instanceof WebView web) { if (on) web.onResume(); else web.onPause(); }
        else if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) active(group.getChildAt(i), on);
    }
}
