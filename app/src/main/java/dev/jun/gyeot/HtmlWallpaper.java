package dev.jun.gyeot;

import android.app.KeyguardManager;
import android.app.Presentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import dev.jun.gyeot.core.AvatarSpec;
import dev.jun.gyeot.core.CalendarQuery;
import dev.jun.gyeot.core.HomeBoard;
import dev.jun.gyeot.core.HtmlShowcase;
import dev.jun.gyeot.core.Jsons;
import dev.jun.gyeot.core.Notices;
import dev.jun.gyeot.core.PieceTune;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 화면 꾸미기's 움직이는 배경화면 (준: "jev에 … 인터랙티브한 화면을 넣는다던가", "1페이지는 기존 배경화면 2페이지 3페이지...에만
 * 앱에서 넣은화면", "내 휴대폰 자체를 거대한 인터랙티브 ai ui/ux로"): web pages as the live wallpaper, drawn by a WebView in a
 * Presentation on a private virtual display whose surface is the wallpaper's own; the touches on the home screen go to it.
 *
 * Each launcher page has its slot (HomeBoard): the user's own photo, 퍼레 홈 (assets/decor/home.html, the day at a glance in
 * a soft, rounded look, kept to the top half for the home screen's icons), or a web piece in a sandboxed frame. They slide with the launcher's offsets. The board's page is
 * served from https://gyeot.local/ (never the network), its data is pushed in while it is seen, and a tap on it is a
 * navigation to /do/… that only the page itself can make (the pieces' frames cannot navigate it), which opens the app's
 * screen through the same route voice uses. One web page alone is loaded straight, as before pages. Hidden (an app in
 * front, the screen off) it stops drawing; locked, it shows the first page and nothing private.
 */
public final class HtmlWallpaper extends WallpaperService {
    static final String PREFS = "decor", URL = "wallUrl", TITLE = "wallTitle", PAGE = "page:", LAYOUT = "wallPages", SEEN = "pagesSeen",
        PAGES = "pageCount", PHOTO_AT = "photoAt";
    static final String HOST = "gyeot.local", PAGES_HOST = "pages.gyeot.local", HOME = "https://gyeot.local/";
    private static final long REFRESH_MS = 60_000;

    static File photo(Context c) { return new File(new File(c.getFilesDir(), "decor"), "photo.jpg"); }
    static File mascot(Context c) { return new File(new File(c.getFilesDir(), "decor"), "mascot.png"); }
    static List<HomeBoard.Slot> slots(SharedPreferences p) { return HomeBoard.slots(p.getString(LAYOUT, ""), p.getString(URL, ""), p.getString(TITLE, "")); }

    @Override public Engine onCreateEngine() { return new Web(); }

    private final class Web extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-wallpaper"); t.setDaemon(true); return t; });
        private VirtualDisplay display; private Presentation stage; private WebView web; private SharedPreferences prefs;
        private SurfaceHolder holder; private int width, height;
        private String loaded = ""; private boolean failed, board, ready, locked, seen; private long mascotAt = -1;
        private double page = 0; private float firstOffset = Float.NaN; private int start;
        private final Runnable refresh = () -> { push(); schedule(); };
        private final BroadcastReceiver screen = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { lockChanged(); }
        };

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true); setOffsetNotificationsEnabled(true);
            prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE); prefs.registerOnSharedPreferenceChangeListener(this);
            seen = prefs.getBoolean(SEEN, false);
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF); f.addAction(Intent.ACTION_SCREEN_ON); f.addAction(Intent.ACTION_USER_PRESENT);
            try { registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED); } catch (RuntimeException ignored) {}
        }
        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            this.holder = holder; this.width = width; this.height = height;
            build();
        }
        private void build() {
            release();
            try {
                DisplayManager displays = getSystemService(DisplayManager.class);
                // The density of the screen the wallpaper is on now: a foldable's cover and inner screens differ (and folding
                // gives a new surface, so the page is laid out again for that screen's width).
                Context on = null; try { on = getDisplayContext(); } catch (RuntimeException ignored) {}
                int dpi = (on != null ? on : HtmlWallpaper.this).getResources().getDisplayMetrics().densityDpi;
                display = displays.createVirtualDisplay("gyeot-wallpaper", width, height, dpi, holder.getSurface(), 0);
                stage = new Presentation(HtmlWallpaper.this, display.getDisplay());
                web = new WebView(stage.getContext());
                WebSettings s = web.getSettings();
                s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false);
                s.setMediaPlaybackRequiresUserGesture(true); s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
                s.setGeolocationEnabled(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                web.setVerticalScrollBarEnabled(false); web.setHorizontalScrollBarEnabled(false); web.setBackgroundColor(0xff101010);
                web.setWebViewClient(new Client());
                stage.setContentView(web);
                stage.show();
                loaded = ""; load();
                if (!isVisible()) sleep(true);
            } catch (RuntimeException unavailable) { release(); say(holder, "이 휴대폰에서는 움직이는 화면을 그리지 못했어요"); }
        }
        private final class Client extends WebViewClient {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri to = request.getUrl(); String host = to.getHost() == null ? "" : to.getHost().toLowerCase(java.util.Locale.ROOT);
                if (!board) {
                    // One page alone: it stays on its own site.
                    Uri now = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
                    return now.getHost() == null || !now.getHost().equalsIgnoreCase(host);
                }
                if (request.isForMainFrame()) {
                    String path = to.getPath() == null ? "" : to.getPath();
                    if (HOST.equals(host) && path.startsWith("/do/")) { String what = to.getLastPathSegment(); main.post(() -> act(what)); }
                    return true; // the board never leaves itself
                }
                // A piece's frame moves only within its own site (or the user's pages).
                return !(host.equals(HtmlShowcase.HOST) || host.equals(PAGES_HOST) || framed(host));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl(); String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
                if (HOST.equals(host)) return own(u.getPath() == null ? "/" : u.getPath());
                if (PAGES_HOST.equals(host)) {
                    try { return html(PageStore.book(HtmlWallpaper.this).body(Uri.decode(u.getLastPathSegment() == null ? "" : u.getLastPathSegment()))); }
                    catch (Exception gone) { return html(waiting("이 페이지를 찾지 못했어요")); }
                }
                return tuned(request);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!board || url == null || !url.startsWith(HOME)) return;
                ready = true; place(); push();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame() && !board) { failed = true; view.loadDataWithBaseURL(null, waiting("인터넷이 연결되면 배경화면을 다시 불러와요"), "text/html", "utf-8", null); }
            }
            // The page's drawing process ended (memory, an update): drawn again rather than left black.
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == web) { web = null; try { view.destroy(); } catch (RuntimeException ignored) {} main.post(() -> { if (holder != null) build(); }); }
                return true;
            }
        }
        /** Hosts the user put on a page themselves (an address). */
        private boolean framed(String host) {
            for (HomeBoard.Slot s : slots(prefs)) if (s.src().startsWith("https://") && host.equalsIgnoreCase(Uri.parse(s.src()).getHost())) return true;
            return false;
        }
        /** Words on the wallpaper itself, when no page can be drawn there. */
        private void say(SurfaceHolder holder, String words) {
            Canvas c = null;
            try {
                c = holder.lockCanvas(); if (c == null) return;
                c.drawColor(0xff101010);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0xffbbbbbb); p.setTextSize(getResources().getDisplayMetrics().density * 15); p.setTextAlign(Paint.Align.CENTER);
                c.drawText(words, c.getWidth() / 2f, c.getHeight() / 2f, p);
            } catch (RuntimeException ignored) {}
            finally { if (c != null) try { holder.unlockCanvasAndPost(c); } catch (RuntimeException ignored) {} }
        }
        @Override public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            sleep(!visible);
            main.removeCallbacks(refresh);
            if (board && ready && web != null) web.evaluateJavascript("window.boardSeen&&boardSeen(" + visible + ")", null);
            if (!visible) return;
            // Shown again after a failed load (offline before), or with 퍼레's look changed since: loaded once more.
            if (failed || (board && mascotAt >= 0 && mascotSource(HtmlWallpaper.this) > mascotAt)) { loaded = ""; load(); return; }
            lockChanged(); schedule();
        }
        @Override public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep, int xPixels, int yPixels) {
            double at = HomeBoard.page(xOffset, xStep);
            if (at < 0) return;
            // Trusted once the launcher has moved it: one that reports a fixed offset never says which page is shown.
            if (!seen) {
                if (Float.isNaN(firstOffset)) firstOffset = xOffset;
                else if (Math.abs(xOffset - firstOffset) > 0.001f) { seen = true; prefs.edit().putBoolean(SEEN, true).apply(); }
            }
            int count = HomeBoard.pages(xStep);
            if (count != prefs.getInt(PAGES, 0)) prefs.edit().putInt(PAGES, count).apply();
            page = at; place();
        }
        @Override public void onTouchEvent(MotionEvent event) { if (web != null) web.dispatchTouchEvent(event); }
        @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
            if (URL.equals(key) || LAYOUT.equals(key) || PHOTO_AT.equals(key)) load();
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder) { release(); this.holder = null; super.onSurfaceDestroyed(holder); }
        @Override public void onDestroy() {
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
            try { unregisterReceiver(screen); } catch (RuntimeException ignored) {}
            main.removeCallbacks(refresh); release(); io.shutdownNow(); super.onDestroy();
        }

        /** Not drawing while nobody sees it (only this WebView: the app's own pages keep their timers). */
        private void sleep(boolean hidden) {
            if (web == null) return;
            if (hidden) { web.onPause(); web.setVisibility(View.INVISIBLE); } else { web.setVisibility(View.VISIBLE); web.onResume(); }
        }
        private void release() {
            ready = false;
            if (web != null) { try { web.destroy(); } catch (RuntimeException ignored) {} web = null; }
            if (stage != null) { try { stage.dismiss(); } catch (RuntimeException ignored) {} stage = null; }
            if (display != null) { display.release(); display = null; }
        }
        private void load() {
            if (web == null) return;
            List<HomeBoard.Slot> slots = slots(prefs);
            String key = prefs.getString(LAYOUT, "") + "|" + prefs.getString(URL, "") + "|" + prefs.getLong(PHOTO_AT, 0);
            if (key.equals(loaded) && !failed) return;
            loaded = key; failed = false; ready = false; board = false;
            if (slots.isEmpty()) { web.loadDataWithBaseURL(null, idle(), "text/html", "utf-8", null); return; }
            if (HomeBoard.direct(slots)) {
                String chosen = slots.get(0).src();
                if (chosen.startsWith(PAGE)) {
                    try { web.loadDataWithBaseURL(null, PageStore.book(HtmlWallpaper.this).body(chosen.substring(PAGE.length())), "text/html", "utf-8", null); }
                    catch (Exception gone) { web.loadDataWithBaseURL(null, waiting("이 페이지를 찾지 못했어요"), "text/html", "utf-8", null); }
                } else web.loadUrl(chosen);
                return;
            }
            board = true; start = HomeBoard.start(slots, seen);
            web.loadUrl(HOME);
        }
        /** The page under the launcher's (the first while locked; before the launcher has said, the first that is not the photo). */
        private void place() {
            if (!board || !ready || web == null) return;
            double at = locked ? 0 : seen ? page : start;
            web.evaluateJavascript("window.board&&board.at(" + at + ")", null);
        }
        private void lockChanged() {
            KeyguardManager k = getSystemService(KeyguardManager.class);
            locked = k != null && k.isKeyguardLocked();
            place(); push();
        }
        private void schedule() { main.removeCallbacks(refresh); if (isVisible() && board) main.postDelayed(refresh, REFRESH_MS); }
        /** The board's data, gathered off the main thread and handed to the page. */
        private void push() {
            if (!board || !ready) return;
            boolean lockedNow = locked;
            try {
                io.execute(() -> {
                    JSONObject data = known(HtmlWallpaper.this, lockedNow);
                    main.post(() -> { if (web != null && board && ready) web.evaluateJavascript("window.board&&board.data(" + data + ")", null); });
                });
            } catch (RuntimeException stopped) { /* the engine is going */ }
        }
        /** A tap on the board: the app's screen, through the route voice uses (the edge handle's accessibility service, or the overlay). */
        private void act(String what) {
            if (locked || what == null) return;
            try {
                io.execute(() -> {
                    Intent open = target(HtmlWallpaper.this, what);
                    if (open == null) return;
                    main.post(() -> {
                        String route;
                        try { route = DeviceActionRunner.start(HtmlWallpaper.this, open, false); } catch (RuntimeException none) { route = "notification"; }
                        if (!DeviceActionRunner.started(route)) DeviceActionRunner.post(HtmlWallpaper.this, open, "눌러서 열어요 · 홈 화면에서 바로 열리려면 오른쪽 바(접근성)를 켜 주세요");
                    });
                });
            } catch (RuntimeException stopped) { /* the engine is going */ }
        }

        // ── the board's own files (https://gyeot.local/…) ─────────────
        private WebResourceResponse own(String path) {
            try {
                switch (path) {
                    case "/", "/index.html": return html(index());
                    case "/board.css", "/board.js": return asset(HtmlWallpaper.this, path);
                    case "/photo.jpg": { File f = photo(HtmlWallpaper.this); return f.isFile() ? new WebResourceResponse("image/jpeg", null, new FileInputStream(f)) : missing(); }
                    case "/mascot.png": { File f = mascot(HtmlWallpaper.this); return f.isFile() ? new WebResourceResponse("image/png", null, new FileInputStream(f)) : missing(); }
                    default: return path.startsWith("/appicon/") ? appIcon(HtmlWallpaper.this, path) : missing();
                }
            } catch (Exception unreadable) { return missing(); }
        }
        private String index() throws Exception {
            String page;
            try (InputStream in = getAssets().open("decor/home.html")) { page = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            List<HomeBoard.Slot> slots = slots(prefs);
            JSONArray list = new JSONArray(); for (HomeBoard.Slot s : slots) list.put(s.json());
            File photo = photo(HtmlWallpaper.this);
            String kind = mascotReady(HtmlWallpaper.this); mascotAt = mascotSource(HtmlWallpaper.this);
            File m = mascot(HtmlWallpaper.this);
            JSONObject config = Jsons.obj("slots", list, "photo", photo.isFile(), "photoAt", photo.lastModified(), "mascot", !kind.isEmpty(), "mascotKind", kind,
                "mascotAt", m.lastModified(), "start", HomeBoard.start(slots, seen));
            return page.replace("/*GYEOT*/", "window.GYEOT=" + config.toString().replace("</", "<\\/") + ";");
        }
    }

    private static final android.util.LruCache<String, byte[]> APP_ICONS = new android.util.LruCache<>(1024 * 1024) { @Override protected int sizeOf(String k, byte[] v) { return v.length; } };
    /** An app's own icon by its package (/appicon/<package>.png), for a notice on the board; kept once drawn. */
    static WebResourceResponse appIcon(Context c, String path) {
        String pkg = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\.png$", "");
        if (!pkg.matches("[A-Za-z0-9_.]{2,200}")) return missing();
        byte[] png = APP_ICONS.get(pkg);
        if (png == null) {
            try {
                Drawable d = c.getPackageManager().getApplicationIcon(pkg);
                int size = 96; Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); d.setBounds(0, 0, size, size); d.draw(new Canvas(b));
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, out); png = out.toByteArray(); APP_ICONS.put(pkg, png);
            } catch (Exception | OutOfMemoryError gone) { return missing(); }
        }
        return new WebResourceResponse("image/png", null, new ByteArrayInputStream(png));
    }
    /** One of the board's shared files (assets/decor/board.css · board.js), for this wallpaper and the home screen app. */
    static WebResourceResponse asset(Context c, String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (!name.matches("board\\.(css|js)|launcher\\.(css|js)")) return missing();
        try { return new WebResourceResponse(name.endsWith(".css") ? "text/css" : "text/javascript", "utf-8", c.getAssets().open("decor/" + name)); }
        catch (Exception gone) { return missing(); }
    }
    static WebResourceResponse html(String body) {
        return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
    /** Showcase pieces fixed (PieceTune: the turntables' record noise), by path; "" when a piece's fixes no longer match. */
    private static final Map<String, String> TUNED = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * A piece with fixes, fetched once and kept while the app runs; null to let the web view load the piece as it is (not one
     * with fixes, offline, or changed so a fix no longer matches). Called on the web view's own request thread.
     */
    static WebResourceResponse tuned(WebResourceRequest request) {
        Uri u = request.getUrl(); String path = u.getPath();
        if (!"GET".equalsIgnoreCase(request.getMethod()) || !PieceTune.knows(u.getHost(), path)) return null;
        String page = TUNED.get(path);
        if (page == null) {
            String body = get("https://" + HtmlShowcase.HOST + path, 2_000_000);
            if (body == null) return null;
            String fixed = PieceTune.tune(path, body);
            page = fixed == null ? "" : fixed; TUNED.put(path, page);
        }
        return page.isEmpty() ? null : html(page);
    }
    static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Map.of(), new ByteArrayInputStream(new byte[0]));
    }

    /** What the board shows now (off the main thread). Locked: nothing of the user's day is read at all. */
    static JSONObject known(Context c, boolean locked) {
        long now = System.currentTimeMillis();
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        String name = "들퍼레"; try { name = AssistantProfile.readName(c); } catch (RuntimeException ignored) {}
        boolean calendarOn = false; HomeBoard.Event next = null; int left = 0;
        List<HomeBoard.Notice> notices = null; int noticeCount = 0;
        List<HomeBoard.Suggestion> ideas = new ArrayList<>(); int working = 0; boolean lab = false;
        if (!locked) {
            calendarOn = PhoneCalendar.on(store) && PhoneCalendar.readable(c);
            if (calendarOn) {
                try {
                    List<CalendarQuery.Event> events = PhoneCalendar.events(c, CalendarQuery.upcoming(ZonedDateTime.now(), 2), false);
                    next = HomeBoard.next(events, now);
                    long midnight = ZonedDateTime.now().toLocalDate().plusDays(1).atStartOfDay(ZonedDateTime.now().getZone()).toInstant().toEpochMilli();
                    for (CalendarQuery.Event e : events) if (e.end() > now && e.begin() < midnight) left++;
                } catch (RuntimeException unreadable) { /* the card says the day is clear */ }
            }
            if (NoticeListener.on(c) && NoticeListener.allowed(c)) {
                try {
                    List<Notices.Notice> worth = Notices.worth(NoticeListener.list(c)), recent = Notices.recent(worth, now, 6, 50);
                    notices = new ArrayList<>(); noticeCount = recent.size();
                    for (Notices.Notice n : recent) if (Notices.keep(n) && notices.size() < 3) notices.add(new HomeBoard.Notice(n.label().isBlank() ? n.app() : n.label(), n.app(), n.title(), n.text(), n.at()));
                } catch (RuntimeException unreadable) { notices = null; }
            }
            try { for (dev.jun.gyeot.core.Proactive.Card card : Briefing.cards(store)) ideas.add(new HomeBoard.Suggestion(card.title(), card.body(), card.ask())); } catch (RuntimeException ignored) {}
            try { working = store.tasks.pendingCount(); } catch (RuntimeException ignored) {}
            lab = LabRunner.runningNow();
        }
        JSONObject out = HomeBoard.json(new HomeBoard.Known(now, locked, calendarOn, next, left, notices, noticeCount, ideas, working, lab, name, quotes()));
        try { JSONObject w = Weather.now(c); if (w != null) Jsons.put(out, "weather", w); } catch (RuntimeException ignored) {}
        // The apps just opened (퍼레 dances after music, and so on); never on the lock screen.
        if (!locked) try { Jsons.put(out, "recent", new JSONArray(c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).getString("recentLabels", "[]"))); } catch (Exception ignored) {}
        return out;
    }

    private static volatile List<HomeBoard.Quote> quotes = List.of();
    private static volatile long quotesAt;
    /**
     * BTC, SOXL and KORU from Binance's USDⓈ-M 24-hour tickers (SOXL and KORU as the perpetuals that follow the ETFs, as the
     * trading page reads them), asked at most once a minute; what was got before stays when the network fails.
     */
    static List<HomeBoard.Quote> quotes() {
        long now = System.currentTimeMillis();
        if (now - quotesAt < 55_000) return quotes;
        quotesAt = now;
        JSONArray got = new JSONArray();
        for (String symbol : HomeBoard.WATCH) {
            String body = get("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=" + symbol);
            if (body == null && symbol.equals("BTCUSDT")) body = get("https://api.binance.com/api/v3/ticker/24hr?symbol=BTCUSDT");
            if (body == null) continue;
            try { got.put(new JSONObject(body)); } catch (Exception unreadable) { /* left out */ }
        }
        List<HomeBoard.Quote> read = HomeBoard.quotes(got.toString(), HomeBoard.WATCH);
        if (!read.isEmpty()) quotes = read; else quotesAt = now - 30_000; // asked again in half a minute
        return quotes;
    }
    private static String get(String url) { return get(url, 100_000); }
    private static String get(String url, int most) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8_000); c.setReadTimeout(8_000); c.setRequestProperty("User-Agent", "Gyeot/1.0");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return new String(in.readNBytes(most), StandardCharsets.UTF_8); }
        } catch (Exception offline) { return null; }
        finally { if (c != null) c.disconnect(); }
    }

    /** The screen a tap on the board opens (off the main thread: the trading page is looked up among the projects). */
    static Intent target(Context c, String what) {
        Intent open;
        switch (what) {
            case "chat": open = new Intent(c, MainActivity.class).putExtra(MainNavigation.TARGET, "chat"); break;
            case "home", "ideas": open = new Intent(c, MainActivity.class); break;
            case "settings": open = new Intent(c, MainActivity.class).putExtra(MainNavigation.TARGET, "settings"); break;
            case "lab": open = new Intent(c, LabActivity.class); break;
            case "mytube": open = new Intent(c, MyTubeActivity.class); break;
            case "pages": open = new Intent(c, PagesActivity.class); break;
            case "notices": open = new Intent(c, NoticeActivity.class); break;
            case "calendarSettings": open = new Intent(c, MainActivity.class).putExtra(PhoneCalendar.PAGE_EXTRA, PhoneCalendar.PAGE); break;
            case "calendar":
                return new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(String.valueOf(System.currentTimeMillis())).build())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            case "trading": {
                String id = null;
                try { for (dev.jun.gyeot.core.ProjectBook.Project p : ProjectMaker.get(c).book().projects()) if ("trading".equals(p.kind())) { id = p.id(); break; } }
                catch (RuntimeException unreadable) { /* 페이지 instead */ }
                open = id == null ? new Intent(c, PagesActivity.class) : new Intent(c, ProjectActivity.class).putExtra(ProjectActivity.ID, id);
                break;
            }
            default: return null;
        }
        return open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    // ── 퍼레's look on the board ─────────────────────────────────
    /** When 퍼레's look last changed (its avatar or its profile picture); 0 when it has none. */
    static long mascotSource(Context c) { return Math.max(AssistantAvatar.file(c).lastModified(), AssistantProfile.file(c).lastModified()); }
    /**
     * 퍼레 as the board shows it, made again when its look changed: "figure" (the living avatar's still pose, see-through), "round"
     * (the profile picture in a circle) or "" (none: the board draws its own round friend).
     */
    static synchronized String mascotReady(Context c) {
        File out = mascot(c), kind = new File(out.getPath() + ".kind");
        long source = mascotSource(c);
        try {
            if (source == 0) { out.delete(); kind.delete(); return ""; }
            if (out.isFile() && kind.isFile() && out.lastModified() >= source) return java.nio.file.Files.readString(kind.toPath()).strip();
            AssistantProfile.Saved saved = AssistantProfile.read(c);
            AssistantProfile.Decoded decoded = saved.bytes().length == 0 ? null : AssistantProfile.decode(saved.bytes());
            AvatarSpec spec = AssistantAvatar.read(c);
            Bitmap texture = null;
            if (spec != null && AvatarSpec.PICTURE.equals(spec.kind)) {
                if (decoded != null && decoded.first() instanceof android.graphics.drawable.BitmapDrawable picture && AssistantAvatar.checksum(saved.bytes()).equals(spec.picture)) texture = picture.getBitmap();
                else spec = null;
            }
            int size = 300; Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(b);
            String made;
            if (spec != null) {
                AvatarDrawable d = new AvatarDrawable(spec, texture, false, 0); d.density(3f); d.setBounds(0, 0, size, size); d.draw(canvas); made = "figure";
            } else if (decoded != null && decoded.first() != null) {
                Drawable first = decoded.first();
                Bitmap square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); first.setBounds(0, 0, size, size); first.draw(new Canvas(square));
                Paint round = new Paint(Paint.ANTI_ALIAS_FLAG); round.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                canvas.drawCircle(size / 2f, size / 2f, size / 2f, round); made = "round";
            } else { out.delete(); kind.delete(); return ""; }
            out.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
            java.nio.file.Files.writeString(kind.toPath(), made);
            return made;
        } catch (Exception | OutOfMemoryError unreadable) { return out.isFile() ? "figure" : ""; }
    }

    private static String waiting(String words) {
        return "<html><body style=\"margin:0;height:100vh;display:grid;place-items:center;background:#101010;color:#bbb;font:16px sans-serif;text-align:center\"><div>" + words + "</div></body></html>";
    }
    private static String idle() {
        return "<html><body style=\"margin:0;height:100vh;display:grid;place-items:center;background:linear-gradient(165deg,#bfe2ff,#fff3dd);color:#3a3a44;font:16px sans-serif;text-align:center\"><div>들퍼레 → 내 휴대폰 → 화면 꾸미기에서<br>배경화면을 골라 주세요</div></body></html>";
    }
}
