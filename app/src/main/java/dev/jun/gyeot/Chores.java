package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiLight;
import dev.jun.gyeot.core.GeminiWork;
import java.util.ArrayList;
import java.util.List;

/**
 * 잡일 (titles, memory, summaries, cards, reviews, 기억 정리, 알림 요약): Groq GPT OSS 120B first, always (the user's choice:
 * Gemini's free tier, Gemma 4 included, may use what is sent to improve Google's models), then Gemma 4, then Flash-Lite only
 * when Groq cannot take it. Material too long for Groq's tokens a minute is shortened for it (its start and its newest part
 * kept). A step without its key, or past its free tier, is skipped; the last reason is thrown when none answered. Blocking.
 * waiting is kept for callers (a button pressed or not); the order no longer depends on it.
 */
final class Chores {
    private Chores() {}
    static String run(AppStore store, String instructions, String input, boolean waiting) throws Exception {
        String gemini; try { gemini = store.secrets.workKey(); } catch (Exception e) { gemini = ""; }
        boolean groq = store.groq.connected();
        if (gemini.isBlank() && !groq) throw new IllegalStateException("설정 → Gemini 또는 Groq API 키를 먼저 넣어 주세요.");
        String[] listed = GeminiQuotas.gemma(), gemma = listed == null || listed.length == 0 ? GeminiLight.GEMMA : listed;
        List<String> order = new ArrayList<>(List.of("groq", "gemma", "lite"));
        Exception last = null;
        for (String step : order) {
            try {
                switch (step) {
                    case "groq" -> { if (groq) return GroqWork.chores(instructions, forGroq(instructions, input)); }
                    case "gemma" -> { if (!gemini.isBlank()) return GeminiAudioClient.light(gemini, instructions, input, gemma); }
                    default -> { if (!gemini.isBlank()) return GeminiAudioClient.light(gemini, instructions, input, GeminiWork.FLASH_LITE); }
                }
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last : new IllegalStateException("잡일을 맡을 모델이 없어요.");
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
