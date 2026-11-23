package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much text one main-chat request may carry for a model: the model's published context window in tokens, the room
 * its answer needs, and a conservative characters-per-token rate for Korean-heavy chat. A provider rejection that names
 * a smaller window teaches a lower limit; nothing here raises a limit a provider has refused.
 */
public final class ContextWindow {
    /** Device-side ceiling for one assembled request, whatever a model allows; and the smallest useful request. */
    public static final int MAX_CHARS = 1_000_000, MIN_CHARS = 24_000;
    /** Share of the computed room actually used, for tokenizer variance and instructions. */
    static final double SAFETY = 0.92;
    private static final Pattern GPT = Pattern.compile("gpt-(\\d+)(?:\\.(\\d+))?");
    private static final Pattern CLAUDE = Pattern.compile("claude-(opus|sonnet|haiku|fable)-(\\d+)(?:-(\\d{1,2})(?!\\d))?");
    private static final Pattern[] LIMITS = {
        // Claude: "prompt is too long: 215000 tokens > 200000 maximum"
        Pattern.compile("(\\d[\\d,]{3,})\\s*(?:tokens?\\s*)?maximum"),
        // xAI "maximum prompt length is 131072", vLLM "maximum context length is 32768", Gemini "maximum number of tokens allowed (1048576)"
        Pattern.compile("maximum[^\\d]{0,60}?(\\d[\\d,]{3,})"),
        Pattern.compile("max_seq_len[^\\d]{0,20}?(\\d[\\d,]{3,})"),
        Pattern.compile("context (?:window|length|limit)[^\\d]{0,30}?(\\d[\\d,]{3,})")
    };
    private static final Pattern[] REQUESTED = {
        Pattern.compile("too long:\\s*(\\d[\\d,]{3,})\\s*tokens"),
        Pattern.compile("input token count[^\\d]{0,10}(\\d[\\d,]{3,})"),
        Pattern.compile("request contains\\s*(\\d[\\d,]{3,})"),
        Pattern.compile("you requested\\s*(\\d[\\d,]{3,})"),
        Pattern.compile("resulted in\\s*(\\d[\\d,]{3,})"),
        // refusal() below
        Pattern.compile("요청\\s*(\\d[\\d,]{3,})\\s*토큰")
    };
    private static final String[] OVERFLOW = {
        "context window", "context length", "context_length", "maximum context", "context limit", "prompt is too long",
        "prompt too long", "too many tokens", "maximum prompt length", "input token count", "max_seq_len",
        "maximum number of tokens", "request_too_large", "request too large", "input is too long", "token limit",
        "보낼 내용이 너무 커요", "맥락 한도"
    };
    private ContextWindow() {}

    /** The model's context window in tokens, from the providers' published limits (October 2026). */
    public static long tokens(String engine, String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return switch (engine == null ? "" : engine) {
            case WorkBook.CLAUDE -> claude(m);
            case WorkBook.GEMINI -> 1_048_576;
            // grok-4.7: 524,288 tokens shared by input and output.
            case WorkBook.GROK -> m.contains("fast") ? 2_000_000 : 524_288;
            // The hosted NIM endpoints may serve less than the 1M the model cards state.
            case WorkBook.NIM -> 262_144;
            // agy is handed the conversation as one prompt; its models take about a million tokens.
            case WorkBook.ANTIGRAVITY -> 1_000_000;
            default -> openAi(m);
        };
    }
    private static long openAi(String m) {
        if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")) return 200_000;
        if (m.startsWith("gpt-4.1")) return 1_047_576;
        Matcher gpt = GPT.matcher(m);
        if (!gpt.find()) return 400_000;
        int major = Integer.parseInt(gpt.group(1)), minor = gpt.group(2) == null ? 0 : Integer.parseInt(gpt.group(2));
        if (major >= 6 || major == 5 && minor >= 4) return 1_050_000;
        if (major == 5) return 400_000;
        return 128_000;
    }
    private static long claude(String m) {
        Matcher c = CLAUDE.matcher(m);
        if (!c.find()) return 200_000;
        int major = Integer.parseInt(c.group(2)), minor = c.group(3) == null ? 0 : Integer.parseInt(c.group(3));
        if ("haiku".equals(c.group(1))) return 200_000;
        return major >= 5 || major == 4 && minor >= 6 ? 1_000_000 : 200_000;
    }
    /** Tokens kept free for the answer and reasoning where the window is shared, plus instructions. */
    public static long reserve(String engine, String model) {
        return switch (engine == null ? "" : engine) {
            case WorkBook.CLAUDE -> 16_000;
            case WorkBook.GEMINI -> 8_000;
            case WorkBook.GROK -> 64_000;
            case WorkBook.NIM -> 20_000;
            case WorkBook.ANTIGRAVITY -> 32_000;
            default -> tokens(engine, model) >= 400_000 ? 128_000 : 32_000;
        };
    }
    /** Characters per token assumed for Korean-heavy text; English text uses fewer tokens, so this errs toward fitting. */
    public static double charsPerToken(String engine) {
        return switch (engine == null ? "" : engine) {
            case WorkBook.CLAUDE -> 0.6;
            case WorkBook.GEMINI -> 1.0;
            case WorkBook.GROK, WorkBook.NIM, WorkBook.ANTIGRAVITY -> 0.8;
            default -> 0.9;
        };
    }
    /** Characters one request may hold for this model at its full window. */
    public static int chars(String engine, String model) { return chars(engine, tokens(engine, model), reserve(engine, model)); }
    static int chars(String engine, long window, long reserve) {
        return clamp((window - reserve) * charsPerToken(engine) * SAFETY);
    }
    private static int clamp(double value) { return (int) Math.max(MIN_CHARS, Math.min(MAX_CHARS, value)); }

    /** True when a provider error says the request did not fit the model's context window. */
    public static boolean overflow(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        for (String sign : OVERFLOW) if (lower.contains(sign)) return true;
        return false;
    }
    /**
     * A body-free description of a context-window refusal, for clients that must not surface provider bodies. It keeps
     * the two numbers, and overflow, limit and requested read it back.
     */
    public static String refusal(String provider, String detail) {
        long max = limit(detail), used = requested(detail);
        return provider + "의 맥락 한도(context window)" + (max > 0 ? " " + max + " 토큰" : "") + "을 넘었어요" + (used > 0 ? " · 요청 " + used + " 토큰" : "") + ". 보내는 대화 분량을 줄여야 해요.";
    }
    /** The window a rejection names, in tokens; -1 when it names none. */
    public static long limit(String message) { return number(message, LIMITS); }
    /** The size of the rejected request in tokens, when the rejection says; -1 otherwise. */
    public static long requested(String message) { return number(message, REQUESTED); }
    private static long number(String message, Pattern[] patterns) {
        if (message == null) return -1;
        String lower = message.toLowerCase(Locale.ROOT);
        for (Pattern pattern : patterns) {
            Matcher m = pattern.matcher(lower);
            while (m.find()) {
                try {
                    long value = Long.parseLong(m.group(1).replace(",", ""));
                    if (value >= 1_000 && value <= 20_000_000) return value;
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }
    /**
     * A smaller character budget after a request of sentChars was refused as too long: from the provider's own numbers
     * when it gives them, otherwise 60% of what was sent. Always below what was refused.
     */
    public static int shrink(String engine, String model, int sentChars, String message) {
        long max = limit(message), used = requested(message), reserve = reserve(engine, model);
        double next;
        if (max > reserve && used > max) next = (double) sentChars * (max - reserve) / used * SAFETY;
        else if (max > reserve) next = (max - reserve) * charsPerToken(engine) * SAFETY;
        else next = sentChars * 0.6;
        next = Math.min(next, sentChars * 0.9);
        return (int) Math.max(MainChatContext.INPUT + 2_000, Math.min(MAX_CHARS, next));
    }
}
