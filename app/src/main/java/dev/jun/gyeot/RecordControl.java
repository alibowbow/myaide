package dev.jun.gyeot;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import dev.jun.gyeot.core.Jsons;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * 녹화·녹음's one controller (준: "녹화하기가 너무 번거로워. 홈에 바로가기를 만들자", "녹화 시작 일시정지 및 정지 버튼도 구현해줘
 * 녹화화면상에서 바로 조작할 수 있기", "같이 녹화되면 안되지"). Every start, 일시정지, 재개 and stop of 화면 녹화 (ScreenRecordService)
 * and 녹음 (AssistantService), from the voice bubble's 녹화, the home screen's satellite, the recording bubble, the notification,
 * the tile and the app's rows, goes through act(); the services call changed() back on every change, after the store's
 * changed(), so all of them say the same.
 *
 * Nothing of ours is in a recording: while 화면 녹화 writes frames everything of ours that floats over other apps is veiled
 * (see-through, or off the screen): the recording bubble, the voice bubble, the edge handle, the taskbar, 퍼레's card and
 * phone control's stop. Each listens with onVeil. A touch on one of the see-through ones ticks and pauses the recording at the
 * touch's own time first (veiledTouch), and only then they show again; going on (재개, and the start) veils them first and
 * writes again once the frame without them is drawn and GONE_WAIT_MS more (clearThen).
 */
final class RecordControl {
    private RecordControl() {}
    static final String SCREEN = "screen", SCREEN_STOP = "screenStop", SCREEN_PAUSE = "screenPause", SCREEN_RESUME = "screenResume",
        AUDIO = "audio", AUDIO_STOP = "audioStop", AUDIO_PAUSE = "audioPause", AUDIO_RESUME = "audioResume";
    /** After the frame without our controls is drawn, this long again before anything is written (a few frames at any refresh rate). */
    static final long GONE_WAIT_MS = 120;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 화면 녹화 now: "" (none), "starting" (asking, opening the encoder), "countdown" (held: the recording bubble counts down
     * before the first frame), "on", "paused", "saving" (finishing the file).
     */
    static String screen() {
        if (!ScreenRecordService.running()) return "";
        if (ScreenRecordService.saving()) return "saving";
        if (!ScreenRecordService.started()) return "starting";
        if (ScreenRecordService.counting()) return "countdown";
        return ScreenRecordService.paused() ? "paused" : "on";
    }
    /** 녹음 now: "" (none), "on", "paused". */
    static String audio(AppStore store) { return !store.recording ? "" : store.recordingPaused ? "paused" : "on"; }
    /** How long 녹음 has recorded, the pauses left out. */
    static long audioElapsedMs(AppStore store) {
        long start = store.recordingStartedAt;
        return store.recording && start > 0 ? store.recordingPauses.elapsed(start, System.currentTimeMillis()) : 0;
    }
    /** What the home screen's shortcut shows: {"screen": …, "audio": …} (its dot, and 정지 in its satellite). */
    static JSONObject state(AppStore store) { return Jsons.obj("screen", screen(), "audio", audio(store)); }

    /**
     * One of the actions above, on the main thread: 녹화하기 (SCREEN: the start sheet and Android's consent, through
     * ScreenRecordActivity; a second press while it records stops it), its stop, pause and resume; 녹음하기 (AUDIO: the
     * microphone asked the app's way when it is not allowed yet) and the same. False when it is not one of them.
     */
    static boolean act(Context c, String what) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        switch (what == null ? "" : what) {
            case SCREEN -> {
                if (ScreenRecordService.running()) { if (!ScreenRecordService.saving()) ScreenRecordService.stop(c); }
                else open(c, null);
            }
            case SCREEN_STOP -> { if (ScreenRecordService.running() && !ScreenRecordService.saving()) ScreenRecordService.stop(c); }
            case SCREEN_PAUSE -> { if ("on".equals(screen())) ScreenRecordService.pause(c); }
            case SCREEN_RESUME -> { if ("paused".equals(screen())) ScreenRecordService.resume(c); }
            case AUDIO -> { if (store.recording) assistant(c, AssistantService.RECORD_STOP); else startAudio(c, store); }
            case AUDIO_STOP -> { if (store.recording) assistant(c, AssistantService.RECORD_STOP); }
            case AUDIO_PAUSE -> { if ("on".equals(audio(store))) assistant(c, AssistantService.RECORD_PAUSE); }
            case AUDIO_RESUME -> { if ("paused".equals(audio(store))) assistant(c, AssistantService.RECORD_RESUME); }
            default -> { return false; }
        }
        return true;
    }
    /**
     * 녹화 시작 ends a voice conversation first (준: "녹화하기 누르면 음성대화는 자동으로 종료되게"), the way the voice bubble's long
     * press ends it, without a goodbye; its microphone is then free for 마이크+미디어. The main chat's spoken conversation and
     * 퍼레's talk end too.
     */
    static void endVoice(Context c) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        if (store.voiceActive) assistant(c, AssistantService.END_LIVE);
        try { if (store.voiceChat != null) store.voiceChat.end("화면 녹화를 시작해서 음성 대화를 끝냈어요"); } catch (RuntimeException ignored) { /* already ended */ }
        try { BuddyVoice talk = BuddyVoice.current(); if (talk != null && talk.on()) talk.stop(); } catch (RuntimeException ignored) { /* already ended */ }
    }
    /** 녹음하기: at once when the microphone is allowed; otherwise the see-through window asks for it first, then starts. */
    private static void startAudio(Context c, AppStore store) {
        if (store.voiceActive) { store.error("음성 대화 중에는 녹음할 수 없어요. 음성을 끝낸 뒤 녹음해 주세요."); return; }
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { open(c, ScreenRecordActivity.AUDIO); return; }
        startAudioNow(c);
    }
    /** The microphone is allowed: 녹음 starts in AssistantService (in the foreground, so it goes on with the screen off). */
    static void startAudioNow(Context c) {
        try { c.startForegroundService(new Intent(c, AssistantService.class).setAction(AssistantService.RECORD_START)); }
        catch (RuntimeException refused) { ((GyeotApp) c.getApplicationContext()).store().error("녹음을 시작하지 못했어요 · " + refused.getClass().getSimpleName()); }
    }
    /** The see-through start (the start sheet and Android's consent, or the microphone question): mode null for 화면 녹화. */
    static void open(Context c, String mode) {
        Intent intent = new Intent(c, ScreenRecordActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mode != null) intent.putExtra(ScreenRecordActivity.MODE, mode);
        try { c.startActivity(intent); } catch (RuntimeException refused) { ((GyeotApp) c.getApplicationContext()).store().error("녹화 화면을 열지 못했어요 · " + refused.getClass().getSimpleName()); }
    }
    /** To AssistantService (running while 녹음 or a voice conversation is on, so a plain start reaches it). */
    private static void assistant(Context c, String action) {
        Intent intent = new Intent(c, AssistantService.class).setAction(action);
        try { c.startService(intent); }
        catch (RuntimeException background) { try { c.startForegroundService(intent); } catch (RuntimeException refused) { /* nothing on */ } }
    }

    // ── The veil: nothing of ours in the video ──────────────────────

    /**
     * What carries 화면 녹화's controls on the screen: the recording bubble when anything of ours can float over other apps
     * (the overlay permission, or 들퍼레 폰 조작's own windows); otherwise only the notification and the tile.
     */
    enum Controls { BUBBLE, NONE }
    static Controls controls(Context c) { return RecordBubble.possible(c) ? Controls.BUBBLE : Controls.NONE; }

    private static boolean veiled;
    private static final List<Runnable> veils = new ArrayList<>();
    /** Whether frames are written now, or about to be: everything of ours over other apps is see-through or off the screen. */
    static boolean veiled() { return veiled; }
    /** An overlay of ours follows the veil (main thread): its listener is run whenever it changes, and it reads veiled(). */
    static void onVeil(Runnable follow) { if (!veils.contains(follow)) veils.add(follow); }
    static void offVeil(Runnable follow) { veils.remove(follow); }
    private static void veil(boolean on) {
        if (veiled == on) return;
        veiled = on;
        for (Runnable follow : new ArrayList<>(veils)) { try { follow.run(); } catch (RuntimeException ignored) { /* that one alone */ } }
    }
    /** Everything of ours shows again (paused, or the recording ended): only once nothing is written (main thread). */
    static void unveil() { veil(false); }
    /**
     * Veils everything of ours, then runs then once it is gone from the screen: after the next frame is drawn without it, and
     * GONE_WAIT_MS more. Not when the recording was paused again meanwhile. Main thread.
     */
    static void clearThen(Context app, Runnable then) {
        veil(true);
        Choreographer.getInstance().postFrameCallback(frame -> MAIN.postDelayed(() -> { if (veiled) then.run(); }, GONE_WAIT_MS));
    }
    private static boolean swallowing;
    /**
     * For each see-through control's touch listener, first: while veiled, a touch ticks, pauses the recording at the touch's
     * own time (the clock its frames are stamped with), then everything shows again; the rest of that touch is used up, so
     * it presses nothing. True when the touch was taken here.
     */
    static boolean veiledTouch(View v, MotionEvent e) {
        int action = e.getActionMasked();
        if (swallowing) { if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowing = false; return true; }
        if (!veiled || action != MotionEvent.ACTION_DOWN) return false;
        swallowing = true;
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (!ScreenRecordService.pauseAt(v.getContext(), e.getEventTime() * 1000L)) unveilIfIdle();
        return true;
    }
    /** Veiled with nothing being written (it ended meanwhile): show everything again. */
    private static void unveilIfIdle() { String s = screen(); if (s.isEmpty() || "paused".equals(s)) unveil(); }

    /** A recording started, paused, went on or ended (any thread): the controls on the screen follow on the main thread. */
    static void changed(Context c) {
        Context app = c.getApplicationContext();
        Runnable follow = () -> { unveilIfIdle(); RecordBubble.sync(app); };
        if (Looper.myLooper() == Looper.getMainLooper()) follow.run(); else MAIN.post(follow);
    }
}
