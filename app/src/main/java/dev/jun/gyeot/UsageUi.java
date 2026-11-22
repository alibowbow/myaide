package dev.jun.gyeot;

import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.PlanUsage;
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
 * 설정 → 사용량 · 남은 한도: for each connected account what its provider last said is left of its limits,
 * "남은 72% · 14:30 초기화" and when it said so, or 모름, then what each company's text requests used in the last seven
 * days, from the usage ledger. What it shows is read off the main thread (load, read: the ledger and the connections'
 * identities), then drawn on it (fill). Opening it also asks the subscriptions' own usage pages (check,
 * core/PlanUsage): the Codex sign-in's 5-hour and weekly limits and Grok's weekly pool, at most every PlanUsage.AUTO_MS,
 * or at once from its 지금 다시 확인.
 */
final class UsageUi {
    static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    static final String[] ENGINES = {WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM};
    /** The settings list's line until the last seven days are read, and when there is nothing in them. */
    static final String ABOUT = "답마다 쓴 토큰과 계정별 남은 한도";
    /** One reader for the page and the list's line, whose thread ends after half a minute without work. */
    private static final ExecutorService READS = reader();
    /** The usage pages' reads, one at a time on their own thread, so a slow answer never holds the page's reads. */
    private static final ExecutorService CHECKS = reader("gyeot-usage-check");
    /** Per page (codex, grok): when it was last asked, and why the last read failed (absent when it did not). */
    private static final java.util.Map<String, Long> CHECKED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, String> PROBLEMS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger CHECK_STATE = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean checking;
    static final String CODEX = "codex", GROK_PAGE = "grok";
    private final Ui ui; private final AppStore store;
    UsageUi(Ui ui, AppStore store) { this.ui = ui; this.store = store; }

    /** What the page shows: a row per connected account and the seven days' sums per company. */
    record Page(List<Account> accounts, JSONArray totals) {}
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
            if (WorkBook.GROK.equals(engine)) problem(lines, GROK_PAGE);
            // The ChatGPT sign-in's own tokens cannot read Codex's usage page; the Codex sign-in can (the row below).
            if (WorkBook.CHATGPT.equals(engine) && UsageLedger.PLAN.equals(route) && lines.isEmpty() && !store.codexImages.connected())
                lines.add("5시간·주간 한도는 설정 → ChatGPT의 ‘이미지용 Codex로 로그인’을 하면 여기에 보여요");
            accounts.add(new Account(WorkBook.author(engine) + " · " + (UsageLedger.PLAN.equals(route) ? "구독" : "API 키"), lines, page(engine), limits.empty() ? List.of() : limits.current(now), now));
            if (WorkBook.CHATGPT.equals(engine) && store.codexImages.connected()) accounts.add(codex(store, now, zone));
        }
        if (!store.ready(WorkBook.CHATGPT) && store.codexImages.connected()) accounts.add(0, codex(store, now, zone));
        return new Page(accounts, store.usage.totals(now - WEEK_MS));
    }
    /** The settings page that engine is connected on: Gemini's key is on "key", the others on their own page. */
    static String page(String engine) {
        return switch (engine) { case WorkBook.CLAUDE -> "claude"; case WorkBook.GEMINI -> "key"; case WorkBook.GROK -> "grok"; case WorkBook.NIM -> "nim"; default -> "chatgpt"; };
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
        if (!codex && !grok) return;
        if (codex) CHECKED.put(CODEX, now);
        if (grok) CHECKED.put(GROK_PAGE, now);
        checking = true; CHECK_STATE.incrementAndGet(); changed.run();
        try {
            CHECKS.execute(() -> {
                java.util.concurrent.CountDownLatch kept = new java.util.concurrent.CountDownLatch((codex ? 1 : 0) + (grok ? 1 : 0));
                if (codex) keep(store, CODEX, WorkBook.CHATGPT, PlanUsage.CODEX_ROUTE, () -> store.codexImages.usageIdentity(), () -> store.codexImages.usage(null), kept);
                if (grok) keep(store, GROK_PAGE, WorkBook.GROK, store.usageRoute(WorkBook.GROK), () -> store.usageIdentity(WorkBook.GROK), () -> store.grok.usage(null), kept);
                try { kept.await(10, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
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
        box.addView(ui.sectionHeader("남은 한도"));
        if (recheck != null && (store.codexImages.connected() || store.grok.connected())) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = ui.dp(6);
            box.addView(ui.actions(ui.chip("지금 다시 확인", recheck)), p);
        }
        LinearLayout limits = limits(page.accounts(), open);
        if (limits.getChildCount() == 0) box.addView(ui.footer("연결된 AI가 없어요. 설정 → AI 연결에서 연결하면 여기에 보여요."));
        else box.addView(limits);
        box.addView(ui.footer("회사가 답에 함께 보낸 한도만 보여요. 보내지 않으면 ‘남은 한도 모름’이에요."));
        if (store.secrets.hasKey() || store.secrets.hasWorkKey()) {
            box.addView(ui.sectionHeader("Gemini 무료 한도"));
            box.addView(geminiQuota(System.currentTimeMillis()));
            box.addView(ui.footer("앱이 스스로 쓰는 Gemini(검색·대화 제목·영상 요약·첨부 분석·받아쓰기·두뇌)를 이 폰에서 센 거예요. 한도가 찬 모델은 건너뛰고 다음 모델을 써요. "
                + "같은 키를 다른 곳에서도 쓰면 실제 남은 양은 더 적을 수 있어요. 한도는 Google이 바꿀 수 있고, Google이 알려 준 한도가 있으면 그걸로 보여요."));
        }
        box.addView(ui.sectionHeader("최근 7일"));
        LinearLayout recent = recent(page.totals());
        if (recent.getChildCount() == 0) box.addView(ui.footer("아직 기록이 없어요. 답을 받을 때마다 쌓여요."));
        else box.addView(recent);
        box.addView(ui.footer("메인 대화·작업창·예약·누르고 말하기에서 받은 답만 세요."));
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
    private LinearLayout window(Limits.Window window, long now) {
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
    /**
     * Gemini 무료 한도 (core/GeminiQuota): a card with each model the app uses on its own, today's requests against the free
     * requests a day, when they are filled again, and a bar filled by what is left.
     */
    private LinearLayout geminiQuota(long now) {
        LinearLayout card = ui.column();
        android.graphics.drawable.GradientDrawable surface = ui.shape(Ui.CARD, 18); surface.setStroke(Math.max(1, ui.dp(.5f)), Ui.SEPARATOR);
        card.setBackground(surface); card.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(14));
        String[] models = {dev.jun.gyeot.core.GeminiWork.FLASH_LITE[0], dev.jun.gyeot.core.GeminiWork.FLASH_LITE[1], dev.jun.gyeot.core.GeminiProtocol.FILE_TRANSCRIBE};
        String reset = refill(dev.jun.gyeot.core.GeminiQuota.resetAt(now));
        for (dev.jun.gyeot.core.GeminiQuota.Row row : dev.jun.gyeot.core.GeminiQuota.rows(store.geminiQuota(), models, now)) {
            LinearLayout box = ui.column(); box.setPadding(0, ui.dp(12), 0, 0);
            box.addView(ui.text(row.label(), 15, 650, Ui.TEXT));
            LinearLayout line = ui.row(); line.setPadding(0, ui.dp(4), 0, ui.dp(8));
            String used = "오늘 " + row.uses() + (row.perDay() > 0 ? " / " + row.perDay() : "") + "회";
            line.addView(ui.text(row.status().isEmpty() ? reset : row.status(), 13, 450, row.status().isEmpty() ? Ui.MUTED : Ui.WARNING), new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(ui.text(used, 13, 500, Ui.TEXT));
            box.addView(line);
            double left = row.status().startsWith("오늘 한도") ? 0 : row.perDay() > 0 ? Math.max(0, Math.min(1, 1 - row.uses() / (double) row.perDay())) : 1;
            int ink = left < 0.1 ? Ui.ERROR : left < 0.25 ? Ui.WARNING : Ui.TEXT;
            LinearLayout track = ui.row(); track.setBackground(ui.shape(Ui.FILL, Ui.PILL)); track.setClipToOutline(true);
            android.view.View filled = new android.view.View(box.getContext()); filled.setBackground(ui.shape(ink, Ui.PILL));
            track.addView(filled, new LinearLayout.LayoutParams(0, -1, (float) left));
            track.addView(new android.view.View(box.getContext()), new LinearLayout.LayoutParams(0, -1, (float) (1 - left)));
            box.addView(track, new LinearLayout.LayoutParams(-1, ui.dp(8)));
            box.setContentDescription(row.label() + ", " + used + ", " + (row.status().isEmpty() ? reset : row.status()));
            card.addView(box);
        }
        return card;
    }
    /** "오후 5시에 다시 채워져요": when the free daily quotas reset, in the phone's own time. */
    static String refill(long at) {
        java.time.ZonedDateTime local = java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
        int hour = local.getHour(), minute = local.getMinute();
        return (hour < 12 ? "오전 " : "오후 ") + (hour % 12 == 0 ? 12 : hour % 12) + "시" + (minute > 0 ? " " + minute + "분" : "") + "에 다시 채워져요";
    }
    /** One row per company with requests in the last seven days, most requests first. */
    private LinearLayout recent(JSONArray totals) {
        LinearLayout group = ui.group();
        for (int i = 0; i < totals.length(); i++) {
            JSONObject total = totals.optJSONObject(i);
            LinearLayout row = ui.listRow(R.drawable.ic_sparkle, Ui.INDIGO, WorkBook.author(total.optString("company")), summary(total), null, null);
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
