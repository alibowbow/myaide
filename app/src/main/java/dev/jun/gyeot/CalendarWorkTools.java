package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.CalendarQuery;
import dev.jun.gyeot.core.GitHubFunctions;
import java.time.ZonedDateTime;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The phone's calendar for every 작업창 model: calendar_events reads, calendar_add adds an event the user
 * asked for at once (journaled by the ledger before it runs and never repeated in a turn), and only when the run's own user
 * words (WorkBook.authority) ask for an add, never an event's or a page's text. Offered only while 설정 → 캘린더 is on and the
 * permission is granted; adding only with the write permission. No event can be changed or deleted from here.
 */
final class CalendarWorkTools extends WorkTools {
    private final Context context; private final android.content.SharedPreferences settings; private final boolean write; private final Supplier<String> words; private final BooleanSupplier stopped;
    CalendarWorkTools(Context context, android.content.SharedPreferences settings, boolean write, Supplier<String> words, BooleanSupplier stopped) { this.context = context; this.settings = settings; this.write = write; this.words = words; this.stopped = stopped; }
    /** The tools for a run, or null when the setting is off or the calendar cannot be read. words: the run's own user words. */
    static CalendarWorkTools offered(Context context, AppStore store, Supplier<String> words, BooleanSupplier stopped) {
        return PhoneCalendar.on(store) && PhoneCalendar.readable(context) ? new CalendarWorkTools(context, store.settings, PhoneCalendar.writable(context), words, stopped) : null;
    }

    @Override JSONArray declarations() { return CalendarQuery.declarations(write); }
    @Override String instructions() { return CalendarQuery.instructions(write); }
    @Override JSONObject run(String name, Object arguments) {
        if (stopped.getAsBoolean()) throw new CancellationException("작업을 멈췄어요.");
        JSONObject args;
        try { args = arguments instanceof JSONObject given ? given : new JSONObject(String.valueOf(arguments)); }
        catch (Exception e) { return GitHubFunctions.failure("도구의 인수를 확인해 주세요."); }
        try {
            ZonedDateTime now = ZonedDateTime.now();
            if (CalendarQuery.EVENTS.equals(name)) {
                if (!PhoneCalendar.readable(context)) return GitHubFunctions.failure(CalendarQuery.NEEDS_PERMISSION);
                return PhoneCalendar.read(context, CalendarQuery.text(args, "range"), args.optBoolean("details"), now);
            }
            if (!CalendarQuery.ADD.equals(name) || !write) return GitHubFunctions.failure("허용하지 않은 도구예요.");
            if (!PhoneCalendar.writable(context)) return GitHubFunctions.failure(CalendarQuery.NEEDS_PERMISSION);
            String asked = words.get();
            if (asked != null && !asked.isBlank() && !CalendarQuery.asksToAdd(asked)) return GitHubFunctions.failure(CalendarQuery.NOT_ASKED);
            CalendarQuery.NewEvent event = CalendarQuery.event(args, now);
            PhoneCalendar.Added added = PhoneCalendar.add(context, settings, event);
            return CalendarQuery.added(event, added.calendar(), added.note(), now);
        } catch (IllegalArgumentException | IllegalStateException refused) { return GitHubFunctions.failure(refused.getMessage()); }
        catch (RuntimeException failed) { return GitHubFunctions.failure("캘린더를 쓰지 못했어요."); }
    }
}
