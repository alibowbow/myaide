package dev.jun.gyeot;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.SearchManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.LruCache;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import dev.jun.gyeot.core.AppStacks;
import dev.jun.gyeot.core.Jsons;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 들퍼레 홈 화면 (준: "이 아이콘들을 혁신적으로 편리하게 사용, 정리할 수 있게(애플 맥북like)", "홈화면에 어플 바로가기 아이콘들이
 * 전체적으로 어질러져 있거든"): a home screen app the user may choose instead of the phone's own (Android lets no other app tidy
 * that one's icons). Every app sorted into named stacks (AppStacks), a dock whose icons swell under the finger, Launchpad,
 * a search that knows 초성 and hands words to 퍼레, and 퍼레 홈's board on top, over the user's own wallpaper.
 *
 * The page is assets/decor/launcher.html, served from https://gyeot.local/ and never navigated away; its bridge (Gyeot) is
 * only on that page (no frames, no other site). Apps come from LauncherApps (work profile included) and change live; icons
 * are drawn once and kept. Folding or unfolding keeps the page (configChanges): it only lays itself out again.
 */
public final class HomeActivity extends Activity {
    static final String PREFS = "launcher", DOCK = "dock", RECENT = "recent", MOVED = "moved", ORDER = "order";
    /** Opened from the 작업 표시줄's all-apps button: "apps" (Launchpad) or "search" (Launchpad with its search). */
    static final String SHOW = "show";
    /** A page of the home screen to show (LpService's notification: "LP"). */
    static final String PAGE = "page";
    /** The home screen is on screen (between onStart and onStop): the 작업 표시줄 (Taskbar) hides then. shownAt: its last onStart. */
    static volatile boolean visible;
    static volatile long shownAt;
    /**
     * The home screen while it exists (stopped behind other apps too): the 작업 표시줄 starts an app beside the one in front from
     * it, because Android 14+ drops FLAG_ACTIVITY_LAUNCH_ADJACENT on a start with no source activity (Taskbar.launchBeside).
     */
    private static volatile java.lang.ref.WeakReference<HomeActivity> alive = new java.lang.ref.WeakReference<>(null);
    static Activity alive() { HomeActivity a = alive.get(); return a == null || a.isFinishing() || a.isDestroyed() ? null : a; }
    private String pendingShow, pendingPage;
    private static final String BASE = "https://gyeot.local/";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-home"); t.setDaemon(true); return t; });
    private final Map<String, LauncherActivityInfo> known = new ConcurrentHashMap<>();
    private static final LruCache<String, byte[]> ICONS = new LruCache<>(6 * 1024 * 1024) { @Override protected int sizeOf(String k, byte[] v) { return v.length; } };
    private WebView web; private LauncherApps launcher; private SharedPreferences prefs;
    private boolean ready, shown; private int top, bottom, keyboard;
    private final Runnable refresh = new Runnable() { @Override public void run() { pushBoard(); main.postDelayed(this, 60_000); } };
    private final Runnable pushBadges = this::pushBadges;
    private final Runnable badgesChanged = () -> { main.removeCallbacks(pushBadges); main.postDelayed(pushBadges, 500); };
    private final Runnable boardSoon = this::pushBoard;
    /** The food's stage changed (NoticeListener): the board again in a moment, so 퍼레 says it now and not within the minute. */
    private final Runnable deliveryChanged = () -> { main.removeCallbacks(boardSoon); main.postDelayed(boardSoon, 700); };
    private final LauncherApps.Callback changed = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { pushApps(); }
        @Override public void onPackageAdded(String p, UserHandle u) { pushApps(); }
        @Override public void onPackageChanged(String p, UserHandle u) { ICONS.evictAll(); pushApps(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean replacing) { pushApps(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean replacing) { pushApps(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        alive = new java.lang.ref.WeakReference<>(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        launcher = getSystemService(LauncherApps.class);
        getWindow().setDecorFitsSystemWindows(false);
        FrameLayout root = new FrameLayout(this);
        setContentView(root);
        build(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            float d = getResources().getDisplayMetrics().density;
            top = Math.round(bars.top / d); bottom = Math.round(bars.bottom / d);
            // The keyboard's height too: 퍼레와 대화's box sits above it (준: "대화입력창은 하단으로").
            keyboard = Math.round(insets.getInsets(WindowInsets.Type.ime()).bottom / d);
            if (ready && web != null) web.evaluateJavascript("launcher.insets(" + top + "," + bottom + "," + keyboard + ")", null);
            return insets;
        });
        root.requestApplyInsets();
        pendingShow = getIntent() == null ? null : getIntent().getStringExtra(SHOW);
        pendingPage = getIntent() == null ? null : getIntent().getStringExtra(PAGE);
        // The LP page's media notification and the audio focus reach the page here, though the home screen is out of sight.
        LpMedia.host(this, cmd -> js(LpMedia.js(cmd)));
        try { launcher.registerCallback(changed, main); } catch (RuntimeException ignored) {}
        prefs.registerOnSharedPreferenceChangeListener(pagesChanged);
        systemBars();
    }
    private void build(FrameLayout root) {
        web = new WebView(this);
        web.setBackgroundColor(0);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false); s.setGeolocationEnabled(false);
        s.setMediaPlaybackRequiresUserGesture(true); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setVerticalScrollBarEnabled(false); web.setHorizontalScrollBarEnabled(false); web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Gate(), "Gyeot");
        web.setWebViewClient(new WebViewClient() {
            // The home screen is this one page: nothing navigates it.
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return request.isForMainFrame(); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl(); String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
                if (HtmlWallpaper.PAGES_HOST.equals(host)) {
                    try { return HtmlWallpaper.html(PageStore.book(HomeActivity.this).body(Uri.decode(u.getLastPathSegment() == null ? "" : u.getLastPathSegment()))); }
                    catch (Exception gone) { return HtmlWallpaper.html("<body style='background:#111;color:#bbb;font:16px sans-serif;display:grid;place-items:center;height:100vh'>이 페이지를 찾지 못했어요</body>"); }
                }
                // A piece on a page of the user's own: its fixes, and the LP page's add-on (PieceExtras: 내 음악's picker opens over this).
                if (!"gyeot.local".equalsIgnoreCase(host)) return PieceExtras.handle(HomeActivity.this, request, pick -> main.post(() -> {
                    try { startActivity(pick); } catch (RuntimeException refused) { PieceMusicStore.of(HomeActivity.this).pick("cancelled"); }
                }));
                String path = u.getPath() == null ? "/" : u.getPath();
                // The home screen page (and its secret) only as the page itself, never inside a frame.
                if ((path.equals("/") || path.equals("/launcher.html")) && !request.isForMainFrame()) return HtmlWallpaper.missing();
                return own(path);
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // Drawn again rather than left blank (a home screen must never stay empty).
                if (view == web) { ready = false; root.removeView(view); try { view.destroy(); } catch (RuntimeException ignored) {} web = null; main.post(() -> build(root)); }
                return true;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(BASE + "launcher.html");
    }
    /** The home screen is dark glass (the aurora, or the wallpaper under a dark veil): the bars' icons are light on it. */
    private void systemBars() {
        WindowInsetsController c = getWindow().getInsetsController(); if (c == null) return;
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        c.setSystemBarsAppearance(0, light);
    }
    /**
     * The one accent of the home screen, from the wallpaper's own main color (준: "전체적인 색감과 디자인이 좀 조화롭지 않다"):
     * its hue kept, made calm (not loud, not grey) and dark enough for white words on it. A quiet slate blue without one.
     */
    String accent() {
        int color = 0xff5e7a99;
        try {
            WallpaperColors colors = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors != null) color = colors.getPrimaryColor().toArgb();
        } catch (RuntimeException unknown) { /* the slate blue */ }
        float[] hsl = new float[3]; androidx_hsl(color, hsl);
        hsl[1] = Math.max(0.22f, Math.min(0.42f, hsl[1])); hsl[2] = 0.46f;
        return String.format(java.util.Locale.ROOT, "#%06x", hslColor(hsl) & 0xffffff);
    }
    private static void androidx_hsl(int c, float[] out) {
        float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f, max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float l = (max + min) / 2, h = 0, s = d == 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
        if (d != 0) h = max == r ? ((g - b) / d) % 6 : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        h *= 60; if (h < 0) h += 360; out[0] = h; out[1] = s; out[2] = l;
    }
    private static int hslColor(float[] hsl) {
        float h = hsl[0], s = hsl[1], l = hsl[2], c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = l - c / 2, r, g, b;
        if (h < 60) { r = c; g = x; b = 0; } else if (h < 120) { r = x; g = c; b = 0; } else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; } else if (h < 300) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return 0xff000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }
    private boolean darkWallpaper() {
        try {
            WallpaperColors colors = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            return colors != null && (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0;
        } catch (RuntimeException unknown) { return false; }
    }

    private static final int LOCATION = 7531, MIC = 7532;
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == LOCATION) pushBoard();
        if (request == MIC) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) voice();
            else js("launcher.voice('error'," + JSONObject.quote("마이크를 허용해야 퍼레와 말로 이야기할 수 있어요") + ")");
        }
    }
    // ── 퍼레's voice talk (BuddyVoice): 퍼레 held on the home screen; apart from the edge handle's voice assistant ──
    /** The page shows the talk while the home screen is in front; the fingerprint is asked here. */
    private final BuddyVoice.Show talkShown = new BuddyVoice.Show() {
        @Override public void state(String state, String text) { state(state, text, 0); }
        /** With ms, how long the words take to say: the bubble types them along with the sound. */
        @Override public void state(String state, String text, int ms) { state(state, text, ms, ""); }
        /** With go, what a tap on the words opens (an app that did not come up: the way through, "fix:overlay"). */
        @Override public void state(String state, String text, int ms, String go) {
            js("launcher.voice(" + JSONObject.quote(state) + "," + JSONObject.quote(text) + "," + Math.max(0, ms) + "," + JSONObject.quote(go == null ? "" : go) + ")");
        }
    };
    private final BuddyVoice.Unlock talkUnlock = then -> main.post(() -> Fingerprint.ask(this, "퍼레의 비밀 기억", "지문으로 확인하면 말해 줄게", () -> { BuddyTalk.unlock(); then.run(); }, then));
    /** The typed talk the voice talk goes on from, kept while the microphone is asked for. */
    private String voiceSeed = "[]";
    private void voice() { String seed = voiceSeed; voiceSeed = "[]"; voice(seed); }
    /** seed: the last turns typed in 퍼레와 대화 ([[role, text] …]), "[]" when 퍼레 itself was held. */
    private void voice(String seed) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { voiceSeed = seed == null ? "[]" : seed; requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, MIC); return; }
        BuddyVoice talk = BuddyVoice.of(this, ((GyeotApp) getApplication()).store());
        talk.attach(talkShown, talkUnlock);
        if (talk.on()) talk.stop(); else talk.start(seed);
    }
    @Override protected void onResume() {
        super.onResume(); shown = true;
        NoticeListener.changed = badgesChanged; NoticeListener.deliveryChanged = deliveryChanged;
        main.removeCallbacks(refresh); if (ready) { refresh.run(); pushBadges(); if (web != null) web.evaluateJavascript("launcher.shown&&launcher.shown()", null); }
        BuddyVoice talk = BuddyVoice.current(); if (talk != null) talk.attach(talkShown, talkUnlock);
        if (ready) BuddyBoard.shown(selfCall);
        dev.jun.gyeot.core.BuddyJobs.unlisten(jobsChanged); dev.jun.gyeot.core.BuddyJobs.listen(jobsChanged);
        if (ready) pushJobs(true);
    }
    // ── 맡은 일 (BuddyJobs, 준 "문 하나"): a small line under the search with 중지, and one passing word when one ends ──
    private final java.util.function.Consumer<dev.jun.gyeot.core.BuddyJobs.Job> jobsChanged = job -> main.post(() -> { if (shown && ready) pushJobs(!job.running()); });
    /**
     * The jobs' line to the page; told: also the passing word of each one that ended and was not said yet (준: "흘러가듯이 슬쩍",
     * once: the voice talk says it when it is on), a tap on it opening its result.
     */
    private void pushJobs(boolean told) {
        long now = System.currentTimeMillis();
        js("launcher.jobs&&launcher.jobs(" + dev.jun.gyeot.core.BuddyJobs.json(now) + ")");
        BuddyVoice talk = BuddyVoice.current();
        if (!told || talk != null && talk.on()) return;
        for (dev.jun.gyeot.core.BuddyJobs.Job j : dev.jun.gyeot.core.BuddyJobs.untold(now)) {
            String line = dev.jun.gyeot.core.BuddyJobs.passing(j);
            if (line.isEmpty() || !dev.jun.gyeot.core.BuddyJobs.claim(j)) continue;
            js("launcher.jobDone&&launcher.jobDone(" + Jsons.obj("id", j.id, "text", line, "go", j.open().isEmpty() ? "" : "job:" + j.id) + ")");
            break;
        }
    }
    /** 퍼레 itself, asked by voice or in the talk (BuddyBoard): a launcher.self() call to the page. */
    private final java.util.function.Consumer<String> selfCall = call -> js(call);
    @Override protected void onPause() {
        BuddyBoard.away(); dev.jun.gyeot.core.BuddyJobs.unlisten(jobsChanged);
        shown = false; pausedAt = android.os.SystemClock.uptimeMillis(); NoticeListener.changed = null; NoticeListener.deliveryChanged = null; main.removeCallbacks(refresh); main.removeCallbacks(boardSoon);
        prefs.edit().putLong("awaySince", System.currentTimeMillis()).apply();
        // The voice talk goes on over the next app (준: "퍼레가 말할때 브라우저라도 켜면 바로 꺼져버려").
        BuddyVoice talk = BuddyVoice.current(); if (talk != null) talk.detach();
        if (ready && web != null) web.evaluateJavascript("launcher.hidden&&launcher.hidden()", null);
        super.onPause();
    }
    @Override protected void onStop() {
        // Left late in the evening: the night's digest now if it was not done today (BuddyTalk.dream), off the main thread.
        if (java.time.LocalTime.now().getHour() >= 22) try { io.execute(() -> BuddyTalk.dreamTonight(getApplicationContext())); } catch (RuntimeException stopped) {}
        // The home screen is gone: the taskbar over other apps may show (Taskbar).
        visible = false; PhoneService.taskbarChanged();
        super.onStop();
    }
    /**
     * Home pressed while the home screen is in front (Android pauses it just before, so "just paused" counts as in front:
     * 준: "폴더화면에서 홈버튼을 눌러서 돌아가기가 안돼"): everything open closes and the home page comes back, at its top.
     * Back from another app: only what was left open closes.
     */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String show = intent == null ? null : intent.getStringExtra(SHOW);
        if (show != null) { if (ready && web != null) showPad(show); else pendingShow = show; return; }
        String page = intent == null ? null : intent.getStringExtra(PAGE);
        if (page != null) { if (ready && web != null) goPage(page); else pendingPage = page; return; }
        boolean front = shown || android.os.SystemClock.uptimeMillis() - pausedAt < 700;
        if (ready && web != null) web.evaluateJavascript(front ? "launcher.home()" : "launcher.closeAll&&launcher.closeAll()", null);
    }
    private long pausedAt;
    private void goPage(String page) { if (web != null) web.evaluateJavascript("launcher.goPage(" + org.json.JSONObject.quote(page) + ")", null); }
    /** The 작업 표시줄's all-apps button: Launchpad over whatever was open, with its search when the button was held. */
    private void showPad(String show) {
        if (web != null) web.evaluateJavascript("launcher.pad&&launcher.pad(" + "search".equals(show) + ")", null);
    }
    @Override protected void onStart() { super.onStart(); shownAt = android.os.SystemClock.uptimeMillis(); visible = true; PhoneService.taskbarChanged(); }
    @Override public void onBackPressed() {
        // A home screen does not close: back only closes what is open on it.
        if (ready && web != null) web.evaluateJavascript("launcher.back()", null);
    }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c); systemBars();
        if (ready && web != null) web.evaluateJavascript("launcher.resized()", null);
    }
    @Override protected void onDestroy() {
        if (alive.get() == this) alive = new java.lang.ref.WeakReference<>(null);
        try { launcher.unregisterCallback(changed); } catch (RuntimeException ignored) {}
        prefs.unregisterOnSharedPreferenceChangeListener(pagesChanged);
        if (NoticeListener.changed == badgesChanged) NoticeListener.changed = null;
        if (NoticeListener.deliveryChanged == deliveryChanged) NoticeListener.deliveryChanged = null;
        LpMedia.host(this, null); LpMedia.gone(this);
        main.removeCallbacksAndMessages(null); io.shutdownNow();
        if (web != null) { try { web.destroy(); } catch (RuntimeException ignored) {} web = null; }
        super.onDestroy();
    }

    // ── what the page shows ─────────────────────────────────────
    private void js(String call) { main.post(() -> { if (web != null && ready) web.evaluateJavascript(call, null); }); }
    private void pushApps() { try { io.execute(() -> js("launcher.apps(" + apps() + ")")); } catch (RuntimeException stopped) {} }
    /**
     * The board, locked or not. Resumed while the lock screen is still going away (a fingerprint unlock), the phone may still
     * say locked: then it is asked again shortly, until unlocked (준: "가끔 화면이 안 뜨는 이런 표시 오류가..").
     */
    private void pushBoard() {
        try {
            io.execute(() -> {
                KeyguardManager k = getSystemService(KeyguardManager.class);
                boolean locked = k != null && k.isKeyguardLocked();
                js("launcher.board(" + HtmlWallpaper.known(this, locked) + ")");
                pushedLocked = locked; main.removeCallbacks(unlocked);
                if (locked && shown && lockedTries++ < 20) main.postDelayed(unlocked, 700); else if (!locked) lockedTries = 0;
            });
        } catch (RuntimeException stopped) {}
    }
    private volatile int lockedTries; private volatile boolean pushedLocked;
    private final Runnable unlocked = () -> { if (shown && ready) pushBoard(); };
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && ready && pushedLocked) { lockedTries = 0; pushBoard(); }
    }
    private void pushBadges() { try { io.execute(() -> js("launcher.badges(" + new JSONObject(NoticeListener.badges()) + ")")); } catch (RuntimeException stopped) {} }

    /** Every launchable app (each profile), its stack, the dock and the recent ones. */
    JSONObject apps() {
        List<LauncherActivityInfo> all = new ArrayList<>();
        try { for (UserHandle u : launcher.getProfiles()) all.addAll(launcher.getActivityList(null, u)); } catch (RuntimeException unreadable) {}
        UserManager users = getSystemService(UserManager.class);
        JSONObject moved = read(MOVED, new JSONObject());
        JSONArray list = new JSONArray(); List<String> ids = new ArrayList<>(), packages = new ArrayList<>();
        Map<String, Integer> badges = NoticeListener.badges();
        known.clear();
        for (LauncherActivityInfo info : all) {
            String id = id(info, users); known.put(id, info);
            ApplicationInfo app = info.getApplicationInfo(); String pkg = app.packageName, label = String.valueOf(info.getLabel());
            String stack = moved.optString(id, "");
            if (stack.isEmpty()) stack = AppStacks.of(pkg, label, app.category, (app.flags & ApplicationInfo.FLAG_IS_GAME) != 0, (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            long version = 0; try { version = getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime; } catch (Exception ignored) {}
            list.put(Jsons.obj("id", id, "label", label, "pkg", pkg, "stack", stack, "v", version));
            ids.add(id); packages.add(pkg);
        }
        JSONArray dock = read(DOCK, (JSONArray) null);
        if (dock == null) {
            // A first dock from the everyday apps there are (the user changes it with a long press).
            dock = new JSONArray();
            for (String pkg : AppStacks.dock(packages, 6)) { int at = packages.indexOf(pkg); if (at >= 0) dock.put(ids.get(at)); }
            prefs.edit().putString(DOCK, dock.toString()).apply();
        }
        // The folders in the order the user dragged them into (준: "앱 라이브러리 폴더를 누르고 있으면 위치 옮기기로"), the rest after.
        JSONArray stacks = new JSONArray(), order = read("stackOrder", new JSONArray()); List<String> placed = new ArrayList<>();
        for (int i = 0; i < order.length(); i++) { AppStacks.Stack s = AppStacks.stack(order.optString(i)); if (s.id().equals(order.optString(i)) && !placed.contains(s.id())) { placed.add(s.id()); stacks.put(Jsons.obj("id", s.id(), "name", s.name(), "color", s.color())); } }
        for (AppStacks.Stack s : AppStacks.STACKS) if (!placed.contains(s.id())) stacks.put(Jsons.obj("id", s.id(), "name", s.name(), "color", s.color()));
        // The dock counts only for messages and calls (준: "독에서 메세지와 전화 제외하고는 우측상단 숫자 안 뜨게"): the phone's own
        // messaging and phone apps, and the usual ones besides.
        JSONArray counted = new JSONArray();
        try { String sms = android.provider.Telephony.Sms.getDefaultSmsPackage(this); if (sms != null) counted.put(sms); } catch (RuntimeException ignored) {}
        try { android.telecom.TelecomManager tm = getSystemService(android.telecom.TelecomManager.class); String dial = tm == null ? null : tm.getDefaultDialerPackage(); if (dial != null) counted.put(dial); } catch (RuntimeException ignored) {}
        for (String pkg : new String[]{"com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms", "com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"}) counted.put(pkg);
        return Jsons.obj("apps", list, "dock", dock, "recent", read(RECENT, new JSONArray()), "stacks", stacks, "badges", new JSONObject(badges), "order", read(ORDER, new JSONObject()), "dockCounts", counted);
    }
    /** An app's id here and in the side dock: its activity and its profile. */
    static String id(LauncherActivityInfo info, UserManager users) {
        long serial = users == null ? 0 : users.getSerialNumberForUser(info.getUser());
        return info.getComponentName().flattenToShortString() + "@" + serial;
    }
    private JSONArray read(String key, JSONArray none) { try { return new JSONArray(prefs.getString(key, "")); } catch (Exception e) { return none; } }
    private JSONObject read(String key, JSONObject none) { try { return new JSONObject(prefs.getString(key, "")); } catch (Exception e) { return none; } }

    // ── 퍼레 on the home screen ─────────────────────────────────
    private final AtomicReference<Runnable> chatCancel = new AtomicReference<>();
    private volatile long chatTicket;
    /**
     * A written answer shown, and the thing it names done (BuddyTalk.ACTS; typed, so an alarm needs no spoken yes); without
     * the sentences 퍼레 does not say to the question (BuddyMind.unasked: cheering, rest, chores, coins), "응응!" when that was all.
     * What the user told is learned with the six turns before it (history) as what came before.
     */
    private void chatAnswer(String question, String history, String text) {
        JSONObject a = BuddyTalk.act(text);
        String written = text.replaceAll("(?m)^\\s*할\\s*일\\s*[:：].*$", "").strip();
        String shown = dev.jun.gyeot.core.BuddyMind.keepAsked(written, question);
        if (a == null) {
            if (shown.isEmpty() && !written.isEmpty()) shown = "응응!";
            js("launcher.reply(" + JSONObject.quote(shown) + ")"); BuddyTalk.learnChat(this, history, question, shown); return;
        }
        if (shown.isEmpty()) shown = BuddyTools.say(a);
        // A job handed over has the user's own words as its authority (BuddyHands); typed, an alarm needs no spoken yes.
        BuddyVoice.Outcome o = BuddyActs.run(this, a, BuddyHands.TOOLS.contains(a.optString("tool")) ? question : "네", false);
        String more = o == null || o.say() == null ? "" : o.say().strip();
        js("launcher.reply(" + JSONObject.quote(more.isEmpty() ? shown : shown + "\n" + more) + ")");
    }
    /** The answer to words about the jobs 퍼레 took on (their status, or 중지 for the newest running one), or null for other words. */
    static String jobWords(String said) {
        long now = System.currentTimeMillis();
        dev.jun.gyeot.core.BuddyJobs.Job running = dev.jun.gyeot.core.BuddyJobs.latestRunning();
        if (running != null && dev.jun.gyeot.core.BuddyJobs.asksStop(said)) return dev.jun.gyeot.core.BuddyJobs.stop(running.id) ? "‘" + running.label + "’ 멈출게." : "그건 벌써 끝났어.";
        if (dev.jun.gyeot.core.BuddyJobs.asksStatus(said) && dev.jun.gyeot.core.BuddyJobs.any(now)) return dev.jun.gyeot.core.BuddyJobs.answer(now);
        return null;
    }
    // ── the page's own files ────────────────────────────────────
    private WebResourceResponse own(String path) {
        try {
            if (path.equals("/launcher.html") || path.equals("/")) return HtmlWallpaper.html(page());
            if (path.equals("/board.css") || path.equals("/board.js") || path.equals("/talk.js")) return HtmlWallpaper.asset(this, path);
            if (path.startsWith("/appicon/")) return HtmlWallpaper.appIcon(this, path);
            if (path.equals("/pad-paper.webp")) return new WebResourceResponse("image/webp", null, getAssets().open("decor/pad-paper.webp"));
            if (path.equals("/mascot.png")) { File f = HtmlWallpaper.mascot(this); return f.isFile() ? new WebResourceResponse("image/png", null, new java.io.FileInputStream(f)) : HtmlWallpaper.missing(); }
            if (path.startsWith("/sq/")) {
                String id = Uri.decode(path.substring(4)).replaceAll("\\.png$", "");
                byte[] png = square(id);
                return png == null ? HtmlWallpaper.missing() : new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
            }
            if (path.startsWith("/icon/")) {
                String id = Uri.decode(path.substring(6)).replaceAll("\\.png$", "");
                byte[] png = icon(id);
                return png == null ? HtmlWallpaper.missing() : new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
            }
        } catch (Exception unreadable) { /* missing */ }
        return HtmlWallpaper.missing();
    }
    private String page() throws Exception {
        String html;
        try (InputStream in = getAssets().open("decor/launcher.html")) { html = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        String kind = HtmlWallpaper.mascotReady(this);
        JSONObject config = Jsons.obj("launcher", true, "token", secret, "pages", pagesJson(), "dark", darkWallpaper(), "accent", accent(), "bg", prefs.getString("bg", "aurora"), "mascot", !kind.isEmpty(), "mascotKind", kind, "mascotAt", HtmlWallpaper.mascot(this).lastModified());
        return html.replace("/*GYEOT*/", "window.GYEOT=" + config.toString().replace("</", "<\\/") + ";");
    }
    /** The pages to the right of 홈 the user put there (화면 꾸미기 → 들퍼레 홈 화면 페이지): web pieces and their own pages. */
    static final String PAGES = "pages";
    JSONArray pagesJson() {
        JSONArray out = new JSONArray();
        // frame: where its frame loads it (the LP piece from the app's own address: PieceExtras.frameUrl)
        for (dev.jun.gyeot.core.HomeBoard.Slot s : dev.jun.gyeot.core.HomeBoard.slots(prefs.getString(PAGES, ""), "", "")) if (s.web()) out.put(PieceExtras.framed(s.json(), s.src()));
        return out;
    }
    private final SharedPreferences.OnSharedPreferenceChangeListener pagesChanged = (p, key) -> { if (PAGES.equals(key)) js("launcher.pages(" + pagesJson() + ")"); };
    /** An app's icon as the phone draws it (its shape, a work badge), once: kept in memory and in the cache folder. */
    /**
     * An app's icon as a rounded square, for the folder previews (준: "폴더 미리보기 아이콘 감싸는 테두리를 둥근 사각형으로 해줘"): the
     * app's own adaptive layers, read from its resources rather than through the phone theme's round frame, clipped to a
     * rounded square; an icon without layers sits on white in the same shape. Kept in memory and the cache folder like icon().
     */
    private byte[] square(String id) {
        byte[] got = ICONS.get("sq:" + id); if (got != null) return got;
        LauncherActivityInfo info = known.get(id); if (info == null) return null;
        long version = 0; try { version = getPackageManager().getPackageInfo(info.getApplicationInfo().packageName, 0).lastUpdateTime; } catch (Exception ignored) {}
        File kept = new File(new File(getCacheDir(), "home-icons"), "sq-" + Integer.toHexString(id.hashCode()) + "-" + version + ".png");
        try {
            if (kept.isFile()) { got = Files.readAllBytes(kept.toPath()); ICONS.put("sq:" + id, got); return got; }
            int dpi = getResources().getDisplayMetrics().densityDpi; Drawable d = null;
            try {
                int res = info.getActivityInfo().getIconResource(); if (res == 0) res = info.getApplicationInfo().icon;
                if (res != 0) d = getPackageManager().getResourcesForApplication(info.getApplicationInfo()).getDrawableForDensity(res, dpi, null);
            } catch (Exception | OutOfMemoryError own) { d = null; }
            if (d == null) d = info.getIcon(dpi);
            int size = 144; Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(b);
            android.graphics.Path shape = new android.graphics.Path(); shape.addRoundRect(new android.graphics.RectF(0, 0, size, size), size * .26f, size * .26f, android.graphics.Path.Direction.CW);
            c.clipPath(shape);
            if (d instanceof android.graphics.drawable.AdaptiveIconDrawable a) {
                int pad = size / 4; android.graphics.Rect layer = new android.graphics.Rect(-pad, -pad, size + pad, size + pad);
                Drawable back = a.getBackground(), front = a.getForeground();
                if (back != null) { back.setBounds(layer); back.draw(c); } else c.drawColor(android.graphics.Color.WHITE);
                if (front != null) { front.setBounds(layer); front.draw(c); }
            } else {
                c.drawColor(android.graphics.Color.WHITE);
                int inset = size / 9; d.setBounds(inset, inset, size - inset, size - inset); d.draw(c);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, out); got = out.toByteArray();
            ICONS.put("sq:" + id, got);
            kept.getParentFile().mkdirs(); Files.write(kept.toPath(), got);
            return got;
        } catch (Exception | OutOfMemoryError unreadable) { return got != null ? got : icon(id); }
    }
    private byte[] icon(String id) {
        byte[] got = ICONS.get(id); if (got != null) return got;
        LauncherActivityInfo info = known.get(id); if (info == null) return null;
        long version = 0; try { version = getPackageManager().getPackageInfo(info.getApplicationInfo().packageName, 0).lastUpdateTime; } catch (Exception ignored) {}
        File kept = new File(new File(getCacheDir(), "home-icons"), Integer.toHexString(id.hashCode()) + "-" + version + ".png");
        try {
            if (kept.isFile()) { got = Files.readAllBytes(kept.toPath()); ICONS.put(id, got); return got; }
            Drawable d = info.getBadgedIcon(getResources().getDisplayMetrics().densityDpi);
            int size = 168; Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, size, size); d.draw(new Canvas(b));
            ByteArrayOutputStream out = new ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, out); got = out.toByteArray();
            ICONS.put(id, got);
            kept.getParentFile().mkdirs(); Files.write(kept.toPath(), got);
            return got;
        } catch (Exception | OutOfMemoryError unreadable) { return got; }
    }

    // ── what the page asks ──────────────────────────────────────
    /**
     * The page's one way in (준: "들퍼레 홈 화면 우측 페이지들을 커스터마이징": other sites now run in frames on it, and Android
     * gives a WebView's bridge to every frame): a call counts only with the secret this page was given (window.GYEOT.token),
     * which no other frame can read.
     */
    private final String secret = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
    private final class Gate {
        @JavascriptInterface public void call(String token, String name, String args) {
            if (token == null || !java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) return;
            JSONArray a; try { a = new JSONArray(args == null ? "[]" : args); } catch (Exception bad) { return; }
            Bridge b = bridge;
            switch (name == null ? "" : name) {
                case "ready" -> b.ready();
                case "launch" -> b.launch(a.optString(0, null), (float) a.optDouble(1), (float) a.optDouble(2), (float) a.optDouble(3), (float) a.optDouble(4));
                case "chat" -> b.chat(a.optString(0, null), a.optString(1, "[]"));
                case "stopChat" -> b.stopChat();
                case "idea" -> b.idea(a.optString(0, null), a.optBoolean(1));
                case "weather" -> b.weather();
                case "look" -> b.look(a.optString(0));
                case "order" -> b.order(a.optString(0, null), a.optString(1, "[]"));
                case "stackOrder" -> b.stackOrder(a.optString(0, "[]"));
                case "dock" -> b.dock(a.optString(0, "[]"));
                case "move" -> b.move(a.optString(0, null), a.optString(1, ""));
                case "info" -> b.info(a.optString(0, null));
                case "uninstall" -> b.uninstall(a.optString(0, null));
                case "open" -> b.open(a.optString(0, ""));
                // 중지 on a job's line (BuddyJobs.stop: the engine's own stop, the same as the voice pill's).
                case "jobStop" -> { String id = a.optString(0, ""); try { io.execute(() -> dev.jun.gyeot.core.BuddyJobs.stop(id)); } catch (RuntimeException ignored) {} }
                case "ask" -> b.ask(a.optString(0, null));
                case "web" -> b.web(a.optString(0, null));
                case "settings" -> b.settings(a.optString(0, ""));
                case "bars" -> b.bars(a.optBoolean(0));
                case "voice" -> { String seed = a.optString(0, "[]"); main.post(() -> voice(seed)); }
                case "voiceStop" -> main.post(() -> { BuddyVoice talk = BuddyVoice.current(); if (talk != null) talk.stop(); });
                case "lived" -> { String kind = a.optString(0, ""), key = a.optString(1, ""), why = a.optString(3, ""); double joy = a.optDouble(2, 0); try { io.execute(() -> BuddyTalk.lived(HomeActivity.this, kind, key, joy, why)); } catch (RuntimeException ignored) {} }
                case "care" -> { String kind = a.optString(0, ""); try { io.execute(() -> js("launcher.life(" + BuddyTalk.care(HomeActivity.this, kind) + ")")); } catch (RuntimeException ignored) {} }
                case "home" -> { try { io.execute(() -> js("launcher.env(" + BuddyTalk.env(HomeActivity.this) + ")")); } catch (RuntimeException ignored) {} }
                case "think" -> { String why = a.optString(0, "tap"); try { io.execute(() -> BuddyTalk.think(HomeActivity.this, why, line -> js("launcher.thought(" + JSONObject.quote(line) + ")"), () -> js("launcher.thoughtFail()"))); } catch (RuntimeException ignored) {} }
                default -> {}
            }
        }
    }
    private final Bridge bridge = new Bridge();
    private final class Bridge {
        public void ready() {
            main.post(() -> {
                ready = true;
                if (web != null) web.evaluateJavascript("launcher.insets(" + top + "," + bottom + "," + keyboard + ")", null);
                pushApps(); main.removeCallbacks(refresh); if (shown) refresh.run(); else pushBoard();
                if (shown) pushJobs(true);
                if (pendingShow != null) { showPad(pendingShow); pendingShow = null; }
                if (pendingPage != null) { goPage(pendingPage); pendingPage = null; }
                // 퍼레 itself, asked by voice or in the talk (BuddyBoard): now that the page can hear it.
                if (shown) BuddyBoard.shown(selfCall);
                // The weather needs roughly where the phone is: asked once, here where the user sees why.
                if (!Weather.allowed(HomeActivity.this) && !prefs.getBoolean("askedLocation", false)) {
                    prefs.edit().putBoolean("askedLocation", true).apply();
                    try { requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION); } catch (RuntimeException ignored) {}
                }
            });
        }
        /** Opens the app, growing from its icon (x, y, w, h in the page's pixels). */
        public void launch(String id, float x, float y, float w, float h) {
            main.post(() -> {
                LauncherActivityInfo info = id == null ? null : known.get(id); if (info == null) return;
                float d = getResources().getDisplayMetrics().density;
                Rect from = new Rect(Math.round(x * d), Math.round(y * d), Math.round((x + w) * d), Math.round((y + h) * d));
                Bundle opts = web == null ? null : ActivityOptions.makeScaleUpAnimation(web, from.left, from.top, from.width(), from.height()).toBundle();
                try { launcher.startMainActivity(info.getComponentName(), info.getUser(), from, opts); }
                catch (RuntimeException gone) { Toast.makeText(HomeActivity.this, "이 앱을 열지 못했어요.", Toast.LENGTH_SHORT).show(); pushApps(); return; }
                JSONArray names = new JSONArray(); names.put(String.valueOf(info.getLabel()));
                try { JSONArray was = new JSONArray(prefs.getString("recentLabels", "[]")); for (int i = 0; i < was.length() && names.length() < 6; i++) if (!names.optString(0).equals(was.optString(i))) names.put(was.optString(i)); } catch (Exception ignored) {}
                prefs.edit().putString("recentLabels", names.toString()).apply();
                // 퍼레's day log: the kind of app (its folder's name), never which app's contents.
                try {
                    android.content.pm.ApplicationInfo app = info.getApplicationInfo(); String moved = read(MOVED, new JSONObject()).optString(id, "");
                    String stack = !moved.isEmpty() ? moved : AppStacks.of(app.packageName, String.valueOf(info.getLabel()), app.category, (app.flags & ApplicationInfo.FLAG_IS_GAME) != 0, (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    String kind = AppStacks.stack(stack).name(); io.execute(() -> BuddyTalk.today(HomeActivity.this, "app", kind));
                } catch (RuntimeException ignored) {}
                JSONArray recent = new JSONArray(); recent.put(id);
                JSONArray old = read(RECENT, new JSONArray());
                for (int i = 0; i < old.length() && recent.length() < 6; i++) if (!id.equals(old.optString(i))) recent.put(old.optString(i));
                prefs.edit().putString(RECENT, recent.toString()).apply();
            });
        }
        /**
         * 퍼레 answers right on the home screen (준: "퍼레대화의 경우 그 자리에서 바로 되게 해줘", "기본 엔비디아 모델"): the
         * question, with the few turns before it, to the NVIDIA model the app is connected to (NimClient, the user's own key),
         * the answer streamed into the page as it comes. Not connected: the page says so and how to connect.
         */
        /** Set while a question goes on after its fingerprint (asked once). */
        private boolean fingerprinted;
        public void chat(String question, String history) {
            if (question == null || question.isBlank() || question.length() > 4000) return;
            // "자가 점검해 줘" (퍼레 자동 점검): its sheet, with no brain asked.
            if (BuddyCheck.asked(question)) {
                js("launcher.reply(" + JSONObject.quote("자동 점검 열게! 끝나면 실패한 것만 보여 줄게.") + ")");
                main.post(() -> { try { startActivity(new Intent(HomeActivity.this, DecorActivity.class).putExtra(DecorActivity.CHECK, true)); } catch (RuntimeException none) { /* the reply said it */ } });
                return;
            }
            // 맡은 일: how it goes ("어떻게 돼 가?") and 중지 ("하던 거 멈춰"), from the job's own record, with no brain.
            String jobs = jobWords(question);
            if (jobs != null) { js("launcher.reply(" + JSONObject.quote(jobs) + ")"); return; }
            AppStore store = ((GyeotApp) getApplication()).store();
            // 퍼레's brain (BuddyTalk.brain): Groq's Qwen unless the user chose NVIDIA; the other when the chosen one is not connected.
            boolean custom = BuddyTalk.CUSTOM.equals(BuddyTalk.brain(HomeActivity.this)) && !BuddyTalk.customLabel(store).isEmpty();
            boolean qwen = GroqWork.on() && !BuddyTalk.NIM.equals(BuddyTalk.brain(HomeActivity.this));
            if (!custom && !qwen && !store.nim.connected()) { js("launcher.replyError(" + JSONObject.quote("퍼레의 두뇌가 아직 연결되지 않았어요. 설정 → AI 연결에서 Groq나 NVIDIA 키를 넣으면 여기서 바로 답해요.") + ",\"settings\")"); return; }
            // Private memories asked for: the fingerprint first (준: "개인정보 중 중요한 내용은 지문인식을 요청해줘"); without it the answer leaves them out.
            if (!fingerprinted && BuddyTalk.needsUnlock(HomeActivity.this, question)) {
                main.post(() -> Fingerprint.ask(HomeActivity.this, "퍼레의 비밀 기억", "지문으로 확인하면 말해 줄게", () -> { BuddyTalk.unlock(); fingerprinted = true; chat(question, history); fingerprinted = false; }, () -> { fingerprinted = true; chat(question, history); fingerprinted = false; }));
                return;
            }
            Runnable old = chatCancel.getAndSet(null); if (old != null) try { old.run(); } catch (RuntimeException ignored) {}
            long ticket = ++chatTicket;
            Thread t = new Thread(() -> {
                AtomicReference<Runnable> cancel = new AtomicReference<>(); chatCancel.set(() -> { Runnable r = cancel.get(); if (r != null) r.run(); });
                try {
                    // 퍼레와 대화's brain, as 퍼레 자동 점검 asks it too (BuddyTalk.chatText).
                    String text = BuddyTalk.chatText(HomeActivity.this, question, history, cancel, () -> ticket == chatTicket,
                        delta -> { if (ticket == chatTicket && !delta.isEmpty()) js("launcher.stream(" + JSONObject.quote(delta) + ")"); });
                    if (ticket == chatTicket) chatAnswer(question, history, text);
                } catch (java.util.concurrent.CancellationException stopped) { /* a newer question, or stopped */ }
                catch (Exception e) {
                    String why = e instanceof java.io.IOException ? "인터넷이 끊겼거나 " + (qwen ? "Groq" : "NVIDIA") + "가 답하지 않았어요. 잠시 뒤 다시 물어봐 주세요." : e.getMessage() == null ? "답을 받지 못했어요." : e.getMessage();
                    if (ticket == chatTicket) js("launcher.replyError(" + JSONObject.quote(why) + ",\"\")");
                }
            }, "gyeot-home-chat");
            t.setDaemon(true); t.start();
        }
        public void stopChat() { chatTicket++; Runnable r = chatCancel.getAndSet(null); if (r != null) try { r.run(); } catch (RuntimeException ignored) {} }
        /**
         * 퍼레's suggestion, from the home screen (준: "제안 눌렀는데 단순히 퍼레앱이 켜지기만 해"): 맡기기 opens 메인 대화 with
         * its request in the box (or the work room it continues), 넘기기 puts it away; either way the next mornings learn.
         */
        public void idea(String title, boolean use) {
            if (title == null) return;
            main.post(() -> {
                AppStore store = ((GyeotApp) getApplication()).store();
                dev.jun.gyeot.core.Proactive.Card card = null;
                for (dev.jun.gyeot.core.Proactive.Card c : Briefing.cards(store)) if (c.title().equals(title)) { card = c; break; }
                if (card == null) { pushBoard(); return; }
                Briefing.react(store, card, use);
                pushBoard();
                if (!use) return;
                Intent open = new Intent(HomeActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if (!card.work().isEmpty() && dev.jun.gyeot.core.WorkBook.find(store.works(), card.work()) != null) open.putExtra("work", card.work()).putExtra("tab", 3);
                else open.putExtra(MainNavigation.TARGET, "chat").putExtra(MainActivity.ASK, card.ask());
                try { startActivity(open); } catch (RuntimeException ignored) {}
            });
        }
        /** The weather widget: the phone's own weather app, else the weather on the web. */
        public void weather() {
            main.post(() -> {
                for (String pkg : new String[]{"com.sec.android.daemonapp", "com.samsung.android.weather", "com.google.android.apps.weather"}) {
                    Intent open = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (open != null) { try { startActivity(open); return; } catch (RuntimeException ignored) {} }
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("날씨")))); } catch (RuntimeException ignored) {}
            });
        }
        /** Over something light (Launchpad in pastel) the bars' icons turn dark; back on the home screen, light again. */
        public void bars(boolean light) {
            main.post(() -> {
                WindowInsetsController c = getWindow().getInsetsController(); if (c == null) return;
                int both = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? both : 0, both);
            });
        }
        /** The background: "aurora" (the home screen's own night sky) or "photo" (the user's wallpaper under a dark veil). */
        public void look(String bg) { prefs.edit().putString("bg", "photo".equals(bg) ? "photo" : "aurora").apply(); }
        /** A folder's icons in the order the user dragged them into (준: "폴더 내에서 아이콘 위치 이동이 안돼"). */
        public void order(String stack, String ids) {
            try {
                JSONArray a = new JSONArray(ids); if (a.length() > 300 || stack == null || stack.length() > 20) return;
                JSONObject all = read(ORDER, new JSONObject()); Jsons.put(all, AppStacks.stack(stack).id(), a);
                prefs.edit().putString(ORDER, all.toString()).apply();
            } catch (Exception ignored) {}
        }
        public void stackOrder(String ids) {
            try { JSONArray a = new JSONArray(ids); if (a.length() <= 40) prefs.edit().putString("stackOrder", a.toString()).apply(); } catch (Exception ignored) {}
        }
        public void dock(String ids) {
            try { JSONArray a = new JSONArray(ids); if (a.length() <= 24) prefs.edit().putString(DOCK, a.toString()).apply(); } catch (Exception ignored) {}
        }
        /** The app goes to another stack ("" to sort it by itself again). */
        public void move(String id, String stack) {
            JSONObject moved = read(MOVED, new JSONObject());
            if (stack == null || stack.isEmpty()) moved.remove(id); else Jsons.put(moved, id, AppStacks.stack(stack).id());
            prefs.edit().putString(MOVED, moved.toString()).apply();
            pushApps();
        }
        public void info(String id) {
            main.post(() -> { LauncherActivityInfo i = (id == null ? null : known.get(id)); if (i != null) try { launcher.startAppDetailsActivity(i.getComponentName(), i.getUser(), null, null); } catch (RuntimeException ignored) {} });
        }
        public void uninstall(String id) {
            main.post(() -> {
                LauncherActivityInfo i = (id == null ? null : known.get(id)); if (i == null) return;
                try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + i.getApplicationInfo().packageName)).putExtra(Intent.EXTRA_USER, i.getUser())); }
                catch (RuntimeException refused) { info(id); }
            });
        }
        /** 퍼레 홈's cards: the app's own screens. */
        public void open(String what) {
            // The way through for something 퍼레 could not do (its words in the voice talk's bubble): one tap (BuddyMisses).
            if (what != null && what.startsWith("fix:")) {
                Intent fix = BuddyMisses.fixIntent(HomeActivity.this, what.substring(4));
                if (fix != null) main.post(() -> { try { startActivity(fix.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException none) { Toast.makeText(HomeActivity.this, "이 휴대폰에서는 열 수 없어요.", Toast.LENGTH_SHORT).show(); } });
                return;
            }
            // A job 퍼레 took on (its line, its passing word): its result (BuddyHands.opening).
            if (what != null && what.startsWith("job:")) {
                Intent result = BuddyHands.opening(HomeActivity.this, dev.jun.gyeot.core.BuddyJobs.find(what.substring(4)));
                if (result != null) main.post(() -> { try { startActivity(result); } catch (RuntimeException none) { Toast.makeText(HomeActivity.this, "열 수 있는 화면이 없어요.", Toast.LENGTH_SHORT).show(); } });
                return;
            }
            Intent t = HtmlWallpaper.target(HomeActivity.this, what == null ? "" : what);
            if (t != null && t.getComponent() != null) t.putExtra(HtmlWallpaper.FROM_HOME, true);
            if (t != null) main.post(() -> { try { startActivity(t); } catch (RuntimeException none) { Toast.makeText(HomeActivity.this, "열 수 있는 앱이 없어요.", Toast.LENGTH_SHORT).show(); } });
        }
        /** The search's words, into 퍼레's message box (the user reads and sends them). */
        public void ask(String text) {
            if (text == null || text.isBlank()) return;
            main.post(() -> startActivity(new Intent(HomeActivity.this, MainActivity.class).putExtra(MainNavigation.TARGET, "chat").putExtra(MainActivity.ASK, text.strip())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        }
        public void web(String text) {
            if (text == null || text.isBlank()) return;
            main.post(() -> {
                try { startActivity(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text.strip())); }
                catch (RuntimeException none) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(text.strip())))); } catch (RuntimeException ignored) {}
                }
            });
        }
        /** The home screen's own menu: the wallpaper, 화면 꾸미기, or back to the phone's own home screen. */
        public void settings(String what) {
            main.post(() -> {
                try {
                    if ("wallpaper".equals(what)) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "배경화면"));
                    else if ("decor".equals(what)) startActivity(new Intent(HomeActivity.this, DecorActivity.class));
                    else if ("memory".equals(what)) startActivity(new Intent(HomeActivity.this, DecorActivity.class).putExtra(DecorActivity.MEMORY, true));
                    else if ("home".equals(what)) startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
                } catch (RuntimeException none) { Toast.makeText(HomeActivity.this, "이 휴대폰에서는 열 수 없어요.", Toast.LENGTH_SHORT).show(); }
            });
        }
    }
}
