package dev.jun.gyeot;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.AudioManager;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;
import dev.jun.gyeot.core.LpPlayback;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAudioManager;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The LP page playing behind other apps (준: "Lp가 다른창 열면 재생이 끊겨") and 퍼레 hearing it (준: "퍼레가 lp에서 노래 나오는 걸
 * 인식못하네"): what the page says it plays (PieceExtras /__gyeot/lp/state) starts the media notification (LpService) and takes
 * the audio focus, and makes BuddyTalk.env's music true; stopped, both go and music is false at once. The notification's
 * buttons and the focus (a call, other music) reach the page through the screen that shows it and in its next answer.
 * The page side is tests/browser/vinyl.test.js and launcher.test.js.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LpPlaybackTests {
    static final String LP = "https://lp.gyeot.local", PIECE = "/Claude-Opus-5.5-100-HTML-Files/057-vinyl-listening-room.html";
    Context app;
    final List<String> js = new ArrayList<>();

    @Before public void setUp() { app = RuntimeEnvironment.getApplication(); LpMedia.reset(); LpMedia.host(this, cmd -> js.add(cmd)); }
    @After public void tearDown() { LpMedia.reset(); }

    static WebResourceRequest get(String path) {
        Map<String, String> h = new HashMap<>(); h.put("Referer", LP + PIECE);
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(LP + "/__gyeot/" + path + (path.contains("?") ? "&" : "?") + "k=" + PieceExtras.key()); }
            @Override public boolean isForMainFrame() { return false; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return h; }
        };
    }
    /** The page's word, as PieceExtras gets it (from this test as its host). */
    JSONObject say(String q) throws Exception {
        WebResourceResponse r = PieceExtras.handle(this.app, get("lp/state?" + q), i -> {});
        assertEquals(200, r.getStatusCode());
        try (InputStream in = r.getData()) { ByteArrayOutputStream o = new ByteArrayOutputStream(); in.transferTo(o); return new JSONObject(o.toString(StandardCharsets.UTF_8)); }
    }
    Intent nextService() { return shadowOf((android.app.Application) app).getNextStartedService(); }

    @Test public void theRulesOfFocusAndWords() {
        LpPlayback.State s = new LpPlayback.State(true, "좋은 날", "아이유", "내 음악 · A면", true, 1000);
        assertTrue(LpPlayback.playing(s, 1000 + 44_000));
        assertFalse("a page that went quiet no longer plays", LpPlayback.playing(s, 1000 + LpPlayback.STALE_MS + 1));
        assertEquals("LP · 좋은 날", LpPlayback.title(s)); assertEquals("아이유 · 내 음악 · A면", LpPlayback.text(s));
        assertEquals("LP", LpPlayback.title(LpPlayback.State.NONE));
        // a call (lost for a while): paused, back afterwards; other music (lost): paused for good; directions (duck): nothing
        LpPlayback.Answer call = LpPlayback.focus(LpPlayback.Focus.LOSS_TRANSIENT, true, false);
        assertEquals(new LpPlayback.Answer("pause", true), call);
        assertEquals(new LpPlayback.Answer("play", false), LpPlayback.focus(LpPlayback.Focus.GAIN, false, call.resumeLater()));
        assertEquals(new LpPlayback.Answer("pause", false), LpPlayback.focus(LpPlayback.Focus.LOSS, true, false));
        assertEquals(new LpPlayback.Answer("", false), LpPlayback.focus(LpPlayback.Focus.LOSS_TRANSIENT_CAN_DUCK, true, false));
        assertEquals("paused by the user before the call: not played after it", new LpPlayback.Answer("", false), LpPlayback.focus(LpPlayback.Focus.GAIN, false, false));
        assertTrue(LpPlayback.serviceWanted(s, 2000, false)); assertTrue("a call holds it paused", LpPlayback.serviceWanted(LpPlayback.State.NONE, 2000, true));
        assertFalse(LpPlayback.serviceWanted(LpPlayback.State.NONE, 2000, false));
        assertTrue(LpPlayback.command("pause")); assertFalse(LpPlayback.command("rm -rf"));
        assertEquals("clipped and plain", 120, new LpPlayback.State(true, "x".repeat(300) + "\n", "", "", false, 0).title().length());
    }

    @Test public void playingStartsTheNotificationAndTheFocusAndIsMusicForThePerre() throws Exception {
        assertFalse(BuddyTalk.env(app).optBoolean("lp"));
        String song = "title=%EC%A2%8B%EC%9D%80%20%EB%82%A0&artist=%EC%95%84%EC%9D%B4%EC%9C%A0&album=%EB%82%B4%20%EC%9D%8C%EC%95%85";
        JSONObject a = say("playing=1&" + song + "&hidden=0");
        assertEquals("nothing asked yet", "", a.getString("cmd"));
        assertTrue(LpMedia.playing());
        assertTrue("the screen it plays on knows (the wallpaper keeps its web view going)", LpMedia.playingIn(app)); assertFalse(LpMedia.playingIn(new Object()));
        Intent show = nextService();
        assertNotNull("the media notification's service", show);
        assertEquals(LpService.class.getName(), show.getComponent().getClassName()); assertEquals(LpService.SHOW, show.getAction());
        assertTrue("the audio focus held", LpMedia.holdsFocus());
        ShadowAudioManager am = shadowOf(app.getSystemService(AudioManager.class));
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, am.getLastAudioFocusRequest().audioFocusRequest.getFocusGain());
        JSONObject env = BuddyTalk.env(app);
        assertTrue("퍼레 hears music: " + env, env.getBoolean("music")); assertTrue(env.getBoolean("lp")); assertFalse("no other app's", env.getBoolean("musicApps"));

        // The service itself: up with "LP · 좋은 날", 일시 정지 and 끄기, its media session (Android 13+ media controls)
        LpService svc = Robolectric.buildService(LpService.class, show).create().startCommand(0, 1).get();
        Notification n = shadowOf(svc).getLastForegroundNotification();
        assertNotNull(n);
        assertEquals("LP · 좋은 날", n.extras.getString(Notification.EXTRA_TITLE)); assertEquals("아이유 · 내 음악", n.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(2, n.actions.length); assertEquals("일시 정지", n.actions[0].title.toString()); assertEquals("끄기", n.actions[1].title.toString());
        assertNotNull("a media session", n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION));
        assertNotNull("the buttons drawn (with icons)", n.actions[0].getIcon()); assertNotNull(n.actions[1].getIcon());
        assertEquals("Android 13+ media controls: playing", android.media.session.PlaybackState.STATE_PLAYING, svc.playbackState().getState());
        assertEquals("and 끄기 there too", "끄기", svc.playbackState().getCustomActions().get(0).getName().toString());
        assertEquals("좋은 날", svc.metadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE));
        assertEquals(Notification.CATEGORY_TRANSPORT, n.category); assertTrue(LpService.running());
        Intent tap = shadowOf(n.contentIntent).getSavedIntent();
        assertEquals("a tap opens the LP page", "LP", tap.getStringExtra(HomeActivity.PAGE)); assertEquals(HomeActivity.class.getName(), tap.getComponent().getClassName());
        draw(n);

        // 일시 정지 on it: the page asked through its screen at once, and in its next answer
        svc.onStartCommand(new Intent(app, LpService.class).setAction(LpService.PAUSE), 0, 2);
        assertTrue("to the screen that shows it: " + js, js.contains("pause"));
        assertEquals("pause", say("playing=1&" + song + "&hidden=1").getString("cmd"));
        assertEquals("asked once", "", say("playing=1&" + song + "&hidden=1").getString("cmd"));
        assertNull("the same song said again: the notification not built again", nextService());
        assertTrue(LpMedia.js("pause").contains("launcher.lp") && LpMedia.js("pause").contains("\"pause\""));

        // paused (from the notification): nothing runs, the notification stays with its play button; 퍼레 hears no music at once
        say("playing=0&" + song + "&hidden=1");
        assertFalse(LpMedia.playing()); assertFalse("the focus let go", LpMedia.holdsFocus());
        assertFalse(BuddyTalk.env(app).getBoolean("music"));
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull("the service told in place (no start from behind other apps)", nextService());
        assertFalse(LpService.running());
        Notification kept = shadowOf(app.getSystemService(android.app.NotificationManager.class)).getNotification(LpService.NOTIFICATION);
        assertNotNull("kept to play again", kept);
        assertEquals("재생", kept.actions[0].title.toString()); assertEquals(0, kept.flags & Notification.FLAG_ONGOING_EVENT);
        assertEquals(android.media.session.PlaybackState.STATE_PAUSED, svc.playbackState().getState());
        // its 재생 (a foreground start, as Android allows from a notification): up at once, and the page asked
        Intent play = shadowOf(kept.actions[0].actionIntent).getSavedIntent();
        assertEquals(LpService.PLAY, play.getAction());
        assertTrue("a foreground start", shadowOf(kept.actions[0].actionIntent).isForegroundServiceIntent());
        LpService again = Robolectric.buildService(LpService.class, play).create().startCommand(0, 1).get();
        assertTrue(LpService.running()); assertNotNull(shadowOf(again).getLastForegroundNotification());
        assertEquals("play", js.get(js.size() - 1));
    }

    @Test public void aCallPausesItAndItPlaysAgainAfter_otherMusicStopsIt_pausedOnThePageEverythingGoes() throws Exception {
        say("playing=1&title=a&hidden=1"); nextService();
        LpMedia.onFocus(app, LpPlayback.Focus.LOSS_TRANSIENT);
        assertEquals(List.of("pause"), js); assertTrue(LpMedia.resumes());
        say("playing=0&title=a&hidden=1");
        assertEquals("the notification stays up for it while the call lasts", LpService.SHOW, nextService().getAction());
        LpMedia.onFocus(app, LpPlayback.Focus.GAIN);
        assertEquals(List.of("pause", "play"), js);
        say("playing=1&title=a&hidden=1"); nextService();
        LpMedia.onFocus(app, LpPlayback.Focus.LOSS);
        assertEquals(List.of("pause", "play", "pause"), js); assertFalse(LpMedia.resumes()); assertFalse(LpMedia.holdsFocus());
        // its word from before the pause (said every 2 s) does not take the focus back from the other music
        say("playing=1&title=a&hidden=1");
        assertFalse("the focus left with the other music", LpMedia.holdsFocus()); assertNull(nextService());
        say("playing=0&title=a&hidden=1");
        assertFalse(LpMedia.holdsFocus()); assertFalse(LpMedia.playing());
        // paused on the page itself (no button of the notification): all of it goes
        say("playing=1&title=a&hidden=0"); assertTrue("played again on the page: the focus again", LpMedia.holdsFocus()); nextService();
        say("playing=0&title=a&hidden=0");
        assertNull("no service to stop (it never came up here): the notification simply gone", nextService());
        assertNull(shadowOf(app.getSystemService(android.app.NotificationManager.class)).getNotification(LpService.NOTIFICATION));
        assertFalse(LpMedia.playing()); assertFalse(BuddyTalk.env(app).getBoolean("music"));
        // the same word every 2 s while it plays: the notification is not built again each time
        say("playing=1&title=b&hidden=1"); Intent up = nextService(); assertNotNull(up);
        LpService svc = Robolectric.buildService(LpService.class, up).create().startCommand(0, 1).get();
        say("playing=1&title=b&hidden=1"); assertNull("the same word again: nothing built again", nextService());
        say("playing=1&title=c&hidden=1"); shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull("another track: brought up to date in place, no start from behind other apps", nextService());
        assertEquals("c", svc.metadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE));
        assertEquals("LP · c", shadowOf(svc).getLastForegroundNotification().extras.getString(Notification.EXTRA_TITLE));
        say("playing=0&title=c&hidden=0"); shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse("paused on the page: all of it goes", LpService.running());
        assertNull(shadowOf(app.getSystemService(android.app.NotificationManager.class)).getNotification(LpService.NOTIFICATION));
        // the screen showing it closed while it plays (its web view gone): it stops there, so the notification and the focus go
        say("playing=1&title=d&hidden=1"); assertTrue(LpMedia.holdsFocus());
        LpMedia.gone(new android.content.ContextWrapper(app));
        assertTrue("another screen closing changes nothing", LpMedia.playing());
        LpMedia.gone(app);
        assertFalse(LpMedia.playing()); assertFalse(LpMedia.holdsFocus()); assertFalse(BuddyTalk.env(app).getBoolean("music"));
    }

    /** The notification's own look, drawn as Android lays it out (build/ui-verification/lp_notification.png). */
    void draw(Notification n) throws Exception {
        draw(Notification.Builder.recoverBuilder(app, n).createContentView(), "lp_notification.png");
        draw(Notification.Builder.recoverBuilder(app, n).createBigContentView(), "lp_notification_big.png");
    }
    void draw(android.widget.RemoteViews rv, String name) throws Exception {
        if (rv == null) return;
        try {
            FrameLayout parent = new FrameLayout(app);
            View v = rv.apply(app, parent);
            int w = Math.round(392 * app.getResources().getDisplayMetrics().density);
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
            Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b); c.drawColor(0xfff2f2f6); v.draw(c);
            File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
        } catch (RuntimeException | Error notHere) { System.out.println("the notification's look could not be drawn here: " + notHere); }
    }
}
