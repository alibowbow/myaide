package dev.jun.gyeot;

import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.OpenAiProtocol;
import dev.jun.gyeot.core.PhoneAgent;
import dev.jun.gyeot.core.PhonePlans;
import dev.jun.gyeot.core.PhoneResponseChain;
import dev.jun.gyeot.core.ResponseSocket;
import dev.jun.gyeot.core.Reasoning;
import dev.jun.gyeot.core.GptSpeed;
import dev.jun.gyeot.core.Usage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Responses transport. Blocking; run it on a worker thread and run the cancel action (off the main thread) to stop it. */
final class OpenAiClient {
    private OpenAiClient() {}
    static final class Result {
        final String text; final JSONArray sources;
        /** Set when web search was asked for but the request was refused with it, so the answer came without search. */
        final String searchRefusal;
        final String speedNotice;
        /** Page reading was asked for but refused before any work, so the answer came without it (Claude's web_fetch). */
        final boolean fetchRefused;
        /** What the request used (core/Usage, Usage.UNKNOWN when the provider said nothing) and the limits its responses reported. */
        final Usage usage; final Limits limits;
        Result(String text, JSONArray sources, String searchRefusal) { this(text, sources, searchRefusal, ""); }
        Result(String text, JSONArray sources, String searchRefusal, String speedNotice) { this(text, sources, searchRefusal, speedNotice, false); }
        Result(String text, JSONArray sources, String searchRefusal, String speedNotice, boolean fetchRefused) { this(text, sources, searchRefusal, speedNotice, fetchRefused, Usage.UNKNOWN, Limits.NONE); }
        Result(String text, JSONArray sources, String searchRefusal, String speedNotice, Usage usage, Limits limits) { this(text, sources, searchRefusal, speedNotice, false, usage, limits); }
        Result(String text, JSONArray sources, String searchRefusal, String speedNotice, boolean fetchRefused, Usage usage, Limits limits) {
            this.text = text; this.sources = sources; this.searchRefusal = searchRefusal; this.speedNotice = speedNotice; this.fetchRefused = fetchRefused;
            this.usage = usage == null ? Usage.UNKNOWN : usage; this.limits = limits == null ? Limits.NONE : limits;
        }
    }
    static void testPhone(OpenAiAccount account, String model, String clock, String effort, JSONObject status,
            Runnable onActivity, AtomicReference<Runnable> cancel, Runnable guard, Consumer<String> notice) throws Exception {
        try (PhoneAgent.Model modelCalls = phoneModel(account, model, clock, effort, onActivity, cancel, guard, true, notice)) {
            PhoneAgent.probe(modelCalls, status, guard);
        }
    }
    /** One adapter per task: a format fallback persists across its turns and uses the captured account/model/effort. */
    static PhoneAgent.Model phoneModel(OpenAiAccount account, String model, String clock, String effort,
            Runnable onActivity, AtomicReference<Runnable> cancel, Runnable guard, boolean probe, Consumer<String> notice) {
        Function<JSONArray, String> choice = input -> probe ? OpenAiProtocol.hasToolOutput(input) ? "none" : "phone_status" : null;
        long formatGeneration = account.phoneFormatGeneration();
        boolean preferPlans = account.phonePlans(model);
        String speed = account.speed(model);
        if (preferPlans) notice.accept("→ GPT · 확인된 JSON 명령 방식으로 바로 시작");
        PhoneSession session = new PhoneSession(account, onActivity, cancel, guard, notice, formatGeneration);
        PhoneAgent.Model calls = PhoneAgent.withPlanFallback(
            input -> phone(account, model, clock, input, effort, onActivity, cancel, session::check, choice.apply(input), false, session, speed),
            input -> {
                PhoneAgent.Reply reply = phone(account, model, clock, input, effort, onActivity, cancel, session::check, choice.apply(input), true, session, speed);
                guard.run(); account.rememberPhonePlans(model, formatGeneration); return reply;
            },
            reason -> { guard.run(); notice.accept("→ GPT JSON 명령 방식으로 전환 · " + reason); }, preferPlans);
        return new PhoneAgent.Model() {
            @Override public PhoneAgent.Reply respond(JSONArray input) throws Exception { session.check(); return calls.respond(input); }
            @Override public boolean prefetchScreen() { return !probe; }
            @Override public void close() { session.close(); }
        };
    }
    private static PhoneAgent.Reply phone(OpenAiAccount account, String model, String clock, JSONArray input, String effort,
            Runnable onActivity, AtomicReference<Runnable> cancel, Runnable guard, String forcedChoice, boolean plans, PhoneSession session, String speed) throws Exception {
        boolean refreshed = false; String level = account.reasoningProfile(model).resolve(effort);
        String required = forcedChoice != null ? forcedChoice : OpenAiProtocol.hasToolOutput(input) || PhoneAgent.hasObservation(input) ? null : "phone_screen";
        Function<JSONArray, JSONObject> payloadFor = items -> {
            JSONObject payload = plans ? PhonePlans.request(model, clock, items, level, required) : OpenAiProtocol.phoneRequest(model, clock, items, level);
            if (!plans && forcedChoice != null) try { payload.put("tool_choice", "none".equals(forcedChoice) ? "none" : new JSONObject().put("type", "function").put("name", forcedChoice)); }
            catch (Exception invalid) { throw new IllegalStateException(invalid); }
            GptSpeed.apply(payload, account.describe().optString("mode"), model, account.models(), speed);
            return payload;
        };
        while (true) {
            guard.run();
            OpenAiProtocol.Stream stream;
            try { stream = session.read(payloadFor, input, plans + ":" + model + ":" + level + ":" + speed); }
            catch (ResponseSocket.ApiFailure refused) {
                if (refused.status == 401 && !refreshed && account.usesPlan()) { refreshed = true; session.resetSocket(); account.expire(); continue; }
                if (GptSpeed.rejected(refused.status, refused.body)) throw new IllegalStateException("선택한 GPT 속도가 이 연결에서 거부됐어요. Standard로 바꾼 뒤 다시 시도해 주세요.");
                if (!level.isEmpty() && Reasoning.rejected(refused.status, refused.body)) throw new IllegalStateException(Reasoning.rejection(model, level));
                throw new IllegalStateException(OpenAiProtocol.explain(refused.status, OpenAiProtocol.message(refused.body, "")));
            }
            if (stream == null) {
                byte[] body = payloadFor.apply(input).toString().getBytes(StandardCharsets.UTF_8);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Bearer " + account.bearer());
                headers.put("Content-Type", "application/json"); headers.put("Accept", "text/event-stream"); guard.run();
                try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", OpenAiProtocol.RESPONSES, headers, body, 120_000, cancel)) {
                    guard.run();
                    if (response.status == 401 && !refreshed && account.usesPlan()) { refreshed = true; account.expire(); continue; }
                    if (response.status >= 300) {
                        String raw = OpenAiAccount.Http.read(response.body, 64 * 1024);
                        JSONObject error; try { error = new JSONObject(raw); } catch (Exception e) { error = new JSONObject(); }
                        if (GptSpeed.rejected(response.status, error)) throw new IllegalStateException("선택한 GPT 속도가 이 연결에서 거부됐어요. Standard로 바꾼 뒤 다시 시도해 주세요.");
                        if (!level.isEmpty() && Reasoning.rejected(response.status, error)) throw new IllegalStateException(Reasoning.rejection(model, level));
                        throw new IllegalStateException(OpenAiProtocol.explain(response.status, OpenAiProtocol.message(error, "")));
                    }
                    stream = read(response, onActivity, guard);
                } finally { cancel.set(null); }
            }
            guard.run();
            PhoneAgent.Reply reply;
            if (plans) reply = PhonePlans.reply(stream.text(), required);
            else {
                reply = new PhoneAgent.Reply(stream.output(), stream.text());
                PhoneAgent.validateNativeReply(reply, input);
                if (required != null && !"none".equals(required)) try { PhoneAgent.requireCall(reply, required); }
                catch (PhoneAgent.MissingCall missing) { throw new PhoneAgent.MissingCall(missing.getMessage() + " · " + stream.diagnostic()); }
            }
            String speedNotice = GptSpeed.receivedTier(speed, new JSONObject().put("service_tier", stream.serviceTier()));
            if (!speedNotice.isBlank()) session.notice.accept(speedNotice);
            session.accept(stream, input, reply, plans + ":" + model + ":" + level + ":" + speed);
            return reply;
        }
    }
    /** Owns only model transport; HTTP recovery receives the full actual history and never replays local operations. */
    private static final class PhoneSession implements AutoCloseable {
        private final OpenAiAccount account;
        private final Runnable activity, guard;
        private final AtomicReference<Runnable> cancel;
        private final Consumer<String> notice;
        private final long accountGeneration;
        private final PhoneResponseChain chain = new PhoneResponseChain();
        private ResponseSocket socket;
        private boolean httpOnly;
        PhoneSession(OpenAiAccount account, Runnable activity, AtomicReference<Runnable> cancel, Runnable guard, Consumer<String> notice, long accountGeneration) {
            this.account = account; this.activity = activity; this.cancel = cancel; this.guard = guard; this.notice = notice; this.accountGeneration = accountGeneration;
        }
        void check() {
            guard.run();
            if (account.phoneFormatGeneration() != accountGeneration) throw new IllegalStateException("GPT 연결이 바뀌어 이전 폰 요청을 취소했어요.");
        }
        OpenAiProtocol.Stream read(Function<JSONArray, JSONObject> payload, JSONArray input, String format) throws Exception {
            check();
            if (httpOnly || !account.phoneSocketAllowed()) { httpOnly = true; return null; }
            try {
                if (socket == null) {
                    Map<String, String> headers = new LinkedHashMap<>(); headers.put("Authorization", "Bearer " + account.bearer());
                    socket = new ResponseSocket(URI.create("wss://api.openai.com/v1/responses"), headers, cancel, activity, this::check);
                    socket.connect(); check(); notice.accept("→ GPT 실시간 연결 · 작업 중 연결 재사용");
                }
                int offset = chain.offset(input, format);
                JSONObject request = chain.request(payload.apply(PhoneResponseChain.slice(input, offset)));
                try {
                    OpenAiProtocol.Stream result = socket.exchange(request);
                    if (offset > 0) notice.accept("→ GPT 이어받기 · 새 입력 " + (input.length() - offset) + "개");
                    return result;
                } catch (ResponseSocket.ApiFailure refused) {
                    if (!refused.previousMissing()) throw refused;
                    check(); chain.reset(); notice.accept("→ GPT 이어받기 복구 · 실제 작업 맥락 재전송");
                    return socket.exchange(chain.request(payload.apply(input)));
                }
            } catch (ResponseSocket.Unavailable unavailable) {
                check(); account.phoneSocketUnavailable(accountGeneration); resetSocket(); httpOnly = true;
                notice.accept("→ GPT 일반 연결 사용 · 실시간 연결을 사용할 수 없어요"); return null;
            } catch (ResponseSocket.ApiFailure refused) {
                if (!refused.transportUnsupported()) throw refused;
                check(); account.phoneSocketUnavailable(accountGeneration); resetSocket(); httpOnly = true;
                notice.accept("→ GPT 일반 연결 사용 · 실시간 연결 미지원"); return null;
            }
        }
        void accept(OpenAiProtocol.Stream stream, JSONArray input, PhoneAgent.Reply reply, String format) {
            if (!httpOnly) chain.accept(stream.responseId(), input, reply, format);
        }
        void resetSocket() { if (socket != null) socket.close(); socket = null; chain.reset(); }
        @Override public void close() { resetSocket(); }
    }
    private static OpenAiProtocol.Stream read(OpenAiAccount.Http.Exchange response, Runnable onActivity, Runnable guard) throws Exception {
        return read(response, onActivity, guard, text -> {}, null);
    }
    /** onThinking, when given, hears the reasoning summary as it streams in; it never reaches onText. */
    private static OpenAiProtocol.Stream read(OpenAiAccount.Http.Exchange response, Runnable onActivity, Runnable guard, java.util.function.Consumer<String> onText,
            java.util.function.Consumer<String> onThinking) throws Exception {
        OpenAiProtocol.Stream stream = new OpenAiProtocol.Stream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                guard.run(); onActivity.run(); String delta = stream.line(line);
                String thought = stream.takeThinking(); if (onThinking != null && !thought.isEmpty()) onThinking.accept(thought);
                if (delta != null && !delta.isEmpty()) onText.accept(delta);
                if (stream.failure() != null || stream.completed()) break;
            }
        }
        // The last event may come without the blank line that ends it: it passes the guard like every line before it.
        String tail = stream.finish(), thought = stream.takeThinking();
        if ((tail != null && !tail.isEmpty()) || !thought.isEmpty()) guard.run();
        if (onThinking != null && !thought.isEmpty()) onThinking.accept(thought);
        if (tail != null && !tail.isEmpty()) onText.accept(tail);
        if (stream.failure() != null) throw new IllegalStateException(stream.failure());
        if (!stream.completed()) throw new IllegalStateException("ChatGPT 응답이 끝나기 전에 연결이 끊겼어요. 다시 시도해 주세요.");
        return stream;
    }
    /**
     * One short answer about one picture from the given model: no tools, no search, the picture sent once as a data URL. A
     * ChatGPT token the API rejects is refreshed once; an effort the model refuses is dropped for its own default once.
     */
    static String look(OpenAiAccount account, String model, String effort, String instructions, String question, String mime, byte[] picture,
            Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        if (model == null || model.isBlank()) throw new IllegalStateException("설정에서 ChatGPT 모델을 골라 주세요.");
        boolean refreshed = false; String level = effort == null ? "" : effort;
        while (true) {
            guard.run();
            byte[] body = OpenAiProtocol.pictureRequest(model, instructions, question, mime, picture, level).toString().getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + account.bearer());
            headers.put("Content-Type", "application/json"); headers.put("Accept", "text/event-stream");
            guard.run();
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", OpenAiProtocol.RESPONSES, headers, body, 120_000, cancel)) {
                if (response.status == 401 && !refreshed && account.usesPlan()) { refreshed = true; account.expire(); continue; }
                if (response.status >= 300) {
                    String detail = OpenAiAccount.Http.read(response.body, 64 * 1024);
                    JSONObject json; try { json = new JSONObject(detail); } catch (Exception e) { json = new JSONObject(); }
                    if (!level.isEmpty() && Reasoning.rejected(response.status, json)) { level = ""; continue; }
                    throw new IllegalStateException(OpenAiProtocol.explain(response.status, OpenAiProtocol.message(json, "")));
                }
                OpenAiProtocol.Stream stream = read(response, guard, guard);
                guard.run();
                return stream.text();
            } finally { if (cancel != null) cancel.set(null); }
        }
    }
    /**
     * Returns the full answer and its cited sources. onActivity runs for every received line so a long reasoning or search phase
     * is not mistaken for a stall. A ChatGPT token the API rejects is refreshed once and the request retried. A request refused
     * with web search is sent again without search, which is reported in the result. A rejected selected reasoning level is
     * surfaced instead of silently changing the user's configuration.
     */
    static Result complete(OpenAiAccount account, Function<Boolean, String> instructions, String input, boolean webSearch, Runnable onActivity, AtomicReference<Runnable> cancel) throws Exception {
        long generation = account.workFormatGeneration();
        String model = account.model(), speed = account.speed(model);
        Runnable capturedAccount = () -> {
            if (generation != account.workFormatGeneration()) throw new java.util.concurrent.CancellationException("GPT 연결이 바뀌어 이전 요청을 멈췄어요.");
            onActivity.run();
        };
        return complete(account, model, instructions, input, webSearch, account.effectiveReasoningEffort(model), speed, capturedAccount, cancel);
    }
    /** Captured model/effort pair: a queued job must not mix one model's saved effort with another model. */
    static Result complete(OpenAiAccount account, String model, Function<Boolean, String> instructions, String input, boolean webSearch, String effort, Runnable onActivity, AtomicReference<Runnable> cancel) throws Exception {
        return complete(account, model, instructions, input, webSearch, effort, "standard", onActivity, cancel);
    }
    static Result complete(OpenAiAccount account, String model, Function<Boolean, String> instructions, String input, boolean webSearch, String effort, String speed, Runnable onActivity, AtomicReference<Runnable> cancel) throws Exception {
        return complete(account, model, instructions, input, webSearch, effort, speed, onActivity, cancel, text -> {});
    }
    /** As complete, telling onText the answer as it streams in (누르고 말하기 reads it aloud meanwhile). */
    static Result complete(OpenAiAccount account, String model, Function<Boolean, String> instructions, String input, boolean webSearch, String effort, String speed, Runnable onActivity, AtomicReference<Runnable> cancel,
            java.util.function.Consumer<String> onText) throws Exception {
        return complete(account, model, instructions, input, webSearch, null, effort, speed, onActivity, cancel, onText);
    }
    /**
     * As above; onThinking, when given, asks a reasoning model for a readable summary of its reasoning too and hears it as it
     * streams in (the main chat's thinking summary). A request refused only for that summary throws MainRequest.SummaryRefused
     * before any answer arrived, so the caller can send it once more without it.
     */
    static Result complete(OpenAiAccount account, String model, Function<Boolean, String> instructions, String input, boolean webSearch, java.util.function.Consumer<String> onThinking, String effort, String speed, Runnable onActivity, AtomicReference<Runnable> cancel,
            java.util.function.Consumer<String> onText) throws Exception {
        return complete(account, model, instructions, input, webSearch, false, onThinking, effort, speed, onActivity, cancel, onText, null);
    }
    /**
     * As above; always forces the web search (the main chat's 검색: 항상, tool_choice "required"). A request refused only
     * for forcing it, before any work, is sent once more with the model choosing, search still on. cacheKey goes as
     * prompt_cache_key (the main chat's replies). The result carries what the request used and the limits the provider's
     * response headers reported.
     */
    static Result complete(OpenAiAccount account, String model, Function<Boolean, String> instructions, String input, boolean webSearch, boolean always, java.util.function.Consumer<String> onThinking, String effort, String speed, Runnable onActivity, AtomicReference<Runnable> cancel,
            java.util.function.Consumer<String> onText, String cacheKey) throws Exception {
        return complete(account, model, instructions, input, webSearch, always, onThinking, effort, speed, onActivity, cancel, onText, cacheKey, null);
    }
    /** The most rounds of the app's functions one main-chat answer takes; the next request asks for the answer (tool_choice "none"). */
    static final int MAIN_ROUNDS = 12;
    /**
     * As above, with the app's own functions when tools is given (the main chat's connected services, MainTools): a response
     * that calls one goes back with each result, round after round, and the last round's text is the answer. Its usage is
     * summed over the rounds and its sources gathered. Without tools the request is exactly the one above.
     */
    static Result complete(OpenAiAccount account, String model, Function<Boolean, String> instructions, String input, boolean webSearch, boolean always, java.util.function.Consumer<String> onThinking, String effort, String speed, Runnable onActivity, AtomicReference<Runnable> cancel,
            java.util.function.Consumer<String> onText, String cacheKey, WorkTools tools) throws Exception {
        return complete(account, model, instructions, input, webSearch, always, onThinking, effort, speed, onActivity, cancel, onText, cacheKey, tools, null);
    }
    /**
     * As above, with files: the photos and PDFs attached to the question (MainAttachments.parts, each with its base64 data),
     * sent with it as Responses content parts (OpenAiProtocol.conversation). Null or none sends the request above.
     */
    static Result complete(OpenAiAccount account, String model, Function<Boolean, String> instructions, String input, boolean webSearch, boolean always, java.util.function.Consumer<String> onThinking, String effort, String speed, Runnable onActivity, AtomicReference<Runnable> cancel,
            java.util.function.Consumer<String> onText, String cacheKey, WorkTools tools, JSONArray files) throws Exception {
        if (model.isBlank()) throw new IllegalStateException("설정에서 ChatGPT 모델을 골라 주세요.");
        boolean attached = files != null && files.length() > 0;
        JSONArray functions = tools == null ? null : tools.openAi(), conversation = attached ? OpenAiProtocol.conversation(dev.jun.gyeot.core.MainAttachments.turn(input, files))
            : tools == null ? null : new JSONArray().put(new JSONObject().put("role", "user").put("content", input));
        JSONArray found = new JSONArray(); dev.jun.gyeot.core.Usage used = null; int rounds = 0, repairs = 0; java.util.Set<String> answered = new java.util.HashSet<>();
        // A connection that answers a request with functions with nothing at all (a ChatGPT sign-in, as 작업창 found) goes on
        // in WorkPlans' text commands: one JSON command per reply, run by the app the same way.
        boolean plans = false, repair = false;
        boolean summary = onThinking != null, force = always;
        boolean search = webSearch, refreshed = false; String refusal = null, level = account.reasoningProfile(model).resolve(effort); Limits limits = Limits.NONE;
        long generation = account.workFormatGeneration();
        // Browsing and saving many files takes more rounds than a lookup does.
        int limit = tools != null && tools.rounds() > 0 ? tools.rounds() : MAIN_ROUNDS;
        Runnable guard = () -> {
            if (generation != account.workFormatGeneration()) throw new java.util.concurrent.CancellationException("GPT 연결이 바뀌어 이전 요청을 멈췄어요.");
            onActivity.run();
        };
        while (true) {
            guard.run();
            // The search is forced on the first round only: later rounds answer from what the functions returned.
            JSONObject payload;
            if (plans) payload = dev.jun.gyeot.core.WorkPlans.request(model, instructions.apply(false) + tools.instructions()
                + (rounds >= limit ? " You have used the functions enough: return tool=finish with your answer now." : "")
                + (repair ? " Your last reply was not one valid JSON command, so nothing from it ran. Return exactly one valid JSON command." : ""), conversation, functions, level, null);
            else {
                payload = OpenAiProtocol.request(model, instructions.apply(search) + (tools == null ? "" : tools.instructions()), input, search, level, summary, force && rounds == 0, cacheKey);
                if (tools != null) OpenAiProtocol.functions(payload, conversation, functions, rounds >= limit);
                else if (attached) payload.put("input", conversation);
            }
            GptSpeed.apply(payload, account.describe().optString("mode"), model, account.models(), speed);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + account.bearer());
            headers.put("Content-Type", "application/json"); headers.put("Accept", "text/event-stream");
            guard.run();
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", OpenAiProtocol.RESPONSES, headers, body, 120_000, cancel)) {
                Limits seen = Limits.headers(response.headers, System.currentTimeMillis()); if (!seen.empty()) limits = seen;
                if (response.status == 401 && !refreshed && account.usesPlan()) { refreshed = true; account.expire(); continue; }
                if (response.status >= 300) {
                    String detail = OpenAiAccount.Http.read(response.body, 64 * 1024);
                    org.json.JSONObject json; try { json = new org.json.JSONObject(detail); } catch (Exception e) { json = new org.json.JSONObject(); }
                    String message = OpenAiProtocol.message(json, "");
                    // Sent again from the start by MainAdapters.send, so only before any function ran (nothing runs twice).
                    if (summary && rounds == 0 && OpenAiProtocol.summaryRefused(response.status, json)) throw new dev.jun.gyeot.core.MainRequest.SummaryRefused();
                    // Refused before any work for forcing the search alone ("tool_choice", "Tool choice"): once more, the
                    // model choosing, search still on. That is no refusal of search, so nothing is kept.
                    if (force && search && OpenAiProtocol.choiceRefused(response.status, json)) { force = false; continue; }
                    if (GptSpeed.rejected(response.status, json)) throw new IllegalStateException("선택한 GPT 속도가 이 연결에서 거부됐어요. 속도를 Standard로 바꾸거나 연결의 지원 여부를 확인해 주세요.");
                    // A request refusal (not sign-in or usage limits) that names the web search tool, even beside a reasoning
                    // level it cannot take: answered without search rather than not at all, before any work, so nothing paid
                    // repeats. Read before the reasoning check, which would fail the reply.
                    if (search && OpenAiProtocol.searchRefused(response.status, json)) { search = false; refusal = message.isBlank() ? "HTTP " + response.status : message; continue; }
                    if (!level.isEmpty() && Reasoning.rejected(response.status, json)) throw new IllegalStateException(Reasoning.rejection(model, level));
                    // A used-up limit says so in its headers too; it travels with the same message for 설정 → 사용량 (MainAdapters.send).
                    if (response.status == 429 || response.status == 402) throw new dev.jun.gyeot.core.ModelLimit(response.status, OpenAiProtocol.explain(response.status, message), limits);
                    throw new IllegalStateException(OpenAiProtocol.explain(response.status, message));
                }
                // A command is not prose: it never reaches the screen.
                OpenAiProtocol.Stream stream = read(response, guard, guard, plans ? text -> {} : onText, plans ? null : onThinking);
                guard.run();
                used = dev.jun.gyeot.core.Usage.sum(used, stream.usage());
                for (int i = 0; i < stream.sources().length(); i++) found.put(stream.sources().opt(i));
                JSONArray output = stream.output(), calls = new JSONArray();
                String tier = GptSpeed.receivedTier(speed, new JSONObject().put("service_tier", stream.serviceTier()));
                if (plans) {
                    dev.jun.gyeot.core.WorkPlans.Reply command;
                    try { command = dev.jun.gyeot.core.WorkPlans.reply(stream.text(), functions, null); repair = false; }
                    catch (IllegalStateException invalid) {
                        if (repairs++ >= 2) throw invalid;
                        repair = true;
                        if (!stream.text().isBlank()) conversation.put(new JSONObject().put("role", "assistant").put("content", dev.jun.gyeot.core.GitHubFunctions.clip(stream.text())));
                        continue;
                    }
                    if (!command.text().isEmpty()) { onText.accept(command.text()); return new Result(command.text(), found, refusal, tier, used, limits); }
                    output = command.output();
                }
                for (int i = 0; tools != null && i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    if (item != null && "function_call".equals(item.optString("type"))) calls.put(item);
                }
                if (tools != null && !plans && calls.length() == 0 && stream.text().isBlank() && !stream.refused()) { plans = true; continue; }
                if (calls.length() == 0) return new Result(stream.text(), found, refusal, tier, used, limits);
                // The response goes back as it came (its reasoning encrypted), then each call's result; what it wrote
                // before the calls stays on screen, and the next round's text is the answer.
                rounds++;
                if (!plans && !stream.text().isBlank()) onText.accept("\n\n");
                for (int i = 0; i < output.length(); i++) if (output.optJSONObject(i) != null) conversation.put(output.optJSONObject(i));
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i); String id = call.optString("call_id"), name = call.optString("name");
                    if (id.isBlank() || !answered.add(id)) throw new IllegalStateException("ChatGPT의 도구 요청이 중복되거나 올바르지 않아 멈췄어요.");
                    guard.run();
                    JSONObject result = tools.supports(name) ? tools.run(name, call.optString("arguments")) : dev.jun.gyeot.core.GitHubFunctions.failure("이 대화에 없는 도구예요: " + name);
                    guard.run();
                    conversation.put(new JSONObject().put("type", "function_call_output").put("call_id", id).put("output", dev.jun.gyeot.core.GitHubFunctions.clip(result.toString())));
                }
            } finally { cancel.set(null); }
        }
    }
}

