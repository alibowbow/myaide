package dev.jun.gyeot.core;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/**
 * Calendar events in the main chat, carried as text like its pictures (ChatImages): the model writes a fenced ```calendar
 * block ({title, start, end | all_day, location, reminder_minutes, calendar}); the app checks it (CalendarQuery.event) and
 * puts a ```calendar-event card in its place. Nothing is added until the user taps 캘린더에 추가 on the card, because the main
 * chat cannot read the event back by voice. A card the model wrote itself becomes ```json code, so only the app's own
 * records ever show as cards, and only they can be added or undone.
 */
public final class CalendarCards {
    public static final String ASK = "calendar", CARD = "calendar-event";
    /** Cards made for one answer at most. */
    public static final int MAX = 3;
    /**
     * A card's states: waiting for the tap, being added, added, being undone, undone (deleted by the undo), already gone from
     * the calendar when the undo looked (nothing deleted); or not addable (error).
     */
    public static final String READY = "ready", ADDING = "adding", ADDED = "added", UNDOING = "undoing", UNDONE = "undone", GONE = "gone", FAILED = "error";
    private static final Set<String> STATES = Set.of(READY, ADDING, ADDED, UNDOING, UNDONE, GONE, FAILED);
    private CalendarCards() {}

    private static String[] lines(String text) { return (text == null ? "" : text.replace("\r\n", "\n")).split("\n", -1); }

    /** The calendar requests in an answer, in order: only blocks whose body is a JSON object; any other is plain code. */
    public static List<JSONObject> requests(String text) {
        List<JSONObject> out = new ArrayList<>();
        for (ChatImages.Fence fence : ChatImages.fences(lines(text))) { JSONObject ask = ASK.equals(fence.lang()) ? parse(fence.body()) : null; if (ask != null) out.add(ask); }
        return out;
    }
    /** A request's body as a JSON object, or null (a month drawn as text, say) so the block stays as it was. */
    static JSONObject parse(String body) {
        String text = body == null ? "" : body.strip();
        if (!text.startsWith("{")) return null;
        try { return new JSONObject(text); } catch (Exception notJson) { return null; }
    }
    /**
     * The card for one request: ready to add, with the checked event ({card, state, title, start, end, all_day, location,
     * reminder_minutes, calendar, zone, when}), or not addable with the reason.
     */
    public static JSONObject card(JSONObject ask, String token, ZonedDateTime now) {
        try {
            // The model's arguments never choose a zone: the card is made in the device's.
            CalendarQuery.NewEvent event = CalendarQuery.event(ask, now);
            JSONObject card = event.json();
            Jsons.put(card, "card", token); Jsons.put(card, "state", READY); Jsons.put(card, "when", CalendarQuery.when(event, now));
            return card;
        } catch (IllegalArgumentException invalid) { return failure(ask, token, invalid.getMessage()); }
        // Whatever else goes wrong with one request, the answer keeps its text and this card says so.
        catch (RuntimeException unexpected) { return failure(ask, token, "날짜를 확인해 주세요."); }
    }
    /** A card that cannot be added, with the reason the user sees. */
    public static JSONObject failure(JSONObject ask, String token, String reason) {
        return Jsons.obj("card", token, "state", FAILED, "title", CalendarQuery.clean(CalendarQuery.text(ask, "title"), CalendarQuery.TITLE), "error", reason == null || reason.isBlank() ? "일정을 읽지 못했어요." : reason);
    }
    /**
     * The answer to keep: the n-th calendar request replaced by cards.get(n), requests past MAX by a short note, and cards the
     * model wrote turned into ```json code.
     */
    public static String apply(String text, List<JSONObject> cards) {
        String[] lines = lines(text); StringBuilder out = new StringBuilder(); int next = 0, used = 0; boolean changed = false;
        for (ChatImages.Fence fence : ChatImages.fences(lines)) {
            boolean ask = ASK.equals(fence.lang()) && parse(fence.body()) != null, made = CARD.equals(fence.lang());
            if (!ask && !made) continue;
            changed = true;
            for (int i = next; i < fence.from(); i++) out.append(lines[i]).append('\n');
            if (made) out.append("```json\n").append(fence.body()).append("\n```\n");
            else if (used < MAX && used < cards.size()) out.append(block(cards.get(used++))).append('\n');
            else out.append("_(일정은 한 번에 ").append(MAX).append("개까지 추가할 수 있어요.)_\n");
            next = fence.to() + 1;
        }
        if (!changed) return text == null ? "" : text;
        for (int i = next; i < lines.length; i++) out.append(lines[i]).append(i + 1 < lines.length ? "\n" : "");
        String result = out.toString();
        return result.endsWith("\n") && !(text == null ? "" : text).endsWith("\n") ? result.substring(0, result.length() - 1) : result;
    }
    /** The answer with only the cards the model wrote turned into ```json code; its requests stay as they are. */
    public static String neutralize(String text) {
        if (text == null || !text.contains(CARD)) return text == null ? "" : text;
        String[] lines = lines(text); StringBuilder out = new StringBuilder(); int next = 0;
        for (ChatImages.Fence fence : ChatImages.fences(lines)) {
            if (!CARD.equals(fence.lang())) continue;
            for (int i = next; i < fence.from(); i++) out.append(lines[i]).append('\n');
            out.append("```json\n").append(fence.body()).append("\n```\n");
            next = fence.to() + 1;
        }
        if (next == 0) return text;
        for (int i = next; i < lines.length; i++) out.append(lines[i]).append(i + 1 < lines.length ? "\n" : "");
        String result = out.toString();
        return result.endsWith("\n") && !text.endsWith("\n") ? result.substring(0, result.length() - 1) : result;
    }
    public static String block(JSONObject record) { return "```" + CARD + "\n" + record + "\n```"; }
    /** A card block's record, or null when the body is not one of the app's cards. */
    public static JSONObject record(String body) {
        try {
            JSONObject record = new JSONObject(body == null ? "" : body.strip());
            return WorkBook.safeId(record.optString("card")) && STATES.contains(record.optString("state")) ? record : null;
        } catch (Exception e) { return null; }
    }
    /** The card blocks' records in order; null for a block that is not one of the app's cards. */
    public static List<JSONObject> cards(String text) {
        List<JSONObject> out = new ArrayList<>();
        for (ChatImages.Fence fence : ChatImages.fences(lines(text))) if (CARD.equals(fence.lang())) out.add(record(fence.body()));
        return out;
    }
    /** The answer with its index-th card holding record instead; everything else stays as it was. */
    public static String replace(String text, int index, JSONObject record) {
        String[] lines = lines(text); int seen = 0;
        for (ChatImages.Fence fence : ChatImages.fences(lines)) {
            if (!CARD.equals(fence.lang()) || seen++ != index) continue;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < fence.from(); i++) out.append(lines[i]).append('\n');
            out.append(block(record));
            for (int i = fence.to() + 1; i < lines.length; i++) out.append('\n').append(lines[i]);
            return out.toString();
        }
        return text;
    }
    /** Whether a card added at addedAt can still be taken back at now (CalendarQuery.UNDO_MS). */
    public static boolean undoable(JSONObject record, long now) {
        long at = record == null ? 0 : record.optLong("added_at");
        return record != null && ADDED.equals(record.optString("state")) && record.optLong("event") > 0 && at > 0 && now - at >= 0 && now - at < CalendarQuery.UNDO_MS;
    }
    /**
     * The answer as the model reads it back in history: each card becomes one short line with what it holds and whether it was
     * added, so no card record, token or event id goes along and the model never copies one.
     */
    public static String history(String text) {
        if (text == null || !text.contains(CARD)) return text == null ? "" : text;
        String[] lines = lines(text); StringBuilder out = new StringBuilder(); int next = 0;
        for (ChatImages.Fence fence : ChatImages.fences(lines)) {
            if (!CARD.equals(fence.lang())) continue;
            JSONObject record = record(fence.body());
            if (record == null) continue;
            for (int i = next; i < fence.from(); i++) out.append(lines[i]).append('\n');
            String state = switch (record.optString("state")) {
                case ADDED, UNDOING -> "캘린더에 추가함"; case UNDONE -> "추가했다가 되돌림"; case GONE -> "추가했지만 캘린더에서 이미 지워짐"; case FAILED -> "추가할 수 없음: " + record.optString("error");
                case ADDING -> "추가하는 중"; default -> "사용자가 아직 추가하지 않음";
            };
            String what = (record.optString("title") + (record.optString("when").isEmpty() ? "" : " · " + record.optString("when"))).strip();
            out.append("[일정 카드 · ").append(state).append(what.isEmpty() ? "" : ": " + what).append("]\n");
            next = fence.to() + 1;
        }
        if (next == 0) return text;
        for (int i = next; i < lines.length; i++) out.append(lines[i]).append(i + 1 < lines.length ? "\n" : "");
        String result = out.toString();
        return result.endsWith("\n") && !text.endsWith("\n") ? result.substring(0, result.length() - 1) : result;
    }
}
