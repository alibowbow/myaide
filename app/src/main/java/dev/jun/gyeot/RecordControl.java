package dev.jun.gyeot;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.Jsons;
import org.json.JSONObject;

/**
 * 녹화·녹음·캡쳐's one controller (준: "녹화하기가 너무 번거로워. 홈에 바로가기를 만들자 그걸 누르면 위성처럼 여러가지 팝업아이콘이
 * 뜨고 그중 하나가 녹화하기야 캡쳐도 만들어줘", "녹화 시작 일시정지 및 정지 버튼도 구현해줘 녹화화면상에서 바로 조작할 수 있기").
 * Every start, 일시정지, 재개 and stop of 화면 녹화 (ScreenRecordService) and 녹음 (AssistantService), from the home screen's
 * satellite, the floating controls (RecordPill), the tile and the app's rows, goes through act(); the services call changed()
 * back on every change, after the store's changed(). So the home screen's dot, the controls, the notifications, the tile and the
 * rows all say the same.
 */
final class RecordControl {
    private RecordControl() {}
    static final String SCREEN = "screen", SCREEN_STOP = "screenStop", SCREEN_PAUSE = "screenPause", SCREEN_RESUME = "screenResume",
        AUDIO = "audio", AUDIO_STOP = "audioStop", AUDIO_PAUSE = "audioPause", AUDIO_RESUME = "audioResume", CAPTURE = "capture";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 화면 녹화 now: "" (none), "starting" (asking, opening the encoder), "on", "paused", "saving" (finishing the file). */
    static String screen() {
        if (!ScreenRecordService.running()) return "";
        if (ScreenRecordService.saving()) return "saving";
        if (!ScreenRecordService.started()) return "starting";
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
     * One of the actions above, on the main thread: 녹화하기 (SCREEN: Android's consent once, through ScreenRecordActivity; a
     * second press while it records stops it), its stop, pause and resume; 녹음하기 (AUDIO: the microphone asked the app's way
     * when it is not allowed yet) and the same; 캡쳐. False when it is not one of them.
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
            case CAPTURE -> ScreenCapture.take(c);
            default -> { return false; }
        }
        return true;
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
    /** The see-through start (Android's consent, or the microphone question): mode null for 화면 녹화. */
    static void open(Context c, String mode) {
        Intent intent = new Intent(c, ScreenRecordActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mode != null) intent.putExtra(ScreenRecordActivity.MODE, mode);
        try { c.startActivity(intent); } catch (RuntimeException refused) { ((GyeotApp) c.getApplicationContext()).store().error("녹화 화면을 열지 못했어요 · " + refused.getClass().getSimpleName()); }
    }
    /** To the running 녹음 (it is in the foreground while it records, so a plain start reaches it). */
    private static void assistant(Context c, String action) {
        Intent intent = new Intent(c, AssistantService.class).setAction(action);
        try { c.startService(intent); }
        catch (RuntimeException background) { try { c.startForegroundService(intent); } catch (RuntimeException refused) { /* nothing recording */ } }
    }

    /** A recording started, paused, went on or ended (any thread): the floating controls follow on the main thread. */
    static void changed(Context c) {
        Context app = c.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) RecordPill.sync(app); else MAIN.post(() -> RecordPill.sync(app));
    }
}
