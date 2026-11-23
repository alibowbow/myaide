package dev.jun.gyeot;

import dev.jun.gyeot.core.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.json.*;

/**
 * Provider-independent dispatch, with account identities captured once per work turn. Every turn that answers is counted in
 * the usage ledger under the surface the run came from (작업창 or 예약).
 */
final class TeamModels {
    private final AppStore store;
    private final String grokAccount, claudeKey, geminiKey, surface;
    private final long nimGeneration, gptGeneration;
    TeamModels(AppStore store) throws Exception { this(store, UsageLedger.WORK); }
    TeamModels(AppStore store, String surface) throws Exception {
        this.store = store; this.surface = surface; grokAccount = store.grok.accountId(); nimGeneration = store.nim.generation(); gptGeneration = store.openai.workFormatGeneration();
        claudeKey = store.claude.connected() ? key(store.claude::key) : "";
        geminiKey = store.gemini.connected() ? key(store.gemini::key) : "";
    }
    private static String key(java.util.concurrent.Callable<String> read) { try { return read.call(); } catch (Exception e) { return ""; } }
    /**
     * 작업창 on Antigravity: agy as an agent with the room's own tools over MCP (AgyRunner), its words streamed. The room
     * keeps one agy conversation (--conversation): a turn sends only the messages since agy last answered, and a changed
     * room (another model, an edited history) starts a new one with the whole conversation.
     */
    private WorkClient.Result antigravity(String model, String effort, String day, JSONArray turns, String context, WorkTools tools, Consumer<String> pieces, Consumer<String> stage, Runnable guard, java.util.concurrent.atomic.AtomicReference<Runnable> cancel) throws Exception {
        int count = turns == null ? 0 : turns.length();
        String first = count == 0 ? "" : Integer.toHexString(turns.optJSONObject(0) == null ? 0 : turns.optJSONObject(0).optString("content").hashCode());
        String key = surface + ":" + first;
        AntigravityAccount.Session kept = store.antigravity.session(key);
        boolean resume = kept != null && !kept.id().isEmpty() && kept.model().equals(model) && kept.sent() > 0 && kept.sent() < count;
        StringBuilder talk = new StringBuilder();
        for (int i = resume ? kept.sent() : 0; i < count; i++) {
            JSONObject turn = turns.optJSONObject(i); if (turn == null || turn.optString("content").isBlank()) continue;
            talk.append("assistant".equals(turn.optString("role")) ? "AI: " : "사용자: ").append(turn.optString("content").strip()).append("\n\n");
        }
        String prompt;
        if (resume) prompt = "(이어지는 대화)\n" + talk.toString().strip();
        else prompt = WorkBook.instructions(day, false, false, WorkBook.ANTIGRAVITY, false, false, WorkBook.names(tools == null ? null : tools.declarations()))
            + (tools == null ? "" : tools.instructions()) + (context == null || context.isBlank() ? "" : "\n\n" + context)
            + "\n\n아래 대화에 이어 사용자의 마지막 말에 답해." + (tools == null ? " 이 방에는 앱의 도구가 없어요." : " 앱의 일(GitHub·파일·페이지 등)은 gyeot 도구로만 해.")
            + "\n\n" + talk.toString().strip();
        stage.accept("Antigravity가 답하는 중");
        guard.run();
        AgyRunner.Result r;
        try { r = AgyRunner.run(store.antigravity.context(), model, effort, resume ? kept.id() : "", prompt, tools, pieces, stage, guard, cancel, 900_000); }
        catch (Exception e) { if (resume) store.antigravity.remember(key, null); throw e; }
        // The answer joins the room as the next turn: agy has read up to and including it.
        if (!r.conversation().isEmpty()) store.antigravity.remember(key, new AntigravityAccount.Session(r.conversation(), count + 1, first, model));
        return new WorkClient.Result(r.text(), new JSONArray(), new JSONArray(), "", "", new JSONArray(), "", store.antigravity.label(model));
    }
    void check(String engine) {
        if (!store.ready(engine)) throw new CancellationException(WorkBook.author(engine) + " 연결이 해제돼 작업을 멈췄어요.");
        try {
            switch (engine) {
                case WorkBook.CHATGPT -> { if (gptGeneration != store.openai.workFormatGeneration()) throw new CancellationException(); }
                case WorkBook.GROK -> store.grok.check(grokAccount);
                case WorkBook.NIM -> store.nim.check(nimGeneration);
                case WorkBook.ANTIGRAVITY -> { if (!store.antigravity.connected()) throw new CancellationException(); }
                case WorkBook.CLAUDE -> { if (!claudeKey.equals(store.claude.key())) throw new CancellationException(); }
                case WorkBook.GEMINI -> { if (!geminiKey.equals(store.gemini.key())) throw new CancellationException(); }
                default -> throw new IllegalArgumentException("연결되지 않은 제공자입니다.");
            }
        } catch (Exception e) { throw new CancellationException("모델 계정 연결이 변경돼 이전 작업을 멈췄어요."); }
    }
    WorkClient.Result run(JSONObject profile, String day, JSONArray turns, String context, boolean search, boolean code, boolean draw,
            WorkTools tools, String container, String cacheKey, WorkClient.Painter painter, Consumer<String> text, Consumer<String> stage,
            Runnable activity, GenerationPool.Control control) throws Exception {
        return run(profile, day, turns, context, search, code, draw, tools, container, cacheKey, painter, text, stage, activity, control, WorkSteering.NONE);
    }
    WorkClient.Result run(JSONObject profile, String day, JSONArray turns, String context, boolean search, boolean code, boolean draw,
            WorkTools tools, String container, String cacheKey, WorkClient.Painter painter, Consumer<String> text, Consumer<String> stage,
            Runnable activity, GenerationPool.Control control, WorkSteering steering) throws Exception {
        String engine = profile.optString("engine"), model = profile.optString("model"), effort = profile.optString("effort");
        Runnable guard = () -> { control.check(); check(engine); GptSpeed.requireConsent(profile, store.openai.speedEpoch()); activity.run(); };
        guard.run();
        // The connection this turn goes out on, and the time to its first piece of answer (the answer's foot shows it).
        String identity = store.usageIdentity(engine), route = store.usageRoute(engine);
        long sent = System.currentTimeMillis(); long[] first = {0};
        Consumer<String> pieces = delta -> { if (first[0] == 0 && delta != null && !delta.isEmpty()) first[0] = Math.max(1, System.currentTimeMillis() - sent); text.accept(delta); };
        WorkClient.Result result;
        try {
            result = switch (engine) {
                case WorkBook.CLAUDE -> ClaudeClient.work(store.claude, model, day, turns, context, search, code, tools, container, effort, pieces, stage, guard, control.request, steering, !profile.optBoolean("strictModel"));
                case WorkBook.GEMINI -> GeminiWorkClient.work(geminiKey, model, day, turns, context, search, code, tools, effort, pieces, stage, guard, control.request, steering);
                case WorkBook.GROK -> GrokWorkClient.work(store.grok, grokAccount, model, day, turns, context, search, code, tools, effort, cacheKey, pieces, stage, guard, control.request, steering);
                case WorkBook.NIM -> NimClient.work(store.nim, nimGeneration, model, day, turns, context, tools, pieces, stage, guard, control.request, steering);
                case WorkBook.ANTIGRAVITY -> antigravity(model, effort, day, turns, context, tools, pieces, stage, guard, control.request);
                default -> WorkClient.run(store.openai, model, day, turns, context, search, code, draw, tools, container, effort, painter, pieces, stage, guard, control.request, steering, profile.optString("speed", "standard"));
            };
        } catch (dev.jun.gyeot.core.ModelLimit refused) {
            // A used-up limit is kept like an answer's (설정 → 사용량 shows it reached), but the refused turn is no usage row.
            if (!refused.limits.empty()) store.usage.limits(engine, route, identity, refused.limits);
            throw refused;
        }
        store.usage.record(new UsageLedger.Row(System.currentTimeMillis(), surface, engine, result.served.isBlank() ? model : result.served, route, identity, result.usage));
        if (!result.limits.empty()) store.usage.limits(engine, route, identity, result.limits);
        return result.first(first[0]);
    }
}
