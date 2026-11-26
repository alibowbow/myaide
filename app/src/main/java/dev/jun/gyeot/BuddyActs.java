package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.UserHandle;
import dev.jun.gyeot.core.DeviceActions;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * What 퍼레 does on the phone when asked (준: "퍼레는 폰조작이 안되네"), from the home screen or over another app (준: "퍼레
 * 대화모드일때는 다른 앱 위에 그려지게 해줘"): an app opened by its name, a web search, and the edge assistant's own phone
 * actions (DeviceActions: a route, an alarm, a timer, music, the torch, a settings panel, the screen's rotation), and the
 * volume and brightness, started the way Android allows from
 * where it is now (DeviceActionRunner.start: in front, the accessibility service, or the overlay). Any thread.
 */
final class BuddyActs {
    private BuddyActs() {}

    /** heard: what the user said (an alarm or a timer runs only after a yes); overlay: 퍼레's window is over another app. */
    /** What failed in this act (fail()), kept by run() in BuddyMisses with the words that asked for it. */
    private static final ThreadLocal<String[]> MISS = new ThreadLocal<>();
    /** A failed act's words, with the tap that clears the way ("" none, BuddyMisses). */
    private static BuddyVoice.Outcome fail(String say, String fix) { MISS.set(new String[]{say, fix == null ? "" : fix}); return new BuddyVoice.Outcome(say, false, null); }
    static BuddyVoice.Outcome run(Context context, JSONObject act, String heard, boolean overlay) {
        MISS.remove();
        BuddyVoice.Outcome o = runOnce(context, act, heard, overlay);
        String[] m = MISS.get(); MISS.remove();
        if (m != null) {
            String what = act.optString("tool") + " " + (act.optString("app") + " " + act.optString("destination") + " " + act.optString("query") + " " + act.optString("panel")).strip();
            BuddyMisses.add(context, heard, what.strip(), m[0], m[1]);
            ((GyeotApp) context.getApplicationContext()).store().phoneLog("  · 퍼레 · 못 함: " + what.strip() + " · " + m[0]);
        }
        return o;
    }
    private static BuddyVoice.Outcome runOnce(Context context, JSONObject act, String heard, boolean overlay) {
        String tool = act.optString("tool").strip().toLowerCase(Locale.ROOT);
        try {
            switch (tool) {
                case "open_app" -> {
                    String name = act.optString("app").strip();
                    LauncherActivityInfo info = findApp(context, name);
                    if (info == null) return fail("'" + name + "' 앱은 이 휴대폰에서 못 찾았어.", "store:" + name);
                    return started(context, open(context, info, overlay), info.getLabel() + " 열었어", true);
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
                case "volume" -> { return volume(context, act); }
                case "brightness" -> { return brightness(context, act, overlay); }
                default -> { /* the phone's own actions, below */ }
            }
            String name = switch (tool) {
                case "alarm" -> DeviceActions.ALARM; case "timer" -> DeviceActions.TIMER; case "navigate" -> DeviceActions.NAVIGATE;
                case "media" -> DeviceActions.MEDIA; case "torch" -> DeviceActions.TORCH; case "settings" -> DeviceActions.SETTINGS;
                case "show_alarms" -> DeviceActions.SHOW_ALARMS; case "rotation" -> DeviceActions.ROTATION; default -> "";
            };
            if (name.isEmpty()) return fail("그건 아직 내가 못 해.", "");
            JSONObject args = new JSONObject(act.toString()); args.remove("tool");
            if (DeviceActions.ALARM.equals(name) || DeviceActions.TIMER.equals(name)) args.put("confirmed", true);
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
            return started(context, route, "", true);
        }
        return fail("거기서 찾아 틀 수가 없어. 그 앱이 깔려 있는지 봐 줘.", act.optString("app").isBlank() ? "" : "store:" + act.optString("app"));
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
