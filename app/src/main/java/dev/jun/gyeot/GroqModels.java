package dev.jun.gyeot;

import android.content.SharedPreferences;
import dev.jun.gyeot.core.Reasoning;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Groq as one of the app's engines (WorkBook.GROQ), beside ChatGPT, Claude, Gemini, Grok, NIM and Antigravity: the chat
 * models the user's Groq key lists (GroqAccount), GPT OSS 120B first, each named plainly; GPT OSS takes a reasoning level
 * (낮음 · 보통 · 높음). Answers are text only, within Groq's per-minute token allowance (GroqWork, GeminiQuotas).
 */
final class GroqModels {
    static final String DEFAULT = GroqWork.CHORES;
    private final GroqAccount account; private final SharedPreferences settings; private final Runnable changed;
    GroqModels(GroqAccount account, SharedPreferences settings, Runnable changed) { this.account = account; this.settings = settings; this.changed = changed; }

    boolean connected() { return account.connected(); }
    /** The key's chat models as [{id, name}]: speech, voices and guard models left out; GPT OSS first, compound last. */
    JSONArray models() {
        List<String> ids = new ArrayList<>(); JSONArray listed = account.models();
        for (int i = 0; i < listed.length(); i++) { String id = listed.optString(i); if (chat(id) && !ids.contains(id)) ids.add(id); }
        if (ids.isEmpty()) ids.add(DEFAULT);
        ids.sort(java.util.Comparator.comparingInt(GroqModels::rank).thenComparing(id -> id));
        JSONArray out = new JSONArray();
        for (String id : ids) try { out.put(new JSONObject().put("id", id).put("name", label(id))); } catch (Exception ignored) {}
        return out;
    }
    static boolean chat(String id) {
        String l = id == null ? "" : id.toLowerCase(Locale.ROOT);
        return !l.isBlank() && !l.contains("whisper") && !l.contains("tts") && !l.contains("orpheus") && !l.contains("playai") && !l.contains("guard");
    }
    private static int rank(String id) {
        String l = id.toLowerCase(Locale.ROOT);
        return l.contains("gpt-oss-120b") ? 0 : l.contains("gpt-oss") ? 1 : l.contains("llama") ? 2 : l.contains("qwen") ? 3 : l.contains("kimi") ? 4 : l.startsWith("groq/") ? 9 : 5;
    }
    /** A model's plain name: "openai/gpt-oss-120b" → GPT OSS 120B, "llama-3.3-70b-versatile" → Llama 3.3 70B. */
    static String label(String id) {
        String name = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
        if (id.startsWith("groq/")) return "Compound" + (name.contains("mini") ? " Mini" : "") + " (검색)";
        name = name.replace("-versatile", "").replace("-instant", "").replace("-instruct", "");
        StringBuilder out = new StringBuilder();
        for (String part : name.split("[-_]")) {
            if (part.isEmpty()) continue;
            String shown = part.matches("\\d+[bmk]") ? part.toUpperCase(Locale.ROOT)
                : part.equalsIgnoreCase("gpt") || part.equalsIgnoreCase("oss") ? part.toUpperCase(Locale.ROOT)
                : Character.toUpperCase(part.charAt(0)) + part.substring(1);
            out.append(out.length() == 0 ? "" : " ").append(shown);
        }
        return out.toString();
    }
    String model() { String m = settings.getString("groqModel", DEFAULT); return m.isBlank() ? DEFAULT : m; }
    void model(String id) { settings.edit().putString("groqModel", id == null || id.isBlank() ? DEFAULT : id).apply(); changed.run(); }
    /** GPT OSS takes reasoning_effort low · medium · high (Groq's default medium); the others none. */
    Reasoning.Profile profile(String model) {
        return model != null && model.startsWith("openai/gpt-oss") ? Reasoning.Profile.of(List.of("low", "medium", "high"), "medium") : Reasoning.Profile.of(List.of(), "");
    }
    String effort(String model) { return profile(model).normalize(settings.getString("groqEffort", "")); }

    /** One answer: the instructions and the input, on the model at the level chosen. Blocking. */
    String ask(String model, String effort, String instructions, String input, AtomicReference<Runnable> cancel) throws Exception {
        String m = model == null || model.isBlank() ? DEFAULT : model;
        int tokens = ((instructions == null ? 0 : instructions.length()) + (input == null ? 0 : input.length())) * 2 / 3 + 2000;
        if (!GroqWork.ready(m, tokens)) throw new IllegalStateException("Groq 한도(분당 토큰)를 기다리는 중이에요. 잠시 뒤 다시 보내거나, 대화가 길면 다른 AI를 골라 주세요.");
        String level = profile(m).resolve(effort == null ? "" : effort);
        JSONArray turns = new JSONArray().put(new JSONObject().put("role", "user").put("content", input == null ? "" : input));
        return GroqWork.converse(m, instructions == null ? "" : instructions, turns, level.isEmpty() ? null : level, 0.5, 120_000, cancel);
    }
}
