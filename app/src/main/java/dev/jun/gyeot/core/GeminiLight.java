package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Light Gemini work (대화 제목, 기억 추출): small jobs the app runs often on its own. They go to Gemma 4 first, whose free tier
 * allows 14,400 requests a day for each size but only 16,000 tokens a minute, and to Gemini Flash-Lite (500 a day) when Gemma
 * cannot take it now: this key cannot use it, its day or minute is used up, or the request is too big for its minute
 * (GeminiQuota.order with the estimate). Gemma on the Gemini API takes no system instruction, so the instructions go at the top
 * of the one user turn, which Flash-Lite reads the same way. Pure Java.
 */
public final class GeminiLight {
    private GeminiLight() {}
    /** Gemma 4's ids on the Gemini API as guessed, larger first; the key's own model list replaces them once read. */
    public static final String[] GEMMA = {"gemma-4-31b-it", "gemma-4-26b-a4b-it"};
    /** Room kept for the answer in the estimate (a title, or a few memories as JSON). */
    static final int ANSWER_TOKENS = 1_500;

    /**
     * The Gemma 4 models a key lists (models/gemma-4-…, generateContent), larger first: dense 31B before 26B, instruction-tuned
     * ("-it") before others; null when the list has none (not read yet, or Google offers none to this key).
     */
    public static String[] gemma(JSONObject list) {
        JSONArray found = list == null ? null : list.optJSONArray("models");
        List<String> ids = new ArrayList<>();
        for (int i = 0; found != null && i < found.length(); i++) {
            JSONObject model = found.optJSONObject(i); if (model == null) continue;
            String id = model.optString("name").replaceFirst("^models/", "");
            JSONArray methods = model.optJSONArray("supportedGenerationMethods");
            boolean generates = methods == null || methods.toString().contains("\"generateContent\"");
            if (generates && id.startsWith("gemma-4-") && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) return null;
        ids.sort((a, b) -> size(b) != size(a) ? Integer.compare(size(b), size(a)) : Boolean.compare(b.endsWith("-it"), a.endsWith("-it")));
        return ids.toArray(new String[0]);
    }
    /** The billions in a Gemma id ("gemma-4-31b-it": 31; the total, not the active "a4b"), 0 when it names none. */
    static int size(String id) {
        for (String part : id.toLowerCase(Locale.ROOT).split("-")) if (part.matches("\\d+b")) return Integer.parseInt(part.substring(0, part.length() - 1));
        return 0;
    }
    /** The models to ask, best first: the Gemma ones given (or the guessed), then Flash-Lite. */
    public static String[] models(String[] gemma) {
        List<String> all = new ArrayList<>(List.of(gemma == null || gemma.length == 0 ? GEMMA : gemma));
        for (String model : GeminiWork.FLASH_LITE) if (!all.contains(model)) all.add(model);
        return all.toArray(new String[0]);
    }
    /** One user turn: the instructions, then the material. */
    public static JSONObject request(String instructions, String input) {
        String text = instructions.strip() + "\n\n---\n\n" + input;
        return obj("contents", arr(obj("role", "user", "parts", arr(obj("text", text)))));
    }
    /**
     * The tokens a request may take, high rather than low so a minute is not overrun: about one token for every one and a
     * half characters of Korean (Latin text takes fewer), plus room for the answer.
     */
    public static int tokens(String instructions, String input) {
        long chars = (long) (instructions == null ? 0 : instructions.length()) + (input == null ? 0 : input.length());
        return (int) Math.min(Integer.MAX_VALUE, chars * 2 / 3 + ANSWER_TOKENS);
    }
}
