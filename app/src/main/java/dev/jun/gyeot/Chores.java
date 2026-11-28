package dev.jun.gyeot;

import dev.jun.gyeot.core.BrainRoutes;
import dev.jun.gyeot.core.Fallback;
import dev.jun.gyeot.core.GeminiLight;
import dev.jun.gyeot.core.GeminiWork;
import dev.jun.gyeot.core.Usage;
import dev.jun.gyeot.core.WorkBook;

/**
 * 잡일 (titles, memory, summaries, cards, reviews, 기억 정리, 알림 요약): 두뇌 배분's LIGHT kind (core/BrainRoutes.LIGHT_ORDER).
 * Groq GPT OSS 120B first, always (the user's choice: Gemini's free tier, Gemma 4 included, may use what is sent to improve
 * Google's models), then Gemma 4, then Flash-Lite only when Groq cannot take it. Material too long for Groq's tokens a minute
 * is shortened for it (its start and its newest part kept). A step without its key, or past its free tier, is skipped; the
 * last reason is thrown when none answered. A content refusal is not asked of the next step (core/Fallback.refused). The
 * step that answered is counted in the usage ledger as 잡일, under the model that really answered. Blocking.
 * waiting is kept for callers (a button pressed or not); the order no longer depends on it.
 */
final class Chores {
    private Chores() {}
    static String run(AppStore store, String instructions, String input, boolean waiting) throws Exception { return run(store, instructions, input, waiting, true); }
    /**
     * As above; geminiOk false: Groq only, never Gemini's free tier (퍼레와 대화's summary of the talk: the user's own words, which
     * that tier may use to improve Google's models). Throws when Groq is not connected or cannot take it now.
     */
    static String run(AppStore store, String instructions, String input, boolean waiting, boolean geminiOk) throws Exception {
        String gemini = ""; if (geminiOk) try { gemini = store.secrets.workKey(); } catch (Exception e) { gemini = ""; }
        boolean groq = store.groq.connected();
        if (gemini.isBlank() && !groq) throw new IllegalStateException(geminiOk ? "설정 → Gemini 또는 Groq API 키를 먼저 넣어 주세요." : "설정 → Groq API 키를 먼저 넣어 주세요.");
        String[] listed = GeminiQuotas.gemma(), gemma = listed == null || listed.length == 0 ? GeminiLight.GEMMA : listed;
        Exception last = null;
        for (String step : BrainRoutes.LIGHT_ORDER) {
            try {
                switch (step) {
                    case "groq" -> {
                        if (!groq) continue;
                        GroqWork.USED.remove();
                        String said = GroqWork.chores(instructions, forGroq(instructions, input));
                        UsageLedger.count(store, UsageLedger.CHORES, WorkBook.GROQ, GroqWork.CHORES, GroqWork.USED.get());
                        return said;
                    }
                    case "gemma" -> { if (!gemini.isBlank()) return counted(store, GeminiAudioClient.light(gemini, instructions, input, gemma)); }
                    default -> { if (!gemini.isBlank()) return counted(store, GeminiAudioClient.light(gemini, instructions, input, GeminiWork.FLASH_LITE)); }
                }
            } catch (Exception e) {
                // The user's stop, or the model declining what was asked: the next model is not asked instead.
                if (Fallback.cancelled(e) || Fallback.refused(e)) throw e;
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("잡일을 맡을 모델이 없어요.");
    }
    /** A Gemini answer counted under the model that gave it (GeminiAudioClient.SERVED). */
    private static String counted(AppStore store, String said) {
        Object[] served = GeminiAudioClient.SERVED.get(); GeminiAudioClient.SERVED.remove();
        UsageLedger.count(store, UsageLedger.CHORES, WorkBook.GEMINI, served == null ? "" : String.valueOf(served[0]), served == null ? Usage.UNKNOWN : (Usage) served[1]);
        return said;
    }
    /** Groq's free tier takes 8,000 tokens a minute: longer material keeps its first part and its newest (the end). */
    static final int GROQ_CHARS = 8_500;
    static String forGroq(String instructions, String input) {
        int room = Math.max(2_000, GROQ_CHARS - instructions.length());
        if (input.length() <= room) return input;
        int head = room / 3, tail = room - head - 40;
        return input.substring(0, head) + "\n…(길어서 가운데를 줄였어요)…\n" + input.substring(input.length() - tail);
    }
}
