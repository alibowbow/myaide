package dev.jun.gyeot;

import dev.jun.gyeot.core.WorkBudget;

import dev.jun.gyeot.core.Drawing;
import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.OpenAiProtocol;
import dev.jun.gyeot.core.Reasoning;
import dev.jun.gyeot.core.GptSpeed;
import dev.jun.gyeot.core.Usage;
import dev.jun.gyeot.core.WorkBook;
import dev.jun.gyeot.core.WorkSteering;
import dev.jun.gyeot.core.WorkPlans;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** 작업창 requests to ChatGPT: a thread's conversation with web search and code execution, streamed, and the files it made. */
final class WorkClient {
    /** Longest file kept from a run. */
    static final int FILE_LIMIT = 25 * 1024 * 1024;
    /** Pictures the app's draw_picture makes in one turn at most, and rounds of function calls in one turn at most. */
    static final int DRAW_ROUNDS = 3;
    /**
     * Runs the app's draw_picture: the checked arguments (core/Drawing) and the pictures an edit changes; returns {data,
     * format, revised, model}. A CancellationException means the user stopped the turn.
     */
    interface Painter {
        JSONObject draw(JSONObject args, JSONArray sources) throws Exception;
        /** A connected Codex image session takes precedence over the text account's hosted image tool. */
        default boolean codex() { return false; }
    }
    private WorkClient() {}

    static final class Result {
        final String text, container, note, steps, served, suggestions; final JSONArray sources, files, images;
        /** What the turn used, summed over its rounds (Usage.UNKNOWN when the provider said nothing), and the limits its responses reported. */
        final Usage usage; final Limits limits;
        /** Milliseconds from the request to the first piece of the answer (TeamModels measures it), 0 when unknown. */
        final long first;
        Result(String text, JSONArray sources, JSONArray files, String container, String note) { this(text, sources, files, container, note, new JSONArray(), ""); }
        /** images: pictures ChatGPT drew, {data (base64), format}; steps: the short notes written between tool calls (WorkBook.split). */
        Result(String text, JSONArray sources, JSONArray files, String container, String note, JSONArray images, String steps) { this(text, sources, files, container, note, images, steps, ""); }
        /** served: the model that actually answered when the chosen one is an alias (Gemini's "Flash 최신"), else "". */
        Result(String text, JSONArray sources, JSONArray files, String container, String note, JSONArray images, String steps, String served) {
            this(text, sources, files, container, note, images, steps, served, "");
        }
        /** suggestions: Google's search suggestions for a Gemini answer grounded with Google Search (HTML and CSS), shown unchanged beside it; else "". */
        Result(String text, JSONArray sources, JSONArray files, String container, String note, JSONArray images, String steps, String served, String suggestions) {
            this(text, sources, files, container, note, images, steps, served, suggestions, Usage.UNKNOWN, Limits.NONE, 0);
        }
        private Result(String text, JSONArray sources, JSONArray files, String container, String note, JSONArray images, String steps, String served, String suggestions, Usage usage, Limits limits, long first) {
            this.text = text; this.sources = sources; this.files = files; this.container = container; this.note = note; this.images = images; this.steps = steps;
            this.served = served == null ? "" : served; this.suggestions = suggestions == null ? "" : suggestions;
            this.usage = usage == null ? Usage.UNKNOWN : usage; this.limits = limits == null ? Limits.NONE : limits; this.first = first;
        }
        /** This result with what its turn used and the limits it saw. */
        Result counted(Usage usage, Limits limits) { return new Result(text, sources, files, container, note, images, steps, served, suggestions, usage, limits, first); }
        /** This result with the time its first piece of answer took. */
        Result first(long first) { return new Result(text, sources, files, container, note, images, steps, served, suggestions, usage, limits, first); }
    }

    /**
     * One turn of a work thread: turns as WorkBook.input gives them (attached files with their data), context as
     * WorkBook.context adds to the instructions. A refused optional feature is dropped and the turn sent again, with a note
     * for the user: an expired container first gets a fresh one, then image generation, attached files, code execution,
     * then web search; a refusal that names nothing drops them one by one. A rejected reasoning level is reported.
     * github, when given, offers the app's github_ functions (core/GitHubFunctions), run on the phone; painter, when given,
     * draws for a connection without the hosted image tool through the app's draw_picture. A turn with function calls goes
     * in rounds: each round's calls are run and answered before it goes on (no fixed tool-round cap; at most DRAW_ROUNDS pictures).
     */
    static Result run(OpenAiAccount account, String model, String clock, JSONArray turns, String context, boolean webSearch, boolean code, boolean draw, WorkTools github, String container, String effort,
            Painter painter, Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel) throws Exception {
        return run(account, model, clock, turns, context, webSearch, code, draw, github, container, effort, painter, onText, onStage, onActivity, cancel, WorkSteering.NONE);
    }
    static Result run(OpenAiAccount account, String model, String clock, JSONArray turns, String context, boolean webSearch, boolean code, boolean draw, WorkTools github, String container, String effort,
            Painter painter, Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel, WorkSteering steering) throws Exception {
        return run(account, model, clock, turns, context, webSearch, code, draw, github, container, effort, painter, onText, onStage, onActivity, cancel, steering, "standard");
    }
    static Result run(OpenAiAccount account, String model, String clock, JSONArray turns, String context, boolean webSearch, boolean code, boolean draw, WorkTools github, String container, String effort,
            Painter painter, Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, AtomicReference<Runnable> cancel, WorkSteering steering, String speed) throws Exception {
        if (model.isBlank()) throw new IllegalStateException("설정 → ChatGPT에서 모델을 골라 주세요.");
        // Tools this connection already said it does not support are not offered (a ChatGPT plan takes only web search and functions).
        boolean search = webSearch, refreshed = false, files = WorkBook.hasFiles(turns), images = draw && !account.usesPlan()
            && !(painter != null && painter.codex()) && !account.refused("image_generation");
        String codeMode = code && !account.refused("code_interpreter") ? (WorkBook.safeId(container) ? container : "auto") : null, level = account.reasoningProfile(model).resolve(effort);
        List<String> notes = new ArrayList<>();
        JSONArray input = OpenAiProtocol.conversation(turns);
        // What each round wrote, joined, and what it found and drew.
        StringBuilder written = new StringBuilder(); List<Integer> cuts = new ArrayList<>(); JSONArray sources = new JSONArray(), pictures = new JSONArray();
        java.util.Set<String> answered = new java.util.HashSet<>(); long rounds = 0; int drawn = 0;
        boolean retriedEmpty = false, mutationAttempted = false, repairCommand = false, externalWork = false;
        int progressOnly = 0, commandRepairs = 0;
        boolean inspectMerge = github != null && github.inspectMerge(turns);
        String mergeReceipt = "";
        long generation = account.workFormatGeneration();
        // A successful JSON command in an earlier task does not mean ordinary answers need JSON.
        // Probe native functions afresh; compatibility applies only to this run.
        WorkBudget budget = new WorkBudget();
        boolean plans = false;
        String formatDiagnostic = "";
        // Every finished round's usage, summed, and the latest limit headers (the turn's result carries them).
        Usage used = null; Limits limits = Limits.NONE;
        while (true) {
            onActivity.run();
            if (generation != account.workFormatGeneration()) throw new java.util.concurrent.CancellationException("ChatGPT 연결이 바뀌어 이전 작업을 멈췄어요.");
            if (steering.pending()) {
                JSONArray added = steering.take();
                if (generation != account.workFormatGeneration()) throw new java.util.concurrent.CancellationException("ChatGPT 연결이 바뀌어 이전 작업을 멈췄어요.");
                if (added.length() > 0) {
                    WorkSteering.append(turns, added); WorkSteering.append(input, OpenAiProtocol.conversation(added));
                    inspectMerge = github != null && github.inspectMerge(turns); progressOnly = 0; written.setLength(0); cuts.clear();
                    onStage.accept("추가 지시를 반영해 이어가는 중");
                }
            }
            boolean drawing = painter != null && draw && !inspectMerge && (!images || plans) && drawn < DRAW_ROUNDS;
            JSONArray functions = new JSONArray();
            if (drawing) functions.put(Drawing.tool());
            if (github != null) {
                JSONArray offered = github.openAi();
                for (int i = 0; i < offered.length(); i++) {
                    JSONObject function = offered.getJSONObject(i); String name = function.optString("name");
                    if (!inspectMerge || !WorkTools.startsWork(name) || GitHubFunctions.MERGE.equals(name)) functions.put(function);
                }
            }
            String instructions = WorkBook.instructions(clock, search && !plans && !inspectMerge, codeMode != null && !plans && !inspectMerge, WorkBook.CHATGPT, images && !plans && !inspectMerge, drawing, WorkBook.names(functions))
                + (github != null ? github.instructions() : "") + (context == null ? "" : context) + WorkSteering.GUIDANCE;
            if (inspectMerge && rounds == 0) instructions += " Start this explicit merge request with github_get to inspect the current PR (or list open PRs in the configured repository if its number is unclear). Do not guess a PR when several candidates exist. This first read is not the merge itself.";
            if (inspectMerge && rounds > 0 && github.mergeNeedsAction()) instructions += " The requested merge is still unfinished. Continue inspecting the PR and checks, then execute the authorized merge using the observed head and base. A status summary or promise alone does not complete this request.";
            if (retriedEmpty) instructions += " Your preceding completed response had no visible answer or executable function call. Continue the user's original request using the available functions or give a useful answer. Keep all returned tool results; do not restart completed actions or claim success without a result.";
            if (progressOnly > 0) instructions += GitHubFunctions.CONTINUE_MERGE;
            if (repairCommand) instructions += " Your last completed reply did not match the work command JSON format. No command from it ran. Return exactly one valid JSON command, preserving the original request and actual tool results. Use finish for a complete ordinary answer when permitted, or a declared tool for remaining actions.";
            String required = inspectMerge && rounds == 0 ? GitHubFunctions.GET : inspectMerge && github.mergeNeedsAction() ? WorkPlans.ANY_TOOL
                : github != null ? github.requiredTool() : null;
            JSONObject request = plans ? WorkPlans.request(model, instructions, input, functions, level, required)
                : OpenAiProtocol.workRequest(model, instructions, input, search && !inspectMerge, inspectMerge ? null : codeMode, images && !inspectMerge, functions.length() > 0 ? functions : null, level);
            if (!plans && required != null) request.put("tool_choice", WorkPlans.ANY_TOOL.equals(required) ? "required" : new JSONObject().put("type", "function").put("name", required));
            GptSpeed.apply(request, account.describe().optString("mode"), model, account.models(), speed);
            budget.beforeRequest(request.toString().length(), onActivity);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + account.bearer());
            headers.put("Content-Type", "application/json"); headers.put("Accept", "text/event-stream");
            if (generation != account.workFormatGeneration()) throw new java.util.concurrent.CancellationException("GPT 연결이 바뀌어 이전 요청을 멈췄어요.");
            OpenAiProtocol.Stream stream;
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", OpenAiProtocol.RESPONSES, headers, body, 300_000, cancel)) {
                Limits seen = Limits.headers(response.headers, System.currentTimeMillis()); if (!seen.empty()) limits = seen;
                if (response.status == 401 && !refreshed && account.usesPlan()) { refreshed = true; account.expire(); continue; }
                if (response.status >= 300) {
                    dev.jun.gyeot.core.ModelLimit.check(response.status, !externalWork, limits);
                    String detail = OpenAiAccount.Http.read(response.body, 64 * 1024);
                    JSONObject json; try { json = new JSONObject(detail); } catch (Exception e) { json = new JSONObject(); }
                    String message = OpenAiProtocol.failure(json, detail), lower = message.toLowerCase(Locale.ROOT);
                    if (GptSpeed.rejected(response.status, json)) throw new IllegalStateException("선택한 GPT 속도가 이 연결에서 거부됐어요. 속도를 Standard로 바꾸거나 연결의 지원 여부를 확인해 주세요.");
                    // The error may point at the tool it refuses ("param": "tools[2]"); that names it even when the wording does not.
                    String tool = OpenAiProtocol.refusedTool(json.optJSONObject("error"), request);
                    if (!tool.isEmpty()) lower += " " + tool;
                    if (!level.isEmpty() && Reasoning.rejected(response.status, json)) throw new IllegalStateException(Reasoning.rejection(model, level));
                    // A tool the connection does not support at all is dropped quietly and remembered; any other refusal is noted.
                    boolean unsupported = OpenAiProtocol.unsupported(message);
                    if (!plans && !mutationAttempted && functions.length() > 0 && (response.status == 400 || response.status == 422)
                        && unsupported && (lower.contains("function") || lower.contains("tool_choice"))) {
                        plans = true; formatDiagnostic = "일반 도구 형식 거절"; onStage.accept("연결에 맞는 작업 방식으로 전환하는 중"); continue;
                    }
                    if (response.status >= 400 && response.status < 500 && response.status != 401 && response.status != 429) {
                        if (codeMode != null && !codeMode.equals("auto") && lower.contains("container")) { codeMode = "auto"; continue; }
                        if (images && lower.contains("image_generation")) {
                            images = false;
                            if (unsupported) account.refuse("image_generation"); else notes.add("이 연결에서는 그림 그리기가 거부돼 그림 없이 답했어요" + (message.isBlank() ? "." : " (" + message + ")."));
                            continue;
                        }
                        if (rounds == 0 && !mutationAttempted && files && lower.matches("(?s).*(input_image|input_file|image_url|file_data|image|pdf).*")) {
                            files = false; input = OpenAiProtocol.conversation(WorkBook.withoutFiles(turns));
                            notes.add("이 연결에서는 첨부 파일이 거부돼 파일 없이 답했어요" + (message.isBlank() ? "." : " (" + message + ")."));
                            continue;
                        }
                        if (codeMode != null && (lower.contains("code_interpreter") || lower.contains("container") || lower.contains("tool"))) {
                            codeMode = null;
                            if (unsupported && lower.contains("code_interpreter")) account.refuse("code_interpreter"); else notes.add("이 연결에서는 코드 실행이 거부돼 코드 없이 답했어요" + (message.isBlank() ? "." : " (" + message + ")."));
                            continue;
                        }
                        if (search && (lower.contains("web_search") || lower.contains("tool"))) {
                            search = false; notes.add("이 연결에서는 웹 검색이 거부돼 검색 없이 답했어요" + (message.isBlank() ? "." : " (" + message + ")."));
                            continue;
                        }
                        // A refusal that names nothing: the optional tools go one per try, newest first, so the turn still gets an answer.
                        if (response.status == 400) {
                            String why = message.isBlank() ? "." : " (" + message + ").";
                            if (images) { images = false; notes.add("ChatGPT가 요청을 거절해 그림 도구를 빼고 다시 보냈어요" + why); continue; }
                            if (codeMode != null) { codeMode = null; notes.add("ChatGPT가 요청을 거절해 코드 실행을 빼고 다시 보냈어요" + why); continue; }
                            if (search) { search = false; notes.add("ChatGPT가 요청을 거절해 웹 검색을 빼고 다시 보냈어요" + why); continue; }
                        }
                    }
                    throw new IllegalStateException(OpenAiProtocol.explain(response.status, message));
                }
                // Commands and the first-read capability probe are not displayed as ordinary assistant prose.
                stream = read(response, plans || inspectMerge || !mergeReceipt.isBlank() ? text -> {} : onText, onStage, onActivity);
            } catch (java.util.concurrent.CancellationException stopped) { throw stopped; }
            catch (Exception failed) {
                if (!mergeReceipt.isBlank()) {
                    onActivity.run();
                    return mergeResult(mergeReceipt, written, sources, pictures, notes).counted(used, limits);
                }
                throw failed;
            } finally { cancel.set(null); }
            used = Usage.sum(used, stream.usage());
            String speedNotice = GptSpeed.receivedTier(speed, new JSONObject().put("service_tier", stream.serviceTier()));
            if (!speedNotice.isBlank() && !notes.contains(speedNotice)) notes.add(speedNotice);
            onActivity.run();
            if (generation != account.workFormatGeneration()) throw new java.util.concurrent.CancellationException("ChatGPT 연결이 바뀌어 이전 작업을 멈췄어요.");
            JSONArray output = stream.output(); externalWork |= hasHostedAction(output); String roundText = stream.text(); List<Integer> roundCuts = stream.cuts();
            boolean hasCalls = false;
            for (int i = 0; i < output.length(); i++) { JSONObject item = output.optJSONObject(i); if (item != null && "function_call".equals(item.optString("type"))) hasCalls = true; }
            if (!plans && !mutationAttempted && functions.length() > 0 && !stream.refused() && !hasCalls && stream.images().length() == 0 && stream.files().length() == 0
                && !hasHostedAction(output) && (roundText.isBlank() || required != null)) {
                plans = true; formatDiagnostic = stream.diagnostic();
                onStage.accept("연결에 맞는 작업 방식으로 전환하는 중"); continue;
            }
            if (plans && !stream.refused()) {
                if (roundText.isBlank() && !mergeReceipt.isBlank()) return mergeResult(mergeReceipt, written, sources, pictures, notes).counted(used, limits);
                try {
                    WorkPlans.Reply reply = WorkPlans.reply(roundText, functions, required);
                    output = reply.output(); roundText = reply.text(); roundCuts = java.util.List.of(); repairCommand = false;
                } catch (IllegalStateException invalid) {
                    if (!mergeReceipt.isBlank()) return mergeResult(mergeReceipt, written, sources, pictures, notes).counted(used, limits);
                    if (!mutationAttempted && ++commandRepairs <= 2) {
                        repairCommand = true;
                        if (!roundText.isBlank()) input.put(new JSONObject().put("role", "assistant").put("content", GitHubFunctions.clip(roundText)));
                        onStage.accept(inspectMerge ? "병합 작업을 이어서 요청하는 중" : "응답을 다시 확인하는 중"); continue;
                    }
                    throw new IllegalStateException(invalid.getMessage() + (inspectMerge ? " 병합은 완료되지 않았습니다." : "") + "\n진단: " + (formatDiagnostic.isEmpty() ? "호환 방식" : formatDiagnostic) + " → " + stream.diagnostic());
                }
            }
            hasCalls = false;
            for (int i = 0; i < output.length(); i++) if (output.optJSONObject(i) != null && "function_call".equals(output.optJSONObject(i).optString("type"))) hasCalls = true;
            if (inspectMerge && mergeReceipt.isBlank() && !stream.refused() && !hasCalls && GitHubFunctions.promisesMerge(roundText)) {
                if (++progressOnly > 2) throw new IllegalStateException("모델이 계획만 답하고 병합 도구를 실행하지 않았어요. 병합은 완료되지 않았습니다. PR 상태를 확인한 뒤 다시 요청해 주세요.");
                input.put(new JSONObject().put("role", "assistant").put("content", roundText));
                onStage.accept("계획 답변에 이어 실제 GitHub 작업을 요청하는 중");
                continue;
            }
            if (!hasCalls && roundText.isBlank() && !mergeReceipt.isBlank()) return mergeResult(mergeReceipt, written, sources, pictures, notes).counted(used, limits);
            if (!hasCalls && (inspectMerge || !mergeReceipt.isBlank())) roundText = GitHubFunctions.mergeFinal(roundText, mergeReceipt);
            if ((plans || inspectMerge || !mergeReceipt.isBlank()) && !roundText.isEmpty()) onText.accept(roundText);
            int base = written.length();
            if (base > 0 && !roundText.isEmpty()) { written.append("\n\n"); base += 2; cuts.add(base); }
            for (int cut : roundCuts) cuts.add(base + cut);
            written.append(roundText);
            for (int i = 0; i < stream.sources().length(); i++) sources.put(stream.sources().opt(i));
            for (int i = 0; i < stream.images().length(); i++) pictures.put(stream.images().opt(i));
            mutationAttempted |= hasHostedAction(output);
            JSONArray calls = calls(output, functions);
            if (calls.length() == 0 && !stream.refused() && steering.pending() && stream.files().length() == 0 && !hasHostedAction(output)) {
                WorkSteering.append(input, output);
                if (written.length() > 0) onText.accept("\n\n");
                continue;
            }
            if (calls.length() == 0) {
                if (inspectMerge && github.mergeNeedsAction() && !stream.refused())
                    throw new IllegalStateException("GitHub 병합 결과를 받기 전에 작업이 멈췄어요. 병합은 완료되지 않았습니다. PR 상태를 확인한 뒤 다시 요청해 주세요.");
                if (calls.length() == 0 && roundText.isBlank() && pictures.length() == 0 && stream.files().length() == 0) {
                    if (!mergeReceipt.isBlank()) return mergeResult(mergeReceipt, written, sources, pictures, notes).counted(used, limits);
                    // Only a successfully completed empty response is eligible. Never replay a mutation or interrupted stream.
                    if (!plans && !stream.refused() && !retriedEmpty && !mutationAttempted) {
                        retriedEmpty = true;
                        for (int i = 0; i < stream.output().length(); i++) input.put(stream.output().get(i));
                        onStage.accept("빈 응답을 받아 이어서 확인하는 중");
                        continue;
                    }
                    throw new IllegalStateException(mutationAttempted
                        ? "응답이 비어 있어 작업 결과를 설명받지 못했어요. 이미 요청한 변경의 상태를 확인한 뒤 이어서 진행해 주세요."
                        : WorkBook.EMPTY_REPLY);
                }
                String[] split = WorkBook.split(written.toString(), cuts);
                if (split[0].isBlank() && split[1].isBlank() && pictures.length() == 0 && stream.files().length() == 0)
                    throw new IllegalStateException(WorkBook.EMPTY_REPLY);
                return new Result(inspectMerge || !mergeReceipt.isBlank() ? GitHubFunctions.mergeFinal(split[0], mergeReceipt) : split[0], sources, stream.files(), stream.container(), String.join(" ", notes), pictures, split[1]).counted(used, limits);
            }
            // The response so far goes back as it came (its reasoning encrypted), then each call's result.
            budget.beforeBatch(calls.length(), (long) input.toString().length() + output.toString().length(), onActivity, onStage);
            if (rounds < Long.MAX_VALUE) rounds++;
            for (int i = 0; i < output.length(); i++) if (output.optJSONObject(i) != null) input.put(output.optJSONObject(i));
            for (int i = 0; i < calls.length(); i++) {
                budget.beforeTool(onActivity);
                JSONObject call = calls.getJSONObject(i); String id = call.optString("call_id"), name = call.optString("name");
                if (id.isBlank() || !answered.add(id)) throw new IllegalStateException("ChatGPT의 도구 요청이 중복되거나 올바르지 않아 멈췄어요.");
                JSONObject result;
                if (steering.pending()) result = WorkSteering.skipped();
                else if (github != null && github.supports(name)) {
                    onStage.accept(WorkTools.stage(name));
                    mutationAttempted |= WorkTools.startsWork(name);
                    result = github.run(name, call.optString("arguments"));
                    JSONObject inspected = inspectMerge ? github.mergeInspectionResult() : null;
                    if (GitHubFunctions.MERGE.equals(name) || inspected != null) {
                        mergeReceipt = GitHubFunctions.mergeReceipt(GitHubFunctions.MERGE.equals(name) ? result : inspected);
                        onText.accept(mergeReceipt);
                        return new Result(mergeReceipt, sources, new JSONArray(), "", String.join(" ", notes), pictures, written.toString().strip()).counted(used, limits);
                    }
                } else {
                    onStage.accept("그림 그리는 중"); drawn++; mutationAttempted = true; externalWork = true;
                    try {
                        JSONObject args = Drawing.arguments(call.optString("arguments"));
                        JSONObject latest = turns.length() == 0 ? null : turns.optJSONObject(turns.length() - 1);
                        String shape = Drawing.aspect(latest == null ? "" : latest.optString("content"));
                        if (!shape.isEmpty()) args.put("aspect", shape);
                        JSONObject picture = painter.draw(args, args.optBoolean("edit") ? Drawing.editSources(turns) : Drawing.sources(turns));
                        picture.put("aspect", args.optString("aspect"));
                        picture.put("prompt", args.optString("prompt"));
                        if (pictures.length() < OpenAiProtocol.IMAGES) pictures.put(picture);
                        result = Drawing.result(picture, picture.optString("model"), null);
                    } catch (Exception failed) {
                        // Stopped by the user: the turn ends here instead of telling ChatGPT the drawing failed.
                        if (failed instanceof java.util.concurrent.CancellationException) throw failed;
                        result = Drawing.result(null, "", failed.getMessage());
                        notes.add("그림을 그리지 못했어요: " + (failed.getMessage() == null ? "알 수 없는 오류" : failed.getMessage()));
                    }
                }
                budget.result(name, call.optString("arguments"), result.toString(), result.has("ok") && !result.optBoolean("ok"));
                onActivity.run();
                input.put(new JSONObject().put("type", "function_call_output").put("call_id", id).put("output", GitHubFunctions.clip(result.toString())));
            }
            if (written.length() > 0) onText.accept("\n\n");
            onStage.accept("생각하는 중");
        }
    }
    static boolean hasHostedAction(JSONArray output) {
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item != null && java.util.Set.of("image_generation_call", "code_interpreter_call").contains(item.optString("type"))) return true;
        }
        return false;
    }
    private static Result mergeResult(String receipt, StringBuilder written, JSONArray sources, JSONArray pictures, List<String> notes) {
        notes.add("ChatGPT의 후속 답변을 받지 못해 GitHub가 반환한 병합 결과를 표시했어요.");
        return new Result(receipt, sources, new JSONArray(), "", String.join(" ", notes), pictures, written.toString().strip());
    }
    /** Execute only functions actually offered in this request, including in native mode. */
    private static JSONArray calls(JSONArray output, JSONArray functions) {
        JSONArray calls = new JSONArray();
        java.util.Set<String> offered = new java.util.HashSet<>();
        for (int i = 0; i < functions.length(); i++) if (functions.optJSONObject(i) != null) offered.add(functions.optJSONObject(i).optString("name"));
        for (int i = 0; output != null && i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"function_call".equals(item.optString("type"))) continue;
            String name = item.optString("name");
            if (offered.contains(name)) calls.put(item);
            else throw new IllegalStateException("ChatGPT가 현재 연결에 없는 도구를 요청했어요. 연결 설정을 확인한 뒤 다시 보내 주세요.");
        }
        return calls;
    }
    private static OpenAiProtocol.Stream read(OpenAiAccount.Http.Exchange response, Consumer<String> onText, Consumer<String> onStage, Runnable onActivity) throws Exception {
        return read(response, onText, onStage, onActivity, "ChatGPT");
    }
    static OpenAiProtocol.Stream read(OpenAiAccount.Http.Exchange response, Consumer<String> onText, Consumer<String> onStage, Runnable onActivity, String provider) throws Exception {
        OpenAiProtocol.Stream stream = new OpenAiProtocol.Stream(); String stage = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                onActivity.run();
                String delta = stream.line(line);
                if (delta != null && !delta.isEmpty()) onText.accept(delta);
                if (!stream.stage().equals(stage)) { stage = stream.stage(); onStage.accept(stage); }
                if (stream.failure() != null || stream.completed()) break;
            }
        }
        // The last event may come without the blank line that ends it: it passes the guard like every line before it.
        String tail = stream.finish(); if (tail != null && !tail.isEmpty()) { onActivity.run(); onText.accept(tail); }
        if (stream.failure() != null) throw new IllegalStateException(stream.failure().replace("ChatGPT", provider).replace("GPT 도구", provider + " 도구"));
        if (!stream.completed()) throw new IllegalStateException(provider + " 응답이 끝나기 전에 연결이 끊겼어요. 다시 보내 주세요.");
        return stream;
    }
    /** Downloads one file a code run made, while its container still exists (about 20 minutes after the last use). */
    static byte[] download(OpenAiAccount account, String container, String file, AtomicReference<Runnable> cancel) throws Exception {
        String url = OpenAiProtocol.containerFile(container, file);
        if (url == null) throw new IllegalArgumentException("파일 주소가 올바르지 않아요.");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + account.bearer()); headers.put("Accept", "*/*");
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", url, headers, null, 120_000, cancel)) {
            if (response.status >= 300) {
                String detail = OpenAiAccount.Http.read(response.body, 16 * 1024);
                JSONObject json; try { json = new JSONObject(detail); } catch (Exception e) { json = new JSONObject(); }
                throw new IllegalStateException(OpenAiProtocol.explain(response.status, OpenAiProtocol.message(json, "")));
            }
            try (InputStream input = response.body) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[16 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > FILE_LIMIT) throw new IllegalStateException("파일이 너무 커요(25MB 초과).");
                    bytes.write(buffer, 0, count);
                }
                return bytes.toByteArray();
            }
        } finally { if (cancel != null) cancel.set(null); }
    }
}


