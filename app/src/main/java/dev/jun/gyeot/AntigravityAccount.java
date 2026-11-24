package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import dev.jun.gyeot.core.Reasoning;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Antigravity as one of the app's engines (WorkBook.ANTIGRAVITY), beside ChatGPT, Claude, Gemini, Grok and NIM: the
 * official agy CLI in the app's Linux (AntigravityCli), signed in with the user's Antigravity subscription. Its models are
 * agy's own list (agy models: Gemini, Claude and GPT models, the reasoning level in each name), shown as agy names them;
 * the one chosen goes with --model. Answers are text only: agy is asked once per turn, without the app's tools.
 */
final class AntigravityAccount {
    /** The model id that sends no --model: agy's own default. */
    static final String DEFAULT = "agy-default";
    private static final Pattern MODEL_LINE = Pattern.compile("^\\s*([a-z][a-z0-9._-]+)\\s+(.+)$"), SAFE = Pattern.compile("[a-z0-9][a-z0-9._-]{1,120}");
    private final Context context; private final SharedPreferences settings; private final Runnable changed;
    AntigravityAccount(Context context, SharedPreferences settings, Runnable changed) { this.context = context; this.settings = settings; this.changed = changed; }
    Context context() { return context; }

    /** Installed and signed in at the last check (설정 → Antigravity · 상태 확인). */
    boolean connected() { return settings.getBoolean("agySignedIn", false) && AntigravityCli.installed(context); }
    /** agy's models as [{id, name}], the default first; the list read at the last check. */
    /**
     * agy's models as the other engines show theirs: one entry per model ({id, name}), its reasoning levels (agy lists them
     * as variants, "Gemini 4 Neon (High)") under 생각 깊이 instead of as models of their own. The id is the variant sent when
     * no level is chosen. "agy 기본 모델" only while agy has listed none.
     */
    JSONArray models() {
        JSONArray out = new JSONArray();
        for (Group g : groups()) out.put(item(g.base(), g.name()));
        if (out.length() == 0) out.put(item(DEFAULT, "agy 기본 모델"));
        return out;
    }
    /** One model of agy's list with its reasoning variants: level (lower case, as agy names it) → the slug agy takes. */
    record Group(String name, java.util.LinkedHashMap<String, String> levels, String plain) {
        /** The slug used for the model itself: its plain one, else the high variant, else the first. */
        String base() { return plain != null ? plain : levels.containsKey("high") ? levels.get("high") : levels.values().iterator().next(); }
        String defaultLevel() { return plain != null || levels.isEmpty() ? "" : levels.containsKey("high") ? "high" : levels.keySet().iterator().next(); }
    }
    private static final Pattern VARIANT = Pattern.compile("^(.*?)\\s*\\(\\s*(none|minimal|low|medium|high|xhigh|max|ultra)\\s*\\)\\s*$", Pattern.CASE_INSENSITIVE);
    List<Group> groups() {
        java.util.LinkedHashMap<String, Group> byName = new java.util.LinkedHashMap<>();
        try {
            JSONArray saved = new JSONArray(settings.getString("agyModels", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject o = saved.optJSONObject(i); if (o == null) continue;
                String id = o.optString("id"), name = o.optString("name", id); Matcher v = VARIANT.matcher(name);
                String base = v.matches() ? v.group(1).strip() : name.strip(), level = v.matches() ? v.group(2).toLowerCase(java.util.Locale.ROOT) : null;
                Group g = byName.get(base);
                if (g == null) { g = new Group(base, new java.util.LinkedHashMap<>(), level == null ? id : null); byName.put(base, g); }
                else if (level == null && g.plain() == null) { g = new Group(base, g.levels(), id); byName.put(base, g); }
                if (level != null) g.levels().putIfAbsent(level, id);
            }
        } catch (Exception ignored) {}
        return new java.util.ArrayList<>(byName.values());
    }
    /** The model groups' limits as last read (AntigravityCli.status), and when; none before the first read. */
    List<String> usage() {
        List<String> out = new java.util.ArrayList<>();
        try { JSONArray saved = new JSONArray(settings.getString("agyUsage", "[]")); for (int i = 0; i < saved.length(); i++) out.add(saved.optString(i)); } catch (Exception ignored) {}
        return out;
    }
    long usageAt() { return settings.getLong("agyUsageAt", 0); }
    /** The model a slug belongs to (a variant's slug finds its model too), or null. */
    Group group(String id) { for (Group g : groups()) if (id.equals(g.plain()) || g.levels().containsValue(id)) return g; return null; }
    String model() { String m = settings.getString("agyModel", ""); Group g = group(m); return g != null ? g.base() : models().optJSONObject(0).optString("id", DEFAULT); }
    void model(String id) { Group g = group(id); settings.edit().putString("agyModel", g == null ? DEFAULT : g.base()).apply(); changed.run(); }
    boolean listed(String id) { return DEFAULT.equals(id) || group(id) != null; }
    String label(String id) { Group g = group(id); return g != null ? g.name() : DEFAULT.equals(id) ? "agy 기본 모델" : id; }
    /** 생각 깊이 as the other engines offer it: the model's own variants (or, for a model without them, --effort's levels). */
    Reasoning.Profile profile(String model) {
        Group g = group(model == null ? "" : model);
        if (g != null && !g.levels().isEmpty()) return Reasoning.Profile.of(new java.util.ArrayList<>(g.levels().keySet()), g.defaultLevel());
        List<String> levels = new java.util.ArrayList<>();
        try { JSONArray saved = new JSONArray(settings.getString("agyEfforts", "[]")); for (int i = 0; i < saved.length(); i++) levels.add(saved.optString(i)); } catch (Exception ignored) {}
        return Reasoning.Profile.of(levels, "");
    }
    /** The slug agy is asked for: the model's variant for the chosen level (its default when none is chosen). */
    String slug(String model, String effort) {
        Group g = group(model == null ? "" : model); if (g == null) return model;
        if (g.levels().isEmpty()) return g.base();
        String level = profile(model).resolve(effort == null ? "" : effort);
        return g.levels().getOrDefault(level, g.base());
    }
    /** --effort: only for a model agy lists without variants (a variant's level is in its slug already). */
    String flag(String model, String effort) {
        Group g = group(model == null ? "" : model);
        if (g != null && !g.levels().isEmpty()) return "";
        return profile(model).normalize(effort == null ? "" : effort);
    }
    /** The level chosen in 설정 for agy's own default; a room's own choice (ScopedModels) goes first. */
    String effort(String model) { String e = settings.getString("agyEffort", ""); return profile(model).normalize(e); }
    private static final Pattern EFFORT_WORDS = Pattern.compile("\\b(none|minimal|low|medium|high|xhigh|max|ultra)\\b");
    private static JSONObject item(String id, String name) { try { return new JSONObject().put("id", id).put("name", name); } catch (Exception e) { return new JSONObject(); } }

    /**
     * Reads agy's state again: signed in or not (and the plan's limits, AntigravityCli.status), and its model list. Blocking:
     * a background thread. The settings page and the model pickers read what it keeps.
     */
    AntigravityCli.Status refresh() {
        AntigravityCli.Status st = AntigravityCli.status(context);
        SharedPreferences.Editor edit = settings.edit();
        // The model groups' limits, kept for 설정 → 사용량 · 남은 한도 (UsageUi), with when they were read.
        if (!st.usage().isEmpty()) edit.putString("agyUsage", new JSONArray(st.usage()).toString()).putLong("agyUsageAt", System.currentTimeMillis());
        if (st.signedIn() != null) edit.putBoolean("agySignedIn", st.installed() && st.signedIn());
        if (st.installed() && Boolean.TRUE.equals(st.signedIn())) {
            try {
                LinuxRuntime.Result r = LinuxRuntime.run(context, AntigravityCli.BINARY + " models </dev/null", 90_000, AntigravityCli.env());
                if (r.code() == 0) {
                    JSONArray list = new JSONArray();
                    for (String line : LinuxTerminal.plain(r.output()).split("\n")) {
                        Matcher m = MODEL_LINE.matcher(line);
                        if (m.matches() && SAFE.matcher(m.group(1)).matches() && !m.group(1).equals(DEFAULT)) list.put(item(m.group(1), m.group(2).strip()));
                    }
                    if (list.length() > 0) edit.putString("agyModels", list.toString()).putLong("agyModelsAt", System.currentTimeMillis());
                }
            } catch (Exception ignored) { /* The list read before stays. */ }
        }
        if (st.installed()) {
            // --effort and the levels agy lists for it, as its help states them.
            try {
                LinuxRuntime.Result h = LinuxRuntime.run(context, AntigravityCli.BINARY + " --help 2>&1 </dev/null", 60_000, AntigravityCli.env());
                JSONArray levels = new JSONArray();
                for (String line : LinuxTerminal.plain(h.output()).split("\n")) {
                    if (!line.contains("--effort")) continue;
                    Matcher m = EFFORT_WORDS.matcher(line.substring(line.indexOf("--effort")));
                    java.util.Set<String> seen = new java.util.LinkedHashSet<>(); while (m.find()) seen.add(m.group(1));
                    for (String level : seen) levels.put(level);
                    break;
                }
                edit.putString("agyEfforts", levels.toString());
            } catch (Exception ignored) {}
        }
        edit.apply(); changed.run();
        return st;
    }

    /** One answer from agy on the chosen model: the instructions and the input in one message, no tools. Blocking. */
    String ask(String model, String instructions, String input, AtomicReference<Runnable> cancel) throws Exception {
        String prompt = (instructions == null || instructions.isBlank() ? "" : instructions.strip() + "\n\n") + (input == null ? "" : input.strip());
        return AgyRunner.run(context, slug(model, effort(model)), flag(model, effort(model)), "", prompt, null, null, null, null, cancel, 300_000).text();
    }

    // ── a room's own agy conversation (--conversation), so a turn sends only what is new ──
    record Session(String id, int sent, String first, String model) {}
    /** The conversation kept for key, or null. */
    Session session(String key) {
        try { JSONObject all = new JSONObject(settings.getString("agySessions", "{}")); JSONObject s = all.optJSONObject(key); if (s == null) return null;
            return new Session(s.optString("id"), s.optInt("sent"), s.optString("first"), s.optString("model")); } catch (Exception e) { return null; }
    }
    void remember(String key, Session s) {
        try {
            JSONObject all = new JSONObject(settings.getString("agySessions", "{}"));
            if (s == null) all.remove(key); else all.put(key, new JSONObject().put("id", s.id()).put("sent", s.sent()).put("first", s.first()).put("model", s.model()).put("at", System.currentTimeMillis()));
            // At most the 60 newest rooms are remembered.
            while (all.length() > 60) { String oldest = null; long at = Long.MAX_VALUE;
                for (java.util.Iterator<String> k = all.keys(); k.hasNext();) { String name = k.next(); long t = all.optJSONObject(name).optLong("at"); if (t < at) { at = t; oldest = name; } }
                all.remove(oldest); }
            settings.edit().putString("agySessions", all.toString()).apply();
        } catch (Exception ignored) {}
    }
}
