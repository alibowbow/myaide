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
    /** The model answered: one more request today. */
    static void used(String model) {
        AppStore s = store; if (s == null) return;
        s.changeGeminiQuota(state -> { GeminiQuota.used(state, model, System.currentTimeMillis()); return null; }, false);
    }
    /** Google refused it (status and error body): true when the next model should be tried (quota used up, resting, or not usable). */
    static boolean refused(String model, int status, String body) {
        AppStore s = store; if (s == null) return false;
        return s.changeGeminiQuota(state -> GeminiQuota.refused(state, model, status, body, System.currentTimeMillis()), true);
    }
}
