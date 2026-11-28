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
import dev.jun.gyeot.core.TradingViewLink;
import dev.jun.gyeot.core.WorkBook;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import static dev.jun.gyeot.Ui.*;

/**
 * One 프로젝트, full screen: its HTML running as a small web app (the network open, as a browser tab: charts and data load;
 * no files; a link leaving it opens in the app's browser), a slim bar over it, and AI로 고치기, the composer
 * that asks the projects' AI to change it (ProjectMaker), shown when asked for. Each change keeps the version before it.
 * The page's own frame (never the charts' frames inside it) gets one message port to the app (connectApp): 트레이딩 asks
 * through it for an AI chart analysis and a news briefing, and any page has window.gyeot over it (미니앱에 다리 놓기: the
 * script put first in the page, PageBridge.inject; its calls go to PageBridgeHost, each family allowed by the user once per
 * project, listed in the menu's 권한); nothing else of the app is reachable from a page. Its bar has TradingView's mark: the
 * picked symbol's chart on tradingview.com, in the AI 브라우저.
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
    /** 트레이딩, and the symbol and interval its page last picked (tv_symbol): the TradingView button opens that chart. */
    private boolean trading; private String tvSymbol = "", tvInterval = "";
    /** The page's port to the app (connectApp), and the asks it started that are still running (by the page's id). */
    private android.webkit.WebMessagePort appPort;
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicReference<Runnable>> asks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService askJobs = java.util.concurrent.Executors.newFixedThreadPool(2);
    /** window.gyeot's other end for this project (its grants keyed "project:<id>"), and the project's name as last drawn. */
    private PageBridgeHost bridge; private volatile String pageTitle = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this); store = ((GyeotApp) getApplication()).store(); maker = ProjectMaker.get(this); book = maker.book();
        id = getIntent().getStringExtra(ID);
        if (id == null || book.project(id) == null) { Toast.makeText(this, "프로젝트를 찾지 못했어요.", Toast.LENGTH_SHORT).show(); finish(); return; }
        // An unchanged 트레이딩 from the old template gets AI 차트 분석 · 최신 뉴스 now (the old page stays in its versions).
        maker.upgrade(id, false);
        ProjectBook.Project made = book.project(id);
        trading = made != null && ProjectMaker.TRADING.kind().equals(made.kind());
        pageTitle = made == null ? "" : made.title();
        bridge = new PageBridgeHost(this, ui, "project:" + id, () -> pageTitle, "오른쪽 위 메뉴(⋮) → 권한", this::toPage);
        composerOpen = book.html(id).isBlank() && !maker.busy(id);
        root = ui.column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)); return insets;
        });
        setContentView(root); root.requestApplyInsets();
        build();
    }
    @Override protected void onResume() {
        super.onResume(); AppNavigator.inFront("pages"); AppNavigator.projectLeft = id; maker.watch(this); refresh();
        // Changed in 들퍼레/프로젝트 meanwhile (a file manager, another editor): that page comes in.
        new Thread(() -> { if (book.pull(id)) main.post(() -> { if (!destroyed) refresh(); }); }, "project-pull").start();
    }
    @Override protected void onPause() { maker.unwatch(this); snapshot(); if (isFinishing() && id != null && id.equals(AppNavigator.projectLeft)) AppNavigator.projectLeft = null; super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true;
        for (java.util.concurrent.atomic.AtomicReference<Runnable> cancel : asks.values()) dev.jun.gyeot.core.RequestAbort.cancel(cancel);
        askJobs.shutdownNow();
        if (bridge != null) bridge.close();
        if (appPort != null) { try { appPort.close(); } catch (RuntimeException ignored) {} appPort = null; }
        if (web != null) { web.destroy(); web = null; } super.onDestroy();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); }
    /** Leaving: back to the home screen when it opened this (HtmlWallpaper.backHome), else to the list. */
    private void leave() { if (!HtmlWallpaper.backHome(this)) finish(); }
    @Override public void onBackPressed() {
        if (showingSource) { showSource(false); return; }
        if (web == null) { leave(); return; }
        // The page's own back first: a chart shown large, an editor open.
        web.evaluateJavascript("(function(){try{return window.gyeotBack&&window.gyeotBack()?1:0}catch(e){return 0}})()", r -> {
            if (destroyed) return;
            if (!"1".equals(r)) { if (composerOpen && input != null && input.getText().toString().isBlank() && !book.html(id).isBlank()) toggleComposer(false); else leave(); }
        });
    }
    @Override public void changed(String project) { if (project.equals(id) && !destroyed) refresh(); }

    // ── the screen ───────────────────────────────────────────────────

    private void build() {
        LinearLayout bar = ui.row(); bar.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)); bar.setMinimumHeight(ui.dp(52));
        bar.addView(ui.bareIcon(R.drawable.ic_back, TEXT, "프로젝트 목록으로", this::leave), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout words = ui.column(); words.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        title = ui.text("", 16, 700, TEXT); title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); words.addView(title);
        subtitle = ui.text("", 12, 500, MUTED); subtitle.setSingleLine(true); subtitle.setEllipsize(TextUtils.TruncateAt.END); words.addView(subtitle);
        words.setOnClickListener(v -> rename()); Ui.pressable(words, true);
        bar.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ai = ui.text("AI로 고치기", FOOTNOTE, 650, ACCENT); ai.setGravity(Gravity.CENTER); ai.setSingleLine(true);
        ai.setBackground(new android.graphics.drawable.InsetDrawable(ui.shape(ACCENT_SOFT, PILL), 0, ui.dp(6), 0, ui.dp(6))); ai.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        // The AI mark of the browser and 마이튜브 요약 (AiMark), in the pill's color.
        AiMark mark = new AiMark(getResources().getDisplayMetrics().density); mark.setTint(ACCENT); mark.setBounds(0, 0, ui.dp(20), ui.dp(20));
        ai.setCompoundDrawablesRelative(mark, null, null, null); ai.setCompoundDrawablePadding(ui.dp(4));
        ai.setOnClickListener(v -> toggleComposer(!composerOpen)); Ui.pressable(ai, false);
        // 트레이딩: TradingView's own mark before AI로 고치기, nothing else (준: "내트뷰 누르면 그냥 ai브라우저로만 열리기", "로그인도
        // 없애줘", "트레이딩뷰 공식 아이콘만 쓰자"): a tap opens the picked symbol's chart in the AI 브라우저. Its 새로 고침 is in the
        // menu, so the title keeps its room on a phone.
        if (trading) {
            ImageView tv = ui.bareIcon(R.drawable.ic_tradingview, TEXT, "트레이딩뷰에서 열기", this::openTradingView); tv.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
            bar.addView(tv, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        }
        bar.addView(ai, new LinearLayout.LayoutParams(-2, ui.dp(44)));
        if (!trading) bar.addView(ui.bareIcon(R.drawable.ic_refresh, TEXT, "새로 고침", this::reload), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ImageView more = ui.bareIcon(R.drawable.ic_more, TEXT, "프로젝트 메뉴", () -> {}); more.setOnClickListener(this::menu);
        bar.addView(more, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        root.addView(bar);
        View rule = new View(this); rule.setBackgroundColor(SEPARATOR); root.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(.5f))));
        busyBar = ui.row(); busyBar.setPadding(ui.dp(14), ui.dp(8), ui.dp(8), ui.dp(8)); busyBar.setBackground(ui.shape(ui.tone(ACCENT), 0)); busyBar.setVisibility(View.GONE);
        busyText = new ShimmerText(this, MUTED, TEXT); busyText.setTextSize(13); busyText.setTypeface(Ui.weight(560)); busyText.setSingleLine(true); busyText.setEllipsize(TextUtils.TruncateAt.END);
        busyBar.addView(busyText, new LinearLayout.LayoutParams(0, -2, 1));
        busyBar.addView(ui.pill("멈추기", R.drawable.ic_stop, false, () -> maker.stop(id)), new LinearLayout.LayoutParams(-2, -2));
        root.addView(busyBar, new LinearLayout.LayoutParams(-1, -2));
        offerBar();
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
            @Override public void onPageFinished(WebView v, String url) { if (url != null && url.startsWith(BASE)) connectApp(); }
            // window.gyeot's script, put first in the page (refresh): served here, on the web view's own thread.
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                return dev.jun.gyeot.core.PageBridge.PROJECT_SCRIPT.equals(request.getUrl().toString()) ? PageBridgeHost.script(ProjectActivity.this) : null;
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
        model = ModelLine.make(this, ui, v -> chooseModel()); model.setMinHeight(ui.dp(44));
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
        title.setText(p.title()); pageTitle = p.title();
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
            web.loadDataWithBaseURL(BASE + id + "/", dev.jun.gyeot.core.PageBridge.inject(ProjectBook.themed(html, DARK), dev.jun.gyeot.core.PageBridge.tag(dev.jun.gyeot.core.PageBridge.PROJECT_SCRIPT)), "text/html", "utf-8", null);
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

    private void modelLabel() { ModelLine.show(model, modelParts(store), "프로젝트 AI"); }
    /** The projects' AI as the composers show a model: its name in bold, the thinking level quiet beside it. */
    static CharSequence modelText(AppStore store) {
        String[] parts = modelParts(store);
        return ModelLine.label(parts[0], parts[1]);
    }
    static String[] modelParts(AppStore store) { return modelParts(store, () -> ScopedModels.projects(store)); }
    /** The model of a choice (the projects' AI, 트레이딩's): {its name, its thinking level or ""}. */
    static String[] modelParts(AppStore store, java.util.function.Supplier<ScopedModels> scope) {
        try {
            ScopedModels selection = scope.get(); String engine = selection.engine();
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
        chooseModel(activity, ui, store, anchor, changed, () -> ScopedModels.projects(store), "프로젝트 AI", "프로젝트에 적용");
    }
    /** The same menu for another choice of the projects' screens (트레이딩's chart analysis). */
    static void chooseModel(Activity activity, Ui ui, AppStore store, View anchor, Runnable changed, java.util.function.Supplier<ScopedModels> scope, String title, String apply) {
        ScopedModels selection; try { selection = scope.get(); } catch (Exception e) { Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        Runnable all = () -> FileExplorerModelUi.show(activity, store, scope::get, title, apply, () -> !activity.isDestroyed(), dialog -> dialog.show(), changed);
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

    // ── the app, for the page: AI chart analysis and news briefing (트레이딩) ─────────────────────────────

    /**
     * A fresh message channel to the page's own frame, its origin checked by WebView (postWebMessage to BASE): the charts'
     * cross-origin frames never get the port. The page takes it from the "gyeot-app" message (trading.html).
     */
    private void connectApp() {
        if (web == null || destroyed) return;
        try {
            if (appPort != null) { try { appPort.close(); } catch (RuntimeException ignored) {} }
            android.webkit.WebMessagePort[] ports = web.createWebMessageChannel();
            android.webkit.WebMessagePort mine = ports[0]; appPort = mine;
            mine.setWebMessageCallback(new android.webkit.WebMessagePort.WebMessageCallback() {
                @Override public void onMessage(android.webkit.WebMessagePort port, android.webkit.WebMessage message) { if (port == appPort && !destroyed) fromPage(message.getData()); }
            }, main);
            web.postWebMessage(new android.webkit.WebMessage("gyeot-app", new android.webkit.WebMessagePort[]{ports[1]}), android.net.Uri.parse(BASE));
        } catch (RuntimeException unavailable) { appPort = null; }
    }
    private void toPage(org.json.JSONObject message) {
        main.post(() -> { if (appPort != null && !destroyed) try { appPort.postMessage(new android.webkit.WebMessage(message.toString())); } catch (RuntimeException closed) {} });
    }
    private static String field(org.json.JSONObject m, String key, int max) { String v = m.optString(key).strip(); return v.length() > max ? v.substring(0, max) : v; }
    private void fromPage(String data) {
        org.json.JSONObject m; try { m = new org.json.JSONObject(data == null ? "" : data); } catch (Exception unreadable) { return; }
        String ask = field(m, "id", 40);
        if (!ask.isEmpty() && !ask.matches("[A-Za-z0-9_.:-]+")) return;
        // window.gyeot (any page): its own checks, grants and limits.
        if ("gyeot".equals(m.optString("type"))) { if (bridge != null) bridge.handle(m); return; }
        switch (m.optString("type")) {
            case "model" -> toPage(dev.jun.gyeot.core.Jsons.obj("type", "model", "label", tradingModelLabel()));
            case "choose_model" -> chooseModel(this, ui, store, title, () -> toPage(dev.jun.gyeot.core.Jsons.obj("type", "model", "label", tradingModelLabel())), () -> ScopedModels.trading(store), "트레이딩 AI", "차트 분석에 적용");
            case "analyze" -> { if (!ask.isEmpty()) capture(m, ask); }
            case "news" -> { if (!ask.isEmpty()) start(ask, (cancel, guard) -> news(m, ask, cancel, guard)); }
            case "followup" -> { if (!ask.isEmpty()) start(ask, (cancel, guard) -> followUp(m, ask, cancel, guard)); }
            case "stop" -> { java.util.concurrent.atomic.AtomicReference<Runnable> cancel = asks.remove(ask); if (cancel != null) dev.jun.gyeot.core.RequestAbort.cancel(cancel); }
            // The symbol and interval the TradingView button opens (tv_ready: a page from before, saying the same).
            case "tv_symbol", "tv_ready" -> { if (trading) pick(m); }
            default -> {}
        }
    }
    private String tradingModelLabel() { String[] parts = modelParts(store, () -> ScopedModels.trading(store)); return parts[1].isEmpty() ? parts[0] : parts[0] + " · " + parts[1]; }
    private interface Ask { void run(java.util.concurrent.atomic.AtomicReference<Runnable> cancel, Runnable guard) throws Exception; }
    /** One ask off the main thread; its answer or its failure goes back to the page under the same id. */
    private void start(String ask, Ask body) {
        java.util.concurrent.atomic.AtomicReference<Runnable> cancel = new java.util.concurrent.atomic.AtomicReference<>();
        if (asks.putIfAbsent(ask, cancel) != null) return;
        Runnable guard = () -> { if (destroyed || asks.get(ask) != cancel) throw new java.util.concurrent.CancellationException("멈췄어요."); };
        try {
            askJobs.execute(() -> {
                try { body.run(cancel, guard); }
                catch (java.util.concurrent.CancellationException stopped) { toPage(dev.jun.gyeot.core.Jsons.obj("type", "result", "id", ask, "ok", false, "error", "멈췄어요.")); }
                catch (Exception failed) { toPage(dev.jun.gyeot.core.Jsons.obj("type", "result", "id", ask, "ok", false, "error", failed.getMessage() == null || failed.getMessage().isBlank() ? "하지 못했어요." : failed.getMessage())); }
                finally { asks.remove(ask, cancel); }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) { asks.remove(ask, cancel); }
    }
    private void progress(String ask, String text) { toPage(dev.jun.gyeot.core.Jsons.obj("type", "progress", "id", ask, "text", text)); }
    /**
     * The chart as it is on screen (rect: its box in the page, CSS pixels, with the page's devicePixelRatio): the window's own
     * pixels, as the project thumbnail takes them, since the chart is a cross-origin frame no script can read. Then the analysis.
     */
    private void capture(org.json.JSONObject m, String ask) {
        org.json.JSONObject r = m.optJSONObject("rect"); double ratio = m.optDouble("dpr", getResources().getDisplayMetrics().density);
        if (web == null || r == null || !(ratio > 0)) { start(ask, (cancel, guard) -> analyze(m, ask, null, cancel, guard)); return; }
        int[] at = new int[2]; web.getLocationInWindow(at);
        int left = Math.max(0, (int) Math.round(r.optDouble("x") * ratio)), top = Math.max(0, (int) Math.round(r.optDouble("y") * ratio));
        int right = Math.min(web.getWidth(), (int) Math.round((r.optDouble("x") + r.optDouble("w")) * ratio)), bottom = Math.min(web.getHeight(), (int) Math.round((r.optDouble("y") + r.optDouble("h")) * ratio));
        if (right - left < 80 || bottom - top < 80) { start(ask, (cancel, guard) -> analyze(m, ask, null, cancel, guard)); return; }
        Bitmap shot = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(getWindow(), new android.graphics.Rect(at[0] + left, at[1] + top, at[0] + right, at[1] + bottom), shot,
                result -> start(ask, (cancel, guard) -> analyze(m, ask, result == android.view.PixelCopy.SUCCESS ? shot : null, cancel, guard)), main);
        } catch (RuntimeException failed) { start(ask, (cancel, guard) -> analyze(m, ask, null, cancel, guard)); }
    }
    /**
     * Binance's numbers and the chart's pictures to the 트레이딩 AI the user chose. The pictures are taken off screen, large
     * (ChartShots): the chart at its interval when Binance has the numbers, else at four intervals (주봉 to 1시간봉) for the AI
     * to read without them. shown, the chart as it was on screen, is used only when the off-screen pictures fail.
     */
    private void analyze(org.json.JSONObject m, String ask, Bitmap shown, java.util.concurrent.atomic.AtomicReference<Runnable> cancel, Runnable guard) throws Exception {
        String symbol = field(m, "symbol", 60), name = field(m, "name", 60), about = field(m, "desc", 80), interval = field(m, "interval", 4);
        if (symbol.isEmpty()) throw new IllegalArgumentException("종목을 알 수 없어요.");
        dev.jun.gyeot.core.TradingData.Style style = dev.jun.gyeot.core.TradingData.style(field(m, "style", 20));
        String custom = "custom".equals(style.id()) ? field(m, "custom", 300) : "";
        ScopedModels scope = ScopedModels.trading(store); String engine = scope.engine();
        if (engine == null || engine.isBlank() || !store.ready(engine)) throw new IllegalStateException("분석할 AI가 연결되지 않았어요. 위의 모델 이름을 눌러 골라 주세요.");
        progress(ask, "바이낸스에서 주봉부터 15분봉까지 시세를 받는 중");
        String[] got = numbers(symbol, name, interval, guard); String numbers = got[0], from = got[1];
        boolean sees = dev.jun.gyeot.core.MainRequest.supported(engine).contains(dev.jun.gyeot.core.MainRequest.Option.FILES);
        java.util.List<dev.jun.gyeot.core.MainRequest.Attachment> pictures = new java.util.ArrayList<>(); StringBuilder named = new StringBuilder();
        if (sees) {
            java.util.List<String> intervals = style.pictures() != null ? style.pictures() : dev.jun.gyeot.core.TradingData.pictured(interval, !numbers.isEmpty());
            java.util.List<String> names = new java.util.ArrayList<>(); for (String tf : intervals) names.add(dev.jun.gyeot.core.TradingData.intervalName(tf));
            progress(ask, String.join("·", names) + " 차트를 크게 찍는 중 (보이지 않는 곳에서)");
            pictures.addAll(pictures(symbol, name, intervals, named, guard));
            if (pictures.isEmpty() && shown != null) { pictures.add(new dev.jun.gyeot.core.MainRequest.Attachment(name + ".jpg", "image/jpeg", jpeg(shown))); named.append("1) 사용자가 지금 보고 있는 차트"); }
        }
        if (pictures.isEmpty() && numbers.isEmpty()) throw new IllegalStateException(sees ? "차트 화면을 찍지 못했고, 바이낸스에도 이 종목이 없어요. 잠시 뒤 다시 눌러 주세요."
            : WorkBook.author(engine) + "는 그림을 볼 수 없고, 바이낸스에도 이 종목이 없어요. 그림을 보는 AI(ChatGPT·Claude·Gemini·Grok)로 바꿔 주세요.");
        String input = "종목: " + name + " (" + symbol + (about.isEmpty() ? "" : ", " + about) + ") · 사용자가 보던 시간대: " + dev.jun.gyeot.core.TradingData.intervalName(interval) + "\n"
            + "분석 스타일: " + style.label() + (custom.isEmpty() ? "" : " · 사용자가 원한 관점: " + custom) + "\n"
            + (numbers.isEmpty() ? "바이낸스 API에 이 종목이 없어 숫자 없이 차트 그림만 보내요. 가격은 그림의 축에서 읽고, 읽기 어려우면 그렇다고 말해 주세요.\n" : "앱이 바이낸스 봉으로 계산한 실제 숫자 (시간대별):" + numbers)
            + (!pictures.isEmpty() ? "첨부한 그림: " + named + " (트레이딩뷰 차트, 아래에 RSI와 MACD)." : "차트 그림은 보내지 못했어요: 숫자로만 분석해 주세요.");
        dev.jun.gyeot.core.MainRequest request = dev.jun.gyeot.core.MainRequest.of(dev.jun.gyeot.core.TradingData.analyst(style), input);
        boolean picture = !pictures.isEmpty();
        if (picture) request = request.files(pictures);
        String label = tradingModelLabel();
        progress(ask, label + " · 차트를 보는 중");
        guard.run();
        String text = MainAdapters.send(store, scope.snapshot(), request, guard, cancel).text();
        if (text == null || text.isBlank()) throw new IllegalStateException("AI가 빈 답을 보냈어요. 다시 해 주세요.");
        toPage(dev.jun.gyeot.core.Jsons.obj("type", "result", "id", ask, "ok", true, "text", text, "model", label,
            "source", (picture ? "차트 화면" : "") + (picture && !from.isEmpty() ? " + " : "") + from, "at", System.currentTimeMillis()));
    }
    /** Grok, searching the web and X, writes the latest news briefing for the symbol. */
    private void news(org.json.JSONObject m, String ask, java.util.concurrent.atomic.AtomicReference<Runnable> cancel, Runnable guard) throws Exception {
        String symbol = field(m, "symbol", 60), name = field(m, "name", 60), about = field(m, "desc", 80);
        if (symbol.isEmpty()) throw new IllegalArgumentException("종목을 알 수 없어요.");
        if (!store.grok.connected()) throw new IllegalStateException("뉴스 브리핑은 Grok이 웹과 X를 검색해서 써요. 설정 → 연결에서 Grok을 연결해 주세요.");
        // The Grok model the 트레이딩 AI uses when it is Grok, else the Grok connection's own.
        ScopedModels scope = ScopedModels.trading(store); String model = store.grok.model(), effort = "";
        if (WorkBook.GROK.equals(scope.engine())) { model = scope.model(WorkBook.GROK); effort = scope.effectiveEffort(WorkBook.GROK, model); }
        progress(ask, "Grok이 웹과 X에서 찾는 중");
        WorkClient.Result answer = GrokWorkClient.searchWebAndX(store.grok, model, "You are a market news researcher. Search before answering.", dev.jun.gyeot.core.TradingData.newsAsk(name, symbol, about), effort, guard, cancel);
        if (answer.text == null || answer.text.isBlank()) throw new IllegalStateException("Grok이 빈 답을 보냈어요. 다시 해 주세요.");
        toPage(dev.jun.gyeot.core.Jsons.obj("type", "result", "id", ask, "ok", true, "text", answer.text, "model", "Grok · 웹·X 검색", "sources", answer.sources, "note", answer.note, "at", System.currentTimeMillis()));
    }
    /**
     * Every timeframe's numbers from Binance, longest first (the one the user was on with its last 30 candles, the others in
     * short): {the text, the source's name}; "" when Binance does not list the symbol. Blocking.
     */
    private String[] numbers(String symbol, String name, String interval, Runnable guard) {
        StringBuilder all = new StringBuilder(); String from = ""; int source = -1;
        for (String tf : dev.jun.gyeot.core.TradingData.TIMEFRAMES) {
            java.util.List<dev.jun.gyeot.core.TradingData.Source> sources = dev.jun.gyeot.core.TradingData.sources(symbol, tf);
            for (int i = source < 0 ? 0 : source; i < sources.size() && (source < 0 || i == source); i++) {
                guard.run();
                try {
                    java.util.List<dev.jun.gyeot.core.TradingData.Bar> bars = dev.jun.gyeot.core.TradingData.klines(new JSONArray(fetch(sources.get(i).url())));
                    if (bars.size() < 30) continue;
                    all.append("\n[").append(dev.jun.gyeot.core.TradingData.intervalName(tf)).append(tf.equals(interval) ? " · 사용자가 보던 시간대" : "").append("]\n")
                        .append(dev.jun.gyeot.core.TradingData.summary(name, symbol, tf, bars, sources.get(i).name(), tf.equals(interval)));
                    source = i; from = sources.get(i).name(); break;
                } catch (java.util.concurrent.CancellationException stopped) { throw stopped; } catch (Exception notListed) { /* the next source, or the chart alone */ }
            }
        }
        return new String[]{all.toString(), from};
    }
    /** The charts of these timeframes, taken large off screen just now, as attachments; named lists them ("1) 1시간봉, 2) 15분봉"). */
    private java.util.List<dev.jun.gyeot.core.MainRequest.Attachment> pictures(String symbol, String name, java.util.List<String> intervals, StringBuilder named, Runnable guard) throws Exception {
        java.util.List<dev.jun.gyeot.core.MainRequest.Attachment> out = new java.util.ArrayList<>();
        for (ChartShots.Shot shot : shots(symbol, intervals, guard)) {
            out.add(new dev.jun.gyeot.core.MainRequest.Attachment(name + "-" + shot.interval() + ".jpg", "image/jpeg", jpeg(shot.picture())));
            named.append(named.length() == 0 ? "" : ", ").append(out.size()).append(") ").append(dev.jun.gyeot.core.TradingData.intervalName(shot.interval()));
        }
        return out;
    }
    /**
     * 이어서 묻기 (준: "Ai분석한것에 이어서 질문할 수 있게"): a question on an answer the page shows, with the questions before it.
     * An analysis goes on with the 트레이딩 AI, with Binance's numbers and the short charts taken again just now (준: "후속 질문시
     * 차트를 못 읽어": it had only its earlier words, so "지금 돌파 실패?" could not be answered); a news briefing with Grok,
     * searching the web and X again.
     */
    private void followUp(org.json.JSONObject m, String ask, java.util.concurrent.atomic.AtomicReference<Runnable> cancel, Runnable guard) throws Exception {
        String symbol = field(m, "symbol", 60), name = field(m, "name", 60), about = field(m, "desc", 80), question = field(m, "question", 1000), base = field(m, "base", 12000);
        if (symbol.isEmpty() || question.isEmpty() || base.isEmpty()) throw new IllegalArgumentException("이어서 물을 내용이 없어요.");
        StringBuilder before = new StringBuilder();
        JSONArray thread = m.optJSONArray("thread");
        for (int i = 0; thread != null && i < thread.length() && i < 6; i++) {
            org.json.JSONObject t = thread.optJSONObject(i); if (t == null) continue;
            before.append("\n\n[사용자] ").append(field(t, "q", 1000)).append("\n[답] ").append(field(t, "a", 3000));
        }
        boolean news = "news".equals(m.optString("kind"));
        // A question on an analysis about news, issues or schedules (준: "오늘 주목할만한 이슈나 발표는?" got "no news in the data"):
        // Grok searches the web and X for it, with the analysis and the price now beside it.
        boolean asksNews = !news && dev.jun.gyeot.core.TradingData.asksNews(question) && store.grok.connected();
        String input = "종목: " + name + " (" + symbol + (about.isEmpty() ? "" : ", " + about) + ")\n\n" + (news ? "앞서 쓴 최신 뉴스 브리핑:\n" : "앞서 쓴 차트 분석:\n") + base
            + (before.length() == 0 ? "" : "\n\n이어서 나눈 질문과 답:" + before) + "\n\n사용자의 새 질문: " + question;
        if (asksNews) {
            progress(ask, "바이낸스에서 지금 시세를 다시 받는 중");
            String[] now = numbers(symbol, name, field(m, "interval", 4), guard);
            if (!now[0].isEmpty()) input += "\n\n지금 시세 (한국 시간 " + java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH:mm")) + "에 새로 받음):" + now[0];
        }
        if (news || asksNews) {
            if (!store.grok.connected()) throw new IllegalStateException("뉴스에 이어서 묻기는 Grok이 웹과 X를 다시 찾아 답해요. 설정 → 연결에서 Grok을 연결해 주세요.");
            ScopedModels scope = ScopedModels.trading(store); String model = store.grok.model(), effort = "";
            if (WorkBook.GROK.equals(scope.engine())) { model = scope.model(WorkBook.GROK); effort = scope.effectiveEffort(WorkBook.GROK, model); }
            progress(ask, "Grok이 웹과 X에서 찾아보고 답하는 중");
            WorkClient.Result answer = GrokWorkClient.searchWebAndX(store.grok, model, dev.jun.gyeot.core.TradingData.FOLLOW_UP + " Search the web and X when the question needs newer or other facts"
                + " (news, issues, announcements, earnings or economic calendars: search them, with today's date in Korea, and cite the sources).", input, effort, guard, cancel);
            if (answer.text == null || answer.text.isBlank()) throw new IllegalStateException("Grok이 빈 답을 보냈어요. 다시 해 주세요.");
            toPage(dev.jun.gyeot.core.Jsons.obj("type", "result", "id", ask, "ok", true, "text", answer.text, "model", "Grok · 웹·X 검색", "at", System.currentTimeMillis()));
            return;
        }
        ScopedModels scope = ScopedModels.trading(store); String engine = scope.engine();
        if (engine == null || engine.isBlank() || !store.ready(engine)) throw new IllegalStateException("답할 AI가 연결되지 않았어요. 위의 모델 이름을 눌러 골라 주세요.");
        // The market now: Binance's numbers again, and the short charts (the style's shortest, else 1시간·15분봉) taken again.
        String interval = field(m, "interval", 4);
        progress(ask, "바이낸스에서 지금 시세를 다시 받는 중");
        String[] got = numbers(symbol, name, interval, guard);
        boolean sees = dev.jun.gyeot.core.MainRequest.supported(engine).contains(dev.jun.gyeot.core.MainRequest.Option.FILES);
        java.util.List<dev.jun.gyeot.core.MainRequest.Attachment> pictures = new java.util.ArrayList<>(); StringBuilder named = new StringBuilder();
        if (sees) {
            java.util.List<String> intervals = dev.jun.gyeot.core.TradingData.followPictures(dev.jun.gyeot.core.TradingData.style(field(m, "style", 20)), interval);
            java.util.List<String> names = new java.util.ArrayList<>(); for (String tf : intervals) names.add(dev.jun.gyeot.core.TradingData.intervalName(tf));
            progress(ask, String.join("·", names) + " 차트를 다시 찍는 중 (보이지 않는 곳에서)");
            pictures.addAll(pictures(symbol, name, intervals, named, guard));
        }
        String now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH:mm"));
        input += "\n\n지금 시세 (한국 시간 " + now + "에 새로 받음):" + (got[0].isEmpty() ? " 바이낸스에 이 종목이 없어 숫자는 없어요." : got[0])
            + (pictures.isEmpty() ? (sees ? "\n차트 그림은 찍지 못했어요." : "") : "\n방금 찍은 차트 그림: " + named + " (트레이딩뷰, 아래에 RSI와 MACD).");
        dev.jun.gyeot.core.MainRequest request = dev.jun.gyeot.core.MainRequest.of(dev.jun.gyeot.core.TradingData.FOLLOW_UP, input);
        if (!pictures.isEmpty()) request = request.files(pictures);
        String label = tradingModelLabel();
        progress(ask, label + " · 답을 쓰는 중");
        String text = MainAdapters.send(store, scope.snapshot(), request, guard, cancel).text();
        if (text == null || text.isBlank()) throw new IllegalStateException("AI가 빈 답을 보냈어요. 다시 해 주세요.");
        toPage(dev.jun.gyeot.core.Jsons.obj("type", "result", "id", ask, "ok", true, "text", text, "model", label, "at", System.currentTimeMillis()));
    }
    /** The off-screen pictures (ChartShots, on the main thread), waited for here; none when they could not be taken in time. */
    private java.util.List<ChartShots.Shot> shots(String symbol, java.util.List<String> intervals, Runnable guard) throws Exception {
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<java.util.List<ChartShots.Shot>> taken = new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of());
        main.post(() -> { if (destroyed) { ready.countDown(); return; } ChartShots.take(this, symbol, intervals, DARK, list -> { taken.set(list); ready.countDown(); }); });
        for (int i = 0; i < 180 && !ready.await(500, java.util.concurrent.TimeUnit.MILLISECONDS); i++) guard.run();
        return taken.get();
    }
    private static String jpeg(Bitmap chart) {
        Bitmap scaled = chart; int w = chart.getWidth();
        if (w > 1280) scaled = Bitmap.createScaledBitmap(chart, 1280, Math.round(chart.getHeight() * 1280f / w), true);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 86, bytes);
        return android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
    }
    /** Candles from one of TradingData.HOSTS (Binance's public APIs), at most 2 MB, within 12 seconds. */
    private static String fetch(String address) throws Exception {
        java.net.URL url = new java.net.URL(address);
        if (!"https".equals(url.getProtocol()) || !dev.jun.gyeot.core.TradingData.HOSTS.contains(url.getHost())) throw new IllegalArgumentException("허용하지 않은 주소예요.");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(12000); c.setRequestProperty("Accept", "application/json");
        try {
            int status = c.getResponseCode(); if (status != 200) throw new java.io.IOException("HTTP " + status);
            try (java.io.InputStream in = c.getInputStream()) { byte[] body = in.readNBytes(2 * 1024 * 1024); return new String(body, java.nio.charset.StandardCharsets.UTF_8); }
        } finally { c.disconnect(); }
    }

    // ── the menu ─────────────────────────────────────────────────────

    /** The line offering the new 트레이딩 (AI 차트 분석 · 최신 뉴스), above the page while it lacks them; again after a 되돌리기. */
    private LinearLayout offer;
    private void offerBar() {
        if (offer != null) { root.removeView(offer); offer = null; }
        if (!maker.offerUpgrade(id)) return;
        LinearLayout bar = ui.row(); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(ui.dp(14), ui.dp(6), ui.dp(8), ui.dp(6)); bar.setBackground(ui.shape(ui.tone(ACCENT), 0));
        bar.addView(ui.text("AI 차트 분석·최신 뉴스가 들어간 새 트레이딩이 있어요. 종목은 그대로, 지금 페이지는 이전 버전에 남아요.", 12.5f, 520, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(ui.pill("바꾸기", R.drawable.ic_refresh, true, () -> { if (maker.upgrade(id, true)) { offerBar(); loaded = ""; refresh(); } }), new LinearLayout.LayoutParams(-2, -2));
        // Above the page, under the busy line (the busy line is the one before the page's stage).
        int at = stage == null ? root.getChildCount() : Math.max(0, root.indexOfChild(stage));
        offer = bar; root.addView(bar, at, new LinearLayout.LayoutParams(-1, -2));
    }
    /** The page again (its HTML loaded anew). */
    private void reload() { loaded = ""; refresh(); }
    /** The page's list picked a symbol or an interval: each kept only when TradingView would take it. */
    private void pick(org.json.JSONObject m) {
        String symbol = TradingViewLink.symbol(field(m, "symbol", 60)), interval = TradingViewLink.interval(field(m, "interval", 4));
        if (!symbol.isEmpty()) tvSymbol = symbol;
        if (!interval.isEmpty()) tvInterval = interval;
    }
    /** TradingView's chart of the picked symbol in the AI 브라우저, where the user is whoever they are there. */
    private void openTradingView() {
        try { BrowserActivity.open(this, TradingViewLink.chartUrl(tvSymbol, tvInterval)); }
        catch (RuntimeException failed) { Toast.makeText(this, "AI 브라우저를 열지 못했어요: " + failed.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void menu(View anchor) {
        ProjectBook.Project p = book.project(id); if (p == null) return;
        // No 되돌리기 here (준: "그냥 되돌리기 버튼을 없애줘"): it brought back the page's code, not what was done on the page, and
        // took 트레이딩's AI 차트 분석 with it. 트레이딩's symbols have their own 방금 바꾼 것 되돌리기 in its 종목 편집.
        // 권한: what the page may use of the app (window.gyeot), each family as the user left it.
        String[] labels = {showingSource ? "미리보기로" : "소스 보기", "이름 바꾸기", p.pinned() ? "고정 해제" : "맨 위에 고정", "HTML 파일로 공유", "파일 위치", "권한", "프로젝트 지우기"};
        // 트레이딩's 새로 고침 is here (the bar has the TradingView button in its place).
        if (trading) { String[] more = new String[labels.length + 1]; more[0] = "새로 고침"; System.arraycopy(labels, 0, more, 1, labels.length); labels = more; }
        ui.miniMenu(anchor, labels, -1, picked -> {
            if (trading && picked == 0) { reload(); return; }
            int i = trading ? picked - 1 : picked;
            switch (i) {
                case 4 -> {
                    String where = book.where();
                    Toast.makeText(this, where.isEmpty() || p.folder().isEmpty() ? "아직 앱 안에만 있어요. 내 휴대폰 → 저장 위치에서 들퍼레 폴더를 연결하면 거기에 저장해요."
                        : "내장 저장공간/" + where + "/" + p.folder() + "/index.html", Toast.LENGTH_LONG).show();
                }
                case 0 -> showSource(!showingSource);
                case 1 -> rename();
                case 2 -> { try { book.save(p.pin(!p.pinned())); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); } }
                case 3 -> share(p);
                case 5 -> { if (bridge != null) bridge.permissions(); }
                default -> ui.confirm("‘" + p.title() + "’ 프로젝트를 지울까요? 들퍼레/프로젝트의 폴더와 이전 버전도 함께 지워져요.", "지우기", () -> {
                    maker.stop(id); try { book.remove(id); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
                    // Its grants and the notifications it set for later go with it.
                    android.content.Context app = getApplicationContext(); String key = "project:" + id;
                    new Thread(() -> PageBridgeHost.forget(app, key), "project-forget").start();
                    finish(); }).show();
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
            try { book.rename(id, t); } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return; }
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
