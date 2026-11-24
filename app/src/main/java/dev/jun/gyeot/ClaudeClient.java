package dev.jun.gyeot;

import dev.jun.gyeot.core.WorkBudget;

import dev.jun.gyeot.core.ClaudeProtocol;
import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.Usage;
import dev.jun.gyeot.core.WorkBook;
import dev.jun.gyeot.core.WorkSteering;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Claude Messages transport: streamed answers with web search and fetch, code execution whose files come back through the
 * Files API, paused long turns picked up again, and a server-side fallback model when the chosen one declines. Blocking; run
 * it on a worker thread and run the cancel action (off the main thread) to stop it.
 */
final class ClaudeClient {
    /** Ultracode on: the most thorough, correct result for real work; light talk stays light. */
    static final String ULTRACODE = "\n\nUltracode is on. For substantive work (research, analysis, code, writing, any multi-step task) aim for the most exhaustive, correct result, not the quickest: "
        + "break the work into parts and cover each one fully, check every conclusion by trying to refute it, then ask what is still missing (a source not read, a claim not verified, a case not tried) and close those gaps before answering. "
        + "For light conversation or a trivial request, answer as usual.";
    /** Room for the answer (and the thinking before it): a spoken or typed answer, and a long piece of work. */
    private static final int ANSWER_TOKENS = 32_000, WORK_TOKENS = 64_000, RETRIES = 2, CONTINUATIONS = 8;
    /** Main chat: room for a Markdown answer with a chart or diagram page in it (thinking included), still bounded. */
    static final int MAIN_TOKENS = 16_000;
    /** Main chat: at most this many web searches in one request (web_search max_uses); each is billed on an API key. */
    static final int MAIN_SEARCHES = 5;
    private ClaudeClient() {}

    /** One answer to one request: the brain, analysis, search and recording summaries. A refused web search is reported in the result. */
    static OpenAiClient.Result complete(ClaudeAccount account, String model, Function<Boolean, String> instructions, String input, boolean search, String effort,
            Runnable onActivity, AtomicReference<Runnable> cancel) throws Exception {
        Turn turn = run(account, model, (web, code) -> instructions.apply(web), ClaudeProtocol.ask(input), null, search, search, false, "", null, effort, ANSWER_TOKENS, false, text -> {}, stage -> {}, onActivity, cancel, false, new JSONArray(), WorkSteering.NONE);
        return new OpenAiClient.Result(turn.text, turn.sources, turn.searchDropped ? turn.note : null, "", turn.usage(), turn.limits);
    }
    /** Main chat explicitly selected one model: never send a server-side fallback_model. */
    static OpenAiClient.Result completeStrict(ClaudeAccount account, String model, Function<Boolean, String> instructions, String input, String effort,
            Runnable onActivity, AtomicReference<Runnable> cancel) throws Exception {
        return completeStrict(account, model, instructions, input, effort, onActivity, cancel, text -> {});
    }
    /** As completeStrict, telling onText the answer as it streams in (누르고 말하기 reads it aloud meanwhile). */
    static OpenAiClient.Result completeStrict(ClaudeAccount account, String model, Function<Boolean, String> instructions, String input, String effort,
            Runnable onActivity, AtomicReference<Runnable> cancel, Consumer<String> onText) throws Exception {
        return completeStrict(account, model, instructions.apply(false), input, false, false, null, effort, onActivity, cancel, onText);
    }
    /**
     * As above, with Claude's own web search (at most MAIN_SEARCHES searches) and page reading when asked and, when
     * onThinking is given, a readable summary of adaptive thinking that it hears as it streams in (the main chat's request,
     * MainAdapters). A refused web search or page reading is reported in the result, after three requests at most; a
     * request refused only for the summary throws MainRequest.SummaryRefused before any answer.
     */
    static OpenAiClient.Result completeStrict(ClaudeAccount account, String model, String instructions, String input, boolean search, boolean fetch, Consumer<String> onThinking, String effort,
            Runnable onActivity, AtomicReference<Runnable> cancel, Consumer<String> onText) throws Exception {
        return completeStrict(account, model, instructions, input, search, fetch, onThinking, false, effort, onActivity, cancel, onText);
    }
    /**
     * As above; cacheSystem marks the instructions for the prompt cache (ClaudeProtocol.cacheSystem), which the main chat's
     * replies ask for. The result carries what the request used and the limits Claude's response headers reported.
     */
    static OpenAiClient.Result completeStrict(ClaudeAccount account, String model, String instructions, String input, boolean search, boolean fetch, Consumer<String> onThinking, boolean cacheSystem, String effort,
            Runnable onActivity, AtomicReference<Runnable> cancel, Consumer<String> onText) throws Exception {
        return completeStrict(account, model, instructions, input, search, fetch, onThinking, cacheSystem, effort, null, onActivity, cancel, onText);
    }
    /**
     * As above, with the app's own functions when tools is given (the main chat's connected services, MainTools), in rounds
     * as in 작업창; the last round's text is the answer. Without tools the request is exactly the one above.
     */
    static OpenAiClient.Result completeStrict(ClaudeAccount account, String model, String instructions, String input, boolean search, boolean fetch, Consumer<String> onThinking, boolean cacheSystem, String effort,
            WorkTools tools, Runnable onActivity, AtomicReference<Runnable> cancel, Consumer<String> onText) throws Exception {
        return completeStrict(account, model, instructions, input, search, fetch, onThinking, cacheSystem, effort, tools, onActivity, cancel, onText, null);
    }
    /**
     * As above, with files: the photos and PDFs attached to the question (MainAttachments.parts, each with its base64 data), as
     * image and document blocks before its text (ClaudeProtocol.conversation). A refusal of them is never answered without
     * them: the reply fails with the reason. Null or none sends the request above.
     */
    static OpenAiClient.Result completeStrict(ClaudeAccount account, String model, String instructions, String input, boolean search, boolean fetch, Consumer<String> onThinking, boolean cacheSystem, String effort,
            WorkTools tools, Runnable onActivity, AtomicReference<Runnable> cancel, Consumer<String> onText, JSONArray files) throws Exception {
        return completeStrict(account, model, instructions, input, search, fetch, false, onThinking, cacheSystem, effort, tools, onActivity, cancel, onText, files);
    }
    /** The same with Claude's code execution when code is asked (its files stay with Claude: the answer is its text). */
    static OpenAiClient.Result completeStrict(ClaudeAccount account, String model, String instructions, String input, boolean search, boolean fetch, boolean code, Consumer<String> onThinking, boolean cacheSystem, String effort,
            WorkTools tools, Runnable onActivity, AtomicReference<Runnable> cancel, Consumer<String> onText, JSONArray files) throws Exception {
        JSONArray asked = files != null && files.length() > 0 ? ClaudeProtocol.conversation(dev.jun.gyeot.core.MainAttachments.turn(input, files)) : ClaudeProtocol.ask(input);
        // With the connected services an answer may carry a long function argument (a file, a piece's new text): more room.
        Turn turn = run(account, model, (web, coding) -> instructions, asked, null, search, fetch, code, "", tools, effort, tools == null && !code ? MAIN_TOKENS : ANSWER_TOKENS, false,
            onText, stage -> {}, onActivity, cancel, false, new JSONArray(), WorkSteering.NONE, false, onThinking, cacheSystem, true);
        String text = tools == null ? turn.text : WorkBook.split(turn.text, turn.cuts)[0];
        return new OpenAiClient.Result(text, turn.sources, turn.searchDropped ? turn.note : null, "", turn.fetchDropped, turn.usage(), turn.limits);
    }
    /**
     * One turn of a 작업창 thread, streamed, with the files its code left for the user: turns as WorkBook.input gives them
     * (attached images and PDFs with their data), context as WorkBook.context adds to the instructions.
     */
    static WorkClient.Result work(ClaudeAccount account, String model, String clock, JSONArray turns, String context, boolean search, boolean code, WorkTools github, String container, String effort,
            Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel) throws Exception {
        return work(account, model, clock, turns, context, search, code, github, container, effort, onText, onStage, onActivity, cancel, WorkSteering.NONE);
    }
    static WorkClient.Result work(ClaudeAccount account, String model, String clock, JSONArray turns, String context, boolean search, boolean code, WorkTools github, String container, String effort,
            Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel, WorkSteering steering) throws Exception {
        return work(account,model,clock,turns,context,search,code,github,container,effort,onText,onStage,onActivity,cancel,steering,true);
    }
    static WorkClient.Result work(ClaudeAccount account, String model, String clock, JSONArray turns, String context, boolean search, boolean code, WorkTools github, String container, String effort,
            Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel, WorkSteering steering, boolean allowFallback) throws Exception {
        boolean inspectMerge = github != null && github.inspectMerge(turns);
        String extra = context == null ? "" : context;
        JSONArray plain = WorkBook.hasFiles(turns) ? ClaudeProtocol.conversation(WorkBook.withoutFiles(turns)) : null;
        Turn turn = run(account, model, (web, run) -> WorkBook.instructions(clock, web, run, WorkBook.CLAUDE, false, false, WorkBook.names(github == null ? null : github.declarations())) + extra + WorkSteering.GUIDANCE, ClaudeProtocol.conversation(turns), plain, search, search, code, container, github, effort, WORK_TOKENS, true, onText, onStage, onActivity, cancel, inspectMerge, turns, steering, allowFallback);
        String[] split = WorkBook.split(turn.text, turn.cuts);
        String answer = turn.inspectMerge || !turn.receipt.isBlank() ? GitHubFunctions.mergeFinal(split[0], turn.receipt) : split[0];
        if (turn.inspectMerge || !turn.receipt.isBlank()) onText.accept(answer);
        // served: the model that wrote the answer when a server-side fallback took over, for the answer and the usage ledger.
        return new WorkClient.Result(answer, turn.sources, turn.files, turn.container, turn.note, new JSONArray(), split[1], turn.served).counted(turn.usage(), turn.limits);
    }

    private static final class Turn {
        String text = "", container = "", note = "", receipt = ""; JSONArray sources = new JSONArray(), files = new JSONArray(); boolean searchDropped, fetchDropped;
        List<Integer> cuts = List.of(); boolean inspectMerge;
        /** Every finished response's usage summed (the rounds of a continued turn too), and the latest limit headers. */
        Usage used; Limits limits = Limits.NONE;
        /** The fallback model that wrote the last finished response (ClaudeProtocol.Stream.fallbackModel), "" when the chosen one did. */
        String served = "";
        Usage usage() { return used == null ? Usage.UNKNOWN : used; }
    }
    /**
     * A refused optional part is dropped and the request sent again, with a note for the user: the fallback, an expired
     * container, attached files (plain is the conversation without them, or null), GitHub, then newer tool versions, then
     * code execution, then web search. Busy or failing servers get two more tries while nothing has been shown yet.
     */
    private static Turn run(ClaudeAccount account, String model, BiFunction<Boolean, Boolean, String> system, JSONArray conversation, JSONArray plain, boolean search, boolean fetch, boolean code, String container,
            WorkTools github, String effort, int maxTokens, boolean cache, Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel, boolean inspectMerge, JSONArray turns, WorkSteering steering) throws Exception {
        return run(account,model,system,conversation,plain,search,fetch,code,container,github,effort,maxTokens,cache,onText,onStage,onActivity,cancel,inspectMerge,turns,steering,true);
    }
    private static Turn run(ClaudeAccount account, String model, BiFunction<Boolean, Boolean, String> system, JSONArray conversation, JSONArray plain, boolean search, boolean fetch, boolean code, String container,
            WorkTools github, String effort, int maxTokens, boolean cache, Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel, boolean inspectMerge, JSONArray turns, WorkSteering steering, boolean allowFallback) throws Exception {
        return run(account,model,system,conversation,plain,search,fetch,code,container,github,effort,maxTokens,cache,onText,onStage,onActivity,cancel,inspectMerge,turns,steering,allowFallback,null,false,false);
    }
    /**
     * As above; onThinking, when given, asks adaptive thinking for a readable summary of itself and hears it as it streams in;
     * cacheSystem marks the system text for the prompt cache. main is the main chat's request: its web searches are capped
     * (MAIN_SEARCHES), and a refused web tool costs one request more at most after the version step, so a turn sends three
     * at most.
     */
    private static Turn run(ClaudeAccount account, String model, BiFunction<Boolean, Boolean, String> system, JSONArray conversation, JSONArray plain, boolean search, boolean fetch, boolean code, String container,
            WorkTools github, String effort, int maxTokens, boolean cache, Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel, boolean inspectMerge, JSONArray turns, WorkSteering steering, boolean allowFallback, Consumer<String> onThinking, boolean cacheSystem, boolean main) throws Exception {
        boolean summary = onThinking != null;
        JSONArray messages = conversation, withoutFiles = plain; WorkTools repos = github;
        // Blocks before from are already in messages (an earlier round of the app's GitHub tools); tool rounds so far.
        int from = 0, progressOnly = 0, answerFrom = 0;
        if (model == null || model.isBlank()) throw new IllegalStateException("설정 → Claude에서 모델을 골라 주세요.");
        if (messages.length() == 0) throw new IllegalArgumentException("보낼 내용이 비어 있어요.");
        JSONObject info = account.info(model);
        boolean fallback = allowFallback && info.optBoolean("fallback"), useSearch = search, useFetch = fetch, useCode = code;
        WorkBudget budget = new WorkBudget();
        String box = container; int level = 0, retries = 0, rounds = 0; boolean narrowed = false;
        // Long runs: the API clears old tool results itself (ClaudeProtocol.contextEdits); dropped if this key or model refuses it.
        JSONObject contextEdits = ClaudeProtocol.contextEdits(info);
        List<String> notes = new ArrayList<>(); Turn turn = new Turn();
        ClaudeProtocol.Stream stream = new ClaudeProtocol.Stream(); JSONArray sending = messages;
        while (true) {
            onActivity.run();
            if (steering.pending()) {
                JSONArray added = steering.take();
                if (added.length() > 0) {
                    WorkSteering.append(turns, added); WorkSteering.append(sending, ClaudeProtocol.conversation(added));
                    messages = sending; from = stream.size(); withoutFiles = null; answerFrom = stream.text().length();
                    inspectMerge = repos != null && repos.inspectMerge(turns); progressOnly = 0;
                    onStage.accept("추가 지시를 반영해 이어가는 중");
                }
            }
            turn.inspectMerge = inspectMerge;
            // Written for the tools this attempt really has, so the model is never told about one that was dropped.
            int textFrom = stream.text().length();
            String instructions = system.apply(useSearch, useCode) + (repos == null ? "" : repos.instructions())
                + (progressOnly > 0 ? GitHubFunctions.CONTINUE_MERGE : "") + (account.ultracode() ? ULTRACODE : "");
            JSONObject request = ClaudeProtocol.request(model, info, instructions, sending, maxTokens, effort, useSearch, useFetch, useCode, level, box, fallback, cache,
                repos == null ? null : repos.claude(), summary, main ? MAIN_SEARCHES : 0);
            if (cacheSystem) ClaudeProtocol.cacheSystem(request);
            if (contextEdits != null) request.put("context_management", contextEdits);
            budget.beforeRequest(request.toString().length(), onActivity);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            String betas = (fallback ? ClaudeProtocol.FALLBACK_BETA : "") + (contextEdits == null ? "" : (fallback ? "," : "") + ClaudeProtocol.CONTEXT_BETA);
            Map<String, String> headers = account.headers(betas.isEmpty() ? null : betas);
            headers.put("Content-Type", "application/json"); headers.put("Accept", "text/event-stream");
            boolean fresh = rounds == 0 && stream.empty();
            // Hosted tools can execute before their event reaches us: never interrupt those requests.
            dev.jun.gyeot.core.WorkInterrupt interrupt = !useSearch && !useFetch && !useCode ? steering.interrupt() : null;
            dev.jun.gyeot.core.WorkInterrupt.Ticket ticket = null;
            ClaudeProtocol.Stream beforeRequest = interrupt == null ? null : stream.snapshot();
            // Recheck the captured task/account after taking the credential snapshot.
            // A later account change cannot replace these already-built headers.
            onActivity.run();
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", ClaudeProtocol.MESSAGES, headers, body, 300_000, cancel)) {
                Limits seen = Limits.headers(response.headers, System.currentTimeMillis()); if (!seen.empty()) turn.limits = seen;
                if (response.status >= 300) {
                    dev.jun.gyeot.core.ModelLimit.check(response.status, !stream.paused(0).toString().contains("code_execution"), turn.limits);
                    String detail = OpenAiAccount.Http.read(response.body, 64 * 1024);
                    JSONObject json; try { json = new JSONObject(detail); } catch (Exception e) { json = new JSONObject(); }
                    String message = ClaudeProtocol.message(json), part = fresh ? ClaudeProtocol.refusedPart(response.status, message) : "";
                    // Refused before any work for the thinking summary alone: the caller sends it once more without it.
                    if (summary && fresh && ClaudeProtocol.summaryRefused(response.status, message)) throw new dev.jun.gyeot.core.MainRequest.SummaryRefused();
                    if (fresh && ClaudeProtocol.retryable(response.status) && retries < RETRIES) { retries++; pause(retries); continue; }
                    if (part.equals("fallback") && fallback) { fallback = false; continue; }
                    if (contextEdits != null && response.status == 400 && message.toLowerCase(java.util.Locale.ROOT).matches("(?s).*context[_ -]management.*")) { contextEdits = null; continue; }
                    if (part.equals("container") && WorkBook.safeId(box)) { box = ""; continue; }
                    if (part.equals("file") && withoutFiles != null) {
                        messages = withoutFiles; sending = withoutFiles; withoutFiles = null;
                        notes.add("이 모델에서는 첨부 파일이 거부돼 파일 없이 답했어요" + (message.isBlank() ? "." : " (" + message + ")."));
                        continue;
                    }
                    if (part.equals("tool") && (useSearch || useFetch || useCode)) {
                        if (level + 1 < ClaudeProtocol.TOOL_LEVELS) { level++; continue; }
                        String reason = message.isBlank() ? "." : " (" + message + ").";
                        if (main && !useCode) {
                            // The main chat, after the version step: one request more, at the basic versions every model with
                            // server tools takes, then the refusal stands. Refused before any work, so nothing paid repeats.
                            // Page reading named alone goes and search stays; search named takes page reading with it; a
                            // refusal that names neither, or both, takes both. Each one named or taken this way is reported.
                            if (narrowed) throw new IllegalStateException(ClaudeProtocol.explain(response.status, ClaudeProtocol.errorType(json), message));
                            narrowed = true;
                            String lower = message.toLowerCase(java.util.Locale.ROOT);
                            boolean namesSearch = useSearch && lower.matches("(?s).*web[_ ]search.*"), namesFetch = useFetch && lower.matches("(?s).*web[_ ]fetch.*");
                            if (namesFetch && !namesSearch) { useFetch = false; turn.fetchDropped = true; continue; }
                            if (useFetch && (namesFetch || !namesSearch)) turn.fetchDropped = true;
                            if (useSearch) { turn.searchDropped = true; notes.add("이 모델에서는 웹 검색이 거부돼 검색 없이 답했어요" + reason); }
                            useSearch = false; useFetch = false; continue;
                        }
                        if (useCode) { useCode = false; level = 0; notes.add("이 모델에서는 코드 실행이 거부돼 코드 없이 답했어요" + reason); continue; }
                        if (useFetch && !message.toLowerCase(java.util.Locale.ROOT).contains("web_search")) { useFetch = false; level = 0; continue; }
                        if (useSearch) { useSearch = false; useFetch = false; level = 0; turn.searchDropped = true; notes.add("이 모델에서는 웹 검색이 거부돼 검색 없이 답했어요" + reason); continue; }
                    }
                    throw new IllegalStateException(ClaudeProtocol.explain(response.status, ClaudeProtocol.errorType(json), message));
                }
                onActivity.run();
                if (interrupt != null) ticket = interrupt.open(cancel == null ? null : cancel.get());
                String stage = stream.stage();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        onActivity.run();
                        if (ticket != null && ticket.requested()) break;
                        String delta = stream.line(line), thought = stream.takeThinking();
                        if (summary && !thought.isEmpty()) onThinking.accept(thought);
                        if (delta != null && !inspectMerge) onText.accept(delta);
                        if (!stream.stage().equals(stage)) { stage = stream.stage(); onStage.accept(stage); }
                        if (stream.failure() != null || stream.completed()) break;
                    }
                }
            } catch (java.io.IOException error) {
                if (ticket == null || !ticket.requested()) throw error;
            } finally {
                if (interrupt != null) interrupt.end(ticket);
                if (cancel != null) cancel.set(null);
            }
            if (ticket != null && ticket.requested()) {
                // Stop, account changes and persistence failures outrank steering.
                onActivity.run();
                stream = beforeRequest;
                steering.interrupted(stream.text());
                onStage.accept("현재 응답을 멈추고 추가 지시를 반영하는 중");
                continue;
            }
            if (stream.failure() != null) {
                boolean busy = "overloaded_error".equals(stream.failureType()) || "api_error".equals(stream.failureType());
                if (rounds == 0 && stream.empty() && busy && retries < RETRIES) { retries++; pause(retries); stream = new ClaudeProtocol.Stream(); continue; }
                throw new IllegalStateException(stream.failure());
            }
            if (!stream.completed()) throw new IllegalStateException("Claude 응답이 끝나기 전에 연결이 끊겼어요. 다시 보내 주세요.");
            // Every finished response counts, each round of a continued turn too (an interrupted or failed one reports nothing).
            turn.used = Usage.sum(turn.used, stream.usage()); turn.served = stream.fallbackModel();
            // A long turn of searches or code runs pauses; sent back as it is, it carries on in the same container.
            // The app's GitHub tools: the turn so far goes back with each call's result, and Claude carries on.
            if ("tool_use".equals(stream.stopReason()) && repos != null) {
                JSONArray calls = stream.toolCalls(from), results = new JSONArray();
                // Files the code saved so far, so edit_video can use a chart Claude just drew in the sandbox.
                repos.providerFiles(stream.files());
                budget.beforeBatch(calls.length(), (long) messages.toString().length() + stream.paused(from).toString().length(), onActivity, onStage);
                for (int i = 0; i < calls.length(); i++) {
                    budget.beforeTool(onActivity);
                    JSONObject call = calls.getJSONObject(i); String name = call.optString("name");
                    onStage.accept(WorkTools.stage(name));
                    onActivity.run(); boolean superseded = steering.pending();
                    JSONObject result = superseded ? WorkSteering.skipped() : repos != null && repos.supports(name) ? repos.run(name, call.optJSONObject("input")) : GitHubFunctions.failure("unknown tool " + name);
                    if (!superseded && GitHubFunctions.MERGE.equals(name)) turn.receipt = GitHubFunctions.mergeReceipt(result);
                    budget.result(name, String.valueOf(call.optJSONObject("input")), result.toString(), result.has("ok") && !result.optBoolean("ok"));
                    onActivity.run(); onStage.accept("도구 응답 받음 · 결과 확인 중");
                    results.put(new JSONObject().put("type", "tool_result").put("tool_use_id", call.optString("id")).put("content", GitHubFunctions.clip(result.toString())).put("is_error", !result.optBoolean("ok")));
                    if (!turn.receipt.isBlank()) break;
                }
                if (!turn.receipt.isBlank()) {
                    turn.text = turn.receipt; turn.sources = stream.sources(); turn.files = stream.files(); turn.container = stream.container(); turn.note = String.join(" ", notes);
                    return turn;
                }
                if (results.length() > 0) {
                    JSONArray next = ClaudeProtocol.resume(messages, stream.paused(from));
                    next.put(new JSONObject().put("role", "user").put("content", results));
                    messages = next; sending = next; from = stream.size();
                    if (WorkBook.safeId(stream.container())) box = stream.container();
                    stream.next(); onStage.accept("생각하는 중"); continue;
                }
            }
            if ("pause_turn".equals(stream.stopReason()) && rounds < CONTINUATIONS) {
                rounds++; sending = ClaudeProtocol.resume(messages, stream.paused(from));
                if (WorkBook.safeId(stream.container())) box = stream.container();
                stream.next(); continue;
            }
            if ("refusal".equals(stream.stopReason())) throw new IllegalStateException(ClaudeProtocol.refusal(stream.category()));
            if ("end_turn".equals(stream.stopReason()) && steering.pending()) {
                sending = ClaudeProtocol.resume(messages, stream.paused(from)); messages = sending; from = stream.size();
                if (WorkBook.safeId(stream.container())) box = stream.container();
                stream.next(); if (!inspectMerge) onText.accept("\n\n"); continue;
            }
            String roundText = stream.text().substring(textFrom);
            if (inspectMerge && "end_turn".equals(stream.stopReason()) && GitHubFunctions.promisesMerge(roundText)) {
                if (++progressOnly > 2) throw new IllegalStateException("Claude가 계획만 답하고 병합 도구를 실행하지 않았어요. 병합은 완료되지 않았습니다.");
                JSONArray next = ClaudeProtocol.resume(messages, stream.paused(from));
                next.put(new JSONObject().put("role", "user").put("content", GitHubFunctions.CONTINUE_MERGE));
                messages = next; sending = next; withoutFiles = null; from = stream.size();
                if (WorkBook.safeId(stream.container())) box = stream.container();
                stream.next(); onStage.accept("계획 답변에 이어 실제 GitHub 작업을 요청하는 중");
                continue;
            }
            if ("max_tokens".equals(stream.stopReason())) notes.add("답이 길어 중간에 끊겼어요. ‘이어서 써 줘’라고 보내면 계속해요.");
            if ("pause_turn".equals(stream.stopReason())) notes.add("작업이 길어 중간에 멈췄어요. ‘계속해 줘’라고 보내면 이어서 해요.");
            if ((inspectMerge ? roundText : stream.text()).isBlank() && stream.files().length() == 0) throw new IllegalStateException("Claude가 답을 보내지 않았어요.");
            turn.text = inspectMerge ? roundText : stream.text().substring(answerFrom); turn.cuts = inspectMerge || answerFrom > 0 ? List.of() : stream.cuts(); turn.sources = stream.sources(); turn.files = stream.files(); turn.container = stream.container(); turn.note = String.join(" ", notes);
            return turn;
        }
    }
    private static void pause(int attempt) throws InterruptedException { Thread.sleep(2_000L * attempt); }

    /** A file a code run made: its name from the Files API metadata, then its bytes (up to WorkClient.FILE_LIMIT). */
    static final class File { final String name; final byte[] bytes; File(String name, byte[] bytes) { this.name = name; this.bytes = bytes; } }
    static File download(ClaudeAccount account, String id, AtomicReference<Runnable> cancel) throws Exception {
        String meta = ClaudeProtocol.file(id), content = ClaudeProtocol.fileContent(id);
        if (meta == null || content == null) throw new IllegalArgumentException("파일 주소가 올바르지 않아요.");
        String name;
        Map<String, String> headers = account.headers(null); headers.put("Accept", "application/json");
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", meta, headers, null, 60_000, cancel)) {
            String body = OpenAiAccount.Http.read(response.body, 256 * 1024);
            JSONObject json; try { json = new JSONObject(body); } catch (Exception e) { json = new JSONObject(); }
            if (response.status >= 300) throw new IllegalStateException(ClaudeProtocol.explain(response.status, ClaudeProtocol.errorType(json), ClaudeProtocol.message(json)));
            if (json.optLong("size_bytes") > WorkClient.FILE_LIMIT) throw new IllegalStateException("파일이 너무 커요(25MB 초과).");
            name = json.optString("filename", "결과");
        } finally { if (cancel != null) cancel.set(null); }
        headers = account.headers(null); headers.put("Accept", "*/*");
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", content, headers, null, 120_000, cancel)) {
            if (response.status >= 300) {
                String detail = OpenAiAccount.Http.read(response.body, 16 * 1024);
                JSONObject json; try { json = new JSONObject(detail); } catch (Exception e) { json = new JSONObject(); }
                throw new IllegalStateException(ClaudeProtocol.explain(response.status, ClaudeProtocol.errorType(json), ClaudeProtocol.message(json)));
            }
            try (InputStream input = response.body) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[16 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > WorkClient.FILE_LIMIT) throw new IllegalStateException("파일이 너무 커요(25MB 초과).");
                    bytes.write(buffer, 0, count);
                }
                return new File(name, bytes.toByteArray());
            }
        } finally { if (cancel != null) cancel.set(null); }
    }
    /** The file name a code run gave a file (Files API metadata only, no download), for matching a name the model used. */
    static String name(ClaudeAccount account, String id, AtomicReference<Runnable> cancel) throws Exception {
        String meta = ClaudeProtocol.file(id);
        if (meta == null) throw new IllegalArgumentException("파일 주소가 올바르지 않아요.");
        Map<String, String> headers = account.headers(null); headers.put("Accept", "application/json");
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", meta, headers, null, 60_000, cancel)) {
            String body = OpenAiAccount.Http.read(response.body, 256 * 1024);
            JSONObject json; try { json = new JSONObject(body); } catch (Exception e) { json = new JSONObject(); }
            if (response.status >= 300) throw new IllegalStateException(ClaudeProtocol.explain(response.status, ClaudeProtocol.errorType(json), ClaudeProtocol.message(json)));
            return json.optString("filename");
        } finally { if (cancel != null) cancel.set(null); }
    }
    /** Removes a downloaded file from the account's Anthropic file storage; it is kept on the phone. Failures are ignored. */
    static void delete(ClaudeAccount account, String id) {
        String url = ClaudeProtocol.file(id);
        if (url == null) return;
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("DELETE", url, account.headers(null), null, 30_000, null)) {
            OpenAiAccount.Http.read(response.body, 16 * 1024);
        } catch (Exception ignored) {}
    }
}


