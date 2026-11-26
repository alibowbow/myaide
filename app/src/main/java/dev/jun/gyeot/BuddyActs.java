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
    static BuddyVoice.Outcome run(Context context, JSONObject act, String heard, boolean overlay) {
        String tool = act.optString("tool").strip().toLowerCase(Locale.ROOT);
        try {
            switch (tool) {
                case "open_app" -> {
                    String name = act.optString("app").strip();
                    LauncherActivityInfo info = findApp(context, name);
                    if (info == null) return new BuddyVoice.Outcome("'" + name + "' 앱은 이 휴대폰에서 못 찾았어.", false, null);
                    return started(context, open(context, info, overlay), info.getLabel() + " 열었어", true);
                }
                case "search" -> {
                    String q = act.optString("query").strip(); if (q.isEmpty()) return null;
                    Intent search = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (search.resolveActivity(context.getPackageManager()) == null)
                        search = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    return started(context, DeviceActionRunner.start(context, search, overlay), "", true);
                }
                case "volume" -> { return volume(context, act); }
                case "brightness" -> { return brightness(context, act, overlay); }
                default -> { /* the phone's own actions, below */ }
            }
            String name = switch (tool) {
                case "alarm" -> DeviceActions.ALARM; case "timer" -> DeviceActions.TIMER; case "navigate" -> DeviceActions.NAVIGATE;
                case "media" -> DeviceActions.MEDIA; case "torch" -> DeviceActions.TORCH; case "settings" -> DeviceActions.SETTINGS;
                case "show_alarms" -> DeviceActions.SHOW_ALARMS; case "rotation" -> DeviceActions.ROTATION; default -> "";
            };
            if (name.isEmpty()) return new BuddyVoice.Outcome("그건 아직 내가 못 해.", false, null);
            JSONObject args = new JSONObject(act.toString()); args.remove("tool");
            if (DeviceActions.ALARM.equals(name) || DeviceActions.TIMER.equals(name)) args.put("confirmed", true);
            DeviceActions.Action action = DeviceActions.parse(name, args, heard == null ? "" : heard, java.time.ZonedDateTime.now());
            JSONObject r = DeviceActionRunner.run(context, action, overlay);
            if ("needs_confirmation".equals(r.optString("outcome")))
                return new BuddyVoice.Outcome(action.readback + (DeviceActions.ALARM.equals(name) ? "에 알람 맞출까?" : " 타이머 시작할까?"), false, act);
            if (!r.optBoolean("ok")) return new BuddyVoice.Outcome(r.optString("say", "잘 안 됐어."), false, null);
            if (DeviceActions.ALARM.equals(name)) return new BuddyVoice.Outcome(action.readback + "에 알람 맞췄어!", false, null);
            if (DeviceActions.TIMER.equals(name)) return new BuddyVoice.Outcome(action.readback + " 타이머 시작!", false, null);
            // 준: "내 휴대폰 가로모드 세로모드 고정 바꾸는게 안돼"
            if (DeviceActions.ROTATION.equals(name)) { String m = r.optString("readback"); return new BuddyVoice.Outcome("auto".equals(m) ? "자동 회전 켰어!" : "landscape".equals(m) ? "가로로 고정했어!" : "세로로 고정했어!", false, null); }
            boolean leaves = DeviceActionRunner.started(r.optString("route")) && (DeviceActions.NAVIGATE.equals(name) || DeviceActions.SETTINGS.equals(name) || DeviceActions.SHOW_ALARMS.equals(name));
            return new BuddyVoice.Outcome("notification".equals(r.optString("outcome")) ? r.optString("say") : "", leaves, null);
        } catch (IllegalArgumentException unclear) { return new BuddyVoice.Outcome(unclear.getMessage(), false, null); }
        catch (Exception failed) { return new BuddyVoice.Outcome("잘 안 됐어. 한 번만 다시 말해 줘.", false, null); }
    }
    /** The media volume: up or down a few steps, a level (0–100), muted or back (the phone shows its volume bar). */
    private static BuddyVoice.Outcome volume(Context context, JSONObject act) {
        android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
        if (audio == null) return new BuddyVoice.Outcome("소리를 못 바꿨어.", false, null);
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
            return new BuddyVoice.Outcome("밝기를 바꾸려면 '시스템 설정 변경'을 허용해 줘. 허용 화면을 열었어.", false, null);
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
        } catch (RuntimeException refused) { return new BuddyVoice.Outcome("밝기를 못 바꿨어.", false, null); }
    }
    /** After a start: nothing to add when it opened, the way to open it when Android only let a notification through. */
    private static BuddyVoice.Outcome started(Context context, String route, String opened, boolean leaves) {
        if (DeviceActionRunner.started(route)) return new BuddyVoice.Outcome("", leaves, null);
        return new BuddyVoice.Outcome("locked".equals(route) ? "화면이 잠겨 있어서 못 열었어. 잠금을 풀고 다시 말해 줘." : "지금은 바로 열 수가 없어. 다른 앱 위에 표시 권한을 켜 주면 바로 열 수 있어.", false, null);
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
