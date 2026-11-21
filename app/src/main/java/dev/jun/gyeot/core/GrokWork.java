package dev.jun.gyeot.core;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** xAI Responses format, separate from Imagine video generation and OpenAI credentials. */
public final class GrokWork {
    public static final String MODEL = "grok-4.7", NAME = "Grok 4.7", RESPONSES = "https://api.x.ai/v1/responses", MODELS = "https://api.x.ai/v1/models";
    private GrokWork() {}
    /** Newer chat models xAI lists (ModelWatch.grok), set by the account from its saved list; empty until one has been read. */
    private static volatile JSONArray detected = arr();
    public static void detected(JSONArray models) { detected = models == null ? arr() : models; }
    public static Reasoning.Profile profile() { return Reasoning.Profile.of(List.of("low", "medium", "high", "xhigh"), "high"); }
    /** Grok 4.7 and the newer chat models xAI has listed since, newest first. */
    public static JSONArray models() {
        JSONArray found = detected;
        if (found.length() == 0) return arr(obj("id", MODEL, "name", NAME));
        try { return new JSONArray(found.toString()); } catch (Exception e) { return arr(obj("id", MODEL, "name", NAME)); }
    }
    public static boolean listed(String model) {
        if (MODEL.equals(model)) return true;
        JSONArray found = detected;
        for (int i = 0; i < found.length(); i++) { JSONObject item = found.optJSONObject(i); if (item != null && item.optString("id").equals(model)) return true; }
        return false;
    }
    public static String model(String model) {
        if (!listed(model)) throw new IllegalArgumentException("작업창에서 Grok 모델을 다시 선택해 주세요.");
        return model;
    }
    public static JSONObject request(String model, String instructions, JSONArray input, boolean search, boolean code,
            JSONArray functions, String effort, String cacheKey) {
        if (input == null || input.length() == 0) throw new IllegalArgumentException("보낼 대화가 없어요.");
        if (effort == null || !effort.isEmpty() && !profile().efforts.contains(effort)) throw new IllegalArgumentException("Grok의 생각 깊이를 다시 선택해 주세요.");
        JSONObject request = obj("model", model(model), "instructions", instructions, "input", input,
            "stream", true, "store", false, "reasoning", obj("effort", profile().resolve(effort)));
        if (WorkBook.safeId(cacheKey)) put(request, "prompt_cache_key", "gyeot-" + cacheKey);
        JSONArray tools = arr();
        if (search) tools.put(obj("type", "web_search"));
        if (code) tools.put(obj("type", "code_interpreter"));
        for (int i = 0; functions != null && i < functions.length(); i++) tools.put(functions.opt(i));
        if (tools.length() > 0) { put(request, "tools", tools); put(request, "parallel_tool_calls", false); }
        return request;
    }
    /**
     * As above; inline false asks xAI to leave its inline citations ([[1]](https://…)) out of the answer's text while web
     * search is on (include "no_inline_citations", which xAI's Responses API takes; they are on by default there). The
     * citations list and the url_citation annotations still come, so the source strip keeps its pages.
     */
    public static JSONObject request(String model, String instructions, JSONArray input, boolean search, boolean code,
            JSONArray functions, String effort, String cacheKey, boolean inline) {
        JSONObject request = request(model, instructions, input, search, code, functions, effort, cacheKey);
        if (search && !inline) put(request, "include", arr("no_inline_citations"));
        return request;
    }
    /** A 400 or 422 whose text names web search: refused before any work, so the request goes once more without it. */
    public static boolean searchRefused(String message) {
        String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("web_search") || lower.contains("web search");
    }
    /** A 400 or 422 about the include field or its citation value: refused before any work, so it goes once more without it. */
    public static boolean citationsRefused(String message) {
        String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("inline_citations") || lower.matches("(?s).*\\binclude\\b.*");
    }
    /** A context-window refusal keeps only the provider's numbers, so a caller can send less; never the raw body. */
    public static String error(int status, String detail) {
        if ((status == 400 || status == 413 || status == 422) && ContextWindow.overflow(detail)) return ContextWindow.refusal("Grok 4.7", detail);
        return error(status);
    }
    /** Do not log provider response bodies, account tokens or HTML error pages. */
    public static String error(int status) {
        return switch (status) {
            case 400, 422 -> "Grok 4.7이 요청 형식을 거절했어요. 첨부·생각 깊이·도구 설정을 확인해 주세요.";
            case 402, 403 -> "이 Grok 로그인 계정에는 Grok 4.7 작업 대화 권한이 허용되지 않았어요. 계정의 구독·모델 권한을 확인해 주세요.";
            case 404 -> "이 Grok 연결에서 grok-4.7 모델을 사용할 수 없어요. 다른 모델로 임의 전환하지 않았어요.";
            default -> GrokOAuth.error(status);
        };
    }
}
