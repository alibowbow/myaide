package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Pictures in the main chat, carried as text so every provider can ask for one: the model writes a fenced ```image block
 * ({prompt, aspect, edit?}); the app draws it and puts a ```picture block (what was made, or why not) in its place. A
 * picture block the model wrote itself becomes plain ```json code, so only the app's own records ever show as pictures.
 */
public final class ChatImages {
    /** Pictures drawn for one answer at most. */
    public static final int MAX = 2;
    public static final String ASK = "image", MADE = "picture";
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})[ \\t]*([A-Za-z][\\w+#.-]*)?[^`]*$");
    private ChatImages() {}

    /** A fenced block in an answer: its first and last line, language and body. Unclosed fences run to the last line. */
    record Fence(int from, int to, String lang, String body) {}

    static List<Fence> fences(String[] lines) {
        List<Fence> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher open = FENCE.matcher(lines[i]);
            if (!open.matches()) continue;
            String mark = open.group(1), lang = open.group(2) == null ? "" : open.group(2).toLowerCase(Locale.ROOT);
            char kind = mark.charAt(0); int j = i + 1; StringBuilder body = new StringBuilder();
            for (; j < lines.length; j++) {
                String s = lines[j].strip();
                if (s.length() >= mark.length() && s.chars().allMatch(c -> c == kind) && lines[j].length() - lines[j].stripLeading().length() <= 3) break;
                body.append(j > i + 1 ? "\n" : "").append(lines[j]);
            }
            out.add(new Fence(i, Math.min(j, lines.length - 1), lang, body.toString()));
            i = j;
        }
        return out;
    }
    private static String[] lines(String text) { return (text == null ? "" : text.replace("\r\n", "\n")).split("\n", -1); }

    /** The image requests in an answer, in order and at most MAX: {prompt, aspect, edit}, edit "" unless it names a drawn picture. */
    public static List<JSONObject> requests(String text) {
        List<JSONObject> out = new ArrayList<>();
        for (Fence fence : fences(lines(text))) {
            if (!ASK.equals(fence.lang())) continue;
            JSONObject ask = parse(fence.body());
            if (ask.optString("prompt").isEmpty()) continue;
            out.add(ask);
            if (out.size() == MAX) break;
        }
        return out;
    }
    /** One request's body: JSON when it is JSON, otherwise the whole body is the prompt. */
    static JSONObject parse(String body) {
        String text = body == null ? "" : body.strip(); JSONObject raw = null;
        if (text.startsWith("{")) try { raw = new JSONObject(text); } catch (Exception ignored) {}
        String prompt = raw == null ? text : raw.optString("prompt").strip();
        String aspect = raw == null ? "" : raw.optString("aspect").strip(), edit = raw == null ? "" : raw.optString("edit").strip();
        return Jsons.obj("prompt", prompt, "aspect", aspect, "edit", edit.startsWith("g") && WorkBook.safeId(edit) ? edit : "");
    }

    /**
     * The answer to keep: the n-th valid image request replaced by results.get(n) as a picture block, requests without a result
     * (past MAX or empty) replaced by a short note, and picture blocks the model wrote turned into ```json code.
     */
    public static String apply(String text, List<JSONObject> results) {
        String[] lines = lines(text); List<Fence> fences = fences(lines);
        StringBuilder out = new StringBuilder(); int next = 0, used = 0;
        for (Fence fence : fences) {
            boolean ask = ASK.equals(fence.lang()), made = MADE.equals(fence.lang());
            if (!ask && !made) continue;
            for (int i = next; i < fence.from(); i++) out.append(lines[i]).append('\n');
            if (made) out.append("```json\n").append(fence.body()).append("\n```\n");
            else if (!parse(fence.body()).optString("prompt").isEmpty() && used < MAX && used < results.size()) out.append(block(results.get(used++))).append('\n');
            else out.append(used >= MAX ? "_(그림은 한 번에 " + MAX + "장까지 만들어요.)_" : "_(그림 요청이 비어 있어 그리지 않았어요.)_").append('\n');
            next = fence.to() + 1;
        }
        for (int i = next; i < lines.length; i++) out.append(lines[i]).append(i + 1 < lines.length ? "\n" : "");
        String result = out.toString();
        return result.endsWith("\n") && !(text == null ? "" : text).endsWith("\n") ? result.substring(0, result.length() - 1) : result;
    }

    /**
     * A picture block for a record: {id, name, prompt, aspect, w, h, model, from?, note?} for a drawn picture, {error, prompt,
     * aspect, edit?} for one that was not drawn, or {pending, prompt, aspect, edit} while the app draws it.
     */
    public static String block(JSONObject record) { return "```" + MADE + "\n" + record + "\n```"; }
    /** A picture block's record, or null when the body is not one. */
    public static JSONObject record(String body) {
        try {
            JSONObject record = new JSONObject(body == null ? "" : body.strip());
            boolean drawn = record.optString("id").startsWith("g") && WorkBook.safeId(record.optString("id"));
            return drawn || !record.optString("error").isBlank() || pending(record) ? record : null;
        } catch (Exception e) { return null; }
    }
    /** A record for a request that was not drawn, with the reason the user sees. */
    public static JSONObject failure(JSONObject ask, String reason) {
        JSONObject record = Jsons.obj("error", reason == null || reason.isBlank() ? "그림을 만들지 못했어요." : reason, "prompt", ask.optString("prompt"), "aspect", ask.optString("aspect"));
        if (!ask.optString("edit").isEmpty()) Jsons.put(record, "edit", ask.optString("edit"));
        return record;
    }
    /**
     * The card for a request the app draws after the answer is shown ("그리는 중"): token names that one drawing, so only
     * its own picture or reason ever replaces the card.
     */
    public static JSONObject pending(JSONObject ask, String token) {
        return Jsons.obj("pending", token, "prompt", ask.optString("prompt"), "aspect", ask.optString("aspect"), "edit", ask.optString("edit"));
    }
    /** Whether a record is a card still waiting for its drawing. */
    public static boolean pending(JSONObject record) { return record != null && WorkBook.safeId(record.optString("pending")); }
    /** What a card asks for, to draw it again: {prompt, aspect, edit}, edit only for a picture id. */
    public static JSONObject ask(JSONObject record) { return parse(Jsons.obj("prompt", record.optString("prompt"), "aspect", record.optString("aspect"), "edit", record.optString("edit")).toString()); }
    /** The picture blocks' records in order; null for a block that is not one of the app's records. */
    public static List<JSONObject> pictures(String text) {
        List<JSONObject> out = new ArrayList<>();
        for (Fence fence : fences(lines(text))) if (MADE.equals(fence.lang())) out.add(record(fence.body()));
        return out;
    }
    /** The answer with its index-th picture block holding record instead; everything else stays as it was. */
    public static String replace(String text, int index, JSONObject record) {
        String[] lines = lines(text); int seen = 0;
        for (Fence fence : fences(lines)) {
            if (!MADE.equals(fence.lang()) || seen++ != index) continue;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < fence.from(); i++) out.append(lines[i]).append('\n');
            out.append(block(record));
            for (int i = fence.to() + 1; i < lines.length; i++) out.append('\n').append(lines[i]);
            return out.toString();
        }
        return text;
    }
    /**
     * The answer as the model reads it back in history: a card still waiting for its drawing, or one that was not drawn,
     * becomes a short note with what was asked, so the history's picture blocks are only pictures already made (and no
     * drawing token goes along).
     */
    public static String history(String text) {
        if (text == null || !text.contains(MADE)) return text == null ? "" : text;
        String[] lines = lines(text); StringBuilder out = new StringBuilder(); int next = 0;
        for (Fence fence : fences(lines)) {
            if (!MADE.equals(fence.lang())) continue;
            JSONObject record = record(fence.body());
            if (record == null || !pending(record) && record.optString("error").isBlank()) continue;
            for (int i = next; i < fence.from(); i++) out.append(lines[i]).append('\n');
            String prompt = record.optString("prompt").strip();
            out.append(pending(record) ? "[그림 요청 · 아직 없음" : "[그림 요청 · 그리지 못함").append(prompt.isEmpty() ? "" : ": " + prompt).append("]\n");
            next = fence.to() + 1;
        }
        if (next == 0) return text;
        for (int i = next; i < lines.length; i++) out.append(lines[i]).append(i + 1 < lines.length ? "\n" : "");
        String result = out.toString();
        return result.endsWith("\n") && !text.endsWith("\n") ? result.substring(0, result.length() - 1) : result;
    }
    /** The answer with every card still waiting for a drawing turned into a record with this reason (after a restart). */
    public static String stopped(String text, String reason) {
        List<JSONObject> cards = pictures(text); String out = text;
        for (int i = 0; i < cards.size(); i++) if (pending(cards.get(i))) out = replace(out, i, failure(ask(cards.get(i)), reason));
        return out;
    }
}
