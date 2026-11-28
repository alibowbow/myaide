package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.PageBook;
import dev.jun.gyeot.core.RichBlocks;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static dev.jun.gyeot.Ui.*;

/**
 * 페이지: two tabs at the top. 문서: the user's documents by category, made in the 작업창 and the main chat. 프로젝트: the user's
 * own small web apps (ProjectBook, opened full screen by ProjectActivity), made and changed by the AI from what the user asks.
 * In 문서 the list groups them by
 * category, with search (titles, categories and words, then the text itself) and category chips (a long press renames or
 * removes one). A page opens as answers are drawn: Markdown natively, HTML in the answers' sandbox (its own scripts, no app
 * bridge, nothing loaded beyond RichBlocks' allowlist) with its source a tab away. Rename, move, pin, share, copy and delete
 * are the user's own taps; nothing else here writes.
 */
public final class PagesActivity extends Activity {
    /** Opens one page (PageSaveSheet's 페이지에서 보기). */
    static final String OPEN = "gyeot.page_id";
    /** Markdown longer than FORMATTED is shown as plain text, and only its first SHOWN characters are drawn at all. */
    static final int FORMATTED = 150_000, SHOWN = 200_000;
    /** Markdown pages share their text; HTML and longer ones are shared as a file. */
    static final int SHARE_TEXT = 50_000;
    /** The most 소스 복사 puts on the clipboard: Android hands a clip to its clipboard in one binder call, and a whole page can be too big for one. */
    static final int COPY_LIMIT = 100_000;
    /** File work off the main thread; tests run it in place. */
    static Executor work;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Dialog> dialogs = new ArrayList<>();
    private Ui ui; private PageBook book; private MainNavigation navigation; private LinearLayout root, list, chips;
    private String category, query = "", openId;
    /** The open page as it was drawn: a change to another page leaves it (and where the user scrolled) alone. */
    private PageBook.Page drawn;
    private long shown = -1, searches, opened; private boolean destroyed, resumed;
    /** The top tabs: 0 문서 (the pages), 1 프로젝트 (the user's own web apps, ProjectActivity). */
    private int section; private ProjectMaker maker; private AppStore store;
    private final ProjectMaker.Listener projectsChanged = project -> { if (!destroyed && section == 1 && openId == null) showList(); };
    /** This screen's own changes on their way (main thread): the book's listener waits for them, so each is drawn once, by its own result. */
    private int changing;
    /** A line longer than this is not given to the Markdown patterns (some go slow on one very long line): the page is shown plain. */
    static final int LONG_LINE = 5_000;
    private final Runnable changed = () -> main.post(this::changedElsewhere), relist = this::listPages;

    /** The menu and rail look the same on every screen: opening and leaving this one swaps only the content. */
    private void noTransition() { if (android.os.Build.VERSION.SDK_INT >= 34) { overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0); overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0); } else overridePendingTransition(0, 0); }
    @Override public void finish() { super.finish(); noTransition(); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); noTransition();
        ui = new Ui(this); book = PageStore.book(this); store = ((GyeotApp) getApplication()).store(); maker = ProjectMaker.get(this);
        section = state != null ? state.getInt("section") : getIntent().hasExtra(OPEN) ? 0 : store.settings.getInt("pagesSection", 0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (state != null) { category = state.getString("category"); query = state.getString("query", ""); openId = state.getString("open"); }
        else openId = getIntent().getStringExtra(OPEN);
        root = ui.column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            v.setPadding(ui.dp(12) + bars.left, ui.dp(8) + bars.top, ui.dp(12) + bars.right, ui.dp(8) + bottom); return insets;
        });
        navigation = new MainNavigation(this, root, "pages", target -> MainNavigation.open(this, target), false);
        setContentView(navigation); root.requestApplyInsets();
        book.listen(changed);
        if (openId != null && book.page(openId) != null) showPage(openId); else showList();
        confirmShown();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        String id = intent.getStringExtra(OPEN);
        if (id != null) { closeDialogs(); showPage(id); }
        confirmShown();
    }
    /** A voice request to open 페이지 (app_open_screen pages) is answered once this screen is really in front. */
    private void confirmShown() { root.post(() -> { if (!destroyed && hasWindowFocus()) AppNavigator.shown(getIntent(), dev.jun.gyeot.core.AppNavigation.PAGES); }); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("category", category); state.putString("query", query); state.putString("open", openId); state.putInt("section", section);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; AppNavigator.inFront("pages"); RichAnswer.active(root, true); maker.watch(projectsChanged);
        if (section == 1 && openId == null) showList();
        else if (shown != book.version()) changedElsewhere();
    }
    @Override protected void onPause() { resumed = false; maker.unwatch(projectsChanged); RichAnswer.active(root, false); if (!isFinishing()) AppNavigator.inFront("pages"); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) AppNavigator.shown(getIntent(), dev.jun.gyeot.core.AppNavigation.PAGES); }
    @Override protected void onDestroy() {
        // shutdown, not shutdownNow: a change the user already confirmed (a delete, a move) still runs; nothing is drawn after this.
        destroyed = true; book.unlisten(changed); main.removeCallbacks(relist); closeDialogs(); RichAnswer.destroy(root); worker.shutdown(); super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (navigation != null && navigation.close()) return;
        if (openId != null) { showList(); return; }
        if (section == 0 && !query.isEmpty()) { query = ""; showList(); return; }
        if (!HtmlWallpaper.backHome(this)) super.onBackPressed();
    }

    /** A change saved somewhere else (the main chat, the 작업창, another sheet): the list is drawn again, an open page too. */
    private void changedElsewhere() {
        if (destroyed || changing > 0 || shown == book.version()) return;
        if (openId != null) {
            PageBook.Page now = book.page(openId);
            // Changed while the screen is away: shown stays behind, so onResume draws it then.
            if (now == null) showList(); else if (now.equals(drawn)) shown = book.version(); else if (resumed) showPage(openId);
        }
        else if (list != null) listPages();
    }

    // ── The list ────────────────────────────────────────────────────────────────────────────────────────

    private void showList() {
        openId = null; drawn = null; AppNavigator.pageLeft = null; RichAnswer.destroy(root); root.removeAllViews();
        // No 돌아가기 row: the app menu (☰ on a phone, the rail on a wide screen) leads away, and Android's back still does.
        // The title with the small 문서 · 프로젝트 switch at the end of its line: compact, not a full-width bar of its own.
        LinearLayout titleRow = ui.row(); titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = ui.title("페이지", 26); title.setAccessibilityHeading(true); titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (section == 0) titleRow.addView(ui.quietIcon(R.drawable.ic_add, "새 카테고리 만들기", this::addCategory), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        titleRow.addView(ui.switcher(new String[]{"문서", "프로젝트"}, section, i -> { section = i; store.settings.edit().putInt("pagesSection", i).apply(); showList(); }), new LinearLayout.LayoutParams(-2, -2));
        ui.add(root, titleRow, 8);
        if (section == 1) { projectsList(); return; }
        ui.add(root, ui.label("작업창과 메인 대화에서 만든 문서를 카테고리별로 모아요.", 13, MUTED), 12);
        EditText search = ui.searchField(ui.input("제목·카테고리·내용 검색", 1, 100)); search.setText(query); search.setContentDescription("페이지 검색");
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            // Searching reads page files: it waits until typing pauses.
            @Override public void afterTextChanged(Editable s) { if (s.toString().equals(query)) return; query = s.toString(); main.removeCallbacks(relist); main.postDelayed(relist, 200); }
        });
        ui.add(root, search, 10);
        chips = ui.row(); HorizontalScrollView strip = new HorizontalScrollView(this); strip.setHorizontalScrollBarEnabled(false); strip.setClipToPadding(false); strip.addView(chips);
        ui.add(root, strip, 4);
        ScrollView scroll = new ScrollView(this); list = ui.column(); list.setPadding(0, 0, 0, ui.dp(24)); scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        listPages();
    }
    // ── 프로젝트 ────────────────────────────────────────────────────────────────────────────────────

    private static int projectHue(int index) { int[] hues = {INDIGO, TEAL, ORANGE, GREEN, BLUE, PURPLE, PINK, RED}; return hues[Math.floorMod(index, hues.length)]; }
    /** The user's projects as cards (two to a row on a wide screen), the newest first; 새 프로젝트 at the top. */
    private void projectsList() {
        maker.seed(store);
        List<dev.jun.gyeot.core.ProjectBook.Project> all = maker.book().projects();
        ScrollView scroll = new ScrollView(this); LinearLayout column = ui.column(); column.setPadding(0, 0, 0, ui.dp(24)); scroll.addView(column);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout top = ui.row();
        // Where they are: 들퍼레/프로젝트 (a file manager opens and changes them too), or the app alone until it is connected.
        String where = maker.book().where();
        TextView about = ui.label(where.isEmpty() ? "들퍼레 폴더를 연결하면 거기에 저장해요 · 연결하기" : "프로젝트 " + all.size() + "개 · " + where + " 폴더에 저장돼요", 13, where.isEmpty() ? ACCENT : MUTED);
        about.setMaxLines(2);
        if (where.isEmpty()) { about.setOnClickListener(v -> StorageSetupActivity.open(this)); Ui.pressable(about, true); }
        top.addView(about, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(ui.pill("새 프로젝트", R.drawable.ic_add, true, this::newProject), new LinearLayout.LayoutParams(-2, -2));
        ui.add(column, top, 12);
        syncProjects();
        if (all.isEmpty()) {
            LinearLayout card = ui.card();
            card.addView(ui.text("아직 프로젝트가 없어요", HEADLINE, 700, TEXT));
            TextView how = ui.text("‘새 프로젝트’를 눌러 만들고 싶은 것을 적으면 AI가 HTML 앱 하나로 만들어요. 열어서 ‘AI로 고치기’로 계속 다듬을 수 있어요.", SUBHEAD, 450, MUTED);
            how.setLineSpacing(ui.dp(3), 1); card.addView(how);
            column.addView(card, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            View card = projectCard(all.get(i), wide);
            if (!wide) { ui.add(column, card, 12); continue; }
            if (i % 2 == 0) { row = ui.row(); row.setGravity(android.view.Gravity.TOP); ui.add(column, row, 12); }
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, -2, 1); if (i % 2 == 1) at.leftMargin = ui.dp(12);
            row.addView(card, at);
            if (i == all.size() - 1 && i % 2 == 0) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        }
    }
    private long syncedAt;
    /** The list and 들퍼레/프로젝트 together, off the main thread (at most every few seconds): a change there redraws the list. */
    private void syncProjects() {
        long now = android.os.SystemClock.elapsedRealtime(); if (now - syncedAt < 4000) return; syncedAt = now;
        background(() -> { boolean changed = maker.book().sync(); if (changed) main.post(() -> { if (!destroyed && section == 1 && openId == null) showList(); }); });
    }
    private View projectCard(dev.jun.gyeot.core.ProjectBook.Project p, boolean wide) {
        LinearLayout card = ui.column(); card.setBackground(ui.glass(R_CARD, false)); card.setClipToOutline(true);
        FrameLayout preview = new FrameLayout(this);
        int hue = projectHue(p.hue());
        java.io.File thumb = maker.book().thumb(p.id());
        android.graphics.Bitmap picture = thumb.isFile() ? android.graphics.BitmapFactory.decodeFile(thumb.getPath()) : null;
        if (picture != null) {
            android.widget.ImageView image = new android.widget.ImageView(this); image.setImageBitmap(picture); image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            preview.addView(image, new FrameLayout.LayoutParams(-1, -1));
        } else {
            android.graphics.drawable.GradientDrawable wash = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.over(ui.tone(hue), DARK ? RAISED : CARD), Ui.over((hue & 0x00ffffff) | 0x40000000, DARK ? RAISED : CARD)});
            preview.setBackground(wash);
            android.widget.ImageView glyph = new android.widget.ImageView(this); glyph.setImageResource("trading".equals(p.kind()) ? R.drawable.ic_bolt : R.drawable.ic_globe);
            glyph.setImageTintList(android.content.res.ColorStateList.valueOf(hue | 0xff000000));
            preview.addView(glyph, new FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), android.view.Gravity.CENTER));
        }
        if (maker.busy(p.id()) || p.pinned()) {
            TextView badge = ui.badge(maker.busy(p.id()) ? "만드는 중" : "고정", maker.busy(p.id()) ? ACCENT : GRAY);
            FrameLayout.LayoutParams ba = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.START | android.view.Gravity.TOP); ba.leftMargin = ui.dp(12); ba.topMargin = ui.dp(12);
            preview.addView(badge, ba);
        }
        card.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(wide ? 120 : 128)));
        LinearLayout words = ui.column(); words.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
        TextView name = ui.text(p.title(), 16.5f, 700, TEXT); name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END); words.addView(name);
        if (!p.about().isBlank()) { TextView a = ui.text(p.about(), 13.5f, 450, MUTED); a.setMaxLines(2); a.setEllipsize(TextUtils.TruncateAt.END); a.setPadding(0, ui.dp(3), 0, 0); words.addView(a); }
        int versions = maker.book().versions(p.id()).length;
        TextView meta = ui.text(when(p.updated(), ZonedDateTime.now()) + (versions > 0 ? " · 고친 기록 " + versions : ""), 12, 500, MUTED); meta.setPadding(0, ui.dp(8), 0, 0); words.addView(meta);
        card.addView(words);
        card.setOnClickListener(v -> openProject(p.id()));
        card.setOnLongClickListener(v -> { projectMenu(v, p); return true; });
        card.setContentDescription(p.title() + ", " + p.about() + ", 열기"); Ui.pressable(card, true);
        return card;
    }
    private void openProject(String id) { startActivity(new Intent(this, ProjectActivity.class).putExtra(ProjectActivity.ID, id)); }
    private void projectMenu(View anchor, dev.jun.gyeot.core.ProjectBook.Project p) {
        ui.miniMenu(anchor, new String[]{"열기", p.pinned() ? "고정 해제" : "맨 위에 고정", "프로젝트 지우기"}, -1, i -> {
            if (i == 0) openProject(p.id());
            else if (i == 1) { try { maker.book().save(p.pin(!p.pinned())); } catch (IOException e) { toast(e.getMessage()); } showList(); }
            else confirm("‘" + p.title() + "’ 프로젝트를 지울까요? 들퍼레/프로젝트의 폴더와 이전 버전도 함께 지워져요.", "지우기", () -> {
                maker.stop(p.id()); try { maker.book().remove(p.id()); } catch (IOException e) { toast(e.getMessage()); } showList();
                // Its grants (window.gyeot) and the notifications it set for later go with it.
                android.content.Context app = getApplicationContext(); String key = "project:" + p.id();
                new Thread(() -> PageBridgeHost.forget(app, key), "project-forget").start(); });
        });
    }
    /** 새 프로젝트: what to make (the AI writes it), or a ready one to start from; the AI that makes it under the field. */
    private void newProject() {
        Ui.Sheet sheet = ui.selectionSheet("새 프로젝트", "AI가 HTML 웹 앱 하나로 만들어요");
        EditText what = ui.input("무엇을 만들까요? · 예: 달러·엔 환율 계산기, 운동 기록장, 코인 김치 프리미엄 표", 4, 2000);
        sheet.body.addView(what);
        EditText name = ui.input("이름 · 비우면 AI가 정해요", 1, 60);
        LinearLayout.LayoutParams na = new LinearLayout.LayoutParams(-1, -2); na.topMargin = ui.dp(10); sheet.body.addView(name, na);
        LinearLayout line = ui.row(); line.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
        TextView label = ui.text("만드는 AI", 13, 500, MUTED); line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView modelLine = ui.text("", 13.5f, 500, TEXT); modelLine.setSingleLine(true); modelLine.setEllipsize(TextUtils.TruncateAt.END); modelLine.setMinHeight(ui.dp(44)); modelLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
        modelLine.setPadding(ui.dp(10), 0, ui.dp(4), 0);
        Runnable paint = () -> modelLine.setText(ProjectActivity.modelText(store)); paint.run();
        modelLine.setOnClickListener(v -> ProjectActivity.chooseModel(this, ui, store, v, paint)); Ui.pressable(modelLine, true);
        line.addView(modelLine, new LinearLayout.LayoutParams(-2, -2));
        sheet.body.addView(line);
        sheet.body.addView(ui.sectionHeader("바로 시작하기"));
        LinearLayout group = ui.group();
        for (ProjectMaker.Template t : ProjectMaker.TEMPLATES)
            ui.addRow(group, ui.listRow(R.drawable.ic_bolt, projectHue(t.hue()), t.title(), t.about(), ui.chevron(), () -> {
                try { dev.jun.gyeot.core.ProjectBook.Project made = maker.fromTemplate(t); sheet.dismiss(); openProject(made.id()); }
                catch (Exception e) { toast(e.getMessage()); }
            }), true);
        sheet.body.addView(group);
        sheet.primary.setText("AI로 만들기");
        sheet.primary.setOnClickListener(v -> {
            String request = what.getText().toString().strip(), title = name.getText().toString().strip();
            if (request.isEmpty()) { toast("무엇을 만들지 적어 주세요."); what.requestFocus(); return; }
            try {
                dev.jun.gyeot.core.ProjectBook.Project made = maker.book().create(title, request, "app", "", (int) (System.currentTimeMillis() / 1000 % 8));
                if (!maker.make(made.id(), request, title.isEmpty())) { toast("만들기를 시작하지 못했어요."); return; }
                sheet.dismiss(); openProject(made.id());
            } catch (Exception e) { toast(e.getMessage()); }
        });
        dialogs.add(sheet); sheet.show();
    }

    private void listPages() {
        if (list == null || destroyed) return;
        shown = book.version();
        if (category != null && find(category) == null) category = null; // removed or renamed elsewhere
        renderChips();
        list.removeAllViews();
        View everywhere = SearchActivity.link(this, ui, query); if (everywhere != null) ui.add(list, everywhere, 12);
        if (!book.problem().isEmpty()) {
            ui.add(list, ui.banner("페이지 목록 파일을 읽지 못했어요. 원본 파일은 그대로 두었고, 고칠 때까지 새로 저장하지 않아요. (" + book.problem() + ")", ERROR), 12);
            return;
        }
        String words = query.strip();
        if (words.isEmpty()) { pages(category == null ? book.pages() : book.pages(category)); return; }
        long generation = ++searches; String scope = category;
        ui.add(list, ui.label("찾는 중…", 13, MUTED), 8);
        background(() -> {
            List<PageBook.Page> found = book.find(words, scope, 200);
            main.post(() -> {
                if (destroyed || generation != searches || list == null || openId != null) return;
                list.removeAllViews();
                View all = SearchActivity.link(this, ui, words); if (all != null) ui.add(list, all, 12);
                if (found.isEmpty()) { ui.add(list, ui.label("‘" + words + "’이(가) 들어간 페이지가 없어요.", 14, MUTED), 8); return; }
                list.addView(ui.sectionHeader("찾은 페이지 · " + found.size()));
                LinearLayout group = ui.group();
                for (PageBook.Page page : found) ui.addRow(group, row(page, true), true);
                list.addView(group);
            });
        });
    }
    private void pages(List<PageBook.Page> pages) {
        if (pages.isEmpty()) { empty(); return; }
        if (category != null) {
            LinearLayout group = ui.group();
            for (PageBook.Page page : pages) ui.addRow(group, row(page, false), true);
            ui.add(list, group, 8);
            return;
        }
        for (PageBook.Category c : book.categories()) {
            if (c.count() == 0) continue;
            list.addView(ui.sectionHeader(c.name() + " · " + c.count()));
            LinearLayout group = ui.group();
            for (PageBook.Page page : book.pages(c.name())) ui.addRow(group, row(page, false), true);
            list.addView(group);
        }
    }
    private void empty() {
        LinearLayout card = ui.card();
        if (category != null) card.addView(ui.text("이 카테고리에는 아직 페이지가 없어요.", BODY, 520, TEXT));
        else {
            card.addView(ui.text("아직 페이지가 없어요", HEADLINE, 700, TEXT));
            TextView how = ui.text("작업창이나 메인 대화에서 “이 내용 페이지에 저장해 줘”라고 하거나, 답 아래 더 보기(⋯)의 ‘페이지에 저장’을 눌러 보세요. Markdown 글과 HTML 문서를 카테고리별로 모아 둘 수 있어요.", SUBHEAD, 450, MUTED);
            how.setLineSpacing(ui.dp(3), 1); card.addView(how);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(12); list.addView(card, p);
    }
    private View row(PageBook.Page page, boolean withCategory) {
        String meta = (withCategory ? page.category() + " · " : "") + when(page.updated(), ZonedDateTime.now()) + (page.preview().isBlank() ? "" : " · " + page.preview());
        LinearLayout row = ui.listRow(page.html() ? R.drawable.ic_globe : R.drawable.ic_pages, page.html() ? PURPLE : BLUE, page.title(), meta,
            page.pinned() ? ui.badge("고정", ACCENT) : ui.chevron(), () -> showPage(page.id()));
        TextView name = ((Ui.ListRow) row).parts.title(); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        row.setOnLongClickListener(v -> { pageMenu(page.id()); return true; });
        row.setContentDescription(page.title() + (page.pinned() ? ", 고정됨" : "") + ", " + meta + ", " + (page.html() ? "HTML" : "Markdown"));
        return row;
    }
    private void renderChips() {
        if (chips == null) return;
        chips.removeAllViews();
        chips.addView(chip("전체 " + book.pages().size(), category == null, () -> select(null), null));
        for (PageBook.Category c : book.categories()) chips.addView(chip(c.name() + " " + c.count(), c.name().equals(category), () -> select(c.name()), () -> categoryMenu(c.name())));
    }
    /** A filter capsule as Ui.pills draws them (the chosen one inverted, ink on paper, so the accent stays for actions). */
    private TextView chip(String label, boolean on, Runnable tap, Runnable hold) {
        TextView chip = ui.text(label, SUBHEAD, on ? 680 : 560, on ? BG : TEXT); chip.setGravity(android.view.Gravity.CENTER);
        // The capsule first: a background with insets replaces the padding set before it.
        chip.setBackground(ui.capsule(on ? TEXT : FILL)); chip.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7)); chip.setMinimumHeight(ui.dp(42));
        chip.setSelected(on); chip.setFocusable(true);
        chip.setContentDescription(label + (on ? ", 선택됨" : "") + (hold == null ? "" : ", 길게 누르면 이름 바꾸기와 삭제"));
        chip.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); info.setSelected(host.isSelected());
            }
        });
        chip.setOnClickListener(v -> tap.run()); Ui.pressable(chip, false);
        if (hold != null) chip.setOnLongClickListener(v -> { hold.run(); return true; });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = ui.dp(6); chip.setLayoutParams(p);
        return chip;
    }
    private void select(String name) { category = name; listPages(); }
    private PageBook.Category find(String name) {
        for (PageBook.Category c : book.categories()) if (c.name().equals(name)) return c;
        return null;
    }

    // ── Categories ──────────────────────────────────────────────────────────────────────────────────────

    private void categoryMenu(String name) {
        PageBook.Category c = find(name); if (c == null) return;
        Ui.Sheet sheet = sheet("카테고리 · " + c.name(), c.count() == 0 ? "비어 있어요" : "페이지 " + c.count() + "개");
        LinearLayout group = ui.group();
        item(group, sheet, R.drawable.ic_tune, ORANGE, "이름 바꾸기", "이미 있는 이름이면 두 카테고리를 합쳐요", () -> renameCategory(c.name()));
        sheet.body.addView(group);
        boolean fallback = c.name().equals(PageBook.DEFAULT_CATEGORY);
        if (!fallback || c.count() == 0) {
            LinearLayout danger = ui.group();
            danger(item(danger, sheet, R.drawable.ic_close, RED, "카테고리 삭제", c.count() == 0 ? null : "페이지 " + c.count() + "개는 ‘" + PageBook.DEFAULT_CATEGORY + "’로 옮겨요", () -> confirm(
                c.count() == 0 ? "‘" + c.name() + "’ 카테고리를 지울까요?" : "‘" + c.name() + "’ 카테고리를 지울까요? 페이지 " + c.count() + "개는 지우지 않고 ‘" + PageBook.DEFAULT_CATEGORY + "’로 옮겨요.", "카테고리 삭제",
                () -> change(() -> book.deleteCategory(c.name()), moved -> "카테고리를 지웠어요"))));
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(12); sheet.body.addView(danger, gap);
        } else sheet.body.addView(ui.footer("‘" + PageBook.DEFAULT_CATEGORY + "’는 비어 있을 때만 지울 수 있어요."));
        sheet.show();
    }
    private void renameCategory(String name) {
        ask("카테고리 이름 바꾸기", "이미 있는 이름을 적으면 두 카테고리를 합쳐요", name, PageBook.CATEGORY_LIMIT, "바꾸기", typed -> change(() -> book.renameCategory(name, typed), target -> {
            if (name.equals(category)) category = target;
            return "‘" + target + "’(으)로 바꿨어요";
        }));
    }
    private void addCategory() {
        ask("새 카테고리", "페이지를 옮기거나 저장할 때 고를 수 있어요", "", PageBook.CATEGORY_LIMIT, "만들기", typed -> change(() -> book.addCategory(typed), made -> {
            category = made;
            return "‘" + made + "’ 카테고리를 만들었어요";
        }));
    }

    // ── One page ────────────────────────────────────────────────────────────────────────────────────────

    private void showPage(String id) {
        PageBook.Page page = book.page(id);
        if (page == null) { toast("페이지를 찾지 못했어요. 지워졌을 수 있어요."); showList(); return; }
        openId = id; drawn = page; AppNavigator.pageLeft = id; list = null; chips = null; shown = book.version(); main.removeCallbacks(relist);
        long generation = ++opened;
        RichAnswer.destroy(root); root.removeAllViews();
        LinearLayout heading = ui.row();
        heading.addView(back("페이지", this::showList), new LinearLayout.LayoutParams(-2, ui.dp(48)));
        heading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        // 질문하기: the page's main action. The main chat opens about this page: it answers from the page, and fixes it or adds
        // to it when asked.
        TextView ask = ui.text("질문하기", FOOTNOTE, 650, ACCENT); ask.setGravity(android.view.Gravity.CENTER); ask.setSingleLine(true);
        ask.setBackground(new android.graphics.drawable.InsetDrawable(ui.shape(ACCENT_SOFT, PILL), 0, ui.dp(6), 0, ui.dp(6))); ask.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        ask.setContentDescription("질문하기: 이 페이지에 대해 AI와 대화하고, 고치거나 내용을 더해 달라고 할 수 있어요"); ask.setFocusable(true);
        ask.setOnClickListener(v -> askAbout(id)); Ui.pressable(ask, false);
        LinearLayout.LayoutParams askAt = new LinearLayout.LayoutParams(-2, ui.dp(48)); askAt.rightMargin = ui.dp(4); heading.addView(ask, askAt);
        heading.addView(ui.quietIcon(R.drawable.ic_share, "공유", () -> share(id)), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        heading.addView(ui.quietIcon(R.drawable.ic_copy, "소스 복사", () -> copyBody(id)), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        heading.addView(ui.quietIcon(R.drawable.ic_more, "이 페이지 더 보기: 이름 바꾸기, 카테고리 옮기기, 고정, 삭제", () -> pageMenu(id)), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        root.addView(heading);
        TextView title = ui.title(page.title(), 24); title.setTextIsSelectable(true); title.setAccessibilityHeading(true); ui.add(root, title, 4);
        TextView meta = ui.label(page.category() + " · " + when(page.updated(), ZonedDateTime.now()) + " · " + PageStore.source(page.source()) + (page.pinned() ? " · 고정" : ""), 13, MUTED);
        // The page's facts on the left; on the right, for an HTML page, the small switch between how it looks and its source.
        LinearLayout facts = ui.row(); facts.setGravity(android.view.Gravity.CENTER_VERTICAL); facts.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout views = new FrameLayout(this); LinearLayout.LayoutParams viewsAt = new LinearLayout.LayoutParams(-2, -2); viewsAt.leftMargin = ui.dp(10); facts.addView(views, viewsAt);
        ui.add(root, facts, 10);
        FrameLayout stage = new FrameLayout(this); root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        stage.addView(ui.label("여는 중…", 13, MUTED));
        background(() -> {
            String text = null, problem = null;
            try { text = book.body(id); } catch (IOException e) { problem = e.getMessage(); }
            String body = text, error = problem;
            main.post(() -> {
                // Only the page drawn last: an earlier load of it (drawn again meanwhile) would put a WebView into a stage no longer shown.
                if (destroyed || generation != opened) return;
                stage.removeAllViews();
                if (body == null) { stage.addView(ui.banner(error == null ? "페이지를 열지 못했어요." : error, ERROR)); return; }
                stage.addView(page.html() ? html(body, views) : markdown(body), new FrameLayout.LayoutParams(-1, -1));
            });
        });
    }
    /**
     * 질문하기: the main chat becomes a conversation about this page (PageStore.about) and opens with the keyboard up. Nothing
     * of the page is copied into the message: each reply reads the page as it is then (page_read), so a change made in the
     * conversation, or here, is what the next answer sees.
     */
    private void askAbout(String id) {
        if (book.page(id) == null) { toast("페이지를 찾지 못했어요. 지워졌을 수 있어요."); return; }
        PageStore.about(this, "main", id);
        ((GyeotApp) getApplication()).store().mainWrite = true;
        MainNavigation.open(this, "chat");
    }
    private View markdown(String body) {
        ScrollView scroll = new ScrollView(this); LinearLayout column = ui.column(); column.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(32));
        if (body.length() <= FORMATTED && !longLine(body)) column.addView(RichAnswer.build(this, ui, withoutTitle(body, drawn == null ? "" : drawn.title()), 4, host()));
        else {
            ui.add(column, ui.label(body.length() > SHOWN ? "아주 긴 페이지라 처음 " + String.format(Locale.ROOT, "%,d", SHOWN) + "자만 서식 없이 보여 줘요. 전체는 공유로 볼 수 있어요." : body.length() > FORMATTED ? "긴 페이지라 서식 없이 보여 줘요." : "아주 긴 줄이 있어서 서식 없이 보여 줘요.", 12, MUTED), 10);
            TextView plain = ui.text(cut(body, SHOWN), BODY, 420, TEXT); plain.setTextIsSelectable(true); plain.setLineSpacing(ui.dp(4), 1); column.addView(plain);
        }
        scroll.addView(column); return scroll;
    }
    /**
     * An HTML page: it runs in the sandbox, and its source (the HTML as written) is one tap away through the small switch
     * put into `views`, at the end of the page's line of facts. 소스, not 원문: the page's original is the material it was
     * made from, and this is the page's own code.
     */
    private View html(String body, FrameLayout views) {
        LinearLayout column = ui.column();
        View source = source(body);
        WebView web;
        try {
            web = RichAnswer.sandbox(this, host(), true); web.setBackgroundColor(BG);
            web.loadDataWithBaseURL(null, RichBlocks.page(new RichBlocks.Block(RichBlocks.Kind.HTML, "html", body, List.of()), RichAnswer.theme(BG)), "text/html", "utf-8", null);
        } catch (RuntimeException unavailable) {
            // A phone without a working WebView (being updated, disabled) still shows the page: its source.
            ui.add(column, ui.banner("이 기기에서 웹 화면을 열지 못해 소스를 보여 줘요.", WARNING), 8);
            column.addView(source, new LinearLayout.LayoutParams(-1, 0, 1));
            return column;
        }
        WebView page = web;
        views.removeAllViews();
        views.addView(ui.switcher(new String[]{"미리보기", "소스"}, 0, index -> { page.setVisibility(index == 0 ? View.VISIBLE : View.GONE); source.setVisibility(index == 1 ? View.VISIBLE : View.GONE); }), new FrameLayout.LayoutParams(-2, -2));
        FrameLayout stage = new FrameLayout(this);
        stage.addView(web, new FrameLayout.LayoutParams(-1, -1));
        source.setVisibility(View.GONE); stage.addView(source, new FrameLayout.LayoutParams(-1, -1));
        column.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        return column;
    }
    private View source(String body) {
        ScrollView scroll = new ScrollView(this); HorizontalScrollView wide = new HorizontalScrollView(this);
        TextView code = ui.text(cut(body, SHOWN), 13.5f, 400, TEXT); code.setTypeface(Typeface.MONOSPACE); code.setTextIsSelectable(true);
        code.setHorizontallyScrolling(true); code.setPadding(ui.dp(4), ui.dp(8), ui.dp(16), ui.dp(24));
        wide.addView(code); scroll.addView(wide); return scroll;
    }
    private RichAnswer.Host host() {
        return new RichAnswer.Host() {
            @Override public void copy(String text) { clip(text); }
            @Override public void open(String url) { link(url); }
            @Override public void expand(RichBlocks.Block block) {
                try { Dialog viewer = RichAnswer.viewer(PagesActivity.this, ui, block, this); dialogs.add(viewer); viewer.show(); }
                catch (RuntimeException unavailable) { toast("이 기기에서 시각화를 열지 못했어요."); }
            }
            @Override public void menu() { if (openId != null) pageMenu(openId); }
            @Override public boolean selectable() { return true; }
        };
    }
    private void pageMenu(String id) {
        PageBook.Page page = book.page(id); if (page == null) return;
        Ui.Sheet sheet = sheet(page.title(), page.category() + " · " + when(page.updated(), ZonedDateTime.now()));
        if (sheet.subheading != null) sheet.subheading.setMaxLines(2);
        LinearLayout group = ui.group();
        if (openId == null) item(group, sheet, R.drawable.ic_eye, BLUE, "열기", null, () -> showPage(id));
        item(group, sheet, R.drawable.ic_tune, ORANGE, "이름 바꾸기", null, () -> ask("이름 바꾸기", null, page.title(), PageBook.TITLE_LIMIT, "저장", typed -> change(() -> book.rename(id, typed), renamed -> "이름을 바꿨어요")));
        item(group, sheet, R.drawable.ic_folder, TEAL, "카테고리 옮기기", "지금: " + page.category(), () -> move(id));
        item(group, sheet, R.drawable.ic_check, INDIGO, page.pinned() ? "고정 해제" : "고정", page.pinned() ? null : "카테고리 맨 위에 둬요", () -> change(() -> book.pin(id, !page.pinned()), pinned -> pinned.pinned() ? "고정했어요" : "고정을 풀었어요"));
        item(group, sheet, R.drawable.ic_share, GREEN, "공유", page.html() || page.chars() > SHARE_TEXT ? "파일로 보내요" : "글 그대로 보내요", () -> share(id));
        item(group, sheet, R.drawable.ic_copy, BLUE, "소스 복사", page.html() ? "HTML 그대로" : "Markdown 그대로", () -> copyBody(id));
        sheet.body.addView(group);
        LinearLayout danger = ui.group();
        danger(item(danger, sheet, R.drawable.ic_close, RED, "삭제", null, () -> confirm("‘" + page.title() + "’ 페이지를 지울까요? 되돌릴 수 없어요.", "삭제", () -> change(() -> { book.delete(id); return id; }, deleted -> "페이지를 지웠어요"))));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(12); sheet.body.addView(danger, gap);
        sheet.body.addView(ui.sectionHeader("이 페이지의 정보"));
        TextView info = ui.text((page.html() ? "HTML" : "Markdown") + " · " + String.format(Locale.ROOT, "%,d", page.chars()) + "자\n"
            + "만든 곳 · " + PageStore.source(page.source()) + (page.origin().isBlank() ? "" : " · " + page.origin()) + "\n"
            + "만든 때 · " + when(page.created(), ZonedDateTime.now()) + "\n고친 때 · " + when(page.updated(), ZonedDateTime.now()), FOOTNOTE, 450, MUTED);
        info.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(4)); info.setLineSpacing(ui.dp(4), 1); info.setTextIsSelectable(true); sheet.body.addView(info);
        sheet.show();
    }
    private void move(String id) {
        PageBook.Page page = book.page(id); if (page == null) return;
        Ui.Sheet sheet = ui.selectionSheet("카테고리 옮기기", page.title()); dialogs.add(sheet);
        PageSaveSheet.Picker picker = new PageSaveSheet.Picker(this, ui, book.categories(), page.category());
        sheet.body.addView(picker.view);
        sheet.primary.setText("옮기기");
        sheet.primary.setOnClickListener(v -> {
            String name = picker.value();
            if (name == null) { picker.fresh.setError("새 카테고리 이름을 적어 주세요"); picker.fresh.requestFocus(); return; }
            sheet.dismiss();
            change(() -> book.move(id, name), moved -> "‘" + moved.category() + "’(으)로 옮겼어요");
        });
        sheet.show();
    }
    private void share(String id) {
        PageBook.Page page = book.page(id); if (page == null) return;
        Intent send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, page.title());
        if (!page.html() && page.chars() <= SHARE_TEXT) {
            background(() -> {
                String body; try { body = book.body(id); } catch (IOException e) { main.post(() -> toast(e.getMessage())); return; }
                main.post(() -> { if (!destroyed) chooser(send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)); });
            });
            return;
        }
        android.net.Uri uri = PageFiles.uri(id);
        send.setType(PageFiles.mime(page)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(PageFiles.name(page), uri));
        chooser(send);
    }
    private void chooser(Intent send) {
        try { startActivity(Intent.createChooser(send, "페이지 공유").addFlags(send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (RuntimeException none) { toast("공유할 앱을 찾지 못했어요."); }
    }
    private void copyBody(String id) {
        PageBook.Page page = book.page(id);
        if (page != null && page.chars() > COPY_LIMIT) { toast("페이지가 길어서 복사하지 않았어요. 공유로 파일을 보내 주세요."); return; }
        background(() -> {
            String body; try { body = book.body(id); } catch (IOException e) { main.post(() -> toast(e.getMessage())); return; }
            main.post(() -> { if (!destroyed) clip(body); });
        });
    }

    // ── Small parts ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * A change by the user's tap: the file work off the main thread, then, on the main thread, done turns its result into one
     * short line (and may select what it made) before the screen is drawn again. Only the book is touched off the main thread.
     */
    interface Change<T> { T run() throws IOException; }
    private <T> void change(Change<T> work, java.util.function.Function<T, String> done) {
        changing++;
        background(() -> {
            T result = null; String problem = null;
            try { result = work.run(); }
            catch (IOException failed) { problem = "페이지 파일을 저장하지 못했어요. 저장 공간을 확인해 주세요."; }
            catch (RuntimeException refused) { problem = refused.getMessage() == null ? "바꾸지 못했어요." : refused.getMessage(); }
            T value = result; String error = problem;
            main.post(() -> {
                changing--;
                if (destroyed) return;
                if (error != null) { toast(error); changedElsewhere(); return; }
                // The result first (it may select the category it made or renamed), then one redraw, in place where it can be:
                // the list keeps its scroll position, and an open page is drawn again only when it changed.
                toast(done.apply(value));
                PageBook.Page open = openId == null ? null : book.page(openId);
                if (openId != null && open == null) showList();
                else if (open != null) { if (open.equals(drawn)) shown = book.version(); else showPage(openId); }
                else if (list != null) listPages(); else showList();
            });
        });
    }
    private void background(Runnable task) {
        Executor executor = work != null ? work : worker;
        try { executor.execute(task); } catch (java.util.concurrent.RejectedExecutionException closing) { /* the screen is closing */ }
    }
    /** A one-field sheet (rename, a new category); action runs with the typed text when the button is pressed. */
    private void ask(String title, String subtitle, String value, int limit, String action, java.util.function.Consumer<String> done) {
        Ui.Sheet sheet = ui.selectionSheet(title, subtitle); dialogs.add(sheet);
        EditText field = ui.input(title, 1, limit); field.setText(value); field.setSelectAllOnFocus(true); field.setContentDescription(title);
        sheet.body.addView(field);
        sheet.primary.setText(action);
        sheet.primary.setOnClickListener(v -> {
            String typed = field.getText().toString().strip();
            if (typed.isEmpty()) { field.setError("이름을 적어 주세요"); return; }
            sheet.dismiss(); done.accept(typed);
        });
        sheet.show(); field.requestFocus();
    }
    private void confirm(String message, String action, Runnable run) { Ui.Sheet sheet = ui.confirm(message, action, run); dialogs.add(sheet); sheet.show(); }
    private Ui.Sheet sheet(String title, String subtitle) { Ui.Sheet sheet = ui.selectionSheet(title, subtitle); sheet.primary.setVisibility(View.GONE); dialogs.add(sheet); return sheet; }
    private LinearLayout item(LinearLayout group, Dialog sheet, int icon, int tint, String title, String subtitle, Runnable action) {
        LinearLayout row = ui.listRow(icon, tint, title, subtitle, null, () -> { sheet.dismiss(); action.run(); });
        ((Ui.ListRow) row).parts.title().setMaxLines(2);
        ui.addRow(group, row, true); return row;
    }
    private static void danger(LinearLayout row) { ((Ui.ListRow) row).parts.title().setTextColor(ERROR); }
    private View back(String to, Runnable action) {
        Button back = ui.chip(to, action); back.setContentDescription(to.equals("돌아가기") ? "돌아가기" : to + "(으)로 돌아가기");
        android.graphics.drawable.Drawable chevron = getDrawable(R.drawable.ic_back).mutate(); chevron.setTint(ACCENT); chevron.setBounds(0, 0, ui.dp(24), ui.dp(24));
        back.setCompoundDrawablesRelative(chevron, null, null, null); back.setBackground(ui.shape(android.graphics.Color.TRANSPARENT, PILL));
        back.setTextColor(ACCENT); back.setPadding(ui.dp(12), 0, ui.dp(14), 0); back.setCompoundDrawablePadding(ui.dp(2));
        return back;
    }
    private void closeDialogs() { for (Dialog dialog : dialogs) if (dialog.isShowing()) dialog.dismiss(); dialogs.clear(); }
    private void clip(String text) {
        if (text.length() > COPY_LIMIT) { toast("너무 길어서 복사하지 않았어요. 공유로 파일을 보내 주세요."); return; }
        try { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("페이지", text)); toast("복사했어요"); }
        catch (RuntimeException tooLarge) { toast("복사하지 못했어요. 공유로 보내 주세요."); }
    }
    private void link(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (RuntimeException none) { toast("링크를 열 앱을 찾지 못했어요."); }
    }
    private void toast(String text) { if (!destroyed && text != null && !text.isBlank()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    /** A Markdown page that opens with its own title as a heading (# or ##) shows the title once, as the page's. By hand: no pattern to backtrack. */
    static String withoutTitle(String body, String title) {
        if (title.isBlank()) return body;
        int start = 0;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) start++;
        int hashes = start;
        while (hashes < body.length() && body.charAt(hashes) == '#' && hashes - start < 3) hashes++;
        if (hashes == start || hashes - start > 2 || hashes >= body.length() || (body.charAt(hashes) != ' ' && body.charAt(hashes) != '\t')) return body;
        int end = body.indexOf('\n', hashes); if (end < 0) end = body.length();
        String said = dev.jun.gyeot.core.Markdown.plain(dev.jun.gyeot.core.PageBook.heading(body.substring(hashes, end))).replaceAll("\\s+", " ").strip();
        return said.equals(title.strip()) ? body.substring(Math.min(body.length(), end + 1)) : body;
    }
    static boolean longLine(String text) {
        for (int at = 0; at < text.length(); ) { int end = text.indexOf('\n', at); if (end < 0) end = text.length(); if (end - at > LONG_LINE) return true; at = end + 1; }
        return false;
    }
    /** At most limit characters, never ending inside a character. */
    static String cut(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = limit; if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
    /** 오늘 오후 2:05, 어제, 11월 16일, 2025년 3월 2일. */
    static String when(long millis, ZonedDateTime now) {
        if (millis <= 0) return "";
        ZonedDateTime at = Instant.ofEpochMilli(millis).atZone(now.getZone());
        LocalDate day = at.toLocalDate(), today = now.toLocalDate();
        if (day.equals(today)) { int h = at.getHour(); return "오늘 " + (h < 12 ? "오전 " : "오후 ") + (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(Locale.ROOT, "%02d", at.getMinute()); }
        if (day.equals(today.minusDays(1))) return "어제";
        if (day.getYear() == today.getYear()) return day.getMonthValue() + "월 " + day.getDayOfMonth() + "일";
        return day.getYear() + "년 " + day.getMonthValue() + "월 " + day.getDayOfMonth() + "일";
    }
}
