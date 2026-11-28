package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 매크로 (준: "매크로 활용방안 구상하고 적용"): what the user did by hand once in other apps, kept as steps and done again later
 * with no AI call. Pure Java: the steps and how they are kept, how a recording becomes steps (Recording: what the
 * accessibility events say, cleaned: an app opened from the home screen is one open step, typing in a field is one step,
 * a password is never kept), how a step's control is found again on a changed screen (find: its text, description,
 * hint and view id first, its place only as a last resort, tolerant of small changes like a count in a label), the words
 * that run one ("퍼레야 출근 체크 해 줘") and the plain words said when one stops ("‘주차 정산’ 3단계: ‘차량번호’ 칸을 찾지
 * 못했어요 · 화면이 바뀌었어요").
 *
 * Safety is the phone control's own: every replayed step goes through PhoneService and its AutomationGate (no payment,
 * transfer, deletion, password or banking app). Here, on top: a step on a payment or deletion control is kept as a stop
 * ("‘결제하기’는 직접 눌러 주세요"), a banking app's steps are not kept at all, and a run nobody is watching (a 조건 알림, a
 * 예약 작업) asks before it sends, posts or calls (asks).
 */
public final class Macros {
    private Macros() {}
    // ── what a step does ──
    public static final String OPEN = "open", TAP = "tap", LONG = "long", TYPE = "type", SCROLL = "scroll", BACK = "back", HOME = "home",
        ENTER = "enter", SEND = "send";
    public static final Set<String> KINDS = Set.of(OPEN, TAP, LONG, TYPE, SCROLL, BACK, HOME, ENTER, SEND);
    /** The steps that need a control found on the screen. */
    static final Set<String> TARGETED = Set.of(TAP, LONG, TYPE);
    /** The voice assistant's function, and 퍼레's tool. */
    public static final String FUNCTION = "run_macro", TOOL = "macro";
    /** A name as shown (longer ones are cut). */
    public static final int NAME = 30;

    /**
     * A control as recorded: its view id, text (never a field's typed value), description, hint, class, role, place among its
     * siblings, bounds and the screen's size then, whether it is a field, and host: the clickable row that took the click when
     * what was recorded is the label inside it (null when the control is itself labeled).
     */
    public record Target(String vid, String text, String desc, String hint, String cls, String role, int idx, int[] box, int[] screen, boolean edit, Target host) {
        public Target {
            vid = s(vid); text = clip(text, 120); desc = clip(desc, 120); hint = clip(hint, 120); cls = s(cls); role = s(role);
            box = box == null || box.length != 4 ? new int[4] : box; screen = screen == null || screen.length != 2 ? new int[2] : screen;
            if (edit) text = "";
        }
        /** What a person calls it: its text, description or hint, else its host's ("" for an unlabeled one). */
        public String label() {
            for (String v : new String[]{text, desc, hint}) if (!v.isEmpty() && !v.equals("[비밀번호]")) return v;
            return host == null ? "" : host.label();
        }
        /** Whether anything but its place names it (text, description, hint or view id). */
        public boolean named() { return !text.isEmpty() || !desc.isEmpty() || !hint.isEmpty() || !vid.isEmpty(); }
        public JSONObject json() {
            JSONObject o = obj("box", arr(box[0], box[1], box[2], box[3]), "screen", arr(screen[0], screen[1]));
            for (String[] f : new String[][]{{"vid", vid}, {"text", text}, {"desc", desc}, {"hint", hint}, {"cls", cls}, {"role", role}}) if (!f[1].isEmpty()) put(o, f[0], f[1]);
            if (idx >= 0) put(o, "idx", idx);
            if (edit) put(o, "edit", true);
            if (host != null) put(o, "host", host.json());
            return o;
        }
        public static Target of(JSONObject o) {
            if (o == null) return null;
            return new Target(o.optString("vid"), o.optString("text"), o.optString("desc"), o.optString("hint"), o.optString("cls"), o.optString("role"),
                o.optInt("idx", -1), ints(o.optJSONArray("box"), 4), ints(o.optJSONArray("screen"), 2), o.optBoolean("edit"), of(o.optJSONObject("host")));
        }
    }
    /**
     * One step: its kind, the app (package) it happens in, the screen (window class, "" unknown), the control (TAP, LONG, TYPE;
     * the list for a SCROLL), the text (TYPE: what to type; OPEN: the app's name), dir (SCROLL: "down" or "up"), gap (ms since
     * the step before when it was recorded), secret (a password or code field: never typed, the run stops there), stop (a
     * payment, deletion or banking step: the run stops before it and says so).
     */
    public record Step(String kind, String app, String screen, Target target, String text, String dir, long gap, boolean secret, boolean stop) {
        public Step { kind = s(kind); app = s(app); screen = s(screen); text = text == null ? "" : text; dir = s(dir); gap = Math.max(0, gap); if (secret) text = ""; }
        public static Step open(String pkg, String label, long gap) { return new Step(OPEN, pkg, "", null, s(label), "", gap, false, false); }
        public static Step of(String kind, String app, Target t, long gap) { return new Step(kind, app, "", t, "", "", gap, false, false); }
        Step with(String newText, long newGap) { return new Step(kind, app, screen, target, newText, dir, newGap, secret, stop); }
        Step stopping() { return new Step(kind, app, screen, target, text, dir, gap, secret, true); }
        public JSONObject json() {
            JSONObject o = obj("kind", kind);
            if (!app.isEmpty()) put(o, "app", app);
            if (!screen.isEmpty()) put(o, "screen", screen);
            if (target != null) put(o, "target", target.json());
            if (!text.isEmpty()) put(o, "text", text);
            if (!dir.isEmpty()) put(o, "dir", dir);
            if (gap > 0) put(o, "gap", gap);
            if (secret) put(o, "secret", true);
            if (stop) put(o, "stop", true);
            return o;
        }
        public static Step of(JSONObject o) {
            if (o == null || !KINDS.contains(o.optString("kind"))) return null;
            Step step = new Step(o.optString("kind"), o.optString("app"), o.optString("screen"), Target.of(o.optJSONObject("target")), o.optString("text"),
                o.optString("dir"), o.optLong("gap"), o.optBoolean("secret"), o.optBoolean("stop"));
            if (TARGETED.contains(step.kind) && step.target == null || OPEN.equals(step.kind) && step.app.isEmpty()) return null;
            return step;
        }
    }
    /** A macro: the user's name for it, its steps, when it was made and last ran, how many runs, and how the last run went. */
    public record Macro(String id, String name, List<Step> steps, long made, long ran, int runs, String last) {
        public Macro { id = s(id).isEmpty() ? "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) : s(id); name = clip(name, NAME); steps = List.copyOf(steps); last = s(last); }
        public Macro named(String to) { return new Macro(id, to, steps, made, ran, runs, last); }
        public Macro ranAt(long at, String how) { return new Macro(id, name, steps, made, at, runs + 1, how); }
        public Macro withSteps(List<Step> to) { return new Macro(id, name, to, made, ran, runs, last); }
        public JSONObject json() {
            JSONArray list = arr(); for (Step step : steps) list.put(step.json());
            JSONObject o = obj("id", id, "name", name, "steps", list, "made", made);
            if (ran > 0) put(o, "ran", ran);
            if (runs > 0) put(o, "runs", runs);
            if (!last.isEmpty()) put(o, "last", last);
            return o;
        }
        public static Macro of(JSONObject o) {
            if (o == null || key(o.optString("name")).isEmpty()) return null;
            List<Step> steps = new ArrayList<>(); JSONArray list = o.optJSONArray("steps");
            for (int i = 0; list != null && i < list.length(); i++) { Step step = Step.of(list.optJSONObject(i)); if (step != null) steps.add(step); }
            if (steps.isEmpty()) return null;
            return new Macro(o.optString("id"), o.optString("name"), steps, o.optLong("made"), o.optLong("ran"), o.optInt("runs"), o.optString("last"));
        }
        /** The apps it opens, by the names they were recorded with ("카카오T"). */
        public List<String> apps() {
            List<String> out = new ArrayList<>();
            for (Step step : steps) if (OPEN.equals(step.kind) && !step.text.isEmpty() && !out.contains(step.text)) out.add(step.text);
            return out;
        }
    }

    // ── kept ──
    /** The stored list; anything unreadable is left out (an unreadable list is an empty one). */
    public static List<Macro> parse(String stored) {
        List<Macro> out = new ArrayList<>();
        try {
            JSONArray list = stored == null || stored.isBlank() ? arr() : new JSONArray(stored);
            for (int i = 0; i < list.length(); i++) { Macro m = Macro.of(list.optJSONObject(i)); if (m != null && find(out, m.name) == null) out.add(m); }
        } catch (Exception unreadable) { /* none */ }
        return out;
    }
    public static String json(List<Macro> list) { JSONArray out = arr(); for (Macro m : list) out.put(m.json()); return out.toString(); }
    /** The list with m added, or put in place of the one with its id. Says why when its name is taken or empty. */
    public static List<Macro> save(List<Macro> list, Macro m) {
        if (key(m.name).isEmpty()) throw new IllegalArgumentException("매크로 이름을 적어 주세요.");
        List<Macro> out = new ArrayList<>(); boolean put = false;
        for (Macro x : list) {
            if (x.id.equals(m.id)) { out.add(m); put = true; continue; }
            if (key(x.name).equals(key(m.name))) throw new IllegalArgumentException("‘" + x.name + "’ 매크로가 이미 있어요. 다른 이름을 적어 주세요.");
            out.add(x);
        }
        if (!put) out.add(m);
        return out;
    }
    public static List<Macro> remove(List<Macro> list, String id) { List<Macro> out = new ArrayList<>(); for (Macro m : list) if (!m.id.equals(id)) out.add(m); return out; }
    public static Macro byId(List<Macro> list, String id) { for (Macro m : list) if (m.id.equals(id)) return m; return null; }
    /** The one with this name, however it is spaced or marked. */
    public static Macro find(List<Macro> list, String name) {
        String k = key(name);
        if (k.isEmpty()) return null;
        for (Macro m : list) if (key(m.name).equals(k)) return m;
        return null;
    }
    /** A free name: base, else base 2, base 3 … ("카카오T 매크로", "카카오T 매크로 2"). */
    public static String freeName(List<Macro> list, String base) {
        String b = clip(s(base).isEmpty() ? "매크로" : base, NAME - 3);
        if (find(list, b) == null) return b;
        for (int n = 2; ; n++) if (find(list, b + " " + n) == null) return b + " " + n;
    }
    /** Words as compared: lower case, no spaces or marks. */
    public static String key(String v) { return v == null ? "" : v.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…~‘’“”「」]+", ""); }

    // ── the words that start, end or run one ──
    // The words around a name that ask for it ("출근 체크 해 줘", "출근 체크 매크로 실행", "퍼레야 출근체크 돌려 줘").
    private static final Pattern FILLER = Pattern.compile("^(?:퍼레야|퍼레|들퍼레야|들퍼레)|매크로|(?:을|를)?(?:실행|돌려|재생|시작|진행)?(?:해|하)?(?:줘|주세요|줄래|줄래요|주라|봐|볼래|라|요|하자)?$");
    private static final Pattern RUN_WORDS = Pattern.compile("(?:실행|돌려|재생|시작|진행|해|하자|해줘|해주세요|줘)$");
    /**
     * The macro the words ask for: they are its name with only asking words around it ("출근 체크 해 줘", "퍼레야 출근체크"), or
     * they say 매크로 and hold its name ("아까 만든 출근 체크 매크로 돌려 줘"). A name inside other words ("출근 체크하고 메일도
     * 봐 줘", "배민에서 지난번 메뉴 담기") is no ask: those go on as ordinary words. Null when none.
     */
    public static Macro named(List<Macro> list, String said) {
        if (list == null || list.isEmpty() || said == null) return null;
        String bare = key(said);
        if (bare.isEmpty()) return null;
        String stripped = asking(bare);
        Macro best = null;
        for (Macro m : list) {
            String k = key(m.name);
            if (k.length() < 2) continue;
            boolean exact = bare.equals(k) || stripped.equals(k) || stripped.equals(asking(k))
                || stripped.startsWith(k) && RUN_WORDS.matcher(stripped.substring(k.length())).matches();
            boolean mentioned = bare.contains("매크로") && bare.contains(k);
            if ((exact || mentioned) && (best == null || k.length() > key(best.name).length())) best = m;
        }
        return best;
    }
    /** Words (in key form) without the asking words around them ("퍼레야", "매크로", "실행해 줘"). */
    private static String asking(String bare) {
        String out = bare;
        for (int i = 0; i < 3; i++) { String next = FILLER.matcher(out).replaceAll(""); if (next.equals(out)) break; out = next; }
        return out;
    }
    // Starting one needs the word 매크로: a bare "녹화해 줘" is the screen recording's.
    private static final Pattern RECORD = Pattern.compile("^(.*?)\\s*매크로\\s*(?:를|을|로)?\\s*(?:새로\\s*)?(?:녹화|기록|만들)");
    private static final Pattern STOP = Pattern.compile("(?:매크로\\s*)?녹화\\s*(?:를|을)?\\s*(?:끝|그만|멈춰|중지|종료|다\\s*했어|완료|저장)|매크로\\s*(?:를|을)?\\s*(?:저장|끝)");
    private static final Pattern SAVE_OFFER = Pattern.compile("매크로\\s*(?:로|에)?\\s*(?:저장|만들어|남겨)");
    private static final Pattern GIVEN_NAME = Pattern.compile("[‘'\"“]([^’'\"”]+)[’'\"”]|이름(?:은|을)\\s*(.+?)(?:\\s*(?:으로|로|라고))?\\s*(?:해|저장|$)");
    /**
     * What 퍼레 does with no brain for words about macros: {"tool":"macro","command":…,"name":…}, or null. recording: whether a
     * recording is on (its 끝 words only count then); offer: whether a phone job just done waits to be saved as one.
     */
    public static JSONObject local(List<Macro> list, String said, boolean recording, boolean offer) {
        String w = said == null ? "" : said.strip().replaceAll("^퍼레야\\s*", "");
        if (w.isEmpty() || w.length() > 60) return null;
        if (recording && STOP.matcher(w).find()) return obj("tool", TOOL, "command", "stop");
        if (offer && SAVE_OFFER.matcher(w).find()) {
            Matcher n = GIVEN_NAME.matcher(w);
            String name = n.find() ? s(n.group(1) != null ? n.group(1) : n.group(2)) : "";
            return obj("tool", TOOL, "command", "save", "name", name);
        }
        Matcher r = RECORD.matcher(w);
        if (r.find()) {
            String name = s(r.group(1)).replaceAll("^(?:새로운|새|내)\\s*", "").replaceAll("\\s*(?:이라는|라는|이란|란)$", "");
            return recording ? obj("tool", TOOL, "command", "record") : obj("tool", TOOL, "command", "record", "name", name);
        }
        Macro m = named(list, w);
        return m == null ? null : obj("tool", TOOL, "command", "run", "name", m.name);
    }

    // ── a recording ──
    /**
     * What the accessibility events of one recording say, made into steps as they come. The phone's own windows (this app,
     * the system UI, the keyboard: ignored) and the home screen (launchers) take no steps: an app that comes to the front from
     * the home screen, the recents or a notification is an open step, and one that a tap in the app before it opened is not
     * (the tap does it again). The clock is the caller's (ms).
     */
    public static final class Recording {
        private final Set<String> ignored; private final Predicate<String> launcher;
        private final List<Step> steps = new ArrayList<>();
        private String front = "", screen = "", actedIn = "";
        private long last, actedAt;
        private int dropped;
        private boolean money;
        /** frontApp: the app in front when it began ("" or this app's own: nothing yet), with its name. */
        public Recording(Set<String> ignored, Predicate<String> launcher, String frontApp, String frontLabel, long now) {
            this.ignored = ignored == null ? Set.of() : ignored; this.launcher = launcher == null ? p -> false : launcher; this.last = now;
            String app = s(frontApp);
            if (!app.isEmpty() && !this.ignored.contains(app) && !this.launcher.test(app)) {
                front = app; money = AutomationGate.bankingApp(app) || AutomationGate.bankingApp(frontLabel);
                add(money ? Step.open(app, frontLabel, 0).stopping() : Step.open(app, frontLabel, 0), now);
            }
        }
        /** The steps so far (the bar's count). */
        public synchronized int count() { return steps.size(); }
        public synchronized String front() { return front; }
        private boolean skip(String app) { return app.isEmpty() || ignored.contains(app) || launcher.test(app); }
        // (window() checks the home screen before the ignored apps, so this app as the user's home screen is a home step.)
        private void add(Step step, long now) { steps.add(step.with(step.text, Math.min(60_000, now - last))); last = now; }
        private Step lastStep() { return steps.isEmpty() ? null : steps.get(steps.size() - 1); }
        /** A window came to the front (its package, class and the app's name). */
        public synchronized void window(String app, String cls, String label, long now) {
            String pkg = s(app);
            if (pkg.isEmpty()) return;
            // A home screen first: this app is one when the user made it theirs (its taps are still never steps).
            if (launcher.test(pkg)) {
                if (!front.isEmpty() && !launcher.test(front)) { Step was = lastStep(); if (was == null || !HOME.equals(was.kind)) add(new Step(HOME, front, "", null, "", "", 0, false, false), now); }
                front = pkg; screen = ""; return;
            }
            if (ignored.contains(pkg)) return;
            if (pkg.equals(front)) { screen = s(cls); return; }
            // A tap in the app before it, a moment ago, opened this one (a share, a link): replaying the tap opens it again.
            boolean caused = actedAt > 0 && now - actedAt < 4000 && actedIn.equals(front) && !front.isEmpty() && !launcher.test(front);
            boolean bank = AutomationGate.bankingApp(pkg) || AutomationGate.bankingApp(label);
            if (!caused || bank) { Step open = Step.open(pkg, label, 0); add(bank ? open.stopping() : open, now); }
            if (bank) money = true;
            front = pkg; screen = s(cls);
        }
        /** A control was pressed (long: held). */
        public synchronized void click(String app, Target t, boolean longPress, long now) {
            String pkg = s(app);
            if (skip(pkg) || t == null) return;
            if (bank(pkg)) { dropped++; return; }
            Step step = new Step(longPress ? LONG : TAP, pkg, screen, t, "", "", 0, false, false);
            add(blocked(t) ? step.stopping() : step, now);
            actedAt = now; actedIn = pkg;
        }
        /** A field's text changed to text (password: Android says it is one). Typing on in the same field is the same step. */
        public synchronized void typed(String app, Target t, String text, boolean password, long now) {
            String pkg = s(app);
            if (skip(pkg) || t == null) return;
            if (bank(pkg)) { dropped++; return; }
            boolean secret = password || DecisionSemanticText.sensitiveInput(t.desc + " " + t.hint + " " + t.vid, text);
            Step was = lastStep();
            if (was != null && TYPE.equals(was.kind) && was.app.equals(pkg) && sameControl(was.target, t)) {
                steps.set(steps.size() - 1, new Step(TYPE, pkg, was.screen, was.target, secret ? "" : text == null ? "" : text, "", was.gap, secret || was.secret, false));
                last = now; actedAt = now; actedIn = pkg; return;
            }
            add(new Step(TYPE, pkg, screen, t, secret ? "" : text == null ? "" : text, "", 0, secret, false), now);
            actedAt = now; actedIn = pkg;
        }
        /** A list scrolled by (dx, dy): forward is "down". Scrolling on in the same list the same way is the same step. */
        public synchronized void scrolled(String app, Target list, int dx, int dy, long now) {
            String pkg = s(app);
            if (skip(pkg) || bank(pkg)) return;
            String dir = dy < 0 || dy == 0 && dx < 0 ? "up" : "down";
            Step was = lastStep();
            if (was != null && SCROLL.equals(was.kind) && was.app.equals(pkg) && was.dir.equals(dir) && (list == null || was.target == null || sameControl(was.target, list))) { last = now; return; }
            add(new Step(SCROLL, pkg, screen, list, "", dir, 0, false, false), now);
        }
        /** The recording bar's 뒤로 (the back gesture itself is not seen by accessibility). */
        public synchronized void back(long now) { if (!front.isEmpty() && !launcher.test(front)) add(new Step(BACK, front, screen, null, "", "", 0, false, false), now); }
        private boolean bank(String pkg) { if (AutomationGate.bankingApp(pkg)) { money = true; return true; } return false; }
        /** What came of it: the steps, cleaned, and what was left out. Throws when nothing worth keeping was done. */
        public synchronized Result finish() {
            List<Step> out = new ArrayList<>(steps);
            // Typing in a field right after tapping it: the tap only focused it (set_text needs no focus).
            for (int i = out.size() - 2; i >= 0; i--) {
                Step a = out.get(i), b = out.get(i + 1);
                if (TAP.equals(a.kind) && a.target != null && a.target.edit && TYPE.equals(b.kind) && sameControl(a.target, b.target)) out.remove(i);
            }
            // The home screen right before an app opens: the open step goes there by itself.
            for (int i = out.size() - 2; i >= 0; i--) if (HOME.equals(out.get(i).kind) && OPEN.equals(out.get(i + 1).kind)) out.remove(i);
            for (int i = out.size() - 2; i >= 0; i--) if (OPEN.equals(out.get(i).kind) && OPEN.equals(out.get(i + 1).kind) && out.get(i).app.equals(out.get(i + 1).app)) out.remove(i + 1);
            while (!out.isEmpty() && Set.of(HOME, BACK).contains(out.get(0).kind)) out.remove(0);
            while (!out.isEmpty() && Set.of(HOME, SCROLL).contains(out.get(out.size() - 1).kind)) out.remove(out.size() - 1);
            // An open app was opened before anything else: the macro starts from it, wherever the phone is then.
            if (!out.isEmpty() && !OPEN.equals(out.get(0).kind) && !out.get(0).app.isEmpty()) out.add(0, Step.open(out.get(0).app, "", 0));
            if (out.isEmpty()) throw new IllegalStateException("녹화된 단계가 없어요. 녹화를 켠 채로 앱을 열고 직접 눌러 주세요.");
            int secrets = 0, stops = 0;
            for (Step step : out) { if (step.secret) secrets++; if (step.stop) stops++; }
            return new Result(out, dropped, secrets, stops, money);
        }
    }
    /** A finished recording: its steps, how many were left out (in a banking app), password steps, steps it stops before. */
    public record Result(List<Step> steps, int dropped, int secrets, int stops, boolean money) {
        /** What the save says about what was left out or will stop ("" nothing). */
        public String note() {
            List<String> parts = new ArrayList<>();
            if (money) parts.add("은행·결제 앱에서 한 일은 저장하지 않아요" + (dropped > 0 ? " (" + dropped + "단계 뺌)" : ""));
            if (secrets > 0) parts.add("비밀번호·인증번호 칸은 저장하지 않고 실행할 때 거기서 멈춰요");
            if (stops > 0) parts.add("결제·삭제 버튼 앞에서는 멈추고 직접 누르게 해요");
            return String.join(" · ", parts);
        }
    }
    /** A payment, purchase, transfer or deletion control: a macro stops before it (the gate would refuse it anyway). */
    static boolean blocked(Target t) {
        if (t == null) return false;
        for (String v : new String[]{t.text, t.desc, t.host == null ? "" : t.host.label()}) if (!v.isEmpty() && AutomationGate.forbiddenLabel(v)) return true;
        return false;
    }
    /** The same control twice: the same view id, or (none) the same place and class. */
    static boolean sameControl(Target a, Target b) {
        if (a == null || b == null) return false;
        if (!a.vid.isEmpty() || !b.vid.isEmpty()) return a.vid.equals(b.vid) && (a.idx < 0 || b.idx < 0 || a.idx == b.idx || java.util.Arrays.equals(a.box, b.box));
        return java.util.Arrays.equals(a.box, b.box) && a.cls.equals(b.cls);
    }

    // ── finding a control again ──
    /** The element found for a target: its id in the reading, how sure (score) and how ("same", "similar", "place", "host"). */
    public record Match(String id, int score, String how, int[] box) {}
    /**
     * The element of a reading (PhoneService's elements with their view id vid, class cls and place among siblings idx) that
     * is the target, or null. Its words (text, description, hint) and view id decide; a count or a small change in a label
     * still matches ("알림 3" for "알림 5", "주차정산" for "주차 정산"); its place only breaks ties, or decides alone for a
     * control with no words and no id, near where it was. A field matches only a field (its text is what was typed, never a
     * name). When the label inside a row is gone, the row itself (host) is looked for.
     */
    public static Match find(Target want, JSONArray elements, int[] screen) {
        Match best = best(want, elements, screen, false);
        if (best == null && want != null && want.host != null) {
            Match row = best(want.host, elements, screen, true);
            if (row != null) best = new Match(row.id, row.score, "host", row.box);
        }
        return best;
    }
    private static Match best(Target want, JSONArray elements, int[] screen, boolean clickableOnly) {
        if (want == null || elements == null) return null;
        Match best = null;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject e = elements.optJSONObject(i);
            if (e == null) continue;
            if (clickableOnly && !e.optString("flags").contains("click")) continue;
            int[] scored = score(want, e, screen);
            if (scored == null) continue;
            if (best == null || scored[0] > best.score) best = new Match(e.optString("id"), scored[0], scored[1] == 2 ? "same" : scored[1] == 1 ? "similar" : "place", ints(e.optJSONArray("bounds"), 4));
        }
        return best;
    }
    /** {score, 2 exact / 1 similar / 0 place only} when the element can be the target, else null. */
    static int[] score(Target want, JSONObject e, int[] screen) {
        String flags = e.optString("flags"), role = e.optString("role");
        boolean field = flags.contains("edit") || "input".equals(role);
        if (want.edit != field) return null;
        if (flags.contains("password") && !want.edit) return null;
        int s = 0, identity = 0;
        String eText = field ? "" : e.optString("text"), eDesc = e.optString("desc"), eHint = e.optString("hint");
        int[] points = {
            same(want.text, eText, 60, 35, -30), same(want.desc, eDesc, 50, 30, -20), same(want.hint, eHint, 40, 25, -10),
            // Apps move a name between text and description from one version to the next.
            cross(want.text, eDesc), cross(want.desc, eText)};
        for (int p : points) { s += p; if (p >= 60 || p == 50 || p == 40) identity = 2; else if (p > 0 && identity < 2) identity = 1; }
        String vid = e.optString("vid");
        if (!want.vid.isEmpty() && !vid.isEmpty()) { if (want.vid.equals(vid)) { s += 45; identity = Math.max(identity, 2); } else s -= 25; }
        if (!want.cls.isEmpty() && want.cls.equals(e.optString("cls"))) s += 6;
        if (!want.role.isEmpty() && want.role.equals(role)) s += 4;
        if (want.idx >= 0 && want.idx == e.optInt("idx", -1)) s += 3;
        int place = place(want, ints(e.optJSONArray("bounds"), 4), screen);
        s += place;
        boolean named = want.edit ? !want.desc.isEmpty() || !want.hint.isEmpty() || !want.vid.isEmpty() : want.named();
        if (named) return identity > 0 && s >= 40 ? new int[]{s, identity} : null;
        // No words and no id: its place alone, near where it was, of the same class.
        return place >= 12 && (want.cls.isEmpty() || want.cls.equals(e.optString("cls"))) ? new int[]{s, 0} : null;
    }
    private static int same(String want, String got, int exact, int similar, int different) {
        if (want.isEmpty() || got == null || got.isEmpty()) return 0;
        int how = alike(want, got);
        return how == 2 ? exact : how == 1 ? similar : different;
    }
    private static int cross(String want, String got) {
        if (want.isEmpty() || got == null || got.isEmpty()) return 0;
        int how = alike(want, got);
        return how == 2 ? 40 : how == 1 ? 20 : 0;
    }
    /** 2: the same words; 1: alike (a count changed, a space, one or two letters, one inside the other); 0: different. */
    static int alike(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        if (x.equals(y)) return 2;
        String xd = x.replaceAll("\\d+", ""), yd = y.replaceAll("\\d+", "");
        if (!xd.isEmpty() && xd.equals(yd)) return 1;
        String shorter = x.length() <= y.length() ? x : y, longer = shorter == x ? y : x;
        if (shorter.length() >= 2 && longer.contains(shorter) && shorter.length() * 10 >= longer.length() * 6) return 1;
        int d = distance(x, y), most = Math.max(x.length(), y.length());
        return most >= 4 && d <= 1 || most >= 8 && d <= 2 ? 1 : 0;
    }
    static String norm(String v) { return v == null ? "" : v.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…~‘’“”•]+", ""); }
    private static int distance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 2) return 3;
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = row[0]; row[0] = i;
            for (int j = 1; j <= b.length(); j++) { int keep = row[j]; row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1)); prev = keep; }
        }
        return row[b.length()];
    }
    /** 0–20: how near the element's middle is to where the target's was, both as shares of their screens. */
    static int place(Target want, int[] box, int[] screen) {
        int ww = want.screen[0] > 0 ? want.screen[0] : screen == null ? 0 : screen[0], wh = want.screen[1] > 0 ? want.screen[1] : screen == null ? 0 : screen[1];
        int sw = screen != null && screen.length == 2 && screen[0] > 0 ? screen[0] : ww, sh = screen != null && screen.length == 2 && screen[1] > 0 ? screen[1] : wh;
        if (ww <= 0 || wh <= 0 || sw <= 0 || sh <= 0 || want.box[2] <= want.box[0] && want.box[3] <= want.box[1]) return 0;
        double ax = (want.box[0] + want.box[2]) / 2.0 / ww, ay = (want.box[1] + want.box[3]) / 2.0 / wh;
        double bx = (box[0] + box[2]) / 2.0 / sw, by = (box[1] + box[3]) / 2.0 / sh;
        return (int) Math.max(0, Math.round(20 - Math.hypot(ax - bx, ay - by) * 80));
    }

    // ── a run ──
    /** The words the gate reads as the user's for step index (each step its own, so a second tap on "+" is no repeat). */
    public static String goal(Macro m, int index) { return "‘" + m.name + "’ 매크로를 실행해 줘. 녹화한 대로 차례로 눌러 줘 (" + (index + 1) + "단계)"; }
    /** How long a step's control is waited for: as long as it took when recorded and a margin, 8 to 45 seconds. */
    public static long patience(Step step) { return Math.min(45_000, Math.max(8_000, step.gap * 3 / 2 + 4_000)); }
    /**
     * Whether a step asks the user first: in a run nobody is watching (a 조건 알림, a 예약 작업), a step that sends, posts or
     * calls (what the gate judges it to be from its label). Asked runs (the user's own words just now) do not ask.
     */
    public static boolean asks(Step step, String label, boolean unattended) {
        if (!unattended || step == null) return false;
        if (SEND.equals(step.kind)) return true;
        if (!Set.of(TAP, LONG, ENTER).contains(step.kind)) return false;
        AutomationGate.Kind kind = AutomationGate.kind(label);
        return kind == AutomationGate.Kind.SEND || kind == AutomationGate.Kind.POST || kind == AutomationGate.Kind.CALL;
    }
    /** The phone_action a step is (without its element and reading), or null for one done another way (SCROLL looks first). */
    public static JSONObject action(Step step) {
        return switch (step.kind) {
            case OPEN -> obj("action", "open_app", "app", step.app);
            case TAP -> obj("action", "tap");
            case LONG -> obj("action", "long_press");
            case TYPE -> obj("action", "set_text", "text", step.text);
            case BACK -> obj("action", "back");
            case HOME -> obj("action", "home");
            case ENTER -> obj("action", "press_enter");
            case SEND -> obj("action", "send");
            case SCROLL -> obj("action", "up".equals(step.dir) ? "scroll_up" : "scroll_down");
            default -> null;
        };
    }

    // ── mending a step whose control is gone ──
    /**
     * What 퍼레's phone agent is asked, once, when a step's control is not found any more: that step alone, by its words,
     * to be done on the control that does the same thing now (an app update moved or renamed it). The run goes on from there
     * and the step keeps the control found (repaired), so the next run needs no AI again.
     */
    public static String repairTask(Macro m, int index, Step step) {
        Target t = step.target;
        List<String> clues = new ArrayList<>();
        if (t != null) {
            if (!t.text.isEmpty()) clues.add("글자 ‘" + t.text + "’");
            if (!t.desc.isEmpty()) clues.add("설명 ‘" + t.desc + "’");
            if (!t.hint.isEmpty()) clues.add("안내 ‘" + t.hint + "’");
            if (!t.vid.isEmpty()) clues.add("id " + t.vid.substring(t.vid.indexOf('/') + 1));
            if (t.host != null && !t.host.label().isEmpty()) clues.add("‘" + t.host.label() + "’ 줄");
        }
        boolean field = t != null && t.edit;
        return "매크로 ‘" + m.name + "’의 " + (index + 1) + "단계만 대신 해 주세요: " + what(step) + "."
            + (clues.isEmpty() ? "" : " 녹화할 때 그 " + (field ? "입력 칸" : "버튼") + "은 " + String.join(", ", clues) + "였어요.")
            + " 앱이 바뀌어 그대로는 찾지 못했어요. 지금 화면에서 같은 일을 하는 " + (field ? "입력 칸" : "버튼") + "을 찾아 그 동작 한 번만 하세요(안 보이면 넘겨 보기만 해도 돼요)."
            + " 다른 것은 누르지 말고, 없으면 없다고 짧게 말하세요.";
    }
    /** Why the phone agent may not do this while mending a step ("" it may): only looking (scroll, wait) and the step's own action on a read element. */
    public static String repairRefusal(Step step, JSONObject args) {
        String action = args == null ? "" : args.optString("action");
        if (Set.of("scroll_down", "scroll_up", "wait").contains(action)) return "";
        String own = TAP.equals(step.kind) ? "tap" : LONG.equals(step.kind) ? "long_press" : TYPE.equals(step.kind) ? "set_text" : "";
        if (own.isEmpty() || !own.equals(action)) return "이 단계는 ‘" + what(step) + "’ 하나만 해요. 다른 동작은 하지 않았어요.";
        if (args.optString("element").isEmpty()) return "좌표 말고 화면 읽기의 요소(element)로 골라 주세요. 실행하지 않았어요.";
        return "";
    }
    /** The step's own action as the agent named it, with the recorded text for typing (never the agent's own words). */
    public static JSONObject repairArgs(Step step, JSONObject args) {
        JSONObject out = copy(args);
        if (TYPE.equals(step.kind)) put(out, "text", step.text);
        return out;
    }
    /** The macro with step index's control replaced by the one found (a repair). */
    public static Macro repaired(Macro m, int index, Target found) {
        if (found == null || index < 0 || index >= m.steps.size()) return m;
        List<Step> steps = new ArrayList<>(m.steps);
        Step was = steps.get(index);
        steps.set(index, new Step(was.kind, was.app, was.screen, new Target(found.vid, found.text, found.desc, found.hint, found.cls, found.role, found.idx, found.box, found.screen, was.target != null && was.target.edit, null),
            was.text, was.dir, was.gap, was.secret, was.stop));
        return m.withSteps(steps);
    }

    // ── the words ──
    /** A step in a few words: "‘카카오T’ 열기", "‘주차 정산’ 누르기", "‘차량번호’ 칸에 ‘12가3456’ 입력", "아래로 넘기기". */
    public static String what(Step step) {
        String label = step.target == null ? "" : cut(step.target.label(), 20);
        String named = label.isEmpty() ? "이름 없는 버튼" : "‘" + label + "’";
        return switch (step.kind) {
            case OPEN -> "‘" + (step.text.isEmpty() ? step.app : cut(step.text, 20)) + "’ 열기";
            case TAP -> named + (step.stop ? " 앞에서 멈춤" : " 누르기");
            case LONG -> named + (step.stop ? " 앞에서 멈춤" : " 길게 누르기");
            case TYPE -> step.secret ? (label.isEmpty() ? "비밀번호 칸" : named + " 칸") + " · 직접 입력" : (label.isEmpty() ? "글" : named + " 칸에") + " ‘" + cut(step.text, 16) + "’ 입력";
            case SCROLL -> "up".equals(step.dir) ? "위로 넘기기" : "아래로 넘기기";
            case BACK -> "뒤로";
            case HOME -> "홈으로";
            case ENTER -> "입력 확인";
            case SEND -> "보내기";
            default -> step.kind;
        };
    }
    /** What could not be found, as said: "‘차량번호’ 칸을 찾지 못했어요", "‘주차 정산’ 버튼을 찾지 못했어요". */
    public static String missing(Step step) {
        String label = step.target == null ? "" : cut(step.target.label(), 20);
        if (OPEN.equals(step.kind)) return "‘" + (step.text.isEmpty() ? step.app : step.text) + "’ 앱이 열리지 않았어요";
        if (step.target != null && step.target.edit) return (label.isEmpty() ? "입력 칸" : "‘" + label + "’ 칸") + "을 찾지 못했어요";
        if (SCROLL.equals(step.kind)) return "넘길 목록을 찾지 못했어요";
        return (label.isEmpty() ? "누를 버튼" : "‘" + label + "’ 버튼") + "을 찾지 못했어요";
    }
    /** One line of a run that stopped: "‘주차 정산’ 3단계: " + why. */
    public static String line(Macro m, int index, String why) { return "‘" + m.name + "’ " + (index + 1) + "단계: " + why; }
    /** Why a stop step stopped: "‘결제하기’는 직접 눌러 주세요", "은행·결제 앱은 직접 열어 주세요", "비밀번호는 직접 입력해 주세요". */
    public static String stopped(Step step) {
        if (step.secret) return "비밀번호·인증번호는 매크로가 입력하지 않아요 · 직접 입력해 주세요";
        if (OPEN.equals(step.kind)) return "은행·결제 앱은 매크로가 열지 않아요 · 여기서부터는 직접 해 주세요";
        String label = step.target == null ? "" : cut(step.target.label(), 20);
        return (label.isEmpty() ? "결제·삭제 버튼은" : "‘" + label + "’" + particle(label, "은", "는")) + " 직접 눌러 주세요 · 그 앞까지 했어요";
    }
    /** The particle after a word: a after a final consonant, else b. */
    static String particle(String word, String a, String b) {
        char last = word == null || word.isEmpty() ? 'a' : word.charAt(word.length() - 1);
        return last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 != 0 ? a : b;
    }
    /** "6단계 · 카카오T · 3번 실행" for a row. */
    public static String summary(Macro m) {
        StringBuilder out = new StringBuilder(m.steps.size() + "단계");
        List<String> apps = m.apps();
        if (!apps.isEmpty()) out.append(" · ").append(String.join(", ", apps.subList(0, Math.min(2, apps.size())))).append(apps.size() > 2 ? " 외" : "");
        if (m.runs > 0) out.append(" · ").append(m.runs).append("번 실행");
        if (!m.last.isEmpty() && !m.last.startsWith("✓")) out.append(" · 지난번 멈춤");
        return out.toString();
    }
    /** The steps, one a line ("1. ‘카카오T’ 열기"). */
    public static String steps(Macro m) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < m.steps.size(); i++) out.append(i == 0 ? "" : "\n").append(i + 1).append(". ").append(what(m.steps.get(i)));
        return out.toString();
    }
    /** "‘출근 체크’ · 카카오T 매크로" names for 퍼레's list answer ("" none). */
    public static String names(List<Macro> list) {
        if (list == null || list.isEmpty()) return "";
        List<String> out = new ArrayList<>(); for (Macro m : list) out.add("‘" + m.name + "’");
        return String.join(", ", out);
    }

    // ── learned from 퍼레's phone job ──
    /**
     * The steps of a phone job that went right, as a macro could do them again with no AI call: each done phone_action (its
     * arguments, the reading it named its element in, and what it answered) made a step. Null when it holds a step a macro
     * does not do the same way (a web address, a coordinate tap, a wait alone is skipped), or fewer than two steps.
     */
    public static List<Step> learned(List<JSONObject[]> done) {
        List<Step> out = new ArrayList<>();
        for (JSONObject[] d : done) {
            JSONObject args = d[0], before = d.length > 1 ? d[1] : null, value = d.length > 2 ? d[2] : null;
            if (args == null) return null;
            String action = args.optString("action"), app = before == null ? "" : before.optString("app");
            Target t = element(before, args.optString("element"));
            switch (action) {
                case "open_app" -> { String pkg = value == null ? "" : value.optString("opened"); if (pkg.isEmpty()) return null; out.add(Step.open(pkg, args.optString("app"), 0)); }
                case "tap", "long_press" -> { if (t == null) return null; out.add(new Step("tap".equals(action) ? TAP : LONG, app, "", t, "", "", 0, false, blocked(t))); }
                case "set_text" -> { if (t == null || !t.edit) return null; boolean secret = DecisionSemanticText.sensitiveInput(t.desc + " " + t.hint, args.optString("text")); out.add(new Step(TYPE, app, "", t, args.optString("text"), "", 0, secret, false)); }
                case "scroll_down", "scroll_up" -> out.add(new Step(SCROLL, app, "", t, "", "scroll_up".equals(action) ? "up" : "down", 0, false, false));
                case "back" -> out.add(new Step(BACK, app, "", null, "", "", 0, false, false));
                case "home" -> out.add(new Step(HOME, app, "", null, "", "", 0, false, false));
                case "press_enter" -> out.add(new Step(ENTER, app, "", t, "", "", 0, false, false));
                case "send" -> out.add(new Step(SEND, app, "", null, "", "", 0, false, false));
                case "wait" -> { /* the run waits for each control by itself */ }
                default -> { return null; }
            }
        }
        while (!out.isEmpty() && Set.of(SCROLL, HOME).contains(out.get(out.size() - 1).kind)) out.remove(out.size() - 1);
        if (!out.isEmpty() && !OPEN.equals(out.get(0).kind) && !out.get(0).app.isEmpty()) out.add(0, Step.open(out.get(0).app, "", 0));
        int acts = 0; for (Step step : out) if (!OPEN.equals(step.kind)) acts++;
        return acts < 1 || out.size() < 2 ? null : out;
    }
    /** A reading's element (PhoneService's phone_screen) as a target, or null. */
    public static Target element(JSONObject screen, String id) {
        JSONArray all = screen == null || id == null || id.isEmpty() ? null : screen.optJSONArray("elements");
        int[] size = screen == null ? new int[2] : ints(screen.optJSONArray("screen"), 2);
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e == null || !id.equals(e.optString("id"))) continue;
            boolean edit = e.optString("flags").contains("edit") || "input".equals(e.optString("role"));
            String text = e.optString("text");
            return new Target(e.optString("vid"), "[비밀번호]".equals(text) ? "" : text, e.optString("desc"), e.optString("hint"), e.optString("cls"), e.optString("role"), e.optInt("idx", -1),
                ints(e.optJSONArray("bounds"), 4), size, edit, null);
        }
        return null;
    }

    // ── the voice assistant's function ──
    public static JSONObject declaration() {
        return obj("name", FUNCTION, "description", "Run one of the user's saved phone macros (steps they recorded by hand, 매크로) by its name, e.g. 출근 체크. It replays them through phone control with no AI; payments, transfers, deletion and passwords are never done. Omit name to list them.",
            "parameters", obj("type", "OBJECT", "properties", obj("name", obj("type", "STRING", "description", "The macro's name as the user said it"))));
    }

    // ── helpers ──
    private static String s(String v) { return v == null ? "" : v.strip(); }
    static String clip(String v, int most) { String t = v == null ? "" : v.replaceAll("\\s+", " ").strip(); return t.length() > most ? t.substring(0, most).strip() : t; }
    static String cut(String v, int most) { String t = s(v); return t.length() > most ? t.substring(0, most - 1) + "…" : t; }
    static int[] ints(JSONArray a, int n) { int[] out = new int[n]; for (int i = 0; a != null && i < n && i < a.length(); i++) out[i] = a.optInt(i); return out; }
    /** A reading's screen size {width, height} ({0, 0} unknown). */
    public static int[] size(JSONObject screen) { return ints(screen == null ? null : screen.optJSONArray("screen"), 2); }
}
