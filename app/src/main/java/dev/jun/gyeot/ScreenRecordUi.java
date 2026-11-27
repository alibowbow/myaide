package dev.jun.gyeot;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.ScreenRecordPlan;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 비서 → 녹음 → 화면 녹화: one row beside 녹음. Its line says what the next recording will be ("1440×3120 · 60fps · HEVC
 * 48Mbps"), or how long it has been recording; its end says the sound ("기기 소리 ›") and opens a small sheet with 화질 and 소리
 * as switchers at the end of their lines. A tap on the row starts or stops the recording.
 */
final class ScreenRecordUi {
    private final Activity activity;
    private final Ui ui;
    private final AppStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView line, value;
    /** The last plan worked out (null: no encoder takes the screen), and the screen and quality it was for. */
    private ScreenRecordPlan.Plan plan;
    private String planFor = "";

    ScreenRecordUi(Activity activity, Ui ui, AppStore store) { this.activity = activity; this.ui = ui; this.store = store; }

    View row() {
        value = ui.text(soundWord() + " ›", 14, 600, Ui.ACCENT); value.setPadding(ui.dp(10), ui.dp(6), 0, ui.dp(6));
        value.setOnClickListener(v -> sheet()); Ui.pressable(value, true);
        LinearLayout row = ui.listRow(R.drawable.ic_video, Ui.BLUE, "화면 녹화", ScreenRecordService.quality(store.settings).label + " 화질", value, this::toggle);
        line = ((Ui.ListRow) row).parts.subtitle();
        // One line; only the long "no encoder" sentence takes a second.
        line.setSingleLine(false); line.setMaxLines(2); line.setEllipsize(TextUtils.TruncateAt.END);
        refresh();
        describe(this::refresh);
        return row;
    }
    /** The page was redrawn without the row. */
    void forget() { line = null; value = null; }
    private void toggle() {
        if (ScreenRecordService.running()) ScreenRecordService.stop(activity);
        else activity.startActivity(new Intent(activity, ScreenRecordActivity.class));
    }
    private String soundWord() { ScreenRecordPlan.Sound sound = ScreenRecordService.sound(store.settings); return sound == ScreenRecordPlan.Sound.NONE ? "소리 없음" : sound.label; }
    private boolean known() { return planFor.equals(key()); }
    private String planLine() { return plan == null ? ScreenRecordPlan.summary(null, ScreenRecordPlan.Sound.NONE) : plan.label(); }
    /** The row's line (the time while recording, the plan otherwise) and its sound. */
    void refresh() {
        if (line == null) return;
        if (ScreenRecordService.running()) {
            line.setText("녹화 중 · " + AssistantService.durationText(System.currentTimeMillis() - ScreenRecordService.startedAt()) + " · 눌러서 멈추기");
            line.setTextColor(Ui.RED);
        } else {
            line.setText(known() ? planLine() : ScreenRecordService.quality(store.settings).label + " 화질");
            line.setTextColor(Ui.MUTED);
        }
        if (value != null) { value.setText(soundWord() + " ›"); value.setContentDescription("화면 녹화 설정, 소리 " + soundWord()); }
    }
    private Rect screen() { return activity.getWindowManager().getMaximumWindowMetrics().getBounds(); }
    /** The screen's refresh rate: the frame rate is an even share of it (60 of 120Hz, 45 of 90Hz). */
    private float refreshRate() { return ScreenRecorder.refreshRate(activity); }
    private String key() { Rect s = screen(); return s.width() + "x" + s.height() + ":" + ScreenRecordService.quality(store.settings); }
    /** Works out the plan for the screen and quality off the main thread (it asks the phone's encoders), then runs then. */
    private void describe(Runnable then) {
        String key = key();
        if (known()) { then.run(); return; }
        Rect s = screen();
        float refresh = refreshRate();
        ScreenRecordPlan.Quality quality = ScreenRecordService.quality(store.settings);
        new Thread(() -> {
            List<ScreenRecordPlan.Plan> plans = ScreenRecorder.plans(s.width(), s.height(), refresh, quality);
            ScreenRecordPlan.Plan best = plans.isEmpty() ? null : plans.get(0);
            main.post(() -> {
                if (!key.equals(key())) return;
                plan = best; planFor = key;
                then.run();
            });
        }, "screen-record-plan").start();
    }

    /** 화질 and 소리, each a switcher at the end of its line, with what the next recording will be under them. */
    void sheet() {
        Ui.Sheet sheet = ui.selectionSheet("화면 녹화", "다음 녹화부터 적용돼요");
        TextView preview = ui.label(ScreenRecordActivity.settingsLine(store.settings), 13, Ui.MUTED);
        preview.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), 0);
        Runnable update = () -> describe(() -> preview.setText(ScreenRecordPlan.summary(plan, ScreenRecordService.sound(store.settings))));
        sheet.body.addView(choice("화질", ScreenRecordPlan.Quality.labels(), ScreenRecordService.quality(store.settings).ordinal(), i -> {
            store.settings.edit().putString(ScreenRecordService.QUALITY, ScreenRecordPlan.Quality.values()[i].name()).apply(); update.run();
        }));
        sheet.body.addView(choice("소리", ScreenRecordPlan.Sound.labels(), ScreenRecordService.sound(store.settings).ordinal(), i -> {
            store.settings.edit().putString(ScreenRecordService.SOUND, ScreenRecordPlan.Sound.values()[i].name()).apply(); update.run();
        }));
        sheet.body.addView(preview);
        TextView about = ui.label("최고·고화질은 화면을 줄이지 않고 원래 크기 그대로, 보통은 폭 1080으로 " + ScreenRecordPlan.FOLDER + "에 저장해요. "
            + "휴대폰 설정의 화면 해상도가 높을수록 더 선명해요. 기기 소리는 그 앱이 허용할 때만 담겨요.", 12, Ui.MUTED);
        about.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0); about.setLineSpacing(ui.dp(3), 1);
        sheet.body.addView(about);
        TextView tile = ui.text("알림창에 ‘화면 녹화’ 타일 추가 ›", 13, 600, Ui.ACCENT); tile.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(6));
        tile.setOnClickListener(v -> addTile()); Ui.pressable(tile, true);
        sheet.body.addView(tile);
        // Notifications off: no "화면 녹화 중 · 중지" and no "저장했어요"; one tap opens the app's notification settings.
        NotificationManager notifications = activity.getSystemService(NotificationManager.class);
        if (notifications != null && !notifications.areNotificationsEnabled()) {
            TextView allow = ui.text("알림 켜기 › 녹화 중 표시와 저장 알림이 보여요", 13, 600, Ui.ACCENT); allow.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(6));
            allow.setOnClickListener(v -> {
                try { activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())); }
                catch (RuntimeException unavailable) { store.status("휴대폰 설정 → 앱 → " + activity.getString(R.string.app_name) + " → 알림에서 켜 주세요"); }
            });
            Ui.pressable(allow, true);
            sheet.body.addView(allow);
        }
        update.run();
        sheet.setOnDismissListener(d -> { refresh(); describe(this::refresh); });
        sheet.show();
    }
    private LinearLayout choice(String name, String[] labels, int selected, IntConsumer pick) {
        LinearLayout line = ui.row(); line.setPadding(ui.dp(4), ui.dp(6), 0, ui.dp(6));
        TextView label = ui.text(name, Ui.CALLOUT, 560, Ui.TEXT);
        line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(ui.switcher(labels, selected, pick));
        return line;
    }
    /** Android's own "add tile" prompt; the user confirms it. */
    private void addTile() {
        StatusBarManager bar = activity.getSystemService(StatusBarManager.class);
        String manual = "알림창을 내린 뒤 연필(편집) 버튼에서 ‘화면 녹화’ 타일을 끌어다 놓아 주세요.";
        if (bar == null) { store.status(manual); return; }
        try {
            bar.requestAddTileService(new ComponentName(activity, ScreenRecordTile.class), activity.getString(R.string.screen_record_tile_label),
                Icon.createWithResource(activity, R.drawable.ic_video), activity.getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) store.status("알림창에 ‘화면 녹화’ 타일을 추가했어요");
                    else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) store.status("‘화면 녹화’ 타일은 이미 알림창에 있어요");
                    else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) store.status(manual);
                });
        } catch (RuntimeException refused) { store.status(manual); }
    }
}
