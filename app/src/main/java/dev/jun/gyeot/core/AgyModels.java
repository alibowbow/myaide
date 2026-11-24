package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * agy models' list read into {slug, name}: newer agy prints one slug per line ("gemini-3.8-flash-high"), older agy a slug and
 * its name ("gemini-3-pro-high  Gemini 3 Pro (High)"). The app read only the second, so with a newer agy it read nothing and
 * kept an old list (준: "지금쓰는 모델들이 아냐"). A bare slug gets a name made from it: claude-opus-5-5-thinking → "Claude Opus
 * 5.5 (Thinking)", gemini-4-neon-xhigh → "Gemini 4 Neon (Xhigh)", whose reasoning level AntigravityAccount groups. Pure Java.
 */
public final class AgyModels {
    private AgyModels() {}
    private static final Pattern ESCAPES = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+");
    private static final Pattern PAIR = Pattern.compile("^([a-z][a-z0-9._-]+)\\s+(.+)$");
    private static final java.util.Set<String> LEVELS = java.util.Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra", "thinking");
    private static final Map<String, String> WORDS = Map.of("gpt", "GPT", "oss", "OSS", "ai", "AI", "agy", "agy");

    /** The models agy listed, slug → name, in its order; none when the text is not a list (an error, a sign-in request). */
    public static LinkedHashMap<String, String> parse(String text) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String plain = ESCAPES.matcher(text == null ? "" : text).replaceAll("").replace('\r', '\n');
        for (String raw : plain.split("\n")) {
            String line = raw.replaceAll("[│┃|*•>]", " ").replaceAll("(?i)\\(default\\)|\\bdefault\\b|\\bcurrent\\b", " ").strip();
            if (line.isEmpty()) continue;
            Matcher pair = PAIR.matcher(line);
            if (pair.matches() && SLUG.matcher(pair.group(1)).matches() && !pair.group(2).isBlank() && !pair.group(2).matches("[a-z0-9._ -]+")) {
                out.putIfAbsent(pair.group(1), pair.group(2).strip());
                continue;
            }
            String first = line.split("\\s+")[0];
            if (SLUG.matcher(first).matches() && first.length() <= 80 && line.split("\\s+").length <= 2) out.putIfAbsent(first, name(first));
        }
        return out;
    }
    /** A readable name for a slug: claude-sonnet-5-5 → "Claude Sonnet 5.5", gemini-3.8-flash-high → "Gemini 3.8 Flash (High)". */
    public static String name(String slug) {
        List<String> parts = new ArrayList<>(List.of(slug.toLowerCase(Locale.ROOT).split("[-_]")));
        String level = parts.size() > 1 && LEVELS.contains(parts.get(parts.size() - 1)) ? parts.remove(parts.size() - 1) : "";
        List<String> words = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            // "5-5" → "5.5": a version written with dashes.
            if (p.matches("\\d+") && !words.isEmpty() && words.get(words.size() - 1).matches("\\d+(?:\\.\\d+)?") && !words.get(words.size() - 1).contains(".") && p.length() <= 2) {
                words.set(words.size() - 1, words.get(words.size() - 1) + "." + p); continue;
            }
            words.add(word(p));
        }
        String name = String.join(" ", words).replace("GPT OSS", "GPT-OSS");
        return level.isEmpty() ? name : name + " (" + Character.toUpperCase(level.charAt(0)) + level.substring(1) + ")";
    }
    private static String word(String p) {
        if (WORDS.containsKey(p)) return WORDS.get(p);
        if (p.matches("\\d+[bkm]")) return p.toUpperCase(Locale.ROOT);
        if (p.matches("[\\d.]+")) return p;
        return Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }
}
