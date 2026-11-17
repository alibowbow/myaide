package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.CalendarQuery;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.json.JSONObject;
import dev.jun.gyeot.core.Jsons;
import static dev.jun.gyeot.core.Jsons.obj;

/**
 * The voice assistant's calendar_events, calendar_add and calendar_undo, run off the main thread; each call
 * gets one reply. An add runs at once and its reply says the saved title, date and time for the model to read back. The undo
 * takes back only an event this call added, within CalendarQuery.UNDO_MS, newest first; no other event can be deleted. Without
 * the permission the reply says how to allow it and a notification opens 설정 → 캘린더. Nothing about an event is logged.
 */
final class CalendarVoice {
    /** One event this call added, as the undo checks it. */
    private record Made(long call, long id, String title, long start, long at, String when, String key) {}
    /** What a call is answered with, and a line for the conversation's record (empty for none). */
    record Answer(JSONObject reply, String notice) {}
    /** Calendar calls run here, one at a time, never behind a slow connector or archive call. */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "voice-calendar"); t.setDaemon(true); return t; });
    private final Context context; private final AppStore store;
    private final Deque<Made> made = new ArrayDeque<>();
    CalendarVoice(Context context, AppStore store) { this.context = context; this.store = store; }

    /** Runs one call off the main thread; done hears its answer once. heard: the user's words for this input ("" for none). */
    void call(String name, JSONObject args, long call, String heard, Consumer<Answer> done) {
        try { WORKER.execute(() -> done.accept(answer(name, args, call, heard))); }
        catch (RejectedExecutionException closed) { done.accept(new Answer(obj("ok", false, "error", "캘린더를 쓰지 못했어요. 잠시 뒤 다시 해 주세요."), "")); }
    }
    /**
     * call identifies the voice call (its epoch): an undo never reaches an event another call added. An add runs only when the
     * user's own words for this input ask for one (CalendarQuery.asksToAdd), or when no words arrived in time; never because
     * of an event's description or an invitation's text.
     */
    Answer answer(String name, JSONObject args, long call, String heard) {
        try {
            if (!PhoneCalendar.on(store)) return new Answer(obj("ok", false, "error", CalendarQuery.OFF, "say", CalendarQuery.OFF), "");
            boolean write = !CalendarQuery.EVENTS.equals(name);
            if (write ? !PhoneCalendar.writable(context) : !PhoneCalendar.readable(context)) {
                boolean notified = PhoneCalendar.askPermission(context);
                return new Answer(obj("ok", false, "needs_permission", true, "notification", notified, "error", CalendarQuery.NEEDS_PERMISSION, "say", CalendarQuery.NEEDS_PERMISSION
                    + (notified ? " 알림을 누르면 설정이 열려요." : "")), "");
            }
            ZonedDateTime now = ZonedDateTime.now();
            return switch (name) {
                case CalendarQuery.EVENTS -> new Answer(PhoneCalendar.read(context, CalendarQuery.text(args, "range"), args.optBoolean("details"), now), "");
                case CalendarQuery.ADD -> heard != null && !heard.isBlank() && !CalendarQuery.asksToAdd(heard)
                    ? new Answer(obj("ok", false, "not_requested", true, "error", CalendarQuery.NOT_ASKED), "") : add(args, call, now);
                default -> undo(call);
            };
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return new Answer(obj("ok", false, "error", refused.getMessage()), "");
        } catch (RuntimeException failed) {
            return new Answer(obj("ok", false, "error", "캘린더를 쓰지 못했어요. 잠시 뒤 다시 해 주세요."), "");
        }
    }
    /** An add of this call, kept so the same request within REPEAT_MS is answered with its first result instead of a second event. */
    private record Recent(long call, String key, long at, JSONObject reply) {}
    private final Deque<Recent> recent = new ArrayDeque<>();
    /** The same title, start, end and calendar asked again this soon in one call is the same event. */
    static final long REPEAT_MS = 5 * 60_000L;
    private Answer add(JSONObject args, long call, ZonedDateTime now) {
        CalendarQuery.NewEvent event = CalendarQuery.event(args, now);
        String key = event.title() + "\u0000" + event.startMillis() + "\u0000" + event.endMillis() + "\u0000" + event.calendar().toLowerCase(java.util.Locale.ROOT);
        long at = System.currentTimeMillis();
        synchronized (made) {
            recent.removeIf(r -> at - r.at() >= REPEAT_MS);
            for (Recent r : recent) if (r.call() == call && r.key().equals(key)) return new Answer(Jsons.copy(r.reply()), "");
        }
        PhoneCalendar.Added added = PhoneCalendar.add(context, store.settings, event);
        JSONObject reply = CalendarQuery.added(event, added.calendar(), added.note(), now);
        synchronized (made) {
            made.addFirst(new Made(call, added.id(), event.title(), event.startMillis(), at, reply.optString("when"), key));
            while (made.size() > 8) made.removeLast();
            recent.addFirst(new Recent(call, key, at, Jsons.copy(reply)));
            while (recent.size() > 8) recent.removeLast();
        }
        return new Answer(reply, "캘린더에 넣었어요 · " + event.title() + " · " + reply.optString("when"));
    }
    /**
     * The newest event this call added, while it can still be taken back. Edited since: refused, and it stays first, so asking
     * again gets the same refusal and never reaches an older one. Already gone: dropped, and the reply says so. A failure keeps it.
     */
    private Answer undo(long call) {
        Made last;
        synchronized (made) {
            long now = System.currentTimeMillis();
            made.removeIf(m -> m.call() != call || now - m.at() >= CalendarQuery.UNDO_MS);
            last = made.peekFirst();
        }
        if (last == null) return new Answer(obj("ok", false, "error", "이번 대화에서 방금 넣은 일정이 없어요. 다른 일정은 지울 수 없어요."), "");
        String outcome = PhoneCalendar.remove(context, last.id(), last.title(), last.start());
        switch (outcome) {
            case "changed" -> { return new Answer(obj("ok", false, "changed", true, "error", "‘" + last.title() + "’ 일정이 그사이 바뀌어서 지우지 않았어요. 캘린더 앱에서 확인해 주세요."), ""); }
            case "deleted", "gone" -> {
                synchronized (made) { made.remove(last); recent.removeIf(r -> r.call() == call && r.key().equals(last.key())); }
                boolean deleted = "deleted".equals(outcome);
                String say = deleted ? "‘" + last.title() + "’ 일정(" + last.when() + ")을 캘린더에서 지웠어요." : "‘" + last.title() + "’ 일정은 캘린더에 이미 없어요.";
                return new Answer(obj("ok", true, "deleted", deleted, "title", last.title(), "when", last.when(), "say", say), deleted ? "캘린더에서 되돌렸어요 · " + last.title() : "");
            }
            default -> { return new Answer(obj("ok", false, "error", "‘" + last.title() + "’ 일정을 지우지 못했어요. 잠시 뒤 다시 해 주세요."), ""); }
        }
    }
}
