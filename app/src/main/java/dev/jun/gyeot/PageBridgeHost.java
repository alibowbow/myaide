package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.CalendarQuery;
import dev.jun.gyeot.core.Jsons;
import dev.jun.gyeot.core.PageBridge;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 미니앱에 다리 놓기 (3단계): one page's window.gyeot (assets/bridge/gyeot.js) on the app's side, for a 프로젝트 (ProjectActivity,
 * over its port) or the user's own page on 들퍼레 홈 화면 (HomeActivity, through the home screen's page). Each call is checked
 * (core/PageBridge: its arguments, the safe tools, the page's limits), then its family's grant: allowed or 이번만 it runs, denied
 * it is refused at once, never asked it shows the compact sheet "‘가계부’가 일정을 읽으려 해요" [거부] [이번만] [허용]; the choice
 * is kept per page (page-grants, keyed "project:<id>" or "home:<src>": one page never reaches another's), listed and changed
 * in permissions(). The work: a notification on the app's channel (PageNotices), the calendar's titles, times and places, its
 * new-event screen filled in, 퍼레's memories without a secret, its remember, the 하루 기록's answer (DayLogs.recall), the light
 * models (Chores) and 퍼레's own acts (BuddyActs) for a safe few tools. Every call goes in 폰 조작 기록, a failure in 오류 진단.
 * Called on the main thread; reading the grants and the work run off it.
 */
final class PageBridgeHost {
    static final String GRANTS = "page-grants";
    /** Where answers go (the page's port, the home screen's frame); any thread. */
    interface Reply { void send(JSONObject message); }
    /** The light model a page's ask() goes to (Chores: the app's routing); tests put their own. */
    interface Brain { String answer(AppStore store, String instructions, String input) throws Exception; }
    static volatile Brain brain = (store, instructions, input) -> Chores.run(store, instructions, input, true);
    /** Sees every answer a page is sent ({page, message}): tests only. */
    static volatile java.util.function.BiConsumer<String, JSONObject> tap;

    private static final PageBridge.Limits LIMITS = new PageBridge.Limits();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-page-grants"); t.setDaemon(true); return t; });
    private static final ExecutorService JOBS = Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "gyeot-page-bridge"); t.setDaemon(true); return t; });
    private static final int PERMISSION = 7801;

    private final Activity activity; private final Ui ui; private final String key, where;
    private final java.util.function.Supplier<String> title; private final Reply reply;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Families allowed 이번만 (until the page closes), the calls waiting on a family's sheet (in order), the sheet up. */
    private final Set<String> once = new HashSet<>();
    private final Map<String, List<PageBridge.Call>> waiting = new LinkedHashMap<>();
    private Ui.Sheet showing; private boolean closed;
    private final AtomicInteger thinking = new AtomicInteger();

    /** key: "project:<id>" or "home:<src>"; where: where the user changes it later ("페이지 메뉴(⋮) → 권한"). */
    PageBridgeHost(Activity activity, Ui ui, String key, java.util.function.Supplier<String> title, String where, Reply reply) {
        this.activity = activity; this.ui = ui; this.key = key; this.title = title; this.where = where; this.reply = reply;
    }
    String key() { return key; }
    private String name() { String t = title.get(); return t == null || t.isBlank() ? "페이지" : t.strip(); }
    private AppStore store() { return ((GyeotApp) activity.getApplicationContext()).store(); }
    /** The page closed: nothing more is shown or answered; 이번만 ends. */
    void close() { closed = true; once.clear(); waiting.clear(); if (showing != null) { Ui.Sheet s = showing; showing = null; try { s.dismiss(); } catch (RuntimeException gone) {} } }
    /** 이번만 ends (the home screen left: its pages ask again next time). */
    void forgetOnce() { once.clear(); }

    // ── a call ──
    /** A page's message {type:"gyeot", id, call, args}. Main thread. */
    void handle(JSONObject m) {
        if (closed || m == null) return;
        String id = PageBridge.id(m);
        if (id.isEmpty()) return;
        long now = System.currentTimeMillis();
        PageBridge.Call call;
        try { call = PageBridge.read(m, now, ZoneId.systemDefault()); }
        catch (PageBridge.Refused r) {
            // Always answered; logged only a few times a minute (a page calling something wrong in a loop fills no log).
            JSONObject args = m.optJSONObject("args");
            boolean quiet = LIMITS.take(key, "refused", now) != null;
            answer(new PageBridge.Call(id, m.optString("call"), "", args == null ? new JSONObject() : args), PageBridge.no(id, r.code, r.getMessage()), quiet ? "" : r.getMessage());
            return;
        }
        String limited = LIMITS.take(key, PageBridge.bucket(call), now);
        if (limited != null) {
            // Logged (폰 조작 기록, 오류 진단) a few times a minute, so a page spamming calls fills neither.
            boolean quiet = LIMITS.take(key, "limited", now) != null;
            answer(call, PageBridge.no(id, PageBridge.LIMIT, limited), quiet ? "" : "한도 · " + limited);
            if (!quiet) diagnose(call, "한도: " + limited);
            return;
        }
        // No grant needed: the page's own states, or its own notification set for later taken away.
        if (call.family().isEmpty()) {
            IO.execute(() -> {
                if ("notify.cancel".equals(call.call())) { String which = call.args().optString("id"); answer(call, PageBridge.ok(id, Jsons.obj("cancelled", which.isEmpty() ? PageNotices.cancel(activity, key) : PageNotices.cancelOne(activity, key, which) ? 1 : 0)), "됨"); }
                else answer(call, PageBridge.ok(id, PageBridge.states(grants(activity, key))), "");
            });
            return;
        }
        IO.execute(() -> { String remembered = PageBridge.state(grants(activity, key), call.family()); main.post(() -> decide(call, remembered)); });
    }
    private void decide(PageBridge.Call call, String remembered) {
        if (closed) return;
        switch (PageBridge.decide(remembered, once.contains(call.family()))) {
            case "go" -> run(call);
            case "refuse" -> answer(call, PageBridge.no(call.id(), PageBridge.DENIED, PageBridge.deniedWhy(call.family()).replace("페이지 메뉴(⋮) → 권한", where)), "거부해 둠");
            default -> ask(call);
        }
    }
    private void ask(PageBridge.Call call) {
        List<PageBridge.Call> same = waiting.get(call.family());
        if (same != null) { same.add(call); return; }
        same = new ArrayList<>(); same.add(call); waiting.put(call.family(), same);
        next();
    }
    /** The next family's sheet, one at a time. */
    private void next() {
        if (closed || showing != null || waiting.isEmpty() || activity.isFinishing() || activity.isDestroyed()) return;
        String family = waiting.keySet().iterator().next();
        showing = sheet(waiting.get(family).get(0), choice -> chosen(family, choice));
    }
    private void chosen(String family, String choice) {
        showing = null;
        List<PageBridge.Call> calls = waiting.remove(family);
        if (calls == null) { next(); return; }
        if (PageBridge.ALLOW.equals(choice) || PageBridge.DENY.equals(choice)) IO.execute(() -> keep(activity, key, family, choice));
        if ("once".equals(choice)) once.add(family);
        String said = switch (choice) { case PageBridge.ALLOW -> "허용"; case "once" -> "이번만 허용"; case PageBridge.DENY -> "거부"; default -> "닫음"; };
        log(PageBridge.log(name(), calls.get(0).call(), calls.get(0).args(), PageBridge.label(family) + " " + said));
        for (PageBridge.Call c : calls) {
            if (PageBridge.ALLOW.equals(choice) || "once".equals(choice)) run(c);
            else answer(c, PageBridge.no(c.id(), PageBridge.DENIED, PageBridge.DENY.equals(choice) ? "사용자가 " + PageBridge.label(family) + " 쓰는 것을 거부했어요." : PageBridge.DISMISSED), said);
        }
        next();
    }
    private void run(PageBridge.Call call) {
        if (closed) return;
        if (PageBridge.AI.equals(call.family()) && thinking.get() >= 2) { answer(call, PageBridge.no(call.id(), PageBridge.LIMIT, "AI에게 묻고 있는 것이 끝나면 다시 해 주세요."), "한도 · 동시에 둘"); return; }
        boolean ai = PageBridge.AI.equals(call.family());
        if (ai) thinking.incrementAndGet();
        try {
            JOBS.execute(() -> {
                try {
                    Object value = perform(call);
                    answer(call, PageBridge.ok(call.id(), value), "됨");
                } catch (PageBridge.Refused r) {
                    answer(call, PageBridge.no(call.id(), r.code, r.getMessage()), r.getMessage());
                    if (PageBridge.FAILED.equals(r.code) || PageBridge.UNAVAILABLE.equals(r.code)) diagnose(call, r.getMessage());
                } catch (Exception | Error failed) {
                    String why = failed.getMessage() == null || failed.getMessage().isBlank() ? "하지 못했어요." : failed.getMessage();
                    answer(call, PageBridge.no(call.id(), PageBridge.FAILED, why), "실패 · " + why);
                    diagnose(call, failed.getClass().getSimpleName() + ": " + why);
                } finally { if (ai) thinking.decrementAndGet(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) { if (ai) thinking.decrementAndGet(); }
    }
    /** The answer to the page, and its line in 폰 조작 기록 ("" none). */
    private void answer(PageBridge.Call call, JSONObject message, String outcome) {
        java.util.function.BiConsumer<String, JSONObject> seen = tap;
        if (seen != null) seen.accept(key, message);
        if (!closed) try { reply.send(message); } catch (RuntimeException gone) { /* the page is gone */ }
        if (outcome != null && !outcome.isEmpty()) log(PageBridge.log(name(), call.call(), call.args(), outcome));
    }
    private void log(String line) { try { store().phoneLog("  · " + line); } catch (RuntimeException ignored) {} }
    private void diagnose(PageBridge.Call call, String why) { DiagnosticsExport.record(activity, "other", PageBridge.log(name(), call.call(), call.args(), why)); }

    // ── the work (off the main thread) ──
    private Object perform(PageBridge.Call call) throws Exception {
        JSONObject a = call.args(); Context app = activity.getApplicationContext(); AppStore store = store();
        switch (call.call()) {
            case "notify" -> {
                if (!PageNotices.allowed(app)) { allow(Manifest.permission.POST_NOTIFICATIONS); throw new PageBridge.Refused(PageBridge.OFF, "들퍼레 알림이 꺼져 있어요. 방금 뜬 창에서 허용하거나, 휴대폰 설정 → 앱 → 들퍼레 → 알림에서 켜 주세요."); }
                long at = a.optLong("at");
                if (at <= 0) {
                    int id = PageNotices.post(app, key, name(), a.optString("title"), a.optString("text"));
                    if (id < 0) throw new PageBridge.Refused(PageBridge.OFF, "들퍼레 알림이 꺼져 있어요.");
                    return Jsons.obj("id", String.valueOf(id), "at", System.currentTimeMillis());
                }
                return Jsons.obj("id", PageNotices.schedule(app, key, name(), a.optString("title"), a.optString("text"), at), "at", at);
            }
            case "calendar.list" -> {
                if (!PhoneCalendar.on(store)) throw new PageBridge.Refused(PageBridge.OFF, "설정 → 캘린더에서 일정 읽기가 꺼져 있어요.");
                if (!PhoneCalendar.readable(app)) { allow(Manifest.permission.READ_CALENDAR); throw new PageBridge.Refused(PageBridge.OFF, "캘린더를 읽을 권한이 없어요. 방금 뜬 창에서 허용하면 다시 볼 수 있어요."); }
                ZoneId zone = ZoneId.systemDefault(); long from = a.optLong("from"), to = a.optLong("to");
                CalendarQuery.Range range = new CalendarQuery.Range(Instant.ofEpochMilli(from).atZone(zone), Instant.ofEpochMilli(to).atZone(zone), "");
                return PageBridge.events(PhoneCalendar.events(app, range, false), from, to);
            }
            case "calendar.add" -> {
                // As 퍼레's calendar_add: the calendar app's own new-event screen, filled in; the user saves it.
                Intent add = PhoneCalendar.insert(a.optString("title"), a.optLong("start"), a.optLong("end"), a.optBoolean("allDay"), a.optString("place"), "");
                if (add.resolveActivity(app.getPackageManager()) == null) throw new PageBridge.Refused(PageBridge.UNAVAILABLE, "캘린더 앱을 못 찾았어요.");
                String route = DeviceActionRunner.start(activity, add, false);
                if (!DeviceActionRunner.started(route)) throw new PageBridge.Refused(PageBridge.OFF, "locked".equals(route) ? "화면이 잠겨 있어서 캘린더를 못 열었어요." : "지금은 캘린더를 열 수 없어요.");
                return Jsons.obj("opened", true);
            }
            case "memory.recall" -> {
                List<String> notes = new ArrayList<>();
                List<dev.jun.gyeot.core.BuddyMemory.Entry> mine = BuddyTalk.memories(app);
                mine.sort((x, y) -> Long.compare(y.at(), x.at()));
                for (dev.jun.gyeot.core.BuddyMemory.Entry e : mine) if (!e.secret() && dev.jun.gyeot.core.BuddyMemory.told(e.kind()) && !dev.jun.gyeot.core.BuddyMind.secret(e.text())) notes.add(e.text());
                if (store.memoryOn()) {
                    JSONArray kept = store.memories();
                    for (int i = kept.length() - 1; i >= 0; i--) { JSONObject n = kept.optJSONObject(i); if (n != null && dev.jun.gyeot.core.Memory.ABOUT.equals(n.optString("kind", dev.jun.gyeot.core.Memory.ABOUT)) && !n.optBoolean("secret")) notes.add(n.optString("text")); }
                }
                return new JSONArray(PageBridge.recall(a.optString("query"), notes, 5));
            }
            case "memory.remember" -> {
                BuddyVoice.Outcome o = BuddyActs.run(activity, Jsons.obj("tool", "remember", "fact", a.optString("text")), "", false);
                if (o == null || o.missed()) throw new PageBridge.Refused(PageBridge.FAILED, o == null || o.say().isBlank() ? "기억하지 못했어요." : o.say());
                return Jsons.obj("said", o.say().isBlank() ? "기억해 둘게요." : o.say());
            }
            case "day.recall" -> {
                DayLogs.Recall r = DayLogs.recall(app, Jsons.obj("query", a.optString("query")), a.optString("query"));
                if (r.off()) throw new PageBridge.Refused(PageBridge.OFF, r.say());
                return r.say();
            }
            case "ask" -> {
                String answer;
                try { answer = brain.answer(store, PageBridge.askInstructions(name(), a.optString("kind")), a.optString("prompt")); }
                catch (IllegalStateException none) { throw new PageBridge.Refused(PageBridge.OFF, none.getMessage()); }
                return PageBridge.askValue(answer, a.optString("kind"));
            }
            case "tool" -> { return tool(a.optString("name"), a.optJSONObject("args")); }
            default -> throw new PageBridge.Refused(PageBridge.BAD, "없는 기능이에요.");
        }
    }
    /** A safe tool (PageBridge.toolRefusal passed): weather here, the rest as 퍼레's own act with its guards (BuddyActs). */
    private Object tool(String name, JSONObject given) throws Exception {
        JSONObject args = given == null ? new JSONObject() : new JSONObject(given.toString());
        Context app = activity.getApplicationContext();
        if ("weather".equals(name)) {
            if (!Weather.allowed(app)) { allow(Manifest.permission.ACCESS_COARSE_LOCATION); throw new PageBridge.Refused(PageBridge.OFF, "날씨는 대략의 위치가 필요해요. 방금 뜬 창에서 허용하면 볼 수 있어요."); }
            JSONObject w = Weather.now(app);
            if (w == null) throw new PageBridge.Refused(PageBridge.UNAVAILABLE, "날씨를 받지 못했어요. 잠시 뒤에 다시 해 주세요.");
            JSONObject out = new JSONObject();
            for (String k : new String[]{"temp", "hi", "lo", "code", "day", "place"}) if (w.has(k)) out.put(k, w.opt(k));
            return out;
        }
        if ("when_event".equals(name)) {
            // What it will do when it fires, read as 퍼레 reads it: never a job (a phone, web or work job) made by a page.
            dev.jun.gyeot.core.Triggers.Draft d = dev.jun.gyeot.core.Triggers.read(args.optString("when"), args.optString("then"), args.optString("what"), args.optString("who"),
                args.optString("text"), args.has("every") ? args.optBoolean("every") : null, "");
            if (d.then() != null && dev.jun.gyeot.core.Triggers.JOB.equals(d.then().kind())) throw new PageBridge.Refused(PageBridge.BLOCKED, "조건 알림으로 일을 맡기는 건 페이지에서 할 수 없어요. 알림·앱 열기·길 안내·노래·문자 초안만 돼요.");
            args.put("command", "make");
        }
        if ("schedule_job".equals(name)) { args.remove("url"); args.remove("browser"); }
        args.put("tool", name);
        // A page's alarm or timer has its own numbers (checked): set as it says, as a typed request is, with no read-back.
        String heard = "alarm".equals(name) || "timer".equals(name) ? "네" : "";
        BuddyVoice.Outcome o = BuddyActs.run(activity, args, heard, false);
        if (o == null) throw new PageBridge.Refused(PageBridge.FAILED, "하지 못했어요.");
        if (o.confirm() != null || o.missed()) throw new PageBridge.Refused(PageBridge.FAILED, o.say().isBlank() ? "하지 못했어요." : o.say());
        return Jsons.obj("said", o.say());
    }
    /** Android's own permission, asked where the user is (one tap), when this page may use it and the phone has not allowed it. */
    private void allow(String permission) {
        if (Manifest.permission.POST_NOTIFICATIONS.equals(permission) && Build.VERSION.SDK_INT < 33) return;
        if (activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return;
        main.post(() -> { if (!closed && !activity.isFinishing()) try { activity.requestPermissions(new String[]{permission}, PERMISSION); } catch (RuntimeException refused) { /* the reason says it */ } });
    }

    // ── the sheet ──
    static int icon(String family) {
        return switch (family) {
            case PageBridge.NOTIFY -> R.drawable.ic_bell; case PageBridge.CALENDAR -> R.drawable.ic_calendar; case PageBridge.MEMORY -> R.drawable.ic_heart;
            case PageBridge.DAY -> R.drawable.ic_history; case PageBridge.AI -> R.drawable.ic_sparkle; default -> R.drawable.ic_tune;
        };
    }
    static int tint(String family) {
        return switch (family) {
            case PageBridge.NOTIFY -> Ui.ORANGE; case PageBridge.CALENDAR -> Ui.RED; case PageBridge.MEMORY -> Ui.PINK;
            case PageBridge.DAY -> Ui.TEAL; case PageBridge.AI -> Ui.PURPLE; default -> Ui.BLUE;
        };
    }
    /** What this one call is, in a few words (its title, its time, the tool's main argument); "" when nothing more. */
    private static String detail(PageBridge.Call call) {
        JSONObject a = call.args(); ZoneId zone = ZoneId.systemDefault();
        java.time.format.DateTimeFormatter clock = java.time.format.DateTimeFormatter.ofPattern("M월 d일 a h:mm", java.util.Locale.KOREA);
        return switch (call.call()) {
            case "notify" -> "‘" + a.optString("title") + "’" + (a.optLong("at") > 0 ? " · " + Instant.ofEpochMilli(a.optLong("at")).atZone(zone).format(clock) : "");
            case "calendar.add" -> "‘" + a.optString("title") + "’ · " + Instant.ofEpochMilli(a.optLong("start")).atZone(zone).format(a.optBoolean("allDay") ? java.time.format.DateTimeFormatter.ofPattern("M월 d일", java.util.Locale.KOREA) : clock);
            case "ask" -> { String p = a.optString("prompt").replaceAll("\\s+", " "); yield "‘" + (p.length() > 50 ? p.substring(0, 50) + "…" : p) + "’"; }
            case "memory.remember" -> "‘" + a.optString("text") + "’";
            case "tool" -> {
                JSONObject t = a.optJSONObject("args"); String v = "";
                if (t != null) for (String k : new String[]{"app", "destination", "query", "url", "when", "request", "text"}) if (!t.optString(k).isBlank()) { v = t.optString(k); break; }
                if (t != null && "timer".equals(a.optString("name"))) v = t.optLong("seconds") / 60 + "분 " + t.optLong("seconds") % 60 + "초";
                if (t != null && "alarm".equals(a.optString("name"))) v = String.format(java.util.Locale.ROOT, "%02d:%02d", t.optInt("hour"), t.optInt("minute"));
                yield v.isEmpty() ? "" : "‘" + (v.length() > 50 ? v.substring(0, 50) + "…" : v) + "’";
            }
            default -> "";
        };
    }
    /**
     * The compact sheet (준: 화면은 컴팩트하게): the page's ask as its title, one row of what allowing lets it do, what this call
     * is, and three small pills. Closed without a choice: this call refused, nothing kept.
     */
    private Ui.Sheet sheet(PageBridge.Call call, java.util.function.Consumer<String> chose) {
        String family = call.family();
        Ui.Sheet s = ui.selectionSheet(PageBridge.asking(name(), call.call(), call.args()), null);
        s.primary.setVisibility(View.GONE); s.footer.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        String what = detail(call);
        LinearLayout row = ui.listRow(icon(family), tint(family), PageBridge.label(family) + (what.isEmpty() ? "" : " · " + what), PageBridge.allows(family), null, null);
        // Read whole: what allowing lets the page do, on two lines at most on a narrow phone.
        if (row instanceof Ui.ListRow r && r.parts.subtitle() != null) { r.parts.subtitle().setSingleLine(false); r.parts.subtitle().setMaxLines(2); }
        ui.addRow(group, row, true);
        s.body.addView(group);
        boolean[] done = {false};
        java.util.function.Consumer<String> pick = choice -> { if (done[0]) return; done[0] = true; s.dismiss(); chose.accept(choice); };
        LinearLayout choices = ui.row(); choices.setGravity(Gravity.CENTER_VERTICAL | Gravity.END); choices.setPadding(0, ui.dp(10), 0, 0);
        TextView note = ui.text("이번만: 닫으면 다시 물어요", 12, 450, Ui.MUTED); note.setMaxLines(2);
        choices.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(6);
        choices.addView(ui.pill("거부", 0, false, () -> pick.accept(PageBridge.DENY)), gap);
        choices.addView(ui.pill("이번만", 0, false, () -> pick.accept("once")), gap);
        choices.addView(ui.pill("허용", R.drawable.ic_check, true, () -> pick.accept(PageBridge.ALLOW)), gap);
        s.body.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        s.setOnDismissListener(d -> { if (!done[0]) { done[0] = true; chose.accept(""); } });
        s.show();
        return s;
    }

    // ── grants, kept per page ──
    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(GRANTS, Context.MODE_PRIVATE); }
    /** The page's remembered choices. Off the main thread (the first read loads a file). */
    static JSONObject grants(Context c, String page) { return PageBridge.grants(prefs(c).getString(page, "")); }
    static synchronized void keep(Context c, String page, String family, String state) {
        // 알림 taken back: what it set for later goes first, then the choice.
        if (PageBridge.NOTIFY.equals(family) && !PageBridge.ALLOW.equals(state)) PageNotices.cancel(c, page);
        String now = PageBridge.granted(grants(c, page), family, state);
        if (PageBridge.grants(now).length() == 0) prefs(c).edit().remove(page).commit(); else prefs(c).edit().putString(page, now).commit();
    }
    /** A page removed: its choices, its waiting notifications and its limits go. Off the main thread. */
    static void forget(Context c, String page) {
        prefs(c).edit().remove(page).commit(); PageNotices.cancel(c, page); LIMITS.forget(page);
    }
    static String stateWord(String state) { return PageBridge.ALLOW.equals(state) ? "허용함" : PageBridge.DENY.equals(state) ? "거부함" : "쓸 때 물어봐요"; }

    /** This page's permissions, from its own screen; a change also ends 이번만 for that family here. */
    void permissions() { permissions(activity, ui, key, name(), once::remove); }
    /**
     * A page's permissions (페이지 메뉴 ⋮ → 권한, 홈 화면 꾸미기 → the page): each family's state in one compact list; a tap
     * gives 허용 · 쓸 때 묻기 · 거부. 알림 taken back cancels what the page set for later.
     */
    static void permissions(Activity a, Ui ui, String page, String title, java.util.function.Consumer<String> changed) {
        Ui.Sheet s = ui.selectionSheet("‘" + title + "’ 권한", "이 페이지가 쓸 수 있는 앱 기능");
        s.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group(); s.body.addView(group);
        TextView foot = ui.footer("처음 쓸 때 물어본 것을 여기서 바꿔요. 알림을 허용에서 바꾸면 예약해 둔 알림도 취소돼요."); s.body.addView(foot);
        Handler main = new Handler(Looper.getMainLooper());
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> IO.execute(() -> {
            JSONObject g = grants(a, page); int later = PageNotices.pending(a, page);
            main.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                group.removeAllViews();
                for (String family : PageBridge.FAMILIES) {
                    String state = PageBridge.state(g, family);
                    String sub = stateWord(state) + (PageBridge.NOTIFY.equals(family) && later > 0 ? " · 예약 알림 " + later + "개" : "");
                    LinearLayout[] row = new LinearLayout[1];
                    row[0] = ui.listRow(icon(family), tint(family), PageBridge.label(family), sub, ui.chevron(), () -> {
                        String[] labels = {"허용", "쓸 때 묻기", "거부"}; String[] states = {PageBridge.ALLOW, PageBridge.ASK, PageBridge.DENY};
                        int at = PageBridge.ALLOW.equals(state) ? 0 : PageBridge.DENY.equals(state) ? 2 : 1;
                        ui.miniMenu(row[0], labels, at, picked -> {
                            if (picked == at) return;
                            IO.execute(() -> {
                                keep(a, page, family, states[picked]);
                                try { ((GyeotApp) a.getApplicationContext()).store().phoneLog("  · " + PageBridge.log(title, "permissions", new JSONObject(), PageBridge.label(family) + " → " + labels[picked])); } catch (RuntimeException ignored) {}
                                main.post(() -> { if (changed != null) changed.accept(family); fill[0].run(); });
                            });
                        });
                    });
                    ui.addRow(group, row[0], true);
                }
            });
        });
        fill[0].run();
        s.show();
    }
    /** "허용 2 · 거부 1" for a menu line, or "" when nothing is kept. Off the main thread. */
    static String summary(Context c, String page) {
        JSONObject g = grants(c, page); int allow = 0, deny = 0;
        for (String f : PageBridge.FAMILIES) { String s = PageBridge.state(g, f); if (PageBridge.ALLOW.equals(s)) allow++; else if (PageBridge.DENY.equals(s)) deny++; }
        return allow + deny == 0 ? "" : (allow > 0 ? "허용 " + allow : "") + (allow > 0 && deny > 0 ? " · " : "") + (deny > 0 ? "거부 " + deny : "");
    }

    // ── the script ──
    private static volatile byte[] script;
    /** assets/bridge/gyeot.js, read once (on the web view's own request thread, never the main one). */
    static android.webkit.WebResourceResponse script(Context c) {
        try {
            byte[] js = script;
            if (js == null) { try (java.io.InputStream in = c.getAssets().open("bridge/gyeot.js")) { js = in.readAllBytes(); } script = js; }
            return new android.webkit.WebResourceResponse("text/javascript", "utf-8", 200, "OK", Map.of("Cache-Control", "no-store", "Access-Control-Allow-Origin", "*"), new java.io.ByteArrayInputStream(js));
        } catch (Exception gone) { return HtmlWallpaper.missing(); }
    }
}
