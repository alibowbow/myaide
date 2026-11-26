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
    static final String PREFS = "launcher", DOCK = "dock", RECENT = "recent", MOVED = "moved";
    private static final String BASE = "https://gyeot.local/";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-home"); t.setDaemon(true); return t; });
    private final Map<String, LauncherActivityInfo> known = new ConcurrentHashMap<>();
    private static final LruCache<String, byte[]> ICONS = new LruCache<>(6 * 1024 * 1024) { @Override protected int sizeOf(String k, byte[] v) { return v.length; } };
    private WebView web; private LauncherApps launcher; private SharedPreferences prefs;
    private boolean ready, shown; private int top, bottom;
    private final Runnable refresh = new Runnable() { @Override public void run() { pushBoard(); main.postDelayed(this, 60_000); } };
    private final Runnable pushBadges = this::pushBadges;
    private final Runnable badgesChanged = () -> { main.removeCallbacks(pushBadges); main.postDelayed(pushBadges, 500); };
    private final LauncherApps.Callback changed = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { pushApps(); }
        @Override public void onPackageAdded(String p, UserHandle u) { pushApps(); }
        @Override public void onPackageChanged(String p, UserHandle u) { ICONS.evictAll(); pushApps(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean replacing) { pushApps(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean replacing) { pushApps(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
            if (ready && web != null) web.evaluateJavascript("launcher.insets(" + top + "," + bottom + ")", null);
            return insets;
        });
        root.requestApplyInsets();
        try { launcher.registerCallback(changed, main); } catch (RuntimeException ignored) {}
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
        web.addJavascriptInterface(new Bridge(), "Gyeot");
        web.setWebViewClient(new WebViewClient() {
            // The home screen is this one page: nothing navigates it.
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!"gyeot.local".equalsIgnoreCase(u.getHost())) return null;
                return own(u.getPath() == null ? "/" : u.getPath());
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

    @Override protected void onResume() {
        super.onResume(); shown = true;
        NoticeListener.changed = badgesChanged;
        main.removeCallbacks(refresh); if (ready) { refresh.run(); pushBadges(); if (web != null) web.evaluateJavascript("launcher.shown&&launcher.shown()", null); }
    }
    @Override protected void onPause() {
        shown = false; NoticeListener.changed = null; main.removeCallbacks(refresh);
        if (ready && web != null) web.evaluateJavascript("launcher.hidden&&launcher.hidden()", null);
        super.onPause();
    }
    /** Home pressed while the home screen is in front: back to its top with everything closed. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (ready && web != null && shown) web.evaluateJavascript("launcher.home()", null);
    }
    @Override public void onBackPressed() {
        // A home screen does not close: back only closes what is open on it.
        if (ready && web != null) web.evaluateJavascript("launcher.back()", null);
    }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c); systemBars();
        if (ready && web != null) web.evaluateJavascript("launcher.resized()", null);
    }
    @Override protected void onDestroy() {
        try { launcher.unregisterCallback(changed); } catch (RuntimeException ignored) {}
        if (NoticeListener.changed == badgesChanged) NoticeListener.changed = null;
        main.removeCallbacksAndMessages(null); io.shutdownNow();
        if (web != null) { try { web.destroy(); } catch (RuntimeException ignored) {} web = null; }
        super.onDestroy();
    }

    // ── what the page shows ─────────────────────────────────────
    private void js(String call) { main.post(() -> { if (web != null && ready) web.evaluateJavascript(call, null); }); }
    private void pushApps() { try { io.execute(() -> js("launcher.apps(" + apps() + ")")); } catch (RuntimeException stopped) {} }
    private void pushBoard() {
        try {
            io.execute(() -> {
                KeyguardManager k = getSystemService(KeyguardManager.class);
                js("launcher.board(" + HtmlWallpaper.known(this, k != null && k.isKeyguardLocked()) + ")");
            });
        } catch (RuntimeException stopped) {}
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
        JSONArray stacks = new JSONArray();
        for (AppStacks.Stack s : AppStacks.STACKS) stacks.put(Jsons.obj("id", s.id(), "name", s.name(), "color", s.color()));
        return Jsons.obj("apps", list, "dock", dock, "recent", read(RECENT, new JSONArray()), "stacks", stacks, "badges", new JSONObject(badges));
    }
    /** An app's id here and in the side dock: its activity and its profile. */
    static String id(LauncherActivityInfo info, UserManager users) {
        long serial = users == null ? 0 : users.getSerialNumberForUser(info.getUser());
        return info.getComponentName().flattenToShortString() + "@" + serial;
    }
    private JSONArray read(String key, JSONArray none) { try { return new JSONArray(prefs.getString(key, "")); } catch (Exception e) { return none; } }
    private JSONObject read(String key, JSONObject none) { try { return new JSONObject(prefs.getString(key, "")); } catch (Exception e) { return none; } }

    // ── the page's own files ────────────────────────────────────
    private WebResourceResponse own(String path) {
        try {
            if (path.equals("/launcher.html") || path.equals("/")) return HtmlWallpaper.html(page());
            if (path.equals("/board.css") || path.equals("/board.js")) return HtmlWallpaper.asset(this, path);
            if (path.startsWith("/appicon/")) return HtmlWallpaper.appIcon(this, path);
            if (path.equals("/mascot.png")) { File f = HtmlWallpaper.mascot(this); return f.isFile() ? new WebResourceResponse("image/png", null, new java.io.FileInputStream(f)) : HtmlWallpaper.missing(); }
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
        JSONObject config = Jsons.obj("launcher", true, "dark", darkWallpaper(), "accent", accent(), "bg", prefs.getString("bg", "aurora"), "mascot", !kind.isEmpty(), "mascotKind", kind, "mascotAt", HtmlWallpaper.mascot(this).lastModified());
        return html.replace("/*GYEOT*/", "window.GYEOT=" + config.toString().replace("</", "<\\/") + ";");
    }
    /** An app's icon as the phone draws it (its shape, a work badge), once: kept in memory and in the cache folder. */
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
    private final class Bridge {
        @JavascriptInterface public void ready() {
            main.post(() -> {
                ready = true;
                if (web != null) web.evaluateJavascript("launcher.insets(" + top + "," + bottom + ")", null);
                pushApps(); main.removeCallbacks(refresh); if (shown) refresh.run(); else pushBoard();
            });
        }
        /** Opens the app, growing from its icon (x, y, w, h in the page's pixels). */
        @JavascriptInterface public void launch(String id, float x, float y, float w, float h) {
            main.post(() -> {
                LauncherActivityInfo info = known.get(id); if (info == null) return;
                float d = getResources().getDisplayMetrics().density;
                Rect from = new Rect(Math.round(x * d), Math.round(y * d), Math.round((x + w) * d), Math.round((y + h) * d));
                Bundle opts = web == null ? null : ActivityOptions.makeScaleUpAnimation(web, from.left, from.top, from.width(), from.height()).toBundle();
                try { launcher.startMainActivity(info.getComponentName(), info.getUser(), from, opts); }
                catch (RuntimeException gone) { Toast.makeText(HomeActivity.this, "이 앱을 열지 못했어요.", Toast.LENGTH_SHORT).show(); pushApps(); return; }
                JSONArray recent = new JSONArray(); recent.put(id);
                JSONArray old = read(RECENT, new JSONArray());
                for (int i = 0; i < old.length() && recent.length() < 6; i++) if (!id.equals(old.optString(i))) recent.put(old.optString(i));
                prefs.edit().putString(RECENT, recent.toString()).apply();
            });
        }
        /** The background: "aurora" (the home screen's own night sky) or "photo" (the user's wallpaper under a dark veil). */
        @JavascriptInterface public void look(String bg) { prefs.edit().putString("bg", "photo".equals(bg) ? "photo" : "aurora").apply(); }
        @JavascriptInterface public void dock(String ids) {
            try { JSONArray a = new JSONArray(ids); if (a.length() <= 24) prefs.edit().putString(DOCK, a.toString()).apply(); } catch (Exception ignored) {}
        }
        /** The app goes to another stack ("" to sort it by itself again). */
        @JavascriptInterface public void move(String id, String stack) {
            JSONObject moved = read(MOVED, new JSONObject());
            if (stack == null || stack.isEmpty()) moved.remove(id); else Jsons.put(moved, id, AppStacks.stack(stack).id());
            prefs.edit().putString(MOVED, moved.toString()).apply();
            pushApps();
        }
        @JavascriptInterface public void info(String id) {
            main.post(() -> { LauncherActivityInfo i = known.get(id); if (i != null) try { launcher.startAppDetailsActivity(i.getComponentName(), i.getUser(), null, null); } catch (RuntimeException ignored) {} });
        }
        @JavascriptInterface public void uninstall(String id) {
            main.post(() -> {
                LauncherActivityInfo i = known.get(id); if (i == null) return;
                try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + i.getApplicationInfo().packageName)).putExtra(Intent.EXTRA_USER, i.getUser())); }
                catch (RuntimeException refused) { info(id); }
            });
        }
        /** 퍼레 홈's cards: the app's own screens. */
        @JavascriptInterface public void open(String what) {
            Intent t = HtmlWallpaper.target(HomeActivity.this, what == null ? "" : what);
            if (t != null) main.post(() -> { try { startActivity(t); } catch (RuntimeException none) { Toast.makeText(HomeActivity.this, "열 수 있는 앱이 없어요.", Toast.LENGTH_SHORT).show(); } });
        }
        /** The search's words, into 퍼레's message box (the user reads and sends them). */
        @JavascriptInterface public void ask(String text) {
            if (text == null || text.isBlank()) return;
            main.post(() -> startActivity(new Intent(HomeActivity.this, MainActivity.class).putExtra(MainNavigation.TARGET, "chat").putExtra(MainActivity.ASK, text.strip())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        }
        @JavascriptInterface public void web(String text) {
            if (text == null || text.isBlank()) return;
            main.post(() -> {
                try { startActivity(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text.strip())); }
                catch (RuntimeException none) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(text.strip())))); } catch (RuntimeException ignored) {}
                }
            });
        }
        /** The home screen's own menu: the wallpaper, 화면 꾸미기, or back to the phone's own home screen. */
        @JavascriptInterface public void settings(String what) {
            main.post(() -> {
                try {
                    if ("wallpaper".equals(what)) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "배경화면"));
                    else if ("decor".equals(what)) startActivity(new Intent(HomeActivity.this, DecorActivity.class));
                    else if ("home".equals(what)) startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
                } catch (RuntimeException none) { Toast.makeText(HomeActivity.this, "이 휴대폰에서는 열 수 없어요.", Toast.LENGTH_SHORT).show(); }
            });
        }
    }
}
