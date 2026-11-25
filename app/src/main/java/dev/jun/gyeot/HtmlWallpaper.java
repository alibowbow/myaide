package dev.jun.gyeot;

import android.app.Presentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.net.Uri;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 화면 꾸미기's 움직이는 배경화면 (준: "jev에 … 인터랙티브한 화면을 넣는다던가", e.g. Herbarium Imaginarium): a web page as the
 * live wallpaper. A widget cannot run a page, a wallpaper can: the page is drawn by a WebView in a Presentation on a private
 * virtual display whose surface is the wallpaper's own, and the touches on the home screen go to it. Hidden (an app in front,
 * the screen off) it stops drawing; the chosen page (DecorActivity, the "decor" settings) loads again when it changes.
 */
public final class HtmlWallpaper extends WallpaperService {
    static final String PREFS = "decor", URL = "wallUrl", TITLE = "wallTitle", PAGE = "page:";

    @Override public Engine onCreateEngine() { return new Web(); }

    private final class Web extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {
        private VirtualDisplay display; private Presentation stage; private WebView web; private String loaded = ""; private boolean failed;
        private SharedPreferences prefs;

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE); prefs.registerOnSharedPreferenceChangeListener(this);
        }
        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            release();
            try {
                DisplayManager displays = getSystemService(DisplayManager.class);
                display = displays.createVirtualDisplay("gyeot-wallpaper", width, height, getResources().getDisplayMetrics().densityDpi, holder.getSurface(), 0);
                stage = new Presentation(HtmlWallpaper.this, display.getDisplay());
                web = new WebView(stage.getContext());
                WebSettings s = web.getSettings();
                s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false);
                s.setMediaPlaybackRequiresUserGesture(true); s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
                s.setGeolocationEnabled(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                web.setVerticalScrollBarEnabled(false); web.setHorizontalScrollBarEnabled(false); web.setBackgroundColor(0xff101010);
                web.setWebViewClient(new WebViewClient() {
                    // The wallpaper stays on its page: a link elsewhere is not followed.
                    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        Uri now = Uri.parse(view.getUrl() == null ? "" : view.getUrl()), to = request.getUrl();
                        return now.getHost() == null || !now.getHost().equalsIgnoreCase(to.getHost());
                    }
                    @Override public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                        if (request.isForMainFrame()) { failed = true; view.loadDataWithBaseURL(null, waiting(), "text/html", "utf-8", null); }
                    }
                });
                stage.setContentView(web);
                stage.show();
                loaded = ""; load();
                if (!isVisible()) sleep(true);
            } catch (RuntimeException unavailable) { release(); say(holder, "이 휴대폰에서는 움직이는 화면을 그리지 못했어요"); }
        }
        /** Words on the wallpaper itself, when no page can be drawn there. */
        private void say(SurfaceHolder holder, String words) {
            android.graphics.Canvas c = null;
            try {
                c = holder.lockCanvas(); if (c == null) return;
                c.drawColor(0xff101010);
                android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); p.setColor(0xffbbbbbb); p.setTextSize(getResources().getDisplayMetrics().density * 15); p.setTextAlign(android.graphics.Paint.Align.CENTER);
                c.drawText(words, c.getWidth() / 2f, c.getHeight() / 2f, p);
            } catch (RuntimeException ignored) {}
            finally { if (c != null) try { holder.unlockCanvasAndPost(c); } catch (RuntimeException ignored) {} }
        }
        @Override public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            sleep(!visible);
            // Shown again after a failed load (offline before): tried once more.
            if (visible && failed) { loaded = ""; load(); }
        }
        @Override public void onTouchEvent(MotionEvent event) { if (web != null) web.dispatchTouchEvent(event); }
        @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) { if (URL.equals(key)) load(); }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder) { release(); super.onSurfaceDestroyed(holder); }
        @Override public void onDestroy() { if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this); release(); super.onDestroy(); }

        /** Not drawing while nobody sees it (only this WebView: the app's own pages keep their timers). */
        private void sleep(boolean hidden) {
            if (web == null) return;
            if (hidden) { web.onPause(); web.setVisibility(View.INVISIBLE); } else { web.setVisibility(View.VISIBLE); web.onResume(); }
        }
        private void load() {
            if (web == null) return;
            String chosen = prefs.getString(URL, "");
            if (chosen.equals(loaded) && !failed) return;
            loaded = chosen; failed = false;
            if (chosen.startsWith(PAGE)) {
                try {
                    String html = PageStore.book(HtmlWallpaper.this).body(chosen.substring(PAGE.length()));
                    web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null); return;
                } catch (Exception gone) { web.loadDataWithBaseURL(null, waiting(), "text/html", "utf-8", null); return; }
            }
            if (chosen.startsWith("https://")) web.loadUrl(chosen);
            else web.loadDataWithBaseURL(null, idle(), "text/html", "utf-8", null);
        }
        private void release() {
            if (web != null) { try { web.destroy(); } catch (RuntimeException ignored) {} web = null; }
            if (stage != null) { try { stage.dismiss(); } catch (RuntimeException ignored) {} stage = null; }
            if (display != null) { display.release(); display = null; }
        }
    }
    private static String waiting() {
        return "<html><body style=\"margin:0;height:100vh;display:grid;place-items:center;background:#101010;color:#bbb;font:16px sans-serif\"><div>인터넷이 연결되면 배경화면을 다시 불러와요</div></body></html>";
    }
    private static String idle() {
        return "<html><body style=\"margin:0;height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 30% 20%,#3a2a24,#101010);color:#ccc;font:16px sans-serif;text-align:center\"><div>들퍼레 → 내 휴대폰 → 화면 꾸미기에서<br>움직이는 배경화면을 골라 주세요</div></body></html>";
    }
}
