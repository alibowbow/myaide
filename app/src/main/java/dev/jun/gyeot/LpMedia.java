package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import dev.jun.gyeot.core.LpPlayback;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * The LP page playing behind other apps (준: "Lp가 다른창 열면 재생이 끊겨"; core/LpPlayback has the rules). The page says what
 * it plays (PieceExtras /__gyeot/lp/state, on each change and every 2 s while it plays); while it plays the app holds the
 * audio focus and LpService shows its media notification, and nothing runs once it is paused or stopped. The notification's
 * buttons and the focus (a call, other music) ask the page through the home screen that shows it (its host's JS: at once,
 * though it is out of sight) and in the answer to its next word. 퍼레 hears it as music (BuddyTalk.env).
 */
final class LpMedia {
    private LpMedia() {}
    private static volatile LpPlayback.State state = LpPlayback.State.NONE;
    private static volatile String pending = "", asked = "";
    private static volatile long askedAt;
    private static volatile boolean resumeLater, pausedOutside;
    private static volatile Object owner;
    private static volatile Context app;
    private static AudioFocusRequest focus;
    private static final Map<Object, Consumer<String>> HOSTS = new WeakHashMap<>();

    /** A screen that shows LP pages (the launcher, the wallpaper's board): how a command reaches them (its page's JS). */
    static void host(Object key, Consumer<String> run) { synchronized (HOSTS) { if (run == null) HOSTS.remove(key); else HOSTS.put(key, run); } }

    /** The JS a host runs for a command: the LP page itself (shown alone), or the home screen or the wallpaper's board around it. */
    static String js(String cmd) {
        return "(function(c){try{if(window.__gyCmd)return __gyCmd(c);if(typeof launcher!=='undefined'&&launcher.lp)return launcher.lp(c);if(window.board&&board.lp)return board.lp(c);}catch(e){}})("
            + org.json.JSONObject.quote(cmd) + ")";
    }
    static LpPlayback.State state() { return state; }
    /** Whether the LP plays now (퍼레 hears music). */
    static boolean playing() { return LpPlayback.playing(state, System.currentTimeMillis()); }
    /** Whether it plays on a page of this screen (the wallpaper does not pause its web view then). */
    static boolean playingIn(Object host) { return owner == host && playing(); }

    /**
     * The page's word (from the web view's request thread): what it plays now. Starts the notification and takes the focus
     * when it starts, drops both when it stops (unless a call holds it paused, or it was paused from the notification, which
     * then stays, dismissible, with its play button). Answers the command waiting for the page, if any.
     */
    static String report(Context c, LpPlayback.State s) {
        Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        LpMedia.app = app;
        boolean was = playing();
        LpPlayback.State prev = state;
        state = s; owner = c;
        // (a pause asked a moment ago and not done yet: its word now is from before it, and changes nothing; else, after a
        // call or other music took the sound, it would take the focus back from them)
        boolean before = System.currentTimeMillis() - askedAt < 10_000 && ("play".equals(asked) ? !s.playing() : ("pause".equals(asked) || "stop".equals(asked)) && s.playing());
        if (!before) asked = "";
        if (before) { /* its next word */ }
        else if (s.playing()) {
            if (!was) { resumeLater = false; pausedOutside = false; }
            // 내 음악: the web view holds the focus for its file itself; taking it here silenced the file (LpPlayback.takesFocus).
            if (LpPlayback.takesFocus(s)) take(app); else drop(app);
            // (said every 2 s while it plays: the notification only when it starts or the track changes)
            if (!was || !LpService.running() || !prev.title().equals(s.title()) || !prev.album().equals(s.album())) LpService.show(app);
        } else if (was || LpService.running()) {
            if (resumeLater) LpService.show(app);            // a call holds it paused: the notification stays up for it
            else { drop(app); LpService.hide(app, pausedOutside); }
        }
        String p = pending; pending = "";
        return p;
    }
    /** The screen that showed the LP page closes its web view: whatever it played stops with it (its notification, the focus). */
    static void gone(Context c) { if (owner == c && state.playing()) report(c, LpPlayback.State.NONE); }
    /** A command for the page (the notification's buttons, the focus): to the screen that shows it now, and in its next answer. */
    static void command(Context c, String cmd, boolean outside) {
        if (!LpPlayback.command(cmd)) return;
        if (outside && ("pause".equals(cmd) || "stop".equals(cmd))) pausedOutside = "pause".equals(cmd);
        pending = cmd; asked = cmd; askedAt = System.currentTimeMillis();
        Object o = owner; Consumer<String> run;
        synchronized (HOSTS) { run = o == null ? null : HOSTS.get(o); if (run == null && HOSTS.size() == 1) run = HOSTS.values().iterator().next(); }
        if (run != null) try { run.accept(cmd); } catch (RuntimeException gone) { /* the next word picks it up */ }
    }
    /** Called by LpService now and then: a page that went quiet (closed, its renderer gone) no longer plays. */
    static void check(Context c) {
        if (!LpPlayback.serviceWanted(state, System.currentTimeMillis(), resumeLater)) { drop(c); LpService.hide(c, false); }
    }

    private static final AudioManager.OnAudioFocusChangeListener LISTEN = change -> {
        LpPlayback.Focus f = switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN -> LpPlayback.Focus.GAIN;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> LpPlayback.Focus.LOSS_TRANSIENT;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> LpPlayback.Focus.LOSS_TRANSIENT_CAN_DUCK;
            default -> LpPlayback.Focus.LOSS;
        };
        onFocus(app, f);
    };
    /** A change of the audio focus: the page paused or played again (core/LpPlayback.focus). */
    static void onFocus(Context c, LpPlayback.Focus f) {
        LpPlayback.Answer a = LpPlayback.focus(f, playing(), resumeLater);
        resumeLater = a.resumeLater();
        if (!a.command().isEmpty() && c != null) command(c, a.command(), false);
        if (f == LpPlayback.Focus.LOSS && c != null) drop(c);
    }
    private static synchronized void take(Context c) {
        if (focus != null) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null) return;
        AudioFocusRequest r = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(LISTEN).build();
        try { if (am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = r; } catch (RuntimeException ignored) {}
    }
    private static synchronized void drop(Context c) {
        if (focus == null) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        try { if (am != null) am.abandonAudioFocusRequest(focus); } catch (RuntimeException ignored) {}
        focus = null;
    }
    /** (for checks) whether the focus is held, and whether a pause waits to be undone. */
    static boolean holdsFocus() { return focus != null; }
    static boolean resumes() { return resumeLater; }
    /** (for checks) back to nothing. */
    static void reset() { LpService.reset(); app = null; state = LpPlayback.State.NONE; pending = ""; asked = ""; askedAt = 0; resumeLater = false; pausedOutside = false; owner = null; focus = null; synchronized (HOSTS) { HOSTS.clear(); } }
}
