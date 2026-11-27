package dev.jun.gyeot;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.WorkBook;
import dev.jun.gyeot.core.WorkSchedules;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 예약 작업 in 작업창: the list (next run, last result), one schedule's details (지금 실행, 켜기·끄기, 결과 보기, 수정, 지우기)
 * and the editor (request, repeat, time and what the repeat needs). A schedule is bound to the AI and model chosen when it
 * is saved, and to that connection: saving again binds it to the current one.
 */
final class WorkSchedulesUi {
    private static final String[] REPEATS = {WorkSchedules.ONCE, WorkSchedules.DAILY, WorkSchedules.WEEKDAYS, WorkSchedules.WEEKLY, WorkSchedules.MONTHLY, WorkSchedules.INTERVAL};
    private static final String[] REPEAT_NAMES = {"한 번", "매일", "평일", "매주", "매월", "간격"};
    private static final String[] WEEK = {"월", "화", "수", "목", "금", "토", "일"};
    /** 아침 브리핑: when it runs unless changed, and what it asks; the bracketed parts are for the user to fill in. */
    static final String BRIEFING_TIME = "07:30";
    static final String BRIEFING = "아침 브리핑을 해 줘. 짧게, 한눈에 보이게.\n"
        + "1. 오늘 날짜와 요일\n"
        + "2. 오늘 내 일정 (캘린더를 읽을 수 있으면)\n"
        + "3. 오늘 날씨와 옷차림 한 줄: [지역을 적어 주세요]\n"
        + "4. 새 소식 3~5개, 출처와 함께: [관심 주제를 적어 주세요]\n"
        + "대괄호가 그대로 남아 있는 항목은 건너뛰어 줘.";
    /** The time a new schedule's editor opens with, for the next edit(null, …) only. */
    private String presetTime;
    private final Activity activity;
    private final Ui ui;
    private final AppStore store;
    private final Consumer<String> openRoom;
    private Ui.Sheet list;

    WorkSchedulesUi(Activity activity, Ui ui, AppStore store, Consumer<String> openRoom) {
        this.activity = activity; this.ui = ui; this.store = store; this.openRoom = openRoom;
    }

    /** "예약 2" for the 작업창 header chip, "예약" when there are none. */
    String chipLabel() {
        int on = 0; JSONArray all = store.schedules();
        for (int i = 0; i < all.length(); i++) if (all.optJSONObject(i).optBoolean("enabled")) on++;
        return on == 0 ? "⏰ 예약" : "⏰ 예약 " + on;
    }

    // ── List ────────────────────────────────────────────────────────────────

    void show() {
        Ui.Sheet sheet = ui.selectionSheet("예약 작업", "정해진 시간이나 정한 일이 생기면 알아서 해요");
        list = sheet; sheet.setOnDismissListener(d -> { if (list == sheet) list = null; });
        fill(sheet);
        sheet.primary.setText("새 예약 만들기");
        sheet.primary.setOnClickListener(v -> edit(null, "", null, null));
        // 아침 브리핑: a new schedule already filled in (every day at BRIEFING_TIME), for the user to adjust and save.
        LinearLayout.LayoutParams above = new LinearLayout.LayoutParams(-1, -2); above.bottomMargin = ui.dp(8);
        sheet.footer.addView(ui.button("아침 브리핑 만들기", false, () -> { presetTime = BRIEFING_TIME; edit(null, BRIEFING, null, null); }), 0, above);
        sheet.show();
    }
    private void refresh() { if (list != null && list.isShowing()) fill(list); }
    private void fill(Ui.Sheet sheet) {
        sheet.body.removeAllViews();
        if (!store.schedulesReadable()) { ui.add(sheet.body, ui.banner("예약 기록을 읽지 못했어요. 원본 기록은 보존했어요.", ERROR), 12); sheet.primary.setEnabled(false); return; }
        if (!WorkScheduleAlarm.exact(activity)) {
            TextView exact = ui.banner("제시간에 실행하려면 ‘알람 및 리마인더’를 허용해 주세요 · 눌러서 열기", WARNING);
            exact.setOnClickListener(v -> openExactSettings()); Ui.pressable(exact, true);
            ui.add(sheet.body, exact, 12);
        }
        JSONArray all = store.schedules(); long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        if (all.length() == 0) {
            TextView none = ui.label("아직 예약이 없어요.\n예: “매일 아침 8시에 새로 올라온 채용 공고를 정리해 줘”", 15, MUTED);
            none.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(10)); none.setLineSpacing(ui.dp(4), 1); sheet.body.addView(none);
        } else {
            LinearLayout group = ui.group();
            for (int i = 0; i < all.length(); i++) {
                JSONObject s = all.optJSONObject(i); if (s == null) continue;
                String subtitle = WorkSchedules.describe(s) + (s.optBoolean("enabled") ? " · 다음 " + WorkSchedules.when(s.optLong("next"), now, zone) : " · 꺼짐")
                    + (s.optString("lastResult").isBlank() ? "" : " · 지난 실행 " + state(s.optString("lastResult")));
                boolean web = WorkSchedules.browser(s);
                ui.addRow(group, ui.listRow(web ? R.drawable.ic_browser : R.drawable.ic_history, s.optBoolean("enabled") ? ACCENT : MUTED, (web ? "🌐 " : "") + title(s.optString("goal"), 48), subtitle, ui.chevron(), () -> details(s.optString("id"))), true);
            }
            ui.add(sheet.body, group, 0);
        }
        sheet.body.addView(ui.footer("실행할 때마다 새 작업방이 열리고 끝나면 알림을 보내요. 지난번 결과를 AI에게 함께 알려줘서 ‘새로 올라온 것만’ 같은 요청도 돼요. "
            + "로그인·결제·전송·삭제처럼 내 확인이 필요한 일은 하지 않고 멈춰서 알려줘요. 휴대폰을 다시 켠 뒤에는 " + Brand.NAME + "을 한 번 열어야 예약이 다시 걸려요."));
        // 조건 알림 (사건 트리거): made by speaking to 퍼레, kept here as one compact group (TriggersUi).
        TriggersUi.fill(activity, ui, sheet.body, this::refresh);
    }

    // ── One schedule ────────────────────────────────────────────────────────

    private void details(String id) {
        JSONObject s = WorkSchedules.find(store.schedules(), id);
        if (s == null) { refresh(); return; }
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        Ui.Sheet sheet = ui.selectionSheet(title(s.optString("goal"), 32), WorkSchedules.describe(s));
        StringBuilder facts = new StringBuilder(s.optBoolean("enabled") ? "켜짐 · 다음 실행 " + WorkSchedules.when(s.optLong("next"), now, zone) : "꺼짐");
        facts.append("\n실행할 AI: ").append(ai(s.optJSONObject("selection")));
        if (WorkSchedules.browser(s)) facts.append("\nAI 브라우저로 진행").append(WorkSchedules.startPage(s).isEmpty() ? "" : " · 시작 페이지 " + WorkSchedules.startPage(s));
        if (!WorkSchedules.memory(s)) facts.append("\n기억은 쓰지 않아요");
        if (s.optLong("lastRun") > 0) facts.append("\n지난 실행: ").append(WorkSchedules.when(s.optLong("lastRun"), now, zone)).append(" · ").append(state(s.optString("lastResult")));
        if (!s.optString("lastNote").isBlank()) facts.append("\n").append(s.optString("lastNote"));
        if (s.optJSONObject("previous") != null) facts.append("\n지난 결과를 다음 실행 때 AI에게 함께 알려줘요.");
        TextView info = ui.label(facts.toString(), 14, MUTED); info.setLineSpacing(ui.dp(4), 1); info.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12)); sheet.body.addView(info);
        TextView goal = ui.text(s.optString("goal"), 15, 450, TEXT); goal.setBackground(ui.shape(FILL, 14)); goal.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)); goal.setLineSpacing(ui.dp(4), 1); goal.setTextIsSelectable(true);
        ui.add(sheet.body, goal, 14);
        boolean running = WorkSchedules.RUNNING.equals(s.optString("lastResult"));
        String room = s.optString("lastRoom");
        if (!room.isBlank() && WorkBook.find(store.works(), room) != null)
            ui.add(sheet.body, ui.button(running ? "진행 중인 작업 보기" : "지난 결과 보기", false, () -> { sheet.dismiss(); if (list != null) list.dismiss(); openRoom.accept(room); }), 8);
        Button now1 = ui.button(running ? "실행 중이에요" : "지금 실행", false, () -> { sheet.dismiss(); runNow(id); });
        now1.setEnabled(!running); if (running) now1.setAlpha(0.5f);
        ui.add(sheet.body, now1, 8);
        ui.add(sheet.body, ui.button(s.optBoolean("enabled") ? "끄기" : "켜기", false, () -> { sheet.dismiss(); enable(id, !s.optBoolean("enabled")); }), 8);
        Button change = ui.button("수정", false, () -> { sheet.dismiss(); edit(s, s.optString("goal"), null, null); });
        change.setEnabled(!running); if (running) change.setAlpha(0.5f);
        ui.add(sheet.body, change, 8);
        sheet.primary.setText("지우기"); sheet.primary.setBackground(ui.shape(ERROR, 14));
        sheet.primary.setOnClickListener(v -> { sheet.dismiss(); ui.confirm("이 예약을 지울까요? 이미 만들어진 작업방과 결과는 그대로 남아요.", "예약 지우기", () -> change(list -> WorkSchedules.remove(list, id), "예약을 지웠어요")).show(); });
        sheet.show();
    }
    private void runNow(String id) {
        try { activity.startForegroundService(new Intent(activity, AssistantService.class).setAction(AssistantService.WORK_SCHEDULE).putExtra("schedule", id)); store.status("예약 작업을 지금 실행해요"); }
        catch (Exception e) { store.error("예약 작업을 시작하지 못했어요. 앱을 다시 연 뒤 시도해 주세요."); }
    }
    private void enable(String id, boolean on) {
        long now = System.currentTimeMillis();
        change(list -> WorkSchedules.enable(list, id, on, now, ZoneId.systemDefault()), on ? "예약을 켰어요" : "예약을 껐어요");
    }
    private void change(java.util.function.Function<JSONArray, Object> action, String done) {
        try { store.changeSchedules(action); store.status(done); }
        catch (Exception e) { store.error(e.getMessage() == null ? "예약을 바꾸지 못했어요." : e.getMessage()); }
        refresh();
    }

    // ── Editor ──────────────────────────────────────────────────────────────

    /**
     * A new schedule (existing null), optionally with a request to start from and the selection and 기억 사용 of the room it
     * came from, or an existing one to change. A new one runs on the selection given, else the one 작업창 would use now; a
     * changed one keeps its own while that AI is still connected.
     */
    void edit(JSONObject existing, String goalText, JSONObject roomSelection, boolean memory) { edit(existing, goalText, roomSelection, null, memory); }
    /**
     * As above, with 기억 사용 on for a new one; startPage, when not null, makes it an AI browser task from that page (the
     * browser's own 예약 entry). Any schedule can be switched to the browser in the editor.
     */
    void edit(JSONObject existing, String goalText, JSONObject roomSelection, String startPage) { edit(existing, goalText, roomSelection, startPage, WorkSchedules.memory(existing)); }
    private void edit(JSONObject existing, String goalText, JSONObject roomSelection, String startPage, boolean memory) {
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        String opensAt = presetTime == null ? "09:00" : presetTime; presetTime = null;
        JSONObject draft = existing == null ? obj("repeat", WorkSchedules.DAILY, "time", opensAt) : copy(existing);
        if (startPage != null) put(draft, "browser", obj("url", startPage.startsWith("http://") || startPage.startsWith("https://") ? startPage : ""));
        if (!draft.has("date")) {
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            put(draft, "date", (Instant.ofEpochMilli(now).atZone(zone).getHour() < 9 ? today : today.plusDays(1)).toString());
        }
        JSONObject saved = existing == null ? null : existing.optJSONObject("selection");
        JSONObject selection = saved != null && store.ready(saved.optString("engine")) ? saved
            : roomSelection != null && store.ready(roomSelection.optString("engine")) ? roomSelection : new ScopedModels(store, "").snapshot();
        Ui.Sheet sheet = ui.selectionSheet(existing == null ? (startPage != null ? "브라우저 작업 예약" : "새 예약") : "예약 수정",
            startPage != null ? "정해진 시간에 AI 브라우저가 이 일을 해요" : "정해진 시간에 이 요청을 작업창에 맡겨요");
        EditText goal = ui.input("맡길 일 (예: 새로 올라온 채용 공고를 찾아 표로 정리해 줘)", 4, WorkSchedules.GOAL_LIMIT);
        goal.setText(goalText == null ? "" : goalText);
        ui.add(sheet.body, goal, 16);
        sheet.body.addView(heading("반복"));
        LinearLayout extra = ui.column();
        int selected = java.util.Arrays.asList(REPEATS).indexOf(draft.optString("repeat"));
        ui.add(sheet.body, ui.pills(REPEAT_NAMES, Math.max(0, selected), index -> { put(draft, "repeat", REPEATS[index]); fillExtra(extra, draft); }), 14);
        TextView time = picker("시간 · " + draft.optString("time"));
        time.setOnClickListener(v -> {
            String[] parts = draft.optString("time", "09:00").split(":");
            new TimePickerDialog(activity, (view, hour, minute) -> {
                put(draft, "time", String.format(Locale.ROOT, "%02d:%02d", hour, minute)); time.setText("시간 · " + draft.optString("time") + "  ▾");
            }, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), true).show();
        });
        ui.add(sheet.body, time, 12);
        sheet.body.addView(extra);
        fillExtra(extra, draft);
        // AI browser: the request is done in a background tab of the AI browser (with its sign-ins), from the start page.
        LinearLayout web = ui.column();
        EditText page = ui.input("시작 페이지 (예: https://example.com/notice, 비워도 돼요)", 1, 2000);
        page.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        page.setText(WorkSchedules.startPage(draft));
        web.addView(page);
        TextView webNote = ui.label("예약 시간에 AI 브라우저가 화면 없이 따로 탭을 열어 이 일을 해요. 브라우저에 로그인해 둔 사이트도 읽을 수 있지만, 로그인·결제·글쓰기·보내기는 하지 않고 멈춰서 알려줘요. 브라우저 AI는 브라우저에서 고른 모델을 써요.", 12, MUTED);
        webNote.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0); web.addView(webNote);
        web.setVisibility(WorkSchedules.browser(draft) ? View.VISIBLE : View.GONE);
        View browserSwitch = ui.toggle("AI 브라우저로 진행", WorkSchedules.browser(draft), on -> web.setVisibility(on ? View.VISIBLE : View.GONE));
        browserSwitch.setPadding(ui.dp(2), ui.dp(16), ui.dp(2), ui.dp(8));
        sheet.body.addView(browserSwitch); sheet.body.addView(web);
        // 기억 사용: what the app keeps about the user goes with each run, as in a room.
        boolean[] remember = {memory};
        View memorySwitch = ui.toggle("기억 사용", memory, on -> remember[0] = on);
        memorySwitch.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), 0); sheet.body.addView(memorySwitch);
        TextView memoryNote = ui.label("켜 두면 실행할 때마다 ‘나에 대해’, 고정 기억, 요청과 관련된 기억을 AI에게 함께 알려줘요.", 12, MUTED);
        memoryNote.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), 0); sheet.body.addView(memoryNote);
        String engine = selection.optString("engine");
        boolean ready = store.ready(engine);
        TextView model = ui.label("실행할 AI: " + ai(selection) + (ready ? "" : " · 연결되지 않았어요. 설정에서 연결해 주세요."), 13, ready ? MUTED : ERROR);
        model.setPadding(ui.dp(4), ui.dp(14), ui.dp(4), 0); sheet.body.addView(model);
        sheet.body.addView(ui.footer("모델은 지금 작업창에서 고른 것을 써요. 바꾸려면 작업창 위의 모델 칩에서 고른 뒤 예약을 다시 저장하세요. 실행할 때마다 그 AI의 사용량을 써요."));
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            try {
                if (!store.ready(engine)) throw new IllegalStateException(WorkBook.author(engine) + "를 먼저 연결해 주세요.");
                String request = goal.getText().toString().strip();
                boolean codex = store.codexImages.connected() || !store.openai.connected() || store.openai.usesPlan();
                if (!request.isEmpty() && dev.jun.gyeot.core.Drawing.chatArguments(request, null, new JSONArray(), codex) != null)
                    throw new IllegalArgumentException("그림 그리기 요청은 아직 예약할 수 없어요. 조사·정리 같은 할 일로 적어 주세요.");
                String connection = store.workConnectionFingerprint(engine);
                if (connection.isBlank()) throw new IllegalStateException("AI 연결을 확인하지 못했어요. 설정에서 다시 연결해 주세요.");
                JSONObject input = copy(draft); put(input, "goal", request); put(input, "enabled", true); put(input, "memory", remember[0]);
                input.remove("browser");
                if (web.getVisibility() == View.VISIBLE) put(input, "browser", obj("url", page.getText().toString().strip()));
                if (existing != null) put(input, "id", existing.optString("id"));
                JSONObject bind = obj("selection", ScopedModels.copy(selection), "connection", connection);
                long at = System.currentTimeMillis();
                JSONObject result = store.changeSchedules(all -> WorkSchedules.save(all, input, bind, at, ZoneId.systemDefault()));
                store.status("예약했어요 · " + WorkSchedules.describe(result) + " · 다음 " + WorkSchedules.when(result.optLong("next"), at, ZoneId.systemDefault())
                    + (WorkScheduleAlarm.exact(activity) ? "" : " · 알람 권한이 없으면 늦게 실행될 수 있어요"));
                sheet.dismiss();
                if (list != null && list.isShowing()) refresh(); else show();
            } catch (Exception e) { store.error(e.getMessage() == null ? "예약을 저장하지 못했어요." : e.getMessage()); }
        });
        sheet.show();
        if (sheet.getWindow() != null) sheet.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        if (goal.length() == 0) goal.requestFocus();
    }
    /** What the chosen repeat needs: a date, a weekday, a day of the month, or the gap. */
    private void fillExtra(LinearLayout extra, JSONObject draft) {
        extra.removeAllViews();
        switch (draft.optString("repeat")) {
            case WorkSchedules.ONCE -> {
                TextView date = picker("날짜 · " + dateLabel(draft.optString("date")));
                date.setOnClickListener(v -> {
                    LocalDate current = parse(draft.optString("date"));
                    DatePickerDialog dialog = new DatePickerDialog(activity, (view, year, month, day) -> {
                        put(draft, "date", LocalDate.of(year, month + 1, day).toString()); date.setText("날짜 · " + dateLabel(draft.optString("date")) + "  ▾");
                    }, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
                    dialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
                    dialog.show();
                });
                ui.add(extra, date, 4);
            }
            case WorkSchedules.WEEKLY -> {
                extra.addView(heading("요일"));
                int day = draft.optInt("day", LocalDate.now().getDayOfWeek().getValue());
                put(draft, "day", day);
                ui.add(extra, ui.pills(WEEK, day - 1, index -> put(draft, "day", index + 1)), 4);
            }
            case WorkSchedules.MONTHLY -> {
                extra.addView(heading("날짜"));
                String[] days = new String[31];
                for (int i = 0; i < 31; i++) days[i] = (i + 1) + "일" + (i >= 28 ? " (없는 달은 마지막 날)" : "");
                int day = draft.optInt("monthDay", LocalDate.now().getDayOfMonth());
                put(draft, "monthDay", day);
                ui.add(extra, ui.spinner(days, day - 1, index -> put(draft, "monthDay", index + 1)), 4);
            }
            case WorkSchedules.INTERVAL -> {
                extra.addView(heading("간격"));
                boolean hours = !WorkSchedules.DAY.equals(draft.optString("unit"));
                put(draft, "unit", hours ? WorkSchedules.HOUR : WorkSchedules.DAY);
                LinearLayout amount = ui.column();
                ui.add(extra, ui.pills(new String[]{"시간마다", "일마다"}, hours ? 0 : 1, index -> {
                    put(draft, "unit", index == 0 ? WorkSchedules.HOUR : WorkSchedules.DAY); put(draft, "every", index == 0 ? 3 : 1); fillEvery(amount, draft);
                }), 10);
                extra.addView(amount); fillEvery(amount, draft);
                TextView note = ui.label("위에서 고른 시간부터 시작해요.", 12, MUTED); note.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0); extra.addView(note);
            }
            default -> {}
        }
    }
    private void fillEvery(LinearLayout amount, JSONObject draft) {
        amount.removeAllViews();
        boolean hours = WorkSchedules.HOUR.equals(draft.optString("unit"));
        int limit = hours ? 24 : 30;
        String[] labels = new String[limit];
        for (int i = 0; i < limit; i++) labels[i] = (i + 1) + (hours ? "시간마다" : "일마다");
        int every = (int) Math.max(1, Math.min(limit, draft.optLong("every", hours ? 3 : 1)));
        put(draft, "every", every);
        amount.addView(ui.spinner(labels, every - 1, index -> put(draft, "every", index + 1)));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private TextView heading(String value) { TextView view = ui.text(value, 14, 650, TEXT); view.setPadding(ui.dp(2), ui.dp(4), 0, ui.dp(10)); return view; }
    private TextView picker(String value) {
        TextView view = ui.text(value + "  ▾", 16, 560, TEXT); view.setBackground(ui.shape(FILL, 14)); view.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        view.setMinHeight(ui.dp(50)); Ui.pressable(view, true); return view;
    }
    private void openExactSettings() {
        try { activity.startActivity(WorkScheduleAlarm.exactSettings(activity)); }
        catch (Exception e) { store.error("설정을 열지 못했어요. 설정 → 앱 → " + Brand.NAME + " → 알람 및 리마인더에서 허용해 주세요."); }
    }
    private String ai(JSONObject selection) {
        if (selection == null) return "알 수 없음";
        String engine = selection.optString("engine"); JSONObject models = selection.optJSONObject("models");
        String model = models == null ? "" : models.optString(engine);
        return WorkBook.author(engine) + (model.isBlank() ? "" : " · " + model);
    }
    private static String state(String value) {
        return switch (value) {
            case WorkSchedules.RUNNING -> "실행 중"; case WorkSchedules.DONE -> "완료"; case WorkSchedules.ERROR -> "실패";
            case WorkSchedules.CANCELLED -> "중지됨"; case WorkSchedules.SKIPPED -> "건너뜀"; case WorkSchedules.INTERRUPTED -> "중간에 끊김"; default -> value;
        };
    }
    private static String title(String goal, int limit) {
        String line = goal == null ? "" : goal.strip().split("\\R", 2)[0];
        return line.length() <= limit ? line : line.substring(0, limit - 1) + "…";
    }
    private static LocalDate parse(String value) { try { return LocalDate.parse(value); } catch (Exception e) { return LocalDate.now(); } }
    private static String dateLabel(String value) {
        LocalDate date = parse(value);
        return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일 (" + WEEK[date.getDayOfWeek().getValue() - 1] + ")";
    }
}
