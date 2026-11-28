package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.provider.CalendarContract;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.jun.gyeot.core.KoreanHolidays;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The home screen's date, as a month (준: "캘린더가 너무 작게 떠", "일욜이랑 공휴일이 붉은색으로 표시되지 않아"; before that "가벼운
 * 구글캘린더", "갤럭시 폰에서 위젯으로 있는 구글캘린더 같이 가벼운 버전"). Google Calendar's own widget, hosted here until now, could
 * not be made larger than its card or have its colors changed, so the month is drawn here as Galaxy's calendar widget draws it:
 * 일요일 and 공휴일 in red, 토요일 in blue, today in a filled circle, each day's holiday name and its first events in the cell.
 * The events are the phone's calendar (the user's Google Calendar, synced by the phone's account: PhoneCalendar), read off the
 * main thread for the month shown; a tap on a day lists them under the month, a tap on one opens it in the calendar app, + makes
 * a new one there. Swiped sideways, or ‹ ›, the month changes. Without the calendar permission the month still shows (fixed
 * holidays only) with one line to allow it. Main thread.
 */
final class MonthCard {
    /** The calendar permission asked from the card (HomeActivity hands the answer back: reload). */
    static final int PERMISSION = 7553;
    /** One event in a day: its id (to open it), title, times, whether all day, its calendar's color and name. */
    record Item(long id, String title, long begin, long end, boolean allDay, int color, String calendar) {}
    /** A day as the card shows it: its events, its holiday's name (or a 기념일's), whether it is off. */
    static final class Day { final List<Item> items = new ArrayList<>(); String holiday = ""; boolean off; }

    private final Activity activity; private final Ui ui; private final Executor io;
    private LocalDate today; private YearMonth month; private LocalDate selected;
    private Map<LocalDate, Day> days = new HashMap<>();
    private int loading;
    /** The card while it shows (tests look at it). */
    Dialog shown;
    private TextView title; private MonthView grid; private LinearLayout list, permission;

    MonthCard(Activity activity, Ui ui, Executor io) { this.activity = activity; this.ui = ui; this.io = io; }

    void show() { show(LocalDate.now()); }
    /** The card at this month, today marked and chosen. */
    void show(LocalDate now) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        close();
        today = now; month = YearMonth.from(now); selected = now;
        float density = activity.getResources().getDisplayMetrics().density;
        int screenW = Math.round(activity.getResources().getDisplayMetrics().widthPixels / density), screenH = Math.round(activity.getResources().getDisplayMetrics().heightPixels / density);
        int widthDp = Math.min(500, screenW - 24);

        LinearLayout card = ui.column(); card.setBackground(ui.shape(Ui.CARD, 26)); card.setClipToOutline(true); card.setElevation(ui.dp(12));
        card.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(12));
        // ‹ 2026년 11월 › (the title back to today), then + and the calendar app at the end
        LinearLayout head = ui.row(); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(icon(R.drawable.ic_nav_back, "이전 달", () -> move(-1)), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        title = ui.text("", 18, 700, Ui.TEXT); title.setGravity(Gravity.CENTER); title.setOnClickListener(v -> { month = YearMonth.from(today); selected = today; redraw(); load(); });
        title.setContentDescription("오늘로"); Ui.pressable(title, true);
        title.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        head.addView(title, new LinearLayout.LayoutParams(-2, ui.dp(40)));
        head.addView(icon(R.drawable.ic_nav_forward, "다음 달", () -> move(1)), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        head.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        head.addView(icon(R.drawable.ic_add, "이 날 새 일정", this::create), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        head.addView(icon(R.drawable.ic_calendar, "캘린더 앱에서 열기", this::openApp), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        card.addView(head);
        // 일 월 화 수 목 금 토: 일 in red, 토 in blue.
        LinearLayout week = ui.row(); week.setPadding(0, ui.dp(4), 0, ui.dp(2));
        String[] names = {"일", "월", "화", "수", "목", "금", "토"};
        for (int i = 0; i < 7; i++) {
            TextView w = ui.text(names[i], 12, 600, i == 0 ? Ui.RED : i == 6 ? Ui.BLUE : Ui.MUTED); w.setGravity(Gravity.CENTER);
            week.addView(w, new LinearLayout.LayoutParams(0, -2, 1));
        }
        card.addView(week);
        grid = new MonthView(); card.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        permission = ui.row(); permission.setVisibility(View.GONE);
        card.addView(permission, new LinearLayout.LayoutParams(-1, -2));
        list = ui.column(); list.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), 0);
        ScrollView scroll = new ScrollView(activity); scroll.setVerticalScrollBarEnabled(false); scroll.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); card.addView(scroll, lp);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(card, new ViewGroup.LayoutParams(ui.dp(widthDp), -2));
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ui.dp(widthDp), -2); w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); w.setDimAmount(0.32f);
        }
        // The day's list scrolls in what is left of 92% of the screen's height.
        scroll.post(() -> { int most = ui.dp(Math.round(screenH * .92f)) - (card.getHeight() - scroll.getHeight()); if (scroll.getHeight() > most && most > 0) { scroll.getLayoutParams().height = most; scroll.requestLayout(); } });
        dialog.setOnDismissListener(d -> { if (shown == dialog) shown = null; });
        shown = dialog;
        redraw();
        dialog.show();
        load();
    }
    void close() { if (shown != null) { try { shown.dismiss(); } catch (RuntimeException ignored) {} shown = null; } }
    /** The permission was answered: the events of the month shown. */
    void reload() { if (shown != null) load(); }

    private ImageView icon(int res, String name, Runnable action) {
        ImageView v = ui.quietIcon(res, name, action); v.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT)); return v;
    }
    private void move(int by) {
        month = month.plusMonths(by);
        selected = month.equals(YearMonth.from(today)) ? today : month.atDay(1);
        redraw(); load();
    }
    private boolean readable() {
        try { return activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED; } catch (RuntimeException e) { return false; }
    }
    /** The 6 weeks shown, Sunday first: the first cell and the day after the last. */
    static LocalDate firstCell(YearMonth month) { LocalDate first = month.atDay(1); return first.minusDays(first.getDayOfWeek().getValue() % 7); }
    static int weeks(YearMonth month) { return (int) Math.ceil((java.time.temporal.ChronoUnit.DAYS.between(firstCell(month), month.atEndOfMonth()) + 1) / 7.0); }

    /** The month's events from the phone's calendar, off the main thread; the answer only if the month is still the one shown. */
    private void load() {
        int ask = ++loading; YearMonth want = month;
        boolean may = readable();
        permission.removeAllViews(); permission.setVisibility(may ? View.GONE : View.VISIBLE);
        if (!may) {
            TextView ask2 = ui.pill("일정도 보려면 캘린더 허용", R.drawable.ic_calendar, true, () -> {
                try { activity.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, PERMISSION); } catch (RuntimeException ignored) {}
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.topMargin = ui.dp(6); permission.setGravity(Gravity.CENTER_HORIZONTAL); permission.addView(ask2, p);
            fill(want, new HashMap<>()); return;
        }
        LocalDate from = firstCell(want), to = from.plusDays(weeks(want) * 7L);
        android.content.Context app = activity.getApplicationContext();
        try {
            io.execute(() -> {
                Map<LocalDate, Day> found = read(app, from, to);
                activity.runOnUiThread(() -> { if (ask == loading && want.equals(month)) fill(want, found); });
            });
        } catch (RuntimeException stopped) { fill(want, new HashMap<>()); }
    }
    private static final String[] COLUMNS = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DESCRIPTION,
        CalendarContract.Instances.STATUS};
    /** Every visible calendar's events in [from, to), by day (an event over several days on each). */
    static Map<LocalDate, Day> read(android.content.Context c, LocalDate from, LocalDate to) {
        Map<LocalDate, Day> out = new HashMap<>();
        ZoneId zone = ZoneId.systemDefault();
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, from.atStartOfDay(zone).toInstant().toEpochMilli() - 86_400_000L); ContentUris.appendId(uri, to.atStartOfDay(zone).toInstant().toEpochMilli() + 86_400_000L);
        try (Cursor q = c.getContentResolver().query(uri.build(), COLUMNS, CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (q != null && q.moveToNext()) {
                if (q.getInt(8) == CalendarContract.Instances.STATUS_CANCELED) continue;
                Item item = new Item(q.getLong(0), q.isNull(1) ? "" : q.getString(1), q.getLong(2), q.getLong(3), q.getInt(4) == 1, q.isNull(5) ? Ui.ACCENT : q.getInt(5) | 0xff000000, q.isNull(6) ? "" : q.getString(6));
                add(out, item, q.isNull(7) ? "" : q.getString(7), from, to, zone);
            }
        } catch (RuntimeException unreadable) { /* the month without them */ }
        return out;
    }
    /** An event onto each day it covers (all-day ones are UTC midnights); a holiday calendar's events mark the day instead. */
    static void add(Map<LocalDate, Day> out, Item item, String description, LocalDate from, LocalDate to, ZoneId zone) {
        ZoneId at = item.allDay() ? ZoneOffset.UTC : zone;
        LocalDate start = Instant.ofEpochMilli(item.begin()).atZone(at).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(Math.max(item.begin(), item.end() - 1)).atZone(at).toLocalDate();
        boolean holiday = KoreanHolidays.holidayCalendar(item.calendar());
        for (LocalDate d = start.isBefore(from) ? from : start; !d.isAfter(end) && d.isBefore(to); d = d.plusDays(1)) {
            Day day = out.computeIfAbsent(d, k -> new Day());
            if (!holiday) { day.items.add(item); continue; }
            boolean off = KoreanHolidays.dayOff(item.title(), description);
            // A day off's name before a 기념일's (one day can have both).
            if (off && !day.off) { day.off = true; day.holiday = item.title(); }
            else if (day.holiday.isEmpty()) day.holiday = item.title();
        }
    }
    /** The month's days (the fixed holidays added), drawn. */
    void fill(YearMonth which, Map<LocalDate, Day> found) {
        if (!which.equals(month)) return;
        LocalDate first = firstCell(which);
        for (int i = 0; i < weeks(which) * 7; i++) {
            LocalDate d = first.plusDays(i); String fixed = KoreanHolidays.fixed(d);
            if (fixed == null) continue;
            Day day = found.computeIfAbsent(d, k -> new Day());
            // The calendar's own name for it stays (it may say more, 대체공휴일); a 기념일's gives way.
            if (!day.off) { day.off = true; day.holiday = fixed; }
        }
        days = found; redraw();
    }
    /** Red for 일요일 and a day off, blue for 토요일, else the text color (tests read it). */
    int ink(LocalDate d) {
        Day day = days.get(d);
        if (d.getDayOfWeek() == DayOfWeek.SUNDAY || day != null && day.off) return Ui.RED;
        return d.getDayOfWeek() == DayOfWeek.SATURDAY ? Ui.BLUE : Ui.TEXT;
    }
    Day day(LocalDate d) { return days.get(d); }

    private void redraw() {
        if (title == null) return;
        title.setText(month.getYear() + "년 " + month.getMonthValue() + "월");
        grid.requestLayout(); grid.invalidate();
        grid.setContentDescription(title.getText() + " 달력 · 날짜를 누르면 그날 일정이 아래에 보여요");
        drawList();
    }
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);
    /** The chosen day under the month: its name, its holiday, its events (a tap opens one), or 일정 없음. */
    private void drawList() {
        list.removeAllViews();
        Day day = days.get(selected);
        String name = selected.getMonthValue() + "월 " + selected.getDayOfMonth() + "일 " + selected.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.KOREAN);
        TextView head = ui.text(name + (day != null && !day.holiday.isEmpty() ? " · " + day.holiday : ""), 14, 650, ink(selected));
        head.setPadding(ui.dp(2), 0, 0, ui.dp(4)); list.addView(head);
        if (day == null || day.items.isEmpty()) {
            TextView none = ui.text("일정 없음", 13, 450, Ui.MUTED); none.setPadding(ui.dp(2), ui.dp(2), 0, ui.dp(4)); list.addView(none); return;
        }
        ZoneId zone = ZoneId.systemDefault();
        for (Item it : day.items) {
            LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(ui.dp(40)); row.setPadding(ui.dp(2), ui.dp(4), ui.dp(2), ui.dp(4));
            View bar = new View(activity); bar.setBackground(ui.shape(it.color(), 2));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ui.dp(4), ui.dp(28)); bp.rightMargin = ui.dp(10); row.addView(bar, bp);
            String when = it.allDay() ? "하루 종일" : TIME.format(Instant.ofEpochMilli(it.begin()).atZone(zone));
            TextView t = ui.text(when, 12, 500, Ui.MUTED); row.addView(t, new LinearLayout.LayoutParams(ui.dp(72), -2));
            TextView what = ui.text(it.title().isBlank() ? "(제목 없음)" : it.title(), 14, 520, Ui.TEXT); what.setSingleLine(true); what.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(what, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> openEvent(it)); Ui.pressable(row, true); row.setContentDescription(when + " " + it.title() + ", 캘린더에서 열기");
            list.addView(row);
        }
    }
    private void openEvent(Item it) {
        Intent view = new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it.id()))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.begin()).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.end()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { activity.startActivity(view); close(); } catch (RuntimeException none) { openApp(); }
    }
    /** The calendar app at the chosen day. */
    private void openApp() {
        long at = selected.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(String.valueOf(at)).build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); close(); }
        catch (RuntimeException none) { /* no calendar app */ }
    }
    /** A new event on the chosen day, in the calendar app's own screen (the user saves it there). */
    private void create() {
        long begin = selected.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try { activity.startActivity(PhoneCalendar.insert("", begin, begin + 3_600_000L, false, "", "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); close(); }
        catch (RuntimeException none) { openApp(); }
    }

    /**
     * The weeks of the month, drawn (42 views would be slow to lay out each month): each day's number (today in a filled circle,
     * the chosen one ringed, the other months' faint), its holiday's name in red (a 기념일's quiet), then its first two events as
     * small colored labels and "+N". A tap chooses a day; a sideways swipe changes the month.
     */
    final class MonthView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint number = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final GestureDetector gestures;
        MonthView() {
            super(activity);
            float sp = activity.getResources().getDisplayMetrics().scaledDensity;
            number.setTextAlign(Paint.Align.CENTER); number.setTextSize(14 * sp);
            small.setTextSize(9.5f * sp);
            gestures = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapUp(MotionEvent e) { tap(e.getX(), e.getY()); return true; }
                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (a == null || Math.abs(vx) < Math.abs(vy) * 1.2f || Math.abs(b.getX() - a.getX()) < ui.dp(40)) return false;
                    move(vx < 0 ? 1 : -1); return true;
                }
            });
        }
        private float cellH() { return ui.dp(62); }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Math.round(cellH() * weeks(month))); }
        @Override public boolean onTouchEvent(MotionEvent e) { return gestures.onTouchEvent(e) || super.onTouchEvent(e); }
        private void tap(float x, float y) {
            int col = Math.min(6, Math.max(0, (int) (x / (getWidth() / 7f)))), row = (int) (y / cellH());
            LocalDate d = firstCell(month).plusDays(row * 7L + col);
            if (!YearMonth.from(d).equals(month)) { month = YearMonth.from(d); selected = d; redraw(); load(); return; }
            selected = d; invalidate(); drawList();
        }
        @Override protected void onDraw(Canvas c) {
            float cw = getWidth() / 7f, ch = cellH(); LocalDate first = firstCell(month);
            for (int i = 0; i < weeks(month) * 7; i++) {
                LocalDate d = first.plusDays(i); boolean inMonth = YearMonth.from(d).equals(month);
                float x = (i % 7) * cw, y = (i / 7) * ch, cx = x + cw / 2, ny = y + ui.dp(15);
                int alpha = inMonth ? 255 : 90;
                if (d.equals(today)) { fill.setColor(Ui.ACCENT); c.drawCircle(cx, ny, ui.dp(12), fill); }
                else if (d.equals(selected)) { fill.setColor(Ui.ACCENT_SOFT | 0x22000000); c.drawCircle(cx, ny, ui.dp(12), fill); }
                number.setColor(d.equals(today) ? Ui.ON_ACCENT : ink(d)); number.setAlpha(alpha);
                number.setFakeBoldText(d.equals(today) || d.equals(selected));
                c.drawText(String.valueOf(d.getDayOfMonth()), cx, ny - (number.descent() + number.ascent()) / 2, number);
                Day day = days.get(d); float line = y + ui.dp(30);
                if (day == null) continue;
                if (!day.holiday.isEmpty()) {
                    small.setColor(day.off ? Ui.RED : Ui.MUTED); small.setAlpha(alpha); small.setTextAlign(Paint.Align.CENTER);
                    c.drawText(TextUtils.ellipsize(day.holiday, small, cw - ui.dp(4), TextUtils.TruncateAt.END).toString(), cx, line + ui.dp(8), small);
                    line += ui.dp(11);
                }
                int room = day.holiday.isEmpty() ? 2 : 1;
                for (int k = 0; k < Math.min(room, day.items.size()); k++) {
                    Item it = day.items.get(k);
                    RectF r = new RectF(x + ui.dp(2), line, x + cw - ui.dp(2), line + ui.dp(11));
                    fill.setColor((it.color() & 0x00ffffff) | (Ui.DARK ? 0x55000000 : 0x33000000)); fill.setAlpha(inMonth ? fill.getAlpha() : 40); c.drawRoundRect(r, ui.dp(3), ui.dp(3), fill);
                    small.setColor(Ui.TEXT); small.setAlpha(alpha); small.setTextAlign(Paint.Align.LEFT);
                    c.drawText(TextUtils.ellipsize(it.title(), small, cw - ui.dp(8), TextUtils.TruncateAt.END).toString(), r.left + ui.dp(2), r.bottom - ui.dp(2.5f), small);
                    line += ui.dp(12.5f);
                }
                if (day.items.size() > room) {
                    small.setColor(Ui.MUTED); small.setAlpha(alpha); small.setTextAlign(Paint.Align.CENTER);
                    c.drawText("+" + (day.items.size() - room), cx, Math.min(y + ch - ui.dp(2), line + ui.dp(8)), small);
                }
            }
        }
    }
}
