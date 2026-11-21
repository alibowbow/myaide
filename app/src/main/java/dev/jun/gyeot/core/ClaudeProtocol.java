package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Anthropic's Claude Messages API over plain HTTPS: request bodies, the streamed (SSE) answer, the model catalog and error
 * text. The API key goes only in the x-api-key header to api.anthropic.com (see ClaudeAccount); nothing here touches it.
 */
public final class ClaudeProtocol {
    public static final String HOST = "api.anthropic.com", MESSAGES = "https://" + HOST + "/v1/messages", MODELS = "https://" + HOST + "/v1/models?limit=100",
        FILES = "https://" + HOST + "/v1/files/", VERSION = "2026-10-21";
    /** fallbacks:"default" is gated by the first header; the model list is read with the second, which publishes allowed_fallback_models. */
    public static final String FALLBACK_BETA = "server-side-fallback-2026-07-01", FALLBACK_LIST_BETA = "server-side-fallback-2026-06-01";
    /** Context editing: the API clears old tool results itself once a long run grows large (contextEdits). */
    public static final String CONTEXT_BETA = "context-management-2025-06-27";
    /** Server tools, newest first: dynamic-filtering web tools with the code runtime they pair with, then the basic versions older models take. */
    private static final String[][] TOOLS = {{"web_search_20260209", "web_fetch_20260209", "code_execution_20260521"}, {"web_search_20250305", "web_fetch_20250910", "code_execution_20250825"}};
    public static final int TOOL_LEVELS = TOOLS.length;
    /** Longest answer kept from one run. */
    public static final int TEXT_LIMIT = 1_000_000;
    private ClaudeProtocol() {}

    /** An Anthropic API key: "sk-ant-…", one token. */
    public static boolean validKey(String key) { return key != null && key.startsWith("sk-ant-") && key.length() >= 30 && key.length() <= 300 && key.matches("[A-Za-z0-9_-]+"); }

    /**
     * A streaming Messages request. info is the model's catalog entry (models()): it decides adaptive thinking, the effort
     * levels it takes, its output limit and whether a refused request may fall back to another model. tools: 0 none, or
     * search, fetch and code as asked, at version level toolLevel. container reuses an earlier code container.
     */
    public static JSONObject request(String model, JSONObject info, String system, JSONArray messages, int maxTokens, String effort,
            boolean search, boolean fetch, boolean code, int toolLevel, String container, boolean fallback, boolean cache) {
        return request(model, info, system, messages, maxTokens, effort, search, fetch, code, toolLevel, container, fallback, cache, null);
    }
    /** As above, with the app's own client tools ({name, description, input_schema}) when functions is given; the app runs them. */
    public static JSONObject request(String model, JSONObject info, String system, JSONArray messages, int maxTokens, String effort,
            boolean search, boolean fetch, boolean code, int toolLevel, String container, boolean fallback, boolean cache, JSONArray functions) {
        return request(model, info, system, messages, maxTokens, effort, search, fetch, code, toolLevel, container, fallback, cache, functions, false);
    }
    /**
     * As above; summarize asks adaptive thinking for a readable summary of itself (thinking.display "summarized", streamed as
     * thinking_delta) instead of the empty thinking current models send by default. A model without adaptive thinking runs
     * without thinking, so nothing is added.
     */
    public static JSONObject request(String model, JSONObject info, String system, JSONArray messages, int maxTokens, String effort,
            boolean search, boolean fetch, boolean code, int toolLevel, String container, boolean fallback, boolean cache, JSONArray functions, boolean summarize) {
        return request(model, info, system, messages, maxTokens, effort, search, fetch, code, toolLevel, container, fallback, cache, functions, summarize, 0);
    }
    /**
     * As above; searches caps the web searches one request may run (web_search max_uses, each search billed on an API key),
     * 0 for no cap of the app's own. A search past the cap comes back as a max_uses_exceeded result, with no pages.
     */
    public static JSONObject request(String model, JSONObject info, String system, JSONArray messages, int maxTokens, String effort,
            boolean search, boolean fetch, boolean code, int toolLevel, String container, boolean fallback, boolean cache, JSONArray functions, boolean summarize, int searches) {
        JSONObject entry = info == null ? new JSONObject() : info;
        int limit = entry.optInt("maxTokens", 0);
        JSONObject body = obj("model", model, "max_tokens", limit > 0 ? Math.min(maxTokens, limit) : Math.min(maxTokens, 8192), "stream", true, "messages", messages);
        try {
            if (system != null && !system.isBlank()) body.put("system", system);
            // Thinking decides for itself how much to think; models before adaptive thinking run without it.
            if (entry.optBoolean("adaptive")) body.put("thinking", summarize ? obj("type", "adaptive", "display", "summarized") : obj("type", "adaptive"));
            if (effort != null && !effort.isBlank() && efforts(entry).contains(effort)) body.put("output_config", obj("effort", effort));
            String[] versions = TOOLS[Math.max(0, Math.min(TOOL_LEVELS - 1, toolLevel))];
            JSONArray tools = arr();
            if (search) tools.put(searches > 0 ? obj("type", versions[0], "name", "web_search", "max_uses", searches) : obj("type", versions[0], "name", "web_search"));
            if (fetch) tools.put(obj("type", versions[1], "name", "web_fetch"));
            if (code) tools.put(obj("type", versions[2], "name", "code_execution"));
            for (int i = 0; functions != null && i < functions.length(); i++) tools.put(functions.get(i));
            if (tools.length() > 0) body.put("tools", tools);
            if (code && WorkBook.safeId(container)) body.put("container", container);
            if (fallback) body.put("fallbacks", "default");
            if (cache) body.put("cache_control", obj("type", "ephemeral"));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return body;
    }
    /**
     * The request with its system text as one block marked for the prompt cache, so the fixed instructions are read back
     * from the cache on the next request. For a single question that ends in new text (the main chat's replies) this is where
     * the cache pays: the request-wide cache_control would mark the end of the question, which is never sent again, and
     * every request would pay to write it. Without a system text the request stays as it is.
     */
    public static JSONObject cacheSystem(JSONObject body) {
        String system = body.optString("system");
        if (!system.isBlank()) put(body, "system", arr(obj("type", "text", "text", system, "cache_control", obj("type", "ephemeral"))));
        return body;
    }
    /** One user turn. */
    public static JSONArray ask(String text) { return arr(obj("role", "user", "content", text == null ? "" : text)); }
    /**
     * A conversation ({role, content, files?} oldest first) as the Messages API takes it: it starts with the user, and turns
     * of the same role in a row are joined, so a thread that lost an answer to an error still alternates. A user turn's
     * attached images and PDFs (files with their base64 "data") go before its text as image and document blocks.
     */
    public static JSONArray conversation(JSONArray turns) {
        JSONArray result = arr(); String last = "";
        for (int i = 0; turns != null && i < turns.length(); i++) {
            JSONObject turn = turns.optJSONObject(i); if (turn == null) continue;
            String role = "assistant".equals(turn.optString("role")) ? "assistant" : "user", text = turn.optString("content");
            JSONArray blocks = role.equals("user") ? blocks(turn.optJSONArray("files")) : arr();
            if ((text.isBlank() && blocks.length() == 0) || (result.length() == 0 && role.equals("assistant"))) continue;
            if (role.equals(last)) { join(result.optJSONObject(result.length() - 1), text, blocks); continue; }
            result.put(obj("role", role, "content", blocks.length() == 0 ? text : withText(blocks, text))); last = role;
        }
        return result;
    }
    /** Image and document blocks for attached files that carry their bytes. */
    private static JSONArray blocks(JSONArray files) {
        JSONArray result = arr();
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i); String data = file == null ? "" : file.optString("data");
            if (data.isEmpty()) continue;
            if (WorkBook.PDF.equals(file.optString("kind"))) result.put(obj("type", "document", "source", obj("type", "base64", "media_type", "application/pdf", "data", data), "title", file.optString("name")));
            else result.put(obj("type", "image", "source", obj("type", "base64", "media_type", file.optString("mime"), "data", data)));
        }
        return result;
    }
    private static JSONArray withText(JSONArray blocks, String text) {
        JSONArray content = arr();
        for (int i = 0; i < blocks.length(); i++) content.put(blocks.opt(i));
        if (!text.isBlank()) content.put(obj("type", "text", "text", text));
        return content;
    }
    /** Adds a turn to the previous one of the same role: plain text stays text, otherwise both become blocks. */
    private static void join(JSONObject previous, String text, JSONArray blocks) {
        Object content = previous.opt("content");
        if (content instanceof String earlier && blocks.length() == 0) { put(previous, "content", earlier + "\n\n" + text); return; }
        JSONArray joined = content instanceof JSONArray list ? list : arr(obj("type", "text", "text", String.valueOf(content)));
        JSONArray added = withText(blocks, text);
        for (int i = 0; i < added.length(); i++) joined.put(added.opt(i));
        put(previous, "content", joined);
    }
    /** The conversation followed by the paused assistant turn, to send again so the server picks the turn up where it stopped. */
    public static JSONArray resume(JSONArray messages, JSONArray assistant) {
        JSONArray result = arr();
        for (int i = 0; i < messages.length(); i++) result.put(messages.opt(i));
        result.put(obj("role", "assistant", "content", assistant));
        return result;
    }

    // ── Model catalog ───────────────────────────────────────────────────────

    /**
     * The catalog from GET /v1/models, newest first: {id, name, maxTokens, adaptive, fallback, supported_reasoning_efforts}.
     * Capabilities come from the API itself, so a new model works without an app update.
     */
    public static JSONArray models(JSONObject body) {
        List<JSONObject> found = new ArrayList<>();
        JSONArray data = body == null ? null : body.optJSONArray("data");
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject model = data.optJSONObject(i);
            String id = model == null ? "" : model.optString("id");
            if (!id.matches("[A-Za-z0-9._:@-]{1,120}") || (!model.optString("type", "model").equals("model"))) continue;
            // A replaced model (ModelIds.CLAUDE_REPLACED, e.g. Fable 5.1 after Fable 5.2) or one older than 5.2 is not offered in any picker.
            if (ModelIds.retired(WorkBook.CLAUDE, id)) continue;
            JSONObject capabilities = model.optJSONObject("capabilities"), effort = capabilities == null ? null : capabilities.optJSONObject("effort");
            JSONArray levels = arr();
            if (effort != null && effort.optBoolean("supported", true)) for (String level : new String[]{"low", "medium", "high", "xhigh", "max"}) {
                JSONObject entry = effort.optJSONObject(level); if (entry != null && entry.optBoolean("supported")) levels.put(level);
            }
            JSONObject thinking = capabilities == null ? null : capabilities.optJSONObject("thinking"), types = thinking == null ? null : thinking.optJSONObject("types");
            JSONObject adaptive = types == null ? null : types.optJSONObject("adaptive");
            JSONArray fallbacks = model.optJSONArray("allowed_fallback_models");
            JSONObject management = capabilities == null ? null : capabilities.optJSONObject("context_management"), clear = management == null ? null : management.optJSONObject("clear_tool_uses_20250919");
            found.add(obj("id", id, "name", model.optString("display_name", id).isBlank() ? id : model.optString("display_name", id), "created", model.optString("created_at"),
                "maxTokens", Math.max(0, model.optInt("max_tokens", 0)), "adaptive", adaptive != null && adaptive.optBoolean("supported"),
                "fallback", fallbacks != null && fallbacks.length() > 0, "supported_reasoning_efforts", levels,
                "contextTokens", Math.max(0, model.optInt("max_input_tokens", 0)), "clearsToolUses", clear != null && clear.optBoolean("supported")));
        }
        // Newest first by release date (ISO timestamps sort as text); the API's own order breaks ties.
        found.sort((a, b) -> b.optString("created").compareTo(a.optString("created")));
        JSONArray result = arr();
        for (JSONObject model : found) result.put(model);
        return result;
    }
    /** The model to start with: the newest Opus, else the newest model. */
    public static String preferred(JSONArray models) {
        for (int i = 0; models != null && i < models.length(); i++) if (models.optJSONObject(i).optString("id").contains("opus")) return models.optJSONObject(i).optString("id");
        return models == null || models.length() == 0 ? "" : models.optJSONObject(0).optString("id");
    }
    public static JSONObject find(JSONArray models, String id) {
        for (int i = 0; models != null && i < models.length(); i++) if (models.optJSONObject(i).optString("id").equals(id)) return models.optJSONObject(i);
        return null;
    }
    /**
     * Old tool results cleared by the API once the request passes 60% of the model's context (its own placeholder stands in
     * for each), the newest six kept and at least a tenth of the window freed each time, so a long run keeps going instead
     * of stopping at a size. Null for a model whose catalog does not say it supports this.
     */
    public static JSONObject contextEdits(JSONObject info) {
        if (info == null || !info.optBoolean("clearsToolUses")) return null;
        int window = info.optInt("contextTokens", 0) > 0 ? info.optInt("contextTokens") : 200_000;
        return obj("edits", arr(obj("type", "clear_tool_uses_20250919", "trigger", obj("type", "input_tokens", "value", Math.max(50_000, window / 10 * 6)),
            "keep", obj("type", "tool_uses", "value", 6), "clear_at_least", obj("type", "input_tokens", "value", Math.max(20_000, window / 10)))));
    }
    public static List<String> efforts(JSONObject info) {
        List<String> levels = new ArrayList<>(); JSONArray values = info == null ? null : info.optJSONArray("supported_reasoning_efforts");
        for (int i = 0; values != null && i < values.length(); i++) levels.add(values.optString(i));
        return levels;
    }

    // ── Errors ──────────────────────────────────────────────────────────────

    /** The error's type and message from an API error body ({"type":"error","error":{type, message}}). */
    public static String errorType(JSONObject body) { JSONObject error = body == null ? null : body.optJSONObject("error"); return error == null ? "" : error.optString("type"); }
    public static String message(JSONObject body) {
        JSONObject error = body == null ? null : body.optJSONObject("error");
        return error == null ? "" : error.optString("message").strip();
    }
    /** Worth sending again after a pause: rate limits, timeouts, server errors and overload. */
    public static boolean retryable(int status) { return status == 408 || status == 409 || status == 429 || status >= 500; }
    /**
     * A 400 about an optional part of the request (a server tool, its version, the fallback, the container, or attached
     * images and PDFs), which can be dropped. A tool counts by its name or in words ("web search is not enabled").
     */
    public static String refusedPart(int status, String message) {
        if (status != 400) return "";
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.contains("fallback")) return "fallback";
        if (lower.contains("container")) return "container";
        if (lower.matches("(?s).*(web[_ ]search|web[_ ]fetch|code[_ ]execution).*")) return "tool";
        if (lower.contains("mcp")) return "mcp";
        if (lower.contains("image") || lower.contains("document") || lower.contains("pdf") || lower.contains("media_type") || lower.contains("base64")) return "file";
        if (lower.contains("tool")) return "tool";
        return "";
    }
    /**
     * A 400 about the thinking summary alone (thinking.display on a model or account that does not take it): the provider
     * did no work, so the main chat sends the request once more without it.
     */
    public static boolean summaryRefused(int status, String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return status == 400 && lower.contains("thinking") && (lower.contains("display") || lower.contains("summar"));
    }
    /** Korean, actionable explanation of a failed request. */
    public static String explain(int status, String type, String detail) {
        String lower = detail == null ? "" : detail.toLowerCase(Locale.ROOT), hint;
        if (status == 401 || "authentication_error".equals(type)) hint = "Claude API 키가 올바르지 않거나 폐기됐어요. 설정 → Claude에서 키를 다시 연결해 주세요.";
        else if (lower.contains("credit balance") || lower.contains("billing")) hint = "Claude API 크레딧이 부족해요. Anthropic Console → Billing에서 충전해 주세요.";
        else if (status == 403 || "permission_error".equals(type)) hint = "이 API 키로는 이 모델이나 기능을 쓸 수 없어요.";
        else if (status == 404 || "not_found_error".equals(type)) hint = "선택한 Claude 모델을 찾지 못했어요. 설정 → Claude에서 모델 새로고침 후 다시 골라 주세요.";
        else if (lower.contains("prompt is too long") || lower.contains("context window") || lower.contains("too many tokens"))
            hint = "작업 기록이 Claude가 한 번에 읽을 수 있는 크기를 넘었어요. 결과를 확인한 뒤 남은 작업을 새 지시로 이어 주세요.";
        else if (status == 413 || "request_too_large".equals(type)) hint = "보낼 내용이 너무 커요. 줄여서 다시 보내 주세요.";
        else if (status == 429 || "rate_limit_error".equals(type)) hint = "Claude 사용 한도에 걸렸어요. 잠시 후 다시 시도해 주세요.";
        else if (status == 529 || "overloaded_error".equals(type)) hint = "Claude가 지금 붐벼요. 잠시 후 다시 시도해 주세요.";
        else if (status >= 500 || "api_error".equals(type)) hint = "Anthropic 서버에 일시적인 문제가 있어요. 잠시 후 다시 시도해 주세요.";
        else return detail == null || detail.isBlank() ? "Claude 요청에 실패했어요 (" + status + ")." : "Claude 요청에 실패했어요: " + clip(detail);
        return detail == null || detail.isBlank() ? hint : hint + " (" + clip(detail) + ")";
    }
    /** A declined answer: classifiers or the model refused, and no fallback model answered either. */
    public static String refusal(String category) {
        return "Claude가 이 요청에는 답하지 않았어요" + (category == null || category.isBlank() ? "." : " (" + category + ").") + " 표현을 바꾸거나 다른 모델로 다시 시도해 주세요.";
    }
    private static String clip(String text) { return text.length() > 160 ? text.substring(0, 160) + "…" : text; }

    // ── Files ───────────────────────────────────────────────────────────────

    /** Metadata and download addresses of a file a code run made; null for an id that is not an opaque token. */
    public static String file(String id) { return WorkBook.safeId(id) ? FILES + id : null; }
    public static String fileContent(String id) { return WorkBook.safeId(id) ? FILES + id + "/content" : null; }

    // ── The streamed answer ─────────────────────────────────────────────────

    /**
     * Reads one turn's server-sent events, across pause_turn continuations: the answer's text (text blocks joined, with a
     * paragraph break after tool work), the pages it searched or cited, the files its code left in $OUTPUT_DIR, the code
     * container, and every content block, so a paused turn can be sent back exactly as it came.
     */
    public static final class Stream {
        private final List<JSONObject> blocks = new ArrayList<>();
        private final Map<Integer, StringBuilder> inputs = new HashMap<>();
        private final StringBuilder text = new StringBuilder();
        private final Map<String, String> sources = new LinkedHashMap<>();
        /** The sources the answer cites or a page fetch read, ranked first in the main chat's source strip. */
        private final java.util.Set<String> cited = new java.util.HashSet<>();
        private final Map<String, JSONObject> files = new LinkedHashMap<>();
        private int base;
        /** Where the text went on after a tool call, for WorkBook.split. */
        private final List<Integer> cuts = new ArrayList<>();
        private boolean completed, paragraph;
        private String stopReason = "", category = "", failure, failureType = "", container = "", stage = "", model = "";
        /** The readable summary of adaptive thinking (thinking.display "summarized"), blocks as paragraphs; never part of text(). */
        private final StringBuilder thinking = new StringBuilder();
        private int thinkingTaken;
        private boolean thoughtBreak;
        /** The model this message's last fallback block handed it to (a server-side fallback), "" when none did. */
        private String switched = "";
        /** This message's usage: message_start's, then message_delta's cumulative counts over it and its iterations (Usage.claude). */
        private JSONObject usage;

        /** Before-request snapshot: never resume incomplete current reasoning or tool blocks. */
        public Stream snapshot() {
            Stream copy = new Stream();
            for (JSONObject block : blocks) copy.blocks.add(block == null ? null : Jsons.copy(block));
            for (Map.Entry<Integer, StringBuilder> input : inputs.entrySet()) copy.inputs.put(input.getKey(), new StringBuilder(input.getValue()));
            copy.text.append(text); copy.sources.putAll(sources); copy.cited.addAll(cited);
            for (Map.Entry<String, JSONObject> file : files.entrySet()) copy.files.put(file.getKey(), Jsons.copy(file.getValue()));
            copy.base = base; copy.cuts.addAll(cuts); copy.completed = completed; copy.paragraph = paragraph;
            copy.stopReason = stopReason; copy.category = category; copy.failure = failure; copy.failureType = failureType;
            copy.container = container; copy.stage = stage; copy.model = model; copy.switched = switched; copy.usage = usage == null ? null : Jsons.copy(usage);
            copy.thinking.append(thinking); copy.thinkingTaken = thinkingTaken; copy.thoughtBreak = thoughtBreak;
            return copy;
        }
        /** One line of the event stream; returns new answer text to show, or null. */
        public String line(String raw) {
            if (raw == null || !raw.startsWith("data:")) return null;
            JSONObject event;
            try { event = new JSONObject(raw.substring(5).strip()); } catch (Exception e) { return null; }
            switch (event.optString("type")) {
                case "message_start" -> {
                    JSONObject message = event.optJSONObject("message");
                    if (message != null) { model = message.optString("model", model); container(message.opt("container")); }
                    usage = message == null || message.optJSONObject("usage") == null ? null : copy(message.optJSONObject("usage"));
                }
                case "content_block_start" -> { return start(base + event.optInt("index"), event.optJSONObject("content_block")); }
                case "content_block_delta" -> { return delta(base + event.optInt("index"), event.optJSONObject("delta")); }
                case "content_block_stop" -> stop(base + event.optInt("index"));
                case "message_delta" -> {
                    JSONObject delta = event.optJSONObject("delta");
                    if (delta != null) {
                        if (!delta.optString("stop_reason").isEmpty() && !delta.isNull("stop_reason")) stopReason = delta.optString("stop_reason");
                        JSONObject details = delta.optJSONObject("stop_details");
                        if (details != null && !details.isNull("category")) category = details.optString("category");
                        container(delta.opt("container"));
                    }
                    container(event.opt("container"));
                    // Counts here are cumulative: each one replaces the earlier count of the same name, and the attempts list the one before.
                    JSONObject counts = event.optJSONObject("usage");
                    for (java.util.Iterator<String> names = counts == null ? null : counts.keys(); names != null && names.hasNext(); ) {
                        String name = names.next();
                        Object value = counts.opt(name);
                        boolean attempts = "iterations".equals(name) && value instanceof JSONArray;
                        if (counts.isNull(name) || !(value instanceof Number || attempts)) continue;
                        if (usage == null) usage = new JSONObject();
                        put(usage, name, attempts ? copy((JSONArray) value) : value);
                    }
                }
                case "message_stop" -> completed = true;
                case "error" -> {
                    JSONObject error = event.optJSONObject("error");
                    failureType = error == null ? "" : error.optString("type");
                    failure = explain(0, failureType, error == null ? "" : error.optString("message"));
                }
                default -> {}
            }
            return null;
        }
        private void container(Object value) {
            if (value instanceof JSONObject object && WorkBook.safeId(object.optString("id"))) container = object.optString("id");
            else if (value instanceof String id && WorkBook.safeId(id)) container = id;
        }
        private String start(int index, JSONObject block) {
            if (block == null || index < 0 || index > 10_000) return null;
            JSONObject copy = copy(block);
            while (blocks.size() <= index) blocks.add(null);
            blocks.set(index, copy);
            String type = copy.optString("type");
            switch (type) {
                case "thinking", "redacted_thinking" -> {
                    stage = "생각하는 중";
                    if (type.equals("thinking")) { thoughtBreak = thinking.length() > 0; thought(copy.optString("thinking")); }
                }
                // The fallback model carries on from the declined attempt's text, so no paragraph break at the switch.
                case "fallback" -> {
                    paragraph = false;
                    JSONObject to = copy.optJSONObject("to");
                    if (to != null && !to.optString("model").isBlank()) switched = to.optString("model");
                }
                case "server_tool_use" -> {
                    String name = copy.optString("name");
                    stage = name.equals("web_search") ? "웹 검색 중" : name.equals("web_fetch") ? "웹 페이지 읽는 중" : "코드 실행 중";
                    paragraph = text.length() > 0; cut();
                }
                case "mcp_tool_use", "tool_use" -> { stage = "GitHub 작업 중"; paragraph = text.length() > 0; cut(); }
                case "web_search_tool_result" -> {
                    JSONArray results = copy.optJSONArray("content");
                    for (int i = 0; results != null && i < results.length(); i++) if (results.optJSONObject(i) != null) source(results.optJSONObject(i).optString("url"), results.optJSONObject(i).optString("title"), false);
                }
                case "web_fetch_tool_result" -> {
                    JSONObject result = copy.optJSONObject("content");
                    JSONObject document = result == null ? null : result.optJSONObject("content");
                    if (result != null) source(result.optString("url"), document == null ? "" : document.optString("title"), true);
                }
                case "bash_code_execution_tool_result", "code_execution_tool_result" -> {
                    JSONObject result = copy.optJSONObject("content");
                    JSONArray outputs = result == null ? null : result.optJSONArray("content");
                    for (int i = 0; outputs != null && i < outputs.length(); i++) {
                        String id = outputs.optJSONObject(i) == null ? "" : outputs.optJSONObject(i).optString("file_id");
                        if (WorkBook.safeId(id) && files.size() < WorkBook.FILES * 2) files.putIfAbsent(id, obj("file", id));
                    }
                }
                case "text" -> {
                    stage = "답 쓰는 중";
                    String initial = copy.optString("text");
                    put(copy, "text", "");
                    if (!initial.isEmpty()) return append(copy, initial);
                }
                default -> {}
            }
            return null;
        }
        private String delta(int index, JSONObject delta) {
            JSONObject block = index >= 0 && index < blocks.size() ? blocks.get(index) : null;
            if (block == null || delta == null) return null;
            switch (delta.optString("type")) {
                case "text_delta" -> { return append(block, delta.optString("text")); }
                case "input_json_delta" -> inputs.computeIfAbsent(index, key -> new StringBuilder()).append(delta.optString("partial_json"));
                case "thinking_delta" -> { put(block, "thinking", block.optString("thinking") + delta.optString("thinking")); thought(delta.optString("thinking")); }
                case "signature_delta" -> put(block, "signature", delta.optString("signature"));
                case "citations_delta" -> {
                    JSONObject citation = delta.optJSONObject("citation");
                    if (citation != null) {
                        JSONArray list = block.optJSONArray("citations");
                        if (list == null) { list = arr(); put(block, "citations", list); }
                        list.put(citation);
                        source(citation.optString("url"), citation.optString("title"), true);
                    }
                }
                default -> {}
            }
            return null;
        }
        /** Readable thinking (only a summarized display has any); a new thinking block starts a new paragraph. */
        private void thought(String value) {
            if (value == null || value.isEmpty() || thinking.length() + value.length() > MainRequest.THINKING) return;
            if (thoughtBreak) { thoughtBreak = false; thinking.append("\n\n"); }
            thinking.append(value);
        }
        private String append(JSONObject block, String value) {
            if (value.isEmpty()) return null;
            if (text.length() + value.length() > TEXT_LIMIT) { failure = "Claude 답이 너무 길어요."; return null; }
            String shown = value;
            // Text after a search or a code run starts a new paragraph; text split around citations joins as it is.
            if (paragraph) { paragraph = false; if (!text.toString().endsWith("\n\n")) shown = (text.toString().endsWith("\n") ? "\n" : "\n\n") + value; }
            put(block, "text", block.optString("text") + value);
            text.append(shown);
            return shown;
        }
        private void stop(int index) {
            StringBuilder partial = inputs.remove(index);
            JSONObject block = index >= 0 && index < blocks.size() ? blocks.get(index) : null;
            if (partial == null || block == null) return;
            String json = partial.toString().strip();
            try { put(block, "input", json.isEmpty() ? new JSONObject() : new JSONObject(json)); }
            catch (Exception e) { failure = "Claude 도구 호출이 완성되지 않았어요. 다시 시도해 주세요."; }
        }
        private void source(String url, String title, boolean cites) {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://")) || sources.size() >= 20 && !sources.containsKey(url)) return;
            String name = title == null ? "" : title.strip();
            sources.putIfAbsent(url, name.isEmpty() ? url : name);
            if (cites) cited.add(url);
        }

        /**
         * After stop_reason pause_turn: the assistant turn so far, to send back so the server continues it. Blocks from a model
         * that declined before a fallback took over keep only their text and finished server tool calls.
         */
        public JSONArray paused() { return paused(0); }
        /** As above, from block index from on: what came after the blocks already sent back (an earlier round of client tools). */
        public JSONArray paused(int from) {
            int boundary = -1;
            for (int i = 0; i < blocks.size(); i++) if (blocks.get(i) != null && "fallback".equals(blocks.get(i).optString("type"))) boundary = i;
            java.util.Set<String> finished = new java.util.HashSet<>();
            for (JSONObject block : blocks) if (block != null && block.optString("type").endsWith("_tool_result")) finished.add(block.optString("tool_use_id"));
            JSONArray result = arr();
            for (int i = Math.max(0, from); i < blocks.size(); i++) {
                JSONObject block = blocks.get(i); if (block == null) continue;
                String type = block.optString("type");
                if (type.equals("fallback")) continue;
                if (i < boundary) {
                    boolean keep = type.equals("text") || (type.equals("server_tool_use") && finished.contains(block.optString("id"))) || (type.endsWith("_tool_result") && !type.equals("tool_result"));
                    if (!keep) continue;
                }
                result.put(block);
            }
            return result;
        }
        /** Blocks read so far, for paused(from). */
        public int size() { return blocks.size(); }
        /** The client tool calls (tool_use blocks) from block index from on: [{id, name, input}]. */
        public JSONArray toolCalls(int from) {
            JSONArray calls = arr();
            for (int i = Math.max(0, from); i < blocks.size(); i++) {
                JSONObject block = blocks.get(i);
                if (block != null && "tool_use".equals(block.optString("type"))) calls.put(block);
            }
            return calls;
        }
        /** Starts reading the continuation of a paused turn; its block indexes start again at 0. */
        public void next() { base = blocks.size(); inputs.clear(); completed = false; stopReason = ""; category = ""; failure = null; failureType = ""; usage = null; switched = ""; }
        /** Tokens this message used (the round just read; next() starts the next one), Usage.UNKNOWN when it said nothing. */
        public Usage usage() { return Usage.claude(usage); }
        /**
         * The model that wrote this message when a server-side fallback answered it: the one the last fallback block handed
         * it to, or for a turn sent straight to the fallback model (no block; a fallback_message in usage.iterations) the
         * message's own model; "" when no fallback ran.
         */
        public String fallbackModel() {
            if (!switched.isEmpty()) return switched;
            JSONArray attempts = usage == null ? null : usage.optJSONArray("iterations");
            for (int i = 0; attempts != null && i < attempts.length(); i++) {
                JSONObject attempt = attempts.optJSONObject(i);
                if (attempt != null && "fallback_message".equals(attempt.optString("type"))) return attempt.optString("model").isBlank() ? model : attempt.optString("model");
            }
            return "";
        }

        public boolean completed() { return completed; }
        public String failure() { return failure; }
        /** The error event's type (overloaded_error, …), for deciding whether to try again. */
        public String failureType() { return failureType; }
        public String stopReason() { return stopReason; }
        public String category() { return category; }
        public String text() { return text.toString(); }
        /** The summarized thinking so far, blocks as paragraphs; "" when none came. */
        public String thinking() { return thinking.toString(); }
        /** What the summarized thinking gained since the last call. */
        public String takeThinking() { String added = thinking.substring(thinkingTaken); thinkingTaken = thinking.length(); return added; }
        /** Offsets in text() where it went on after a tool call. */
        public List<Integer> cuts() { return new ArrayList<>(cuts); }
        private void cut() { if (text.length() > 0 && (cuts.isEmpty() || cuts.get(cuts.size() - 1) != text.length())) cuts.add(text.length()); }
        public String stage() { return stage; }
        public String model() { return model; }
        /** The code container this turn ran in, or "". */
        public String container() { return container; }
        /**
         * Nothing has been shown or made yet, so sending the request again repeats nothing the user saw. A thinking summary
         * counts: it was shown as it came, so a request that already sent one is never sent again on its own.
         */
        public boolean empty() { return text.length() == 0 && files.isEmpty() && thinking.length() == 0; }
        /** Files the code left for the user: [{file}], in order. */
        public JSONArray files() { JSONArray result = arr(); for (JSONObject file : files.values()) result.put(file); return result; }
        /** Searched and cited pages as [{title, uri}], the shape task records use for sources; a cited or fetched page also carries cited: true. */
        public JSONArray sources() {
            JSONArray result = arr();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                JSONObject page = obj("title", source.getValue(), "uri", source.getKey());
                if (cited.contains(source.getKey())) put(page, "cited", true);
                result.put(page);
            }
            return result;
        }
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
}
