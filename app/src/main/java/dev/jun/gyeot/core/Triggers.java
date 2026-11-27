package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 사건 트리거 (준's plan, stage 2): 예약 작업 run at times; these run when something happens, made by speaking to 퍼레 ("집에
 * 도착하면 알려 줘", "차 타면 매번 네이버 지도 켜 줘", "충전 다 되면 알려 줘", "비트코인 1억 넘으면 알려 줘", "배달 출발하면 알려 줘").
 * Here, in plain Java: the conditions (a notice from an app, with its words, a delivery's stage, a parcel's, a messenger's
 * sender; arriving at or leaving a place; a paired Bluetooth device connecting or going; the charger in, out, the battery
 * full or at N%; a price crossing a line or moving N%), the actions (tell, open an app, a route, music, a draft the user
 * sends, one of 퍼레's jobs), once or every time, how an event is matched (edges, re-arming, a cooldown so one event fires
 * once), the words said back and shown, and how they are kept. The phone listens only while triggers of a kind exist
 * (TriggerWatch) and does the action (TriggerFire); 퍼레 never sends a message by itself: a send becomes a draft.
 */
public final class Triggers {
    private Triggers() {}

    // ── what can happen ───────────────────────────────────────────
    public static final String NOTICE = "notice", PLACE = "place", BLUETOOTH = "bluetooth", POWER = "power", PRICE = "price";
    public static final List<String> KINDS = List.of(NOTICE, PLACE, BLUETOOTH, POWER, PRICE);
    /** A notice's sort: a food delivery's stage (core/Deliveries), a parcel's (NoticeActions), a messenger's sender, or any app's words. */
    public static final String DELIVERY = "delivery", PARCEL = "parcel", MESSAGE = "message", APP = "app";
    public static final String ARRIVE = "arrive", LEAVE = "leave", CONNECT = "connect", DISCONNECT = "disconnect";
    public static final String PLUGGED = "plugged", UNPLUGGED = "unplugged", FULL = "full", LEVEL_UP = "level_up", LEVEL_DOWN = "level_down";
    public static final String ABOVE = "above", BELOW = "below", RISE = "rise", FALL = "fall", MOVE = "move";
    public static final String KRW = "KRW", USD = "USD";
    // ── what to do then ───────────────────────────────────────────
    public static final String TELL = "tell", OPEN_APP = "open_app", NAVIGATE = "navigate", PLAY = "play", DRAFT = "draft", JOB = "job";
    public static final String ONCE = "once", EVERY = "every";
    public static final int MOST = 30;
    /** A place's circle (m) when none is given, and how far past it counts as out (so a fix at the edge does not flip it). */
    public static final int RADIUS = 150, MARGIN = 100;
    /** Right after a place trigger is made, a first fix only says where the phone is (it is not an arrival). */
    public static final long SETTLE_MS = 3 * 60_000L;
    /** A fired once-trigger stays in the list this long (끝남), then goes. */
    public static final long DONE_KEPT_MS = 2 * 24 * 3_600_000L;
    /** How far a price goes back across its line before it can fire again. */
    public static final double HYSTERESIS = 0.005;

    /**
     * When it fires. kind: NOTICE, PLACE, BLUETOOTH, POWER, PRICE. edge: which change (ARRIVE …, "" for a notice). sort, app,
     * who, words, stage: a notice's. name: what the user calls the place, the device ("차"), the coin ("비트코인"). id: the
     * device's address, the coin's symbol (BTC). lat, lng, radius: the place (NaN until found). value: the battery %, the
     * price line, or the % to move. unit: KRW or USD. base: the price a % move counts from (0 until first seen).
     */
    public record Condition(String kind, String edge, String sort, String app, String who, String words, String stage, String name, String id,
                            double lat, double lng, int radius, double value, String unit, double base) {
        public static Condition notice(String sort, String app, String who, String words, String stage) { return new Condition(NOTICE, "", sort, s(app), s(who), s(words), s(stage), "", "", Double.NaN, Double.NaN, 0, 0, "", 0); }
        public static Condition place(String edge, String name, double lat, double lng, int radius) { return new Condition(PLACE, edge, "", "", "", "", "", s(name), "", lat, lng, radius <= 0 ? RADIUS : radius, 0, "", 0); }
        public static Condition bluetooth(String edge, String name, String address) { return new Condition(BLUETOOTH, edge, "", "", "", "", "", s(name), s(address), Double.NaN, Double.NaN, 0, 0, "", 0); }
        public static Condition power(String edge, int level) { return new Condition(POWER, edge, "", "", "", "", "", "", "", Double.NaN, Double.NaN, 0, level, "", 0); }
        public static Condition price(String edge, String name, String symbol, double value, String unit, double base) { return new Condition(PRICE, edge, "", "", "", "", "", s(name), s(symbol), Double.NaN, Double.NaN, 0, value, s(unit), base); }
        public Condition at(String placeName, double lat, double lng) { return new Condition(kind, edge, sort, app, who, words, stage, s(placeName).isEmpty() ? name : placeName, id, lat, lng, radius, value, unit, base); }
        public Condition device(String address) { return new Condition(kind, edge, sort, app, who, words, stage, name, s(address), lat, lng, radius, value, unit, base); }
        public Condition from(double price) { return new Condition(kind, edge, sort, app, who, words, stage, name, id, lat, lng, radius, value, unit, price); }
        /** The place has its coordinates. */
        public boolean located() { return !Double.isNaN(lat) && !Double.isNaN(lng); }
        /** The user's car: the device they call 차 (WakeWordFiles' car, or one they picked once). */
        public boolean car() { return BLUETOOTH.equals(kind) && CAR_WORD.matcher(name).matches(); }
        public JSONObject json() {
            JSONObject o = obj("kind", kind, "edge", edge);
            for (String[] f : new String[][]{{"sort", sort}, {"app", app}, {"who", who}, {"words", words}, {"stage", stage}, {"name", name}, {"id", id}, {"unit", unit}}) if (!f[1].isEmpty()) put(o, f[0], f[1]);
            if (located()) { put(o, "lat", lat); put(o, "lng", lng); }
            if (radius > 0) put(o, "radius", radius);
            if (value != 0) put(o, "value", value);
            if (base != 0) put(o, "base", base);
            return o;
        }
        public static Condition of(JSONObject o) {
            if (o == null || !KINDS.contains(o.optString("kind"))) return null;
            return new Condition(o.optString("kind"), o.optString("edge"), o.optString("sort"), o.optString("app"), o.optString("who"), o.optString("words"), o.optString("stage"),
                o.optString("name"), o.optString("id"), o.optDouble("lat", Double.NaN), o.optDouble("lng", Double.NaN), o.optInt("radius"), o.optDouble("value", 0), o.optString("unit"), o.optDouble("base", 0));
        }
    }
    /**
     * What to do. kind: TELL (text: what to remind of, "" just tell), OPEN_APP, NAVIGATE, PLAY (target: the app, the place, what
     * to play; app: where), DRAFT (tool: message, share, email or call; who, text, app: written ready, never sent), JOB (tool:
     * phone_job, web_job, work_job or research_job; target: the request; app: the app or the site).
     */
    public record Action(String kind, String tool, String target, String text, String who, String app) {
        public static Action tell(String text) { return new Action(TELL, "", "", s(text), "", ""); }
        public static Action open(String app) { return new Action(OPEN_APP, "open_app", s(app), "", "", ""); }
        public static Action route(String destination) { return new Action(NAVIGATE, "navigate", s(destination), "", "", ""); }
        public static Action play(String query, String app) { return new Action(PLAY, "play", s(query), "", "", s(app)); }
        public static Action draft(String tool, String who, String text, String app) { return new Action(DRAFT, tool, "", s(text), s(who), s(app)); }
        public static Action job(String tool, String request, String app) { return new Action(JOB, tool, s(request), "", "", s(app)); }
        /** The act BuddyActs or BuddyHands takes for it ({"tool": …}), null for TELL. */
        public JSONObject act() {
            return switch (kind) {
                case OPEN_APP -> obj("tool", "open_app", "app", target);
                case NAVIGATE -> obj("tool", "navigate", "destination", target);
                case PLAY -> app.isEmpty() ? obj("tool", "play", "query", target) : obj("tool", "play", "query", target, "app", app);
                case DRAFT -> switch (tool) {
                    case "share" -> obj("tool", "share", "text", text, "app", app.isEmpty() ? "카카오톡" : app);
                    case "email" -> obj("tool", "email", "who", who, "text", text);
                    case "call" -> obj("tool", "call", "who", who);
                    default -> obj("tool", "message", "who", who, "text", text);
                };
                case JOB -> "web_job".equals(tool) ? obj("tool", tool, "request", target, "url", app)
                    : "research_job".equals(tool) ? obj("tool", tool, "question", target)
                    : "phone_job".equals(tool) && !app.isEmpty() ? obj("tool", tool, "request", target, "app", app) : obj("tool", tool, "request", target);
                default -> null;
            };
        }
        public JSONObject json() {
            JSONObject o = obj("kind", kind);
            for (String[] f : new String[][]{{"tool", tool}, {"target", target}, {"text", text}, {"who", who}, {"app", app}}) if (!f[1].isEmpty()) put(o, f[0], f[1]);
            return o;
        }
        public static Action of(JSONObject o) {
            if (o == null || !List.of(TELL, OPEN_APP, NAVIGATE, PLAY, DRAFT, JOB).contains(o.optString("kind"))) return null;
            return new Action(o.optString("kind"), o.optString("tool"), o.optString("target"), o.optString("text"), o.optString("who"), o.optString("app"));
        }
    }
    /**
     * One trigger: when, then, ONCE or EVERY, on (the user's switch), done (a once that fired), the user's words (its authority
     * for a job), when made and last fired, how often, armed (a state condition may fire: it was false since it last fired),
     * seen (what was last seen: a place "in"/"out", the charger "p"/"u", a price "p" once read; "" not yet).
     */
    public record Trigger(String id, Condition when, Action then, String mode, boolean on, boolean done, String said, long made, long fired, int times, boolean armed, String seen) {
        public boolean every() { return EVERY.equals(mode); }
        public boolean active() { return on && !done; }
        public Trigger switched(boolean to) { return new Trigger(id, when, then, mode, to, to ? false : done, said, made, fired, times, to || armed, seen); }
        Trigger state(Condition c, boolean a, String s) { return new Trigger(id, c, then, mode, on, done, said, made, fired, times, a, s); }
        Trigger fire(long now, Condition c, boolean a, String s) { return new Trigger(id, c, then, mode, on, !every(), said, made, now, times + 1, a, s); }
        public JSONObject json() {
            return obj("id", id, "when", when.json(), "then", then.json(), "mode", mode, "on", on, "done", done, "said", said, "made", made, "fired", fired, "times", times, "armed", armed, "seen", seen);
        }
        public static Trigger of(JSONObject o) {
            if (o == null || o.optString("id").isEmpty()) return null;
            Condition c = Condition.of(o.optJSONObject("when")); Action a = Action.of(o.optJSONObject("then"));
            if (c == null || a == null) return null;
            return new Trigger(o.optString("id"), c, a, EVERY.equals(o.optString("mode")) ? EVERY : ONCE, o.optBoolean("on", true), o.optBoolean("done"), o.optString("said"),
                o.optLong("made"), o.optLong("fired"), o.optInt("times"), o.optBoolean("armed", true), o.optString("seen"));
        }
    }
    /** A new trigger from a structured spec (퍼레's routine suggestion and the tool both make them here): on, armed, nothing seen. */
    public static Trigger make(Condition when, Action then, String mode, String said, long now) {
        if (when == null || then == null) throw new IllegalArgumentException("조건과 할 일이 다 있어야 해.");
        return new Trigger("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), when, then, EVERY.equals(mode) ? EVERY : ONCE, true, false, cut(s(said), 300), now, 0, 0, true, "");
    }

    // ── keeping them ──────────────────────────────────────────────
    public static String save(List<Trigger> all) { JSONArray out = new JSONArray(); for (Trigger t : all) out.put(t.json()); return out.toString(); }
    /** The kept triggers; a once that fired more than DONE_KEPT_MS ago goes, anything unreadable is left out. */
    public static List<Trigger> load(String json, long now) {
        List<Trigger> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json == null || json.isBlank() ? "[]" : json);
            for (int i = 0; i < a.length() && out.size() < MOST; i++) {
                Trigger t = Trigger.of(a.optJSONObject(i));
                if (t != null && !(t.done() && now - t.fired() > DONE_KEPT_MS)) out.add(t);
            }
        } catch (Exception broken) { /* none */ }
        return out;
    }
    /** The list with t added (at most MOST: the oldest finished ones go first); throws when it is full of live ones. */
    public static List<Trigger> add(List<Trigger> all, Trigger t) {
        List<Trigger> out = new ArrayList<>(all);
        while (out.size() >= MOST) {
            int drop = -1; for (int i = 0; i < out.size() && drop < 0; i++) if (!out.get(i).active()) drop = i;
            if (drop < 0) throw new IllegalStateException("조건 알림은 " + MOST + "개까지야. 안 쓰는 걸 지운 뒤 다시 말해 줘.");
            out.remove(drop);
        }
        out.add(t);
        return out;
    }
    public static List<Trigger> remove(List<Trigger> all, String id) { List<Trigger> out = new ArrayList<>(); for (Trigger t : all) if (!t.id().equals(id)) out.add(t); return out; }
    public static List<Trigger> switched(List<Trigger> all, String id, boolean on) { List<Trigger> out = new ArrayList<>(); for (Trigger t : all) out.add(t.id().equals(id) ? t.switched(on) : t); return out; }
    public static List<Trigger> replace(List<Trigger> all, Trigger with) { List<Trigger> out = new ArrayList<>(); for (Trigger t : all) out.add(t.id().equals(with.id()) ? with : t); return out; }
    public static Trigger find(List<Trigger> all, String id) { for (Trigger t : all) if (t.id().equals(id)) return t; return null; }
    /** The kinds the phone listens for now: those of the triggers on and not done. */
    public static Set<String> kinds(List<Trigger> all) { Set<String> out = new LinkedHashSet<>(); for (Trigger t : all) if (t.active()) out.add(t.when().kind()); return out; }
    /** Some trigger on reads the battery's level (full, N%): Android's battery broadcast is listened to. */
    public static boolean needsLevel(List<Trigger> all) { for (Trigger t : all) if (t.active() && POWER.equals(t.when().kind()) && !PLUGGED.equals(t.when().edge()) && !UNPLUGGED.equals(t.when().edge())) return true; return false; }
    /** Some trigger on fires only while charging (plugged in, full, up to N%): a job wakes the app when charging starts. */
    public static boolean needsCharging(List<Trigger> all) { for (Trigger t : all) if (t.active() && POWER.equals(t.when().kind()) && List.of(PLUGGED, FULL, LEVEL_UP).contains(t.when().edge())) return true; return false; }
    /** The quotes to read: each {symbol, unit} once, for the price triggers on. */
    public static List<String[]> quotes(List<Trigger> all) {
        Set<String> seen = new LinkedHashSet<>(); List<String[]> out = new ArrayList<>();
        for (Trigger t : all) if (t.active() && PRICE.equals(t.when().kind()) && seen.add(t.when().id() + "/" + t.when().unit())) out.add(new String[]{t.when().id(), t.when().unit()});
        return out;
    }

    // ── what happened ─────────────────────────────────────────────
    /**
     * Something the phone saw. kind as Condition's. edge: what changed (a power broadcast's PLUGGED, a device's CONNECT, a
     * proximity alert's ARRIVE; "" for a reading). app, label, who, text, sort, stage, key: a notice (key: the parcel or the
     * order, so one is not told twice). id: the device's address, the trigger a proximity alert is for, the coin. name: the
     * device's. lat, lng, accuracy: a location fix. value: the battery %, the price. plugged: charging. unit: the price's.
     */
    public record Event(String kind, String edge, String sort, String app, String label, String who, String text, String stage, String id, String name,
                        double lat, double lng, double accuracy, double value, boolean plugged, String unit, long at, String key) {
        public static Event notice(String pkg, String label, String sort, String who, String text, String stage, String key, long at) {
            return new Event(NOTICE, "", s(sort).isEmpty() ? APP : sort, s(pkg), s(label), s(who), s(text), s(stage), "", "", Double.NaN, Double.NaN, 0, 0, false, "", at, s(key));
        }
        public static Event bluetooth(String edge, String address, String name, long at) { return new Event(BLUETOOTH, edge, "", "", "", "", "", "", s(address), s(name), Double.NaN, Double.NaN, 0, 0, false, "", at, ""); }
        /** A battery reading (edge ""), or a power broadcast (edge PLUGGED or UNPLUGGED); level -1 when unknown, full: Android says FULL. */
        public static Event power(String edge, boolean plugged, int level, boolean full, long at) { return new Event(POWER, s(edge), "", "", "", "", "", full ? FULL : "", "", "", Double.NaN, Double.NaN, 0, level, plugged, "", at, ""); }
        public static Event fix(double lat, double lng, double accuracy, long at) { return new Event(PLACE, "", "", "", "", "", "", "", "", "", lat, lng, accuracy, 0, false, "", at, ""); }
        /** Android's proximity alert for one trigger: entering (ARRIVE) or leaving (LEAVE) its circle. */
        public static Event proximity(String trigger, boolean entering, long at) { return new Event(PLACE, entering ? ARRIVE : LEAVE, "", "", "", "", "", "", s(trigger), "", Double.NaN, Double.NaN, 0, 0, false, "", at, ""); }
        public static Event price(String symbol, String unit, double price, long at) { return new Event(PRICE, "", "", "", "", "", "", "", s(symbol), "", Double.NaN, Double.NaN, 0, price, false, s(unit), at, ""); }
    }
    /** A trigger that fired, the trigger as it is after it fired (done for a once), and what happened. */
    public record Fired(Trigger trigger, Event event) {}
    /** The triggers after an event, those that fired, and whether anything is to be kept again. */
    public record Outcome(List<Trigger> list, List<Fired> fired, boolean changed) {}

    /** How long after firing the same trigger does not fire again: one event (a burst of messages, a car's flaky link) fires it once. */
    public static long cooldown(Condition c) {
        return switch (c.kind()) {
            case NOTICE -> DELIVERY.equals(c.sort()) ? 3 * 3_600_000L : PARCEL.equals(c.sort()) ? 60_000L : 10 * 60_000L;
            case PLACE -> 20 * 60_000L;
            case BLUETOOTH -> 5 * 60_000L;
            case POWER -> PLUGGED.equals(c.edge()) || UNPLUGGED.equals(c.edge()) ? 2 * 60_000L : 10 * 60_000L;
            case PRICE -> 30 * 60_000L;
            default -> 60_000L;
        };
    }

    /** Each trigger on is shown the event: those it fires (and not in their cooldown) are in fired; state seen is kept. */
    public static Outcome match(List<Trigger> all, Event e, long now) {
        List<Trigger> out = new ArrayList<>(); List<Fired> fired = new ArrayList<>(); boolean changed = false;
        for (Trigger t : all) {
            if (!t.active() || e == null || !t.when().kind().equals(e.kind())) { out.add(t); continue; }
            Step s = step(t, e, now);
            Trigger next = t;
            boolean cool = t.fired() > 0 && now - t.fired() < cooldown(t.when());
            if (s.fire && !cool) {
                next = t.fire(now, s.when, s.armed, s.seen);
                fired.add(new Fired(next, e));
            } else if (s.armed != t.armed() || !s.seen.equals(t.seen()) || !s.when.equals(t.when())) next = t.state(s.when, s.armed, s.seen);
            if (next != t) changed = true;
            out.add(next);
        }
        return new Outcome(out, fired, changed);
    }
    /** What an event makes of one trigger: fire or not, the condition after (a % move's new base), armed and seen after. */
    private record Step(boolean fire, Condition when, boolean armed, String seen) {}
    private static Step step(Trigger t, Event e, long now) {
        Condition c = t.when();
        Step same = new Step(false, c, t.armed(), t.seen());
        switch (c.kind()) {
            case NOTICE -> {
                if (!noticeHit(c, e)) return same;
                // The same parcel at the same stage is told once, however often its notice comes again (the last few kept).
                if (e.key().isEmpty()) return new Step(true, c, t.armed(), t.seen());
                String key = e.key() + "@" + e.stage();
                List<String> kept = new ArrayList<>(List.of(t.seen().isEmpty() ? new String[0] : t.seen().split("\n")));
                if (kept.contains(key)) return same;
                kept.add(0, key);
                return new Step(true, c, t.armed(), String.join("\n", kept.subList(0, Math.min(5, kept.size()))));
            }
            case BLUETOOTH -> {
                if (!c.edge().equals(e.edge()) || !deviceHit(c, e.id(), e.name())) return same;
                return new Step(true, c, t.armed(), t.seen());
            }
            case PLACE -> { return place(t, e, now); }
            case POWER -> { return power(t, e); }
            case PRICE -> { return price(t, e); }
            default -> { return same; }
        }
    }
    private static Step place(Trigger t, Event e, long now) {
        Condition c = t.when();
        String inside;
        if (!e.id().isEmpty()) {
            if (!e.id().equals(t.id())) return new Step(false, c, t.armed(), t.seen());
            inside = ARRIVE.equals(e.edge()) ? "in" : "out";
        } else {
            if (!c.located() || Double.isNaN(e.lat()) || e.accuracy() > 1000) return new Step(false, c, t.armed(), t.seen());
            double d = meters(c.lat(), c.lng(), e.lat(), e.lng());
            inside = d <= c.radius() ? "in" : d >= c.radius() + MARGIN + Math.min(200, Math.max(0, e.accuracy())) ? "out" : t.seen();
        }
        if (inside.equals(t.seen())) return new Step(false, c, t.armed(), t.seen());
        // Not known yet and just made: this only says where the phone is now, it is not an arrival.
        if (t.seen().isEmpty() && now - t.made() < SETTLE_MS) return new Step(false, c, t.armed(), inside);
        boolean fire = ARRIVE.equals(c.edge()) ? inside.equals("in") : inside.equals("out");
        return new Step(fire, c, t.armed(), inside);
    }
    private static Step power(Trigger t, Event e) {
        Condition c = t.when();
        String was = t.seen(), now = e.plugged() ? "p" : "u";
        // A broadcast says the edge itself; a reading shows it as a change from what was seen (none when nothing was seen yet).
        boolean plugged = PLUGGED.equals(e.edge()) || e.edge().isEmpty() && was.equals("u") && e.plugged();
        boolean unplugged = UNPLUGGED.equals(e.edge()) || e.edge().isEmpty() && was.equals("p") && !e.plugged();
        boolean in = PLUGGED.equals(e.edge()) || !UNPLUGGED.equals(e.edge()) && e.plugged();
        String seen = PLUGGED.equals(e.edge()) ? "p" : UNPLUGGED.equals(e.edge()) ? "u" : now;
        double level = e.value();
        switch (c.edge()) {
            case PLUGGED -> { return new Step(plugged, c, t.armed(), seen); }
            case UNPLUGGED -> { return new Step(unplugged, c, t.armed(), seen); }
            case FULL -> {
                boolean full = in && (FULL.equals(e.stage()) || level >= 100);
                if (level < 0 && e.stage().isEmpty() && in) return new Step(false, c, t.armed(), seen); // a broadcast with no reading
                boolean rearm = !in || level >= 0 && level < 95;
                return state(t, c, full, rearm, seen);
            }
            case LEVEL_UP -> {
                if (level < 0) return new Step(false, c, t.armed() || !in, seen);
                return state(t, c, in && level >= c.value(), !in || level < c.value() - 3, seen);
            }
            case LEVEL_DOWN -> {
                if (level < 0) return new Step(false, c, t.armed() || in, seen);
                return state(t, c, !in && level <= c.value(), in || level > c.value() + 3, seen);
            }
            default -> { return new Step(false, c, t.armed(), seen); }
        }
    }
    /** A state condition: fires while armed when it holds (armed off after), armed again once it no longer holds. */
    private static Step state(Trigger t, Condition c, boolean holds, boolean rearm, String seen) {
        if (holds && t.armed()) return new Step(true, c, false, seen);
        return new Step(false, c, t.armed() || rearm && !holds, seen);
    }
    private static Step price(Trigger t, Event e) {
        Condition c = t.when();
        if (!c.id().equalsIgnoreCase(e.id()) || !c.unit().equalsIgnoreCase(e.unit()) || !(e.value() > 0)) return new Step(false, c, t.armed(), t.seen());
        double p = e.value();
        // The first price read only says where it is: a line already crossed waits until it comes back (no surprise at once).
        if (t.seen().isEmpty()) {
            Condition based = c.base() > 0 ? c : c.from(p);
            return new Step(false, based, !holds(based, p), "p");
        }
        if (c.base() <= 0 && !List.of(ABOVE, BELOW).contains(c.edge())) return new Step(false, c.from(p), true, "p");
        boolean holds = holds(c, p);
        if (holds && t.armed()) {
            // Every time a % move: counted again from here.
            Condition next = List.of(RISE, FALL, MOVE).contains(c.edge()) ? c.from(p) : c;
            return new Step(true, next, List.of(RISE, FALL, MOVE).contains(c.edge()), "p");
        }
        boolean back = switch (c.edge()) {
            case ABOVE -> p < c.value() * (1 - HYSTERESIS);
            case BELOW -> p > c.value() * (1 + HYSTERESIS);
            default -> true;
        };
        return new Step(false, c, t.armed() || back, "p");
    }
    /** Whether the price stands where the condition says (over its line, under it, moved N% from its base). */
    public static boolean holds(Condition c, double p) {
        double pct = c.base() > 0 ? (p / c.base() - 1) * 100 : 0;
        return switch (c.edge()) {
            case ABOVE -> p >= c.value();
            case BELOW -> p <= c.value();
            case RISE -> c.base() > 0 && pct >= c.value();
            case FALL -> c.base() > 0 && -pct >= c.value();
            case MOVE -> c.base() > 0 && Math.abs(pct) >= c.value();
            default -> false;
        };
    }
    /**
     * Sets what is seen now without firing (when a trigger is made): where the phone is, the charger and the battery, the price.
     * A state that already holds waits until it no longer does (충전 다 되면 said at 100%: the next full charge).
     */
    public static Trigger prime(Trigger t, Event now) {
        if (now == null || !t.when().kind().equals(now.kind())) return t;
        Condition c = t.when();
        switch (c.kind()) {
            case POWER -> {
                String seen = now.plugged() ? "p" : "u"; double level = now.value();
                boolean holds = switch (c.edge()) {
                    case FULL -> now.plugged() && (FULL.equals(now.stage()) || level >= 100);
                    case LEVEL_UP -> now.plugged() && level >= c.value();
                    case LEVEL_DOWN -> !now.plugged() && level >= 0 && level <= c.value();
                    default -> false;
                };
                return t.state(c, !holds, seen);
            }
            case PRICE -> {
                if (!(now.value() > 0)) return t;
                Condition based = c.base() > 0 ? c : c.from(now.value());
                return t.state(based, !holds(based, now.value()), "p");
            }
            case PLACE -> {
                if (!c.located() || Double.isNaN(now.lat())) return t;
                double d = meters(c.lat(), c.lng(), now.lat(), now.lng());
                return t.state(c, t.armed(), d <= c.radius() ? "in" : d >= c.radius() + MARGIN ? "out" : "");
            }
            default -> { return t; }
        }
    }
    /** Whether a state condition already holds as primed (it then waits for the next time): for the words said back. */
    public static boolean waiting(Trigger t) { return !t.armed() && List.of(POWER, PRICE).contains(t.when().kind()) && !PLUGGED.equals(t.when().edge()) && !UNPLUGGED.equals(t.when().edge()); }

    /** Metres between two points (haversine). */
    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    // ── notices ───────────────────────────────────────────────────
    private static final List<String> FOOD = List.of(Deliveries.Stage.ORDERED.id, Deliveries.Stage.COOKING.id, Deliveries.Stage.READY.id, Deliveries.Stage.ON_WAY.id, Deliveries.Stage.NEAR.id, Deliveries.Stage.DONE.id);
    private static final List<String> BOX = List.of(NoticeActions.SHIPPED, NoticeActions.OUT, NoticeActions.DONE);
    static boolean noticeHit(Condition c, Event e) {
        boolean app = appMatches(c.app(), e.app(), e.label());
        switch (c.sort()) {
            case DELIVERY -> {
                if (!DELIVERY.equals(e.sort()) || !app) return false;
                String want = c.stage().isEmpty() ? Deliveries.Stage.DONE.id : c.stage();
                if (want.equals(Deliveries.Stage.CANCELED.id) || e.stage().equals(Deliveries.Stage.CANCELED.id)) return want.equals(e.stage());
                return FOOD.indexOf(e.stage()) >= FOOD.indexOf(want) && FOOD.indexOf(want) >= 0;
            }
            case PARCEL -> {
                if (!PARCEL.equals(e.sort()) || !app) return false;
                String want = c.stage().isEmpty() ? NoticeActions.DONE : c.stage();
                return BOX.indexOf(e.stage()) >= BOX.indexOf(want) && BOX.indexOf(want) >= 0;
            }
            case MESSAGE -> {
                if (!MESSAGE.equals(e.sort()) || !app) return false;
                return (c.who().isEmpty() || key(e.who()).contains(key(c.who()))) && wordsIn(c.words(), e.who() + " " + e.text());
            }
            default -> { return app && (!c.app().isEmpty() || !c.words().isEmpty()) && wordsIn(c.words(), e.who() + " " + e.text()); }
        }
    }
    private static boolean wordsIn(String words, String text) {
        if (words == null || words.isBlank()) return true;
        String t = key(text);
        for (String w : words.split("[,·|/]")) if (!key(w).isEmpty() && !t.contains(key(w))) return false;
        return true;
    }
    /** Apps by what people call them: a name said finds the package or the label (카톡 → com.kakao.talk, 문자 → messaging). */
    private static final String[][] APPS = {
        {"카카오톡", "카톡", "kakaotalk", "com.kakao.talk"}, {"전화", "dialer", "incallui", "telecom", "phone"}, {"메시지", "문자", "메세지", "sms", "messaging", "com.android.mms"}, {"텔레그램", "telegram"},
        {"라인", "jp.naver.line"}, {"디스코드", "discord"}, {"인스타그램", "인스타", "instagram"}, {"왓츠앱", "whatsapp"},
        {"배민", "배달의민족", "baemin", "woowahan", "sampleapp"}, {"쿠팡이츠", "coupangeats", "coupang.mobile.eats"}, {"요기요", "yogiyo"}, {"땡겨요", "ddangyo"},
        {"쿠팡", "coupang"}, {"네이버", "naver", "com.nhn"}, {"지메일", "gmail"}, {"유튜브", "youtube"}, {"당근", "당근마켓", "daangn"},
        {"우체국", "epost"}, {"cj대한통운", "대한통운", "cj택배", "cjlogistics"}, {"한진", "hanjin"}, {"롯데택배", "lotteglogis"}, {"컬리", "kurly"}};
    public static boolean appMatches(String want, String pkg, String label) {
        String w = key(want); if (w.isEmpty()) return true;
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT), l = key(label);
        if (l.contains(w) || p.contains(w)) return true;
        for (String[] group : APPS) {
            boolean said = false; for (String g : group) said |= key(g).equals(w);
            if (!said) continue;
            for (String g : group) if (l.contains(key(g)) || p.contains(g.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    // ── devices ───────────────────────────────────────────────────
    /** What the user calls the car. */
    static final Pattern CAR_WORD = Pattern.compile("차|내차|우리차|자동차|car|내 차|우리 차");
    /** A paired device's name that is a car's (a carmaker, a car kit): for picking the car when the user has not yet. */
    private static final Pattern CAR_NAME = Pattern.compile("(?i)\\bcar\\b|mycar|my car|carkit|car ?kit|carplay|android ?auto|hyundai|kia|genesis|bmw|benz|mercedes|audi|tesla|volvo|toyota|lexus|honda|nissan|renault|chevrolet|chevy|ssangyong|kgm|polestar|porsche|volkswagen|\\bvw\\b|ford|jeep|mini|peugeot|lincoln|cadillac|현대|기아|제네시스|르노|쉐보레|쌍용|벤츠|아우디|테슬라|볼보|차량|자동차|내 ?차|블루링크|bluelink|uvo|ccnc|ccs|mbux|sync|mylink|^my ?car");
    public static boolean carName(String deviceName) { return deviceName != null && CAR_NAME.matcher(deviceName).find(); }
    static boolean deviceHit(Condition c, String address, String deviceName) {
        if (!c.id().isEmpty()) return c.id().equalsIgnoreCase(address == null ? "" : address);
        // Not picked yet: the car by a car's name, another device by its name ("버즈" in "Galaxy Buds2 Pro" is not found; "버즈" is).
        if (c.car()) return carName(deviceName);
        return !c.name().isEmpty() && deviceNamed(c.name(), deviceName);
    }
    /** Whether a device's name is the one said ("버즈" → "Galaxy Buds", "에어팟" → "AirPods"). */
    public static boolean deviceNamed(String said, String deviceName) {
        String w = key(said), n = key(deviceName);
        if (w.isEmpty() || n.isEmpty()) return false;
        if (n.contains(w)) return true;
        for (String[] g : new String[][]{{"버즈", "buds"}, {"에어팟", "airpods"}, {"이어폰", "buds", "airpods", "earbuds", "headset", "earphone"}, {"헤드폰", "headphone", "wh1000", "xm4", "xm5", "qc"}, {"헤드셋", "headset"}, {"워치", "watch"}, {"스피커", "speaker", "jbl", "soundlink"}})
            if (key(g[0]).equals(w)) for (String x : g) if (n.contains(x)) return true;
        return false;
    }
    /** The paired device a trigger means, by its name ({name, address} pairs), or null when none or more than one is it. */
    public static String[] pickDevice(Condition c, List<String[]> bonded, String carAddress, String carName) {
        if (c.car() && carAddress != null && !carAddress.isBlank()) return new String[]{carName == null || carName.isBlank() ? carAddress : carName, carAddress};
        List<String[]> found = new ArrayList<>();
        for (String[] d : bonded) if (c.car() ? carName(d[0]) : deviceNamed(c.name(), d[0])) found.add(d);
        return found.size() == 1 ? found.get(0) : null;
    }

    // ── the words ─────────────────────────────────────────────────
    /** "집에 도착하면", "차에 타면", "충전이 다 되면", "비트코인이 1억 원을 넘으면", "배달이 출발하면", "엄마한테서 카톡 오면". */
    public static String when(Condition c) {
        String n = c.name();
        return switch (c.kind()) {
            case PLACE -> ARRIVE.equals(c.edge()) ? n + "에 도착하면" : n + "에서 나가면";
            case BLUETOOTH -> c.car() ? (CONNECT.equals(c.edge()) ? "차에 타면" : "차에서 내리면") : "‘" + n + "’" + (CONNECT.equals(c.edge()) ? " 연결되면" : " 연결이 끊기면");
            case POWER -> switch (c.edge()) {
                case PLUGGED -> "충전기를 꽂으면"; case UNPLUGGED -> "충전기를 빼면"; case FULL -> "충전이 다 되면";
                case LEVEL_UP -> "충전이 " + (int) c.value() + "%가 되면"; default -> "배터리가 " + (int) c.value() + "% 아래로 떨어지면";
            };
            case PRICE -> {
                String who = Brand.josa(n, "이", "가");
                yield switch (c.edge()) {
                    case ABOVE -> who + " " + Brand.josa(money(c.value(), c.unit()), "을", "를") + " 넘으면";
                    case BELOW -> who + " " + money(c.value(), c.unit()) + " 아래로 떨어지면";
                    case RISE -> who + " " + pct(c.value()) + " 오르면";
                    case FALL -> who + " " + pct(c.value()) + " 떨어지면";
                    default -> who + " " + pct(c.value()) + " 넘게 움직이면";
                };
            }
            default -> noticeWhen(c);
        };
    }
    private static String noticeWhen(Condition c) {
        String app = c.app();
        switch (c.sort()) {
            case DELIVERY -> {
                String pre = app.isEmpty() ? "" : app + " ";
                return switch (c.stage().isEmpty() ? Deliveries.Stage.DONE.id : c.stage()) {
                    case "ordered" -> pre + "주문이 들어가면"; case "cooking" -> pre + "음식을 만들기 시작하면"; case "ready" -> pre + "음식이 다 되면";
                    case "onway" -> pre + "배달이 출발하면"; case "near" -> pre + "배달이 곧 오면"; case "canceled" -> pre + "주문이 취소되면"; default -> pre + "배달이 오면";
                };
            }
            case PARCEL -> {
                String pre = app.isEmpty() ? "" : app + " ";
                return switch (c.stage().isEmpty() ? NoticeActions.DONE : c.stage()) {
                    case NoticeActions.SHIPPED -> pre + "택배가 발송되면"; case NoticeActions.OUT -> pre + "택배가 배송을 시작하면"; default -> pre + "택배가 오면";
                };
            }
            case MESSAGE -> {
                String what = messageWord(app);
                return (c.who().isEmpty() ? "" : c.who() + "한테서 ") + what + " 오면" + (c.words().isEmpty() ? "" : "(‘" + c.words() + "’)");
            }
            default -> {
                String words = c.words().isEmpty() ? "" : "‘" + c.words() + "’ ";
                return app.isEmpty() ? words + "알림이 오면" : "‘" + app + "’에서 " + words + "알림이 오면";
            }
        }
    }
    private static String messageWord(String app) {
        String a = key(app);
        if (a.isEmpty()) return "메시지";
        if (a.contains("카톡") || a.contains("카카오") || a.contains("kakao")) return "카톡";
        if (a.contains("문자") || a.contains("메시지") || a.contains("메세지") || a.contains("sms")) return "문자";
        if (a.equals("전화") || a.contains("dialer")) return "전화";
        return app + " 메시지";
    }
    /** "알려 줄게", "네이버 지도 열게", "집으로 길 안내할게", "엄마한테 보낼 문자를 써 둘게. 보내기는 너가 눌러 줘". */
    public static String promise(Action a) {
        return switch (a.kind()) {
            case OPEN_APP -> a.target() + " 열게";
            case NAVIGATE -> Brand.euro(a.target()) + " 길 안내할게";
            case PLAY -> Brand.josa(a.target(), "을", "를") + " 틀게";
            case DRAFT -> drafted(a, true);
            case JOB -> "‘" + cut(a.target(), 30) + "’ 맡길게";
            default -> a.text().isEmpty() ? "알려 줄게" : reminder(a.text()) + " 알려 줄게";
        };
    }
    /** A draft in words: "엄마한테 보낼 문자를 써 둘게. 보내기는 너가 눌러 줘" (future) or "엄마한테 보낼 문자 써 뒀어, 눌러서 보내 줘" (done). */
    static String drafted(Action a, boolean future) {
        String who = a.who().isEmpty() ? "" : a.who() + "한테 ";
        return switch (a.tool()) {
            case "call" -> future ? who + "전화 걸 준비를 해 둘게. 통화는 너가 눌러 줘" : who + "전화 걸 준비 해 뒀어, 눌러서 걸어 줘";
            case "email" -> future ? who + "보낼 메일을 써 둘게. 보내기는 너가 눌러 줘" : who + "보낼 메일 써 뒀어, 눌러서 보내 줘";
            case "share" -> future ? who + "보낼 " + messageWord(a.app()) + "을 써 둘게. 받는 사람 고르기와 보내기는 너가 해 줘" : who + "보낼 " + messageWord(a.app()) + " 써 뒀어, 눌러서 보내 줘";
            default -> future ? who + "보낼 문자를 써 둘게. 보내기는 너가 눌러 줘" : who + "보낼 문자 써 뒀어, 눌러서 보내 줘";
        };
    }
    /** What to remind of, as said: "우산 챙기라고" stays, a bare thing is quoted ("‘약 먹기’"). */
    private static String reminder(String text) { return text.matches(".*(라고|다고|자고|냐고)$") ? text : "‘" + text + "’" + Brand.josa(text, "이라고", "라고").substring(text.length()); }
    /** The words said back when one is made: "차에 타면 네이버 지도 열게. 매번." */
    public static String said(Trigger t) {
        String back = when(t.when()) + " " + promise(t.then()) + ". " + (t.every() ? "매번." : "한 번만.");
        return waiting(t) ? back + " " + waitingWords(t.when()) : back;
    }
    /** Said when the state already holds as it is made: it waits for the next time. */
    private static String waitingWords(Condition c) {
        return switch (c.edge()) {
            case FULL -> "지금 벌써 다 차 있어서, 다음번에 다 차면 할게.";
            case LEVEL_UP -> "지금 벌써 그만큼 차 있어서, 다음번에 할게.";
            case LEVEL_DOWN -> "지금 벌써 그 아래라서, 충전했다가 다시 떨어지면 할게.";
            case ABOVE -> "지금 벌써 넘어 있어서, 내려갔다가 다시 넘으면 할게.";
            case BELOW -> "지금 벌써 그 아래라서, 올라갔다가 다시 떨어지면 할게.";
            default -> "";
        };
    }
    /** For the list: "차에 타면 → 네이버 지도 열기". */
    public static String title(Trigger t) { return when(t.when()) + " → " + todo(t.then()); }
    /** "알려 주기", "네이버 지도 열기", "집으로 길 안내", "아이유 틀기", "엄마한테 보낼 문자 쓰기", "‘…’ 맡기기". */
    public static String todo(Action a) {
        String who = a.who().isEmpty() ? "" : a.who() + "한테 ";
        return switch (a.kind()) {
            case OPEN_APP -> a.target() + " 열기";
            case NAVIGATE -> Brand.euro(a.target()) + " 길 안내";
            case PLAY -> a.target() + " 틀기";
            case DRAFT -> switch (a.tool()) { case "call" -> who + "전화 걸 준비"; case "email" -> who + "보낼 메일 쓰기"; case "share" -> who + "보낼 " + messageWord(a.app()) + " 쓰기"; default -> who + "보낼 문자 쓰기"; };
            case JOB -> "‘" + cut(a.target(), 24) + "’ 맡기기";
            default -> a.text().isEmpty() ? "알려 주기" : reminder(a.text()) + " 알려 주기";
        };
    }
    /** "매번 · 켜짐 · 지난번 11/27 18:20", "한 번 · 끝남 · 11/27 18:20에 했어". */
    public static String state(Trigger t, long now, ZoneId zone) {
        String when = t.fired() > 0 ? clock(t.fired(), now, zone) : "";
        if (t.done()) return (t.every() ? "매번" : "한 번") + " · 끝남" + (when.isEmpty() ? "" : " · " + when + "에 했어");
        return (t.every() ? "매번" : "한 번") + (t.on() ? "" : " · 꺼짐") + (when.isEmpty() ? "" : " · 지난번 " + when);
    }
    private static String clock(long at, long now, ZoneId zone) {
        ZonedDateTime z = Instant.ofEpochMilli(at).atZone(zone), n = Instant.ofEpochMilli(now).atZone(zone);
        String hm = String.format(Locale.ROOT, "%02d:%02d", z.getHour(), z.getMinute());
        return z.toLocalDate().equals(n.toLocalDate()) ? "오늘 " + hm : z.getMonthValue() + "/" + z.getDayOfMonth() + " " + hm;
    }
    /** What happened, as 퍼레 tells it when one fires: "집에 도착했어", "차에 탔네", "충전 다 됐어", "비트코인이 1억 원을 넘었어 (지금 1억 23만 원)". */
    public static String happened(Trigger t, Event e) {
        Condition c = t.when();
        return switch (c.kind()) {
            case PLACE -> ARRIVE.equals(c.edge()) ? c.name() + "에 도착했어" : c.name() + "에서 나왔어";
            case BLUETOOTH -> c.car() ? (CONNECT.equals(c.edge()) ? "차에 탔네" : "차에서 내렸네") : "‘" + c.name() + "’" + (CONNECT.equals(c.edge()) ? " 연결됐어" : " 연결이 끊겼어");
            case POWER -> switch (c.edge()) {
                case PLUGGED -> "충전기 꽂았네"; case UNPLUGGED -> "충전기 뺐네";
                case FULL -> "충전 다 됐어" + (e != null && e.value() > 0 ? " (" + (int) e.value() + "%)" : "");
                case LEVEL_UP -> "충전이 " + (int) (e != null && e.value() > 0 ? e.value() : c.value()) + "% 됐어";
                default -> "배터리가 " + (int) (e != null && e.value() >= 0 ? e.value() : c.value()) + "% 남았어";
            };
            case PRICE -> {
                String who = Brand.josa(c.name(), "이", "가"), now = e == null || !(e.value() > 0) ? "" : " (지금 " + money(e.value(), c.unit()) + ")";
                yield switch (c.edge()) {
                    case ABOVE -> who + " " + Brand.josa(money(c.value(), c.unit()), "을", "를") + " 넘었어" + now;
                    case BELOW -> who + " " + money(c.value(), c.unit()) + " 아래로 떨어졌어" + now;
                    case RISE -> who + " " + pct(c.value()) + " 넘게 올랐어" + now;
                    case FALL -> who + " " + pct(c.value()) + " 넘게 떨어졌어" + now;
                    default -> who + " " + pct(c.value()) + " 넘게 움직였어" + now;
                };
            }
            default -> noticeHappened(c, e);
        };
    }
    private static String noticeHappened(Condition c, Event e) {
        String app = e == null || e.label().isEmpty() ? c.app() : e.label();
        switch (c.sort()) {
            case DELIVERY -> {
                String stage = e == null || e.stage().isEmpty() ? c.stage() : e.stage();
                return switch (stage) {
                    case "ordered" -> "주문 들어갔대"; case "cooking" -> "음식 만드는 중이래"; case "ready" -> "음식 다 됐대";
                    case "onway" -> "배달 출발했대 🛵"; case "near" -> "배달 곧 온대!"; case "canceled" -> "주문 취소됐대"; default -> "배달 왔대";
                };
            }
            case PARCEL -> {
                String stage = e == null || e.stage().isEmpty() ? c.stage() : e.stage();
                return switch (stage) { case NoticeActions.SHIPPED -> "택배 발송됐대"; case NoticeActions.OUT -> "택배 오늘 온대"; default -> "택배 왔대 📦"; };
            }
            case MESSAGE -> {
                String who = c.who().isEmpty() ? (e == null ? "" : e.who()) : c.who();
                return (who.isEmpty() ? "" : who + "한테서 ") + messageWord(c.app().isEmpty() && e != null ? e.label() : c.app()) + " 왔어";
            }
            default -> { return app.isEmpty() ? "알림이 왔어" : "‘" + app + "’ 알림이 왔어"; }
        }
    }
    /** What was done, after what happened: "네이버 지도 열었어", "엄마한테 보낼 문자 써 뒀어, 눌러서 보내 줘", "‘…’ 맡겼어"; for TELL the reminder. */
    public static String did(Action a, boolean started) {
        return switch (a.kind()) {
            case OPEN_APP -> started ? a.target() + " 열었어" : "눌러서 " + a.target() + " 열기";
            case NAVIGATE -> started ? Brand.euro(a.target()) + " 길 안내 시작했어" : "눌러서 " + Brand.euro(a.target()) + " 길 안내";
            case PLAY -> started ? a.target() + " 틀었어" : "눌러서 " + a.target() + " 틀기";
            case DRAFT -> drafted(a, false);
            case JOB -> started ? "‘" + cut(a.target(), 24) + "’ 맡겼어" : "‘" + cut(a.target(), 24) + "’ 못 맡겼어";
            default -> a.text().isEmpty() ? "" : a.text().matches(".*(라고|다고|자고)$") ? a.text() + " 했지!" : "‘" + a.text() + "’ 잊지 마!";
        };
    }
    /** "걸어 둔 건 두 개야. 차에 타면 네이버 지도 열기, 매번. 충전이 다 되면 알려 주기, 한 번." */
    public static String listSay(List<Trigger> all) {
        List<Trigger> live = new ArrayList<>(); for (Trigger t : all) if (t.active()) live.add(t);
        if (live.isEmpty()) return all.isEmpty() ? "걸어 둔 조건 알림은 없어. ‘집에 도착하면 알려 줘’처럼 말하면 걸어 둘게." : "켜져 있는 조건 알림은 없어. 꺼 둔 건 " + count(all.size()) + "야.";
        StringBuilder out = new StringBuilder("걸어 둔 건 " + count(live.size()) + "야.");
        for (int i = 0; i < Math.min(3, live.size()); i++) out.append(' ').append(title(live.get(i)).replace(" → ", " ")).append(", ").append(live.get(i).every() ? "매번." : "한 번.");
        if (live.size() > 3) out.append(" 그 밖에 ").append(count(live.size() - 3)).append(" 더 있어.");
        return out.toString();
    }
    private static String count(int n) { return n == 1 ? "하나" : n == 2 ? "두 개" : n == 3 ? "세 개" : n == 4 ? "네 개" : n + "개"; }
    /** The triggers the words name ("차 타면 지도 켜는 거"), best first: those whose words and kind match most. */
    public static List<Trigger> named(List<Trigger> all, String which) {
        List<Trigger> out = new ArrayList<>();
        if (all.isEmpty()) return out;
        String w = key(which == null ? "" : which.replaceAll("(?:거|것|알림|조건|트리거|좀|다시|꺼|켜|지워|없애|취소)(?:\\s|$)", " "));
        if (w.isEmpty()) { if (all.size() == 1) out.add(all.get(0)); return out; }
        int best = 0;
        for (Trigger t : all) {
            int score = score(t, w);
            if (score > best) { best = score; out.clear(); out.add(t); } else if (score == best && score > 0) out.add(t);
        }
        return out;
    }
    private static int score(Trigger t, String w) {
        String c = key(title(t) + " " + t.said() + " " + t.when().app() + " " + t.when().who() + " " + t.then().target());
        int score = 0;
        for (int i = 0; i + 2 <= w.length(); i++) if (c.contains(w.substring(i, i + 2))) score++;
        String kindWords = switch (t.when().kind()) {
            case BLUETOOTH -> t.when().car() ? "차타|운전|차에|블루투스" : "블루투스|연결|이어폰|버즈";
            case PLACE -> "도착|나가|나오|장소|위치";
            case POWER -> "충전|배터리";
            case PRICE -> "가격|시세|넘|떨어|오르|코인|비트";
            default -> "알림|배달|택배|카톡|문자|메시지";
        };
        for (String k : kindWords.split("\\|")) if (w.contains(key(k))) score += 2;
        return score;
    }

    // ── money (only in a trigger the user asked for: 퍼레 never brings up prices by itself) ──
    /** "1억 230만 원", "9,870만 원", "870원", "70,000달러". */
    public static String money(double v, String unit) {
        if (USD.equals(unit)) return (v >= 100 ? String.format(Locale.ROOT, "%,.0f", v) : v >= 1 ? trim(String.format(Locale.ROOT, "%.2f", v)) : trim(String.format(Locale.ROOT, "%.4f", v))) + "달러";
        long w = Math.round(v);
        if (w >= 100_000_000L) { long eok = w / 100_000_000L, man = (w % 100_000_000L) / 10_000L; return eok + "억" + (man > 0 ? " " + String.format(Locale.ROOT, "%,d", man) + "만" : "") + " 원"; }
        if (w >= 10_000L) { long man = w / 10_000L, rest = w % 10_000L; return String.format(Locale.ROOT, "%,d", man) + "만" + (rest > 0 && man < 100 ? " " + String.format(Locale.ROOT, "%,d", rest) : "") + " 원"; }
        return v >= 1 || v == 0 ? String.format(Locale.ROOT, "%,d", w) + "원" : trim(String.format(Locale.ROOT, "%.2f", v)) + "원";
    }
    private static String trim(String n) { return n.contains(".") ? n.replaceAll("0+$", "").replaceAll("\\.$", "") : n; }
    private static String pct(double v) { return trim(String.format(Locale.ROOT, "%.1f", v)) + "%"; }
    /** Coins and stocks by what people call them: {words regex, symbol, name, "coin" or "stock"}. */
    private static final String[][] SYMBOLS = {
        {"비트코인|비트|btc|bitcoin", "BTC", "비트코인", "coin"}, {"이더리움|이더|eth|ethereum", "ETH", "이더리움", "coin"}, {"리플|xrp", "XRP", "리플", "coin"},
        {"솔라나|솔|sol|solana", "SOL", "솔라나", "coin"}, {"도지코인|도지|doge", "DOGE", "도지코인", "coin"}, {"에이다|카르다노|ada", "ADA", "에이다", "coin"},
        {"트론|trx", "TRX", "트론", "coin"}, {"체인링크|link", "LINK", "체인링크", "coin"}, {"폴카닷|dot", "DOT", "폴카닷", "coin"}, {"수이|sui", "SUI", "수이", "coin"},
        {"바이낸스코인|bnb", "BNB", "BNB", "coin"}, {"테더|usdt", "USDT", "테더", "coin"},
        {"엔비디아|nvda|nvidia", "NVDA", "엔비디아", "stock"}, {"테슬라|tsla|tesla", "TSLA", "테슬라", "stock"}, {"애플|aapl|apple", "AAPL", "애플", "stock"},
        {"마이크로소프트|마소|msft", "MSFT", "마이크로소프트", "stock"}, {"아마존|amzn", "AMZN", "아마존", "stock"}, {"구글|알파벳|googl", "GOOGL", "구글", "stock"},
        {"메타|meta", "META", "메타", "stock"}, {"삭슬|soxl", "SOXL", "SOXL", "stock"}, {"코루|koru", "KORU", "KORU", "stock"}, {"마이크로스트래티지|mstr", "MSTR", "MSTR", "stock"}};
    /** {symbol, name, coin|stock} the words name, or null. */
    public static String[] symbol(String words) {
        String w = (words == null ? "" : words).toLowerCase(Locale.ROOT);
        for (String[] s : SYMBOLS) if (Pattern.compile("(?<![a-z가-힣])(?:" + s[0] + ")(?![a-z])").matcher(w).find()) return new String[]{s[1], s[2], s[3]};
        return null;
    }
    /**
     * Where to read a price: KRW from Upbit's public ticker (Binance has no won), USD from Binance's public tickers (USDⓈ-M
     * futures first, which also follow tokenized stocks, then spot for a coin), the same hosts the trading page reads.
     */
    public static List<String> quoteUrls(String symbol, String unit) {
        String s = symbol == null ? "" : symbol.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (s.isEmpty()) return List.of();
        if (KRW.equals(unit)) return List.of("https://api.upbit.com/v1/ticker?markets=KRW-" + s);
        String pair = TradingData.pair(s);
        boolean coin = false; for (String[] x : SYMBOLS) if (x[1].equals(s)) coin = x[3].equals("coin");
        return coin ? List.of("https://api.binance.com/api/v3/ticker/price?symbol=" + pair, "https://fapi.binance.com/fapi/v1/ticker/price?symbol=" + pair)
            : List.of("https://fapi.binance.com/fapi/v1/ticker/price?symbol=" + pair);
    }
    /** The price in a ticker's answer (Binance {"price": "…"}, Upbit [{"trade_price": …}]), or NaN. */
    public static double quote(String json) {
        try {
            String t = json == null ? "" : json.strip();
            if (t.startsWith("[")) { JSONObject o = new JSONArray(t).optJSONObject(0); return o == null ? Double.NaN : o.optDouble("trade_price", Double.NaN); }
            JSONObject o = new JSONObject(t);
            double p = o.has("price") ? Double.parseDouble(o.optString("price")) : o.optDouble("trade_price", Double.NaN);
            return p > 0 ? p : Double.NaN;
        } catch (Exception unreadable) { return Double.NaN; }
    }

    // ── from the words ────────────────────────────────────────────
    /** A trigger read from words: its condition (a place or a device still by name), action, once or every; why when it cannot be. */
    public record Draft(Condition when, Action then, String mode, String why) {}
    /**
     * Reads what the tool was given (when: the condition's words; then: tell, open_app, navigate, play, message, phone_job,
     * web_job, work_job; what, who, text; every) with the user's own words (heard) filling what the brain left out: "집에
     * 도착하면 엄마한테 문자 보내 줘" is a condition, then a message drafted (never sent: a send is always a draft).
     */
    public static Draft read(String when, String then, String what, String who, String text, Boolean every, String heard) {
        String words = s(heard);
        String[] split = split(words);
        // The condition in the user's own words first (the quick model wrote when="카톡 오면" for "엄마한테 카톡 오면"), the brain's when after.
        Condition c = split != null ? condition(split[0]) : null;
        if (c == null) c = condition(s(when));
        if (c == null && !words.isEmpty()) c = condition(words);
        if (c == null) return new Draft(null, null, ONCE, "");
        if (PRICE.equals(c.kind()) && "stock".equals(kindOf(c.id())) && KRW.equals(c.unit())) return new Draft(null, null, ONCE, "주식 가격은 달러로만 볼 수 있어. ‘엔비디아 200달러 넘으면’처럼 말해 줘.");
        // The user's words are the authority (퍼레 자동 점검, 2026-11-27: the quick model put text="배달 출발했어" and every=true
        // for "배달 출발하면 알려 줘"): a body or a name the words do not have is the brain's own, and left out.
        String rest = split == null ? "" : split[1];
        String body = words.isEmpty() || key(words).contains(key(text)) ? s(text) : "", to = words.isEmpty() || key(words).contains(key(who)) ? s(who) : "";
        Action heardAct = rest.isEmpty() ? null : fromWords(rest);
        Action a = heardAct != null && !JOB.equals(heardAct.kind()) && !(TELL.equals(heardAct.kind()) && !s(then).isEmpty() && !TELL.equals(s(then)) && !Pattern.compile("알려|말해|알림").matcher(rest).find())
            ? fill(heardAct, s(what), to, body) : action(s(then), s(what), to, body, rest);
        // A send is never done by itself: it becomes a draft the user sends.
        a = safe(a, words);
        if (a == null) return new Draft(c, null, ONCE, "그때 뭘 할지 못 알아들었어. ‘알려 줘’, ‘지도 열어 줘’처럼 말해 줘.");
        if (JOB.equals(a.kind()) && (AutomationGate.requestsForbidden(a.target()) || AutomationGate.requestsForbidden(words))) return new Draft(c, a, ONCE, "결제·송금·삭제 같은 일은 걸어 둘 수 없어. 그건 직접 해 줘.");
        if (JOB.equals(a.kind()) && "phone_job".equals(a.tool()) && !a.app().isEmpty() && AutomationGate.bankingApp(a.app())) return new Draft(c, a, ONCE, "은행·카드·증권 앱은 내가 조작하지 않아. 그건 직접 해 줘.");
        String mode = !words.isEmpty() || every == null ? mode(words + " " + s(when)) : every ? EVERY : ONCE;
        return new Draft(c, a, mode, "");
    }
    private static String kindOf(String symbol) { for (String[] x : SYMBOLS) if (x[1].equals(symbol)) return x[3]; return "coin"; }
    private static final Pattern EVERY_WORD = Pattern.compile("매번|항상|언제나|늘\\s|때마다|그때마다|마다\\s|계속|자동으로"), ONCE_WORD = Pattern.compile("한\\s?번만|이번만|이번에만|딱\\s?한\\s?번"), HABIT = Pattern.compile("(?:ㄹ|을|할|탈|갈|올|낼|뺄|꽂을|될|볼)\\s?때(?!마다)");
    /** EVERY when the words say so (매번, 항상, …할 때마다, …할 때), else ONCE. */
    public static String mode(String words) {
        String w = s(words);
        if (ONCE_WORD.matcher(w).find()) return ONCE;
        return EVERY_WORD.matcher(w + " ").find() || HABIT.matcher(w).find() ? EVERY : ONCE;
    }
    /** Words ending in 면 that are no condition (라면, 화면 …). */
    private static final Set<String> NOT_IF = Set.of("라면", "화면", "측면", "전면", "정면", "표면", "장면", "방면", "국면", "단면", "냉면", "쫄면", "짜장면", "우동면", "이면", "반면", "대면", "비대면", "당면", "면", "수면", "지면", "후면", "양면", "평면", "내면", "외면");
    private static final Pattern CUT = Pattern.compile("(\\S*?(?:면|거든|자마자|때마다|때|대로))(?=[\\s,.]|$)");
    /** {condition, then} from words like "집에 도착하면 엄마한테 문자 보내 줘"; null when there is no 하면 / 할 때 in them. */
    public static String[] split(String words) {
        String w = s(words);
        Matcher m = CUT.matcher(w);
        while (m.find()) {
            String token = m.group(1);
            if (NOT_IF.contains(token) || token.endsWith("대로") && !token.equals("대로") || token.endsWith("때") && token.length() > 1 && !token.matches(".*(?:ㄹ|을|할|탈|갈|올|낼|뺄|될|볼|날|울)때")) continue;
            if (token.equals("가면") && !w.substring(0, m.start()).matches("(?s).*(?:에|으로|로)\\s*$")) continue; // 가면 (a mask) unless 집에 가면
            if (token.endsWith("면") && token.length() < 2) continue;
            return new String[]{w.substring(0, m.end()).strip(), w.substring(m.end()).replaceAll("^[\\s,.]+", "").strip()};
        }
        return null;
    }

    // ── a condition from its words ──
    private static final Pattern PCT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:%|퍼센트|프로|퍼)");
    private static final Pattern DOWN = Pattern.compile("떨어|내려|하락|빠지|빠져|밑으로|아래로|이하|깨지|깨면|낮아|미만");
    private static final Pattern UP = Pattern.compile("넘|이상|돌파|위로|올라|오르|상승|뛰|찍|도달|높아|초과|되면|가면");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:[.,]\\d+)*)\\s*(억|천만|백만|십만|만|천|백|k|K)?");
    /** A Korean amount: "1억 2천만" 120,000,000; "9,800만" 98,000,000; "7만" 70,000; "1.2억"; "70k"; 0 when none. */
    public static double amount(String words) {
        Matcher m = NUMBER.matcher(s(words));
        double total = 0; int end = -1;
        while (m.find()) {
            if (end >= 0 && !s(words).substring(end, m.start()).isBlank()) break;
            String digits = m.group(1).replace(",", "");
            double n; try { n = Double.parseDouble(digits); } catch (NumberFormatException no) { break; }
            String unit = m.group(2) == null ? "" : m.group(2);
            double times = switch (unit) { case "억" -> 1e8; case "천만" -> 1e7; case "백만" -> 1e6; case "십만" -> 1e5; case "만" -> 1e4; case "천" -> 1e3; case "백" -> 100; case "k", "K" -> 1e3; default -> 1; };
            // "1억 2천만": 2천만 after 억 is 2 × 천만.
            total += n * times; end = m.end();
            if (unit.isEmpty()) break;
        }
        return total;
    }
    /** A condition in words, or null. Order matters: a price, the battery, the car or a device, a notice, then a place. */
    public static Condition condition(String words) {
        String w = s(words); if (w.isEmpty()) return null;
        String b = w.replaceAll("\\s+", "");
        Condition c = priceOf(w);
        if (c == null) c = powerOf(w, b);
        if (c == null) c = deviceOf(w, b);
        if (c == null) c = noticeOf(w, b);
        if (c == null) c = placeOf(w, b);
        return c;
    }
    private static Condition priceOf(String w) {
        String[] sym = symbol(w); if (sym == null) return null;
        String rest = w.toLowerCase(Locale.ROOT);
        boolean down = DOWN.matcher(rest).find(), up = UP.matcher(rest).find();
        Matcher p = PCT.matcher(rest);
        if (p.find()) {
            double v = Double.parseDouble(p.group(1)); if (v <= 0 || v > 1000) return null;
            String edge = down && !Pattern.compile("올라|오르|상승|뛰").matcher(rest).find() ? FALL : Pattern.compile("올라|오르|상승|뛰").matcher(rest).find() ? RISE : MOVE;
            return Condition.price(edge, sym[1], sym[0], v, USD, 0);
        }
        if (!down && !up) return null;
        // The number after the coin's name ("비트코인 1억", "이더 500만 원", "엔비디아 200달러").
        Matcher at = Pattern.compile("(?:\\$\\s*)?\\d").matcher(rest);
        if (!at.find()) return null;
        String from = rest.substring(at.start()).replace("$", "");
        double v = amount(from);
        if (!(v > 0)) return null;
        boolean usd = Pattern.compile("달러|불(?![가-힣])|\\$|usd|usdt").matcher(rest).find(), krw = Pattern.compile("원(?![가-힣])|억|krw").matcher(rest).find();
        String unit = usd ? USD : krw ? KRW : "stock".equals(sym[2]) ? USD : v >= 1_000_000 ? KRW : USD;
        return Condition.price(down ? BELOW : ABOVE, sym[1], sym[0], v, unit, 0);
    }
    private static Condition powerOf(String w, String b) {
        if (!Pattern.compile("충전|배터리|완충|전원|케이블|밧데리").matcher(b).find()) return null;
        Matcher p = PCT.matcher(w);
        if (p.find()) {
            int level = (int) Math.round(Double.parseDouble(p.group(1)));
            if (level >= 100) return Condition.power(FULL, 100);
            if (level <= 0) return null;
            boolean down = Pattern.compile("떨어|아래|밑|이하|남으|남았|남으면|부족|빠지|내려|미만|모자").matcher(b).find();
            boolean up = Pattern.compile("충전|차면|넘|올라|이상").matcher(b).find();
            return Condition.power(down ? LEVEL_DOWN : up ? LEVEL_UP : level >= 50 ? LEVEL_UP : LEVEL_DOWN, level);
        }
        if (Pattern.compile("완충|충전(?:이|을)?다(?:되|돼|차|찼|됐|끝)|충전(?:이|을)?끝나|충전완료|배터리(?:가)?다(?:차|찼|되|돼)|가득").matcher(b).find()) return Condition.power(FULL, 100);
        if (Pattern.compile("충전기(?:를|을)?(?:빼|뽑|분리|떼|빠지)|충전(?:을)?(?:빼|멈추|중단|그만)|케이블(?:을|를)?(?:빼|뽑)|전원(?:을|를)?(?:빼|뽑)|충전(?:이)?(?:끊|해제)").matcher(b).find()) return Condition.power(UNPLUGGED, 0);
        if (Pattern.compile("충전기(?:를|을)?(?:꽂|꼽|연결|끼우|대|물리)|충전(?:을)?(?:시작|하기시작)|충전(?:하면|할때|중이면|되기시작)|전원(?:을|를)?(?:꽂|연결)|케이블(?:을|를)?(?:꽂|연결)").matcher(b).find()) return Condition.power(PLUGGED, 0);
        return null;
    }
    private static final Pattern CAR = Pattern.compile("(?<![가-힣])(?:내\\s?|우리\\s?)?(?:자동)?차(?:에서|에|를|로|가|랑|와|\\s)*");
    private static Condition deviceOf(String w, String b) {
        boolean car = Pattern.compile("(?<![가-힣])(?:내\\s?|우리\\s?)?(?:자동)?차\\s?(?:에서|에|를|가|랑|와|의)?\\s?(?:타|탔|탈|올라|탑승|내리|내렸|내릴|하차|블루투스|연결|시동)").matcher(w).find()
            || Pattern.compile("운전(?:을|하러|할|하면|시작|끝|마치|마칠)|시동(?:을)?(?:걸|켜|끄|꺼)|드라이브").matcher(b).find();
        if (car) {
            boolean off = Pattern.compile("내리|내렸|내릴|하차|운전(?:이|을)?(?:끝|마치|마칠)|시동(?:을)?(?:끄|꺼)|끊|해제|떨어지").matcher(b).find();
            return Condition.bluetooth(off ? DISCONNECT : CONNECT, "차", "");
        }
        Matcher m = Pattern.compile("([A-Za-z0-9가-힣]+?)?\\s*(블루투스|이어폰|헤드폰|헤드셋|버즈|에어팟|스피커|워치)(?:이|가|을|를|랑|과|와)?\\s*(?:연결|붙|켜지|끼|끊|해제|빼|꺼지|떨어지)").matcher(w);
        if (!m.find()) return null;
        String noun = m.group(2), before = m.group(1) == null ? "" : m.group(1);
        String name = noun.equals("블루투스") && !before.isEmpty() ? before : before.matches("갤럭시|내|우리||무선") ? noun : before + " " + noun;
        boolean off = Pattern.compile("끊|해제|빼|꺼지|떨어지").matcher(w.substring(m.start())).find();
        return Condition.bluetooth(off ? DISCONNECT : CONNECT, name.strip(), "");
    }
    private static Condition noticeOf(String w, String b) {
        // A food delivery's stage (core/Deliveries).
        if (Pattern.compile("배달|라이더|배민|쿠팡이츠|요기요|땡겨요|음식(?:이|을)?(?:다|오|도착|출발)").matcher(b).find() && !b.contains("택배")) {
            String stage = Pattern.compile("취소").matcher(b).find() ? "canceled" : Pattern.compile("출발|시작|픽업|가지고|오기시작").matcher(b).find() ? "onway"
                : Pattern.compile("곧|거의|근처").matcher(b).find() ? "near" : Pattern.compile("조리(?:가|를)?(?:완료|다|끝)|음식(?:이)?다(?:되|돼|만들)").matcher(b).find() ? "ready"
                : Pattern.compile("조리|만들기").matcher(b).find() ? "cooking" : Pattern.compile("주문(?:이)?(?:접수|들어|수락)").matcher(b).find() ? "ordered" : "done";
            String app = b.contains("쿠팡이츠") ? "쿠팡이츠" : b.contains("요기요") ? "요기요" : b.contains("땡겨요") ? "땡겨요" : b.contains("배민") || b.contains("배달의민족") ? "배민" : "";
            return Condition.notice(DELIVERY, app, "", "", stage);
        }
        // A parcel's (NoticeActions).
        if (Pattern.compile("택배|소포|등기|배송|로켓|주문한(?:거|게|것|물건)|물건(?:이)?(?:오|도착)").matcher(b).find()) {
            String stage = Pattern.compile("발송|출고|보내면|보냈").matcher(b).find() ? NoticeActions.SHIPPED : Pattern.compile("출발|배송(?:을|이)?시작|오늘(?:도착|온|와)|배송중").matcher(b).find() ? NoticeActions.OUT : NoticeActions.DONE;
            Matcher shop = Pattern.compile("(쿠팡|CJ대한통운|대한통운|우체국|한진|롯데|로젠|컬리|네이버|11번가|G마켓|지마켓|SSG|무신사|알리|테무)").matcher(w);
            return Condition.notice(PARCEL, shop.find() ? shop.group(1) : "", "", "", stage);
        }
        // A messenger's sender ("엄마한테 카톡 오면", "팀장님 문자 오면").
        Matcher msg = Pattern.compile("(?:([가-힣A-Za-z0-9]+?)\\s*(?:한테서|한테|에게서|에게|께서|께|으로부터|로부터|이|가|의)?\\s+)?(카톡|카카오톡|문자|메시지|메세지|톡|텔레그램|디엠|DM|dm|라인|연락|전화)(?:이|가|으로|로)?\\s*(?:오|왔|들어오|받|도착)").matcher(w);
        if (msg.find()) {
            String who = msg.group(1) == null ? "" : msg.group(1).replaceAll("(한테서|한테|에게서|에게|께서|께)$", "");
            if (who.matches("새|무슨|아무|어떤|누가|누구|그|이|내|나")) who = "";
            String app = switch (msg.group(2)) { case "카톡", "카카오톡", "톡" -> "카카오톡"; case "문자", "메시지", "메세지" -> "메시지"; case "텔레그램" -> "텔레그램"; case "라인" -> "라인"; case "디엠", "DM", "dm" -> "인스타그램"; case "전화" -> "전화"; default -> ""; };
            return Condition.notice(MESSAGE, app, who, "", "");
        }
        // Any app's notice, with words ("쿠팡에서 ‘배송 완료’ 알림 오면", "은행 알림 오면").
        Matcher any = Pattern.compile("(?:([가-힣A-Za-z0-9]+?)(?:\\s*앱)?(?:에서|의)?\\s+)?(?:[‘'\"“]([^’'\"”]+)[’'\"”](?:라는|이라는|라고|이라고)?\\s*)?알림(?:이)?\\s*(?:오|왔|뜨|떴|울리|들어오)").matcher(w);
        if (any.find() && (any.group(1) != null || any.group(2) != null)) {
            String app = any.group(1) == null ? "" : any.group(1);
            if (app.matches("새|무슨|아무|어떤|그|이|내|무슨")) app = "";
            return app.isEmpty() && any.group(2) == null ? null : Condition.notice(APP, app, "", any.group(2) == null ? "" : any.group(2).strip(), "");
        }
        return null;
    }
    private static final Pattern NOT_PLACE = Pattern.compile(".*(?:알림|메시지|문자|카톡|전화|가격|시세|한테|에게|께서).*|(?:비|눈|소나기|태풍|미세먼지|아침|저녁|밤|시간|때|날|봄|여름|가을|겨울|누가|누구|사람|손님|엄마|아빠).*|.*(?:이|가|은|는|을|를|도|만)$");
    private static Condition placeOf(String w, String b) {
        if (b.contains("퇴근")) return Condition.place(LEAVE, "회사", Double.NaN, Double.NaN, 0);
        if (b.contains("출근")) return Condition.place(ARRIVE, "회사", Double.NaN, Double.NaN, 0);
        // Leaving: 에서 나가면 · 나서면 · 떠나면 · 출발하면. Arriving: 에/으로 오면·가면, or 도착하면 · 들어가면 (a bare "비 오면" is weather).
        Matcher leave = Pattern.compile("(.+?)\\s*(?:에서)?\\s*(?:나가|나오|나서|떠나|출발하|출발해|벗어나)").matcher(w);
        Matcher arrive = Pattern.compile("(.+?)(?:\\s*(?:에|으로|로|까지)\\s*(?:도착|다\\s?(?:오|왔|가|와)|돌아오|돌아가|들어오|들어가|들어서|닿|들르|오|가|와)|\\s*(?:도착|돌아오|돌아가|들어오|들어가|들어서))").matcher(w);
        Matcher m = leave.find() ? leave : arrive.find() ? arrive : null;
        if (m == null) return null;
        String name = m.group(1).strip().replaceAll("^(?:내가|나|저|제가|우리가)\\s+", "").strip();
        if (name.isEmpty() || name.length() > 24 || NOT_PLACE.matcher(name).matches()) return null;
        return Condition.place(m == leave ? LEAVE : ARRIVE, name, Double.NaN, Double.NaN, 0);
    }

    // ── an action from its words ──
    private static final Pattern SEND = Pattern.compile("보내|전송|답장|남겨|send|올려\\s?줘?\\s*(?:카톡|단톡)");
    /** The action: as the brain named it (then, what, who, text), else read from the words after the condition. */
    /** An action read from the words, what they left blank filled from the brain's (the app as it named it, the person, the body). */
    private static Action fill(Action a, String what, String who, String text) {
        return switch (a.kind()) {
            case DRAFT -> new Action(DRAFT, a.tool(), a.target(), a.text().isEmpty() ? text : a.text(), a.who().isEmpty() ? who : a.who(), a.app());
            case OPEN_APP -> a.target().isEmpty() && !what.isEmpty() ? Action.open(what) : a;
            default -> a;
        };
    }
    public static Action action(String then, String what, String who, String text, String words) {
        String w = s(words);
        switch (s(then)) {
            case TELL: { String r = remindOf(w); return Action.tell(!r.isEmpty() || !w.isEmpty() ? r : !s(text).isEmpty() ? s(text) : remindOf(s(what))); }
            case "open_app": return s(what).isEmpty() ? fromWords(w) : Action.open(s(what));
            case "navigate": return s(what).isEmpty() ? fromWords(w) : Action.route(s(what).replaceAll("(으로|로|까지)$", ""));
            case "play": return s(what).isEmpty() ? fromWords(w) : Action.play(s(what), "");
            case "message", "share", "email", "call": {
                Action fromWords = fromWords(w);
                String body = !s(text).isEmpty() ? s(text) : fromWords != null && DRAFT.equals(fromWords.kind()) ? fromWords.text() : "";
                String to = !s(who).isEmpty() ? s(who) : fromWords != null && DRAFT.equals(fromWords.kind()) && !fromWords.who().isEmpty() ? fromWords.who() : then.equals("share") ? "" : s(what);
                return Action.draft(then, to, body, then.equals("share") ? (s(what).isEmpty() ? "카카오톡" : s(what)) : "");
            }
            case "phone_job", "web_job", "work_job", "research_job": {
                String request = !s(what).isEmpty() ? s(what) : !w.isEmpty() ? w : s(text);
                if (request.isEmpty()) return null;
                return Action.job(then, request, "phone_job".equals(then) ? appIn(request) : "");
            }
            default: return w.isEmpty() ? Action.tell("") : fromWords(w);
        }
    }
    private static final Pattern TELL_WORDS = Pattern.compile("^(?:(.+?)\\s*(?:라고|하라고|다고|자고))?\\s*(?:꼭\\s*|바로\\s*|좀\\s*)?(?:나한테\\s*)?(?:알려|말해|알림|얘기해|이야기해|깨워|불러)|^(?:알려|말해)");
    private static String remindOf(String w) {
        Matcher m = TELL_WORDS.matcher(s(w));
        if (m.find() && m.group(1) != null) return m.group(1).strip() + (w.contains("하라고") ? "하라고" : w.contains("다고") ? "다고" : "라고");
        return "";
    }
    /** The action in words ("알려 줘", "네이버 지도 열어 줘", "집으로 길 안내해 줘", "아이유 노래 틀어 줘", "엄마한테 문자 보내 줘", "배민에서 지난번 메뉴 담아 줘"). */
    public static Action fromWords(String words) {
        String w = s(words).replaceAll("(?:매번|항상|언제나|꼭|좀|바로|자동으로|한\\s?번만)\\s*", "").replaceAll("\\s*(?:해\\s?줘|해\\s?주세요|해\\s?줄래|줘|주세요|줄래|해)[.!?~]*$", "").strip();
        if (w.isEmpty() || TELL_WORDS.matcher(w).find()) return Action.tell(remindOf(s(words)));
        String b = w.replaceAll("\\s+", "");
        // A send: always a draft (퍼레 never sends by itself).
        if (SEND.matcher(b).find() || Pattern.compile("전화(?:를)?(?:걸|해|하)").matcher(b).find()) {
            Matcher to = Pattern.compile("([가-힣A-Za-z0-9]+?)\\s*(?:한테|에게|께)").matcher(w);
            String who = to.find() ? to.group(1) : "";
            String body = "";
            Matcher said = Pattern.compile("[‘'\"“]([^’'\"”]+)[’'\"”]").matcher(w);
            if (said.find()) body = said.group(1).strip();
            else { Matcher clause = Pattern.compile("(?:한테|에게|께)\\s*(.+?)\\s*(?:(?:이)?라고|고)\\s+(?:문자|카톡|카카오톡|메시지|메세지|메일|톡|보내|전해|남겨)").matcher(w + " "); if (clause.find()) body = clause.group(1).strip(); }
            if (Pattern.compile("전화(?:를)?(?:걸|해|하)").matcher(b).find()) return Action.draft("call", who, "", "");
            if (b.contains("메일")) return Action.draft("email", who, body, "");
            if (Pattern.compile("카톡|카카오톡|톡").matcher(b).find()) return Action.draft("share", who, body, "카카오톡");
            return Action.draft("message", who, body, "");
        }
        Matcher route = Pattern.compile("(.+?)\\s*(?:으로|로|까지)?\\s*(?:길\\s*)?(?:안내|길찾기|길\\s*찾아|내비|네비|가는\\s*길)").matcher(w);
        if (route.find() && !route.group(1).isBlank()) return Action.route(route.group(1).strip().replaceAll("(?:으로|로|까지)$", "").replaceAll("^(?:바로|곧장)\\s*", ""));
        Matcher play = Pattern.compile("(?:(유튜브|유튜브\\s?뮤직|멜론|스포티파이|지니)(?:에서|로)?\\s*)?(.+?)\\s*(?:을|를)?\\s*(?:틀어|재생|들려|켜\\s?줘\\s*노래)").matcher(w);
        if (play.find() && !play.group(2).isBlank()) return Action.play(play.group(2).strip(), play.group(1) == null ? "" : play.group(1));
        Matcher open = Pattern.compile("(.+?)\\s*(?:앱|어플)?\\s*(?:을|를)?\\s*(?:열어|열기|켜|띄워|실행)").matcher(w);
        if (open.find() && !open.group(1).isBlank() && !Pattern.compile("에서\\s").matcher(open.group(1) + " ").find()) return Action.open(open.group(1).strip());
        // A job: in an app (phone_job), on a site (web_job), or to work out (work_job).
        String app = appIn(w);
        if (!app.isEmpty()) return Action.job("phone_job", w, app);
        if (Pattern.compile("(?i)사이트|홈페이지|https?://|www\\.|\\.com|\\.kr").matcher(w).find()) return Action.job("web_job", w, "");
        if (Pattern.compile("찾아|검색|정리|요약|조사|분석|써|만들어|확인").matcher(b).find()) return Action.job("work_job", w, "");
        return null;
    }
    /** An app the words name for a phone job ("배민에서 …" → 배민). */
    private static String appIn(String w) {
        Matcher m = Pattern.compile("(배민|배달의민족|쿠팡이츠|쿠팡|요기요|카카오톡|카톡|네이버|당근|토스|유튜브|인스타그램|인스타|11번가|G마켓|무신사|컬리|야놀자|여기어때|카카오T|카카오택시|티맵|멜론)\\s*(?:에서|앱에서|으로|로|을|를)?").matcher(s(w));
        return m.find() ? m.group(1) : "";
    }
    /** A send turned into its draft (never sent by itself); anything else as it is. */
    static Action safe(Action a, String heard) {
        if (a == null || !JOB.equals(a.kind())) return a;
        String b = a.target().replaceAll("\\s+", "");
        if (!SEND.matcher(b).find()) return a;
        Action draft = fromWords(a.target());
        return draft != null && DRAFT.equals(draft.kind()) ? draft : Action.draft("message", "", "", "");
    }
    /** A time said in the words (아침 7시, 9시 30분): such a request is a 예약 작업, not an event. */
    public static boolean timed(String words) { return Pattern.compile("\\d{1,2}\\s*시|\\d{1,2}:\\d{2}|(?:매일|매주|평일|주말)\\s*(?:아침|저녁|밤|오전|오후)?").matcher(s(words)).find(); }

    // ── helpers ──
    private static String s(String v) { return v == null ? "" : v.strip(); }
    static String cut(String v, int most) { String t = s(v); return t.length() > most ? t.substring(0, most - 1) + "…" : t; }
    /** Words as matched: no spaces or marks, lower case. */
    static String key(String v) { return v == null ? "" : v.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…~‘’“”]+", ""); }
}
