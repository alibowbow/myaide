package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 한마디 루틴: a short word the user chooses, and what it stands for. Saying or sending only that word asks for the whole
 * of it. A routine is {id, word, does}; the list is kept in the order the user made it.
 */
public final class Routines {
    private Routines() {}
    /** The most routines, and the longest word and description. */
    public static final int MOST = 12, WORD = 20, DOES = 400;

    /** The stored list; anything unreadable or incomplete is left out. */
    public static JSONArray parse(String stored) {
        JSONArray out = arr();
        try {
            JSONArray list = stored == null || stored.isBlank() ? arr() : new JSONArray(stored);
            for (int i = 0; i < list.length() && out.length() < MOST; i++) {
                JSONObject item = list.optJSONObject(i); if (item == null) continue;
                String word = word(item.optString("word")), does = does(item.optString("does"));
                if (key(word).length() < 2 || does.isEmpty() || find(out, key(word)) != null) continue;
                out.put(obj("id", item.optString("id").isBlank() ? UUID.randomUUID().toString().substring(0, 8) : item.optString("id"), "word", word, "does", does));
            }
        } catch (Exception unreadable) { /* An unreadable list is an empty one. */ }
        return out;
    }
    private static String word(String text) { String clean = text == null ? "" : text.replaceAll("\\s+", " ").strip(); return clean.length() > WORD ? clean.substring(0, WORD).strip() : clean; }
    private static String does(String text) { String clean = text == null ? "" : text.replace("\r", "").replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").strip(); return clean.length() > DOES ? clean.substring(0, DOES).strip() : clean; }

    /** What two ways of writing the same word share: lower case, one space between words, no punctuation at the ends. */
    public static String key(String said) {
        String clean = said == null ? "" : said.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        return clean.replaceAll("^[\\p{Punct}…·~\\s]+|[\\p{Punct}…·~\\s]+$", "");
    }
    private static JSONObject find(JSONArray list, String key) {
        for (int i = 0; list != null && i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null && key(item.optString("word")).equals(key)) return item; }
        return null;
    }
    /** The routine whose word is the whole of what was said; null when what was said is anything more or less. */
    public static JSONObject match(JSONArray list, String said) {
        String key = key(said);
        return key.length() < 2 || key.length() > WORD ? null : find(list, key);
    }

    /** The list with one routine added (a blank id) or changed. Says why when it cannot be kept. */
    public static JSONArray save(JSONArray list, String id, String word, String does) {
        String name = word(word), task = does(does), key = key(name);
        if (key.length() < 2) throw new IllegalArgumentException("한마디는 두 글자부터 적어 주세요.");
        if (word != null && word.replaceAll("\\s+", " ").strip().length() > WORD) throw new IllegalArgumentException("한마디는 " + WORD + "자까지예요.");
        if (task.isEmpty()) throw new IllegalArgumentException("그 한마디에 할 일을 적어 주세요.");
        if (does != null && does.strip().length() > DOES) throw new IllegalArgumentException("할 일은 " + DOES + "자까지예요.");
        JSONArray out = arr(); boolean changed = false, fresh = id == null || id.isBlank();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i); if (item == null) continue;
            boolean same = !fresh && id.equals(item.optString("id"));
            if (!same && key(item.optString("word")).equals(key)) throw new IllegalArgumentException("‘" + item.optString("word") + "’ 루틴이 이미 있어요.");
            if (same) { out.put(obj("id", id, "word", name, "does", task)); changed = true; } else out.put(item);
        }
        if (!changed) {
            if (out.length() >= MOST) throw new IllegalArgumentException("루틴은 " + MOST + "개까지 만들 수 있어요.");
            out.put(obj("id", UUID.randomUUID().toString().substring(0, 8), "word", name, "does", task));
        }
        return out;
    }
    public static JSONArray remove(JSONArray list, String id) {
        JSONArray out = arr();
        for (int i = 0; list != null && i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null && !item.optString("id").equals(id)) out.put(item); }
        return out;
    }
    /** The routines of incoming whose word the list does not have yet, added while there is room. Returns how many. */
    public static int addUnseen(JSONArray list, JSONArray incoming) {
        int count = 0; JSONArray clean = parse(incoming == null ? "" : incoming.toString());
        for (int i = 0; i < clean.length() && list.length() < MOST; i++) {
            JSONObject item = clean.optJSONObject(i);
            if (find(list, key(item.optString("word"))) != null) continue;
            list.put(obj("id", UUID.randomUUID().toString().substring(0, 8), "word", item.optString("word"), "does", item.optString("does"))); count++;
        }
        return count;
    }

    /** What goes after a message that is only a routine's word, so the model is asked for the whole of it. */
    public static String expand(JSONObject routine) {
        return routine == null ? "" : "\n\n[한마디 루틴 ‘" + routine.optString("word") + "’] 사용자가 미리 정해 둔 루틴이에요. 이 한마디는 아래 일을 차례로 해 달라는 요청이에요:\n" + routine.optString("does");
    }
    /** For a model's standing instructions: the words and what each stands for. "" when there are none. */
    public static String instructions(JSONArray list) {
        if (list == null || list.length() == 0) return "";
        StringBuilder text = new StringBuilder("\n\nThe user set up one-word routines (한마디 루틴). When the whole of what the user says or sends is one of these words, it is a request to do "
            + "what is written for that word: do it step by step, using your tools where they are needed, then report briefly. When the word only appears inside a longer sentence, it is an ordinary word.\n<routines>");
        for (int i = 0; i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null) text.append("\n- ").append(item.optString("word")).append(": ").append(item.optString("does").replace("\n", " / ")); }
        return text.append("\n</routines>").toString();
    }
    /** "3개 · 퇴근, 출근, 정리" for a settings row; "" when there are none. */
    public static String summary(JSONArray list) {
        if (list == null || list.length() == 0) return "";
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < list.length(); i++) words.append(i == 0 ? "" : ", ").append(list.optJSONObject(i).optString("word"));
        return list.length() + "개 · " + words;
    }
}
