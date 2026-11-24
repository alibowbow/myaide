package dev.jun.gyeot;

import dev.jun.gyeot.core.WorkBudget;

import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.GrokWork;
import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.OpenAiProtocol;
import dev.jun.gyeot.core.Usage;
import dev.jun.gyeot.core.WorkBook;
import dev.jun.gyeot.core.WorkPlans;
import dev.jun.gyeot.core.WorkSteering;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Grok 4.7 work chat over the existing encrypted subscription session. Never starts a video. */
final class GrokWorkClient {
    interface Session {
        String accountId();
        void check(String expectedAccount);
        OpenAiAccount.Http.Exchange responses(JSONObject body, String expectedAccount, AtomicReference<Runnable> cancel, Runnable guard) throws Exception;
    }
    private GrokWorkClient() {}
    /** The note when xAI refused web search before any work and the answer came without it (the main chat reads it too). */
    static final String SEARCH_REFUSED = "Grok 연결이 웹 검색을 거절해 검색 없이 답했어요.";
    static WorkClient.Result work(Session account, String expectedAccount, String model, String clock, JSONArray turns, String context,
            boolean search, boolean code, WorkTools github, String effort, String cacheKey,
            Consumer<String> onText, Consumer<String> onStage, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        return work(account, expectedAccount, model, clock, turns, context, search, code, github, effort, cacheKey, onText, onStage, guard, cancel, WorkSteering.NONE);
    }
    static WorkClient.Result work(Session account, String expectedAccount, String model, String clock, JSONArray turns, String context,
            boolean search, boolean code, WorkTools github, String effort, String cacheKey,
            Consumer<String> onText, Consumer<String> onStage, Runnable guard, AtomicReference<Runnable> cancel, WorkSteering steering) throws Exception {
        boolean inspectMerge = github != null && github.inspectMerge(turns);
        String extra = (context == null ? "" : context) + WorkSteering.GUIDANCE;
        return run(account, expectedAccount, model, (web, run) -> WorkBook.instructions(clock, web, run, WorkBook.GROK, false, false, WorkBook.names(github == null ? null : github.declarations())) + extra,
            OpenAiProtocol.conversation(turns), search, code, github, effort, cacheKey, onText, onStage, guard, cancel, inspectMerge, turns, steering, true);
    }
    static WorkClient.Result complete(Session account, String model, java.util.function.Function<Boolean, String> instructions, String input,
            boolean search, String effort, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        return complete(account, model, instructions, input, search, effort, guard, cancel, text -> {});
    }
    /** As complete, telling onText the answer as it streams in (누르고 말하기 reads it aloud meanwhile). */
    static WorkClient.Result complete(Session account, String model, java.util.function.Function<Boolean, String> instructions, String input,
            boolean search, String effort, Runnable guard, AtomicReference<Runnable> cancel, Consumer<String> onText) throws Exception {
        return complete(account, model, instructions, input, search, effort, guard, cancel, onText, true);
    }
    /**
     * As above; inline false keeps xAI's inline citations ([[1]](https://…)) out of a searched answer's text (the main chat,
     * whose source strip shows the pages instead).
     */
    static WorkClient.Result complete(Session account, String model, java.util.function.Function<Boolean, String> instructions, String input,
            boolean search, String effort, Runnable guard, AtomicReference<Runnable> cancel, Consumer<String> onText, boolean inline) throws Exception {
        return complete(account, model, instructions, input, search, effort, null, guard, cancel, onText, inline);
    }
    /** As above, with the app's own functions when tools is given (the main chat's connected services, MainTools). */
    static WorkClient.Result complete(Session account, String model, java.util.function.Function<Boolean, String> instructions, String input,
            boolean search, String effort, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel, Consumer<String> onText, boolean inline) throws Exception {
        return complete(account, model, instructions, input, search, effort, tools, guard, cancel, onText, inline, null);
    }
    /**
     * As above, with files: the photos and PDFs attached to the question (MainAttachments.parts, each with its base64 data), as
     * 작업창 sends them (OpenAiProtocol.conversation). Null or none sends the request above.
     */
    static WorkClient.Result complete(Session account, String model, java.util.function.Function<Boolean, String> instructions, String input,
            boolean search, String effort, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel, Consumer<String> onText, boolean inline, JSONArray files) throws Exception {
        return complete(account, model, instructions, input, search, false, effort, tools, guard, cancel, onText, inline, files);
    }
    /** The same with Grok's code interpreter when code is asked (the main chat, for exact numbers). */
    static WorkClient.Result complete(Session account, String model, java.util.function.Function<Boolean, String> instructions, String input,
            boolean search, boolean code, String effort, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel, Consumer<String> onText, boolean inline, JSONArray files) throws Exception {
        JSONArray asked = files != null && files.length() > 0 ? OpenAiProtocol.conversation(dev.jun.gyeot.core.MainAttachments.turn(input, files)) : arr(obj("role", "user", "content", input));
        return run(account, account.accountId(), model, (web, coding) -> instructions.apply(web), asked,
            search, code, tools, effort, "", onText, stage -> {}, guard, cancel, false, arr(), WorkSteering.NONE, inline);
    }
    /** One answer with both web search and X search (트레이딩's news briefing); without X search when the connection refuses it. */
    static WorkClient.Result searchWebAndX(Session account, String model, String instructions, String input, String effort, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        return run(account, account.accountId(), model, (web, coding) -> instructions, arr(obj("role", "user", "content", input)),
            true, false, null, effort, "", text -> {}, stage -> {}, guard, cancel, false, arr(), WorkSteering.NONE, true, true);
    }
    private static WorkClient.Result run(Session account, String expectedAccount, String model,
            java.util.function.BiFunction<Boolean, Boolean, String> instructions, JSONArray input, boolean search, boolean code,
            WorkTools github, String effort, String cacheKey, Consumer<String> onText, Consumer<String> onStage,
            Runnable guard, AtomicReference<Runnable> cancel, boolean inspectMerge, JSONArray turns, WorkSteering steering, boolean inline) throws Exception {
        return run(account, expectedAccount, model, instructions, input, search, code, github, effort, cacheKey, onText, onStage, guard, cancel, inspectMerge, turns, steering, inline, false);
    }
    private static WorkClient.Result run(Session account, String expectedAccount, String model,
            java.util.function.BiFunction<Boolean, Boolean, String> instructions, JSONArray input, boolean search, boolean code,
            WorkTools github, String effort, String cacheKey, Consumer<String> onText, Consumer<String> onStage,
            Runnable guard, AtomicReference<Runnable> cancel, boolean inspectMerge, JSONArray turns, WorkSteering steering, boolean inline, boolean x) throws Exception {
        StringBuilder written = new StringBuilder(); List<Integer> cuts = new ArrayList<>();
        JSONArray sources = arr(); List<String> notes = new ArrayList<>(); Set<String> answered = new HashSet<>();
        JSONArray functions = github == null ? arr() : github.openAi();
        String receipt = "";
        boolean plans = false, mutated = false, externalWork = false;
        int progressOnly = 0;
        WorkBudget budget = new WorkBudget();
        // Every finished round's usage (xAI's response.completed), summed, and the latest limit headers.
        Usage used = null; Limits limits = Limits.NONE;
        for (long round = 0;;) {
            guard.run(); account.check(expectedAccount);
            if (steering.pending()) {
                JSONArray added = steering.take();
                if (added.length() > 0) {
                    WorkSteering.append(turns, added); WorkSteering.append(input, OpenAiProtocol.conversation(added));
                    inspectMerge = github != null && github.inspectMerge(turns); progressOnly = 0; written.setLength(0); cuts.clear();
                    onStage.accept("추가 지시를 반영해 이어가는 중");
                }
            }
            String required = inspectMerge && round == 0 ? GitHubFunctions.GET : null;
            String guidance = instructions.apply(search && !plans, code && !plans) + (github == null ? "" : github.instructions()) + (progressOnly > 0 ? GitHubFunctions.CONTINUE_MERGE : "")
                + (required != null ? " First inspect the current PR with github_get; if its number is unclear list PRs in the configured repository. Never guess between multiple candidates." : "");
            JSONObject request;
            if (plans) {
                JSONObject plan = WorkPlans.request(model, guidance, input, functions, "", required);
                request = GrokWork.request(model, plan.optString("instructions"), plan.optJSONArray("input"), false, false, arr(), effort, cacheKey);
            } else {
                request = GrokWork.request(model, guidance, input, search, code, functions, effort, cacheKey, inline, x);
                if (required != null) put(request, "tool_choice", obj("type", "function", "name", required));
            }
            budget.beforeRequest(request.toString().length(), guard);
            OpenAiProtocol.Stream stream;
            try (OpenAiAccount.Http.Exchange response = account.responses(request, expectedAccount, cancel, guard)) {
                Limits seen = Limits.headers(response.headers, System.currentTimeMillis()); if (!seen.empty()) limits = seen;
                if (response.status >= 300) {
                    dev.jun.gyeot.core.ModelLimit.check(response.status, !externalWork, limits);
                    String body = OpenAiAccount.Http.read(response.body, 64 * 1024);
                    JSONObject error; try { error = new JSONObject(body); } catch (Exception ignored) { error = obj(); }
                    String refused = OpenAiProtocol.refusedTool(error.optJSONObject("error"), request);
                    if (response.status == 400 || response.status == 422) {
                        // Refused before any work for leaving inline citations out: once more with them, search still on.
                        if (request.has("include") && GrokWork.citationsRefused(OpenAiProtocol.message(error, ""))) { inline = true; continue; }
                        // xAI names the tool in param, or only in its error text.
                        if (x && ("x_search".equals(refused) || GrokWork.xSearchRefused(OpenAiProtocol.message(error, "")))) { x = false; notes.add("Grok 연결이 X 검색을 거절해 웹 검색으로만 찾았어요."); continue; }
                        if (search && ("web_search".equals(refused) || GrokWork.searchRefused(OpenAiProtocol.message(error, "")))) { search = false; notes.add(SEARCH_REFUSED); continue; }
                        if (code && "code_interpreter".equals(refused)) { code = false; notes.add("Grok 연결이 코드 실행을 거절해 실행 없이 답했어요."); continue; }
                    }
                    throw new IllegalStateException(GrokWork.error(response.status, OpenAiProtocol.message(error, "")));
                }
                stream = WorkClient.read(response, plans || inspectMerge ? text -> {} : onText, onStage, () -> { guard.run(); account.check(expectedAccount); }, "Grok");
            } catch (java.util.concurrent.CancellationException stopped) { throw stopped; }
            catch (Exception failed) {
                guard.run(); account.check(expectedAccount);
                if (receipt.isBlank()) throw failed;
                notes.add("Grok의 후속 답변을 받지 못해 GitHub가 반환한 병합 결과를 표시했어요.");
                return result(receipt, written.toString(), sources, notes, model).counted(used, limits);
            } finally { cancel.set(null); }
            used = Usage.sum(used, stream.usage());
            guard.run(); account.check(expectedAccount);
            String text = stream.text(); JSONArray output = stream.output(); externalWork |= WorkClient.hasHostedAction(output);
            boolean hasCalls = false;
            for (int i = 0; i < output.length(); i++) if (output.optJSONObject(i) != null && "function_call".equals(output.optJSONObject(i).optString("type"))) hasCalls = true;
            if (!plans && !mutated && functions.length() > 0 && !stream.refused() && !hasCalls
                && !WorkClient.hasHostedAction(output) && (text.isBlank() || required != null)) {
                plans = true; onStage.accept("Grok 연결에 맞는 작업 방식으로 전환하는 중"); continue;
            }
            if (plans && !stream.refused()) {
                if (text.isBlank() && !receipt.isBlank()) return result(receipt, written.toString(), sources, notes, model).counted(used, limits);
                try { WorkPlans.Reply plan = WorkPlans.reply(text, functions, required); text = plan.text(); output = plan.output(); }
                catch (IllegalStateException e) {
                    if (!receipt.isBlank()) {
                        notes.add("Grok의 후속 답변 대신 GitHub가 반환한 병합 결과를 표시했어요.");
                        return result(receipt, written.toString(), sources, notes, model).counted(used, limits);
                    }
                    throw new IllegalStateException(e.getMessage().replace("ChatGPT", "Grok"));
                }
            }
            hasCalls = false;
            for (int i = 0; i < output.length(); i++) if (output.optJSONObject(i) != null && "function_call".equals(output.optJSONObject(i).optString("type"))) hasCalls = true;
            if (inspectMerge && receipt.isBlank() && !stream.refused() && !hasCalls && GitHubFunctions.promisesMerge(text)) {
                if (++progressOnly > 2) throw new IllegalStateException("Grok이 계획만 답하고 병합 도구를 실행하지 않았어요. 병합은 완료되지 않았습니다.");
                input.put(obj("role", "assistant", "content", text)); onStage.accept("계획 답변에 이어 실제 GitHub 작업을 요청하는 중"); continue;
            }
            if (!hasCalls && (inspectMerge || !receipt.isBlank())) text = GitHubFunctions.mergeFinal(text, receipt);
            if ((plans || inspectMerge) && !text.isBlank()) onText.accept(text);
            mutated |= WorkClient.hasHostedAction(output);
            int base = written.length();
            if (base > 0 && !text.isEmpty()) { written.append("\n\n"); base += 2; cuts.add(base); }
            if (!plans) for (int cut : stream.cuts()) cuts.add(base + cut);
            written.append(text);
            for (int i = 0; i < stream.sources().length(); i++) sources.put(stream.sources().opt(i));
            JSONArray calls = arr(); Set<String> batch = new HashSet<>();
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null || !"function_call".equals(item.optString("type"))) continue;
                String name = item.optString("name"), id = item.optString("call_id");
                if (github == null || !github.supports(name) || id.isBlank() || answered.contains(id) || !batch.add(id))
                    throw new IllegalStateException("Grok의 도구 요청이 중복되었거나 사용할 수 없는 도구여서 멈췄어요.");
                calls.put(item);
            }
            if (calls.length() == 0) {
                if (!stream.refused() && !WorkClient.hasHostedAction(output) && steering.pending()) {
                    WorkSteering.append(input, output); if (!text.isBlank()) onText.accept("\n\n"); continue;
                }
                if (text.isBlank() && receipt.isBlank()) throw new IllegalStateException("Grok이 내용이 없는 응답을 보냈어요. 작업 결과를 확인한 뒤 다시 요청해 주세요.");
                if (stream.files().length() > 0) notes.add("Grok 코드 결과 파일은 아직 직접 내려받을 수 없어요. 본문의 결과를 확인해 주세요.");
                String[] split = WorkBook.split(written.toString(), cuts);
                return result(inspectMerge || !receipt.isBlank() ? GitHubFunctions.mergeFinal(split[0], receipt) : split[0], split[1], sources, notes, model).counted(used, limits);
            }
            budget.beforeBatch(calls.length(), (long) input.toString().length() + output.toString().length(), guard, onStage);
            if (round < Long.MAX_VALUE) round++;
            // Preserve xAI's opaque reasoning and server-side tool output unchanged within this task.
            for (int i = 0; i < output.length(); i++) input.put(output.opt(i));
            for (int i = 0; i < calls.length(); i++) {
                budget.beforeTool(guard); account.check(expectedAccount);
                JSONObject call = calls.getJSONObject(i); String name = call.getString("name"), id = call.getString("call_id"); answered.add(id);
                onStage.accept(WorkTools.stage(name));
                boolean superseded = steering.pending();
                mutated |= !superseded && WorkTools.startsWork(name);
                JSONObject value = superseded ? WorkSteering.skipped() : github.run(name, call.optString("arguments"));
                budget.result(name, call.optString("arguments"), value.toString(), value.has("ok") && !value.optBoolean("ok"));
                guard.run();
                if (!superseded && GitHubFunctions.MERGE.equals(name)) {
                    receipt = GitHubFunctions.mergeReceipt(value);
                    onText.accept(receipt);
                    return result(receipt, written.toString(), sources, notes, model).counted(used, limits);
                }
                input.put(obj("type", "function_call_output", "call_id", id, "output", GitHubFunctions.clip(value.toString())));
            }
            if (written.length() > 0) onText.accept("\n\n");
        }
    }
    private static WorkClient.Result result(String text, String steps, JSONArray sources, List<String> notes, String model) {
        return new WorkClient.Result(text, sources, arr(), "", String.join(" ", notes), arr(), steps, model);
    }
}


