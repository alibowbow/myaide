package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.ProjectBook;
import dev.jun.gyeot.core.WorkBook;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import static dev.jun.gyeot.Ui.*;

/**
 * One 프로젝트, full screen: its HTML running as a small web app (the network open, as a browser tab: charts and data load;
 * no app bridge, no files; a link leaving it opens in the app's browser), a slim bar over it, and AI로 고치기, the composer
 * that asks the projects' AI to change it (ProjectMaker), shown when asked for. Each change keeps the version before it.
 */
public final class ProjectActivity extends Activity implements ProjectMaker.Listener {
    static final String ID = "gyeot.project_id";
    private static final String BASE = "https://projects.gyeot.invalid/";
    private Ui ui; private AppStore store; private ProjectMaker maker; private ProjectBook book;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String id; private boolean destroyed, showingSource, composerOpen;
    private LinearLayout root, composer; private FrameLayout stage; private WebView web; private View source, making;
    private TextView title, subtitle, model, busyText; private EditText input; private ImageView send;
    private LinearLayout busyBar; private String loaded = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this); store = ((GyeotApp) getApplication()).store(); maker = ProjectMaker.get(this); book = maker.book();
        id = getIntent().getStringExtra(ID);
        if (id == null || book.project(id) == null) { Toast.makeText(this, "프로젝트를 찾지 못했어요.", Toast.LENGTH_SHORT).show(); finish(); return; }
        composerOpen = book.html(id).isBlank() && !maker.busy(id);
        root = ui.column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)); return insets;
        });
        setContentView(root); root.requestApplyInsets();
        build();
    }
    @Override protected void onResume() { super.onResume(); AppNavigator.inFront("pages"); maker.watch(this); refresh(); }
    @Override protected void onPause() { maker.unwatch(this); snapshot(); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; if (web != null) { web.destroy(); web = null; } super.onDestroy(); }
    @Override public void onBackPressed() {
        if (showingSource) { showSource(false); return; }
        if (web == null) { finish(); return; }
        // The page's own back first: a chart shown large, an editor open.
        web.evaluateJavascript("(function(){try{return window.gyeotBack&&window.gyeotBack()?1:0}catch(e){return 0}})()", r -> {
            if (destroyed) return;
            if (!"1".equals(r)) { if (composerOpen && input != null && input.getText().toString().isBlank() && !book.html(id).isBlank()) toggleComposer(false); else finish(); }
        });
    }
    @Override public void changed(String project) { if (project.equals(id) && !destroyed) refresh(); }

    // ── the screen ───────────────────────────────────────────────────

    private void build() {
        LinearLayout bar = ui.row(); bar.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)); bar.setMinimumHeight(ui.dp(52));
        bar.addView(ui.bareIcon(R.drawable.ic_back, TEXT, "프로젝트 목록으로", this::finish), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout words = ui.column(); words.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        title = ui.text("", 16, 700, TEXT); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); words.addView(title);
        subtitle = ui.text("", 12, 500, MUTED); subtitle.setSingleLine(true); subtitle.setEllipsize(TextUtils.TruncateAt.END); words.addView(subtitle);
        words.setOnClickListener(v -> rename()); Ui.pressable(words, true);
        bar.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ai = ui.text("AI로 고치기", FOOTNOTE, 650, ACCENT); ai.setGravity(Gravity.CENTER); ai.setSingleLine(true);
        ai.setBackground(new android.graphics.drawable.InsetDrawable(ui.shape(ACCENT_SOFT, PILL), 0, ui.dp(6), 0, ui.dp(6))); ai.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        android.graphics.drawable.Drawable spark = getDrawable(R.drawable.ic_sparkle);
        if (spark != null) { spark = spark.mutate(); spark.setTint(ACCENT); spark.setBounds(0, 0, ui.dp(15), ui.dp(15)); ai.setCompoundDrawablesRelative(spark, null, null, null); ai.setCompoundDrawablePadding(ui.dp(5)); }
        ai.setOnClickListener(v -> toggleComposer(!composerOpen)); Ui.pressable(ai, false);
        bar.addView(ai, new LinearLayout.LayoutParams(-2, ui.dp(44)));
        bar.addView(ui.bareIcon(R.drawable.ic_refresh, TEXT, "새로 고침", () -> { loaded = ""; refresh(); }), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ImageView more = ui.bareIcon(R.drawable.ic_more, TEXT, "프로젝트 메뉴", () -> {}); more.setOnClickListener(this::menu);
        bar.addView(more, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        root.addView(bar);
        View rule = new View(this); rule.setBackgroundColor(SEPARATOR); root.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(.5f))));
        busyBar = ui.row(); busyBar.setPadding(ui.dp(14), ui.dp(8), ui.dp(8), ui.dp(8)); busyBar.setBackground(ui.shape(ui.tone(ACCENT), 0)); busyBar.setVisibility(View.GONE);
        busyText = new ShimmerText(this, MUTED, TEXT); busyText.setTextSize(13); busyText.setTypeface(Ui.weight(560)); busyText.setSingleLine(true); busyText.setEllipsize(TextUtils.TruncateAt.END);
        busyBar.addView(busyText, new LinearLayout.LayoutParams(0, -2, 1));
        busyBar.addView(ui.pill("멈추기", R.drawable.ic_stop, false, () -> maker.stop(id)), new LinearLayout.LayoutParams(-2, -2));
        root.addView(busyBar, new LinearLayout.LayoutParams(-1, -2));
        stage = new FrameLayout(this); stage.setBackgroundColor(BG);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        try { web = web(); stage.addView(web, new FrameLayout.LayoutParams(-1, -1)); }
        catch (RuntimeException unavailable) { web = null; TextView none = ui.label("이 기기에서 웹 화면을 열지 못했어요. 메뉴의 ‘소스 보기’로 HTML을 볼 수 있어요.", 14, MUTED); none.setPadding(ui.dp(24), ui.dp(40), ui.dp(24), 0); stage.addView(none); }
        making = makingView(); stage.addView(making, new FrameLayout.LayoutParams(-1, -1));
        composer = composer(); root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
    }
    /** A browser tab for the project: scripts and storage on, files and the app off, leaving links to the app's browser. */
    private WebView web() {
        WebView view = new WebView(this);
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false); s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true); s.setBuiltInZoomControls(false); s.setSupportZoom(false);
        try { s.setSafeBrowsingEnabled(true); } catch (RuntimeException ignored) {}
        view.setBackgroundColor(BG);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (url.startsWith(BASE)) return false;
                if (url.startsWith("https://") || url.startsWith("http://")) { try { BrowserActivity.open(ProjectActivity.this, url); } catch (Exception ignored) {} }
                return true;
            }
        });
        return view;
    }
    private View makingView() {
        LinearLayout box = ui.column(); box.setGravity(Gravity.CENTER); box.setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32)); box.setBackgroundColor(BG); box.setVisibility(View.GONE);
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.ic_sparkle); mark.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        mark.setBackground(ui.shape(ACCENT_SOFT, 20)); mark.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        box.addView(mark, new LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)));
        TextView t = ui.title("", 19); t.setGravity(Gravity.CENTER); t.setPadding(0, ui.dp(16), 0, ui.dp(6)); t.setTag("title"); box.addView(t);
        TextView d = ui.label("", 14, MUTED); d.setGravity(Gravity.CENTER); d.setLineSpacing(ui.dp(3), 1); d.setTag("detail"); box.addView(d);
        return box;
    }
    private LinearLayout composer() {
        LinearLayout outer = ui.column(); outer.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(8));
        LinearLayout box = ui.column(); box.setBackground(ui.glass(28, false)); ui.lift(box, 4); box.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(4));
        outer.addView(box, new LinearLayout.LayoutParams(-1, -2));
        input = new EditText(this); input.setBackground(null); input.setTextColor(TEXT); input.setHintTextColor(MUTED); input.setTextSize(16); input.setMaxLines(5);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        input.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(2));
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout bar = ui.row(); bar.setGravity(Gravity.CENTER_VERTICAL);
        model = ui.text("", 13.5f, 500, TEXT); model.setSingleLine(true); model.setEllipsize(TextUtils.TruncateAt.END);
        model.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); model.setPadding(ui.dp(10), 0, ui.dp(6), 0); model.setMinHeight(ui.dp(44));
        model.setOnClickListener(v -> chooseModel()); Ui.pressable(model, true);
        bar.addView(model, new LinearLayout.LayoutParams(0, -2, 1));
        send = ui.sendButton("보내기", () -> { if (maker.busy(id)) maker.stop(id); else submit(); });
        bar.addView(send, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        outer.setVisibility(composerOpen ? View.VISIBLE : View.GONE);
        return outer;
    }
    private void toggleComposer(boolean open) {
        composerOpen = open; composer.setVisibility(open ? View.VISIBLE : View.GONE);
        android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (open) { input.requestFocus(); if (keyboard != null) keyboard.showSoftInput(input, 0); }
        else if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    /** Everything again from the book and the maker: the bar, the page (only when its HTML changed), the composer. */
    private void refresh() {
        ProjectBook.Project p = book.project(id); if (p == null) { finish(); return; }
        title.setText(p.title());
        boolean busy = maker.busy(id); String html = book.html(id);
        subtitle.setText(busy ? maker.doing(id) : (p.about().isBlank() ? "프로젝트" : p.about()));
        busyBar.setVisibility(busy && !html.isBlank() ? View.VISIBLE : View.GONE);
        if (busy) busyText.setText(maker.doing(id));
        String failure = maker.failure(id); if (failure != null) Toast.makeText(this, failure, Toast.LENGTH_LONG).show();
        String summary = maker.summary(id); if (summary != null) Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
        boolean empty = html.isBlank();
        making.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            ((TextView) making.findViewWithTag("title")).setText(busy ? "만들고 있어요" : "무엇을 만들까요?");
            ((TextView) making.findViewWithTag("detail")).setText(busy ? maker.doing(id) + "\n완성되면 바로 여기에 열려요. 다른 화면으로 가도 계속 만들어요." : "아래에 만들고 싶은 것을 적어 주세요. 예: 환율 계산기, 운동 기록장, 주식 차트 모음");
            if (!busy && !composerOpen) toggleComposer(true);
        } else if (web != null && !html.equals(loaded)) {
            loaded = html;
            web.loadDataWithBaseURL(BASE + id + "/", ProjectBook.themed(html, DARK), "text/html", "utf-8", null);
            if (showingSource) showSource(true);
        }
        input.setHint(empty ? "만들 것을 자세히 적어 주세요" : "어떻게 바꿀까요? · 예: ETH 차트 추가, 3열로");
        ui.sendLooks(send, busy);
        modelLabel();
    }
    private void submit() {
        String text = input.getText().toString().strip(); if (text.isEmpty()) return;
        boolean empty = book.html(id).isBlank();
        boolean started = empty ? maker.make(id, text, true) : maker.change(id, text);
        if (!started) { Toast.makeText(this, "지금은 다른 작업이 진행 중이에요.", Toast.LENGTH_SHORT).show(); return; }
        input.setText(""); toggleComposer(false); refresh();
    }

    // ── the model ────────────────────────────────────────────────────

    private void modelLabel() { model.setText(modelText(store)); model.setContentDescription("프로젝트 AI 모델 바꾸기, 지금 " + model.getText()); }
    /** The projects' AI as the composers show a model: its name in bold, the thinking level quiet beside it. */
    static CharSequence modelText(AppStore store) {
        String[] parts = modelParts(store);
        SpannableStringBuilder label = new SpannableStringBuilder(parts[0]); label.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(), 0);
        if (!parts[1].isEmpty()) { int at = label.length(); label.append("  ").append(parts[1]); label.setSpan(new ForegroundColorSpan(MUTED), at, label.length(), 0); }
        return label;
    }
    static String[] modelParts(AppStore store) {
        try {
            ScopedModels selection = ScopedModels.projects(store); String engine = selection.engine();
            if (engine == null || engine.isBlank() || !store.ready(engine)) return new String[]{"AI 연결·선택", ""};
            String m = selection.model(engine); if (m.isBlank()) return new String[]{WorkBook.author(engine) + " · 모델 고르기", ""};
            TeamRoster roster = new TeamRoster(store, selection);
            dev.jun.gyeot.core.Reasoning.Profile profile = roster.profile(engine, m); String effort = selection.effort(engine, m);
            String level = profile.efforts.isEmpty() ? "" : MyTubeChat.effortWord(engine, effort.isEmpty() ? profile.defaultEffort : effort);
            return new String[]{roster.label(engine, m), level};
        } catch (Exception unavailable) { return new String[]{"AI 연결·선택", ""}; }
    }
    private void chooseModel() { chooseModel(this, ui, store, model, this::modelLabel); }
    /** The same model menu as every screen, for the projects' AI. */
    static void chooseModel(Activity activity, Ui ui, AppStore store, View anchor, Runnable changed) {
        ScopedModels selection; try { selection = ScopedModels.projects(store); } catch (Exception e) { Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        Runnable all = () -> FileExplorerModelUi.show(activity, store, () -> ScopedModels.projects(store), "프로젝트 AI", "프로젝트에 적용", () -> !activity.isDestroyed(), dialog -> dialog.show(), changed);
        String engine = selection.engine();
        if (engine == null || engine.isBlank() || !store.ready(engine)) { all.run(); return; }
        TeamRoster roster = new TeamRoster(store, selection);
        new ModelPopover(activity, ui, store, selection, new ModelPopover.Catalog() {
            @Override public JSONArray models(String e) { return roster.catalog(e); }
            @Override public String label(String e, String m) { return roster.label(e, m); }
            @Override public dev.jun.gyeot.core.Reasoning.Profile profile(String e, String m) { return new TeamRoster(store).profile(e, m); }
            @Override public String effortLabel(String e, String effort) { return MyTubeChat.effortWord(e, effort); }
            @Override public String effortHint(String effort, String fallback) { return MainActivity.effortHint(effort, fallback); }
            @Override public String team() { return ""; }
        }, e -> {
            try { selection.chooseEngine(e); } catch (Exception failed) { Toast.makeText(activity, failed.getMessage(), Toast.LENGTH_LONG).show(); return; }
            if (!store.ready(e)) Toast.makeText(activity, WorkBook.author(e) + "를 설정에서 먼저 연결해 주세요.", Toast.LENGTH_LONG).show();
            changed.run();
        }, all, null, changed).show(anchor);
    }

    // ── the menu ─────────────────────────────────────────────────────

    private void menu(View anchor) {
        ProjectBook.Project p = book.project(id); if (p == null) return;
        int versions = book.versions(id).length;
        String[] labels = {showingSource ? "미리보기로" : "소스 보기", versions == 0 ? "되돌릴 버전 없음" : "이전 버전으로 되돌리기 (" + versions + ")", "이름 바꾸기", p.pinned() ? "고정 해제" : "맨 위에 고정", "HTML 파일로 공유", "프로젝트 지우기"};
        ui.miniMenu(anchor, labels, -1, i -> {
            switch (i) {
                case 0 -> showSource(!showingSource);
                case 1 -> { if (versions == 0) return; ui.confirm("바로 전 버전으로 되돌릴까요? 지금 버전은 사라져요.", "되돌리기", () -> {
                    try { book.undo(id); loaded = ""; refresh(); Toast.makeText(this, "이전 버전으로 되돌렸어요.", Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); } }).show(); }
                case 2 -> rename();
                case 3 -> { try { book.save(p.pin(!p.pinned())); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); } }
                case 4 -> share(p);
                default -> ui.confirm("‘" + p.title() + "’ 프로젝트를 지울까요? 이전 버전도 함께 지워져요.", "지우기", () -> {
                    maker.stop(id); try { book.remove(id); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; } finish(); }).show();
            }
        });
    }
    private void showSource(boolean on) {
        showingSource = on;
        if (source != null) { stage.removeView(source); source = null; }
        if (!on) return;
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(BG); HorizontalScrollView wide = new HorizontalScrollView(this);
        String html = book.html(id);
        TextView code = ui.text(html.length() > 200_000 ? html.substring(0, 200_000) : html, 12.5f, 400, TEXT); code.setTypeface(Typeface.MONOSPACE); code.setTextIsSelectable(true);
        code.setHorizontallyScrolling(true); code.setPadding(ui.dp(14), ui.dp(12), ui.dp(16), ui.dp(24));
        wide.addView(code); scroll.addView(wide); source = scroll;
        stage.addView(source, new FrameLayout.LayoutParams(-1, -1));
    }
    private void rename() {
        ProjectBook.Project p = book.project(id); if (p == null) return;
        Ui.Sheet sheet = ui.selectionSheet("이름 바꾸기", "");
        EditText name = ui.input("프로젝트 이름", 1, 60); name.setText(p.title()); sheet.body.addView(name);
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            String t = name.getText().toString().strip(); if (t.isEmpty()) return;
            try { book.save(p.titled(t)); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
            sheet.dismiss(); refresh();
        });
        sheet.show();
    }
    /** The HTML itself, as text (a file manager or a messenger keeps it as an .html to open anywhere). */
    private void share(ProjectBook.Project p) {
        String html = book.html(id);
        if (html.length() > 400_000) { Toast.makeText(this, "HTML이 커서 공유할 수 없어요. ‘소스 보기’에서 복사해 주세요.", Toast.LENGTH_LONG).show(); return; }
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, p.title() + ".html").putExtra(Intent.EXTRA_TEXT, html);
        try { startActivity(Intent.createChooser(send, p.title())); } catch (RuntimeException none) { Toast.makeText(this, "공유할 앱을 찾지 못했어요.", Toast.LENGTH_SHORT).show(); }
    }

    /** How the project looks now, small, for its card in the list (the screen's own pixels: charts drawn by the GPU too). */
    private void snapshot() {
        if (web == null || web.getWidth() == 0 || web.getHeight() == 0 || book.html(id).isBlank() || showingSource) return;
        int[] at = new int[2]; web.getLocationInWindow(at);
        int w = web.getWidth(), h = Math.min(web.getHeight(), Math.round(w * 0.75f));
        Bitmap shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(getWindow(), new android.graphics.Rect(at[0], at[1], at[0] + w, at[1] + h), shot, result -> {
                if (result != android.view.PixelCopy.SUCCESS) return;
                new Thread(() -> {
                    try {
                        float scale = Math.min(1f, 720f / w);
                        Bitmap small = Bitmap.createScaledBitmap(shot, Math.round(w * scale), Math.round(h * scale), true);
                        File out = book.thumb(id); out.getParentFile().mkdirs();
                        try (FileOutputStream stream = new FileOutputStream(out)) { small.compress(Bitmap.CompressFormat.JPEG, 82, stream); }
                    } catch (Exception ignored) {}
                }, "project-thumb").start();
            }, main);
        } catch (RuntimeException ignored) {}
    }
}
