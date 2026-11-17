package dev.jun.gyeot;

import dev.jun.gyeot.core.WorkBudget;

import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.NimProtocol;
import dev.jun.gyeot.core.Usage;
import dev.jun.gyeot.core.WorkBook;
import dev.jun.gyeot.core.WorkSteering;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** The NVIDIA NIM model (NimProtocol.MODEL) on its own credential, never another provider's token or paid fallback. */
final class NimClient {
    interface Session {
        long generation();
        void check(long expected);
        OpenAiAccount.Http.Exchange completions(JSONObject body, long expected, AtomicReference<Runnable> cancel, Runnable guard) throws Exception;
        /** Whether to ask this model for its token counts (stream_options.include_usage): not once NVIDIA refused the field for it on this connection. */
        default boolean asksUsage(String model) { return true; }
        /** NVIDIA refused stream_options for this model and answered without it: later requests on this connection do not ask (NimAccount keeps it). */
        default void refuseUsage(String model) {}
    }
    private NimClient() {}
    static WorkClient.Result work(Session account, long expected, String model, String clock, JSONArray turns, String context,
            WorkTools github, Consumer<String> onText, Consumer<String> onStage, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        return work(account, expected, model, clock, turns, context, github, onText, onStage, guard, cancel, WorkSteering.NONE);
    }
    static WorkClient.Result work(Session account, long expected, String model, String clock, JSONArray turns, String context,
            WorkTools github, Consumer<String> onText, Consumer<String> onStage, Runnable guard, AtomicReference<Runnable> cancel, WorkSteering steering) throws Exception {
        boolean inspectMerge = github != null && github.inspectMerge(turns);
        String instructions = WorkBook.instructions(clock, false, false, WorkBook.NIM, false, false, WorkBook.names(github == null ? null : github.declarations())) + " Your selected model is " + NimProtocol.label(model) + ". "
            + (context == null ? "" : context) + WorkSteering.GUIDANCE;
        // The stream ends with its token counts (stream_options.include_usage), for the usage ledger.
        return run(account, expected, model, instructions, NimProtocol.conversation(turns), github, onText, onStage, guard, cancel, inspectMerge, turns, steering, true);
    }
    static WorkClient.Result complete(Session account, long expected, String model, String instructions, String input,
            Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        return complete(account, expected, model, instructions, input, guard, cancel, text -> {});
    }
    /** As complete, telling onText the answer as it streams in (누르고 말하기 reads it aloud meanwhile). */
    static WorkClient.Result complete(Session account, long expected, String model, String instructions, String input,
            Runnable guard, AtomicReference<Runnable> cancel, Consumer<String> onText) throws Exception {
        return complete(account, expected, model, instructions, input, false, guard, cancel, onText);
    }
    /** As above; usage asks NVIDIA to end the stream with the request's token counts (the main chat's usage report). */
    static WorkClient.Result complete(Session account, long expected, String model, String instructions, String input, boolean usage,
            Runnable guard, AtomicReference<Runnable> cancel, Consumer<String> onText) throws Exception {
        return complete(account, expected, model, instructions, input, usage, null, guard, cancel, onText);
    }
    /** As above, with the app's own functions when tools is given (the main chat's connected services, MainTools). */
    static WorkClient.Result complete(Session account, long expected, String model, String instructions, String input, boolean usage, WorkTools tools,
            Runnable guard, AtomicReference<Runnable> cancel, Consumer<String> onText) throws Exception {
        return complete(account, expected, model, instructions, input, usage, tools, guard, cancel, onText, null);
    }
    /**
     * As above, with files: the photos attached to the question (MainAttachments.parts, each with its base64 data), as image
     * parts (NimProtocol.conversation, which refuses a PDF before anything is sent). Null or none sends the request above.
     */
    static WorkClient.Result complete(Session account, long expected, String model, String instructions, String input, boolean usage, WorkTools tools,
            Runnable guard, AtomicReference<Runnable> cancel, Consumer<String> onText, JSONArray files) throws Exception {
        JSONArray asked = files != null && files.length() > 0 ? NimProtocol.conversation(dev.jun.gyeot.core.MainAttachments.turn(input, files)) : arr(obj("role", "user", "content", input));
        return run(account, expected, model, instructions, asked, tools, onText, stage -> {}, guard, cancel, false, arr(), WorkSteering.NONE, usage);
    }
    private static WorkClient.Result run(Session account, long expected, String model, String instructions, JSONArray messages, WorkTools github,
            Consumer<String> onText, Consumer<String> onStage, Runnable guard, AtomicReference<Runnable> cancel, boolean inspectMerge, JSONArray turns, WorkSteering steering, boolean usage) throws Exception {
        StringBuilder steps = new StringBuilder(); String receipt = ""; Set<String> executed = new HashSet<>();
        int progressOnly = 0;
        JSONArray declarations = github == null ? arr() : github.declarations();
        Set<String> offered = new HashSet<>(); for (int i = 0; i < declarations.length(); i++) offered.add(declarations.getJSONObject(i).getString("name"));
        WorkBudget budget = new WorkBudget();
        // Every finished round's usage chunk, summed, and the latest limit headers.
        Usage used = null; Limits limits = Limits.NONE;
        // A model that refused stream_options on this connection is not asked again; dropped: this turn sent it once more without.
        usage = usage && account.asksUsage(model); boolean dropped = false;
        // This request was refused with 502 or 429 and sent once more; a second refusal stops it (NimProtocol.retries).
        boolean retried = false;
        while (true) {
            guard.run(); account.check(expected);
            if (steering.pending()) {
                JSONArray added = steering.take();
                if (added.length() > 0) {
                    WorkSteering.append(turns, added); WorkSteering.append(messages, NimProtocol.conversation(added));
                    inspectMerge = github != null && github.inspectMerge(turns); progressOnly = 0;
                    onStage.accept("추가 지시를 반영해 이어가는 중");
                }
            }
            NimProtocol.Stream stream;
            String guidance = instructions + (github == null ? "" : github.instructions()) + (progressOnly > 0 ? GitHubFunctions.CONTINUE_MERGE : "");
            JSONObject request = NimProtocol.request(model, guidance, messages, declarations, usage);
            budget.beforeRequest(request.toString().length(), guard);
            boolean resend = false, again = false;
            try (OpenAiAccount.Http.Exchange response = account.completions(request, expected, cancel, guard)) {
                Limits seen = Limits.headers(response.headers, System.currentTimeMillis()); if (!seen.empty()) limits = seen;
                // 502 or 429 came before any answer, so nothing ran: sent once more, then it stops with the reason.
                again = !retried && NimProtocol.retries(response.status);
                if (!again && response.status == 429) throw new dev.jun.gyeot.core.ModelLimit(429, NimProtocol.error(429), limits);
                if (!again) dev.jun.gyeot.core.ModelLimit.check(response.status, true, limits);
                if (again) stream = null;
                else if (response.status != 200) {
                    // Only a request-shape refusal is read: to recognize a context-window limit, or the usage request.
                    String body = "", detail = "";
                    if (response.status == 400 || response.status == 413 || response.status == 422)
                        try { body = OpenAiAccount.Http.read(response.body, 16 * 1024); detail = dev.jun.gyeot.core.OpenAiProtocol.message(new JSONObject(body), ""); } catch (Exception ignored) {}
                    // Refused before any work because of stream_options, named anywhere in the error (FastAPI puts the field only in
                    // detail[].loc): sent once more without asking for usage, at most once, and counted as unknown.
                    resend = usage && NimProtocol.refusesUsage(response.status, body);
                    if (!resend) throw new IllegalStateException(NimProtocol.error(response.status, detail));
                    stream = null;
                } else stream = read(response, inspectMerge ? value -> {} : onText, onStage, () -> { guard.run(); account.check(expected); });
            } catch (java.util.concurrent.CancellationException e) { throw e; }
            catch (Exception e) {
                guard.run(); account.check(expected);
                if (receipt.isBlank()) throw e;
                return result(receipt, steps.toString(), model, "NIM의 후속 설명을 받지 못해 GitHub가 반환한 병합 결과를 표시했어요.").counted(used, limits);
            } finally { cancel.set(null); }
            if (again) { retried = true; onStage.accept("NVIDIA가 답하지 못해 한 번 다시 보내는 중"); pause(NimProtocol.RETRY_MS, guard, account, expected); continue; }
            retried = false;
            if (resend) { usage = false; dropped = true; continue; }
            used = Usage.sum(used, stream.usage());
            guard.run(); account.check(expected);
            // Answered without the field on the same key: it was the field, so later requests skip it. An error that only echoed the request is not kept.
            if (dropped) { account.refuseUsage(model); dropped = false; }
            JSONArray calls = stream.calls(); String text = stream.text();
            if (calls.length() == 0) {
                if (steering.pending()) {
                    messages.put(stream.assistant());
                    if (!text.isBlank()) { if (steps.length() > 0) steps.append("\n\n"); steps.append(text); if (!inspectMerge) onText.accept("\n\n"); }
                    continue;
                }
                if (inspectMerge && receipt.isBlank() && GitHubFunctions.promisesMerge(text)) {
                    if (++progressOnly > 2) throw new IllegalStateException("NIM이 계획만 답하고 병합 도구를 실행하지 않았어요. 병합은 완료되지 않았습니다.");
                    messages.put(stream.assistant());
                    messages.put(obj("role", "user", "content", GitHubFunctions.CONTINUE_MERGE));
                    onStage.accept("계획 답변에 이어 실제 GitHub 작업을 요청하는 중");
                    continue;
                }
                if (text.isBlank() && receipt.isBlank()) throw new IllegalStateException("NIM이 내용 없는 답변을 보냈어요. 요청을 확인한 뒤 다시 보내 주세요.");
                String answer = inspectMerge || !receipt.isBlank() ? GitHubFunctions.mergeFinal(text, receipt) : text;
                if (inspectMerge) onText.accept(answer);
                return result(answer, steps.toString(), model, "").counted(used, limits);
            }
            budget.beforeBatch(calls.length(), (long) messages.toString().length() + stream.assistant().toString().length(), guard, onStage);
            // Validate the entire batch before any side effect, including hallucinated tools and repeated call IDs.
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.getJSONObject(i);
                if (!offered.contains(call.getJSONObject("function").getString("name")) || executed.contains(call.getString("id")))
                    throw new IllegalStateException("NIM이 사용할 수 없거나 이미 실행한 도구를 요청해 멈췄어요.");
            }
            if (!text.isBlank() && (!inspectMerge || GitHubFunctions.mergeFinal(text, "").equals(text))) {
                if (steps.length() > 0) steps.append("\n\n"); steps.append(text); if (!inspectMerge) onText.accept("\n\n");
            }
            messages.put(stream.assistant());
            for (int i = 0; i < calls.length(); i++) {
                budget.beforeTool(guard); account.check(expected);
                JSONObject call = calls.getJSONObject(i), function = call.getJSONObject("function");
                String id = call.getString("id"), name = function.getString("name"); executed.add(id); onStage.accept(WorkTools.stage(name));
                boolean superseded = steering.pending();
                JSONObject value = superseded ? WorkSteering.skipped() : github.run(name, function.getString("arguments"));
                budget.result(name, function.optString("arguments"), value.toString(), value.has("ok") && !value.optBoolean("ok"));
                guard.run();
                if (!superseded && GitHubFunctions.MERGE.equals(name)) receipt = GitHubFunctions.mergeReceipt(value);
                messages.put(obj("role", "tool", "tool_call_id", id, "content", GitHubFunctions.clip(value.toString())));
                if (!receipt.isBlank()) break;
            }
            if (!receipt.isBlank()) {
                onText.accept(receipt);
                return result(receipt, steps.toString(), model, "").counted(used, limits);
            }
        }
    }
    private static WorkClient.Result result(String text, String steps, String model, String note) {
        return new WorkClient.Result(text, arr(), arr(), "", note, arr(), steps, model);
    }
    private static NimProtocol.Stream read(OpenAiAccount.Http.Exchange response, Consumer<String> onText, Consumer<String> onStage, Runnable guard) throws Exception {
        NimProtocol.Stream stream = new NimProtocol.Stream(); String stage = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder(); int character; long bytes = 0;
            while ((character = reader.read()) != -1) {
                if (++bytes > NimProtocol.MAX_RESPONSE * 2L) throw new IllegalStateException("NIM 응답이 너무 커서 멈췄어요.");
                if ((bytes & 4095) == 0) guard.run();
                if (character != '\n') { line.append((char) character); if (line.length() > NimProtocol.MAX_EVENT) throw new IllegalStateException("NIM 응답 한 조각이 너무 커요."); continue; }
                guard.run(); String raw = line.toString(); line.setLength(0); if (raw.endsWith("\r")) raw = raw.substring(0, raw.length() - 1);
                String added = stream.line(raw); if (!added.isEmpty()) onText.accept(added);
                String next = stream.thinking() ? "생각하는 중" : "답변을 받는 중";
                if (!next.equals(stage)) { onStage.accept(next); stage = next; }
                if (stream.done()) break;
            }
            if (!stream.done()) {
                // A last line without its line break passes the guard like every line before it.
                guard.run();
                if (line.length() > 0) { String added = stream.line(line.toString().stripTrailing()); if (!added.isEmpty()) onText.accept(added); }
                String added = stream.line(""); if (!added.isEmpty()) onText.accept(added);
            }
        }
        guard.run(); stream.verify(); return stream;
    }
    /** Waits before the one retry, stopping at once when the request is cancelled or the key changes. */
    private static void pause(long ms, Runnable guard, Session account, long expected) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        for (long left; (left = until - System.currentTimeMillis()) > 0; ) { guard.run(); account.check(expected); Thread.sleep(Math.min(100, left)); }
        guard.run(); account.check(expected);
    }
}
