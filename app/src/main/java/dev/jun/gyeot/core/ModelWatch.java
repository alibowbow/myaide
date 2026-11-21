package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Newer models a provider lists, found without an app update. Each account reads its provider's model list at most once a
 * day (due), on its own background thread and only while connected: one small GET, nothing kept running between reads. What
 * is found here is only added to the pickers; a model the user chose stays chosen. Pure Java.
 */
public final class ModelWatch {
    private ModelWatch() {}
    public static final long DAY = 24L * 60 * 60 * 1000;
    /** Whether a list read at lastAt (wall clock) is to be read again now: never read, a clock that went back, or a day old. */
    public static boolean due(long lastAt, long now) { return lastAt <= 0 || now < lastAt || now - lastAt >= DAY; }

    /** The ids in a list response: OpenAI-style data[].id, or models[].id / models[].name ("models/…" dropped). */
    static List<String> ids(JSONObject body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        for (String key : new String[]{"data", "models"}) {
            JSONArray list = body.optJSONArray(key);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i); if (item == null) continue;
                String id = item.optString("id", item.optString("name")).replaceFirst("^models/", "").strip();
                if (!id.isEmpty() && !out.contains(id)) out.add(id);
            }
        }
        return out;
    }
    private static String words(String suffix) {
        StringBuilder out = new StringBuilder();
        for (String word : suffix.split("-")) if (!word.isEmpty()) out.append(' ').append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        return out.toString();
    }

    private static final Pattern GROK = Pattern.compile("^grok-(\\d+)(?:[.-](\\d+))?(?:-([a-z][a-z0-9-]*))?$");
    private static final Pattern NOT_CHAT = Pattern.compile("image|imagine|video|voice|vision|embed|tts|audio|realtime|transcribe|speech|latest");
    /**
     * xAI's chat models as new as builtIn or newer (grok-<major>[.<minor>][-words], not image, video, voice or other kinds),
     * newest first, at most 8, with builtIn always among them.
     */
    public static JSONArray grok(JSONObject body, String builtIn, String builtInName) {
        record Found(String id, int version, String shown, String suffix) {}
        Matcher base = GROK.matcher(builtIn); int floor = base.matches() ? version(base) : 0;
        List<Found> found = new ArrayList<>();
        for (String id : ids(body)) {
            Matcher m = GROK.matcher(id.toLowerCase(Locale.ROOT));
            if (!m.matches() || !id.equals(id.toLowerCase(Locale.ROOT))) continue;
            String suffix = m.group(3) == null ? "" : m.group(3);
            if (NOT_CHAT.matcher(suffix).find() || version(m) < floor) continue;
            found.add(new Found(id, version(m), m.group(1) + (m.group(2) == null ? "" : "." + m.group(2)), suffix));
        }
        if (found.stream().noneMatch(f -> f.id.equals(builtIn))) found.add(new Found(builtIn, floor, "", ""));
        found.sort((a, b) -> a.version != b.version ? Integer.compare(b.version, a.version) : a.suffix.isEmpty() != b.suffix.isEmpty() ? (a.suffix.isEmpty() ? -1 : 1) : a.id.compareTo(b.id));
        JSONArray out = arr();
        for (Found f : found) {
            if (out.length() >= 8) break;
            String name = f.id.equals(builtIn) ? builtInName : "Grok " + f.shown + words(f.suffix);
            out.put(obj("id", f.id, "name", name));
        }
        return out;
    }
    private static int version(Matcher m) { return Integer.parseInt(m.group(1)) * 100 + (m.group(2) == null ? 0 : Math.min(99, Integer.parseInt(m.group(2)))); }

    private static final Pattern NEMOTRON = Pattern.compile("^nvidia/nemotron-(\\d+)(?:\\.(\\d+))?-(ultra|super)(-[a-z0-9.-]+)?$");
    /**
     * NVIDIA's Nemotron chat models of builtIn's line (nvidia/nemotron-<generation>-ultra|super…) as new as builtIn or newer,
     * newest first and Ultra before Super, at most 6, with builtIn always among them.
     */
    public static JSONArray nim(JSONObject body, String builtIn, String builtInName) {
        record Found(String id, int version, boolean ultra) {}
        Matcher base = NEMOTRON.matcher(builtIn); int floor = base.matches() ? version(base) : 0;
        List<Found> found = new ArrayList<>();
        for (String id : ids(body)) {
            Matcher m = NEMOTRON.matcher(id);
            if (!m.matches() || version(m) < floor) continue;
            found.add(new Found(id, version(m), "ultra".equals(m.group(3))));
        }
        if (found.stream().noneMatch(f -> f.id.equals(builtIn))) found.add(new Found(builtIn, floor, true));
        found.sort((a, b) -> a.version != b.version ? Integer.compare(b.version, a.version) : a.ultra != b.ultra ? (a.ultra ? -1 : 1) : a.id.compareTo(b.id));
        JSONArray out = arr();
        for (Found f : found) {
            if (out.length() >= 6) break;
            String name = f.id.equals(builtIn) ? builtInName
                : "Nemotron " + (f.version / 100) + (f.version % 100 == 0 ? "" : "." + (f.version % 100)) + (f.ultra ? " Ultra" : " Super");
            out.put(obj("id", f.id, "name", name));
        }
        return out;
    }

    private static final Pattern LIVE = Pattern.compile("^gemini-(\\d+)(?:\\.(\\d+))?-live(-extended-thinking)?$");
    /**
     * Gemini Live models newer than newerThan (gemini-<version>-live, and its -extended-thinking), as {id, label} for the
     * Live model choices, newest first; the listed one takes live audio (bidiGenerateContent) when the list says what it takes.
     */
    public static List<String[]> geminiLive(JSONObject body, String newerThan) {
        Matcher floorMatch = LIVE.matcher(newerThan == null ? "" : newerThan); int floor = floorMatch.matches() ? version(floorMatch) : 0;
        record Found(String id, int version, boolean extended) {}
        List<Found> found = new ArrayList<>();
        JSONArray models = body == null ? null : body.optJSONArray("models");
        for (int i = 0; models != null && i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i); if (model == null) continue;
            String id = model.optString("name").replaceFirst("^models/", "");
            JSONArray methods = model.optJSONArray("supportedGenerationMethods");
            if (methods != null && !methods.toString().contains("\"bidiGenerateContent\"")) continue;
            Matcher m = LIVE.matcher(id);
            if (!m.matches() || version(m) <= floor) continue;
            found.add(new Found(id, version(m), m.group(3) != null));
        }
        found.sort((a, b) -> a.version != b.version ? Integer.compare(b.version, a.version) : a.extended != b.extended ? (a.extended ? -1 : 1) : 0);
        List<String[]> out = new ArrayList<>();
        for (Found f : found) out.add(new String[]{f.id, "Gemini " + (f.version / 100) + (f.version % 100 == 0 ? "" : "." + (f.version % 100)) + " Live" + (f.extended ? " Extended Thinking" : "") + " (새 모델)"});
        return out;
    }
}
