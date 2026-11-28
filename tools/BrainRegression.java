package dev.jun.gyeot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 두뇌 배분 (core/BrainRoutes, Fallback, LocalAnswers, Prices): which failures another model may take over and which never
 * (a stubbed 429 goes to the next model, a stop and a content refusal do not, two failures are never a third try), the
 * note the answer carries, the routing table's chains, the answers made on the phone with no model (and that each agrees
 * with 퍼레 자동 점검's scenarios), and what the ledger's tokens cost. Run by tools/build.py.
 */
final class BrainRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }
    /** Friday 2026-11-27, 15:05 in Seoul. */
    static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 27, 15, 5, 0, 0, ZoneId.of("Asia/Seoul"));

    public static void main(String[] args) throws Exception {
        why();
        once();
        notes();
        chains();
        local();
        scenarios(args.length > 0 ? Path.of(args[0]) : Path.of("app/src/main/assets/buddy/scenarios.json"));
        prices();
        foot();
        System.out.println("Brain routing regression checks passed: " + checked);
    }

    // ── which failures another model may take over ──
    static void why() {
        same(Fallback.Why.LIMIT, Fallback.why(new ModelLimit(429)), "a 429 the client confirmed");
        same(Fallback.Why.LIMIT, Fallback.why(new ModelLimit(402)), "402 payment required");
        same(Fallback.Why.ACCOUNT, Fallback.why(new ProviderError(401, ClaudeProtocol.explain(401, "authentication_error", ""))), "401 with its status kept");
        same(Fallback.Why.ACCOUNT, Fallback.why(new ProviderError(403, "x")), "403");
        same(Fallback.Why.GONE, Fallback.why(new ProviderError(404, "x")), "404: the model removed");
        same(Fallback.Why.SERVER, Fallback.why(new ProviderError(503, "x")), "503");
        same(Fallback.Why.SERVER, Fallback.why(new ProviderError(529, "x")), "529 overloaded");
        same(Fallback.Why.TIMEOUT, Fallback.why(new ProviderError(504, "x")), "504");
        same(Fallback.Why.NONE, Fallback.why(new ProviderError(400, "x")), "400 is the request's fault, not the model's");
        same(Fallback.Why.TIMEOUT, Fallback.why(new java.net.SocketTimeoutException("Read timed out")), "a read timeout");
        same(Fallback.Why.TIMEOUT, Fallback.why(new IllegalStateException("wrapped", new java.net.SocketTimeoutException())), "a wrapped timeout");
        same(Fallback.Why.NONE, Fallback.why(new java.net.UnknownHostException("api.anthropic.com")), "offline: another company is as far away");
        // The clients' own Korean explanations, without a status: read from their words.
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(429, "rate_limit_error", ""))), "Claude's 429 words");
        same(Fallback.Why.SERVER, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(529, "overloaded_error", ""))), "Claude's 529 words");
        same(Fallback.Why.ACCOUNT, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(401, "authentication_error", ""))), "Claude's 401 words");
        same(Fallback.Why.GONE, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(404, "not_found_error", ""))), "Claude's 404 words");
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(400, "invalid_request_error", "Your credit balance is too low"))), "no credit");
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException(NimProtocol.error(429))), "NVIDIA's 429 words");
        same(Fallback.Why.SERVER, Fallback.why(new IllegalStateException(NimProtocol.error(503))), "NVIDIA's 503 words");
        same(Fallback.Why.ACCOUNT, Fallback.why(new IllegalStateException(NimProtocol.error(401))), "NVIDIA's 401 words");
        same(Fallback.Why.SERVER, Fallback.why(new java.io.IOException("Gemini 요청 오류 HTTP 503 (The model is overloaded). 도구·모델은 바꾸지 않았어요.")), "Gemini's own line");
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException("Groq 무료 한도를 기다리는 중이에요.")), "Groq waiting for its free tier");
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException(OpenAiProtocol.explain(429, ""))), "ChatGPT's limit words");
        same(Fallback.Why.ACCOUNT, Fallback.why(new IllegalStateException(OpenAiProtocol.explain(401, ""))), "ChatGPT's sign-in expired");
        same(Fallback.Why.NO_KEY, Fallback.why(new IllegalStateException("설정 → Gemini API 키에서 키를 먼저 넣어 주세요.")), "no key");
        // Never handed over.
        same(Fallback.Why.NONE, Fallback.why(new CancellationException("stopped")), "the user's stop");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("x", new CancellationException())), "a stop inside");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("HTTP 429", new InterruptedException())), "an interrupt wins over its status");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException(ClaudeProtocol.refusal("cyber"))), "Claude declined the request");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("NIM 모델이 이 요청에 대한 답변을 거절했어요.")), "NVIDIA declined it");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("Response blocked by SAFETY settings")), "a safety block");
        same(Fallback.Why.NONE, Fallback.why(new IllegalArgumentException("메인 대화의 NVIDIA NIM 연결에서는 쓸 수 없어요: 웹 검색.")), "an option the engine cannot carry");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("모델이 빈 답변을 보냈어요.")), "an empty answer is not recognised");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException(ContextWindow.refusal("Claude", "prompt is too long: 250000 tokens > 200000 maximum"))), "too long: the history is cut instead");
        check(Fallback.refused(new IllegalStateException(ClaudeProtocol.refusal(""))), "a refusal is a refusal");
        check(!Fallback.refused(new ProviderError(429, ClaudeProtocol.explain(429, "rate_limit_error", ""))), "a limit is not a refusal");
        check(Fallback.cancelled(new IllegalStateException("x", new CancellationException())), "a stop is found inside");
    }

    // ── the one hop ──
    record Brain(String who, String label) {}
    static final Brain CLAUDE = new Brain("Claude", "Claude Opus 5.5"), GPT = new Brain("ChatGPT", "GPT-6.1 Terra");
    interface Answering { String answer(Brain b) throws Exception; }
    static Fallback.Answer<Brain, String> ask(List<Brain> chain, AtomicInteger tries, Answering answer, boolean fresh, Runnable guard) throws Exception {
        return Fallback.once(chain, Brain::who, Brain::label, b -> { tries.incrementAndGet(); return answer.answer(b); }, () -> fresh, guard);
    }
    static void once() throws Exception {
        List<Brain> both = List.of(CLAUDE, GPT);
        // Fails first: a stubbed 429 on Claude goes to GPT, once, and the note says so.
        AtomicInteger tries = new AtomicInteger();
        Fallback.Answer<Brain, String> a = ask(both, tries, b -> { if (b == CLAUDE) throw new ModelLimit(429); return "GPT의 답"; }, true, () -> {});
        same("GPT의 답", a.value(), "the next model answered"); same(GPT, a.used(), "who answered"); same(2, tries.get(), "two tries");
        same("Claude 한도 → GPT-6.1 Terra로 답했어요", a.note(), "the note"); check(a.hopped(), "hopped");
        // The chosen one answering: no hop, no note.
        tries.set(0); a = ask(both, tries, b -> "Claude의 답", true, () -> {});
        same(CLAUDE, a.used(), "first answered"); same("", a.note(), "no note"); same(1, tries.get(), "one try");
        // A stop is never handed on.
        tries.set(0);
        try { ask(both, tries, b -> { throw new CancellationException("사용자가 멈춤"); }, true, () -> {}); check(false, "a stop must be thrown"); }
        catch (CancellationException stopped) { same(1, tries.get(), "a stop: one try"); }
        // Nor a content refusal.
        tries.set(0);
        try { ask(both, tries, b -> { throw new IllegalStateException(ClaudeProtocol.refusal("bio")); }, true, () -> {}); check(false, "a refusal must be thrown"); }
        catch (IllegalStateException refused) { same(1, tries.get(), "a refusal: one try"); check(refused.getMessage().contains("답하지 않았어요"), "the refusal itself is told"); }
        // Never a loop: both failing is two tries and one error naming both.
        tries.set(0);
        try { ask(List.of(CLAUDE, GPT, new Brain("Grok", "Grok 4.7")), tries, b -> { throw new ModelLimit(429); }, true, () -> {}); check(false, "two failures must be thrown"); }
        catch (IllegalStateException both429) {
            same(2, tries.get(), "no third try"); check(both429.getMessage().startsWith("Claude 한도 → GPT-6.1 Terra도 답하지 못했어요"), "names both: " + both429.getMessage());
            same(1, both429.getSuppressed().length, "the first failure kept");
        }
        // Words already reached the user (or a tool ran): not sent again.
        tries.set(0);
        try { ask(both, tries, b -> { throw new ProviderError(503, "x"); }, false, () -> {}); check(false, "must be thrown"); }
        catch (ProviderError kept) { same(1, tries.get(), "nothing resent once words were shown"); }
        // Stopped between the two: the guard throws and the second is never asked.
        tries.set(0);
        try { ask(both, tries, b -> { throw new ProviderError(503, "x"); }, true, () -> { throw new CancellationException(); }); check(false, "must be thrown"); }
        catch (CancellationException stopped) { same(1, tries.get(), "stopped before the hop"); }
        // Only one model: the failure stands.
        tries.set(0);
        try { ask(List.of(CLAUDE), tries, b -> { throw new ModelLimit(429); }, true, () -> {}); check(false, "must be thrown"); }
        catch (ModelLimit alone) { same(1, tries.get(), "alone: one try"); }
        // The second stopped by the user is a stop, not "both failed".
        tries.set(0);
        try { ask(both, tries, b -> { if (b == CLAUDE) throw new ProviderError(500, "x"); throw new CancellationException(); }, true, () -> {}); check(false, "must be thrown"); }
        catch (CancellationException stopped) { same(2, tries.get(), "the stop of the second"); }
    }
    static void notes() {
        same("Claude 서버 오류 → GPT-6으로 답했어요", Fallback.note(Fallback.Why.SERVER, "Claude", "GPT-6"), "6 is 육: 으로");
        same("ChatGPT 계정 거절 → Claude Sonnet 5.5로 답했어요", Fallback.note(Fallback.Why.ACCOUNT, "ChatGPT", "Claude Sonnet 5.5"), "5 is 오: 로");
        same("Grok 4.7로", Fallback.euro("Grok 4.7"), "7 is 칠: 로");
        same("Grok으로", Fallback.euro("Grok"), "그록: 으로");
        same("Nemotron Ultra로", Fallback.euro("Nemotron Ultra"), "울트라: 로");
        same("GPT OSS 120B로", Fallback.euro("GPT OSS 120B"), "비: 로");
        same("Qwen으로", Fallback.euro("Qwen"), "큐웬: 으로");
        same("Gemini 3으로", Fallback.euro("Gemini 3"), "삼: 으로");
        same("클로드로", Fallback.euro("클로드"), "Hangul without a final");
        same("그록으로", Fallback.euro("그록"), "Hangul with one");
        same("서울로", Fallback.euro("서울"), "ㄹ: 로");
        same("ChatGPT로", Fallback.euro("ChatGPT"), "티: 로");
        same("Claude Sonnet으로", Fallback.euro("Claude Sonnet"), "소넷: 으로");
    }

    // ── the routing table ──
    static void chains() {
        java.util.function.Predicate<String> all = e -> true;
        same(List.of(WorkBook.CLAUDE, WorkBook.CHATGPT), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CLAUDE, true, all), "Claude → ChatGPT");
        same(List.of(WorkBook.CHATGPT, WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CHATGPT, true, all), "ChatGPT → Claude");
        same(List.of(WorkBook.GEMINI, WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.GEMINI, true, all), "a chosen Gemini still answers first");
        same(List.of(WorkBook.GROQ, WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.GROQ, true, all), "a chosen Groq too");
        same(List.of(WorkBook.CLAUDE, WorkBook.NIM), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CLAUDE, true, e -> e.equals(WorkBook.NIM) || e.equals(WorkBook.GEMINI)), "only NVIDIA connected: Gemini never chosen for the user");
        same(List.of(WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CLAUDE, true, e -> e.equals(WorkBook.GEMINI) || e.equals(WorkBook.GROQ) || e.equals(WorkBook.ANTIGRAVITY)), "Gemini, Groq, Antigravity never take over");
        same(List.of(WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CLAUDE, false, all), "turned off: no hop");
        same(List.of(WorkBook.GROQ), BrainRoutes.chain(BrainRoutes.Kind.TALK, WorkBook.GROQ, true, all), "TALK keeps its own race");
        same(List.of(), BrainRoutes.chain(BrainRoutes.Kind.WORK, "", true, all), "nothing chosen");
        for (String chosen : List.of(WorkBook.CLAUDE, WorkBook.CHATGPT, WorkBook.GROK, WorkBook.NIM, WorkBook.GEMINI, WorkBook.GROQ, WorkBook.ANTIGRAVITY))
            check(BrainRoutes.chain(BrainRoutes.Kind.WORK, chosen, true, all).size() <= 2, "one hop at most from " + chosen);
        // 메인 대화's decision to hand a failed turn over (MainChatEngine.handoff).
        same(Fallback.Why.LIMIT, BrainRoutes.hop(new ModelLimit(429), true, false, false, false), "a stubbed 429: the next model");
        same(Fallback.Why.NONE, BrainRoutes.hop(new CancellationException(), true, false, false, false), "a stop: never");
        same(Fallback.Why.NONE, BrainRoutes.hop(new IllegalStateException(ClaudeProtocol.refusal("cyber")), true, false, false, false), "a content refusal: never");
        same(Fallback.Why.NONE, BrainRoutes.hop(new ModelLimit(429), true, false, false, true), "already handed over once: never a loop");
        same(Fallback.Why.NONE, BrainRoutes.hop(new ModelLimit(429), true, true, false, false), "words already shown: not sent again");
        same(Fallback.Why.NONE, BrainRoutes.hop(new ModelLimit(429), true, false, true, false), "a function already ran: not sent again");
        same(Fallback.Why.NONE, BrainRoutes.hop(new ModelLimit(429), false, false, false, false), "the user turned it off");
        same(Fallback.Why.SERVER, BrainRoutes.hop(new ProviderError(503, "x"), true, false, false, false), "a 503");
        same(BrainRoutes.Kind.TALK, BrainRoutes.kind("buddy"), "퍼레 is talk");
        same(BrainRoutes.Kind.LIGHT, BrainRoutes.kind("chores"), "잡일 is light");
        same(BrainRoutes.Kind.WORK, BrainRoutes.kind("main"), "메인 대화 is work");
        same(BrainRoutes.Kind.WORK, BrainRoutes.kind("browser"), "the browser is work");
        same(BrainRoutes.Kind.DEVICE, BrainRoutes.kind("device"), "기기 안");
        same(List.of("groq", "gemma", "lite"), BrainRoutes.LIGHT_ORDER, "잡일: Groq first, the user's rule about Gemini's free tier");
        same("퍼레", BrainRoutes.surfaceLabel("buddy"), "a surface in words");
        check(!BrainRoutes.WORK_FALLBACK.contains(WorkBook.GEMINI), "Gemini is never chosen for the user");
    }

    // ── 기기 안 ──
    static LocalAnswers.Answer at(String said) { return LocalAnswers.of(said, NOW, null); }
    static void local() {
        same("지금 오후 3:05야.", at("지금 몇 시야").say(), "the time");
        same("time", at("퍼레야 몇 시야?").rule(), "with the name and a question mark");
        same("time", at("몇시").rule(), "short");
        same("오늘은 11월 27일 금요일이야.", at("오늘 며칠이야").say(), "the date");
        same("date", at("오늘 무슨 요일이야?").rule(), "the weekday");
        JSONObject timer = at("3분 타이머 맞춰 줘").act();
        same("timer", timer.optString("tool"), "a timer"); same(180L, timer.optLong("seconds"), "three minutes");
        same(5400L, at("타이머 1시간 30분 맞춰 줘").act().optLong("seconds"), "an hour and a half");
        same(10L, at("10초 타이머").act().optLong("seconds"), "ten seconds");
        same("coin", at("동전 던져 줘").act().optString("tool"), "a coin");
        same("dice", at("주사위 굴려 줘").act().optString("tool"), "a die");
        check(at("손전등 켜 줘").act().optBoolean("on"), "the torch on");
        check(!at("플래시 꺼 줘").act().optBoolean("on", true), "the torch off");
        JSONObject sum = at("12 곱하기 7은?").act();
        same("calculator", sum.optString("tool"), "a sum goes to the calculator tool"); same("12 곱하기 7", sum.optString("expression"), "its expression");
        same("35000원의 15퍼센트", at("35000원의 15퍼센트 계산해 줘").act().optString("expression"), "a percentage");
        same("(3+4)^2", at("(3+4)^2 얼마야").act().optString("expression"), "brackets");
        same("카카오톡", at("카카오톡 열어 줘").act().optString("app"), "an app by its name");
        same("유튜브", at("유튜브 꺼 줘").act().optString("app"), "an app closed by its name");
        LocalAnswers.Sky fresh = new LocalAnswers.Sky("사천읍", 12, "맑음", 15, 3, 5 * 60_000L);
        same("지금 사천읍 12도, 맑음이야. 오늘은 최고 15도, 최저 3도야.", LocalAnswers.of("오늘 날씨 어때", NOW, fresh).say(), "the weather kept");
        // Not these: the brain answers them.
        for (String talk : List.of("몇 시에 만날까?", "몇 시간 걸려?", "시간 있어?", "뉴욕은 지금 몇 시야", "크리스마스까지 며칠 남았어", "오늘 뭐 했지",
                "3분 타이머 말고 5분", "타이머 꺼 줘", "타이머 화면 보여 줘", "주사위 꺼내 줘", "동전 어디 있어", "손전등 어디 있어",
                "010-1234-5678", "2026-11-27", "3-2", "12시 30분", "1+1은 귀요미", "녹음 앱 켜 줘", "사진첩 열어 줘", "네이버 홈페이지 열어 줘",
                "방해 금지 설정 열어 줘", "지금 켜진 앱 꺼 줘", "음악 꺼 줘", "불 켜 줘", "유튜브 열고 아이유 틀어 줘", "오늘 기분 어때", "심심해",
                "지금 몇 시야 그리고 날씨도 알려 줘", "내일 아침 7시 30분에 알람 맞춰 줘"))
            check(at(talk) == null, "goes to the brain: " + talk + " → " + (at(talk) == null ? "" : at(talk).rule()));
        check(LocalAnswers.of("오늘 날씨 어때", NOW, null) == null, "no weather kept: the brain");
        check(LocalAnswers.of("오늘 날씨 어때", NOW, new LocalAnswers.Sky("", 12, "맑음", null, null, 31 * 60_000L)) == null, "stale weather: the brain");
        same("3-2", at("3-2는 얼마야").act().optString("expression"), "a bare minus asked as a sum");
    }
    /**
     * Every 퍼레 자동 점검 scenario (assets/buddy/scenarios.json): where 기기 안 answers it, it does the very tool expected, or
     * nothing when nothing is to be done. How many it answers is printed (the measure).
     */
    static void scenarios(Path file) throws Exception {
        JSONObject json = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        JSONArray all = json.getJSONArray("scenarios"); int here = 0; List<String> rules = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.getJSONObject(i), expect = s.getJSONObject("expect");
            if (!expect.has("tool") && !expect.optBoolean("none")) continue;
            LocalAnswers.Answer a = LocalAnswers.of(BuddyMind.nameHeard(s.getString("said")), NOW, null);
            if (a == null) continue;
            here++; rules.add(a.rule());
            if (expect.optBoolean("none")) { check(a.act() == null, "nothing to do, yet 기기 안 does " + a.act() + ": " + s.getString("said")); continue; }
            check(a.act() != null && expect.getString("tool").equals(a.act().optString("tool")), "기기 안 must do " + expect.getString("tool") + ": " + s.getString("said") + " → " + (a.act() == null ? a.rule() : a.act()));
            JSONObject want = expect.optJSONObject("args");
            for (java.util.Iterator<String> k = want == null ? java.util.Collections.<String>emptyIterator() : want.keys(); k.hasNext(); ) {
                String key = k.next(); Object w = want.get(key), g = a.act().opt(key);
                if (w instanceof Number n) check(g instanceof Number gn && gn.doubleValue() == n.doubleValue(), key + " of " + s.getString("said"));
                else if (w instanceof Boolean b) check(b.equals(g), key + " of " + s.getString("said"));
                else { boolean found = false; for (String alt : String.valueOf(w).split("\\|")) found |= String.valueOf(g).replace(" ", "").toLowerCase().contains(alt.replace(" ", "").toLowerCase()); check(found, key + " of " + s.getString("said") + ": " + g); }
            }
        }
        check(here >= 8, "기기 안 answers the scenarios it should: " + here + " " + rules);
        System.out.println("기기 안 answered " + here + " of the scenarios with a tool or nothing to do: " + rules);
        // 퍼레와 대화 sends the whole list of what it can do only when the words may ask for something (준: "퍼레 대화는 간단한 잡담인데
        // 설정 인풋이 많나보네"): every scenario with a thing to do goes with it at once (a miss would cost a second request).
        int acts = 0, plain = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.getJSONObject(i), expect = s.getJSONObject("expect");
            if (!expect.has("tool")) continue;
            acts++; check(BuddyMind.mayAct(BuddyMind.nameHeard(s.getString("said"))), "a thing to do taken for chitchat (the whole list would not go): " + s.getString("said"));
        }
        for (String talk : new String[]{"안녕", "뭐해?", "심심해", "오늘 너무 피곤하다", "고마워", "ㅋㅋㅋ 귀엽다", "잘 자", "사랑해", "배고프다", "오늘 날씨 좋네", "너는 누구야?", "기분이 좀 우울해"}) {
            plain++; check(!BuddyMind.mayAct(talk), "chitchat taken for a thing to do (the whole list would go): " + talk);
        }
        System.out.println("퍼레와 대화: " + acts + " things to do go with the whole list, " + plain + " chitchats with its one line");
    }

    // ── what it costs ──
    static Prices.Tokens tokens(String surface, String company, String model, String route, long requests, long priced, long in, long read, long write, long out) {
        return new Prices.Tokens(surface, company, model, route, requests, priced, in, read, write, out);
    }
    static void prices() {
        Prices.Price opus = Prices.of("claude-opus-5-5");
        check(Math.abs(Prices.usd(opus, 1_000_000, 1_000_000, 1_000_000, 1_000_000) - 29.2) < 1e-9, "Opus 5.5: $4 + $0.20 + $5 + $20");
        check(Math.abs(Prices.usd(Prices.of("gpt-6.1-terra"), 2_000_000, 0, 0, 1_000_000) - 7.0) < 1e-9, "GPT-6.1 Terra: 2 × $1 + $5");
        check(Math.abs(Prices.usd(Prices.of("models/gemini-3.5-flash-lite"), 1_000_000, 0, 0, 1_000_000) - 2.8) < 1e-9, "Flash-Lite, its models/ prefix read");
        check(Math.abs(Prices.usd(Prices.of("grok-4.7"), 0, 0, 500, 0) - 0.001) < 1e-9, "a cache write where none is priced counts as input");
        check(Prices.of("claude-mystery-9") == null, "an unknown model is 모름, never a guess");
        check(Prices.of("gemini-flash-latest") == null, "a -latest alias may point anywhere: 모름");
        same(Prices.Billing.PLAN, Prices.billing(WorkBook.CHATGPT, "plan", "gpt-6.1-terra", false, false), "ChatGPT sign-in: 구독");
        same(Prices.Billing.PAID, Prices.billing(WorkBook.CHATGPT, "key", "gpt-6.1-terra", false, false), "ChatGPT key: paid");
        same(Prices.Billing.PLAN, Prices.billing(WorkBook.GROK, "plan", "grok-4.7", false, false), "Grok sign-in: 구독");
        same(Prices.Billing.PLAN, Prices.billing(WorkBook.ANTIGRAVITY, "plan", "x", false, false), "Antigravity: 구독");
        same(Prices.Billing.PLAN, Prices.billing(WorkBook.CHATGPT, "codex", "x", false, false), "Codex sign-in: 구독");
        same(Prices.Billing.FREE, Prices.billing(WorkBook.NIM, "key", "nvidia/nemotron-3-ultra-550b-a55b", false, false), "NVIDIA: 무료");
        same(Prices.Billing.FREE, Prices.billing(WorkBook.GROQ, "key", "openai/gpt-oss-120b", false, false), "Groq free tier: 무료");
        same(Prices.Billing.FREE, Prices.billing(WorkBook.GEMINI, "key", "gemma-4-31b-it", false, false), "Gemma: 무료");
        same(Prices.Billing.FREE, Prices.billing(WorkBook.GEMINI, "key", "gemini-3.5-flash-lite", true, true), "a 무료 Gemini key");
        same(Prices.Billing.UNKNOWN, Prices.billing(WorkBook.GEMINI, "key", "gemini-3.5-flash-lite", false, false), "a Gemini key of unknown tier: 모름");
        same(Prices.Billing.PAID, Prices.billing(WorkBook.GEMINI, "key", "gemini-3.5-flash-lite", false, true), "a paid Gemini key");
        same(Prices.Billing.UNKNOWN, Prices.billing(WorkBook.CLAUDE, "key", "claude-mystery-9", false, false), "unknown Claude model");
        same(Prices.Billing.DEVICE, Prices.billing(BrainRoutes.DEVICE, BrainRoutes.LOCAL, "time", false, false), "기기 안");
        List<Prices.Tokens> groups = List.of(
            tokens("main", WorkBook.CLAUDE, "claude-opus-5-5", "key", 3, 2, 1_000_000, 1_000_000, 1_000_000, 1_000_000),
            tokens("buddy", WorkBook.CLAUDE, "claude-sonnet-5-5", "key", 1, 1, 1_000_000, 0, 0, 0),
            tokens("main", WorkBook.CHATGPT, "gpt-6.1-terra", "plan", 5, 5, 9, 9, 0, 9),
            tokens("buddy", WorkBook.GROQ, "qwen/qwen3-32b", "key", 4, 0, 0, 0, 0, 0),
            tokens("buddy", BrainRoutes.DEVICE, "time", BrainRoutes.LOCAL, 6, 6, 0, 0, 0, 0),
            tokens("chores", WorkBook.GEMINI, "gemini-flash-latest", "key", 2, 2, 100, 0, 0, 100));
        List<Prices.Spend> companies = Prices.spend(groups, true, false, true);
        Prices.Spend claude = companies.stream().filter(s -> s.key().equals(WorkBook.CLAUDE)).findFirst().orElseThrow();
        check(Math.abs(claude.usd() - 31.2) < 1e-9, "Claude: $29.20 + $2.00 = " + claude.usd());
        same(3L, claude.paid(), "Claude: three priced"); same(1L, claude.unknown(), "Claude: one told no tokens");
        same("$31.20 · 약 43,680원 · 1번은 모름", Prices.words(claude), "in words");
        same(WorkBook.CLAUDE, companies.get(0).key(), "the most money first");
        same(BrainRoutes.DEVICE, companies.get(1).key(), "then the most requests");
        Prices.Spend gpt = companies.stream().filter(s -> s.key().equals(WorkBook.CHATGPT)).findFirst().orElseThrow();
        same("구독", Prices.words(gpt), "a subscription is not money"); same(0.0, gpt.usd(), "no dollars for it");
        same("무료", Prices.words(companies.stream().filter(s -> s.key().equals(WorkBook.GROQ)).findFirst().orElseThrow()), "Groq free");
        same("모름", Prices.words(companies.stream().filter(s -> s.key().equals(WorkBook.GEMINI)).findFirst().orElseThrow()), "an alias: 모름");
        List<Prices.Spend> surfaces = Prices.spend(groups, false, false, true);
        Prices.Spend buddy = surfaces.stream().filter(s -> s.key().equals("buddy")).findFirst().orElseThrow();
        same("$2.00 · 약 2,800원 + 무료 10번", Prices.words(buddy), "퍼레: one paid answer and ten free (Groq and 기기 안)");
        same(11L, buddy.requests(), "퍼레's requests");
        same("$0.004 · 약 6원", Prices.dollars(0.004), "under a cent");
        same("$0", Prices.dollars(0), "nothing");
        same("1 미만", Prices.won(0.0001), "under a won");
        same("100만 토큰 $4/$20", Prices.tag(WorkBook.CLAUDE, "key", "claude-opus-5-5", false, true), "the picker's line");
        same("100만 토큰 $0.1/$0.5", Prices.tag(WorkBook.CLAUDE, "key", "claude-haiku-5-5", false, true), "a small price");
        same("구독", Prices.tag(WorkBook.CHATGPT, "plan", "gpt-6.1-terra", false, true), "a subscription");
        same("무료", Prices.tag(WorkBook.NIM, "key", "nvidia/nemotron-3-ultra-550b-a55b", false, true), "NVIDIA");
        same("요금 모름", Prices.tag(WorkBook.CLAUDE, "key", "claude-mystery-9", false, true), "unknown");
        same("무료 키 0원 · 유료 $0.3/$2.5", Prices.geminiTag("gemini-3.5-flash-lite"), "Gemini on the picker");
        same("요금 모름", Prices.geminiTag("gemini-flash-latest"), "an alias on the picker");
        for (Prices.Price p : Prices.table().values()) check(!p.source().isBlank() && Prices.CHECKED.equals(p.checked()), "every price says where and when it was read");
    }
    static void foot() {
        JSONObject saved = new Usage(10L, null, null, 5L, null, 15L).json(1100);
        Jsons.put(saved, "via", "Claude 한도 → GPT-6.1 Terra로 답했어요");
        same("Claude 한도 → GPT-6.1 Terra로 답했어요 · 첫 글자 1.1초 · 15 토큰", Usage.foot(saved), "the note leads the foot");
        same("Claude 한도 → GPT-6.1 Terra로 답했어요", Usage.via(saved.toString()), "read back from the column");
        same("", Usage.via("{\"first\":5}"), "no note");
        same("첫 글자 1.1초 · 15 토큰", Usage.foot(new Usage(10L, null, null, 5L, null, 15L).json(1100)), "an answer without one is as before");
    }
}
