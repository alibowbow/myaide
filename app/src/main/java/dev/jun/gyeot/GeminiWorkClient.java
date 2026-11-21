package dev.jun.gyeot;

import dev.jun.gyeot.core.WorkBudget;

import dev.jun.gyeot.core.GeminiWork;
import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.Usage;
import dev.jun.gyeot.core.WorkBook;
import dev.jun.gyeot.core.WorkSteering;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 작업창 requests to Gemini on the user's Gemini API key: a thread's conversation streamed with Google Search,
 * page reading and code execution, and single questions for analysis, summaries and filing. Every Gemini text request in the
 * app leaves through stream(); the key headers here take apiKey(). Blocking; run it on a worker thread and run the cancel
 * action (off the main thread) to stop it.
 */
final class GeminiWorkClient {
    private static final int RETRIES = 2;
    private GeminiWorkClient() {}

    /** One streamed Gemini text request (alt=sse) on the API key GeminiWorkAccount.key() gave. */
    static OpenAiAccount.Http.Exchange stream(String key, String model, JSONObject request, int timeoutMs, AtomicReference<Runnable> cancel) throws Exception {
        return OpenAiAccount.Http.open("POST", GeminiWork.streamUrl(model), apiHeaders(key, "text/event-stream"), request.toString().getBytes(StandardCharsets.UTF_8), timeoutMs, cancel);
    }
    /** A saved API key for x-goog-api-key; a blank one is refused before anything is sent. */
    static String apiKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("설정 → Gemini API 키에서 키를 먼저 넣어 주세요.");
        return key;
    }
    /** Headers for a JSON request on a real API key (apiKey()). */
    static Map<String, String> apiHeaders(String key, String accept) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-goog-api-key", apiKey(key)); headers.put("Content-Type", "application/json"); headers.put("Accept", accept);
        return headers;
    }

    /** One turn of a work thread: turns as WorkBook.input gives them (attached images and PDFs with their data), context as WorkBook.context adds. */
    static WorkClient.Result work(String key, String model, String clock, JSONArray turns, String context, boolean search, boolean code, WorkTools github, String effort,
            Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel) throws Exception {
        return work(key, model, clock, turns, context, search, code, github, effort, onText, onStage, onActivity, cancel, WorkSteering.NONE);
    }
    static WorkClient.Result work(String key, String model, String clock, JSONArray turns, String context, boolean search, boolean code, WorkTools github, String effort,
            Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel, WorkSteering steering) throws Exception {
        boolean inspectMerge = github != null && github.inspectMerge(turns);
        String extra = (context == null ? "" : context) + WorkSteering.GUIDANCE;
        JSONArray plain = WorkBook.hasFiles(turns) ? GeminiWork.contents(WorkBook.withoutFiles(turns)) : null;
        Turn turn = run(key, model, (web, run) -> WorkBook.instructions(clock, web, run, WorkBook.GEMINI, false, false, WorkBook.names(github == null ? null : github.declarations())) + extra, GeminiWork.contents(turns), plain, search, code, github, effort, onText, onStage, onActivity, cancel, inspectMerge, turns, steering);
        String[] split = WorkBook.split(turn.text, turn.cuts);
        String answer = turn.inspectMerge || !turn.receipt.isBlank() ? GitHubFunctions.mergeFinal(split[0], turn.receipt) : split[0];
        if (turn.inspectMerge || !turn.receipt.isBlank()) onText.accept(answer);
        return new WorkClient.Result(answer, turn.sources, new JSONArray(), "", turn.note, new JSONArray(), split[1], turn.version, turn.suggestions).counted(turn.usage(), Limits.NONE);
    }
    /** One answer to one request: analysis, summaries, filing. A refused web search is reported in the result. */
    static OpenAiClient.Result complete(String key, String model, Function<Boolean, String> instructions, String input, boolean search, String effort,
            Runnable onActivity, AtomicReference<Runnable> cancel) throws Exception {
        JSONArray contents = arr(obj("role", "user", "parts", arr(obj("text", input == null ? "" : input))));
        Turn turn = run(key, model, (web, run) -> instructions.apply(web), contents, null, search, false, null, effort, text -> {}, stage -> {}, onActivity, cancel, false, arr(), WorkSteering.NONE);
        return new OpenAiClient.Result(turn.text, turn.sources, turn.searchDropped ? turn.note : null, "", turn.usage(), Limits.NONE);
    }
    /** The models this key can use for 작업창 (GeminiWork.models). */
    static JSONArray models(String key) throws Exception { return GeminiWork.models(list(key)); }
    /** The key's whole model list as Google sends it (the text models and the Live models are both read from it). */
    static JSONObject list(String key) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-goog-api-key", apiKey(key)); headers.put("Accept", "application/json");
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", GeminiWork.modelsUrl(), headers, null, 30_000, null)) {
            String body = OpenAiAccount.Http.read(response.body, 4 * 1024 * 1024);
            JSONObject json; try { json = new JSONObject(body); } catch (Exception e) { json = new JSONObject(); }
            if (response.status >= 300) throw new IllegalStateException(GeminiWork.explain(response.status, GeminiWork.message(json)));
            return json;
        }
    }

    private static final class Turn {
        String text = "", note = "", version = "", receipt = "", suggestions = ""; JSONArray sources = new JSONArray(); List<Integer> cuts = List.of(); boolean searchDropped, inspectMerge;
        /** Every finished response's usageMetadata, summed over the rounds of the turn. Google sends no limit headers. */
        Usage used;
        Usage usage() { return used == null ? Usage.UNKNOWN : used; }
    }
    /**
     * A refused optional part is dropped and the request sent again, with a note for the user: the thinking level, attached
     * files (plain is the conversation without them, or null), code execution, then search; a refusal that names nothing
     * drops them one by one. Busy servers get two more tries.
     */
    private static Turn run(String key, String model, BiFunction<Boolean, Boolean, String> system, JSONArray contents, JSONArray plain, boolean search, boolean code, WorkTools github, String effort,
            Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel, boolean inspectMerge, JSONArray turns, WorkSteering steering) throws Exception {
        WorkBudget budget = new WorkBudget();
        if (model == null || model.isBlank()) throw new IllegalStateException("작업창 위의 모델 칩에서 Gemini 모델을 골라 주세요.");
        if (key == null || key.isBlank()) throw new IllegalStateException("설정 → Gemini API 키에서 키를 먼저 넣어 주세요.");
        if (contents.length() == 0) throw new IllegalArgumentException("보낼 내용이 비어 있어요.");
        JSONArray sending = contents, withoutFiles = plain;
        boolean useSearch = search, read = true, useCode = code; String level = effort == null ? "" : effort; int retries = 0;
        List<String> notes = new ArrayList<>(); Turn turn = new Turn();
        // A turn with GitHub function calls goes in rounds: what each round wrote, joined, and the pages it found.
        StringBuilder written = new StringBuilder(); List<Integer> cuts = new ArrayList<>(); JSONArray found = new JSONArray(); int progressOnly = 0; boolean externalWork = false;
        while (true) {
            onActivity.run();
            if (steering.pending()) {
                JSONArray added = steering.take();
                if (added.length() > 0) {
                    WorkSteering.append(turns, added); WorkSteering.append(sending, GeminiWork.contents(added)); withoutFiles = null;
                    inspectMerge = github != null && github.inspectMerge(turns); progressOnly = 0; written.setLength(0); cuts.clear();
                    onStage.accept("추가 지시를 반영해 이어가는 중");
                }
            }
            turn.inspectMerge = inspectMerge;
            // Written for the tools this attempt really has, so the model is never told about one that was dropped.
            String instructions = system.apply(useSearch, useCode) + (github == null ? "" : github.instructions()) + (progressOnly > 0 ? GitHubFunctions.CONTINUE_MERGE : "");
            JSONObject request = GeminiWork.request(model, instructions, sending, useSearch, read, useCode, github == null ? null : github.gemini(), level);
            budget.beforeRequest(request.toString().length(), onActivity);
            externalWork |= sending.toString().contains("executableCode") || sending.toString().contains("codeExecutionResult");
            GeminiWork.Stream stream = new GeminiWork.Stream();
            try (OpenAiAccount.Http.Exchange response = stream(key, model, request, 300_000, cancel)) {
                if (response.status >= 300) {
                    dev.jun.gyeot.core.ModelLimit.check(response.status, !externalWork);
                    String detail = OpenAiAccount.Http.read(response.body, 64 * 1024);
                    JSONObject json; try { json = new JSONObject(detail); } catch (Exception e) { json = new JSONObject(); }
                    String message = GeminiWork.message(json), lower = message.toLowerCase(Locale.ROOT), why = message.isBlank() ? "." : " (" + message + ").";
                    if ((response.status == 500 || response.status == 503) && retries < RETRIES) { retries++; pause(retries); continue; }
                    if (response.status == 400) {
                        if (level != null && lower.contains("think")) { level = null; notes.add("이 모델이 선택한 생각 깊이를 지원하지 않아 모델 기본값으로 답했어요."); continue; }
                        if (withoutFiles != null && lower.matches("(?s).*(inline_data|inline data|mime|image|pdf|document|file).*")) {
                            sending = withoutFiles; withoutFiles = null; notes.add("이 모델에서는 첨부 파일이 거부돼 파일 없이 답했어요" + why);
                            continue;
                        }
                        if (useCode && lower.matches("(?s).*code.?execution.*")) { useCode = false; notes.add("이 모델에서는 코드 실행이 거부돼 코드 없이 답했어요" + why); continue; }
                        // Page reading goes quietly first: search alone still finds and cites pages.
                        if (useSearch && read && lower.matches("(?s).*url.?context.*")) { read = false; continue; }
                        if (useSearch && lower.matches("(?s).*(google_search|url_context|search|grounding).*")) {
                            useSearch = false; turn.searchDropped = true; notes.add("이 모델에서는 웹 검색이 거부돼 검색 없이 답했어요" + why);
                            continue;
                        }
                        // A refusal that names nothing: the optional parts go one per try, so the turn still gets an answer.
                        if (level != null) { level = null; notes.add("요청이 거절되어 생각 깊이를 모델 기본값으로 다시 보냈어요."); continue; }
                        if (useCode) { useCode = false; notes.add("Gemini가 요청을 거절해 코드 실행을 빼고 다시 보냈어요" + why); continue; }
                        if (useSearch) { useSearch = false; turn.searchDropped = true; notes.add("Gemini가 요청을 거절해 웹 검색을 빼고 다시 보냈어요" + why); continue; }
                    }
                    throw new IllegalStateException(GeminiWork.explain(response.status, message));
                }
                String stage = "";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        onActivity.run();
                        String delta = stream.line(line);
                        if (delta != null && !inspectMerge) onText.accept(delta);
                        if (!stream.stage().equals(stage)) { stage = stream.stage(); onStage.accept(stage); }
                        if (stream.failure() != null) break;
                    }
                }
            } finally { if (cancel != null) cancel.set(null); }
            if (stream.failure() != null) throw new IllegalStateException(stream.failure());
            if (!stream.finished()) throw new IllegalStateException("Gemini 응답이 끝나기 전에 연결이 끊겼어요. 다시 보내 주세요.");
            turn.used = Usage.sum(turn.used, stream.usage());
            if (inspectMerge && stream.calls().length() == 0 && stream.note().isBlank() && GitHubFunctions.promisesMerge(stream.text())) {
                if (++progressOnly > 2) throw new IllegalStateException("Gemini가 계획만 답하고 병합 도구를 실행하지 않았어요. 병합은 완료되지 않았습니다.");
                JSONArray next = new JSONArray();
                for (int i = 0; i < sending.length(); i++) next.put(sending.opt(i));
                next.put(obj("role", "model", "parts", stream.parts()));
                next.put(obj("role", "user", "parts", arr(obj("text", GitHubFunctions.CONTINUE_MERGE))));
                sending = next; withoutFiles = null;
                onStage.accept("계획 답변에 이어 실제 GitHub 작업을 요청하는 중");
                continue;
            }
            int base = written.length();
            if (base > 0 && !stream.text().isEmpty()) { written.append("\n\n"); base += 2; cuts.add(base); }
            for (int cut : stream.cuts()) cuts.add(base + cut);
            written.append(stream.text());
            for (int i = 0; i < stream.sources().length(); i++) found.put(stream.sources().opt(i));
            // Google's search suggestions go with the grounded answer: the last round's that came with any.
            if (!stream.searchEntry().isBlank()) turn.suggestions = stream.searchEntry();
            // The app's GitHub functions: the model's turn goes back as it came (thought signatures included) with each result.
            if (github != null && stream.calls().length() > 0) {
                budget.beforeBatch(stream.calls().length(), (long) sending.toString().length() + stream.parts().toString().length(), onActivity, onStage);
                JSONArray responses = new JSONArray();
                for (int i = 0; i < stream.calls().length(); i++) {
                    budget.beforeTool(onActivity);
                    JSONObject call = stream.calls().getJSONObject(i); String name = call.optString("name");
                    onStage.accept(WorkTools.stage(name));
                    onActivity.run(); boolean superseded = steering.pending();
                    JSONObject result = superseded ? WorkSteering.skipped() : github != null && github.supports(name) ? github.run(name, call.optJSONObject("args")) : GitHubFunctions.failure("unknown function " + name);
                    if (!superseded && GitHubFunctions.MERGE.equals(name)) turn.receipt = GitHubFunctions.mergeReceipt(result);
                    budget.result(name, String.valueOf(call.optJSONObject("args")), result.toString(), result.has("ok") && !result.optBoolean("ok"));
                    onActivity.run();
                    // Gemini takes the result as an object; one too long goes as clipped text inside one.
                    JSONObject response = obj("name", name, "response", result.toString().length() <= GitHubFunctions.RESULT_CHARS ? result : obj("result", GitHubFunctions.clip(result.toString())));
                    if (!call.optString("id").isBlank()) response.put("id", call.optString("id"));
                    responses.put(obj("functionResponse", response));
                    if (!turn.receipt.isBlank()) break;
                }
                if (!turn.receipt.isBlank()) {
                    turn.text = turn.receipt; turn.sources = found; turn.version = stream.version(); turn.note = String.join(" ", notes);
                    return turn;
                }
                JSONArray next = new JSONArray();
                for (int i = 0; i < sending.length(); i++) next.put(sending.opt(i));
                next.put(obj("role", "model", "parts", stream.parts()));
                next.put(obj("role", "user", "parts", responses));
                sending = next; withoutFiles = null;
                if (written.length() > 0 && !inspectMerge) onText.accept("\n\n");
                onStage.accept("생각하는 중");
                continue;
            }
            if (stream.calls().length() == 0 && stream.note().isBlank() && steering.pending()) {
                sending.put(obj("role", "model", "parts", stream.parts())); withoutFiles = null;
                if (!inspectMerge) onText.accept("\n\n"); continue;
            }
            if (written.toString().isBlank()) throw new IllegalStateException(stream.note().isBlank() ? "Gemini가 답을 보내지 않았어요." : stream.note());
            if (!stream.note().isBlank()) notes.add(stream.note());
            turn.text = written.toString(); turn.cuts = cuts; turn.sources = found; turn.version = stream.version(); turn.note = String.join(" ", notes);
            return turn;
        }
    }
    private static void pause(int attempt) throws InterruptedException { Thread.sleep(2_000L * attempt); }
}



