package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiQuota;
import java.util.List;

/**
 * Gemini 무료 한도 for the whole app (core/GeminiQuota): the order to try models in now, and what each request taught,
 * kept in AppStore so every caller shares it. Calls made outside the app's process (none today) or before GyeotApp made its
 * store simply go in the order given and keep nothing. Any thread.
 */
final class GeminiQuotas {
    private GeminiQuotas() {}
    private static volatile AppStore store;
    /** GyeotApp, once its store exists. */
    static void use(AppStore value) { store = value; }

    /** The models to try now, in the given order, leaving out those whose free quota is used up or resting. */
    static List<String> order(String[] models) {
        AppStore s = store;
        return s == null ? List.of(models) : GeminiQuota.order(models, s.geminiQuota(), System.currentTimeMillis());
    }
    /** As order(models), also leaving out models whose tokens a minute this request (an estimate) would go past. */
    static List<String> order(String[] models, int tokens) {
        AppStore s = store;
        return s == null ? List.of(models) : GeminiQuota.order(models, s.geminiQuota(), System.currentTimeMillis(), tokens);
    }
    /** The model answered: one more request today. */
    static void used(String model) { used(model, 0); }
    /** The model answered, counting the tokens it said it took (0 when not given). */
    static void used(String model, int tokens) {
        AppStore s = store; if (s == null) return;
        s.changeGeminiQuota(state -> { GeminiQuota.used(state, model, System.currentTimeMillis(), tokens); return null; }, false);
    }
    /** The Gemma 4 models the key lists (GeminiLight), or null to go with the guessed ones. */
    static String[] gemma() {
        AppStore s = store;
        try { return s == null ? null : s.gemini.gemma(); } catch (RuntimeException e) { return null; }
    }
    /** Google refused it (status and error body): true when the next model should be tried (quota used up, resting, or not usable). */
    static boolean refused(String model, int status, String body) {
        AppStore s = store; if (s == null) return false;
        return s.changeGeminiQuota(state -> GeminiQuota.refused(state, model, status, body, System.currentTimeMillis()), true);
    }
    /** The first of models this key can use, or null when it can use none (no store: the first). */
    static String usable(String[] models) {
        AppStore s = store;
        return s == null ? models[0] : GeminiQuota.usable(models, s.geminiQuota());
    }
    /** The model can take a request of about tokens now (no store: yes). */
    static boolean ready(String model, int tokens) {
        AppStore s = store;
        return s == null || GeminiQuota.ready(model, s.geminiQuota(), System.currentTimeMillis(), tokens);
    }
    /** Requests to the model today, and its free requests a day (0: not known). */
    static int uses(String model) { AppStore s = store; return s == null ? 0 : GeminiQuota.uses(s.geminiQuota(), model, System.currentTimeMillis()); }
    static int perDay(String model) { AppStore s = store; return s == null ? 0 : GeminiQuota.perDay(s.geminiQuota(), model); }
    /** count requests the model answered at once (a batch), with the tokens they took together. */
    static void used(String model, int tokens, int count) {
        AppStore s = store; if (s == null || count <= 0) return;
        s.changeGeminiQuota(state -> { long now = System.currentTimeMillis(); for (int i = 0; i < count; i++) GeminiQuota.used(state, model, now, i == 0 ? tokens : 0); return null; }, false);
    }
    /** The app's store, for the Groq side (GroqWork), once GyeotApp made it. */
    static AppStore store() { return store; }
    /**
     * Groq refused (status and body): a day's limit (requests or tokens a day) leaves the model out until the day turns, a
     * minute's rests it as long as Groq said. True when it was a limit, so the next choice should be tried.
     */
    static boolean refusedGroq(String model, int status, String body) {
        AppStore s = store; if (s == null || status != 429) return false;
        String text = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        boolean day = text.contains("per day") || text.contains("(rpd)") || text.contains("(tpd)") || text.contains("(asd)");
        return s.changeGeminiQuota(state -> {
            long now = System.currentTimeMillis();
            if (day) GeminiQuota.full(state, model, now); else GeminiQuota.rest(state, model, now, GeminiQuota.groqRetry(body));
            return true;
        }, true);
    }
}
