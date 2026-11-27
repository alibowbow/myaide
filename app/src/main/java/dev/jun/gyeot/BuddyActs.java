package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import dev.jun.gyeot.core.BuddyTricks;
import dev.jun.gyeot.core.DeviceActions;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * What 퍼레 does on the phone when asked (준: "퍼레는 폰조작이 안되네"), from the home screen or over another app (준: "퍼레
 * 대화모드일때는 다른 앱 위에 그려지게 해줘"): an app opened by its name, a web search, and the edge assistant's own phone
 * actions (DeviceActions: a route, an alarm, a timer, music, the torch, a settings panel, the screen's rotation), the
 * volume and brightness, the apps' own ways (a share, a text, the dialer, the camera, an event, a memo, a mail, a translator,
 * the gallery, contacts, downloads, the recorder, an app's info), and 퍼레's own answers that need no app (BuddyTricks: a
 * coin, dice, a pick, a sum, the time elsewhere, days to a date, its stopwatch, its memory, its quiet), started the way Android allows from
 * where it is now (DeviceActionRunner.start: in front, the accessibility service, or the overlay). Any thread.
 */
final class BuddyActs {
    private BuddyActs() {}

    /*
     * heard: what the user said (an alarm or a timer runs at once when it says its time or length, else only after a yes);
     * overlay: 퍼레's window is over another app.
     */
    /** What failed in this act (fail()), kept by run() in BuddyMisses with the words that asked for it. */
    private static final ThreadLocal<String[]> MISS = new ThreadLocal<>();
    /** A failed act's words, with the tap that clears the way ("" none, BuddyMisses). */
    private static BuddyVoice.Outcome fail(String say, String fix) { MISS.set(new String[]{say, fix == null ? "" : fix}); return new BuddyVoice.Outcome(say, false, null); }
    private static final ThreadLocal<String> HEARD = new ThreadLocal<>();
    /** A thing done its way that may still go wrong later (no number found …): kept, while the act goes on. */
    private static void note(String why, String fix) { MISS.set(new String[]{why, fix == null ? "" : fix}); }
    /**
     * Who hears of an act that went wrong after it was started (BuddyVoice: 퍼레 says it once): what (the app's name) did not
     * come up, with the tap that clears the way ("" none).
     */
    interface Late { void notShown(String what, String fix); }
    private static final ThreadLocal<Late> LATE = new ThreadLocal<>();
    /** How an opened app is seen to come up (폰 조작: PhoneService.whenShown, on the main thread); tests put their own. */
    interface Watch { boolean on(); void whenShown(String pkg, long withinMs, java.util.function.Consumer<Boolean> shown); }
    static Watch watch = new Watch() {
        @Override public boolean on() { return PhoneService.connected(); }
        @Override public void whenShown(String pkg, long withinMs, java.util.function.Consumer<Boolean> shown) { PhoneService.whenShown(pkg, withinMs, shown); }
    };
    /**
     * With 폰 조작 connected, whether the app it opened came to the front (준: "4번" — 됐는지 확인까지); one that did not is kept
     * in BuddyMisses with the words that asked for it, and with the way through when it is this app's own (the overlay
     * permission lets it start apps from behind). name: the app's name to say it did not come up (Late; open_app and share
     * only: YouTube's search always shows, so nothing is said for it); null says nothing.
     */
    private static void verify(Context context, String pkg, String what, String name) {
        Watch w = watch;
        if (!w.on() || pkg == null) return;
        String heard = HEARD.get(); Late late = name == null ? null : LATE.get();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> w.whenShown(pkg, 4000, shown -> {
            if (shown) return;
            String fix = android.provider.Settings.canDrawOverlays(context) ? "" : "overlay";
            BuddyMisses.add(context, heard, what, "열었는데 화면에 안 떴어요", fix, BuddyMisses.NOT_SHOWN);
            ((GyeotApp) context.getApplicationContext()).store().phoneLog("  · 퍼레 · " + what + " 열었는데 안 보여요");
            if (late != null) late.notShown(name, fix);
        }));
    }
    static BuddyVoice.Outcome run(Context context, JSONObject act, String heard, boolean overlay) { return run(context, act, heard, overlay, false, null); }
    /**
     * answers: heard answers 퍼레's own read-back ("아니 8시로" to "…7:00에 알람 맞출까?"), so an alarm whose hour it says is
     * set without a second question; late: who hears of an app that did not come up (null: no one).
     */
    static BuddyVoice.Outcome run(Context context, JSONObject act, String heard, boolean overlay, boolean answers, Late late) {
        MISS.remove(); HEARD.set(heard); LATE.set(late);
        BuddyVoice.Outcome o;
        try { o = runOnce(context, act, heard, overlay, answers); } finally { LATE.remove(); }
        String[] m = MISS.get(); MISS.remove();
        if (m != null) {
            String what = act.optString("tool") + " " + (act.optString("app") + " " + act.optString("destination") + " " + act.optString("query") + " " + act.optString("panel")).strip();
            BuddyMisses.add(context, heard, what.strip(), m[0], m[1]);
            ((GyeotApp) context.getApplicationContext()).store().phoneLog("  · 퍼레 · 못 함: " + what.strip() + " · " + m[0]);
            if (o != null) o = new BuddyVoice.Outcome(o.say(), o.leaves(), o.confirm(), true);
        }
        return o;
    }
    /**
     * The acts that tell something of the user (a song, a place, what they looked up, a plan), with how the talk's learning
     * names them; a torch, the volume or a coin tells nothing (준: "필요없는것들이 다 기록이돼").
     */
    private static final Map<String, String> TELLING = Map.of("play", "노래", "navigate", "길 안내", "map_search", "장소 찾기", "search", "검색", "calendar_add", "일정");
    /**
     * An act done, as the voice talk's learning reads it (BuddyTalk.learnTalk): its time, what it was and about, and the app,
     * "22:10 노래 ‘아이유 좋은날’(멜론)"; "" for an act that tells nothing of the user.
     */
    static String line(JSONObject a, long at) {
        String name = a == null ? null : TELLING.get(a.optString("tool").strip());
        if (name == null) return "";
        String what = "";
        for (String key : new String[]{"query", "destination", "title", "text"}) {
            String v = a.optString(key).strip(); if (!v.isEmpty()) { what = " ‘" + (v.length() > 40 ? v.substring(0, 40) + "…" : v) + "’"; break; }
        }
        String app = a.optString("app").strip();
        return new java.text.SimpleDateFormat("HH:mm", Locale.KOREA).format(new java.util.Date(at)) + " " + name + what + (app.isEmpty() ? "" : "(" + app + ")");
    }
    /**
     * An act in a few words, for the brain's next answer and 퍼레가 못 한 일: its tool, then what it was about (the words to
     * find, the place, the time, the length, the panel), then the app: "play · ‘아이유 좋은날’ · YouTube", "alarm · 07:30",
     * "timer · 180초".
     */
    static String about(JSONObject a) {
        if (a == null) return "";
        String tool = a.optString("tool").strip();
        java.util.List<String> parts = new java.util.ArrayList<>(java.util.List.of(tool));
        switch (tool) {
            case "alarm" -> parts.add(String.format(Locale.ROOT, "%02d:%02d", a.optInt("hour"), a.optInt("minute")));
            case "timer" -> parts.add(a.optLong("seconds") + "초");
            default -> {
                for (String key : new String[]{"query", "destination", "text", "who", "title", "toy", "play", "thing"}) {
                    String v = a.optString(key).strip(); if (v.isEmpty()) continue;
                    parts.add("‘" + (v.length() > 40 ? v.substring(0, 40) + "…" : v) + "’"); break;
                }
                for (String key : new String[]{"command", "panel", "mode", "direction"}) { String v = a.optString(key).strip(); if (!v.isEmpty()) { parts.add(v); break; } }
            }
        }
        String app = a.optString("app").strip(); if (!app.isEmpty()) parts.add(app);
        return String.join(" · ", parts);
    }
    private static BuddyVoice.Outcome runOnce(Context context, JSONObject act, String heard, boolean overlay, boolean answers) {
        String tool = act.optString("tool").strip().toLowerCase(Locale.ROOT);
        try {
            switch (tool) {
                case "open_app" -> {
                    String name = act.optString("app").strip();
                    LauncherActivityInfo info = findApp(context, name);
                    if (info == null) return fail("'" + name + "' 앱은 이 휴대폰에서 못 찾았어.", "store:" + name);
                    String route = open(context, info, overlay);
                    if (DeviceActionRunner.started(route)) verify(context, info.getComponentName().getPackageName(), "open_app " + name, String.valueOf(info.getLabel()));
                    return started(context, route, info.getLabel() + " 열었어", true);
                }
                case "search" -> {
                    String q = act.optString("query").strip(); if (q.isEmpty()) return null;
                    Intent search = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (search.resolveActivity(context.getPackageManager()) == null)
                        search = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    return started(context, DeviceActionRunner.start(context, search, overlay), "", true);
                }
                case "navigate" -> { return navigate(context, act, overlay); }
                case "play" -> { return play(context, act, overlay); }
                case "share" -> { return share(context, act, overlay); }
                case "message" -> { return message(context, act, overlay); }
                case "call" -> { return call(context, act, overlay); }
                case "camera" -> { return camera(context, act, overlay); }
                case "calendar_add" -> { return calendarAdd(context, act, overlay); }
                case "open_url" -> { return openUrl(context, act, overlay); }
                case "map_search" -> { String q = act.optString("query").strip(); if (q.isEmpty()) return fail("뭘 찾을지 알려 줘.", ""); JSONObject r = DeviceActionRunner.run(context, DeviceActions.search(q, true), overlay); return r.optBoolean("ok") ? new BuddyVoice.Outcome("", DeviceActionRunner.started(r.optString("route")), null) : fail(r.optString("say", "지도를 못 열었어."), ""); }
                case "volume" -> { return volume(context, act); }
                case "brightness" -> { return brightness(context, act, overlay); }
                case "show_timers" -> { return timers(context, overlay); }
                case "calendar_view" -> { return calendarView(context, act, overlay); }
                case "memo" -> { return memo(context, act, overlay); }
                case "recorder" -> { return recorder(context, overlay); }
                case "app_settings" -> { return appSettings(context, act, overlay); }
                case "downloads" -> { return downloads(context, overlay); }
                case "gallery" -> { return gallery(context, overlay); }
                case "contacts" -> { return contacts(context, act, overlay); }
                case "calculator" -> { return calculator(context, act, overlay); }
                case "email" -> { return email(context, act, overlay); }
                case "translate" -> { return translate(context, act, overlay); }
                // 퍼레's own, no app: the answer worked out here (BuddyTricks), never made up by the brain.
                case "stopwatch" -> { return stopwatch(context, act); }
                case "coin" -> { java.util.Random r = java.util.concurrent.ThreadLocalRandom.current(); return said(BuddyTricks.coinSay(BuddyTricks.heads(r), r)); }
                case "dice" -> { java.util.Random r = java.util.concurrent.ThreadLocalRandom.current(); return said(BuddyTricks.diceSay(BuddyTricks.dice(act.optInt("count", 1), act.optInt("sides", 6), r))); }
                case "pick" -> { return pick(act); }
                case "world_time" -> { return worldTime(act); }
                case "count_days" -> { return countDays(act); }
                case "remember" -> { return remember(context, act); }
                case "forget" -> { return forget(context, act); }
                case "call_me" -> {
                    String t = BuddyTricks.title(act.optString("title"));
                    if (t.isEmpty()) return fail("어떻게 부를지 열두 글자 안으로 알려 줘.", "");
                    BuddyTalk.title(context, t);
                    return said("");
                }
                case "quiet" -> { return quiet(context, act); }
                // 퍼레 itself on the home screen (BuddyBoard): at once while it is in front, else when it is shown again.
                case "buddy_wish" -> { BuddyTalk.wish(context, act.optString("thing", act.optString("toy"))); return new BuddyVoice.Outcome("", false, null); }
                case "buddy_toy", "buddy_outfit", "buddy_move" -> {
                    String call = BuddyBoard.call(act);
                    if (call == null) return fail("그건 나한테 없어.", "");
                    return new BuddyVoice.Outcome(BuddyBoard.send(call) ? "" : "홈 화면에 가면 바로 할게!", false, null);
                }
                default -> { /* the phone's own actions, below */ }
            }
            String name = switch (tool) {
                case "alarm" -> DeviceActions.ALARM; case "timer" -> DeviceActions.TIMER; case "navigate" -> DeviceActions.NAVIGATE;
                case "media" -> DeviceActions.MEDIA; case "torch" -> DeviceActions.TORCH; case "settings" -> DeviceActions.SETTINGS;
                case "show_alarms" -> DeviceActions.SHOW_ALARMS; case "rotation" -> DeviceActions.ROTATION; default -> "";
            };
            if (name.isEmpty()) return fail("그건 아직 내가 못 해.", "");
            JSONObject args = new JSONObject(act.toString()); args.remove("tool");
            if (DeviceActions.ALARM.equals(name) || DeviceActions.TIMER.equals(name)) {
                args.put("confirmed", true);
                // A time or a length the user said themselves is set at once and read back as it is set (준: "퍼레의 판단력");
                // only a guessed one waits for a yes: none said, another than said, or an hour without 오전·오후 (7 or 19).
                // Words that answer 퍼레's own read-back have heard the half of the day already.
                if (DeviceActions.stated(tool, args, heard) && (DeviceActions.TIMER.equals(name) || answers || !DeviceActions.halfDayUnsaid(args.optInt("hour"), heard))) heard = "네";
            }
            DeviceActions.Action action = DeviceActions.parse(name, args, heard == null ? "" : heard, java.time.ZonedDateTime.now());
            JSONObject r = DeviceActionRunner.run(context, action, overlay);
            if ("needs_confirmation".equals(r.optString("outcome")))
                return new BuddyVoice.Outcome(action.readback + (DeviceActions.ALARM.equals(name) ? "에 알람 맞출까?" : " 타이머 시작할까?"), false, act);
            if (!r.optBoolean("ok")) return fail(r.optString("say", "잘 안 됐어."), "");
            if (DeviceActions.ALARM.equals(name)) return new BuddyVoice.Outcome(action.readback + "에 알람 맞췄어!", false, null);
            if (DeviceActions.TIMER.equals(name)) return new BuddyVoice.Outcome(action.readback + " 타이머 시작!", false, null);
            // 준: "내 휴대폰 가로모드 세로모드 고정 바꾸는게 안돼"
            if (DeviceActions.ROTATION.equals(name)) { String m = r.optString("readback"); return new BuddyVoice.Outcome("auto".equals(m) ? "자동 회전 켰어!" : "landscape".equals(m) ? "가로로 고정했어!" : "세로로 고정했어!", false, null); }
            boolean leaves = DeviceActionRunner.started(r.optString("route")) && (DeviceActions.NAVIGATE.equals(name) || DeviceActions.SETTINGS.equals(name) || DeviceActions.SHOW_ALARMS.equals(name));
            return new BuddyVoice.Outcome("notification".equals(r.optString("outcome")) ? r.optString("say") : "", leaves, null);
        } catch (IllegalArgumentException unclear) { return fail(unclear.getMessage(), ""); }
        catch (Exception failed) { return fail("잘 안 됐어. 한 번만 다시 말해 줘.", ""); }
    }
    /**
     * 길안내 as the edge assistant does it (준: "우리앱에 네비게이션 설정해놓은것을 퍼레가 모르고 있어"): a saved place (내비게이션 →
     * 자주 가는 곳, with its own way of travel and stops) at once; an address by the phone's geocoder; a place by 네이버 지역 검색
     * (the saved key), navigation started on the one it is sure of; the maps app's own search otherwise. The way of travel is the
     * one said, else the app's setting; Naver Map's 안내 시작 is pressed when 폰 조작 allows it. Network: never on the main thread.
     */
    private static BuddyVoice.Outcome navigate(Context context, JSONObject act, boolean overlay) throws Exception {
        AppStore store = ((GyeotApp) context.getApplicationContext()).store();
        String destination = DeviceActions.destination(act), said = act.optString("mode").strip();
        String mode = dev.jun.gyeot.core.NaverPlaces.mode(said.isEmpty() ? store.navigationMode() : said);
        DeviceActions.Action action; dev.jun.gyeot.core.NaverPlaces.Place place = null;
        java.util.List<dev.jun.gyeot.core.SavedPlaces.Saved> all = store.savedPlaces();
        dev.jun.gyeot.core.SavedPlaces.Saved saved = dev.jun.gyeot.core.SavedPlaces.match(destination, all);
        store.phoneLog("  · 퍼레 · 길안내 ‘" + destination + "’ → " + (saved != null ? "자주 가는 곳 " + (saved.name().isEmpty() ? saved.address() : saved.name()) : "자주 가는 곳 아님 (저장 " + all.size() + "곳)"));
        // 집·회사 not saved yet: not a map search for the word 집 (준: "집으로 가자니깐 엉뚱한 걸 여는데?"), but where it is saved.
        if (saved == null && dev.jun.gyeot.core.SavedPlaces.own(destination)) {
            Intent add = new Intent(context, NavigationActivity.class).putExtra(NavigationActivity.ADD, destination.replaceAll("(으로|로|에|까지)$", "")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            DeviceActionRunner.start(context, add, overlay);
            return fail("‘" + destination + "’이 어딘지 아직 몰라. 여기 저장해 주면 다음부턴 바로 안내할게!", "place:" + destination.replaceAll("(으로|로|에|까지)$", ""));
        }
        if (saved != null) {
            place = saved.place();
            action = DeviceActions.navigateTo(place, said.isEmpty() && !saved.mode().isEmpty() ? saved.mode() : mode, saved.via());
        } else if (dev.jun.gyeot.core.NaverPlaces.address(destination)) {
            java.util.List<dev.jun.gyeot.core.NaverPlaces.Place> found = PlaceLocator.geocode(context, destination);
            if (found.isEmpty()) action = DeviceActions.search(destination, true);
            else { place = new dev.jun.gyeot.core.NaverPlaces.Place(destination, found.get(0).address(), found.get(0).lat(), found.get(0).lng()); action = DeviceActions.navigateTo(place, mode); }
        } else {
            java.util.List<dev.jun.gyeot.core.NaverPlaces.Place> found = java.util.List.of();
            NaverPlacesClient.Key key = NaverPlacesClient.key(store);
            if (key != null) try {
                found = NaverPlacesClient.search(key, destination, null);
                for (String shorter : dev.jun.gyeot.core.NaverPlaces.shorter(destination)) { if (!found.isEmpty()) break; found = NaverPlacesClient.search(key, shorter, null); }
            } catch (Exception unreachable) { found = java.util.List.of(); }
            place = dev.jun.gyeot.core.NaverPlaces.sure(destination, found);
            action = place != null ? DeviceActions.navigateTo(place, mode) : DeviceActions.search(destination, true);
        }
        JSONObject r = DeviceActionRunner.run(context, action, overlay);
        if (!r.optBoolean("ok")) return fail(r.optString("say", "지도를 못 열었어."), "");
        if ("navigation".equals(r.optString("outcome")) && "네이버 지도".equals(r.optString("app")) && store.phoneControl() && store.pressNavigationStart() && PhoneService.connected())
            PhoneService.pressWhenShown("com.nhn.android.nmap", java.util.List.of("안내 시작", "안내시작", "길안내 시작", "경로 안내 시작"), 15_000, label -> store.phoneLog("  · 퍼레 · 네이버 지도 ‘" + label + "’ 눌렀어요"));
        if ("notification".equals(r.optString("outcome"))) return new BuddyVoice.Outcome(r.optString("say"), false, null);
        return new BuddyVoice.Outcome("", DeviceActionRunner.started(r.optString("route")), null);
    }
    // ── apps their own way (준: "4번" — 앱이 열어 둔 공식 길로): share, a text, the dialer, the camera, a new event, a page ──
    private static final String[][] SENDERS = {{"com.kakao.talk", "카카오톡", "카톡", "kakaotalk", "kakao"}, {"jp.naver.line.android", "라인", "line"},
        {"org.telegram.messenger", "텔레그램", "telegram"}, {"com.instagram.android", "인스타그램", "인스타", "instagram"}, {"com.facebook.orca", "메신저", "messenger"}};
    /** The package for an app as said (the common messengers by their names, else any app on the phone); null when none. */
    private static String packageFor(Context context, String app) {
        String k = appKey(app).replaceAll("(으로|로|에서|에게|한테)$", "");
        if (k.isEmpty()) return null;
        for (String[] s : SENDERS) for (int i = 1; i < s.length; i++) if (k.equals(s[i]) || k.contains(s[i])) return s[0];
        LauncherActivityInfo info = findApp(context, app); return info == null ? null : info.getComponentName().getPackageName();
    }
    private static Intent task(Intent i) { return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }
    /** Text to someone through an app (KakaoTalk's own share: the user picks the friend), or Android's share sheet. */
    private static BuddyVoice.Outcome share(Context context, JSONObject act, boolean overlay) {
        String text = act.optString("text").strip(), app = act.optString("app").strip();
        if (text.isEmpty()) return fail("보낼 말을 같이 알려 줘.", "");
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        String pkg = app.isEmpty() ? null : packageFor(context, app);
        if (!app.isEmpty() && pkg == null) return fail("'" + app + "' 앱이 이 휴대폰에 없어.", "store:" + app);
        if (pkg != null) { send.setPackage(pkg); if (send.resolveActivity(context.getPackageManager()) == null) return fail("그 앱으로는 보낼 수가 없어.", ""); }
        Intent go = task(pkg != null ? send : Intent.createChooser(send, "보낼 곳"));
        String route = DeviceActionRunner.start(context, go, overlay);
        if (DeviceActionRunner.started(route)) verify(context, pkg, "share " + app, app.isEmpty() ? null : app);
        return started(context, route, "", true);
    }
    /** The number for a name in the user's contacts (with the contacts permission), or null. Off the main thread. */
    static String numberOf(Context context, String who) {
        if (who == null || who.isBlank() || context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null;
        android.net.Uri uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, android.net.Uri.encode(who.strip().replaceAll("(한테|에게|께)$", "")));
        try (android.database.Cursor c = context.getContentResolver().query(uri, new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC")) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException unreadable) { /* none */ }
        return null;
    }
    private static String number(Context context, JSONObject act) {
        String n = act.optString("number").replaceAll("[^0-9+]", "");
        return n.length() >= 3 ? n : numberOf(context, act.optString("who"));
    }
    /** A text message written in the phone's messages app (the user presses send). */
    private static BuddyVoice.Outcome message(Context context, JSONObject act, boolean overlay) {
        String who = act.optString("who").strip(), n = number(context, act), text = act.optString("text").strip();
        Intent sms = task(new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + (n == null ? "" : n))).putExtra("sms_body", text));
        if (sms.resolveActivity(context.getPackageManager()) == null) return fail("문자 앱을 못 찾았어.", "");
        if (n == null && !who.isEmpty()) note("‘" + who + "’ 번호를 몰라서 받는 사람은 비워 뒀어요", context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED ? "" : "contacts");
        BuddyVoice.Outcome o = started(context, DeviceActionRunner.start(context, sms, overlay), "", true);
        return n == null && !who.isEmpty() && o.say().isEmpty() ? new BuddyVoice.Outcome("‘" + who + "’ 번호를 몰라서 받는 사람은 비워 뒀어. 골라 줘!", o.leaves(), null) : o;
    }
    /** The dialer with the number in (퍼레 never calls by itself: the user presses call). */
    private static BuddyVoice.Outcome call(Context context, JSONObject act, boolean overlay) {
        String who = act.optString("who").strip(), n = number(context, act);
        Intent dial = task(new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + (n == null ? "" : android.net.Uri.encode(n)))));
        if (dial.resolveActivity(context.getPackageManager()) == null) return fail("전화 앱을 못 찾았어.", "");
        boolean allowed = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (n == null && !who.isEmpty()) note("‘" + who + "’ 번호를 몰라서 전화 앱만 열었어요", allowed ? "" : "contacts");
        BuddyVoice.Outcome o = started(context, DeviceActionRunner.start(context, dial, overlay), "", true);
        if (!o.say().isEmpty()) return o;
        return new BuddyVoice.Outcome(n != null ? (who.isEmpty() ? "" : who + " 번호 눌러 놨어, 통화만 눌러 줘!") : who.isEmpty() ? "" : "‘" + who + "’ 번호를 몰라서 전화 앱만 열었어." + (allowed ? "" : " 연락처를 허용해 주면 바로 찾아 줄게."), o.leaves(), null);
    }
    /** The camera: a photo, a selfie (front camera) or a video. */
    private static BuddyVoice.Outcome camera(Context context, JSONObject act, boolean overlay) {
        String mode = act.optString("mode", "photo");
        Intent cam = task(new Intent("video".equals(mode) ? android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA : android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        if ("selfie".equals(mode)) cam.putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extras.LENS_FACING_FRONT", 1).putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
        if (cam.resolveActivity(context.getPackageManager()) == null) return fail("카메라 앱을 못 찾았어.", "");
        return started(context, DeviceActionRunner.start(context, cam, overlay), "", true);
    }
    /** A new event in the calendar app, filled in (the user saves it): its name, day, time, length and place. */
    private static BuddyVoice.Outcome calendarAdd(Context context, JSONObject act, boolean overlay) {
        String title = act.optString("title").strip();
        if (title.isEmpty()) return fail("일정 이름을 알려 줘.", "");
        java.time.ZoneId zone = java.time.ZoneId.systemDefault(); java.time.LocalDate today = java.time.LocalDate.now(zone);
        String d = act.optString("date").strip().toLowerCase(Locale.ROOT);
        java.time.LocalDate day = d.isEmpty() || d.equals("today") || d.equals("오늘") ? today : d.equals("tomorrow") || d.equals("내일") ? today.plusDays(1) : d.equals("모레") ? today.plusDays(2) : null;
        if (day == null) try { day = java.time.LocalDate.parse(d); } catch (RuntimeException unclear) { day = BuddyTricks.day(d, today); if (day == null) day = today; }
        // A year the brain guessed wrong (2024-11-27 said "tomorrow" in 2026): the same day this year, or next year once past.
        if (day.isBefore(today)) { day = day.withYear(today.getYear()); if (day.isBefore(today)) day = day.plusYears(1); }
        boolean timed = act.has("hour");
        java.time.ZonedDateTime begin = timed ? java.time.ZonedDateTime.of(day, java.time.LocalTime.of(Math.max(0, Math.min(23, act.optInt("hour"))), Math.max(0, Math.min(59, act.optInt("minute")))), zone) : day.atStartOfDay(zone);
        if (timed && d.isEmpty() && begin.isBefore(java.time.ZonedDateTime.now(zone))) begin = begin.plusDays(1);
        long start = begin.toInstant().toEpochMilli(), end = timed ? start + Math.max(10, act.optInt("minutes", 60)) * 60_000L : begin.plusDays(1).toInstant().toEpochMilli();
        Intent add = task(new Intent(Intent.ACTION_INSERT).setDataAndType(android.provider.CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.dir/event")
            .putExtra(android.provider.CalendarContract.Events.TITLE, title).putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end).putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, !timed));
        if (!act.optString("place").isBlank()) add.putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, act.optString("place").strip());
        if (add.resolveActivity(context.getPackageManager()) == null) return fail("캘린더 앱을 못 찾았어.", "");
        BuddyVoice.Outcome o = started(context, DeviceActionRunner.start(context, add, overlay), "", true);
        return o.say().isEmpty() ? new BuddyVoice.Outcome("‘" + title + "’ 일정 채워 놨어, 저장만 눌러 줘!", o.leaves(), null) : o;
    }
    /** A web page (https), as said ("네이버", "naver.com" …). */
    private static BuddyVoice.Outcome openUrl(Context context, JSONObject act, boolean overlay) {
        String url = act.optString("url").strip();
        if (url.matches("(?i)^[a-z0-9.-]+\\.[a-z]{2,}(/.*)?$")) url = "https://" + url;
        if (!url.matches("(?i)^https://\\S+$")) return fail("그 주소는 열 수가 없어.", "");
        Intent view = task(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        if (view.resolveActivity(context.getPackageManager()) == null) return fail("브라우저를 못 찾았어.", "");
        return started(context, DeviceActionRunner.start(context, view, overlay), "", true);
    }

    // ── more of the apps' own ways (2026-11-26): the clock's timers, a day in the calendar, a memo, a recorder, an app's info,
    //    the downloads, the gallery, a person's card, a sum, a mail, a translator; each tells what is missing and how to get it ──
    private static BuddyVoice.Outcome said(String words) { return new BuddyVoice.Outcome(words == null ? "" : words, false, null); }
    /** The first of these some app here handles, started (or what kept it from opening); null when none is handled. */
    private static BuddyVoice.Outcome first(Context context, boolean overlay, Intent... tries) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (Intent t : tries) {
            if (t == null) continue;
            task(t);
            if (t.resolveActivity(pm) == null) continue;
            return started(context, DeviceActionRunner.start(context, t, overlay), "", true);
        }
        return null;
    }
    /** The app with one of these names (as open_app finds it), started; null when none is here. */
    private static BuddyVoice.Outcome byName(Context context, boolean overlay, String... names) {
        for (String n : names) { LauncherActivityInfo info = findApp(context, n); if (info != null) return started(context, open(context, info, overlay), "", true); }
        return null;
    }
    /** The first of these packages that is here, by its launcher, started; null when none is. */
    private static BuddyVoice.Outcome byPackage(Context context, boolean overlay, String... packages) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (String p : packages) { Intent launch = pm.getLaunchIntentForPackage(p); if (launch != null) return started(context, DeviceActionRunner.start(context, task(launch), overlay), "", true); }
        return null;
    }
    private static boolean contactsAllowed(Context context) { return context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED; }
    private static String person(String who) { return who == null ? "" : who.strip().replaceAll("(한테|에게|께|의)$", ""); }
    /** The clock app's timers (its alarms, where the timer is a tab away, when it shows no timers screen). */
    private static BuddyVoice.Outcome timers(Context context, boolean overlay) {
        BuddyVoice.Outcome o = first(context, overlay, new Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS), new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS));
        return o != null ? o : fail("타이머를 보여 줄 시계 앱을 못 찾았어.", "store:시계");
    }
    /** The calendar app on a day (CalendarContract's time link: it reads nothing itself, so no permission). */
    private static BuddyVoice.Outcome calendarView(Context context, JSONObject act, boolean overlay) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault(); java.time.LocalDate today = java.time.LocalDate.now(zone);
        java.time.LocalDate day = BuddyTricks.day(act.optString("date"), today); if (day == null) day = today;
        android.net.Uri.Builder time = android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time");
        android.content.ContentUris.appendId(time, day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli());
        BuddyVoice.Outcome o = first(context, overlay, new Intent(Intent.ACTION_VIEW).setData(time.build()), Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR));
        if (o == null) o = byName(context, overlay, "캘린더", "Calendar");
        if (o == null) return fail("일정을 보여 줄 캘린더 앱을 못 찾았어.", "store:캘린더");
        return o.say().isEmpty() && !day.equals(today) ? new BuddyVoice.Outcome(BuddyTricks.dateWords(day, today) + " 일정 열었어!", o.leaves(), null) : o;
    }
    /** Notes apps that take a new note as shared text, the phone's own first. */
    private static final String[] NOTES = {"com.samsung.android.app.notes", "com.google.android.keep", "com.microsoft.office.onenote", "com.socialnmobile.dictapp.notepad.color.note", "com.evernote", "notion.id"};
    /** A new note in a notes app (shared text: the user keeps it there), or the notes app itself when there is nothing to write. */
    private static BuddyVoice.Outcome memo(Context context, JSONObject act, boolean overlay) {
        String text = act.optString("text").strip(), title = act.optString("title").strip(), app = act.optString("app").strip();
        String named = app.isEmpty() ? null : packageFor(context, app);
        if (!app.isEmpty() && named == null) return fail("'" + app + "' 앱이 이 휴대폰에 없어.", "store:" + app);
        String[] apps = named != null ? new String[]{named} : NOTES;
        if (text.isEmpty()) {
            BuddyVoice.Outcome o = byPackage(context, overlay, apps);
            if (o == null && named == null) o = byName(context, overlay, "메모", "노트", "Keep");
            return o != null ? o : fail("메모 앱을 못 찾았어.", "store:메모");
        }
        for (String pkg : apps) {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg);
            if (!title.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TITLE, title);
            BuddyVoice.Outcome o = first(context, overlay, send);
            if (o != null) { if (o.say().isEmpty()) verify(context, pkg, "memo", null); return o; }
        }
        return named != null ? fail("그 앱에는 메모를 넣을 수가 없어.", "")
            : fail("메모 앱을 못 찾아서 못 적었어. 나한테 ‘기억해 줘’라고 하면 내가 대신 기억해 둘게.", "store:메모");
    }
    /** The voice recorder: the phone's own recorder app, else any app that records a sound. */
    private static BuddyVoice.Outcome recorder(Context context, boolean overlay) {
        BuddyVoice.Outcome o = byPackage(context, overlay, "com.sec.android.app.voicenote", "com.google.android.apps.recorder");
        if (o == null) o = byName(context, overlay, "음성 녹음", "녹음", "Recorder");
        if (o == null) o = first(context, overlay, new Intent(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION));
        return o != null ? o : fail("녹음 앱을 못 찾았어.", "store:음성 녹음");
    }
    /** An app's info screen in Settings (its notifications, permissions, storage): the user changes them there. */
    private static BuddyVoice.Outcome appSettings(Context context, JSONObject act, boolean overlay) {
        String app = act.optString("app").strip();
        if (app.isEmpty()) return fail("어느 앱 설정인지 알려 줘.", "");
        String pkg = packageFor(context, app);
        if (pkg == null) return fail("'" + app + "' 앱은 이 휴대폰에서 못 찾았어.", "store:" + app);
        BuddyVoice.Outcome o = first(context, overlay, new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg)));
        return o != null ? o : fail("앱 정보 화면을 못 열었어.", "");
    }
    /** The downloaded files (the system's downloads list, else the files app). */
    private static BuddyVoice.Outcome downloads(Context context, boolean overlay) {
        BuddyVoice.Outcome o = first(context, overlay, new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS), Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES));
        if (o == null) o = byName(context, overlay, "내 파일", "Files", "파일");
        return o != null ? o : fail("받은 파일을 보여 줄 파일 앱을 못 찾았어.", "store:파일 관리자");
    }
    /** The phone's gallery (the app that says it is one, else one by its name). */
    private static BuddyVoice.Outcome gallery(Context context, boolean overlay) {
        BuddyVoice.Outcome o = first(context, overlay, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY));
        if (o == null) o = byName(context, overlay, "갤러리", "Google 포토", "포토", "Photos", "Gallery");
        return o != null ? o : fail("사진을 보여 줄 갤러리 앱을 못 찾았어.", "store:갤러리");
    }
    /** The card of someone in the user's contacts (with the contacts permission), or null. Off the main thread. */
    static android.net.Uri contactOf(Context context, String who) {
        if (who == null || who.isBlank() || !contactsAllowed(context)) return null;
        android.net.Uri uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_FILTER_URI, android.net.Uri.encode(person(who)));
        try (android.database.Cursor c = context.getContentResolver().query(uri, new String[]{android.provider.ContactsContract.Contacts._ID, android.provider.ContactsContract.Contacts.LOOKUP_KEY}, null, null, null)) {
            if (c != null && c.moveToFirst()) return android.provider.ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1));
        } catch (RuntimeException unreadable) { /* none */ }
        return null;
    }
    /** The contacts app, on the person said when they are in it. */
    private static BuddyVoice.Outcome contacts(Context context, JSONObject act, boolean overlay) {
        String who = act.optString("who").strip();
        android.net.Uri card = contactOf(context, who);
        if (card != null) { BuddyVoice.Outcome o = first(context, overlay, new Intent(Intent.ACTION_VIEW, card)); if (o != null) return o; }
        BuddyVoice.Outcome o = first(context, overlay, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS), new Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI));
        if (o == null) o = byName(context, overlay, "연락처", "Contacts");
        if (o == null) return fail("연락처 앱을 못 찾았어.", "");
        if (who.isEmpty() || !o.say().isEmpty()) return o;
        boolean allowed = contactsAllowed(context);
        note("‘" + who + "’ 연락처를 못 찾아서 연락처 앱만 열었어요", allowed ? "" : "contacts");
        return new BuddyVoice.Outcome("‘" + who + "’ 연락처를 못 찾아서 연락처 앱만 열었어." + (allowed ? "" : " 연락처를 허용해 주면 바로 찾아 줄게."), o.leaves(), null);
    }
    /** A sum worked out and said (BuddyTricks.calc); with none, or one it cannot read, the calculator app. */
    private static BuddyVoice.Outcome calculator(Context context, JSONObject act, boolean overlay) {
        String expr = act.optString("expression").strip();
        if (!expr.isEmpty()) {
            try { Double v = BuddyTricks.calc(expr); if (v != null) return said(BuddyTricks.calcSay(v, java.util.concurrent.ThreadLocalRandom.current())); }
            catch (ArithmeticException byZero) { return said("0으로는 나눌 수가 없어!"); }
        }
        BuddyVoice.Outcome o = first(context, overlay, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR));
        if (o == null) o = byName(context, overlay, "계산기", "Calculator");
        if (o == null) return fail(expr.isEmpty() ? "계산기 앱을 못 찾았어." : "그 식은 내가 못 풀었고 계산기 앱도 못 찾았어. 숫자랑 더하기·곱하기로 다시 말해 줘!", "store:계산기");
        return expr.isEmpty() || !o.say().isEmpty() ? o : new BuddyVoice.Outcome("그 식은 내가 못 풀어서 계산기를 열었어!", o.leaves(), null);
    }
    /** Someone's mail address in the user's contacts (with the contacts permission), or null. Off the main thread. */
    static String emailOf(Context context, String who) {
        if (who == null || who.isBlank() || !contactsAllowed(context)) return null;
        android.net.Uri uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, android.net.Uri.encode(person(who)));
        try (android.database.Cursor c = context.getContentResolver().query(uri, new String[]{android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException unreadable) { /* none */ }
        return null;
    }
    /** A new mail written in the mail app (the user presses send): to, subject, words. */
    private static BuddyVoice.Outcome email(Context context, JSONObject act, boolean overlay) {
        String who = act.optString("who").strip(), to = act.optString("to").strip(), subject = act.optString("subject").strip(), text = act.optString("text").strip();
        if (!to.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) to = "";
        if (to.isEmpty() && !who.isEmpty()) { String found = emailOf(context, who); if (found != null) to = found; }
        Intent mail = task(new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")));
        if (!to.isEmpty()) mail.putExtra(Intent.EXTRA_EMAIL, new String[]{to});
        if (!subject.isEmpty()) mail.putExtra(Intent.EXTRA_SUBJECT, subject);
        if (!text.isEmpty()) mail.putExtra(Intent.EXTRA_TEXT, text);
        if (mail.resolveActivity(context.getPackageManager()) == null) return fail("메일 앱을 못 찾았어.", "store:Gmail");
        boolean unknown = to.isEmpty() && !who.isEmpty();
        if (unknown) note("‘" + who + "’ 메일 주소를 몰라서 받는 사람은 비워 뒀어요", contactsAllowed(context) ? "" : "contacts");
        BuddyVoice.Outcome o = started(context, DeviceActionRunner.start(context, mail, overlay), "", true);
        if (!o.say().isEmpty()) return o;
        return new BuddyVoice.Outcome(unknown ? "‘" + who + "’ 메일 주소를 몰라서 받는 사람은 비워 뒀어. 골라 줘!" : "메일 써 놨어, 보내기만 눌러 줘!", o.leaves(), null);
    }
    private static final String[] TRANSLATORS = {"com.naver.labs.translator", "com.google.android.apps.translate"};
    /**
     * Words translated in a translator: Papago's or Google Translate's own text action (it shows the translation over the
     * screen), else the text shared to it, else Google Translate on the web (first, when a language other than the usual
     * Korean↔English is asked: the apps pick theirs themselves).
     */
    private static BuddyVoice.Outcome translate(Context context, JSONObject act, boolean overlay) {
        String text = act.optString("text").strip();
        if (text.isEmpty()) return fail("번역할 말을 같이 알려 줘.", "");
        if (text.length() > 1000) text = text.substring(0, 1000);
        String to = BuddyTricks.language(act.optString("to"), text);
        boolean usual = to.equals(text.codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3) ? "en" : "ko");
        Intent web = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://translate.google.com/?sl=auto&tl=" + android.net.Uri.encode(to) + "&text=" + android.net.Uri.encode(text) + "&op=translate"));
        java.util.List<Intent> tries = new java.util.ArrayList<>();
        if (!usual) tries.add(web);
        for (String pkg : TRANSLATORS) tries.add(new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true).setPackage(pkg));
        for (String pkg : TRANSLATORS) tries.add(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg));
        if (usual) tries.add(web);
        BuddyVoice.Outcome o = first(context, overlay, tries.toArray(new Intent[0]));
        return o != null ? o : fail("번역할 앱도 브라우저도 못 찾았어.", "store:파파고");
    }

    // ── 퍼레's own, with no app ──
    /** 퍼레's stopwatch (no clock app has an official one): started, read, stopped; kept across restarts. */
    private static BuddyVoice.Outcome stopwatch(Context context, JSONObject act) {
        android.content.SharedPreferences p = context.getSharedPreferences("buddyTalk", Context.MODE_PRIVATE);
        long since = p.getLong("stopwatch", 0), now = System.currentTimeMillis();
        boolean running = since > 0 && since <= now;
        String none = "지금 재고 있는 건 없어. 시작하자고 하면 바로 잴게!";
        switch (act.optString("command", "start").strip().toLowerCase(Locale.ROOT)) {
            case "check", "lap", "read", "status" -> { return said(running ? "지금 " + BuddyTricks.elapsed(now - since) + "째야!" : none); }
            case "stop", "end", "reset" -> {
                if (!running) return said(none);
                p.edit().remove("stopwatch").apply();
                return said("멈췄어, " + BuddyTricks.elapsed(now - since) + "!");
            }
            default -> {
                p.edit().putLong("stopwatch", now).apply();
                return said(running ? "아까 건 " + dev.jun.gyeot.core.Brand.josa(BuddyTricks.elapsed(now - since), "이었어", "였어") + ". 처음부터 다시 잴게!" : "");
            }
        }
    }
    /** One of the choices, at random. */
    private static BuddyVoice.Outcome pick(JSONObject act) {
        java.util.List<String> options = BuddyTricks.options(act.opt("options"));
        if (options.isEmpty()) return said("뭐 중에서 고를지 알려 줘!");
        if (options.size() == 1) return said("‘" + options.get(0) + "’ 하나뿐이네, 그걸로 가자!");
        java.util.Random r = java.util.concurrent.ThreadLocalRandom.current();
        return said(BuddyTricks.pickSay(options.get(r.nextInt(options.size())), r));
    }
    /** The time in a city (BuddyTricks: its time zone, the gap from here). */
    private static BuddyVoice.Outcome worldTime(JSONObject act) {
        String place = act.optString("place").strip();
        String[] z = BuddyTricks.place(place, act.optString("zone"));
        if (z == null) return fail("‘" + place + "’" + dev.jun.gyeot.core.Brand.josa(place, "이", "가").substring(place.length()) + " 어딘지 몰라서 시각을 못 셌어. 도시 이름으로 말해 줘!", "");
        return said(BuddyTricks.worldTime(z[1], java.time.ZoneId.of(z[0]), java.time.ZonedDateTime.now()));
    }
    /** Days to a day or since it, or the day so many days on (BuddyTricks). */
    private static BuddyVoice.Outcome countDays(JSONObject act) {
        java.time.LocalDate today = java.time.LocalDate.now();
        String date = act.optString("date").strip(), label = act.optString("label").strip();
        java.time.LocalDate day = BuddyTricks.day(date, today);
        if (day == null) return fail("날짜를 못 알아들었어. 12월 25일처럼 말해 줘!", "");
        if (act.has("add_days")) return said(BuddyTricks.addDays(day, act.optLong("add_days"), today));
        // A day by its name (크리스마스) is its own label.
        if (label.isEmpty() && !date.matches(".*\\d.*") && !date.matches("(?i)오늘|내일|모레|글피|어제|그제|그저께|today|tomorrow|yesterday")) label = date;
        return said(BuddyTricks.countDays(day, label, today));
    }
    /**
     * What the user asks to be remembered ("기억해 둬"), as a memory of theirs that matters most (weight 3), private when they say
     * so or it is; never a password or account number. "나 고양이 키워" is kept as "사용자는 고양이 키워"; one it knew already is told
     * once more (seen), and one it says the opposite of is set right (BuddyMind.negates). A subject let go before is learned
     * again from then on.
     */
    private static final java.util.regex.Pattern SECRET_VALUE = java.util.regex.Pattern.compile("비밀 ?번호|비번|패스워드|password|계좌|카드 ?번호|주민 ?(등록)? ?번호|인증 ?번호|otp|pin ?번호|보안 ?코드|cvc", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern ME = java.util.regex.Pattern.compile("^(?:나는|난|나|저는|전|저|내가|제가)\\s+");
    /** The words to keep, as the user's memory: "사용자는 …" ("나 고양이 키워" → "사용자는 고양이 키워"); ones with their own subject ("차는 …") as asked. */
    static String rememberText(String fact) {
        String f = fact == null ? "" : fact.strip().replaceAll("\\s+", " ").replaceAll("[\"“”]", "");
        if (f.isEmpty() || f.startsWith("사용자")) return f;
        java.util.regex.Matcher me = ME.matcher(f);
        if (me.find()) { String rest = f.substring(me.end()).strip(); return rest.isEmpty() ? "" : (me.group().startsWith("내가") || me.group().startsWith("제가") ? "사용자가 " : "사용자는 ") + rest; }
        // Its own subject first ("차는 지하 2층에", "열쇠는 서랍에"): what they asked to be kept, as asked.
        if (f.split(" ", 2)[0].matches(".+(은|는)")) return "사용자가 기억해 달랬어: " + f;
        return "사용자는 " + f;
    }
    private static BuddyVoice.Outcome remember(Context context, JSONObject act) {
        String fact = act.optString("fact").strip();
        if (fact.isEmpty()) return fail("뭘 기억할지 알려 줘.", "");
        if (SECRET_VALUE.matcher(fact).find()) return said("비밀번호나 계좌 같은 번호는 내 기억에 안 적어 둬.");
        String text = rememberText(fact);
        if (text.length() > 90) text = text.substring(0, 90);
        boolean secret = act.optBoolean("private", act.optBoolean("secret")) || dev.jun.gyeot.core.BuddyMind.secret(text);
        BuddyTalk.unforgot(context, text);
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> all = BuddyTalk.memories(context);
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) if (dev.jun.gyeot.core.BuddyMind.similarity(e.text(), text) >= 0.6 && !dev.jun.gyeot.core.BuddyMind.negates(e.text(), text)) {
            BuddyTalk.confirm(context, e.at());
            return said("그건 벌써 기억하고 있어!");
        }
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : all) if (!e.secret() || BuddyTalk.unlocked()) if (("fact".equals(e.kind()) || "profile".equals(e.kind())) && dev.jun.gyeot.core.BuddyMind.negates(e.text(), text)) {
            BuddyTalk.replace(context, e.at(), text, secret || e.secret(), 3);
            return said("");
        }
        BuddyTalk.rememberStrong(context, "fact", text, secret, 3);
        return said("");
    }
    /** What the last "잊어 줘" let go, kept two minutes for "되돌려 줘" (in memory only, never on disk). */
    private static volatile java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> letGo = java.util.List.of();
    private static volatile long letGoAt;
    static final long UNDO_MS = 2 * 60_000L;
    /**
     * The memories a "잊어 줘" or "그건 틀렸어 지워" names, deleted (at most five; more, or all of them, in 퍼레의 기억 behind the
     * fingerprint), with the night's notes on the same thing (BuddyMind.toForget), and what went said back; the subject is not
     * learned again by itself for ninety days. A private one waits for the fingerprint. "되돌려 줘" (undo) within two minutes
     * brings them back.
     */
    private static BuddyVoice.Outcome forget(Context context, JSONObject act) {
        if (act.optBoolean("undo")) return unforget(context);
        String what = act.optString("what").strip();
        if (what.isEmpty()) return fail("뭘 잊을지 알려 줘.", "");
        if (what.replaceAll("\\s+", "").matches("(내)?(기억|것|거)?(을|를)?(다|전부|모두|싹|싹다|모든것|모든기억|전부다)(기억)?"))
            return fail("기억을 한꺼번에 지우는 건 ‘퍼레의 기억’에서 직접 해 줘. 지문으로 열 수 있어.", "memory");
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> gone = dev.jun.gyeot.core.BuddyMind.toForget(BuddyTalk.memories(context), what);
        if (gone.isEmpty()) return said("‘" + what + "’ 기억은 원래 없었어.");
        if (gone.size() > 5) return fail("‘" + what + "’ 기억이 " + gone.size() + "개나 돼서 그대로 뒀어. ‘퍼레의 기억’에서 골라 지워 줘.", "memory");
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : gone) if (e.secret() && !BuddyTalk.unlocked()) return fail("지문을 확인해야 지울 수 있어.", "memory");
        BuddyTalk.forget(context, gone);
        BuddyTalk.forgot(context, dev.jun.gyeot.core.BuddyMind.forgetting(what, gone));
        letGo = java.util.List.copyOf(gone); letGoAt = System.currentTimeMillis();
        String first = dev.jun.gyeot.core.BuddyMind.toldBack(gone.get(0).text());
        return said(gone.size() == 1 ? "‘" + first + "’ 잊었어." : "‘" + first + "’ 하고 " + (gone.size() - 1) + "개 더 잊었어.");
    }
    /** What the last "잊어 줘" let go, back (within two minutes), its subjects learned again. */
    private static BuddyVoice.Outcome unforget(Context context) {
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> back = letGo;
        if (back.isEmpty() || System.currentTimeMillis() - letGoAt > UNDO_MS) return said("방금 잊은 게 없어서 되돌릴 게 없어.");
        letGo = java.util.List.of();
        BuddyTalk.restore(context, back);
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : back) BuddyTalk.unforgot(context, e.text());
        return said("다시 기억했어!");
    }
    /** A quiet while (0–12 hours, one when not said; 0 talks again): the home screen's 퍼레 starts no talk, and answers when called. */
    private static BuddyVoice.Outcome quiet(Context context, JSONObject act) {
        double hours = act.has("hours") ? act.optDouble("hours", 1) : act.has("minutes") ? act.optDouble("minutes", 60) / 60 : 1;
        if (Double.isNaN(hours) || Double.isInfinite(hours)) hours = 1;
        hours = Math.max(0, Math.min(12, hours));
        if (hours <= 0) { BuddyTalk.hush(context, 0); return said(""); }
        long ms = Math.round(hours * 3_600_000);
        BuddyTalk.hush(context, ms);
        return said(BuddyTricks.clock(java.time.ZonedDateTime.now().plusSeconds(ms / 1000)) + "까지는 먼저 말 안 걸게. 부르면 바로 대답할게!");
    }
    /** The apps a song or a video is found and played in, by the words for them. */
    private static final String[][] PLAYERS = {
        {"com.google.android.apps.youtube.music", "유튜브뮤직", "youtubemusic", "ytmusic", "유튜브음악"},
        {"com.google.android.youtube", "유튜브", "youtube"},
        {"com.iloen.melon", "멜론", "melon"},
        {"com.spotify.music", "스포티파이", "spotify"},
        {"com.ktmusic.geniemusic", "지니", "지니뮤직", "genie"},
        {"com.sec.android.app.music", "삼성뮤직", "samsungmusic"}};
    /**
     * 유튜브·음악 앱에서 찾아 틀기 (준: "유튜브 열고 최신곡 틀게!"라고만 하고 안 틀었다): the app said (YouTube when none), asked to
     * play what was asked (MEDIA_PLAY_FROM_SEARCH, what the phone's assistant uses), else its search for it; YouTube itself
     * shows its results for it.
     */
    private static BuddyVoice.Outcome play(Context context, JSONObject act, boolean overlay) {
        String q = act.optString("query").strip(), named = appKey(act.optString("app"));
        android.content.pm.PackageManager pm = context.getPackageManager();
        String pkg = null;
        // The name as said first (유튜브뮤직 is not 유튜브), then one inside the words (유튜브에서).
        for (String[] p : PLAYERS) for (int i = 1; i < p.length && pkg == null; i++) if (named.equals(p[i])) pkg = p[0];
        for (String[] p : PLAYERS) for (int i = 1; i < p.length && pkg == null; i++) if (!named.isEmpty() && named.contains(p[i])) pkg = p[0];
        if (pkg == null && !named.isEmpty()) { LauncherActivityInfo info = findApp(context, act.optString("app")); if (info != null) pkg = info.getComponentName().getPackageName(); }
        if (pkg == null) pkg = "com.google.android.youtube";
        if (q.isEmpty()) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) return fail("그 앱이 이 휴대폰에 없어.", "store:" + act.optString("app"));
            return started(context, DeviceActionRunner.start(context, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), overlay), "", true);
        }
        java.util.List<Intent> tries = new java.util.ArrayList<>();
        if (!pkg.equals("com.google.android.youtube"))
            tries.add(new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).putExtra(android.app.SearchManager.QUERY, q).putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"));
        tries.add(new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(android.app.SearchManager.QUERY, q).putExtra("query", q));
        if (pkg.equals("com.google.android.youtube"))
            tries.add(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(q))).setPackage(pkg));
        for (Intent t : tries) {
            t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (t.resolveActivity(pm) == null) continue;
            String route = DeviceActionRunner.start(context, t, overlay);
            ((GyeotApp) context.getApplicationContext()).store().phoneLog("  · 퍼레 · " + pkg + " · ‘" + q + "’ " + (t.getAction() != null && t.getAction().contains("PLAY_FROM_SEARCH") ? "찾아 틀기" : "검색") + " · " + route);
            if (DeviceActionRunner.started(route)) {
                verify(context, pkg, "play " + q, null);
                if (t.getAction() != null && t.getAction().contains("PLAY_FROM_SEARCH")) playing(context, pkg, q);
            }
            return started(context, route, "", true);
        }
        return fail("거기서 찾아 틀 수가 없어. 그 앱이 깔려 있는지 봐 줘.", act.optString("app").isBlank() ? "" : "store:" + act.optString("app"));
    }
    /** Checks made a few seconds after an act (off the main thread). */
    private static final java.util.concurrent.ScheduledExecutorService LATER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "gyeot-buddy-acts-later"); t.setDaemon(true); return t; });
    /** How long a music app is given to start playing what it was asked to find. */
    static final long PLAYING_MS = 6000;
    /**
     * A music app asked to find and play (Melon, Spotify …): whether music plays PLAYING_MS later (its media session); when
     * it does not, it is kept in 퍼레가 못 한 일 and the log, and nothing is said (the app's own screen shows what it found).
     */
    private static void playing(Context context, String pkg, String q) {
        String heard = HEARD.get();
        try {
            LATER.schedule(() -> {
                if (!Boolean.FALSE.equals(BuddyTalk.musicPlaying(context))) return;
                BuddyMisses.add(context, heard, "play " + q + " · " + pkg, "찾아 틀라고 했는데 음악이 안 나와요", "", BuddyMisses.NOT_PLAYING);
                ((GyeotApp) context.getApplicationContext()).store().phoneLog("  · 퍼레 · " + pkg + " · ‘" + q + "’ 틀라고 했는데 안 나와요");
            }, PLAYING_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException stopped) { /* not checked */ }
    }
    /** The media volume: up or down a few steps, a level (0–100), muted or back (the phone shows its volume bar). */
    private static BuddyVoice.Outcome volume(Context context, JSONObject act) {
        android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
        if (audio == null) return fail("소리를 못 바꿨어.", "");
        int stream = android.media.AudioManager.STREAM_MUSIC, flags = android.media.AudioManager.FLAG_SHOW_UI, max = audio.getStreamMaxVolume(stream);
        String direction = act.optString("direction").strip().toLowerCase(Locale.ROOT);
        if (act.has("level")) {
            int level = Math.max(0, Math.min(100, act.optInt("level")));
            audio.setStreamVolume(stream, Math.round(max * level / 100f), flags);
            return new BuddyVoice.Outcome("소리 " + level + "으로 맞췄어!", false, null);
        }
        switch (direction) {
            case "mute" -> { audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, flags); return new BuddyVoice.Outcome("소리 껐어.", false, null); }
            case "unmute" -> { audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, flags); return new BuddyVoice.Outcome("소리 다시 켰어!", false, null); }
            default -> {
                boolean up = !"down".equals(direction); int steps = Math.max(1, Math.min(max, act.optInt("steps", 2)));
                for (int i = 0; i < steps; i++) audio.adjustStreamVolume(stream, up ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, i == steps - 1 ? flags : 0);
                return new BuddyVoice.Outcome(up ? "소리 키웠어!" : "소리 줄였어.", false, null);
            }
        }
    }
    /** The screen's brightness: up or down a fifth, or a level (0–100); auto brightness off. Needs 시스템 설정 변경 (as rotation). */
    private static BuddyVoice.Outcome brightness(Context context, JSONObject act, boolean overlay) {
        if (!android.provider.Settings.System.canWrite(context)) {
            Intent allow = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { DeviceActionRunner.start(context, allow, overlay); } catch (RuntimeException ignored) { /* Said below. */ }
            return fail("밝기를 바꾸려면 '시스템 설정 변경'을 허용해 줘. 허용 화면을 열었어.", "writeSettings");
        }
        android.content.ContentResolver settings = context.getContentResolver();
        try {
            int now = android.provider.Settings.System.getInt(settings, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
            String direction = act.optString("direction").strip().toLowerCase(Locale.ROOT);
            int to = act.has("level") ? Math.round(255 * Math.max(0, Math.min(100, act.optInt("level"))) / 100f) : "down".equals(direction) ? now - 51 : now + 51;
            to = Math.max(5, Math.min(255, to));
            android.provider.Settings.System.putInt(settings, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            android.provider.Settings.System.putInt(settings, android.provider.Settings.System.SCREEN_BRIGHTNESS, to);
            return new BuddyVoice.Outcome(act.has("level") ? "밝기 " + Math.max(0, Math.min(100, act.optInt("level"))) + "으로 맞췄어!" : to > now ? "화면 밝게 했어!" : "화면 어둡게 했어.", false, null);
        } catch (RuntimeException refused) { return fail("밝기를 못 바꿨어.", ""); }
    }
    /** After a start: nothing to add when it opened, the way to open it when Android only let a notification through. */
    private static BuddyVoice.Outcome started(Context context, String route, String opened, boolean leaves) {
        if (DeviceActionRunner.started(route)) return new BuddyVoice.Outcome("", leaves, null);
        return "locked".equals(route) ? fail("화면이 잠겨 있어서 못 열었어. 잠금을 풀고 다시 말해 줘.", "") : fail("지금은 바로 열 수가 없어. 다른 앱 위에 표시 권한을 켜 주면 바로 열 수 있어.", "overlay");
    }
    /** The app's own launcher intent, started the way Android allows now; a work-profile app through LauncherApps. */
    private static String open(Context context, LauncherActivityInfo info, boolean overlay) {
        if (!android.os.Process.myUserHandle().equals(info.getUser())) {
            try { context.getSystemService(LauncherApps.class).startMainActivity(info.getComponentName(), info.getUser(), null, null); return "app"; }
            catch (RuntimeException refused) { return "notification"; }
        }
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(info.getComponentName())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return DeviceActionRunner.start(context, main, overlay);
    }

    /** Names people say for apps whose label is in another script or a nickname. */
    private static final Map<String, String> APP_ALIASES = Map.ofEntries(Map.entry("유튜브", "youtube"), Map.entry("크롬", "chrome"), Map.entry("인스타", "instagram"),
        Map.entry("인스타그램", "instagram"), Map.entry("넷플릭스", "netflix"), Map.entry("디스코드", "discord"), Map.entry("카톡", "카카오톡"), Map.entry("지메일", "gmail"),
        Map.entry("구글지도", "지도"), Map.entry("구글맵", "지도"), Map.entry("트위터", "x"), Map.entry("페이스북", "facebook"), Map.entry("스포티파이", "spotify"),
        Map.entry("테슬라", "tesla"), Map.entry("바이낸스", "binance"), Map.entry("챗지피티", "chatgpt"), Map.entry("클로드", "claude"), Map.entry("제미나이", "gemini"));
    /** The launchable app a spoken name means, among every profile's apps. */
    static LauncherActivityInfo findApp(Context context, String spoken) {
        LauncherApps launcher = context.getSystemService(LauncherApps.class); if (launcher == null) return null;
        Map<String, String[]> apps = new HashMap<>(); Map<String, LauncherActivityInfo> infos = new HashMap<>();
        try {
            for (UserHandle u : launcher.getProfiles()) for (LauncherActivityInfo info : launcher.getActivityList(null, u)) {
                String id = info.getComponentName().flattenToShortString() + "#" + u.hashCode();
                apps.put(id, new String[]{String.valueOf(info.getLabel()), info.getApplicationInfo().packageName}); infos.put(id, info);
            }
        } catch (RuntimeException unreadable) { return null; }
        String id = bestApp(apps, spoken);
        return id == null ? null : infos.get(id);
    }
    /** Of apps (id → {label, package}), the one a spoken name means: the same label, else one starting with it, else one containing it; shorter first. */
    static String bestApp(Map<String, String[]> apps, String spoken) {
        String want = appKey(spoken).replaceAll("앱$|어플$", "");
        if (want.isEmpty()) return null;
        String alias = APP_ALIASES.getOrDefault(want, "");
        String best = null; int bestScore = 0;
        for (Map.Entry<String, String[]> e : apps.entrySet()) {
            String label = appKey(e.getValue()[0]), pkg = e.getValue()[1] == null ? "" : e.getValue()[1].toLowerCase(Locale.ROOT);
            int score = label.equals(want) || !alias.isEmpty() && label.equals(alias) ? 100 : label.startsWith(want) ? 80 : label.contains(want) ? 60
                : !alias.isEmpty() && (label.contains(alias) || pkg.contains(alias)) ? 55 : want.length() >= 3 && want.contains(label) && label.length() >= 2 ? 40 : 0;
            if (score == 0) continue;
            score = score * 100 - Math.min(99, label.length());
            if (score > bestScore) { bestScore = score; best = e.getKey(); }
        }
        return best;
    }
    private static String appKey(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·]", ""); }
}
