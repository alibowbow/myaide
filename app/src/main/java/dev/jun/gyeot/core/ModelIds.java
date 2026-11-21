package dev.jun.gyeot.core;

import java.util.Map;
import java.util.Set;

/**
 * Saved model choices whose model was replaced or retired. Every place that reads a stored choice (the main chat,
 * each 작업창 room and recording, the browser, the team, the account's own default) passes it through current(), so every
 * picker shows and sends the replacement instead of a name the catalog no longer has.
 */
public final class ModelIds {
    private ModelIds() {}
    /** DEPRECATED Claude ids and what replaces them; the replaced ones are also left out of the catalog (ClaudeProtocol.models). */
    public static final Map<String, String> CLAUDE_REPLACED = Map.of("claude-fable-5-1", "claude-fable-5-2");
    /** DEPRECATED display names a saved team profile may still carry for those models. */
    public static final Set<String> RETIRED_NAMES = Set.of("DeepSeek V4.1 Flash", "MiniMax M3.1 Flash", "Fable 5.1", "Claude Fable 5.1");

    /** The Claude models that stay when older ones go, by family; the first is where a saved choice of no known family goes. */
    private static final String[][] CLAUDE_KEPT = {{"opus", "claude-opus-5-5"}, {"sonnet", "claude-sonnet-5-5"}, {"haiku", "claude-haiku-5-5"}, {"fable", "claude-fable-5-2"}};
    /** What a saved choice of GPT-5.6 or older becomes, by its family's suffix; any other (gpt-5.6, gpt-5.3-codex, gpt-4o) goes to the first. */
    private static final String[][] GPT_KEPT = {{"-terra", "gpt-6.1-terra"}, {"-sol", "gpt-6.1-sol"}, {"-luna", "gpt-6-luna"}};
    private static final java.util.regex.Pattern CLAUDE_VERSION = java.util.regex.Pattern.compile("claude-[a-z]+-(\\d{1,2})(?:-(\\d{1,2}))?(?:-.*)?");

    /**
     * Whether a Claude id names a model older than 5.2, which no picker offers (only Fable 5.2
     * and Opus, Sonnet and Haiku 5.5 stay). Only an id that says its version is judged: claude-<family>-<major>[-<minor>][-date],
     * and the older shapes that begin with the version 1 to 3 or "instant" (claude-3-5-sonnet-…, claude-2.1). An id of another shape is
     * kept, so a model Anthropic names differently later still shows up without an app update.
     */
    public static boolean claudeOld(String id) {
        if (id == null) return false;
        if (id.matches("claude-([1-3]([.-].*)?|instant.*)")) return true;
        java.util.regex.Matcher version = CLAUDE_VERSION.matcher(id);
        if (!version.matches()) return false;
        int major = Integer.parseInt(version.group(1)), minor = version.group(2) == null ? 0 : Integer.parseInt(version.group(2));
        return major < 5 || major == 5 && minor < 2;
    }
    private static final java.util.regex.Pattern GPT_VERSION = java.util.regex.Pattern.compile("gpt-(\\d+)(?:\\.(\\d+))?.*");
    /**
     * Whether a GPT id names GPT-5.6 or an older model (gpt-5.6-sol, gpt-5.5, gpt-5.3-codex, gpt-5, gpt-4o, gpt-4.1, …), which no
     * picker offers. Only an id that says its version after "gpt-" is
     * judged; GPT-6 and later, and ids of another shape, are kept.
     */
    public static boolean gptOld(String id) {
        if (id == null) return false;
        java.util.regex.Matcher version = GPT_VERSION.matcher(id);
        if (!version.matches()) return false;
        int major = Integer.parseInt(version.group(1)), minor = version.group(2) == null ? 0 : Integer.parseInt(version.group(2));
        return major < 5 || major == 5 && minor <= 6;
    }
    /** Whether engine's picker leaves this id out: a replaced or old Claude model, or GPT-5.6 and older. Engine as WorkBook or the browser ("gpt") names it. */
    public static boolean retired(String engine, String id) {
        if (WorkBook.CLAUDE.equals(engine)) return CLAUDE_REPLACED.containsKey(id) || claudeOld(id);
        return (WorkBook.CHATGPT.equals(engine) || "gpt".equals(engine)) && gptOld(id);
    }

    /** The id to show and send for engine's saved id: NIM keeps a model NVIDIA lists, else Nemotron Ultra; a replaced or retired Claude or GPT id becomes its successor. */
    public static String current(String engine, String id) {
        if (id == null || id.isBlank()) return id == null ? "" : id;
        if (WorkBook.NIM.equals(engine)) return NimProtocol.listed(id) ? id : NimProtocol.MODEL;
        if (WorkBook.CLAUDE.equals(engine)) {
            if (CLAUDE_REPLACED.containsKey(id)) return CLAUDE_REPLACED.get(id);
            if (!claudeOld(id)) return id;
            for (String[] kept : CLAUDE_KEPT) if (id.contains(kept[0])) return kept[1];
            return CLAUDE_KEPT[0][1];
        }
        if ((WorkBook.CHATGPT.equals(engine) || "gpt".equals(engine)) && gptOld(id)) {
            for (String[] kept : GPT_KEPT) if (id.endsWith(kept[0]) || id.contains(kept[0] + "-")) return kept[1];
            return GPT_KEPT[0][1];
        }
        return id;
    }
    /** Whether a saved team name is only a retired model's name, not one the user chose. */
    public static boolean retiredName(String name) { return name != null && RETIRED_NAMES.contains(name.strip()); }
}
