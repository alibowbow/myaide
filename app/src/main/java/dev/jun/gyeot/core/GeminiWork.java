package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Gemini in 작업창, on the user's Gemini API key: a streamed generateContent turn with the thread's conversation (pictures
 * and PDFs inline), Google Search and page reading for current facts, and Python code execution, all run by Google during
 * the turn. Which models the key offers, how a model takes its thinking level, and reading the stream. Pure data.
 */
public final class GeminiWork {
    public static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";
    /** Offered before the key's own list arrives: the newest Flash, Pro and Flash-Lite, which Google moves to each new release. */
    public static final String[][] DEFAULT_MODELS = {{"gemini-flash-latest", "Gemini Flash 최신"}, {"gemini-pro-latest", "Gemini Pro 최신"}, {"gemini-flash-lite-latest", "Gemini Flash-Lite 최신"}};
    /** For small jobs (filing a conversation): the lightest model. */
    public static final String LIGHT = "gemini-flash-lite-latest";
    /**
     * The app's own Gemini jobs (search, titles, watching video, hearing recordings): Flash-Lite, whose free tier allows 500 a
     * day per model, where Flash allows 20; 3.5 first, then 3.1, then whatever Flash-Lite is newest. Work that needs more than
     * Flash-Lite goes to Claude, GPT or Grok, not to a Gemini Flash.
     */
    public static final String[] FLASH_LITE = {"gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-flash-lite-latest"};
    private static final Pattern ALIAS = Pattern.compile("^gemini-(flash|pro|flash-lite)-latest$");
    /** A numbered model of any family: "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-4-argon", "gemini-3.8-pro-preview". */
    private static final Pattern VERSIONED = Pattern.compile("^gemini-(\\d+(?:\\.\\d+)?)-([a-z]+(?:-lite)?)(-preview)?$");
    /** Families that cannot answer a 작업창 turn: speech, live audio, pictures, embeddings and the like. */
    private static final java.util.Set<String> SPECIAL = java.util.Set.of("live", "tts", "image", "embedding", "audio", "transcribe", "robotics", "computer", "nano");
    /** Any numbered model id, for naming the version that answered: "gemini-3.8-flash-preview-09-2026" too. */
    private static final Pattern NAMED = Pattern.compile("^gemini-(\\d+(?:\\.\\d+)?)-(flash-lite|[a-z]+)(-preview)?(?:-.*)?$");
    private GeminiWork() {}

    public static String streamUrl(String model) { return BASE + "models/" + model + ":streamGenerateContent?alt=sse"; }
    public static String modelsUrl() { return BASE + "models?pageSize=200"; }

    /**
     * The models in a key's list that can take this work, as [{id, name}]: the -latest aliases first (Flash, Pro, Flash-Lite),
     * then numbered models newest first, Flash before Pro before Flash-Lite, previews after their stable release. An alias the
     * list leaves out is added when the list has a model of its kind. Live, image, speech, embedding and other special models
     * are left out.
     */
    public static JSONArray models(JSONObject list) {
        JSONArray found = list == null ? null : list.optJSONArray("models");
        List<JSONObject> kept = new ArrayList<>();
        for (int i = 0; found != null && i < found.length(); i++) {
            JSONObject model = found.optJSONObject(i); if (model == null) continue;
            String id = model.optString("name").replaceFirst("^models/", "");
            JSONArray methods = model.optJSONArray("supportedGenerationMethods");
            boolean generates = methods == null || methods.toString().contains("\"generateContent\"");
            if (!generates || !(ALIAS.matcher(id).matches() || numbered(id) != null)) continue;
            String name = model.optString("displayName").strip();
            kept.add(obj("id", id, "name", name.isEmpty() || ALIAS.matcher(id).matches() ? label(id) : name));
        }
        for (String[] alias : DEFAULT_MODELS) {
            String kind = ALIAS.matcher(alias[0]).replaceFirst("$1");
            boolean listed = false, offered = false;
            for (JSONObject model : kept) {
                String id = model.optString("id"); Matcher versioned = numbered(id);
                listed |= id.equals(alias[0]); offered |= versioned != null && versioned.group(2).equals(kind);
            }
            if (!listed && offered) kept.add(obj("id", alias[0], "name", alias[1]));
        }
        kept.sort((a, b) -> Double.compare(rank(b.optString("id")), rank(a.optString("id"))));
        JSONArray result = arr();
        for (JSONObject model : kept) result.put(model);
        if (result.length() == 0) for (String[] model : DEFAULT_MODELS) result.put(obj("id", model[0], "name", model[1]));
        return result;
    }
    /** A numbered model that can answer, with its version, family and preview mark as groups; else null. */
    private static Matcher numbered(String id) {
        Matcher versioned = VERSIONED.matcher(id == null ? "" : id);
        return versioned.matches() && !SPECIAL.contains(versioned.group(2).replace("-lite", "")) ? versioned : null;
    }
    /** Higher first: aliases, then by version, Flash before Pro (and the other big families, like Argon) before Flash-Lite, stable before preview. */
    private static double rank(String id) {
        Matcher alias = ALIAS.matcher(id);
        if (alias.matches()) return 1_000_000 - tier(alias.group(1));
        Matcher versioned = numbered(id);
        if (versioned == null) return 0;
        return Double.parseDouble(versioned.group(1)) * 1000 - tier(versioned.group(2)) * 10 - (versioned.group(3) == null ? 0 : 1);
    }
    private static int tier(String kind) { return kind.equals("flash") ? 0 : kind.equals("flash-lite") ? 2 : 1; }
    /** "Gemini Flash 최신", "Gemini 3.8 Flash", "Gemini 4 Argon", "Gemini 3.8 Flash-Lite (미리보기)": a model id as people read it. */
    public static String label(String id) {
        if (id == null || id.isBlank()) return "Gemini";
        Matcher alias = ALIAS.matcher(id);
        if (alias.matches()) return "Gemini " + kind(alias.group(1)) + " 최신";
        Matcher named = NAMED.matcher(id);
        if (named.matches()) return "Gemini " + named.group(1) + " " + kind(named.group(2)) + (named.group(3) == null ? "" : " (미리보기)");
        return id;
    }
    private static String kind(String kind) {
        if (kind.equals("flash-lite")) return "Flash-Lite";
        return kind.isEmpty() ? kind : Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }

    /**
     * The app's balanced thinking profiles. Recent Pro, Flash and Argon support medium; legacy 3 Pro only low/high.
     * Flash 3.7/3.8 and Argon reject minimal. Older models take a token budget.
     * Empty uses the app's balanced default, explicitly sent on the wire. Models before Gemini 3 take a thinking budget.
     */
    public static Reasoning.Profile profile(String model) {
        String id = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (id.matches("gemini-3\\.(7|8).*flash.*")) return Reasoning.Profile.of(List.of("low", "medium", "high"), "medium");
        if (id.contains("flash")) return Reasoning.Profile.of(List.of("minimal", "low", "medium", "high"), "medium");
        if (id.equals("gemini-3-pro-preview")) return Reasoning.Profile.of(List.of("low", "high"), "low");
        if (id.contains("-pro")) return Reasoning.Profile.of(List.of("low", "medium", "high"), "medium");
        if (id.contains("argon")) return Reasoning.Profile.of(List.of("low", "medium", "high"), "medium");
        return Reasoning.Profile.of(List.of("low", "medium", "high"), "medium");
    }
    /** A model id someone typed: Gemini's own form, nothing that could leave the models path. */
    public static boolean validModel(String id) { return id != null && id.matches("gemini-[a-z0-9][a-z0-9.-]{1,60}"); }
    /** The thinking setting for a level: thinkingLevel from Gemini 3 on, a token budget for the 2.x models. */
    public static JSONObject thinking(String model, String effort) {
        if (model != null && model.startsWith("gemini-2.")) {
            boolean pro = model.contains("-pro");
            int budget = switch (effort) { case "minimal" -> pro ? 128 : 0; case "low" -> 1024; case "medium" -> 8192; default -> pro ? 32768 : 24576; };
            return obj("thinkingBudget", budget);
        }
        return obj("thinkingLevel", effort);
    }

    /**
     * The conversation as Gemini contents: assistant turns as "model", the user's with their pictures and PDFs as inline data
     * (files carrying base64 "data"); turns of the same side in a row are joined.
     */
    public static JSONArray contents(JSONArray turns) {
        JSONArray contents = arr(); String last = "";
        for (int i = 0; turns != null && i < turns.length(); i++) {
            JSONObject turn = turns.optJSONObject(i); if (turn == null) continue;
            String role = "assistant".equals(turn.optString("role")) ? "model" : "user", text = turn.optString("content");
            JSONArray parts = arr();
            JSONArray files = role.equals("user") ? turn.optJSONArray("files") : null;
            for (int f = 0; files != null && f < files.length(); f++) {
                JSONObject file = files.optJSONObject(f); String data = file == null ? "" : file.optString("data");
                if (data.isEmpty()) continue;
                String mime = WorkBook.PDF.equals(file.optString("kind")) ? "application/pdf" : file.optString("mime", "image/png");
                parts.put(obj("inline_data", obj("mime_type", mime, "data", data)));
            }
            if (!text.isBlank() || parts.length() == 0) parts.put(obj("text", text.isBlank() ? "첨부한 파일을 봐 주세요." : text));
            if (role.equals(last)) { JSONArray previous = contents.optJSONObject(contents.length() - 1).optJSONArray("parts"); for (int p = 0; p < parts.length(); p++) previous.put(parts.opt(p)); }
            else contents.put(obj("role", role, "parts", parts));
            last = role;
        }
        return contents;
    }
    /** A 작업창 turn: the instructions, the conversation, Google Search with page reading when allowed, code execution, and the thinking level. */
    public static JSONObject request(String model, String instructions, JSONArray contents, boolean search, boolean code, String effort) {
        return request(model, instructions, contents, search, search, code, effort);
    }
    /** As above, with page reading (url_context) apart from search, for a model that refuses only that. */
    public static JSONObject request(String model, String instructions, JSONArray contents, boolean search, boolean read, boolean code, String effort) {
        return request(model, instructions, contents, search, read, code, null, effort);
    }
    /** As above, with the app's own functions ({functionDeclarations: [...]}) when functions is given; the app runs them. */
    public static JSONObject request(String model, String instructions, JSONArray contents, boolean search, boolean read, boolean code, JSONObject functions, String effort) {
        return request(model, instructions, contents, search, read, code, functions, effort, false);
    }
    /**
     * As above; thoughts asks for summaries of the model's thinking (thinkingConfig.includeThoughts), which stream as parts
     * marked thought and stay out of Stream's answer text. Without a thinking setting nothing is added.
     */
    public static JSONObject request(String model, String instructions, JSONArray contents, boolean search, boolean read, boolean code, JSONObject functions, String effort, boolean thoughts) {
        return request(model, instructions, contents, search, read, code, functions, effort, thoughts, false);
    }
    /**
     * As above; readAlone reads pages (url_context) without Google Search too, so the main chat reads a linked page with
     * search off. The Gemini API takes url_context alone; 작업창 keeps it beside search only.
     */
    public static JSONObject request(String model, String instructions, JSONArray contents, boolean search, boolean read, boolean code, JSONObject functions, String effort, boolean thoughts, boolean readAlone) {
        JSONObject body = obj("systemInstruction", obj("parts", arr(obj("text", instructions == null ? "" : instructions))), "contents", contents);
        JSONArray tools = arr();
        if (search) tools.put(obj("google_search", obj()));
        if (read && (search || readAlone)) tools.put(obj("url_context", obj()));
        if (code) tools.put(obj("code_execution", obj()));
        if (functions != null) tools.put(functions);
        if (tools.length() > 0) put(body, "tools", tools);
        // null is reserved for one explicit compatibility retry; an empty saved choice means app default.
        if (effort != null) {
            JSONObject thinking = thinking(model, profile(model).resolve(effort));
            if (thoughts) put(thinking, "includeThoughts", true);
            put(body, "generationConfig", obj("thinkingConfig", thinking));
        }
        return body;
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    /** The error's message from a Gemini error body. */
    public static String message(JSONObject body) {
        JSONObject error = body == null ? null : body.optJSONObject("error");
        return error == null ? "" : error.optString("message");
    }
    /**
     * A 400 about the thought summaries alone (includeThoughts on a model or plan that does not take them): Google did no
     * work, so the main chat sends the request once more without them.
     */
    public static boolean thoughtsRefused(int status, String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return status == 400 && (lower.contains("includethoughts") || lower.contains("include_thoughts") || (lower.contains("thought") && lower.contains("summar")));
    }
    private static final Pattern READ_TOOL = Pattern.compile("url[_ ]?context"), SEARCH_TOOL = Pattern.compile("google[_ ]?search|\\bsearch\\b|\\bgrounding\\b"),
        ANY_TOOL = Pattern.compile("\\btools?\\b|\\btool_choice\\b");
    /**
     * Which hosted web tool a 400 refused before Google did any work: "url_context" when the reason names page reading,
     * "google_search" when it names Google Search (a whole word: never "research"), "tools" when it names no tool of its own
     * but is about tools (a whole-word tool or tools, tool_choice) or says only that the request was invalid ("Request
     * contains an invalid argument.", or nothing); "" for any other failure. The main chat counts a refusal that names no
     * tool against a tool only once the request sent again without that tool is answered.
     */
    public static String refusedTool(int status, String message) {
        if (status != 400) return "";
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (READ_TOOL.matcher(lower).find()) return "url_context";
        if (SEARCH_TOOL.matcher(lower).find()) return "google_search";
        return lower.isBlank() || lower.contains("invalid argument") || ANY_TOOL.matcher(lower).find() ? "tools" : "";
    }
    /** A failed request in Korean, with what to do about it in 작업창. */
    public static String explain(int status, String message) {
        String text = message == null ? "" : message.strip(), lower = text.toLowerCase(Locale.ROOT), hint;
        if (status == 401 || status == 403 || lower.contains("api key") || lower.contains("permission")) hint = "작업창 Gemini 키가 올바르지 않거나 이 모델을 쓸 권한이 없어요. 설정 → Gemini API 키를 확인해 주세요.";
        else if (status == 429 || lower.contains("quota") || lower.contains("resource_exhausted")) hint = "작업창 Gemini 키의 사용 한도에 닿았어요. 잠시 뒤 다시 보내거나, 위의 모델 칩에서 Flash-Lite처럼 가벼운 모델로 바꿔 보세요. 무료 한도는 프로젝트마다 따로라, 음성과 나누려면 다른 프로젝트의 키를 작업창용으로 넣어 주세요.";
        else if (status == 404 || lower.contains("not found") || lower.contains("is not supported")) hint = "이 Gemini 모델을 쓸 수 없어요. 위의 모델 칩에서 다른 모델을 골라 주세요.";
        else if (status >= 500) hint = "Gemini 서버에 일시적인 문제가 있어요. 잠시 후 다시 보내 주세요.";
        else return text.isEmpty() ? "Gemini 요청에 실패했어요 (" + status + ")." : "Gemini 요청에 실패했어요: " + (text.length() > 200 ? text.substring(0, 200) + "…" : text);
        return text.isEmpty() ? hint : hint + " (" + (text.length() > 160 ? text.substring(0, 160) + "…" : text) + ")";
    }

    /**
     * A streamed answer (alt=sse): the text with thought parts left out, the pages Google Search grounded it on (and the
     * pages url_context read), Google's search suggestions for it (searchEntryPoint.renderedContent, which Google requires
     * shown with a grounded answer), the model version that actually answered, and where the text went on after a code run
     * (for WorkBook.split). Text after a code run starts a new paragraph.
     */
    public static final class Stream {
        private final StringBuilder text = new StringBuilder();
        private final Map<String, String> sources = new LinkedHashMap<>();
        /** The pages url_context read: the question's own links, so they come first in the source strip. */
        private final Set<String> fetched = new HashSet<>();
        private final List<Integer> cuts = new ArrayList<>();
        /** Every part of the model's turn as it came (thought signatures included), to send back with function results; and the calls in it. */
        private final JSONArray parts = arr(), calls = arr();
        private String stage = "", finish = "", failure, version = "", note = "", entry = "";
        private boolean paragraph;
        /** The thought summaries (thinkingConfig.includeThoughts): parts marked thought, joined as they came; never part of text(). */
        private final StringBuilder thinking = new StringBuilder();
        private int thinkingTaken;
        /** The latest usageMetadata: each chunk may carry one, and the last one has the whole answer's counts. */
        private JSONObject usage;

        /** One line of the stream; returns new answer text to show, or null. */
        public String line(String line) {
            if (line == null || !line.startsWith("data:")) return null;
            JSONObject chunk;
            try { chunk = new JSONObject(line.substring(5).strip()); } catch (Exception e) { return null; }
            return chunk(chunk);
        }
        public String chunk(JSONObject chunk) {
            if (chunk.optJSONObject("error") != null) { failure = explain(chunk.optJSONObject("error").optInt("code"), message(chunk)); return null; }
            if (!chunk.optString("modelVersion").isBlank()) version = chunk.optString("modelVersion").replaceFirst("^models/", "");
            if (chunk.optJSONObject("usageMetadata") != null) usage = chunk.optJSONObject("usageMetadata");
            JSONObject feedback = chunk.optJSONObject("promptFeedback");
            if (feedback != null && !feedback.optString("blockReason").isBlank()) { failure = "Gemini가 이 요청에 답하지 않았어요 (" + feedback.optString("blockReason") + ")."; return null; }
            JSONArray candidates = chunk.optJSONArray("candidates");
            JSONObject candidate = candidates == null ? null : candidates.optJSONObject(0);
            if (candidate == null) return null;
            StringBuilder delta = new StringBuilder();
            JSONObject content = candidate.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i); if (part == null) continue;
                this.parts.put(part);
                if (part.optBoolean("thought")) {
                    stage = "생각하는 중"; String thought = part.optString("text");
                    if (!thought.isEmpty() && thinking.length() + thought.length() <= MainRequest.THINKING) thinking.append(thought);
                    continue;
                }
                if (part.optJSONObject("functionCall") != null) {
                    calls.put(part.optJSONObject("functionCall")); stage = "GitHub 작업 중";
                    if (text.length() > 0 && (cuts.isEmpty() || cuts.get(cuts.size() - 1) != text.length())) cuts.add(text.length());
                    paragraph = text.length() > 0;
                    continue;
                }
                if (part.has("executableCode") || part.has("codeExecutionResult")) {
                    stage = "코드 실행 중";
                    if (text.length() > 0 && (cuts.isEmpty() || cuts.get(cuts.size() - 1) != text.length())) cuts.add(text.length());
                    paragraph = text.length() > 0;
                    continue;
                }
                String value = part.optString("text");
                if (value.isEmpty()) continue;
                if (paragraph) { paragraph = false; String end = text.toString(); if (!end.endsWith("\n\n")) value = (end.endsWith("\n") ? "\n" : "\n\n") + value; }
                stage = "답 쓰는 중";
                text.append(value); delta.append(value);
            }
            grounding(candidate.optJSONObject("groundingMetadata"));
            read(candidate.optJSONObject("urlContextMetadata"));
            String reason = candidate.optString("finishReason");
            if (!reason.isBlank()) {
                finish = reason;
                if (reason.matches("SAFETY|RECITATION|BLOCKLIST|PROHIBITED_CONTENT|SPII|IMAGE_SAFETY")) note = "Gemini가 이유(" + reason + ")로 답을 중간에 멈췄어요.";
                else if (reason.equals("MAX_TOKENS")) note = "답이 길어 Gemini가 중간에 멈췄어요. ‘계속해 줘’라고 보내면 이어서 해요.";
            }
            return delta.length() == 0 ? null : delta.toString();
        }
        private void grounding(JSONObject metadata) {
            if (metadata == null) return;
            if (metadata.optJSONArray("webSearchQueries") != null && text.length() == 0) stage = "웹 검색 중";
            JSONArray chunks = metadata.optJSONArray("groundingChunks");
            for (int i = 0; chunks != null && i < chunks.length() && sources.size() < 20; i++) {
                JSONObject web = chunks.optJSONObject(i) == null ? null : chunks.optJSONObject(i).optJSONObject("web");
                String uri = web == null ? "" : web.optString("uri");
                if (uri.startsWith("https://") || uri.startsWith("http://")) sources.putIfAbsent(uri, web.optString("title", uri).isBlank() ? uri : web.optString("title", uri));
            }
            JSONObject point = metadata.optJSONObject("searchEntryPoint") != null ? metadata.optJSONObject("searchEntryPoint") : metadata.optJSONObject("search_entry_point");
            String html = point == null ? "" : point.optString("renderedContent", point.optString("rendered_content"));
            if (!html.isBlank() && html.length() <= MainSearch.SUGGESTIONS) entry = html;
        }
        /**
         * The pages url_context read for the answer (urlContextMetadata.urlMetadata): only the ones it retrieved. They are
         * cited, so a pasted page shows however many pages Google Search grounded the answer on, and have a cap of their own.
         */
        private void read(JSONObject metadata) {
            JSONArray pages = metadata == null ? null : metadata.optJSONArray("urlMetadata");
            for (int i = 0; pages != null && i < pages.length() && fetched.size() < 20; i++) {
                JSONObject page = pages.optJSONObject(i); String uri = page == null ? "" : page.optString("retrievedUrl");
                if ((uri.startsWith("https://") || uri.startsWith("http://")) && page.optString("urlRetrievalStatus").endsWith("SUCCESS")) { sources.putIfAbsent(uri, uri); fetched.add(uri); }
            }
        }
        public String text() { return text.toString(); }
        /** The thought summaries so far; "" when none came. */
        public String thinking() { return thinking.toString(); }
        /** What the thought summaries gained since the last call. */
        public String takeThinking() { String added = thinking.substring(thinkingTaken); thinkingTaken = thinking.length(); return added; }
        public List<Integer> cuts() { return new ArrayList<>(cuts); }
        /** The model's turn as it came, for sending back with function results. */
        public JSONArray parts() { return parts; }
        /** The function calls in this turn: [{name, args, id?}]. */
        public JSONArray calls() { return calls; }
        public String stage() { return stage; }
        public String failure() { return failure; }
        public boolean finished() { return !finish.isEmpty(); }
        /** The model that answered, e.g. "gemini-3.8-flash" behind "gemini-flash-latest"; "" when not told. */
        public String version() { return version; }
        public String note() { return note; }
        /** Tokens the answer used (Usage.gemini), Usage.UNKNOWN when no chunk said. */
        public Usage usage() { return Usage.gemini(usage); }
        /** Google's search suggestions for this grounded answer as HTML and CSS, to show unchanged beside it; "" when none came. */
        public String searchEntry() { return entry; }
        /** Pages Google Search grounded the answer on, and the pages url_context read (cited): [{uri, title, cited?}]. */
        public JSONArray sources() {
            JSONArray result = arr();
            for (Map.Entry<String, String> source : sources.entrySet())
                result.put(fetched.contains(source.getKey()) ? obj("uri", source.getKey(), "title", source.getValue(), "cited", true) : obj("uri", source.getKey(), "title", source.getValue()));
            return result;
        }
    }
}

