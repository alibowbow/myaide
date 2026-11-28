package dev.jun.gyeot;

import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.PlanUsage;
import dev.jun.gyeot.core.Prices;
import dev.jun.gyeot.core.Usage;
import dev.jun.gyeot.core.WorkBook;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 설정 → 사용량 · 남은 한도: first 오늘 남은 양 (core/SpareQuota), a one-line row per free tier and subscription the app can call
 * with what it can still do today and when it fills again, and 남는 한도로 한 일 (SpareWork); then for each connected account what its provider last said is left of its limits,
 * "남은 72% · 14:30 초기화" and when it said so, or 모름, then what each company's text requests used in the last seven
 * days, from the usage ledger. What it shows is read off the main thread (load, read: the ledger and the connections'
 * identities), then drawn on it (fill). Opening it also asks the subscriptions' own usage pages (check,
 * core/PlanUsage): the Codex sign-in's 5-hour and weekly limits and Grok's weekly pool, at most every PlanUsage.AUTO_MS,
 * or at once from its 지금 다시 확인.
 */
final class UsageUi {
    static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    static final String[] ENGINES = {WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM, WorkBook.ANTIGRAVITY, WorkBook.GROQ};
    /** The settings list's line until the last seven days are read, and when there is nothing in them. */
    static final String ABOUT = "답마다 쓴 토큰·예상 비용과 계정별 남은 한도";
    /** One reader for the page and the list's line, whose thread ends after half a minute without work. */
    private static final ExecutorService READS = reader();
    /** The usage pages' reads, one at a time on their own thread, so a slow answer never holds the page's reads. */
    private static final ExecutorService CHECKS = reader("gyeot-usage-check");
    /** Per page (codex, grok): when it was last asked, and why the last read failed (absent when it did not). */
    private static final java.util.Map<String, Long> CHECKED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, String> PROBLEMS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger CHECK_STATE = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean checking;
    static final String CODEX = "codex", GROK_PAGE = "grok", AGY_PAGE = "antigravity";
    /** agy's /usage takes half a minute and more in the app's Linux: read at most every 15 minutes, unless asked. */
    private static final long AGY_EVERY = 15 * 60_000L;
    private final Ui ui; private final AppStore store;
    UsageUi(Ui ui, AppStore store) { this.ui = ui; this.store = store; }

    /**
     * What the page shows: a row per connected account, the seven days' sums per company, and for 예상 비용 the ledger's
     * tokens for today, seven and thirty days (core/Prices.Tokens), with whether the Gemini key is marked 무료 and whether
     * its tier is known at all (GeminiKeyRing).
     */
    /**
     * With 오늘 남은 양 (left), what spare work did today in words and its tokens (spent, spentTokens: core/SpareQuota.line, tokens),
     * and whether it can run at all (spare: Groq connected).
     */
    record Page(List<Account> accounts, JSONArray totals, List<List<Prices.Tokens>> spend, boolean geminiFree, boolean geminiTier, List<Left> left, String spent, long spentTokens, boolean spare) {
        Page(List<Account> accounts, JSONArray totals, List<List<Prices.Tokens>> spend, boolean geminiFree, boolean geminiTier) { this(accounts, totals, spend, geminiFree, geminiTier, List.of(), "", 0, false); }
        Page(List<Account> accounts, JSONArray totals) { this(accounts, totals, List.of(List.of(), List.of(), List.of()), false, false); }
    }
    /**
     * 오늘 남은 양 (core/SpareQuota): one free tier or subscription the app can call, what it can still do today in a line ("모델 4개
     * · 토큰 612k / 800k · 요청 3,950 / 4,000회 남음 · 오후 5시에 다시 채워져요"), and whether little is left.
     */
    record Left(String name, String line, boolean low) {}
    static final long MONTH_MS = 30L * 24 * 60 * 60 * 1000;
    /** 예상 비용's periods, at the end of its heading line; the one shown is kept while the app runs. */
    static final String[] PERIODS = {"오늘", "7일", "30일"};
    private static volatile int period = 1;
    /**
     * One connected account: its name and route, its lines (a window each, then when they were seen), none for 모름, and
     * the settings page it is connected on (MainActivity.openSettings).
     */
    record Account(String name, List<String> lines, String page, List<Limits.Window> windows, long now) {
        Account(String name, List<String> lines, String page) { this(name, lines, page, List.of(), 0); }
        /** The lines that are not a window: when it was seen, why a read failed, a hint. */
        List<String> notes() { return lines.subList(Math.min(windows.size(), lines.size()), lines.size()); }
    }

    private static ExecutorService reader() { return reader("gyeot-usage-page"); }
    private static ExecutorService reader(String name) {
        ThreadPoolExecutor reader = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; });
        reader.allowCoreThreadTimeOut(true);
        return reader;
    }
    /**
     * When the page must be drawn again, cheaply and on the main thread: the accounts and routes connected, a write to the
     * ledger, and each minute (a window resets, its age and the seven days move on). No
     * credential is read for it.
     */
    String fingerprint(long now) {
        StringBuilder seen = new StringBuilder();
        for (String engine : ENGINES) if (store.ready(engine)) seen.append(engine).append(':').append(store.usageRoute(engine)).append('|');
        seen.append(store.codexImages.connected()).append('|').append(CHECK_STATE.get()).append('|');
        return seen.append(store.usage.version()).append('|').append(now / 60_000).toString();
    }
    /** load, on the page's reader; then hears the page there (post it to draw it). Nothing is heard when it could not be read. */
    static void read(AppStore store, long now, ZoneId zone, Consumer<Page> then) {
        try { READS.execute(() -> { Page page; try { page = load(store, now, zone); } catch (RuntimeException unreadable) { return; } then.accept(page); }); }
        catch (RejectedExecutionException busy) { /* Drawn on the next change or minute. */ }
    }
    /** What the page shows now; reads the ledger and each connection's identity, so never on the main thread. */
    static Page load(AppStore store, long now, ZoneId zone) {
        List<Account> accounts = new ArrayList<>();
        for (String engine : ENGINES) {
            if (!store.ready(engine)) continue;
            String route = store.usageRoute(engine);
            Limits limits = store.usage.limits(engine, route, store.usageIdentity(engine));
            List<String> lines = lines(limits, now, zone);
            List<Limits.Window> windows = limits.empty() ? List.of() : limits.current(now);
            if (WorkBook.GROK.equals(engine)) problem(lines, GROK_PAGE);
            // Antigravity sends no limits with its answers: agy's own /usage panel, read by AntigravityCli and kept.
            if (WorkBook.ANTIGRAVITY.equals(engine) && lines.isEmpty()) {
                // As bars by family and window (Claude·GPT 5시간, Gemini 주간), each until its own reset; lines for the rest.
                Limits agy = store.antigravity.limits(); List<String> rows = store.antigravity.usage();
                windows = agy.current(now); lines.addAll(agy.lines(now, zone)); lines.addAll(rows);
                if (lines.isEmpty()) lines.add(agy.empty() ? "모델별 한도는 설정 → Antigravity에서 ‘상태 확인’을 누르면 여기에도 보여요" : "지난번에 읽은 한도가 지났어요 · 설정 → Antigravity에서 ‘상태 확인’을 누르면 새로 읽어요");
                else { long at = store.antigravity.usageAt(); long minutes = Math.max(0, (now - at) / 60_000); lines.add(minutes < 1 ? "방금 기준" : minutes < 120 ? minutes + "분 전 기준" : (minutes / 60) + "시간 전 기준"); }
                problem(lines, AGY_PAGE);
            }
            // The ChatGPT sign-in's own tokens cannot read Codex's usage page; the Codex sign-in can (the row below).
            if (WorkBook.CHATGPT.equals(engine) && UsageLedger.PLAN.equals(route) && lines.isEmpty() && !store.codexImages.connected())
                lines.add("5시간·주간 한도는 설정 → ChatGPT의 ‘이미지용 Codex로 로그인’을 하면 여기에 보여요");
            accounts.add(new Account(WorkBook.author(engine) + " · " + (UsageLedger.PLAN.equals(route) ? "구독" : "API 키"), lines, page(engine), windows, now));
            if (WorkBook.CHATGPT.equals(engine) && store.codexImages.connected()) accounts.add(codex(store, now, zone));
        }
        if (!store.ready(WorkBook.CHATGPT) && store.codexImages.connected()) accounts.add(0, codex(store, now, zone));
        long midnight = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
        boolean[] gemini = geminiTier(store);
        return new Page(accounts, store.usage.totals(now - WEEK_MS), List.of(store.usage.tokens(midnight), store.usage.tokens(now - WEEK_MS), store.usage.tokens(now - MONTH_MS)), gemini[0], gemini[1],
            left(store, now, zone), dev.jun.gyeot.core.SpareQuota.line(SpareWork.log(store), now, zone), dev.jun.gyeot.core.SpareQuota.tokens(SpareWork.log(store), now, zone), store.groq.connected());
    }
    /**
     * 오늘 남은 양: a line per free tier the app counts (Groq's chat models together, Gemini's that the app uses on its own) with
     * when it fills again, NVIDIA's free tier, and each subscription's tightest window as it last reported it. Paid keys are not
     * listed (what is left there is money). Reads the ledger and the connections' identities: never on the main thread.
     */
    static List<Left> left(AppStore store, long now, ZoneId zone) {
        List<Left> out = new ArrayList<>();
        JSONObject state = store.geminiQuota();
        String again = resets(dev.jun.gyeot.core.GeminiQuota.resetAt(now), now, zone);
        if (store.groq.connected()) {
            List<String> models = SpareQuotas.groq(store, GroqWork.CHORES);
            String qwen = BuddyTalk.qwen(store); if (!models.contains(qwen) && dev.jun.gyeot.core.SpareQuota.tier(qwen) != null) models.add(qwen);
            out.add(free("Groq 무료", models, state, now, again));
        }
        if (store.secrets.hasKey() || store.secrets.hasWorkKey()) out.add(free("Gemini 무료", java.util.Arrays.asList(geminiModels(store)), state, now, again));
        if (store.nim.connected()) {
            Limits.Window w = SpareQuotas.tightest(store, WorkBook.NIM, store.usageRoute(WorkBook.NIM), now);
            out.add(new Left("NVIDIA NIM 무료", w == null ? "하루 한도 없이 분당 한도만 있어요" : w.name() + " · " + Limits.left(w, now, zone), w != null && w.left() < dev.jun.gyeot.core.SpareQuota.LOW));
        }
        for (String engine : new String[]{WorkBook.CHATGPT, WorkBook.GROK, WorkBook.ANTIGRAVITY}) {
            if (!store.ready(engine) || !UsageLedger.PLAN.equals(store.usageRoute(engine))) continue;
            Limits.Window w = SpareQuotas.tightest(store, engine, UsageLedger.PLAN, now);
            out.add(new Left(WorkBook.author(engine) + " 구독", w == null ? "남은 한도 모름 · 답을 받으면 알려 줘요" : w.name() + " · " + Limits.left(w, now, zone), w != null && w.left() < dev.jun.gyeot.core.SpareQuota.LOW));
        }
        return out;
    }
    /**
     * A free tier's row: its name with how many models it counts, the models together in one line (core/SpareQuota.summary) and
     * when it fills again; low when every one has little left.
     */
    private static Left free(String name, List<String> models, JSONObject state, long now, String again) {
        List<dev.jun.gyeot.core.SpareQuota.Room> rooms = new ArrayList<>(); boolean low = !models.isEmpty();
        for (String model : models) { dev.jun.gyeot.core.SpareQuota.Room r = dev.jun.gyeot.core.SpareQuota.remaining(state, model, now); rooms.add(r); if (r.share() >= dev.jun.gyeot.core.SpareQuota.LOW) low = false; }
        String sum = dev.jun.gyeot.core.SpareQuota.summary(rooms);
        return new Left(name + (models.size() > 1 ? " · 모델 " + models.size() + "개" : ""), (sum.isEmpty() ? "" : sum + " · ") + again, low);
    }
    /** "17:00 초기화", or "11.29 17:00 초기화" on another day: when a day's counts start again, as the subscriptions' rows say it (core/Limits.left). */
    static String resets(long at, long now, ZoneId zone) {
        java.time.ZonedDateTime reset = java.time.Instant.ofEpochMilli(at).atZone(zone);
        boolean today = reset.toLocalDate().equals(java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
        return reset.format(java.time.format.DateTimeFormatter.ofPattern(today ? "HH:mm" : "M.d HH:mm")) + " 초기화";
    }
    /** {marked 무료, its tier known}: the key the app's Gemini requests go with, as the user marked it (GeminiKeyRing). Reads the keys: never on the main thread. */
    static boolean[] geminiTier(AppStore store) {
        try {
            String key = store.secrets.workKey(); if (key == null || key.isBlank()) return new boolean[]{false, false};
            GeminiKeyRing.Key found = GeminiKeyRing.find(store.geminiKeys.keys(), key);
            String tier = found == null ? "" : found.tier();
            return new boolean[]{GeminiKeyRing.FREE.equals(tier), GeminiKeyRing.FREE.equals(tier) || GeminiKeyRing.PAID.equals(tier)};
        } catch (Exception unreadable) { return new boolean[]{false, false}; }
    }
    /** The settings page that engine is connected on: Gemini's key is on "key", the others on their own page. */
    static String page(String engine) {
        return switch (engine) { case WorkBook.CLAUDE -> "claude"; case WorkBook.GEMINI -> "key"; case WorkBook.GROK -> "grok"; case WorkBook.NIM -> "nim"; case WorkBook.ANTIGRAVITY -> "antigravity"; case WorkBook.GROQ -> "groq"; default -> "chatgpt"; };
    }
    /**
     * The windows the provider last reported for this account and this connection, then when it reported them ("3분 전
     * 기준"); none is 모름.
     */
    static List<String> lines(AppStore store, String engine, String route, long now, ZoneId zone) {
        return lines(store.usage.limits(engine, route, store.usageIdentity(engine)), now, zone);
    }
    static List<String> lines(Limits limits, long now, ZoneId zone) {
        List<String> lines = limits.empty() ? new ArrayList<>() : new ArrayList<>(limits.lines(now, zone));
        if (!lines.isEmpty() && !limits.seen(now).isEmpty()) lines.add(limits.seen(now));
        return lines;
    }
    /** The Codex sign-in's row: what its usage page last said, for that same ChatGPT account. */
    static Account codex(AppStore store, long now, ZoneId zone) {
        Limits limits = store.usage.limits(WorkBook.CHATGPT, PlanUsage.CODEX_ROUTE, store.codexImages.usageIdentity());
        List<String> lines = lines(limits, now, zone);
        problem(lines, CODEX);
        // The Codex sign-in (이미지용 Codex로 로그인) is on the ChatGPT page.
        return new Account("ChatGPT · Codex 한도", lines, "chatgpt", limits.empty() ? List.of() : limits.current(now), now);
    }
    /** Why the last read of that usage page failed, or that it is being read, after what is known. */
    private static void problem(List<String> lines, String page) {
        String why = PROBLEMS.get(page);
        if (why != null) lines.add((lines.isEmpty() ? "" : "새로 확인하지 못했어요 · ") + why);
        else if (checking && lines.isEmpty() && CHECKED.containsKey(page)) lines.add("사용량을 확인하는 중이에요");
    }
    /**
     * Asks the usage pages of the connected subscriptions (core/PlanUsage), off the main thread, and keeps what they say in
     * the ledger like any other report: the Codex sign-in under ChatGPT's PlanUsage.CODEX_ROUTE, Grok under its own
     * connection. Unless forced (the page's 지금 다시 확인), a page asked within PlanUsage.AUTO_MS is not asked again.
     * changed runs once the reads are done and kept, so the page draws them.
     */
    static void check(AppStore store, boolean force, Runnable changed) {
        long now = System.currentTimeMillis();
        boolean codex = store.codexImages.connected() && due(CODEX, force, now), grok = store.grok.connected() && due(GROK_PAGE, force, now);
        Long agyLast = CHECKED.get(AGY_PAGE);
        boolean agy = store.antigravity.connected() && (force || agyLast == null || now - agyLast >= AGY_EVERY);
        if (!codex && !grok && !agy) return;
        if (codex) CHECKED.put(CODEX, now);
        if (grok) CHECKED.put(GROK_PAGE, now);
        if (agy) CHECKED.put(AGY_PAGE, now);
        checking = true; CHECK_STATE.incrementAndGet(); changed.run();
        try {
            CHECKS.execute(() -> {
                java.util.concurrent.CountDownLatch kept = new java.util.concurrent.CountDownLatch((codex ? 1 : 0) + (grok ? 1 : 0));
                if (codex) keep(store, CODEX, WorkBook.CHATGPT, PlanUsage.CODEX_ROUTE, () -> store.codexImages.usageIdentity(), () -> store.codexImages.usage(null), kept);
                if (grok) keep(store, GROK_PAGE, WorkBook.GROK, store.usageRoute(WorkBook.GROK), () -> store.usageIdentity(WorkBook.GROK), () -> store.grok.usage(null), kept);
                try { kept.await(10, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                if (agy) {
                    // Codex and Grok drawn first; agy's panel takes longer.
                    CHECK_STATE.incrementAndGet(); changed.run();
                    try {
                        AntigravityCli.Status st = store.antigravity.refresh();
                        if (st.usage().isEmpty() && st.limits().empty()) PROBLEMS.put(AGY_PAGE, st.detail().isEmpty() ? "모델별 한도를 읽지 못했어요" : st.detail().length() > 160 ? st.detail().substring(0, 160) + "…" : st.detail());
                        else PROBLEMS.remove(AGY_PAGE);
                    } catch (Exception failed) { PROBLEMS.put(AGY_PAGE, failed.getMessage() == null ? "모델별 한도를 읽지 못했어요" : failed.getMessage()); }
                }
                checking = false; CHECK_STATE.incrementAndGet(); changed.run();
            });
        } catch (RejectedExecutionException busy) { checking = false; }
    }
    /** For tests: as if no usage page had been asked in this process. */
    static void forget() { CHECKED.clear(); PROBLEMS.clear(); checking = false; CHECK_STATE.incrementAndGet(); }
    /** True while a check is running (UsageUi.check), for tests that wait for it. */
    static boolean checking() { return checking; }
    private static boolean due(String page, boolean force, long now) { Long last = CHECKED.get(page); return force || last == null || now - last >= PlanUsage.AUTO_MS; }
    interface Read { Limits get() throws Exception; }
    /** One usage page: what it said goes to the ledger, or why it could not be read to PROBLEMS. */
    private static void keep(AppStore store, String page, String company, String route, java.util.function.Supplier<String> identity, Read read, java.util.concurrent.CountDownLatch kept) {
        try {
            Limits limits = read.get();
            String connection = identity.get();
            if (limits.empty()) { PROBLEMS.put(page, "사용량 정보가 오지 않았어요(형식이 바뀌었을 수 있어요)"); kept.countDown(); return; }
            if (connection.isBlank()) { PROBLEMS.put(page, "연결을 확인하지 못했어요"); kept.countDown(); return; }
            PROBLEMS.remove(page);
            store.usage.limits(company, route, connection, limits, kept::countDown);
        } catch (Exception failed) {
            String why = failed.getMessage() == null || failed.getMessage().isBlank() ? "사용량을 받지 못했어요" : failed.getMessage();
            PROBLEMS.put(page, why.length() > 120 ? why.substring(0, 120) + "…" : why);
            kept.countDown();
        }
    }
    /** The page, on the main thread, with rows that open nothing and no 지금 다시 확인 (the page's text alone). */
    void fill(LinearLayout box, Page page) { fill(box, page, null, null); }
    /**
     * The page, on the main thread: 남은 한도 per connected account, then 최근 7일 per company. Given open, each account's row
     * opens the settings page it is connected on; given recheck, 지금 다시 확인 asks the usage pages at once while a Codex
     * or Grok sign-in is there.
     */
    void fill(LinearLayout box, Page page, Consumer<String> open, Runnable recheck) {
        spare(box, page, System.currentTimeMillis(), ZoneId.systemDefault());
        box.addView(ui.sectionHeader("남은 한도"));
        if (recheck != null && (store.codexImages.connected() || store.grok.connected() || store.antigravity.connected())) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(6);
            box.addView(ui.actions(ui.chip("지금 다시 확인", recheck)), p);
        }
        LinearLayout limits = limits(page.accounts(), open);
        if (limits.getChildCount() == 0) box.addView(ui.footer("연결된 AI가 없어요. 설정 → AI 연결에서 연결하면 여기에 보여요."));
        else box.addView(limits);
        box.addView(ui.footer("회사가 답에 함께 보낸 한도만 보여요. 보내지 않으면 ‘남은 한도 모름’이에요."));
        // Two keys, two sections (준: "제미니 무료 api키랑 Groq 모델 한도 서로 분리해줘 헷갈려").
        long now = System.currentTimeMillis();
        if (store.secrets.hasKey() || store.secrets.hasWorkKey()) {
            box.addView(ui.sectionHeader("Gemini 무료 한도 · Gemini API 키"));
            box.addView(quota(geminiModels(), false, now));
            box.addView(ui.footer("앱이 스스로 쓰는 Gemini(검색·영상 요약·첨부 분석·받아쓰기)를 이 폰에서 센 거예요. 한도가 찬 모델은 건너뛰고 다음 모델을 써요. "
                + "같은 키를 다른 곳에서도 쓰면 실제 남은 양은 더 적을 수 있어요. 한도는 Google이 바꿀 수 있고, Google이 알려 준 한도가 있으면 그걸로 보여요."));
        }
        if (store.groq.connected()) {
            box.addView(ui.sectionHeader("Groq 무료 한도 · Groq API 키"));
            box.addView(quota(groqModels(), true, now));
            box.addView(ui.footer("Groq는 하루 요청 횟수와 하루 토큰(보내고 받은 글의 양)을 따로 세요. 둘 중 하나라도 차면 Groq가 막고, Groq가 알려 준 시간이 지나면 다시 써요. "
                + "퍼레 대화는 한 번에 긴 글을 보내서 횟수보다 토큰이 먼저 찰 수 있어요. 한 모델이 차면 같은 급에서 한도가 남은 모델이 이어받아요."));
        }
        box.addView(ui.sectionHeader("최근 7일"));
        LinearLayout recent = recent(page.totals());
        if (recent.getChildCount() == 0) box.addView(ui.footer("아직 기록이 없어요. 답을 받을 때마다 쌓여요."));
        else box.addView(recent);
        box.addView(ui.footer("메인 대화·작업창·예약·누르고 말하기·퍼레·잡일·브라우저에서 받은 답과, 휴대폰이 모델 없이 바로 답한 것(기기 안)을 세요."));
        cost(box, page);
        routes(box, page);
    }

    // ── 오늘 남은 양 · 남는 한도로 한 일 (core/SpareQuota): what each free tier and subscription can still do, and what was left used for ──
    /**
     * A row per free tier or subscription (Page.left: what it can still do today and when it fills again; a warning color when
     * little is left), then, with Groq connected, 남는 한도로 한 일 today, and how that works. Rows in one group, no cards.
     */
    void spare(LinearLayout box, Page page, long now, ZoneId zone) {
        if (page.left().isEmpty() && !page.spare()) return;
        box.addView(ui.sectionHeader("오늘 남은 양"));
        LinearLayout group = ui.group();
        for (Left left : page.left()) {
            LinearLayout row = ui.listRow(0, 0, left.name(), left.line(), null, null);
            TextView sub = ((Ui.ListRow) row).parts.subtitle();
            if (sub != null) { sub.setSingleLine(false); sub.setMaxLines(2); if (left.low()) sub.setTextColor(Ui.WARNING); }
            ui.addRow(group, row, false);
        }
        if (page.spare()) {
            TextView took = page.spentTokens() > 0 ? ui.text("토큰 " + Usage.tokens(page.spentTokens()), 13, 500, Ui.MUTED) : null;
            LinearLayout done = ui.listRow(0, 0, "남는 한도로 한 일", page.spent().isEmpty() ? "오늘은 아직 없어요" : "오늘 " + page.spent(), took, null);
            TextView sub = ((Ui.ListRow) done).parts.subtitle(); if (sub != null) { sub.setSingleLine(false); sub.setMaxLines(2); }
            ui.addRow(group, done, false);
        }
        box.addView(group);
        long reset = dev.jun.gyeot.core.GeminiQuota.resetAt(now);
        String from = clock(reset - dev.jun.gyeot.core.SpareQuota.WINDOW, zone), to = clock(reset, zone);
        if (from.substring(0, 3).equals(to.substring(0, 3))) to = to.substring(3);
        if (page.spare()) box.addView(ui.footer("다시 채워지기 전 " + from + "~" + to + ", 충전 중·Wi-Fi·화면 꺼짐이면 남은 Groq 한도로 대화 제목·기억 점검·나에 대한 이해를 미리 해요(하루 "
            + Usage.tokens(dev.jun.gyeot.core.SpareQuota.DAY_BUDGET) + " 토큰까지, 모델마다 " + Math.round(dev.jun.gyeot.core.SpareQuota.FLOOR * 100) + "%는 남김). Gemini와 유료 키는 안 써요."));
    }

    // ── 예상 비용 (core/Prices): the ledger's tokens at each model's own price, by company and by where they were used ──
    /** Its heading with the period's switcher at the end of the same line, then the two lists, then how it is counted. */
    void cost(LinearLayout box, Page page) {
        LinearLayout head = ui.row(); head.setGravity(android.view.Gravity.BOTTOM);
        head.addView(ui.sectionHeader("예상 비용"), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout body = ui.column();
        LinearLayout periods = ui.switcher(PERIODS, period, i -> { period = i; body.removeAllViews(); spend(body, page); });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.rightMargin = ui.dp(12); at.bottomMargin = ui.dp(2);
        head.addView(periods, at);
        box.addView(head); box.addView(body, new LinearLayout.LayoutParams(-1, -2));
        spend(body, page);
        box.addView(ui.footer("회사 요금 페이지(" + Prices.CHECKED + " 확인)의 100만 토큰당 달러로 셈한 추정이에요. 1달러 = " + String.format(java.util.Locale.ROOT, "%,d", (long) Prices.KRW_PER_USD) + "원으로 고정해서 계산해요. "
            + "구독 로그인(ChatGPT·Grok·Antigravity)과 무료(NVIDIA·Groq 무료 등급·Gemma·무료로 표시한 Gemini 키)는 돈으로 세지 않아요. 요금표에 없는 모델과 토큰을 알려 주지 않은 답은 ‘모름’이에요."));
        box.addView(ui.details("요금표", priceTable()));
    }
    /** The period's two lists: by company, then by where (메인 대화, 작업창, 퍼레 …); "no answers" when there were none. */
    private void spend(LinearLayout body, Page page) {
        List<Prices.Tokens> groups = page.spend().get(Math.max(0, Math.min(2, period)));
        if (groups.isEmpty()) { body.addView(ui.footer(PERIODS[period] + " 동안 받은 답이 없어요.")); return; }
        List<Prices.Spend> companies = Prices.spend(groups, true, page.geminiFree(), page.geminiTier());
        double usd = 0; long priced = 0;
        for (Prices.Spend s : companies) { usd += s.usd(); priced += s.paid(); }
        LinearLayout byCompany = ui.group();
        for (Prices.Spend s : companies) ui.addRow(byCompany, ui.listRow(0, 0, company(s.key()), Prices.words(s) + " · 요청 " + s.requests() + "번", null, null), false);
        body.addView(byCompany);
        if (priced > 0) body.addView(ui.footer("합계 " + Prices.dollars(usd) + " (돈이 드는 요청 " + priced + "번)"));
        TextView where = ui.sectionHeader("어디서 썼나"); where.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(8)); body.addView(where);
        LinearLayout bySurface = ui.group();
        for (Prices.Spend s : Prices.spend(groups, false, page.geminiFree(), page.geminiTier()))
            ui.addRow(bySurface, ui.listRow(0, 0, dev.jun.gyeot.core.BrainRoutes.surfaceLabel(s.key()), Prices.words(s) + " · 요청 " + s.requests() + "번", null, null), false);
        body.addView(bySurface);
    }
    /** A ledger company in words: the AI's maker as the app names it, or 기기 안 for an answer made on the phone. */
    static String company(String company) { return dev.jun.gyeot.core.BrainRoutes.DEVICE.equals(company) ? "기기 안 (모델 없음)" : WorkBook.author(company); }
    /** Every price the page counts with, a line each, with where it was read. */
    static String priceTable() {
        StringBuilder out = new StringBuilder("100만 토큰당 달러: 입력 · 캐시 읽기 · 출력 (Claude는 캐시 쓰기도).");
        String source = "";
        for (java.util.Map.Entry<String, Prices.Price> e : Prices.table().entrySet()) {
            Prices.Price p = e.getValue();
            if (!p.source().equals(source)) { source = p.source(); out.append("\n\n").append(source); }
            out.append("\n").append(e.getKey()).append("  $").append(Prices.money(p.input())).append(" · $").append(Prices.money(p.cacheRead()))
                .append(p.cacheWrite() > 0 ? " · 쓰기 $" + Prices.money(p.cacheWrite()) : "").append(" · $").append(Prices.money(p.output()))
                .append(p.note().isEmpty() ? "" : " (" + p.note() + ")");
        }
        return out.append("\n\nNVIDIA NIM·Groq 무료 등급·Gemma: 무료. 구독 로그인: 구독.").toString();
    }

    // ── 두뇌 배분 (core/BrainRoutes): which kind of request goes to which brain, and who takes over ──
    void routes(LinearLayout box, Page page) {
        box.addView(ui.sectionHeader("두뇌 배분"));
        LinearLayout group = ui.group();
        long local = 0;
        for (Prices.Tokens t : page.spend().get(0)) if (dev.jun.gyeot.core.BrainRoutes.DEVICE.equals(t.company())) local += t.requests();
        for (dev.jun.gyeot.core.BrainRoutes.Route r : dev.jun.gyeot.core.BrainRoutes.TABLE) {
            boolean device = r.kind() == dev.jun.gyeot.core.BrainRoutes.Kind.DEVICE;
            String line = r.brains() + (device ? " · 오늘 " + local + "번" : " · 막히면 " + r.fallback());
            LinearLayout row = ui.listRow(0, 0, r.kind().label.equals(r.title()) ? r.title() : r.kind().label + " · " + r.title(), line, null, null);
            TextView sub = ((Ui.ListRow) row).parts.subtitle(); if (sub != null) { sub.setSingleLine(false); sub.setMaxLines(2); }
            ui.addRow(group, row, false);
        }
        ui.addRow(group, ui.switchRow("막히면 다음 모델로", "메인 대화에서 고른 모델이 한도·계정·서버 문제로 답하지 못하면, 연결된 다음 큰 모델이 한 번만 대신 답하고 답 아래에 적어요. 고른 모델은 그대로예요.",
            store.settings.getBoolean(MainChatEngine.FALLBACK, true), on -> store.settings.edit().putBoolean(MainChatEngine.FALLBACK, on).apply()), false);
        box.addView(group);
    }
    /**
     * A card per connected account: its name (and when it was seen), then each limit as a heading, "4시간 29분 후
     * 초기화됩니다" with "49% 남음" across from it and a bar filled by what is left; or 남은 한도 모름. With open, a tap
     * opens the account's page.
     */
    private LinearLayout limits(List<Account> accounts, Consumer<String> open) {
        LinearLayout cards = ui.column();
        for (Account account : accounts) {
            LinearLayout card = ui.column();
            android.graphics.drawable.GradientDrawable surface = ui.shape(Ui.CARD, 18); surface.setStroke(Math.max(1, ui.dp(.5f)), Ui.SEPARATOR);
            card.setBackground(surface); card.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
            LinearLayout head = ui.row(); head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            head.addView(ui.text(account.name(), 13, 600, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
            if (open != null) head.addView(ui.text("›", 18, 500, Ui.MUTED));
            card.addView(head);
            for (Limits.Window window : account.windows()) card.addView(window(window, account.now()));
            List<String> notes = account.windows().isEmpty() ? account.lines() : account.notes();
            if (account.lines().isEmpty()) notes = List.of("남은 한도 모름");
            for (String note : notes) {
                TextView line = ui.text(note, 12, 450, Ui.MUTED); line.setPadding(0, ui.dp(8), 0, 0); card.addView(line);
            }
            String shown = account.lines().isEmpty() ? "남은 한도 모름" : String.join(", ", account.lines());
            card.setContentDescription(account.name() + ", " + shown);
            if (open != null) { card.setOnClickListener(v -> open.accept(account.page())); Ui.pressable(card, true); }
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2); if (cards.getChildCount() > 0) at.topMargin = ui.dp(10);
            cards.addView(card, at);
        }
        return cards;
    }
    /** One limit: its heading, when it resets and how much is left, and a rounded bar filled by what is left. */
    private LinearLayout window(Limits.Window window, long now) { return window(ui, window, now); }
    /** The same, for another page that shows a plan's limits (설정 → Antigravity). */
    static LinearLayout window(Ui ui, Limits.Window window, long now) {
        LinearLayout box = ui.column(); box.setPadding(0, ui.dp(12), 0, 0);
        box.addView(ui.text(Limits.heading(window), 15, 650, Ui.TEXT));
        LinearLayout line = ui.row(); line.setPadding(0, ui.dp(4), 0, ui.dp(8));
        line.addView(ui.text(Limits.until(window, now), 13, 450, Ui.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(ui.text(Limits.remaining(window), 13, 500, Ui.TEXT));
        box.addView(line);
        double left = Math.max(0, Math.min(1, window.left()));
        int ink = left < 0.1 ? Ui.ERROR : left < 0.25 ? Ui.WARNING : Ui.TEXT;
        LinearLayout track = ui.row(); track.setBackground(ui.shape(Ui.FILL, Ui.PILL)); track.setClipToOutline(true);
        android.view.View filled = new android.view.View(box.getContext()); filled.setBackground(ui.shape(ink, Ui.PILL));
        track.addView(filled, new LinearLayout.LayoutParams(0, -1, (float) left));
        track.addView(new android.view.View(box.getContext()), new LinearLayout.LayoutParams(0, -1, (float) (1 - left)));
        box.addView(track, new LinearLayout.LayoutParams(-1, ui.dp(8)));
        box.setContentDescription(Limits.heading(window) + ", " + Limits.remaining(window) + ", " + Limits.until(window, now));
        return box;
    }
    /** Gemma 4 (light work, the key's two largest), Flash-Lite (search, summaries), Transcribe (recordings). */
    String[] geminiModels() { return geminiModels(store); }
    static String[] geminiModels(AppStore store) {
        String[] listed = store.gemini.gemma(), gemma = listed == null ? dev.jun.gyeot.core.GeminiLight.GEMMA : listed;
        java.util.List<String> shown = new java.util.ArrayList<>(java.util.List.of(gemma).subList(0, Math.min(2, gemma.length)));
        shown.add(dev.jun.gyeot.core.GeminiWork.FLASH_LITE[0]); shown.add(dev.jun.gyeot.core.GeminiWork.FLASH_LITE[1]); shown.add(dev.jun.gyeot.core.GeminiProtocol.FILE_TRANSCRIBE);
        return shown.toArray(new String[0]);
    }
    /** Groq's: 잡일 (GPT OSS), 퍼레's brain (the newest Qwen the key lists), web search and Whisper. */
    String[] groqModels() {
        java.util.List<String> shown = new java.util.ArrayList<>(java.util.List.of(GroqWork.CHORES));
        String qwen = BuddyTalk.qwen(store); if (!shown.contains(qwen)) shown.add(qwen);
        shown.add(GroqWork.SEARCH); shown.add(GroqWork.WHISPER);
        return shown.toArray(new String[0]);
    }
    /**
     * 무료 한도 (core/GeminiQuota): a card with each of models (and the others of the same company used today): today's
     * requests against the free requests a day, who stopped it and until when when it is stopped, and a bar filled by what is
     * left. Gemini's say when Google fills them again (midnight Pacific); Groq's say today's tokens, which Groq counts apart.
     */
    LinearLayout quota(String[] models, boolean groq, long now) {
        LinearLayout card = ui.column();
        android.graphics.drawable.GradientDrawable surface = ui.shape(Ui.CARD, 18); surface.setStroke(Math.max(1, ui.dp(.5f)), Ui.SEPARATOR);
        card.setBackground(surface); card.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(14));
        String reset = refill(dev.jun.gyeot.core.GeminiQuota.resetAt(now));
        for (dev.jun.gyeot.core.GeminiQuota.Row row : dev.jun.gyeot.core.GeminiQuota.rows(store.geminiQuota(), models, now, groq)) {
            LinearLayout box = ui.column(); box.setPadding(0, ui.dp(12), 0, 0);
            // What the app has it do (준: "큐웬을 어디서 쓴거야?"), by its name.
            String job = job(row.model());
            box.addView(ui.text((groq ? row.label().replaceFirst("^Groq ", "") : row.label()) + (job.isEmpty() ? "" : " · " + job), 15, 650, Ui.TEXT));
            LinearLayout line = ui.row(); line.setPadding(0, ui.dp(4), 0, ui.dp(8));
            String used = "오늘 " + row.uses() + (row.perDay() > 0 ? " / " + row.perDay() : "") + "회";
            String tokens = row.tokensPerDay() > 0 ? "토큰 " + Usage.tokens(row.tokens()) + " / " + Usage.tokens(row.tokensPerDay()) : "토큰 " + Usage.tokens(row.tokens());
            String note = !row.status().isEmpty() ? row.status() : !groq ? reset : row.tokensPerDay() > 0 || row.tokens() > 0 ? tokens : "";
            line.addView(ui.text(note, 13, 450, row.status().isEmpty() ? Ui.MUTED : Ui.WARNING), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(ui.text(used, 13, 500, Ui.TEXT));
            box.addView(line);
            double byRequests = row.perDay() > 0 ? 1 - row.uses() / (double) row.perDay() : 1, byTokens = row.tokensPerDay() > 0 ? 1 - row.tokens() / (double) row.tokensPerDay() : 1;
            double left = row.blocked() ? 0 : Math.max(0, Math.min(1, Math.min(byRequests, byTokens)));
            int ink = left < 0.1 ? Ui.ERROR : left < 0.25 ? Ui.WARNING : Ui.TEXT;
            LinearLayout track = ui.row(); track.setBackground(ui.shape(Ui.FILL, Ui.PILL)); track.setClipToOutline(true);
            android.view.View filled = new android.view.View(box.getContext()); filled.setBackground(ui.shape(ink, Ui.PILL));
            track.addView(filled, new LinearLayout.LayoutParams(0, -1, (float) left));
            track.addView(new android.view.View(box.getContext()), new LinearLayout.LayoutParams(0, -1, (float) (1 - left)));
            box.addView(track, new LinearLayout.LayoutParams(-1, ui.dp(8)));
            box.setContentDescription(row.label() + ", " + used + ", " + note);
            card.addView(box);
        }
        return card;
    }
    /** What the app has a model do, in a word or two ("" for one only used now and then). */
    private String job(String model) {
        if (GroqWork.CHORES.equals(model)) return "잡일";
        if (GroqWork.SEARCH.equals(model)) return "웹 검색";
        if (GroqWork.WHISPER.equals(model) || dev.jun.gyeot.core.GeminiProtocol.FILE_TRANSCRIBE.equals(model)) return "받아쓰기";
        if (model.startsWith("qwen/") && model.equals(BuddyTalk.qwen(store))) return "퍼레 대화";
        if (model.startsWith("gemma-4-")) return "잡일 예비";
        if (java.util.List.of(dev.jun.gyeot.core.GeminiWork.FLASH_LITE).contains(model)) return "검색 · 요약";
        return "";
    }
    /** "오후 5시에 다시 채워져요": when the free daily quotas reset, in the phone's own time. */
    static String refill(long at) { return refill(at, ZoneId.systemDefault()); }
    static String refill(long at, ZoneId zone) { return clock(at, zone) + "에 다시 채워져요"; }
    /** "오후 5시", "오전 9시 30분": a moment as people say it, in zone. */
    static String clock(long at, ZoneId zone) {
        java.time.ZonedDateTime local = java.time.Instant.ofEpochMilli(at).atZone(zone);
        int hour = local.getHour(), minute = local.getMinute();
        return (hour < 12 ? "오전 " : "오후 ") + (hour % 12 == 0 ? 12 : hour % 12) + "시" + (minute > 0 ? " " + minute + "분" : "");
    }
    /** One row per company with requests in the last seven days, most requests first. */
    private LinearLayout recent(JSONArray totals) {
        LinearLayout group = ui.group();
        for (int i = 0; i < totals.length(); i++) {
            JSONObject total = totals.optJSONObject(i);
            LinearLayout row = ui.listRow(R.drawable.ic_sparkle, Ui.INDIGO, company(total.optString("company")), summary(total), null, null);
            // A line with cache and unknown counts is longer than a phone's row: it wraps instead of being cut.
            TextView line = ((Ui.ListRow) row).parts.subtitle(); if (line != null) { line.setSingleLine(false); line.setMaxLines(2); }
            ui.addRow(group, row, true);
        }
        return group;
    }
    /** "요청 12번 · 34.5k 토큰 · 캐시 60% · 2번은 토큰 모름": the known part, and how much is not known. */
    static String summary(JSONObject total) {
        long requests = total.optLong("requests"), unknown = total.optLong("unknown"), prompt = total.optLong("prompt");
        StringBuilder line = new StringBuilder("요청 ").append(requests).append("번");
        if (unknown < requests) line.append(" · ").append(Usage.tokens(total.optLong("tokens"))).append(" 토큰");
        if (prompt > 0) line.append(" · 캐시 ").append(Math.round(100.0 * total.optLong("read") / prompt)).append('%');
        if (unknown > 0) line.append(" · ").append(unknown == requests ? "토큰 모름" : unknown + "번은 토큰 모름");
        return line.toString();
    }
    /** The settings list's line for the page: the last seven days in all (the ledger's totals since then), or ABOUT. */
    static String overview(JSONArray totals) {
        long requests = 0, tokens = 0;
        for (int i = 0; i < totals.length(); i++) { requests += totals.optJSONObject(i).optLong("requests"); tokens += totals.optJSONObject(i).optLong("tokens"); }
        return requests == 0 ? ABOUT : "최근 7일 요청 " + requests + "번 · " + Usage.tokens(tokens) + " 토큰";
    }
    /** overview, read on the page's reader; then hears the line there (post it to show it). */
    static void overview(AppStore store, long now, Consumer<String> then) {
        try { READS.execute(() -> { String line; try { line = overview(store.usage.totals(now - WEEK_MS)); } catch (RuntimeException unreadable) { return; } then.accept(line); }); }
        catch (RejectedExecutionException busy) { /* The list keeps ABOUT. */ }
    }
}
