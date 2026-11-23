package dev.jun.gyeot;

import android.content.SharedPreferences;
import dev.jun.gyeot.core.GeminiWork;
import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.MainAttachments;
import dev.jun.gyeot.core.MainChatContext;
import dev.jun.gyeot.core.MainRequest;
import dev.jun.gyeot.core.MainSearch;
import dev.jun.gyeot.core.OpenAiProtocol;
import dev.jun.gyeot.core.Usage;
import dev.jun.gyeot.core.WorkBook;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The main chat's five engines behind one request value (core/MainRequest). Replies, summaries and memory passes all
 * leave through send(): it takes the model, level and speed of the selection the request was bound to, refuses an option
 * that engine cannot carry before anything is sent, and hands the text and options to that engine's own client, whose
 * request builder, transport and stream parser do the rest. The answer streams back through the request's onText, the
 * thinking summary through its onThinking, what it used to its onUsage once it has come, and the limits the provider's
 * response headers reported go to the usage ledger. A hosted web search or page reading an account and model refused
 * before any work is left out for a week after, each on its own (core/MainSearch).
 */
final class MainAdapters {
    private MainAdapters() {}
    /**
     * What one request brought back: the answer, the pages it cited, a note when the provider refused an optional part,
     * Google's search suggestions for an answer Gemini grounded with Google Search (HTML and CSS, shown unchanged beside
     * it), unsearched when the web search the request asked for was left out (refused now, or within the week), unread
     * when its page reading was refused now, what it used (summed over the provider responses it took) and the limits the
     * provider reported.
     */
    record Reply(String text, JSONArray sources, String note, String suggestions, boolean unsearched, boolean unread, Usage usage, Limits limits) {
        Reply(String text, JSONArray sources, String note) { this(text, sources, note, Usage.UNKNOWN, Limits.NONE); }
        Reply(String text, JSONArray sources, String note, Usage usage, Limits limits) { this(text, sources, note, "", false, false, usage, limits); }
        Reply {
            sources = sources == null ? new JSONArray() : sources; note = note == null ? "" : note; suggestions = suggestions == null ? "" : suggestions;
            usage = usage == null ? Usage.UNKNOWN : usage; limits = limits == null ? Limits.NONE : limits;
        }
        /** The same reply, marked as answered without the web search it asked for. */
        Reply withoutSearch() { return new Reply(text, sources, note, suggestions, true, unread, usage, limits); }
        /** Sizes and counts only: the answer never goes to a log or an error. */
        @Override public String toString() {
            return "Reply[" + (text == null ? 0 : text.length()) + " chars, " + sources.length() + " sources, note " + note.length() + " chars, suggestions " + suggestions.length() + " chars" + (unsearched ? ", unsearched" : "") + (unread ? ", unread" : "") + ", "
                + (usage.total() == null ? "tokens unknown" : usage.total() + " tokens") + ", " + limits.windows().size() + " limit windows]";
        }
    }

    static Reply send(AppStore app, JSONObject selection, MainRequest request, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        return send(app, selection, request, null, guard, cancel);
    }
    /**
     * As above, with the app's own functions when tools is given (the main chat's connected services,
     * MainTools): every engine runs them in rounds and answers with the last round's text. Without tools
     * every request is exactly the one above.
     */
    static Reply send(AppStore app, JSONObject selection, MainRequest request, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        ScopedModels s = ScopedModels.frozen(app, selection); String engine = s.engine(), model = s.model(engine), effort = s.effectiveEffort(engine, model);
        request.check(engine);
        guard.run();
        // The connection this request goes out on: its limits are kept under it, never under one that replaced it meanwhile.
        String identity = app.usageIdentity(engine), route = app.usageRoute(engine);
        // An account and model that refused the thinking summary before are not asked for it again; one that refused web
        // search, or page reading, within the last week is not offered that tool (an answer without search says so). Each
        // is kept on its own, so a refused page reading never turns search off.
        boolean wanted = request.search();
        String refusal = refusalKey(engine, model), searchKey = MainSearch.refusalKey(engine, model), fetchKey = MainSearch.fetchRefusalKey(engine, model);
        String account = request.thinkingSummary() || request.search() || request.fetch() ? app.workConnectionFingerprint(engine) : "";
        if (request.thinkingSummary() && !account.isEmpty() && account.equals(app.settings.getString(refusal, ""))) request = request.thinkingSummary(null);
        long now = System.currentTimeMillis();
        boolean searchKept = request.search() && MainSearch.refused(app.settings.getString(searchKey, ""), account, now);
        boolean fetchKept = request.fetch() && MainSearch.refused(app.settings.getString(fetchKey, ""), account, now);
        request = request.tools(request.search() && !searchKept, request.fetch() && !fetchKept);
        // What Gemini's resends below left out: search, page reading the refusal named, page reading a refusal that named
        // no tool took (counted only if the answer then came with search still on).
        boolean searchDropped = false, fetchNamed = false, fetchGuessed = false;
        // One loop, one request at a time: each pass sends the request as it now stands, and only the one that answered
        // reports its usage (a refused request did no work and writes no row).
        while (true) {
            try {
                Reply reply = dispatch(app, s, engine, model, effort, request, tools, guard, cancel);
                // A tool was refused before any work and the answer came without it: the client sent it once more on its
                // own (ChatGPT, Claude, Grok), or this loop did (Gemini). Kept for a week for this account and model, as its
                // connection fingerprint, never a key.
                boolean searchLost = searchDropped || (request.search() && reply.unsearched()), fetchLost = fetchNamed || fetchGuessed || reply.unread();
                // Not remembered when app functions went with the request: a provider that refuses the two together (Gemini's
                // search beside function declarations) still takes search alone, in every reply without functions.
                if ((searchLost || fetchLost) && !account.isEmpty() && tools == null) {
                    SharedPreferences.Editor kept = app.settings.edit(); String at = MainSearch.refusal(account, System.currentTimeMillis());
                    if (searchLost) kept.putString(searchKey, at);
                    if (fetchLost) kept.putString(fetchKey, at);
                    kept.apply();
                }
                if (!reply.limits().empty()) app.usage.limits(engine, route, identity, reply.limits());
                if (request.countsUsage()) request.onUsage().accept(reply.usage());
                return wanted && (searchKept || searchLost) ? reply.withoutSearch() : reply;
            } catch (MainRequest.SummaryRefused refused) {
                if (!request.thinkingSummary()) throw refused;
                // The provider refused the summary field before doing any work, so sending once more without it repeats nothing
                // (the same rule as a refused hosted tool). Later turns on this account and model skip it.
                guard.run();
                if (!account.isEmpty()) app.settings.edit().putString(refusal, account).apply();
                request = request.thinkingSummary(null);
            } catch (MainRequest.ToolsRefused refused) {
                if (!request.search() && !request.fetch()) throw refused;
                // Gemini refused its hosted web tools before any work: sent once more with one fewer, which repeats nothing
                // paid, so a turn sends three requests at most. As in 작업창, page reading goes first, when the refusal
                // names it or names no tool; search goes when the refusal names it (page reading with it), or when the
                // request without page reading is refused too.
                guard.run();
                if (request.fetch() && (refused.tool != MainRequest.Option.SEARCH || !request.search())) {
                    fetchNamed |= refused.tool == MainRequest.Option.FETCH; fetchGuessed = refused.tool != MainRequest.Option.FETCH;
                    request = request.tools(request.search(), false);
                } else { searchDropped = true; fetchGuessed = false; request = request.tools(false, false); }
            } catch (dev.jun.gyeot.core.ModelLimit refused) {
                // A used-up limit is kept like an answer's (설정 → 사용량 shows it reached), but the refused request is no usage
                // row. Any pass of the loop may meet it, a resend after a refused optional part too.
                if (!refused.limits.empty()) app.usage.limits(engine, route, identity, refused.limits);
                throw refused;
            }
        }
    }
    /** Forgets the web tools every connection and model of this engine refused, so the next reply offers them again (검색: 자동 or 항상 picked). */
    static void forgetRefusals(AppStore app, String engine) {
        SharedPreferences.Editor forget = app.settings.edit(); boolean any = false; java.util.Map<String, ?> saved = app.settings.getAll();
        for (String key : saved.keySet()) for (String prefix : MainSearch.refusalPrefixes(engine)) if (key.startsWith(prefix)) { forget.remove(key); any = true; }
        if (any) forget.apply();
    }
    /** Where a thinking-summary refusal is kept: per engine and model, holding the fingerprint of the account that refused. */
    static String refusalKey(String engine, String model) { return "mainThinkingRefused." + engine + "." + model; }
    private static Reply dispatch(AppStore app, ScopedModels s, String engine, String model, String effort, MainRequest request, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        return switch (engine) {
            case WorkBook.CLAUDE -> claude(app.claude, model, effort, request, tools, guard, cancel);
            case WorkBook.GEMINI -> completeGeminiText(app.gemini.key(), model, effort, request, tools, guard, cancel);
            case WorkBook.GROK -> grok(app.grok, model, effort, request, tools, guard, cancel);
            case WorkBook.NIM -> nim(app.nim, app.nim.generation(), model, request, tools, guard, cancel);
            case WorkBook.ANTIGRAVITY -> antigravity(app, model, effort, request, tools, guard, cancel);
            default -> chatgpt(app.openai, model, effort, s.speed(model), request, tools, guard, cancel);
        };
    }
    /** ChatGPT, Responses API: web_search when asked (required for 항상), reasoning.summary on a model sent a reasoning level, the selection's speed, prompt_cache_key when cached. */
    private static Reply chatgpt(OpenAiAccount account, String model, String effort, String speed, MainRequest request, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        OpenAiClient.Result result = OpenAiClient.complete(account, model, web -> request.instructions(), request.input(), request.search(), request.always(), request.onThinking(), effort, speed, guard, cancel, request.onText(), request.cacheKey(), tools, request.parts());
        return new Reply(result.text, result.sources, result.searchRefusal == null ? "" : result.searchRefusal, "", request.search() && result.searchRefusal != null, false, result.usage, result.limits);
    }
    /**
     * Claude, Messages API: the one model chosen (never a server-side fallback), web_search (at most ClaudeClient.MAIN_SEARCHES
     * searches) and web_fetch as asked, thinking.display on adaptive models, the instructions marked for the prompt cache
     * when cached. The client reports a refused search or page reading.
     */
    private static Reply claude(ClaudeAccount account, String model, String effort, MainRequest request, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        OpenAiClient.Result result = ClaudeClient.completeStrict(account, model, request.instructions(), request.input(), request.search(), request.fetch(), request.onThinking(), request.cacheKey() != null, effort, tools, guard, cancel, request.onText(), request.parts());
        return new Reply(result.text, result.sources, result.searchRefusal == null ? "" : result.searchRefusal, "", request.search() && result.searchRefusal != null, request.fetch() && result.fetchRefused, result.usage, result.limits);
    }
    /** Grok 4.7, xAI Responses API on the signed-in account: web_search when asked, without inline citations in the answer (the strip shows them). */
    private static Reply grok(GrokAccount account, String model, String effort, MainRequest request, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        WorkClient.Result result = GrokWorkClient.complete(account, model, web -> request.instructions(), request.input(), request.search(), effort, tools, guard, cancel, request.onText(), false, request.parts());
        return new Reply(result.text, result.sources, result.note, "", request.search() && result.note.contains(GrokWorkClient.SEARCH_REFUSED), false, result.usage, result.limits);
    }
    /** NVIDIA NIM, Chat Completions: text only; stream_options.include_usage when usage is counted. */
    /** Antigravity (agy in the app's Linux): one answer to the whole request, text only, shown when it comes. */
    private static Reply antigravity(AppStore app, String model, String effort, MainRequest request, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        guard.run();
        String prompt = request.instructions() + (tools == null ? "" : tools.instructions()) + "\n\n" + request.input();
        AgyRunner.Result r = AgyRunner.run(app.antigravity.context(), model, effort, "", prompt, tools, request.onText(), null, guard, cancel, 600_000);
        return new Reply(r.text(), new JSONArray(), "");
    }
    private static Reply nim(NimAccount account, long expected, String model, MainRequest request, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        WorkClient.Result result = NimClient.complete(account, expected, model, request.instructions(), request.input(), request.countsUsage(), tools, guard, cancel, request.onText(), request.parts());
        return new Reply(result.text, result.sources, result.note, result.usage, result.limits);
    }
    /**
     * Gemini on the API key (GeminiWorkClient.stream): no code, Google Search and page reading as asked (page reading alone
     * too), thought summaries when asked, and the app's own functions when tools is given. Nothing is sent again here: a
     * request refused for its thought summaries or its web tools goes back to send(), which sends it again without the
     * summary, or with one tool fewer (only before any function ran, so nothing runs twice). A response that calls functions
     * goes back as it came (thought signatures included) with each result, round after round, at most
     * OpenAiClient.MAIN_ROUNDS before the model must answer; an answer that is not finished plain text is refused. Google's
     * search suggestions come back with a grounded answer.
     */
    private static Reply completeGeminiText(String key, String model, String effort, MainRequest r, WorkTools tools, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        if (key == null || key.isBlank() || model == null || model.isBlank()) throw new IllegalStateException("메인 대화의 Gemini 연결과 모델을 먼저 선택해 주세요.");
        // Attached photos and PDFs go as inline data before the question's text, as in 작업창 (GeminiWork.contents).
        JSONArray contents = r.parts() == null ? arr(obj("role", "user", "parts", arr(obj("text", r.input())))) : GeminiWork.contents(MainAttachments.turn(r.input(), r.parts()));
        String instructions = r.instructions() + (tools == null ? "" : tools.instructions());
        JSONArray found = new JSONArray(); String suggestions = ""; Usage used = null; int rounds = 0;
        while (true) {
            JSONObject request = GeminiWork.request(model, instructions, contents, r.search(), r.fetch(), false, tools == null ? null : tools.gemini(), effort, r.thinkingSummary(), true);
            if (tools != null && rounds >= (tools.rounds() > 0 ? tools.rounds() : OpenAiClient.MAIN_ROUNDS)) request.put("toolConfig", obj("functionCallingConfig", obj("mode", "NONE")));
            guard.run();
            GeminiWork.Stream stream = new GeminiWork.Stream();
            try (OpenAiAccount.Http.Exchange response = GeminiWorkClient.stream(key, model, request, 120_000, cancel)) {
                if (response.status >= 300) {
                    // The provider's reason (e.g. a context-window limit) lets the caller react; it is never shown raw beyond a short excerpt.
                    String detail = ""; try { detail = OpenAiProtocol.message(new JSONObject(OpenAiAccount.Http.read(response.body, 16 * 1024)), ""); } catch (Exception ignored) {}
                    if (rounds == 0 && r.thinkingSummary() && GeminiWork.thoughtsRefused(response.status, detail)) throw new MainRequest.SummaryRefused();
                    String tool = rounds == 0 && (r.search() || r.fetch()) ? GeminiWork.refusedTool(response.status, detail) : "";
                    if (!tool.isEmpty()) throw new MainRequest.ToolsRefused(tool.equals("url_context") ? MainRequest.Option.FETCH : tool.equals("google_search") ? MainRequest.Option.SEARCH : null);
                    throw new IOException("Gemini 요청 오류 HTTP " + response.status + (detail.isBlank() ? "" : " (" + MainChatContext.clip(detail, 240) + ")") + ". 도구·모델은 바꾸지 않았어요.");
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
                    String line; long total = 0;
                    while ((line = reader.readLine()) != null) {
                        guard.run(); total += line.length();
                        if (total > 2 * 1024 * 1024) throw new IOException("메인 답변 한도를 넘었어요.");
                        String delta = stream.line(line), thought = stream.takeThinking();
                        if (stream.failure() != null) throw new IOException(stream.failure());
                        if (r.thinkingSummary() && !thought.isEmpty()) r.onThinking().accept(thought);
                        if (delta != null && !delta.isEmpty()) r.onText().accept(delta);
                    }
                }
            } finally { cancel.set(null); }
            guard.run();
            used = Usage.sum(used, stream.usage());
            for (int i = 0; i < stream.sources().length(); i++) found.put(stream.sources().opt(i));
            if (!stream.searchEntry().isBlank()) suggestions = stream.searchEntry();
            if (tools != null && stream.calls().length() > 0 && stream.note().isEmpty()) {
                rounds++;
                JSONArray responses = new JSONArray();
                for (int i = 0; i < stream.calls().length(); i++) {
                    JSONObject call = stream.calls().getJSONObject(i); String name = call.optString("name");
                    guard.run();
                    JSONObject result = tools.supports(name) ? tools.run(name, call.optJSONObject("args")) : GitHubFunctions.failure("이 대화에 없는 도구예요: " + name);
                    guard.run();
                    // Gemini takes the result as an object; one too long goes as clipped text inside one.
                    JSONObject answer = obj("name", name, "response", result.toString().length() <= GitHubFunctions.RESULT_CHARS ? result : obj("result", GitHubFunctions.clip(result.toString())));
                    if (!call.optString("id").isBlank()) answer.put("id", call.optString("id"));
                    responses.put(obj("functionResponse", answer));
                }
                contents.put(obj("role", "model", "parts", stream.parts()));
                contents.put(obj("role", "user", "parts", responses));
                if (!stream.text().isBlank()) r.onText().accept("\n\n");
                continue;
            }
            if (!stream.finished() || stream.calls().length() != 0 || !stream.note().isEmpty()) throw new IOException("완전한 텍스트 답변을 확인하지 못했어요.");
            // Google sends no limit headers.
            return new Reply(stream.text(), found, "", suggestions, false, false, used, Limits.NONE);
        }
    }
}
