package dev.jun.gyeot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.MyTube;
import dev.jun.gyeot.core.MyTubeBook;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

/**
 * 마이튜브's player: one page of the app's own browser engine (the AI 브라우저's, so a YouTube sign-in there is the
 * player's too: with Premium there are no ads) playing YouTube's phone page, one video of the queue after another. The
 * browser bridge on the page (content.js, mytube) says when the video plays, pauses and ends; at its end the next one
 * loads, and a music feed that runs out chooses more songs by itself. The page shows in 마이튜브's screen while it is open
 * (attach); with 화면 꺼져도 계속 재생 on it keeps playing without it (MyTubeService holds the app up), otherwise it pauses.
 * One for the app; main thread.
 */
final class MyTubePlayer {
    private static MyTubePlayer instance;
    static synchronized MyTubePlayer get(Context context) { if (instance == null) instance = new MyTubePlayer(context.getApplicationContext()); return instance; }
    /** The setting: go on playing with the screen off or another app in front (for YouTube Premium members). */
    static final String BACKGROUND = "mytubeBackground";
    /** The setting: the picture quality (core/MyTube's QUALITY_CODES). */
    static final String QUALITY = "mytubeQuality";

    private final Context context; private final AppStore store; private final MyTubeBook book;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private GeckoSession session; private WebExtension.Port port; private GeckoView view;
    private MyTube.Feed feed; private final List<MyTube.Video> queue = new ArrayList<>(); private int at = -1;
    private boolean playing, wantPlay, refilling, onScreen;
    /** The page bridge is bound to this page: until then a load waits (the page would load with no one to start it). */
    private boolean bound; private String pendingUri;
    private String status = "";
    /** What the page said of the picture: the quality playing, those the video has, and whether it can be changed there. */
    private String qualityNow = ""; private List<String> levels = List.of(); private boolean qualitySettable = true;
    private final AtomicReference<Runnable> cancel = new AtomicReference<>();

    private MyTubePlayer(Context context) {
        this.context = context; this.store = ((GyeotApp) context).store(); this.book = MyTubeClient.book(context);
    }
    void listen(Runnable l) { listeners.addIfAbsent(l); }
    void unlisten(Runnable l) { listeners.remove(l); }
    private void changed() { for (Runnable l : listeners) l.run(); MyTubeService.update(context); }

    MyTube.Feed feed() { return feed; }
    MyTube.Video current() { return at >= 0 && at < queue.size() ? queue.get(at) : null; }
    List<MyTube.Video> queue() { return new ArrayList<>(queue); }
    int index() { return at; }
    boolean playing() { return playing; }
    boolean active() { return current() != null; }
    String status() { return status; }
    boolean background() { return store.settings.getBoolean(BACKGROUND, false); }
    boolean liked() { MyTube.Video v = current(); return v != null && feed != null && book.liked(feed.id(), v.id()); }
    /** The chosen quality (a QUALITY_CODES code). */
    String quality() { String q = store.settings.getString(QUALITY, "auto"); return q == null ? "auto" : q; }
    /** What plays now, for the screen: "720p", or "" before the page said. */
    String qualityNow() { return MyTube.qualityName(qualityNow); }
    boolean qualitySettable() { return qualitySettable; }
    /** The qualities this video has, as they read (1080p, 720p, …). */
    List<String> levels() { List<String> out = new ArrayList<>(); for (String l : levels) { String n = MyTube.qualityName(l); if (!n.isEmpty() && !"자동".equals(n)) out.add(n); } return out; }
    /** A new quality: kept, and applied to what plays. */
    void quality(String code) {
        store.settings.edit().putString(QUALITY, code).apply();
        sendQuality("quality"); changed();
    }

    // ── the page ─────────────────────────────────────────────────────────────
    private GeckoSession session() {
        if (session != null) return session;
        GeckoSession s = new GeckoSession(new GeckoSessionSettings.Builder().userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE).build());
        // The video plays with sound without a tap on the page.
        s.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession session, ContentPermission permission) {
                if (permission.permission == PERMISSION_AUTOPLAY_AUDIBLE || permission.permission == PERMISSION_AUTOPLAY_INAUDIBLE) return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                return null;
            }
        });
        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onLocationChange(GeckoSession session, String url, List<GeckoSession.PermissionDelegate.ContentPermission> permissions, Boolean gesture) {
                main.post(() -> followed(url));
            }
        });
        BrowserRuntime shared = BrowserRuntime.get(context);
        s.open(shared.runtime);
        shared.bridge().accept(extension -> { s.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
            @Override public void onConnect(WebExtension.Port connected) {
                if (connected.sender.session != s || !connected.sender.isTopLevel()) { connected.disconnect(); return; }
                connected.setDelegate(new WebExtension.PortDelegate() {
                    @Override public void onPortMessage(Object message, WebExtension.Port source) {
                        if (!(message instanceof JSONObject json)) return;
                        main.post(() -> {
                            if (json.optBoolean("ready")) { port = source; if (active()) { if (wantPlay) sendQuality("watch"); else send("pause"); } }
                            JSONObject event = json.optJSONObject("mytube");
                            if (event != null && source == port) heard(event);
                        });
                    }
                    @Override public void onDisconnect(WebExtension.Port source) { main.post(() -> { if (port == source) port = null; }); }
                });
            }
        }, BrowserEngine.NATIVE);
            // Bound before the first page loads: a page loaded earlier would have no one to start it.
            main.post(() -> { bound = true; String uri = pendingUri; pendingUri = null; if (uri != null && session == s) s.loadUri(uri); });
        }, failed -> main.post(() -> {
            // Without the bridge the page still loads: its own ▶ plays it (the app cannot start or follow it).
            bound = true; String uri = pendingUri; pendingUri = null; if (uri != null && session == s) s.loadUri(uri);
            status = "플레이어 연결을 준비하지 못했어요 · 화면의 ▶로 재생할 수 있어요 · 앱을 다시 열면 고쳐져요"; changed();
        }));
        session = s;
        return s;
    }
    private void send(String command) {
        WebExtension.Port p = port;
        if (p != null) try { p.postMessage(dev.jun.gyeot.core.Jsons.obj("mytube", command)); } catch (RuntimeException gone) {}
    }
    /** A command that carries the chosen quality (watch: start this page's video; quality: apply it now). */
    private void sendQuality(String command) {
        WebExtension.Port p = port;
        if (p != null) try { p.postMessage(dev.jun.gyeot.core.Jsons.obj("mytube", dev.jun.gyeot.core.Jsons.obj("command", command, "quality", quality()))); } catch (RuntimeException gone) {}
    }
    /** Opens an address in the page once the bridge is bound to it. */
    private void open(String uri) {
        GeckoSession s = session(); s.setActive(true);
        if (bound) s.loadUri(uri); else pendingUri = uri;
    }
    /** Shows the page in 마이튜브's screen (its GeckoView). */
    void attach(GeckoView shown) {
        onScreen = true;
        if (view == shown) return;
        view = shown;
        shown.setSession(session());
        session().setActive(true);
    }
    /** The screen is gone: the page plays on without it when allowed (MyTubeService keeps the app up), else it pauses. */
    void detach(GeckoView shown) {
        onScreen = false;
        if (view == shown) { try { shown.releaseSession(); } catch (RuntimeException ignored) {} view = null; }
        if (!background() && playing) pause();
        if (session != null) session.setActive(background() && wantPlay);
    }

    // ── playing ──────────────────────────────────────────────────────────────
    /** Plays the list from index, as the given feed's. */
    void play(MyTube.Feed from, List<MyTube.Video> list, int index) {
        if (list == null || list.isEmpty()) return;
        feed = from; queue.clear(); queue.addAll(list); load(Math.max(0, Math.min(index, list.size() - 1)));
    }
    private void load(int index) {
        at = index; MyTube.Video v = current(); if (v == null) return;
        wantPlay = true; playing = false; status = "불러오는 중"; qualityNow = ""; levels = List.of(); qualitySettable = true;
        open(MyTube.watch(v.id()));
        if (feed != null) try { book.playedNow(feed.id(), v.id()); } catch (Exception ignored) {}
        if (background()) MyTubeService.start(context);
        changed();
    }
    /** Pauses what plays (or is about to), else plays: as the page is, not as it was asked. */
    void toggle() { if (playing || wantPlay && "불러오는 중".equals(status)) pause(); else resume(); }
    void pause() { wantPlay = false; send("pause"); status = "멈춤"; changed(); }
    void resume() { if (!active()) return; wantPlay = true; if (session != null) session.setActive(true); send("play"); status = "재생 중"; if (background()) MyTubeService.start(context); changed(); }
    void previous() { if (at > 0) load(at - 1); }
    void next() {
        if (at + 1 < queue.size()) { load(at + 1); return; }
        // A music feed goes on: more songs chosen afresh, away from what just played.
        if (feed != null && feed.kind() == MyTube.Kind.MUSIC) { refill(); return; }
        wantPlay = false; playing = false; status = "목록을 다 봤어요"; changed();
    }
    void like() {
        MyTube.Video v = current(); if (v == null || feed == null) return;
        try { if (book.liked(feed.id(), v.id())) book.unlike(feed.id(), v.id()); else book.mark(feed.id(), v, true); } catch (Exception ignored) {}
        changed();
    }
    /** Not for me: remembered (the next choice stays away from it) and the next one plays. */
    void skip() {
        MyTube.Video v = current(); if (v == null) return;
        if (feed != null) try { book.mark(feed.id(), v, false); } catch (Exception ignored) {}
        next();
    }
    /** Stops and forgets the queue; the page goes blank. */
    void stop() {
        wantPlay = false; playing = false; queue.clear(); at = -1; status = "";
        Runnable abort = cancel.getAndSet(null); if (abort != null) abort.run();
        pendingUri = null;
        if (session != null) { session.loadUri("about:blank"); session.setActive(onScreen); }
        MyTubeService.stop(context); changed();
    }
    /** Opens YouTube's sign-in in the player's page (the AI 브라우저 shares it). */
    void signIn() {
        wantPlay = false; queue.clear(); at = -1; status = "유튜브 로그인";
        open("https://accounts.google.com/ServiceLogin?service=youtube&continue=" + java.net.URLEncoder.encode("https://m.youtube.com/", java.nio.charset.StandardCharsets.UTF_8));
        changed();
    }

    private void heard(JSONObject event) {
        String what = event.optString("event"); MyTube.Video v = current();
        // Only the video this queue is on: an ad, or a video YouTube moved on to by itself, is not it. A start that never
        // came is told from any page (a sign-in or check page has no video id).
        if (v == null || !"stuck".equals(what) && !v.id().equals(MyTube.idOf(event.optString("url")))) return;
        switch (what) {
            // The page plays (by the app's word, by itself or by a tap on it): followed, never fought.
            case "playing" -> { playing = true; wantPlay = true; status = event.optBoolean("ad") ? "광고 재생 중" : "재생 중"; changed(); }
            case "paused" -> { playing = false; wantPlay = false; status = "멈춤"; changed(); }
            case "ended" -> next();
            case "error" -> { status = "재생할 수 없는 영상이라 다음으로 넘겨요"; changed(); main.postDelayed(() -> { if (current() == v) next(); }, 1500); }
            case "stuck" -> {
                status = "재생이 시작되지 않았어요 · 화면의 ▶를 눌러 보세요";
                store.phoneLog("마이튜브 · 재생이 시작되지 않음 · " + event.optString("page") + " · 플레이어 " + (event.optBoolean("player") ? "있음" : "없음")
                    + " · 상태 " + event.opt("state") + " · 영상 " + (event.optBoolean("video") ? "있음(" + event.optInt("ready") + ")" : "없음")
                    + (event.optString("why").isEmpty() ? "" : " · " + event.optString("why")));
                changed();
            }
            case "quality" -> {
                qualityNow = event.optString("quality"); qualitySettable = event.optBoolean("settable", true);
                List<String> got = new ArrayList<>(); org.json.JSONArray list = event.optJSONArray("levels");
                for (int i = 0; list != null && i < list.length(); i++) got.add(list.optString(i));
                levels = got; changed();
            }
            default -> {}
        }
    }
    /** The page went to another address: a video of the queue chosen on the page is followed; the start of a new load is not news. */
    private void followed(String url) {
        String id = MyTube.idOf(url);
        if (id.isEmpty()) return;
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).id().equals(id) && i != at) { at = i; changed(); return; }
    }
    private void refill() {
        if (refilling || feed == null) return;
        refilling = true; status = "다음 곡을 고르는 중"; changed();
        MyTube.Feed now = feed;
        Thread work = new Thread(() -> {
            String failure = null; List<MyTube.Video> more = List.of();
            try { more = MyTubeClient.refresh(context, store, now, step -> main.post(() -> { status = step; changed(); }), cancel); }
            catch (Exception e) { failure = e.getMessage(); }
            List<MyTube.Video> got = more; String why = failure;
            main.post(() -> {
                refilling = false;
                if (feed != now) return;
                if (got.isEmpty()) { wantPlay = false; status = why == null ? "더 고를 곡이 없어요" : why; changed(); return; }
                int from = queue.size(); queue.addAll(got); load(from);
            });
        }, "gyeot-mytube-refill");
        work.setDaemon(true); work.start();
    }
}
