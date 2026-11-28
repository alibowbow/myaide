package dev.jun.gyeot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebResponse;

/**
 * 들퍼레 홈 화면's left page as Claude Code (준: "홈화면 왼쪽 페이지 자체를 내 ai브라우저로 https://claude.ai/code 이페이지를 띄울 수
 * 있어?", "왼쪽 페이지는 하단 독 없애고"). claude.ai refuses to be put in a frame, so the page is not in launcher.html: it is the AI
 * 브라우저's own engine and profile (BrowserRuntime: one Gecko, so a sign-in there is a sign-in here), a GeckoView laid over the
 * place of the launcher's first page and moved with it while the pages are swiped (the page says where its first page is:
 * launcher.html tellLeft). It takes the whole height but the status bar, the system bar and a thin strip for the page dots
 * (the dock's room is the page's: the launcher hides the dock there).
 *
 * Kept light: nothing is made before the page first comes into view. Settled on it, it takes touches and the keyboard; next
 * door (홈) it only stops drawing, so coming back is at once; two pages away, when the home screen stops, when memory runs low
 * or the page is turned off (화면 꾸미기), its page is closed and opened again where it was the next time it shows.
 *
 * Ways out of it, as its own sideways swipes are the site's: a thin strip at the right edge hands its touches to the home
 * page (the swipe turns the pages there), the dots under it turn to a page with a tap, the home button goes to 홈, and back is
 * the site's back first, then 홈. Links to other sites, popups and downloads go to the AI 브라우저; sign-in pages (Google,
 * Apple, GitHub …) stay here. Main thread.
 */
final class ClaudePage {
    static final String HOME = "https://claude.ai/code";
    /** In HomeActivity.PREFS: the left page is Claude Code (on unless turned off in 화면 꾸미기). */
    static final String ON = "claudeLeft";
    static boolean on(SharedPreferences prefs) { return prefs.getBoolean(ON, true); }
    /** HomeActivity's requests: a file for the page, the microphone for the page. */
    static final int FILES = 7541, MIC = 7542;
    /** The strip under the page where the launcher's dots are (dp), and the right edge's strip (dp). */
    static final int DOTS = 24, EDGE = 12;
    /** The screen tests draw a stand-in (Robolectric has no Gecko). */
    static boolean standIn = "robolectric".equals(Build.FINGERPRINT);

    /** The page's layer over the home screen, the width of the screen, moved sideways with the launcher's first page. */
    final FrameLayout host;
    /** The right edge's strip while the page is settled on: its touches go to the home page under it. */
    final View edge;
    private final Activity activity; private final FrameLayout root; private final Runnable home;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Where the edge's touches go: the home page (tests listen in). */
    Consumer<MotionEvent> handTo;
    private View content; private GeckoView view; private GeckoSession session, popup;
    /** at: the launcher's scroll in pages (0 the page shown, 1 a page away, 2 two); live: a page is open (drawn or not). */
    private float at = 1;
    private boolean enabled, started, covered, settled = true, live, failed, drawn, focused, touching, picking, canBack, popupBack;
    private int top, bottom, ime, revived;
    /** The last claude.ai address (opened again there), the main page's address now (a sign-in step too), the popup's. */
    private String url = HOME, page = "", popupAt = "";
    /** For the screen tests: how often the page was opened, and the site's backs taken. */
    int loads, backs;

    ClaudePage(Activity activity, FrameLayout root, Supplier<View> web, Runnable home) {
        this.activity = activity; this.root = root; this.home = home;
        host = new FrameLayout(activity); host.setVisibility(View.GONE);
        root.addView(host, new FrameLayout.LayoutParams(-1, -1));
        edge = new View(activity); edge.setVisibility(View.GONE); edge.setContentDescription("홈으로 넘기기");
        edge.setOnTouchListener(this::edgeTouch);
        root.addView(edge, new FrameLayout.LayoutParams(dp(EDGE), -1, Gravity.END));
        handTo = e -> { View w = web.get(); if (w != null) w.dispatchTouchEvent(e); };
        // Turned or unfolded: the page moves by the new width.
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol) main.post(this::place); });
    }

    // ── what the home screen says ──────────────────────────────
    /** Where the launcher's first page is (its scroll in pages), whether the swipe has ended, and whether Launchpad covers it. */
    void moved(float pages, boolean settled, boolean covered) {
        if (Float.isNaN(pages) || Float.isInfinite(pages)) return;
        at = Math.max(0f, pages); this.settled = settled; this.covered = covered; apply();
    }
    void enabled(boolean on) { enabled = on; if (!on) release(); apply(); }
    /** The home screen started or stopped. Stopped, the page closes (not while a file is being picked for it). */
    void started(boolean on) {
        started = on;
        if (on) revived = 0; else if (!picking) release();
        apply();
    }
    /** Memory runs low: the page closes unless it is the one in use. */
    void trim(int level) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !here()) { release(); apply(); } }
    /** The bars in px: the page starts under the status bar and ends over the dots and the system bar, or over the keyboard. */
    void insets(int top, int bottom, int ime) { this.top = top; this.bottom = bottom; this.ime = ime; place(); apply(); }
    /** Back: the site's own first (a sign-in popup closes), then 홈; false when the page is not the one in use. */
    boolean back() {
        if (!here()) return false;
        if (popup != null) { if (popupBack) popup.goBack(); else closePopup(); return true; }
        if (canBack && live) { backs++; if (session != null) session.goBack(); return true; }
        home.run(); return true;
    }
    void destroy() { release(); main.removeCallbacksAndMessages(null); }

    /** A page is open (drawn or kept next door). */
    boolean open() { return live; }
    /** The page draws (some of it is on screen). */
    boolean active() { return live && drawn; }
    /** The page is on screen in part or whole. */
    boolean near() { return enabled && started && !covered && at < 0.999f; }
    /** Settled on, nothing over it: it takes touches and back. */
    boolean here() { return near() && settled && at < 0.001f; }
    /** The address the page opens at next. */
    String url() { return url; }
    /** For the screen tests: the stand-in page can or cannot go back. */
    void standInHistory(boolean back) { if (standIn) canBack = back; }

    // ── placing ────────────────────────────────────────────────
    private int dp(int v) { return Math.round(v * activity.getResources().getDisplayMetrics().density); }
    private int width() { int w = root.getWidth(); return w > 0 ? w : activity.getResources().getDisplayMetrics().widthPixels; }
    private void place() {
        if (content != null) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) content.getLayoutParams();
            int below = ime > 0 ? ime : bottom + dp(DOTS);
            if (p.topMargin != top || p.bottomMargin != below) { p.topMargin = top; p.bottomMargin = below; content.setLayoutParams(p); }
        }
        // The middle of the right edge: clear of the site's top bar and of its message box and send button at the bottom.
        FrameLayout.LayoutParams e = (FrameLayout.LayoutParams) edge.getLayoutParams();
        int up = top + dp(64), down = bottom + dp(DOTS) + dp(120);
        if (e.topMargin != up || e.bottomMargin != down) { e.topMargin = up; e.bottomMargin = down; edge.setLayoutParams(e); }
        host.setTranslationX(-Math.min(1f, at) * width());
    }
    private void apply() {
        // Two pages away, or the page turned off: its memory is given back.
        if (live && (!enabled || settled && at >= 1.5f)) release();
        boolean near = near(), here = here();
        if (near && !failed) ensure();
        host.setTranslationX(-Math.min(1f, at) * width());
        // Next door it lies off the screen's left edge (untouchable there), its surface kept so a swipe back shows it at once;
        // hidden once closed, under Launchpad, and when the home screen stops.
        if (content != null) host.setVisibility(enabled && started && !covered && live ? View.VISIBLE : View.INVISIBLE);
        else if (host.getVisibility() != View.GONE) host.setVisibility(View.GONE);
        drawing(near && live);
        focus(here && live);
        // Not while the finger that started on it is still down: that touch is the home page's swipe.
        if (!touching) edge.setVisibility(here && content != null && ime == 0 ? View.VISIBLE : View.GONE);
    }
    private boolean edgeTouch(View v, MotionEvent e) {
        int action = e.getActionMasked();
        touching = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
        MotionEvent onPage = MotionEvent.obtain(e); onPage.offsetLocation(v.getLeft(), v.getTop());
        try { handTo.accept(onPage); } finally { onPage.recycle(); }
        if (!touching) apply();
        return true;
    }
    private void drawing(boolean on) {
        if (drawn == on) return;
        drawn = on;
        if (standIn) return;
        try { if (session != null) session.setActive(on && popup == null); if (popup != null) popup.setActive(on); } catch (RuntimeException ignored) {}
    }
    private void focus(boolean on) {
        if (focused == on) return;
        focused = on;
        if (standIn) return;
        try { GeckoSession s = popup != null ? popup : session; if (s != null) s.setFocused(on); } catch (RuntimeException ignored) {}
        if (!on && view != null && view.hasFocus()) {
            InputMethodManager keys = activity.getSystemService(InputMethodManager.class);
            if (keys != null) keys.hideSoftInputFromWindow(view.getWindowToken(), 0);
            view.clearFocus();
        }
    }

    // ── the page ───────────────────────────────────────────────
    private void ensure() {
        if (content == null) {
            try { content = standIn ? standInView() : gecko(); }
            catch (RuntimeException | LinkageError unavailable) { failed = true; content = null; return; }
            host.addView(content, new FrameLayout.LayoutParams(-1, -1));
            place();
        }
        if (live) return;
        live = true; loads++; drawn = true; focused = false; canBack = false; page = url;
        if (standIn) return;
        try {
            GeckoSession s = session(false);
            s.open(BrowserRuntime.get(activity).runtime);
            session = s;
            view.setSession(s);
            s.loadUri(url);
        } catch (RuntimeException | LinkageError unavailable) { failed = true; release(); }
    }
    private View gecko() {
        view = new GeckoView(activity);
        view.setActivityContextDelegate(() -> activity);
        // Until the site draws: its own ground in the phone's light or dark (claude.ai follows it).
        boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        view.coverUntilFirstPaint(night ? 0xff262624 : 0xfffaf9f5);
        view.setContentDescription("Claude Code");
        return view;
    }
    private View standInView() {
        TextView mark = new TextView(activity); mark.setText("Claude Code"); mark.setGravity(Gravity.CENTER); mark.setTextSize(15);
        mark.setTextColor(0xffd9d6cf); mark.setBackgroundColor(0xff262624); mark.setContentDescription("Claude Code");
        return mark;
    }
    /** The page closed and its memory given back; it opens again at its last claude.ai address when it next shows. */
    void release() {
        if (!live) return;
        live = false; canBack = false; drawn = false; focused = false;
        closePopup();
        GeckoSession s = session; session = null;
        if (view != null) try { view.releaseSession(); } catch (RuntimeException ignored) {}
        if (s != null) try { s.close(); } catch (RuntimeException ignored) {}
    }
    private GeckoSession session(boolean isPopup) {
        GeckoSession s = new GeckoSession(new GeckoSessionSettings.Builder().userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE).build());
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession g, LoadRequest r) {
                if (r.isDirectNavigation || stays(r.uri, isPopup ? popupAt : page, r.hasUserGesture, r.isRedirect)) return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                main.post(() -> elsewhere(r.uri));
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            @Override public void onLocationChange(GeckoSession g, String location, List<GeckoSession.PermissionDelegate.ContentPermission> perms, Boolean gesture) {
                if (location == null) return;
                if (g == session) { page = location; if (inside(location)) url = location; }
                else if (g == popup) popupAt = location;
            }
            @Override public void onCanGoBack(GeckoSession g, boolean value) { if (g == session) canBack = value; else if (g == popup) popupBack = value; }
            /** A window the page opens: a sign-in shown here over the page until it closes; anything else in the AI 브라우저. */
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession g, String uri) {
                if (isPopup || popup != null || !signIn(uri) || view == null) { main.post(() -> elsewhere(uri)); return GeckoResult.fromValue(null); }
                GeckoSession p = session(true); popup = p; popupAt = uri; popupBack = false;
                main.post(() -> showPopup(p, 0));
                return GeckoResult.fromValue(p);
            }
        });
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            /** A download: the AI 브라우저 fetches it again with the same sign-in; a file made in the page cannot leave it. */
            @Override public void onExternalResponse(GeckoSession g, WebResponse response) {
                try { if (response.body != null) response.body.close(); } catch (java.io.IOException ignored) {}
                String uri = response.uri;
                main.post(() -> {
                    if (web(uri)) { elsewhere(uri); toast("파일은 AI 브라우저에서 받아요"); }
                    else toast("이 파일은 여기서 받을 수 없어요. AI 브라우저에서 claude.ai를 열어 받아 주세요.");
                });
            }
            @Override public void onCloseRequest(GeckoSession g) { main.post(() -> { if (g == popup) closePopup(); }); }
            // The page's process killed (the phone needed memory) or crashed: opened again where it was, twice at most.
            @Override public void onKill(GeckoSession g) { main.post(() -> revive(g)); }
            @Override public void onCrash(GeckoSession g) { main.post(() -> revive(g)); }
        });
        s.setPromptDelegate(prompts);
        s.setPermissionDelegate(permissions);
        return s;
    }
    private void showPopup(GeckoSession p, int tries) {
        if (p != popup || view == null) return;
        if (!p.isOpen()) { if (tries < 10) main.postDelayed(() -> showPopup(p, tries + 1), 50); else closePopup(); return; }
        try { view.releaseSession(); view.setSession(p); p.setActive(near()); p.setFocused(here()); if (session != null) session.setActive(false); }
        catch (RuntimeException failed) { closePopup(); }
    }
    private void closePopup() {
        GeckoSession p = popup; popup = null; popupBack = false; popupAt = "";
        if (p == null) return;
        try { if (view != null && view.getSession() == p) { view.releaseSession(); if (session != null) { view.setSession(session); session.setActive(near()); session.setFocused(here()); } } }
        catch (RuntimeException ignored) {}
        try { p.close(); } catch (RuntimeException ignored) {}
    }
    private void revive(GeckoSession which) {
        if (which == popup) { closePopup(); return; }
        if (which != session) return;
        release();
        if (revived++ < 2) apply();
    }
    private void elsewhere(String uri) {
        if (activity.isDestroyed() || !web(uri)) return;
        try { BrowserActivity.open(activity, uri); } catch (RuntimeException ignored) {}
    }
    private void toast(String words) { if (!activity.isDestroyed()) Toast.makeText(activity, words, Toast.LENGTH_SHORT).show(); }

    // ── what the page asks: dialogs, files, the microphone ─────
    private final GeckoSession.PromptDelegate prompts = new GeckoSession.PromptDelegate() {
        @Override public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt p) {
            return dialog(p, (b, r) -> b.setMessage(p.message).setPositiveButton("확인", (d, w) -> r.complete(p.dismiss())));
        }
        @Override public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt p) {
            return dialog(p, (b, r) -> b.setMessage(p.message).setPositiveButton("확인", (d, w) -> r.complete(p.confirm(ButtonPrompt.Type.POSITIVE)))
                .setNegativeButton("취소", (d, w) -> r.complete(p.confirm(ButtonPrompt.Type.NEGATIVE))));
        }
        @Override public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt p) {
            List<ChoicePrompt.Choice> options = new ArrayList<>(); flatten(p.choices, options);
            String[] labels = options.stream().map(c -> c.label).toArray(String[]::new);
            return dialog(p, (b, r) -> {
                if (p.type == ChoicePrompt.Type.MULTIPLE) {
                    boolean[] checked = new boolean[options.size()]; for (int i = 0; i < checked.length; i++) checked[i] = options.get(i).selected;
                    b.setMultiChoiceItems(labels, checked, (d, n, on) -> { if (!options.get(n).disabled) checked[n] = on; });
                    b.setPositiveButton("완료", (d, w) -> { List<ChoicePrompt.Choice> chosen = new ArrayList<>(); for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(options.get(i)); r.complete(p.confirm(chosen.toArray(new ChoicePrompt.Choice[0]))); });
                } else b.setItems(labels, (d, n) -> r.complete(options.get(n).disabled ? p.dismiss() : p.confirm(options.get(n))));
                b.setNegativeButton("취소", (d, w) -> r.complete(p.dismiss()));
            });
        }
        @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt p) { return pick(p); }
    };
    private static void flatten(GeckoSession.PromptDelegate.ChoicePrompt.Choice[] input, List<GeckoSession.PromptDelegate.ChoicePrompt.Choice> output) {
        if (input == null) return;
        for (var choice : input) { if (choice.items != null) flatten(choice.items, output); else if (!choice.separator) output.add(choice); }
    }
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> dialog(GeckoSession.PromptDelegate.BasePrompt p,
            BiConsumer<AlertDialog.Builder, GeckoResult<GeckoSession.PromptDelegate.PromptResponse>> build) {
        if (!here() || activity.isFinishing()) return GeckoResult.fromValue(p.dismiss());
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> r = new GeckoResult<>();
        AlertDialog.Builder b = new AlertDialog.Builder(activity).setTitle("claude.ai" + (p.title == null || p.title.isBlank() ? "" : " · " + p.title));
        build.accept(b, r);
        AlertDialog d = b.create();
        d.setOnDismissListener(x -> { if (!p.isComplete()) r.complete(p.dismiss()); });
        try { d.show(); } catch (RuntimeException gone) { if (!p.isComplete()) r.complete(p.dismiss()); }
        return r;
    }
    private GeckoSession.PromptDelegate.FilePrompt filePrompt;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> fileResult;
    /** A file for the page (attaching to a message): the phone's picker; the page goes on while it is open. */
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> pick(GeckoSession.PromptDelegate.FilePrompt p) {
        if (!here() || fileResult != null) return GeckoResult.fromValue(p.dismiss());
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        List<String> types = new ArrayList<>();
        if (p.mimeTypes != null) for (String t : p.mimeTypes) if (t != null && t.contains("/")) types.add(t.strip().toLowerCase(Locale.ROOT));
        if (types.size() == 1) pick.setType(types.get(0)); else if (types.size() > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
        if (p.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> r = new GeckoResult<>();
        filePrompt = p; fileResult = r; picking = true;
        try { activity.startActivityForResult(pick, FILES); }
        catch (RuntimeException none) { filePrompt = null; fileResult = null; picking = false; r.complete(p.dismiss()); toast("파일을 고를 수 있는 앱이 없어요."); }
        return r;
    }
    /** HomeActivity's onActivityResult: the files picked for the page; false for another request. */
    boolean files(int code, int result, Intent data) {
        if (code != FILES) return false;
        GeckoSession.PromptDelegate.FilePrompt p = filePrompt; GeckoResult<GeckoSession.PromptDelegate.PromptResponse> r = fileResult;
        filePrompt = null; fileResult = null; picking = false;
        if (p != null && r != null) {
            List<Uri> uris = new ArrayList<>();
            if (result == Activity.RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) { Uri u = clip.getItemAt(i).getUri(); if (u != null) uris.add(u); }
                else if (data.getData() != null) uris.add(data.getData());
            }
            try {
                if (uris.isEmpty()) r.complete(p.dismiss());
                else if (p.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) r.complete(p.confirm(activity, uris.toArray(new Uri[0])));
                else r.complete(p.confirm(activity, uris.get(0)));
            } catch (RuntimeException unreadable) { if (!p.isComplete()) r.complete(p.dismiss()); toast("이 파일은 넣지 못했어요."); }
        }
        // Stopped while the picker was open, and not started again: closed now.
        if (!started) release();
        apply();
        return true;
    }
    private GeckoSession.PermissionDelegate.Callback micAsked;
    /** The microphone (claude.ai's dictation), for claude.ai alone: Android asked once; nothing else (camera, place …) is given. */
    private final GeckoSession.PermissionDelegate permissions = new GeckoSession.PermissionDelegate() {
        @Override public void onAndroidPermissionsRequest(GeckoSession s, String[] wanted, Callback callback) {
            boolean micOnly = wanted != null && wanted.length > 0;
            if (wanted != null) for (String w : wanted) micOnly &= android.Manifest.permission.RECORD_AUDIO.equals(w);
            if (!micOnly || !here() || micAsked != null) { callback.reject(); return; }
            if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { callback.grant(); return; }
            micAsked = callback;
            try { activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, MIC); } catch (RuntimeException refused) { micAsked = null; callback.reject(); }
        }
        @Override public void onMediaPermissionRequest(GeckoSession s, String uri, MediaSource[] video, MediaSource[] audio, MediaCallback callback) {
            MediaSource mic = null;
            if (audio != null) for (MediaSource m : audio) if (m.source == MediaSource.SOURCE_MICROPHONE) { mic = m; break; }
            boolean ok = mic != null && (video == null || video.length == 0) && inside(uri) && here()
                && activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (ok) callback.grant((MediaSource) null, mic); else callback.reject();
        }
    };
    /** HomeActivity's onRequestPermissionsResult: the microphone's answer for the page; false for another request. */
    boolean permissionResult(int code, int[] results) {
        if (code != MIC) return false;
        GeckoSession.PermissionDelegate.Callback c = micAsked; micAsked = null;
        if (c != null) { if (results != null && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) c.grant(); else c.reject(); }
        return true;
    }

    // ── where an address goes ──────────────────────────────────
    private static String host(String uri) {
        try { String h = java.net.URI.create(uri.strip()).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (RuntimeException bad) { return ""; }
    }
    private static String path(String uri) {
        try { String p = java.net.URI.create(uri.strip()).getRawPath(); return p == null ? "" : p; } catch (RuntimeException bad) { return ""; }
    }
    private static boolean https(String uri) { return uri != null && uri.regionMatches(true, 0, "https://", 0, 8); }
    static boolean web(String uri) { return https(uri) || uri != null && uri.regionMatches(true, 0, "http://", 0, 7); }
    private static boolean under(String host, String domain) { return host.equals(domain) || host.endsWith("." + domain); }
    /** claude.ai itself. */
    static boolean inside(String uri) { return https(uri) && under(host(uri), "claude.ai"); }
    /** A sign-in claude.ai sends to (Google, Apple, Microsoft, GitHub's sign-in and app install for Claude Code). */
    static boolean signIn(String uri) {
        if (!https(uri)) return false;
        String h = host(uri);
        if (h.equals("accounts.google.com") || h.equals("accounts.youtube.com") || h.equals("appleid.apple.com") || h.equals("login.microsoftonline.com") || h.equals("login.live.com")) return true;
        if (!h.equals("github.com")) return false;
        String p = path(uri);
        for (String step : new String[]{"/login", "/session", "/sessions", "/apps", "/installations", "/settings/installations"}) if (p.equals(step) || p.startsWith(step + "/")) return true;
        return false;
    }
    /**
     * Whether a load in the page stays in it: claude.ai, a sign-in, a blank or claude.ai's own blob; and, not tapped (a redirect or
     * the page going on by itself), a step of a sign-in under way or Anthropic's other addresses. Anything else is the AI 브라우저's.
     */
    static boolean stays(String uri, String from, boolean tapped, boolean redirect) {
        if (uri == null) return false;
        String u = uri.strip();
        if (u.regionMatches(true, 0, "about:", 0, 6)) return true;
        if (u.regionMatches(true, 0, "blob:", 0, 5)) return inside(u.substring(5));
        if (!web(u)) return false;
        if (inside(u) || signIn(u)) return true;
        if (tapped && !redirect) return false;
        String h = host(u);
        return https(u) && (signIn(from == null ? "" : from) || under(h, "claude.com") || under(h, "anthropic.com"));
    }
}
