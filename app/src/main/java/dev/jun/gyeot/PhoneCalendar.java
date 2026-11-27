package dev.jun.gyeot;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.CalendarQuery;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.obj;

/**
 * 캘린더: the phone's own calendar provider (CalendarContract), which already holds the user's Google
 * Calendar, synced by the phone's Google account. No OAuth, and reads work offline. Every visible calendar is read, each event
 * labelled with its calendar's own name; a new event goes to the main writable calendar (the primary Google one first) or to
 * the one named. The only deletion is the undo of an event the app itself just added, checked against what it added.
 * Nothing about an event is ever logged.
 */
final class PhoneCalendar {
    /** 설정 → 캘린더 → 대화에서 캘린더 쓰기; on by default, in use once the permission is granted. */
    static final String SETTING = "calendarOn";
    /** Whether the permission was asked once, so a later refusal opens Android's settings instead. */
    static final String ASKED = "calendarAsked";
    /** The calendar the user picked for new events in 설정 → 캘린더, by its provider id. */
    static final String PICKED = "calendarTarget";
    static final String[] PERMISSIONS = {Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR};
    /** The settings page's and the main chat card's permission requests (clear of 20-22, 7416 and 7417). */
    static final int SETTINGS_REQUEST = 7418, CARD_REQUEST = 7419;
    /** The tap-to-open notification for a missing permission: one id, so a second ask replaces it (clear of 10-3823, 4090, 4110-4141). */
    static final int NOTIFICATION = 4160;
    /** MainActivity opens this settings page for the notification's tap. */
    static final String PAGE_EXTRA = "gyeot.settings_page", PAGE = "calendar";
    private PhoneCalendar() {}

    static boolean on(SharedPreferences settings) { return settings.getBoolean(SETTING, true); }
    static boolean on(AppStore store) { return on(store.settings); }
    static boolean readable(Context context) { return granted(context, Manifest.permission.READ_CALENDAR); }
    static boolean writable(Context context) { return readable(context) && granted(context, Manifest.permission.WRITE_CALENDAR); }
    private static boolean granted(Context context, String permission) {
        try { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; } catch (RuntimeException e) { return false; }
    }
    /**
     * The calendar app's own new-event screen, filled in (the user saves it; nothing is written here): the calendar_add tool
     * (BuddyActs) and 알림을 행동으로's 일정에 넣기 (NoticeActActivity) both open it this way. place and note may be empty.
     */
    static Intent insert(String title, long begin, long end, boolean allDay, String place, String note) {
        Intent add = new Intent(Intent.ACTION_INSERT).setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.dir/event")
            .putExtra(CalendarContract.Events.TITLE, title).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end).putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay);
        if (place != null && !place.isBlank()) add.putExtra(CalendarContract.Events.EVENT_LOCATION, place.strip());
        if (note != null && !note.isBlank()) add.putExtra(CalendarContract.Events.DESCRIPTION, note.strip());
        return add.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * One visible calendar: its name as the calendar app shows it, its account, whether events can be added, whether the
     * provider marks it primary (IS_PRIMARY), and whether its owner is its account (a Google account's own calendar; a shared
     * or secondary one is owned by …@group.calendar.google.com).
     */
    record Calendar(long id, String name, String account, String type, boolean writable, boolean primary, boolean own) {}
    private static final String[] CALENDARS = {CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE,
        CalendarContract.Calendars.OWNER_ACCOUNT};
    /** The same without IS_PRIMARY, for a provider that does not have that column. */
    private static final String[] WITHOUT_PRIMARY = java.util.Arrays.stream(CALENDARS).filter(c -> !CalendarContract.Calendars.IS_PRIMARY.equals(c)).toArray(String[]::new);
    /** Every visible calendar, in the provider's order. */
    static List<Calendar> calendars(Context context) {
        try { return calendars(context, CALENDARS); }
        // A provider without is_primary (it is optional for other calendar apps' providers) is asked without only that column.
        catch (IllegalArgumentException | android.database.sqlite.SQLiteException unknownColumn) { return calendars(context, WITHOUT_PRIMARY); }
    }
    private static List<Calendar> calendars(Context context, String[] projection) {
        List<Calendar> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, projection, CalendarContract.Calendars.VISIBLE + "=1", null, null)) {
            while (c != null && c.moveToNext()) {
                if (integer(c, CalendarContract.Calendars.VISIBLE, 1) != 1) continue;
                String name = string(c, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME), account = string(c, CalendarContract.Calendars.ACCOUNT_NAME), owner = string(c, CalendarContract.Calendars.OWNER_ACCOUNT);
                boolean write = integer(c, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, 0) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
                out.add(new Calendar(c.getLong(c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)), name.isBlank() ? account : name, account, string(c, CalendarContract.Calendars.ACCOUNT_TYPE), write,
                    integer(c, CalendarContract.Calendars.IS_PRIMARY, 0) == 1, !account.isEmpty() && account.equalsIgnoreCase(owner)));
            }
        }
        return out;
    }
    /**
     * Where new events go when the user names none: the calendar picked in 설정 → 캘린더 (saved by its id) while it is still
     * there and writable; otherwise the first writable one the provider marks primary, then the first writable one its account
     * owns, then the first writable one. Null when none is writable.
     */
    static Calendar main(List<Calendar> calendars, long picked) {
        Calendar owned = null, any = null, primary = null;
        for (Calendar c : calendars) {
            if (!c.writable()) continue;
            if (picked > 0 && c.id() == picked) return c;
            if (primary == null && c.primary()) primary = c;
            if (owned == null && c.own()) owned = c;
            if (any == null) any = c;
        }
        return primary != null ? primary : owned != null ? owned : any;
    }
    /** The calendar picked for new events in 설정 → 캘린더, by its id (0: none). */
    static long picked(SharedPreferences settings) { return settings.getLong(PICKED, 0); }
    /** Where a new event goes: the calendar named (exact name first, then a part of it) or the main one (main). */
    static Calendar target(List<Calendar> calendars, String named, long picked) {
        List<Calendar> writable = new ArrayList<>();
        for (Calendar c : calendars) if (c.writable()) writable.add(c);
        if (writable.isEmpty()) throw new IllegalStateException("일정을 넣을 수 있는 캘린더가 없어요. 휴대폰 캘린더 앱에서 구글 계정 동기화를 확인해 주세요.");
        String wanted = named == null ? "" : named.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return main(calendars, picked);
        for (Calendar c : writable) if (c.name().toLowerCase(Locale.ROOT).equals(wanted)) return c;
        for (Calendar c : writable) if (c.name().toLowerCase(Locale.ROOT).contains(wanted) || c.account().toLowerCase(Locale.ROOT).equals(wanted)) return c;
        StringBuilder names = new StringBuilder();
        for (Calendar c : writable) names.append(names.length() == 0 ? "" : ", ").append(c.name());
        throw new IllegalArgumentException("‘" + named.strip() + "’ 캘린더를 찾지 못했어요. 넣을 수 있는 캘린더: " + names + ".");
    }

    private static final String[] INSTANCES = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DESCRIPTION,
        CalendarContract.Instances.STATUS, CalendarContract.Instances.VISIBLE};
    /**
     * The events of every visible calendar around the range (a day wider on each side, since an all-day event's times are UTC
     * midnights); CalendarQuery keeps the ones in it. Cancelled events are left out; attendees only with details.
     */
    static List<CalendarQuery.Event> events(Context context, CalendarQuery.Range range, boolean details) {
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, range.from() - 86_400_000L); ContentUris.appendId(uri, range.to() + 86_400_000L);
        List<CalendarQuery.Event> out = new ArrayList<>(); List<Long> ids = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        try (Cursor c = resolver.query(uri.build(), INSTANCES, CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext()) {
                if (integer(c, CalendarContract.Instances.VISIBLE, 1) != 1 || integer(c, CalendarContract.Instances.STATUS, 0) == CalendarContract.Instances.STATUS_CANCELED) continue;
                out.add(new CalendarQuery.Event(string(c, CalendarContract.Instances.TITLE), c.getLong(c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)), c.getLong(c.getColumnIndexOrThrow(CalendarContract.Instances.END)),
                    integer(c, CalendarContract.Instances.ALL_DAY, 0) == 1, string(c, CalendarContract.Instances.EVENT_LOCATION), string(c, CalendarContract.Instances.CALENDAR_DISPLAY_NAME),
                    details ? string(c, CalendarContract.Instances.DESCRIPTION) : "", List.of()));
                ids.add(c.getLong(c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)));
            }
        }
        if (!details) return out;
        // Attendees only for the events in the range, which a model reads at most LIMIT of.
        Map<Long, List<String>> people = new HashMap<>(); List<CalendarQuery.Event> withPeople = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            CalendarQuery.Event e = out.get(i);
            if (!CalendarQuery.within(e, range) || people.size() >= CalendarQuery.LIMIT) { withPeople.add(e); continue; }
            List<String> names = people.computeIfAbsent(ids.get(i), id -> attendees(resolver, id));
            withPeople.add(new CalendarQuery.Event(e.title(), e.begin(), e.end(), e.allDay(), e.location(), e.calendar(), e.description(), names));
        }
        return withPeople;
    }
    private static List<String> attendees(ContentResolver resolver, long event) {
        List<String> names = new ArrayList<>();
        try (Cursor c = CalendarContract.Attendees.query(resolver, event, new String[]{CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL})) {
            while (c != null && c.moveToNext() && names.size() < CalendarQuery.ATTENDEES) {
                String name = string(c, CalendarContract.Attendees.ATTENDEE_NAME); if (name.isBlank()) name = string(c, CalendarContract.Attendees.ATTENDEE_EMAIL);
                if (!name.isBlank()) names.add(name);
            }
        } catch (RuntimeException unreadable) { /* The event is read without its attendees. */ }
        return names;
    }

    /** An event the app added: its provider id, its calendar's name, and a note when its reminders could not be set. */
    record Added(long id, String calendar, String note) {}
    /** Inserts the checked event and its reminders into the target calendar. Called only for a request the user made. */
    static Added add(Context context, SharedPreferences settings, CalendarQuery.NewEvent event) {
        Calendar calendar = target(calendars(context), event.calendar(), picked(settings));
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendar.id()); values.put(CalendarContract.Events.TITLE, event.title());
        values.put(CalendarContract.Events.DTSTART, event.startMillis()); values.put(CalendarContract.Events.DTEND, event.endMillis());
        values.put(CalendarContract.Events.EVENT_TIMEZONE, event.timezone()); values.put(CalendarContract.Events.ALL_DAY, event.allDay() ? 1 : 0);
        if (!event.location().isEmpty()) values.put(CalendarContract.Events.EVENT_LOCATION, event.location());
        ContentResolver resolver = context.getContentResolver();
        Uri made = resolver.insert(CalendarContract.Events.CONTENT_URI, values);
        long id = made == null ? -1 : parse(made.getLastPathSegment());
        if (id <= 0) throw new IllegalStateException("캘린더가 일정을 받지 않았어요. 휴대폰 캘린더 앱을 확인해 주세요.");
        String note = "";
        for (int minutes : event.reminders()) {
            ContentValues reminder = new ContentValues();
            reminder.put(CalendarContract.Reminders.EVENT_ID, id); reminder.put(CalendarContract.Reminders.MINUTES, minutes); reminder.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            try { if (resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder) == null) note = "알림은 넣지 못했어요."; }
            catch (RuntimeException refused) { note = "알림은 넣지 못했어요."; }
        }
        return new Added(id, calendar.name(), note);
    }
    private static long parse(String id) { try { return Long.parseLong(id); } catch (RuntimeException e) { return -1; } }
    /**
     * Takes back an event the app added: only when the provider still holds it with the title and start the app gave it.
     * "deleted"; "gone" (no such row, or one already marked deleted); "changed" (it was edited since, so it is left alone); or
     * "failed" (the provider gave no answer, or deleted nothing): nothing is known to be gone, so it may be tried again.
     */
    static String remove(Context context, long id, String title, long start) {
        if (id <= 0) return "failed";
        Uri event = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
        ContentResolver resolver = context.getContentResolver();
        try (Cursor c = resolver.query(event, new String[]{CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DELETED}, null, null, null)) {
            if (c == null) return "failed";
            if (!c.moveToFirst() || integer(c, CalendarContract.Events.DELETED, 0) == 1) return "gone";
            if (!title.equals(string(c, CalendarContract.Events.TITLE)) || c.getLong(c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)) != start) return "changed";
        }
        return resolver.delete(event, null, null) > 0 ? "deleted" : "failed";
    }

    /** A tool's read: {ok, range, count, events}, or why it could not read. */
    static JSONObject read(Context context, String words, boolean details, ZonedDateTime now) {
        CalendarQuery.Range range = CalendarQuery.range(words, now);
        if (range == null) return obj("ok", false, "error", "기간을 알아듣지 못했어요. 오늘, 내일, 이번 주, 다음 주 금요일, 2026-11-12 같은 말로 알려 주세요.");
        return CalendarQuery.result(events(context, range, details), range, details);
    }

    /** The intent that opens 설정 → 캘린더. */
    static Intent settings(Context context) {
        return new Intent(context, MainActivity.class).putExtra(PAGE_EXTRA, PAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    /** A tap-to-open notification to 설정 → 캘린더, where the permission is granted; false when notifications are off. */
    static boolean askPermission(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null || !notifications.areNotificationsEnabled()) return false;
        if (notifications.getNotificationChannel(DeviceActionRunner.CHANNEL) == null) notifications.createNotificationChannel(new NotificationChannel(DeviceActionRunner.CHANNEL, "작업 결과", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, NOTIFICATION, settings(context), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, DeviceActionRunner.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Brand.NAME + " · 캘린더").setContentText("탭해서 캘린더 권한 허용하기").setContentIntent(open).setAutoCancel(true).build();
        try { notifications.notify(NOTIFICATION, notification); } catch (SecurityException off) { return false; }
        return true;
    }

    private static String string(Cursor c, String column) { int i = c.getColumnIndex(column); return i < 0 || c.isNull(i) ? "" : String.valueOf(c.getString(i)); }
    private static int integer(Cursor c, String column, int otherwise) { int i = c.getColumnIndex(column); return i < 0 || c.isNull(i) ? otherwise : c.getInt(i); }
}
