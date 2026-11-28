package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.ScreenRecordPlan;
import java.util.ArrayList;
import java.util.List;

/**
 * 화면 녹화's see-through start, over whatever is open (the voice bubble's 녹화, the home screen's 녹화하기, the app's row, the
 * quick settings tile): first our own 화면 녹화 시작 sheet, after Samsung's (준: "이거 참고"), compact: one caution line and 녹음할
 * 오디오 as three choices in a row (화면만 · 미디어 · 마이크+미디어, the last one used chosen), then 취소 | 녹화 시작. 녹화 시작
 * ends a voice conversation first (준: "녹화하기 누르면 음성대화는 자동으로 종료되게"), asks for the microphone when the sound needs
 * it, then Android's consent for the whole screen, hands the result and the screen's real size to ScreenRecordService, and
 * closes. When a recording is already on, it stops it instead. Only one asks at a time: a second tap while the first one's
 * sheet or prompt shows closes at once, so the user never gets two consent prompts.
 *
 * Samsung's 부분 화면 is left out (a part of the screen would need a crop on the way into the encoder). Its 누른 위치 표시 is
 * a switch under the sound: Android's own is a setting an app cannot switch, so 들퍼레 draws its own circles (TouchMarks),
 * where 폰 조작 says a view was pressed; without 폰 조작 the line says so and opens it in one tap.
 *
 * The home screen's 녹음하기 opens it as AUDIO: the microphone asked as the app's 녹음 asks it, then 녹음 starts.
 */
public final class ScreenRecordActivity extends Activity {
    private static final int PERMISSIONS = 1, CONSENT = 2, MIC = 3;
    /** How it was opened: absent for 화면 녹화, or AUDIO. */
    static final String MODE = "mode", AUDIO = "audio";
    /** An instance is asking (its sheet, permission or consent prompt is up). */
    private static volatile boolean open;
    private boolean asked, requesting, owns, started;
    /** The start sheet while it shows (tests). */
    Dialog sheet;
    private ScreenRecordPlan.Sound chosen;
    /** 누른 위치 표시 as chosen on the sheet. */
    private boolean touches;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        asked = state != null && state.getBoolean("asked");
        requesting = state != null && state.getBoolean("requesting");
        // Recreated (a font or theme change) while a prompt was showing: its answer comes to this new window.
        if (asked || requesting) { open = true; owns = true; return; }
        // Another tap while the first one still asks: that one goes on, this one leaves.
        if (open) { close(); return; }
        String how = getIntent() == null ? null : getIntent().getStringExtra(MODE);
        if (AUDIO.equals(how)) { audio(); return; }
        if (ScreenRecordService.running()) { ScreenRecordService.stop(this); close(); return; }
        open = true; owns = true;
        startSheet();
    }

    // ── 화면 녹화 시작 ──────────────────────────────────────────────────

    /** Its three sounds, in the order shown, with their icons. */
    static final ScreenRecordPlan.Sound[] SOUNDS = {ScreenRecordPlan.Sound.NONE, ScreenRecordPlan.Sound.DEVICE, ScreenRecordPlan.Sound.BOTH};
    private static final int[] ICONS = {R.drawable.ic_sound_off, R.drawable.ic_sound, R.drawable.ic_mic};

    private void startSheet() {
        SharedPreferences settings = ((GyeotApp) getApplication()).store().settings;
        // The see-through window's theme is a bare one: the sheet is built in the phone's own (its switch as everywhere else).
        android.content.Context themed = new android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight);
        Ui ui = new Ui(themed);
        chosen = ScreenRecordService.sound(settings);
        Dialog d = new Dialog(themed);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(22), ui.dp(20), ui.dp(22), ui.dp(6));
        body.setBackground(ui.shape(Ui.CARD, 26));
        body.addView(ui.text("화면 녹화 시작", 18, 700, Ui.TEXT));
        TextView caution = ui.text("화면에 보이고 들리는 것이 모두 담겨요. 비밀번호·결제 정보·메시지가 나오지 않게 주의해 주세요.", 13, 450, Ui.MUTED);
        caution.setLineSpacing(ui.dp(2), 1); caution.setPadding(0, ui.dp(8), 0, 0);
        body.addView(caution);
        TextView heading = ui.text("녹음할 오디오", 14, 650, Ui.TEXT); heading.setPadding(0, ui.dp(16), 0, ui.dp(8));
        body.addView(heading);
        LinearLayout row = ui.row(); row.setGravity(Gravity.TOP);
        List<LinearLayout> options = new ArrayList<>();
        for (int i = 0; i < SOUNDS.length; i++) {
            ScreenRecordPlan.Sound sound = SOUNDS[i];
            LinearLayout option = ui.column(); option.setGravity(Gravity.CENTER_HORIZONTAL); option.setPadding(0, ui.dp(4), 0, ui.dp(4));
            ImageView icon = new ImageView(themed); icon.setImageResource(ICONS[i]);
            option.addView(icon, new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
            TextView name = ui.text(sound.label, 13, 600, Ui.TEXT); name.setPadding(0, ui.dp(6), 0, ui.dp(8)); name.setSingleLine(true);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            option.addView(name, new LinearLayout.LayoutParams(-1, -2));
            FrameLayout radio = new FrameLayout(themed);
            option.addView(radio, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            option.setContentDescription("녹음할 오디오 " + sound.label);
            option.setTag(sound);
            option.setOnClickListener(v -> { chosen = sound; for (LinearLayout o : options) mark(ui, o); });
            Ui.pressable(option, true);
            options.add(option);
            row.addView(option, new LinearLayout.LayoutParams(0, -2, 1));
        }
        for (LinearLayout o : options) mark(ui, o);
        body.addView(row);
        // 누른 위치 표시 (준: "녹화하기에 누른곳 표시하기 기능이 없어"): one line with a switch, as Samsung's. 폰 조작 tells where a view
        // was pressed (Android's own show_touches cannot be switched by an app), so it needs 폰 조작, and the circle is roughly there.
        boolean phone = PhoneService.connected();
        touches = phone && TouchMarks.wanted(settings);
        TextView touchNote = ui.text(phone ? "누른 곳은 대략적으로 보여요" : "폰 조작이 필요해요 · 켜기 ›", 12, phone ? 450 : 600, phone ? Ui.MUTED : Ui.ACCENT);
        Switch touch = ui.toggle("누른 위치 표시", touches, on -> { touches = on; touchNote.setVisibility(on ? View.VISIBLE : View.GONE); });
        touch.setShowText(false); touch.setTextSize(14); touch.setTypeface(Ui.weight(650)); touch.setPadding(0, ui.dp(10), 0, ui.dp(2)); touch.setEnabled(phone);
        body.addView(touch, new LinearLayout.LayoutParams(-1, -2));
        touchNote.setVisibility(!phone || touches ? View.VISIBLE : View.GONE);
        if (!phone) {
            touchNote.setPadding(0, ui.dp(2), 0, ui.dp(6)); Ui.pressable(touchNote, true);
            touchNote.setOnClickListener(v -> { d.cancel(); phoneControl(); });
        }
        body.addView(touchNote);
        LinearLayout buttons = ui.row(); buttons.setPadding(0, ui.dp(12), 0, 0);
        TextView cancel = ui.text("취소", 15, 650, Ui.ACCENT), go = ui.text("녹화 시작", 15, 700, Ui.ACCENT);
        for (TextView b : new TextView[]{cancel, go}) { b.setGravity(Gravity.CENTER); b.setMinHeight(ui.dp(44)); Ui.pressable(b, true); }
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        View divider = new View(themed); divider.setBackgroundColor(Ui.SEPARATOR);
        buttons.addView(divider, new LinearLayout.LayoutParams(ui.dp(1), ui.dp(18)));
        buttons.addView(go, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(buttons);
        cancel.setOnClickListener(v -> d.cancel());
        go.setOnClickListener(v -> { started = true; d.dismiss(); begin(chosen); });
        d.setContentView(body);
        d.setOnCancelListener(x -> { if (!started) { sheet = null; close(); } });
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screen = getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(screen - ui.dp(32), ui.dp(380)), -2);
        }
        sheet = d;
        d.show();
    }
    /** A choice drawn chosen (an accent ring with a dot) or not, its icon and name in the accent when chosen. */
    private void mark(Ui ui, LinearLayout option) {
        boolean on = option.getTag() == chosen;
        ImageView icon = (ImageView) option.getChildAt(0);
        icon.setImageTintList(ColorStateList.valueOf(on ? Ui.ACCENT : Ui.MUTED));
        ((TextView) option.getChildAt(1)).setTextColor(on ? Ui.ACCENT : Ui.TEXT);
        FrameLayout radio = (FrameLayout) option.getChildAt(2);
        GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setStroke(ui.dp(2), on ? Ui.ACCENT : Ui.MUTED);
        radio.setBackground(ring);
        radio.removeAllViews();
        if (on) {
            View dot = new View(option.getContext()); GradientDrawable fill = new GradientDrawable(); fill.setShape(GradientDrawable.OVAL); fill.setColor(Ui.ACCENT); dot.setBackground(fill);
            radio.addView(dot, new FrameLayout.LayoutParams(ui.dp(10), ui.dp(10), Gravity.CENTER));
        }
        option.setSelected(on);
    }
    /**
     * 녹화 시작: the sound is kept for next time (the 화면 녹화 settings sheet shows the same), a voice conversation ends (its
     * microphone is then free for 마이크+미디어), then the permissions it needs and Android's consent.
     */
    private void begin(ScreenRecordPlan.Sound sound) {
        sheet = null;
        SharedPreferences settings = ((GyeotApp) getApplication()).store().settings;
        settings.edit().putString(ScreenRecordService.SOUND, sound.name()).putBoolean(TouchMarks.SETTING, touches).apply();
        // Once: what Android says to an app writing its own 누른 위치 표시 (show_touches), noted for the phone's log.
        if (touches && !settings.getBoolean("showTouchesTried", false)) {
            settings.edit().putBoolean("showTouchesTried", true).apply();
            android.content.Context app = getApplicationContext();
            Thread t = new Thread(() -> DiagnosticsExport.record(app, "video", "누른 위치 표시 · Android의 show_touches 쓰기: " + TouchMarks.systemShowTouches(app)), "show-touches");
            t.setDaemon(true); t.start();
        }
        RecordControl.endVoice(this);
        List<String> missing = new ArrayList<>();
        if (sound.needsRecordPermission() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO);
        // Notifications show "화면 녹화 중" with 일시정지 · 중지, and "저장했어요"; asked once.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !settings.getBoolean("askedScreenRecordNotifications", false)) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS); settings.edit().putBoolean("askedScreenRecordNotifications", true).apply();
        }
        if (!missing.isEmpty()) { requesting = true; requestPermissions(missing.toArray(new String[0]), PERMISSIONS); return; }
        ask();
    }

    /**
     * 폰 조작 off: Android's accessibility settings in one tap, with 들퍼레 폰 조작 pointed out in the list (its own page is a
     * system-only screen).
     */
    private void phoneControl() {
        String entry = new ComponentName(this, PhoneService.class).flattenToString();
        Bundle args = new Bundle(); args.putString(":settings:fragment_args_key", entry);
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(":settings:fragment_args_key", entry)
                .putExtra(":settings:show_fragment_args", args).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException none) { Toast.makeText(this, "휴대폰 설정 → 접근성에서 ‘들퍼레 폰 조작’을 켜 주세요", Toast.LENGTH_LONG).show(); }
    }
    /** 녹음하기 from the home screen: the microphone first when it is not allowed yet, then 녹음 starts (in AssistantService). */
    private void audio() {
        if (((GyeotApp) getApplication()).store().recording) { RecordControl.act(this, RecordControl.AUDIO_STOP); close(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { RecordControl.startAudioNow(this); close(); return; }
        open = true; owns = true; requesting = true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC);
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putBoolean("asked", asked); state.putBoolean("requesting", requesting); }
    @Override protected void onDestroy() {
        if (sheet != null) { try { sheet.dismiss(); } catch (RuntimeException ignored) { /* gone */ } sheet = null; }
        if (owns && !isChangingConfigurations()) open = false;
        super.onDestroy();
    }
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
    /**
     * Android's consent; from Android 14 for the whole screen only, with no 앱 하나 step: the point of this recorder is the whole
     * (folded or unfolded) screen at its own size (준: "삼성녹화기에서 아쉬운 점은 화질 단 하나거든 폴드 전체화면을 찍으면 최고화질로
     * 설정해도 흐리게 녹화돼"). Our own controls stay out of it by timing (RecordControl's veil, RecordBubble).
     */
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
        if (result != RESULT_OK || data == null) { Toast.makeText(this, "화면 녹화를 시작하지 않았어요", Toast.LENGTH_SHORT).show(); close(); return; }
        // The screen's real size in its current orientation: the recording is made at this size, not scaled down. Its refresh
        // rate sets the frame rate (an even share of it).
        Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
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
        return quality.label + " 화질 · " + sound.label;
    }
}
