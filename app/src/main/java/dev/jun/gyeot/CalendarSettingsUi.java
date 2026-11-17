package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * 설정 → 캘린더: the 대화에서 캘린더 쓰기 switch; while it is on, the calendar
 * permission with a badge to allow it, the calendars the conversations read (every visible one, by its own name) with the
 * one new events go to, and how it works in one fold.
 */
final class CalendarSettingsUi {
    private final Activity activity; private final Ui ui; private final AppStore store; private final Runnable refresh;
    CalendarSettingsUi(Activity activity, Ui ui, AppStore store, Runnable refresh) { this.activity = activity; this.ui = ui; this.store = store; this.refresh = refresh; }

    /** The settings list's row subtitle; it reads no calendar, so the list draws at once. */
    static String summary(Activity activity, AppStore store) {
        if (!PhoneCalendar.on(store)) return "꺼짐";
        if (!PhoneCalendar.readable(activity)) return "권한이 필요해요";
        return PhoneCalendar.writable(activity) ? "켜짐 · 일정 읽기와 넣기" : "켜짐 · 읽기만 허용됨";
    }

    /** The page's views, top to bottom; with the switch off, only the switch and a line on what turning it on shows. */
    View page() {
        LinearLayout page = ui.column();
        LinearLayout use = ui.group();
        ui.addRow(use, ui.switchRow("대화에서 캘린더 쓰기", "음성 비서·메인 대화·작업창이 일정을 읽고 넣어요", PhoneCalendar.on(store), value -> {
            store.settings.edit().putBoolean(PhoneCalendar.SETTING, value).apply();
            store.status(value ? "캘린더를 켰어요 · 음성은 다음 대화부터 써요" : "캘린더를 껐어요"); refresh.run();
        }), false);
        page.addView(use);
        if (!PhoneCalendar.on(store)) { page.addView(ui.footer("켜면 권한과 캘린더가 여기 보여요.")); return page; }
        boolean read = PhoneCalendar.readable(activity), write = PhoneCalendar.writable(activity);
        page.addView(ui.sectionHeader("권한"));
        LinearLayout permission = ui.group();
        LinearLayout allowed = ui.listRow(R.drawable.ic_lock, write ? Ui.GREEN : Ui.ORANGE, "캘린더 권한", write ? "허용됨 · 읽기와 일정 넣기" : read ? "읽기만 허용됨 · 일정을 넣으려면 다시 허용해 주세요" : "허용 안 됨",
            write ? null : ui.badge("허용하기", Ui.ORANGE), write ? null : this::ask);
        // The read-only state's line is long: it wraps beside the badge instead of being cut.
        TextView state = ((Ui.ListRow) allowed).parts.subtitle(); if (state != null) { state.setSingleLine(false); state.setMaxLines(2); }
        ui.addRow(permission, allowed, true);
        page.addView(permission);
        if (!write) page.addView(ui.footer("‘허용하기’를 누르면 Android가 물어요. 한 번 거절했다면 앱 정보가 열려요 → 권한 → 캘린더에서 켜 주세요."));
        page.addView(ui.sectionHeader("보이는 캘린더"));
        LinearLayout list = ui.group();
        if (!read) ui.addRow(list, ui.listRow(0, 0, "권한을 허용하면 보여요", "", null, null), false);
        else {
            ui.addRow(list, ui.listRow(0, 0, "불러오는 중…", "", null, null), false);
            // The provider may take a moment to start: the list is read off the main thread and drawn when it arrives.
            Thread loader = new Thread(() -> {
                List<PhoneCalendar.Calendar> calendars;
                try { calendars = PhoneCalendar.calendars(activity); } catch (RuntimeException unreadable) { calendars = List.of(); }
                List<PhoneCalendar.Calendar> found = calendars;
                list.post(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) calendars(list, found); });
            }, "calendar-settings");
            loader.setDaemon(true); loader.start();
        }
        page.addView(list);
        page.addView(ui.footer("눌러서 새 일정을 넣을 캘린더를 골라요. 캘린더 앱에서 보이게 한 캘린더만 읽어요."));
        page.addView(ui.details("어떻게 동작하나요", "휴대폰에 로그인한 구글 계정이 동기화하는 캘린더도 그대로 보여요. 따로 로그인하지 않고, 인터넷이 없어도 읽어요.\n"
            + "새 일정은 부탁할 때만 넣어요. 메인 대화는 카드의 ‘캘린더에 추가’를 눌러야 들어가요. 말한 캘린더나 ‘새 일정’ 표시된 캘린더에 넣어요.\n"
            + "음성 비서와 메인 대화에서는 방금 넣은 일정만 10분 안에 되돌릴 수 있어요. 다른 일정은 고치거나 지우지 않아요.\n"
            + "일정 내용은 기록(로그)에 남기지 않아요."));
        return page;
    }
    /**
     * Each visible calendar with its account; the one new events go to carries 새 일정, the other writable ones a chevron. A
     * tap on one of those picks it for new events (saved by its id); until one is picked, the provider's primary calendar
     * is used.
     */
    private void calendars(LinearLayout list, List<PhoneCalendar.Calendar> calendars) {
        list.removeAllViews();
        PhoneCalendar.Calendar target = PhoneCalendar.main(calendars, PhoneCalendar.picked(store.settings));
        for (PhoneCalendar.Calendar c : calendars) {
            String kind = "com.google".equals(c.type()) ? "구글" : c.type().isBlank() ? "" : "기기";
            String sub = (c.account().isBlank() ? "" : c.account()) + (kind.isEmpty() ? "" : (c.account().isBlank() ? "" : " · ") + kind) + (c.writable() ? "" : " · 읽기만");
            Runnable pick = !c.writable() || c == target ? null : () -> {
                store.settings.edit().putLong(PhoneCalendar.PICKED, c.id()).apply();
                store.status("새 일정은 ‘" + c.name() + "’ 캘린더에 넣어요"); calendars(list, calendars);
            };
            ui.addRow(list, ui.listRow(R.drawable.ic_calendar, Ui.BLUE, c.name(), sub, c == target ? ui.badge("새 일정", Ui.BLUE) : pick == null ? null : ui.chevron(), pick), true);
        }
        if (calendars.isEmpty()) {
            LinearLayout none = ui.listRow(0, 0, "보이는 캘린더가 없어요", "휴대폰 캘린더 앱에서 표시할 캘린더와 구글 계정 동기화를 확인해 주세요.", null, null);
            TextView why = ((Ui.ListRow) none).parts.subtitle(); if (why != null) { why.setSingleLine(false); why.setMaxLines(2); }
            ui.addRow(list, none, false);
        }
    }
    /** The permission dialog the first time; after a refusal Android shows none, so its app settings open instead. */
    private void ask() {
        boolean asked = store.settings.getBoolean(PhoneCalendar.ASKED, false);
        if (asked && !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.READ_CALENDAR) && !PhoneCalendar.readable(activity)) {
            try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName()))); return; }
            catch (RuntimeException none) { /* Ask again below. */ }
        }
        store.settings.edit().putBoolean(PhoneCalendar.ASKED, true).apply();
        activity.requestPermissions(PhoneCalendar.PERMISSIONS, PhoneCalendar.SETTINGS_REQUEST);
    }
    /** After the permission dialog: the page shows the new state. */
    void permissionResult() {
        store.status(PhoneCalendar.writable(activity) ? "캘린더 권한을 허용했어요" : "캘린더 권한이 없어요 · 대화에서 캘린더를 쓰려면 허용해 주세요");
        refresh.run();
    }
}
