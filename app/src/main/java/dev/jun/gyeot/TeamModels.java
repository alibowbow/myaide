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
    void check(String engine) {
        if (!store.ready(engine)) throw new CancellationException(WorkBook.author(engine) + " 연결이 해제돼 작업을 멈췄어요.");
        try {
            switch (engine) {
                case WorkBook.CHATGPT -> { if (gptGeneration != store.openai.workFormatGeneration()) throw new CancellationException(); }
                case WorkBook.GROK -> store.grok.check(grokAccount);
                case WorkBook.NIM -> store.nim.check(nimGeneration);
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
