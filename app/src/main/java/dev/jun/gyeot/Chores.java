package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiLight;
import dev.jun.gyeot.core.GeminiWork;
import java.util.ArrayList;
import java.util.List;

/**
 * 잡일 (titles, memory, summaries, cards, reviews, 기억 정리, 알림 요약): sent to the free tiers in the order that fits who is
 * waiting. The user waiting (a button pressed): Groq GPT OSS 120B first (an answer in about a second), then Gemma 4, then
 * Flash-Lite. Nobody waiting (after a call, the morning job): Gemma 4 first (14,400 a day), then Groq, then Flash-Lite.
 * A step without its key, or past its free tier, is skipped; the last reason is thrown when none answered. Blocking.
 */
final class Chores {
    private Chores() {}
    static String run(AppStore store, String instructions, String input, boolean waiting) throws Exception {
        String gemini; try { gemini = store.secrets.workKey(); } catch (Exception e) { gemini = ""; }
        boolean groq = store.groq.connected();
        if (gemini.isBlank() && !groq) throw new IllegalStateException("설정 → Gemini 또는 Groq API 키를 먼저 넣어 주세요.");
        String[] listed = GeminiQuotas.gemma(), gemma = listed == null || listed.length == 0 ? GeminiLight.GEMMA : listed;
        List<String> order = new ArrayList<>(waiting ? List.of("groq", "gemma", "lite") : List.of("gemma", "groq", "lite"));
        Exception last = null;
        for (String step : order) {
            try {
                switch (step) {
                    case "groq" -> { if (groq) return GroqWork.chores(instructions, input); }
                    case "gemma" -> { if (!gemini.isBlank()) return GeminiAudioClient.light(gemini, instructions, input, gemma); }
                    default -> { if (!gemini.isBlank()) return GeminiAudioClient.light(gemini, instructions, input, GeminiWork.FLASH_LITE); }
                }
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last : new IllegalStateException("잡일을 맡을 모델이 없어요.");
    }
}
