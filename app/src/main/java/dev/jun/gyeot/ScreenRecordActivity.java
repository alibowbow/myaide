package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import dev.jun.gyeot.core.ScreenRecordPlan;
import java.util.ArrayList;
import java.util.List;

/**
 * 화면 녹화's see-through start (the app's row, the quick settings tile): asks for the microphone when the chosen sound needs
 * it, then Android's screen capture consent for the whole screen, hands the result and the screen's real size to
 * ScreenRecordService, and closes. When a recording is already on, it stops it instead. Only one asks at a time: a second
 * tap while the first one's prompt shows closes at once, so the user never gets two consent prompts.
 *
 * The home screen's satellite (RecordControl) opens it in two more ways (MODE): 녹음하기 (AUDIO: the microphone asked as the
 * app's 녹음 asks it, then 녹음 starts) and 캡쳐's last way (CAPTURE: Android's consent, then one frame, ScreenShotService).
 */
public final class ScreenRecordActivity extends Activity {
    private static final int PERMISSIONS = 1, CONSENT = 2, MIC = 3;
    /** How it was opened: absent for 화면 녹화, AUDIO or CAPTURE. */
    static final String MODE = "mode", AUDIO = "audio", CAPTURE = "capture";
    private String mode = "";
    /** An instance is asking (its permission or consent prompt is up). */
    private static volatile boolean open;
    private boolean asked, requesting, owns;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        asked = state != null && state.getBoolean("asked");
        requesting = state != null && state.getBoolean("requesting");
        // Recreated (turned, a font or theme change) while a prompt was showing: its answer comes to this new window.
        if (asked || requesting) { open = true; owns = true; return; }
        // Another tap while the first one still asks: that one goes on, this one leaves.
        if (open) { close(); return; }
        String how = getIntent() == null ? null : getIntent().getStringExtra(MODE);
        mode = how == null ? "" : how;
        if (AUDIO.equals(mode)) { audio(); return; }
        if (CAPTURE.equals(mode)) { open = true; owns = true; ask(); return; }
        if (ScreenRecordService.running()) { ScreenRecordService.stop(this); close(); return; }
        open = true; owns = true;
        SharedPreferences settings = ((GyeotApp) getApplication()).store().settings;
        List<String> missing = new ArrayList<>();
        if (ScreenRecordService.sound(settings).needsRecordPermission() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO);
        // Notifications show "화면 녹화 중" with 중지, and "저장했어요"; asked once.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !settings.getBoolean("askedScreenRecordNotifications", false)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS); settings.edit().putBoolean("askedScreenRecordNotifications", true).apply();
        }
        if (!missing.isEmpty()) { requesting = true; requestPermissions(missing.toArray(new String[0]), PERMISSIONS); return; }
        ask();
    }
    /** 녹음하기 from the home screen: the microphone first when it is not allowed yet, then 녹음 starts (in AssistantService). */
    private void audio() {
        if (((GyeotApp) getApplication()).store().recording) { RecordControl.act(this, RecordControl.AUDIO_STOP); close(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { RecordControl.startAudioNow(this); close(); return; }
        open = true; owns = true; requesting = true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putBoolean("asked", asked); state.putBoolean("requesting", requesting); }
    @Override protected void onDestroy() { if (owns && !isChangingConfigurations()) open = false; super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == MIC) {
            requesting = false;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) RecordControl.startAudioNow(this);
            else Toast.makeText(this, "녹음에는 마이크 권한이 필요해요 · Android 설정 → 앱 → 들퍼레 → 권한에서 켤 수 있어요", Toast.LENGTH_LONG).show();
            close();
            return;
        }
        if (code != PERMISSIONS) return;
        requesting = false;
        // A second answer (the window was recreated while the prompt showed) must not open a second consent prompt.
        if (asked) return;
        // Without the microphone it still records the picture; the service says so in its notification.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            && ScreenRecordService.sound(((GyeotApp) getApplication()).store().settings).needsRecordPermission())
            Toast.makeText(this, "마이크 권한이 없어서 소리 없이 녹화해요", Toast.LENGTH_SHORT).show();
        ask();
    }
    /** Android's consent; from Android 14 for the whole screen only (not one app's window). */
    private void ask() {
        asked = true;
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            Intent consent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) : manager.createScreenCaptureIntent();
            startActivityForResult(consent, CONSENT);
        } catch (ActivityNotFoundException | NullPointerException | SecurityException unavailable) {
            Toast.makeText(this, "이 휴대폰에서는 화면 녹화 허락 창을 열 수 없어요", Toast.LENGTH_LONG).show();
            close();
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CONSENT) return;
        boolean capture = CAPTURE.equals(mode) || getIntent() != null && CAPTURE.equals(getIntent().getStringExtra(MODE));
        if (result != RESULT_OK || data == null) { Toast.makeText(this, capture ? "캡쳐하지 않았어요" : "화면 녹화를 시작하지 않았어요", Toast.LENGTH_SHORT).show(); close(); return; }
        // The screen's real size in its current orientation: the recording is made at this size, not scaled down. Its refresh
        // rate sets the frame rate (an even share of it).
        Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
        if (capture) {
            try { ScreenShotService.start(this, result, data, screen.width(), screen.height(), getResources().getConfiguration().densityDpi); }
            catch (RuntimeException refused) { ScreenCapture.failed(this, "캡쳐를 시작하지 못했어요 · " + refused.getClass().getSimpleName()); }
            close();
            return;
        }
        float refresh = ScreenRecorder.refreshRate(this);
        try {
            ScreenRecordService.start(this, result, data, screen.width(), screen.height(), getResources().getConfiguration().densityDpi, refresh);
            // Without notifications the "화면 녹화 중 · 중지" line never shows: say once where it stops instead.
            NotificationManager notifications = getSystemService(NotificationManager.class);
            if (notifications != null && !notifications.areNotificationsEnabled()) Toast.makeText(this, ScreenRecordService.NO_NOTIFICATIONS, Toast.LENGTH_LONG).show();
        } catch (RuntimeException refused) { Toast.makeText(this, "화면 녹화를 시작하지 못했어요 · " + refused.getClass().getSimpleName(), Toast.LENGTH_LONG).show(); }
        close();
    }
    @SuppressWarnings("deprecation")
    private void close() {
        // Done asking: a new tap may ask again at once (onDestroy can come a while after finish).
        if (owns) { open = false; owns = false; }
        if (!isFinishing()) finish(); overridePendingTransition(0, 0);
    }
    /** What the next recording will be, for the tile and the app's row (no encoder query). */
    static String settingsLine(SharedPreferences settings) {
        ScreenRecordPlan.Quality quality = ScreenRecordService.quality(settings); ScreenRecordPlan.Sound sound = ScreenRecordService.sound(settings);
        return quality.label + " 화질 · " + (sound == ScreenRecordPlan.Sound.NONE ? "소리 없음" : sound.label);
    }
}
