package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.NaverPlaces;
import dev.jun.gyeot.core.SavedPlaces;
import dev.jun.gyeot.core.Triggers;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * 퍼레's when_event tool (사건 트리거, stage 2): "…하면 …" made from the user's words, said back clearly ("차에 타면 네이버
 * 지도 열게. 매번."), and the ones kept listed, turned off or on, or deleted by words ("내 조건 알림 뭐 있어?", "차 타면 지도
 * 켜는 거 꺼 줘"). The condition is read in core/Triggers; here it is found on the phone: a place by 자주 가는 곳 or a search
 * (PlaceLocator), the car among the paired devices (호출어's car first), the price and the battery now (so a state that
 * already holds waits for the next time). What a trigger needs to be heard and is not allowed yet is said in one line, with
 * a notification whose tap allows it (TriggerFire.ask). A request with a time and no event goes to 예약 작업 as schedule_job
 * would. Off the main thread (BuddyActs).
 */
final class BuddyTriggers {
    private BuddyTriggers() {}
    static final java.util.Set<String> TOOLS = java.util.Set.of("when_event");
    /** What to say, the line why (for 퍼레가 못 한 일, "" none), its one tap (BuddyMisses' fix), and whether nothing was done. */
    record Said(String say, String why, String fix, boolean failed) {
        static Said ok(String say) { return new Said(say, "", "", false); }
        static Said no(String say, String fix) { return new Said(say, say, fix == null ? "" : fix, true); }
    }
    private static final Pattern LIST = Pattern.compile("(?:조건\\s?알림|걸어\\s?둔|걸어둔|트리거|조건).*(?:뭐|무슨|몇|있어|있나|목록|알려|보여|말해)|(?:뭐|무슨)\\s*(?:조건\\s?알림|걸어\\s?둔)");
    /** "…거 꺼 줘", "…알림 지워 줘", "…거 다시 켜 줘": words about one already made (not a new "차 타면 블루투스 꺼 줘"). */
    private static final Pattern TARGETED = Pattern.compile("(?:거|것|알림|조건|트리거)\\s*(?:좀\\s*|도\\s*)?(?:다시\\s*)?(꺼|끄|지워|지우|없애|삭제|취소|그만|멈춰|중지|켜|걸어)");

    static Said run(Context c, JSONObject act, String heard) {
        String said = heard == null ? "" : heard.strip(), command = act.optString("command").strip().toLowerCase(java.util.Locale.ROOT);
        // The user's words say what to do with one made ("…거 꺼 줘", "…알림 지워 줘", "뭐 있어?"), whatever the brain chose
        // (퍼레 자동 점검, 2026-11-27: "차 타면 지도 켜는 거 꺼 줘" came as command=delete) or left out.
        java.util.regex.Matcher m = TARGETED.matcher(said);
        if (m.find() && !TriggerStore.list(c).isEmpty()) command = m.group(1).matches("켜|걸어") ? "on" : m.group(1).matches("꺼|끄|그만|멈춰|중지") ? "off" : "delete";
        else if ((command.isEmpty() || command.equals("make")) && LIST.matcher(said).find() && act.optString("when").isBlank()) command = "list";
        return switch (command) {
            case "list", "show" -> Said.ok(Triggers.listSay(TriggerStore.list(c)));
            case "off", "on", "delete", "cancel", "remove", "stop" -> change(c, command, (act.optString("when") + " " + act.optString("what")).strip(), said);
            default -> make(c, act, said);
        };
    }

    // ── off, on, delete ──
    private static Said change(Context c, String command, String which, String said) {
        List<Triggers.Trigger> all = TriggerStore.list(c);
        if (all.isEmpty()) return Said.ok("걸어 둔 조건 알림이 없어.");
        List<Triggers.Trigger> found = Triggers.named(all, which.isEmpty() ? said : which);
        if (found.isEmpty() && !which.isEmpty()) found = Triggers.named(all, said);
        if (found.isEmpty()) return Said.ok("그런 조건 알림은 못 찾았어. " + Triggers.listSay(all));
        if (found.size() > 1) {
            StringBuilder ask = new StringBuilder("어느 거? ");
            for (int i = 0; i < Math.min(3, found.size()); i++) ask.append(i == 0 ? "" : " 아니면 ").append("‘").append(Triggers.title(found.get(i)).replace(" → ", " ")).append("’");
            return Said.ok(ask.append("?").toString());
        }
        Triggers.Trigger t = found.get(0);
        String name = "‘" + Triggers.title(t).replace(" → ", " ") + "’";
        String back;
        switch (command) {
            case "on" -> { TriggerStore.change(c, list -> Triggers.switched(list, t.id(), true)); back = name + " 다시 켰어."; }
            case "off", "stop" -> { TriggerStore.change(c, list -> Triggers.switched(list, t.id(), false)); back = name + " 껐어. 다시 켜 달라면 켤게."; }
            default -> { TriggerStore.change(c, list -> Triggers.remove(list, t.id())); back = name + " 지웠어."; }
        }
        TriggerWatch.sync(c);
        return Said.ok(back);
    }

    // ── a new one ──
    private static Said make(Context c, JSONObject act, String heard) {
        Triggers.Draft d = Triggers.read(act.optString("when"), act.optString("then"), act.optString("what"), act.optString("who"), act.optString("text"),
            act.has("every") ? act.optBoolean("every") : null, heard);
        if (!d.why().isEmpty()) return Said.no(d.why(), "");
        if (d.when() == null) {
            // A time and no event (매일 아침 7시에 …): 예약 작업, the way schedule_job makes one.
            if (Triggers.timed(heard) || Triggers.timed(act.optString("when"))) {
                JSONObject job = new JSONObject();
                try { job.put("tool", "schedule_job").put("request", act.optString("what").isBlank() ? heard : act.optString("what")); } catch (Exception impossible) { /* none */ }
                BuddyHands.Started s = BuddyHands.start(c, job, heard);
                return s.ok() ? Said.ok(s.say()) : Said.no(s.say(), s.fix());
            }
            return Said.no("어떤 일이 생기면 할지 못 알아들었어. ‘집에 도착하면’, ‘차에 타면’, ‘충전 다 되면’처럼 말해 줘.", "");
        }
        if (d.then() == null) return Said.no("그때 뭘 할지 못 알아들었어. ‘알려 줘’, ‘지도 열어 줘’처럼 말해 줘.", "");
        Triggers.Condition when = d.when();
        long now = System.currentTimeMillis();
        String why = "", fix = "";
        switch (when.kind()) {
            case Triggers.PLACE -> {
                NaverPlaces.Place place = locate(c, when.name());
                if (place == null) {
                    String bare = when.name();
                    return Said.no("‘" + bare + "’" + dev.jun.gyeot.core.Brand.josa(bare, "이", "가").substring(bare.length()) + " 어딘지 아직 몰라. 자주 가는 곳에 저장해 주면 바로 걸어 둘게.", "place:" + bare);
                }
                when = when.at(when.name(), place.lat(), place.lng());
                if (!TriggerWatch.locationAllowed(c)) { why = "도착·출발을 알려면 위치를 ‘항상 허용’해 줘. 알림을 누르면 바로 허용할 수 있어."; fix = "allow:location"; }
            }
            case Triggers.BLUETOOTH -> {
                if (!TriggerWatch.bluetoothAllowed(c)) { why = (when.car() ? "차에 탄 걸" : "연결된 걸") + " 알려면 ‘근처 기기’ 권한이 필요해. 알림을 눌러 허용해 줘."; fix = "allow:bluetooth"; }
                else {
                    AppStore store = ((GyeotApp) c.getApplicationContext()).store();
                    String[] device = Triggers.pickDevice(when, TriggerWatch.bonded(c), store.settings.getString(WakeWordFiles.CAR, ""), store.settings.getString(WakeWordFiles.CAR_NAME, ""));
                    if (device != null) when = when.device(device[1]);
                    else { why = when.car() ? "어느 블루투스 기기가 차인지 한 번만 골라 줘. 알림을 누르면 고를 수 있어." : "어느 블루투스 기기인지 한 번만 골라 줘. 알림을 누르면 고를 수 있어."; fix = "pick"; }
                }
            }
            case Triggers.NOTICE -> { if (!NoticeListener.allowed(c)) { why = "알림을 보려면 ‘알림 접근’을 허용해 줘. 알림을 누르면 바로 열려."; fix = "notices"; } }
            default -> { }
        }
        Triggers.Trigger made;
        try { made = add(c, Triggers.make(when, d.then(), d.mode(), heard.isEmpty() ? Triggers.when(when) : heard, now)); }
        catch (IllegalStateException full) { return Said.no(full.getMessage(), ""); }
        String back = Triggers.said(made);
        if (fix.isEmpty()) return Said.ok(back);
        if (fix.equals("pick")) fix = "pick:" + made.id();
        TriggerFire.ask(c, fix, why);
        return new Said(back + " " + why, why, fix, false);
    }
    /**
     * A trigger kept and listened for, from a structured spec (Triggers.make; 퍼레's routine suggestion after the 하루 타임라인 is
     * meant to call this): first primed with what is so now (the battery, the price, where the phone is), so a state that
     * already holds waits for the next time. Returns it as kept (Triggers.said(it) says it back); throws IllegalStateException
     * when the list is full. Network (a price): off the main thread.
     */
    static Triggers.Trigger add(Context c, Triggers.Trigger t) {
        Triggers.Condition when = t.when(); long now = System.currentTimeMillis();
        Triggers.Event seen = switch (when.kind()) {
            case Triggers.POWER -> TriggerWatch.battery(c);
            case Triggers.PRICE -> { double p = TriggerWatch.quotes.price(when.id(), when.unit()); yield p > 0 ? Triggers.Event.price(when.id(), when.unit(), p, now) : null; }
            case Triggers.PLACE -> TriggerWatch.here(c);
            default -> null;
        };
        Triggers.Trigger made = Triggers.prime(t, seen);
        TriggerStore.change(c, all -> Triggers.add(all, made));
        TriggerWatch.start(c);
        TriggerWatch.log(c, "● 조건 알림 걸기: " + Triggers.title(made) + " · " + (made.every() ? "매번" : "한 번"));
        return made;
    }
    /** Where a place is: 자주 가는 곳 by its words, else the phone's search (Naver's 지역 검색, the geocoder); null when not found. Network. */
    static NaverPlaces.Place locate(Context c, String words) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        SavedPlaces.Saved saved = SavedPlaces.match(words, store.savedPlaces());
        if (saved != null) return saved.place();
        // 집·회사 not saved: never a search for the word 집 (BuddyActs.navigate does the same).
        if (SavedPlaces.own(words)) return null;
        try { List<NaverPlaces.Place> found = PlaceLocator.find(c, store, words); return found.isEmpty() ? null : found.get(0); }
        catch (RuntimeException unreachable) { return null; }
    }
}
