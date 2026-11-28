package dev.jun.gyeot;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import dev.jun.gyeot.core.AutomationGate;
import dev.jun.gyeot.core.BuddyJobs;
import dev.jun.gyeot.core.Macros;
import dev.jun.gyeot.core.PhoneGate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 매크로 실행: a macro's steps done again with no AI call, from wherever it was asked (퍼레, the voice assistant, its row, a
 * 조건 알림, a 예약 작업). Every step is a phone_action through PhoneService and its AutomationGate, as 퍼레's phone job:
 * no payment, transfer, deletion, password or banking app, ever. A step's control is looked for on the screen until it
 * shows and holds still (core/Macros.find: its words and view id, not where it was), for as long as the step took when
 * recorded and a margin; a scroll recorded before it scrolls only when the control is not on the screen yet. A run nobody
 * watches asks before it sends, posts or calls (a heads-up notification with 누르기·멈추기). It never runs on a locked phone:
 * a run that finds it locked leaves a notification that runs it once tapped after unlocking. While it runs, its line and
 * 중지 are 퍼레's (BuddyJobs) and an ongoing notification's; when it stops, the reason says which step, what was not found
 * and why ("‘주차 정산’ 3단계: ‘차량번호’ 칸을 찾지 못했어요 · 화면이 바뀌었어요").
 */
final class MacroRunner {
    private MacroRunner() {}
    static final String CHANNEL = "macros", ASK_CHANNEL = "macro_ask";
    static final int RUNNING = 4311, ASKING = 4312, RESULT = 4313, LATER = 4314;
    /** How long a run waits for the user's answer before a send, before it stops. */
    static final long ASK_MS = 120_000;
    /** Times are multiplied by this (tests make it small). */
    static volatile double pace = 1;
    private static long ms(long value) { return Math.max(1, Math.round(value * pace)); }

    /** The phone as a run uses it (tests put their own). */
    interface Phone {
        boolean ready();
        boolean locked(Context c);
        /** Phone control on for the run: true when this switched it on (it goes off again after). */
        boolean arm() throws Exception;
        PhoneGate.Task task();
        AutoCloseable watch(PhoneGate.Task control);
        /** A reading with each element's vid, cls and idx (PhoneService.macroScreen). */
        JSONObject read(PhoneGate.Task control) throws Exception;
        /** One phone_action under the gate, with goal as the user's words. */
        JSONObject act(PhoneGate.Task control, JSONObject args, String goal) throws Exception;
        void disarm();
        /**
         * A step whose control is gone, done once by 퍼레's phone agent (Macros.repairTask: that step alone, through the same
         * gate): what it did, and the control it used to keep (null: no phone AI connected, or it did not find one).
         */
        Repair repair(Context c, PhoneGate.Task control, Macros.Macro m, int index, Macros.Step step, boolean unattended) throws Exception;
    }
    /** What the phone agent made of a gone step: whether it did it, on which control (null when not known), and why not. */
    record Repair(boolean done, Macros.Target target, String why) {}
    static Phone phone = new Real();

    /**
     * Why a run stopped where it did (FAILED), or before a step the user does themselves (WAITING). missing: the step's app
     * was in front and its control was not there (the one a repair can mend).
     */
    static final class Stop extends Exception {
        final BuddyJobs.State how; final boolean missing;
        Stop(BuddyJobs.State how, String why) { this(how, why, false); }
        Stop(BuddyJobs.State how, String why, boolean missing) { super(why); this.how = how; this.missing = missing; }
    }

    /**
     * Starts a run, on its own thread. unattended: nobody asked just now (a 조건 알림, a 예약 작업): it asks before sending.
     * ended: told the job when the run ends (null for no one). The answer is BuddyHands' (its say is 퍼레's words).
     */
    static BuddyHands.Started start(Context c, Macros.Macro m, boolean unattended, String heard, Consumer<BuddyJobs.Job> ended) {
        Context app = c.getApplicationContext();
        AppStore store = ((GyeotApp) app).store();
        if (m == null) return no("그런 매크로가 없어. 한마디 루틴 화면에서 매크로를 녹화해 줘.", "settings:routines");
        if (!phone.ready()) return no("매크로를 실행하려면 접근성에서 ‘" + dev.jun.gyeot.core.Brand.NAME + " 폰 조작’을 켜 줘.", "accessibility");
        if (!store.phoneControl()) return no("‘음성 비서의 폰 조작 허용’이 꺼져 있어. 켜 주면 ‘" + m.name() + "’ 매크로를 할게.", "settings:phone");
        if (MacroRecorder.recording()) return no("매크로를 녹화하는 중이야. 녹화를 끝낸 뒤에 실행해 줘.", "");
        for (BuddyJobs.Job j : BuddyJobs.running()) if (BuddyJobs.PHONE.equals(j.kind)) return no("지금 ‘" + j.label + "’ 하고 있어. 끝나면 할게.", "");
        if (phone.locked(app)) {
            if (unattended) later(app, m, heard);
            return no("화면이 잠겨 있어서 ‘" + m.name() + "’ 매크로를 못 했어." + (unattended ? " 잠금을 풀고 알림을 누르면 할게." : " 잠금을 풀고 다시 말해 줘."), "");
        }
        boolean armedHere;
        try { armedHere = phone.arm(); } catch (Exception off) { return no("폰 조작을 켜지 못했어. " + message(off), "settings:phone"); }
        PhoneGate.Task control;
        try { control = phone.task(); } catch (RuntimeException off) { if (armedHere) phone.disarm(); return no(message(off), "settings:phone"); }
        BuddyJobs.Job job = BuddyJobs.start(BuddyJobs.PHONE, "‘" + m.name() + "’", "‘" + m.name() + "’ 매크로 실행", System.currentTimeMillis());
        BuddyJobs.stopper(job, () -> control.cancel("중지했어요"));
        BuddyJobs.opens(job, "phone");
        Thread t = new Thread(() -> run(app, store, m, job, control, unattended, armedHere, ended), "gyeot-macro");
        t.setDaemon(true); t.start();
        return new BuddyHands.Started(true, "‘" + m.name() + "’ 할게!", "", job);
    }
    private static BuddyHands.Started no(String why, String fix) { return new BuddyHands.Started(false, why, fix, null); }

    private static void run(Context c, AppStore store, Macros.Macro m, BuddyJobs.Job job, PhoneGate.Task control, boolean unattended, boolean armedHere, Consumer<BuddyJobs.Job> ended) {
        store.phoneLog("● 매크로 ‘" + m.name() + "’ · " + m.steps().size() + "단계" + (unattended ? " · 자동 실행" : ""));
        int[] at = {0};
        BuddyJobs.State how; String line;
        try (AutoCloseable watch = phone.watch(control)) {
            String after = ""; Macros.Step scroll = null;
            for (int i = 0; i < m.steps().size(); i++) {
                at[0] = i;
                Macros.Step step = m.steps().get(i);
                control.check();
                BuddyJobs.progress(job, i + 1, appName(c, step.app()), Macros.what(step));
                ongoing(c, m, i, job);
                if (step.stop() || step.secret()) throw new Stop(BuddyJobs.State.WAITING, Macros.line(m, i, Macros.stopped(step)));
                switch (step.kind()) {
                    // Looked for only when the next control is not on the screen yet (a fling is no fixed distance).
                    case Macros.SCROLL -> { scroll = step; continue; }
                    case Macros.OPEN -> open(c, m, i, step, control);
                    case Macros.TAP, Macros.LONG, Macros.TYPE -> targeted(c, m, i, step, scroll, after, control, unattended);
                    default -> {
                        if (Macros.asks(step, Macros.SEND.equals(step.kind()) ? "보내기" : "", unattended) && !ask(c, m, i, Macros.what(step), control))
                            throw new Stop(BuddyJobs.State.FAILED, Macros.line(m, i, "‘" + Macros.what(step) + "’ 전에 답이 없어서 멈췄어요"));
                        JSONObject value = phone.act(control, Macros.action(step), Macros.goal(m, i));
                        if (!value.optBoolean("ok")) throw new Stop(BuddyJobs.State.FAILED, Macros.line(m, i, Macros.what(step) + " · " + value.optString("error", "되지 않았어요")));
                    }
                }
                scroll = null; after = step.kind();
                store.phoneLog("  · " + (i + 1) + ". " + Macros.what(step));
            }
            how = BuddyJobs.State.DONE; line = "‘" + m.name() + "’ 다 했어요 · " + m.steps().size() + "단계";
        } catch (Stop stop) { how = stop.how; line = stop.getMessage(); }
        catch (Exception error) {
            String stopped = control.stopped();
            how = stopped != null ? BuddyJobs.State.STOPPED : BuddyJobs.State.FAILED;
            line = stopped != null ? "‘" + m.name() + "’ " + (at[0] + 1) + "단계에서 중지했어요" : Macros.line(m, at[0], message(error));
        } finally {
            if (armedHere && !store.voiceActive) phone.disarm();
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) { nm.cancel(RUNNING); nm.cancel(ASKING); }
        }
        store.phoneLog((how == BuddyJobs.State.DONE ? "✓ " : "✗ ") + line);
        MacroBook.ran(c, m.id(), System.currentTimeMillis(), (how == BuddyJobs.State.DONE ? "✓ " : "") + line);
        // Something to tell later: a stop (where and why), and whatever ran while nobody watched (told before the job ends).
        if (how != BuddyJobs.State.DONE || unattended) result(c, m, line, how);
        BuddyJobs.end(job, how, line, System.currentTimeMillis());
        if (ended != null) try { ended.accept(job); } catch (RuntimeException ignored) { /* the caller's */ }
    }

    // ── one step ──
    private static void open(Context c, Macros.Macro m, int i, Macros.Step step, PhoneGate.Task control) throws Exception {
        JSONObject value = phone.act(control, Macros.action(step), Macros.goal(m, i));
        if (!value.optBoolean("ok")) throw new Stop(BuddyJobs.State.FAILED, Macros.line(m, i, AutomationGate.refused(value) ? value.optString("error") : Macros.missing(step) + " · " + value.optString("error", "")));
        long until = System.currentTimeMillis() + ms(10_000);
        String seen = "";
        while (System.currentTimeMillis() < until) {
            control.check();
            JSONObject screen = phone.read(control);
            if (screen.optBoolean("ok") && step.app().equals(screen.optString("app"))) return;
            seen = screen.optString("app_label", screen.optString("app"));
            Thread.sleep(ms(400));
        }
        throw new Stop(BuddyJobs.State.FAILED, Macros.line(m, i, Macros.missing(step) + (seen.isEmpty() ? "" : " · 지금 ‘" + seen + "’ 화면이에요")));
    }
    /** A control found where it is now, in that reading. */
    private record Found(JSONObject screen, Macros.Match match) {}
    private static void targeted(Context c, Macros.Macro m, int i, Macros.Step step, Macros.Step scroll, String after, PhoneGate.Task control, boolean unattended) throws Exception {
        long until = System.currentTimeMillis() + ms(Macros.patience(step));
        boolean asked = false;
        while (true) {
            Found found;
            try { found = locate(m, i, step, scroll, after, control, until); }
            catch (Stop gone) {
                if (!gone.missing) throw gone;
                // Its control is gone (an update moved or renamed it): 퍼레's phone agent does this one step, once, and the macro
                // keeps the control it used, so the next run needs no AI again.
                Repair r = phone.repair(c, control, m, i, step, unattended);
                if (r == null) throw gone;
                if (!r.done()) throw new Stop(BuddyJobs.State.FAILED, gone.getMessage() + " · AI로 찾아봐도 없었어요");
                if (r.target() != null) MacroBook.change(c, all -> { Macros.Macro now = Macros.byId(all, m.id()); return now == null ? all : Macros.save(all, Macros.repaired(now, i, r.target())); });
                ((GyeotApp) c.getApplicationContext()).store().phoneLog("  · " + (i + 1) + "단계를 AI가 찾아서 했어요" + (r.target() == null ? "" : " · ‘" + r.target().label() + "’로 고쳐 둠"));
                return;
            }
            String label = label(found);
            if (!asked && Macros.asks(step, label, unattended)) {
                if (!ask(c, m, i, "‘" + label + "’ 누르기", control)) throw new Stop(BuddyJobs.State.FAILED, Macros.line(m, i, "‘" + label + "’ 누르기 전에 답이 없어서 멈췄어요"));
                asked = true;
            }
            JSONObject args = Macros.action(step);
            put(args, "element", found.match.id()); put(args, "snapshot_id", found.screen.optString("snapshot_id"));
            JSONObject value = phone.act(control, args, Macros.goal(m, i));
            if (value.optBoolean("ok")) return;
            if (AutomationGate.refused(value)) throw new Stop(BuddyJobs.State.FAILED, Macros.line(m, i, value.optString("error")));
            // Known not to have run (the screen moved between the reading and the tap): found again and tried again. A step that
            // may have run is never done twice.
            boolean notRun = "not_dispatched".equals(value.optString("execution_status")) || Boolean.FALSE.equals(value.opt("action_executed"));
            if (!notRun || System.currentTimeMillis() > until) throw new Stop(BuddyJobs.State.FAILED, Macros.line(m, i, Macros.what(step) + " · " + value.optString("error", "되지 않았어요")));
        }
    }
    /**
     * The step's control on the screen, once it shows and holds still (found twice in the same place), before until. When it
     * is not there and the screen is still: a scroll recorded before it scrolls that way until it shows or the list ends;
     * after typing, Enter is pressed once (a search the keyboard's key ran); right after an app opened, the app is opened once
     * more from its first screen (it came up where it was left; on its first screen already, nothing is lost). Otherwise the
     * run stops, saying what was not found and what shows.
     */
    private static Found locate(Macros.Macro m, int i, Macros.Step step, Macros.Step scroll, String after, PhoneGate.Task control, long until) throws Exception {
        int[] held = null; String still = ""; boolean scrollEnd = scroll == null, entered = !Macros.TYPE.equals(after), restarted = !Macros.OPEN.equals(after);
        long since = System.currentTimeMillis(); String other = "", error = "";
        while (System.currentTimeMillis() < until) {
            control.check();
            JSONObject screen = phone.read(control);
            if (!screen.optBoolean("ok")) {
                error = screen.optString("error");
                if (phone.locked(null)) throw new Stop(BuddyJobs.State.FAILED, Macros.line(m, i, "폰이 잠겼어요 · 잠금을 풀고 다시 실행해 주세요"));
                held = null; Thread.sleep(ms(400)); continue;
            }
            String app = screen.optString("app");
            if (!step.app().isEmpty() && !step.app().equals(app)) {
                other = screen.optString("app_label", app); held = null;
                Thread.sleep(ms(400)); continue;
            }
            other = "";
            Macros.Match match = Macros.find(step.target(), screen.optJSONArray("elements"), Macros.size(screen));
            if (match != null) {
                if (held != null && java.util.Arrays.equals(held, match.box())) return new Found(screen, match);
                held = match.box(); Thread.sleep(ms(250)); continue;
            }
            held = null;
            String sign = signature(screen); boolean calm = sign.equals(still); still = sign;
            if (calm) {
                if (!scrollEnd) {
                    Macros.Match list = scroll.target() == null ? null : Macros.find(scroll.target(), screen.optJSONArray("elements"), Macros.size(screen));
                    JSONObject args = Macros.action(scroll);
                    if (list != null) { put(args, "element", list.id()); put(args, "snapshot_id", screen.optString("snapshot_id")); }
                    JSONObject value = phone.act(control, args, Macros.goal(m, i));
                    if (!value.optBoolean("ok")) scrollEnd = true;
                    // Scrolled and nothing new came: the list's end.
                    JSONObject next = phone.read(control);
                    if (!next.optBoolean("ok") || signature(next).equals(sign)) scrollEnd = true;
                    still = ""; continue;
                }
                if (!entered) { entered = true; phone.act(control, obj("action", "press_enter"), Macros.goal(m, i)); still = ""; continue; }
                if (!restarted && System.currentTimeMillis() - since > ms(2500)) {
                    restarted = true;
                    JSONObject fresh = Macros.action(Macros.Step.open(step.app(), "", 0)); put(fresh, "fresh", true);
                    phone.act(control, fresh, Macros.goal(m, i)); still = ""; continue;
                }
            }
            Thread.sleep(ms(400));
        }
        String why = !other.isEmpty() ? "지금 ‘" + other + "’ 화면이에요" : !error.isEmpty() ? error : "화면이 바뀌었어요";
        throw new Stop(BuddyJobs.State.FAILED, Macros.line(m, i, Macros.missing(step) + " · " + why), other.isEmpty() && error.isEmpty());
    }
    /** The words a reading's element shows (for the gate's kind and the ask), else the step's. */
    private static String label(Found found) {
        JSONArray all = found.screen.optJSONArray("elements");
        for (int k = 0; all != null && k < all.length(); k++) {
            JSONObject e = all.optJSONObject(k);
            if (e != null && found.match.id().equals(e.optString("id"))) { String t = e.optString("text"); return t.isEmpty() || e.optString("flags").contains("edit") ? e.optString("desc") : t; }
        }
        return "";
    }
    /** What a reading shows, to tell a screen that moved from one that holds still. */
    static String signature(JSONObject screen) {
        StringBuilder out = new StringBuilder(screen.optString("app"));
        JSONArray all = screen.optJSONArray("elements");
        for (int k = 0; all != null && k < all.length(); k++) { JSONObject e = all.optJSONObject(k); if (e != null) out.append('|').append(e.optString("text")).append(e.optString("desc")).append(e.optJSONArray("bounds")); }
        return out.toString();
    }

    // ── asking before a send ──
    private static final Map<String, CompletableFuture<Boolean>> ASKS = new ConcurrentHashMap<>();
    /** The answer to an ask (MacroReceiver). */
    static void answer(String id, boolean yes) { CompletableFuture<Boolean> f = ASKS.remove(id == null ? "" : id); if (f != null) f.complete(yes); }
    /** Asks the user (a heads-up notification) whether to do what, waiting ASK_MS; false for no, no answer or a stop. */
    static boolean ask(Context c, Macros.Macro m, int i, String what, PhoneGate.Task control) throws Exception {
        String id = "a" + Long.toString(System.nanoTime(), 36);
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        ASKS.put(id, answer);
        NotificationManager nm = channel(c, true);
        String text = (i + 1) + "단계: " + what + " 할까요?";
        if (nm != null) {
            PendingIntent yes = PendingIntent.getBroadcast(c, ASKING * 10 + 1, new Intent(c, MacroReceiver.class).setAction(MacroReceiver.ANSWER).putExtra(MacroReceiver.ID, id).putExtra(MacroReceiver.YES, true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent no = PendingIntent.getBroadcast(c, ASKING * 10 + 2, new Intent(c, MacroReceiver.class).setAction(MacroReceiver.ANSWER).putExtra(MacroReceiver.ID, id).putExtra(MacroReceiver.YES, false), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(c, ASK_CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("‘" + m.name() + "’ 매크로").setContentText(text)
                .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "할게요", yes).build()).addAction(new Notification.Action.Builder(null, "멈추기", no).build()).build();
            try { nm.notify(ASKING, n); } catch (SecurityException off) { /* no way to ask: no answer */ }
        }
        try {
            long until = System.currentTimeMillis() + ms(ASK_MS);
            while (System.currentTimeMillis() < until) {
                control.check();
                try { return answer.get(ms(200), TimeUnit.MILLISECONDS); } catch (java.util.concurrent.TimeoutException wait) { /* again */ }
            }
            return false;
        } finally { ASKS.remove(id); if (nm != null) nm.cancel(ASKING); }
    }

    // ── notifications ──
    static NotificationManager channel(Context c, boolean asking) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return null;
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "매크로", NotificationManager.IMPORTANCE_LOW));
        if (asking && nm.getNotificationChannel(ASK_CHANNEL) == null) nm.createNotificationChannel(new NotificationChannel(ASK_CHANNEL, "매크로 확인", NotificationManager.IMPORTANCE_HIGH));
        return nm;
    }
    /** "‘출근 체크’ 실행 중 · 2/6단계 · ‘주차 정산’ 누르기" with 중지. */
    private static void ongoing(Context c, Macros.Macro m, int i, BuddyJobs.Job job) {
        NotificationManager nm = channel(c, false);
        if (nm == null) return;
        PendingIntent stop = PendingIntent.getBroadcast(c, RUNNING, new Intent(c, MacroReceiver.class).setAction(MacroReceiver.RUN_STOP).putExtra(MacroReceiver.ID, job.id), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("‘" + m.name() + "’ 실행 중")
            .setContentText((i + 1) + "/" + m.steps().size() + "단계 · " + Macros.what(m.steps().get(i)))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS).setProgress(m.steps().size(), i, false)
            .addAction(new Notification.Action.Builder(null, "중지", stop).build()).build();
        try { nm.notify(RUNNING, n); } catch (SecurityException off) { /* the home line still has 중지 */ }
    }
    private static void result(Context c, Macros.Macro m, String line, BuddyJobs.State how) {
        NotificationManager nm = channel(c, false);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(c, RESULT, MacrosUi.opening(c, m.id()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("‘" + m.name() + "’ 매크로" + (how == BuddyJobs.State.DONE ? "" : how == BuddyJobs.State.WAITING ? " · 직접 할 차례" : " · 멈춤"))
            .setContentText(line).setStyle(new Notification.BigTextStyle().bigText(line)).setContentIntent(open).setAutoCancel(true).build();
        try { nm.notify(RESULT, n); } catch (SecurityException off) { /* notifications are off */ }
    }
    /** A locked phone: a notification whose tap (after unlocking) runs it, as a 조건 알림's waiting act (TriggerActivity). */
    private static void later(Context c, Macros.Macro m, String heard) {
        NotificationManager nm = channel(c, false);
        if (nm == null) return;
        String id = TriggerStore.pend(c, obj("tool", Macros.TOOL, "command", "run", "name", m.name()), heard);
        PendingIntent tap = PendingIntent.getActivity(c, LATER, TriggerActivity.fire(c, id), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = "화면이 잠겨 있어서 하지 않았어요 · 잠금을 풀고 누르면 실행해요";
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("‘" + m.name() + "’ 매크로").setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(tap).setAutoCancel(true).build();
        try { nm.notify(LATER, n); } catch (SecurityException off) { /* notifications are off */ }
    }
    private static String appName(Context c, String pkg) { String l = MacroRecorder.label(c, pkg); return l.isEmpty() ? "" : l; }
    private static String message(Throwable e) { return e == null || e.getMessage() == null || e.getMessage().isBlank() ? "결과를 확인하지 못했어요" : e.getMessage(); }

    // ── the phone as it is ──
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final class Real implements Phone {
        @Override public boolean ready() { return PhoneService.connected(); }
        @Override public boolean locked(Context c) {
            Context ctx = c != null ? c : PhoneService.overlayContext();
            if (ctx == null) return false;
            KeyguardManager lock = ctx.getSystemService(KeyguardManager.class); PowerManager power = ctx.getSystemService(PowerManager.class);
            return lock != null && lock.isDeviceLocked() || power != null && !power.isInteractive();
        }
        @Override public boolean arm() throws Exception {
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            Runnable arming = () -> { try { boolean was = PhoneService.gate.armed(); if (!was) PhoneService.arm(); done.complete(!was); } catch (RuntimeException e) { done.completeExceptionally(e); } };
            if (Looper.myLooper() == Looper.getMainLooper()) arming.run(); else MAIN.post(arming);
            try { return done.get(10, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException e) { throw e.getCause() instanceof Exception x ? x : new IllegalStateException(e.getCause()); }
        }
        @Override public PhoneGate.Task task() { return PhoneService.gate.task(); }
        @Override public AutoCloseable watch(PhoneGate.Task control) { return PhoneService.gate.watch(control.ticket, () -> control.cancel("폰 조작이 중지됐어요.")); }
        @Override public JSONObject read(PhoneGate.Task control) throws Exception {
            CompletableFuture<JSONObject> f = new CompletableFuture<>();
            PhoneService.macroScreen(control.ticket, control::check, f::complete);
            long until = System.currentTimeMillis() + 12_000;
            while (true) {
                control.check();
                try { return f.get(200, TimeUnit.MILLISECONDS); }
                catch (java.util.concurrent.TimeoutException wait) { if (System.currentTimeMillis() > until) return obj("ok", false, "error", "화면을 읽지 못했어요 (시간 초과)"); }
            }
        }
        @Override public JSONObject act(PhoneGate.Task control, JSONObject args, String goal) throws Exception {
            CompletableFuture<JSONObject> f = new CompletableFuture<>();
            PhoneService.run("phone_action", args, control.ticket, control::check, goal, false, f::complete);
            JSONObject value = dev.jun.gyeot.core.PhoneTools.awaitResult("phone_action", args, f, control::check);
            PhoneService.takeTiming(value);
            return value;
        }
        @Override public void disarm() { MAIN.post(PhoneService::stop); }
        /** Thrown by the agent's tools once the one step is done: the agent stops there. */
        private static final class Mended extends RuntimeException { Mended() { super("mended", null, false, false); } }
        @Override public Repair repair(Context c, PhoneGate.Task control, Macros.Macro m, int i, Macros.Step step, boolean unattended) throws Exception {
            AppStore store = ((GyeotApp) c.getApplicationContext()).store();
            java.util.concurrent.atomic.AtomicReference<Runnable> abort = new java.util.concurrent.atomic.AtomicReference<>();
            String task = Macros.repairTask(m, i, step);
            dev.jun.gyeot.core.PhoneAgent.Model model = brain(store, task, abort, control);
            if (model == null) return null;
            store.phoneLog("  · " + (i + 1) + "단계를 못 찾아서 AI에게 한 번 물어요");
            JSONObject[] reading = {null}; Macros.Target[] used = {null}; boolean[] done = {false};
            dev.jun.gyeot.core.PhoneAgent.Tools tools = (name, args) -> {
                control.check();
                if ("phone_screen".equals(name)) { JSONObject s = read(control); if (s.optBoolean("ok")) reading[0] = s; return s; }
                if (!"phone_action".equals(name)) return obj("ok", false, "error", "이 단계에서는 화면 읽기와 그 동작만 해요.");
                String why = Macros.repairRefusal(step, args);
                if (!why.isEmpty()) return obj("ok", false, "action_executed", false, "execution_status", "not_dispatched", "retryable", false, "error", why);
                boolean own = !java.util.Set.of("scroll_down", "scroll_up", "wait").contains(args.optString("action"));
                Macros.Target target = own ? Macros.element(reading[0], args.optString("element")) : null;
                if (own && Macros.asks(step, target == null ? "" : target.label(), unattended))
                    return obj("ok", false, "action_executed", false, "execution_status", "not_dispatched", "retryable", false, "error", "보내기·게시·전화는 사용자가 지켜볼 때만 해요.");
                JSONObject value = act(control, own ? Macros.repairArgs(step, args) : args, Macros.goal(m, i));
                if (own && value.optBoolean("ok")) { used[0] = target; done[0] = true; throw new Mended(); }
                return value;
            };
            try (dev.jun.gyeot.core.PhoneAgent.Model brain = model;
                 AutoCloseable watch = PhoneService.gate.watch(control.ticket, () -> { Runnable a = abort.getAndSet(null); if (a != null) a.run(); })) {
                new dev.jun.gyeot.core.PhoneAgent().run(task, brain, tools, control::check, true);
            } catch (Mended mended) { /* the one step is done */ }
            catch (Exception failed) { if (!done[0]) return new Repair(false, null, message(failed)); }
            return new Repair(done[0], used[0], "");
        }
        /** The phone agent's brain as 퍼레's phone job picks it (BuddyHands): Gemini Decision or GPT; null when neither is connected. */
        private static dev.jun.gyeot.core.PhoneAgent.Model brain(AppStore store, String task, java.util.concurrent.atomic.AtomicReference<Runnable> abort, PhoneGate.Task control) throws Exception {
            boolean gpt = store.openai.connected() && !store.openai.model().isBlank();
            boolean decisions = store.phoneDecisions() || !gpt && store.secrets.hasKey();
            if (!decisions && !gpt) return null;
            return decisions
                ? new dev.jun.gyeot.core.DecisionPhone.Model(GeminiDecisions.client(store.secrets.get(), abort), task, "", line -> store.phoneLog("  · " + line))
                : OpenAiClient.phoneModel(store.openai, store.openai.model(), dev.jun.gyeot.core.GeminiProtocol.clock(java.time.ZonedDateTime.now()),
                    store.openai.effectiveReasoningEffort(store.openai.model()), () -> {}, abort, control::check, false, store::phoneLog);
        }
    }
}
