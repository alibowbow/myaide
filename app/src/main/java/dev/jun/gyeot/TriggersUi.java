package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.widget.LinearLayout;
import dev.jun.gyeot.core.Triggers;
import java.time.ZoneId;
import java.util.List;
import static dev.jun.gyeot.Ui.*;

/**
 * 조건 알림 in the 예약 작업 sheet (WorkSchedulesUi), compact: one group of rows "차에 타면 → 네이버 지도 열기" with 매번 or
 * 한 번, a switch for on and off, and a tap that offers what it needs (a permission, the device) and 지우기. They are made
 * by speaking to 퍼레 ("집에 도착하면 알려 줘"); no screen of their own.
 */
final class TriggersUi {
    private TriggersUi() {}
    static void fill(Activity a, Ui ui, LinearLayout body, Runnable refresh) {
        List<Triggers.Trigger> all = TriggerStore.list(a);
        body.addView(ui.sectionHeader("조건 알림"));
        if (all.isEmpty()) { body.addView(ui.footer("퍼레에게 ‘집에 도착하면 알려 줘’, ‘차 타면 매번 지도 켜 줘’, ‘충전 다 되면 알려 줘’처럼 말하면 여기에 생겨요.")); return; }
        LinearLayout group = ui.group();
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        for (Triggers.Trigger t : all) {
            String[] problem = TriggerWatch.problem(a, t);
            String sub = Triggers.state(t, now, zone) + (problem[0].isEmpty() ? "" : " · " + problem[0]);
            android.widget.Switch on = ui.toggle("", t.active(), to -> { TriggerStore.change(a, list -> Triggers.switched(list, t.id(), to)); TriggerWatch.start(a); refresh.run(); });
            on.setPadding(0, 0, 0, 0); on.setContentDescription(Triggers.title(t) + (t.active() ? " 켜짐" : " 꺼짐"));
            LinearLayout row = ui.listRow(icon(t), problem[0].isEmpty() && t.active() ? ACCENT : problem[0].isEmpty() ? MUTED : WARNING, Triggers.title(t), sub, on, () -> details(a, ui, t, problem, refresh));
            ui.addRow(group, row, true);
        }
        ui.add(body, group, 0);
        body.addView(ui.footer("그 일이 생기면 알림으로 알려 주고, 홈 화면이 앞에 있으면 퍼레가 한마디 해요. 문자·카톡은 써 두기만 하고 보내기는 직접 눌러요."));
    }
    private static int icon(Triggers.Trigger t) {
        return switch (t.when().kind()) {
            case Triggers.PLACE -> R.drawable.ic_navigation; case Triggers.BLUETOOTH -> R.drawable.ic_plug; case Triggers.POWER -> R.drawable.ic_bolt;
            case Triggers.PRICE -> R.drawable.ic_wave; default -> R.drawable.ic_bell;
        };
    }
    /** A tap: what it needs (one tap to allow or pick) and 지우기, in Android's short choice list. */
    private static void details(Activity a, Ui ui, Triggers.Trigger t, String[] problem, Runnable refresh) {
        String fix = problem[1];
        String[] labels = fix.isEmpty() ? new String[]{"지우기"} : new String[]{BuddyMisses.fixLabel(fix), "지우기"};
        ui.choices(Triggers.title(t), Triggers.said(t), labels, -1, which -> {
            if (!fix.isEmpty() && which == 0) {
                Intent go = BuddyMisses.fixIntent(a, fix);
                if (go != null) try { a.startActivity(go); } catch (RuntimeException none) { /* nothing to open */ }
                return;
            }
            ui.confirm("이 조건 알림을 지울까요?", "지우기", () -> { TriggerStore.change(a, list -> Triggers.remove(list, t.id())); TriggerWatch.start(a); refresh.run(); }).show();
        }).show();
    }
}
