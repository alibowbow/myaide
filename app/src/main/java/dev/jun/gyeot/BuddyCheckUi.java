package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

import static dev.jun.gyeot.Ui.*;

/**
 * 퍼레 자동 점검 (준: "내가 자꾸 테스트를 많이 해봐야하는데 자동테스트도 구현해줘 ㅜ"): one tap, no talking. The words of
 * assets/buddy/scenarios.json through what 퍼레 answers with no AI (BuddyCheck.words), the phone's readiness (permissions,
 * keys, the model hosts, the taskbar, the LP page), then the same words through the very path the voice talk
 * (BuddyTalk.reply) and 퍼레와 대화 (BuddyTalk.chatText) take to 퍼레's answer, as a dry run: the thing it would do is only
 * written down (nothing is opened, set, sent or said). One request at a time; stops when the brain cannot be reached and
 * says why. The sheet shows the counts and only what failed, each with what was said, expected and done, and the tap
 * that clears the way when there is one; the report goes into the 오류 진단 보고서 to share.
 */
final class BuddyCheckUi {
    private BuddyCheckUi() {}
    /** Runs the check off the main thread; tests run it in place. */
    static Executor runner = task -> { Thread t = new Thread(task, "gyeot-buddy-check"); t.setDaemon(true); t.start(); };
    /** One answer of 퍼레's brain to a scenario: the thing it would do (null: none), its words, why it could not (null: it did), the times. */
    record Answer(JSONObject act, String text, String failed, long toolMs, long wordsMs, String by) {}
    /** How 퍼레's brain is asked (path: BuddyCheck.VOICE or CHAT); tests give their own. Off the main thread. */
    interface Brain { Answer ask(Context c, BuddyCheck.Scenario s, String path, AtomicBoolean cancelled); }
    static Brain brain = BuddyCheckUi::ask;
    /** The phone's readiness; tests give their own. Off the main thread (it reaches the model hosts). */
    interface Ready { List<BuddyCheck.Result> check(Context c); }
    static Ready ready = BuddyCheckUi::readiness;
    /** Whether a brain is connected for the check (tests say yes). */
    interface Connected { boolean any(Context c); }
    static Connected connected = c -> { AppStore s = ((GyeotApp) c.getApplicationContext()).store(); return GroqWork.on() || s.nim.connected() || BuddyTalk.CUSTOM.equals(BuddyTalk.brain(c)) && !BuddyTalk.customLabel(s).isEmpty(); };
    /** The whole brain part stops after this long (a slow brain on every scenario), saying how far it got. */
    static long budgetMs = 8 * 60_000L;
    private static final String PREFS = "buddyTalk", LAST = "checkLast";

    /** The last run's one line ("통과 150 · 실패 3 · 11-27 15:03"), for the row that opens it; "" before the first. */
    static String last(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, ""); }

    /** The check's sheet, run as it opens; again from its 다시 chip. */
    static Ui.Sheet show(Activity activity) { return show(activity, null); }
    /** As above; closed: after the sheet is closed (the row that opened it shows the new last line). */
    static Ui.Sheet show(Activity activity, Runnable closed) {
        Ui ui = new Ui(activity); Handler main = new Handler(Looper.getMainLooper());
        Ui.Sheet sheet = ui.selectionSheet("퍼레 자동 점검", "말 · 두뇌 · 준비 상태 · 실제로 하지는 않아요");
        sheet.primary.setVisibility(View.GONE);
        AtomicBoolean[] cancelled = {new AtomicBoolean()}; boolean[] busy = {false};
        Runnable[] run = new Runnable[1];
        run[0] = () -> {
            if (busy[0]) return; busy[0] = true;
            AtomicBoolean stop = new AtomicBoolean(); cancelled[0] = stop;
            Run state = new Run();
            draw(activity, ui, sheet, state, run[0], stop);
            runner.execute(() -> {
                check(activity.getApplicationContext(), state, stop, () -> main.post(() -> { if (sheet.isShowing()) draw(activity, ui, sheet, state, run[0], stop); }));
                main.post(() -> { busy[0] = false; if (sheet.isShowing()) draw(activity, ui, sheet, state, run[0], stop); });
            });
        };
        sheet.setOnDismissListener(d -> { cancelled[0].set(true); if (closed != null) closed.run(); });
        sheet.show(); run[0].run();
        return sheet;
    }

    /** What a run has found so far (written on the runner, read on the main thread). */
    static final class Run {
        final List<BuddyCheck.Result> results = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String now = "점검 문장을 읽는 중…", voiceBy = "", chatBy = "", stopped = "", when = "";
        volatile boolean done;
        /** Which brains answered: "음성 Groq · openai/gpt-oss-120b · 글 Groq · qwen/qwen3-32b" ("" before any). */
        String brain() {
            List<String> out = new ArrayList<>();
            if (!voiceBy.isBlank()) out.add("음성 " + voiceBy.replaceAll("[a-z0-9-]+/", ""));
            if (!chatBy.isBlank()) out.add("글 " + chatBy.replaceAll("[a-z0-9-]+/", ""));
            return String.join(" · ", out);
        }
        List<BuddyCheck.Result> copy() { synchronized (results) { return new ArrayList<>(results); } }
    }

    /** The whole check, on the runner: the words, the readiness, then the brain on both paths. changed: after each step. */
    static void check(Context c, Run run, AtomicBoolean cancelled, Runnable changed) {
        List<BuddyCheck.Scenario> all;
        try (InputStream in = c.getAssets().open("buddy/scenarios.json")) { all = BuddyCheck.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
        catch (Exception broken) {
            run.results.add(BuddyCheck.Result.of(BuddyCheck.WORDS, "점검 문장 파일", false, "읽힘", broken.getMessage() == null ? "읽지 못함" : broken.getMessage(), ""));
            finish(c, run); changed.run(); return;
        }
        run.results.addAll(BuddyCheck.words(all)); run.now = "준비 상태를 보는 중…"; changed.run();
        if (cancelled.get()) { run.stopped = "닫아서 멈췄어요"; finish(c, run); return; }
        try { run.results.addAll(ready.check(c)); } catch (RuntimeException failed) { run.results.add(BuddyCheck.Result.of(BuddyCheck.READY, "준비 상태", false, "확인됨", "확인하지 못함: " + failed.getClass().getSimpleName(), "")); }
        changed.run();
        if (!connected.any(c)) {
            run.results.add(BuddyCheck.Result.of(BuddyCheck.BRAIN, "퍼레 두뇌", false, "Groq나 NVIDIA 연결", "연결된 두뇌가 없어 두뇌 점검은 못 했어요", "settings:groq"));
            run.stopped = "연결된 두뇌 없음"; finish(c, run); changed.run(); return;
        }
        List<BuddyCheck.Scenario> asked = new ArrayList<>(); for (BuddyCheck.Scenario s : all) if (s.toBrain()) asked.add(s);
        long begun = SystemClock.elapsedRealtime(); int total = asked.size() * 2, n = 0;
        outer:
        for (String path : List.of(BuddyCheck.VOICE, BuddyCheck.CHAT)) {
            int failedInRow = 0, answered = 0;
            for (BuddyCheck.Scenario s : asked) {
                if (cancelled.get()) { run.stopped = "닫아서 멈췄어요 (" + n + "/" + total + ")"; break outer; }
                if (SystemClock.elapsedRealtime() - begun > budgetMs) { run.stopped = "시간이 오래 걸려 " + n + "/" + total + "개에서 멈췄어요"; break outer; }
                run.now = "두뇌 · " + path + " " + (++n) + "/" + total + " · “" + s.said() + "”"; changed.run();
                Answer a;
                try { a = brain.ask(c, s, path, cancelled); } catch (RuntimeException e) { a = new Answer(null, "", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 0, 0, ""); }
                if (cancelled.get()) { run.stopped = "닫아서 멈췄어요 (" + (n - 1) + "/" + total + ")"; break outer; }
                if (a.by() != null && !a.by().isBlank()) { if (path.equals(BuddyCheck.CHAT)) run.chatBy = a.by(); else run.voiceBy = a.by(); }
                BuddyCheck.Result r = BuddyCheck.judge(s, path, a.act(), a.text(), a.failed(), a.toolMs(), a.wordsMs());
                if (a.failed() == null) { answered++; failedInRow = 0; run.results.add(r); }
                // The brain out of reach: its first answer failed, or two in a row did. Said, with the brain's settings one tap away, not tried on every scenario.
                else if (answered == 0 || ++failedInRow >= 2) {
                    String fix = a.failed().contains("NVIDIA") ? "settings:nim" : "settings:groq";
                    run.results.add(new BuddyCheck.Result(r.layer(), r.path(), r.said(), false, r.expected(), r.got(), fix, r.toolMs(), r.wordsMs()));
                    run.stopped = "두뇌(" + path + ")가 답하지 않아 멈췄어요: " + a.failed(); break outer;
                } else run.results.add(r);
                changed.run();
            }
        }
        finish(c, run); changed.run();
    }
    /** The run's end: its time, the last line for the row, and the report into the 오류 진단 보고서. */
    private static void finish(Context c, Run run) {
        run.when = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new java.util.Date());
        List<BuddyCheck.Result> results = run.copy();
        run.now = ""; run.done = true;
        int[] all = BuddyCheck.count(results, null);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST, "통과 " + all[0] + " · 실패 " + (all[1] - all[0]) + " · " + run.when).apply();
        List<dev.jun.gyeot.core.DiagnosticReport.Check> lines = new ArrayList<>();
        for (String[] l : BuddyCheck.lines(results, run.brain(), run.when, run.stopped, dev.jun.gyeot.core.DiagnosticReport.CHECK_LIMIT)) lines.add(new dev.jun.gyeot.core.DiagnosticReport.Check(l[0], l[1]));
        DiagnosticsExport.buddyChecked(c, lines);
    }

    // ── the sheet ──
    private static void draw(Activity activity, Ui ui, Ui.Sheet sheet, Run run, Runnable again, AtomicBoolean stop) {
        sheet.body.removeAllViews();
        List<BuddyCheck.Result> results = run.copy();
        List<BuddyCheck.Result> failed = BuddyCheck.failures(results);
        int[] all = BuddyCheck.count(results, null);
        // One line: the counts, then 다시 · 보고서 (or 멈추기 while it runs) at its end.
        LinearLayout head = ui.row(); head.setPadding(ui.dp(4), 0, 0, ui.dp(2));
        String verdict = !run.done ? "확인 중 · 통과 " + all[0] + " · 실패 " + (all[1] - all[0]) : failed.isEmpty() ? "모두 통과 " + all[0] : "통과 " + all[0] + " · 실패 " + failed.size();
        TextView title = ui.text(verdict, HEADLINE, 700, !run.done ? TEXT : failed.isEmpty() ? SUCCESS : ERROR); title.setAccessibilityHeading(true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (run.done) {
            head.addView(ui.smallChip("다시", again));
            head.addView(ui.smallChip("보고서", () -> DiagnosticsExport.show(activity, List.of())));
        } else head.addView(ui.smallChip("멈추기", () -> stop.set(true)));
        sheet.body.addView(head);
        List<String> parts = new ArrayList<>();
        for (String layer : List.of(BuddyCheck.WORDS, BuddyCheck.BRAIN, BuddyCheck.READY)) { int[] c = BuddyCheck.count(results, layer); if (c[1] > 0) parts.add(layer + " " + c[0] + "/" + c[1]); }
        String speed = BuddyCheck.speed(results);
        String sub = String.join(" · ", parts) + (run.brain().isBlank() ? "" : " · " + run.brain());
        TextView counts = ui.text(sub, FOOTNOTE, 450, MUTED); counts.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8)); sheet.body.addView(counts);
        if (!run.now.isEmpty()) {
            TextView now = ui.text(run.now, FOOTNOTE, 500, MUTED); now.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            now.setSingleLine(true); now.setEllipsize(android.text.TextUtils.TruncateAt.END); now.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8)); sheet.body.addView(now);
        }
        if (!run.stopped.isBlank()) { TextView why = ui.text("멈춤: " + run.stopped, FOOTNOTE, 550, WARNING); why.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(8)); sheet.body.addView(why); }
        if (!failed.isEmpty()) {
            LinearLayout group = ui.group();
            for (BuddyCheck.Result r : failed) ui.addRow(group, row(activity, ui, sheet, r), false);
            sheet.body.addView(group);
        }
        if (run.done) {
            TextView foot = ui.footer((speed.isEmpty() ? "" : speed + "\n") + "결과는 오류 진단 보고서에도 넣었어요.");
            sheet.body.addView(foot);
        }
    }
    /** One failure: what was said (or the item), what was expected and what happened, and its fix's tap when there is one. */
    private static View row(Activity activity, Ui ui, Ui.Sheet sheet, BuddyCheck.Result r) {
        Intent fix = fixIntent(activity, r.fix());
        View chip = fix == null ? null : ui.smallChip(fixLabel(r.fix()), () -> { try { activity.startActivity(fix); } catch (RuntimeException none) { Toast.makeText(activity, "그 화면을 열지 못했어요", Toast.LENGTH_SHORT).show(); } });
        String sub = r.tag() + " · 기대 " + r.expected() + " · 실제 " + r.got();
        LinearLayout row = ui.listRow(0, 0, r.title(), sub, chip, null);
        TextView s = ((Ui.ListRow) row).parts.subtitle(); if (s != null) { s.setSingleLine(false); s.setMaxLines(3); }
        row.setMinimumHeight(ui.dp(48));
        row.setContentDescription(r.tag() + " 실패: " + r.said() + ". 기대 " + r.expected() + ". 실제 " + r.got());
        return row;
    }
    /** The tap that clears the way, as its chip's words ("" for none). */
    static String fixLabel(String fix) {
        if (fix == null || fix.isEmpty()) return "";
        return switch (fix) {
            case "mic" -> "마이크 허용"; case "notices" -> "알림 접근"; case "home" -> "홈 앱 고르기"; case "voiceInput" -> "음성 인식 설정";
            case "network" -> "네트워크"; case "pages" -> "페이지 넣기";
            default -> fix.startsWith("settings:") ? "키 넣기" : BuddyMisses.fixLabel(fix);
        };
    }
    /** That tap's screen, or null. */
    static Intent fixIntent(Context c, String fix) {
        if (fix == null || fix.isEmpty()) return null;
        return switch (fix) {
            case "mic" -> new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
            case "notices" -> new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            case "home" -> new Intent(Settings.ACTION_HOME_SETTINGS);
            case "voiceInput" -> new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);
            case "network" -> new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            case "pages" -> new Intent(c, DecorActivity.class);
            default -> fix.startsWith("settings:") ? new Intent(c, MainActivity.class).putExtra("tab", 2).putExtra(MainActivity.SETTINGS_PAGE, fix.substring(9)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) : BuddyMisses.fixIntent(c, fix);
        };
    }

    // ── the brain, dry ──
    /**
     * One scenario through 퍼레's own path: the voice talk (BuddyTalk.reply, as BuddyVoice asks it, the name put back first)
     * with the thing to do only recorded (Ask.dry), or 퍼레와 대화 (BuddyTalk.chatText) whose "할일:" is only read. Times:
     * until the thing was chosen (the voice talk does it after the whole answer), until the first sentence to say.
     */
    static Answer ask(Context c, BuddyCheck.Scenario s, String path, AtomicBoolean cancelled) {
        long begun = SystemClock.elapsedRealtime();
        if (path.equals(BuddyCheck.CHAT)) {
            AtomicLong first = new AtomicLong(); AtomicReference<Runnable> cancel = new AtomicReference<>();
            try {
                String text = BuddyTalk.chatText(c, s.said(), "[]", cancel, () -> !cancelled.get(), d -> { if (!d.isBlank()) first.compareAndSet(0, SystemClock.elapsedRealtime()); });
                long end = SystemClock.elapsedRealtime();
                JSONObject act = BuddyTalk.act(text);
                String shown = text.replaceAll("(?m)^\\s*할\\s*일\\s*[:：].*$", "").strip();
                return new Answer(act, shown, text.isBlank() ? "빈 답" : null, end - begun, first.get() == 0 ? 0 : first.get() - begun, BuddyTalk.chatBy);
            } catch (java.util.concurrent.CancellationException stopped) { return new Answer(null, "", "멈춤", 0, 0, ""); }
            catch (Exception failed) { return new Answer(null, "", BuddyTalk.why("글 두뇌", failed, 30_000), SystemClock.elapsedRealtime() - begun, 0, BuddyTalk.chatBy); }
        }
        CountDownLatch over = new CountDownLatch(1);
        AtomicReference<JSONObject> act = new AtomicReference<>(); AtomicReference<String> line = new AtomicReference<>(""), why = new AtomicReference<>();
        AtomicLong chosen = new AtomicLong(), first = new AtomicLong(), ended = new AtomicLong();
        BuddyTalk.Ask dry = new BuddyTalk.Ask(BuddyTalk.heard(c, s.said()), List.of(), false,
            a -> { act.set(a); chosen.compareAndSet(0, SystemClock.elapsedRealtime()); },
            part -> first.compareAndSet(0, SystemClock.elapsedRealtime()),
            l -> { line.set(l == null ? "" : l); ended.set(SystemClock.elapsedRealtime()); over.countDown(); },
            w -> { why.set(w == null || w.isBlank() ? "답 없음" : w); ended.set(SystemClock.elapsedRealtime()); over.countDown(); }, false, "", true);
        BuddyTalk.reply(c, dry);
        try { if (!over.await(30, TimeUnit.SECONDS)) return new Answer(null, "", "30초 안에 답이 없음", 30_000, 0, BuddyTalk.answeredBy); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return new Answer(null, "", "멈춤", 0, 0, ""); }
        long tool = (chosen.get() != 0 ? chosen.get() : ended.get()) - begun;
        return new Answer(act.get(), line.get(), why.get(), Math.max(1, tool), first.get() == 0 ? 0 : first.get() - begun, BuddyTalk.answeredBy);
    }

    // ── the phone's readiness ──
    /** Permissions, keys, the speech recognizer, the model hosts, the taskbar and the LP page, each with its fix. Off the main thread. */
    static List<BuddyCheck.Result> readiness(Context c) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        List<BuddyCheck.Result> out = new ArrayList<>();
        out.add(item("마이크 권한", c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED, "허용", "허용 안 됨 · 음성 대화를 못 해요", "mic"));
        out.add(item("폰 조작(접근성)", PhoneService.connected(), "켜짐", "꺼짐 · 앱 끄기·작업 표시줄·안내 시작 누르기를 못 해요", "accessibility"));
        out.add(item("알림 접근(음악 조작)", NoticeListener.allowed(c), "허용", "허용 안 됨 · 다음 곡·멈춤을 못 해요", "notices"));
        out.add(item("다른 앱 위에 보이기", Settings.canDrawOverlays(c), "허용", "허용 안 됨 · 다른 앱 위에서 퍼레가 못 떠요", "overlay"));
        boolean home = Taskbar.homeHeld(c);
        out.add(item("기본 홈 앱", home, "들퍼레", "다른 홈 앱 · ‘LP 페이지로’ 같은 홈 화면 넘기기를 못 해요", "home"));
        out.add(item("Gemini 키(목소리·음악 만들기)", store.secrets.hasWorkKey(), "있음", "없음 · Lyria 음악 만들기를 못 해요", "settings:key"));
        out.add(item("Typecast 키(퍼레 목소리)", store.typecast.connected(), "있음", "없음 · 휴대폰 기본 목소리로 말해요", "settings:typecast"));
        out.add(item("Groq(퍼레 귀·빠른 두뇌)", GroqWork.on(), "연결됨", "연결 안 됨 · 다른 앱 위에서 퍼레 귀를 못 써요", "settings:groq"));
        out.add(item("NVIDIA NIM(두뇌)", store.nim.connected(), "연결됨", "연결 안 됨 · Groq가 막히면 대신 답할 두뇌가 없어요", "settings:nim"));
        out.add(item("휴대폰 음성 인식", SpeechInput.available(c), "있음", "없음 · Android 설정에서 음성 인식 서비스를 확인해 주세요", "voiceInput"));
        // The hosts the brains and voices are reached at, only those with a key (one request each, no key sent).
        if (store.nim.connected()) out.add(reach("NVIDIA 서버", "integrate.api.nvidia.com"));
        if (GroqWork.on()) out.add(reach("Groq 서버", "api.groq.com"));
        if (store.secrets.hasWorkKey()) out.add(reach("Gemini 서버", "generativelanguage.googleapis.com"));
        if (store.typecast.connected()) out.add(reach("Typecast 서버", "api.typecast.ai"));
        // The taskbar (TaskbarPlan, Taskbar.status): off is fine; on, what keeps it away.
        String bar = Taskbar.status(c, store);
        boolean barOk = !store.taskbar() || bar.startsWith("켜짐 · 지금") || bar.startsWith("켜짐 · 다른 앱") || bar.startsWith("켜짐 · 키보드") || bar.startsWith("켜짐 · 폰 조작이 끝나면") || bar.startsWith("켜짐 · 큰 화면");
        out.add(BuddyCheck.Result.of(BuddyCheck.READY, "작업 표시줄", barOk, "켜면 다른 앱 아래에 나옴", bar, barOk ? "" : !home ? "home" : !PhoneService.connected() ? "accessibility" : ""));
        // The LP page: on the home screen's pages, and its piece fetched with its fixes.
        if (home) {
            boolean placed = false;
            for (dev.jun.gyeot.core.HomeBoard.Slot slot : dev.jun.gyeot.core.HomeBoard.slots(c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).getString(HomeActivity.PAGES, ""), "", ""))
                if ((slot.src() + " " + slot.title()).toLowerCase(Locale.ROOT).matches("(?s).*(vinyl|lp|턴테이블|레코드).*")) placed = true;
            out.add(item("홈 화면의 LP 페이지", placed, "있음", "없음 · ‘LP 페이지로’가 갈 곳이 없어요", "pages"));
        }
        HtmlWallpaper.Piece piece = null; try { piece = HtmlWallpaper.piece(c, PieceExtras.LP); } catch (RuntimeException offline) { /* below */ }
        out.add(BuddyCheck.Result.of(BuddyCheck.READY, "LP 작품 불러오기", piece != null && piece.tuned() != null, "불러와서 고침까지 맞음",
            piece == null ? "못 불러옴 · 인터넷을 확인해 주세요" : piece.tuned() == null ? "작품이 바뀌어 고침이 안 맞아요(원본으로 떠요)" : "불러와서 고침까지 맞음", piece == null ? "network" : ""));
        return out;
    }
    private static BuddyCheck.Result item(String name, boolean ok, String yes, String no, String fix) {
        return BuddyCheck.Result.of(BuddyCheck.READY, name, ok, yes, ok ? yes : no, ok ? "" : fix);
    }
    /** Whether host answers at all (any HTTP status, in a few seconds); nothing but the request line goes. */
    private static BuddyCheck.Result reach(String name, String host) {
        long begun = SystemClock.elapsedRealtime();
        try {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) java.net.URI.create("https://" + host + "/").toURL().openConnection();
            h.setRequestMethod("HEAD"); h.setConnectTimeout(5000); h.setReadTimeout(5000); h.setInstanceFollowRedirects(false);
            int status = h.getResponseCode(); h.disconnect();
            return BuddyCheck.Result.of(BuddyCheck.READY, name, true, "닿음", "닿음 · HTTP " + status + " · " + BuddyCheck.secs(SystemClock.elapsedRealtime() - begun), "");
        } catch (Exception unreachable) {
            return BuddyCheck.Result.of(BuddyCheck.READY, name, false, "닿음", "닿지 않음 · " + host + " · " + unreachable.getClass().getSimpleName(), "network");
        }
    }
}
