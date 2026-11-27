package dev.jun.gyeot.core;

import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The tokens one request used, defined once for every provider. input is the prompt the provider read
 * without its prompt cache, cacheRead the part it read from the cache, cacheWrite the part it wrote to the cache (only
 * Claude reports writes; elsewhere written tokens are inside input). output is everything the model wrote, its reasoning
 * or thoughts included, and reasoning is that part alone where the provider says. total is the provider's own total, or
 * the sum of the parts when it gives none. A count the provider did not report is null, which means unknown, never 0.
 */
public record Usage(Long input, Long cacheRead, Long cacheWrite, Long output, Long reasoning, Long total) {
    public static final Usage UNKNOWN = new Usage(null, null, null, null, null, null);

    /** Whether the provider reported anything at all. */
    public boolean known() { return input != null || cacheRead != null || cacheWrite != null || output != null || reasoning != null || total != null; }

    /**
     * OpenAI and xAI Responses (response.completed's response.usage): input_tokens includes input_tokens_details.cached_tokens,
     * and output_tokens includes output_tokens_details.reasoning_tokens. A provider that counts reasoning apart from the output
     * shows it in total_tokens (input + output + reasoning); then reasoning is added to the output.
     */
    public static Usage responses(JSONObject usage) {
        if (usage == null) return UNKNOWN;
        JSONObject in = usage.optJSONObject("input_tokens_details"), out = usage.optJSONObject("output_tokens_details");
        return prompted(count(usage, "input_tokens"), in == null ? null : count(in, "cached_tokens"), count(usage, "output_tokens"),
            out == null ? null : count(out, "reasoning_tokens"), count(usage, "total_tokens"));
    }
    /**
     * Chat Completions (NVIDIA NIM's last chunk, asked for with stream_options.include_usage): prompt_tokens includes
     * prompt_tokens_details.cached_tokens (DEPRECATED fallback: prompt_cache_hit_tokens, the retired DeepSeek model's own field,
     * kept because it costs nothing), completion_tokens includes
     * completion_tokens_details.reasoning_tokens unless total_tokens shows them apart.
     */
    public static Usage chat(JSONObject usage) {
        if (usage == null) return UNKNOWN;
        JSONObject in = usage.optJSONObject("prompt_tokens_details"), out = usage.optJSONObject("completion_tokens_details");
        Long cached = in != null && count(in, "cached_tokens") != null ? count(in, "cached_tokens") : count(usage, "prompt_cache_hit_tokens");
        return prompted(count(usage, "prompt_tokens"), cached, count(usage, "completion_tokens"), out == null ? null : count(out, "reasoning_tokens"), count(usage, "total_tokens"));
    }
    private static Usage prompted(Long prompt, Long cached, Long output, Long reasoning, Long total) {
        if (prompt == null && output == null && total == null) return UNKNOWN;
        Long input = prompt == null || cached == null ? null : Math.max(0, prompt - cached);
        if (output != null && reasoning != null && reasoning > 0 && prompt != null && total != null && total == prompt + output + reasoning) output = output + reasoning;
        if (total == null && prompt != null && output != null) total = prompt + output;
        return new Usage(input, cached, null, output, reasoning, total);
    }
    /**
     * Claude Messages: message_start's message.usage, then message_delta's usage, whose counts are cumulative and replace the
     * ones before (ClaudeProtocol.Stream merges them). input_tokens excludes cache_read_input_tokens and
     * cache_creation_input_tokens; output_tokens includes thinking. Claude reports no total and no reasoning count. A
     * response with a server-side step (a fallback model taking over a declined attempt, compaction) also lists every
     * attempt in iterations, the per-attempt source of truth: the top-level counts cover only the attempt that wrote the
     * message, so the iterations are summed instead.
     */
    public static Usage claude(JSONObject usage) {
        if (usage == null) return UNKNOWN;
        JSONArray iterations = usage.optJSONArray("iterations");
        if (iterations != null && iterations.length() > 0) {
            Usage all = null;
            for (int i = 0; i < iterations.length(); i++) {
                JSONObject attempt = iterations.optJSONObject(i);
                all = sum(all, attempt == null || attempt.has("iterations") ? UNKNOWN : claude(attempt));
            }
            return all;
        }
        Long input = count(usage, "input_tokens"), read = count(usage, "cache_read_input_tokens"), write = count(usage, "cache_creation_input_tokens"), output = count(usage, "output_tokens");
        if (input == null && read == null && write == null && output == null) return UNKNOWN;
        Long total = input == null || read == null || write == null || output == null ? null : input + read + write + output;
        return new Usage(input, read, write, output, null, total);
    }
    /**
     * Gemini usageMetadata, on an API key and through Code Assist alike: promptTokenCount includes cachedContentTokenCount,
     * candidatesTokenCount excludes thoughtsTokenCount, tool results read back count as toolUsePromptTokenCount, and
     * totalTokenCount is all four. Google leaves a count of 0 out of the JSON, so in a report a missing count is 0.
     */
    public static Usage gemini(JSONObject metadata) {
        if (metadata == null) return UNKNOWN;
        Long prompt = count(metadata, "promptTokenCount"), cached = count(metadata, "cachedContentTokenCount"), tools = count(metadata, "toolUsePromptTokenCount"),
            candidates = count(metadata, "candidatesTokenCount"), thoughts = count(metadata, "thoughtsTokenCount"), total = count(metadata, "totalTokenCount");
        if (prompt == null && candidates == null && thoughts == null && total == null) return UNKNOWN;
        long read = zero(cached), thought = zero(thoughts), whole = zero(prompt) + zero(tools);
        return new Usage(Math.max(0, whole - read), read, null, zero(candidates) + thought, thought, total != null ? total : whole + zero(candidates) + thought);
    }
    private static long zero(Long value) { return value == null ? 0 : value; }
    /** A non-negative whole count, or null when the field is missing, null or not a number. */
    private static Long count(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        Object value = object.opt(key);
        long count;
        if (value instanceof Number number) count = number.longValue();
        else try { count = Long.parseLong(String.valueOf(value).strip()); } catch (NumberFormatException notCount) { return null; }
        return count < 0 ? null : count;
    }

    /** a, then b: the responses of one request (a continued Claude turn, the rounds of a 작업창 run); null a starts the sum. */
    public static Usage sum(Usage a, Usage b) { return a == null ? b : b == null ? a : a.plus(b); }
    /** Both together; a count unknown in either is unknown in the sum, never counted as 0. */
    public Usage plus(Usage other) {
        return new Usage(add(input, other.input), add(cacheRead, other.cacheRead), add(cacheWrite, other.cacheWrite), add(output, other.output), add(reasoning, other.reasoning), add(total, other.total));
    }
    private static Long add(Long a, Long b) { return a == null || b == null ? null : a + b; }

    /**
     * The share of the prompt read from the cache, 0 to 1: cacheRead / (input + cacheRead + cacheWrite), with an unreported
     * write counted as none (it is inside input there). Null when the split is unknown or the prompt is empty.
     */
    public Double cacheShare() {
        if (input == null || cacheRead == null) return null;
        long prompt = input + cacheRead + (cacheWrite == null ? 0 : cacheWrite);
        return prompt <= 0 ? null : (double) cacheRead / prompt;
    }

    /** {first?, input?, cacheRead?, …}: what an answer's foot shows, kept with the answer; only known counts are written. */
    public JSONObject json(long first) {
        JSONObject json = obj();
        if (first > 0) put(json, "first", first);
        if (input != null) put(json, "input", input);
        if (cacheRead != null) put(json, "cacheRead", cacheRead);
        if (cacheWrite != null) put(json, "cacheWrite", cacheWrite);
        if (output != null) put(json, "output", output);
        if (reasoning != null) put(json, "reasoning", reasoning);
        if (total != null) put(json, "total", total);
        return json;
    }
    public static Usage of(JSONObject json) {
        if (json == null) return UNKNOWN;
        return new Usage(count(json, "input"), count(json, "cacheRead"), count(json, "cacheWrite"), count(json, "output"), count(json, "reasoning"), count(json, "total"));
    }
    /** The foot line of an answer kept as json(first) (a stored JSON string); "" when nothing is known. */
    public static String foot(String saved) {
        if (saved == null || saved.isBlank()) return "";
        try { return foot(new JSONObject(saved)); } catch (Exception broken) { return ""; }
    }
    /**
     * As above, for one kept as an object (a 작업창 answer); null is nothing known. When another model answered because the
     * chosen one could not (core/Fallback), its note comes first: "Claude 한도 → GPT-6.1 Terra로 답했어요 · 첫 글자 1.1초 · …".
     */
    public static String foot(JSONObject saved) {
        if (saved == null) return "";
        String line = foot(saved.optLong("first"), of(saved)), via = via(saved);
        return via.isEmpty() ? line : line.isEmpty() ? via : via + " · " + line;
    }
    /** The note of a kept answer another model gave (core/Fallback.note), or "". */
    public static String via(JSONObject saved) { return saved == null ? "" : saved.optString("via").strip(); }
    /** The same for one kept as a JSON string (메인 대화's usage column). */
    public static String via(String saved) {
        if (saved == null || saved.isBlank()) return "";
        try { return via(new JSONObject(saved)); } catch (Exception broken) { return ""; }
    }
    /** "첫 글자 1.1초 · 12.4k 토큰 · 캐시 78%": only the parts that are known; "" when none is. first is in milliseconds, 0 unknown. */
    public static String foot(long first, Usage usage) {
        StringBuilder line = new StringBuilder();
        if (first > 0) line.append("첫 글자 ").append(seconds(first));
        Usage used = usage == null ? UNKNOWN : usage;
        if (used.total != null) line.append(line.length() == 0 ? "" : " · ").append(tokens(used.total)).append(" 토큰");
        Double share = used.cacheShare();
        if (share != null) line.append(line.length() == 0 ? "" : " · ").append("캐시 ").append(Math.round(share * 100)).append('%');
        return line.toString();
    }
    /** "0.4초", "1.1초", "23초", "1분 5초"; never less than 0.1초. Rounded to tenths first, so 9,999 ms is "10초", not "10.0초". */
    public static String seconds(long millis) {
        long tenths = Math.max(1, Math.round(millis / 100.0));
        if (tenths < 100) return String.format(Locale.ROOT, "%.1f초", tenths / 10.0);
        long whole = Math.round(millis / 1000.0);
        return whole < 60 ? whole + "초" : whole / 60 + "분" + (whole % 60 == 0 ? "" : " " + whole % 60 + "초");
    }
    /** "980", "1.2k", "12.4k", "124k", "1.2M"; a count that rounds to 1,000k is "1M". */
    public static String tokens(long count) {
        if (count < 1_000) return String.valueOf(count);
        if (count < 100_000) return trim(String.format(Locale.ROOT, "%.1f", count / 1000.0)) + "k";
        if (count < 999_500) return Math.round(count / 1000.0) + "k";
        return trim(String.format(Locale.ROOT, "%.1f", count / 1_000_000.0)) + "M";
    }
    private static String trim(String decimal) { return decimal.endsWith(".0") ? decimal.substring(0, decimal.length() - 2) : decimal; }
}
